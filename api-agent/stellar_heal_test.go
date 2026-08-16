package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestApplyStellarCaptiveCorePortsStripsNestedKeys(t *testing.T) {
	in := `
NETWORK_PASSPHRASE="Test SDF Network ; September 2015"
[[VALIDATORS]]
NAME="sdf1"
HOME_DOMAIN="testnet.stellar.org"
PUBLIC_KEY="GABC"
ADDRESS="core-testnet1.stellar.org"
QUALITY="HIGH"
HTTP_PORT=11628
PEER_PORT=9999
`
	out := applyStellarCaptiveCorePorts(in, lookupStellarNetwork("testnet"))
	if strings.Contains(out, "HTTP_PORT") {
		t.Fatalf("HTTP_PORT must be stripped, got:\n%s", out)
	}
	if !strings.Contains(out, "PEER_PORT=11627\n") {
		t.Fatalf("want Confirm P2P as root PEER_PORT=11627:\n%s", out)
	}
	if !strings.Contains(out, "[[VALIDATORS]]") {
		t.Fatal("validators section lost")
	}
}

func TestEnsureStellarFullHistoryTomlMigratesHTTPPort(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "stellar-rpc.toml")
	body := `
ENDPOINT = "127.0.0.1:8001"
HISTORY_RETENTION_WINDOW = 604800
STELLAR_CAPTIVE_CORE_HTTP_PORT = 11628
`
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
	changed, err := ensureStellarFullHistoryToml(dir, 11628)
	if err != nil {
		t.Fatal(err)
	}
	if !changed {
		t.Fatal("expected migrate")
	}
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	s := string(got)
	if !strings.Contains(s, "HISTORY_RETENTION_WINDOW = 4294967295") {
		t.Fatalf("history not migrated: %s", s)
	}
	if !strings.Contains(s, "STELLAR_CAPTIVE_CORE_HTTP_PORT = 0") {
		t.Fatalf("captive HTTP not forced to 0: %s", s)
	}
	if !strings.Contains(s, "STELLAR_CAPTIVE_CORE_HTTP_QUERY_PORT = 11628") {
		t.Fatalf("captive HTTP_QUERY missing: %s", s)
	}
}

func TestEnsureStellarFullHistoryTomlIdempotent(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "stellar-rpc.toml")
	body := `
ENDPOINT = "127.0.0.1:8001"
HISTORY_RETENTION_WINDOW = 4294967295
STELLAR_CAPTIVE_CORE_HTTP_PORT = 0
STELLAR_CAPTIVE_CORE_HTTP_QUERY_PORT = 11628
`
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
	changed, err := ensureStellarFullHistoryToml(dir, 11628)
	if err != nil {
		t.Fatal(err)
	}
	if changed {
		t.Fatal("already good toml must be no-op")
	}
}

func TestStellarJournalNeedsResetMarkers(t *testing.T) {
	cases := []struct {
		line string
		want bool
	}{
		{"Invalid captive core toml file", true},
		{"VALIDATORS.2.HTTP_PORT unknown", true},
		{"listen tcp :11628: address already in use", true},
		{"Permission denied (os error 13)", true},
		{"failed to create storage directory", true},
		{"catching up to ledger 4087041", false},
	}
	for _, tc := range cases {
		got := stellarJournalTextNeedsReset(tc.line)
		if got != tc.want {
			t.Fatalf("%q: got %v want %v", tc.line, got, tc.want)
		}
	}
}
