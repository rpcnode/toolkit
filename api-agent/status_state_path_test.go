package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestStateFileForNetworkEnvLeafIgnoresForeignQuery(t *testing.T) {
	dir := t.TempDir()
	state := filepath.Join(dir, "agent-state.json")
	if err := os.WriteFile(state, []byte(`{"ok":true}`), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Setenv("TRON_NETWORK", "bsc")
	t.Setenv("TRON_ENV", "testnet")
	t.Setenv("TRON_STATE_DIR", dir)
	t.Setenv("TRON_AGENT_STATE", state)
	t.Setenv("TRON_PUBLIC_PORT", "39891")
	t.Setenv("TRON_AGENT_PORT", "39991")

	cfg := loadConfig()
	s := &Server{cfg: cfg}

	path, local := s.stateFileForNetworkEnv("tron", "mainnet")
	if path != state {
		t.Fatalf("leaf must ignore foreign tron/mainnet query, got %q", path)
	}
	if !local {
		t.Fatal("leaf view must be local")
	}

	path2, _ := s.stateFileForNetworkEnv("bsc", "testnet")
	if path2 != state {
		t.Fatalf("matched leaf query still own state, got %q", path2)
	}
}

func TestStateFileForNetworkEnvHostTipNeverDefaultsTron(t *testing.T) {
	dir := t.TempDir()
	hostDir := filepath.Join(dir, "host")
	if err := os.MkdirAll(hostDir, 0o755); err != nil {
		t.Fatal(err)
	}
	hostState := filepath.Join(hostDir, "agent-state.json")
	if err := os.WriteFile(hostState, []byte(`{"ok":true,"host_tip":true}`), 0o644); err != nil {
		t.Fatal(err)
	}
	// Stale foreign TRON state next to host tip — must not be selected by env alone.
	tronDir := filepath.Join(dir, "tron-testnet")
	if err := os.MkdirAll(tronDir, 0o755); err != nil {
		t.Fatal(err)
	}
	tronState := filepath.Join(tronDir, "agent-state.json")
	if err := os.WriteFile(tronState, []byte(`{"ok":true,"instance":{"network":"tron"}}`), 0o644); err != nil {
		t.Fatal(err)
	}

	t.Setenv("TRON_NETWORK", "")
	t.Setenv("TRON_ENV", "mainnet")
	t.Setenv("TRON_STATE_DIR", hostDir)
	t.Setenv("TRON_AGENT_STATE", hostState)
	t.Setenv("TRON_PUBLIC_PORT", "39090")
	t.Setenv("TRON_AGENT_PORT", "0")

	cfg := loadConfig()
	s := &Server{cfg: cfg}

	path, local := s.stateFileForNetworkEnv("", "testnet")
	if path != hostState || !local {
		t.Fatalf("env-only query must stay on host tip, got path=%q local=%v", path, local)
	}

	// Without a real /var/lib/rpcnode/bsc-testnet on this host, tip stays on host state.
	path2, local2 := s.stateFileForNetworkEnv("bsc", "testnet")
	if path2 != hostState || !local2 {
		t.Fatalf("missing leaf inventory must not invent tron path, got %q local=%v", path2, local2)
	}
}
