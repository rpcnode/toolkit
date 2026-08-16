package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// workloadPreProvision — registry row before host Install (leaf does not exist).
// Add persists tip catalog ports with status ready_to_install — still no leaf.
// Dialing tip/leaf invents false "Agent unreachable".
func workloadPreProvision(wl WorkloadRef) bool {
	st := strings.ToLower(strings.TrimSpace(wl.Status))
	if st == "awaiting_ports" || st == "ready_to_install" {
		return true
	}
	// No dedicated leaf port yet — host install has not landed units.
	return !workloadLooksProvisioned(wl)
}

// workloadLeafShouldBeUp — dial the dedicated per-node agent for status / sync %.
// After Install provision the leaf may serve while SQLite still says installing.
// Unreachable during mid-setup is soft (see workloadLeafDialSoftFail).
func workloadLeafShouldBeUp(wl WorkloadRef) bool {
	if !workloadLooksProvisioned(wl) {
		return false
	}
	switch strings.ToLower(strings.TrimSpace(wl.Status)) {
	case "awaiting_ports", "ready_to_install":
		return false
	default:
		return true
	}
}

// workloadLeafDialSoftFail — leaf may not listen yet right after Install provision.
// Do not flip the row to agent_error / «Agent unreachable».
func workloadLeafDialSoftFail(wl WorkloadRef) bool {
	switch strings.ToLower(strings.TrimSpace(wl.Status)) {
	case "ports_confirmed", "ready_to_install", "installing", "":
		return true
	default:
		return false
	}
}

// proxyToAgent forwards ops APIs to a registered / default host agent.
// Panel never runs chain RPC itself — agents own that.
func (s *Server) proxyToAgent(w http.ResponseWriter, r *http.Request) {
	// Status before Confirm ports: synthetic Setup shell — do NOT dial tip/leaf.
	// Exception: stuck awaiting_ports/ready_to_install while leaf units already live
	// (re-add / missed SQLite advance) — probe leaf and heal status.
	if isStatusPath(r.URL.Path) {
		if wl, ok := s.workloadFromProxyRequest(r); ok && workloadPreProvision(wl) {
			if healed, payload := s.tryServeHealedPreProvisionStatus(r, wl); healed {
				w.Header().Set("Content-Type", "application/json")
				w.Header().Set("Cache-Control", "no-store")
				w.WriteHeader(http.StatusOK)
				_, _ = w.Write(payload)
				return
			}
			base, _ := s.resolveAgent(r)
			doc := map[string]any{"ok": true}
			payload := marshalNeedsProvisionStatus(
				doc, wl, base, "", wl.Env, strings.ToLower(strings.TrimSpace(wl.Network)),
				"Install to check ports and install on the host",
			)
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "no-store")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(payload)
			return
		}
		// Collector already polls tips every ~2s. Prefer that snapshot so N parallel
		// Install/watch clients do not each hold a 45s tip dial (connection resets).
		if r.URL.Query().Get("live") != "1" {
			if doc, ok := s.freshCollectorStatus(r, 20*time.Second); ok {
				writeJSON(w, http.StatusOK, doc)
				return
			}
		}
	}

	base, token := s.resolveAgent(r)
	if base == "" {
		if isStatusPath(r.URL.Path) {
			if cached, ok := s.cachedStatusPayload(r, "", fmt.Errorf("no agent URL")); ok {
				writeJSON(w, http.StatusOK, cached)
				return
			}
		}
		writeJSON(w, http.StatusOK, s.emptyControlPlaneStatus(r))
		return
	}

	fwdPath := r.URL.RequestURI()
	// Status for a known leaf UUID: never forward UI defaults (network=tron&env=mainnet)
	// onto a BSC/bitcoin/… agent — that used to load stale /var/lib/rpcnode/tron-* state.
	if isStatusPath(r.URL.Path) {
		if wl, ok := s.workloadFromProxyRequest(r); ok && strings.TrimSpace(wl.Network) != "" {
			u, err := url.Parse(base + "/api/status.json")
			if err == nil {
				q := u.Query()
				q.Set("network", strings.ToLower(strings.TrimSpace(wl.Network)))
				env := strings.TrimSpace(wl.Env)
				if env == "" {
					env = "mainnet"
				}
				q.Set("env", env)
				u.RawQuery = q.Encode()
				fwdPath = u.RequestURI()
			}
		}
	}

	ctx := r.Context()
	req, err := http.NewRequestWithContext(ctx, r.Method, base+fwdPath, r.Body)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	for k, vv := range r.Header {
		lk := strings.ToLower(k)
		if lk == "host" || lk == "connection" || lk == "content-length" {
			continue
		}
		for _, v := range vv {
			req.Header.Add(k, v)
		}
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("X-Api-Token", token)
	}

	cli := s.client
	if isStatusPath(r.URL.Path) {
		cli = s.agentStatusClient()
	}
	resp, err := cli.Do(req)
	if err != nil {
		if isStatusPath(r.URL.Path) {
			// Unprovisioned / awaiting Confirm ports — tip down or wrong Server URL
			// must still show NODE SETUP, not a bare "Agent unreachable" ops shell.
			if wl, ok := s.workloadFromProxyRequest(r); ok && workloadPreProvision(wl) {
				doc := map[string]any{"ok": true}
				payload := marshalNeedsProvisionStatus(
					doc, wl, base, "", wl.Env, strings.ToLower(strings.TrimSpace(wl.Network)),
					"Install to check ports and install on the host",
				)
				w.Header().Set("Content-Type", "application/json")
				w.Header().Set("Cache-Control", "no-store")
				w.WriteHeader(http.StatusOK)
				_, _ = w.Write(payload)
				return
			}
			if cached, ok := s.cachedStatusPayload(r, base, err); ok {
				writeJSON(w, http.StatusOK, cached)
				return
			}
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "degraded": true, "agent_reachable": false,
			"health": "agent_unreachable",
			"error":  "agent_unreachable", "message": err.Error(),
			"agent_url": base, "updated_at": time.Now().UTC().Format(time.RFC3339),
			"instances": []any{}, "connect": map[string]any{"ready": false},
			"note": "Panel is up. Last agent poll failed — no cached node_status yet.",
			"agent": map[string]any{
				"status": "error", "activity": "unreachable", "last_error": err.Error(),
			},
		})
		return
	}
	defer resp.Body.Close()
	payload, _ := io.ReadAll(io.LimitReader(resp.Body, 32<<20))

	// When panel knows the workload is bitcoin, never surface a TRON snapshot lifecycle
	// (host agent may still be provisioned as tron/mainnet until reconfigured).
	if isStatusPath(r.URL.Path) {
		wl, wlOK := s.workloadFromProxyRequest(r)
		// Skip extra /healthz when panel already knows the network — doubles outbound
		// under parallel Install/watch and was the RST amplifier.
		if !wlOK || strings.TrimSpace(wl.Network) == "" {
			if hzReq, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/healthz", nil); err == nil {
				if token != "" {
					hzReq.Header.Set("Authorization", "Bearer "+token)
					hzReq.Header.Set("X-Api-Token", token)
				}
				if hzResp, err := s.agentStatusClient().Do(hzReq); err == nil {
					hzBody, _ := io.ReadAll(io.LimitReader(hzResp.Body, 1<<20))
					_ = hzResp.Body.Close()
					if hzResp.StatusCode < 300 {
						payload = mergeAgentIdentityFromHealthz(payload, hzBody)
					}
				}
			}
		}
		if wlOK {
			payload = sanitizeStatusForWorkload(payload, wl, base)
		}
	}

	ct := resp.Header.Get("Content-Type")
	if ct != "" {
		w.Header().Set("Content-Type", ct)
	} else {
		w.Header().Set("Content-Type", "application/json")
	}
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(resp.StatusCode)
	_, _ = w.Write(payload)
}

