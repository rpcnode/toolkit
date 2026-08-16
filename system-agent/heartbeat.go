package main

import (
	"bytes"
	"encoding/json"
	"log"
	"net/http"
	"runtime"
	"strings"
	"time"
)

// pushMetricsHeartbeat posts a compact host snapshot to the panel ingest URL.
// PANEL_INGEST_URL or RPCNODE_PANEL_BASE must be set (e.g. https://panel.example.com).
func pushMetricsHeartbeat(cfg Config, host map[string]any) {
	base := strings.TrimRight(envOr("PANEL_INGEST_URL", cfg.PanelBase), "/")
	if base == "" {
		return
	}
	token := strings.TrimSpace(envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", "")))
	if token == "" {
		return
	}

	disk, _ := host["disk"].(map[string]any)
	if disk == nil {
		disk = diskRoot()
	}

	agentURL := strings.TrimRight(envOr("TRON_PUBLIC_BASE", cfg.PublicBase), "/")

	body := map[string]any{
		"host_id":       envOr("RPCNODE_HOST_ID", cfg.Env),
		"agent_url":     agentURL,
		"cpu_pct":       floatOf(host["cpu_pct"]),
		"mem_pct":       floatOf(host["mem_pct"]),
		"mem_used_mb":   floatOf(host["mem_used_mb"]),
		"mem_total_mb":  floatOf(host["mem_total_mb"]),
		"disk_used_pct": floatOf(disk["used_pct"]),
		"disk_used_gb":  floatOf(disk["used_gb"]),
		"disk_total_gb": floatOf(disk["total_gb"]),
		"load_1":        floatOf(host["load_1"]),
		"os":            runtime.GOOS,
		"arch":          runtime.GOARCH,
		"collected_at":  time.Now().UTC().Format(time.RFC3339),
	}

	raw, err := json.Marshal(body)
	if err != nil {
		return
	}
	url := base + "/api/ingest/server-metrics"
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(raw))
	if err != nil {
		return
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("X-Api-Token", token)

	client := &http.Client{Timeout: 12 * time.Second}
	res, err := client.Do(req)
	if err != nil {
		log.Printf("metrics heartbeat: %v", err)
		return
	}
	defer res.Body.Close()
	if res.StatusCode >= 300 {
		log.Printf("metrics heartbeat: HTTP %d", res.StatusCode)
	}
}

func floatOf(v any) float64 {
	switch t := v.(type) {
	case float64:
		return t
	case float32:
		return float64(t)
	case int:
		return float64(t)
	case int64:
		return float64(t)
	case json.Number:
		f, _ := t.Float64()
		return f
	default:
		return 0
	}
}

func startMetricsHeartbeat(ctxDone <-chan struct{}, cfg Config, sampleFn func() map[string]any) {
	sec := mustAtoi(envOr("PANEL_METRICS_HEARTBEAT_SEC", "45"), 45)
	if sec < 15 {
		sec = 15
	}
	if strings.TrimRight(envOr("PANEL_INGEST_URL", cfg.PanelBase), "/") == "" {
		log.Printf("metrics heartbeat: disabled (set PANEL_INGEST_URL or RPCNODE_PANEL_BASE)")
		return
	}
	t := time.NewTicker(time.Duration(sec) * time.Second)
	defer t.Stop()
	time.Sleep(3 * time.Second)
	pushMetricsHeartbeat(cfg, sampleFn())
	for {
		select {
		case <-ctxDone:
			return
		case <-t.C:
			pushMetricsHeartbeat(cfg, sampleFn())
		}
	}
}
