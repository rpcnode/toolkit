package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/ali3/tron-toolkit/panel/store"
)

// One in-flight panel remove job per node id (accept → tip ACK → delete/error).
var panelRemoveOnce sync.Map // nodeID → struct{}

func (s *Server) handleWorkloadsAPI(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSuffix(r.URL.Path, "/")
	switch {
	case path == "/api/workloads" && r.Method == http.MethodGet:
		// Prefer views with collector-cached lifecycle (no live fan-out).
		views := s.workloads.ListViews()
		if len(views) > 0 {
			writeJSON(w, http.StatusOK, map[string]any{"ok": true, "items": views, "count": len(views), "source": "db"})
			return
		}
		items := s.workloads.List()
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "items": items, "count": len(items), "source": "db"})
	case path == "/api/workloads/plan" && r.Method == http.MethodPost:
		s.handleWorkloadPlan(w, r)
	case path == "/api/workloads/check-ports" && r.Method == http.MethodPost:
		s.handleWorkloadCheckPorts(w, r)
	case path == "/api/workloads/port-holder/kill" && r.Method == http.MethodPost:
		s.handleWorkloadPortHolderKill(w, r)
	case path == "/api/workloads/port-holder" && r.Method == http.MethodPost:
		s.handleWorkloadPortHolder(w, r)
	case path == "/api/workloads/host-disks" && r.Method == http.MethodGet:
		s.handleWorkloadHostDisks(w, r)
	case path == "/api/workloads/debug" && r.Method == http.MethodGet:
		s.handleWorkloadDebug(w, r)
	case path == "/api/workloads/provision" && r.Method == http.MethodPost:
		s.handleWorkloadProvision(w, r)
	case path == "/api/workloads/start" && r.Method == http.MethodPost:
		s.handleWorkloadStart(w, r)
	case path == "/api/workloads/status" && r.Method == http.MethodPost:
		s.handleWorkloadStatus(w, r)
	case path == "/api/workloads/remove" && r.Method == http.MethodPost:
		s.handleWorkloadRemove(w, r)
	case strings.HasSuffix(path, "/disk-layout") && strings.HasPrefix(path, "/api/workloads/"):
		s.handleWorkloadDiskLayout(w, r, path)
	case path == "/api/workloads" && r.Method == http.MethodPost:
		// Add node: tip plan → persist catalog ports → ready_to_install (no host install yet).
		var body WorkloadRef
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
			return
		}
		body.ServerID = strings.TrimSpace(body.ServerID)
		body.Network = strings.TrimSpace(body.Network)
		body.Env = strings.TrimSpace(body.Env)
		if body.ServerID == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "server_id_required"})
			return
		}
		if body.Env == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "env_required"})
			return
		}
		if body.Network == "" {
			body.Network = "tron"
		}
		srv, ok := s.registry.Get(body.ServerID)
		if !ok || srv.AgentURL == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "server_not_found"})
			return
		}
		// One node per server+network+env — any status (incl. removing / error / syncing).
		if existing, hit := s.nodeOnServer(body.ServerID, body.Network, body.Env); hit {
			net := strings.ToLower(body.Network)
			msg := fmt.Sprintf("%s/%s already exists on this server", net, body.Env)
			st := strings.ToLower(strings.TrimSpace(existing.Status))
			if st == "removing" {
				msg = fmt.Sprintf(
					"%s/%s remove still in progress on this server — open the node and retry Remove (tip resumes kill→units→wipe)",
					net, body.Env,
				)
			} else if st == "remove_error" {
				msg = fmt.Sprintf(
					"%s/%s remove failed on this server — open the node and retry Remove (tip resumes kill → units → wipe)",
					net, body.Env,
				)
			}
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "already_exists",
				"message":          msg,
				"occupied_node_id": existing.ID,
				"occupied_status":  existing.Status,
			})
			return
		}
		if other, hit := s.otherEnvOnServer(body.ServerID, body.Network, body.Env); hit {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "one_env_per_host",
				"message": fmt.Sprintf(
					"%s already has %s/%s on this server — only one environment per host",
					body.Network, other.Network, other.Env,
				),
				"occupied_env":     other.Env,
				"occupied_node_id": other.ID,
			})
			return
		}
		pub, agentPort, httpPort, p2p, planAgent, errMsg := s.tipPlanPorts(r.Context(), srv, body.Network, body.Env)
		if errMsg != "" {
			writeJSON(w, http.StatusBadGateway, map[string]any{
				"ok": false, "error": "ports_plan_failed",
				"message": errMsg,
			})
			return
		}
		if pub <= 0 || agentPort <= 0 {
			writeJSON(w, http.StatusBadGateway, map[string]any{
				"ok": false, "error": "ports_plan_failed",
				"message": "tip agent returned empty catalog ports",
			})
			return
		}
		body.PublicPort = pub
		body.AgentPort = agentPort
		body.NodeHTTPPort = httpPort
		body.P2PPort = p2p
		body.Status = "ready_to_install"
		item := s.workloads.Upsert(body)
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "item": item, "agent": planAgent,
			"message": "tip catalog ports saved — Install will check they are free",
		})
	case strings.HasPrefix(path, "/api/workloads/") && r.Method == http.MethodDelete:
		// Bare DELETE skips the host agent — refuse. Use POST /api/workloads/remove.
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{
			"ok": false, "error": "use_remove",
			"message": "POST /api/workloads/remove with {id, delete_files}. Panel deletes only after agent ACK.",
		})
	case strings.HasPrefix(path, "/api/workloads/") && r.Method == http.MethodGet:
		id := strings.TrimPrefix(path, "/api/workloads/")
		item, ok := s.workloads.Get(id)
		if !ok {
			writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "item": item})
	default:
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
	}
}

func normalizeRemoveMode(mode string, deleteFiles, force bool) (out string, wipe bool, panelOnly bool) {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "wipe", "full":
		return "wipe", true, false
	case "agents", "host":
		return "agents", false, false
	case "panel":
		return "panel", false, true
	default:
		if force {
			return "panel", deleteFiles, false
		}
		if deleteFiles {
			return "wipe", true, false
		}
		return "agents", false, false
	}
}

