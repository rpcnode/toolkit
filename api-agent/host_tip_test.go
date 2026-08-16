package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestIsHostTipStateDir(t *testing.T) {
	if !isHostTipStateDir("/var/lib/rpcnode/host") {
		t.Fatal("expected host tip dir")
	}
	if !isHostTipStateDir("/var/lib/rpcnode/host/") {
		t.Fatal("trailing slash should match")
	}
	if isHostTipStateDir("/var/lib/rpcnode/tron-mainnet") {
		t.Fatal("tron state must not be host tip")
	}
}

func TestLoadConfigHostTipHonorsStateDir(t *testing.T) {
	t.Setenv("TRON_NETWORK", "")
	t.Setenv("TRON_ENV", "mainnet")
	t.Setenv("TRON_STATE_DIR", "/var/lib/rpcnode/host")
	t.Setenv("TRON_PUBLIC_PORT", "39090")
	t.Setenv("TRON_AGENT_PORT", "0")
	t.Setenv("TRON_NODE_HTTP_PORT", "")
	os.Unsetenv("TRON_AGENT_STATE")
	os.Unsetenv("TRON_INSTANCE_FILE")

	cfg := loadConfig()
	wantState := filepath.Join("/var/lib/rpcnode/host", "agent-state.json")
	if cfg.StateFile != wantState {
		t.Fatalf("StateFile=%q want %q", cfg.StateFile, wantState)
	}
	if cfg.UpstreamPort != 0 {
		t.Fatalf("host tip UpstreamPort=%d want 0 (no fake :18090)", cfg.UpstreamPort)
	}
}

func TestAgentIdentityHostTipClean(t *testing.T) {
	t.Setenv("TRON_NETWORK", "")
	t.Setenv("TRON_ENV", "mainnet")
	t.Setenv("TRON_STATE_DIR", "/var/lib/rpcnode/host")
	t.Setenv("TRON_PUBLIC_PORT", "39090")
	t.Setenv("TRON_AGENT_PORT", "0")

	cfg := loadConfig()
	s := &Server{cfg: cfg}
	out := s.agentIdentity(true)
	if out["host_tip"] != true {
		t.Fatalf("want host_tip=true: %#v", out)
	}
	if out["node_status"] != "host" {
		t.Fatalf("node_status=%v", out["node_status"])
	}
	if _, has := out["lifecycle"]; has {
		t.Fatalf("host tip must not expose lifecycle: %#v", out["lifecycle"])
	}
	if _, has := out["upstream"]; has {
		t.Fatalf("host tip must not expose upstream: %#v", out["upstream"])
	}
	if _, has := out["network"]; has {
		t.Fatalf("host tip must not set network: %#v", out["network"])
	}
}
