package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

func storeNormalizeOS(v string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "linux":
		return "linux"
	case "darwin", "macos", "osx", "mac":
		return "darwin"
	default:
		return strings.ToLower(strings.TrimSpace(v))
	}
}

func storeNormalizeArch(v string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "x86_64", "amd64", "x64":
		return "amd64"
	case "aarch64", "arm64", "armv8", "armv8l":
		return "arm64"
	default:
		return strings.ToLower(strings.TrimSpace(v))
	}
}

// probeAgentHost fetches /api/host from the agent and prefers live OS/arch over form defaults.
// Also reads installed agent_version and agent-reported network from status.json.
// Returns agentNetwork (may be empty) and a short error when unreachable / auth fails.
func (s *Server) probeAgentHost(n *NodeRef) (agentNetwork string, err error) {
	if n == nil || n.AgentURL == "" {
		return "", fmt.Errorf("agent_url_required")
	}
	if n.AgentKey == "" {
		return "", fmt.Errorf("agent_key_required")
	}

	base := strings.TrimRight(n.AgentURL, "/")
	client := s.client
	if client == nil {
		client = &http.Client{Timeout: 8 * time.Second}
	}

	req, err := http.NewRequest(http.MethodGet, base+"/api/host", nil)
	if err != nil {
		return "", fmt.Errorf("bad agent url: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+n.AgentKey)
	req.Header.Set("X-Api-Token", n.AgentKey)

	resp, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("unreachable: %v", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", fmt.Errorf("read failed: %v", err)
	}
	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		return "", fmt.Errorf("auth failed (HTTP %d) — check agent key", resp.StatusCode)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		msg := strings.TrimSpace(string(body))
		if len(msg) > 180 {
			msg = msg[:180] + "…"
		}
		if msg != "" {
			return "", fmt.Errorf("agent HTTP %d: %s", resp.StatusCode, msg)
		}
		return "", fmt.Errorf("agent HTTP %d", resp.StatusCode)
	}

	var doc map[string]any
	if err := json.Unmarshal(body, &doc); err != nil {
		return "", fmt.Errorf("invalid host json")
	}
	host, _ := doc["host"].(map[string]any)
	if host == nil {
		host = doc
	}

	osVal, _ := host["os"].(string)
	archVal, _ := host["arch"].(string)
	if osVal != "" {
		n.OS = storeNormalizeOS(osVal)
	}
	if archVal != "" {
		n.Arch = storeNormalizeArch(archVal)
	}
	if n.OS != "" && n.Arch != "" {
		n.OSPretty = n.OS + "/" + n.Arch
	} else if osVal != "" || archVal != "" {
		n.OSPretty = strings.TrimSpace(osVal + " " + archVal)
	}

	if ver := s.probeAgentVersion(client, base, n.AgentKey); ver != "" {
		n.AgentVersion = ver
	}

	agentNetwork = s.probeAgentNetwork(client, base, n.AgentKey)
	return agentNetwork, nil
}

// probeAgentNetwork prefers /healthz identity (network / supported_networks / capabilities)
// over status.json instance.network which often stays "tron" after bitcoin re-label.
func (s *Server) probeAgentNetwork(client *http.Client, base, key string) string {
	if client == nil {
		client = &http.Client{Timeout: 5 * time.Second}
	}
	for _, path := range []string{"/healthz", "/", "/api/v1/agent", "/api/status.json", "/status.json"} {
		doc, err := s.getAgentJSON(client, base+path, key)
		if err != nil {
			continue
		}
		if n := agentReportedNetwork(doc); n != "" {
			return n
		}
	}
	return ""
}

// probeAgentSupportsNetwork — true when /healthz (or status) lists want in supported_networks.
func (s *Server) probeAgentSupportsNetwork(client *http.Client, base, key, want string) bool {
	want = strings.ToLower(strings.TrimSpace(want))
	if want == "" {
		return false
	}
	if client == nil {
		client = &http.Client{Timeout: 5 * time.Second}
	}
	for _, path := range []string{"/healthz", "/", "/api/status.json", "/status.json"} {
		doc, err := s.getAgentJSON(client, base+path, key)
		if err != nil {
			continue
		}
		if agentSupportsNetwork(doc, want) {
			return true
		}
	}
	return false
}

func (s *Server) probeAgentVersion(client *http.Client, base, key string) string {
	if client == nil {
		client = &http.Client{Timeout: 5 * time.Second}
	}
	for _, path := range []string{"/healthz", "/api/v1/agent", "/"} {
		doc, err := s.getAgentJSON(client, base+path, key)
		if err != nil {
			continue
		}
		for _, k := range []string{"agent_version", "version", "local_version"} {
			if v, ok := doc[k].(string); ok {
				v = strings.TrimSpace(v)
				if v != "" && !strings.EqualFold(v, "unknown") {
					return v
				}
			}
		}
	}
	return ""
}

func (s *Server) handleNodesAPI(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSuffix(r.URL.Path, "/")
	switch {
	case path == "/api/nodes" && r.Method == http.MethodGet:
		items := s.registry.List()
		// List is SQLite + collector metrics cache only. ❌ Never dial tip here
		// (Refresh used to sequential-probe every host → /nodes loader stuck).
		// Live agent is node detail / collector, not Servers or Nodes list.
		latest := s.cdnToolkitVersion()
		enriched := make([]map[string]any, 0, len(items))
		for _, n := range items {
			full, _ := s.registry.Get(n.ID)
			m, ok := s.metrics.Get(n.ID, n.AgentURL)
			st := "unknown"
			if ok {
				st = metricsStatus(m)
			}
			nodesCount := 0
			if s.workloads != nil {
				nodesCount = s.workloads.CountByServerID(n.ID)
			}
			agentVer := strings.TrimSpace(n.AgentVersion)
			if agentVer == "" {
				agentVer = strings.TrimSpace(full.AgentVersion)
			}
			updateAvail := agentVersionOutdated(agentVer, latest)
			row := map[string]any{
				"id": n.ID, "name": n.Name, "env": n.Env, "network": n.Network,
				"agent_url": n.AgentURL, "os": n.OS, "arch": n.Arch, "os_pretty": n.OSPretty,
				"agent_version": agentVer, "latest_agent_version": latest,
				"agent_update_available": updateAvail,
				"created_at":             n.CreatedAt, "updated_at": n.UpdatedAt,
				"nodes_count": nodesCount,
				"can_delete":  nodesCount == 0,
			}
			if ok {
				row["metrics"] = m
				row["metrics_status"] = st
				row["metrics_stale"] = st != "online"
				if n.OSPretty == "" && (m.OS != "" || m.Arch != "") {
					row["os"] = m.OS
					row["arch"] = m.Arch
					row["os_pretty"] = strings.TrimSpace(m.OS + "/" + m.Arch)
				}
			} else {
				row["metrics"] = nil
				row["metrics_status"] = "unknown"
				row["metrics_stale"] = true
			}
			enriched = append(enriched, row)
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "items": enriched, "count": len(enriched),
			"latest_agent_version": latest,
		})
	case path == "/api/nodes/probe" && r.Method == http.MethodPost:
		var body NodeRef
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
			return
		}
		body.AgentURL = strings.TrimRight(strings.TrimSpace(body.AgentURL), "/")
		if port := agentURLPort(body.AgentURL); isLeafAgentPort(port) {
			writeJSON(w, http.StatusBadRequest, map[string]any{
				"ok": false, "error": "tip_url_required",
				"message": fmt.Sprintf(
					"Agent URL port :%d is a per-node leaf Agent API — Servers must use the host tip URL from /etc/rpcnode/agent.port (tip listen), never leaf :39190/:39390/…",
					port,
				),
			})
			return
		}
		agentNet, err := s.probeAgentHost(&body)
		if err != nil {
			writeJSON(w, http.StatusBadGateway, map[string]any{
				"ok": false, "error": "probe_failed", "message": err.Error(),
			})
			return
		}
		resp := map[string]any{
			"ok":            true,
			"agent_url":     body.AgentURL,
			"os":            body.OS,
			"arch":          body.Arch,
			"os_pretty":     body.OSPretty,
			"agent_version": body.AgentVersion,
		}
		if agentNet != "" {
			resp["agent_network"] = agentNet
			resp["network"] = agentNet
		}
		if want := strings.TrimSpace(body.Network); want != "" && agentNet != "" && !strings.EqualFold(want, agentNet) {
			// Host Server agents often default instance.network=tron while supporting bitcoin.
			if s.probeAgentSupportsNetwork(nil, body.AgentURL, body.AgentKey, want) {
				resp["note"] = "Host agent default profile is " + agentNet +
					"; supported_networks includes " + want
			} else {
				resp["warning"] = networkMismatchMessage(want, agentNet)
				resp["network_mismatch"] = true
			}
		}
		writeJSON(w, http.StatusOK, resp)
	case path == "/api/nodes" && r.Method == http.MethodPost:
		var body NodeRef
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
			return
		}
		if body.AgentURL == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "agent_url_required"})
			return
		}
		if port := agentURLPort(body.AgentURL); isLeafAgentPort(port) {
			writeJSON(w, http.StatusBadRequest, map[string]any{
				"ok": false, "error": "tip_url_required",
				"message": fmt.Sprintf(
					"Agent URL port :%d is a per-node leaf Agent API — Servers must use the host tip URL from /etc/rpcnode/agent.port (tip listen), never leaf :39190/:39390/…",
					port,
				),
			})
			return
		}
		// Edit existing server: reuse stored agent key / name when omitted.
		if body.ID != "" {
			if prev, ok := s.registry.Get(body.ID); ok {
				if body.AgentKey == "" {
					body.AgentKey = prev.AgentKey
				}
				if strings.TrimSpace(body.Name) == "" {
					body.Name = prev.Name
				}
				if body.Network == "" {
					body.Network = prev.Network
				}
				if body.Env == "" {
					body.Env = prev.Env
				}
			}
		}
		wantNet := strings.TrimSpace(body.Network)
		agentNet, err := s.probeAgentHost(&body)
		if err != nil {
			writeJSON(w, http.StatusBadGateway, map[string]any{
				"ok": false, "error": "probe_failed", "message": err.Error(),
			})
			return
		}
		// Prefer live agent network when caller did not specify one.
		if body.Network == "" && agentNet != "" {
			body.Network = agentNet
		}
		item := s.registry.Upsert(body)
		resp := map[string]any{"ok": true, "item": item}
		if agentNet != "" {
			resp["agent_network"] = agentNet
		}
		if wantNet != "" && agentNet != "" && !strings.EqualFold(wantNet, agentNet) {
			base := strings.TrimRight(strings.TrimSpace(body.AgentURL), "/")
			if s.probeAgentSupportsNetwork(nil, base, body.AgentKey, wantNet) {
				resp["note"] = "Host agent default profile is " + agentNet +
					"; supported_networks includes " + wantNet
			} else {
				resp["warning"] = networkMismatchMessage(wantNet, agentNet)
				resp["network_mismatch"] = true
			}
		} else if agentNet != "" && wantNet == "" {
			resp["note"] = "Agent reports network=" + agentNet +
				". Choose the matching network when adding a node."
		}
		writeJSON(w, http.StatusOK, resp)
	case strings.HasPrefix(path, "/api/nodes/") && r.Method == http.MethodDelete:
		id := strings.TrimPrefix(path, "/api/nodes/")
		if id == "" {
			writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
			return
		}
		if _, ok := s.registry.Get(id); !ok {
			writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
			return
		}
		if s.workloads != nil {
			if n := s.workloads.CountByServerID(id); n > 0 {
				ids := s.workloads.IDsByServerID(id)
				writeJSON(w, http.StatusConflict, map[string]any{
					"ok": false, "error": "has_nodes",
					"message": fmt.Sprintf("Cannot remove server: %d node(s) still attached (%s). Delete nodes first.",
						n, strings.Join(ids, ", ")),
					"nodes_count": n,
					"node_ids":    ids,
				})
				return
			}
		}
		if !s.registry.Delete(id) {
			writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "deleted": id})
	case strings.HasPrefix(path, "/api/nodes/") && r.Method == http.MethodGet:
		id := strings.TrimPrefix(path, "/api/nodes/")
		n, ok := s.registry.Get(id)
		if !ok {
			writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
			return
		}
		n.AgentKey = ""
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "item": n})
	default:
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
	}
}