func isStatusPath(path string) bool {
	return strings.HasSuffix(path, "/status.json") || path == "/api/status.json"
}

func (s *Server) workloadFromProxyRequest(r *http.Request) (WorkloadRef, bool) {
	if id := strings.TrimSpace(r.URL.Query().Get("node")); id != "" {
		if wl, ok := s.workloads.Get(id); ok {
			return wl, true
		}
	}
	network := strings.TrimSpace(strings.ToLower(r.URL.Query().Get("network")))
	if env := strings.TrimSpace(r.URL.Query().Get("env")); env != "" {
		return s.workloadByNetworkEnv(network, env)
	}
	return WorkloadRef{}, false
}

// mergeAgentIdentityFromHealthz overlays live /healthz identity onto status.json.
// healthz is the source of truth for network / supported_networks / capabilities;
// status.json often keeps stale instance.network=tron in system-agent state files.
func mergeAgentIdentityFromHealthz(statusRaw, healthzRaw []byte) []byte {
	if len(healthzRaw) == 0 {
		return statusRaw
	}
	var hz map[string]any
	if err := json.Unmarshal(healthzRaw, &hz); err != nil || hz == nil {
		return statusRaw
	}
	var doc map[string]any
	if len(statusRaw) == 0 {
		doc = map[string]any{}
	} else if err := json.Unmarshal(statusRaw, &doc); err != nil || doc == nil {
		doc = map[string]any{}
	}
	for _, k := range []string{
		"network", "supported_networks", "capabilities", "supported_steps",
		"upstream", "agent_version", "version",
	} {
		if v, ok := hz[k]; ok && v != nil {
			doc[k] = v
		}
	}
	if n, _ := hz["network"].(string); strings.TrimSpace(n) != "" {
		doc["network"] = strings.ToLower(strings.TrimSpace(n))
	}
	out, err := json.Marshal(doc)
	if err != nil {
		return statusRaw
	}
	return out
}

// agentReportedNetwork extracts the chain network the agent identity claims.
// Prefer healthz / top-level `network` (and capabilities) over stale status.json
// `instance.network` / lifecycle.profile — those often linger as "tron" after
// bitcoin re-label / provision.
func agentReportedNetwork(doc map[string]any) string {
	if doc == nil {
		return ""
	}
	// 1) Agent identity (healthz / status top-level) — source of truth.
	if n, _ := doc["network"].(string); strings.TrimSpace(n) != "" {
		return strings.ToLower(strings.TrimSpace(n))
	}
	// 2) Capabilities: IBD-only agents are bitcoin even when instance still says tron.
	if caps, ok := doc["capabilities"].(map[string]any); ok {
		if truthy(caps["ibd"]) && !truthy(caps["snapshot"]) && agentSupportsNetwork(doc, "bitcoin") {
			return "bitcoin"
		}
	}
	// 3) Fall back to lifecycle / instance only when identity omitted.
	if lc, ok := doc["lifecycle"].(map[string]any); ok {
		if prof, ok := lc["profile"].(map[string]any); ok {
			if n, _ := prof["network"].(string); strings.TrimSpace(n) != "" {
				return strings.ToLower(strings.TrimSpace(n))
			}
		}
	}
	if inst, ok := doc["instance"].(map[string]any); ok {
		if n, _ := inst["network"].(string); strings.TrimSpace(n) != "" {
			return strings.ToLower(strings.TrimSpace(n))
		}
	}
	return ""
}

func networkMismatchMessage(wantNetwork, agentNetwork string) string {
	want := strings.ToLower(strings.TrimSpace(wantNetwork))
	got := strings.ToLower(strings.TrimSpace(agentNetwork))
	if got == "" {
		got = "?"
	}
	// Short — no lectures. Prefer silent routing; this is last-resort copy.
	return want + " agent expected (got " + got + ")"
}

// agentURLPortMismatch — true when response came from a different listen port than
// the workload's dedicated agent_port (typical: host :39190 vs bitcoin :39390).
func agentURLPortMismatch(agentURL string, wantPort int) bool {
	if wantPort <= 0 {
		return false
	}
	u, err := url.Parse(strings.TrimSpace(agentURL))
	if err != nil || u.Host == "" {
		return false
	}
	_, portStr, err := net.SplitHostPort(u.Host)
	if err != nil {
		// host without port
		return false
	}
	got, err := strconv.Atoi(portStr)
	if err != nil || got <= 0 {
		return false
	}
	return got != wantPort
}

// workloadLooksProvisioned — panel has a dedicated per-node agent_port for this chain.
func workloadLooksProvisioned(wl WorkloadRef) bool {
	if wl.AgentPort <= 0 {
		return false
	}
	want := strings.ToLower(strings.TrimSpace(wl.Network))
	// Bitcoin must not sit on TRON default agent ports (39190–39192).
	if want == "bitcoin" {
		switch wl.AgentPort {
		case 39190, 39191, 39192, 39090, 39091, 39092:
			return false
		}
	}
	return true
}

