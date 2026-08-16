package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Shared eth-JSON-RPC collect for hyperliquid / arb / optimism (full sync via eth_syncing).

func collectL2EVM(cfg Config, network string) map[string]any {
	if network == "" {
		network = cfg.Network
	}
	prof := LookupNetworkProfile(network, cfg.Env)

	nodeState := systemctlActive(cfg.NodeService)
	procOK, _ := l2ProcessRunning(cfg, network)

	startErr, startBad := l2StartFailureDetail(cfg, procOK, network)
	nodeActive := procOK && !startBad
	if !nodeActive && (nodeState == "active" || nodeState == "activating") {
		nodeActive = !startBad
	}

	isHL := strings.EqualFold(network, "hyperliquid")
	hlJournal := hlJournalProgress{}
	if isHL {
		hlJournal = hlJournalSnapshot(cfg, network)
	}

	var rpc ethereumRPCResult
	var nodePortOpen bool
	if nodeActive {
		if isHL {
			rpc = probeHyperliquidRPC(cfg)
			nodePortOpen = rpc.OK || portOpen(cfg.UpstreamHost, cfg.UpstreamPort) || portOpen("127.0.0.1", 3001)
		} else {
			nodePortOpen = portOpen(cfg.UpstreamHost, cfg.UpstreamPort)
			if nodePortOpen {
				rpc = probeL2RPC(cfg, network)
			}
		}
	}
	rpcOK := rpc.OK
	syncing := rpcOK && rpc.Syncing
	warmupSyncing := nodeActive && !rpcOK && !startBad
	ibdFlag := syncing || warmupSyncing
	syncDetail := rpc.SyncDetail

	verifyPct := float64(0)
	evmTip := int64(0)
	l1Tip := int64(0)
	isBase := strings.EqualFold(network, "base")
	rethJournal := rethJournalProgress{}
	if isBase && nodeActive {
		rethJournal = rethJournalSnapshot(cfg, network)
	}
	if isHL {
		if rpcOK || hlJournal.AppliedBlock > 0 || hlJournal.FinishedBootstrap {
			evmTip = hlPublicEVMTip(cfg.Env)
			l1Tip = hlPublicL1Tip(cfg.Env, hlJournal.AppliedBlock)
		}
		verifyPct = hlVerificationPct(rpc, rpcOK, hlJournal, evmTip, l1Tip)
	} else if rpcOK {
		verifyPct = ethSyncVerificationPct(rpc.CurrentBlock, rpc.HighestBlock, syncing)
		// base-reth: eth_syncing current/highest stay 0x0 — stages[] (preferred) or
		// journal Headers are the honest bar. ❌ Do not gate only on syncing+journal
		// (Bodies phase drops "Received headers" from the journal tail).
		if isBase {
			if use, syn, pct, detail, tip, cursor := applyBaseRethProgress(
				syncing, rpc.HighestBlock, verifyPct, rpc.Stages, rethJournal,
			); use {
				syncing = syn
				ibdFlag = true
				verifyPct = pct
				if detail != "" {
					syncDetail = detail
					rpc.SyncDetail = detail
				}
				if tip > 0 {
					rpc.HighestBlock = tip
				}
				if cursor > 0 && (rpc.CurrentBlock <= 0 || rpc.CurrentBlock > tip) {
					rpc.CurrentBlock = cursor
				}
			}
			// Persist last honest % — Bodies phase drops journal headers from the
			// tail; brief RPC blips must not paint verification_pct=0 (empty bar).
			if verifyPct > 0 && (syncing || ibdFlag) {
				saveBaseRethProgress(cfg, verifyPct, syncDetail)
			} else if syncing || ibdFlag {
				if p, d := loadBaseRethProgress(cfg); p > 0 {
					verifyPct = p
					ibdFlag = true
					syncing = true
					if syncDetail == "" && d != "" {
						syncDetail = d
						rpc.SyncDetail = d
					}
				}
			} else if rpcOK && !syncing {
				clearBaseRethProgress(cfg)
			}
		}
	} else if isBase && rethJournal.OK && rethJournal.VerifyPct > 0 {
		// RPC down but journal still advancing (rare) — keep bar alive.
		verifyPct = rethJournal.VerifyPct
		ibdFlag = true
		if rethJournal.Detail != "" {
			syncDetail = rethJournal.Detail
		}
		saveBaseRethProgress(cfg, verifyPct, syncDetail)
	} else if isBase && nodeActive {
		if p, d := loadBaseRethProgress(cfg); p > 0 {
			verifyPct = p
			ibdFlag = true
			syncing = true
			if d != "" {
				syncDetail = d
			}
		}
	}
	if isHL && hlJournal.AppliedBlock > 0 {
		// Tip probe can lag a few blocks; local applied is authoritative floor.
		if l1Tip > 0 && hlJournal.AppliedBlock > l1Tip {
			l1Tip = hlJournal.AppliedBlock
			hlL1TipMu.Lock()
			hlL1TipCache[strings.ToLower(cfg.Env)] = hlL1TipCacheEntry{tip: l1Tip, at: time.Now()}
			hlL1TipMu.Unlock()
		}
	}
	l1Behind := isHL && hlJournal.AppliedBlock > 0 && l1Tip > 0 && hlJournal.AppliedBlock+512 < l1Tip
	if isHL && l1Behind {
		ibdFlag = true
		syncing = true
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

	diskOK, freeGiB, needGiB, diskDetail := l2DiskGateOK(cfg, prof)

	wantsSnap := prof.SnapshotPolicy != SnapshotNever || prof.HasExtra(StepSnapshot)
	isRobinhood := strings.EqualFold(network, "robinhood")
	snapEnabled := wantsSnap && snapshotFeatureEnabled(cfg)
	if isRobinhood {
		// Orbit IBD needs pruned --init.url; always advertise snapshot when profile
		// requires it — ignore stale TRON_SNAPSHOT_ENABLED=0 from older provision.
		snapEnabled = true
		if strings.TrimSpace(cfg.SnapshotURL) == "" {
			cfg.SnapshotURL = prof.DefaultSnapshotURL
		}
	}

	logTail := l2EVMLogTail(cfg, network, 80)
	if nodeActive && !rpcOK && !startBad {
		maybeAppendL2ProgressLog(cfg, network, true)
		logTail = l2EVMLogTail(cfg, network, 80)
	}

	warmupDetail := ""
	if nodeActive && !rpcOK && !startBad {
		warmupDetail = l2WarmupDetailFromJournal(cfg, network, network+" warming up · waiting for JSON-RPC")
		if isHL && hlJournal.Detail != "" {
			warmupDetail = hlJournal.Detail + " · waiting for HyperEVM RPC"
		}
	}
	// Always scrape nitro init-download progress when journal has it (snapshot or start).
	if !isHL && (nodeActive || snapEnabled) {
		if d := l2WarmupDetailFromJournal(cfg, network, ""); d != "" &&
			(strings.Contains(strings.ToLower(d), "download") ||
				strings.Contains(strings.ToLower(d), "snapshot")) {
			warmupDetail = d
			maybeAppendL2ProgressLog(cfg, network, true)
			logTail = l2EVMLogTail(cfg, network, 80)
		}
	}

	nitroPct := nitroDownloadPct(warmupDetail)
	if nitroPct < 0 && !isHL {
		nitroPct = nitroDownloadPctFromJournal(cfg, network)
	}
	// Journal \r progress often missing from journald — fall back to on-disk part bytes.
	if nitroPct < 0 && !isHL && snapEnabled {
		if diskPct, detail := nitroInitDownloadPctFromDisk(cfg); diskPct >= 0 {
			nitroPct = diskPct
			if warmupDetail == "" || !strings.Contains(strings.ToLower(warmupDetail), "download") {
				warmupDetail = detail
			}
		}
	}
	if nitroPct >= 0 && !rpcOK {
		verifyPct = nitroPct
	}

	snapMarker := fileExists(cfg.SnapshotMarker)
	snapState := readJSONFile(cfg.SnapshotState)
	snapPhase, _ := snapState["phase"].(string)
	snapDetail, _ := snapState["detail"].(string)
	snapErr, _ := snapState["error"].(string)
	snapUnitActive := systemctlActive(cfg.SnapshotService) == "active"
	nitroDownloading := nitroPct >= 0 && nitroPct < 100 && !rpcOK
	if snapEnabled && !snapMarker {
		if nitroDownloading || (nodeActive && !rpcOK && !startBad && strings.Contains(strings.ToLower(warmupDetail), "download")) {
			snapPhase = "download"
			if snapDetail == "" {
				snapDetail = warmupDetail
			}
		} else if snapUnitActive || strings.EqualFold(snapPhase, "download") {
			if snapDetail == "" {
				snapDetail = "Starting nitro pruned snapshot (--init.url)"
			}
		}
	}
	// Heal marker only when RPC is up, or nitro finished transfer into a real chaindb
	// (never mark ready from an empty/staging `nitro/tmp` dir).
	if snapEnabled && !snapMarker && !startBad {
		if rpcOK || (nodeActive && nitroPct < 0 && l2NitroInitDone(cfg, network) && !nitroDownloading) {
			_ = writeFileAtomic(cfg.SnapshotMarker, []byte("ok\n"))
			snapMarker = true
			snapPhase = "done"
			snapDetail = "nitro init snapshot ready"
			_ = writeJSONFile(cfg.SnapshotState, map[string]any{
				"phase": "done", "detail": snapDetail, "url": cfg.SnapshotURL,
				"updated_at": time.Now().UTC().Format(time.RFC3339),
			})
		}
	}
	snapBusy := snapEnabled && !snapMarker && !strings.EqualFold(snapPhase, "error") &&
		(nitroDownloading || snapUnitActive || strings.EqualFold(snapPhase, "download") ||
			(nodeActive && !rpcOK && !startBad && wantsSnap))
	snapFailed := snapEnabled && !snapMarker && (strings.EqualFold(snapPhase, "error") || snapErr != "")
	snapPctStr := ""
	if snapMarker {
		snapPctStr = "100"
	} else if nitroPct >= 0 {
		snapPctStr = fmt.Sprintf("%.2f", nitroPct)
	} else if snapBusy {
		snapPctStr = "0"
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
		SnapDetail:     firstNonEmptyStr(snapDetail, warmupDetail),
		SnapErr:        snapErr,
		Pct:            snapPctStr,
		NodeActive:     nodeActive && !startBad,
		StartError:     startErr,
		WarmupDetail:   warmupDetail,
		RPCOK:          rpcOK,
		IBD:            ibdFlag && (!snapEnabled || snapMarker),
		Progress:       prog,
	}
	if isHL && hlJournal.AppliedBlock > 0 {
		lcIn.Height = hlJournal.AppliedBlock
		if l1Tip > 0 {
			lcIn.Headers = l1Tip
		}
		lcIn.VerifyPct = verifyPct / 100
	} else if rpcOK {
		lcIn.Height = rpc.Block
		if syncing && rpc.HighestBlock > 0 {
			lcIn.Headers = rpc.HighestBlock
			if rpc.CurrentBlock > 0 {
				lcIn.Height = rpc.CurrentBlock
			}
		}
		lcIn.VerifyPct = verifyPct / 100
	} else if verifyPct > 0 || nitroPct >= 0 {
		if nitroPct >= 0 {
			verifyPct = nitroPct
		}
		lcIn.VerifyPct = verifyPct / 100
	} else if snapBusy {
		// Visible 0% — never leave Sync/wizard bar without a numeric pct during snapshot.
		lcIn.VerifyPct = 0
		verifyPct = 0
	}
	if rpc.Peers >= 0 {
		lcIn.Peers = int(rpc.Peers)
	} else if isHL && hlJournal.Peers >= 0 {
		lcIn.Peers = hlJournal.Peers
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
	peers := rpc.Peers
	if peers < 0 && isHL {
		peers = int64(hlJournal.Peers)
	}
	if isHL && (hlJournal.AppliedBlock > 0 || l1Tip > 0) {
		localL1 := hlJournal.AppliedBlock
		switch {
		case l1Behind || (rpcOK && syncing):
			chainDetail = fmt.Sprintf("Syncing · L1 %d", localL1)
			if l1Tip > 0 {
				chainDetail = fmt.Sprintf("Syncing · L1 %d / %d", localL1, l1Tip)
			}
		case rpcOK && !syncing && !l1Behind:
			chainDetail = fmt.Sprintf("Synced · L1 %d", localL1)
			if l1Tip > 0 {
				chainDetail = fmt.Sprintf("Synced · L1 %d / %d", localL1, l1Tip)
			}
		default:
			chainDetail = fmt.Sprintf("Bootstrap · L1 %d", localL1)
		}
		if rpc.Block > 0 {
			chainDetail += fmt.Sprintf(" · EVM %d", rpc.Block)
		}
		if peers >= 0 {
			chainDetail += fmt.Sprintf(" · peers %d", peers)
		}
		if rpc.ChainID != "" {
			chainDetail += " · chain " + rpc.ChainID
		}
	} else if snapBusy {
		chainDetail = firstNonEmptyStr(snapDetail, warmupDetail, "Downloading pruned snapshot")
		if nitroPct >= 0 && !strings.Contains(chainDetail, "%") {
			chainDetail = fmt.Sprintf("%s · %.2f%%", chainDetail, nitroPct)
		}
	} else if !rpcOK && warmupDetail != "" {
		chainDetail = warmupDetail
	} else if rpcOK && syncing {
		chainDetail = syncDetail
		// Prefer stages/syncDetail already set by applyBaseRethProgress.
		// ❌ Do not overwrite Bodies % with stale journal Headers detail.
		if chainDetail == "" && isBase && rethJournal.OK && rethJournal.Detail != "" {
			chainDetail = rethJournal.Detail
		}
		if chainDetail == "" {
			chainDetail = fmt.Sprintf("Syncing · block %d", rpc.Block)
		}
		if peers >= 0 && chainDetail != "" && !strings.Contains(chainDetail, "peers") {
			chainDetail += fmt.Sprintf(" · peers %d", peers)
		}
	} else if rpcOK {
		chainDetail = fmt.Sprintf("Synced · block %d", rpc.Block)
		if peers >= 0 {
			chainDetail += fmt.Sprintf(" · peers %d", peers)
		}
		if rpc.ChainID != "" {
			chainDetail += " · chain " + rpc.ChainID
		}
	}
	if verifyPct > 0 && (ibdFlag || syncing || l1Behind) {
		if !strings.Contains(chainDetail, "%") {
			chainDetail = fmt.Sprintf("%s · %.1f%%", chainDetail, verifyPct)
		}
	}

	// While RPC is up and syncing, keep sampling eth_syncing into agent logs.
	if rpcOK {
		maybeAppendL2SyncRPCLog(cfg, network, rpc, syncing)
		logTail = l2EVMLogTail(cfg, network, 80)
	} else if isHL && hlJournal.AppliedBlock > 0 {
		maybeAppendHLAppliedLog(cfg, hlJournal)
		logTail = l2EVMLogTail(cfg, network, 80)
	}

	setupSteps := []map[string]any{
		{"id": "registry", "title": "Instance registered", "done": instRegistered,
			"detail": "INSTANCE.json + /etc/rpcnode/instances.d"},
		{"id": "disk", "title": "Disk floor", "done": diskOK,
			"detail": diskDetail, "active": !diskOK && apiUp},
		{"id": "node", "title": network + " process", "done": procOK && !startBad,
			"detail": "systemd/process", "active": apiUp && !procOK},
		{"id": "rpc", "title": "RPC responding", "done": rpcOK,
			"detail": "eth_blockNumber", "active": nodeActive && !rpcOK},
		{"id": "sync", "title": chainTitle, "done": rpcOK && !syncing && !warmupSyncing && !l1Behind,
			"detail": chainDetail,
			"active": ibdFlag},
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
	case snapFailed || uiPhase == "error":
		health = "error"
		degraded = true
	case snapBusy || uiPhase == "snapshot":
		health = "setup"
		degraded = true
	case uiPhase == "install" || uiPhase == "setup" || uiPhase == "ports":
		health = "setup"
		degraded = true
	case uiPhase == "start" || uiPhase == "run" || ibdFlag:
		health = "degraded"
		degraded = true
	case !nodeActive || !rpcOK:
		health = "degraded"
		degraded = true
	}

	nodeReady := nodeActive && rpcOK && !syncing && !startBad && !l1Behind && (!snapEnabled || snapMarker)
	agentActivity := "idle"
	agentStatus := "ok"
	agentLastErr := ""
	switch {
	case startBad:
		agentActivity = "node_start_failed"
		agentStatus = "error"
		agentLastErr = startErr
	case snapFailed:
		agentActivity = "snapshot_error"
		agentStatus = "error"
		agentLastErr = snapErr
		if agentLastErr == "" {
			agentLastErr = snapDetail
		}
	case snapBusy:
		agentActivity = "snapshot_download"
		agentStatus = "ok"
	case !diskOK && apiUp && !nodeActive:
		agentActivity = "disk_gate"
		agentStatus = "degraded"
		agentLastErr = diskDetail
	case uiPhase == "start" || (nodeActive && !rpcOK):
		agentActivity = "node_starting"
		if health == "degraded" {
			agentStatus = "degraded"
		}
	case syncing || ibdFlag:
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

	blockHeight := rpc.Block
	if isHL && hlJournal.AppliedBlock > 0 {
		blockHeight = hlJournal.AppliedBlock
	} else if blockHeight <= 0 && hlJournal.AppliedBlock > 0 {
		blockHeight = hlJournal.AppliedBlock
	}
	peerOut := rpc.Peers
	if peerOut < 0 && isHL && hlJournal.Peers >= 0 {
		peerOut = int64(hlJournal.Peers)
	}
	syncOK := rpcOK && !syncing && !warmupSyncing && !l1Behind
	syncBlock := map[string]any{
		"ok":               syncOK,
		"syncing":          ibdFlag,
		"ibd":              ibdFlag,
		"block":            blockHeight,
		"blocks":           blockHeight,
		"detail":           chainDetail,
		"network":          network,
		"log_tail":         logTail,
		"verification_pct": verifyPct,
	}
	if peerOut >= 0 {
		syncBlock["peers"] = peerOut
	}
	if isHL && l1Tip > 0 {
		syncBlock["headers"] = l1Tip
		syncBlock["blocks"] = hlJournal.AppliedBlock
		if rpc.Block > 0 {
			syncBlock["evm_block"] = rpc.Block
		}
		if evmTip > 0 {
			syncBlock["evm_headers"] = evmTip
		}
	} else if rpcOK && syncing && rpc.HighestBlock > 0 {
		cur := rpc.CurrentBlock
		if cur <= 0 {
			cur = blockHeight
		}
		syncBlock["blocks"] = cur
		syncBlock["headers"] = rpc.HighestBlock
		syncBlock["block"] = cur
	}
	if isHL {
		if sz := hlDataDirBytes(cfg); sz > 0 {
			syncBlock["size_on_disk"] = sz
			syncBlock["size_on_disk_gb"] = float64(sz) / (1024 * 1024 * 1024)
		}
	}
	if nitroPct >= 0 && !isHL {
		syncBlock["verification_pct"] = nitroPct
		verifyPct = nitroPct
	} else if snapBusy && !rpcOK {
		syncBlock["verification_pct"] = verifyPct
		syncBlock["detail"] = firstNonEmptyStr(snapDetail, warmupDetail, "Downloading pruned snapshot")
		syncBlock["syncing"] = true
		syncBlock["ibd"] = true
	}

	out := map[string]any{
		"ok": true, "health": health, "degraded": degraded,
		"network": network, "env": cfg.Env,
		"hostname": host, "updated_at": updatedAt,
		"version": agentVersion(), "client_version": rpc.ClientVersion,
		"ui_phase": uiPhase, "node_status": nodeStatus,
		"lifecycle": lifecycle,
		"public_base": base, "panel_base": panelBase,
		"ports": map[string]any{
			"public": publicPort, "agent": agentPort, "upstream": cfg.UpstreamPort,
			"public_open": publicPortOpen, "agent_open": agentPortOpen, "upstream_open": nodePortOpen,
		},
		"services": map[string]any{
			"node": nodeSvcEffective, "api": apiSvc,
			"snapshot": systemctlActive(cfg.SnapshotService),
		},
		"checks": map[string]any{
			"node_process_up": procOK, "rpc_ok": rpcOK, "disk_ok": diskOK,
			"api_up": apiUp, "instance_registered": instRegistered,
		},
		"disk_gate": map[string]any{
			"ok": diskOK, "free_gib": freeGiB, "need_gib": needGiB, "detail": diskDetail,
		},
		"rpc": map[string]any{
			"ok": rpcOK, "block": blockHeight, "blocks": blockHeight, "syncing": syncing || l1Behind || snapBusy,
			"peers": peerOut, "chain_id": rpc.ChainID, "detail": syncDetail,
			"initialblockdownload": ibdFlag || snapBusy, "verification_pct": verifyPct,
			"client_version":       rpc.ClientVersion,
		},
		"sync": syncBlock,
		"snapshot": map[string]any{
			"enabled":      snapEnabled,
			"ready":        snapMarker,
			"pct":          map[bool]string{true: "100", false: snapPctStr}[snapMarker],
			"phase":        map[bool]string{true: "done", false: firstNonEmptyStr(snapPhase, "idle")}[snapMarker],
			"detail":       firstNonEmptyStr(snapDetail, warmupDetail),
			"error":        snapErr,
			"url":          cfg.SnapshotURL,
			"wget_running": snapBusy,
			"can_start":    snapEnabled && !snapMarker && !snapBusy,
			"can_stop":     snapEnabled && snapBusy,
			"manual":       true,
			"failed":       snapFailed,
			"log_tail":     logTail,
		},
		"logs": map[string]any{
			"title":  "Logs",
			"source": map[bool]string{true: "snapshot", false: l2LogSource(network)}[snapBusy],
			"lines":  logTail,
		},
		"setup_steps": setupSteps,
		"connect": map[string]any{
			"ready":       nodeReady && apiUp,
			"public_base": base,
			"panel_base":  panelBase,
		},
		"agent": map[string]any{
			"activity": agentActivity, "status": agentStatus, "last_error": agentLastErr,
		},
		"supported_networks": ListKnownNetworks(),
		"supported_steps":    SupportedLifecycleSteps(network, cfg.Env),
		"capabilities":       LifecycleCapabilitiesFor(network, cfg.Env),
	}
	if startErr != "" {
		out["start_error"] = startErr
	}

	return out
}

// nitroDownloadPctFromJournal scrapes the node unit journal for init-transfer %.
func nitroDownloadPctFromJournal(cfg Config, network string) float64 {
	detail := l2WarmupDetailFromJournal(cfg, network, "")
	return nitroDownloadPct(detail)
}

// nitroInitDownloadPctFromDisk — honest % from pruned.tar.part* bytes vs known total.
func nitroInitDownloadPctFromDisk(cfg Config) (float64, string) {
	root := strings.TrimSpace(cfg.DataDir)
	if root == "" {
		return -1, ""
	}
	var got int64
	_ = filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		name := d.Name()
		if !strings.HasPrefix(name, "pruned.tar.part") && !strings.Contains(name, ".tar.part") {
			return nil
		}
		if st, e := d.Info(); e == nil {
			got += st.Size()
		}
		return nil
	})
	if got <= 0 {
		return -1, ""
	}
	exp := robinhoodExpectedSnapshotBytes(cfg)
	if exp <= 0 {
		return -1, ""
	}
	pct := float64(got) / float64(exp) * 100
	if pct > 99.9 && !fileExists(cfg.SnapshotMarker) {
		pct = 99.9 // reserve 100 for marker/RPC
	}
	if pct > 100 {
		pct = 100
	}
	detail := fmt.Sprintf("init download  %.2f%%  ·  %.1f/%.0f GiB  ·  on-disk parts",
		pct, float64(got)/(1024*1024*1024), float64(exp)/(1024*1024*1024))
	return pct, detail
}

func robinhoodExpectedSnapshotBytes(cfg Config) int64 {
	env := normalizeEnvName(cfg.Env)
	// Official explorer pruned totals (single multipart archive); override via env.
	if v := strings.TrimSpace(os.Getenv("RPCNODE_ROBINHOOD_SNAPSHOT_BYTES")); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil && n > 0 {
			return n
		}
	}
	switch env {
	case "testnet":
		return 200216596480 // ~186.5 GiB (2026-08-06)
	default:
		return 231394099200 // ~215.5 GiB (2026-08-03)
	}
}

