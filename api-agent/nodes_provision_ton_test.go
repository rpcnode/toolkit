package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestTonWorkdirIsLive(t *testing.T) {
	dir := t.TempDir()
	if tonWorkdirIsLive(dir) {
		t.Fatal("empty dir is not live")
	}
	_ = os.MkdirAll(filepath.Join(dir, "keys"), 0o755)
	if err := os.WriteFile(filepath.Join(dir, "keys", "client"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if !tonWorkdirIsLive(dir) {
		t.Fatal("keys/client must be live")
	}
}

func TestEnsureTonWorkdirLinkAt_EmptyDirBecomesSymlink(t *testing.T) {
	root := t.TempDir()
	data := filepath.Join(root, "data")
	work := filepath.Join(root, "ton-work")
	if err := os.MkdirAll(work, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := ensureTonWorkdirLinkAt(work, data); err != nil {
		t.Fatal(err)
	}
	st, err := os.Lstat(work)
	if err != nil || st.Mode()&os.ModeSymlink == 0 {
		t.Fatalf("want symlink, err=%v mode=%v", err, st)
	}
	tgt, _ := os.Readlink(work)
	if filepath.Clean(tgt) != filepath.Clean(data) {
		t.Fatalf("target=%q want %q", tgt, data)
	}
}

func TestEnsureTonWorkdirLinkAt_LiveDirKept(t *testing.T) {
	root := t.TempDir()
	data := filepath.Join(root, "data")
	work := filepath.Join(root, "ton-work")
	if err := os.MkdirAll(filepath.Join(work, "keys"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(work, "keys", "client"), []byte("k"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := ensureTonWorkdirLinkAt(work, data); err != nil {
		t.Fatal(err)
	}
	st, err := os.Lstat(work)
	if err != nil || st.Mode()&os.ModeSymlink != 0 {
		t.Fatalf("live dir must stay a directory, err=%v", err)
	}
}

func TestWriteTonBootstrapScript_RepointsEmptyTonWork(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "rpcnode-ton-bootstrap.sh")
	if err := writeTonBootstrapScript(p, "testnet", "testnet", 30311, 8082, "/data/ton/testnet", "/etc/ton/testnet", "/var/log/ton/testnet"); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	s := string(b)
	if !strings.Contains(s, "/var/ton-work/keys/client") || !strings.Contains(s, "ln -sfn \"$DATA\" /var/ton-work") {
		t.Fatalf("bootstrap must replace empty /var/ton-work:\n%s", s)
	}
}
