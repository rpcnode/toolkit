package main

import (
	"os"
	"strings"
	"testing"
)

func TestResolveNodeLogPathsTON(t *testing.T) {
	cfg := Config{
		Network:     "ton",
		Env:         "mainnet",
		NodeService: "ton-mainnet",
		EtcDir:      "/etc/ton/mainnet",
		StateFile:   "/var/lib/rpcnode/ton-mainnet/state.json",
	}
	paths := resolveNodeLogPaths(cfg)
	if len(paths) < 3 {
		t.Fatalf("paths=%v", paths)
	}
	if paths[0] != "/var/log/ton/mainnet/bootstrap.log" {
		t.Fatalf("primary want bootstrap.log got %q", paths[0])
	}
	joined := strings.Join(paths, "\n")
	if strings.Contains(joined, "ton-mainnet.service") {
		t.Fatalf("oneshot wrapper must not be primary/listed: %v", paths)
	}
	if !strings.Contains(joined, "validator.service") {
		t.Fatalf("missing validator journal: %v", paths)
	}
}

func TestResolveNodeLogPathsETC(t *testing.T) {
	cfg := Config{
		Network:     "etc",
		Env:         "mainnet",
		NodeService: "etc-mainnet",
		StateFile:   "/var/lib/rpcnode/etc-mainnet/state.json",
	}
	paths := resolveNodeLogPaths(cfg)
	if len(paths) < 2 {
		t.Fatalf("paths=%v", paths)
	}
	if paths[0] != "journalctl -u etc-mainnet.service" {
		t.Fatalf("primary=%q", paths[0])
	}
	if paths[1] != "/var/lib/rpcnode/etc-mainnet/sync.log" {
		t.Fatalf("sync=%q", paths[1])
	}
}

func TestResolveNodeLogPathsTRON(t *testing.T) {
	cfg := Config{
		Network:     "tron",
		Env:         "nile",
		NodeService: "tron-nile",
		OptDir:      "/opt/tron/nile",
		SnapshotLog: "/var/log/tron/nile-snapshot.log",
		StateFile:   "/var/lib/rpcnode/tron-nile/state.json",
	}
	paths := resolveNodeLogPaths(cfg)
	if len(paths) < 1 || paths[0] != "/opt/tron/nile/logs/tron.log" {
		t.Fatalf("primary want tron.log got %v", paths)
	}
	joined := strings.Join(paths, "\n")
	if !strings.Contains(joined, "tron-nile.service") {
		t.Fatalf("missing journal: %v", paths)
	}
}

func TestFileLogTail(t *testing.T) {
	dir := t.TempDir()
	p := dir + "/tron.log"
	if err := os.WriteFile(p, []byte("one\n\ntwo\nthree\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	got := fileLogTail(p, 2)
	if len(got) != 2 || got[0] != "two" || got[1] != "three" {
		t.Fatalf("%v", got)
	}
}

func TestAttachLogPaths(t *testing.T) {
	cfg := Config{Network: "etc", Env: "mainnet", NodeService: "etc-mainnet.service", StateFile: "/var/lib/rpcnode/etc-mainnet/x"}
	st := attachLogPaths(cfg, map[string]any{
		"logs": map[string]any{"title": "ETC", "lines": []string{"a"}},
	})
	logs := st["logs"].(map[string]any)
	if logs["path"] != "journalctl -u etc-mainnet.service" {
		t.Fatalf("path=%v", logs["path"])
	}
	ps, ok := logs["paths"].([]string)
	if !ok || len(ps) < 1 {
		t.Fatalf("paths=%T %#v", logs["paths"], logs["paths"])
	}
}
