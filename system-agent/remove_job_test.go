package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestRemoveJobPending(t *testing.T) {
	prev := removeJobsDir
	removeJobsDir = t.TempDir()
	t.Cleanup(func() { removeJobsDir = prev })

	write := func(name, body string) {
		t.Helper()
		if err := os.WriteFile(filepath.Join(removeJobsDir, name), []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	write("xrpl-mainnet.json", `{"network":"xrpl","env":"mainnet","status":"started"}`)
	if !removeJobPending("xrpl", "mainnet") {
		t.Fatal("started job must block pipeline start")
	}
	write("xrpl-mainnet.json", `{"network":"xrpl","env":"mainnet","status":"deleting"}`)
	if !removeJobPending("XRPL", "mainnet") {
		t.Fatal("deleting job must block")
	}
	write("xrpl-mainnet.json", `{"network":"xrpl","env":"mainnet","status":"completed"}`)
	if removeJobPending("xrpl", "mainnet") {
		t.Fatal("completed is not pending")
	}
	write("xrpl-mainnet.json", `{"network":"xrpl","env":"mainnet","status":"superseded"}`)
	if removeJobPending("xrpl", "mainnet") {
		t.Fatal("superseded is not pending")
	}
	if removeJobPending("xrpl", "testnet") {
		t.Fatal("missing job is not pending")
	}
}
