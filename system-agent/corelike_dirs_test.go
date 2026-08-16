package main

import "testing"

func TestCoreLikeNestDirName(t *testing.T) {
	cases := []struct {
		network, env, want string
	}{
		{"ltc", "testnet", "testnet4"},
		{"ltc", "regtest", "regtest"},
		{"ltc", "mainnet", ""},
		{"dash", "testnet", "testnet3"},
		{"bch", "testnet", "testnet3"},
		{"dash", "regtest", "regtest"},
	}
	for _, tc := range cases {
		got := coreLikeNestDirName(tc.network, tc.env)
		if got != tc.want {
			t.Fatalf("%s/%s nest=%q want %q", tc.network, tc.env, got, tc.want)
		}
	}
}

func TestCoreLikeChainDataDirLTCTestnet(t *testing.T) {
	// Old wrong profile path still resolves to the nest litecoind creates.
	got := coreLikeChainDataDir("ltc", "testnet", "/data/ltc/testnet")
	if got != "/data/ltc/testnet4" {
		t.Fatalf("got %q", got)
	}
	got = coreLikeChainDataDir("ltc", "testnet", "/data/ltc/testnet4")
	if got != "/data/ltc/testnet4" {
		t.Fatalf("got %q", got)
	}
}
