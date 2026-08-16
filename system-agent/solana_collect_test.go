package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestSolanaDownloadPctFromDetail(t *testing.T) {
	line := `[2026-08-10T09:42:45Z INFO  solana_file_download] downloaded 6045773488 bytes 5.5% 17854978.0 bytes/s`
	if p := solanaDownloadPctFromDetail(line); p < 5.4 || p > 5.6 {
		t.Fatalf("download line pct=%v", p)
	}
	if p := solanaDownloadPctFromDetail("snapshot download 42.0% · 1.0/2 GiB"); p != 42 {
		t.Fatalf("warmup detail pct=%v", p)
	}
	if p := solanaDownloadPctFromDetail("Healthy · slot 1"); p != 0 {
		t.Fatalf("healthy detail should be 0, got %v", p)
	}
}

func TestSolanaVerificationPctHealthy(t *testing.T) {
	cfg := Config{Env: "mainnet", DataDir: t.TempDir()}
	pct, ok := solanaVerificationPct(cfg, solanaRPCResult{}, true, false, false, "")
	if !ok || pct != 100 {
		t.Fatalf("healthy want 100,ok got %v,%v", pct, ok)
	}
	pct, ok = solanaVerificationPct(cfg, solanaRPCResult{}, true, true, false, "snapshot download 12.5%")
	if !ok || pct != 12.5 {
		t.Fatalf("catching with download detail got %v,%v", pct, ok)
	}
	cfg.StateFile = filepath.Join(t.TempDir(), "agent-state.json")
	// Catch-up % = lag closed vs peak behind (not me/tip ~99%).
	rpc := solanaRPCResult{Slot: 750_000, Behind: "Node is behind by 250000 slots"}
	pct, ok = solanaVerificationPct(cfg, rpc, true, true, false, "")
	if !ok || pct > 1.0 {
		t.Fatalf("first catch-up sample want ~0.1 got %v ok=%v", pct, ok)
	}
	rpc.Behind = "Node is behind by 125000 slots"
	pct, ok = solanaVerificationPct(cfg, rpc, true, true, false, "")
	if !ok || pct < 49.9 || pct > 50.1 {
		t.Fatalf("half lag closed want ~50 got %v ok=%v", pct, ok)
	}
}

func TestParseSolanaBehindSlots(t *testing.T) {
	n, ok := parseSolanaBehindSlots("Node is behind by 2851 slots")
	if !ok || n != 2851 {
		t.Fatalf("got %d %v", n, ok)
	}
	if _, ok := parseSolanaBehindSlots("ok"); ok {
		t.Fatal("ok must not parse")
	}
}

func TestParseSolanaMeCluster(t *testing.T) {
	m, c, ok := parseSolanaMeCluster("behind by 2984 slots: me=438621923, latest cluster=438624907")
	if !ok || m != 438621923 || c != 438624907 {
		t.Fatalf("got me=%d cluster=%d ok=%v", m, c, ok)
	}
}

func TestSolanaLedgerSizeBytes(t *testing.T) {
	dir := t.TempDir()
	ledger := filepath.Join(dir, "ledger")
	if err := os.MkdirAll(ledger, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(ledger, "x"), []byte("hello-solana-ledger"), 0o644); err != nil {
		t.Fatal(err)
	}
	cfg := Config{Env: "mainnet", DataDir: dir}
	n := solanaLedgerSizeBytes(cfg)
	if n == 0 {
		t.Skip("du unavailable in this environment")
	}
	if n < 10 {
		t.Fatalf("expected ledger bytes, got %d", n)
	}
}
