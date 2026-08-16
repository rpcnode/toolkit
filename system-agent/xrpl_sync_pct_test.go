package main

import "testing"

func TestParseXRPLCompleteLedgers(t *testing.T) {
	lo, hi := parseXRPLCompleteLedgers("106326475-106333417")
	if lo != 106326475 || hi != 106333417 {
		t.Fatalf("range lo=%d hi=%d", lo, hi)
	}
	lo, hi = parseXRPLCompleteLedgers("empty")
	if lo != 0 || hi != 0 {
		t.Fatalf("empty lo=%d hi=%d", lo, hi)
	}
	lo, hi = parseXRPLCompleteLedgers("1-100,200-300")
	if lo != 1 || hi != 300 {
		t.Fatalf("multi lo=%d hi=%d", lo, hi)
	}
}

func TestXRPLHistoryCaughtUp(t *testing.T) {
	if xrplHistoryCaughtUp("mainnet", 106326475, 106333417, 106333417) {
		t.Fatal("recent window is not full history")
	}
	if !xrplHistoryCaughtUp("mainnet", 32570, 106333417, 106333417) {
		t.Fatal("genesis + tip must count as caught up")
	}
	if xrplHistoryCaughtUp("mainnet", 32570, 100, 106333417) {
		t.Fatal("complete high far from tip")
	}
	if !xrplHistoryCaughtUp("testnet", 1, 100, 100) {
		t.Fatal("testnet genesis is ledger 1")
	}
}

func TestXRPLVerificationPct(t *testing.T) {
	genesis := int64(32570)
	// server_state=full + recent window only — Syncing, not 100.
	got := xrplVerificationPct(true, false, 106326475, 106333417, 106333417, genesis, 0)
	if got < 0.006 || got > 0.007 {
		t.Fatalf("history backfill start=%v", got)
	}
	if xrplVerificationPct(true, true, 32570, 106333417, 106333417, genesis, 0) != 100 {
		t.Fatal("live + genesis coverage must be 100")
	}
	got = xrplVerificationPct(false, false, 100, 50_000_000, 100_000_000, genesis, 0)
	if got < 49.9 || got > 50.1 {
		t.Fatalf("mid tip catch-up=%v", got)
	}
	if got := xrplVerificationPct(false, false, 0, 0, 0, genesis, 0); got != 0 {
		t.Fatalf("empty=%v", got)
	}
}

func TestXRPLHistoryWindowWeeks(t *testing.T) {
	weeks := parseXRPLHistoryMode("weeks")
	lo := int64(106333417 - 300000 + 1)
	if !xrplHistoryOK("mainnet", lo, 106333417, 106333417, weeks) {
		t.Fatal("300k window at tip is weeks-complete")
	}
	if xrplHistoryOK("mainnet", 106326475, 106333417, 106333417, weeks) {
		t.Fatal("7k window is not 300k")
	}

	got := xrplVerificationPct(true, false, 106326475, 106333417, 106333417, 32570, 300000)
	if got < 2.3 || got > 2.4 {
		t.Fatalf("weeks fill %% = %v", got)
	}
	if xrplVerificationPct(true, true, lo, 106333417, 106333417, 32570, 300000) != 100 {
		t.Fatal("weeks + tip must be 100")
	}
}
