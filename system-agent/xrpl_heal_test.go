package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestHealXRPLCfgFileDropsHugeAndOnlineDelete(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "xrpld.cfg")
	src := `# managed
[node_size]
huge

[node_db]
type=NuDB
path=/data/xrpl/mainnet/db/nudb
online_delete=512
advisory_delete=0

[ledger_history]
256
`
	if err := os.WriteFile(path, []byte(src), 0o644); err != nil {
		t.Fatal(err)
	}
	changed, err := healXRPLCfgFile(path, "mainnet", false)
	if err != nil {
		t.Fatal(err)
	}
	if !changed {
		t.Fatal("expected cfg heal")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	body := string(b)
	if !strings.Contains(body, "online_delete=256") {
		t.Fatalf("windowed cfg must keep matching online_delete:\n%s", body)
	}
	if !strings.Contains(body, "[ledger_history]\n256") {
		t.Fatalf("heal must not force full when cfg is 256:\n%s", body)
	}
	if !strings.Contains(body, "[node_size]\nmedium") {
		t.Fatalf("empty ledger must bootstrap medium:\n%s", body)
	}
	if !strings.Contains(body, "r.ripple.com 51235") {
		t.Fatalf("want mainnet ips:\n%s", body)
	}
	if !strings.Contains(body, "s2.ripple.com 51235") {
		t.Fatalf("want s2 history peer:\n%s", body)
	}
	if !strings.Contains(body, "[peers_max]\n100") {
		t.Fatalf("want peers_max=100:\n%s", body)
	}
	if !strings.Contains(body, "[fetch_depth]\nfull") {
		t.Fatalf("want fetch_depth=full:\n%s", body)
	}
	changed, err = healXRPLCfgFile(path, "mainnet", false)
	if err != nil {
		t.Fatal(err)
	}
	if changed {
		t.Fatal("second heal must be a no-op")
	}
	changed, err = healXRPLCfgFile(path, "mainnet", true)
	if err != nil {
		t.Fatal(err)
	}
	want := xrplNodeSizeForRAMGiB(float64(ramGB()))
	if want != "medium" && !changed {
		t.Fatal("after first ledger must promote node_size from medium")
	}
}

func TestHealXRPLCfgFileHonorsHistoryJSON(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "xrpld.cfg")
	src := `[node_size]
medium

[node_db]
type=NuDB
path=/data/xrpl/mainnet/db/nudb
advisory_delete=0

[ledger_history]
full
`
	if err := os.WriteFile(path, []byte(src), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := writeXRPLHistoryPolicy(dir, parseXRPLHistoryMode("weeks")); err != nil {
		t.Fatal(err)
	}
	changed, err := healXRPLCfgFile(path, "mainnet", true)
	if err != nil {
		t.Fatal(err)
	}
	if !changed {
		t.Fatal("expected history.json weeks to rewrite cfg")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	body := string(b)
	if !strings.Contains(body, "[ledger_history]\n300000") {
		t.Fatalf("want weeks window:\n%s", body)
	}
	if !strings.Contains(body, "online_delete=300000") {
		t.Fatalf("want online_delete=300000:\n%s", body)
	}
}

func TestXRPLStatusHasLedger(t *testing.T) {
	if xrplStatusHasLedger(map[string]any{"rpc": map[string]any{"ledger_seq": float64(0), "complete_ledgers": "empty"}}) {
		t.Fatal("seq=0 empty is not a ledger")
	}
	if !xrplStatusHasLedger(map[string]any{"rpc": map[string]any{"ledger_seq": float64(91000000)}}) {
		t.Fatal("seq>0 is a ledger")
	}
}

func TestXRPLReinitStaleNuDBOnce(t *testing.T) {
	data := t.TempDir()
	nudb := filepath.Join(data, "db", "nudb")
	if err := os.MkdirAll(nudb, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(nudb, "nudb.dat"), []byte("partial"), 0o644); err != nil {
		t.Fatal(err)
	}
	ok, err := xrplReinitStaleNuDB(data)
	if err != nil || !ok {
		t.Fatalf("first reinit: ok=%v err=%v", ok, err)
	}
	if _, err := os.Stat(filepath.Join(data, "db", "nudb.stale", "nudb.dat")); err != nil {
		t.Fatal("want stale copy")
	}
	ok, err = xrplReinitStaleNuDB(data)
	if err != nil || ok {
		t.Fatalf("second reinit must no-op: ok=%v err=%v", ok, err)
	}
}
