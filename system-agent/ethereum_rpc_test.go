package main

import "testing"

func TestEthereumPublicTipRPC(t *testing.T) {
	t.Setenv("ETHEREUM_PUBLIC_TIP_RPC", "")
	if got := ethereumPublicTipRPC(Config{Env: "mainnet"}); got != "https://ethereum-rpc.publicnode.com" {
		t.Fatalf("mainnet tip rpc: %s", got)
	}
	if got := ethereumPublicTipRPC(Config{Env: "sepolia"}); got != "https://ethereum-sepolia-rpc.publicnode.com" {
		t.Fatalf("sepolia tip rpc: %s", got)
	}
	t.Setenv("ETHEREUM_PUBLIC_TIP_RPC", "https://example.test/eth")
	if got := ethereumPublicTipRPC(Config{Env: "mainnet"}); got != "https://example.test/eth" {
		t.Fatalf("override: %s", got)
	}
}

func TestEthereumDisplayHeights(t *testing.T) {
	blocks, headers := ethereumDisplayHeights(ethereumRPCResult{
		Block: 25770429, Syncing: false,
	}, 0)
	if blocks != 25770429 || headers != 25770429 {
		t.Fatalf("synced no public tip: blocks=%d headers=%d", blocks, headers)
	}

	blocks, headers = ethereumDisplayHeights(ethereumRPCResult{
		Block: 25770429, Syncing: false,
	}, 25771000)
	if blocks != 25770429 || headers != 25771000 {
		t.Fatalf("synced + public tip: blocks=%d headers=%d", blocks, headers)
	}

	blocks, headers = ethereumDisplayHeights(ethereumRPCResult{
		Syncing: true, CurrentBlock: 100, HighestBlock: 200, Block: 100,
	}, 250)
	if blocks != 100 || headers != 250 {
		t.Fatalf("syncing prefers public tip: blocks=%d headers=%d", blocks, headers)
	}
}

func TestEthSyncVerificationPct(t *testing.T) {
	cases := []struct {
		name    string
		cur, hi int64
		syncing bool
		want    float64
	}{
		{"synced", 0, 0, false, 100},
		{"synced_with_height", 12_000_000, 0, false, 100},
		{"early", 100, 1000, true, 10},
		{"mid", 5_000_000, 10_000_000, true, 50},
		{"near", 999, 1000, true, 99.9},
		{"cap", 1100, 1000, true, 99.9},
		{"unknown_highest", 42, 0, true, 0},
		{"zero_current", 0, 1000, true, 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := ethSyncVerificationPct(tc.cur, tc.hi, tc.syncing)
			if got != tc.want {
				t.Fatalf("got %v want %v", got, tc.want)
			}
		})
	}
}