// hostControlPlaneResponse — multi-network Server agent that can provision want,
// but is NOT currently labeled as want (healthz.network / identity).
// Per-node bitcoin agents also advertise supported_networks — that alone is NOT host CP.
func hostControlPlaneResponse(doc map[string]any, want string) bool {
	if !agentSupportsNetwork(doc, want) {
		return false
	}
	identity := agentReportedNetwork(doc)
	if identity != "" && strings.EqualFold(identity, want) {
		return false
	}
	return true
}

// agentSupportsNetwork — unified Server agent advertises supported_networks.
func agentSupportsNetwork(doc map[string]any, want string) bool {
	want = strings.ToLower(strings.TrimSpace(want))
	if want == "" || doc == nil {
		return false
	}
	raw, ok := doc["supported_networks"]
	if !ok {
		return false
	}
	switch t := raw.(type) {
	case []any:
		for _, v := range t {
			if strings.EqualFold(strings.TrimSpace(fmt.Sprint(v)), want) {
				return true
			}
		}
	case []string:
		for _, v := range t {
			if strings.EqualFold(strings.TrimSpace(v), want) {
				return true
			}
		}
	}
	return false
}

// agentHasSupportedNetworks — status/healthz declared a supported_networks list.
func agentHasSupportedNetworks(doc map[string]any) bool {
	if doc == nil {
		return false
	}
	raw, ok := doc["supported_networks"]
	if !ok {
		return false
	}
	switch t := raw.(type) {
	case []any:
		return len(t) > 0
	case []string:
		return len(t) > 0
	default:
		return false
	}
}

// agentIncompatibleWithNetwork — binary explicitly cannot serve want
// (supported_networks present and does not include want).
func agentIncompatibleWithNetwork(doc map[string]any, want string) bool {
	return agentHasSupportedNetworks(doc) && !agentSupportsNetwork(doc, want)
}

