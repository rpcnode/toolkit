package main

import (
	"fmt"
	"strconv"
	"strings"
	"time"
)

// collectZcash — sync lifecycle for network=zcash (Zebra zebrad).
// Honest %: getblockchaininfo.verificationprogress (+ estimatedheight/blocks).
// zcashd is EOL (halt height 3417100, 2026-07-18) — do not treat as supported client.
func collectZcash(cfg Config) map[string]any {
	network := cfg.Network
	if network == "" {
		network = "zcash"
	}
	prof := LookupNetworkProfile(network, cfg.Env)

	nodeState := systemctlActive(cfg.NodeService)
	procOK, _ := zcashdRunningFor(cfg)
	startErr, startBad := zcashStartFailureDetail(cfg, procOK)
	nodeActive := procOK && !startBad
	if !nodeActive && (nodeState == "active" || nodeState == "activating") {
		nodeActive = !startBad
	}

	var chain bitcoinChainInfo
	var estimatedHeight int64
	var ibdComplete bool
	var nodePortOpen bool
	if nodeActive {
		nodePortOpen = portOpen(cfg.UpstreamHost, cfg.UpstreamPort)
		if nodePortOpen {
			chain = getBlockchainInfo(cfg)
			estimatedHeight, ibdComplete = zcashExtraChainFields(cfg)
			chain = zcashNormalizeChainIBD(chain, estimatedHeight, ibdComplete)
		}
	}
	rpcOK := chain.OK
	displayIBD := chain.IBD

	nodeSvcEffective := nodeState
	switch {
	case startBad:
		nodeSvcEffective = "failed"
	case nodeActive && rpcOK && !displayIBD:
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

	diskOK, freeGiB, needGiB, diskDetail := zcashDiskGateOK(cfg, prof)

	journalSnip := journalUnitSnippet(cfg.NodeService, 80)
	headerSyncPct := parseCoreHeaderSyncPct(zcashDebugLogTail(cfg, 40))
	if headerSyncPct <= 0 {
		headerSyncPct = parseCoreHeaderSyncPct(strings.Split(journalSnip, "\n"))
	}
	if headerSyncPct <= 0 {
		headerSyncPct = parseZebraSyncPct(journalSnip)
	}
	// Zebra often reports headers==blocks while still far from tip — use estimatedheight as tip.
	tipHeaders := chain.Headers
	if estimatedHeight > tipHeaders {
		tipHeaders = estimatedHeight
	}
	honestPct := coreHonestIBDPct(chain.Blocks, tipHeaders, chain.Verify, headerSyncPct)
	if !displayIBD && rpcOK {
		honestPct = 100
	}
	// Warmup before RPC: keep journal sync_percent so UI is not empty (TON/Base class).
	if !rpcOK && headerSyncPct > 0 {
		honestPct = headerSyncPct
		if honestPct > 99.9 {
			honestPct = 99.9
		}
		displayIBD = true
	}
	historyGap := coreHistoryMissing(chain, false)
	if historyGap {
		displayIBD = true
		if honestPct >= 99.9 {
			honestPct = 99.8
		}
	}
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
		IBD:            displayIBD,
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
	syncDetail := chain.Error
	switch {
	case rpcOK && historyGap && !chain.IBD:
		syncDetail = fmt.Sprintf("Pruned · not full history · height %d", chain.Blocks)
	case rpcOK && displayIBD:
		tip := chain.Headers
		if estimatedHeight > tip {
			tip = estimatedHeight
		}
		syncDetail = fmt.Sprintf(
			"Sync · blocks %d / tip %d · %s",
			chain.Blocks, tip, formatCoreSyncPct(verifyPct),
		)
		if estimatedHeight > 0 {
			syncDetail = fmt.Sprintf("%s · est.height %d", syncDetail, estimatedHeight)
		}
		if chain.Peers >= 0 {
			syncDetail = fmt.Sprintf("%s · peers %d", syncDetail, chain.Peers)
		}
	case rpcOK:
		syncDetail = fmt.Sprintf("Synced · height %d", chain.Blocks)
	case displayIBD && verifyPct > 0:
		syncDetail = fmt.Sprintf("Sync · %s (zebrad journal)", formatCoreSyncPct(verifyPct))
	}

	setupSteps := []map[string]any{
		{"id": "registry", "title": "Instance registered", "done": instRegistered,
			"detail": "INSTANCE.json + /etc/rpcnode/instances.d"},
		{"id": "disk", "title": "Disk floor for sync", "done": diskOK,
			"detail": diskDetail, "active": !diskOK && apiUp},
		{"id": "node", "title": "zebrad running", "done": nodeActive,
			"detail": "process/systemd", "active": apiUp && !nodeActive},
		{"id": "rpc", "title": "JSON-RPC responding", "done": rpcOK,
			"detail": "getblockchaininfo", "active": nodeActive && !rpcOK},
		{"id": "ibd", "title": "Sync complete", "done": rpcOK && !displayIBD,
			"detail": map[bool]string{
				true:  syncDetail,
				false: "verificationprogress≈1",
			}[displayIBD && syncDetail != ""],
			"active": displayIBD,
			"pct":    map[bool]any{true: verifyPct, false: nil}[displayIBD && verifyPct > 0]},
		{"id": "api", "title": "API agent up", "done": apiUp,
			"detail": fmt.Sprintf(":%d /healthz", apiProbePort)},
	}

	logTail := zcashLogTail(cfg, 80)

	base, _ := effectivePublicBases(cfg)
	nodeReady := rpcOK && !displayIBD

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
			"ok": rpcOK && !displayIBD, "syncing": displayIBD, "ibd": displayIBD,
			"block": chain.Blocks, "blocks": chain.Blocks, "headers": chain.Headers,
			"estimatedheight": estimatedHeight,
			"peers":           chain.Peers, "size_on_disk": chain.SizeOnDisk,
			"size_on_disk_gb": round1(float64(chain.SizeOnDisk) / (1024 * 1024 * 1024)),
			"verificationprogress": map[bool]float64{true: honestVerify, false: chain.Verify}[rpcOK || verifyPct > 0],
			"verification_pct":     verifyPct,
			"verify_pct":           honestVerify, "detail": syncDetail,
			"network": network, "log_tail": logTail,
		},
		"rpc": map[string]any{
			"ok": rpcOK, "height": chain.Blocks, "blocks": chain.Blocks,
			"headers": chain.Headers, "estimatedheight": estimatedHeight,
			"peers": chain.Peers,
			"initialblockdownload":            displayIBD,
			"initial_block_download_complete": ibdComplete || (rpcOK && !displayIBD),
			"verificationprogress":            chain.Verify, "verification_pct": verifyPct,
			"client_version":                  chain.ClientVersion,
		},
		"services": map[string]any{
			"node": nodeSvcEffective, "api": apiSvc,
		},
		"checks": map[string]any{
			"node_process_up": procOK, "zebrad_process": procOK,
			"node_port_open": nodePortOpen,
		},
		"ports": map[string]any{
			"public": publicPort, "agent": agentPort, "node_http": cfg.UpstreamPort,
		},
		"start_error": startErr,
		"logs": map[string]any{
			"title":  "Logs",
			"source": "zcash-sync",
			"lines":  logTail,
		},
		"connect":        bitcoinFamilyConnectMap(cfg, base, nodeReady, "zebrad", ""),
		"version":        agentVersion(),
		"client_version": chain.ClientVersion,
	}
}

