package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func readJSONFile(path string) map[string]any {
	b, err := os.ReadFile(path)
	if err != nil {
		return map[string]any{}
	}
	var out map[string]any
	if err := json.Unmarshal(b, &out); err != nil {
		return map[string]any{"error": err.Error()}
	}
	return out
}

// safeStateSlug — network/env path segment (reject path traversal).
func safeStateSlug(s string) string {
	s = strings.TrimSpace(strings.ToLower(s))
	if s == "" {
		return ""
	}
	for _, r := range s {
		if (r < 'a' || r > 'z') && (r < '0' || r > '9') && r != '-' && r != '_' {
			return ""
		}
	}
	return s
}

// stateFileForNetworkEnv resolves agent-state.json for a status view.
// Leaf agents (TRON_NETWORK set) ALWAYS serve their own state — never a foreign
// tron-{env} file when the UI briefly sends network=tron&env=mainnet defaults.
// Host tip may read /var/lib/rpcnode/{network}-{env}/… when both are set; otherwise
// host tip state. Never invent tron-{env} from env alone.
func (s *Server) stateFileForNetworkEnv(viewNetwork, viewEnv string) (path string, localView bool) {
	myNet := strings.ToLower(strings.TrimSpace(envOr("TRON_NETWORK", "")))
	myEnv := strings.ToLower(strings.TrimSpace(s.cfg.Env))
	hostTip := myNet == "" && (isHostTipStateDir(filepath.Dir(s.cfg.StateFile)) ||
		isHostTipStateDir(strings.TrimSpace(os.Getenv("TRON_STATE_DIR"))))

	viewNetwork = safeStateSlug(viewNetwork)
	viewEnv = safeStateSlug(viewEnv)

	if !hostTip {
		// Per-node agent: ignore mismatched ?network=&env= — own leaf only.
		return s.cfg.StateFile, true
	}

	if viewNetwork == "" || viewEnv == "" {
		return s.cfg.StateFile, true
	}
	// Host tip: multi-network inventory path (never default network to tron).
	cand := fmt.Sprintf("/var/lib/rpcnode/%s-%s/agent-state.json", viewNetwork, viewEnv)
	if st, err := os.Stat(cand); err == nil && !st.IsDir() {
		return cand, viewNetwork == myNet && viewEnv == myEnv
	}
	return s.cfg.StateFile, true
}

func (s *Server) buildStatus(publicBase string) map[string]any {
	return s.buildStatusForNetworkEnv(publicBase, envOr("TRON_NETWORK", ""), s.cfg.Env)
}

// buildStatusForEnv — legacy env-only entry (network inferred empty → leaf/host own state).
func (s *Server) buildStatusForEnv(publicBase, viewEnv string) map[string]any {
	return s.buildStatusForNetworkEnv(publicBase, "", viewEnv)
}

