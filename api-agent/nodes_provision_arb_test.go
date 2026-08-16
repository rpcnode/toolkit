package main

import (
	"strings"
	"testing"
)

func TestArbProvisionEnvOK(t *testing.T) {
	if err := arbProvisionEnvOK("sepolia"); err != nil {
		t.Fatalf("sepolia must provision: %v", err)
	}
	if err := arbProvisionEnvOK("mainnet"); err != nil {
		t.Fatalf("mainnet must provision: %v", err)
	}
	if err := arbProvisionEnvOK("devnet"); err == nil {
		t.Fatal("devnet must be rejected")
	}
}

func TestArbL1URLs(t *testing.T) {
	t.Setenv("RPCNODE_L1_RPC_URL", "")
	t.Setenv("RPCNODE_L1_BEACON_URL", "")
	if got := defaultL1RPCURLFor(arbL1Env("sepolia")); got != publicSepoliaL1RPCURL {
		t.Fatalf("sepolia L1 RPC=%q want publicnode", got)
	}
	if got := defaultL1BeaconURLFor(arbL1Env("sepolia")); got != "https://ethereum-sepolia-beacon-api.publicnode.com" {
		t.Fatalf("sepolia L1 beacon=%q", got)
	}
	if got := defaultL1RPCURLFor(arbL1Env("mainnet")); got != "http://185.44.207.117:39690" {
		t.Fatalf("mainnet L1 RPC=%q want :39690", got)
	}
}

func TestRenderArbUnitSepolia(t *testing.T) {
	prof := lookupPortProfile("arb", "sepolia")
	req := nodeProvisionRequest{Network: "arb", Env: "sepolia", NodeHTTPPort: prof.NodeHTTP}
	cluster := lookupArbNetwork("sepolia")
	l1 := "http://127.0.0.1:39691"
	beacon := "https://ethereum-sepolia-beacon-api.publicnode.com"
	body := renderArbUnit("sepolia", "/opt/arbitrum/sepolia/bin/nitro", "/data/arbitrum/sepolia",
		"/etc/arbitrum/sepolia", req, prof, cluster, l1, beacon, prof.SolHTTP, "legacy,target")
	for _, want := range []string{
		"--chain.id=421614",
		"--http.port=8657",
		"--ws.port=8658",
		"--init.latest=pruned",
		"--parent-chain.connection.url=http://127.0.0.1:39691",
		"--parent-chain.blob-client.beacon-url=https://ethereum-sepolia-beacon-api.publicnode.com",
		"LimitNOFILE=1048576",
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("sepolia unit missing %q\n%s", want, body)
		}
	}
	if strings.Contains(body, "--chain.id=42161 ") || strings.Contains(body, "--chain.id=42161\\") {
		t.Fatal("sepolia must not use mainnet chain id")
	}
	if !strings.Contains(body, "--chain.id=421614") {
		t.Fatal("sepolia chain id missing")
	}
}