// sanitizeStatusForWorkload overlays panel-known network onto agent status JSON.
// Network identity = panel node.network + agent healthz.network / TRON_NETWORK /
// capabilities / lifecycle.profile — NEVER upstream port (:18090 / :8332).
// Host Server agent default profile (tron) is NOT a fatal mismatch for bitcoin.
func sanitizeStatusForWorkload(payload []byte, wl WorkloadRef, agentURL string) []byte {
	wantNet := strings.ToLower(strings.TrimSpace(wl.Network))
	if wantNet == "" {
		return payload
	}
	var doc map[string]any
	if err := json.Unmarshal(payload, &doc); err != nil || doc == nil {
		return payload
	}

	env := wl.Env
	if env == "" {
		env = "mainnet"
	}
	agentNet := agentReportedNetwork(doc)
	identityMatches := agentNet != "" && strings.EqualFold(agentNet, wantNet)

	doc["panel_network"] = wantNet
	doc["panel_env"] = env
	doc["panel_node_id"] = wl.ID
	doc["agent_url"] = agentURL
	doc["view_env"] = env
	if agentNet != "" {
		doc["agent_network"] = agentNet
	}

	netDiffers := agentNet != "" && !identityMatches
	// Stale TRON snapshot lifecycle — setup smell only when identity does NOT match
	// (healthz.network=bitcoin wins over lifecycle.profile=tron).
	tronLifecycle := false
	if strings.EqualFold(wantNet, "bitcoin") && !identityMatches {
		if lc, ok := doc["lifecycle"].(map[string]any); ok {
			prof, _ := lc["profile"].(map[string]any)
			profNet := ""
			if prof != nil {
				profNet, _ = prof["network"].(string)
			}
			includeSnap := truthy(lc["include_snapshot"])
			if prof != nil && truthy(prof["include_snapshot"]) {
				includeSnap = true
			}
			if (profNet != "" && !strings.EqualFold(profNet, "bitcoin")) || includeSnap {
				tronLifecycle = true
				if agentNet == "" {
					agentNet = firstNonEmpty(profNet, "tron")
					doc["agent_network"] = agentNet
				}
				netDiffers = true
			}
		}
	}

	provisioned := workloadLooksProvisioned(wl)
	hostCP := hostControlPlaneResponse(doc, wantNet)
	// Response URL port ≠ dedicated agent_port → we hit host Server agent, not the node unit.
	hitHostPort := agentURLPortMismatch(agentURL, wl.AgentPort)
	incompatible := agentIncompatibleWithNetwork(doc, wantNet)
	supportsWant := agentSupportsNetwork(doc, wantNet)

	// Panel row still awaiting Confirm ports — never paint leftover leaf lifecycle
	// (re-add / incomplete remove) as Install/Start. Host install starts only after
	// provision → ports_confirmed.
	// If the dedicated leaf is already live, do not force Setup (SQLite heal path).
	if strings.EqualFold(strings.TrimSpace(wl.Status), "awaiting_ports") ||
		strings.EqualFold(strings.TrimSpace(wl.Status), "ready_to_install") {
		if !leafStatusLooksLive(doc, wantNet) {
			return marshalNeedsProvisionStatus(doc, wl, agentURL, agentNet, env, wantNet, "")
		}
	}

	// Truly incompatible binary on the dedicated node port — short Wrong agent only.
	// Identity-only: never infer tron/bitcoin from upstream listen port.
	if netDiffers && provisioned && !hitHostPort && !hostCP &&
		!identityMatches && !supportsWant && incompatible {
		detail := networkMismatchMessage(wantNet, agentNet)
		doc["ok"] = true
		doc["degraded"] = true
		doc["health"] = "mismatch"
		doc["ui_phase"] = "error"
		doc["network_mismatch"] = true
		doc["note"] = detail
		doc["error"] = "network_mismatch"
		doc["message"] = detail
		doc["instance"] = map[string]any{
			"id": wl.ID, "network": wantNet, "env": env,
			"agent_network": agentNet, "mismatch": true,
		}
		doc["lifecycle"] = map[string]any{
			"phase":           "error",
			"label":           "Wrong agent",
			"detail":          detail,
			"busy":            false,
			"node_status":     "network_mismatch",
			"supported_steps": panelSupportedSteps(wantNet),
			"capabilities":    panelCapabilities(wantNet),
			"profile": map[string]any{
				"network":          wantNet,
				"env":              env,
				"agent_network":    agentNet,
				"include_snapshot": false,
				"supported_steps":  panelSupportedSteps(wantNet),
				"capabilities":     panelCapabilities(wantNet),
			},
			"steps": []any{
				map[string]any{
					"id": "mismatch", "title": "Wrong agent", "status": "error", "error": true,
					"detail": detail,
				},
			},
			"current": "mismatch", "current_step_id": "mismatch",
		}
		doc["supported_steps"] = panelSupportedSteps(wantNet)
		doc["capabilities"] = panelCapabilities(wantNet)
		doc["snapshot"] = map[string]any{
			"enabled": false, "ready": false, "phase": "idle",
		}
		out, err := json.Marshal(doc)
		if err != nil {
			return payload
		}
		return out
	}

	// Host / mis-bound / not-yet-bitcoin / multi-network Server agent → Setup.
	// Dedicated per-node agent_port (matched URL): NEVER invent needs_provision shell —
	// that freezes Confirm ports (wizard waits for ACK that the shell can never give).
	// Do NOT force Setup from upstream port; do NOT lecture java-tron on bitcoin.
	onDedicatedAgent := provisioned && !hitHostPort
	hostTipAns := truthyAny(doc["host_tip"]) || strings.EqualFold(fmt.Sprint(doc["node_status"]), "host")
	// Dialing tip URL + host_tip is expected mid-setup — NOT "tip stole leaf".
	// Tip-stole only when host_tip answers on the dedicated leaf agent_port.
	tipStoleLeaf := hostTipAns && onDedicatedAgent
	setupLike := !provisioned || (hitHostPort && !hostTipAns) || tipStoleLeaf
	if !onDedicatedAgent && !hostTipAns {
		setupLike = setupLike || hostCP || tronLifecycle ||
			(netDiffers && !incompatible && !identityMatches)
	}
	if tipStoleLeaf || (setupLike && (strings.EqualFold(wantNet, "bitcoin") || netDiffers || tronLifecycle ||
		hostCP || !provisioned || (hitHostPort && !hostTipAns))) {
		detail := ""
		wantPort := wl.AgentPort
		unit := strings.TrimSpace(wantNet) + "-" + firstNonEmpty(env, "mainnet")
		switch {
		case tipStoleLeaf && wantPort > 0:
			detail = fmt.Sprintf(
				"Host tip answered — leaf agent on :%d is down or tip stole that port. Check rpcnode-api-agent-%s.service bind.",
				wantPort, unit,
			)
		case !provisioned && hostTipAns:
			detail = "Host tip answered — provision / start the per-node agent for this network"
		case hitHostPort && !hostTipAns && wantPort > 0:
			detail = fmt.Sprintf(
				"Wrong agent port — expected leaf :%d. Check rpcnode-api-agent-%s.service bind.",
				wantPort, unit,
			)
		}
		return marshalNeedsProvisionStatus(doc, wl, agentURL, agentNet, env, wantNet, detail)
	}

	if strings.EqualFold(wantNet, "bitcoin") {
		if inst, ok := doc["instance"].(map[string]any); ok {
			inst["network"] = "bitcoin"
			inst["env"] = env
			inst["id"] = wl.ID
			doc["instance"] = inst
		} else {
			doc["instance"] = map[string]any{"id": wl.ID, "network": "bitcoin", "env": env}
		}
		doc["network_mismatch"] = false
		doc["snapshot"] = map[string]any{
			"enabled": false, "ready": false, "phase": "idle",
		}
		doc["supported_steps"] = panelSupportedSteps("bitcoin")
		doc["capabilities"] = panelCapabilities("bitcoin")
		if lc, ok := doc["lifecycle"].(map[string]any); ok {
			lc["supported_steps"] = panelSupportedSteps("bitcoin")
			lc["capabilities"] = panelCapabilities("bitcoin")
			if prof, ok := lc["profile"].(map[string]any); ok {
				prof["network"] = "bitcoin"
				prof["supported_steps"] = panelSupportedSteps("bitcoin")
				prof["capabilities"] = panelCapabilities("bitcoin")
				prof["include_snapshot"] = false
				lc["profile"] = prof
			}
			// Lifecycle label/detail/steps come from the agent — do not rewrite copy here.
			doc["lifecycle"] = lc
		}
	}

	// healthz.network may match while status.json still carries a foreign chain
	// lifecycle (stale tron-mainnet state on a BSC leaf). Never paint that.
	stripForeignChainStatus(doc, wantNet, env, wl.ID)

	// SYNCED / verification_pct=100 while lifecycle stuck on Run (ETC Height=0 etc.).
	_ = healStuckRunLifecycle(doc)

	out, err := json.Marshal(doc)
	if err != nil {
		return payload
	}
	return out
}

// statusPayloadChainNetwork — chain claimed by status body (instance / lifecycle),
// NOT top-level healthz overlay.
func statusPayloadChainNetwork(doc map[string]any) string {
	if doc == nil {
		return ""
	}
	if inst, ok := doc["instance"].(map[string]any); ok {
		if n, _ := inst["network"].(string); strings.TrimSpace(n) != "" {
			return strings.ToLower(strings.TrimSpace(n))
		}
	}
	if lc, ok := doc["lifecycle"].(map[string]any); ok {
		if prof, ok := lc["profile"].(map[string]any); ok {
			if n, _ := prof["network"].(string); strings.TrimSpace(n) != "" {
				return strings.ToLower(strings.TrimSpace(n))
			}
		}
	}
	return ""
}

func statusBlobLooksForeignTron(doc map[string]any) bool {
	raw, err := json.Marshal(doc)
	if err != nil {
		return false
	}
	low := strings.ToLower(string(raw))
	return strings.Contains(low, "/data/tron") ||
		strings.Contains(low, "insufficient disk for snapshot") ||
		(strings.Contains(low, "snapshot_error") && strings.Contains(low, "tron"))
}