func (s *Server) buildStatusForNetworkEnv(publicBase, viewNetwork, viewEnv string) map[string]any {
	viewEnv = strings.TrimSpace(strings.ToLower(viewEnv))
	if viewEnv == "" {
		viewEnv = s.cfg.Env
	}
	statePath, localView := s.stateFileForNetworkEnv(viewNetwork, viewEnv)
	st := readJSONFile(statePath)
	if len(st) == 0 {
		if localView {
			st = s.fallbackStatus(publicBase)
		} else {
			net := safeStateSlug(viewNetwork)
			if net == "" {
				net = "unknown"
			}
			st = map[string]any{
				"ok": true, "degraded": true, "health": "setup", "ui_phase": "setup",
				"env": viewEnv, "updated_at": time.Now().UTC().Format(time.RFC3339),
				"managed_by": "RpcNode toolkit",
				"instance": map[string]any{
					"id": net + "-" + viewEnv, "network": net, "env": viewEnv, "registered": false,
					"state_file": statePath,
				},
				"snapshot": map[string]any{"enabled": false, "ready": false, "phase": "idle"},
				"services": map[string]any{}, "rpc": map[string]any{},
				"connect": map[string]any{"ready": false},
				"note":    "No local agent-state for " + net + "/" + viewEnv,
			}
		}
	}

	// Merge live proxy metrics only for the agent env (this process).
	if localView {
		st["metrics"] = s.metrics.Snapshot()
	}
	agentStatus, agentActivity, agentLastErr := deriveAgentStatus(st)
	ver := agentVersion()
	st["gateway"] = "api-agent"
	st["agent_version"] = ver
	// Promote fullnode client version to top-level when only nested under rpc/sync.
	if cv, _ := st["client_version"].(string); strings.TrimSpace(cv) == "" {
		if rpc, _ := st["rpc"].(map[string]any); rpc != nil {
			if v, _ := rpc["client_version"].(string); strings.TrimSpace(v) != "" {
				st["client_version"] = strings.TrimSpace(v)
			} else if v, _ := rpc["version"].(string); strings.TrimSpace(v) != "" {
				st["client_version"] = strings.TrimSpace(v)
			}
		}
		if cv, _ := st["client_version"].(string); strings.TrimSpace(cv) == "" {
			if sync, _ := st["sync"].(map[string]any); sync != nil {
				if v, _ := sync["build_version"].(string); strings.TrimSpace(v) != "" {
					st["client_version"] = strings.TrimSpace(v)
				}
			}
		}
	}
	if rpcErr := rpcListenError(); rpcErr != "" {
		st["rpc_listen_error"] = rpcErr
		if strings.TrimSpace(agentLastErr) == "" {
			agentLastErr = rpcErr
		}
	}
	st["api_agent"] = map[string]any{
		"role":         "api",
		"version":      ver,
		"status":       agentStatus,
		"activity":     agentActivity,
		"last_error":   agentLastErr,
		"rpc_listen":   fmt.Sprintf("%s:%d", s.cfg.ListenHost, s.cfg.RPCPort),
		"panel_listen": fmt.Sprintf("%s:%d", s.cfg.ListenHost, s.cfg.PanelPort),
		"state":        s.cfg.StateFile,
	}
	// Prefer system-agent block when present; otherwise expose node-agent status here.
	if ag, ok := st["agent"].(map[string]any); ok {
		ag["version"] = ver
		if _, has := ag["status"]; !has {
			ag["status"] = agentStatus
		}
		if _, has := ag["activity"]; !has {
			ag["activity"] = agentActivity
		}
		if _, has := ag["last_error"]; !has {
			ag["last_error"] = agentLastErr
		}
		st["agent"] = ag
	} else {
		st["agent"] = map[string]any{
			"role":       "api",
			"version":    ver,
			"status":     agentStatus,
			"activity":   agentActivity,
			"last_error": agentLastErr,
		}
	}
	// Always expose toolkit/agent version; keep java-tron VERSION keys when present.
	if verMap, ok := st["version"].(map[string]any); ok {
		verMap["toolkit"] = ver
		verMap["agent"] = ver
		st["version"] = verMap
	} else if verStrMap, ok := st["version"].(map[string]string); ok {
		out := map[string]any{}
		for k, v := range verStrMap {
			out[k] = v
		}
		out["toolkit"] = ver
		out["agent"] = ver
		st["version"] = out
	} else {
		st["version"] = map[string]any{
			"toolkit": ver,
			"agent":   ver,
		}
	}
	st["agent_env"] = s.cfg.Env
	st["view_env"] = viewEnv
	st["controls_local"] = localView
	if !localView {
		st["controls"] = map[string]any{}
	}
	if _, ok := st["updated_at"]; !ok {
		st["updated_at"] = time.Now().UTC().Format("2006-01-02T15:04:05Z")
	}
	st["served_at"] = time.Now().UTC().Format("2006-01-02T15:04:05Z")

	// Connect URLs: RPC = public Go proxy (direct when RPCNODE_PUBLIC_PORT=0);
	// panel = Node Agent API (agent_port). Ops console Apply writes public-base.json.
	if localView {
		rpcPort := s.cfg.PublicRPCPort()
		base := resolveConnectBase(s.cfg.PublicBase, publicBase, rpcPort)
		panelBase := strings.TrimRight(s.cfg.PanelBase, "/")
		if ov := readJSONFile(filepath.Join(filepath.Dir(s.cfg.StateFile), "public-base.json")); len(ov) > 0 {
			if pb, _ := ov["public_base"].(string); pb != "" && !isLoopbackBase(pb) {
				base = strings.TrimRight(pb, "/")
			}
			if pnb, _ := ov["panel_base"].(string); pnb != "" && !isLoopbackBase(pnb) {
				panelBase = strings.TrimRight(pnb, "/")
			}
		} else if inst, ok := st["instance"].(map[string]any); ok {
			if pb, _ := inst["public_base_url"].(string); pb != "" && !isLoopbackBase(pb) {
				if isLoopbackBase(base) || s.cfg.PublicBase == "" {
					base = strings.TrimRight(pb, "/")
				}
			}
			if pnb, _ := inst["panel_base_url"].(string); pnb != "" && !isLoopbackBase(pnb) {
				if panelBase == "" || isLoopbackBase(panelBase) {
					panelBase = strings.TrimRight(pnb, "/")
				}
			}
		}
		if panelBase == "" {
			panelBase = swapURLPort(base, s.cfg.PanelPort)
		}
		if base != "" {
			applyConnectBase(st, base, panelBase, rpcPort, s.cfg.PanelPort, s.cfg.RPCPort > 0)
		}
	} else {
		// Other env from primary panel: seed default ports for IP / public-base picker.
		rpcPort, panelPort := defaultPortsForEnv(viewEnv)
		if inst, ok := st["instance"].(map[string]any); ok {
			if v, ok := inst["public_port"].(float64); ok && int(v) > 0 {
				rpcPort = int(v)
			} else if v, ok := inst["gateway_port"].(float64); ok && int(v) > 0 {
				rpcPort = int(v)
			}
			if v, ok := inst["panel_port"].(float64); ok && int(v) > 0 {
				panelPort = int(v)
			}
			inst["public_port"] = rpcPort
			inst["gateway_port"] = rpcPort
			inst["panel_port"] = panelPort
			st["instance"] = inst
		}
		conn, _ := st["connect"].(map[string]any)
		if conn == nil {
			conn = map[string]any{}
		}
		conn["rpc_port"] = rpcPort
		conn["panel_port"] = panelPort
		conn["ready"] = false
		st["connect"] = conn
	}

	// Ensure instances list always present for env switcher.
	if _, ok := st["instances"]; !ok {
		st["instances"] = []map[string]any{
			{"env": "mainnet", "id": "tron-mainnet", "current": s.cfg.Env == "mainnet"},
			{"env": "nile", "id": "tron-nile", "current": s.cfg.Env == "nile"},
			{"env": "shasta", "id": "tron-shasta", "current": s.cfg.Env == "shasta"},
		}
	}

	// State age / system-agent freshness (interval default 2s; stale after ~6 missed ticks)
	stale := true
	if ua, _ := st["updated_at"].(string); ua != "" {
		if t, err := time.Parse(time.RFC3339, ua); err == nil {
			stale = time.Since(t) > 12*time.Second
		}
	}
	st["system_agent_stale"] = stale
	if stale {
		st["degraded"] = true
		if h, _ := st["health"].(string); h == "ok" || h == "" {
			st["health"] = "degraded"
		}
		if ag, ok := st["api_agent"].(map[string]any); ok {
			if act, _ := ag["activity"].(string); act == "idle" || act == "" {
				ag["activity"] = "unreachable"
			}
			if stt, _ := ag["status"].(string); stt == "ok" || stt == "" {
				ag["status"] = "degraded"
			}
			st["api_agent"] = ag
		}
	}

	st["ok"] = true

	// Panel sanitize / multi-network routing needs these on status.json (not only /healthz).
	st["supported_networks"] = supportedNetworks()
	st["upstream"] = fmt.Sprintf("%s:%d", s.cfg.UpstreamHost, s.cfg.UpstreamPort)
	if networkEnv := strings.ToLower(strings.TrimSpace(envOr("TRON_NETWORK", ""))); networkEnv != "" {
		st["network"] = networkEnv
	}

	// Ensure supported_steps / capabilities are present even when system-agent state is old.
	network := ""
	if inst, ok := st["instance"].(map[string]any); ok {
		network, _ = inst["network"].(string)
	}
	if network == "" {
		network = strings.ToLower(strings.TrimSpace(envOr("TRON_NETWORK", "")))
	}
	if network == "" {
		if lc, ok := st["lifecycle"].(map[string]any); ok {
			if prof, ok := lc["profile"].(map[string]any); ok {
				network, _ = prof["network"].(string)
			}
		}
	}
	if network != "" {
		steps := supportedLifecycleSteps(network, viewEnv)
		caps := lifecycleCapabilities(network, viewEnv)
		if _, ok := st["supported_steps"]; !ok {
			st["supported_steps"] = steps
		}
		if _, ok := st["capabilities"]; !ok {
			st["capabilities"] = caps
		}
		if lc, ok := st["lifecycle"].(map[string]any); ok {
			if _, ok := lc["supported_steps"]; !ok {
				lc["supported_steps"] = steps
			}
			if _, ok := lc["capabilities"]; !ok {
				lc["capabilities"] = caps
			}
			if prof, ok := lc["profile"].(map[string]any); ok {
				if _, ok := prof["supported_steps"]; !ok {
					prof["supported_steps"] = steps
				}
				if _, ok := prof["capabilities"]; !ok {
					prof["capabilities"] = caps
				}
				lc["profile"] = prof
			}
			st["lifecycle"] = lc
		}
	}

	return st
}

