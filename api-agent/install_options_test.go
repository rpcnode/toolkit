package main

import "testing"

func TestInstallOptions_TronMainnetInternalTx(t *testing.T) {
	opts := mergeInstallOptions("tron", "mainnet", map[string]string{"snapshot": "internal_tx"})
	if opts["snapshot"] != "internal_tx" {
		t.Fatalf("opts=%v", opts)
	}
	ch := findInstallChoice("tron", "mainnet", "snapshot", "internal_tx")
	if ch == nil || ch.SnapshotURL != "http://35.247.128.170/" {
		t.Fatalf("choice=%+v", ch)
	}
	if ch.SaveInternalTx == nil || !*ch.SaveInternalTx {
		t.Fatal("internal_tx must enable saveInternalTx")
	}
	url := resolveSnapshotURLForOptions("tron", "mainnet", opts)
	if url != "http://35.247.128.170/" {
		t.Fatalf("url=%q", url)
	}
}

func TestInstallOptions_UnknownFallsBack(t *testing.T) {
	opts := mergeInstallOptions("tron", "mainnet", map[string]string{"snapshot": "nope"})
	if opts["snapshot"] != "standard" {
		t.Fatalf("want standard default, got %v", opts)
	}
}

func TestInstallOptions_NileHasNone(t *testing.T) {
	if len(installOptionGroups("tron", "nile")) != 0 {
		t.Fatal("nile has a single snapshot — no picker")
	}
}

func TestInstallOptions_XRPLWeeksDefault(t *testing.T) {
	opts := mergeInstallOptions("xrpl", "mainnet", nil)
	if opts["xrpl_history"] != "weeks" {
		t.Fatalf("want weeks default, got %v", opts)
	}
	opts = mergeInstallOptions("xrpl", "mainnet", map[string]string{"xrpl_history": "full"})
	if opts["xrpl_history"] != "full" {
		t.Fatalf("want full, got %v", opts)
	}
}
