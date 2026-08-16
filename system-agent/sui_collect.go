package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// collectSui — formal snapshot (sui-tool) then checkpoint catch-up.
// Snapshot % from sui-tool journal/log; catch-up = lag-closed vs peak (❌ me/tip).
// Primary UI after snapshot: checkpoints behind.
func collectSui(cfg Config) map[string]any {
	network := cfg.Network
	if network == "" {
		network = "sui"
	}
	prof := LookupNetworkProfile(network, cfg.Env)

	nodeState := systemctlActive(cfg.NodeService)
	procOK, _ := suiNodeRunningFor(cfg)
	startErr, startBad := suiStartFailureDetail(cfg, procOK)
	nodeActive := procOK || nodeState == "active" || nodeState == "activating"
	if startBad {
		nodeActive = false
	}

	metricsPort := suiMetricsPort(cfg, prof)
	var synced, known, tip int64
	var metricsOK, rpcOK bool
	var clientVer string
	var nodePortOpen bool

	if nodeActive {
		nodePortOpen = portOpen(cfg.UpstreamHost, cfg.UpstreamPort)
		synced, known, metricsOK = scrapeSuiCheckpointMetrics(cfg, metricsPort)
		if nodePortOpen {
			localTip, ok := suiLocalCheckpointRPC(cfg)
			if ok && localTip > synced {
				synced = localTip
			}
			rpcOK = ok || metricsOK
			clientVer = suiClientVersion(cfg)
		}
		tip = suiPublicTipCheckpoint(cfg)
	}

	behind := int64(0)
	catchingUp := false
	if tip > 0 && synced >= 0 {
		behind = tip - synced
		if behind < 0 {
			behind = 0
		}
		catchingUp = behind > 32 // small lag = caught up for UI
	} else if known > synced && synced >= 0 {
		behind = known - synced
		catchingUp = behind > 32
	}
	// Genesis / pre-metrics: RPC may answer checkpoint 0 while tip probe fails
	// (Mysten public JSON-RPC deprecated). Never treat as healthy — keep catch-up.
	if nodeActive && (rpcOK || metricsOK) && synced == 0 {
		catchingUp = true
		if tip > 0 && behind == 0 {
			behind = tip
		}
	}
	healthy := rpcOK && !catchingUp && synced > 0

	verifyPct, verifyOK := suiVerificationPct(cfg, healthy, catchingUp, behind)
	if !verifyOK {
		verifyPct = 0
	}

	nodeSvcEffective := nodeState
	switch {
	case startBad:
		nodeSvcEffective = "failed"
	case nodeActive && healthy:
		nodeSvcEffective = "active"
	case nodeActive || nodePortOpen:
		if nodeState != "active" {
			nodeSvcEffective = "activating"
		} else {
			nodeSvcEffective = "active"
		}
	}

	agentPort := cfg.AgentAPIPort()
	apiProbePort := agentPort
	if apiProbePort <= 0 {
		apiProbePort = cfg.PublicRPCPort()
	}
	apiPortOpen := apiProbePort > 0 && portOpen("127.0.0.1", apiProbePort)
	apiHealth := apiProbePort > 0 && httpOK(fmt.Sprintf("http://127.0.0.1:%d/healthz", apiProbePort))
	apiSvc := "inactive"
	if apiHealth || apiPortOpen {
		apiSvc = "active"
	} else if g := systemctlActive(cfg.APIService); g == "active" {
		apiSvc = g
	}

	instRegistered := fileExists(cfg.RegistryFile) || fileExists(cfg.InstanceFile)
	apiUp := apiHealth || apiPortOpen
	publicPort := cfg.PublicRPCPort()
	publicPortOpen := publicPort > 0 && portOpen("127.0.0.1", publicPort)
	agentPortOpen := apiProbePort > 0 && (apiPortOpen || apiHealth)

	diskOK, freeGiB, needGiB, diskDetail := suiDiskGateOK(cfg, prof)

	// Formal snapshot (sui-tool) — always on for SnapshotRequired even if older
	// toolkit.env still has TRON_SNAPSHOT_ENABLED=0.
	wantsSnap := prof.SnapshotPolicy != SnapshotNever || prof.HasExtra(StepSnapshot)
	snapEnabled := wantsSnap
	if snapEnabled && strings.TrimSpace(cfg.SnapshotURL) == "" {
		cfg.SnapshotURL = prof.DefaultSnapshotURL
	}
	snapMarker := fileExists(cfg.SnapshotMarker)
	snapState := readJSONFile(cfg.SnapshotState)
	snapPhase, _ := snapState["phase"].(string)
	snapDetail, _ := snapState["detail"].(string)
	snapErr, _ := snapState["error"].(string)
	snapUnitState := systemctlActive(cfg.SnapshotService)
	// oneshot download spends a long time in "activating (start)" — count as busy.
	snapUnitActive := snapUnitState == "active" || snapUnitState == "activating"
	snapUnitFailed := systemctlFailed(cfg.SnapshotService)
	toolRunning := suiToolSnapshotRunning(cfg)
	snapPct, snapPctOK := suiFormalSnapshotPct(cfg)
	if snapEnabled && !snapMarker && (snapUnitActive || toolRunning) {
		snapPhase = "download"
		if snapDetail == "" {
			snapDetail = "Formal snapshot · sui-tool download-formal-snapshot"
		}
	}
	if snapEnabled && !snapMarker && synced > 32 && !snapUnitActive && !toolRunning {
		_ = writeFileAtomic(cfg.SnapshotMarker, []byte("ok\n"))
		snapMarker = true
		snapPhase = "done"
		snapDetail = "checkpoint advance (snapshot marker healed)"
	}
	snapBusy := snapEnabled && !snapMarker && !strings.EqualFold(snapPhase, "error") &&
		(snapUnitActive || toolRunning || strings.EqualFold(snapPhase, "download") || snapPctOK ||
			(snapEnabled && !snapMarker && !snapUnitFailed))
	// Live download wins over stale Start() error (tip Update / host-CLI fallback noise).
	if snapBusy && (snapUnitActive || toolRunning) {
		stale := strings.Contains(strings.ToLower(snapErr), "tronctl") ||
			strings.Contains(strings.ToLower(snapErr), "rpcnodectl") ||
			strings.Contains(strings.ToLower(snapErr), "terminated") ||
			strings.Contains(strings.ToLower(snapDetail), "tronctl") ||
			strings.Contains(strings.ToLower(snapDetail), "rpcnodectl") ||
			strings.Contains(strings.ToLower(snapDetail), "terminated") ||
			strings.EqualFold(snapPhase, "error")
		if stale {
			snapErr = ""
			snapPhase = "download"
			snapDetail = "Formal snapshot · sui-tool download-formal-snapshot"
			_ = writeJSONFile(cfg.SnapshotState, map[string]any{
				"phase": "download", "pct": snapPct, "detail": snapDetail,
				"updated_at": time.Now().UTC().Format(time.RFC3339),
			})
		}
	}
	snapFailed := snapEnabled && !snapMarker && !snapBusy &&
		(snapUnitFailed || strings.EqualFold(snapPhase, "error") || snapErr != "")
	snapPctStr := ""
	if snapMarker {
		snapPctStr = "100"
		snapPct = 100
		snapPctOK = true
	} else if snapPctOK {
		snapPctStr = fmt.Sprintf("%.1f", snapPct)
	} else if snapBusy {
		snapPctStr = "0"
		snapPct = 0
		snapPctOK = true
	}
	if snapBusy && snapPctOK {
		verifyPct = snapPct
		verifyOK = true
	}

	prog := loadLifecycleProgress(cfg)
	lcIn := nodeLifecycleInput{
		Network:        network,
		Env:            cfg.Env,
		PublicPort:     publicPort,
		AgentPort:      agentPort,
		UpstreamPort:   cfg.UpstreamPort,
		PublicPortOpen: publicPortOpen,
		AgentPortOpen:  agentPortOpen,
		InstRegistered: instRegistered,
		APIUp:          apiUp,
		SnapEnabled:    snapEnabled,
		Marker:         snapMarker,
		SnapBusy:       snapBusy,
		SnapFailed:     snapFailed,
		SnapPhase:      snapPhase,
		SnapDetail:     snapDetail,
		SnapErr:        snapErr,
		Pct:            snapPctStr,
		NodeActive:     nodeActive && !snapBusy,
		StartError:     startErr,
		RPCOK:          rpcOK || metricsOK,
		IBD:            catchingUp && (!snapEnabled || snapMarker),
		VerifyPct:      verifyPct / 100,
		Progress:       prog,
	}
	if synced > 0 {
		lcIn.Height = synced
	}
	lifecycle := buildNodeLifecycle(lcIn)
	saveLifecycleProgress(cfg, prog)

	uiPhase, _ := lifecycle["phase"].(string)
	if uiPhase == "" {
		uiPhase = "setup"
	}
	nodeStatus, _ := lifecycle["node_status"].(string)
	if nodeStatus == "" {
		nodeStatus = "unknown"
	}

	syncDetail := ""
	switch {
	case snapBusy:
		syncDetail = "Formal snapshot download"
		if snapDetail != "" {
			syncDetail = snapDetail
		}
		if snapPctOK {
			syncDetail = fmt.Sprintf("%s · %s", syncDetail, formatCoreSyncPct(verifyPct))
		}
	case !nodeActive:
		syncDetail = "sui-node not running"
	case healthy:
		syncDetail = fmt.Sprintf("Synced · checkpoint %d", synced)
		clearSuiCatchupMaxBehind(cfg)
	case catchingUp:
		if synced == 0 && tip > 32 && snapEnabled && !snapMarker {
			syncDetail = fmt.Sprintf("Genesis stall · checkpoint 0 · tip %d · waiting for formal snapshot", tip)
		} else if synced == 0 && tip > 32 {
			syncDetail = fmt.Sprintf("Genesis stall · checkpoint 0 · tip %d · formal snapshot required", tip)
		} else {
			syncDetail = fmt.Sprintf("Catch-up · %d checkpoints behind · local %d", behind, synced)
			if tip > 0 {
				syncDetail = fmt.Sprintf("%s · tip %d", syncDetail, tip)
			}
		}
		if verifyOK {
			syncDetail = fmt.Sprintf("%s · %s", syncDetail, formatCoreSyncPct(verifyPct))
		}
	default:
		syncDetail = "Waiting for metrics/RPC"
	}

	setupSteps := []map[string]any{
		{"id": "registry", "title": "Instance registered", "done": instRegistered,
			"detail": "INSTANCE.json + /etc/rpcnode/instances.d"},
		{"id": "disk", "title": "Disk floor for fullnode", "done": diskOK,
			"detail": diskDetail, "active": !diskOK && apiUp},
		{"id": "snapshot", "title": "Formal snapshot", "done": !snapEnabled || snapMarker,
			"detail": firstNonEmptyStr(snapDetail, "sui-tool download-formal-snapshot --no-sign-request"),
			"active": snapBusy, "pct": map[bool]any{true: snapPct, false: nil}[snapBusy && snapPctOK]},
		{"id": "node", "title": "sui-node running", "done": nodeActive && !snapBusy,
			"detail": "process/systemd", "active": apiUp && !nodeActive && !snapBusy},
		{"id": "rpc", "title": "JSON-RPC / metrics", "done": rpcOK || metricsOK,
			"detail": "checkpoint metrics + sui_getLatestCheckpointSequenceNumber",
			"active": nodeActive && !(rpcOK || metricsOK) && !snapBusy},
		{"id": "sync", "title": "Checkpoint catch-up", "done": healthy,
			"detail": syncDetail, "active": catchingUp && !snapBusy,
			"pct": map[bool]any{true: verifyPct, false: nil}[catchingUp && verifyOK && !snapBusy]},
		{"id": "api", "title": "API agent up", "done": apiUp,
			"detail": fmt.Sprintf(":%d /healthz", apiProbePort)},
	}

	logTail := strings.Split(journalUnitSnippet(cfg.NodeService, 80), "\n")
	if snapBusy || snapFailed {
		if snip := journalUnitSnippet(cfg.SnapshotService, 80); snip != "" {
			logTail = strings.Split(snip, "\n")
		}
	}

	return map[string]any{
		"ok": true, "ts": time.Now().UTC().Format(time.RFC3339),
		"network": network, "env": cfg.Env,
		"ui_phase": uiPhase, "node_status": nodeStatus,
		"lifecycle":   lifecycle,
		"setup_steps": setupSteps,
		"disk_gate": map[string]any{
			"ok": diskOK, "free_gib": freeGiB, "need_gib": needGiB, "detail": diskDetail,
		},
		"snapshot": map[string]any{
			"enabled": snapEnabled, "ready": snapMarker, "busy": snapBusy, "failed": snapFailed,
			"pct": snapPct, "phase": snapPhase, "detail": snapDetail, "error": snapErr,
			"url": cfg.SnapshotURL, "wget_running": toolRunning || snapUnitActive,
			"service": cfg.SnapshotService,
		},
		"sync": map[string]any{
			"ok": healthy, "syncing": catchingUp || snapBusy, "ibd": catchingUp && !snapBusy,
			"checkpoint": synced, "highest_synced_checkpoint": synced,
			"highest_known_checkpoint": known, "tip_checkpoint": tip,
			"behind": behind, "checkpoints_behind": behind,
			"verification_pct": verifyPct, "detail": syncDetail,
			"network": network, "log_tail": logTail,
		},
		"rpc": map[string]any{
			"ok": rpcOK || metricsOK, "checkpoint": synced,
			"behind": behind, "verification_pct": verifyPct,
			"client_version": clientVer,
		},
		"services": map[string]any{
			"node": nodeSvcEffective, "api": apiSvc,
			"snapshot": systemctlActive(cfg.SnapshotService),
		},
		"checks": map[string]any{
			"node_process_up": procOK, "sui_node_process": procOK,
			"node_port_open": nodePortOpen, "metrics_ok": metricsOK,
			"snapshot_marker": snapMarker, "sui_tool_running": toolRunning,
		},
		"ports": map[string]any{
			"public": publicPort, "agent": agentPort, "node_http": cfg.UpstreamPort,
			"metrics": metricsPort, "p2p": LookupNetworkProfile(network, cfg.Env).DefaultP2PPort,
		},
		"start_error": startErr,
		"logs": map[string]any{
			"title":  "Logs",
			"source": map[bool]string{true: "sui-snapshot", false: "sui-sync"}[snapBusy || snapFailed],
			"lines":  logTail,
		},
		"version":        agentVersion(),
		"client_version": clientVer,
	}
}