func deriveAgentStatus(st map[string]any) (status, activity, lastErr string) {
	status = "ok"
	activity = "idle"
	snap, _ := st["snapshot"].(map[string]any)
	rpc, _ := st["rpc"].(map[string]any)
	conn, _ := st["connect"].(map[string]any)
	if snap != nil {
		failed, _ := snap["failed"].(bool)
		phase, _ := snap["phase"].(string)
		wget, _ := snap["wget_running"].(bool)
		ready, _ := snap["ready"].(bool)
		errStr, _ := snap["error"].(string)
		detail, _ := snap["detail"].(string)
		if failed || strings.EqualFold(phase, "error") {
			return "error", "snapshot_error", firstNonEmpty(errStr, detail, "snapshot failed")
		}
		if wget || strings.EqualFold(phase, "download") || strings.EqualFold(phase, "extract") || strings.EqualFold(phase, "extracting") {
			return "ok", "snapshot_download", ""
		}
		processUp, _ := rpc["process_up"].(bool)
		reachable, _ := rpc["reachable"].(bool)
		httpOK, _ := rpc["http_ok"].(bool)
		connReady, _ := conn["ready"].(bool)
		if ready && !reachable && !httpOK && !connReady {
			return "ok", "node_starting", ""
		}
		if reachable || httpOK || connReady {
			return "ok", "online", ""
		}
		if processUp {
			return "degraded", "node_starting", ""
		}
	}
	if h, _ := st["health"].(string); h == "degraded" || h == "setup" || h == "maintenance" {
		status = "degraded"
	}
	if h, _ := st["health"].(string); h == "error" {
		status = "error"
	}
	return status, activity, lastErr
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}

