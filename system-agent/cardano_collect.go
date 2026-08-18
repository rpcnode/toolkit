package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// collectCardano — cardano-node + Ogmios lifecycle (sync via /health).
func collectCardano(cfg Config) map[string]any {
	network := cfg.Network
	if network == "" {
		network = "cardano"
	}
	prof := LookupNetworkProfile(network, cfg.Env)

	nodeUnit := cfg.NodeService
	ogmiosUnit := fmt.Sprintf("cardano-ogmios-%s.service", cfg.Env)
	nodeState := systemctlActive(nodeUnit)
	procOK, _ := cardanoProcessRunning(cfg)
	startErr, startBad := cardanoStartFailureDetail(cfg, procOK)
	nodeActive := procOK && !startBad
	if !nodeActive && (nodeState == "active" || nodeState == "activating") {
		nodeActive = !startBad
	}

	health := probeOgmiosHealth(cfg)
	rpcOK := health.OK
	syncing := rpcOK && !health.Synced

	nodeSvcEffective := nodeState
	switch {
	case startBad:
		nodeSvcEffective = "failed"
	case nodeActive && rpcOK && !syncing:
		nodeSvcEffective = "active"
	case nodeActive:
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

	diskOK, freeGiB, needGiB, diskDetail := cardanoDiskGateOK(cfg, prof)
	if rpcOK {
		maybeAppendCardanoSyncLog(cfg, health)
	}
	logTail := cardanoSyncLogTail(cfg, 80)

	wantsSnap := prof.HasExtra(StepSnapshot) || prof.SnapshotPolicy != SnapshotNever
	snapEnabled := wantsSnap
	if snapEnabled && strings.TrimSpace(cfg.SnapshotURL) == "" {
		cfg.SnapshotURL = prof.DefaultSnapshotURL
	}
	snapMarker := fileExists(cfg.SnapshotMarker)
	snapState := readJSONFile(cfg.SnapshotState)
	snapPhase, _ := snapState["phase"].(string)
	snapDetail, _ := snapState["detail"].(string)
	snapErr, _ := snapState["error"].(string)
	snapUnitState := systemctlActive(cfg.SnapshotService)
	snapUnitActive := snapUnitState == "active" || snapUnitState == "activating"
	snapUnitFailed := systemctlFailed(cfg.SnapshotService)
	mithrilRunning := cardanoMithrilSnapshotRunning(cfg)
	snapPct, snapPctOK := cardanoMithrilSnapshotPct(cfg)
	if snapEnabled && !snapMarker && (snapUnitActive || mithrilRunning) {
		snapPhase = "download"
		if snapDetail == "" {
			snapDetail = "Mithril · cardano-db download latest"
		}
	}
	snapBusy := snapEnabled && !snapMarker && !strings.EqualFold(snapPhase, "error") &&
		(snapUnitActive || mithrilRunning || strings.EqualFold(snapPhase, "download") || snapPctOK ||
			(snapEnabled && !snapMarker && !snapUnitFailed))
	snapFailed := snapEnabled && !snapMarker && !snapBusy &&
		(snapUnitFailed || strings.EqualFold(snapPhase, "error") || snapErr != "")
	if snapMarker {
		snapPct = 100
		snapPctOK = true
	} else if snapBusy && !snapPctOK {
		snapPct = 0
		snapPctOK = true
	}

	verifyPct := health.SyncPct * 100
	if snapBusy && snapPctOK {
		verifyPct = snapPct
	}
	if verifyPct < 0 {
		verifyPct = 0
	}
	if verifyPct > 100 {
		verifyPct = 100
	}
	// Prefer block height for lifecycle "height"; fall back to tip slot.
	tipHeight := health.TipHeight
	if tipHeight <= 0 {
		tipHeight = health.TipSlot
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
		SnapDetail:     snapDetail,
		SnapErr:        snapErr,
		Pct:            map[bool]string{true: fmt.Sprintf("%.1f", snapPct), false: ""}[snapBusy && snapPctOK],
		NodeActive:     nodeActive && !snapBusy,
		StartError:     startErr,
		RPCOK:          rpcOK,
		IBD:            syncing && (!snapEnabled || snapMarker),
		VerifyPct:      map[bool]float64{true: snapPct / 100, false: health.SyncPct}[snapBusy],
		Progress:       prog,
	}
	if rpcOK {
		lcIn.Height = tipHeight
		if health.Peers >= 0 {
			lcIn.Peers = health.Peers
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

	syncDetail := health.Error
	if snapBusy {
		syncDetail = "Mithril snapshot download"
		if snapDetail != "" {
			syncDetail = snapDetail
		}
		if snapPctOK {
			syncDetail = fmt.Sprintf("%s · %.1f%%", syncDetail, snapPct)
		}
	} else if rpcOK {
		switch {
		case syncing:
			syncDetail = fmt.Sprintf(
				"syncing · tip slot %d · height %d · %.1f%%",
				health.TipSlot, tipHeight, verifyPct,
			)
			if health.Epoch >= 0 {
				syncDetail = fmt.Sprintf("%s · epoch %d", syncDetail, health.Epoch)
			}
			if health.Peers >= 0 {
				syncDetail = fmt.Sprintf("%s · peers %d", syncDetail, health.Peers)
			}
		default:
			syncDetail = fmt.Sprintf(
				"synced · tip slot %d · height %d · networkSynchronization=%s",
				health.TipSlot, tipHeight, health.NetworkSync,
			)
		}
	}

	setupSteps := []map[string]any{
		{"id": "registry", "title": "Instance registered", "done": instRegistered,
			"detail": "INSTANCE.json + /etc/rpcnode/instances.d"},
		{"id": "disk", "title": "Disk floor for Cardano sync", "done": diskOK,
			"detail": diskDetail, "active": !diskOK && apiUp},
		{"id": "snapshot", "title": "Mithril snapshot", "done": !snapEnabled || snapMarker,
			"detail": firstNonEmptyStr(snapDetail, "mithril-client cardano-db download latest"),
			"active": snapBusy, "pct": map[bool]any{true: snapPct, false: nil}[snapBusy && snapPctOK]},
		{"id": "node", "title": "cardano-node running", "done": nodeActive && !snapBusy,
			"detail": "process/systemd", "active": apiUp && !nodeActive},
		{"id": "rpc", "title": "Ogmios responding", "done": rpcOK,
			"detail": "/health", "active": nodeActive && !rpcOK},
		{"id": "ibd", "title": "Ledger sync complete", "done": rpcOK && !syncing,
			"detail": map[bool]string{
				true:  syncDetail,
				false: fmt.Sprintf("networkSynchronization=%s", health.NetworkSync),
			}[rpcOK && syncing],
			"active": rpcOK && syncing,
			"pct":    map[bool]any{true: verifyPct, false: nil}[rpcOK && syncing]},
		{"id": "api", "title": "API agent up", "done": apiUp,
			"detail": fmt.Sprintf(":%d /healthz", apiProbePort)},
		{"id": "ogmios", "title": "Ogmios unit", "done": systemctlActive(ogmiosUnit) == "active",
			"detail": ogmiosUnit},
	}

	clientVer := cardanoClientVersion(cfg, health)

	return map[string]any{
		"ok": true, "ts": time.Now().UTC().Format(time.RFC3339),
		"network": network, "env": cfg.Env,
		"ui_phase": uiPhase, "node_status": nodeStatus,
		"lifecycle":   lifecycle,
		"setup_steps": setupSteps,
		"disk_gate": map[string]any{
			"ok": diskOK, "free_gib": freeGiB, "need_gib": needGiB, "detail": diskDetail,
		},
		"snapshot": map[string]any{
			"enabled": snapEnabled, "ready": snapMarker, "busy": snapBusy, "failed": snapFailed,
			"pct": snapPct, "phase": snapPhase, "detail": snapDetail, "error": snapErr,
			"url": cfg.SnapshotURL, "wget_running": mithrilRunning || snapUnitActive,
			"service": cfg.SnapshotService,
		},
		"sync": map[string]any{
			"ok": rpcOK && !syncing && !snapBusy, "syncing": syncing || snapBusy, "ibd": syncing && !snapBusy,
			"block": tipHeight, "blocks": tipHeight, "slot": health.TipSlot,
			"height": tipHeight, "epoch": health.Epoch, "slot_in_epoch": health.SlotInEpoch,
			"peers": health.Peers, "density": health.Density,
			"network_synchronization": health.NetworkSync,
			"verificationprogress":    health.SyncPct,
			"verification_pct":        verifyPct,
			"verify_pct":              health.SyncPct,
			"detail":                  syncDetail,
			"network":                 network,
			"log_tail":                logTail,
		},
		"rpc": map[string]any{
			"ok": rpcOK, "height": tipHeight, "slot": health.TipSlot,
			"peers": health.Peers, "verificationprogress": health.SyncPct,
			"verification_pct": verifyPct, "client_version": clientVer,
		},
		"services": map[string]any{
			"node": nodeSvcEffective, "api": apiSvc, "ogmios": systemctlActive(ogmiosUnit),
			"snapshot": systemctlActive(cfg.SnapshotService),
		},
		"checks": map[string]any{
			"node_process_up": procOK, "cardano_process": procOK,
			"snapshot_marker": snapMarker, "mithril_running": mithrilRunning,
		},
		"ports": map[string]any{
			"public": publicPort, "agent": agentPort, "node_http": cfg.UpstreamPort,
		},
		"start_error": startErr,
		"logs": map[string]any{
			"title":  "Logs",
			"source": "cardano-sync",
			"lines":  logTail,
		},
		"version":        agentVersion(),
		"client_version": clientVer,
	}
}

type ogmiosHealth struct {
	OK          bool
	Synced      bool
	NetworkSync string
	SyncPct     float64 // 0..1 from networkSynchronization
	TipSlot     int64
	TipHeight   int64
	Epoch       int64
	SlotInEpoch int64
	Density     float64
	Peers       int
	Version     string
	Error       string
}

func probeOgmiosHealth(cfg Config) ogmiosHealth {
	url := fmt.Sprintf("http://%s:%d/health", cfg.UpstreamHost, cfg.UpstreamPort)
	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		return ogmiosHealth{Error: err.Error(), Peers: -1, Epoch: -1}
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return ogmiosHealth{Error: err.Error(), Peers: -1, Epoch: -1}
	}
	var doc map[string]any
	if err := json.Unmarshal(b, &doc); err != nil {
		return ogmiosHealth{Error: "health decode: " + err.Error(), Peers: -1, Epoch: -1}
	}
	h := ogmiosHealth{OK: true, Peers: -1, Epoch: -1, SlotInEpoch: -1, Density: -1}
	switch v := doc["networkSynchronization"].(type) {
	case string:
		h.NetworkSync = v
		if strings.EqualFold(v, "synchronized") || v == "1" || v == "1.0" {
			h.Synced = true
			h.SyncPct = 1
		} else if p, err := strconv.ParseFloat(v, 64); err == nil {
			h.SyncPct = clamp01(p)
			h.Synced = h.SyncPct >= 0.999
		}
	case float64:
		h.NetworkSync = fmt.Sprintf("%.5f", v)
		h.SyncPct = clamp01(v)
		h.Synced = h.SyncPct >= 0.999
	}
	if tip, ok := doc["lastKnownTip"].(map[string]any); ok {
		if s, ok := tip["slot"].(float64); ok {
			h.TipSlot = int64(s)
		}
		if ht, ok := tip["height"].(float64); ok {
			h.TipHeight = int64(ht)
		}
	}
	if v, ok := doc["currentEpoch"].(float64); ok {
		h.Epoch = int64(v)
	}
	if v, ok := doc["slotInEpoch"].(float64); ok {
		h.SlotInEpoch = int64(v)
	}
	// Optional older Ogmios fields.
	if v, ok := doc["density"].(float64); ok {
		h.Density = v
	}
	if v, ok := doc["connectionStatus"].(string); ok && strings.EqualFold(v, "disconnected") {
		h.Synced = false
	}
	for _, k := range []string{"version", "ogmiosVersion", "serverVersion"} {
		if s, ok := doc[k].(string); ok {
			if t := strings.TrimSpace(s); t != "" {
				h.Version = t
				break
			}
		}
	}
	// Honest fallback: if Ogmios omitted networkSynchronization but tip moves, keep SyncPct=0.
	if h.SyncPct <= 0 && h.Synced {
		h.SyncPct = 1
	}

	return h
}

func cardanoClientVersion(cfg Config, health ogmiosHealth) string {
	if v := strings.TrimSpace(health.Version); v != "" {
		return v
	}
	bin := filepath.Join(cfg.OptDir, "bin", "cardano-node")
	if !fileExists(bin) {
		bin = "cardano-node"
	}
	out, err := runCmd(3*time.Second, bin, "--version")
	if err != nil {
		return ""
	}
	line := strings.TrimSpace(strings.Split(out, "\n")[0])
	// "cardano-node 10.1.4 - ..." → keep first token after name or whole line.
	fields := strings.Fields(line)
	if len(fields) >= 2 {
		return fields[0] + " " + fields[1]
	}

	return line
}

func clamp01(v float64) float64 {
	if v < 0 {
		return 0
	}
	if v > 1 {
		// Some payloads may already be percent (0..100).
		if v <= 100 {
			return v / 100
		}
		return 1
	}

	return v
}

func cardanoProcessRunning(cfg Config) (bool, string) {
	unit := strings.TrimSpace(cfg.NodeService)
	if unit == "" {
		unit = fmt.Sprintf("cardano-%s.service", cfg.Env)
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	out, _ := runCmd(3*time.Second, "systemctl", "show", unit,
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
		if cmd != "" && strings.Contains(cmd, "cardano-node") {
			return true, cmd
		}
	}
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		data = LookupNetworkProfile(cfg.Network, cfg.Env).DataPath
	}
	ps, err := runCmd(2*time.Second, "bash", "-lc",
		fmt.Sprintf(`ps -eo pid=,args= | grep -E '[c]ardano-node' | grep -F %q | head -1`, data))
	if err != nil || strings.TrimSpace(ps) == "" {
		return false, ""
	}

	return true, strings.TrimSpace(ps)
}

func cardanoStartFailureDetail(cfg Config, procOK bool) (string, bool) {
	if procOK {
		return "", false
	}
	if systemctlFailed(cfg.NodeService) || systemctlActive(cfg.NodeService) == "failed" {
		snip := journalUnitSnippet(cfg.NodeService, 16)
		if snip != "" {
			return snip, true
		}

		return "cardano-node unit failed", true
	}

	return "", false
}

func cardanoDiskGateOK(cfg Config, prof NetworkProfile) (ok bool, freeGiB, needGiB float64, detail string) {
	needGiB = prof.DiskHintGiB
	if needGiB <= 0 {
		needGiB = 150
	}
	floor := needGiB * 0.1
	if floor < 30 {
		floor = 30
	}
	freeGiB = freeDiskGiB(cfg.DataDir)
	ok = freeGiB >= floor
	detail = fmt.Sprintf("%.0f GiB free (floor %.0f GiB for cardano sync; hint %.0f GiB)", freeGiB, floor, needGiB)

	return ok, freeGiB, needGiB, detail
}