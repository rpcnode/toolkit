package main

import (
	"math"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestEnsureStellarFullHistoryToml(t *testing.T) {
	if stellarHistoryRetentionWindow != uint32(math.MaxUint32) {
		t.Fatalf("want MaxUint32, got %d", stellarHistoryRetentionWindow)
	}
	dir := t.TempDir()
	path := filepath.Join(dir, "stellar-rpc.toml")
	if err := os.WriteFile(path, []byte("DB_PATH = \"x\"\nHISTORY_RETENTION_WINDOW = 120960\nSTELLAR_CAPTIVE_CORE_HTTP_PORT = 11628\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	changed, err := ensureStellarFullHistoryToml(dir)
	if err != nil || !changed {
		t.Fatalf("changed=%v err=%v", changed, err)
	}
	b, _ := os.ReadFile(path)
	s := string(b)
	if !strings.Contains(s, "4294967295") {
		t.Fatalf("%s", b)
	}
	if !strings.Contains(s, "STELLAR_CAPTIVE_CORE_HTTP_PORT = 0") {
		t.Fatalf("want captive-core HTTP disabled:\n%s", s)
	}
	if !strings.Contains(s, "STELLAR_CAPTIVE_CORE_HTTP_QUERY_PORT = ") {
		t.Fatalf("want captive-core HTTP_QUERY set (stellar-rpc default 11628 if unset):\n%s", s)
	}
}
