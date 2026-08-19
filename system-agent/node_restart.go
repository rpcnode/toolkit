package main

import (
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"
)

// NodeRestartController — soft-stop + start chain fullnode unit(s) with Go-proxy sleep.
// Same stop as client update: CLI/RPC then systemctl stop.
type NodeRestartController struct {
	cfg  Config
	ctrl *ControlState
	mu   sync.Mutex
	busy bool
	st   map[string]any
}

func newNodeRestartController(cfg Config, ctrl *ControlState) *NodeRestartController {
	return &NodeRestartController{
		cfg:  cfg,
		ctrl: ctrl,
		st: map[string]any{
			"phase": "idle", "detail": "", "pct": 0,
		},
	}
}

func (n *NodeRestartController) Snapshot() map[string]any {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.snapshotLocked()
}

func (n *NodeRestartController) snapshotLocked() map[string]any {
	if !n.busy {
		n.hydrateFromFileLocked()
	}
	out := map[string]any{}
	for k, v := range n.st {
		out[k] = v
	}
	out["busy"] = n.busy
	out["node_run"] = nodeRunSnapshot(n.cfg)
	return out
}

func (n *NodeRestartController) hydrateFromFileLocked() {
	switch loadNodeRun(n.cfg).Status {
	case "stopped":
		n.st["action"] = "stop"
		n.st["phase"] = "stopped"
		if strings.TrimSpace(fmt.Sprint(n.st["detail"])) == "" || fmt.Sprint(n.st["detail"]) == "<nil>" {
			n.st["detail"] = "fullnode stopped — Start to start"
		}
	case "running":
		ph := strings.ToLower(strings.TrimSpace(fmt.Sprint(n.st["phase"])))
		if ph == "stopped" || ph == "stopping" {
			n.st["phase"] = "idle"
			n.st["action"] = "start"
		}
	}
}

func (n *NodeRestartController) set(phase, detail string, pct float64, errMsg string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.st["phase"] = phase
	n.st["detail"] = detail
	n.st["pct"] = pct
	n.st["updated_at"] = time.Now().UTC().Format(time.RFC3339)
	if errMsg != "" {
		n.st["last_error"] = errMsg
	} else if phase == "idle" || phase == "restarting" || phase == "starting" || phase == "stopping" || phase == "stopped" {
		n.st["last_error"] = ""
	}
	action := "node_restart"
	switch strings.ToLower(fmt.Sprint(n.st["action"])) {
	case "stop":
		action = "node_stop"
	case "start":
		action = "node_start"
	}
	level := "INFO"
	if phase == "error" || strings.TrimSpace(errMsg) != "" {
		level = "ERROR"
	}
	msg := detail
	if strings.TrimSpace(errMsg) != "" {
		msg = detail + " — " + errMsg
	}
	hostLogf(level, "system-agent", action,
		"%s/%s phase=%s pct=%.0f %s", n.cfg.Network, n.cfg.Env, phase, pct, msg)
}

