package main

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestStellarSyncProgress(t *testing.T) {
	pct, synced := stellarSyncProgress(true, 1, 1000, 1000)
	if !synced || pct != 1 {
		t.Fatalf("genesis + tip: pct=%v synced=%v", pct, synced)
	}
	pct, synced = stellarSyncProgress(true, 900, 1000, 1000)
	if synced || pct >= 1 {
		t.Fatalf("live tip + recent window must not be Synced: pct=%v synced=%v", pct, synced)
	}
	pct, synced = stellarSyncProgress(true, 1, 500, 1000)
	if synced || pct <= 0 || pct >= 1 {
		t.Fatalf("behind tip: pct=%v synced=%v", pct, synced)
	}
	pct, synced = stellarSyncProgress(true, 500, 500, 0)
	if synced {
		t.Fatalf("healthy no tip but oldest>genesis must not be Synced: pct=%v", pct)
	}
}

func TestStellarJSONRPCOmitsEmptyParams(t *testing.T) {
	raw, err := json.Marshal(stellarJSONRPCBody("getHealth", nil))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), `"params"`) {
		t.Fatalf("nil params must be omitted: %s", raw)
	}
	raw2, _ := json.Marshal(stellarJSONRPCBody("getHealth", map[string]any{}))
	if strings.Contains(string(raw2), `"params"`) {
		t.Fatalf("empty map params must be omitted: %s", raw2)
	}
}

func TestStellarRPCErrorString(t *testing.T) {
	got := stellarRPCErrorString(map[string]any{"code": float64(-32602), "message": "no parameters accepted"})
	if got != "-32602: no parameters accepted" {
		t.Fatalf("got %q", got)
	}
}

func TestJournalHintUnitPathOnly(t *testing.T) {
	if !journalHintIsUnitPathOnly("unit=/etc/systemd/system/stellar-testnet.service") {
		t.Fatal("expected unit-path-only")
	}
	if stripUnitPathNoise("unit=/etc/systemd/system/stellar-testnet.service") != "" {
		t.Fatal("strip must clear bare unit path")
	}
	if stripUnitPathNoise("boom — unit=/etc/systemd/system/stellar-testnet.service") != "boom" {
		t.Fatal("strip must keep real error head")
	}
}

func TestStellarStartFailureInactiveNotError(t *testing.T) {
	// Fresh unit after Confirm ports — never started.
	detail, bad := stellarStartFailureFromProbe(systemdUnitProbe{
		ActiveState: "inactive", Result: "", NRestarts: 0,
	}, "unit=/etc/systemd/system/stellar-testnet.service")
	if bad || detail != "" {
		t.Fatalf("inactive must not be start_error: bad=%v detail=%q", bad, detail)
	}
	_, bad = stellarStartFailureFromProbe(systemdUnitProbe{ActiveState: "dead"}, "")
	if bad {
		t.Fatal("dead (never started) must not be start_error")
	}
	_, bad = stellarStartFailureFromProbe(systemdUnitProbe{ActiveState: "activating"}, "")
	if bad {
		t.Fatal("clean activating must not be start_error")
	}
	detail, bad = stellarStartFailureFromProbe(systemdUnitProbe{
		ActiveState: "failed", Failed: true, Result: "exit-code", NRestarts: 2,
	}, "boom")
	if !bad || detail != "boom" {
		t.Fatalf("failed unit: bad=%v detail=%q", bad, detail)
	}
}

func TestLookupStellarPortsMatch(t *testing.T) {
	// Port tables live in api-agent; here we only assert profile defaults.
	p := LookupNetworkProfile("stellar", "testnet")
	if p.DefaultPublicPort != 40891 || p.DefaultNodeHTTP != 8001 {
		t.Fatalf("unexpected stellar/testnet ports: pub=%d http=%d", p.DefaultPublicPort, p.DefaultNodeHTTP)
	}
	p2 := LookupNetworkProfile("stellar", "futurenet")
	if p2.DefaultPublicPort != 40892 {
		t.Fatalf("unexpected stellar/futurenet public=%d", p2.DefaultPublicPort)
	}
	caps := LifecycleCapabilitiesFor("stellar", "mainnet")
	if caps["snapshot"] || !caps["ibd"] {
		t.Fatalf("stellar caps: %#v", caps)
	}
}
