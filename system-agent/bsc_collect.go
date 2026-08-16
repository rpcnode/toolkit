package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// collectBSC — bnb-chain/bsc geth fork lifecycle (Parlia, no separate CL / no TRON snapshot).
func collectBSC(cfg Config) map[string]any {
	network := cfg.Network
	if network == "" {
		network = "bsc"
	}
	prof := LookupNetworkProfile(network, cfg.Env)

	nodeState := systemctlActive(cfg.NodeService)
	procOK, procCmd := bscProcessRunning(cfg)

	startErr, startBad := bscStartFailureDetail(cfg, procOK)
	nodeActive := bscNodeReallyUp(cfg, nodeState, procOK)

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
	syncDetail := rpc.SyncDetail
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

	diskOK, freeGiB, needGiB, diskDetail := bscDiskGateOK(cfg)

	logTail := bscSyncLogTail(cfg, 80)
	warmupDetail := ""
	if nodeActive && !rpcOK && !startBad {
		warmupDetail = "bsc-geth warming up · waiting for JSON-RPC"
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
		SnapEnabled:    false,
		NodeActive:     nodeActive && !startBad,
		StartError:     startErr,
		WarmupDetail:   warmupDetail,
		RPCOK:          rpcOK,
		IBD:            syncing,
		Progress:       prog,
	}
	if rpcOK {
		lcIn.Height = rpc.Block
		if syncing && rpc.HighestBlock > 0 {
			lcIn.Headers = rpc.HighestBlock
			if rpc.CurrentBlock > 0 {
				lcIn.Height = rpc.CurrentBlock
			}
		}
		lcIn.VerifyPct = verifyPct / 100
	}
	if rpc.Peers >= 0 {
		lcIn.Peers = int(rpc.Peers)
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

	chainTitle := "Full sync"
	chainDetail := "Waiting for eth_syncing"
	if !rpcOK && warmupDetail != "" {
		chainDetail = warmupDetail
	} else if rpcOK && syncing {
		if rpc.HighestBlock > 0 {
			chainDetail = fmt.Sprintf(
				"Syncing · blocks %d / %d · %.1f%%",
				rpc.CurrentBlock, rpc.HighestBlock, verifyPct,
			)
		} else {
			chainDetail = syncDetail
			if chainDetail == "" {
				chainDetail = fmt.Sprintf("Syncing · block %d", rpc.Block)
			}
		}
		if rpc.Peers >= 0 {
			chainDetail = fmt.Sprintf("%s · peers %d", chainDetail, rpc.Peers)
		}
	} else if rpcOK {
		chainDetail = fmt.Sprintf("Synced · block %d · %.1f%%", rpc.Block, verifyPct)
		if rpc.Peers >= 0 {
			chainDetail = fmt.Sprintf("%s · peers %d", chainDetail, rpc.Peers)
		}
		if rpc.ChainID != "" {
			chainDetail += " · chain " + rpc.ChainID
		}
	}

	setupSteps := []map[string]any{
		{"id": "registry", "title": "Instance registered", "done": instRegistered,
			"detail": "INSTANCE.json + /etc/rpcnode/instances.d"},
		{"id": "disk", "title": "Disk floor", "done": diskOK,
			"detail": diskDetail, "active": !diskOK && apiUp},
		{"id": "geth", "title": "BSC geth running", "done": procOK && !startBad,
			"detail": "bsc-geth process/systemd", "active": apiUp && !procOK},
		{"id": "rpc", "title": "RPC responding", "done": rpcOK,
			"detail": "eth_blockNumber", "active": nodeActive && !rpcOK},
		{"id": "sync", "title": chainTitle, "done": rpcOK && !syncing,
			"detail": chainDetail,
			"active": rpcOK && syncing,
			"pct":    map[bool]any{true: verifyPct, false: nil}[rpcOK && syncing]},
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

	nodeReady := nodeActive && rpcOK && !syncing && !startBad
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
	case syncing:
		agentActivity = "sync"
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
		"ok":         rpcOK,
		"reachable":  rpcOK,
		"http_ok":    rpcOK,
		"process_up": nodeActive,
		"port_open":  nodePortOpen,
		"syncing":    syncing,
		"height":     rpc.Block,
		"blocks":     rpc.Block,
		"peers":      rpc.Peers,
		"chain_id":   rpc.ChainID,
		"detail":     syncDetail,
	}
	if rpc.ClientVersion != "" {
		rpcBlock["client_version"] = rpc.ClientVersion
		rpcBlock["version"] = rpc.ClientVersion
	}
	if rpcOK {
		rpcBlock["initialblockdownload"] = syncing
		rpcBlock["verification_pct"] = verifyPct
		if syncing && rpc.HighestBlock > 0 {
			rpcBlock["blocks"] = rpc.CurrentBlock
			rpcBlock["headers"] = rpc.HighestBlock
			rpcBlock["height"] = rpc.CurrentBlock
		}
	}

	syncBlock := map[string]any{
		"ok":               rpcOK && !syncing,
		"syncing":          syncing,
		"ibd":              syncing, // UI nodeReadyForOps / wizard — same meaning as eth IBD
		"block":            rpc.Block,
		"blocks":           rpc.Block,
		"peers":            rpc.Peers,
		"detail":           chainDetail,
		"network":          network,
		"log_tail":         logTail,
		"verification_pct": verifyPct,
	}
	if rpcOK && syncing && rpc.HighestBlock > 0 {
		syncBlock["blocks"] = rpc.CurrentBlock
		syncBlock["headers"] = rpc.HighestBlock
		syncBlock["block"] = rpc.CurrentBlock
	}

	out := map[string]any{
		"ok":             !startBad,
		"version":        agentVersion(),
		"client_version": rpc.ClientVersion,
		"network":        network,
		"env":         cfg.Env,
		"agent_env":   cfg.Env,
		"view_env":    cfg.Env,
		"hostname":    host,
		"updated_at":  updatedAt,
		"health":      health,
		"degraded":    degraded,
		"ui_phase":    uiPhase,
		"node_status": nodeStatus,
		"lifecycle":   lifecycle,
		"setup_steps": setupSteps,
		"start_error": startErr,
		"disk_gate": map[string]any{
			"ok": diskOK, "free_gib": freeGiB, "need_gib": needGiB, "detail": diskDetail,
		},
		"snapshot": map[string]any{
			"enabled": false, "ready": true, "phase": "idle", "required": false,
		},
		"sync": syncBlock,
		"logs": map[string]any{
			"title":  "Logs",
			"source": "bsc-geth",
			"lines":  logTail,
		},
		"rpc": rpcBlock,
		"checks": map[string]any{
			"node_process_up":     procOK,
			"geth_process":        procOK,
			"node_port_open":      nodePortOpen,
			"rpc_ok":              rpcOK,
			"api_agent_up":        apiUp,
			"public_port_open":    publicPortOpen,
			"instance_registered": instRegistered,
		},
		"services": map[string]any{
			"node":         nodeSvcEffective,
			"node_unit":    cfg.NodeService,
			"api_agent":    apiSvc,
			"system_agent": "active",
		},
		"ports": map[string]any{
			"public":   publicPort,
			"agent":    agentPort,
			"upstream": cfg.UpstreamPort,
			"p2p":      cfg.P2PPort,
		},
		"paths": map[string]any{
			"data": cfg.DataDir,
			"etc":  cfg.EtcDir,
			"opt":  cfg.OptDir,
		},
		"profile": map[string]any{
			"network": network, "env": cfg.Env,
			"display_name": prof.DisplayName,
			"watch_slug":   prof.WatchSlug,
			"chain_id":     prof.ChainFlag,
		},
		"instance": map[string]any{
			"network": network, "env": cfg.Env, "id": "bsc-" + cfg.Env,
		},
		"supported_steps": SupportedLifecycleSteps(network, cfg.Env),
		"capabilities":    LifecycleCapabilitiesFor(network, cfg.Env),
		"connect": map[string]any{
			"ready":       nodeReady && apiUp,
			"public_base": base,
			"panel_base":  panelBase,
		},
		"agent": map[string]any{
			"activity":   agentActivity,
			"status":     agentStatus,
			"last_error": agentLastErr,
		},
		"process": map[string]any{
			"cmd": procCmd,
		},
	}
	if procCmd != "" {
		out["process_cmd"] = procCmd
	}

	return out
}

func bscProcessRunning(cfg Config) (bool, string) {
	data := cfg.DataDir
	if data == "" {
		data = LookupNetworkProfile(cfg.Network, cfg.Env).DataPath
	}
	env := normalizeEnvName(cfg.Env)
	out, _ := runCmd(3*time.Second, "bash", "-lc", fmt.Sprintf(
		`pgrep -af '[g]eth' | grep -E '%s|bsc-%s|/opt/bsc/%s' | head -1`,
		data, env, env,
	))
	line := strings.TrimSpace(out)
	if line == "" {
		return false, ""
	}

	return true, line
}

func bscStartFailureDetail(cfg Config, procOK bool) (detail string, bad bool) {
	unit := cfg.NodeService
	if unit == "" {
		unit = fmt.Sprintf("bsc-%s", normalizeEnvName(cfg.Env))
	}
	genesis := filepath.Join(cfg.EtcDir, "genesis.json")
	if cfg.EtcDir == "" {
		genesis = filepath.Join(LookupNetworkProfile(cfg.Network, cfg.Env).EtcPath, "genesis.json")
	}
	if !fileExists(genesis) {
		return fmt.Sprintf("genesis missing: %s", genesis), true
	}

	probe := probeSystemdUnit(unit)
	snip := journalUnitSnippet(unit+".service", 16)
	resultBad := probe.Result == "exit-code" || probe.Result == "signal" ||
		probe.Result == "resources" || probe.Result == "timeout"
	crashLoop := !procOK && resultBad && (probe.NRestarts >= 1 || probe.ActiveState == "activating")
	failed := probe.Failed || probe.ActiveState == "failed"

	if failed || crashLoop {
		msg := fmt.Sprintf("%s unit failed (Result=%s, restarts=%d)", unit, probe.Result, probe.NRestarts)
		if snip != "" {
			msg += ": " + snip
		}

		return msg, true
	}

	return "", false
}

func bscNodeReallyUp(cfg Config, nodeState string, procOK bool) bool {
	if procOK {
		return true
	}
	if _, bad := bscStartFailureDetail(cfg, procOK); bad {
		return false
	}

	return nodeState == "active" || nodeState == "activating"
}

func bscDiskGateOK(cfg Config) (ok bool, freeGiB, needGiB float64, detail string) {
	prof := LookupNetworkProfile(cfg.Network, cfg.Env)
	needGiB = prof.DiskHintGiB
	if needGiB <= 0 {
		needGiB = 400
	}
	// Soft floor: ~20% of hint (same pattern as ethereum/bitcoin).
	floor := needGiB * 0.2
	if floor < 40 {
		floor = 40
	}
	path := cfg.DataDir
	if path == "" {
		path = prof.DataPath
	}
	if path == "" {
		path = "/"
	}
	freeGiB = diskUsageGiB(path)
	if freeGiB >= floor {
		return true, freeGiB, needGiB, fmt.Sprintf("free %.0f GiB ≥ floor %.0f GiB (plan %.0f GiB)", freeGiB, floor, needGiB)
	}

	return false, freeGiB, needGiB, fmt.Sprintf("free %.0f GiB < floor %.0f GiB before BSC sync (plan %.0f GiB for %s)", freeGiB, floor, needGiB, cfg.Env)
}

func bscSyncLogTail(cfg Config, n int) []string {
	unit := cfg.NodeService
	if unit == "" {
		unit = fmt.Sprintf("bsc-%s", normalizeEnvName(cfg.Env))
	}
	out, _ := runCmd(3*time.Second, "journalctl", "-u", unit+".service", "-n", fmt.Sprintf("%d", n), "--no-pager", "-o", "cat")
	lines := strings.Split(strings.TrimSpace(out), "\n")
	if len(lines) == 1 && lines[0] == "" {
		return nil
	}

	return lines
}

func resolveBSCGethBin(cfg Config) string {
	opt := cfg.OptDir
	if opt == "" {
		opt = LookupNetworkProfile(cfg.Network, cfg.Env).OptPath
	}
	for _, cand := range []string{
		filepath.Join(opt, "bin", "geth"),
		"/usr/local/bin/bsc-geth",
		"/opt/bsc/bin/geth",
	} {
		if fileExists(cand) {
			return cand
		}
	}

	return ""
}

func ensureBSCDirs(cfg Config) error {
	for _, d := range []string{cfg.OptDir, cfg.EtcDir, cfg.DataDir} {
		if d == "" {
			continue
		}
		if err := os.MkdirAll(d, 0o755); err != nil {
			return err
		}
	}
	_ = exec.Command("chown", "-R", "nodeop:nodeop", cfg.DataDir, cfg.EtcDir, cfg.OptDir).Run()

	return nil
}