func (s *Server) handleWorkloadRemove(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ID          string `json:"id"`
		WorkloadID  string `json:"workload_id"`
		ServerID    string `json:"server_id"`
		Env         string `json:"env"`
		Mode        string `json:"mode"` // wipe | agents | panel
		DeleteFiles bool   `json:"delete_files"`
		Force       bool   `json:"force"` // legacy: drop row + best-effort tip wipe
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
		return
	}
	wlID := strings.TrimSpace(body.ID)
	if wlID == "" {
		wlID = strings.TrimSpace(body.WorkloadID)
	}
	wl, srv, errMsg := s.resolveWorkloadTarget(wlID, body.ServerID, body.Env)
	if errMsg != "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": errMsg})
		return
	}

	mode, deleteFiles, panelOnly := normalizeRemoveMode(body.Mode, body.DeleteFiles, body.Force)
	body.DeleteFiles = deleteFiles

	// Explicit panel-only: drop the SQLite row, do not touch the host.
	if panelOnly && !body.Force {
		deletedID := wl.ID
		_ = s.workloads.Delete(deletedID)
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "deleted": deletedID, "mode": mode,
			"delete_files": false, "panel_only": true, "status": "deleted",
			"tip_cleanup": "skipped",
			"message":     "Removed from panel — host node and files were not changed",
		})
		return
	}

	// Legacy force: drop row immediately so Add is unblocked, then
	// best-effort tip wipe in background (orphans used to block re-add).
	if body.Force {
		deletedID := wl.ID
		_ = s.workloads.Delete(deletedID)
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "deleted": deletedID, "delete_files": body.DeleteFiles,
			"panel_only": true, "status": "deleted",
			"tip_cleanup": "enqueued",
			"message":     "Removed from panel — best-effort tip wipe enqueued (force=true)",
		})
		go s.bestEffortTipRemove(wl, srv, body.DeleteFiles)
		return
	}

	retry := strings.EqualFold(wl.Status, "removing") || strings.EqualFold(wl.Status, "remove_error")

	// Accept immediately: UI shows removing; tip kill→units→wipe runs in a goroutine.
	// Stuck removing / remove_error → re-kick tip (leaf agent down is fine; tip owns remove).
	wl.Status = "removing"
	_ = s.workloads.Upsert(wl)
	s.markNodeStatusRemoving(wl.ID, body.DeleteFiles)

	msg := "Remove accepted — tip runs kill → teardown units → wipe (if requested) in background"
	if retry {
		msg = "Remove retried — tip resume kick (leaf agent may already be stopped)"
		panelRemoveOnce.Delete(wl.ID)
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "accepted": true, "status": "removing", "id": wl.ID,
		"mode":               mode,
		"delete_files":       body.DeleteFiles,
		"delete_files_async": true,
		"retried":            retry,
		"message":            msg,
	})

	go s.finishWorkloadRemoveAsync(wl, srv, body.DeleteFiles)
}

func (s *Server) markNodeStatusRemoving(nodeID string, deleteFiles bool) {
	if s.db == nil || strings.TrimSpace(nodeID) == "" {
		return
	}
	detail := "Stopping on host — row drops after agent ACK"
	if deleteFiles {
		detail = "Stopping on host — wipe continues after ACK"
	}
	now := time.Now().UTC()
	_ = s.db.UpsertNodeStatus(store.NodeStatus{
		NodeID:      nodeID,
		Phase:       "removing",
		Label:       "removing",
		Detail:      detail,
		Health:      "removing",
		CollectedAt: now,
		LastSeenAt:  now,
	})
}

func (s *Server) finishWorkloadRemoveAsync(wl WorkloadRef, srv NodeRef, deleteFiles bool) {
	id := strings.TrimSpace(wl.ID)
	if id == "" {
		return
	}
	if _, loaded := panelRemoveOnce.LoadOrStore(id, struct{}{}); loaded {
		return
	}
	defer panelRemoveOnce.Delete(id)

	var usedURL, agentErr string
	for attempt := 0; attempt < 3; attempt++ {
		usedURL, agentErr = s.callTipRemove(wl, srv, deleteFiles)
		if agentErr == "" {
			break
		}
		log.Printf("workloads remove async %s: tip attempt %d failed url=%s err=%s", id, attempt+1, usedURL, agentErr)
		time.Sleep(time.Duration(2+attempt*3) * time.Second)
	}

	// Re-read — user may have force-deleted meanwhile.
	if cur, ok := s.workloads.Get(id); !ok {
		return
	} else if !strings.EqualFold(cur.Status, "removing") {
		return
	}

	if agentErr != "" {
		wl.Status = "remove_error"
		_ = s.workloads.Upsert(wl)
		if s.db != nil {
			now := time.Now().UTC()
			_ = s.db.UpsertNodeStatus(store.NodeStatus{
				NodeID:      id,
				Phase:       "error",
				Label:       "remove error",
				Detail:      "Tip remove failed — retry Remove (tip resumes). " + agentErr,
				Health:      "error",
				Error:       "agent_remove_failed",
				CollectedAt: now,
				LastSeenAt:  now,
			})
		}
		log.Printf("workloads remove async %s: agent failed url=%s err=%s", id, usedURL, agentErr)
		return
	}

	_ = s.workloads.Delete(id)
	log.Printf("workloads remove async %s: agent ACK ok url=%s delete_files=%v", id, usedURL, deleteFiles)
}

// bestEffortTipRemove — after force/panel drop: still ask tip to stop+wipe so
// orphans do not block re-add. Panel row is already gone; ignore tip errors.
func (s *Server) bestEffortTipRemove(wl WorkloadRef, srv NodeRef, deleteFiles bool) {
	id := strings.TrimSpace(wl.ID)
	key := "force:" + id
	if id == "" {
		key = "force:" + strings.ToLower(wl.Network) + "/" + wl.Env + "/" + srv.ID
	}
	if _, loaded := panelRemoveOnce.LoadOrStore(key, struct{}{}); loaded {
		return
	}
	defer panelRemoveOnce.Delete(key)

	usedURL, agentErr := s.callTipRemove(wl, srv, deleteFiles)
	if agentErr != "" {
		log.Printf("workloads force tip cleanup %s: failed url=%s err=%s", id, usedURL, agentErr)
		return
	}
	log.Printf("workloads force tip cleanup %s: ACK ok url=%s delete_files=%v", id, usedURL, deleteFiles)
}

// callTipRemove — POST tip remove (never per-node agent_port). Returns used URL + error.
func (s *Server) callTipRemove(wl WorkloadRef, srv NodeRef, deleteFiles bool) (usedURL string, agentErr string) {
	network := wl.Network
	if network == "" {
		network = "tron"
	}
	payload, _ := json.Marshal(map[string]any{
		"env":          wl.Env,
		"network":      network,
		"delete_files": deleteFiles,
		"force":        false,
	})

	base := strings.TrimRight(strings.TrimSpace(srv.AgentURL), "/")
	urls := []string{}
	if base != "" {
		urls = append(urls,
			base+"/api/v1/networks/"+network+"/envs/"+wl.Env+"/remove",
			base+"/api/v1/nodes/remove",
		)
	}
	if len(urls) == 0 {
		return "", "no_agent_url"
	}

	// Tip phase-1 stop budget can be ~45s; keep margin. Leaf agent reachability irrelevant.
	client := s.client
	if client == nil {
		client = &http.Client{Timeout: 180 * time.Second}
	} else {
		client = &http.Client{Timeout: 180 * time.Second, Transport: client.Transport}
	}
	for _, url := range urls {
		req, err := http.NewRequestWithContext(context.Background(), http.MethodPost, url, bytes.NewReader(payload))
		if err != nil {
			agentErr = err.Error()
			continue
		}
		req.Header.Set("Content-Type", "application/json")
		if srv.AgentKey != "" {
			req.Header.Set("Authorization", "Bearer "+srv.AgentKey)
			req.Header.Set("X-Api-Token", srv.AgentKey)
		}
		resp, err := client.Do(req)
		if err != nil {
			agentErr = err.Error()
			continue
		}
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		_ = resp.Body.Close()
		agent := map[string]any{}
		_ = json.Unmarshal(raw, &agent)
		if resp.StatusCode == http.StatusNotFound {
			agentErr = "not_found"
			continue
		}
		if resp.StatusCode >= 300 || !truthy(agent["ok"]) {
			msg, _ := agent["message"].(string)
			if msg == "" {
				msg, _ = agent["error"].(string)
			}
			if msg == "" {
				msg = string(raw)
			}
			return url, msg
		}
		return url, ""
	}
	return usedURL, agentErr
}

