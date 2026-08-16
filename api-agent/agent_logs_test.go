package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestAgentLogStreamLabel(t *testing.T) {
	cases := map[string]string{
		"rpcnode-api-agent":                    "Tip api-agent",
		"rpcnode-system-agent.service":         "Tip system-agent",
		"rpcnode-agent-watchdog":               "Watchdog",
		"rpcnode-api-agent-tron-mainnet":       "Leaf api-agent · tron/mainnet",
		"rpcnode-system-agent-bitcoin-mainnet": "Leaf system-agent · bitcoin/mainnet",
		"rpcnode-api-agent-stellar-testnet":    "Leaf api-agent · stellar/testnet",
	}
	for in, want := range cases {
		if got := agentLogStreamLabel(in); got != want {
			t.Fatalf("%s: got %q want %q", in, got, want)
		}
	}
}

func TestClampAgentLogLines(t *testing.T) {
	if clampAgentLogLines(0) != 200 || clampAgentLogLines(10) != 50 || clampAgentLogLines(9999) != 500 {
		t.Fatalf("clamp bounds")
	}
	if clampAgentLogLines(120) != 120 {
		t.Fatalf("passthrough")
	}
}

func TestCollectHostAuditLogStream(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "rpcnode.log")
	t.Setenv("RPCNODE_HOST_LOG", path)

	empty := collectHostAuditLogStream(50)
	if empty.ID != "host" || empty.Source != "empty" || empty.Path != path {
		t.Fatalf("empty host stream: %+v", empty)
	}

	body := "line-a\nline-b\nline-c\n"
	if err := os.WriteFile(path, []byte(body), 0o640); err != nil {
		t.Fatal(err)
	}
	got := collectHostAuditLogStream(2)
	if got.Source != "file" || len(got.Lines) != 2 || got.Lines[0] != "line-b" || got.Lines[1] != "line-c" {
		t.Fatalf("tailed host stream: %+v", got)
	}

	onlyHost := collectAgentLogStreams("host", 50)
	if len(onlyHost) != 1 || onlyHost[0].ID != "host" {
		t.Fatalf("unit=host: %+v", onlyHost)
	}
}

func TestListProvisionedTronEnvsFrom(t *testing.T) {
	root := t.TempDir()
	systemd := filepath.Join(root, "systemd")
	nodes := filepath.Join(root, "nodes")
	opt := filepath.Join(root, "opt")
	_ = os.MkdirAll(systemd, 0o755)
	_ = os.MkdirAll(nodes, 0o755)
	_ = os.MkdirAll(filepath.Join(opt, "nile", "logs"), 0o755)
	_ = os.WriteFile(filepath.Join(systemd, "tron-mainnet.service"), []byte("[Unit]\n"), 0o644)
	_ = os.WriteFile(filepath.Join(systemd, "tron-nile-snapshot.service"), []byte("[Unit]\n"), 0o644)
	_ = os.WriteFile(filepath.Join(nodes, "tron-shasta.json"), []byte("{}"), 0o644)
	_ = os.WriteFile(filepath.Join(opt, "nile", "logs", "tron.log"), []byte("hello nile\n"), 0o644)

	got := listProvisionedTronEnvsFrom(systemd, nodes, opt)
	want := map[string]bool{"mainnet": true, "nile": true, "shasta": true}
	if len(got) != 3 {
		t.Fatalf("envs=%v", got)
	}
	for _, e := range got {
		if !want[e] {
			t.Fatalf("unexpected env %q in %v", e, got)
		}
	}
}

func TestCollectFileLogStreamTron(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "tron.log")
	if err := os.WriteFile(path, []byte("a\nb\nc\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	st := collectFileLogStream("tron-nile", "TRON · nile", path, 2)
	if st.ID != "tron-nile" || st.Source != "file" || len(st.Lines) != 2 || st.Lines[0] != "b" {
		t.Fatalf("%+v", st)
	}
}