// defaultPortsForEnv returns RPC + panel defaults.
// Panel is always 8093; only RPC differs per env.
func defaultPortsForEnv(env string) (rpcPort, panelPort int) {
	panelPort = 8093
	switch strings.ToLower(strings.TrimSpace(env)) {
	case "nile":
		return 8091, panelPort
	case "shasta":
		return 8092, panelPort
	default:
		return 8090, panelPort
	}
}

// applyConnectBase overlays public/agent bases and ports only.
// Path-specific connect (wallet, JSON-RPC examples, …) belongs to system-agent
// collect for that network — never invent a default chain here.
func applyConnectBase(st map[string]any, rpcBase, panelBase string, rpcPort, panelPort int, proxyEnabled bool) {
	rpcBase = strings.TrimRight(rpcBase, "/")
	panelBase = strings.TrimRight(panelBase, "/")
	if rpcBase == "" {
		return
	}
	if panelBase == "" {
		panelBase = swapURLPort(rpcBase, panelPort)
	}

	rpcMode := "go_proxy"
	note := "Clients → Go RPC (public_port) → upstream FullNode (loopback). " +
		"On update/maintenance Go returns 503 Retry-After. " +
		"Node Agent API on agent_port."
	if !proxyEnabled {
		rpcMode = "fullnode_direct"
		note = "Misconfig: RPCNODE_PUBLIC_PORT=0 — clients would hit FullNode directly (no sleep control). " +
			"Prefer Go RPC proxy on RPCNODE_PUBLIC_PORT."
	}
	conn, _ := st["connect"].(map[string]any)
	if conn == nil {
		conn = map[string]any{}
	}
	conn["base_url"] = rpcBase
	conn["rpc_base"] = rpcBase
	conn["panel_base"] = panelBase
	conn["rpc_port"] = rpcPort
	conn["panel_port"] = panelPort
	conn["public_port"] = rpcPort
	conn["agent_port"] = panelPort
	conn["rpc_mode"] = rpcMode
	conn["rpcnode_upstream"] = rpcBase
	// Rebase collect-owned public URLs onto the resolved public host:port.
	// Never invent chain paths (wallet / eth_… / JSON-RPC examples).
	rebaseConnectPublicURLs(conn, rpcBase)
	if strings.TrimSpace(fmt.Sprint(conn["note"])) == "" {
		conn["note"] = note
	}
	st["connect"] = conn
	if inst, ok := st["instance"].(map[string]any); ok {
		inst["public_base_url"] = rpcBase
		inst["panel_base_url"] = panelBase
		inst["status_url"] = panelBase + "/status"
		inst["public_port"] = rpcPort
		inst["gateway_port"] = rpcPort
		inst["panel_port"] = panelPort
		inst["rpc_mode"] = rpcMode
		st["instance"] = inst
	}
	st["public_base"] = rpcBase
	st["panel_base"] = panelBase
}

