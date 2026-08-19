package main

import "testing"

func TestGuessArtifactKind(t *testing.T) {
	cases := []struct {
		kind, url, want string
	}{
		{"jar", "", "jar"},
		{"bin", "https://example/geth_linux", "bin"},
		{"binary", "https://example/geth", "bin"},
		{"tarball", "", "tarball"},
		{"archive", "", "tarball"},
		{"zip", "", "zip"},
		{"apt", "", "apt"},
		{"docker_extract", "docker://stellar/stellar-rpc:28", "docker_extract"},
		{"", "https://x/FullNode.jar", "jar"},
		{"", "https://x/core.tar.gz", "tarball"},
		{"", "https://x/core.tgz", "tarball"},
		{"", "https://x/app.zip", "zip"},
		{"", "https://github.com/bnb-chain/bsc/releases/download/v1.7.7/geth_linux", "bin"},
	}
	for _, tc := range cases {
		if got := guessArtifactKind(tc.kind, tc.url); got != tc.want {
			t.Fatalf("guessArtifactKind(%q, %q)=%q want %q", tc.kind, tc.url, got, tc.want)
		}
	}
}

func TestCfgNodeUnitsAux(t *testing.T) {
	got := cfgNodeUnits(Config{Network: "ethereum", Env: "mainnet", NodeService: "ethereum-mainnet"})
	if len(got) != 2 || got[0] != "ethereum-mainnet" || got[1] != "ethereum-lighthouse-mainnet" {
		t.Fatalf("ethereum units=%v", got)
	}
	bsc := cfgNodeUnits(Config{Network: "bsc", Env: "mainnet", NodeService: "bsc-mainnet"})
	if len(bsc) != 1 || bsc[0] != "bsc-mainnet" {
		t.Fatalf("bsc units=%v", bsc)
	}
}
