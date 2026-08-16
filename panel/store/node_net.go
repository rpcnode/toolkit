package store

import (
	"encoding/json"
	"strconv"
	"strings"
)

// ParseNodeNetFromStatusJSON extracts node_net_* from cached status raw_json
// (top-level node_net or host_metrics.current / host).
func ParseNodeNetFromStatusJSON(raw string) *NodeNetStats {
	raw = strings.TrimSpace(raw)
	if raw == "" || raw == "{}" {
		return nil
	}
	var doc map[string]any
	if json.Unmarshal([]byte(raw), &doc) != nil || doc == nil {
		return nil
	}
	return extractNodeNet(doc)
}

func extractNodeNet(doc map[string]any) *NodeNetStats {
	if doc == nil {
		return nil
	}
	candidates := []map[string]any{}
	if nn, _ := doc["node_net"].(map[string]any); nn != nil {
		candidates = append(candidates, nn)
	}
	if hm, _ := doc["host_metrics"].(map[string]any); hm != nil {
		if cur, _ := hm["current"].(map[string]any); cur != nil {
			candidates = append(candidates, cur)
		}
	}
	if host, _ := doc["host"].(map[string]any); host != nil {
		candidates = append(candidates, host)
	}
	for _, m := range candidates {
		rx, okRx := asFloatOK(m["node_net_rx_mbps"])
		tx, okTx := asFloatOK(m["node_net_tx_mbps"])
		if !okRx && !okTx {
			continue
		}
		st := &NodeNetStats{RxMbps: rx, TxMbps: tx}
		if v, ok := asUint64OK(m["node_net_rx_bytes"]); ok {
			st.RxBytes = v
		}
		if v, ok := asUint64OK(m["node_net_tx_bytes"]); ok {
			st.TxBytes = v
		}
		return st
	}
	return nil
}

func asFloatOK(v any) (float64, bool) {
	switch t := v.(type) {
	case float64:
		return t, true
	case float32:
		return float64(t), true
	case int:
		return float64(t), true
	case int64:
		return float64(t), true
	case json.Number:
		f, err := t.Float64()
		return f, err == nil
	case string:
		f, err := strconv.ParseFloat(strings.TrimSpace(t), 64)
		return f, err == nil
	default:
		return 0, false
	}
}

func asUint64OK(v any) (uint64, bool) {
	switch t := v.(type) {
	case float64:
		if t < 0 {
			return 0, false
		}
		return uint64(t), true
	case int64:
		if t < 0 {
			return 0, false
		}
		return uint64(t), true
	case int:
		if t < 0 {
			return 0, false
		}
		return uint64(t), true
	case uint64:
		return t, true
	case json.Number:
		u, err := strconv.ParseUint(t.String(), 10, 64)
		return u, err == nil
	case string:
		u, err := strconv.ParseUint(strings.TrimSpace(t), 10, 64)
		return u, err == nil
	default:
		return 0, false
	}
}
