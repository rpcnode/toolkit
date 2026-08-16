package main

import (
	"fmt"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// collectETC — Core-Geth Ethereum Classic (archive IBD via eth_syncing).
func collectETC(cfg Config) map[string]any {
	network := cfg.Network
	if network == "" {
		network = "etc"
	}
	prof := LookupNetworkProfile(network, cfg.Env)

	nodeState := systemctlActive(cfg.NodeService)
	procOK := etcProcessRunning(cfg)
	startErr, startBad := etcStartFailureDetail(cfg, procOK)
	nodeActive := procOK && !startBad
	if !nodeActive && (nodeState == "active" || nodeState == "activating") {
		nodeActive = !startBad
	}

	var rpc ethereumRPCResult
	var nodePortOpen bool
	if nodeActive {
		nodePortOpen = portOpen(cfg.UpstreamHost, cfg.UpstreamPort)
		if nodePortOpen {
			rpc = probeEthereumRPC(cfg)
		}
	}
	rpcOK := rpc.OK
	syncing := rpcOK && rpc.Syncing
	verifyPct := ethSyncVerificationPct(rpc.CurrentBlock, rpc.HighestBlock, syncing)
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

	diskOK, freeGiB, needGiB, diskDetail := etcDiskGateOK(cfg, prof)
	logTail := etcLogTail(cfg, 80)

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
		Progress:       prog,
	}
	if rpcOK {
		// eth_blockNumber → Block; eth_syncing current/highest stay 0 when synced.
		// Mirror bsc/l2: never feed Height=0 into Run (stuck "Syncing · height 0").
		lcIn.Height = rpc.Block
		if syncing && rpc.HighestBlock > 0 {
			lcIn.Headers = rpc.HighestBlock
			if rpc.CurrentBlock > 0 {
				lcIn.Height = rpc.CurrentBlock
			}
		}
		// ethSyncVerificationPct is 0..100; lifecycle VerifyPct is 0..1 (same as bsc).
		lcIn.VerifyPct = verifyPct / 100
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
		{"id": "disk", "title": "Disk floor for ETC archive", "done": diskOK,
			"detail": diskDetail, "active": !diskOK && apiUp},
		{"id": "node", "title": "Core-Geth running", "done": nodeActive,
			"detail": "systemd", "active": apiUp && !nodeActive},
		{"id": "rpc", "title": "JSON-RPC responding", "done": rpcOK,
			"detail": "eth_syncing", "active": nodeActive && !rpcOK},
		{"id": "ibd", "title": "Archive sync complete", "done": rpcOK && !syncing,
			"detail": rpc.SyncDetail, "active": rpcOK && syncing,
			"pct": map[bool]any{true: verifyPct, false: nil}[rpcOK && syncing || rpcOK && !syncing]},
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

	clientVer := etcClientVersion(cfg)
	updatedAt := time.Now().UTC().Format(time.RFC3339)
	host := hostname()
	base, panelBase := effectivePublicBases(cfg)

	verifyUI := any(nil)
	if rpcOK {
		verifyUI = verifyPct // already 0..100 from ethSyncVerificationPct
	}

	blockHeight := rpc.Block
	if syncing && rpc.CurrentBlock > 0 {
		blockHeight = rpc.CurrentBlock
	}
	syncDetail := rpc.SyncDetail
	if syncDetail == "" && rpcOK && !syncing && blockHeight > 0 {
		syncDetail = fmt.Sprintf("Synced · height %d", blockHeight)
	}
	syncBlock := map[string]any{
		"network":          network,
		"ibd":              syncing,
		"syncing":          syncing,
		"blocks":           blockHeight,
		"headers":          rpc.HighestBlock,
		"ok":               rpcOK && !syncing,
		"updated_at":       updatedAt,
		"log_tail":         logTail,
		"detail":           syncDetail,
		"verification_pct": verifyUI,
	}

	return map[string]any{
		"ok":             true,
		"version":        agentVersion(),
		"client_version": clientVer,
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
			"activity": map[bool]string{true: "ibd", false: "online"}[syncing],
			"status":   map[bool]string{true: "degraded", false: "ok"}[degraded],
			"last_error": startErr,
		},
		"services": map[string]any{
			"node":   nodeSvcEffective,
			"api":    apiSvc,
			"system": systemctlActive(cfg.SystemService),
		},
		"checks": map[string]any{
			"node_process_up": procOK,
			"rpc_ok":          rpcOK,
			"disk_ok":         diskOK,
		},
		"disk_gate": map[string]any{
			"ok": diskOK, "free_gib": freeGiB, "need_gib": needGiB, "detail": diskDetail,
		},
		"rpc": map[string]any{
			"ok": rpcOK, "reachable": rpcOK, "syncing": syncing,
			"current_block": blockHeight, "highest_block": rpc.HighestBlock,
			"client_version": clientVer, "error": rpc.Error,
		},
		"sync":               syncBlock,
		"logs":               map[string]any{"title": "ETC sync", "source": "journal", "lines": logTail},
		"height":             map[bool]any{true: blockHeight, false: nil}[rpcOK && blockHeight > 0],
		"start_error":        startErr,
		"supported_networks": ListKnownNetworks(),
		"capabilities":       LifecycleCapabilitiesFor(network, cfg.Env),
		"supported_steps":    SupportedLifecycleSteps(network, cfg.Env),
	}
}