// agentControlBase — URL for Node Agent API (NOT the public Go RPC port).
func agentControlBase(srv NodeRef, wl WorkloadRef) string {
	base := strings.TrimRight(strings.TrimSpace(srv.AgentURL), "/")
	if u := strings.TrimRight(strings.TrimSpace(wl.AgentURL), "/"); u != "" {
		base = u
	}
	if base == "" {
		return ""
	}
	// Always prefer dedicated agent_port for control APIs.
	if wl.AgentPort > 0 {
		return rewriteHostPort(base, wl.AgentPort)
	}
	return base
}

func (s *Server) handleWorkloadStart(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ServerID string `json:"server_id"`
		Workload string `json:"workload_id"`
		Env      string `json:"env"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
		return
	}
	wl, srv, errMsg := s.resolveWorkloadTarget(body.Workload, body.ServerID, body.Env)
	if errMsg != "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": errMsg})
		return
	}
	payload, _ := json.Marshal(map[string]any{"env": wl.Env, "network": wl.Network})
	// Prefer Server tip for start (same as remove/provision) — leaf agent_port may
	// not be listening yet right after provision; tip routes by network/env.
	base := strings.TrimRight(strings.TrimSpace(srv.AgentURL), "/")
	if base == "" {
		base = agentControlBase(srv, wl)
	}
	url := base + "/api/v1/nodes/start"
	if wl.Network != "" && wl.Env != "" {
		url = base + "/api/v1/networks/" + wl.Network + "/envs/" + wl.Env + "/start"
	}
	req, err := http.NewRequestWithContext(r.Context(), http.MethodPost, url, bytes.NewReader(payload))
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	req.Header.Set("Content-Type", "application/json")
	if srv.AgentKey != "" {
		req.Header.Set("Authorization", "Bearer "+srv.AgentKey)
		req.Header.Set("X-Api-Token", srv.AgentKey)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"ok": false, "error": "agent_unreachable", "message": err.Error(),
		})
		return
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	var agent map[string]any
	_ = json.Unmarshal(raw, &agent)
	if resp.StatusCode >= 300 || !truthy(agent["ok"]) {
		msg, _ := agent["message"].(string)
		if msg == "" {
			msg = string(raw)
		}
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"ok": false, "error": "start_failed", "message": msg, "agent": agent,
		})
		return
	}
	wl.Status = "starting"
	wl = s.workloads.Upsert(wl)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "item": wl, "agent": agent})
}

func (s *Server) handleWorkloadStatus(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ID     string `json:"id"`
		Status string `json:"status"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
		return
	}
	wl, ok := s.workloads.Get(strings.TrimSpace(body.ID))
	if !ok {
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
		return
	}
	if body.Status != "" {
		wl.Status = strings.TrimSpace(body.Status)
		wl = s.workloads.Upsert(wl)
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "item": wl})
}

func (s *Server) resolveWorkloadTarget(workloadID, serverID, env string) (WorkloadRef, NodeRef, string) {
	workloadID = strings.TrimSpace(workloadID)
	serverID = strings.TrimSpace(serverID)
	env = strings.TrimSpace(env)
	var wl WorkloadRef
	var ok bool
	if workloadID != "" {
		wl, ok = s.workloads.Get(workloadID)
	}
	if !ok && env != "" {
		wl, ok = s.workloadByEnv(env)
	}
	if !ok {
		return WorkloadRef{}, NodeRef{}, "workload_not_found"
	}
	if serverID == "" {
		serverID = wl.ServerID
	}
	srv, sok := s.registry.Get(serverID)
	if !sok || srv.AgentURL == "" {
		return WorkloadRef{}, NodeRef{}, "server_not_found"
	}
	return wl, srv, ""
}

