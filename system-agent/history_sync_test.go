package main

import "testing"

func TestHistoryWindowCaughtUp(t *testing.T) {
	if historyWindowCaughtUp(106326475, 106333417, 106333417, 32570, 16) {
		t.Fatal("recent window is not full history")
	}
	if !historyWindowCaughtUp(32570, 106333417, 106333417, 32570, 16) {
		t.Fatal("genesis + tip")
	}
	if historyWindowCaughtUp(1, 100, 1000, 1, 32) {
		t.Fatal("high far from tip")
	}
	if !historyWindowCaughtUp(1, 100, 100, 1, 32) {
		t.Fatal("stellar-style genesis 1")
	}
}

func TestHistoryWindowPct(t *testing.T) {
	got := historyWindowPct(true, false, 106326475, 106333417, 106333417, 32570)
	if got < 0.006 || got > 0.007 {
		t.Fatalf("xrpl-like backfill start=%v", got)
	}

	got = historyWindowPct(true, false, 106324442, 106341242, 106341242, 32570)
	if got != 0.016 {
		t.Fatalf("tip window want 0.016 got %v", got)
	}

	if formatSyncPct(0.016) != "0.016" || formatSyncPct(12.3) != "12.3" {
		t.Fatalf("formatSyncPct 0.016=%q 12.3=%q", formatSyncPct(0.016), formatSyncPct(12.3))
	}
	if historyWindowPct(true, true, 1, 1000, 1000, 1) != 100 {
		t.Fatal("live + genesis must be 100")
	}
	got = historyWindowPct(false, false, 100, 50_000_000, 100_000_000, 1)
	if got < 49.9 || got > 50.1 {
		t.Fatalf("mid tip catch-up=%v", got)
	}
}

func TestCoreHistoryMissing(t *testing.T) {
	if coreHistoryMissing(bitcoinChainInfo{OK: true, Pruned: true}, false) != true {
		t.Fatal("pruned mainnet is not full history")
	}
	if coreHistoryMissing(bitcoinChainInfo{OK: true, Pruned: true}, true) {
		t.Fatal("regtest prune is local")
	}
	if coreHistoryMissing(bitcoinChainInfo{OK: true, Pruned: false}, false) {
		t.Fatal("unpruned ok")
	}
}