func etcProcessRunning(cfg Config) bool {
	hint := cfg.DataDir
	if hint == "" {
		hint = "etc/" + cfg.Env
	}
	out, _ := exec.Command("bash", "-lc", fmt.Sprintf(
		`pgrep -af '[g]eth' | grep -E %q | head -1`, hint,
	)).CombinedOutput()
	return strings.TrimSpace(string(out)) != ""
}

func etcStartFailureDetail(cfg Config, procOK bool) (string, bool) {
	bin := filepath.Join(cfg.OptDir, "bin", "geth")
	if !fileExists(bin) {
		return "core-geth binary missing under " + cfg.OptDir + "/bin — re-provision", true
	}
	if systemctlFailed(cfg.NodeService) && !procOK {
		snip := stripUnitPathNoise(journalUnitSnippet(cfg.NodeService, 12))
		msg := "etc unit failed"
		if snip != "" {
			msg += " — " + snip
		}
		return msg, true
	}
	return "", false
}

func etcDiskGateOK(cfg Config, prof NetworkProfile) (ok bool, freeGiB, needGiB float64, detail string) {
	needGiB = prof.DiskHintGiB
	if needGiB <= 0 {
		needGiB = 1024
	}
	floor := needGiB * 0.15
	if floor < 40 {
		floor = 40
	}
	path := cfg.DataDir
	if path == "" {
		path = prof.DataPath
	}
	freeGiB = freeDiskGiB(path)
	if freeGiB < 0 {
		return true, freeGiB, needGiB, "disk free unknown"
	}
	if freeGiB < floor {
		return false, freeGiB, needGiB, fmt.Sprintf("%.0f GiB free < %.0f GiB floor (hint %.0f GiB archive)", freeGiB, floor, needGiB)
	}
	return true, freeGiB, needGiB, fmt.Sprintf("%.0f GiB free (hint %.0f GiB)", freeGiB, needGiB)
}

func etcClientVersion(cfg Config) string {
	bin := filepath.Join(cfg.OptDir, "bin", "geth")
	if !fileExists(bin) {
		return ""
	}
	out, err := runCmd(3*time.Second, bin, "version")
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(strings.ToLower(line), "version:") {
			return strings.TrimSpace(strings.TrimPrefix(line, "Version:"))
		}
	}
	return strings.TrimSpace(strings.Split(out, "\n")[0])
}

func etcLogTail(cfg Config, n int) []string {
	j := journalUnitSnippet(cfg.NodeService, n)
	if j == "" {
		return nil
	}
	return strings.Split(j, "\n")
}
