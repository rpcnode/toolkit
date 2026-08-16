package main

import (
	"fmt"
	"strconv"
	"strings"
	"time"
)

// collectDoge — IBD lifecycle for network=doge (dogecoind + rpcuser auth).
func collectDoge(cfg Config) map[string]any {
	network := cfg.Network
	if network == "" {
		network = "doge"
	}
	prof := LookupNetworkProfile(network, cfg.Env)

	nodeState := systemctlActive(cfg.NodeService)
	procOK, _ := dogecoindRunningFor(cfg)
	startErr, startBad := dogeStartFailureDetail(cfg, procOK)
	nodeActive := procOK && !startBad
	if !nodeActive && (nodeState == "active" || nodeState == "activating") {
		nodeActive = !startBad
	}

	var chain bitcoinChainInfo
	var nodePortOpen bool
	if nodeActive {
		nodePortOpen = portOpen(cfg.UpstreamHost, cfg.UpstreamPort)
		if nodePortOpen {
			chain = getBlockchainInfo(cfg)
		}
	}
	rpcOK := chain.OK
	displayIBD := chain.IBD
	historyGap := coreHistoryMissing(chain, isBitcoinRegtest(cfg.Env))
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

	diskOK, freeGiB, needGiB, diskDetail := dogeDiskGateOK(cfg, prof)

	headerSyncPct := parseCoreHeaderSyncPct(dogeDebugLogTail(cfg, 40))
	if headerSyncPct <= 0 {
		headerSyncPct = parseCoreHeaderSyncPct(strings.Split(journalUnitSnippet(cfg.NodeService, 40), "\n"))
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

	verifyPct := honestPct
	if !rpcOK {
		verifyPct = 0
	} else if historyGap && verifyPct >= 99.9 {
		verifyPct = 99.8
	}
	syncDetail := chain.Error
	if rpcOK {
		switch {
		case displayIBD:
			syncDetail = fmt.Sprintf(
				"IBD · blocks %d / headers %d · %s",
				chain.Blocks, chain.Headers, formatCoreSyncPct(verifyPct),
			)
			if chain.Peers >= 0 {
				syncDetail = fmt.Sprintf("%s · peers %d", syncDetail, chain.Peers)
			}
		case historyGap:
			syncDetail = fmt.Sprintf("Pruned · not full history · height %d", chain.Blocks)
		default:
			syncDetail = fmt.Sprintf("Synced · height %d", chain.Blocks)
		}
	}

	setupSteps := []map[string]any{
		{"id": "registry", "title": "Instance registered", "done": instRegistered,
			"detail": "INSTANCE.json + /etc/rpcnode/instances.d"},
		{"id": "disk", "title": "Disk floor for IBD", "done": diskOK,
			"detail": diskDetail, "active": !diskOK && apiUp},
		{"id": "node", "title": "dogecoind running", "done": nodeActive,
			"detail": "process/systemd", "active": apiUp && !nodeActive},
		{"id": "rpc", "title": "JSON-RPC responding", "done": rpcOK,
			"detail": "getblockchaininfo", "active": nodeActive && !rpcOK},
		{"id": "ibd", "title": "IBD complete", "done": rpcOK && !syncing,
			"detail": map[bool]string{
				true:  syncDetail,
				false: "initialblockdownload=false",
			}[rpcOK && syncing],
			"active": rpcOK && syncing,
			"pct":    map[bool]any{true: verifyPct, false: nil}[rpcOK && syncing]},
		{"id": "api", "title": "API agent up", "done": apiUp,
			"detail": fmt.Sprintf(":%d /healthz", apiProbePort)},
	}

	logTail := dogeLogTail(cfg, 80)

	base, _ := effectivePublicBases(cfg)
	nodeReady := rpcOK && !syncing

	return map[string]any{
		"ok": true, "ts": time.Now().UTC().Format(time.RFC3339),
		"network": network, "env": cfg.Env,
		"ui_phase": uiPhase, "node_status": nodeStatus,
		"lifecycle":   lifecycle,
		"setup_steps": setupSteps,
		"disk_gate": map[string]any{
			"ok": diskOK, "free_gib": freeGiB, "need_gib": needGiB, "detail": diskDetail,
		},
		"sync": map[string]any{
			"ok": rpcOK && !syncing, "syncing": syncing, "ibd": syncing, "pruned": chain.Pruned,
			"block": chain.Blocks, "blocks": chain.Blocks, "headers": chain.Headers,
			"peers": chain.Peers, "size_on_disk": chain.SizeOnDisk,
			"size_on_disk_gb": round1(float64(chain.SizeOnDisk) / (1024 * 1024 * 1024)),
			"verificationprogress": chain.Verify, "verification_pct": verifyPct,
			"verify_pct": chain.Verify, "detail": syncDetail,
			"network": network, "log_tail": logTail,
		},
		"rpc": map[string]any{
			"ok": rpcOK, "height": chain.Blocks, "blocks": chain.Blocks,
			"headers": chain.Headers, "peers": chain.Peers,
			"initialblockdownload": syncing,
			"verificationprogress": chain.Verify, "verification_pct": verifyPct,
			"client_version":       chain.ClientVersion,
		},
		"services": map[string]any{
			"node": nodeSvcEffective, "api": apiSvc,
		},
		"checks": map[string]any{
			"node_process_up": procOK, "bitcoind_process": procOK,
			"dogecoind_process": procOK, "node_port_open": nodePortOpen,
		},
		"ports": map[string]any{
			"public": publicPort, "agent": agentPort, "node_http": cfg.UpstreamPort,
		},
		"start_error": startErr,
		"logs": map[string]any{
			"title":  "Logs",
			"source": "doge-sync",
			"lines":  logTail,
		},
		"connect":        bitcoinFamilyConnectMap(cfg, base, nodeReady, "dogecoind", ""),
		"version":        agentVersion(),
		"client_version": chain.ClientVersion,
	}
}

func dogecoindRunningFor(cfg Config) (bool, string) {
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
			if cmd != "" && strings.Contains(cmd, "dogecoind") {
				return true, cmd
			}
		}
	}
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		data = LookupNetworkProfile(cfg.Network, cfg.Env).DataPath
	}
	out, err := runCmd(2*time.Second, "bash", "-lc",
		fmt.Sprintf(`ps -eo pid=,args= | grep -E '[d]ogecoind' | grep -F %q | head -1`, data))
	if err != nil || strings.TrimSpace(out) == "" {
		return false, ""
	}

	return true, strings.TrimSpace(out)
}

func dogeStartFailureDetail(cfg Config, procOK bool) (string, bool) {
	if procOK {
		return "", false
	}
	state := systemctlActive(cfg.NodeService)
	if systemctlFailed(cfg.NodeService) || state == "failed" {
		snip := journalUnitSnippet(cfg.NodeService, 16)
		if snip != "" {
			return snip, true
		}

		return "dogecoind unit failed", true
	}

	return "", false
}

func dogeDiskGateOK(cfg Config, prof NetworkProfile) (ok bool, freeGiB, needGiB float64, detail string) {
	needGiB = prof.DiskHintGiB
	if needGiB <= 0 {
		needGiB = 200
	}
	// Soft gate: require ~10% of hint free (IBD grows over time).
	floor := needGiB * 0.1
	if floor < 40 {
		floor = 40
	}
	freeGiB = freeDiskGiB(cfg.DataDir)
	ok = freeGiB >= floor
	detail = fmt.Sprintf("%.0f GiB free (floor %.0f GiB for doge IBD; hint %.0f GiB)", freeGiB, floor, needGiB)

	return ok, freeGiB, needGiB, detail
}
