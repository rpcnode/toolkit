package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// collectCoreLike — IBD lifecycle for ltc / dash / bch (Core-API getblockchaininfo).
func collectCoreLike(cfg Config) map[string]any {
	network := strings.ToLower(strings.TrimSpace(cfg.Network))
	client, ok := lookupCoreLikeSA(network)
	if !ok {
		return map[string]any{"ok": false, "error": "unsupported_corelike", "network": network}
	}
	prof := LookupNetworkProfile(network, cfg.Env)

	// Every collect: ensure nest dir + nodeop ownership (ltc → testnet4). systemd may
	// auto-restart litecoind before pipeline startNode; without this, Permission denied loops.
	_ = ensureCoreLikeDataDirs(cfg)

	nodeState := systemctlActive(cfg.NodeService)
	procOK, _ := coreLikeDaemonRunningFor(cfg, client.Daemon)
	startErr, startBad := coreLikeStartFailureDetail(cfg, client, procOK)
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
	regtest := isBitcoinRegtest(cfg.Env)
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

	diskOK, freeGiB, needGiB, diskDetail := coreLikeDiskGateOK(cfg, prof, network)

	headerSyncPct := parseCoreHeaderSyncPct(coreLikeDebugLogTail(cfg, 40))
	if headerSyncPct <= 0 {
		headerSyncPct = parseCoreHeaderSyncPct(coreLikeJournalLogLines(cfg, 40))
	}
	honestPct := coreHonestIBDPct(chain.Blocks, chain.Headers, chain.Verify, headerSyncPct)
	// lifecycle VerifyPct is 0..1
	honestVerify := honestPct / 100

	if rpcOK {
		chainForLog := chain
		chainForLog.Verify = honestVerify
		maybeAppendBitcoinSyncLog(cfg, chainForLog)
	} else {
		// Pre-RPC / restart: still heartbeat sync.log so Logs modal is never blank.
		maybeAppendCoreLikeWaitingLog(cfg, client, nodeActive, startErr)
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
	}
	syncDetail := chain.Error
	if rpcOK {
		switch {
		case regtest:
			syncDetail = fmt.Sprintf("Regtest · height %d", chain.Blocks)
			verifyPct = 100
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
			if verifyPct >= 99.9 {
				verifyPct = 99.8
			}
		default:
			syncDetail = fmt.Sprintf("Synced · height %d", chain.Blocks)
		}
	}

	setupSteps := []map[string]any{
		{"id": "registry", "title": "Instance registered", "done": instRegistered,
			"detail": "INSTANCE.json + /etc/rpcnode/instances.d"},
		{"id": "disk", "title": "Disk floor for IBD", "done": diskOK,
			"detail": diskDetail, "active": !diskOK && apiUp},
		{"id": "node", "title": client.Daemon + " running", "done": nodeActive,
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

	logTail := coreLikeLogTail(cfg, client, 80)

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
			client.Daemon + "_process": procOK, "node_port_open": nodePortOpen,
		},
		"ports": map[string]any{
			"public": publicPort, "agent": agentPort, "node_http": cfg.UpstreamPort,
		},
		"start_error": startErr,
		"logs": map[string]any{
			"title":  "Logs",
			"source": network + "-sync",
			"lines":  logTail,
		},
		"connect": bitcoinFamilyConnectMap(cfg, base, nodeReady, client.Daemon, ""),
		"version":        agentVersion(),
		"client_version": chain.ClientVersion,
	}
}

type coreLikeSA struct {
	Daemon   string
	ConfName string
}

func lookupCoreLikeSA(network string) (coreLikeSA, bool) {
	switch strings.ToLower(strings.TrimSpace(network)) {
	case "ltc":
		return coreLikeSA{Daemon: "litecoind", ConfName: "litecoin.conf"}, true
	case "dash":
		return coreLikeSA{Daemon: "dashd", ConfName: "dash.conf"}, true
	case "bch":
		return coreLikeSA{Daemon: "bitcoind", ConfName: "bitcoin.conf"}, true
	default:
		return coreLikeSA{}, false
	}
}

func networkIsCoreLikeSA(network string) bool {
	_, ok := lookupCoreLikeSA(network)
	return ok
}

func coreLikeDaemonRunningFor(cfg Config, daemon string) (bool, string) {
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
			if cmd != "" && strings.Contains(cmd, daemon) {
				return true, cmd
			}
		}
	}
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		data = LookupNetworkProfile(cfg.Network, cfg.Env).DataPath
	}
	// Escape for grep -F; match datadir so BCH bitcoind ≠ Bitcoin Core.
	out, err := runCmd(2*time.Second, "bash", "-lc",
		fmt.Sprintf(`ps -eo pid=,args= | grep -F %q | grep -F %q | grep -v grep | head -1`, daemon, data))
	if err != nil || strings.TrimSpace(out) == "" {
		return false, ""
	}

	return true, strings.TrimSpace(out)
}

func coreLikeStartFailureDetail(cfg Config, client coreLikeSA, procOK bool) (string, bool) {
	if procOK {
		return "", false
	}
	state := systemctlActive(cfg.NodeService)
	if systemctlFailed(cfg.NodeService) || state == "failed" {
		snip := journalUnitSnippet(cfg.NodeService, 16)
		if snip != "" {
			return snip, true
		}

		return client.Daemon + " unit failed", true
	}

	return "", false
}