func suiMetricsPort(cfg Config, prof NetworkProfile) int {
	if v := envOr("TRON_METRICS_PORT", ""); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return n
		}
	}
	if strings.EqualFold(cfg.Env, "testnet") {
		return 9185
	}
	if prof.DefaultNodeHTTP == 9001 {
		return 9185
	}

	return 9184
}

func scrapeSuiCheckpointMetrics(cfg Config, metricsPort int) (synced, known int64, ok bool) {
	if metricsPort <= 0 {
		return 0, 0, false
	}
	url := envOr("SUI_METRICS_URL", fmt.Sprintf("http://127.0.0.1:%d/metrics", metricsPort))
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return 0, 0, false
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return 0, 0, false
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return 0, 0, false
	}
	sc := bufio.NewScanner(io.LimitReader(resp.Body, 4<<20))
	for sc.Scan() {
		ln := strings.TrimSpace(sc.Text())
		if ln == "" || strings.HasPrefix(ln, "#") {
			continue
		}
		name, val, parsed := parsePromSample(ln)
		if !parsed {
			continue
		}
		switch name {
		case "highest_synced_checkpoint":
			synced = val
			ok = true
		case "highest_known_checkpoint", "highest_verified_checkpoint":
			if val > known {
				known = val
			}
			ok = true
		}
	}

	return synced, known, ok
}

