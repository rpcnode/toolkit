package main

import (
	"strings"
	"testing"
)

func TestBitcoinRunStepIBDNotDone(t *testing.T) {
	step := buildRunStep(nodeLifecycleInput{
		Network: "bitcoin", Env: "mainnet",
		RPCOK: true, IBD: true, Height: int64(100), Headers: int64(200), VerifyPct: 0.42,
	})
	if step["status"] != "active" {
		t.Fatalf("IBD must keep run active: %+v", step)
	}
	detail, _ := step["detail"].(string)
	if detail == "" || detail == "Healthy · height 100" {
		t.Fatalf("detail=%q", detail)
	}
}

func TestXRPLRunStepKeepsHistoryThousandths(t *testing.T) {
	step := buildRunStep(nodeLifecycleInput{
		Network: "xrpl", Env: "mainnet",
		RPCOK: true, IBD: true,
		Height: int64(106341483), Headers: int64(106341483),
		VerifyPct: 0.00017, Peers: 38,
	})
	detail, _ := step["detail"].(string)
	if !strings.Contains(detail, "0.017%") {
		t.Fatalf("history %% must stay 0.017, got %q", detail)
	}
	pct, _ := step["pct"].(float64)
	if pct < 0.016 || pct > 0.018 {
		t.Fatalf("run pct=%v want ~0.017", pct)
	}
}

func TestBitcoinRunStepDoneWhenSynced(t *testing.T) {
	step := buildRunStep(nodeLifecycleInput{
		Network: "bitcoin", Env: "mainnet",
		RPCOK: true, IBD: false, Height: int64(200), Headers: int64(200), VerifyPct: 1,
	})
	if step["status"] != "done" {
		t.Fatalf("synced bitcoin must be done: %+v", step)
	}
}
