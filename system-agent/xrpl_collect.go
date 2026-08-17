package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type xrplServerInfo struct {
	OK         bool
	State      string
	Complete   string
	Seq        int64
	Peers      int
	Uptime     int64
	BuildVer   string
	PubkeyNode string
	Error      string
	Synced     bool
}

// collectXRPL — stock xrpld lifecycle.
// Synced = live tip (server_state full|proposing|validating) AND the chosen
// history window (full → genesis; otherwise complete span ≥ ledger_history).
func collectXRPL(cfg Config) map[string]any {
	network := cfg.Network
	if network == "" {
		network = "xrpl"
	}
	prof := LookupNetworkProfile(network, cfg.Env)

	nodeState := systemctlActive(cfg.NodeService)
	procOK, _ := xrplProcessRunning(cfg)
	startErr, startBad := xrplStartFailureDetail(cfg, procOK)
	nodeActive := procOK && !startBad
	if !nodeActive && (nodeState == "active" || nodeState == "activating") {
		nodeActive = !startBad
	}

	var info xrplServerInfo
	var nodePortOpen bool
	if nodeActive {
		nodePortOpen = portOpen(cfg.UpstreamHost, cfg.UpstreamPort)
		if nodePortOpen {
			info = probeXRPLServerInfo(cfg)
		}
	}
	rpcOK := info.OK
	completeLo, completeHi := parseXRPLCompleteLedgers(info.Complete)
	live := info.Synced
	histPol := resolveXRPLHistoryPolicy(cfg.EtcDir)
	historyOK := xrplHistoryOK(cfg.Env, completeLo, completeHi, info.Seq, histPol)
	// Synced = live tip AND chosen history window. server_state=full alone is not enough.
	info.Synced = live && historyOK
	syncing := rpcOK && !info.Synced
	verifyPct := xrplVerificationPct(live, historyOK, completeLo, completeHi, info.Seq, xrplGenesisLedger(cfg.Env), int64(histPol.Ledgers))
	if !rpcOK {
		verifyPct = 0
	}

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

	diskOK, freeGiB, needGiB, diskDetail := xrplDiskGateOK(cfg, prof)

	logTail := xrplLogTail(cfg, 80)
	if nodeActive && !rpcOK && !startBad {
		maybeAppendXRPLProgressLog(cfg, true)
		logTail = xrplLogTail(cfg, 80)
	}

	prog := loadLifecycleProgress(cfg)
	if prog != nil && xrplServerStopNoise(prog.Auto.LastError) {
		prog.Auto.LastError = ""
		saveLifecycleProgress(cfg, prog)
	}
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
		Progress:       prog,
	}
	if rpcOK {
		lcIn.Height = info.Seq
		if completeHi > 0 {
			lcIn.Height = completeHi
		}
		if info.Seq > 0 {
			lcIn.Headers = info.Seq
		}
		lcIn.VerifyPct = verifyPct / 100
		if info.Peers >= 0 {
			lcIn.Peers = info.Peers
		}
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

	setupSteps := []map[string]any{
		{"id": "registry", "title": "Instance registered", "done": instRegistered,
			"detail": "INSTANCE.json + /etc/rpcnode/instances.d"},
		{"id": "disk", "title": "Disk floor for XRPL sync", "done": diskOK,
			"detail": diskDetail, "active": !diskOK && apiUp},
		{"id": "node", "title": "xrpld running", "done": nodeActive,
			"detail": "process/systemd", "active": apiUp && !nodeActive},
		{"id": "rpc", "title": "JSON-RPC responding", "done": rpcOK,
			"detail": "server_info", "active": nodeActive && !rpcOK},
		{"id": "ibd", "title": "Ledger sync complete", "done": rpcOK && !syncing,
			"detail": map[bool]string{
				true:  fmt.Sprintf("syncing · state=%s · seq %d", info.State, info.Seq),
				false: fmt.Sprintf("server_state=%s", info.State),
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

	if rpcOK {
		maybeAppendXRPLProgressLog(cfg, syncing)
	}

	updatedAt := time.Now().UTC().Format(time.RFC3339)

	rpcBlock := map[string]any{
		"ok":               rpcOK,
		"reachable":        rpcOK,
		"http_ok":          rpcOK,
		"process_up":       nodeActive,
		"port_open":        nodePortOpen,
		"server_state":     info.State,
		"complete_ledgers": info.Complete,
		"ledger_seq":       info.Seq,
		"peers":            info.Peers,
		"uptime":           info.Uptime,
		"build_version":    info.BuildVer,
		"client_version":   info.BuildVer,
		"synced":           info.Synced,
		"error":            info.Error,
		"node_height":      nil,
	}
	if rpcOK {
		rpcBlock["node_height"] = info.Seq
		rpcBlock["verification_pct"] = verifyPct
		rpcBlock["syncing"] = syncing
		if completeHi > 0 {
			rpcBlock["blocks"] = completeHi
		}
		if info.Seq > 0 {
			rpcBlock["headers"] = info.Seq
		}
	}

	syncBlock := map[string]any{
		"network":          network,
		"ibd":              syncing,
		"syncing":          syncing,
		"server_state":     info.State,
		"ledger_seq":       info.Seq,
		"complete_ledgers": info.Complete,
		"peers":            info.Peers,
		"ok":               rpcOK && !syncing,
		"updated_at":       updatedAt,
		"log_tail":         logTail,
		"verification_pct": verifyPct,
		"history_mode":     histPol.Mode,
		"history_ledgers":  histPol.Ledgers,
		"detail":           "",
	}
	if completeLo > 0 {
		syncBlock["blocks"] = completeLo
	} else if info.Seq > 0 {
		syncBlock["blocks"] = info.Seq
	}
	if info.Seq > 0 {
		syncBlock["headers"] = info.Seq
	}
	if rpcOK {
		if info.Seq <= 0 {
			if xrplBuildIsBroken32(info.BuildVer) {
				_, cat := xrplDebFromCatalog(cfg.Env)
				syncBlock["detail"] = fmt.Sprintf("xrpld %s (3.2.x first-ledger) · catalog %s · state=%s · peers %d",
					info.BuildVer, cat, info.State, info.Peers)
			} else {
				syncBlock["detail"] = fmt.Sprintf("Waiting for first ledger · state=%s · peers %d",
					info.State, info.Peers)
			}
		} else if syncing && live {
			syncBlock["detail"] = fmt.Sprintf("Syncing history · live tip · complete %s · %s%%",
				info.Complete, formatSyncPct(verifyPct))
		} else if syncing {
			syncBlock["detail"] = fmt.Sprintf("Syncing · state=%s · complete %s · tip %d · %s%%",
				info.State, info.Complete, info.Seq, formatSyncPct(verifyPct))
		} else {
			syncBlock["detail"] = fmt.Sprintf("Synced · state=%s · complete %s · tip %d",
				info.State, info.Complete, info.Seq)
		}
	} else if info.Error != "" {
		syncBlock["detail"] = info.Error
	} else if xrplJournalHasLoadStall(logTail) {
		syncBlock["detail"] = "LoadManager stall — node_size too large for host RAM (xrpld.cfg)"
	} else if nodeActive {
		syncBlock["detail"] = "Waiting for xrpld JSON-RPC"
	} else {
		syncBlock["detail"] = "xrpld not running"
	}

	var height any
	if rpcOK {
		height = info.Seq
	}

	return map[string]any{
		"ok":             true,
		"version":        agentVersion(),
		"client_version": info.BuildVer,
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
			"activity":   agentActivity,
			"status":     agentStatus,
			"last_error": agentLastErr,
		},
		"services": map[string]any{
			"node":   nodeSvcEffective,
			"clio":   systemctlActive(strings.TrimSuffix(xrplClioUnitName(cfg.Env), ".service")),
			"scylla": systemctlActive("scylla-server"),
			"api":    apiSvc,
			"system": systemctlActive(cfg.SystemService),
		},
		"checks": map[string]any{
			"node_process_up": procOK,
			"xrpld_process":   procOK,
			"rpc_ok":          rpcOK,
			"disk_ok":         diskOK,
		},
		"disk_gate": map[string]any{
			"ok":       diskOK,
			"free_gib": freeGiB,
			"need_gib": needGiB,
			"detail":   diskDetail,
		},
		"rpc":  rpcBlock,
		"sync": syncBlock,
		"logs": map[string]any{
			"lines": logTail,
		},
		"height":             height,
		"peers":              info.Peers,
		"start_error":        startErr,
		"supported_networks": ListKnownNetworks(),
		"capabilities":       LifecycleCapabilitiesFor(network, cfg.Env),
		"supported_steps":    SupportedLifecycleSteps(network, cfg.Env),
	}
}

func probeXRPLServerInfo(cfg Config) xrplServerInfo {
	url := fmt.Sprintf("http://%s:%d/", cfg.UpstreamHost, cfg.UpstreamPort)
	body := []byte(`{"method":"server_info","params":[{}]}`)
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return xrplServerInfo{Error: err.Error()}
	}
	req.Header.Set("Content-Type", "application/json")
	client := &http.Client{Timeout: 4 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return xrplServerInfo{Error: err.Error()}
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode >= 300 {
		return xrplServerInfo{Error: fmt.Sprintf("http %d", resp.StatusCode)}
	}

	var doc map[string]any
	if err := json.Unmarshal(raw, &doc); err != nil {
		return xrplServerInfo{Error: "invalid json"}
	}
	result, _ := doc["result"].(map[string]any)
	if result == nil {
		return xrplServerInfo{Error: "missing result"}
	}
	if st, _ := result["status"].(string); st != "" && st != "success" {
		msg, _ := result["error_message"].(string)
		if msg == "" {
			msg, _ = result["error"].(string)
		}
		if msg == "" {
			msg = st
		}
		return xrplServerInfo{Error: msg}
	}
	info, _ := result["info"].(map[string]any)
	if info == nil {
		return xrplServerInfo{Error: "missing info"}
	}

	out := xrplServerInfo{OK: true}
	out.State, _ = info["server_state"].(string)
	out.Complete, _ = info["complete_ledgers"].(string)
	out.BuildVer, _ = info["build_version"].(string)
	out.PubkeyNode, _ = info["pubkey_node"].(string)
	if v, ok := info["peers"].(float64); ok {
		out.Peers = int(v)
	}
	if v, ok := info["uptime"].(float64); ok {
		out.Uptime = int64(v)
	}
	if lv, ok := info["validated_ledger"].(map[string]any); ok {
		if seq, ok := lv["seq"].(float64); ok {
			out.Seq = int64(seq)
		}
	}
	switch strings.ToLower(out.State) {
	case "full", "proposing", "validating":
		out.Synced = true
	}

	return out
}

// parseXRPLCompleteLedgers — "106326475-106333417", "empty", or "1-100,200-300".
func parseXRPLCompleteLedgers(s string) (lo, hi int64) {
	s = strings.TrimSpace(strings.ToLower(s))
	if s == "" || s == "empty" || s == "none" {
		return 0, 0
	}
	for _, part := range strings.Split(s, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		a, b := int64(0), int64(0)
		if i := strings.IndexByte(part, '-'); i > 0 {
			a, _ = strconv.ParseInt(strings.TrimSpace(part[:i]), 10, 64)
			b, _ = strconv.ParseInt(strings.TrimSpace(part[i+1:]), 10, 64)
		} else {
			n, _ := strconv.ParseInt(part, 10, 64)
			a, b = n, n
		}
		if a > 0 && (lo == 0 || a < lo) {
			lo = a
		}
		if b > hi {
			hi = b
		}
		if a > hi {
			hi = a
		}
	}
	return lo, hi
}

func xrplGenesisLedger(env string) int64 {
	switch normalizeEnvName(env) {
	case "testnet", "devnet", "altnet":
		return 1
	default:
		return 32570 // XRPL mainnet first closed ledger
	}
}

func xrplHistoryCaughtUp(env string, lo, hi, seq int64) bool {
	return xrplHistoryOK(env, lo, hi, seq, parseXRPLHistoryMode("full"))
}

func xrplVerificationPct(live, historyOK bool, lo, hi, seq, genesis, target int64) float64 {
	if target > 0 {
		if live && historyOK {
			return 100
		}

		if !live {
			return historyWindowPct(false, false, lo, hi, seq, genesis)
		}

		if lo <= 0 || hi <= 0 {
			return 0
		}

		have := hi - lo + 1
		pct := float64(have) / float64(target) * 100
		out := math.Round(pct*1000) / 1000
		if out < 0.001 && have > 0 {
			return 0.001
		}

		if out >= 100 && !historyOK {
			return 99.9
		}

		return out
	}

	return historyWindowPct(live, historyOK, lo, hi, seq, genesis)
}

func xrplProcessRunning(cfg Config) (bool, string) {
	data := cfg.DataDir
	etc := cfg.EtcDir
	unit := cfg.NodeService
	out, err := runCmd(2*time.Second, "bash", "-lc",
		fmt.Sprintf(`ps -eo pid=,args= | grep -E '[x]rpld|[r]ippled' | grep -E '%s|%s|%s' | head -1`,
			regexpQuote(etc), regexpQuote(data), regexpQuote(unit)))
	if err != nil || strings.TrimSpace(out) == "" {
		// Broader match for package binary path.
		out2, err2 := runCmd(2*time.Second, "bash", "-lc",
			`ps -eo pid=,args= | grep -E '[x]rpld --conf|[r]ippled --conf' | head -1`)
		if err2 != nil || strings.TrimSpace(out2) == "" {
			return false, ""
		}
		return true, strings.TrimSpace(out2)
	}
	return true, strings.TrimSpace(out)
}

func xrplStartFailureDetail(cfg Config, procOK bool) (string, bool) {
	if procOK {
		return "", false
	}
	// Same as stellar: provision writes the unit; inactive until start ≠ start_error.
	probe := probeSystemdUnit(cfg.NodeService)
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
	snip := journalUnitSnippet(cfg.NodeService, 16)
	if xrplServerStopNoise(snip) {
		// ExecStop=server_stop when xrpld is already dead — not a start fault.
		return "", false
	}
	if strings.TrimSpace(snip) == "" {
		snip = fmt.Sprintf("xrpld unit failed (state=%s result=%s restarts=%d)",
			probe.ActiveState, probe.Result, probe.NRestarts)
	}
	return snip, true
}

func xrplDiskGateOK(cfg Config, prof NetworkProfile) (ok bool, freeGiB, needGiB float64, detail string) {
	needGiB = prof.DiskHintGiB
	if needGiB <= 0 {
		needGiB = 256
	}
	floor := needGiB * 0.2
	if floor < 5 {
		floor = 5
	}
	freeGiB = diskUsageGiB(cfg.DataDir)
	if freeGiB >= floor {
		return true, freeGiB, needGiB, fmt.Sprintf("free %.0f GiB ≥ floor %.0f GiB (plan %.0f GiB)", freeGiB, floor, needGiB)
	}
	return false, freeGiB, needGiB, fmt.Sprintf("free %.0f GiB < floor %.0f GiB before XRPL sync (plan %.0f GiB for %s)", freeGiB, floor, needGiB, cfg.Env)
}

func resolveXRPLDBin(cfg Config) string {
	for _, c := range []string{
		filepath.Join(cfg.OptDir, "bin", "xrpld"),
		"/usr/bin/xrpld",
		"/opt/ripple/bin/xrpld",
		"/opt/ripple/bin/rippled",
		"/usr/bin/rippled",
	} {
		if fileExists(c) {
			return c
		}
	}
	if p, err := exec.LookPath("xrpld"); err == nil {
		return p
	}
	if p, err := exec.LookPath("rippled"); err == nil {
		return p
	}
	return ""
}

func xrplJournalHasLoadStall(lines []string) bool {
	for _, ln := range lines {
		if strings.Contains(ln, "LoadManager") && strings.Contains(strings.ToLower(ln), "stalled") {
			return true
		}
	}
	return false
}

func regexpQuote(s string) string {
	replacer := strings.NewReplacer(
		`.`, `\.`, `+`, `\+`, `*`, `\*`, `?`, `\?`, `|`, `\|`,
		`(`, `\(`, `)`, `\)`, `[`, `\[`, `]`, `\]`, `{`, `\{`, `}`, `\}`,
		`^`, `\^`, `$`, `\$`,
	)
	return replacer.Replace(s)
}