// pullAgentMetrics fetches /api/v1/metrics from the host agent and stores in panel cache.
// Falls back to /status.json for disk when metrics omit it (older agents).
func (s *Server) pullAgentMetrics(n NodeRef) (ServerMetrics, error) {
	base := strings.TrimRight(strings.TrimSpace(n.AgentURL), "/")
	if base == "" || n.AgentKey == "" {
		return ServerMetrics{}, fmt.Errorf("agent_url_or_key_missing")
	}
	client := s.client
	if client == nil || client.Timeout > 5*time.Second {
		client = &http.Client{Timeout: 5 * time.Second}
		if s.client != nil && s.client.Transport != nil {
			client.Transport = s.client.Transport
		}
	}
	doc, err := s.getAgentJSON(client, base+"/api/v1/metrics", n.AgentKey)
	if err != nil {
		return ServerMetrics{}, err
	}
	cur, _ := doc["current"].(map[string]any)
	if cur == nil {
		cur = doc
	}
	disk, _ := doc["disk"].(map[string]any)
	if disk == nil {
		disk, _ = cur["disk"].(map[string]any)
	}
	cpu := floatField(cur, "cpu_pct")
	loadPct := floatField(cur, "load_pct")
	m := ServerMetrics{
		ServerID:    n.ID,
		AgentURL:    base,
		CPUPct:      cpu,
		LoadPct:     loadPct,
		NCPU:        int(floatField(cur, "ncpu")),
		MemPct:      floatField(cur, "mem_pct"),
		MemUsedMB:   floatField(cur, "mem_used_mb"),
		MemTotalMB:  floatField(cur, "mem_total_mb"),
		Load1:       floatField(cur, "load_1"),
		DiskUsedPct: floatField(cur, "disk_used_pct"),
		DiskUsedGB:  floatField(cur, "disk_used_gb"),
		DiskTotalGB: floatField(cur, "disk_total_gb"),
		OS:          n.OS,
		Arch:        n.Arch,
	}
	if disk != nil {
		if v := floatField(disk, "used_pct"); v > 0 || m.DiskUsedPct == 0 {
			m.DiskUsedPct = v
		}
		if v := floatField(disk, "used_gb"); v > 0 || m.DiskUsedGB == 0 {
			m.DiskUsedGB = v
		}
		if v := floatField(disk, "total_gb"); v > 0 || m.DiskTotalGB == 0 {
			m.DiskTotalGB = v
		}
	}
	if m.DiskTotalGB <= 0 {
		if st, err := s.getAgentJSON(client, base+"/status.json", n.AgentKey); err == nil {
			if d, ok := st["disk"].(map[string]any); ok {
				m.DiskUsedPct = floatField(d, "used_pct")
				m.DiskUsedGB = floatField(d, "used_gb")
				m.DiskTotalGB = floatField(d, "total_gb")
			}
		}
	}
	return s.metrics.Upsert(m), nil
}

func (s *Server) getAgentJSON(client *http.Client, url, key string) (map[string]any, error) {
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+key)
	req.Header.Set("X-Api-Token", key)
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("agent HTTP %d", resp.StatusCode)
	}
	var doc map[string]any
	if err := json.Unmarshal(body, &doc); err != nil {
		return nil, err
	}
	return doc, nil
}
