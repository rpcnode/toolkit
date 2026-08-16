package main

import "testing"

func TestParseCoreHeaderSyncPct(t *testing.T) {
	lines := []string{
		"2026-08-11T20:58:23Z Synchronizing blockheaders, height: 2601999 (~82.44%)",
		"2026-08-11T20:58:24Z Synchronizing blockheaders, height: 2607999 (~82.62%)",
	}
	got := parseCoreHeaderSyncPct(lines)
	if got != 82.62 {
		t.Fatalf("got %v want 82.62", got)
	}
}

func TestCoreHonestIBDPctHeaderSync(t *testing.T) {
	// Header sync: verify≈0, blocks=0 — use daemon header %.
	got := coreHonestIBDPct(0, 2_600_000, 0.0001, 82.62)
	if got != 82.6 {
		t.Fatalf("got %v want 82.6", got)
	}
}

func TestCoreHonestIBDPctBlocksFloor(t *testing.T) {
	// Early block download: verify tiny, blocks/headers ~1%.
	got := coreHonestIBDPct(33_417, 3_158_306, 0.00028, 0)
	if got < 1.0 || got > 1.2 {
		t.Fatalf("got %v want ~1.1 (blocks/headers)", got)
	}
}

func TestCoreHonestIBDPctNative(t *testing.T) {
	got := coreHonestIBDPct(1_000_000, 3_000_000, 0.42, 90)
	if got != 42.0 {
		t.Fatalf("got %v want 42 (native verify once meaningful)", got)
	}
}
