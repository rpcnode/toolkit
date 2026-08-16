package main

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestSummarizeStatusBitcoinStartErrorNotWarming(t *testing.T) {
	detail := `bitcoin.conf missing: /etc/bitcoin/mainnet/bitcoin.conf — Error: specified config file "/etc/bitcoin/mainnet/bitcoin.conf" could not be opened.`
	raw, _ := json.Marshal(map[string]any{
		"ok": true, "health": "error", "network": "bitcoin",
		"agent": map[string]any{
			"status": "error", "activity": "node_start_failed", "last_error": detail,
		},
		"lifecycle": map[string]any{
			"phase": "error", "label": "Start error", "detail": detail,
			"node_status": "start_error", "current": "start",
			"steps": []any{
				map[string]any{"id": "ports", "title": "Ports", "status": "done"},
				map[string]any{"id": "install", "title": "Install", "status": "done"},
				map[string]any{"id": "start", "title": "Start", "status": "error", "detail": detail, "error": true},
				map[string]any{"id": "run", "title": "IBD / sync", "status": "pending"},
			},
		},
	})
	st := summarizeStatus("d976bd5b", "starting", raw)
	if st.Phase != "error" {
		t.Fatalf("phase=%q want error", st.Phase)
	}
	if st.Error == "" || !strings.Contains(st.Error, "could not be opened") {
		t.Fatalf("Error=%q", st.Error)
	}
	if !strings.Contains(st.Detail, "could not be opened") {
		t.Fatalf("Detail=%q", st.Detail)
	}
	low := strings.ToLower(st.Detail + " " + st.Label)
	if strings.Contains(low, "warming") {
		t.Fatalf("must not show warming: label=%q detail=%q", st.Label, st.Detail)
	}
}

func TestSummarizeStatusHealthyIgnoresForeignWget(t *testing.T) {
	// Mainnet is healthy; snapshot.wget_running true is another env's download.
	raw, _ := json.Marshal(map[string]any{
		"ok": true, "health": "ok", "network": "tron",
		"rpc": map[string]any{"node_height": 85228899, "reachable": true, "http_ok": true},
		"snapshot": map[string]any{
			"phase": "done", "ready": true, "pct": "100", "wget_running": true,
		},
		"lifecycle": map[string]any{
			"phase": "healthy", "label": "Running", "detail": "Healthy · height 85228899",
			"complete": true, "current": "run", "node_status": "running",
			"steps": []any{
				map[string]any{"id": "ports", "title": "Ports", "status": "done"},
				map[string]any{"id": "install", "title": "Install", "status": "done"},
				map[string]any{"id": "snapshot", "title": "Snapshot", "status": "done"},
				map[string]any{"id": "start", "title": "Start", "status": "done"},
				map[string]any{"id": "run", "title": "Running", "status": "done"},
			},
		},
	})
	st := summarizeStatus("66407d81", "snapshot_running", raw)
	if st.Phase != "working" {
		t.Fatalf("phase=%q want working (healthy must beat foreign wget)", st.Phase)
	}
	// Ops-ready cards use Healthy (not "Running" / Step N) — see summarizeStatus.
	if !strings.Contains(strings.ToLower(st.Label), "healthy") {
		t.Fatalf("label=%q", st.Label)
	}
}

func TestSummarizeStatusFullSyncStepTitleNotRunning(t *testing.T) {
	// HL/BSC-style: still on run/Full sync — card must not say "Step N: Running".
	raw, _ := json.Marshal(map[string]any{
		"ok": true, "health": "degraded", "network": "hyperliquid",
		"rpc": map[string]any{"node_height": 12345, "reachable": true, "http_ok": true},
		"connect": map[string]any{"ready": false},
		"lifecycle": map[string]any{
			"phase": "run", "label": "Syncing", "detail": "Synced · block 12345 · peers 0",
			"complete": false, "current": "run", "node_status": "syncing", "pct": 100,
			"steps": []any{
				map[string]any{"id": "ports", "title": "Ports", "status": "done"},
				map[string]any{"id": "install", "title": "Install", "status": "done"},
				map[string]any{"id": "start", "title": "Start", "status": "done"},
				map[string]any{"id": "run", "title": "Full sync", "status": "active", "detail": "Synced · block 12345 · peers 0"},
			},
		},
	})
	st := summarizeStatus("453ab902", "syncing", raw)
	if st.Phase != "syncing" {
		t.Fatalf("phase=%q want syncing", st.Phase)
	}
	if !strings.Contains(st.Label, "Full sync") {
		t.Fatalf("label=%q want Full sync step title", st.Label)
	}
	if strings.Contains(strings.ToLower(st.Label), "running") {
		t.Fatalf("label must not say Running while Full sync: %q", st.Label)
	}
}

