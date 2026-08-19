package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

// system-agent — supervisor/checker. Writes agent-state JSON for api-agent.
// Lifecycle pipeline may auto-start snapshot → java-tron after prior steps complete
// (see pipeline.go). Manual POST /v1/snapshot/start and /api/v1/nodes/start still work.

func main() {
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "-version", "--version", "version":
			fmt.Println(agentVersion())
			return
		}
	}
	cfg := loadConfig()
	ensureDir(filepath.Dir(cfg.StateFile))
	ensureDir(filepath.Dir(cfg.RegistryFile))
	ensureDir(filepath.Dir(cfg.SnapshotState))
	ensureDir(filepath.Dir(cfg.SnapshotMarker))
	ensureDir(filepath.Dir(cfg.MaintenanceFile))

	snap := newSnapshotController(cfg)
	pipe := newLifecyclePipeline(cfg, snap)
	ctrl := newControlState(cfg)
	tkUp := newToolkitUpdateController(cfg)
	nodeRestart := newNodeRestartController(cfg, ctrl)
	clientUp := newClientUpdateController(cfg, ctrl, nodeRestart)
	nodeConfig := newNodeConfigController(cfg, ctrl, nodeRestart)
	notify := newNotifier(cfg)
	hist := newMetricsHistory()
	nodeNet := newNodeNetTracker()
	if !cfg.HostTip {
		ensureLocalNodeIPAccounting(cfg.NodeService)
	}

	if cfg.HostTip {
		log.Printf("rpcnode-system-agent host_tip=true state=%s interval=%s internal=%s (no single-network lifecycle)",
			cfg.StateFile, cfg.Interval, cfg.InternalListen)
	} else {
		log.Printf("rpcnode-system-agent env=%s network=%s state=%s interval=%s internal=%s",
			cfg.Env, cfg.Network, cfg.StateFile, cfg.Interval, cfg.InternalListen)
	}

	// Docker-first: always regenerate host preflight on container start.
	// Never serve a foreign-OS snapshot (e.g. Mac JSON on Linux); fail-open as unavailable.
	ensurePreflightFresh(cfg)

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	if cfg.InternalListen != "" {
		go serveInternal(cfg, snap, ctrl, tkUp, clientUp, nodeRestart, nodeConfig, notify)
	}

	// Periodic remote version check + auto-update window (UTC HH:MM).
	go func() {
		t := time.NewTicker(60 * time.Second)
		defer t.Stop()
		tkUp.Check()
		if !cfg.HostTip {
			clientUp.Check()
		}
		n := 0
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				tkUp.TickAuto()
				n++
				// Client channel ~ every 5 minutes.
				if !cfg.HostTip && n%5 == 0 {
					clientUp.Check()
				}
			}
		}
	}()

	// Push host metrics to panel cache (no panel fan-out on UI refresh).
	go startMetricsHeartbeat(ctx.Done(), cfg, func() map[string]any {
		hm := hist.Collect()
		if !cfg.HostTip {
			nodeNet.Sample(cfg.NodeService)
		}
		disk := diskRoot()
		osName, arch, _ := liveUname()
		out := map[string]any{
			"cpu_pct":       hm.CPUPct,
			"cpu_busy":      hm.CPUBusy,
			"load_pct":      hm.LoadPct,
			"ncpu":          hm.NCPU,
			"mem_pct":       hm.MemPct,
			"mem_used_mb":   hm.MemUsed,
			"mem_total_mb":  hm.MemTotal,
			"load_1":        hm.Load1,
			"net_rx_mbps":   hm.NetRxMbps,
			"net_tx_mbps":   hm.NetTxMbps,
			"net_rx_bps":    hm.NetRxBps,
			"net_tx_bps":    hm.NetTxBps,
			"disk":          disk,
			"disk_used_pct": disk["used_pct"],
			"disk_used_gb":  disk["used_gb"],
			"disk_total_gb": disk["total_gb"],
			"os":            osName,
			"arch":          arch,
		}
		mergeNodeNetIntoCurrent(out, nodeNet.Snapshot())
		return out
	})

	var collectMu sync.Mutex
	collectFn := func() map[string]any {
		hist.Push(hist.Collect())
		// Mid-download ENOSPC floor — stop wget before the rootfs fills.
		snap.GuardDiskDuringDownload()
		st := attachLogPaths(cfg, collect(cfg))
		actions := snap.Actions()
		if snapMap, ok := st["snapshot"].(map[string]any); ok {
			enabled := truthy(snapMap["enabled"])
			canStart, _ := actions["can_start"].(bool)
			canStop, _ := actions["can_stop"].(bool)
			if !enabled {
				canStart = false
				canStop = false
			}
			snapMap["can_start"] = canStart
			snapMap["can_stop"] = canStop
			// Pipeline may auto-start; UI can still trigger manually.
			snapMap["manual"] = false
			st["snapshot"] = snapMap
			actions["can_start"] = canStart
			actions["can_stop"] = canStop
			actions["enabled"] = enabled
		}
		st["actions"] = map[string]any{"snapshot": actions}

		// Agent-driven lifecycle: advance auto steps (snapshot → java-tron).
		// Host tip is control-plane only — never run phantom TRON snapshot/start.
		if !cfg.HostTip {
			pipe.Tick(st)
		}

		if m := ctrl.Maintenance(); truthy(m["enabled"]) {
			st["maintenance"] = m
			st["pause"] = map[string]any{
				"active": true, "title": "UPDATE PAUSE",
				"message": m["reason"], "phase": m["phase"],
			}
			st["health"] = "maintenance"
			st["degraded"] = true
		}

		if !cfg.HostTip {
			nodeNet.Sample(cfg.NodeService)
		}
		hm := hist.Snapshot()
		hostCur, _ := hm["current"].(map[string]any)
		if hostCur == nil {
			hostCur = map[string]any{}
		}
		for k, v := range hostNetForStatus() {
			hostCur[k] = v
		}
		if nn := nodeNet.Snapshot(); nn != nil {
			mergeNodeNetIntoCurrent(hostCur, nn)
			if cur, _ := hm["current"].(map[string]any); cur != nil {
				mergeNodeNetIntoCurrent(cur, nn)
			}
			if histMap, _ := hm["history"].(map[string]any); histMap != nil {
				mergeNodeHistoryInto(histMap, nn)
			}
			st["node_net"] = nn
		}
		st["host"] = hostCur
		st["host_metrics"] = hm
		maint, _ := st["maintenance"].(map[string]any)
		if maint == nil {
			maint = ctrl.Maintenance()
			st["maintenance"] = maint
		}
		st["controls"] = consoleControls(cfg, snap, maint)
		st["toolkit_update"] = tkUp.Snapshot()
		if !cfg.HostTip {
			applyClientUpdateToStatus(st, clientUp.Snapshot())
			applyNodeRestartToStatus(st, nodeRestart.Snapshot())
		}
		// Live state-dir preflight only (foreign-OS / placeholder never served).
		if pf := loadPreflightFile(cfg); len(pf) > 0 {
			st["preflight"] = pf
		} else if ua := readJSONFile(preflightLivePath(cfg)); len(ua) > 0 {
			if src, _ := ua["source"].(string); src == "unavailable" {
				st["preflight"] = ua
			}
		}
		notify.ObserveState(st)
		st["notifications"] = map[string]any{
			"webhooks": len(notify.Webhooks()),
			"events":   len(notify.Recent(eventRingSize)),
		}
		return st
	}

	writeCollect := func() error {
		collectMu.Lock()
		defer collectMu.Unlock()
		return writeState(cfg, collectFn())
	}
	ctrl.SetSyncRefresh(writeCollect)

	if err := writeCollect(); err != nil {
		log.Printf("initial state write: %v", err)
	}

	t := time.NewTicker(cfg.Interval)
	defer t.Stop()

	for {
		select {
		case <-ctx.Done():
			log.Printf("shutdown")
			collectMu.Lock()
			st := collectFn()
			st["agent"] = map[string]any{"role": "system", "status": "stopping"}
			_ = writeState(cfg, st)
			collectMu.Unlock()
			return
		case <-ctrl.refreshCh:
			// Async kick from other controls; /v1/refresh uses sync path.
			if err := writeCollect(); err != nil {
				log.Printf("state write error: %v", err)
			}
		case <-t.C:
			if err := writeCollect(); err != nil {
				log.Printf("state write error: %v", err)
			}
		}
	}
}

