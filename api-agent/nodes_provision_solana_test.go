package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestEnsureSolanaRunScript_HighLoadRPCFlags(t *testing.T) {
	dir := t.TempDir()
	bin := filepath.Join(dir, "agave-validator")
	if err := os.WriteFile(bin, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}

	prof := networkPortProfile{Env: "mainnet", OptPath: dir, NodeHTTP: 8899, P2P: 8000}
	req := nodeProvisionRequest{NodeHTTPPort: 8899, P2PPort: 8000}
	cluster := solanaCluster{
		Entrypoints:     []string{"entrypoint.mainnet-beta.solana.com:8001"},
		Genesis:         "5eykt4Ussw8PYiNJV+9XynWfB8aF",
		KnownValidators: []string{"Certusm1sa411sLZ2QYmQkxblirN"},
		P2PRangeSpan:    26,
	}

	ledger := filepath.Join(dir, "ledger")
	accounts := filepath.Join(dir, "accounts")
	snapshots := filepath.Join(dir, "snapshots")
	script, err := ensureSolanaRunScript(prof, req, cluster, bin,
		filepath.Join(dir, "id.json"),
		ledger, accounts, snapshots,
		filepath.Join(dir, "validator.log"),
	)
	if err != nil {
		t.Fatal(err)
	}
	body, err := os.ReadFile(script)
	if err != nil {
		t.Fatal(err)
	}
	s := string(body)
	for _, want := range []string{
		"--rpc-threads 64",
		"--rpc-pubsub-worker-threads 16",
		"--rpc-pubsub-max-active-subscriptions 1000000",
		"--rpc-max-request-body-size 104857600",
		"--private-rpc",
		"--full-rpc-api",
		"--ledger '" + ledger + "'",
		"--accounts '" + accounts + "'",
		"--snapshots '" + snapshots + "'",
	} {
		if !strings.Contains(s, want) {
			t.Fatalf("missing %q in:\n%s", want, s)
		}
	}
}

func TestAgaveReleaseFallbackURL(t *testing.T) {
	got, err := agaveReleaseFallbackURL("4.2.1", "amd64")
	if err != nil {
		t.Fatal(err)
	}
	want := "https://github.com/anza-xyz/agave/releases/download/v4.2.1/solana-release-x86_64-unknown-linux-gnu.tar.bz2"
	if got != want {
		t.Fatalf("got %s", got)
	}
	if _, err := agaveReleaseTarballName("arm64"); err == nil {
		t.Fatal("linux aarch64 should be unsupported")
	}
}

func TestResolveSolanaKeygenUsesOptPath(t *testing.T) {
	dir := t.TempDir()
	bin := filepath.Join(dir, "bin")
	if err := os.MkdirAll(bin, 0o755); err != nil {
		t.Fatal(err)
	}
	kg := filepath.Join(bin, "solana-keygen")
	if err := os.WriteFile(kg, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	got := resolveSolanaKeygen(dir)
	if got != kg {
		t.Fatalf("got %q want %q", got, kg)
	}
}