func shouldStripForeignChain(doc map[string]any, wantNet string) bool {
	if doc == nil || wantNet == "" {
		return false
	}
	// Hard markers: TRON disk gate / snapshot paths on a non-TRON leaf.
	if !strings.EqualFold(wantNet, "tron") && statusBlobLooksForeignTron(doc) {
		return true
	}
	bodyNet := statusPayloadChainNetwork(doc)
	if bodyNet == "" || strings.EqualFold(bodyNet, wantNet) {
		return false
	}
	lc, _ := doc["lifecycle"].(map[string]any)
	ns := strings.ToLower(strFieldMap(lc, "node_status"))
	phase := strings.ToLower(strFieldMap(lc, "phase"))
	if ns == "snapshot_error" || ns == "start_error" || phase == "error" {
		return true
	}
	if snap, ok := doc["snapshot"].(map[string]any); ok {
		if truthy(snap["failed"]) || strings.EqualFold(strFieldMap(snap, "phase"), "error") {
			return true
		}
		if truthy(snap["enabled"]) && !strings.EqualFold(wantNet, "tron") &&
			strings.EqualFold(bodyNet, "tron") {
			return true
		}
	}
	if lc != nil {
		if prof, ok := lc["profile"].(map[string]any); ok {
			if truthy(prof["include_snapshot"]) && strings.EqualFold(bodyNet, "tron") &&
				!strings.EqualFold(wantNet, "tron") {
				return true
			}
		}
	}
	// Stale instance/profile label alone (e.g. bitcoin healthz + leftover profile.tron)
	// — keep agent lifecycle copy.
	return false
}

// stripForeignChainStatus drops lifecycle/snapshot/errors that belong to another
// network than the panel leaf UUID (tip/wrong-env pollution).
func stripForeignChainStatus(doc map[string]any, wantNet, env, nodeID string) {
	if !shouldStripForeignChain(doc, wantNet) {
		return
	}

	doc["network_mismatch"] = false
	doc["needs_provision"] = false
	if h, _ := doc["health"].(string); strings.EqualFold(h, "error") || strings.EqualFold(h, "mismatch") {
		doc["health"] = "degraded"
	}
	doc["error"] = nil
	doc["message"] = ""
	doc["note"] = ""
	doc["snapshot"] = map[string]any{
		"enabled": false, "ready": false, "phase": "idle", "failed": false,
	}
	if inst, ok := doc["instance"].(map[string]any); ok {
		inst["network"] = wantNet
		inst["env"] = env
		inst["id"] = nodeID
		delete(inst, "data_dir")
		delete(inst, "mismatch")
		doc["instance"] = inst
	} else {
		doc["instance"] = map[string]any{"id": nodeID, "network": wantNet, "env": env}
	}
	// Do not invent step copy — clear foreign agent lifecycle entirely.
	delete(doc, "lifecycle")
	doc["node_status"] = ""
	doc["ui_phase"] = ""
	clearAgentForeignError(doc)
}

func clearAgentForeignError(doc map[string]any) {
	for _, key := range []string{"agent", "api_agent"} {
		ag, ok := doc[key].(map[string]any)
		if !ok || ag == nil {
			continue
		}
		errStr, _ := ag["last_error"].(string)
		act, _ := ag["activity"].(string)
		low := strings.ToLower(errStr + " " + act)
		if strings.Contains(low, "/data/tron") ||
			strings.Contains(low, "insufficient disk") ||
			strings.Contains(low, "snapshot_error") {
			ag["last_error"] = ""
			if strings.Contains(strings.ToLower(act), "snapshot") {
				ag["activity"] = "idle"
			}
			if st, _ := ag["status"].(string); strings.EqualFold(st, "error") {
				ag["status"] = "ok"
			}
			doc[key] = ag
		}
	}
}

// workloadPortsConfirmed — panel DB says user already ACK'd ports (UUID nodes included).
func workloadPortsConfirmed(wl WorkloadRef) bool {
	switch strings.ToLower(strings.TrimSpace(wl.Status)) {
	case "ports_confirmed", "ready_to_install", "starting", "start_error",
		"snapshot_running", "snapshot_error", "online":
		return true
	default:
		return false
	}
}

// tryServeHealedPreProvisionStatus — stuck awaiting_ports/ready_to_install while leaf
// agent already serves this network. Dial leaf, heal SQLite status, return live payload.
func (s *Server) tryServeHealedPreProvisionStatus(r *http.Request, wl WorkloadRef) (bool, []byte) {
	if wl.AgentPort <= 0 {
		return false, nil
	}
	st := strings.ToLower(strings.TrimSpace(wl.Status))
	if st != "awaiting_ports" && st != "ready_to_install" {
		return false, nil
	}
	leaf, key := s.agentForWorkload(wl)
	if leaf == "" || key == "" {
		return false, nil
	}
	wantNet := strings.ToLower(strings.TrimSpace(wl.Network))
	env := strings.TrimSpace(wl.Env)
	if env == "" {
		env = "mainnet"
	}
	statusURL := leaf + "/api/status.json?env=" + url.QueryEscape(env)
	if wantNet != "" {
		statusURL += "&network=" + url.QueryEscape(wantNet)
	}
	doc, err := s.getAgentJSON(s.client, statusURL, key)
	if err != nil || doc == nil {
		return false, nil
	}
	if hz, hzErr := s.getAgentJSON(s.client, leaf+"/healthz", key); hzErr == nil && hz != nil {
		if raw, mErr := json.Marshal(doc); mErr == nil {
			if hzRaw, hErr := json.Marshal(hz); hErr == nil {
				merged := mergeAgentIdentityFromHealthz(raw, hzRaw)
				doc = decodeStatusDoc(merged)
			}
		}
	}
	if !leafStatusLooksLive(doc, wantNet) {
		return false, nil
	}
	// Heal SQLite + in-memory workloads so next poll dials leaf (ops UI, not Install).
	next := healWorkloadStatusFromLeafDoc(doc, wl.Status)
	if next != "" && !strings.EqualFold(next, wl.Status) {
		if n, ok, _ := s.db.GetNode(wl.ID); ok {
			n.Status = next
			if _, err := s.db.UpsertNode(n); err != nil {
				log.Printf("heal preprovision status %s: %v", wl.ID, err)
			} else {
				wl.Status = next
				if s.workloads != nil {
					_ = s.workloads.Upsert(wl)
				}
			}
		}
	}
	payload := sanitizeStatusForWorkload(mustJSON(doc), wl, leaf)
	return true, payload
}

