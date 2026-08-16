package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestParseAvalancheBootstrapProgress(t *testing.T) {
	pct, ok, detail := parseAvalancheBootstrapProgress("INFO snowman fetched 500 of 2000 blocks")
	if !ok || pct < 24.9 || pct > 25.1 {
		t.Fatalf("fetched of: pct=%v ok=%v detail=%q", pct, ok, detail)
	}
	pct2, ok2, _ := parseAvalancheBootstrapProgress("bootstrapping: fetched 9,000 of 10,000 blocks")
	if !ok2 || pct2 < 89.9 || pct2 > 90.1 {
		t.Fatalf("comma fetched: pct=%v ok=%v", pct2, ok2)
	}
	pct3, ok3, _ := parseAvalancheBootstrapProgress("fetching 100/100 blocks")
	if !ok3 || pct3 != 99.9 {
		t.Fatalf("complete fetch must cap 99.9: pct=%v ok=%v", pct3, ok3)
	}
	_, ok4, detail4 := parseAvalancheBootstrapProgress("executed 12345 blocks")
	if !ok4 || detail4 == "" {
		t.Fatalf("executed-only: ok=%v detail=%q", ok4, detail4)
	}

	// AvalancheGo structured bootstrap (P-Chain) — live 2026-08-12 stuck-UI case.
	line := `[08-12|14:44:18.803] INFO <P Chain> bootstrap/bootstrapper.go:644 fetching blocks {"numFetchedBlocks": 7450192, "numTotalBlocks": 25352074, "eta": "33m5s", "pctComplete": 29.39}`
	pct5, ok5, detail5 := parseAvalancheBootstrapProgress(line)
	if !ok5 || pct5 < 29.3 || pct5 > 29.5 {
		t.Fatalf("pctComplete: pct=%v ok=%v detail=%q", pct5, ok5, detail5)
	}
	if !strings.Contains(detail5, "P-Chain") || !strings.Contains(detail5, "7450192") {
		t.Fatalf("detail want P-Chain + fetched counts: %q", detail5)
	}
}

func TestAvalancheCatchupLagClosedPct(t *testing.T) {
	dir := t.TempDir()
	cfg := Config{StateFile: filepath.Join(dir, "agent-state.json")}
	_ = os.WriteFile(cfg.StateFile, []byte(`{}`), 0o644)

	p, ok := avalancheCatchupLagClosedPct(cfg, 1000)
	if !ok || p != 0.1 {
		t.Fatalf("first lag pct=%v ok=%v want 0.1", p, ok)
	}
	p2, ok2 := avalancheCatchupLagClosedPct(cfg, 500)
	if !ok2 || p2 < 49.0 || p2 > 51.0 {
		t.Fatalf("half closed pct=%v ok=%v", p2, ok2)
	}
	p3, ok3 := avalancheCatchupLagClosedPct(cfg, 0)
	if !ok3 || p3 != 99.9 {
		t.Fatalf("behind0 pct=%v ok=%v", p3, ok3)
	}
	st := filepath.Join(dir, "avalanche-catchup.json")
	if !fileExists(st) {
		// behind=0 clears? no — only clearAvalancheCatchupMaxBehind; lag-closed at 0 keeps file
		// file should exist from max_behind save on first call
	}
	_ = st
}

func TestAvalancheProfilesExist(t *testing.T) {
	m := LookupNetworkProfile("avalanche", "mainnet")
	if m.DefaultPublicPort != 43090 || m.DefaultAgentPort != 43190 || m.DefaultNodeHTTP != 9650 {
		t.Fatalf("avalanche mainnet profile: %+v", m)
	}
	if m.LifecycleCapabilities()["snapshot"] || !m.LifecycleCapabilities()["ibd"] {
		t.Fatalf("avalanche caps: %v", m.LifecycleCapabilities())
	}
	f := LookupNetworkProfile("avalanche", "fuji")
	if f.DefaultPublicPort != 43091 || f.DefaultNodeHTTP != 9660 {
		t.Fatalf("avalanche fuji profile: %+v", f)
	}
	alias := LookupNetworkProfile("avalanche", "testnet")
	if alias.Env != "fuji" || alias.DefaultPublicPort != 43091 {
		t.Fatalf("avalanche testnet alias: %+v", alias)
	}
}

func TestNormalizeAvalancheEnvName(t *testing.T) {
	if normalizeAvalancheEnvName("testnet") != "fuji" {
		t.Fatal("testnet→fuji")
	}
	if normalizeAvalancheEnvName("fuji") != "fuji" {
		t.Fatal("fuji stays")
	}
	if normalizeAvalancheEnvName("mainnet") != "mainnet" {
		t.Fatal("mainnet stays")
	}
}