// l2NitroInitDone — nitro finished --init.url download/extract (RPC may still be warming).
// ❌ Do not treat staging dirs (nitro/, nitro/tmp, LOCK) as ready.
func l2NitroInitDone(cfg Config, network string) bool {
	if fileExists(cfg.SnapshotMarker) {
		return true
	}
	if nitroDownloadPctFromJournal(cfg, network) >= 0 {
		return false
	}
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		return false
	}
	// Real chain DB paths after a successful nitro pruned init extract.
	for _, rel := range []string{
		"nitro/l2chaindata",
		"l2chaindata",
		"nitro/arbitrumdata",
		"arbitrumdata",
	} {
		p := filepath.Join(data, rel)
		if !dirExists(p) {
			continue
		}
		if nitroDirLooksExtracted(p) {
			return true
		}
	}
	return false
}

func nitroDirLooksExtracted(p string) bool {
	ents, err := os.ReadDir(p)
	if err != nil || len(ents) == 0 {
		return false
	}
	for _, e := range ents {
		name := strings.ToLower(e.Name())
		if name == "tmp" || name == "lock" || strings.HasPrefix(name, ".") {
			continue
		}
		return true
	}
	return false
}

func writeFileAtomic(path string, body []byte) error {
	if path == "" {
		return fmt.Errorf("empty path")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, body, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func dirExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && st.IsDir()
}

func probeL2RPC(cfg Config, network string) ethereumRPCResult {
	if strings.EqualFold(network, "hyperliquid") {
		return probeHyperliquidRPC(cfg)
	}

	return probeEthereumRPC(cfg)
}

func probeHyperliquidRPC(cfg Config) ethereumRPCResult {
	host := cfg.UpstreamHost
	if host == "" {
		host = "127.0.0.1"
	}
	ports := make([]int, 0, 4)
	addPort := func(p int) {
		if p <= 0 {
			return
		}
		for _, x := range ports {
			if x == p {
				return
			}
		}
		ports = append(ports, p)
	}
	addPort(cfg.UpstreamPort)
	addPort(LookupNetworkProfile("hyperliquid", cfg.Env).DefaultNodeHTTP)
	// hl-visor --serve-eth-rpc always binds HyperEVM on :3001 (mainnet+testnet).
	addPort(3001)
	addPort(3002)

	var last ethereumRPCResult
	last.Peers = -1
	for _, port := range ports {
		out := probeHyperliquidRPCAt(host, port)
		if out.OK {
			return out
		}
		last = out
	}

	return last
}

func probeHyperliquidRPCAt(host string, port int) ethereumRPCResult {
	url := fmt.Sprintf("http://%s:%d/evm", host, port)
	out := ethereumRPCResult{Peers: -1}

	syncRaw, err := jsonRPCPost(url, "eth_syncing", nil)
	if err != nil {
		out.Error = err.Error()
		return out
	}
	var syncing any
	if err := json.Unmarshal(syncRaw, &syncing); err != nil {
		out.Error = err.Error()
		return out
	}
	out.OK = true
	switch v := syncing.(type) {
	case bool:
		out.Syncing = v
		if !v {
			out.SyncProgress = 1
		}
	case map[string]any:
		out.Syncing = true
		if cur, ok := v["currentBlock"].(string); ok {
			if h, err := parseHexInt64(cur); err == nil {
				out.Block = h
				out.CurrentBlock = h
			}
		}
		if hi, ok := v["highestBlock"].(string); ok {
			if h, err := parseHexInt64(hi); err == nil {
				out.HighestBlock = h
			}
		}
		if out.HighestBlock > 0 {
			out.SyncProgress = float64(out.CurrentBlock) / float64(out.HighestBlock)
			if out.SyncProgress > 1 {
				out.SyncProgress = 1
			}
			pct := ethSyncVerificationPct(out.CurrentBlock, out.HighestBlock, true)
			out.SyncDetail = fmt.Sprintf("blocks %d / %d · %.1f%%", out.CurrentBlock, out.HighestBlock, pct)
		}
	default:
		out.Syncing = false
		out.SyncProgress = 1
	}

	if blockRaw, err := jsonRPCPost(url, "eth_blockNumber", nil); err == nil {
		var hex string
		if json.Unmarshal(blockRaw, &hex) == nil {
			if h, err := parseHexInt64(hex); err == nil && h > out.Block {
				out.Block = h
			}
		}
	}
	if chainRaw, err := jsonRPCPost(url, "eth_chainId", nil); err == nil {
		var hex string
		if json.Unmarshal(chainRaw, &hex) == nil {
			if id, err := parseHexInt64(hex); err == nil {
				out.ChainID = strconv.FormatInt(id, 10)
			}
		}
	}
	if peerRaw, err := jsonRPCPost(url, "net_peerCount", nil); err == nil {
		var hex string
		if json.Unmarshal(peerRaw, &hex) == nil {
			if n, err := parseHexInt64(hex); err == nil {
				out.Peers = n
			}
		}
	}
	if verRaw, err := jsonRPCPost(url, "web3_clientVersion", nil); err == nil {
		var ver string
		if json.Unmarshal(verRaw, &ver) == nil {
			out.ClientVersion = formatClientVersion(ver)
		}
	}
	if out.SyncDetail == "" && out.Syncing {
		out.SyncDetail = fmt.Sprintf("syncing · block %d · peers %d", out.Block, out.Peers)
	}

	return out
}

// maybeAppendHLAppliedLog samples applied-block bootstrap into sync.log.
func maybeAppendHLAppliedLog(cfg Config, journal hlJournalProgress) {
	if journal.AppliedBlock <= 0 && !journal.FinishedBootstrap {
		return
	}
	now := time.Now()
	ts := now.UTC().Format("15:04:05Z")
	line := ts + "  bootstrap  " + journal.Detail
	if journal.Detail == "" {
		line = fmt.Sprintf("%s  bootstrap  applied block %d", ts, journal.AppliedBlock)
	}

	l2SyncLog.mu.Lock()
	defer l2SyncLog.mu.Unlock()

	same := line == l2SyncLog.lastLine || syncLogSameProgress(l2SyncLog.lastLine, line)
	gapOK := l2SyncLog.lastWrite.IsZero() || now.Sub(l2SyncLog.lastWrite) >= l2SyncLogMinGap
	if same && !gapOK {
		return
	}

	path := l2SyncLogPath(cfg)
	_ = os.MkdirAll(filepath.Dir(path), 0o755)
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	_, _ = f.WriteString(line + "\n")
	_ = f.Close()

	l2SyncLog.lastLine = line
	l2SyncLog.lastWrite = now
	trimBitcoinSyncLogFile(path, l2SyncLogMaxLines)
}

func jsonRPCPost(url, method string, params []any) (json.RawMessage, error) {
	body := map[string]any{
		"jsonrpc": "2.0",
		"id":      1,
		"method":  method,
	}
	if params != nil {
		body["params"] = params
	} else {
		body["params"] = []any{}
	}
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	var envelope struct {
		Result json.RawMessage `json:"result"`
		Error  *struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(b, &envelope); err != nil {
		return nil, err
	}
	if envelope.Error != nil {
		return nil, fmt.Errorf("%s", envelope.Error.Message)
	}

	return envelope.Result, nil
}

func l2ProcessRunning(cfg Config, network string) (bool, string) {
	switch strings.ToLower(network) {
	case "hyperliquid":
		// Never shell out with "hl-node"/"hl-visor" in argv — HL panics on that substring.
		hint := cfg.DataDir
		if hint == "" {
			hint = cfg.NodeService
		}
		if hint == "" {
			hint = "hyperliquid"
		}
		if cmd, ok := procCmdlineHasAny([]string{"hl-node", "hl-visor"}, hint); ok {
			return true, cmd
		}
		// Named visor binary hl-visor-<env> under /home/nodeop/hl
		if cmd, ok := procCmdlineHas("hl-visor-", cfg.Env); ok {
			return true, cmd
		}

		return false, ""
	case "arb", "robinhood":
		// Prefer /proc scan — bash+pgrep self-matches (`…pgrep -af nitro…robinhood-mainnet…`)
		// and falsely sets node_process_up, blocking pipeline startNode forever.
		hints := make([]string, 0, 2)
		if cfg.DataDir != "" {
			hints = append(hints, cfg.DataDir)
		}
		if cfg.NodeService != "" {
			hints = append(hints, cfg.NodeService)
		}
		if len(hints) == 0 {
			hints = append(hints, network)
		}
		if cmd, ok := procCmdlineHasAny([]string{"/bin/nitro", " nitro "}, hints...); ok {
			return true, cmd
		}
		if cfg.DataDir != "" {
			if cmd, ok := procCmdlineHas("nitro", "--persistent.chain="+cfg.DataDir); ok {
				return true, cmd
			}
		}
		return false, ""
	case "optimism":
		out, _ := exec.Command("bash", "-lc",
			fmt.Sprintf(`pgrep -af 'op-geth' | grep -E '%s|optimism-%s' | grep -vE 'pgrep|grep|bash -lc' | head -1`,
				cfg.DataDir, cfg.Env)).CombinedOutput()
		s := strings.TrimSpace(string(out))
		return s != "", s
	case "base":
		out, _ := exec.Command("bash", "-lc",
			fmt.Sprintf(`pgrep -af 'base-reth-node' | grep -E '%s|base-%s' | grep -vE 'pgrep|grep|bash -lc' | head -1`,
				cfg.DataDir, cfg.Env)).CombinedOutput()
		s := strings.TrimSpace(string(out))
		return s != "", s
	default:
		return false, ""
	}
}

func l2StartFailureDetail(cfg Config, procOK bool, network string) (string, bool) {
	unit := cfg.NodeService
	if unit == "" {
		return "", false
	}
	if systemctlFailed(unit) || systemctlActive(unit) == "failed" {
		snip := journalUnitSnippet(unit+".service", 40)
		if snip == "" {
			snip = network + " unit failed"
		}
		return snip, true
	}
	if !procOK && systemctlActive(unit) == "inactive" {
		// Not started yet — not an error.
		return "", false
	}

	return "", false
}

func l2DiskGateOK(cfg Config, prof NetworkProfile) (ok bool, freeGiB, needGiB float64, detail string) {
	needGiB = prof.DiskHintGiB
	if needGiB <= 0 {
		needGiB = 1024
	}
	// Soft floor ~20% of hint (same spirit as bitcoin).
	floor := needGiB * 0.2
	data := cfg.DataDir
	if data == "" {
		data = prof.DataPath
	}
	if data == "" {
		data = "/"
	}
	_ = os.MkdirAll(data, 0o755)
	freeGiB = freeDiskGiB(data)
	ok = freeGiB >= floor
	detail = fmt.Sprintf("free %.0f GiB · need ≥%.0f GiB (hint %.0f)", freeGiB, floor, needGiB)
	if !ok {
		detail = "insufficient disk: " + detail
	}

	return ok, freeGiB, needGiB, detail
}

func freeDiskGiB(path string) float64 {
	out, err := exec.Command("df", "-B1", "--output=avail", path).CombinedOutput()
	if err != nil {
		return 0
	}
	lines := strings.Split(strings.TrimSpace(string(out)), "\n")
	if len(lines) < 2 {
		return 0
	}
	var avail float64
	fmt.Sscanf(strings.TrimSpace(lines[len(lines)-1]), "%f", &avail)

	return avail / (1024 * 1024 * 1024)
}

// Ensure helpers used by pipeline start.
func ensureL2Dirs(cfg Config) error {
	for _, d := range []string{cfg.OptDir, cfg.EtcDir, cfg.DataDir} {
		if d == "" {
			continue
		}
		if err := os.MkdirAll(d, 0o755); err != nil {
			return err
		}
	}
	_ = exec.Command("chown", "-R", "nodeop:nodeop", cfg.OptDir, cfg.EtcDir, cfg.DataDir).Run()

	return nil
}

func resolveL2NodeBin(cfg Config, network string) string {
	opt := cfg.OptDir
	if opt == "" {
		opt = LookupNetworkProfile(network, cfg.Env).OptPath
	}
	switch strings.ToLower(network) {
	case "hyperliquid":
		for _, p := range []string{filepath.Join(opt, "bin", "hl-visor"), "/usr/local/bin/hl-visor"} {
			if fileExists(p) {
				return p
			}
		}
	case "arb", "robinhood":
		for _, p := range []string{filepath.Join(opt, "bin", "nitro"), "/usr/local/bin/nitro"} {
			if fileExists(p) {
				return p
			}
		}
	case "optimism":
		for _, p := range []string{filepath.Join(opt, "bin", "op-geth"), "/usr/local/bin/op-geth"} {
			if fileExists(p) {
				return p
			}
		}
	case "base":
		for _, p := range []string{filepath.Join(opt, "bin", "base-reth-node"), "/usr/local/bin/base-reth-node"} {
			if fileExists(p) {
				return p
			}
		}
	}

	return ""
}