func coreLikeDiskGateOK(cfg Config, prof NetworkProfile, network string) (ok bool, freeGiB, needGiB float64, detail string) {
	needGiB = prof.DiskHintGiB
	if needGiB <= 0 {
		needGiB = 200
	}
	floor := needGiB * 0.1
	if floor < 40 {
		floor = 40
	}
	freeGiB = freeDiskGiB(cfg.DataDir)
	ok = freeGiB >= floor
	detail = fmt.Sprintf("%.0f GiB free (floor %.0f GiB for %s IBD; hint %.0f GiB)", freeGiB, floor, network, needGiB)

	return ok, freeGiB, needGiB, detail
}

func coreLikeLogTail(cfg Config, client coreLikeSA, n int) []string {
	if n <= 0 {
		n = 80
	}
	samples := textLogTail(bitcoinSyncLogPath(cfg), n)
	debugLines := coreLikeDebugLogTail(cfg, n)
	journalLines := coreLikeJournalLogLines(cfg, n)
	merged := mergeLogTails(journalLines, debugLines, n)
	merged = mergeLogTails(merged, samples, n)
	if len(merged) > 0 {
		return merged
	}
	if len(samples) > 0 {
		return samples
	}
	if len(debugLines) > 0 {
		return debugLines
	}
	if len(journalLines) > 0 {
		return journalLines
	}
	// Always surface something in Logs modal during install/IBD (empty ≠ "nothing happens").
	unit := strings.TrimSpace(cfg.NodeService)
	if unit == "" {
		unit = fmt.Sprintf("%s-%s", strings.ToLower(cfg.Network), normalizeEnvName(cfg.Env))
	}
	debugPath := coreLikeDebugLogPath(cfg)
	syncPath := bitcoinSyncLogPath(cfg)
	return []string{
		fmt.Sprintf("%s · waiting for %s log lines (journalctl -u %s · %s · %s)",
			time.Now().UTC().Format("15:04:05Z"), client.Daemon, unit, syncPath, debugPath),
	}
}

// maybeAppendCoreLikeWaitingLog — rate-limited sync.log line before getblockchaininfo works.
func maybeAppendCoreLikeWaitingLog(cfg Config, client coreLikeSA, nodeActive bool, startErr string) {
	now := time.Now()
	state := "waiting for JSON-RPC"
	if startErr != "" {
		state = "start error"
	} else if !nodeActive {
		state = "daemon not running"
	}
	line := fmt.Sprintf("%s  %s  %s  ·  %s",
		now.UTC().Format("15:04:05Z"), strings.ToLower(cfg.Network), client.Daemon, state)

	bitcoinSyncLog.mu.Lock()
	defer bitcoinSyncLog.mu.Unlock()
	gapOK := bitcoinSyncLog.lastWrite.IsZero() || now.Sub(bitcoinSyncLog.lastWrite) >= bitcoinSyncLogMinGap
	if !gapOK && bitcoinSyncLog.lastLine != "" {
		return
	}
	path := bitcoinSyncLogPath(cfg)
	_ = os.MkdirAll(filepath.Dir(path), 0o755)
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	_, _ = f.WriteString(line + "\n")
	_ = f.Close()
	bitcoinSyncLog.lastLine = line
	bitcoinSyncLog.lastWrite = now
	trimBitcoinSyncLogFile(path, bitcoinSyncLogMaxLines)
}

func coreLikeJournalLogLines(cfg Config, n int) []string {
	unit := strings.TrimSpace(cfg.NodeService)
	if unit == "" {
		unit = fmt.Sprintf("%s-%s.service", strings.ToLower(cfg.Network), normalizeEnvName(cfg.Env))
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	snip := journalUnitSnippet(unit, n)
	if snip == "" {
		return nil
	}
	out := make([]string, 0, n)
	for _, ln := range strings.Split(snip, "\n") {
		ln = strings.TrimSpace(ln)
		if ln == "" {
			continue
		}
		out = append(out, ln)
	}
	if len(out) > n {
		out = out[len(out)-n:]
	}

	return out
}

func coreLikeDebugLogTail(cfg Config, n int) []string {
	path := coreLikeDebugLogPath(cfg)
	if path == "" || !fileExists(path) {
		return nil
	}
	if n <= 0 {
		n = 80
	}
	out, err := runCmd(4*time.Second, "tail", "-n", strconv.Itoa(n*4), path)
	if err != nil || strings.TrimSpace(out) == "" {
		return nil
	}
	raw := strings.Split(out, "\n")
	interesting := make([]string, 0, n)
	fallback := make([]string, 0, n)
	for _, ln := range raw {
		ln = strings.TrimSpace(ln)
		if ln == "" {
			continue
		}
		if len(ln) > 400 {
			ln = ln[:400] + "…"
		}
		fallback = append(fallback, ln)
		low := strings.ToLower(ln)
		if strings.Contains(low, "updatetip") ||
			strings.Contains(low, "progress=") ||
			strings.Contains(low, "leaving initialblockdownload") ||
			strings.Contains(low, "update tip") ||
			strings.Contains(low, "error") ||
			strings.Contains(low, "warning") ||
			strings.Contains(low, "loaded block") ||
			strings.Contains(low, "synchronizing") ||
			strings.Contains(low, "connectblock") {
			interesting = append(interesting, ln)
		}
	}
	pick := interesting
	if len(pick) == 0 {
		pick = fallback
	}
	if len(pick) > n {
		pick = pick[len(pick)-n:]
	}

	return pick
}
