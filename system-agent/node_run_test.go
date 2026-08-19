package main

import (
	"path/filepath"
	"testing"
)

func TestNodeRunSaveLoad(t *testing.T) {
	dir := t.TempDir()
	cfg := Config{Network: "bitcoin", Env: "mainnet", StateFile: filepath.Join(dir, "agent-state.json")}
	if operatorNodeStopped(cfg) {
		t.Fatal("empty file must not be stopped")
	}
	if err := saveNodeRun(cfg, "stopped", "stop"); err != nil {
		t.Fatal(err)
	}
	if !operatorNodeStopped(cfg) {
		t.Fatal("expected stopped")
	}
	got := loadNodeRun(cfg)
	if got.Status != "stopped" || got.Source != "stop" {
		t.Fatalf("got %+v", got)
	}
	if err := saveNodeRun(cfg, "running", "start"); err != nil {
		t.Fatal(err)
	}
	if operatorNodeStopped(cfg) {
		t.Fatal("running must not be stopped")
	}
}