// markStopped — leave units down (after Stop or client update). Start starts them.
func (n *NodeRestartController) markStopped(detail string) {
	if n == nil {
		return
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.busy {
		return
	}
	n.st["action"] = "stop"
	n.st["phase"] = "stopped"
	n.st["detail"] = strings.TrimSpace(detail)
	if n.st["detail"] == "" {
		n.st["detail"] = "fullnode stopped — Start to start"
	}
	n.st["pct"] = 100
	n.st["last_error"] = ""
	n.st["updated_at"] = time.Now().UTC().Format(time.RFC3339)
	_ = saveNodeRun(n.cfg, "stopped", "mark")
}

func (n *NodeRestartController) nodeUnits() []string {
	return cfgNodeUnits(n.cfg)
}

func (n *NodeRestartController) stopBudget() time.Duration {
	return cfgStopBudget(n.cfg.Network)
}

func (n *NodeRestartController) Restart() (map[string]any, error) {
	n.mu.Lock()
	if n.busy {
		st := n.snapshotLocked()
		n.mu.Unlock()
		return st, fmt.Errorf("node restart already running")
	}
	if n.cfg.HostTip {
		n.mu.Unlock()
		return nil, fmt.Errorf("host tip has no chain node — use per-node agent")
	}
	units := n.nodeUnits()
	if len(units) == 0 {
		n.mu.Unlock()
		return nil, fmt.Errorf("node unit unknown")
	}
	n.busy = true
	n.st["action"] = "restart"
	n.st["phase"] = "restarting"
	n.st["detail"] = "starting soft restart"
	n.st["pct"] = 5
	n.st["last_error"] = ""
	n.st["unit"] = units[0]
	n.st["units"] = strings.Join(units, ",")
	st := n.snapshotLocked()
	n.mu.Unlock()
	hostLogf("INFO", "system-agent", "node_restart",
		"%s/%s accepted — soft restart units=%s", n.cfg.Network, n.cfg.Env, strings.Join(units, ","))

	go n.run(units)
	return st, nil
}

func (n *NodeRestartController) Stop() (map[string]any, error) {
	n.mu.Lock()
	if n.busy {
		st := n.snapshotLocked()
		n.mu.Unlock()
		return st, fmt.Errorf("node restart already running")
	}
	if n.cfg.HostTip {
		n.mu.Unlock()
		return nil, fmt.Errorf("host tip has no chain node — use per-node agent")
	}
	units := n.nodeUnits()
	if len(units) == 0 {
		n.mu.Unlock()
		return nil, fmt.Errorf("node unit unknown")
	}
	n.busy = true
	n.st["action"] = "stop"
	n.st["phase"] = "stopping"
	n.st["detail"] = "starting soft stop"
	n.st["pct"] = 5
	n.st["last_error"] = ""
	n.st["unit"] = units[0]
	n.st["units"] = strings.Join(units, ",")
	st := n.snapshotLocked()
	n.mu.Unlock()
	hostLogf("INFO", "system-agent", "node_stop",
		"%s/%s accepted — soft stop units=%s", n.cfg.Network, n.cfg.Env, strings.Join(units, ","))

	go n.runStop(units)
	return st, nil
}

func (n *NodeRestartController) run(units []string) {
	defer func() {
		n.mu.Lock()
		n.busy = false
		n.mu.Unlock()
		if n.ctrl != nil {
			n.ctrl.RequestRefresh()
		}
	}()

	label := strings.Join(units, ", ")

	// 1) Sleep Go proxy before bouncing the node.
	if n.ctrl != nil {
		_ = n.ctrl.SetMaintenanceEx(n.cfg, true, "node restart — RPC paused", "node_restart")
	}
	n.set("restarting", "RPC sleep (maintenance) — soft-stopping "+label, 15, "")

	// Stellar: patch HISTORY_RETENTION_WINDOW → never prune before recycle.
	if strings.EqualFold(strings.TrimSpace(n.cfg.Network), "stellar") {
		if changed, err := ensureStellarFullHistoryToml(n.cfg.EtcDir); err != nil {
			log.Printf("node_restart: stellar full-history toml: %v", err)
		} else if changed {
			n.set("restarting", "stellar-rpc.toml → full history retention; soft-stopping "+label, 20, "")
		}
	}

	// 2) Soft stop — CLI/RPC then systemctl stop (ExecStop / SIGTERM).
	n.set("stopping", "soft-stopping "+label, 35, "")
	if err := stopNodeUnits(n.cfg, n.stopBudget()); err != nil {
		log.Printf("node_restart: stop %s: %v — starting anyway", label, err)
	}

	n.set("starting", "starting "+label, 70, "")
	if err := startNodeUnits(n.cfg); err != nil {
		n.set("error", err.Error(), 40, err.Error())
		if n.ctrl != nil {
			_ = n.ctrl.SetMaintenanceEx(n.cfg, false, "", "")
		}
		log.Printf("node_restart failed: %s", err)
		return
	}

	time.Sleep(2 * time.Second)

	if n.ctrl != nil {
		_ = n.ctrl.SetMaintenanceEx(n.cfg, false, "", "")
	}
	if err := saveNodeRun(n.cfg, "running", "restart"); err != nil {
		log.Printf("node_restart: node-run.json: %v", err)
	}
	n.set("idle", "soft-restarted — node starting", 100, "")
	log.Printf("node_restart: %s/%s units=%s ok (soft stop→start)", n.cfg.Network, n.cfg.Env, label)
}

func (n *NodeRestartController) runStop(units []string) {
	defer func() {
		n.mu.Lock()
		n.busy = false
		n.mu.Unlock()
		if n.ctrl != nil {
			n.ctrl.RequestRefresh()
		}
	}()

	label := strings.Join(units, ", ")
	if n.ctrl != nil {
		_ = n.ctrl.SetMaintenanceEx(n.cfg, true, "node stopped — RPC paused", "node_stop")
	}
	n.set("stopping", "RPC sleep — soft-stopping "+label, 20, "")
	if err := stopNodeUnits(n.cfg, n.stopBudget()); err != nil {
		n.set("error", "fullnode did not stop: "+err.Error(), 25, err.Error())
		if n.ctrl != nil {
			_ = n.ctrl.SetMaintenanceEx(n.cfg, false, "", "")
		}
		log.Printf("node_stop failed: %s", err)
		return
	}
	if err := saveNodeRun(n.cfg, "stopped", "stop"); err != nil {
		n.set("error", "stopped but could not write node-run.json: "+err.Error(), 90, err.Error())
		return
	}
	n.set("stopped", "fullnode stopped — Start to start", 100, "")
	log.Printf("node_stop: %s/%s units=%s stopped (soft)", n.cfg.Network, n.cfg.Env, label)
}

func (n *NodeRestartController) Start() (map[string]any, error) {
	n.mu.Lock()
	if n.busy {
		st := n.snapshotLocked()
		n.mu.Unlock()
		return st, fmt.Errorf("node restart already running")
	}
	if n.cfg.HostTip {
		n.mu.Unlock()
		return nil, fmt.Errorf("host tip has no chain node — use per-node agent")
	}
	units := n.nodeUnits()
	if len(units) == 0 {
		n.mu.Unlock()
		return nil, fmt.Errorf("node unit unknown")
	}
	n.busy = true
	n.st["action"] = "start"
	n.st["phase"] = "starting"
	n.st["detail"] = "starting fullnode"
	n.st["pct"] = 10
	n.st["last_error"] = ""
	n.st["unit"] = units[0]
	n.st["units"] = strings.Join(units, ",")
	st := n.snapshotLocked()
	n.mu.Unlock()
	hostLogf("INFO", "system-agent", "node_start",
		"%s/%s accepted — start units=%s", n.cfg.Network, n.cfg.Env, strings.Join(units, ","))

	go n.runStart(units)
	return st, nil
}

func (n *NodeRestartController) runStart(units []string) {
	defer func() {
		n.mu.Lock()
		n.busy = false
		n.mu.Unlock()
		if n.ctrl != nil {
			n.ctrl.RequestRefresh()
		}
	}()

	label := strings.Join(units, ", ")
	if n.ctrl != nil {
		_ = n.ctrl.SetMaintenanceEx(n.cfg, true, "node start — RPC paused", "node_start")
	}
	if strings.EqualFold(strings.TrimSpace(n.cfg.Network), "stellar") {
		if changed, err := ensureStellarFullHistoryToml(n.cfg.EtcDir); err != nil {
			log.Printf("node_start: stellar full-history toml: %v", err)
		} else if changed {
			n.set("starting", "stellar-rpc.toml → full history retention; starting "+label, 25, "")
		}
	}
	n.set("starting", "starting "+label, 50, "")
	if err := startNodeUnits(n.cfg); err != nil {
		n.set("error", err.Error(), 40, err.Error())
		if n.ctrl != nil {
			_ = n.ctrl.SetMaintenanceEx(n.cfg, false, "", "")
		}
		log.Printf("node_start failed: %s", err)
		return
	}
	if err := saveNodeRun(n.cfg, "running", "start"); err != nil {
		n.set("error", "started but could not write node-run.json: "+err.Error(), 90, err.Error())
		return
	}
	time.Sleep(2 * time.Second)
	if n.ctrl != nil {
		_ = n.ctrl.SetMaintenanceEx(n.cfg, false, "", "")
	}
	n.set("idle", "started — node coming up", 100, "")
	log.Printf("node_start: %s/%s units=%s ok", n.cfg.Network, n.cfg.Env, label)
}

func (n *NodeRestartController) handleRestart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	st, err := n.Restart()
	if err != nil {
		writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "error": err.Error(), "node_restart": st})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "accepted": true, "node_restart": st})
}

