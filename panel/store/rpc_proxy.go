package store

import (
	"encoding/json"
	"strings"
)

func ParseRPCProxyJSON(s string) *RPCProxyStats {
	s = strings.TrimSpace(s)
	if s == "" || s == "{}" || s == "null" {
		return nil
	}
	var raw map[string]any
	if err := json.Unmarshal([]byte(s), &raw); err != nil || len(raw) == 0 {
		return nil
	}
	// Idle proxy (all zeros) is still a valid sample — rps_1m=0 must show the panel.
	var st RPCProxyStats
	if err := json.Unmarshal([]byte(s), &st); err != nil {
		return nil
	}
	return &st
}

func EncodeRPCProxyJSON(st *RPCProxyStats) string {
	if st == nil {
		return ""
	}
	b, err := json.Marshal(st)
	if err != nil {
		return ""
	}
	return string(b)
}
