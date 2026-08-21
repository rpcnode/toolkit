package main

import "testing"

func TestRobinhoodSnapshotRequired(t *testing.T) {
	mn := LookupNetworkProfile("robinhood", "mainnet")
	if mn.SnapshotPolicy != SnapshotRequired {
		t.Fatalf("mainnet SnapshotPolicy=%v want SnapshotRequired", mn.SnapshotPolicy)
	}
	if !mn.HasExtra(StepSnapshot) {
		t.Fatal("mainnet must list StepSnapshot in ExtraSteps")
	}
	if mn.DefaultSnapshotURL == "" {
		t.Fatal("mainnet DefaultSnapshotURL must be set")
	}
	caps := mn.LifecycleCapabilities()
	if !caps["snapshot"] || !caps["ibd"] {
		t.Fatalf("mainnet caps=%v want snapshot+ibd", caps)
	}
	tn := LookupNetworkProfile("robinhood", "testnet")
	if tn.SnapshotPolicy != SnapshotRequired || !tn.HasExtra(StepSnapshot) {
		t.Fatalf("testnet must require snapshot: policy=%v extras=%v", tn.SnapshotPolicy, tn.ExtraSteps)
	}
}

// Stale toolkit.env TRON_SNAPSHOT_ENABLED=0 must not drop robinhood Snapshot step / %.
func TestRobinhoodSnapshotIgnoresExplicitDisable(t *testing.T) {
	t.Setenv("TRON_SNAPSHOT_ENABLED", "0")
	p := resolveLifecycleProfile(nodeLifecycleInput{
		Network: "robinhood", Env: "testnet",
	})
	if !p.IncludeSnapshot || !p.SnapshotRequired || !p.AutoSnapshot {
		t.Fatalf("robinhood profile with ENABLED=0: %+v want Include+Required+Auto", p)
	}
}

func TestBSCSnapshotIgnoresExplicitDisable(t *testing.T) {
	t.Setenv("TRON_SNAPSHOT_ENABLED", "0")
	p := resolveLifecycleProfile(nodeLifecycleInput{
		Network: "bsc", Env: "mainnet",
	})
	if !p.IncludeSnapshot || !p.SnapshotRequired || !p.AutoSnapshot {
		t.Fatalf("bsc profile with ENABLED=0: %+v want Include+Required+Auto", p)
	}
}
