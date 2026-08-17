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
	if strings.Contains(body, "online_delete=") {
		t.Fatalf("empty NuDB must not set online_delete:\n%s", body)
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
	if i := strings.Index(body, "[ips_fixed]"); i < 0 || !strings.Contains(body[i:], "r.ripple.com 51235") {
		t.Fatalf("first ledger needs hub in ips_fixed:\n%s", body)
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

func TestXRPLSystemdLooksLikeAbort(t *testing.T) {
	if !xrplSystemdLooksLikeAbort("core-dump", "0") {
		t.Fatal("core-dump")
	}
	if !xrplSystemdLooksLikeAbort("signal", "6") {
		t.Fatal("SIGABRT")
	}
	if !xrplSystemdLooksLikeAbort("exit-code", "134") {
		t.Fatal("128+ABRT")
	}
	if xrplSystemdLooksLikeAbort("success", "0") {
		t.Fatal("healthy must not look like abort")
	}
	if xrplSystemdLooksLikeAbort("signal", "9") {
		t.Fatal("SIGKILL from recycle must not wipe NuDB")
	}
}

func TestXRPLJournalHasStateDBError(t *testing.T) {
	if !xrplJournalHasStateDBError("terminate called after throwing an instance of 'std::runtime_error'\nwhat():  state db error") {
		t.Fatal("want state db error")
	}
	if !xrplJournalHasStateDBError("SHAMapStore:ERR state db error:\nwritableDbExists false archiveDbExists false") {
		t.Fatal("want SHAMapStore")
	}
	if xrplJournalHasStateDBError("JobQueue:NFO Using 6 threads") {
		t.Fatal("plain journal is not a state db error")
	}
}

func TestXRPLReinitCorruptStateDBIgnoresSize(t *testing.T) {
	data := t.TempDir()
	nudb := filepath.Join(data, "db", "nudb")
	if err := os.MkdirAll(nudb, 0o755); err != nil {
		t.Fatal(err)
	}
	big := make([]byte, 9<<20)
	if err := os.WriteFile(filepath.Join(nudb, "rippledb.6a6f"), big, 0o644); err != nil {
		t.Fatal(err)
	}
	if !xrplDatadirHasLedger(data) {
		t.Fatal("fixture must look like a ledger so stale-reinit would skip")
	}
	ok, err := xrplReinitStaleNuDB(data)
	if err != nil || ok {
		t.Fatalf("stale path must skip sized NuDB: ok=%v err=%v", ok, err)
	}
	if err := os.WriteFile(filepath.Join(data, "db", "state"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	ok, err = xrplReinitCorruptStateDB(data)
	if err != nil || !ok {
		t.Fatalf("state-db reinit: ok=%v err=%v", ok, err)
	}
	if _, err := os.Stat(filepath.Join(data, "db", "nudb.stale", "rippledb.6a6f")); err != nil {
		t.Fatal("want stale copy of corrupt NuDB")
	}
	if _, err := os.Stat(filepath.Join(data, "db", "state")); !os.IsNotExist(err) {
		t.Fatal("xrpld asks to remove db/state*")
	}
	ok, err = xrplReinitCorruptStateDB(data)
	if err != nil || ok {
		t.Fatalf("second state-db reinit must no-op: ok=%v err=%v", ok, err)
	}
	if err := os.WriteFile(filepath.Join(data, "db", "state.journal"), []byte("y"), 0o644); err != nil {
		t.Fatal(err)
	}
	ok, err = xrplReinitCorruptStateDB(data)
	if err != nil || !ok {
		t.Fatalf("leftover state* after marker must still wipe: ok=%v err=%v", ok, err)
	}
	if _, err := os.Stat(filepath.Join(data, "db", "state.journal")); !os.IsNotExist(err) {
		t.Fatal("want state.journal gone")
	}
}