func (n *NodeRestartController) handleStop(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	st, err := n.Stop()
	if err != nil {
		writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "error": err.Error(), "node_restart": st})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "accepted": true, "node_restart": st})
}

func (n *NodeRestartController) handleStart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	st, err := n.Start()
	if err != nil {
		writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "error": err.Error(), "node_restart": st})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "accepted": true, "node_restart": st})
}

func applyNodeRestartToStatus(st map[string]any, snap map[string]any) {
	if st == nil || snap == nil {
		return
	}
	st["node_restart"] = snap
	if nr, ok := snap["node_run"]; ok {
		st["node_run"] = nr
	}
	phase := strings.ToLower(strings.TrimSpace(fmt.Sprint(snap["phase"])))
	action := strings.ToLower(strings.TrimSpace(fmt.Sprint(snap["action"])))
	detail := strings.TrimSpace(fmt.Sprint(snap["detail"]))
	switch phase {
	case "stopped":
		st["ui_phase"] = "stopped"
		st["health"] = "stopped"
		st["degraded"] = true
		if lc, ok := st["lifecycle"].(map[string]any); ok && lc != nil {
			lc["phase"] = "stopped"
			lc["label"] = "Stopped"
			lc["detail"] = firstNonEmptyStr(detail, "Fullnode stopped — Start to start")
			lc["busy"] = false
			st["lifecycle"] = lc
		}
	case "restarting", "starting", "stopping":
		st["ui_phase"] = phase
		if phase == "restarting" || (phase == "stopping" && action != "stop") {
			st["ui_phase"] = "restarting"
		}
		st["health"] = "maintenance"
		st["degraded"] = true
		label := "Restarting node"
		lcPhase := "restarting"
		switch {
		case phase == "starting" && action == "start":
			label = "Starting node"
			lcPhase = "starting"
		case phase == "starting":
			label = "Starting after restart"
			lcPhase = "starting"
		case phase == "stopping" && action == "stop":
			label = "Soft-stopping node"
			lcPhase = "stopping"
		case phase == "stopping":
			label = "Soft-stopping node"
		}
		if lc, ok := st["lifecycle"].(map[string]any); ok && lc != nil {
			lc["phase"] = lcPhase
			lc["label"] = label
			lc["detail"] = detail
			lc["busy"] = true
			if pct, ok := snap["pct"]; ok {
				lc["pct"] = pct
			}
			st["lifecycle"] = lc
		}
	}
}
