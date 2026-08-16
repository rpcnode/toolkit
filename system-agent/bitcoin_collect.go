package main

import (
	"fmt"
	"strings"
	"time"
)

// collectBitcoin — IBD lifecycle for network=bitcoin (no TRON snapshot).
func collectBitcoin(cfg Config) map[string]any {
	network := cfg.Network
	if network == "" {
		network = "bitcoin"
	}
	prof := LookupNetworkProfile(network, cfg.Env)

	nodeState := systemctlActive(cfg.NodeService)
	procOK, procCmd := bitcoindRunningFor(cfg)
	startErr, startBad := bitcoinStartFailureDetail(cfg, procOK)
	nodeActive := bitcoinNodeReallyUp(cfg, nodeState, procOK)

	var chain bitcoinChainInfo
	var nodePortOpen bool
	if nodeActive {
		nodePortOpen = portOpen(cfg.UpstreamHost, cfg.UpstreamPort)
		if nodePortOpen {
			chain = getBlockchainInfo(cfg)
		}
	}
	rpcOK := chain.OK
	regtest := isBitcoinRegtest(cfg.Env)
	// Regtest: ignore bitcoind initialblockdownload for lifecycle/UI (local chain, not IBD).
	displayIBD := chain.IBD && !regtest
	historyGap := coreHistoryMissing(chain, regtest)
	syncing := displayIBD || historyGap

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

	diskOK, freeGiB, needGiB, diskDetail := bitcoinDiskGateOK(cfg)

	headerSyncPct := parseCoreHeaderSyncPct(bitcoinSyncLogTail(cfg, 40))
	if headerSyncPct <= 0 {
		unit := strings.TrimSpace(cfg.NodeService)
		if unit == "" {
			unit = "bitcoin-" + normalizeEnvName(cfg.Env)
		}
		headerSyncPct = parseCoreHeaderSyncPct(strings.Split(journalUnitSnippet(unit, 40), "\n"))
	}
	honestPct := coreHonestIBDPct(chain.Blocks, chain.Headers, chain.Verify, headerSyncPct)
	honestVerify := honestPct / 100
	if rpcOK {
		chainForLog := chain
		chainForLog.Verify = honestVerify
		maybeAppendBitcoinSyncLog(cfg, chainForLog)
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
		NodeActive:     nodeActive,
		StartError:     startErr,
		RPCOK:          rpcOK,
		IBD:            syncing,
		VerifyPct:      honestVerify,
		Progress:       prog,
	}
	if rpcOK {
		lcIn.Height = chain.Blocks
		lcIn.Headers = chain.Headers
		lcIn.SizeOnDisk = chain.SizeOnDisk
		if chain.Peers >= 0 {
			lcIn.Peers = int(chain.Peers)
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

	diskTitle := "Disk floor for IBD"
	chainStepTitle := "IBD complete"
	chainStepDetail := map[bool]string{
		true:  fmt.Sprintf("IBD · blocks %d / headers %d", chain.Blocks, chain.Headers),
		false: "initialblockdownload=false",
	}[rpcOK && displayIBD]
	if regtest {
		diskTitle = "Disk floor"
		chainStepTitle = "Regtest ready"
		if rpcOK {
			chainStepDetail = fmt.Sprintf("Regtest · blocks %d · peers %d", chain.Blocks, chain.Peers)
		} else {
			chainStepDetail = "Waiting for bitcoind RPC"
		}
	}

	setupSteps := []map[string]any{
		{"id": "registry", "title": "Instance registered", "done": instRegistered,
			"detail": "INSTANCE.json + /etc/rpcnode/instances.d"},
		{"id": "disk", "title": diskTitle, "done": diskOK,
			"detail": diskDetail, "active": !diskOK && apiUp},
		{"id": "node", "title": "bitcoind running", "done": nodeActive,
			"detail": "process/systemd", "active": apiUp && !nodeActive},
		{"id": "rpc", "title": "RPC responding", "done": rpcOK,
			"detail": "getblockchaininfo", "active": nodeActive && !rpcOK},
		{"id": "ibd", "title": chainStepTitle, "done": rpcOK && !syncing,
			"detail": chainStepDetail,
			"active": rpcOK && syncing,
			"pct":    map[bool]any{true: 100.0, false: chain.Verify * 100}[rpcOK && !syncing]},
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
	case syncing || (!regtest && uiPhase == "run"):
		agentActivity = "ibd"
		agentStatus = "degraded"
	case nodeReady || uiPhase == "healthy" || (regtest && uiPhase == "run" && rpcOK):
		agentActivity = "online"
	default:
		if health == "degraded" || health == "setup" {
			agentStatus = "degraded"
		}
	}

	host := hostname()
	base, panelBase := effectivePublicBases(cfg)

	verifyPct := 0.0
	if rpcOK {
		verifyPct = honestPct
		if !syncing {
			verifyPct = 100
		}
	}

	updatedAt := time.Now().UTC().Format(time.RFC3339)
	logTail := bitcoinSyncLogTail(cfg, 80)

	rpcBlock := map[string]any{
		"ok":                   rpcOK,
		"reachable":            rpcOK,
		"http_ok":              rpcOK,
		"process_up":           nodeActive,
		"port_open":            nodePortOpen,
		"blocks":               chain.Blocks,
		"headers":              chain.Headers,
		"initialblockdownload": syncing,
		"pruned":               chain.Pruned,
		"verificationprogress": chain.Verify,
		"verification_pct":     verifyPct,
		"size_on_disk":         chain.SizeOnDisk,
		"peers":                chain.Peers,
		"chain":                chain.Chain,
		"error":                chain.Error,
		"node_height":          nil,
	}
	if rpcOK {
		rpcBlock["node_height"] = chain.Blocks
	}
	if chain.ClientVersion != "" {
		rpcBlock["client_version"] = chain.ClientVersion
		rpcBlock["version"] = chain.ClientVersion
	}

	syncBlock := map[string]any{
		"network":              network,
		"ibd":                  syncing,
		"syncing":              syncing,
		"pruned":               chain.Pruned,
		"blocks":               chain.Blocks,
		"headers":              chain.Headers,
		"verificationprogress": chain.Verify,
		"verification_pct":     verifyPct,
		"size_on_disk":         chain.SizeOnDisk,
		"size_on_disk_gb":      round1(float64(chain.SizeOnDisk) / (1024 * 1024 * 1024)),
		"peers":                chain.Peers,
		"chain":                chain.Chain,
		"ok":                   rpcOK && !syncing,
		"updated_at":           updatedAt,
		"log_tail":             logTail,
		"detail":               "",
	}
	if rpcOK {
		switch {
		case regtest:
			syncBlock["detail"] = fmt.Sprintf("Regtest · blocks %d", chain.Blocks)
		case displayIBD:
			syncBlock["detail"] = fmt.Sprintf(
				"IBD · blocks %d / headers %d · %s",
				chain.Blocks, chain.Headers, formatCoreSyncPct(verifyPct),
			)
		case historyGap:
			syncBlock["detail"] = fmt.Sprintf("Pruned · not full history · height %d", chain.Blocks)
		default:
			syncBlock["detail"] = fmt.Sprintf("Synced · height %d", chain.Blocks)
		}
		if chain.Peers >= 0 {
			syncBlock["detail"] = fmt.Sprintf("%s · peers %d", syncBlock["detail"], chain.Peers)
		}
	} else if chain.Error != "" {
		syncBlock["detail"] = chain.Error
	} else if nodeActive {
		syncBlock["detail"] = "Waiting for bitcoind RPC"
	} else {
		syncBlock["detail"] = "bitcoind not running"
	}

	var height any
	if rpcOK {
		height = chain.Blocks
	}

	return map[string]any{
		"ok":               true,
		"degraded":         degraded,
		"health":           health,
		"ui_phase":         uiPhase,
		"node_status":      nodeStatus,
		"lifecycle":        lifecycle,
		"supported_steps":  prof.SupportedLifecycleSteps(),
		"capabilities":     prof.LifecycleCapabilities(),
		"env":              cfg.Env,
		"network":          network,
		"updated_at":       updatedAt,
		"managed_by":       "RpcNode toolkit",
		"agent_version":    agentVersion(),
		"client_version":   chain.ClientVersion,
		"agent": map[string]any{
			"role":       "system",
			"version":    agentVersion(),
			"status":     agentStatus,
			"activity":   agentActivity,
			"last_error": agentLastErr,
			"interval":   cfg.Interval.String(),
			"internal":   cfg.InternalListen,
		},
		"instance": map[string]any{
			"id": fmt.Sprintf("%s-%s", network, cfg.Env), "network": network, "env": cfg.Env,
			"hostname": host, "public_base_url": base, "panel_base_url": panelBase,
			"public_port": publicPort, "agent_port": agentPort,
			"node_rpc_port": cfg.UpstreamPort, "p2p_port": cfg.P2PPort,
			"data_dir": cfg.DataDir, "etc_dir": cfg.EtcDir, "opt_dir": cfg.OptDir,
			"registered": instRegistered, "watch_slug": prof.WatchSlug,
			"managed_by": "RpcNode toolkit",
		},
		"setup": map[string]any{
			"complete": uiPhase == "healthy", "phase": uiPhase,
			"steps": setupSteps, "lifecycle_steps": lifecycle["steps"],
		},
		"disk_gate": map[string]any{
			"ok": diskOK, "free_gb": freeGiB, "planned_gb": needGiB, "detail": diskDetail,
		},
		"checks": map[string]any{
			"bitcoind_process": procOK,
			"bitcoind_cmd":     trimCmd(procCmd),
			"node_process_up":  nodeActive,
			"node_port_open":   nodePortOpen,
			"node_rpc_ok":      rpcOK,
			"api_port_open":    apiPortOpen,
			"api_healthz":      apiHealth,
			"disk_gate_ok":     diskOK,
			"bitcoin_conf":     bitcoinConfPath(cfg),
			"bitcoin_conf_ok":  fileExists(bitcoinConfPath(cfg)),
			"start_error":      startErr,
		},
		"snapshot": map[string]any{
			"enabled": false, "ready": false, "phase": "skipped",
			"detail": map[bool]string{
				true:  "Regtest — snapshot step not used",
				false: "IBD only — snapshot step not used",
			}[regtest],
			"can_start": false, "can_stop": false, "failed": false,
		},
		"sync": syncBlock,
		// Agent-owned lines for panel Logs (UI only renders — no SQLite invent).
		"logs": map[string]any{
			"title":  "Logs",
			"source": "bitcoin-sync",
			"lines":  logTail,
		},
		"rpc":        rpcBlock,
		"blockchain": rpcBlock,
		"services":     map[string]any{"node": nodeSvcEffective, "api": apiSvc, "system": "active"},
		"version":      map[string]any{"toolkit": agentVersion(), "agent": agentVersion(), "node": "bitcoind"},
		// Client endpoint = Go public_port (e.g. :39290), never agent_port (:39390).
		"connect": bitcoinFamilyConnectMap(
			cfg, base, nodeReady, "bitcoind",
			fmt.Sprintf("curl -s %s/rest/chaininfo.json", strings.TrimRight(base, "/")),
		),
		"node_height": height,
	}
}
