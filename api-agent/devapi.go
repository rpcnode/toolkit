package main

import (
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Developer API (/api/v1/*) — stable JSON for integrations & alerting.
// Auth: panel session/basic and/or agent key (AGENT_API_TOKEN / TRON_API_TOKEN).

func (s *Server) apiToken() string {
	return agentAPIToken()
}

func extractAPIToken(r *http.Request) string {
	if t := strings.TrimSpace(r.Header.Get("X-Api-Token")); t != "" {
		return t
	}
	auth := strings.TrimSpace(r.Header.Get("Authorization"))
	if len(auth) >= 7 && strings.EqualFold(auth[:7], "Bearer ") {
		return strings.TrimSpace(auth[7:])
	}
	return ""
}

func tokenMatch(got, want string) bool {
	if want == "" || got == "" {
		return false
	}
	if len(got) != len(want) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(want)) == 1
}

// handleInternalAuthToken — optional edge nginx auth_request target.
func (s *Server) handleInternalAuthToken(w http.ResponseWriter, r *http.Request) {
	want := s.apiToken()
	if want == "" {
		http.Error(w, "token auth disabled", http.StatusUnauthorized)
		return
	}
	got := extractAPIToken(r)
	if !tokenMatch(got, want) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	w.WriteHeader(http.StatusOK)
}

func (s *Server) requireDevAPI(w http.ResponseWriter, r *http.Request) bool {
	want := s.apiToken()
	if want == "" {
		return true // panel basic auth (middleware) is enough
	}
	got := extractAPIToken(r)
	if got == "" {
		// Already passed panel basic auth; token optional unless required.
		if agentAPITokenRequired() {
			writeJSON(w, http.StatusUnauthorized, map[string]any{
				"ok": false, "error": "api_token_required",
				"message": "set X-Api-Token or Authorization: Bearer (AGENT_API_TOKEN)",
			})
			return false
		}
		return true
	}
	if !tokenMatch(got, want) {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"ok": false, "error": "invalid_api_token"})
		return false
	}
	return true
}

func (s *Server) handleDevAPI(w http.ResponseWriter, r *http.Request) {
	if strings.HasPrefix(r.URL.Path, "/internal/") {
		s.handleInternalAuthToken(w, r)
		return
	}
	if !s.requireDevAPI(w, r) {
		return
	}
	path := strings.TrimSuffix(r.URL.Path, "/")
	switch {
	case path == "/api/v1" || path == "/api/v1/openapi.json":
		s.handleDevOpenAPI(w, r)
	case path == "/api/v1/node" || path == "/api/v1/status":
		s.handleDevNode(w, r)
	case path == "/api/v1/metrics":
		s.handleDevMetrics(w, r)
	case path == "/api/v1/updates":
		s.handleDevUpdates(w, r)
	case path == "/api/v1/client":
		s.proxySystemAgentMethod(w, r, "/v1/client", http.MethodGet, nil, 15*time.Second)
	case path == "/api/v1/client/release":
		s.proxySystemAgentMethod(w, r, "/v1/client/release", http.MethodGet, nil, 20*time.Second)
	case path == "/api/v1/client/check":
		s.proxySystemAgentMethod(w, r, "/v1/client/check", http.MethodPost, nil, 30*time.Second)
	case path == "/api/v1/client/update":
		s.proxySystemAgentMethod(w, r, "/v1/client/update", http.MethodPost, nil, 30*time.Second)
	case path == "/api/v1/node/restart":
		s.proxySystemAgentMethod(w, r, "/v1/node/restart", http.MethodPost, nil, 60*time.Second)
	case path == "/api/v1/node/stop":
		s.proxySystemAgentMethod(w, r, "/v1/node/stop", http.MethodPost, nil, 60*time.Second)
	case path == "/api/v1/node/config":
		s.handleDevNodeConfig(w, r)
	case path == "/api/v1/events":
		s.proxySystemAgentMethod(w, r, "/v1/events", http.MethodGet, nil, 10*time.Second)
	case path == "/api/v1/webhooks":
		s.handleDevWebhooks(w, r)
	case path == "/api/v1/nodes" || strings.HasPrefix(path, "/api/v1/nodes/") ||
		strings.HasPrefix(path, "/api/v1/networks/"):
		s.handleNodesV1(w, r)
	case path == "/api/v1/host/disks":
		s.handleHostDisks(w, r)
	case path == "/api/v1/agent" || strings.HasPrefix(path, "/api/v1/agent/"):
		s.handleAgentV1(w, r)
	default:
		writeJSON(w, http.StatusNotFound, map[string]any{
			"ok": false, "error": "not_found",
			"message": "see GET /api/v1 for endpoint list",
		})
	}
}

func (s *Server) handleDevOpenAPI(w http.ResponseWriter, r *http.Request) {
	base := publicBaseFromRequest(r, s.cfg.PublicBase)
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":      true,
		"name":    "RpcNode toolkit developer API",
		"version": "1",
		"docs":    base + "/api/v1",
		"auth": map[string]any{
			"panel_session":      "POST /api/auth/login → rpcnode_session cookie",
			"panel_basic":        "htpasswd basic auth (legacy curl)",
			"api_token":          "agent key: X-Api-Token or Authorization: Bearer (AGENT_API_TOKEN)",
			"agent_key":          "AGENT_API_TOKEN for machine agents / panel",
			"token_set":          s.apiToken() != "",
			"panel_port":         s.cfg.PanelPort,
			"rpc_port":           s.cfg.RPCPort,
			"agent_download_url": defaultAgentDownloadURL(),
		},
		"endpoints": []map[string]any{
			{"method": "GET", "path": "/api/v1/node", "desc": "Node health, sync, versions, services, instance"},
			{"method": "GET", "path": "/api/v1/metrics", "desc": "CPU/mem/load/RPS snapshot (+ short history)"},
			{"method": "GET", "path": "/api/v1/updates", "desc": "Toolkit agent + chain client update availability"},
			{"method": "GET", "path": "/api/v1/client", "desc": "Chain client_version + client_update (local/latest/phase)"},
			{"method": "GET", "path": "/api/v1/client/release", "desc": "Native client catalog: version + artifact_url (tron=GitHub GreatVoyage)"},
			{"method": "POST", "path": "/api/v1/client/check", "desc": "Refresh latest (native catalog; CDN override if artifact_url set)"},
			{"method": "POST", "path": "/api/v1/client/update", "desc": "Apply client update (node must already be Stopped; replace artifact; Restart to start)", "body": true},
			{"method": "POST", "path": "/api/v1/node/restart", "desc": "Soft-restart fullnode (Go RPC sleep → systemctl stop→start / ExecStop → wake)"},
			{"method": "POST", "path": "/api/v1/node/stop", "desc": "Soft-stop fullnode (Go RPC sleep → CLI/RPC then systemctl stop; stays down until Restart)"},
			{"method": "GET", "path": "/api/v1/node/config", "desc": "Leaf chain config documents + field schema (per network)"},
			{"method": "PUT", "path": "/api/v1/node/config", "desc": "Save config (confirm=true) then soft stop→start", "body": true},
			{"method": "GET", "path": "/api/v1/events?limit=50", "desc": "Recent notification events (newest first)"},
			{"method": "GET", "path": "/api/v1/webhooks", "desc": "Configured outbound webhook URLs"},
			{"method": "PUT", "path": "/api/v1/webhooks", "desc": "Replace webhook URL list {\"urls\":[...]}", "body": true},
			{"method": "POST", "path": "/api/v1/webhooks", "desc": "Append one URL {\"url\":\"https://...\"}", "body": true},
			{"method": "GET", "path": "/api/v1/host/disks", "desc": "Host block devices + mounts (lsblk/findmnt); ?network=solana adds recommended JBOD layout"},
			{"method": "GET", "path": "/api/v1/nodes", "desc": "Local env instances present on this host"},
			{"method": "POST", "path": "/api/v1/nodes/plan", "desc": "Return tip catalog ports for network/env (fixed, no remap)", "body": true},
			{"method": "POST", "path": "/api/v1/nodes/check-ports", "desc": "Check catalog ports free/reclaimable before Install", "body": true},
			{"method": "POST", "path": "/api/v1/nodes/port-holder", "desc": "Who is LISTEN on a catalog port (pid/comm/cmdline/unit)", "body": true},
			{"method": "POST", "path": "/api/v1/nodes/port-holder/kill", "desc": "Kill foreign LISTEN on a catalog port (confirm=true). Never tip/self/sshd.", "body": true},
			{"method": "POST", "path": "/api/v1/nodes/provision", "desc": "Create env dirs + systemd api-agent for env", "body": true},
			{"method": "POST", "path": "/api/v1/nodes/start", "desc": "Start node unit after install/snapshot ready", "body": true},
			{"method": "POST", "path": "/api/v1/nodes/remove", "desc": "Remove order: (1) stop node+Go RPC proxy (2) async wipe files (3) remove per-node agents. Tip host Server never stopped. ACK after phase 1.", "body": true},
			{"method": "POST", "path": "/api/v1/networks/{network}/envs/{env}/remove", "desc": "Network-scoped remove {delete_files}; phase-1 stop ACK then async wipe; tip untouched", "body": true},
			{"method": "POST", "path": "/api/v1/networks/{network}/envs/{env}/start", "desc": "Network-scoped start", "body": true},
			{"method": "POST", "path": "/api/v1/networks/{network}/envs/{env}/provision", "desc": "Network-scoped provision", "body": true},
			{"method": "GET", "path": "/api/v1/networks/{network}/envs", "desc": "List local envs on this host"},
			{"method": "GET", "path": "/api/v1/agent", "desc": "Agent version + update channel"},
			{"method": "GET", "path": "/api/v1/agent/logs", "desc": "Tail tip/leaf/watchdog agent logs (per-unit streams; ?lines=&unit=)"},
			{"method": "POST", "path": "/api/v1/agent/check", "desc": "Compare local vs CDN TOOLKIT_VERSION"},
			{"method": "POST", "path": "/api/v1/agent/update", "desc": "Download agent binaries from CDN, ensure watchdog unit, restart units", "body": true},
			{"method": "POST", "path": "/api/v1/agent/restart", "desc": "Restart api-agent + system-agent systemd units"},
			{"method": "GET", "path": "/api/host", "desc": "Detected host LAN IPs for RPCNODE_PUBLIC_BASE"},
			{"method": "GET", "path": "/api/public-base", "desc": "Current RPC/panel public URLs"},
			{"method": "POST", "path": "/api/public-base", "desc": "Apply RPCNODE_PUBLIC_BASE from {ip|url}", "body": true},
		},
		"legacy_aliases": []string{"/api/status.json", "/api/metrics.json"},
		"webhook_events": []string{
			"node.down", "node.up", "disk.low", "disk.ok",
			"maintenance.on", "maintenance.off",
			"snapshot.failed", "snapshot.running",
			"toolkit.update_available", "node.update_available",
		},
	})
}

func (s *Server) handleDevNode(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	st := s.buildStatus(publicBaseFromRequest(r, s.cfg.PublicBase))
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":          true,
		"api":         "v1",
		"env":         st["env"],
		"network":     st["network"],
		"health":      st["health"],
		"ui_phase":    st["ui_phase"],
		"node_status": st["node_status"],
		"lifecycle":   st["lifecycle"],
		"degraded":    st["degraded"],
		"updated_at":  st["updated_at"],
		"served_at":   st["served_at"],
		"instance":    st["instance"],
		"services":    st["services"],
		"rpc":         st["rpc"],
		// Sync progress for panel Sync status + /nodes cards (verification_pct).
		"sync":            st["sync"],
		"logs":            st["logs"],
		"capabilities":    st["capabilities"],
		"supported_steps": st["supported_steps"],
		// Toolkit agent vs fullnode client (Agave / geth / bitcoind / …).
		"version":         st["version"],
		"agent_version":   st["agent_version"],
		"client_version":  st["client_version"],
		"client_update":   st["client_update"],
		"node_restart":    st["node_restart"],
		"disk":            st["disk"],
		"disk_gate":       st["disk_gate"],
		"agent":           st["agent"],
		"setup":           st["setup"],
		"setup_steps":     st["setup_steps"],
		"snapshot": map[string]any{
			"ready":        dig(st, "snapshot", "ready"),
			"phase":        dig(st, "snapshot", "phase"),
			"pct":          dig(st, "snapshot", "pct"),
			"wget_running": dig(st, "snapshot", "wget_running"),
			"failed":       dig(st, "snapshot", "failed"),
			"detail":       dig(st, "snapshot", "detail"),
			"error":        dig(st, "snapshot", "error"),
		},
		"maintenance": st["maintenance"],
		"connect":     st["connect"],
		"checks":      st["checks"],
		"paths":       st["paths"],
	})
}

func (s *Server) handleDevMetrics(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	// Reuse existing metrics builder shape.
	s.handleMetrics(w, r)
}

func (s *Server) handleDevUpdates(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	st := s.buildStatus(publicBaseFromRequest(r, s.cfg.PublicBase))
	tu, _ := st["toolkit_update"].(map[string]any)
	if tu == nil {
		tu = map[string]any{}
	}
	upd, _ := st["updater"].(map[string]any)
	if upd == nil {
		upd = map[string]any{}
	}
	jarAvail := truthyAny(upd["update_available"]) || truthyAny(upd["available"])
	tkAvail := truthyAny(tu["update_available"])
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":              true,
		"api":             "v1",
		"needs_attention": tkAvail || jarAvail,
		"toolkit": map[string]any{
			"local_version":    tu["local_version"],
			"remote_version":   tu["remote_version"],
			"update_available": tkAvail,
			"status":           tu["status"],
			"message":          tu["message"],
			"apply_ready":      tu["apply_ready"],
			"apply_mode":       tu["apply_mode"],
			"channel":          tu["channel"],
			"auto":             tu["auto"],
			"last_check_at":    tu["last_check_at"],
			"last_apply_at":    tu["last_apply_at"],
		},
		"node_jar": map[string]any{
			"local_version":    firstStr(upd["local_version"], digMap(st, "version")),
			"remote_version":   firstStr(upd["remote_version"], upd["latest"]),
			"update_available": jarAvail,
			"raw":              upd,
		},
	})
}

func (s *Server) handleDevWebhooks(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.proxySystemAgentMethod(w, r, "/v1/webhooks", http.MethodGet, nil, 10*time.Second)
	case http.MethodPut, http.MethodPost:
		body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		s.proxySystemAgentMethod(w, r, "/v1/webhooks", r.Method, strings.NewReader(string(body)), 10*time.Second)
	default:
		http.Error(w, "GET|PUT|POST", http.StatusMethodNotAllowed)
	}
}

func (s *Server) handleDevNodeConfig(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.proxySystemAgentMethod(w, r, "/v1/node/config", http.MethodGet, nil, 20*time.Second)
	case http.MethodPut:
		body, err := io.ReadAll(io.LimitReader(r.Body, 4<<20))
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		s.proxySystemAgentMethod(w, r, "/v1/node/config", http.MethodPut, strings.NewReader(string(body)), 120*time.Second)
	default:
		http.Error(w, "GET|PUT", http.StatusMethodNotAllowed)
	}
}

func (s *Server) proxySystemAgentMethod(w http.ResponseWriter, r *http.Request, path, method string, body io.Reader, timeout time.Duration) {
	url := s.cfg.SystemAgentURL + path
	if r.URL.RawQuery != "" && method == http.MethodGet {
		url += "?" + r.URL.RawQuery
	}
	if timeout <= 0 {
		timeout = 15 * time.Second
	}
	ctx := r.Context()
	req, err := http.NewRequestWithContext(ctx, method, url, body)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	client := &http.Client{Timeout: timeout}
	resp, err := client.Do(req)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"ok": false, "error": "system-agent unreachable: " + err.Error(),
		})
		return
	}
	defer resp.Body.Close()
	payload, _ := io.ReadAll(resp.Body)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(resp.StatusCode)
	_, _ = w.Write(payload)
}

func dig(m map[string]any, keys ...string) any {
	var cur any = m
	for _, k := range keys {
		mm, ok := cur.(map[string]any)
		if !ok {
			return nil
		}
		cur = mm[k]
	}
	return cur
}

func digMap(m map[string]any, key string) any {
	return m[key]
}

func truthyAny(v any) bool {
	switch t := v.(type) {
	case bool:
		return t
	case string:
		return t == "true" || t == "1" || t == "yes"
	case float64:
		return t != 0
	default:
		return false
	}
}

func firstStr(vals ...any) any {
	for _, v := range vals {
		if v == nil {
			continue
		}
		switch t := v.(type) {
		case string:
			if t != "" {
				return t
			}
		case map[string]any:
			if s, ok := t["code"].(string); ok && s != "" {
				return s
			}
			if s, ok := t["version"].(string); ok && s != "" {
				return s
			}
			b, _ := json.Marshal(t)
			if len(b) > 2 {
				return json.RawMessage(b)
			}
		default:
			return fmt.Sprint(v)
		}
	}
	return nil
}