func (s *Server) handleWorkloadPlan(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ServerID string `json:"server_id"`
		Network  string `json:"network"`
		Env      string `json:"env"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
		return
	}
	body.ServerID = strings.TrimSpace(body.ServerID)
	body.Env = strings.TrimSpace(body.Env)
	if body.Network == "" {
		body.Network = "tron"
	}
	if body.ServerID == "" || body.Env == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "server_id_and_env_required"})
		return
	}
	srv, ok := s.registry.Get(body.ServerID)
	if !ok || srv.AgentURL == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "server_not_found"})
		return
	}
	if other, hit := s.otherEnvOnServer(body.ServerID, body.Network, body.Env); hit {
		writeJSON(w, http.StatusConflict, map[string]any{
			"ok": false, "error": "one_env_per_host",
			"message": fmt.Sprintf(
				"%s already has %s/%s on this server — only one environment per host (Hyperliquid hl-node process singleton)",
				body.Network, other.Network, other.Env,
			),
			"network":            body.Network,
			"env":                body.Env,
			"occupied_env":       other.Env,
			"occupied_node_id":   other.ID,
			"occupied_node_name": other.Name,
			"capability":         "one_env_per_host",
		})
		return
	}
	payload, _ := json.Marshal(map[string]any{"network": body.Network, "env": body.Env})
	url := strings.TrimRight(srv.AgentURL, "/") + "/api/v1/nodes/plan" // server host agent (pre-provision)
	req, err := http.NewRequestWithContext(r.Context(), http.MethodPost, url, bytes.NewReader(payload))
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	req.Header.Set("Content-Type", "application/json")
	if srv.AgentKey != "" {
		req.Header.Set("Authorization", "Bearer "+srv.AgentKey)
		req.Header.Set("X-Api-Token", srv.AgentKey)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"ok": false, "error": "agent_unreachable", "message": err.Error(),
		})
		return
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	var agent map[string]any
	_ = json.Unmarshal(raw, &agent)
	if resp.StatusCode >= 300 || !truthy(agent["ok"]) {
		code, out := rewriteAgentCapabilityError(agent, "plan_failed", body.Network, body.Env, srv.AgentVersion, string(raw))
		writeJSON(w, code, out)
		return
	}
	pub := intFromAny(agent["public_port"])
	agentPort := intFromAny(agent["agent_port"])
	httpPort := intFromAny(agent["node_http_port"])
	p2p := intFromAny(agent["p2p_port"])
	out := map[string]any{
		"ok": true, "network": body.Network, "env": body.Env,
		"public_port": pub, "agent_port": agentPort,
		"node_http_port": httpPort, "p2p_port": p2p,
		"rpc_mode": "go_proxy",
		"external_ports": []map[string]any{
			{"port": pub, "proto": "tcp", "role": "rpc_proxy", "open_in_firewall": true,
				"desc": "Go RPC proxy (clients; sleep/maintenance on update)"},
			{"port": agentPort, "proto": "tcp", "role": "agent_api", "open_in_firewall": true,
				"desc": "Node Agent API (panel / control)"},
			{"port": p2p, "proto": "tcp", "role": "p2p", "open_in_firewall": p2p > 0,
				"desc": planP2PDesc(body.Network)},
		},
		"internal_ports": []map[string]any{
			{"port": httpPort, "proto": "tcp",
				"role":             planUpstreamRole(body.Network),
				"open_in_firewall": false,
				"desc":             planUpstreamDesc(body.Network)},
		},
		"next_after_provision": nextAfterProvision(body.Network, body.Env),
		"agent":                agent,
		"install_options":      agent["install_options"],
	}
	base := strings.TrimRight(srv.AgentURL, "/")
	if supports := s.probeAgentSupportsNetwork(s.client, base, srv.AgentKey, body.Network); supports {
		out["supported_networks"] = true
		// Host Server agent default profile may be tron — not a plan blocker.
	} else if agentNet := s.probeAgentNetwork(s.client, base, srv.AgentKey); agentNet != "" {
		out["agent_network"] = agentNet
		// Do not set network_mismatch on plan — provision may still work; UI must not lecture.
		if !strings.EqualFold(agentNet, body.Network) {
			out["host_profile"] = agentNet
		}
	}
	writeJSON(w, http.StatusOK, out)
}

// tipPlanPorts asks the tip (server) agent for fixed catalog ports for network/env.
func (s *Server) tipPlanPorts(ctx context.Context, srv NodeRef, network, env string) (pub, agentPort, httpPort, p2p int, agent map[string]any, errMsg string) {
	payload, _ := json.Marshal(map[string]any{"network": network, "env": env})
	url := strings.TrimRight(srv.AgentURL, "/") + "/api/v1/nodes/plan"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(payload))
	if err != nil {
		return 0, 0, 0, 0, nil, err.Error()
	}
	req.Header.Set("Content-Type", "application/json")
	if srv.AgentKey != "" {
		req.Header.Set("Authorization", "Bearer "+srv.AgentKey)
		req.Header.Set("X-Api-Token", srv.AgentKey)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		return 0, 0, 0, 0, nil, "tip agent unreachable: " + err.Error()
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	_ = json.Unmarshal(raw, &agent)
	if resp.StatusCode >= 300 || !truthy(agent["ok"]) {
		if m, _ := agent["message"].(string); m != "" {
			return 0, 0, 0, 0, agent, m
		}
		if e, _ := agent["error"].(string); e != "" {
			return 0, 0, 0, 0, agent, e
		}
		return 0, 0, 0, 0, agent, "tip plan failed: " + string(raw)
	}
	return intFromAny(agent["public_port"]), intFromAny(agent["agent_port"]),
		intFromAny(agent["node_http_port"]), intFromAny(agent["p2p_port"]), agent, ""
}

// handleWorkloadHostDisks — tip GET /api/v1/host/disks (Solana JBOD layout wizard).
func (s *Server) handleWorkloadHostDisks(w http.ResponseWriter, r *http.Request) {
	serverID := strings.TrimSpace(r.URL.Query().Get("server_id"))
	network := strings.TrimSpace(r.URL.Query().Get("network"))
	env := strings.TrimSpace(r.URL.Query().Get("env"))
	if serverID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "server_id_required"})
		return
	}
	srv, ok := s.registry.Get(serverID)
	if !ok || srv.AgentURL == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "server_not_found"})
		return
	}
	url := strings.TrimRight(srv.AgentURL, "/") + "/api/v1/host/disks"
	q := []string{}
	if network != "" {
		q = append(q, "network="+network)
	}
	if env != "" {
		q = append(q, "env="+env)
	}
	if len(q) > 0 {
		url += "?" + strings.Join(q, "&")
	}
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, url, nil)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	if srv.AgentKey != "" {
		req.Header.Set("Authorization", "Bearer "+srv.AgentKey)
		req.Header.Set("X-Api-Token", srv.AgentKey)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"ok": false, "error": "agent_unreachable", "message": err.Error(),
		})
		return
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	var agent map[string]any
	_ = json.Unmarshal(raw, &agent)
	if resp.StatusCode >= 300 {
		if agent == nil {
			agent = map[string]any{}
		}
		agent["ok"] = false
		if _, has := agent["error"]; !has {
			agent["error"] = "host_disks_failed"
		}
		writeJSON(w, resp.StatusCode, agent)
		return
	}
	if agent == nil {
		agent = map[string]any{"ok": true}
	}
	writeJSON(w, http.StatusOK, agent)
}

// handleWorkloadDebug — tip GET /api/v1/nodes/debug?network=&env= (read-only).
func (s *Server) handleWorkloadDebug(w http.ResponseWriter, r *http.Request) {
	serverID := strings.TrimSpace(r.URL.Query().Get("server_id"))
	network := strings.TrimSpace(r.URL.Query().Get("network"))
	env := strings.TrimSpace(r.URL.Query().Get("env"))
	if serverID == "" || network == "" || env == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"ok": false, "error": "server_network_env_required",
		})
		return
	}
	srv, ok := s.registry.Get(serverID)
	if !ok || srv.AgentURL == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "server_not_found"})
		return
	}
	q := url.Values{}
	q.Set("network", network)
	q.Set("env", env)
	tipURL := strings.TrimRight(srv.AgentURL, "/") + "/api/v1/nodes/debug?" + q.Encode()
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, tipURL, nil)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	if srv.AgentKey != "" {
		req.Header.Set("Authorization", "Bearer "+srv.AgentKey)
		req.Header.Set("X-Api-Token", srv.AgentKey)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"ok": false, "error": "agent_unreachable", "message": err.Error(),
		})
		return
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	var agent map[string]any
	_ = json.Unmarshal(raw, &agent)
	if resp.StatusCode >= 300 {
		if agent == nil {
			agent = map[string]any{}
		}
		agent["ok"] = false
		if _, has := agent["error"]; !has {
			agent["error"] = "debug_failed"
		}
		writeJSON(w, resp.StatusCode, agent)
		return
	}
	if agent == nil {
		agent = map[string]any{"ok": true}
	}
	writeJSON(w, http.StatusOK, agent)
}

func (s *Server) handleWorkloadCheckPorts(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ServerID string `json:"server_id"`
		Network  string `json:"network"`
		Env      string `json:"env"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
		return
	}
	body.ServerID = strings.TrimSpace(body.ServerID)
	body.Env = strings.TrimSpace(body.Env)
	if body.Network == "" {
		body.Network = "tron"
	}
	if body.ServerID == "" || body.Env == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "server_id_and_env_required"})
		return
	}
	srv, ok := s.registry.Get(body.ServerID)
	if !ok || srv.AgentURL == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "server_not_found"})
		return
	}
	unlock := lockWorkloadCheckPorts(body.ServerID)
	defer unlock()

	checkCtx, checkCancel := context.WithTimeout(r.Context(), 25*time.Second)
	defer checkCancel()

	status, agent, err := s.postTipJSON(checkCtx, srv, "/api/v1/nodes/check-ports", map[string]any{
		"network": body.Network, "env": body.Env,
	})
	if err != nil {
		errName := "agent_unreachable"
		if strings.Contains(err.Error(), "deadline exceeded") || strings.Contains(strings.ToLower(err.Error()), "timeout") {
			errName = "agent_timeout"
		}
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"ok": false, "error": errName, "message": err.Error(),
		})
		return
	}
	if agent == nil {
		agent = map[string]any{}
	}
	reachCtx, reachCancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer reachCancel()
	s.attachOutboundReach(reachCtx, srv, body.Network, body.Env, agent)
	if status >= 300 || !truthy(agent["ok"]) {
		code := status
		if code < 400 {
			code = http.StatusConflict
		}
		if _, has := agent["error"]; !has {
			agent["error"] = "port_busy"
		}
		agent["ok"] = false
		writeJSON(w, code, agent)
		return
	}
	writeJSON(w, http.StatusOK, agent)
}

