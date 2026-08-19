package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// tonOutOfSyncHealthySec — MyTonCtrl ≈≤3s; allow ≤5s under multi-tenant host load
// (getstats often reports 4–5s while the node is at tip and THA answers).
const tonOutOfSyncHealthySec = 5

type tonRPCInfo struct {
	OK            bool
	Seqno         int64
	OutOfSyncSec  float64
	OutOfSyncOK   bool // parsed out-of-sync
	ClientVersion string
	Error         string
	Synced        bool
	VerifyPct     float64 // 0..1; only set when honest
	DumpPct       int     // MyTonCtrl dump install % (0 = unknown)
}

var (
	// MyTonCtrl: "Masterchain out of sync: 3 sec" — seconds (honest RPC lag).
	// ❌ "Local validator out of sync: 45" is BLOCKS, not seconds — never use as oos.
	tonMasterchainOutOfSyncRe = regexp.MustCompile(`(?i)masterchain\s+out of sync[:\s]+([0-9]+(?:\.[0-9]+)?)\s*sec`)
	tonOutOfSyncRe            = regexp.MustCompile(`(?i)(?:masterchain\s+)?out of sync[:\s]+([0-9]+(?:\.[0-9]+)?)\s*sec`)
	tonOutOfSyncPlainRe       = regexp.MustCompile(`(?i)out of sync[:\s]+([0-9]+(?:\.[0-9]+)?)`)
	// aria2c dump: [#id 12GiB/235GiB(5%) CN:8 DL:112MiB ETA:33m54s]
	tonAria2DumpRe = regexp.MustCompile(`(?i)\[#[0-9a-f]+\s+(\d+(?:\.\d+)?(?:GiB|MiB|TiB))/(\d+(?:\.\d+)?(?:GiB|MiB|TiB))\((\d{1,3})%\)(?:[^\]\n]*?\bETA:([0-9hms]+))?`)
	// aria2c post-download checksum: [Checksum:#id 194GiB/205GiB(94%)]
	tonAria2ChecksumRe = regexp.MustCompile(`(?i)\[Checksum:#[0-9a-f]+\s+(\d+(?:\.\d+)?(?:GiB|MiB|TiB))/(\d+(?:\.\d+)?(?:GiB|MiB|TiB))\((\d{1,3})%\)\]`)
	// wget dump progress — require dump keyword or large size (GiB/TiB).
	// ❌ Bare git ".... 100% 52.2M=0s" must NOT paint dump 100%.
	tonWgetDumpPctRe = regexp.MustCompile(`(?i)(?:dump|ton_dump|dump-cache).{0,80}?(\d{1,3})%|(?i)(\d{1,3})%\s+\d+(?:\.\d+)?[GT]i?B?\b`)
	tonUnixTimeRe    = regexp.MustCompile(`(?i)\bunixtime\b[\s":=]+(\d+)`)
	tonMasterTimeRe  = regexp.MustCompile(`(?i)\bmasterchainblocktime\b[\s":=]+(\d+)`)
	tonMasterBlockRe = regexp.MustCompile(`(?i)\bmasterchainblock(?:number)?\b[\s":=]+(\d+)`)
	// getstats: masterchainblock  (-1,8000000000000000,78941023):hash
	tonMasterBlockTupleRe = regexp.MustCompile(`(?i)\bmasterchainblock\b[^\n]*?\(\s*-1\s*,\s*[0-9a-fA-F]+\s*,\s*(\d+)\s*\)`)
	tonShardClientSeqRe   = regexp.MustCompile(`(?i)\bshardclientmasterchainseqno\s+(\d+)`)
	tonTimediffRe         = regexp.MustCompile(`(?i)\btimediff\b[\s":=]+(-?[0-9]+(?:\.[0-9]+)?)`)
	tonSeqnoHintRe        = regexp.MustCompile(`(?i)(?:last applied masterchain block id|masterchain)\D{0,40}?(\d{5,})`)
	// MyTonCtrl initial sync: "Syncing blocks, last known block was 35601 s ago"
	tonLastKnownBlockAgoRe = regexp.MustCompile(`(?i)last known block was\s+([0-9]+(?:\.[0-9]+)?)\s*s(?:ec(?:onds?)?)?\s+ago`)
	// validator-engine --version: "Commit: bb935a83e8da…, Date: …"
	tonBuildCommitRe = regexp.MustCompile(`(?i)Commit:\s*([0-9a-f]{7,40})`)
)