// zcashNormalizeChainIBD — Zebra omits initialblockdownload; derive from verify/est.height.
func zcashNormalizeChainIBD(chain bitcoinChainInfo, estimatedHeight int64, ibdComplete bool) bitcoinChainInfo {
	if !chain.OK {
		return chain
	}
	if ibdComplete {
		chain.IBD = false
		return chain
	}
	if chain.IBD {
		return chain
	}
	if chain.Verify > 0 && chain.Verify < 0.999 {
		chain.IBD = true
		return chain
	}
	if estimatedHeight > 0 && chain.Blocks+1 < estimatedHeight {
		chain.IBD = true
		return chain
	}
	if chain.Headers > 0 && chain.Blocks+1 < chain.Headers {
		chain.IBD = true
	}

	return chain
}

func zcashExtraChainFields(cfg Config) (estimatedHeight int64, ibdComplete bool) {
	res, err := bitcoinRPC(cfg, "getblockchaininfo", nil)
	if err != nil || res == nil {
		return 0, false
	}
	if v, ok := res["estimatedheight"].(float64); ok {
		estimatedHeight = int64(v)
	}
	if v, ok := res["estimated_height"].(float64); ok && estimatedHeight == 0 {
		estimatedHeight = int64(v)
	}
	if v, ok := res["initial_block_download_complete"].(bool); ok {
		ibdComplete = v
	}

	return estimatedHeight, ibdComplete
}