// rebaseConnectPublicURLs rewrites collect-owned absolute http(s) values onto
// rpcBase (keep path). Skips internal upstream and non-URL fields. Does not
// invent missing chain paths.
func rebaseConnectPublicURLs(conn map[string]any, rpcBase string) {
	rpcBase = strings.TrimRight(rpcBase, "/")
	if conn == nil || rpcBase == "" {
		return
	}
	skip := map[string]bool{
		"internal_node": true, "note": true, "rpc_mode": true,
		"base_url": true, "rpc_base": true, "panel_base": true,
	}
	for k, raw := range conn {
		if skip[k] {
			continue
		}
		switch v := raw.(type) {
		case string:
			if reb, ok := rebasePublicURL(v, rpcBase); ok {
				conn[k] = reb
			}
		case map[string]any:
			if k != "examples" {
				continue
			}
			for ek, ev := range v {
				if s, ok := ev.(string); ok {
					if reb, ok := rebasePublicURLInText(s, rpcBase); ok {
						v[ek] = reb
					}
				}
			}
		case map[string]string:
			if k != "examples" {
				continue
			}
			for ek, ev := range v {
				if reb, ok := rebasePublicURLInText(ev, rpcBase); ok {
					v[ek] = reb
				}
			}
		}
	}
	conn["http_fullnode"] = rpcBase
	conn["rpcnode_upstream"] = rpcBase
}