func parsePromSample(line string) (name string, value int64, ok bool) {
	// metric{labels} value  OR  metric value
	space := strings.LastIndex(line, " ")
	if space <= 0 {
		return "", 0, false
	}
	left := line[:space]
	right := strings.TrimSpace(line[space+1:])
	f, err := strconv.ParseFloat(right, 64)
	if err != nil {
		return "", 0, false
	}
	name = left
	if i := strings.IndexByte(left, '{'); i >= 0 {
		name = left[:i]
	}
	name = strings.TrimSpace(name)
	if name == "" {
		return "", 0, false
	}

	return name, int64(f), true
}

func suiLocalCheckpointRPC(cfg Config) (int64, bool) {
	n, err := suiJSONRPCNumber(cfg, "sui_getLatestCheckpointSequenceNumber", nil)
	if err != nil {
		return 0, false
	}

	return n, true
}

func suiPublicTipCheckpoint(cfg Config) int64 {
	if n := suiTipFromJSONRPCURLs(suiPublicTipRPCCandidates(cfg)); n > 0 {
		return n
	}
	if n, err := suiGraphQLTipCheckpoint(cfg); err == nil && n > 0 {
		return n
	}

	return 0
}

func suiTipFromJSONRPCURLs(urls []string) int64 {
	for _, tipURL := range urls {
		tipURL = strings.TrimSpace(tipURL)
		if tipURL == "" {
			continue
		}
		if n, err := suiJSONRPCNumberURL(tipURL, "sui_getLatestCheckpointSequenceNumber", nil); err == nil && n > 0 {
			return n
		}
	}

	return 0
}