func (s *Server) handleWorkloadPortHolder(w http.ResponseWriter, r *http.Request) {
	s.proxyWorkloadPortHolder(w, r, "/api/v1/nodes/port-holder", 15*time.Second)
}

func (s *Server) handleWorkloadPortHolderKill(w http.ResponseWriter, r *http.Request) {
	s.proxyWorkloadPortHolder(w, r, "/api/v1/nodes/port-holder/kill", 20*time.Second)
}

func (s *Server) proxyWorkloadPortHolder(w http.ResponseWriter, r *http.Request, tipPath string, timeout time.Duration) {
	var body map[string]any
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
		return
	}
	serverID := strings.TrimSpace(fmt.Sprint(body["server_id"]))
	if serverID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "server_id_required"})
		return
	}
	srv, ok := s.registry.Get(serverID)
	if !ok || srv.AgentURL == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "server_not_found"})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), timeout)
	defer cancel()
	status, agent, err := s.postTipJSON(ctx, srv, tipPath, body)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"ok": false, "error": "agent_unreachable", "message": err.Error(),
		})
		return
	}
	if agent == nil {
		agent = map[string]any{}
	}
	if status >= 300 || !truthy(agent["ok"]) {
		code := status
		if code < 400 {
			code = http.StatusConflict
		}
		agent["ok"] = false
		writeJSON(w, code, agent)
		return
	}
	writeJSON(w, http.StatusOK, agent)
}

func planP2PDesc(network string) string {
	switch strings.ToLower(strings.TrimSpace(network)) {
	case "bitcoin":
		return "bitcoind P2P"
	case "solana":
		return "Agave gossip/TVU/TPU (base of dynamic range)"
	case "ethereum":
		return "geth execution P2P"
	case "bsc":
		return "bsc-geth P2P"
	case "hyperliquid":
		return "Hyperliquid gossip 4001–4002"
	case "arb":
		return "none (Nitro has no P2P)"
	case "robinhood":
		return "none (Nitro has no P2P)"
	case "optimism":
		return "op-geth P2P + op-node P2P"
	case "base":
		return "base-reth-node P2P + base-consensus P2P"
	case "zcash":
		return "zebrad P2P"
	case "sui":
		return "sui-node P2P"
	case "xrpl":
		return "xrpld peer protocol"
	default:
		return "TRON P2P peer traffic"
	}
}

func planUpstreamRole(network string) string {
	switch strings.ToLower(strings.TrimSpace(network)) {
	case "bitcoin":
		return "bitcoind_rpc"
	case "solana":
		return "agave_rpc"
	case "ethereum":
		return "geth_rpc"
	case "bsc":
		return "bsc_geth_rpc"
	case "hyperliquid":
		return "hl_evm_rpc"
	case "arb":
		return "nitro_rpc"
	case "robinhood":
		return "robinhood_nitro_rpc"
	case "optimism":
		return "op_geth_rpc"
	case "base":
		return "base_reth_rpc"
	case "zcash":
		return "zebrad_rpc"
	case "sui":
		return "sui_json_rpc"
	case "xrpl":
		return "xrpld_rpc"
	default:
		return "fullnode_http"
	}
}

func planUpstreamDesc(network string) string {
	switch strings.ToLower(strings.TrimSpace(network)) {
	case "bitcoin":
		return "bitcoind JSON-RPC (loopback; via Go proxy)"
	case "solana":
		return "Agave JSON-RPC (loopback; via Go proxy)"
	case "ethereum":
		return "geth JSON-RPC (loopback; via Go proxy)"
	case "bsc":
		return "bsc-geth JSON-RPC (loopback; via Go proxy)"
	case "hyperliquid":
		return "hl-visor HyperEVM JSON-RPC /evm (loopback; via Go proxy)"
	case "arb":
		return "nitro JSON-RPC (loopback; via Go proxy)"
	case "robinhood":
		return "nitro JSON-RPC (loopback; via Go proxy)"
	case "optimism":
		return "op-geth JSON-RPC (loopback; via Go proxy)"
	case "base":
		return "base-reth-node JSON-RPC (loopback; via Go proxy)"
	case "zcash":
		return "zebrad JSON-RPC (loopback; via Go proxy)"
	case "sui":
		return "sui-node JSON-RPC (loopback; via Go proxy)"
	case "xrpl":
		return "xrpld JSON-RPC (loopback; via Go proxy)"
	default:
		return "upstream HTTP / RPC (loopback; only via Go proxy)"
	}
}