func rebasePublicURL(u, rpcBase string) (string, bool) {
	u = strings.TrimSpace(u)
	var rest string
	switch {
	case strings.HasPrefix(u, "https://"):
		rest = u[len("https://"):]
	case strings.HasPrefix(u, "http://"):
		rest = u[len("http://"):]
	default:
		return "", false
	}
	path := ""
	if i := strings.IndexByte(rest, '/'); i >= 0 {
		path = rest[i:]
	}
	return rpcBase + path, true
}

func rebasePublicURLInText(s, rpcBase string) (string, bool) {
	start := strings.Index(s, "https://")
	plen := len("https://")
	if start < 0 {
		start = strings.Index(s, "http://")
		plen = len("http://")
	}
	if start < 0 {
		return "", false
	}
	rest := s[start+plen:]
	hostEnd := len(rest)
	for i := 0; i < len(rest); i++ {
		c := rest[i]
		if c == '/' || c == ' ' || c == '\'' || c == '"' || c == '`' {
			hostEnd = i
			break
		}
	}
	pathEnd := hostEnd
	if hostEnd < len(rest) && rest[hostEnd] == '/' {
		pathEnd = hostEnd
		for pathEnd < len(rest) {
			c := rest[pathEnd]
			if c == ' ' || c == '\'' || c == '"' || c == '`' {
				break
			}
			pathEnd++
		}
	}
	old := s[start : start+plen+pathEnd]
	reb, ok := rebasePublicURL(old, rpcBase)
	if !ok {
		return "", false
	}
	return s[:start] + reb + s[start+plen+pathEnd:], true
}

func (s *Server) fallbackStatus(publicBase string) map[string]any {
	base := strings.TrimRight(publicBase, "/")
	if base == "" {
		base = fmt.Sprintf("http://127.0.0.1:%d", s.cfg.PublicRPCPort())
	}
	host, _ := os.Hostname()
	net := strings.ToLower(strings.TrimSpace(envOr("TRON_NETWORK", "")))
	if net == "" {
		net = "unknown"
	}
	return map[string]any{
		"ok": true, "degraded": true, "health": "setup", "ui_phase": "setup",
		"env": s.cfg.Env, "network": net, "updated_at": time.Now().UTC().Format(time.RFC3339),
		"managed_by": "RpcNode toolkit",
		"instance": map[string]any{
			"id": net + "-" + s.cfg.Env, "network": net, "env": s.cfg.Env,
			"hostname": host, "managed_by": "RpcNode toolkit",
			"public_base_url": base, "status_url": base + "/status",
			"registered": false, "state_file": s.cfg.StateFile,
		},
		"setup": map[string]any{
			"complete": false, "phase": "setup",
			"steps": []map[string]any{
				{"id": "system", "title": "system-agent writing state", "done": false,
					"detail": "docker compose up -d system-agent"},
				{"id": "api", "title": "api-agent serving /status", "done": true,
					"detail": "this process"},
			},
		},
		"services": map[string]any{"api": "active", "system": "n/a", "node": "n/a"},
		"snapshot": map[string]any{"ready": false, "pct": "?", "phase": "idle", "eta": "—"},
		"rpc":      map[string]any{"node": fmt.Sprintf("%s:%d", s.cfg.UpstreamHost, s.cfg.UpstreamPort)},
		"connect": map[string]any{
			"ready": false, "base_url": base,
			"note": "Waiting for system-agent state file at " + s.cfg.StateFile,
		},
		"disk": map[string]any{}, "maintenance": map[string]any{"enabled": false},
		"pause": map[string]any{"active": false}, "updater": map[string]any{},
		"version": map[string]any{}, "paths": map[string]any{"state": s.cfg.StateFile},
	}
}
