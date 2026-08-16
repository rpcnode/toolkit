package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// collectSolana — Agave / test-validator lifecycle (no TRON snapshot).
func collectSolana(cfg Config) map[string]any {
	network := cfg.Network
	if network == "" {
		network = "solana"
	}
	prof := LookupNetworkProfile(network, cfg.Env)
	localnet := isSolanaLocalnet(cfg.Env)

	nodeState := systemctlActive(cfg.NodeService)
	procOK, procCmd := solanaProcessRunning(cfg)
	startErr, startBad := solanaStartFailureDetail(cfg, procOK)
	nodeActive := procOK || nodeState == "active" || nodeState == "activating"

	var rpc solanaRPCResult
	var nodePortOpen bool
	if nodeActive {
		nodePortOpen = portOpen(cfg.UpstreamHost, cfg.UpstreamPort)
		if nodePortOpen {
			rpc = probeSolanaRPC(cfg)
		}
	}
	rpcOK := rpc.OK
	catchingUp := rpcOK && !rpc.Healthy && !localnet
	if localnet {
		catchingUp = false
	}

	nodeSvcEffective := nodeState
	switch {
	case startBad:
		nodeSvcEffective = "failed"
	case nodeActive && rpcOK && !catchingUp:
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

	diskOK, freeGiB, needGiB, diskDetail := solanaDiskGateOK(cfg)

	logTail := solanaLogTail(cfg, 80)
	warmupDetail := ""
	if nodeActive && !rpcOK && !startBad {
		warmupDetail = solanaWarmupDetail(cfg, "agave-validator warming up · waiting for RPC")
	}

	sizeOnDisk := solanaLedgerSizeBytes(cfg)
	verifyPct, verifyOK := solanaVerificationPct(cfg, rpc, rpcOK, catchingUp, localnet, warmupDetail)
	var slotsBehind int64 = -1
	var clusterSlot int64
	if catchingUp && rpcOK {
		if me, tip, behind, ok := solanaCatchupSlots(cfg, rpc); ok {
			slotsBehind = behind
			clusterSlot = tip
			if rpc.Slot <= 0 && me > 0 {
				rpc.Slot = me
			}
		}
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
		IBD:            catchingUp, // reused as catch-up flag; buildRunStep has solana branch
		Progress:       prog,
	}
	if rpcOK {
		lcIn.Height = rpc.Slot
	}
	if clusterSlot > 0 {
		lcIn.Headers = clusterSlot
	}
	if rpc.Peers >= 0 {
		lcIn.Peers = rpc.Peers
	}
	if verifyOK {
		lcIn.VerifyPct = verifyPct / 100.0
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

	chainTitle := "Cluster catch-up"
	chainDetail := "Waiting for getHealth"
	if localnet {
		chainTitle = "Localnet ready"
		if rpcOK {
			chainDetail = fmt.Sprintf("Localnet · slot %d", rpc.Slot)
		} else {
			chainDetail = "Waiting for test-validator RPC"
		}
	} else if !rpcOK && warmupDetail != "" {
		chainDetail = warmupDetail
	} else if rpcOK && catchingUp {
		// Prefer structured node/tip/behind (same fields as Sync card).
		tip := clusterSlot
		if tip <= 0 && slotsBehind >= 0 && rpc.Slot > 0 {
			tip = rpc.Slot + slotsBehind
		}
		if rpc.Slot > 0 && tip > 0 && slotsBehind >= 0 {
			chainDetail = fmt.Sprintf("node %d · tip %d · %d behind", rpc.Slot, tip, slotsBehind)
			if verifyOK {
				chainDetail = fmt.Sprintf("%s · %.1f%% lag closed", chainDetail, verifyPct)
			}
		} else if rpc.Behind != "" {
			chainDetail = rpc.Behind
		} else {
			chainDetail = fmt.Sprintf("Catching up · slot %d", rpc.Slot)
		}
	} else if rpcOK {
		chainDetail = fmt.Sprintf("Healthy · slot %d", rpc.Slot)
	}

	setupSteps := []map[string]any{
		{"id": "registry", "title": "Instance registered", "done": instRegistered,
			"detail": "INSTANCE.json + /etc/rpcnode/instances.d"},
		{"id": "disk", "title": "Disk floor", "done": diskOK,
			"detail": diskDetail, "active": !diskOK && apiUp},
		{"id": "node", "title": "Agave running", "done": nodeActive && !startBad,
			"detail": "process/systemd", "active": apiUp && !nodeActive},
		{"id": "rpc", "title": "RPC responding", "done": rpcOK,
			"detail": "getSlot", "active": nodeActive && !rpcOK},
		{"id": "catchup", "title": chainTitle, "done": rpcOK && !catchingUp,
			"detail": chainDetail,
			"active": rpcOK && catchingUp},
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
	case uiPhase == "start" || uiPhase == "run" || catchingUp:
		health = "degraded"
		degraded = true
	case !nodeActive || !rpcOK:
		health = "degraded"
		degraded = true
	}

	nodeReady := nodeActive && rpcOK && !catchingUp && !startBad
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
	case catchingUp:
		agentActivity = "catchup"
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
		"slot":       rpc.Slot,
		"healthy":    rpc.Healthy,
		"version":    rpc.Version,
		"behind":     rpc.Behind,
		"syncing":    catchingUp,
	}
	if rpcOK {
		// height/blocks stay as slot for lifecycle/progress compatibility.
		rpcBlock["height"] = rpc.Slot
		rpcBlock["blocks"] = rpc.Slot
		rpcBlock["headers"] = rpc.Slot
		if rpc.BlockHeight > 0 {
			rpcBlock["block_height"] = rpc.BlockHeight
		}
	}
	if rpc.Peers >= 0 {
		rpcBlock["peers"] = rpc.Peers
	}
	if rpc.Version != "" {
		rpcBlock["client_version"] = rpc.Version
	}
	if sizeOnDisk > 0 {
		rpcBlock["size_on_disk"] = sizeOnDisk
	}
	if verifyOK {
		rpcBlock["verification_pct"] = verifyPct
	}
	if slotsBehind >= 0 {
		rpcBlock["slots_behind"] = slotsBehind
	}
	if clusterSlot > 0 {
		rpcBlock["cluster_slot"] = clusterSlot
	}

	syncBlock := map[string]any{
		"ok":       rpcOK && !catchingUp,
		"catching": catchingUp,
		"syncing":  catchingUp,
		"ibd":      catchingUp,
		"slot":     rpc.Slot,
		"healthy":  rpc.Healthy,
		"detail":   chainDetail,
		"network":  network,
		"log_tail": logTail,
	}
	if rpcOK {
		syncBlock["height"] = rpc.Slot
		syncBlock["blocks"] = rpc.Slot
		syncBlock["headers"] = rpc.Slot
		if rpc.BlockHeight > 0 {
			syncBlock["block_height"] = rpc.BlockHeight
		}
	}
	if rpc.Peers >= 0 {
		syncBlock["peers"] = rpc.Peers
	}
	if sizeOnDisk > 0 {
		syncBlock["size_on_disk"] = sizeOnDisk
		syncBlock["size_on_disk_gb"] = round1(float64(sizeOnDisk) / (1024 * 1024 * 1024))
	}
	if verifyOK {
		syncBlock["verification_pct"] = verifyPct
	}
	if slotsBehind >= 0 {
		syncBlock["slots_behind"] = slotsBehind
	}
	if clusterSlot > 0 {
		syncBlock["cluster_slot"] = clusterSlot
		syncBlock["headers"] = clusterSlot // tip for Sync card local/tip display
	}
	if rpc.Peers >= 0 && chainDetail != "" {
		syncBlock["detail"] = fmt.Sprintf("%s · peers %d", chainDetail, rpc.Peers)
	}

	out := map[string]any{
		"ok":             !startBad,
		"version":        agentVersion(),
		"client_version": rpc.Version,
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
		// Agent-owned lines for panel Logs / wizard (UI only renders — no invent).
		"logs": map[string]any{
			"title":  "Logs",
			"source": "solana-validator",
			"lines":  logTail,
		},
		"rpc": rpcBlock,
		"checks": map[string]any{
			"node_process_up":   procOK,
			"agave_process":     procOK,
			"node_port_open":    nodePortOpen,
			"rpc_ok":            rpcOK,
			"api_agent_up":      apiUp,
			"public_port_open":  publicPortOpen,
			"instance_registered": instRegistered,
		},
		"services": map[string]any{
			"node":        nodeSvcEffective,
			"node_unit":   cfg.NodeService,
			"api_agent":   apiSvc,
			"system_agent": "active",
		},
		"ports": map[string]any{
			"public":   publicPort,
			"agent":    agentPort,
			"upstream": cfg.UpstreamPort,
			"p2p":      cfg.P2PPort,
		},
		"paths": map[string]any{
			"data":     cfg.DataDir,
			"etc":      cfg.EtcDir,
			"opt":      cfg.OptDir,
			"ledger":   cfg.DataDir + "/ledger",
			"accounts": cfg.DataDir + "/accounts",
			"script":   solanaRunScriptPath(cfg),
		},
		"profile": map[string]any{
			"network": network, "env": cfg.Env,
			"display_name": prof.DisplayName,
			"watch_slug":   prof.WatchSlug,
			"cluster":      prof.ChainFlag,
		},
		"instance": map[string]any{
			"network": network, "env": cfg.Env, "id": "solana-" + cfg.Env,
		},
		"supported_steps": SupportedLifecycleSteps(network, cfg.Env),
		"capabilities":    LifecycleCapabilitiesFor(network, cfg.Env),
		"connect": map[string]any{
			"ready":      nodeReady && apiUp,
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
	if strings.TrimSpace(procCmd) != "" {
		out["process_cmd"] = procCmd
	}

	return out
}

// solanaVerificationPct — honest bar for the panel.
// Healthy → 100; Agave snapshot download log %; catch-up → share of lag closed
// since peak slots_behind (NOT me/tip — that always reads ~99.9% with a few k behind).
func solanaVerificationPct(cfg Config, rpc solanaRPCResult, rpcOK, catchingUp, localnet bool, warmupDetail string) (float64, bool) {
	if rpcOK && !catchingUp {
		clearSolanaCatchupMaxBehind(cfg)
		return 100, true
	}
	if localnet && rpcOK {
		clearSolanaCatchupMaxBehind(cfg)
		return 100, true
	}
	if p := solanaDownloadPctFromDetail(warmupDetail); p > 0 {
		return p, true
	}
	if p := solanaDownloadPctFromLogs(cfg); p > 0 {
		return p, true
	}
	if catchingUp && rpcOK {
		if _, _, behind, ok := solanaCatchupSlots(cfg, rpc); ok {
			if p, ok := solanaCatchupLagClosedPct(cfg, behind); ok {
				return p, true
			}
		}
	}

	return 0, false
}

// solanaCatchupLagClosedPct — (peakBehind - behind) / peakBehind.
// Grows only when the node actually closes lag; stuck lag → stuck %.
func solanaCatchupLagClosedPct(cfg Config, behind int64) (float64, bool) {
	if behind < 0 {
		return 0, false
	}
	if behind == 0 {
		return 99.9, true
	}
	maxBehind := loadSolanaCatchupMaxBehind(cfg)
	if behind > maxBehind {
		maxBehind = behind
		saveSolanaCatchupMaxBehind(cfg, maxBehind)
	}
	if maxBehind <= 0 {
		return 0, false
	}
	pct := float64(maxBehind-behind) / float64(maxBehind) * 100
	if pct > 99.9 {
		pct = 99.9
	}
	if pct < 0.1 {
		pct = 0.1
	}
	pct = float64(int(pct*10+0.5)) / 10
	return pct, true
}

func solanaCatchupStatePath(cfg Config) string {
	return filepath.Join(filepath.Dir(cfg.StateFile), "solana-catchup.json")
}

func loadSolanaCatchupMaxBehind(cfg Config) int64 {
	doc := readJSONFile(solanaCatchupStatePath(cfg))
	if doc == nil {
		return 0
	}
	switch v := doc["max_behind"].(type) {
	case float64:
		return int64(v)
	case int64:
		return v
	case int:
		return int64(v)
	default:
		return 0
	}
}

func saveSolanaCatchupMaxBehind(cfg Config, maxBehind int64) {
	if maxBehind <= 0 || strings.TrimSpace(cfg.StateFile) == "" {
		return
	}
	_ = ensureDir(filepath.Dir(cfg.StateFile))
	_ = writeJSONFile(solanaCatchupStatePath(cfg), map[string]any{
		"max_behind": maxBehind,
		"updated_at": time.Now().UTC().Format(time.RFC3339),
	})
}

func clearSolanaCatchupMaxBehind(cfg Config) {
	if strings.TrimSpace(cfg.StateFile) == "" {
		return
	}
	_ = os.Remove(solanaCatchupStatePath(cfg))
}

// solanaCatchupSlots — local me + cluster tip (+ behind). Prefer explicit me/cluster
// from health/log; else local slot + getHealth "behind by N".
func solanaCatchupSlots(cfg Config, rpc solanaRPCResult) (me, tip, behind int64, ok bool) {
	if m, c, ok := parseSolanaMeCluster(rpc.Behind); ok && m > 0 && c >= m {
		return m, c, c - m, true
	}
	if m, c, ok := solanaMeClusterFromLogs(cfg); ok && m > 0 && c >= m {
		return m, c, c - m, true
	}
	if b, ok := parseSolanaBehindSlots(rpc.Behind); ok && rpc.Slot > 0 {
		return rpc.Slot, rpc.Slot + b, b, true
	}
	return 0, 0, 0, false
}

func parseSolanaBehindSlots(msg string) (int64, bool) {
	msg = strings.TrimSpace(msg)
	if msg == "" {
		return 0, false
	}
	// "Node is behind by 2851 slots"
	low := strings.ToLower(msg)
	const marker = "behind by "
	idx := strings.Index(low, marker)
	if idx < 0 {
		return 0, false
	}
	rest := strings.TrimSpace(msg[idx+len(marker):])
	var n int64
	if _, err := fmt.Sscanf(rest, "%d", &n); err != nil {
		return 0, false
	}
	if n < 0 {
		return 0, false
	}

	return n, true
}

// parseSolanaMeCluster — "me=438621923, latest cluster=438624907"
func parseSolanaMeCluster(msg string) (me, cluster int64, ok bool) {
	msg = strings.TrimSpace(msg)
	if msg == "" {
		return 0, 0, false
	}
	low := strings.ToLower(msg)
	mi := strings.Index(low, "me=")
	ci := strings.Index(low, "cluster=")
	if mi < 0 || ci < 0 {
		return 0, 0, false
	}
	var m, c int64
	if _, err := fmt.Sscanf(msg[mi:], "me=%d", &m); err != nil {
		// case-insensitive scan via lower slice offsets on original
		if _, err2 := fmt.Sscanf(low[mi:], "me=%d", &m); err2 != nil {
			return 0, 0, false
		}
	}
	if _, err := fmt.Sscanf(msg[ci:], "cluster=%d", &c); err != nil {
		if _, err2 := fmt.Sscanf(low[ci:], "cluster=%d", &c); err2 != nil {
			return 0, 0, false
		}
	}
	if m <= 0 || c < m {
		return 0, 0, false
	}
	return m, c, true
}

func solanaMeClusterFromLogs(cfg Config) (me, cluster int64, ok bool) {
	path := solanaValidatorLogPath(cfg)
	raw := solanaRawLogTail(path, 128*1024)
	for i := len(raw) - 1; i >= 0; i-- {
		if m, c, ok := parseSolanaMeCluster(raw[i]); ok {
			return m, c, true
		}
	}
	return 0, 0, false
}

func solanaDownloadPctFromDetail(detail string) float64 {
	m := solanaDownloadRe.FindStringSubmatch(detail)
	if len(m) < 3 {
		// also match "snapshot download 12.3%"
		idx := strings.Index(strings.ToLower(detail), "snapshot download ")
		if idx < 0 {
			return 0
		}
		rest := detail[idx+len("snapshot download "):]
		var pct float64
		if _, err := fmt.Sscanf(rest, "%f%%", &pct); err == nil && pct > 0 {
			if pct > 100 {
				pct = 100
			}

			return pct
		}

		return 0
	}
	pct, _ := strconv.ParseFloat(m[2], 64)
	if pct > 100 {
		pct = 100
	}

	return pct
}

func solanaDownloadPctFromLogs(cfg Config) float64 {
	path := solanaValidatorLogPath(cfg)
	raw := solanaRawLogTail(path, 256*1024)
	for i := len(raw) - 1; i >= 0; i-- {
		if p := solanaDownloadPctFromDetail(raw[i]); p > 0 {
			return p
		}
	}

	return 0
}

// Cached du — Solana ledger+accounts can be multi‑TiB; full `du -sb` is too slow
// every collect tick. Refresh at most every 2 minutes; serve last good value.
var (
	solanaDiskSizeMu    sync.Mutex
	solanaDiskSizeCache = map[string]solanaDiskSizeEntry{}
)

type solanaDiskSizeEntry struct {
	bytes int64
	at    time.Time
}

// solanaLedgerSizeBytes — ledger+accounts footprint for Sync "size on disk".
func solanaLedgerSizeBytes(cfg Config) int64 {
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		return 0
	}
	solanaDiskSizeMu.Lock()
	ent, ok := solanaDiskSizeCache[data]
	fresh := ok && time.Since(ent.at) < 2*time.Minute && ent.bytes > 0
	cached := ent.bytes
	solanaDiskSizeMu.Unlock()
	if fresh {
		return cached
	}

	// Prefer ledger+accounts; fall back to whole datadir.
	var total int64
	for _, p := range []string{
		data + "/ledger",
		data + "/accounts",
		data,
	} {
		n := duBytes(p)
		if n <= 0 {
			continue
		}
		if p == data {
			if total > 0 {
				break
			}
			total = n
			break
		}
		total += n
	}
	if total <= 0 {
		return cached // last good, if any
	}
	solanaDiskSizeMu.Lock()
	solanaDiskSizeCache[data] = solanaDiskSizeEntry{bytes: total, at: time.Now()}
	solanaDiskSizeMu.Unlock()

	return total
}

func duBytes(path string) int64 {
	if path == "" {
		return 0
	}
	// 20s: cold du on large Agave ledger; warm runs are much faster (page cache).
	out, err := runCmd(20*time.Second, "du", "-sb", path)
	if err != nil {
		return 0
	}
	fields := strings.Fields(strings.TrimSpace(out))
	if len(fields) < 1 {
		return 0
	}
	n, err := strconv.ParseInt(fields[0], 10, 64)
	if err != nil || n < 0 {
		return 0
	}

	return n
}