func mustJSON(doc map[string]any) []byte {
	b, err := json.Marshal(doc)
	if err != nil {
		return []byte(`{"ok":true}`)
	}
	return b
}

// marshalNeedsProvisionStatus — host Server agent answered; node not on its per-node agent yet.
// Structural Setup shell only. NEVER invent lifecycle.current=install from panel SQLite
// (ports_confirmed is a UX helper — agent lifecycle started_at/finished_at is source of truth).
func marshalNeedsProvisionStatus(doc map[string]any, wl WorkloadRef, agentURL, agentNet, env, wantNet, optionalDetail string) []byte {
	doc["ok"] = true
	doc["degraded"] = false
	doc["health"] = "setup"
	doc["ui_phase"] = "setup"
	doc["needs_provision"] = true
	doc["network_mismatch"] = false
	doc["error"] = nil
	doc["message"] = ""
	doc["note"] = ""
	doc["agent_url"] = agentURL
	doc["panel_network"] = wantNet
	doc["panel_env"] = env
	doc["panel_node_id"] = wl.ID
	doc["view_env"] = env
	if agentNet != "" {
		doc["agent_network"] = agentNet
	}
	doc["instance"] = map[string]any{
		"id": wl.ID, "network": wantNet, "env": env,
		"needs_provision": true,
	}
	// Reflect panel helper status, but do not advance lifecycle steps from it.
	nodeStatus := "awaiting_ports"
	if st := strings.TrimSpace(wl.Status); st != "" {
		nodeStatus = st
	}
	detail := strings.TrimSpace(optionalDetail)
	if detail == "" {
		detail = "Install to check catalog ports on the host"
	}
	portsTitle := "Check ports"
	portsDetail := detail
	if wl.PublicPort > 0 && wl.AgentPort > 0 {
		portsDetail = fmt.Sprintf("public :%d · agent :%d (tip catalog)", wl.PublicPort, wl.AgentPort)
	}
	doc["lifecycle"] = map[string]any{
		"phase":            "ports",
		"label":            "Setup",
		"detail":           detail,
		"busy":             false,
		"node_status":      nodeStatus,
		"supported_steps":  panelSupportedSteps(wantNet),
		"capabilities":     panelCapabilities(wantNet),
		"profile": map[string]any{
			"network":          wantNet,
			"env":              env,
			"include_snapshot": false,
			"supported_steps":  panelSupportedSteps(wantNet),
			"capabilities":     panelCapabilities(wantNet),
		},
		"steps": []any{
			map[string]any{
				"id": "ports", "title": portsTitle, "status": "active",
				"done": false, "detail": portsDetail,
			},
			map[string]any{
				"id": "install", "title": "Install", "status": "pending",
				"detail": "After ports check",
			},
		},
		"current": "ports", "current_step_id": "ports",
	}
	doc["supported_steps"] = panelSupportedSteps(wantNet)
	doc["capabilities"] = panelCapabilities(wantNet)
	doc["snapshot"] = map[string]any{
		"enabled": false, "ready": false, "phase": "idle",
	}
	out, err := json.Marshal(doc)
	if err != nil {
		return nil
	}
	return out
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}

func (s *Server) resolveAgent(r *http.Request) (base, token string) {
	// Prefer workload / node UUID (rewrites to per-node agent_port, e.g. bitcoin :39390).
	// UI may send ?node=<uuid>&server=<server_id> — server URL alone is the host
	// control-plane agent (:39190) whose default instance may still be tron.
	if id := strings.TrimSpace(r.URL.Query().Get("node")); id != "" {
		if wl, ok := s.workloads.Get(id); ok {
			if url, key := s.agentForWorkload(wl); url != "" {
				return url, key
			}
		}
		if n, ok := s.registry.Get(id); ok && n.AgentURL != "" {
			return n.AgentURL, n.AgentKey
		}
	}
	network := strings.TrimSpace(strings.ToLower(r.URL.Query().Get("network")))
	if env := strings.TrimSpace(r.URL.Query().Get("env")); env != "" {
		if wl, ok := s.workloadByNetworkEnv(network, env); ok {
			if url, key := s.agentForWorkload(wl); url != "" {
				return url, key
			}
		}
		if network != "" {
			// Legacy server ids sometimes mirrored network-env; keep as soft fallback.
			want := network + "-" + env
			if n, ok := s.registry.Get(want); ok && n.AgentURL != "" {
				return n.AgentURL, n.AgentKey
			}
		}
		for _, n := range s.registry.List() {
			full, _ := s.registry.Get(n.ID)
			if full.Env == env && full.AgentURL != "" {
				if network == "" || strings.EqualFold(full.Network, network) {
					return full.AgentURL, full.AgentKey
				}
			}
		}
	}
	// Servers page (agent update / version) — only when no node/workload target.
	if id := strings.TrimSpace(r.URL.Query().Get("server")); id != "" {
		if n, ok := s.registry.Get(id); ok && n.AgentURL != "" {
			return n.AgentURL, n.AgentKey
		}
	}
	if s.cfg.DefaultAgentURL != "" {
		return s.cfg.DefaultAgentURL, s.cfg.DefaultAgentToken
	}
	// First registered node as fallback.
	for _, n := range s.registry.List() {
		full, ok := s.registry.Get(n.ID)
		if ok && full.AgentURL != "" {
			return full.AgentURL, full.AgentKey
		}
	}
	return "", ""
}

func (s *Server) workloadByEnv(env string) (WorkloadRef, bool) {
	return s.workloadByNetworkEnv("", env)
}

// workloadByNetworkEnv resolves a chain workload by network+env.
// When env alone is ambiguous (tron+bitcoin mainnet), return false.
func (s *Server) workloadByNetworkEnv(network, env string) (WorkloadRef, bool) {
	env = strings.TrimSpace(env)
	network = strings.TrimSpace(strings.ToLower(network))
	if env == "" {
		return WorkloadRef{}, false
	}

	var matches []WorkloadRef
	for _, wl := range s.workloads.List() {
		if wl.Env != env {
			continue
		}
		if network != "" && !strings.EqualFold(wl.Network, network) {
			continue
		}
		matches = append(matches, wl)
	}
	if len(matches) == 1 {
		return matches[0], true
	}
	// Ambiguous (multiple nodes share env, or multiple of same network/env).
	return WorkloadRef{}, false
}

