package main

import (
	"fmt"
	"log"
	"net/http"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// NodeRestartController — soft-stop + start chain fullnode unit(s) with Go-proxy sleep.
// Uses systemctl stop (honors ExecStop: bitcoin-cli / xrpld server_stop / SIGTERM) then start.
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
	out := map[string]any{}
	for k, v := range n.st {
		out[k] = v
	}
	out["busy"] = n.busy
	return out
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
	} else if phase == "idle" || phase == "restarting" || phase == "starting" || phase == "stopping" {
		n.st["last_error"] = ""
	}
}

func (n *NodeRestartController) nodeUnit() string {
	unit := strings.TrimSuffix(strings.TrimSpace(n.cfg.NodeService), ".service")
	if unit == "" {
		np := LookupNetworkProfile(n.cfg.Network, n.cfg.Env)
		unit = strings.TrimSuffix(np.ServiceUnit(), ".service")
	}
	return unit
}

// nodeUnits — primary + aux units that must recycle together (CL / op-node / consensus).
func (n *NodeRestartController) nodeUnits() []string {
	primary := n.nodeUnit()
	if primary == "" {
		return nil
	}
	net := strings.ToLower(strings.TrimSpace(n.cfg.Network))
	env := strings.ToLower(strings.TrimSpace(n.cfg.Env))
	units := []string{primary}
	switch net {
	case "ethereum":
		units = append(units, "ethereum-lighthouse-"+env)
	case "optimism":
		units = append(units, "optimism-op-node-"+env)
	case "base":
		units = append(units, "base-consensus-"+env)
	}
	// Dedup preserve order.
	seen := map[string]bool{}
	out := make([]string, 0, len(units))
	for _, u := range units {
		u = strings.TrimSuffix(strings.TrimSpace(u), ".service")
		if u == "" || seen[u] {
			continue
		}
		seen[u] = true
		out = append(out, u)
	}
	return out
}

func (n *NodeRestartController) stopBudget() time.Duration {
	switch strings.ToLower(strings.TrimSpace(n.cfg.Network)) {
	case "bitcoin", "doge", "ltc", "dash", "bch", "zcash":
		return 25 * time.Second
	case "xrpl":
		return 35 * time.Second
	case "solana", "optimism", "stellar", "sui", "aptos":
		return 35 * time.Second
	case "avalanche":
		return 50 * time.Second
	default:
		return 50 * time.Second
	}
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
	n.st["phase"] = "restarting"
	n.st["detail"] = "starting soft restart"
	n.st["pct"] = 5
	n.st["last_error"] = ""
	n.st["unit"] = units[0]
	n.st["units"] = strings.Join(units, ",")
	st := n.snapshotLocked()
	n.mu.Unlock()

	go n.run(units)
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

	// 2) Soft stop — systemctl stop honors ExecStop (cli stop / server_stop / SIGTERM).
	n.set("stopping", "soft-stopping "+label, 35, "")
	budget := n.stopBudget()
	for _, u := range units {
		ctxDone := make(chan struct{})
		go func(unit string) {
			_ = exec.Command("systemctl", "stop", unit+".service").Run()
			close(ctxDone)
		}(u)
		select {
		case <-ctxDone:
		case <-time.After(budget):
			log.Printf("node_restart: stop %s exceeded %s — continuing", u, budget)
		}
	}

	n.set("starting", "starting "+label, 70, "")
	// Start primary first, then aux (consensus / lighthouse after execution).
	for _, u := range units {
		out, err := exec.Command("systemctl", "start", u+".service").CombinedOutput()
		if err != nil {
			msg := fmt.Sprintf("systemctl start %s: %v (%s)", u, err, strings.TrimSpace(string(out)))
			n.set("error", msg, 40, msg)
			if n.ctrl != nil {
				_ = n.ctrl.SetMaintenanceEx(n.cfg, false, "", "")
			}
			log.Printf("node_restart failed: %s", msg)
			return
		}
	}

	time.Sleep(2 * time.Second)

	if n.ctrl != nil {
		_ = n.ctrl.SetMaintenanceEx(n.cfg, false, "", "")
	}
	n.set("idle", "soft-restarted — node starting", 100, "")
	log.Printf("node_restart: %s/%s units=%s ok (soft stop→start)", n.cfg.Network, n.cfg.Env, label)
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

func applyNodeRestartToStatus(st map[string]any, snap map[string]any) {
	if st == nil || snap == nil {
		return
	}
	st["node_restart"] = snap
	phase := strings.ToLower(strings.TrimSpace(fmt.Sprint(snap["phase"])))
	switch phase {
	case "restarting", "starting", "stopping":
		st["ui_phase"] = phase
		if phase == "restarting" || phase == "stopping" {
			st["ui_phase"] = "restarting"
		}
		st["health"] = "maintenance"
		st["degraded"] = true
		detail := strings.TrimSpace(fmt.Sprint(snap["detail"]))
		label := "Restarting node"
		switch phase {
		case "starting":
			label = "Starting after restart"
		case "stopping":
			label = "Soft-stopping node"
		}
		if lc, ok := st["lifecycle"].(map[string]any); ok && lc != nil {
			lc["phase"] = "restarting"
			if phase == "starting" {
				lc["phase"] = "starting"
			}
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