func TestSummarizeStatusHealthyBadgeNotStepRunning(t *testing.T) {
	raw, _ := json.Marshal(map[string]any{
		"ok": true, "health": "ok", "network": "hyperliquid",
		"rpc": map[string]any{"node_height": 12345, "reachable": true, "http_ok": true},
		"connect": map[string]any{"ready": true},
		"lifecycle": map[string]any{
			"phase": "healthy", "label": "Running", "detail": "Synced · block 12345 · peers 2",
			"complete": true, "current": "run", "node_status": "running",
			"steps": []any{
				map[string]any{"id": "ports", "title": "Ports", "status": "done"},
				map[string]any{"id": "install", "title": "Install", "status": "done"},
				map[string]any{"id": "start", "title": "Start", "status": "done"},
				map[string]any{"id": "run", "title": "Full sync", "status": "done"},
			},
		},
	})
	st := summarizeStatus("453ab902", "online", raw)
	if st.Phase != "working" {
		t.Fatalf("phase=%q want working", st.Phase)
	}
	if strings.HasPrefix(strings.ToLower(st.Label), "step ") {
		t.Fatalf("ops-ready label must not be Step headline: %q", st.Label)
	}
	if !strings.Contains(strings.ToLower(st.Label), "running") &&
		!strings.Contains(strings.ToLower(st.Label), "healthy") {
		t.Fatalf("label=%q", st.Label)
	}
}

func TestLifecycleStartErrorDetailIgnoresBareUnitPath(t *testing.T) {
	lc := map[string]any{
		"phase": "start", "node_status": "ready_to_start", "label": "Start",
		"detail": "Ready to start stellar-rpc",
		"progress": map[string]any{
			"auto": map[string]any{
				"last_error": "unit=/etc/systemd/system/stellar-testnet.service",
			},
		},
		"steps": []any{
			map[string]any{"id": "start", "status": "pending", "detail": "Ready to start stellar-rpc"},
		},
	}
	if got := lifecycleStartErrorDetail(lc); got != "" {
		t.Fatalf("bare unit path must not be Start error: %q", got)
	}
	lc2 := map[string]any{
		"phase": "error", "node_status": "start_error", "label": "Start error",
		"detail": "unit=/etc/systemd/system/stellar-testnet.service",
	}
	if got := lifecycleStartErrorDetail(lc2); got != "" {
		t.Fatalf("detail-only unit path must be dropped: %q", got)
	}
}

func TestLifecycleStartErrorDetailFromStep(t *testing.T) {
	lc := map[string]any{
		"phase": "start", "node_status": "starting", "detail": "bitcoind warming up · waiting for RPC",
		"steps": []any{
			map[string]any{
				"id": "start", "status": "error",
				"detail": "bitcoin-mainnet unit failed (Result=exit-code, restarts=188): could not be opened",
				"error":  true,
			},
		},
	}
	got := lifecycleStartErrorDetail(lc)
	if !strings.Contains(got, "exit-code") {
		t.Fatalf("got=%q", got)
	}
}

func TestSummarizeStatusTronSnapshotDiskError(t *testing.T) {
	detail := "insufficient disk for snapshot: free≈7000 GiB on /data/tron/mainnet, need≥3733 GiB (stream unpack)"
	raw, _ := json.Marshal(map[string]any{
		"ok": true, "network": "tron",
		"snapshot": map[string]any{
			"enabled": true, "failed": true, "phase": "error",
			"error": detail, "detail": detail,
		},
		"lifecycle": map[string]any{
			"phase": "error", "label": "Snapshot error", "detail": detail,
			"node_status": "snapshot_error", "current": "snapshot",
		},
	})
	st := summarizeStatus("tron-node", "installing", raw)
	if st.Phase != "error" {
		t.Fatalf("phase=%q want error", st.Phase)
	}
	if !strings.Contains(st.Detail, "insufficient disk") {
		t.Fatalf("Detail=%q", st.Detail)
	}
	if st.Error == "" || !strings.Contains(st.Error, "insufficient disk") {
		t.Fatalf("Error=%q", st.Error)
	}
}