// agentForWorkload prefers Node Agent API port (not public Go RPC); key from server registry.
func (s *Server) agentForWorkload(wl WorkloadRef) (base, token string) {
	if srv, ok := s.registry.Get(wl.ServerID); ok {
		token = srv.AgentKey
		base = agentControlBase(srv, wl)
		return base, token
	}
	base = strings.TrimRight(strings.TrimSpace(wl.AgentURL), "/")
	if wl.AgentPort > 0 && base != "" {
		base = rewriteHostPort(base, wl.AgentPort)
	}
	return base, token
}

func (s *Server) emptyControlPlaneStatus(r *http.Request) map[string]any {
	items := s.registry.List()
	instances := make([]map[string]any, 0, len(items))
	for _, n := range items {
		instances = append(instances, map[string]any{
			"id": n.ID, "env": n.Env, "network": n.Network, "name": n.Name,
			"registered": true, "agent_url": n.AgentURL, "health": "unknown",
		})
	}
	return map[string]any{
		"ok":         true,
		"degraded":   len(instances) == 0,
		"health":     "panel_only",
		"ui_phase":   "setup",
		"updated_at": time.Now().UTC().Format(time.RFC3339),
		"managed_by": "RpcNode panel",
		"gateway":    "panel",
		"instances":  instances,
		"instance": map[string]any{
			"id": "control-plane", "registered": true, "role": "panel",
		},
		"snapshot": map[string]any{"enabled": false, "ready": false, "phase": "idle"},
		"services": map[string]any{},
		"rpc":      map[string]any{},
		"connect": map[string]any{
			"ready":      false,
			"panel_base": publicBaseFromRequest(r),
			"panel_port": s.cfg.ListenPort,
		},
		"note": "Standalone panel — add a node agent (URL + key) or set PANEL_DEFAULT_AGENT_URL.",
	}
}

func (s *Server) agentStatusClient() *http.Client {
	if s != nil && s.statusClient != nil {
		return s.statusClient
	}
	if s != nil && s.client != nil {
		return s.client
	}
	return http.DefaultClient
}

// freshCollectorStatus serves the last collector snapshot when it is new enough.
// Does NOT mark the node unreachable — collector is the live poller.
func (s *Server) freshCollectorStatus(r *http.Request, maxAge time.Duration) (map[string]any, bool) {
	if s == nil || s.db == nil || r == nil || maxAge <= 0 {
		return nil, false
	}
	wl, ok := s.workloadFromProxyRequest(r)
	if !ok {
		return nil, false
	}
	st, found, err := s.db.GetNodeStatus(wl.ID)
	if err != nil || !found || strings.TrimSpace(st.RawJSON) == "" {
		return nil, false
	}
	if st.CollectedAt.IsZero() || time.Since(st.CollectedAt) > maxAge {
		return nil, false
	}
	doc := map[string]any{}
	if err := json.Unmarshal([]byte(st.RawJSON), &doc); err != nil || doc == nil {
		return nil, false
	}
	doc["ok"] = true
	doc["source"] = "collector"
	doc["panel_node_id"] = wl.ID
	doc["panel_network"] = wl.Network
	doc["panel_env"] = wl.Env
	if !st.CollectedAt.IsZero() {
		at := st.CollectedAt.UTC().Format(time.RFC3339)
		doc["served_at"] = at
		doc["collector_at"] = at
	}
	return doc, true
}

// cachedStatusPayload returns the last good SQLite node_status snapshot annotated
// as agent-unreachable. Prefer live agent when up; when down, never invent zeros.
func (s *Server) cachedStatusPayload(r *http.Request, agentURL string, reachErr error) (map[string]any, bool) {
	if s == nil || s.db == nil {
		return nil, false
	}
	wl, ok := s.workloadFromProxyRequest(r)
	if !ok {
		return nil, false
	}
	st, found, err := s.db.GetNodeStatus(wl.ID)
	if err != nil || !found {
		return nil, false
	}
	hasRaw := strings.TrimSpace(st.RawJSON) != ""
	hasSummary := strings.TrimSpace(st.Phase) != "" || st.Height != nil || strings.TrimSpace(st.Label) != ""
	if !hasRaw && !hasSummary {
		return nil, false
	}

	msg := "agent_unreachable"
	if reachErr != nil && strings.TrimSpace(reachErr.Error()) != "" {
		msg = reachErr.Error()
	}
	_ = s.db.MarkNodeUnreachable(wl.ID, msg)

	doc := map[string]any{}
	if hasRaw {
		if err := json.Unmarshal([]byte(st.RawJSON), &doc); err != nil || doc == nil {
			doc = map[string]any{}
		}
	}
	if len(doc) == 0 {
		doc = map[string]any{
			"lifecycle": map[string]any{
				"phase":       st.Phase,
				"label":       st.Label,
				"detail":      st.Detail,
				"node_status": firstNonEmpty(wl.Status, st.Phase),
				"busy":        false,
			},
			"health":   firstNonEmpty(st.Health, "unknown"),
			"ui_phase": st.Phase,
		}
		if st.Height != nil {
			doc["rpc"] = map[string]any{"node_height": *st.Height}
		}
		if st.SnapshotPct != nil {
			doc["snapshot"] = map[string]any{"pct": *st.SnapshotPct, "progress_pct": *st.SnapshotPct}
		}
	}

	// Annotate without clearing lifecycle / rpc / sync / logs from the snapshot.
	doc["ok"] = true
	doc["degraded"] = true
	doc["agent_reachable"] = false
	doc["cached"] = true
	doc["error"] = "agent_unreachable"
	doc["message"] = msg
	doc["note"] = "Agent unreachable — showing last known status from panel cache."
	doc["updated_at"] = time.Now().UTC().Format(time.RFC3339)
	if !st.CollectedAt.IsZero() {
		doc["cached_at"] = st.CollectedAt.UTC().Format(time.RFC3339)
		doc["served_at"] = st.CollectedAt.UTC().Format(time.RFC3339)
	}
	if agentURL != "" {
		doc["agent_url"] = agentURL
	} else if wl.AgentURL != "" {
		doc["agent_url"] = wl.AgentURL
	}
	doc["panel_node_id"] = wl.ID
	doc["panel_network"] = wl.Network
	doc["panel_env"] = wl.Env
	doc["view_env"] = firstNonEmpty(strFieldMap(doc, "view_env"), wl.Env)
	if inst, ok := doc["instance"].(map[string]any); ok {
		inst["id"] = wl.ID
		if _, has := inst["network"]; !has && wl.Network != "" {
			inst["network"] = wl.Network
		}
		if _, has := inst["env"]; !has && wl.Env != "" {
			inst["env"] = wl.Env
		}
		doc["instance"] = inst
	} else {
		doc["instance"] = map[string]any{
			"id": wl.ID, "network": wl.Network, "env": wl.Env,
		}
	}
	doc["agent"] = map[string]any{
		"status": "error", "activity": "unreachable", "last_error": msg,
	}

	// Fresh Add / never Confirm ports — cached error must not hide NODE SETUP.
	if !workloadLooksProvisioned(wl) ||
		strings.EqualFold(strings.TrimSpace(wl.Status), "awaiting_ports") ||
		strings.EqualFold(strings.TrimSpace(wl.Status), "ports_confirmed") {
		var out map[string]any
		if err := json.Unmarshal(marshalNeedsProvisionStatus(
			doc, wl, agentURL, "", wl.Env, strings.ToLower(strings.TrimSpace(wl.Network)), msg,
		), &out); err == nil && out != nil {
			out["agent_reachable"] = false
			out["cached"] = true
			out["error"] = "agent_unreachable"
			out["message"] = msg
			return out, true
		}
	}

	// Cache must obey the same SYNCED⇒complete invariant as live sanitize.
	// Stale raw_json can keep lifecycle at ports while sync/detail already Synced.
	_ = healStuckRunLifecycle(doc)
	if leafHonestlySynced(doc) {
		if next := healWorkloadStatusFromLeafDoc(doc, wl.Status); next != "" &&
			!strings.EqualFold(next, wl.Status) {
			if n, ok, _ := s.db.GetNode(wl.ID); ok {
				n.Status = next
				if _, err := s.db.UpsertNode(n); err == nil {
					wl.Status = next
					if s.workloads != nil {
						_ = s.workloads.Upsert(wl)
					}
				}
			}
		}
	}

	return doc, true
}

