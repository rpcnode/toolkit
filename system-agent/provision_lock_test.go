package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestProvisionLockPending(t *testing.T) {
	prev := provisionLocksDir
	provisionLocksDir = t.TempDir()
	t.Cleanup(func() { provisionLocksDir = prev })

	if provisionLockPending("xrpl", "mainnet") {
		t.Fatal("missing lock is not pending")
	}

	path := filepath.Join(provisionLocksDir, "xrpl-mainnet.json")
	if err := os.WriteFile(path, []byte(`{"status":"running"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if !provisionLockPending("xrpl", "mainnet") {
		t.Fatal("running lock must block start")
	}
	if !provisionLockPending("XRPL", "mainnet") {
		t.Fatal("network case")
	}
	if err := os.WriteFile(path, []byte(`{"status":"done"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if provisionLockPending("xrpl", "mainnet") {
		t.Fatal("done is not pending")
	}
}
