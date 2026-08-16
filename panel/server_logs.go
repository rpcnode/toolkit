package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// handleServersAPI — Servers-scoped panel routes (tip proxy helpers).
// GET /api/servers/{id}/logs → tip GET /api/v1/agent/logs
func (s *Server) handleServersAPI(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSuffix(r.URL.Path, "/")
	if !strings.HasPrefix(path, "/api/servers/") {
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
		return
	}
	rest := strings.TrimPrefix(path, "/api/servers/")
	parts := strings.Split(rest, "/")
	if len(parts) < 2 || parts[0] == "" {
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
		return
	}
	id := parts[0]
	action := parts[1]
	if action != "logs" || r.Method != http.MethodGet {
		writeJSON(w, http.StatusNotFound, map[string]any{
			"ok": false, "error": "not_found",
			"message": "GET /api/servers/{id}/logs",
		})
		return
	}

	srv, ok, err := s.db.GetServer(id)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	if !ok {
		// Registry may still hold the server before DB migrate edge-cases.
		if n, found := s.registry.Get(id); found {
			srv = n
			ok = true
		}
	}
	if !ok {
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
		return
	}
	base := strings.TrimRight(strings.TrimSpace(srv.AgentURL), "/")
	if base == "" || strings.TrimSpace(srv.AgentKey) == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"ok": false, "error": "agent_url_or_key_missing",
			"message": "Server tip Agent URL / key missing",
		})
		return
	}
	if port := agentURLPort(base); isLeafAgentPort(port) {
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"ok": false, "error": "tip_url_required",
			"message": fmt.Sprintf(
				"Server Agent URL port :%d is a leaf Agent API — fix Servers → Edit to tip /etc/rpcnode/agent.port",
				port,
			),
		})
		return
	}

	lines := 200
	if v := strings.TrimSpace(r.URL.Query().Get("lines")); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			lines = n
		}
	}
	if lines < 50 {
		lines = 50
	}
	if lines > 500 {
		lines = 500
	}
	unit := strings.TrimSpace(r.URL.Query().Get("unit"))
	tipURL := fmt.Sprintf("%s/api/v1/agent/logs?lines=%d", base, lines)
	if unit != "" {
		tipURL += "&unit=" + unit
	}

	client := s.client
	if client == nil {
		client = &http.Client{Timeout: 12 * time.Second}
	}
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, tipURL, nil)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	req.Header.Set("Authorization", "Bearer "+srv.AgentKey)
	req.Header.Set("X-Api-Token", srv.AgentKey)
	resp, err := client.Do(req)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"ok": false, "error": "agent_unreachable", "message": err.Error(),
			"agent_url": base,
		})
		return
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if resp.StatusCode >= 400 {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"ok": false, "error": "agent_error",
			"message": fmt.Sprintf("tip HTTP %d", resp.StatusCode),
			"body":    strings.TrimSpace(string(body)),
			"agent_url": base,
		})
		return
	}
	var doc map[string]any
	if err := json.Unmarshal(body, &doc); err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"ok": false, "error": "invalid_json", "message": err.Error(),
		})
		return
	}
	doc["server_id"] = id
	doc["agent_url"] = base
	writeJSON(w, resp.StatusCode, doc)
}