func nextAfterProvision(network, env string) []string {
	if strings.EqualFold(network, "bitcoin") {
		return []string{
			"Open external ports in cloud security group / ufw (public proxy + agent + P2P)",
			"bitcoind starts via agent pipeline (no TRON snapshot) — wait for IBD",
			"Register NetworkUpstream when initialblockdownload=false",
		}
	}
	if strings.EqualFold(network, "solana") {
		return []string{
			"Open external ports in cloud security group / ufw (public proxy + agent + gossip UDP range)",
			"Agave starts via agent pipeline (no TRON snapshot) — wait for catch-up / getHealth",
			"Register NetworkUpstream when getHealth=ok",
		}
	}
	if strings.EqualFold(network, "ethereum") {
		return []string{
			"Open external ports in cloud security group / ufw (public proxy + agent + geth P2P + CL gossip)",
			"Geth + Lighthouse start via agent pipeline (no TRON snapshot) — wait for EL/CL sync",
			"Register NetworkUpstream when eth_syncing=false and beacon is_syncing=false",
		}
	}
	if strings.EqualFold(network, "bsc") {
		return []string{
			"Open external ports in cloud security group / ufw (public proxy + agent + bsc-geth P2P)",
			"bsc-geth starts via agent pipeline (no TRON snapshot) — wait for full sync",
			"Register NetworkUpstream when eth_syncing=false (chain id 56/97)",
		}
	}
	if strings.EqualFold(network, "hyperliquid") {
		return []string{
			"Open gossip 4001–4002 + public Go RPC / agent ports",
			"hl-visor starts via agent pipeline — wait for HyperEVM RPC (chain id 999)",
			"Register NetworkUpstream when eth_chainId=0x3e7",
		}
	}
	if strings.EqualFold(network, "arb") {
		return []string{
			"Open public Go RPC + agent ports (no Nitro P2P)",
			"Ensure L1 ethereum-host RPC reachable; nitro --init.latest=pruned on first start",
			"Register NetworkUpstream when eth_syncing=false (chain id 42161)",
		}
	}
	if strings.EqualFold(network, "robinhood") {
		return []string{
			"Open public Go RPC + agent ports (no Nitro P2P)",
			"Ensure L1 ethereum-host RPC reachable; nitro pruned --init.url snapshot then catch-up",
			"Register NetworkUpstream when eth_syncing=false (chain id 4663/46630)",
		}
	}
	if strings.EqualFold(network, "xrpl") {
		return []string{
			"Open public Go RPC + agent + xrpld peer P2P (51235/51236)",
			"xrpld stock server starts via agent pipeline — wait for server_state=full",
			"Register NetworkUpstream when server_info reports full/proposing",
		}
	}
	if strings.EqualFold(network, "optimism") {
		return []string{
			"Open public Go RPC + agent + op-geth P2P 30333 + op-node P2P 9003",
			"Ensure L1 RPC + beacon from ethereum-host; wait for op-geth + op-node sync",
			"Register NetworkUpstream when eth_syncing=false (chain id 10)",
		}
	}
	if strings.EqualFold(network, "base") {
		return []string{
			"Open public Go RPC + agent + base-reth P2P 30353 + base-consensus P2P 9023",
			"Ensure L1 RPC + beacon from ethereum-host; wait for base-reth-node + base-consensus sync",
			"Register NetworkUpstream when eth_syncing=false (chain id 8453/84532)",
		}
	}
	return []string{
		"Open external ports in cloud security group / ufw",
		"Download snapshot (required before starting the node)",
		"Start " + env + " node via panel after snapshot ready",
	}
}

