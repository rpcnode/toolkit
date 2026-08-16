package main

import (
	"strings"
	"testing"
)

func TestParseHLJournalProgressApplied(t *testing.T) {
	lines := []string{
		"noise",
		"2026-08-10T21:48:21.829Z WARN >>> hl-node @@ applied block 647726000",
		"2026-08-10T21:48:28.807Z WARN >>> hl-node @@ applied block 647726100",
	}
	got := parseHLJournalProgress(lines)
	if got.AppliedBlock != 647726100 {
		t.Fatalf("applied=%d want 647726100", got.AppliedBlock)
	}
	if !strings.Contains(got.Detail, "647726100") {
		t.Fatalf("detail=%q", got.Detail)
	}
}

func TestParseHLJournalProgressBootstrap(t *testing.T) {
	lines := []string{
		"2025-01-02T15:01:13.380Z WARN >>> hl-node @@ finished bootstrap",
		"2025-01-02T15:01:14.000Z WARN >>> hl-node @@ applied block 100",
	}
	got := parseHLJournalProgress(lines)
	if !got.FinishedBootstrap || got.AppliedBlock != 100 {
		t.Fatalf("got %+v", got)
	}
}

func TestParseHLJournalPeers(t *testing.T) {
	lines := []string{
		`WARN >>> hl-node @@ connection established @@ [node_ip: Ip(65.21.82.126)] @ [connection_type: Gossip]`,
		`WARN >>> hl-node @@ connection established @@ [node_ip: Ip(72.46.86.39)] @ [connection_type: Gossip]`,
		`WARN >>> hl-node @@ connection established @@ [node_ip: Ip(65.21.82.126)] @ [connection_type: Gossip]`,
	}
	got := parseHLJournalProgress(lines)
	if got.Peers != 2 {
		t.Fatalf("peers=%d want 2", got.Peers)
	}
}

func TestHLVerificationPctEthSyncing(t *testing.T) {
	rpc := ethereumRPCResult{OK: true, Syncing: true, CurrentBlock: 50, HighestBlock: 100, Block: 50}
	pct := hlVerificationPct(rpc, true, hlJournalProgress{}, 0, 0)
	if pct != 50 {
		t.Fatalf("pct=%v want 50", pct)
	}
}

func TestHLVerificationPctL1Lag(t *testing.T) {
	rpc := ethereumRPCResult{OK: true, Syncing: false, Block: 61_000_000}
	// Explorer tip ahead of applied — must NOT be 100 even if EVM looks synced.
	pct := hlVerificationPct(rpc, true, hlJournalProgress{AppliedBlock: 647_741_800}, 61_000_000, 647_759_862)
	if pct >= 100 {
		t.Fatalf("pct=%v want <100 while L1 lagging", pct)
	}
	if pct < 99 {
		t.Fatalf("pct=%v want ~99.997", pct)
	}
}

func TestHLVerificationPctL1CaughtUp(t *testing.T) {
	rpc := ethereumRPCResult{OK: true, Syncing: false, Block: 61_227_000}
	pct := hlVerificationPct(rpc, true, hlJournalProgress{AppliedBlock: 647_759_800}, 61_227_000, 647_759_862)
	if pct != 100 {
		t.Fatalf("pct=%v want 100", pct)
	}
}

func TestHLVerificationPctNoFake100WithoutTip(t *testing.T) {
	rpc := ethereumRPCResult{OK: true, Syncing: false, Block: 1000}
	pct := hlVerificationPct(rpc, true, hlJournalProgress{}, 0, 0)
	if pct >= 100 {
		t.Fatalf("pct=%v must not be 100 without public tip", pct)
	}
}

func TestHLVerificationPctBootstrap(t *testing.T) {
	pct := hlVerificationPct(ethereumRPCResult{}, false, hlJournalProgress{AppliedBlock: 1e6}, 0, 0)
	if pct != 35 {
		t.Fatalf("pct=%v want 35", pct)
	}
	pct = hlVerificationPct(ethereumRPCResult{}, false, hlJournalProgress{FinishedBootstrap: true}, 0, 0)
	if pct != 92 {
		t.Fatalf("pct=%v want 92", pct)
	}
}