func zcashdRunningFor(cfg Config) (bool, string) {
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
			if cmd != "" && (strings.Contains(cmd, "zebrad") || strings.Contains(cmd, "zcashd")) {
				return true, cmd
			}
		}
	}
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		data = LookupNetworkProfile(cfg.Network, cfg.Env).DataPath
	}
	out, err := runCmd(2*time.Second, "bash", "-lc",
		fmt.Sprintf(`ps -eo pid=,args= | grep -E '[z]ebrad|[z]cashd' | grep -F %q | head -1`, data))
	if err != nil || strings.TrimSpace(out) == "" {
		return false, ""
	}

	return true, strings.TrimSpace(out)
}

func zcashStartFailureDetail(cfg Config, procOK bool) (string, bool) {
	if procOK {
		return "", false
	}
	state := systemctlActive(cfg.NodeService)
	if systemctlFailed(cfg.NodeService) || state == "failed" {
		snip := stripUnitPathNoise(journalUnitSnippet(cfg.NodeService, 24))
		if snip != "" {
			return snip, true
		}
		// Stale zcashd unit without zebrad.toml — surface actionable reason.
		if !fileExists(filepathJoinEtc(cfg, "zebrad.toml")) && fileExists(filepathJoinEtc(cfg, "zcash.conf")) {
			return "zcashd EOL (halted 2026-07-18) — re-provision for zebrad", true
		}

		return "zebrad unit failed", true
	}

	return "", false
}

func filepathJoinEtc(cfg Config, name string) string {
	etc := strings.TrimSpace(cfg.EtcDir)
	if etc == "" {
		etc = LookupNetworkProfile(cfg.Network, cfg.Env).EtcPath
	}
	if etc == "" {
		return name
	}

	return strings.TrimRight(etc, "/") + "/" + name
}

func zcashDiskGateOK(cfg Config, prof NetworkProfile) (ok bool, freeGiB, needGiB float64, detail string) {
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
	detail = fmt.Sprintf("%.0f GiB free (floor %.0f GiB for zcash sync; hint %.0f GiB)", freeGiB, floor, needGiB)

	return ok, freeGiB, needGiB, detail
}

func zcashLogTail(cfg Config, n int) []string {
	if n <= 0 {
		n = 80
	}
	lines := append([]string{}, zcashDebugLogTail(cfg, n)...)
	if j := journalUnitSnippet(cfg.NodeService, n/2); j != "" {
		lines = append(lines, strings.Split(j, "\n")...)
	}
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}

	return lines
}

func zcashDebugLogTail(cfg Config, n int) []string {
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		data = LookupNetworkProfile(cfg.Network, cfg.Env).DataPath
	}
	// Legacy zcashd debug.log; Zebra logs primarily via journal.
	path := data + "/debug.log"
	out, err := runCmd(2*time.Second, "bash", "-lc",
		fmt.Sprintf(`tail -n %d %q 2>/dev/null || true`, n, path))
	if err != nil || strings.TrimSpace(out) == "" {
		return nil
	}

	return strings.Split(strings.TrimSpace(out), "\n")
}

// parseZebraSyncPct — "sync_percent=12.345 %" / "estimated progress … 12.3 %" from zebrad journal.
func parseZebraSyncPct(snip string) float64 {
	best := 0.0
	for _, ln := range strings.Split(snip, "\n") {
		low := strings.ToLower(ln)
		if !strings.Contains(low, "sync_percent") && !strings.Contains(low, "estimated progress") {
			continue
		}
		// Prefer key=value form: sync_percent=10.783
		if i := strings.Index(low, "sync_percent="); i >= 0 {
			rest := low[i+len("sync_percent="):]
			num := ""
			for _, r := range rest {
				if (r >= '0' && r <= '9') || r == '.' {
					num += string(r)
					continue
				}
				break
			}
			if f, err := strconv.ParseFloat(num, 64); err == nil && f > best && f <= 100 {
				best = f
				continue
			}
		}
		for _, tok := range strings.Fields(strings.ReplaceAll(ln, "=", " ")) {
			tok = strings.Trim(tok, "%,")
			if f, err := strconv.ParseFloat(tok, 64); err == nil && f > best && f <= 100 {
				best = f
			}
		}
	}

	return best
}