func serveInternal(cfg Config, snap *SnapshotController, ctrl *ControlState, tkUp *ToolkitUpdateController, clientUp *ClientUpdateController, nodeRestart *NodeRestartController, nodeConfig *NodeConfigController, notify *Notifier) {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		b, err := os.ReadFile(cfg.StateFile)
		age := "missing"
		ok := false
		if err == nil {
			var st map[string]any
			if json.Unmarshal(b, &st) == nil {
				if ua, _ := st["updated_at"].(string); ua != "" {
					if t, e := time.Parse(time.RFC3339, ua); e == nil {
						d := time.Since(t)
						age = d.Round(time.Second).String()
						ok = d < 30*time.Second
					}
				}
			}
		}
		code := http.StatusOK
		if !ok {
			code = http.StatusServiceUnavailable
		}
		body := map[string]any{
			"ok": ok, "role": "system-agent", "env": cfg.Env,
			"version": agentVersion(),
			"state":   cfg.StateFile, "age": age, "alive": true,
		}
		if cfg.HostTip {
			body["host_tip"] = true
			body["node_status"] = "host"
		} else {
			np := LookupNetworkProfile(cfg.Network, cfg.Env)
			body["supported_steps"] = np.SupportedLifecycleSteps()
			body["capabilities"] = np.LifecycleCapabilities()
			if net := strings.TrimSpace(cfg.Network); net != "" {
				body["network"] = net
			}
		}
		writeJSON(w, code, body)
	})
	mux.HandleFunc("/v1/snapshot/start", snap.handleStart)
	mux.HandleFunc("/v1/snapshot/stop", snap.handleStop)
	mux.HandleFunc("/v1/maintenance", ctrl.handleMaintenance(cfg))
	mux.HandleFunc("/v1/maintenance/enable", ctrl.handleMaintenance(cfg))
	mux.HandleFunc("/v1/maintenance/disable", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		if err := ctrl.SetMaintenance(cfg, false, ""); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		ctrl.RequestRefresh()
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "maintenance": ctrl.Maintenance()})
	})
	mux.HandleFunc("/v1/refresh", ctrl.handleRefresh)
	mux.HandleFunc("/v1/preflight", ctrl.handlePreflight(cfg))
	mux.HandleFunc("/v1/public-base", ctrl.handlePublicBase(cfg))
	mux.HandleFunc("/v1/host", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.Error(w, "GET only", http.StatusMethodNotAllowed)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "host": hostNetForStatus()})
	})
	mux.HandleFunc("/v1/toolkit/check", tkUp.handleCheck)
	mux.HandleFunc("/v1/toolkit/apply", tkUp.handleApply)
	mux.HandleFunc("/v1/toolkit/schedule", tkUp.handleSchedule)
	mux.HandleFunc("/v1/client", clientUp.handleGet)
	mux.HandleFunc("/v1/client/release", clientUp.handleRelease)
	mux.HandleFunc("/v1/client/check", clientUp.handleCheck)
	mux.HandleFunc("/v1/client/update", clientUp.handleApply)
	mux.HandleFunc("/v1/node/restart", nodeRestart.handleRestart)
	mux.HandleFunc("/v1/node/stop", nodeRestart.handleStop)
	mux.HandleFunc("/v1/node/config", nodeConfig.handleConfig)
	mux.HandleFunc("/v1/events", notify.handleEvents)
	mux.HandleFunc("/v1/webhooks", notify.handleWebhooks)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "system-agent internal — use panel :TRON_PANEL_PORT/status", http.StatusNotFound)
	})

	ln, err := net.Listen("tcp", cfg.InternalListen)
	if err != nil {
		log.Printf("internal listen failed: %v", err)
		return
	}
	log.Printf("internal API on %s (/healthz /v1/snapshot/* /v1/maintenance /v1/client/* /v1/public-base /v1/host /v1/toolkit/* /v1/events /v1/webhooks /v1/refresh /v1/preflight)", cfg.InternalListen)
	srv := &http.Server{Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	if err := srv.Serve(ln); err != nil && err != http.ErrServerClosed {
		log.Printf("internal server: %v", err)
	}
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	b, _ := json.MarshalIndent(v, "", "  ")
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_, _ = w.Write(b)
}

