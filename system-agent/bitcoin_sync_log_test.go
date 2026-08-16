package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestFormatBitcoinSyncLogLineIBD(t *testing.T) {
	line := formatBitcoinSyncLogLine(bitcoinChainInfo{
		OK: true, IBD: true, Blocks: 100, Headers: 200, Verify: 0.421,
		Peers: 8, SizeOnDisk: 12 * 1024 * 1024 * 1024,
	}, time.Date(2026, 8, 9, 12, 0, 0, 0, time.UTC), "mainnet")
	if !strings.Contains(line, "IBD") || !strings.Contains(line, "blocks 100 / headers 200") {
		t.Fatalf("line=%q", line)
	}
	if !strings.Contains(line, "peers 8") || !strings.Contains(line, "42.1%") {
		t.Fatalf("line=%q", line)
	}
	if strings.Contains(line, "java-tron") || strings.Contains(line, "IBD=true") {
		t.Fatalf("must be human bitcoin copy, got %q", line)
	}
}

func TestFormatBitcoinSyncLogLineSynced(t *testing.T) {
	line := formatBitcoinSyncLogLine(bitcoinChainInfo{
		OK: true, IBD: false, Blocks: 800000, Peers: 12, Chain: "main",
	}, time.Date(2026, 8, 9, 12, 0, 0, 0, time.UTC), "mainnet")
	if !strings.Contains(line, "synced") || !strings.Contains(line, "height 800000") {
		t.Fatalf("line=%q", line)
	}
}

func TestFormatBitcoinSyncLogLineRegtestNoIBD(t *testing.T) {
	line := formatBitcoinSyncLogLine(bitcoinChainInfo{
		OK: true, IBD: true, Blocks: 0, Headers: 0, Verify: 0, Peers: 0,
	}, time.Date(2026, 8, 10, 8, 0, 0, 0, time.UTC), "regtest")
	if strings.Contains(line, "IBD") {
		t.Fatalf("regtest must not say IBD: %q", line)
	}
	if !strings.Contains(line, "regtest") || !strings.Contains(line, "blocks 0") {
		t.Fatalf("line=%q", line)
	}
}

func TestBitcoinRunStepRegtestNoIBD(t *testing.T) {
	step := buildRunStep(nodeLifecycleInput{
		Network: "bitcoin", Env: "regtest",
		RPCOK: true, IBD: true, Height: int64(0), Headers: int64(0), Peers: 0,
	})
	title, _ := step["title"].(string)
	detail, _ := step["detail"].(string)
	if title == "IBD / sync" || strings.Contains(detail, "IBD") {
		t.Fatalf("regtest run must not use IBD copy: title=%q detail=%q", title, detail)
	}
	if step["status"] != "done" {
		t.Fatalf("status=%v want done", step["status"])
	}
	if !strings.Contains(detail, "Regtest") || !strings.Contains(detail, "blocks 0") {
		t.Fatalf("detail=%q", detail)
	}
}

func TestMaybeAppendBitcoinSyncLogCadence(t *testing.T) {
	dir := t.TempDir()
	cfg := Config{StateFile: filepath.Join(dir, "agent-state.json")}
	bitcoinSyncLog.mu.Lock()
	bitcoinSyncLog.lastLine = ""
	bitcoinSyncLog.lastBlocks = 0
	bitcoinSyncLog.lastHeaders = 0
	bitcoinSyncLog.lastWrite = time.Time{}
	bitcoinSyncLog.mu.Unlock()

	chain := bitcoinChainInfo{OK: true, IBD: true, Blocks: 10, Headers: 100, Verify: 0.01, Peers: 3}
	maybeAppendBitcoinSyncLog(cfg, chain)
	maybeAppendBitcoinSyncLog(cfg, chain) // same progress, no gap — skip
	tail := bitcoinSyncLogTail(cfg, 10)
	if len(tail) != 1 {
		t.Fatalf("want 1 line after duplicate tick, got %d %v", len(tail), tail)
	}

	// advance blocks → emit
	chain.Blocks = 60
	maybeAppendBitcoinSyncLog(cfg, chain)
	tail = bitcoinSyncLogTail(cfg, 10)
	if len(tail) != 2 {
		t.Fatalf("want 2 lines after blocks jump, got %d %v", len(tail), tail)
	}

	// catch up → one synced line
	chain.IBD = false
	chain.Blocks = 100
	chain.Headers = 100
	chain.Verify = 1
	maybeAppendBitcoinSyncLog(cfg, chain)
	maybeAppendBitcoinSyncLog(cfg, chain) // quiet
	tail = bitcoinSyncLogTail(cfg, 10)
	if len(tail) != 3 {
		t.Fatalf("want 3 lines (incl synced once), got %d %v", len(tail), tail)
	}
	if !strings.Contains(tail[len(tail)-1], "synced") {
		t.Fatalf("last=%q", tail[len(tail)-1])
	}
	_ = os.Remove(bitcoinSyncLogPath(cfg))
}

func TestBitcoinRunStepDetailIncludesPeers(t *testing.T) {
	step := buildRunStep(nodeLifecycleInput{
		Network: "bitcoin", Env: "mainnet",
		RPCOK: true, IBD: true, Height: int64(100), Headers: int64(200),
		VerifyPct: 0.42, Peers: 7, SizeOnDisk: 5 * 1024 * 1024 * 1024,
	})
	detail, _ := step["detail"].(string)
	if !strings.Contains(detail, "peers 7") || !strings.Contains(detail, "GiB") {
		t.Fatalf("detail=%q", detail)
	}
}