func (s *Server) handleWorkloadDiskLayout(w http.ResponseWriter, r *http.Request, path string) {
	rest := strings.TrimPrefix(path, "/api/workloads/")
	id := strings.TrimSuffix(rest, "/disk-layout")
	id = strings.Trim(id, "/")
	if id == "" || strings.Contains(id, "/") {
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
		return
	}
	wl, ok := s.workloads.Get(id)
	if !ok {
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
		return
	}
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "node_id": wl.ID, "network": wl.Network, "env": wl.Env,
			"disk_layout":     wl.DiskLayout,
			"install_options": wl.InstallOptions,
		})
	case http.MethodPut:
		var body struct {
			DiskLayout map[string]any `json:"disk_layout"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
			return
		}
		if body.DiskLayout == nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "disk_layout_required"})
			return
		}
		if err := s.workloads.SetDiskLayout(wl.ID, body.DiskLayout); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		updated, _ := s.workloads.Get(wl.ID)
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "node_id": wl.ID, "disk_layout": updated.DiskLayout, "item": updated,
		})
	default:
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "error": "method_not_allowed"})
	}
}

func (s *Server) handleWorkloadProvision(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ServerID     string         `json:"server_id"`
		Network      string         `json:"network"`
		Env          string         `json:"env"`
		Name         string         `json:"name"`
		PublicPort   int            `json:"public_port"`
		AgentPort    int            `json:"agent_port"`
		NodeHTTPPort int            `json:"node_http_port"`
		P2PPort      int            `json:"p2p_port"`
		LedgerDir    string         `json:"ledger_dir,omitempty"`
		AccountsDir  string         `json:"accounts_dir,omitempty"`
		SnapshotsDir string         `json:"snapshots_dir,omitempty"`
		DiskLayout   map[string]any `json:"disk_layout,omitempty"`
		XrplHistory     string            `json:"xrpl_history,omitempty"`
		InstallOptions  map[string]string `json:"install_options,omitempty"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
		return
	}
	body.ServerID = strings.TrimSpace(body.ServerID)
	body.Network = strings.TrimSpace(body.Network)
	body.Env = strings.TrimSpace(body.Env)
	if body.ServerID == "" || body.Env == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "server_id_and_env_required"})
		return
	}
	if body.Network == "" {
		body.Network = "tron"
	}
	srv, ok := s.registry.Get(body.ServerID)
	if !ok || srv.AgentURL == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "server_not_found"})
		return
	}
	if other, hit := s.otherEnvOnServer(body.ServerID, body.Network, body.Env); hit {
		writeJSON(w, http.StatusConflict, map[string]any{
			"ok": false, "error": "one_env_per_host",
			"message": fmt.Sprintf(
				"%s already has %s/%s on this server — only one environment per host (Hyperliquid hl-node process singleton)",
				body.Network, other.Network, other.Env,
			),
			"network":            body.Network,
			"env":                body.Env,
			"occupied_env":       other.Env,
			"occupied_node_id":   other.ID,
			"occupied_node_name": other.Name,
			"capability":         "one_env_per_host",
		})
		return
	}

	// Reuse panel-persisted layout on re-provision/retry when body omits disk_layout.
	prevWL, hasPrev := s.workloads.FindByServerNetworkEnv(body.ServerID, body.Network, body.Env)
	diskLayout := body.DiskLayout
	if diskLayout == nil && hasPrev {
		diskLayout = prevWL.DiskLayout
	}
	diskLayout = mergeProvisionDiskLayout(diskLayout, body.LedgerDir, body.AccountsDir, body.SnapshotsDir)
	ledgerDir := body.LedgerDir
	accountsDir := body.AccountsDir
	snapshotsDir := body.SnapshotsDir
	if diskLayout != nil {
		if ledgerDir == "" {
			ledgerDir = stringFromAny(diskLayout["ledger_dir"])
		}
		if accountsDir == "" {
			accountsDir = stringFromAny(diskLayout["accounts_dir"])
		}
		if snapshotsDir == "" {
			snapshotsDir = stringFromAny(diskLayout["snapshots_dir"])
		}
	}

	// Unified Server agent: always attempt provision on the Server agent URL.
	// Host default profile (tron) is fine — per-node bitcoin agent is created by provision.
	// If host CP is merged onto the bitcoin listen port (only :39390 up), servers.agent_url
	// already points there — do not invent a dead :39190.
	payloadMap := map[string]any{
		"network":        body.Network,
		"env":            body.Env,
		"name":           body.Name,
		"public_port":    body.PublicPort,
		"agent_port":     body.AgentPort,
		"node_http_port": body.NodeHTTPPort,
		"p2p_port":       body.P2PPort,
	}
	if ledgerDir != "" {
		payloadMap["ledger_dir"] = ledgerDir
	}
	if accountsDir != "" {
		payloadMap["accounts_dir"] = accountsDir
	}
	if snapshotsDir != "" {
		payloadMap["snapshots_dir"] = snapshotsDir
	}
	if diskLayout != nil {
		payloadMap["disk_layout"] = diskLayout
	}
	installOpts := map[string]string{}
	if len(body.InstallOptions) > 0 {
		for k, v := range body.InstallOptions {
			k = strings.ToLower(strings.TrimSpace(k))
			v = strings.ToLower(strings.TrimSpace(v))
			if k != "" && v != "" {
				installOpts[k] = v
			}
		}
	} else if hasPrev && len(prevWL.InstallOptions) > 0 {
		for k, v := range prevWL.InstallOptions {
			installOpts[k] = v
		}
	}
	if h := strings.TrimSpace(body.XrplHistory); h != "" {
		payloadMap["xrpl_history"] = h
		installOpts["xrpl_history"] = strings.ToLower(strings.TrimSpace(h))
	}
	var persistOpts map[string]string
	if len(installOpts) > 0 {
		persistOpts = installOpts
		payloadMap["install_options"] = installOpts
	}
	payload, _ := json.Marshal(payloadMap)
	url := strings.TrimRight(srv.AgentURL, "/") + "/api/v1/nodes/provision" // server host agent
	// Detached from browser cancel; longer than default 45s client (agent may write units).
	req, err := http.NewRequestWithContext(context.Background(), http.MethodPost, url, bytes.NewReader(payload))
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	req.Header.Set("Content-Type", "application/json")
	if srv.AgentKey != "" {
		req.Header.Set("Authorization", "Bearer "+srv.AgentKey)
		req.Header.Set("X-Api-Token", srv.AgentKey)
	}
	client := &http.Client{Timeout: 8 * time.Minute, Transport: s.client.Transport}
	resp, err := client.Do(req)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"ok": false, "error": "agent_unreachable", "message": err.Error(),
		})
		return
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	var agent map[string]any
	_ = json.Unmarshal(raw, &agent)
	if resp.StatusCode >= 300 || !truthy(agent["ok"]) {
		code, out := rewriteAgentCapabilityError(agent, "provision_failed", body.Network, body.Env, srv.AgentVersion, string(raw))
		writeJSON(w, code, out)
		return
	}

	pub := intFromAny(agent["public_port"])
	agentPort := intFromAny(agent["agent_port"])
	httpPort := intFromAny(agent["node_http_port"])
	p2p := intFromAny(agent["p2p_port"])
	agentURL, _ := agent["agent_url"].(string)
	if agentURL == "" && agentPort > 0 {
		agentURL = rewriteHostPort(srv.AgentURL, agentPort)
	} else if agentURL == "" && pub > 0 {
		agentURL = rewriteHostPort(srv.AgentURL, pub)
	}
	// Never rewrite Server Agent URL to a per-node agent_port — host stays the control plane.
	serverURLUpdated := false
	// Update in place — never invent a second node for the same server+network+env.
	// Install provision ACK → installing (ports were already known from tip plan at Add).
	status := "installing"
	updated := false
	nodeID := ""
	if hasPrev {
		updated = true
		nodeID = prevWL.ID
		// Keep later lifecycle helpers (starting/online/…) if provision is re-run.
		switch prevWL.Status {
		case "", "awaiting_ports", "ready_to_install", "ports_confirmed", "installing":
			status = "installing"
		default:
			status = prevWL.Status
		}
	}
	wl := s.workloads.Upsert(WorkloadRef{
		ID:           nodeID,
		ServerID:     body.ServerID,
		Name:         body.Name,
		Network:      body.Network,
		Env:          body.Env,
		PublicPort:   pub,
		AgentPort:    agentPort,
		NodeHTTPPort: httpPort,
		P2PPort:      p2p,
		AgentURL:     agentURL,
		Status:       status,
		DiskLayout:     diskLayout,
		InstallOptions: persistOpts,
	})
	// Persist confirmed layout for Node Config + re-provision (even if Upsert race).
	if diskLayout != nil && wl.ID != "" {
		_ = s.workloads.SetDiskLayout(wl.ID, diskLayout)
		if refreshed, ok := s.workloads.Get(wl.ID); ok {
			wl = refreshed
		}
	}
	if len(persistOpts) > 0 && wl.ID != "" {
		_ = s.workloads.SetInstallOptions(wl.ID, persistOpts)
		if refreshed, ok := s.workloads.Get(wl.ID); ok {
			wl = refreshed
		}
	}
	if wl.ID != "" {
		_ = s.db.StampNodeInstallStarted(wl.ID)
		if refreshed, ok := s.workloads.Get(wl.ID); ok {
			wl = refreshed
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "item": wl, "updated": updated, "server_agent_url_updated": serverURLUpdated,
		"server_agent_url": srv.AgentURL, "agent": agent,
		"disk_layout":     wl.DiskLayout,
		"install_options": wl.InstallOptions,
		"rpc_mode":        "go_proxy",
		"external_ports": []map[string]any{
			{"port": pub, "proto": "tcp", "role": "rpc_proxy", "open_in_firewall": true,
				"desc": "Go RPC proxy (clients; sleep/maintenance on update)"},
			{"port": agentPort, "proto": "tcp", "role": "agent_api", "open_in_firewall": true,
				"desc": "Node Agent API (panel / control)"},
			{"port": p2p, "proto": "tcp", "role": "p2p", "open_in_firewall": true,
				"desc": planP2PDesc(body.Network)},
		},
		"internal_ports": []map[string]any{
			{"port": httpPort, "proto": "tcp",
				"role":             planUpstreamRole(body.Network),
				"open_in_firewall": false,
				"desc":             planUpstreamDesc(body.Network)},
		},
		"next_steps": nextAfterProvision(body.Network, body.Env),
	})
}

