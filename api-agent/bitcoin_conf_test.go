package main

import (
	"strings"
	"testing"
)

func TestRenderBitcoinConfWithCache_ProdRPCKeys(t *testing.T) {
	prof := networkPortProfile{
		Env:      "mainnet",
		DataPath: "/data/bitcoin/mainnet",
		P2P:      8333,
		NodeHTTP: 8332,
	}
	req := nodeProvisionRequest{}

	body := renderBitcoinConfWithCache(prof, req, 4096)

	for _, k := range []string{
		"server=1",
		"txindex=1",
		"prune=0",
		"dbcache=4096",
		"rpcthreads=64",
		"rpcworkqueue=1024",
		"maxconnections=125",
		"rest=1",
	} {
		if !strings.Contains(body, k) {
			t.Fatalf("missing %q in:\n%s", k, body)
		}
	}
}

func TestPreserveBitcoinRPCAuthLines(t *testing.T) {
	old := []byte("server=1\nrpcauth=alice:deadbeef\n# comment\nrpcauth=bob:cafebabe\n")
	got := preserveBitcoinRPCAuthLines(old)
	if len(got) != 2 || got[0] != "rpcauth=alice:deadbeef" || got[1] != "rpcauth=bob:cafebabe" {
		t.Fatalf("got %#v", got)
	}
}
