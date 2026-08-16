package main

import "testing"

// Regression: etc used to pass ethSyncVerificationPct (0..100) into lifecycle VerifyPct
// (0..1). That made ~6.7% IBD display as lifecycle.pct=100 while still syncing.
func TestETCVerifyPctLifecycleScale(t *testing.T) {
	uiPct := ethSyncVerificationPct(1_676_040, 25_131_147, true)
	if uiPct < 6.5 || uiPct > 7.0 {
		t.Fatalf("honest UI pct=%v want ~6.7", uiPct)
	}

	step := buildRunStep(nodeLifecycleInput{
		Network: "etc", Env: "mainnet",
		RPCOK: true, IBD: true,
		Height: 1_676_040, Headers: 25_131_147,
		VerifyPct: uiPct / 100,
	})
	if step["status"] != "active" {
		t.Fatalf("IBD must keep run active: %+v", step)
	}
	got, _ := step["pct"].(float64)
	if got < 6.5 || got > 7.0 {
		t.Fatalf("lifecycle pct=%v want ~6.7 (not clamped 100)", got)
	}

	bad := buildRunStep(nodeLifecycleInput{
		Network: "etc", Env: "mainnet",
		RPCOK: true, IBD: true,
		Height: 1_676_040, Headers: 25_131_147,
		VerifyPct: uiPct, // wrong scale — reproduces pre-fix clamp-to-100
	})
	badPct, _ := bad["pct"].(float64)
	if badPct != 100 {
		t.Fatalf("wrong-scale fixture should clamp to 100, got %v", badPct)
	}
}

// Mordor: eth_syncing=false leaves CurrentBlock=0; Run must still complete (not hn<1000).
func TestETCRunStepDoneWhenSyncedHeight0(t *testing.T) {
	step := buildRunStep(nodeLifecycleInput{
		Network: "etc", Env: "mordor",
		RPCOK: true, IBD: false,
		Height: int64(0), VerifyPct: 1,
	})
	if step["status"] != "done" {
		t.Fatalf("synced etc (height 0 from eth_syncing=false) must complete Run: %+v", step)
	}
}

func TestETCRunStepDoneWithBlockHeight(t *testing.T) {
	step := buildRunStep(nodeLifecycleInput{
		Network: "etc", Env: "mordor",
		RPCOK: true, IBD: false,
		Height: int64(16_758_444), VerifyPct: 1,
	})
	if step["status"] != "done" {
		t.Fatalf("synced etc must complete Run: %+v", step)
	}
}
