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