// mergeProvisionDiskLayout folds flat Solana dirs into the persisted disk_layout document.
func mergeProvisionDiskLayout(layout map[string]any, ledger, accounts, snapshots string) map[string]any {
	if layout == nil && ledger == "" && accounts == "" && snapshots == "" {
		return nil
	}
	out := map[string]any{}
	for k, v := range layout {
		out[k] = v
	}
	if ledger != "" {
		out["ledger_dir"] = ledger
	}
	if accounts != "" {
		out["accounts_dir"] = accounts
	}
	if snapshots != "" {
		out["snapshots_dir"] = snapshots
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

func stringFromAny(v any) string {
	s, _ := v.(string)
	return strings.TrimSpace(s)
}

func truthy(v any) bool {
	switch t := v.(type) {
	case bool:
		return t
	case string:
		return t == "1" || strings.EqualFold(t, "true") || strings.EqualFold(t, "yes")
	default:
		return false
	}
}

func intFromAny(v any) int {
	switch t := v.(type) {
	case float64:
		return int(t)
	case int:
		return t
	case json.Number:
		i, _ := t.Int64()
		return int(i)
	case string:
		var n int
		_, _ = fmt.Sscanf(t, "%d", &n)
		return n
	default:
		return 0
	}
}

func rewriteHostPort(base string, port int) string {
	base = strings.TrimRight(strings.TrimSpace(base), "/")
	if i := strings.LastIndex(base, ":"); i > 7 {
		base = base[:i]
	}
	return fmt.Sprintf("%s:%d", base, port)
}

func (s *Server) handleIngestMetrics(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "error": "POST only"})
		return
	}
	token := extractBearer(r)
	if token == "" || !s.ingestTokenOK(token) {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"ok": false, "error": "unauthorized"})
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "read_failed"})
		return
	}
	var raw map[string]any
	if err := json.Unmarshal(body, &raw); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
		return
	}
	cpu := floatField(raw, "cpu_pct")
	loadPct := floatField(raw, "load_pct")
	m := ServerMetrics{
		ServerID:    strField(raw, "server_id"),
		HostID:      strField(raw, "host_id"),
		AgentURL:    strings.TrimRight(strField(raw, "agent_url"), "/"),
		CPUPct:      cpu,
		LoadPct:     loadPct,
		NCPU:        int(floatField(raw, "ncpu")),
		MemPct:      floatField(raw, "mem_pct"),
		MemUsedMB:   floatField(raw, "mem_used_mb"),
		MemTotalMB:  floatField(raw, "mem_total_mb"),
		DiskUsedPct: floatField(raw, "disk_used_pct"),
		DiskUsedGB:  floatField(raw, "disk_used_gb"),
		DiskTotalGB: floatField(raw, "disk_total_gb"),
		Load1:       floatField(raw, "load_1"),
		OS:          strField(raw, "os"),
		Arch:        strField(raw, "arch"),
	}
	if t := strField(raw, "collected_at"); t != "" {
		if ts, err := time.Parse(time.RFC3339, t); err == nil {
			m.CollectedAt = ts
		}
	}
	// Resolve server_id from agent_url / token match.
	if m.ServerID == "" {
		m.ServerID = s.registry.FindIDByAgentURLOrToken(m.AgentURL, token)
	}
	saved := s.metrics.Upsert(m)
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "server_id": saved.ServerID, "status": metricsStatus(saved),
	})
}

func extractBearer(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if strings.HasPrefix(strings.ToLower(h), "bearer ") {
		return strings.TrimSpace(h[7:])
	}
	if t := r.Header.Get("X-Api-Token"); t != "" {
		return strings.TrimSpace(t)
	}
	return ""
}

func (s *Server) ingestTokenOK(token string) bool {
	if tok := strings.TrimSpace(envOr("PANEL_INGEST_TOKEN", "")); tok != "" {
		if subtleConstantTimeEq(tok, token) {
			return true
		}
	}
	return s.registry.HasAgentKey(token)
}

func subtleConstantTimeEq(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	var v byte
	for i := 0; i < len(a); i++ {
		v |= a[i] ^ b[i]
	}
	return v == 0
}

func strField(m map[string]any, k string) string {
	v, _ := m[k].(string)
	return strings.TrimSpace(v)
}

func floatField(m map[string]any, k string) float64 {
	if m == nil {
		return 0
	}
	switch v := m[k].(type) {
	case float64:
		return v
	case float32:
		return float64(v)
	case int:
		return float64(v)
	case int64:
		return float64(v)
	case json.Number:
		f, _ := v.Float64()
		return f
	case string:
		f, _ := strconv.ParseFloat(strings.TrimSpace(v), 64)
		return f
	default:
		return 0
	}
}

// rewriteAgentCapabilityError — tip plan/provision rejected network/env (old agent).
// Preserve unsupported_* so UI can show "update agent" instead of a dead Confirm ports button.
func rewriteAgentCapabilityError(
	agent map[string]any,
	fallback string,
	network, env, registryAgentVer, raw string,
) (int, map[string]any) {
	errCode, _ := agent["error"].(string)
	msg, _ := agent["message"].(string)
	if msg == "" {
		msg = strings.TrimSpace(raw)
	}
	low := strings.ToLower(msg)
	// Old tip agents often return no_free_ports / "supported: …" — map to capability errors.
	switch {
	case errCode == "unsupported_network" || errCode == "unsupported_env":
		// keep
	case strings.Contains(low, "unsupported_network") || strings.HasPrefix(low, "supported:"):
		errCode = "unsupported_network"
	case strings.Contains(low, "unsupported_env") || strings.Contains(low, "no canonical ports for"):
		errCode = "unsupported_env"
	case errCode == "":
		errCode = fallback
	}
	agentVer, _ := agent["agent_version"].(string)
	if agentVer == "" {
		if v, ok := agent["version"].(string); ok {
			agentVer = v
		}
	}
	if agentVer == "" {
		agentVer = strings.TrimSpace(registryAgentVer)
	}
	out := map[string]any{
		"ok": false, "error": errCode, "message": msg, "agent": agent,
		"network": network, "env": env,
	}
	if errCode == "unsupported_network" || errCode == "unsupported_env" {
		out["hint"] = "update_agent"
	}
	if agentVer != "" {
		out["agent_version"] = agentVer
	}
	if v, ok := agent["supported_networks"]; ok {
		out["supported_networks"] = v
	}
	if v, ok := agent["supported_envs"]; ok {
		out["supported_envs"] = v
	}
	switch errCode {
	case "unsupported_network", "unsupported_env":
		needRewrite := msg == "" ||
			strings.HasPrefix(low, "supported:") ||
			strings.Contains(low, "no canonical ports") ||
			!strings.Contains(msg, "Update the host agent")
		if needRewrite {
			verNote := ""
			if agentVer != "" {
				verNote = " (v" + agentVer + ")"
			}
			out["message"] = fmt.Sprintf(
				"%s/%s is not supported by this agent%s. Update the host agent to the latest version.",
				strings.TrimSpace(network), strings.TrimSpace(env), verNote,
			)
		}
		return http.StatusBadRequest, out
	default:
		return http.StatusBadGateway, out
	}
}