// suiPublicTipRPCCandidates — Mysten fullnode.*.sui.io JSON-RPC is deprecated
// (method not found). Prefer configured URL, then third-party JSON-RPC, then GraphQL.
func suiPublicTipRPCCandidates(cfg Config) []string {
	seen := map[string]bool{}
	var out []string
	add := func(u string) {
		u = strings.TrimSpace(u)
		if u == "" || seen[u] {
			return
		}
		seen[u] = true
		out = append(out, u)
	}
	add(envOr("SUI_PUBLIC_TIP_RPC", ""))
	if b, err := os.ReadFile(filepath.Join(cfg.EtcDir, "public_tip.url")); err == nil {
		add(string(b))
	}
	if strings.EqualFold(cfg.Env, "testnet") {
		add("https://fullnode.testnet.sui.io:443")
		add("https://rpc-testnet.suiscan.xyz")
	} else {
		add("https://fullnode.mainnet.sui.io:443")
		add("https://rpc-mainnet.suiscan.xyz")
		add("https://mainnet.sui.rpcpool.com")
		add("https://sui-mainnet-endpoint.blockvision.org")
	}

	return out
}

func suiGraphQLTipURL(cfg Config) string {
	if u := strings.TrimSpace(envOr("SUI_PUBLIC_TIP_GRAPHQL", "")); u != "" {
		return u
	}
	if strings.EqualFold(cfg.Env, "testnet") {
		return "https://graphql.testnet.sui.io/graphql"
	}

	return "https://graphql.mainnet.sui.io/graphql"
}

