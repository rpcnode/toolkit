package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestParsePromSampleLabeledAptosSynced(t *testing.T) {
	name, labels, val, ok := parsePromSampleLabeled(`aptos_state_sync_version{type="synced"} 12345`)
	if !ok || name != "aptos_state_sync_version" || val != 12345 || labels["type"] != "synced" {
		t.Fatalf("got name=%q val=%d labels=%v ok=%v", name, val, labels, ok)
	}
	name, labels, val, ok = parsePromSampleLabeled(`aptos_state_sync_version{type="synced_states"} 99`)
	if !ok || labels["type"] != "synced_states" || val != 99 {
		t.Fatalf("synced_states: name=%q labels=%v val=%d", name, labels, val)
	}
}

func TestAptosCatchupLagClosedPctEarlyNear(t *testing.T) {
	dir := t.TempDir()
	cfg := Config{StateFile: filepath.Join(dir, "agent-state.json")}
	_ = os.WriteFile(cfg.StateFile, []byte(`{}`), 0o644)

	// Early: first observation peak=behind → floor 0.1
	p, ok := aptosCatchupLagClosedPct(cfg, 10000)
	if !ok || p != 0.1 {
		t.Fatalf("early lag pct=%v ok=%v want 0.1", p, ok)
	}
	// Near: half closed
	p2, ok2 := aptosCatchupLagClosedPct(cfg, 5000)
	if !ok2 || p2 < 49.0 || p2 > 51.0 {
		t.Fatalf("near lag pct=%v ok=%v", p2, ok2)
	}
}

func TestAptosVerificationPctHealthy(t *testing.T) {
	dir := t.TempDir()
	cfg := Config{StateFile: filepath.Join(dir, "agent-state.json")}
	_ = os.WriteFile(cfg.StateFile, []byte(`{}`), 0o644)
	saveAptosCatchupMaxBehind(cfg, 9000)

	// Healthy threshold: behind ≤ 50 → 100
	p, ok := aptosVerificationPct(cfg, false, true, 50)
	if !ok || p != 100 {
		t.Fatalf("healthy≤50 pct=%v ok=%v want 100", p, ok)
	}
	p2, ok2 := aptosVerificationPct(cfg, true, false, 0)
	if !ok2 || p2 != 100 {
		t.Fatalf("healthy flag pct=%v ok=%v", p2, ok2)
	}
	// Still catching up far behind — lag-closed uses prior peak if any remained; fresh dir after clear.
	dir2 := t.TempDir()
	cfg2 := Config{StateFile: filepath.Join(dir2, "agent-state.json")}
	_ = os.WriteFile(cfg2.StateFile, []byte(`{}`), 0o644)
	p3, ok3 := aptosVerificationPct(cfg2, false, true, 2000)
	if !ok3 || p3 != 0.1 {
		t.Fatalf("catching-up early pct=%v ok=%v want 0.1", p3, ok3)
	}
}

func TestParseAptosLedgerVersion(t *testing.T) {
	n, ok := parseAptosLedgerVersion("123456789")
	if !ok || n != 123456789 {
		t.Fatalf("string: %d ok=%v", n, ok)
	}
	n, ok = parseAptosLedgerVersion(float64(42))
	if !ok || n != 42 {
		t.Fatalf("float: %d ok=%v", n, ok)
	}
}

func TestAptosProfileExists(t *testing.T) {
	a := LookupNetworkProfile("aptos", "mainnet")
	if a.DefaultPublicPort != 42890 || a.DefaultAgentPort != 42990 || a.DefaultNodeHTTP != 8080 || a.DefaultP2PPort != 6180 {
		t.Fatalf("aptos profile: %+v", a)
	}
	if a.LifecycleCapabilities()["snapshot"] || !a.LifecycleCapabilities()["ibd"] {
		t.Fatalf("aptos caps: %v", a.LifecycleCapabilities())
	}
	tn := LookupNetworkProfile("aptos", "testnet")
	if tn.DefaultPublicPort != 42891 || tn.DefaultAgentPort != 42991 || tn.DefaultNodeHTTP != 8081 || tn.DefaultP2PPort != 6182 {
		t.Fatalf("aptos testnet: %+v", tn)
	}
}