// panelSupportedSteps — static lifecycle ids when agent has not declared them yet
// (pre-provision / mismatch). Keep aligned with system-agent NetworkProfile.ExtraSteps.
func panelSupportedSteps(network string) []string {
	n := strings.ToLower(strings.TrimSpace(network))
	if n == "robinhood" || n == "tron" || n == "sui" {
		return []string{"ports", "install", "snapshot", "start", "run"}
	}
	if n == "bitcoin" || n == "solana" || n == "ethereum" || n == "bsc" ||
		n == "hyperliquid" || n == "arb" || n == "optimism" || n == "base" ||
		n == "stellar" || n == "cardano" || n == "doge" || n == "ltc" ||
		n == "dash" || n == "bch" || n == "xrpl" || n == "ton" || n == "etc" ||
		n == "zcash" {
		return []string{"ports", "install", "start", "run"}
	}
	// Unknown default: include snapshot bootstrap (safe for TRON-shaped setups).
	return []string{"ports", "install", "snapshot", "start", "run"}
}

func panelCapabilities(network string) map[string]bool {
	n := strings.ToLower(strings.TrimSpace(network))
	snap := n == "tron" || n == "robinhood" || n == "sui"
	return map[string]bool{
		"snapshot": snap,
		"ibd": n == "bitcoin" || n == "ethereum" || n == "bsc" ||
			n == "hyperliquid" || n == "arb" || n == "robinhood" || n == "optimism" || n == "base" ||
			n == "xrpl" || n == "doge" || n == "ltc" || n == "dash" ||
			n == "bch" || n == "cardano" || n == "stellar" || n == "ton" || n == "etc" ||
			n == "zcash" || n == "sui",
		"one_env_per_host": networkOneEnvPerHost(n),
	}
}

// networkOneEnvPerHost — panel-side mirror of agent network_constraints (HL / TON host singleton).
func networkOneEnvPerHost(network string) bool {
	switch strings.ToLower(strings.TrimSpace(network)) {
	case "hyperliquid", "ton":
		return true
	default:
		return false
	}
}

// nodeOnServer — exact server + network + env (any status). Used to block duplicate Add node.
func (s *Server) nodeOnServer(serverID, network, env string) (WorkloadRef, bool) {
	serverID = strings.TrimSpace(serverID)
	network = strings.ToLower(strings.TrimSpace(network))
	env = strings.TrimSpace(env)
	if serverID == "" || network == "" || env == "" {
		return WorkloadRef{}, false
	}
	for _, wl := range s.workloads.List() {
		if wl.ServerID != serverID {
			continue
		}
		if !strings.EqualFold(wl.Network, network) {
			continue
		}
		if !strings.EqualFold(strings.TrimSpace(wl.Env), env) {
			continue
		}
		return wl, true
	}
	return WorkloadRef{}, false
}

// otherEnvOnServer — if network is one_env_per_host and another env already exists on server.
func (s *Server) otherEnvOnServer(serverID, network, wantEnv string) (WorkloadRef, bool) {
	if !networkOneEnvPerHost(network) {
		return WorkloadRef{}, false
	}
	serverID = strings.TrimSpace(serverID)
	network = strings.ToLower(strings.TrimSpace(network))
	wantEnv = strings.TrimSpace(wantEnv)
	for _, wl := range s.workloads.List() {
		if wl.ServerID != serverID {
			continue
		}
		if !strings.EqualFold(wl.Network, network) {
			continue
		}
		if strings.EqualFold(strings.TrimSpace(wl.Env), wantEnv) {
			continue
		}
		if strings.EqualFold(wl.Status, "removing") {
			continue
		}

		return wl, true
	}

	return WorkloadRef{}, false
}

func publicBaseFromRequest(r *http.Request) string {
	host := strings.TrimSpace(r.Host)
	if xfh := strings.TrimSpace(r.Header.Get("X-Forwarded-Host")); xfh != "" {
		host = strings.TrimSpace(strings.Split(xfh, ",")[0])
	}
	if host == "" {
		return ""
	}
	proto := "http"
	if xf := r.Header.Get("X-Forwarded-Proto"); xf != "" {
		proto = strings.TrimSpace(strings.Split(xf, ",")[0])
	}
	return proto + "://" + host
}
