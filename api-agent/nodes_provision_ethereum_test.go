package main

import (
	"strings"
	"testing"
)

func TestRenderGethUnit_HighLoadDefaults(t *testing.T) {
	prof := networkPortProfile{Env: "mainnet", NodeHTTP: 8545, P2P: 30303, SolHTTP: 8551}
	req := nodeProvisionRequest{NodeHTTPPort: 8545, P2PPort: 30303}
	cluster := ethereumNetwork{LHNetwork: "mainnet", HistoryPostMerge: true}

	body := renderGethUnit("mainnet", "/usr/bin/geth", "/data/ethereum/mainnet/geth",
		"/etc/ethereum/mainnet/jwt.hex", req, prof, cluster)

	for _, want := range []string{
		"--http.addr 127.0.0.1",
		"--cache 4096",
		"--maxpeers 100",
		"--rpc.batch-request-limit 2000",
		"--history.chain postmerge",
		"LimitNOFILE=1048576",
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("missing %q in:\n%s", want, body)
		}
	}
}

func TestRenderGethUnit_TestnetCacheModest(t *testing.T) {
	prof := networkPortProfile{Env: "sepolia", NodeHTTP: 8546, P2P: 30313, SolHTTP: 8552}
	req := nodeProvisionRequest{NodeHTTPPort: 8546, P2PPort: 30313}
	cluster := ethereumNetwork{LHNetwork: "sepolia", GethFlag: "--sepolia"}

	body := renderGethUnit("sepolia", "/usr/bin/geth", "/data/ethereum/sepolia/geth",
		"/etc/ethereum/sepolia/jwt.hex", req, prof, cluster)

	if !strings.Contains(body, "--cache 2048") {
		t.Fatalf("sepolia should use modest cache:\n%s", body)
	}
	if strings.Contains(body, "--cache 4096") {
		t.Fatalf("sepolia must not use mainnet cache")
	}
	if !strings.Contains(body, "IPAccounting=yes") {
		t.Fatalf("missing IPAccounting:\n%s", body)
	}
	if !strings.Contains(body, "LimitNOFILE=1048576") {
		t.Fatalf("missing high LimitNOFILE")
	}
}

func TestEthereumGethCacheMB(t *testing.T) {
	if ethereumGethCacheMB("mainnet") != 4096 {
		t.Fatalf("mainnet cache")
	}
	if ethereumGethCacheMB("sepolia") != 2048 || ethereumGethCacheMB("hoodi") != 2048 {
		t.Fatalf("testnet cache")
	}
}

func TestLighthouseReleaseURL(t *testing.T) {
	got := lighthouseReleaseURL("v8.2.1", "x86_64")
	want := "https://github.com/sigp/lighthouse/releases/download/v8.2.1/lighthouse-v8.2.1-x86_64-unknown-linux-gnu.tar.gz"
	if got != want {
		t.Fatalf("url=%s want %s", got, want)
	}
	if strings.Contains(got, "portable") {
		t.Fatal("v8.2+ has no portable asset")
	}
}