// collectTon — MyTonCtrl liteserver + TON HTTP API.
// Primary sync signal: out_of_sync seconds (not seqno/tip ratio).
func collectTon(cfg Config) map[string]any {
	network := cfg.Network
	if network == "" {
		network = "ton"
	}
	prof := LookupNetworkProfile(network, cfg.Env)

	bootDone := tonBootstrapDone(cfg)
	bootActive := tonBootstrapActive(cfg)
	procOK := tonValidatorRunning()
	thaOpen := portOpen(cfg.UpstreamHost, cfg.UpstreamPort)
	nodeState := systemctlActive(cfg.NodeService)
	startErr, startBad := tonStartFailureDetail(cfg, procOK, bootDone, bootActive)

	nodeActive := (procOK || (bootActive && !startBad) || (nodeState == "active" && bootDone)) && !startBad
	if !nodeActive && (nodeState == "active" || nodeState == "activating") && !startBad {
		nodeActive = true
	}

	dumpPct, dumpDetail := tonBootstrapDumpProgress(cfg)
	// install.sh floods bootstrap.log with compile noise after aria2 lines scroll
	// out of the tail window — keep last honest dump % while bootstrap is alive.
	if dumpPct > 0 {
		saveTonDumpProgress(cfg, dumpPct, dumpDetail)
	} else if bootActive || !bootDone {
		if p, d := loadTonDumpProgress(cfg); p > 0 {
			dumpPct, dumpDetail = p, d
		}
	}
	// After bootstrap.done, aria2's last 100% line is stale — but only clear once
	// we have a real catch-up signal (oos) or validator/THA is past dump.
	// ❌ Clearing immediately on bootstrap.done → empty bar until getstats works.
	if bootDone && !bootActive {
		if oos, seq, ok := readTonOutOfSyncSec(cfg); ok && oos >= 0 && seq > 0 {
			dumpPct = 0
			dumpDetail = ""
			clearTonDumpProgress(cfg)
		} else if dumpPct >= 100 || dumpPct == 0 {
			// Dump finished; seqno=0 means apply/OOM — hold 99, not lag-closed 0.1%.
			dumpPct = 99
			dumpDetail = "dump done · applying state"
		}
	}

	var info tonRPCInfo
	info.DumpPct = dumpPct
	if thaOpen {
		info = probeTonHTTPAPI(cfg)
		info.DumpPct = dumpPct
	}
	// getstats / MyTonCtrl oos works before THA — also try once validator or
	// bootstrap.done (DESIGN: primary sync signal before THA).
	if thaOpen || procOK || bootDone {
		if oos, seq, ok := readTonOutOfSyncSec(cfg); ok {
			info.OutOfSyncSec = oos
			info.OutOfSyncOK = true
			if info.Seqno <= 0 && seq > 0 {
				info.Seqno = seq
			}
			oom := tonValidatorOOM()
			// Healthy only with applied seqno + THA. oos≈0 + seqno=0 is dump/OOM, not tip.
			info.Synced = tonCatchupHonest(oos, info.Seqno, oom) && info.OK && !bootActive
			if info.Synced {
				info.VerifyPct = 1
				clearTonCatchupMaxBehind(cfg)
				clearTonDumpProgress(cfg)
				dumpPct = 0
				info.DumpPct = 0
			} else if info.Seqno > 0 && !oom {
				if p, ok := tonLagClosedPct(cfg, oos); ok {
					info.VerifyPct = p / 100
					dumpPct = 0
					info.DumpPct = 0
					clearTonDumpProgress(cfg)
				}
			} else {
				info.Synced = false
			}
		} else if info.OK {
			// THA up but no out-of-sync yet — treat as syncing without fake %.
			info.Synced = false
		}
	}
	// Dump phase (installer) — real aria2/wget % from bootstrap.log only while bootstrap runs
	// or until catch-up oos appears (see clear above).
	if info.VerifyPct <= 0 && dumpPct > 0 && (bootActive || !info.Synced) {
		info.VerifyPct = float64(dumpPct) / 100
		if info.VerifyPct > 0.999 {
			info.VerifyPct = 0.999
		}
	}
	rpcOK := info.OK
	syncing := nodeActive && (!rpcOK || !info.Synced || bootActive)

	nodeSvcEffective := nodeState
	switch {
	case startBad:
		nodeSvcEffective = "failed"
	case bootActive:
		nodeSvcEffective = "activating"
	case nodeActive && rpcOK && !syncing:
		nodeSvcEffective = "active"
	case nodeActive || thaOpen:
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

	diskOK, freeGiB, needGiB, diskDetail := tonDiskGateOK(cfg, prof)
	logTail := tonLogTail(cfg, 80)

	prog := loadLifecycleProgress(cfg)
	// Drop stale "signal: terminated" from systemctl restart races / agent Update
	// while bootstrap is healthy — was flashing Start error in the panel.
	if prog != nil && (bootActive || bootDone || procOK) {
		le := strings.ToLower(strings.TrimSpace(prog.Auto.LastError))
		if strings.Contains(le, "signal: terminated") || strings.Contains(le, "signal: killed") {
			prog.Auto.LastError = ""
			saveLifecycleProgress(cfg, prog)
		}
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
		// bootActive counts as "node starting" — otherwise pipe last_error from a
		// transient systemctl SIGTERM flashes Start error while MyTonCtrl installs.
		NodeActive: nodeActive && (bootDone || procOK || thaOpen || bootActive),
		StartError: startErr,
		RPCOK:      rpcOK,
		IBD:        syncing,
		VerifyPct:  info.VerifyPct,
		Progress:   prog,
	}
	if bootActive {
		lcIn.WarmupDetail = "MyTonCtrl bootstrap (dump/install)"
		if dumpPct > 0 {
			if dumpDetail != "" {
				lcIn.WarmupDetail = fmt.Sprintf("MyTonCtrl dump %d%% · %s", dumpPct, dumpDetail)
			} else {
				lcIn.WarmupDetail = fmt.Sprintf("MyTonCtrl bootstrap · dump %d%%", dumpPct)
			}
		} else if phase := tonBootstrapPhaseDetail(cfg); phase != "" {
			lcIn.WarmupDetail = phase
		}
	}
	if info.Seqno > 0 {
		lcIn.Height = info.Seqno
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
		{"id": "disk", "title": "Disk floor for TON liteserver (~1 TiB)", "done": diskOK,
			"detail": diskDetail, "active": !diskOK && apiUp},
		{"id": "bootstrap", "title": "MyTonCtrl install", "done": bootDone,
			"detail": map[bool]string{true: "bootstrap.done", false: "install.sh -m liteserver"}[bootDone],
			"active": bootActive || (apiUp && !bootDone),
			"pct":    map[bool]any{true: dumpPct, false: nil}[dumpPct > 0 && !bootDone]},
		{"id": "node", "title": "validator-engine running", "done": procOK,
			"detail": "systemd validator", "active": bootDone && !procOK},
		{"id": "rpc", "title": "TON HTTP API responding", "done": rpcOK,
			"detail": "getMasterchainInfo", "active": procOK && !rpcOK},
		{"id": "ibd", "title": "Catch-up complete", "done": rpcOK && info.Synced,
			"detail": tonSyncDetailWithBootstrap(cfg, info, syncing, bootActive),
			"active": syncing && (rpcOK || info.OutOfSyncOK || dumpPct > 0 || bootActive),
			"pct":    tonSetupIbdPct(info, rpcOK)},
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
	case uiPhase == "install" || uiPhase == "setup" || uiPhase == "ports" || bootActive:
		health = "setup"
		degraded = true
	case uiPhase == "start" || uiPhase == "run" || syncing:
		health = "degraded"
		degraded = true
	case !nodeActive || !rpcOK:
		health = "degraded"
		degraded = true
	}

	nodeReady := procOK && rpcOK && info.Synced
	agentActivity := "idle"
	agentStatus := "ok"
	agentLastErr := ""
	switch {
	case startBad:
		agentActivity = "node_start_failed"
		agentStatus = "error"
		agentLastErr = startErr
	case !diskOK && apiUp && !procOK:
		agentActivity = "disk_gate"
		agentStatus = "degraded"
		agentLastErr = diskDetail
	case bootActive:
		agentActivity = "install"
		agentStatus = "degraded"
	case uiPhase == "start" || (nodeActive && !rpcOK):
		agentActivity = "node_starting"
		agentStatus = "degraded"
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

	if nodeActive {
		maybeAppendTonProgressLog(cfg, syncing || !rpcOK, info)
		logTail = tonLogTail(cfg, 80)
	}

	host := hostname()
	base, panelBase := effectivePublicBases(cfg)
	updatedAt := time.Now().UTC().Format(time.RFC3339)
	clientVer := info.ClientVersion
	if clientVer == "" || looksLikeShellNoise(clientVer) {
		clientVer = tonClientVersion()
	}
	clientVer = formatClientVersion(clientVer)
	if looksLikeShellNoise(clientVer) || normalizeClientVersion(clientVer) == "" {
		clientVer = ""
	}

	verifyPctUI := any(nil)
	if info.VerifyPct > 0 {
		verifyPctUI = info.VerifyPct * 100
	}

	rpcBlock := map[string]any{
		"ok":             rpcOK,
		"reachable":      rpcOK,
		"http_ok":        rpcOK,
		"process_up":     procOK,
		"port_open":      thaOpen,
		"seqno":          info.Seqno,
		"client_version": clientVer,
		"synced":         info.Synced,
		"error":          info.Error,
		"node_height":    nil,
	}
	if info.Seqno > 0 {
		rpcBlock["node_height"] = info.Seqno
	}
	if info.OutOfSyncOK && info.Seqno > 0 {
		rpcBlock["out_of_sync_sec"] = info.OutOfSyncSec
	}
	if verifyPctUI != nil {
		rpcBlock["verification_pct"] = verifyPctUI
	}

	syncBlock := map[string]any{
		"network":    network,
		"ibd":        syncing,
		"syncing":    syncing,
		"height":     nil,
		"ok":         rpcOK || info.OutOfSyncOK,
		"updated_at": updatedAt,
		"log_tail":   logTail,
		"detail":     tonSyncDetailWithBootstrap(cfg, info, syncing, bootActive),
		"history":    "recent_30d",
	}
	if info.Seqno > 0 {
		syncBlock["height"] = info.Seqno
	}
	if info.OutOfSyncOK && info.Seqno > 0 {
		syncBlock["out_of_sync_sec"] = info.OutOfSyncSec
	}
	if dumpPct > 0 && !info.Synced {
		syncBlock["dump_pct"] = dumpPct
	}
	if verifyPctUI != nil {
		syncBlock["verification_pct"] = verifyPctUI
	}

	var height any
	if info.Seqno > 0 {
		height = info.Seqno
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
			"activity":   agentActivity,
			"status":     agentStatus,
			"last_error": agentLastErr,
		},
		"services": map[string]any{
			"node":      nodeSvcEffective,
			"api":       apiSvc,
			"system":    systemctlActive(cfg.SystemService),
			"validator": systemctlActive("validator.service"),
			"mytoncore": systemctlActive("mytoncore.service"),
			"bootstrap": systemctlActive(fmt.Sprintf("ton-%s-bootstrap.service", cfg.Env)),
		},
		"checks": map[string]any{
			"node_process_up":   procOK,
			"validator_process": procOK,
			"bootstrap_done":    bootDone,
			"rpc_ok":            rpcOK,
			"disk_ok":           diskOK,
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
			"title":  "TON sync / bootstrap",
			"source": "journal+bootstrap",
			"lines":  logTail,
		},
		"height":             height,
		"start_error":        startErr,
		"supported_networks": ListKnownNetworks(),
		"capabilities":       LifecycleCapabilitiesFor(network, cfg.Env),
		"supported_steps":    SupportedLifecycleSteps(network, cfg.Env),
	}
}

func tonSetupIbdPct(info tonRPCInfo, rpcOK bool) any {
	if rpcOK && info.Synced {
		return 100.0
	}
	if info.VerifyPct > 0 {
		return float64(int(info.VerifyPct*1000+0.5)) / 10
	}
	if info.DumpPct > 0 {
		return float64(info.DumpPct)
	}
	return nil
}

func tonSyncDetail(info tonRPCInfo, syncing bool) string {
	if tonValidatorOOM() {
		if info.Seqno <= 0 {
			return "OOM killer — celldb preload/huge cache after dump (seqno 0)"
		}
		return fmt.Sprintf("OOM killer — celldb preload/huge cache · seqno %d", info.Seqno)
	}
	lag := ""
	if info.VerifyPct > 0 && info.VerifyPct < 1 && info.OutOfSyncOK && info.Seqno > 0 {
		lag = fmt.Sprintf(" · %.1f%% lag closed", info.VerifyPct*100)
	}
	if info.OutOfSyncOK && info.Seqno > 0 {
		if syncing {
			return fmt.Sprintf("%s sec behind%s · seqno %d", formatTonBehind(info.OutOfSyncSec), lag, info.Seqno)
		}
		return fmt.Sprintf("Synced · %s sec behind · seqno %d", formatTonBehind(info.OutOfSyncSec), info.Seqno)
	}
	if info.DumpPct > 0 && syncing {
		if tonValidatorOOM() {
			return fmt.Sprintf("MyTonCtrl dump %d%% · apply OOM (seqno 0)", info.DumpPct)
		}
		if tonValidatorApplyCrashLoop() {
			return fmt.Sprintf("MyTonCtrl dump %d%% · validator restart during apply (seqno 0)", info.DumpPct)
		}
		if info.OutOfSyncOK && info.OutOfSyncSec >= 60 {
			return fmt.Sprintf("MyTonCtrl dump %d%% · applying state (~%s dump age)", info.DumpPct, formatTonBehind(info.OutOfSyncSec))
		}

		return fmt.Sprintf("MyTonCtrl dump %d%% · applying state", info.DumpPct)
	}
	if info.OK {
		if syncing {
			return fmt.Sprintf("Syncing · seqno %d (out-of-sync pending)", info.Seqno)
		}
		return fmt.Sprintf("THA ok · seqno %d", info.Seqno)
	}
	if info.Error != "" {
		return info.Error
	}
	return "Waiting for TON HTTP API / validator catch-up"
}

// tonSyncDetailWithBootstrap — prefer bootstrap/dump phase over empty tha=0 copy.
// Once bootstrap.done (and not actively installing), never re-surface stale dump %.
func tonSyncDetailWithBootstrap(cfg Config, info tonRPCInfo, syncing, bootActive bool) string {
	bootDone := tonBootstrapDone(cfg)
	if bootActive || (!bootDone && !info.OutOfSyncOK && !info.OK) {
		if phase := tonBootstrapPhaseDetail(cfg); phase != "" {
			return phase
		}
		if info.DumpPct > 0 {
			return fmt.Sprintf("MyTonCtrl dump %d%%", info.DumpPct)
		}
		if bootActive {
			return "MyTonCtrl bootstrap (dump/install)"
		}
	}
	return tonSyncDetail(info, syncing)
}

func formatTonBehind(sec float64) string {
	if sec >= 1e6 {
		// Days (dump / deep catch-up).
		return fmt.Sprintf("%.0f", sec)
	}
	if sec >= 100 {
		return fmt.Sprintf("%.0f", sec)
	}
	return fmt.Sprintf("%g", sec)
}

func probeTonHTTPAPI(cfg Config) tonRPCInfo {
	host := cfg.UpstreamHost
	if host == "" {
		host = "127.0.0.1"
	}
	url := fmt.Sprintf("http://%s:%d/getMasterchainInfo", host, cfg.UpstreamPort)
	client := &http.Client{Timeout: 4 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		// Some THA builds want trailing path variants.
		url2 := fmt.Sprintf("http://%s:%d/api/v2/getMasterchainInfo", host, cfg.UpstreamPort)
		resp, err = client.Get(url2)
		if err != nil {
			return tonRPCInfo{Error: err.Error()}
		}
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode >= 300 {
		return tonRPCInfo{Error: fmt.Sprintf("http %d", resp.StatusCode)}
	}
	var doc map[string]any
	if err := json.Unmarshal(raw, &doc); err != nil {
		return tonRPCInfo{Error: "invalid json"}
	}
	// shapes: {ok:true,result:{last:{seqno:N}}} or {result:{…}}
	result, _ := doc["result"].(map[string]any)
	if result == nil {
		if inner, ok := doc["result"].(map[string]any); ok {
			result = inner
		}
	}
	seq := int64(0)
	if result != nil {
		if last, ok := result["last"].(map[string]any); ok {
			seq = int64FromAny(last["seqno"])
		}
		if seq == 0 {
			seq = int64FromAny(result["seqno"])
		}
	}
	if seq == 0 {
		seq = int64FromAny(doc["seqno"])
	}
	okFlag := true
	if v, has := doc["ok"]; has {
		okFlag = truthy(v)
	}
	if !okFlag && seq == 0 {
		return tonRPCInfo{Error: "tha not ok"}
	}
	return tonRPCInfo{OK: true, Seqno: seq}
}

// readTonOutOfSyncSec — (seconds behind, masterchain seqno hint, ok).
// Live MyTonCtrl / getstats first; cache is fallback only (and must be fresh).
// ❌ Preferring /etc/ton/<env>/out_of_sync forever froze UI at a stale peak
// (e.g. 50859) while MyTonCtrl already showed a lower "last known block … s ago".
func readTonOutOfSyncSec(cfg Config) (float64, int64, bool) {
	cachePath := filepath.Join(cfg.EtcDir, "out_of_sync")
	writeCache := func(v float64) {
		if cfg.EtcDir == "" {
			return
		}
		_ = os.MkdirAll(cfg.EtcDir, 0o755)
		_ = os.WriteFile(cachePath, []byte(fmt.Sprintf("%g\n", v)), 0o644)
	}

	// 1) getstats — has masterchain seqno as a tuple. MyTonCtrl status often
	// has oos without seqno; returning that first froze the UI at seqno=0 / dump 99%.
	if out := tonValidatorGetstats(); out != "" {
		if v, seq, ok := parseTonSyncSignals(out); ok && seq > 0 {
			writeCache(v)

			return v, seq, true
		}
	}
	// 2) mytonctrl status (dump / console not ready). ❌ `-c "status"` is a config path.
	if out, err := runCmd(10*time.Second, "bash", "-lc", tonMytonctrlStatusCmd()); err == nil {
		if v, seq, ok := parseTonSyncSignals(out); ok {
			writeCache(v)

			return v, seq, true
		}
	}
	// 3) getstats again if seqno was missing (oos-only parse).
	if out := tonValidatorGetstats(); out != "" {
		if v, seq, ok := parseTonSyncSignals(out); ok {
			writeCache(v)

			return v, seq, true
		}
	}
	// 4) cache / status files — only if mtime is recent (≤30s). Stale peak must not win.
	for _, p := range []string{
		cachePath,
		"/tmp/mytonctrl_status.txt",
		"/var/ton-work/keys/out_of_sync",
	} {
		st, err := os.Stat(p)
		if err != nil || time.Since(st.ModTime()) > 30*time.Second {
			continue
		}
		if b, err := os.ReadFile(p); err == nil {
			if v, seq, ok := parseTonSyncSignals(string(b)); ok {
				return v, seq, true
			}
		}
	}
	return 0, 0, false
}

func tonMytonctrlStatusCmd() string {
	return `
set +e
DB=/usr/local/bin/mytoncore/mytoncore.db
PY=/usr/local/bin/mytoncore/venv/bin/python
if [ -x "$PY" ] && [ -f "$DB" ]; then
  timeout 8 "$PY" -m mytonctrl -c "$DB" --cmd status 2>/dev/null
  exit 0
fi
timeout 8 mytonctrl --cmd status 2>/dev/null || timeout 8 mytonctrl <<<'status' 2>/dev/null || true
`
}

func tonValidatorGetstats() string {
	script := `
set -e
cfg=/var/ton-work/db/config.json
cli=/var/ton-work/keys/client
pub=/var/ton-work/keys/server.pub
bin=""
# Prefer real binary — /usr/bin/ton/validator-engine-console is often a CMake dir (-x true).
for c in /usr/bin/ton/validator-engine-console/validator-engine-console \
  /usr/local/bin/validator-engine-console; do
  if [ -x "$c" ] && [ ! -d "$c" ]; then bin="$c"; break; fi
done
if [ -z "$bin" ]; then
  if command -v validator-engine-console >/dev/null 2>&1; then
    bin=$(command -v validator-engine-console)
  fi
fi
[ -n "$bin" ] || exit 0
[ -f "$cfg" ] && [ -f "$cli" ] && [ -f "$pub" ] || exit 0
port=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["control"][0]["port"])' "$cfg" 2>/dev/null \
  || jq -r '.control[0].port' "$cfg" 2>/dev/null \
  || grep -oP '"port"\s*:\s*\K[0-9]+' "$cfg" 2>/dev/null | head -1)
[ -n "$port" ] || exit 0
timeout 4 "$bin" -k "$cli" -p "$pub" -a "127.0.0.1:$port" -c "getstats" 2>/dev/null | head -200 || true
`
	out, err := runCmd(6*time.Second, "bash", "-lc", script)
	if err != nil {
		return ""
	}
	return out
}

func parseTonOutOfSync(s string) (float64, bool) {
	v, _, ok := parseTonSyncSignals(s)
	return v, ok
}

// stripANSIEscapes — MyTonCtrl colors numbers (`\x1b[32m1\x1b[0m`) which break oos regex.
func stripANSIEscapes(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for i := 0; i < len(s); i++ {
		if s[i] == 0x1b && i+1 < len(s) && s[i+1] == '[' {
			j := i + 2
			for j < len(s) {
				c := s[j]
				if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') {
					j++
					break
				}
				j++
			}
			i = j - 1
			continue
		}
		b.WriteByte(s[i])
	}
	return b.String()
}

// parseTonSyncSignals — MyTonCtrl "out of sync" text, timediff, or getstats
// unixtime − masterchainblocktime. Also best-effort masterchain seqno.
func parseTonSyncSignals(s string) (oos float64, seqno int64, ok bool) {
	s = stripANSIEscapes(s)
	seqno = parseTonSeqno(s)
	// Prefer Masterchain … sec (or any "out of sync … sec"). Skip local-validator blocks.
	for _, re := range []*regexp.Regexp{tonMasterchainOutOfSyncRe, tonOutOfSyncRe} {
		if m := re.FindStringSubmatch(s); len(m) >= 2 {
			v, err := strconv.ParseFloat(m[1], 64)
			if err == nil && v >= 0 {
				return v, seqno, true
			}
		}
	}
	// Initial IBD: "Syncing blocks, last known block was 35601 s ago"
	if m := tonLastKnownBlockAgoRe.FindStringSubmatch(s); len(m) >= 2 {
		v, err := strconv.ParseFloat(m[1], 64)
		if err == nil && v >= 0 {
			return v, seqno, true
		}
	}
	// Cached file / bare number / legacy "out of sync: N" without unit — only when
	// the line is not the Local-validator blocks field.
	if !strings.Contains(strings.ToLower(s), "local validator out of sync") {
		if m := tonOutOfSyncPlainRe.FindStringSubmatch(s); len(m) >= 2 {
			v, err := strconv.ParseFloat(m[1], 64)
			if err == nil && v >= 0 {
				return v, seqno, true
			}
		}
		// Single-line cache written by agent: "<seconds>\n"
		if v, err := strconv.ParseFloat(strings.TrimSpace(s), 64); err == nil && v >= 0 && !strings.Contains(s, "\n") {
			return v, seqno, true
		}
		if lines := strings.Split(strings.TrimSpace(s), "\n"); len(lines) == 1 {
			if v, err := strconv.ParseFloat(strings.TrimSpace(lines[0]), 64); err == nil && v >= 0 {
				return v, seqno, true
			}
		}
	}
	if m := tonTimediffRe.FindStringSubmatch(s); len(m) >= 2 {
		v, err := strconv.ParseFloat(m[1], 64)
		if err == nil {
			if v < 0 {
				v = -v
			}
			return v, seqno, true
		}
	}
	um := tonUnixTimeRe.FindStringSubmatch(s)
	mm := tonMasterTimeRe.FindStringSubmatch(s)
	if len(um) >= 2 && len(mm) >= 2 {
		u, err1 := strconv.ParseInt(um[1], 10, 64)
		mt, err2 := strconv.ParseInt(mm[1], 10, 64)
		// masterchainblocktime==0 → node has not applied MC blocks yet (dump/start).
		if err1 == nil && err2 == nil && u > 0 && mt > 1_000_000_000 {
			diff := float64(u - mt)
			if diff < 0 {
				diff = 0
			}
			return diff, seqno, true
		}
	}
	return 0, seqno, false
}

// parseTonSeqno — getstats prints masterchainblock as a tuple, not
// masterchainblocknumber=N. Hint regex must not take workchain 8000000000000000.
func parseTonSeqno(s string) int64 {
	if m := tonMasterBlockTupleRe.FindStringSubmatch(s); len(m) >= 2 {
		if n, err := strconv.ParseInt(m[1], 10, 64); err == nil && tonPlausibleMCSeqno(n) {
			return n
		}
	}
	if m := tonMasterBlockRe.FindStringSubmatch(s); len(m) >= 2 {
		if n, err := strconv.ParseInt(m[1], 10, 64); err == nil && tonPlausibleMCSeqno(n) {
			return n
		}
	}
	if m := tonShardClientSeqRe.FindStringSubmatch(s); len(m) >= 2 {
		if n, err := strconv.ParseInt(m[1], 10, 64); err == nil && tonPlausibleMCSeqno(n) {
			return n
		}
	}
	if m := tonSeqnoHintRe.FindStringSubmatch(s); len(m) >= 2 {
		if n, err := strconv.ParseInt(m[1], 10, 64); err == nil && tonPlausibleMCSeqno(n) {
			return n
		}
	}

	return 0
}

func tonPlausibleMCSeqno(n int64) bool {
	return n > 0 && n < 1_000_000_000
}

// tonLagClosedPct — (peakBehind - behind) / peakBehind * 100 (Solana lesson).
// Grows only when out_of_sync_sec actually shrinks. ❌ Not seqno/tip ratio.
func tonLagClosedPct(cfg Config, behindSec float64) (float64, bool) {
	if behindSec < 0 {
		return 0, false
	}
	if behindSec <= tonOutOfSyncHealthySec {
		return 99.9, true
	}
	maxBehind := loadTonCatchupMaxBehind(cfg)
	if behindSec > maxBehind {
		maxBehind = behindSec
		saveTonCatchupMaxBehind(cfg, maxBehind)
	}
	if maxBehind <= tonOutOfSyncHealthySec {
		return 0, false
	}
	pct := (maxBehind - behindSec) / maxBehind * 100
	if pct > 99.9 {
		pct = 99.9
	}
	if pct < 0.1 {
		pct = 0.1
	}
	return float64(int(pct*10+0.5)) / 10, true
}

func tonCatchupStatePath(cfg Config) string {
	base := filepath.Dir(cfg.StateFile)
	if strings.TrimSpace(base) == "" || base == "." {
		base = filepath.Join("/var/lib/rpcnode", "ton-"+cfg.Env)
	}
	return filepath.Join(base, "ton-catchup.json")
}

func loadTonCatchupMaxBehind(cfg Config) float64 {
	doc := readJSONFile(tonCatchupStatePath(cfg))
	if doc == nil {
		return 0
	}
	switch v := doc["max_behind_sec"].(type) {
	case float64:
		return v
	case int64:
		return float64(v)
	case int:
		return float64(v)
	default:
		return 0
	}
}

func saveTonCatchupMaxBehind(cfg Config, maxBehind float64) {
	if maxBehind <= 0 {
		return
	}
	path := tonCatchupStatePath(cfg)
	_ = ensureDir(filepath.Dir(path))
	_ = writeJSONFile(path, map[string]any{
		"max_behind_sec": maxBehind,
		"updated_at":     time.Now().UTC().Format(time.RFC3339),
	})
}

func clearTonCatchupMaxBehind(cfg Config) {
	_ = os.Remove(tonCatchupStatePath(cfg))
}

func tonValidatorRunning() bool {
	if systemctlActive("validator.service") == "active" {
		return true
	}
	out, _ := exec.Command("bash", "-lc", `pgrep -af 'validator-engine' | grep -vE 'pgrep|grep' | head -1`).CombinedOutput()
	return strings.TrimSpace(string(out)) != ""
}

func tonBootstrapDone(cfg Config) bool {
	if fileExists(filepath.Join(cfg.EtcDir, "bootstrap.done")) {
		return true
	}
	// Mid-install MyTonCtrl creates /usr/bin/ton/validator-engine/ as a build
	// directory long before dump finishes — never treat path existence as done
	// while the bootstrap unit is still running.
	bootUnit := fmt.Sprintf("ton-%s-bootstrap.service", cfg.Env)
	if st := systemctlActive(bootUnit); st == "activating" || st == "active" || st == "reloading" {
		return false
	}
	if tonValidatorEngineBin() != "" && systemctlActive("validator.service") == "active" {
		return true
	}
	return false
}

func tonBootstrapActive(cfg Config) bool {
	u := fmt.Sprintf("ton-%s-bootstrap.service", cfg.Env)
	st := systemctlActive(u)
	if st == "activating" || st == "reloading" {
		return true
	}
	return st == "active" && !tonBootstrapDone(cfg)
}

// tonValidatorEngineBin — real binary (not the CMake build dir of the same name).
func tonValidatorEngineBin() string {
	for _, p := range []string{
		"/usr/bin/ton/validator-engine/validator-engine",
		"/usr/bin/ton/validator-engine",
		"/usr/local/bin/validator-engine",
		"/usr/bin/validator-engine",
	} {
		st, err := os.Stat(p)
		if err != nil || st.IsDir() {
			continue
		}
		if st.Mode()&0o111 != 0 {
			return p
		}
	}
	return ""
}

func tonBootstrapDumpPct(cfg Config) int {
	pct, _ := tonBootstrapDumpProgress(cfg)
	return pct
}

func tonDumpProgressPath(cfg Config) string {
	base := strings.TrimSpace(cfg.EtcDir)
	if base == "" {
		base = filepath.Join("/etc/ton", cfg.Env)
	}
	return filepath.Join(base, "dump-progress.json")
}

func saveTonDumpProgress(cfg Config, pct int, detail string) {
	if pct <= 0 || pct > 100 {
		return
	}
	path := tonDumpProgressPath(cfg)
	_ = ensureDir(filepath.Dir(path))
	_ = writeJSONFile(path, map[string]any{
		"pct":        pct,
		"detail":     detail,
		"updated_at": time.Now().UTC().Format(time.RFC3339),
	})
}

func loadTonDumpProgress(cfg Config) (pct int, detail string) {
	doc := readJSONFile(tonDumpProgressPath(cfg))
	if doc == nil {
		return 0, ""
	}
	switch v := doc["pct"].(type) {
	case float64:
		pct = int(v)
	case int:
		pct = v
	case json.Number:
		i, _ := v.Int64()
		pct = int(i)
	}
	if pct <= 0 || pct > 100 {
		return 0, ""
	}
	if s, ok := doc["detail"].(string); ok {
		detail = s
	}
	return pct, detail
}

func clearTonDumpProgress(cfg Config) {
	_ = os.Remove(tonDumpProgressPath(cfg))
}

// tonBootstrapDumpProgress — aria2c / wget dump % (+ size/ETA / checksum detail) from bootstrap.log.
func tonBootstrapDumpProgress(cfg Config) (pct int, detail string) {
	logPath := filepath.Join("/var/log/ton", cfg.Env, "bootstrap.log")
	b, err := os.ReadFile(logPath)
	if err != nil {
		return 0, ""
	}
	// aria2 often rewrites the same line with CR.
	text := strings.ReplaceAll(string(b), "\r", "\n")
	lines := strings.Split(text, "\n")
	// install.sh compile/git noise can push aria2 lines past a short tail —
	// scan a deep window so dump % does not vanish mid-bootstrap.
	start := len(lines) - 4000
	if start < 0 {
		start = 0
	}

	var (
		dumpPct    int
		dumpDetail string
		checkPct   int
		checkSize  string
		postDump   string // verify finished / extract phase label
	)
	for i := len(lines) - 1; i >= start; i-- {
		ln := strings.TrimSpace(lines[i])
		if ln == "" {
			continue
		}
		low := strings.ToLower(ln)
		if postDump == "" {
			switch {
			case strings.Contains(low, "verification finished") || strings.Contains(low, "download complete"):
				postDump = "verifying/extracting dump"
			case strings.Contains(low, "extract") && (strings.Contains(low, "dump") || strings.Contains(low, ".tar") || strings.Contains(low, "lz")):
				postDump = "extracting dump"
			}
		}
		if checkPct <= 0 {
			if m := tonAria2ChecksumRe.FindStringSubmatch(ln); len(m) >= 4 {
				n, _ := strconv.Atoi(m[3])
				if n > 0 && n <= 100 {
					checkPct = n
					checkSize = fmt.Sprintf("%s/%s", m[1], m[2])
				}
			}
		}
		if dumpPct <= 0 {
			if m := tonAria2DumpRe.FindStringSubmatch(ln); len(m) >= 4 {
				n, _ := strconv.Atoi(m[3])
				if n > 0 && n <= 100 {
					dumpPct = n
					dumpDetail = fmt.Sprintf("%s/%s", m[1], m[2])
					if len(m) >= 5 && m[4] != "" {
						dumpDetail += " · ETA " + m[4]
					}
				}
			} else if m := tonWgetDumpPctRe.FindStringSubmatch(ln); len(m) >= 2 {
				raw := m[1]
				if raw == "" && len(m) >= 3 {
					raw = m[2]
				}
				n, _ := strconv.Atoi(raw)
				if n > 0 && n <= 100 {
					dumpPct = n
				}
			}
		}
		if dumpPct > 0 && (checkPct > 0 || postDump != "") {
			break
		}
	}

	// After download hits 100%, aria2/mytoninstaller still checksum (~minutes for ~200GiB)
	// then extract — keep bar at 100 but surface the real phase (not opaque dump 100% / tha=0).
	if dumpPct >= 100 {
		if checkPct > 0 && checkPct < 100 && postDump == "" {
			d := fmt.Sprintf("checksum %d%%", checkPct)
			if checkSize != "" {
				d += " · " + checkSize
			}
			return 100, d
		}
		if postDump != "" {
			d := postDump
			if dumpDetail != "" {
				d = dumpDetail + " · " + postDump
			}
			return 100, d
		}
		if checkPct >= 100 {
			d := "checksum done"
			if dumpDetail != "" {
				d = dumpDetail + " · " + d
			}
			return 100, d
		}
	}
	if dumpPct > 0 {
		return dumpPct, dumpDetail
	}
	return 0, ""
}

// tonBootstrapPhaseDetail — honest bootstrap phase for UI when THA/validator not up yet.
func tonBootstrapPhaseDetail(cfg Config) string {
	// Stale aria2 100% after bootstrap.done must not keep driving UI copy.
	if tonBootstrapDone(cfg) && !tonBootstrapActive(cfg) {
		return ""
	}
	logPath := filepath.Join("/var/log/ton", cfg.Env, "bootstrap.log")
	b, err := os.ReadFile(logPath)
	if err == nil {
		text := strings.ReplaceAll(string(b), "\r", "\n")
		lines := strings.Split(text, "\n")
		for i := len(lines) - 1; i >= 0 && i >= len(lines)-80; i-- {
			ln := strings.TrimSpace(lines[i])
			if ln == "" {
				continue
			}
			low := strings.ToLower(ln)
			switch {
			case strings.Contains(low, "waiting for apt"):
				return "MyTonCtrl bootstrap · waiting for apt/dpkg"
			case strings.Contains(low, "timeout waiting for apt"):
				return "MyTonCtrl bootstrap · apt wait timed out (retrying)"
			case strings.Contains(low, "enable tha") || strings.Contains(low, "ton_http_api") || strings.Contains(low, "ton-http-api"):
				return "MyTonCtrl bootstrap · enabling THA"
			case strings.Contains(low, "bootstrap marker") || strings.Contains(low, "bootstrap finished"):
				return "MyTonCtrl bootstrap · finishing"
			case strings.Contains(low, "checksum:") && strings.Contains(low, "%") &&
				!strings.Contains(low, "(100%)"):
				return "MyTonCtrl bootstrap · verifying dump checksum"
			case strings.Contains(low, "verification finished") || strings.Contains(low, "download complete"):
				return "MyTonCtrl bootstrap · dump downloaded · extracting"
			case strings.Contains(low, "install.sh attempt") || strings.Contains(low, "install.sh exit"):
				return "MyTonCtrl bootstrap · running install.sh"
			case strings.Contains(low, "mytoninstaller"):
				return "MyTonCtrl bootstrap · mytoninstaller"
			case strings.Contains(low, "bootstrap start"):
				return "MyTonCtrl bootstrap · starting install"
			}
		}
	}
	if pct, detail := tonBootstrapDumpProgress(cfg); pct > 0 {
		if detail != "" {
			return fmt.Sprintf("MyTonCtrl dump %d%% · %s", pct, detail)
		}
		return fmt.Sprintf("MyTonCtrl dump %d%%", pct)
	}
	return ""
}

func truncateRunes(s string, max int) string {
	if max <= 0 || len(s) <= max {
		return s
	}
	return s[:max] + "…"
}

func tonStartFailureDetail(cfg Config, procOK, bootDone, bootActive bool) (string, bool) {
	if bootActive || (!bootDone && systemctlActive(fmt.Sprintf("ton-%s-bootstrap.service", cfg.Env)) == "activating") {
		return "", false
	}
	bootUnit := fmt.Sprintf("ton-%s-bootstrap.service", cfg.Env)
	// Activating / auto-restart after apt-lock flake — not a hard start error yet.
	if st := systemctlActive(bootUnit); st == "activating" || st == "reloading" {
		return "", false
	}
	if systemctlFailed(bootUnit) && !bootDone {
		msg := "MyTonCtrl bootstrap failed"
		if tip := tonBootstrapLogTip(cfg, 6); tip != "" {
			msg += " — " + tip
		} else if snip := stripUnitPathNoise(journalUnitSnippet(bootUnit, 16)); snip != "" {
			msg += " — " + snip
		}
		return msg, true
	}
	if bootDone && !procOK {
		if tonValidatorOOM() {
			return "validator.service killed by OOM — MyTonCtrl celldb preload / huge cache (heal strips preload-all and caps cache)", true
		}
		if systemctlFailed("validator.service") {
			snip := stripUnitPathNoise(journalUnitSnippet("validator.service", 12))
			msg := "validator.service failed"
			if snip != "" {
				msg += " — " + snip
			}
			return msg, true
		}
	}
	return "", false
}

func tonDiskGateOK(cfg Config, prof NetworkProfile) (ok bool, freeGiB, needGiB float64, detail string) {
	needGiB = prof.DiskHintGiB
	if needGiB <= 0 {
		needGiB = 1024
	}
	// Soft floor for start: allow bootstrap with ~20% of hint (dump grows).
	floor := needGiB * 0.2
	if floor < 80 {
		floor = 80
	}
	path := cfg.DataDir
	if path == "" {
		path = prof.DataPath
	}
	if path == "" {
		path = "/data/ton/" + cfg.Env
	}
	freeGiB = freeDiskGiB(path)
	if freeGiB < 0 {
		return true, freeGiB, needGiB, "disk free unknown"
	}
	if freeGiB < floor {
		return false, freeGiB, needGiB, fmt.Sprintf("%.0f GiB free < %.0f GiB floor (hint %.0f GiB for ~30d liteserver)", freeGiB, floor, needGiB)
	}
	return true, freeGiB, needGiB, fmt.Sprintf("%.0f GiB free (hint %.0f GiB)", freeGiB, needGiB)
}

var tonClientVerRe = regexp.MustCompile(`(?i)(?:validator(?:-engine)?|ton|mytonctrl)?[^0-9]{0,24}(v?\d+\.\d+(?:\.\d+){0,3})\b`)

func tonClientVersion() string {
	// Prefer absolute binaries — PATH often lacks /usr/bin/ton during bootstrap.
	// ❌ /usr/bin/ton/validator-engine is often a CMake dir mid-install.
	bins := []string{}
	if b := tonValidatorEngineBin(); b != "" {
		bins = append(bins, b)
	}
	bins = append(bins, "/usr/local/bin/validator-engine", "/usr/bin/validator-engine")
	for _, bin := range bins {
		st, err := os.Stat(bin)
		if err != nil || st.IsDir() {
			continue
		}
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		out, _ := exec.CommandContext(ctx, bin, "--version").CombinedOutput()
		cancel()
		if v := parseTonClientVersionOutput(string(out)); v != "" {
			return v
		}
	}
	for _, c := range []string{
		`command -v mytonctrl >/dev/null && mytonctrl -c "version" 2>/dev/null | head -5`,
		`dpkg-query -W -f='${Version}\n' ton-http-api 2>/dev/null`,
		`dpkg-query -W -f='${Version}\n' ton 2>/dev/null`,
	} {
		out, _ := runCmd(4*time.Second, "bash", "-lc", c)
		if v := parseTonClientVersionOutput(out); v != "" {
			return v
		}
	}
	return ""
}

func parseTonClientVersionOutput(raw string) string {
	s := strings.TrimSpace(raw)
	if s == "" || looksLikeShellNoise(s) {
		return ""
	}
	// Take first non-noise line.
	for _, line := range strings.Split(s, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || looksLikeShellNoise(line) {
			continue
		}
		// Stock binary prints commit hash, not semver — use short commit.
		if m := tonBuildCommitRe.FindStringSubmatch(line); len(m) >= 2 {
			c := m[1]
			if len(c) > 12 {
				c = c[:12]
			}
			return c
		}
		if m := tonClientVerRe.FindStringSubmatch(line); len(m) >= 2 {
			return formatClientVersion(m[1])
		}
		if tok := normalizeClientVersion(line); tok != "" && clientVersionToken(tok) {
			return formatClientVersion(tok)
		}
	}
	return ""
}

func looksLikeShellNoise(s string) bool {
	low := strings.ToLower(strings.TrimSpace(s))
	if low == "" {
		return true
	}
	return strings.Contains(low, "command not found") ||
		strings.Contains(low, "not found") ||
		strings.HasPrefix(low, "bash:") ||
		strings.HasPrefix(low, "sh:") ||
		strings.Contains(low, "no such file") ||
		strings.Contains(low, "permission denied") ||
		strings.Contains(low, "usage:")
}

// tonBootstrapLogTip — last useful error/status lines from bootstrap.log (UI start_error).
func tonBootstrapLogTip(cfg Config, n int) string {
	if n <= 0 {
		n = 4
	}
	path := filepath.Join("/var/log/ton", cfg.Env, "bootstrap.log")
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	all := strings.Split(string(b), "\n")
	var pick []string
	for i := len(all) - 1; i >= 0 && len(pick) < n; i-- {
		ln := strings.TrimSpace(all[i])
		if ln == "" {
			continue
		}
		low := strings.ToLower(ln)
		if strings.Contains(low, "waiting for apt") ||
			strings.Contains(low, "could not get lock") ||
			strings.Contains(low, "bootstrap failed") ||
			strings.Contains(low, "install.sh") ||
			strings.Contains(low, "timeout waiting") ||
			strings.Contains(low, "error") ||
			strings.HasPrefix(ln, "E:") {
			pick = append([]string{ln}, pick...)
		}
	}
	if len(pick) == 0 {
		// Fallback: last non-empty lines.
		for i := len(all) - 1; i >= 0 && len(pick) < n; i-- {
			ln := strings.TrimSpace(all[i])
			if ln != "" {
				pick = append([]string{ln}, pick...)
			}
		}
	}
	out := strings.Join(pick, " · ")
	if len(out) > 400 {
		out = out[len(out)-400:]
	}
	return out
}

func tonLogTail(cfg Config, n int) []string {
	var lines []string
	boot := filepath.Join("/var/log/ton", cfg.Env, "bootstrap.log")
	if b, err := os.ReadFile(boot); err == nil {
		all := strings.Split(strings.TrimSpace(string(b)), "\n")
		if len(all) > n/2 {
			all = all[len(all)-n/2:]
		}
		lines = append(lines, all...)
	}
	progPath := filepath.Join(cfg.EtcDir, "sync-progress.log")
	if b, err := os.ReadFile(progPath); err == nil {
		all := strings.Split(strings.TrimSpace(string(b)), "\n")
		if len(all) > n/2 {
			all = all[len(all)-n/2:]
		}
		lines = append(lines, all...)
	}
	if j := journalUnitSnippet("validator.service", 20); j != "" {
		lines = append(lines, strings.Split(j, "\n")...)
	}
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return lines
}

func int64FromAny(v any) int64 {
	switch t := v.(type) {
	case float64:
		return int64(t)
	case int64:
		return t
	case int:
		return int64(t)
	case json.Number:
		i, _ := t.Int64()
		return i
	case string:
		i, _ := strconv.ParseInt(strings.TrimSpace(t), 10, 64)
		return i
	default:
		return 0
	}
}
