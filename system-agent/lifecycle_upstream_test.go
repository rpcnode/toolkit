package main

import (
	"strings"
	"testing"
)

func TestBuildPortsStepUpstreamMismatchBitcoin(t *testing.T) {
	step := buildPortsStep(nodeLifecycleInput{
		Network:        "bitcoin",
		Env:            "mainnet",
		PublicPort:     39290,
		AgentPort:      39390,
		UpstreamPort:   18090,
		PublicPortOpen: true,
		AgentPortOpen:  true,
		APIUp:          true,
	})
	if step["status"] != "active" {
		t.Fatalf("status=%v want active while upstream mismatches profile", step["status"])
	}
	detail, _ := step["detail"].(string)
	if detail != "Upstream :18090 — point bitcoind at RPC :8332" {
		t.Fatalf("detail=%q", detail)
	}
	if strings.Contains(strings.ToLower(detail), "java-tron") {
		t.Fatalf("must not mention java-tron: %q", detail)
	}
}

func TestBuildInstallStepBitcoinProvisionCopy(t *testing.T) {
	step := buildInstallStep(nodeLifecycleInput{
		Network: "bitcoin",
		Env:     "mainnet",
		APIUp:   false,
	})
	if step["status"] != "active" {
		t.Fatalf("status=%v", step["status"])
	}
	detail, _ := step["detail"].(string)
	if detail != "Provision bitcoind (IBD)" {
		t.Fatalf("detail=%q want Provision bitcoind (IBD)", detail)
	}

	reg := buildInstallStep(nodeLifecycleInput{
		Network: "bitcoin",
		Env:     "regtest",
		APIUp:   false,
	})
	regDetail, _ := reg["detail"].(string)
	if strings.Contains(regDetail, "IBD") || !strings.Contains(regDetail, "regtest") {
		t.Fatalf("regtest install detail=%q", regDetail)
	}
}

func TestBuildStartStepUsesProfileHint(t *testing.T) {
	tron := buildStartStep(nodeLifecycleInput{
		Network:    "tron",
		Env:        "mainnet",
		Marker:     true,
		NodeActive: true,
	}, networkLifecycleProfile{IncludeSnapshot: true, SnapshotRequired: true}, "done")
	if tron["detail"] != "java-tron warming up · waiting for RPC" {
		t.Fatalf("tron detail=%v", tron["detail"])
	}

	btc := buildStartStep(nodeLifecycleInput{
		Network:    "bitcoin",
		Env:        "mainnet",
		NodeActive: true,
	}, networkLifecycleProfile{IncludeSnapshot: false, SnapshotRequired: false}, "")
	if btc["detail"] != "bitcoind warming up · waiting for RPC" {
		t.Fatalf("bitcoin detail=%v", btc["detail"])
	}
}

func TestBuildStartStepErrorNotWarming(t *testing.T) {
	step := buildStartStep(nodeLifecycleInput{
		Network:    "bitcoin",
		Env:        "mainnet",
		NodeActive: false,
		StartError: `bitcoin.conf missing: /etc/bitcoin/mainnet/bitcoin.conf — Error: specified config file "/etc/bitcoin/mainnet/bitcoin.conf" could not be opened.`,
	}, networkLifecycleProfile{IncludeSnapshot: false, SnapshotRequired: false}, "")
	if step["status"] != "error" {
		t.Fatalf("status=%v want error", step["status"])
	}
	detail, _ := step["detail"].(string)
	if !strings.Contains(detail, "could not be opened") {
		t.Fatalf("detail=%q", detail)
	}
}

func TestBuildNodeLifecycleStartErrorPhase(t *testing.T) {
	lc := buildNodeLifecycle(nodeLifecycleInput{
		Network:        "bitcoin",
		Env:            "mainnet",
		PublicPort:     39290,
		AgentPort:      39390,
		PublicPortOpen: true,
		AgentPortOpen:  true,
		APIUp:          true,
		InstRegistered: true,
		StartError:     "bitcoin-mainnet unit failed (Result=exit-code, restarts=188): could not be opened",
	})
	if lc["phase"] != "error" {
		t.Fatalf("phase=%v want error", lc["phase"])
	}
	if lc["node_status"] != "start_error" {
		t.Fatalf("node_status=%v want start_error", lc["node_status"])
	}
	detail, _ := lc["detail"].(string)
	if !strings.Contains(detail, "exit-code") {
		t.Fatalf("detail=%q", detail)
	}
}
