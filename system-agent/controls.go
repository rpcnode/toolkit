package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// ControlState — runtime toggles (maintenance) shared with collectors.
type ControlState struct {
	mu          sync.Mutex
	maintOn     bool
	maintReason string
	maintPhase  string
	refreshCh   chan struct{}
	// syncRefresh runs collect+write and returns when state is fresh (set from main).
	syncRefresh func() error
}

func newControlState(cfg Config) *ControlState {
	c := &ControlState{
		refreshCh: make(chan struct{}, 1),
	}
	if m := readJSONFile(cfg.MaintenanceFile); truthy(m["enabled"]) {
		c.maintOn = true
		c.maintReason, _ = m["reason"].(string)
		c.maintPhase, _ = m["phase"].(string)
	}
	return c
}

// SetSyncRefresh wires a blocking collect+write used by POST /v1/refresh.
func (c *ControlState) SetSyncRefresh(fn func() error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.syncRefresh = fn
}

func truthy(v any) bool {
	switch t := v.(type) {
	case bool:
		return t
	case string:
		return t == "true" || t == "1"
	default:
		return false
	}
}

func (c *ControlState) RequestRefresh() {
	select {
	case c.refreshCh <- struct{}{}:
	default:
	}
}

func (c *ControlState) Maintenance() map[string]any {
	c.mu.Lock()
	defer c.mu.Unlock()
	return map[string]any{
		"enabled":         c.maintOn,
		"reason":          c.maintReason,
		"phase":           c.maintPhase,
		"retry_after_sec": 30,
		"updated_at":      time.Now().UTC().Format(time.RFC3339),
	}
}

func (c *ControlState) SetMaintenance(cfg Config, enabled bool, reason string) error {
	phase := ""
	if enabled {
		phase = "manual"
	}
	return c.SetMaintenanceEx(cfg, enabled, reason, phase)
}

// SetMaintenanceEx writes maintenance.json; api-agent Go proxy sleeps (503) while enabled.
func (c *ControlState) SetMaintenanceEx(cfg Config, enabled bool, reason, phase string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.maintOn = enabled
	if enabled {
		if reason == "" {
			reason = "RPC paused (503 Retry-After)"
		}
		if phase == "" {
			phase = "manual"
		}
		c.maintReason = reason
		c.maintPhase = phase
	} else {
		c.maintPhase = ""
		c.maintReason = ""
		reason = ""
		phase = ""
	}
	payload := map[string]any{
		"enabled":         enabled,
		"reason":          c.maintReason,
		"phase":           c.maintPhase,
		"retry_after_sec": 30,
		"updated_at":      time.Now().UTC().Format(time.RFC3339),
		"source":          "ops-console",
	}
	b, err := json.MarshalIndent(payload, "", "  ")
	if err != nil {
		return err
	}
	b = append(b, '\n')
	path := cfg.MaintenanceFile
	_ = ensureDir(filepath.Dir(path))
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func (c *ControlState) handleMaintenance(cfg Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		enable := true
		var body struct {
			Enabled *bool  `json:"enabled"`
			Reason  string `json:"reason"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body.Enabled != nil {
			enable = *body.Enabled
		}
		if err := c.SetMaintenance(cfg, enable, body.Reason); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		c.RequestRefresh()
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "maintenance": c.Maintenance(),
		})
	}
}

func (c *ControlState) handleRefresh(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	c.mu.Lock()
	fn := c.syncRefresh
	c.mu.Unlock()
	if fn != nil {
		if err := fn(); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{
				"ok": false, "action": "refresh", "error": err.Error(),
			})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "action": "refresh", "synced": true,
		})
		return
	}
	// Fallback: async kick (state may lag one interval).
	c.RequestRefresh()
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "action": "refresh", "synced": false})
}

func (c *ControlState) handlePreflight(cfg Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		rep, err := refreshPreflight(cfg)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{
				"ok": false, "error": err.Error(), "preflight": preflightToMap(rep),
			})
			return
		}
		c.RequestRefresh()
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "action": "preflight", "preflight": preflightToMap(rep),
		})
	}
}

func consoleControls(cfg Config, snap *SnapshotController, maint map[string]any) map[string]any {
	actions := snap.Actions()
	canStart, _ := actions["can_start"].(bool)
	canStop, _ := actions["can_stop"].(bool)
	maintOn := truthy(maint["enabled"])
	snapOn := snapshotFeatureEnabled(cfg)
	if !snapOn {
		canStart = false
		canStop = false
	}

	return map[string]any{
		"snapshot_start": map[string]any{
			"available": canStart,
			"reason": map[bool]string{
				true: "Download / restore chain snapshot (manual only)",
				false: map[bool]string{
					true:  "Snapshot already running or complete",
					false: "Snapshot disabled for this env (no TRON_SNAPSHOT_URL / TRON_SNAPSHOT_ENABLED=0)",
				}[snapOn],
			}[canStart],
		},
		"snapshot_stop": map[string]any{
			"available": canStop,
			"reason": map[bool]string{
				true:  "Stop in-progress snapshot download",
				false: "No snapshot download in progress",
			}[canStop],
		},
		"maintenance_enable": map[string]any{
			"available": !maintOn,
			"reason": map[bool]string{
				true:  "Pause RPC (api-agent returns 503 Retry-After)",
				false: "Maintenance already active",
			}[!maintOn],
		},
		"maintenance_disable": map[string]any{
			"available": maintOn,
			"reason": map[bool]string{
				true:  "Resume RPC traffic",
				false: "Maintenance is not active",
			}[maintOn],
		},
		"refresh": map[string]any{
			"available": true,
			"reason":    "Force system-agent re-check now",
		},
		"preflight": map[string]any{
			"available": true,
			"reason":    "Re-run host suitability on this host (CPU/RAM/disk/OS) — not a Mac leftover file",
			"method":    "POST",
			"path":      "/api/preflight",
		},
		"public_base": map[string]any{
			"available": true,
			"reason":    "Set RPCNODE_PUBLIC_BASE (RPC) + RPCNODE_PANEL_BASE from a host IP",
			"method":    "POST",
			"path":      "/api/public-base",
			"body":      map[string]string{"ip": "<lan-or-public-ip>", "url": "optional full URL"},
		},
		"agents_restart": map[string]any{
			"available": true,
			"reason":    "Restart api-agent + system-agent via POST /api/v1/agent/restart",
			"method":    "POST",
			"path":      "/api/v1/agent/restart",
			"command":   "curl -X POST -H \"X-Api-Token: $AGENT_API_TOKEN\" http://127.0.0.1:$TRON_AGENT_PORT/api/v1/agent/restart",
		},
		"agent_update": map[string]any{
			"available": true,
			"reason":    "Download agent binaries from CDN and restart units",
			"method":    "POST",
			"path":      "/api/v1/agent/update",
		},
		"hint": fmt.Sprintf("Ops console · env=%s", cfg.Env),
	}
}
