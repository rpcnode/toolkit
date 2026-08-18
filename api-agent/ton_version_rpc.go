package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"strings"
	"time"
)

// tonVersionRPCMethods — admin/node GetVersion probes these (toncenter has none).
var tonVersionRPCMethods = map[string]bool{
	"version":        true,
	"getversion":     true,
	"getnodeversion": true,
}

func networkIsTonCfg() bool {
	return networkIsTon(envOr("TRON_NETWORK", ""))
}

func parseTonVersionRPC(body []byte) (id any, hit bool) {
	body = bytes.TrimSpace(body)
	if len(body) == 0 || body[0] != '{' {
		return nil, false
	}
	var doc map[string]any
	if err := json.Unmarshal(body, &doc); err != nil {
		return nil, false
	}
	method, _ := doc["method"].(string)
	if !tonVersionRPCMethods[strings.ToLower(strings.TrimSpace(method))] {
		return nil, false
	}
	id = doc["id"]
	if id == nil {
		id = 1
	}
	return id, true
}

func tonClientVersionFromState(st map[string]any) string {
	cv := strings.TrimSpace(digString(st, "client_version"))
	if cv != "" {
		return cv
	}
	cv = strings.TrimSpace(digString(st, "rpc", "client_version"))
	if cv != "" {
		return cv
	}
	return strings.TrimSpace(digString(st, "rpc", "version"))
}

func rewriteTonUpstreamPath(method, path, rawQuery string) string {
	if path != "" && path != "/" {
		return ""
	}
	if !strings.EqualFold(method, http.MethodPost) {
		return ""
	}
	up := "/api/v2/jsonRPC"
	if rawQuery != "" {
		up += "?" + rawQuery
	}
	return up
}

func (s *Server) serveTonVersionRPC(w http.ResponseWriter, body []byte, start time.Time) bool {
	id, hit := parseTonVersionRPC(body)
	if !hit {
		return false
	}
	cv := tonClientVersionFromState(readJSONFile(s.cfg.StateFile))
	if cv == "" {
		return false
	}
	s.metrics.Observe(http.StatusOK, time.Since(start), false)
	writeJSON(w, http.StatusOK, map[string]any{
		"jsonrpc": "2.0",
		"id":      id,
		"result":  cv,
	})
	return true
}