func suiGraphQLTipCheckpoint(cfg Config) (int64, error) {
	url := suiGraphQLTipURL(cfg)
	body := []byte(`{"query":"query { checkpoint { sequenceNumber } }"}`)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return 0, err
	}
	var envelope struct {
		Data *struct {
			Checkpoint *struct {
				SequenceNumber any `json:"sequenceNumber"`
			} `json:"checkpoint"`
		} `json:"data"`
		Errors any `json:"errors"`
	}
	if err := json.Unmarshal(b, &envelope); err != nil {
		return 0, err
	}
	if envelope.Errors != nil && fmt.Sprint(envelope.Errors) != "<nil>" && fmt.Sprint(envelope.Errors) != "null" {
		return 0, fmt.Errorf("graphql errors: %v", envelope.Errors)
	}
	if envelope.Data == nil || envelope.Data.Checkpoint == nil {
		return 0, fmt.Errorf("graphql: empty checkpoint")
	}
	switch v := envelope.Data.Checkpoint.SequenceNumber.(type) {
	case float64:
		return int64(v), nil
	case string:
		return strconv.ParseInt(strings.TrimSpace(v), 10, 64)
	case json.Number:
		return v.Int64()
	default:
		return 0, fmt.Errorf("graphql sequenceNumber type %T", v)
	}
}

func suiJSONRPCNumber(cfg Config, method string, params []any) (int64, error) {
	url := fmt.Sprintf("http://%s:%d", cfg.UpstreamHost, cfg.UpstreamPort)

	return suiJSONRPCNumberURL(url, method, params)
}

func suiJSONRPCNumberURL(url, method string, params []any) (int64, error) {
	if params == nil {
		params = []any{}
	}
	body := map[string]any{
		"jsonrpc": "2.0",
		"id":      1,
		"method":  method,
		"params":  params,
	}
	raw, err := json.Marshal(body)
	if err != nil {
		return 0, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(raw))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return 0, err
	}
	var envelope struct {
		Result any `json:"result"`
		Error  any `json:"error"`
	}
	if err := json.Unmarshal(b, &envelope); err != nil {
		return 0, err
	}
	if envelope.Error != nil && fmt.Sprint(envelope.Error) != "<nil>" && fmt.Sprint(envelope.Error) != "null" {
		return 0, fmt.Errorf("rpc error: %v", envelope.Error)
	}
	switch v := envelope.Result.(type) {
	case float64:
		return int64(v), nil
	case string:
		n, err := strconv.ParseInt(strings.TrimSpace(v), 10, 64)
		return n, err
	case json.Number:
		n, err := v.Int64()
		return n, err
	default:
		return 0, fmt.Errorf("unexpected result type %T", envelope.Result)
	}
}

