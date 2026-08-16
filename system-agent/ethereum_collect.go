package main

import (
	"fmt"
	"strings"
	"time"
)

// collectEthereum — Geth + Lighthouse EL/CL lifecycle (no TRON snapshot).
func collectEthereum(cfg Config) map[string]any {
	network := cfg.Network
	if network == "" {
		network = "ethereum"
	}
	prof := LookupNetworkProfile(network, cfg.Env)

	gethState := systemctlActive(cfg.NodeService)
	gethOK, gethCmd := gethProcessRunning(cfg)
	lhUnit := envOr("TRON_LIGHTHOUSE_SERVICE", ethereumLighthouseUnit(cfg.Env))
	lhState := systemctlActive(lhUnit)
	lhOK, lhCmd := lighthouseProcessRunning(cfg)

	startErr, startBad := ethereumStartFailureDetail(cfg, gethOK, lhOK)
	nodeActive := ethereumNodeReallyUp(cfg, gethState, gethOK, lhOK)

	var rpc ethereumRPCResult
	var nodePortOpen bool
	beaconPort := ethereumBeaconPort(cfg)
	lhSyncing, lhDetail := probeLighthouseSync(beaconPort)

	if nodeActive {
		nodePortOpen = portOpen(cfg.UpstreamHost, cfg.UpstreamPort)
		if nodePortOpen {
			rpc = probeEthereumRPC(cfg)
		}
	}
	rpcOK := rpc.OK
	elSyncing := rpcOK && rpc.Syncing
	syncing := elSyncing || lhSyncing
	syncDetail := rpc.SyncDetail
	verifyPct := ethSyncVerificationPct(rpc.CurrentBlock, rpc.HighestBlock, elSyncing)
	if !rpcOK {
		verifyPct = 0
	} else if !elSyncing && lhSyncing {
		// EL at tip; CL still catching — do not paint 0 (empty bar).
		verifyPct = 99.9
	}
	if lhSyncing && lhDetail != "" {
		if syncDetail != "" {
			syncDetail = syncDetail + " · " + lhDetail
		} else {
			syncDetail = lhDetail
		}
	}

	nodeSvcEffective := gethState
	switch {
	case startBad:
		nodeSvcEffective = "failed"
	case nodeActive && rpcOK && !syncing:
		nodeSvcEffective = "active"
	case nodeActive || nodePortOpen:
		if gethState != "active" {
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

	diskOK, freeGiB, needGiB, diskDetail := ethereumDiskGateOK(cfg)

	logTail := ethereumSyncLogTail(cfg, 80)
	warmupDetail := ""
	if nodeActive && !rpcOK && !startBad {
		warmupDetail = "geth warming up · waiting for JSON-RPC"
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
		if elSyncing && rpc.HighestBlock > 0 {
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

	chainTitle := "EL/CL sync"
	chainDetail := "Waiting for eth_syncing"
	if !rpcOK && warmupDetail != "" {
		chainDetail = warmupDetail
	} else if rpcOK && syncing {
		chainDetail = syncDetail
		if chainDetail == "" {
			chainDetail = fmt.Sprintf("Syncing · block %d · peers %d", rpc.Block, rpc.Peers)
		}
	} else if rpcOK {
		chainDetail = fmt.Sprintf("Synced · block %d · peers %d", rpc.Block, rpc.Peers)
		if rpc.ChainID != "" {
			chainDetail += " · chain " + rpc.ChainID
		}
	}

	setupSteps := []map[string]any{
		{"id": "registry", "title": "Instance registered", "done": instRegistered,
			"detail": "INSTANCE.json + /etc/rpcnode/instances.d"},
		{"id": "disk", "title": "Disk floor", "done": diskOK,
			"detail": diskDetail, "active": !diskOK && apiUp},
		{"id": "geth", "title": "Geth running", "done": gethOK && !startBad,
			"detail": "EL process/systemd", "active": apiUp && !gethOK},
		{"id": "lighthouse", "title": "Lighthouse running", "done": lhOK && !startBad,
			"detail": "CL process/systemd", "active": gethOK && !lhOK},
		{"id": "rpc", "title": "RPC responding", "done": rpcOK,
			"detail": "eth_blockNumber", "active": nodeActive && !rpcOK},
		{"id": "sync", "title": chainTitle, "done": rpcOK && !syncing,
			"detail": chainDetail,
			"active": rpcOK && syncing},
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

	if rpcOK {
		maybeAppendEthereumSyncLog(cfg, rpc, syncing)
	}

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
	lhVer := probeLighthouseVersion(beaconPort)
	if lhVer == "" {
		lhVer = lighthouseBinaryVersion(cfg)
	}
	clientVer := formatEthereumClientVersion(rpc.ClientVersion, lhVer)
	if clientVer != "" {
		rpcBlock["client_version"] = clientVer
		rpcBlock["version"] = clientVer
	}
	if rpc.ClientVersion != "" {
		rpcBlock["geth_version"] = formatClientVersion(rpc.ClientVersion)
	}
	if lhVer != "" {
		rpcBlock["lighthouse_version"] = formatLighthouseClientVersion(lhVer)
	}
	if rpcOK {
		rpcBlock["initialblockdownload"] = syncing
		rpcBlock["verification_pct"] = verifyPct
		if elSyncing && rpc.HighestBlock > 0 {
			rpcBlock["blocks"] = rpc.CurrentBlock
			rpcBlock["headers"] = rpc.HighestBlock
			rpcBlock["height"] = rpc.CurrentBlock
		}
	}

	syncBlock := map[string]any{
		"ok":               rpcOK && !syncing,
		"syncing":          syncing,
		"ibd":              syncing,
		"block":            rpc.Block,
		"blocks":           rpc.Block,
		"peers":            rpc.Peers,
		"detail":           chainDetail,
		"network":          network,
		"log_tail":         logTail,
		"verification_pct": verifyPct,
		"beacon": map[string]any{
			"port":    beaconPort,
			"syncing": lhSyncing,
			"detail":  lhDetail,
		},
	}
	if rpcOK && elSyncing && rpc.HighestBlock > 0 {
		syncBlock["blocks"] = rpc.CurrentBlock
		syncBlock["headers"] = rpc.HighestBlock
		syncBlock["block"] = rpc.CurrentBlock
	}

	procCmd := strings.TrimSpace(gethCmd)
	if lhCmd != "" {
		if procCmd != "" {
			procCmd += " | " + lhCmd
		} else {
			procCmd = lhCmd
		}
	}

	out := map[string]any{
		"ok":             !startBad,
		"version":        agentVersion(),
		"client_version": clientVer,
		"network":        network,
		"env":           cfg.Env,
		"agent_env":     cfg.Env,
		"view_env":      cfg.Env,
		"hostname":      host,
		"updated_at":    updatedAt,
		"health":        health,
		"degraded":      degraded,
		"ui_phase":      uiPhase,
		"node_status":   nodeStatus,
		"lifecycle":     lifecycle,
		"setup_steps":   setupSteps,
		"start_error":   startErr,
		"disk_gate": map[string]any{
			"ok": diskOK, "free_gib": freeGiB, "need_gib": needGiB, "detail": diskDetail,
		},
		"snapshot": map[string]any{
			"enabled": false, "ready": true, "phase": "idle", "required": false,
		},
		"sync": syncBlock,
		"logs": map[string]any{
			"title":  "Logs",
			"source": "geth",
			"lines":  logTail,
		},
		"rpc": rpcBlock,
		"checks": map[string]any{
			"node_process_up":     gethOK && lhOK,
			"geth_process":        gethOK,
			"lighthouse_process":  lhOK,
			"node_port_open":      nodePortOpen,
			"rpc_ok":              rpcOK,
			"api_agent_up":        apiUp,
			"public_port_open":    publicPortOpen,
			"instance_registered": instRegistered,
		},
		"services": map[string]any{
			"node":         nodeSvcEffective,
			"node_unit":    cfg.NodeService,
			"lighthouse":   lhState,
			"lighthouse_unit": lhUnit,
			"api_agent":    apiSvc,
			"system_agent": "active",
		},
		"ports": map[string]any{
			"public":   publicPort,
			"agent":    agentPort,
			"upstream": cfg.UpstreamPort,
			"p2p":      cfg.P2PPort,
			"engine":   ethereumEnginePort(cfg),
			"beacon":   beaconPort,
		},
		"paths": map[string]any{
			"data":       cfg.DataDir,
			"etc":        cfg.EtcDir,
			"opt":        cfg.OptDir,
			"geth":       cfg.DataDir + "/geth",
			"lighthouse": cfg.DataDir + "/lighthouse",
			"jwt":        ethereumJWTPath(cfg),
		},
		"profile": map[string]any{
			"network": network, "env": cfg.Env,
			"display_name": prof.DisplayName,
			"watch_slug":   prof.WatchSlug,
			"chain_id":     prof.ChainFlag,
		},
		"instance": map[string]any{
			"network": network, "env": cfg.Env, "id": "ethereum-" + cfg.Env,
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
