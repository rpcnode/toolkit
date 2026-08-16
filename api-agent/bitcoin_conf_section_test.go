package main

import (
	"strings"
	"testing"
)

func TestRenderBitcoinConfRegtestSection(t *testing.T) {
	prof := lookupPortProfile("bitcoin", "regtest")
	body := renderBitcoinConfWithCache(prof, nodeProvisionRequest{
		Network: "bitcoin", Env: "regtest",
		NodeHTTPPort: 18443, P2PPort: 18444,
	}, 256)
	for _, want := range []string{
		"regtest=1", "[regtest]", "port=18444", "rpcport=18443", "dbcache=256",
		"datadir=/data/bitcoin", // parent — Core nests → /data/bitcoin/regtest
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("missing %q in:\n%s", want, body)
		}
	}
	if strings.Contains(body, "datadir=/data/bitcoin/regtest\n") {
		t.Fatalf("datadir must be parent to avoid /regtest/regtest:\n%s", body)
	}
	if strings.Index(body, "port=18444") < strings.Index(body, "[regtest]") {
		t.Fatalf("port must be under [regtest]:\n%s", body)
	}
}

func TestParseBitcoinCoreVersion(t *testing.T) {
	maj, min, ok := parseBitcoinCoreVersion("Bitcoin Core version v27.1.0")
	if !ok || maj != 27 || min != 1 {
		t.Fatalf("27.1 got %d.%d ok=%v", maj, min, ok)
	}
	maj, min, ok = parseBitcoinCoreVersion("Bitcoin Core version v28.1.0")
	if !ok || maj != 28 || min != 1 {
		t.Fatalf("28.1 got %d.%d ok=%v", maj, min, ok)
	}
	if defaultBitcoinCoreVersion != "28.1" {
		t.Fatalf("default Core must be 28.1+ for testnet4, got %s", defaultBitcoinCoreVersion)
	}
}