func writeState(cfg Config, st map[string]any) error {
	b, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	b = append(b, '\n')
	tmp := cfg.StateFile + ".tmp"
	if err := os.WriteFile(tmp, b, 0644); err != nil {
		return err
	}
	if err := os.Rename(tmp, cfg.StateFile); err != nil {
		return err
	}
	_ = writeInstanceSidecar(cfg, st)
	return nil
}

func writeInstanceSidecar(cfg Config, st map[string]any) error {
	if cfg.HostTip {
		// Do not register a fake tron-mainnet INSTANCE from the host tip.
		return nil
	}
	inst, _ := st["instance"].(map[string]any)
	if inst == nil {
		inst = map[string]any{}
	}
	net := cfg.Network
	if net == "" {
		net = DefaultNetwork
	}
	inst["id"] = fmt.Sprintf("%s-%s", net, cfg.Env)
	inst["network"] = net
	inst["env"] = cfg.Env
	inst["managed_by"] = "RpcNode toolkit"
	inst["system_agent"] = true
	inst["api_agent"] = true
	inst["state_file"] = cfg.StateFile
	inst["health"] = st["health"]
	inst["updated_at"] = st["updated_at"]
	rpcBase, panelBase := effectivePublicBases(cfg)
	if rpcBase != "" {
		inst["public_base_url"] = rpcBase
		inst["panel_base_url"] = panelBase
		inst["status_url"] = strings.TrimRight(panelBase, "/") + "/status"
		inst["public_port"] = cfg.PublicRPCPort()
		inst["gateway_port"] = cfg.PublicRPCPort()
		inst["panel_port"] = cfg.AgentAPIPort()
	}
	b, err := json.MarshalIndent(inst, "", "  ")
	if err != nil {
		return err
	}
	b = append(b, '\n')
	for _, path := range []string{cfg.InstanceFile, cfg.RegistryFile} {
		if path == "" {
			continue
		}
		_ = ensureDir(filepath.Dir(path))
		tmp := path + ".tmp"
		if err := os.WriteFile(tmp, b, 0644); err == nil {
			_ = os.Rename(tmp, path)
		}
	}
	return nil
}

func ensureDir(dir string) error {
	if dir == "" || dir == "." {
		return nil
	}
	return os.MkdirAll(dir, 0755)
}