func suiClientVersion(cfg Config) string {
	bin := filepath.Join(cfg.OptDir, "bin", "sui-node")
	if !fileExists(bin) {
		bin = "sui-node"
	}
	out, err := runCmd(3*time.Second, bin, "--version")
	if err != nil {
		return ""
	}
	s := strings.TrimSpace(out)
	if s == "" {
		return ""
	}
	// Keep first line, strip path noise.
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		s = s[:i]
	}

	return formatClientVersion(s)
}

func suiVerificationPct(cfg Config, healthy, catchingUp bool, behind int64) (float64, bool) {
	if healthy {
		clearSuiCatchupMaxBehind(cfg)

		return 100, true
	}
	if catchingUp {
		return suiCatchupLagClosedPct(cfg, behind)
	}

	return 0, false
}

func suiCatchupLagClosedPct(cfg Config, behind int64) (float64, bool) {
	if behind < 0 {
		return 0, false
	}
	if behind == 0 {
		return 99.9, true
	}
	maxBehind := loadSuiCatchupMaxBehind(cfg)
	if behind > maxBehind {
		maxBehind = behind
		saveSuiCatchupMaxBehind(cfg, maxBehind)
	}
	if maxBehind <= 0 {
		return 0, false
	}
	pct := float64(maxBehind-behind) / float64(maxBehind) * 100
	if pct > 99.9 {
		pct = 99.9
	}
	if pct < 0.1 {
		pct = 0.1
	}
	pct = float64(int(pct*10+0.5)) / 10

	return pct, true
}

func suiCatchupStatePath(cfg Config) string {
	return filepath.Join(filepath.Dir(cfg.StateFile), "sui-catchup.json")
}

func loadSuiCatchupMaxBehind(cfg Config) int64 {
	doc := readJSONFile(suiCatchupStatePath(cfg))
	if doc == nil {
		return 0
	}
	switch v := doc["max_behind"].(type) {
	case float64:
		return int64(v)
	case int64:
		return v
	case int:
		return int64(v)
	default:
		return 0
	}
}

func saveSuiCatchupMaxBehind(cfg Config, maxBehind int64) {
	if maxBehind <= 0 || strings.TrimSpace(cfg.StateFile) == "" {
		return
	}
	_ = ensureDir(filepath.Dir(cfg.StateFile))
	_ = writeJSONFile(suiCatchupStatePath(cfg), map[string]any{
		"max_behind": maxBehind,
		"updated_at": time.Now().UTC().Format(time.RFC3339),
	})
}

func clearSuiCatchupMaxBehind(cfg Config) {
	if strings.TrimSpace(cfg.StateFile) == "" {
		return
	}
	_ = os.Remove(suiCatchupStatePath(cfg))
}

var (
	reSuiFilesDone = regexp.MustCompile(`(?i)(\d+)\s+out of\s+(\d+)\s+files?\s+done`)
	// Require whitespace before the number so "87.5%" is not parsed as "5%".
	reSuiPctBare = regexp.MustCompile(`(?i)(?:download|restor\w*|snapshot)[^\n%]{0,80}\s(\d{1,3}(?:\.\d+)?)\s*%`)
)

func suiToolSnapshotRunning(cfg Config) bool {
	data := strings.TrimSpace(cfg.DataDir)
	out, err := runCmd(2*time.Second, "bash", "-lc",
		`pgrep -af 'sui-tool' | grep -F 'download-formal-snapshot' | grep -v grep | head -1`)
	if err != nil || strings.TrimSpace(out) == "" {
		return false
	}
	if data != "" && !strings.Contains(out, data) && !strings.Contains(out, "sui") {
		return false
	}

	return true
}

