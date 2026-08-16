package main

import (
	"strings"
	"testing"
)

func TestOptimismProvisionEnvOK(t *testing.T) {
	if err := optimismProvisionEnvOK("sepolia"); err != nil {
		t.Fatalf("sepolia must provision: %v", err)
	}
	if err := optimismProvisionEnvOK("mainnet"); err != nil {
		t.Fatalf("mainnet must provision: %v", err)
	}
	if err := optimismProvisionEnvOK("devnet"); err == nil {
		t.Fatal("devnet must be rejected")
	}
}

func TestOptimismL1URLs(t *testing.T) {
	t.Setenv("RPCNODE_L1_RPC_URL", "")
	t.Setenv("RPCNODE_L1_BEACON_URL", "")
	if got := defaultL1RPCURLFor(optimismL1Env("sepolia")); got != publicSepoliaL1RPCURL {
		t.Fatalf("sepolia L1 RPC=%q want publicnode", got)
	}
	if got := defaultL1BeaconURLFor(optimismL1Env("sepolia")); got != "https://ethereum-sepolia-beacon-api.publicnode.com" {
		t.Fatalf("sepolia L1 beacon=%q", got)
	}
	if got := defaultL1RPCURLFor(optimismL1Env("mainnet")); got != "http://185.44.207.117:39690" {
		t.Fatalf("mainnet L1 RPC=%q want :39690", got)
	}
}

func TestOptimismGethCacheMBFor(t *testing.T) {
	if got := optimismGethCacheMBFor("sepolia", 4096); got != 1024 {
		t.Fatalf("4 GiB sepolia cache=%d want 1024", got)
	}
	if got := optimismGethCacheMBFor("mainnet", 32768); got != 8192 {
		t.Fatalf("32 GiB mainnet cache=%d want 8192", got)
	}
	if got := optimismGethCacheMBFor("sepolia", 0); got != 4096 {
		t.Fatalf("unknown RAM keeps profile default, got %d", got)
	}
}

func TestRenderOpGethUnitSepolia(t *testing.T) {
	prof := lookupPortProfile("optimism", "sepolia")
	req := nodeProvisionRequest{Network: "optimism", Env: "sepolia", NodeHTTPPort: prof.NodeHTTP, P2PPort: prof.P2P}
	cluster := lookupOptimismNetwork("sepolia")
	body := renderOpGethUnit("sepolia", "/opt/optimism/sepolia/bin/op-geth", "/data/optimism/sepolia/op-geth",
		"/etc/optimism/sepolia/jwt.hex", req, prof, cluster, prof.SolHTTP)
	for _, want := range []string{
		"--http.port=8649",
		"--authrpc.port=8569",
		"--gcmode=full",
		"LimitNOFILE=1048576",
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("sepolia op-geth unit missing %q\n%s", want, body)
		}
	}
	opBody := renderOpNodeUnit("sepolia", "/opt/optimism/sepolia/bin/op-node", "/etc/optimism/sepolia/jwt.hex",
		prof.SolHTTP, prof.PBFTHTTP, prof.Metrics, cluster, "http://127.0.0.1:39691",
		"https://ethereum-sepolia-beacon-api.publicnode.com", "optimism-sepolia.service")
	for _, want := range []string{
		"--network=op-sepolia",
		"--l1=http://127.0.0.1:39691",
		"--l1.beacon=https://ethereum-sepolia-beacon-api.publicnode.com",
		"--l2.jwt-secret=/etc/optimism/sepolia/jwt.hex",
	} {
		if !strings.Contains(opBody, want) {
			t.Fatalf("sepolia op-node unit missing %q\n%s", want, opBody)
		}
	}
	if strings.Contains(opBody, "op-mainnet") {
		t.Fatal("sepolia must not use op-mainnet")
	}
}
