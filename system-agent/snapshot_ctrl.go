package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// SnapshotController — start/stop via UI / rpcnodectl / API, or lifecycle pipeline
// after install completes (see pipeline.go). Idempotent Start() is safe to call
// from the auto-pipeline tick.

type SnapshotController struct {
	cfg Config
	mu  sync.Mutex
}

func newSnapshotController(cfg Config) *SnapshotController {
	return &SnapshotController{cfg: cfg}
}

func (c *SnapshotController) handleStart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	if err := c.Start(); err != nil {
		writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "action": "start"})
}

func (c *SnapshotController) handleStop(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	if err := c.Stop(); err != nil {
		writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "action": "stop"})
}

func (c *SnapshotController) writeSnapshotState(phase, detail, errMsg string) {
	if c.cfg.SnapshotState == "" {
		return
	}
	_ = ensureDir(filepath.Dir(c.cfg.SnapshotState))
	doc := readJSONFile(c.cfg.SnapshotState)
	if doc == nil {
		doc = map[string]any{}
	}
	doc["phase"] = phase
	doc["detail"] = detail
	doc["error"] = errMsg
	doc["updated_at"] = time.Now().UTC().Format(time.RFC3339)
	if url := c.cfg.SnapshotURL; url != "" {
		doc["url"] = url
	}
	if err := writeJSONFile(c.cfg.SnapshotState, doc); err != nil {
		log.Printf("snapshot state write: %v", err)
	}
}

func (c *SnapshotController) Start() error {
	hostLogf("INFO", "system-agent", "snapshot", "begin %s/%s", c.cfg.Network, c.cfg.Env)
	c.mu.Lock()
	defer c.mu.Unlock()

	if fileExists(c.cfg.SnapshotMarker) {
		return fmt.Errorf("snapshot already ready (%s)", c.cfg.SnapshotMarker)
	}
	unitState := systemctlActive(c.cfg.SnapshotService)
	// oneshot units stay "activating" for the whole download (Sui formal / long TRON).
	if unitState == "active" || unitState == "activating" || wgetRunning(c.cfg) ||
		(strings.EqualFold(c.cfg.Network, "sui") && suiToolSnapshotRunning(c.cfg)) {
		return fmt.Errorf("snapshot already running")
	}
	// Pre-start disk gate: free ≥ archive×mult + margin (TRON streams → ×1.0).
	if err := checkSnapshotDiskSpace(c.cfg); err != nil {
		msg := err.Error()
		c.writeSnapshotState("error", msg, msg)
		hostLogf("ERROR", "system-agent", "snapshot", "blocked %s/%s: %s", c.cfg.Network, c.cfg.Env, msg)
		log.Printf("snapshot START blocked: %s", msg)
		return err
	}
	unit := c.cfg.SnapshotService + ".service"
	// --no-block: oneshot formal downloads (Sui) can run for hours; do not stall Start()/pipeline.
	cmd := exec.Command("systemctl", "start", "--no-block", unit)
	if out, err := cmd.CombinedOutput(); err != nil {
		// After start race: unit may already be activating — treat as success.
		st := systemctlActive(c.cfg.SnapshotService)
		if st == "active" || st == "activating" ||
			(strings.EqualFold(c.cfg.Network, "sui") && suiToolSnapshotRunning(c.cfg)) {
			c.writeSnapshotState("download", "started via API", "")
			return nil
		}
		// Host CLI fallback only for TRON profile (not Sui/Robinhood/…).
		if strings.EqualFold(c.cfg.Network, "tron") && c.cfg.ToolkitDir != "" {
			ctl := toolkitCtlPath(c.cfg.ToolkitDir)
			bg := exec.Command(ctl, "snapshot", "start")
			bg.Env = append(os.Environ(), "TRON_ENV="+c.cfg.Env)
			if err2 := bg.Start(); err2 != nil {
				msg := fmt.Sprintf("systemctl start %s: %v (%s); rpcnodectl: %v", unit, err, string(out), err2)
				c.writeSnapshotState("error", msg, msg)
				return fmt.Errorf("%s", msg)
			}
			log.Printf("snapshot START via %s pid=%d", filepath.Base(ctl), bg.Process.Pid)
			c.writeSnapshotState("download", "started via API (rpcnodectl)", "")
			return nil
		}
		msg := fmt.Sprintf("systemctl start %s: %v (%s)", unit, err, string(out))
		c.writeSnapshotState("error", msg, msg)
		return fmt.Errorf("%s", msg)
	}
	log.Printf("snapshot START via %s", unit)
	c.writeSnapshotState("download", "started via API", "")
	return nil
}

func (c *SnapshotController) Stop() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	unit := c.cfg.SnapshotService + ".service"
	_ = exec.Command("systemctl", "stop", unit).Run()
	// Best-effort: stop wget for THIS env only (shared archive basename across envs).
	stopEnvSnapshotWget(c.cfg)
	log.Printf("snapshot STOP requested")
	c.writeSnapshotState("idle", "stopped via API", "")
	return nil
}

func (c *SnapshotController) Actions() map[string]any {
	c.mu.Lock()
	defer c.mu.Unlock()
	ready := fileExists(c.cfg.SnapshotMarker)
	running := systemctlActive(c.cfg.SnapshotService) == "active" || wgetRunning(c.cfg)
	return map[string]any{
		"can_start": !ready && !running,
		"can_stop":  running,
		"manual":    true,
	}
}

// GuardDiskDuringDownload — periodic ENOSPC floor while wget/unit is active.
// Stops the snapshot and surfaces snapshot.error / lifecycle detail.
func (c *SnapshotController) GuardDiskDuringDownload() {
	c.mu.Lock()
	defer c.mu.Unlock()

	running := systemctlActive(c.cfg.SnapshotService) == "active" || wgetRunning(c.cfg)
	if !running {
		return
	}
	if err := checkSnapshotDiskAbort(c.cfg); err != nil {
		msg := err.Error()
		log.Printf("snapshot disk abort: %s", msg)
		unit := c.cfg.SnapshotService + ".service"
		_ = exec.Command("systemctl", "stop", unit).Run()
		stopEnvSnapshotWget(c.cfg)
		c.writeSnapshotState("error", msg, msg)
	}
}

// stopEnvSnapshotWget kills wget whose cmdline references this env's data dir.
func stopEnvSnapshotWget(cfg Config) {
	needle := strings.TrimSpace(cfg.DataDir)
	if needle == "" {
		needle = strings.TrimSpace(cfg.Env)
	}
	if needle == "" {
		return
	}
	_ = exec.Command("pkill", "-f", "wget.*"+needle).Run()
}

// ClearDiskErrorIfRecovered — when a prior insufficient-disk failure is set and
// free space now meets the pre-start gate, reset phase so the pipeline can retry.
func (c *SnapshotController) ClearDiskErrorIfRecovered() bool {
	c.mu.Lock()
	defer c.mu.Unlock()

	doc := readJSONFile(c.cfg.SnapshotState)
	phase, _ := doc["phase"].(string)
	errMsg, _ := doc["error"].(string)
	if !strings.EqualFold(phase, "error") && errMsg == "" {
		return false
	}
	if !isInsufficientDiskError(phase + " " + errMsg) {
		detail, _ := doc["detail"].(string)
		if !isInsufficientDiskError(detail) {
			return false
		}
	}
	if err := checkSnapshotDiskSpace(c.cfg); err != nil {
		return false
	}
	c.writeSnapshotState("idle", "disk space recovered — ready to retry snapshot", "")
	log.Printf("snapshot: disk space recovered — cleared previous disk error")
	return true
}
