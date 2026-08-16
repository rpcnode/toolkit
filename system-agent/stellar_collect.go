package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

type stellarRPCInfo struct {
	OK            bool
	Healthy       bool
	LatestLedger  int64
	OldestLedger  int64
	ClientVersion string
	NetworkPass   string
	Error         string
	TipLedger     int64
	VerifyPct     float64 // 0..1
	Synced        bool
}

// collectStellar — stellar-rpc catch-up.
// Synced only when healthy at tip AND oldestLedger reaches genesis (1).
// getHealth=healthy alone is live tip, not full history.
func collectStellar(cfg Config) map[string]any {
	network := cfg.Network
	if network == "" {
		network = "stellar"
	}
	prof := LookupNetworkProfile(network, cfg.Env)

	nodeState := systemctlActive(cfg.NodeService)
	procOK := stellarProcessRunning(cfg)
	startErr, startBad := stellarStartFailureDetail(cfg, procOK)
	nodeActive := procOK && !startBad
	if !nodeActive && (nodeState == "active" || nodeState == "activating") {
		nodeActive = !startBad
	}

	var info stellarRPCInfo
	var nodePortOpen bool
	if nodeActive {
		nodePortOpen = portOpen(cfg.UpstreamHost, cfg.UpstreamPort)
		if nodePortOpen {
			info = probeStellarRPC(cfg)
		}
	}
	rpcOK := info.OK
	syncing := rpcOK && !info.Synced

	nodeSvcEffective := nodeState
	switch {
	case startBad:
		nodeSvcEffective = "failed"
	case nodeActive && rpcOK && !syncing:
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

	diskOK, freeGiB, needGiB, diskDetail := stellarDiskGateOK(cfg, prof)

	if nodeActive {
		maybeAppendStellarProgressLog(cfg, syncing || !rpcOK, info)
	}
	logTail := stellarLogTail(cfg, 80)

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
		SnapEnabled:    false,
		NodeActive:     nodeActive,
		StartError:     startErr,
		RPCOK:          rpcOK,
		IBD:            syncing,
		VerifyPct:      info.VerifyPct,
		Progress:       prog,
	}
	if rpcOK {
		lcIn.Height = info.LatestLedger
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

	verifyPct := info.VerifyPct * 100
	if verifyPct < 0 {
		verifyPct = 0
	}
	if verifyPct > 100 {
		verifyPct = 100
	}

	setupSteps := []map[string]any{
		{"id": "registry", "title": "Instance registered", "done": instRegistered,
			"detail": "INSTANCE.json + /etc/rpcnode/instances.d"},
		{"id": "disk", "title": "Disk floor for Stellar catch-up", "done": diskOK,
			"detail": diskDetail, "active": !diskOK && apiUp},
		{"id": "node", "title": "stellar-rpc running", "done": nodeActive,
			"detail": "systemd", "active": apiUp && !nodeActive},
		{"id": "rpc", "title": "JSON-RPC responding", "done": rpcOK,
			"detail": "getHealth", "active": nodeActive && !rpcOK},
		{"id": "ibd", "title": "Ledger catch-up complete", "done": rpcOK && !syncing,
			"detail": map[bool]string{
				true:  fmt.Sprintf("syncing · ledger %d · tip %d", info.LatestLedger, info.TipLedger),
				false: fmt.Sprintf("healthy · ledger %d", info.LatestLedger),
			}[rpcOK && syncing],
			"active": rpcOK && syncing,
			"pct":    map[bool]any{true: verifyPct, false: nil}[rpcOK]},
		{"id": "api", "title": "API agent up", "done": apiUp,
			"detail": fmt.Sprintf(":%d /healthz", apiProbePort)},
	}

	health := "ok"
	degraded := false
	switch {
	case startBad || uiPhase == "error" || nodeStatus == "start_error":
		health = "error"
		degraded = true
	case !diskOK && (uiPhase == "start" || nodeStatus == "ready_to_start"):
		health = "setup"
		degraded = true
	case uiPhase == "install" || uiPhase == "setup" || uiPhase == "ports":
		health = "setup"
		degraded = true
	case uiPhase == "start" || uiPhase == "run" || syncing:
		health = "degraded"
		degraded = true
	case !nodeActive || !rpcOK:
		health = "degraded"
		degraded = true
	}

	nodeReady := nodeActive && rpcOK && !syncing
	agentActivity := "idle"
	agentStatus := "ok"
	agentLastErr := ""
	switch {
	case startBad:
		agentActivity = "node_start_failed"
		agentStatus = "error"
		agentLastErr = startErr
	case !diskOK && apiUp && !nodeActive:
		agentActivity = "disk_gate"
		agentStatus = "degraded"
		agentLastErr = diskDetail
	case uiPhase == "start" || (nodeActive && !rpcOK):
		agentActivity = "node_starting"
		if health == "degraded" {
			agentStatus = "degraded"
		}
	case syncing || uiPhase == "run":
		agentActivity = "ibd"
		agentStatus = "degraded"
	case nodeReady || uiPhase == "healthy":
		agentActivity = "online"
	default:
		if health == "degraded" || health == "setup" {
			agentStatus = "degraded"
		}
	}

	host := hostname()
	base, panelBase := effectivePublicBases(cfg)

	updatedAt := time.Now().UTC().Format(time.RFC3339)

	rpcBlock := map[string]any{
		"ok":                 rpcOK,
		"reachable":          rpcOK,
		"http_ok":            rpcOK,
		"process_up":         nodeActive,
		"port_open":          nodePortOpen,
		"healthy":            info.Healthy,
		"latest_ledger":      info.LatestLedger,
		"oldest_ledger":      info.OldestLedger,
		"tip_ledger":         info.TipLedger,
		"client_version":     info.ClientVersion,
		"network_passphrase": info.NetworkPass,
		"synced":             info.Synced,
		"verification_pct":   verifyPct,
		"error":              info.Error,
		"node_height":        nil,
	}
	if rpcOK {
		rpcBlock["node_height"] = info.LatestLedger
	}

	syncBlock := map[string]any{
		"network":          network,
		"ibd":              syncing || (nodeActive && !rpcOK),
		"syncing":          syncing || (nodeActive && !rpcOK),
		"latest_ledger":    info.LatestLedger,
		"oldest_ledger":    info.OldestLedger,
		"tip_ledger":       info.TipLedger,
		"blocks":           info.LatestLedger,
		"verification_pct": verifyPct,
		"ok":               rpcOK && !syncing,
		"updated_at":       updatedAt,
		"log_tail":         logTail,
		"detail":           "",
	}
	if info.OldestLedger > 0 && syncing && info.Healthy {
		syncBlock["blocks"] = info.OldestLedger
	}
	// Only set headers when public tip is known — UI must not show "ledger / 0".
	if info.TipLedger > 0 {
		syncBlock["headers"] = info.TipLedger
	}
	if sz := stellarDataDirBytes(cfg); sz > 0 {
		syncBlock["size_on_disk"] = sz
		syncBlock["size_on_disk_gb"] = round1(float64(sz) / (1024 * 1024 * 1024))
		rpcBlock["size_on_disk"] = sz
		rpcBlock["size_on_disk_gb"] = syncBlock["size_on_disk_gb"]
	}
	if rpcOK {
		if syncing && info.Healthy && info.OldestLedger > 1 {
			syncBlock["detail"] = fmt.Sprintf("Syncing history · live tip · oldest %d · %s%%",
				info.OldestLedger, formatSyncPct(verifyPct))
		} else if syncing {
			if info.TipLedger > 0 {
				syncBlock["detail"] = fmt.Sprintf("Catch-up · ledger %d / tip %d · %s%%",
					info.LatestLedger, info.TipLedger, formatSyncPct(verifyPct))
			} else {
				syncBlock["detail"] = fmt.Sprintf("Catch-up · ledger %d · tip n/a · %s%%",
					info.LatestLedger, formatSyncPct(verifyPct))
			}
		} else {
			syncBlock["detail"] = fmt.Sprintf("Synced · ledger %d · %s", info.LatestLedger, info.ClientVersion)
		}
	} else if nodeActive {
		syncBlock["detail"] = "Captive Core catch-up · waiting for stellar-rpc"
	} else {
		syncBlock["detail"] = "stellar-rpc not running"
	}

	var height any
	if rpcOK {
		height = info.LatestLedger
	}

	return map[string]any{
		"ok":             true,
		"version":        agentVersion(),
		"client_version": info.ClientVersion,
		"network":        network,
		"env":            cfg.Env,
		"hostname":       host,
		"updated_at":     updatedAt,
		"health":         health,
		"degraded":       degraded,
		"ui_phase":       uiPhase,
		"node_status":    nodeStatus,
		"lifecycle":      lifecycle,
		"setup_steps":    setupSteps,
		"public_base":    base,
		"panel_base":     panelBase,
		"agent": map[string]any{
			"activity": agentActivity, "status": agentStatus, "last_error": agentLastErr,
		},
		"services": map[string]any{
			"node": nodeSvcEffective, "api": apiSvc,
		},
		"ports": map[string]any{
			"public": publicPort, "agent": agentPort, "upstream": cfg.UpstreamPort,
			"public_open": publicPortOpen, "agent_open": agentPortOpen, "upstream_open": nodePortOpen,
		},
		"disk": map[string]any{
			"ok": diskOK, "free_gib": freeGiB, "need_gib": needGiB, "detail": diskDetail,
		},
		"rpc":    rpcBlock,
		"sync":   syncBlock,
		"height": height,
		"logs": map[string]any{
			"title":  "Sync progress",
			"source": "stellar-sync",
			"lines":  logTail,
		},
	}
}

func stellarProcessRunning(cfg Config) bool {
	st := systemctlActive(cfg.NodeService)
	if st == "active" || st == "activating" {
		return true
	}
	// Captive-core child may still be up briefly after unit stop — treat as running.
	env := strings.TrimSpace(cfg.Env)
	if env == "" {
		env = "mainnet"
	}
	marker := "stellar/" + env
	out, err := exec.Command("bash", "-lc",
		fmt.Sprintf(`pgrep -af 'stellar-rpc' | grep -F %q | grep -v grep >/dev/null`, marker),
	).CombinedOutput()
	_ = out
	return err == nil
}

func probeStellarRPC(cfg Config) stellarRPCInfo {
	base := fmt.Sprintf("http://%s:%d", cfg.UpstreamHost, cfg.UpstreamPort)
	info := stellarRPCInfo{}

	// Stellar RPC rejects params for these methods — omit the field (nil), never {}.
	health := stellarJSONRPC(base, "getHealth", nil)
	if health.err != "" {
		info.Error = health.err
		return info
	}
	info.OK = true
	if st, _ := health.result["status"].(string); strings.EqualFold(st, "healthy") {
		info.Healthy = true
	}
	info.LatestLedger = jsonInt64(health.result["latestLedger"])
	info.OldestLedger = jsonInt64(health.result["oldestLedger"])

	ver := stellarJSONRPC(base, "getVersionInfo", nil)
	if ver.err == "" {
		if v, ok := ver.result["version"].(string); ok && v != "" {
			info.ClientVersion = v
		} else if v, ok := ver.result["commitHash"].(string); ok && v != "" {
			info.ClientVersion = v
		}
		// Prefer stellar-rpc version field variants.
		for _, k := range []string{"version", "rpcVersion", "commit_hash"} {
			if v, ok := ver.result[k].(string); ok && strings.TrimSpace(v) != "" {
				info.ClientVersion = strings.TrimSpace(v)
				break
			}
		}
	}

	net := stellarJSONRPC(base, "getNetwork", nil)
	if net.err == "" {
		if p, ok := net.result["passphrase"].(string); ok {
			info.NetworkPass = p
		}
	}

	if info.LatestLedger <= 0 {
		lat := stellarJSONRPC(base, "getLatestLedger", nil)
		if lat.err == "" {
			info.LatestLedger = jsonInt64(lat.result["sequence"])
			if info.LatestLedger <= 0 {
				info.LatestLedger = jsonInt64(lat.result["latestLedger"])
			}
		}
	}

	tip := stellarPublicTipLedger(cfg)
	info.TipLedger = tip
	info.VerifyPct, info.Synced = stellarSyncProgress(info.Healthy, info.OldestLedger, info.LatestLedger, tip)
	return info
}

type stellarRPCCall struct {
	result map[string]any
	err    string
}

func stellarJSONRPCBody(method string, params map[string]any) map[string]any {
	body := map[string]any{
		"jsonrpc": "2.0",
		"id":      1,
		"method":  method,
	}
	// Omit params entirely when empty — stellar-rpc returns -32602 for params:{}.
	if len(params) > 0 {
		body["params"] = params
	}
	return body
}

func stellarJSONRPC(base, method string, params map[string]any) stellarRPCCall {
	raw, _ := json.Marshal(stellarJSONRPCBody(method, params))
	client := &http.Client{Timeout: 4 * time.Second}
	resp, err := client.Post(strings.TrimRight(base, "/")+"/", "application/json", bytes.NewReader(raw))
	if err != nil {
		return stellarRPCCall{err: err.Error()}
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	var doc map[string]any
	if err := json.Unmarshal(b, &doc); err != nil {
		return stellarRPCCall{err: "invalid json"}
	}
	if e, ok := doc["error"]; ok && e != nil {
		return stellarRPCCall{err: stellarRPCErrorString(e)}
	}
	res, _ := doc["result"].(map[string]any)
	if res == nil {
		return stellarRPCCall{err: "empty result"}
	}
	return stellarRPCCall{result: res}
}

func stellarRPCErrorString(e any) string {
	m, ok := e.(map[string]any)
	if !ok {
		return fmt.Sprint(e)
	}
	code := m["code"]
	msg, _ := m["message"].(string)
	if msg == "" {
		return fmt.Sprint(e)
	}
	if code != nil {
		return fmt.Sprintf("%v: %s", code, msg)
	}
	return msg
}

func stellarPublicTipLedger(cfg Config) int64 {
	for _, tip := range stellarTipRPCCandidates(cfg) {
		if n := stellarProbeTipRPC(tip); n > 0 {
			stellarSaveTipCache(cfg, n)
			return n
		}
	}
	// Horizon (SDF) — reliable ledger tip when community RPCs are down/blocked.
	if n := stellarHorizonTipLedger(cfg); n > 0 {
		stellarSaveTipCache(cfg, n)
		return n
	}
	return stellarLoadTipCache(cfg)
}

func stellarTipRPCCandidates(cfg Config) []string {
	var out []string
	seen := map[string]bool{}
	add := func(u string) {
		u = strings.TrimSpace(u)
		if u == "" || seen[u] {
			return
		}
		seen[u] = true
		out = append(out, u)
	}
	add(os.Getenv("STELLAR_PUBLIC_TIP"))
	tipPath := filepath.Join(cfg.EtcDir, "public_tip.url")
	if b, err := os.ReadFile(tipPath); err == nil {
		add(string(b))
	}
	switch strings.ToLower(strings.TrimSpace(cfg.Env)) {
	case "testnet":
		add("https://soroban-testnet.stellar.org")
	case "futurenet":
		add("https://rpc-futurenet.stellar.org")
	default:
		// SDF has no official public mainnet RPC — try community tips then Horizon.
		add("https://mainnet.sorobanrpc.com")
		add("https://rpc.ankr.com/stellar_soroban")
	}
	return out
}

func stellarProbeTipRPC(tip string) int64 {
	call := stellarJSONRPC(tip, "getLatestLedger", nil)
	if call.err != "" {
		call = stellarJSONRPC(tip, "getHealth", nil)
		if call.err != "" {
			return 0
		}
		return jsonInt64(call.result["latestLedger"])
	}
	n := jsonInt64(call.result["sequence"])
	if n <= 0 {
		n = jsonInt64(call.result["latestLedger"])
	}
	return n
}

func stellarHorizonTipLedger(cfg Config) int64 {
	url := "https://horizon.stellar.org/"
	switch strings.ToLower(strings.TrimSpace(cfg.Env)) {
	case "testnet":
		url = "https://horizon-testnet.stellar.org/"
	case "futurenet":
		url = "https://horizon-futurenet.stellar.org/"
	}
	client := &http.Client{Timeout: 4 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		return 0
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	var doc map[string]any
	if json.Unmarshal(b, &doc) != nil {
		return 0
	}
	n := jsonInt64(doc["history_latest_ledger"])
	if n <= 0 {
		n = jsonInt64(doc["core_latest_ledger"])
	}
	return n
}

func stellarTipCachePath(cfg Config) string {
	return filepath.Join(filepath.Dir(cfg.StateFile), "stellar-tip-ledger.json")
}

func stellarSaveTipCache(cfg Config, n int64) {
	if n <= 0 {
		return
	}
	_ = os.WriteFile(stellarTipCachePath(cfg),
		[]byte(fmt.Sprintf(`{"tip_ledger":%d,"updated_at":%q}`, n, time.Now().UTC().Format(time.RFC3339))),
		0644)
}

func stellarLoadTipCache(cfg Config) int64 {
	b, err := os.ReadFile(stellarTipCachePath(cfg))
	if err != nil {
		return 0
	}
	var doc map[string]any
	if json.Unmarshal(b, &doc) != nil {
		return 0
	}
	return jsonInt64(doc["tip_ledger"])
}

// stellarDataDirBytes — captive-core / stellar-rpc datadir size for Sync "disk".
func stellarDataDirBytes(cfg Config) int64 {
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		return 0
	}
	return duBytes(data)
}

func stellarSyncProgress(healthy bool, oldest, local, tip int64) (verify float64, synced bool) {
	const genesis int64 = 1
	const slack int64 = 32
	seq := local
	if tip > seq {
		seq = tip
	}
	live := healthy && local > 0 && (tip <= 0 || local+slack >= tip)
	historyOK := historyWindowCaughtUp(oldest, local, seq, genesis, slack)
	if live && historyOK {
		return 1, true
	}
	if !live {
		if local <= 0 {
			return 0, false
		}
		if tip <= 0 {
			p := 0.02 + 0.12*math.Log10(float64(local)+1)
			if p > 0.85 {
				p = 0.85
			}
			if p < 0.02 {
				p = 0.02
			}
			return p, false
		}
		pct := float64(local) / float64(tip)
		if pct > 0.999 {
			pct = 0.999
		}
		if pct < 0.01 {
			pct = 0.01
		}
		return pct, false
	}
	return historyWindowPct(true, false, oldest, local, seq, genesis) / 100, false
}

func jsonInt64(v any) int64 {
	switch t := v.(type) {
	case float64:
		return int64(t)
	case int64:
		return t
	case int:
		return int64(t)
	case json.Number:
		n, _ := t.Int64()
		return n
	case string:
		var n int64
		fmt.Sscanf(t, "%d", &n)
		return n
	default:
		return 0
	}
}

func stellarStartFailureDetail(cfg Config, procOK bool) (string, bool) {
	if procOK {
		return "", false
	}
	// After Confirm ports the unit file exists but is still inactive until lifecycle
	// start — that is NOT start_error (was flashing "unit=/etc/.../stellar-*.service").
	probe := probeSystemdUnit(cfg.NodeService)
	return stellarStartFailureFromProbe(probe, journalUnitSnippet(cfg.NodeService, 16))
}

// stellarStartFailureFromProbe — failed / crash-loop only; inactive|dead|activating(ok) → no error.
func stellarStartFailureFromProbe(probe systemdUnitProbe, snip string) (string, bool) {
	if probe.ActiveState == "activating" {
		resultBad := probe.Result == "exit-code" || probe.Result == "signal" ||
			probe.Result == "resources" || probe.Result == "timeout"
		if !resultBad && probe.NRestarts < 1 {
			return "", false
		}
	}
	resultBad := probe.Result == "exit-code" || probe.Result == "signal" ||
		probe.Result == "resources" || probe.Result == "timeout"
	crashLoop := resultBad && (probe.NRestarts >= 1 || probe.ActiveState == "activating")
	failed := probe.Failed || probe.ActiveState == "failed"
	if !failed && !crashLoop {
		return "", false
	}
	if strings.TrimSpace(snip) == "" {
		snip = fmt.Sprintf("stellar-rpc unit failed (state=%s result=%s restarts=%d)",
			probe.ActiveState, probe.Result, probe.NRestarts)
	}
	return snip, true
}

func stellarDiskGateOK(cfg Config, prof NetworkProfile) (ok bool, freeGiB, needGiB float64, detail string) {
	needGiB = prof.DiskHintGiB
	if needGiB <= 0 {
		needGiB = 64
	}
	floor := needGiB * 0.2
	if floor < 5 {
		floor = 5
	}
	freeGiB = diskUsageGiB(cfg.DataDir)
	if freeGiB >= floor {
		return true, freeGiB, needGiB, fmt.Sprintf("free %.0f GiB ≥ floor %.0f GiB (plan %.0f GiB)", freeGiB, floor, needGiB)
	}
	return false, freeGiB, needGiB, fmt.Sprintf("free %.0f GiB < floor %.0f GiB before Stellar catch-up (plan %.0f GiB for %s)", freeGiB, floor, needGiB, cfg.Env)
}