// suiFormalSnapshotPct — honest % from state json, snapshot log, or journal.
func suiFormalSnapshotPct(cfg Config) (float64, bool) {
	if st := readJSONFile(cfg.SnapshotState); st != nil {
		switch v := st["pct"].(type) {
		case float64:
			if v >= 0 && v <= 100 {
				return v, true
			}
		case int:
			if v >= 0 && v <= 100 {
				return float64(v), true
			}
		case string:
			if p, err := strconv.ParseFloat(strings.TrimSpace(v), 64); err == nil && p >= 0 && p <= 100 {
				return p, true
			}
		}
	}
	texts := []string{}
	if p := strings.TrimSpace(cfg.SnapshotLog); p != "" {
		if b, err := os.ReadFile(p); err == nil && len(b) > 0 {
			texts = append(texts, string(b))
		}
	}
	if snip := journalUnitGrepSnippet(cfg.SnapshotService, 60, `out of|%|download-formal`); snip != "" {
		texts = append(texts, snip)
	}
	var best float64
	var ok bool
	for _, t := range texts {
		if p, hit := parseSuiFormalSnapshotProgress(t); hit && (!ok || p >= best) {
			best, ok = p, true
		}
	}

	return best, ok
}

func parseSuiFormalSnapshotProgress(text string) (float64, bool) {
	lines := strings.Split(text, "\n")
	var best float64
	var ok bool
	for _, ln := range lines {
		if m := reSuiFilesDone.FindStringSubmatch(ln); len(m) == 3 {
			x, y := parseCommaInt64(m[1]), parseCommaInt64(m[2])
			if y > 0 && x >= 0 {
				p := float64(x) / float64(y) * 100
				if p > 99.9 {
					p = 99.9
				}
				if !ok || p >= best {
					best, ok = p, true
				}
			}
		}
		if m := reSuiPctBare.FindStringSubmatch(ln); len(m) == 2 {
			if p, err := strconv.ParseFloat(m[1], 64); err == nil && p >= 0 && p <= 100 {
				if p > 99.9 {
					p = 99.9
				}
				if !ok || p >= best {
					best, ok = p, true
				}
			}
		}
	}

	return best, ok
}

func suiNodeRunningFor(cfg Config) (bool, string) {
	unit := strings.TrimSpace(cfg.NodeService)
	if unit != "" {
		name := unit
		if !strings.HasSuffix(name, ".service") {
			name += ".service"
		}
		out, _ := runCmd(3*time.Second, "systemctl", "show", name,
			"-p", "ActiveState", "-p", "MainPID", "--no-pager")
		state, pid := "", 0
		for _, ln := range strings.Split(out, "\n") {
			ln = strings.TrimSpace(ln)
			if k, v, ok := strings.Cut(ln, "="); ok {
				switch k {
				case "ActiveState":
					state = v
				case "MainPID":
					pid, _ = strconv.Atoi(v)
				}
			}
		}
		if (state == "active" || state == "activating") && pid > 0 {
			cmdOut, _ := runCmd(2*time.Second, "ps", "-p", strconv.Itoa(pid), "-o", "args=")
			cmd := strings.TrimSpace(cmdOut)
			if cmd != "" && strings.Contains(cmd, "sui-node") {
				return true, cmd
			}
		}
	}
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		data = LookupNetworkProfile(cfg.Network, cfg.Env).DataPath
	}
	out, err := runCmd(2*time.Second, "bash", "-lc",
		fmt.Sprintf(`ps -eo pid=,args= | grep -E '[s]ui-node' | grep -F %q | head -1`, data))
	if err != nil || strings.TrimSpace(out) == "" {
		return false, ""
	}

	return true, strings.TrimSpace(out)
}

func suiStartFailureDetail(cfg Config, procOK bool) (string, bool) {
	if procOK {
		return "", false
	}
	state := systemctlActive(cfg.NodeService)
	if systemctlFailed(cfg.NodeService) || state == "failed" {
		snip := journalUnitSnippet(cfg.NodeService, 16)
		if snip != "" {
			return snip, true
		}

		return "sui-node unit failed", true
	}

	return "", false
}

func suiDiskGateOK(cfg Config, prof NetworkProfile) (ok bool, freeGiB, needGiB float64, detail string) {
	needGiB = prof.DiskHintGiB
	if needGiB <= 0 {
		needGiB = 1024
	}
	floor := needGiB * 0.05
	if floor < 80 {
		floor = 80
	}
	freeGiB = freeDiskGiB(cfg.DataDir)
	ok = freeGiB >= floor
	detail = fmt.Sprintf("%.0f GiB free (floor %.0f GiB for sui fullnode; hint %.0f GiB)", freeGiB, floor, needGiB)

	return ok, freeGiB, needGiB, detail
}
