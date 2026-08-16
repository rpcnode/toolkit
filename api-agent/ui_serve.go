package main

import (
	"net/http"
)

// SPA was moved to the standalone panel service (../panel).
// Host api-agent no longer embeds or serves the ops console.

func isSPAPath(p string) bool {
	return false
}

func (s *Server) serveStatusUI(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusNotFound, map[string]any{
		"ok":      false,
		"error":   "panel_moved",
		"message": "Ops SPA runs on the standalone panel (control plane), not on the node agent. Start: docker compose -f docker-compose.panel.yml up -d --build",
		"hint":    "http://127.0.0.1:8093/",
	})
}
