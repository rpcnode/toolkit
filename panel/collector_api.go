package main

import (
	"net/http"
	"strings"

	"github.com/ali3/tron-toolkit/panel/store"
)

// POST /api/collector/tick — ask panel-collector to enqueue a full poll ASAP.
// UI refresh icon may live-poll agents separately; this only kicks the SQLite writer.
func (s *Server) handleCollectorAPI(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSuffix(r.URL.Path, "/")
	switch {
	case path == "/api/collector/tick" && r.Method == http.MethodPost:
		if err := s.db.RequestForceTick(); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		stats, _, _ := s.db.GetMeta(store.MetaLastStats)
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "forced": true,
			"note":  "collector will enqueue all registered nodes on next loop (≤ interval)",
			"stats": stats,
		})
	case path == "/api/collector/stats" && r.Method == http.MethodGet:
		stats, _, _ := s.db.GetMeta(store.MetaLastStats)
		pulse := s.db.CollectorPulse()
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":              true,
			"has_tick":        pulse.OK,
			"last_stats":      stats,
			"last_tick_at":    pulse.LastTickAt,
			"age_sec":         pulse.AgeSec,
			"stale":           pulse.Stale,
			"stale_after_sec": int(store.CollectorStaleAfter.Seconds()),
			"hint":            pulse.Hint(),
		})
	default:
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
	}
}
