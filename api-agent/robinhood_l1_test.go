package main

import "testing"

func TestRobinhoodL1URLs(t *testing.T) {
	t.Setenv("RPCNODE_L1_RPC_URL", "")
	t.Setenv("RPCNODE_L1_BEACON_URL", "")
	if got := defaultL1RPCURLFor(robinhoodL1Env("testnet")); got != publicSepoliaL1RPCURL {
		t.Fatalf("testnet L1 RPC=%q want publicnode sepolia", got)
	}
	if got := defaultL1BeaconURLFor(robinhoodL1Env("testnet")); got != "https://ethereum-sepolia-beacon-api.publicnode.com" {
		t.Fatalf("testnet L1 beacon=%q want publicnode sepolia (PeerDAS blob API)", got)
	}
	if got := defaultL1RPCURLFor(robinhoodL1Env("mainnet")); got != "http://185.44.207.117:39690" {
		t.Fatalf("mainnet L1 RPC=%q want :39690", got)
	}
	if got := defaultL1BeaconURLFor(robinhoodL1Env("mainnet")); got != "http://185.44.207.117:15052" {
		t.Fatalf("mainnet L1 beacon=%q want ethereum-host :15052", got)
	}
}
