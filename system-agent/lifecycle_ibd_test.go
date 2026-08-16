package main

import "testing"

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

func TestBitcoinRunStepDoneWhenSynced(t *testing.T) {
	step := buildRunStep(nodeLifecycleInput{
		Network: "bitcoin", Env: "mainnet",
		RPCOK: true, IBD: false, Height: int64(200), Headers: int64(200), VerifyPct: 1,
	})
	if step["status"] != "done" {
		t.Fatalf("synced bitcoin must be done: %+v", step)
	}
}
