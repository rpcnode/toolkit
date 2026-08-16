package main

import (
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestParseTronBlockTimestamp(t *testing.T) {
	ts := parseTronBlockTimestamp(map[string]any{
		"block_header": map[string]any{
			"raw_data": map[string]any{
				"number":    float64(563259),
				"timestamp": float64(1531581954000),
			},
		},
	})
	if ts.IsZero() || ts.Year() != 2018 {
		t.Fatalf("want 2018 block time, got %v", ts)
	}
	if !tronBlockTimeStale(ts) {
		t.Fatal("2018 header must be stale vs wall clock")
	}
	if tronBlockTimeStale(time.Now().UTC()) {
		t.Fatal("fresh block time is not stale")
	}
	if tronBlockTimeStale(time.Time{}) {
		t.Fatal("zero time is unknown, not stale")
	}
}

func TestParseTronBlockNumber(t *testing.T) {
	n := parseTronBlockNumber(map[string]any{
		"block_header": map[string]any{
			"raw_data": map[string]any{"number": float64(68820786)},
		},
	})
	if n != 68820786 {
		t.Fatalf("got %d", n)
	}
}

func TestParseTronNodeInfoPeers(t *testing.T) {
	info := parseTronNodeInfo(map[string]any{
		"currentConnectCount": float64(3),
		"activeConnectCount":  float64(1),
		"passiveConnectCount": float64(2),
		"block":               "Num:68824786,ID:00000000041a2ed2deadbeef",
	})
	if !info.OK || info.Peers != 3 || info.BlockNum != 68824786 {
		t.Fatalf("%+v", info)
	}
	sum := parseTronNodeInfo(map[string]any{
		"activeConnectCount":  float64(1),
		"passiveConnectCount": float64(1),
	})
	if sum.Peers != 2 {
		t.Fatalf("active+passive=%d", sum.Peers)
	}
}

func TestTronPublicTipURLs(t *testing.T) {
	nile := tronPublicTipURLs(Config{Env: "nile"})
	if len(nile) == 0 || !strings.Contains(nile[0], "nile.trongrid.io") {
		t.Fatalf("nile urls=%v", nile)
	}
	main := tronPublicTipURLs(Config{Env: "mainnet"})
	if len(main) == 0 || !strings.Contains(main[0], "api.trongrid.io") {
		t.Fatalf("main urls=%v", main)
	}
}

func TestTronLagClosedPct(t *testing.T) {
	cfg := Config{Env: "nile", StateFile: filepath.Join(t.TempDir(), "agent-state.json")}
	p, ok := tronLagClosedPct(cfg, 188_000)
	if !ok || p > 1.0 {
		t.Fatalf("first sample want ~0.1 got %v ok=%v", p, ok)
	}
	p, ok = tronLagClosedPct(cfg, 94_000)
	if !ok || p < 49.9 || p > 50.1 {
		t.Fatalf("half lag closed want ~50 got %v ok=%v", p, ok)
	}
	p, ok = tronLagClosedPct(cfg, 20)
	if !ok || p != 100 {
		t.Fatalf("near tip want 100 got %v ok=%v", p, ok)
	}
}

func TestTronRunStepShowsBehind(t *testing.T) {
	step := buildRunStep(nodeLifecycleInput{
		Network: "tron", Env: "nile",
		NodeActive: true, RPCOK: true, IBD: true,
		Height: int64(69_927_899), Headers: int64(70_115_669),
		VerifyPct: 0.001, Peers: 1,
	})
	if step["status"] != "active" {
		t.Fatalf("catch-up must stay active: %+v", step)
	}
	detail, _ := step["detail"].(string)
	if !strings.Contains(detail, "behind") || !strings.Contains(detail, "70115669") {
		t.Fatalf("detail=%q", detail)
	}
}

func TestTronRunStepSynced(t *testing.T) {
	step := buildRunStep(nodeLifecycleInput{
		Network: "tron", Env: "nile",
		NodeActive: true, RPCOK: true, IBD: false,
		Height: int64(70_115_669), Headers: int64(70_115_680),
		VerifyPct: 1, Peers: 4,
	})
	if step["status"] != "done" {
		t.Fatalf("synced: %+v", step)
	}
}
