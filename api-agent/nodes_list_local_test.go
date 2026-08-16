package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestListLocalNodeEnvsFrom_MultiNetwork(t *testing.T) {
	root := t.TempDir()
	nodesDir := filepath.Join(root, "nodes")
	etcRoot := filepath.Join(root, "etc")
	if err := os.MkdirAll(nodesDir, 0o755); err != nil {
		t.Fatal(err)
	}

	write := func(path, body string) {
		t.Helper()
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	write(filepath.Join(nodesDir, "bsc-mainnet.json"), `{
  "network": "bsc",
  "env": "mainnet",
  "public_port": 39890,
  "agent_port": 39990,
  "node_http_port": 8575,
  "p2p_port": 30311,
  "agent_url": "http://185.44.207.117:39990",
  "rpc_mode": "go_proxy"
}
`)
	write(filepath.Join(nodesDir, "ethereum-sepolia.json"), `{
  "network": "ethereum",
  "env": "sepolia",
  "public_port": 39691,
  "agent_port": 39791,
  "node_http_port": 8546
}
`)
	// Contaminated legacy tron dir must not hide real bsc from nodes json,
	// and TRON_NETWORK=bsc must not invent a second bsc/mainnet row.
	write(filepath.Join(etcRoot, "tron", "mainnet", "toolkit.env"), `TRON_ENV=mainnet
TRON_NETWORK=bsc
TRON_PUBLIC_PORT=39890
TRON_AGENT_PORT=39990
TRON_NODE_HTTP_PORT=8575
`)
	write(filepath.Join(etcRoot, "bsc", "testnet", "toolkit.env"), `TRON_ENV=testnet
TRON_NETWORK=bsc
TRON_PUBLIC_PORT=39891
TRON_AGENT_PORT=39991
TRON_NODE_HTTP_PORT=8576
`)

	items := listLocalNodeEnvsFrom(nodesDir, etcRoot)
	byKey := map[string]map[string]any{}
	for _, it := range items {
		net, _ := it["network"].(string)
		env, _ := it["env"].(string)
		byKey[net+"/"+env] = it
	}

	if len(byKey) != 3 {
		t.Fatalf("want 3 unique network/env, got %d: %#v", len(byKey), byKey)
	}
	bscMN := byKey["bsc/mainnet"]
	if bscMN == nil {
		t.Fatal("missing bsc/mainnet")
	}
	if intFromAny(bscMN["agent_port"]) != 39990 {
		t.Fatalf("bsc mainnet agent_port=%v", bscMN["agent_port"])
	}
	if byKey["bsc/testnet"] == nil {
		t.Fatal("missing bsc/testnet from toolkit.env fallback")
	}
	if byKey["ethereum/sepolia"] == nil {
		t.Fatal("missing ethereum/sepolia")
	}
	if byKey["tron/mainnet"] != nil {
		t.Fatalf("contaminated /etc/tron/mainnet should not appear as tron: %#v", byKey["tron/mainnet"])
	}
}

func TestSplitNodesFileName(t *testing.T) {
	net, env := splitNodesFileName("bsc-mainnet.json")
	if net != "bsc" || env != "mainnet" {
		t.Fatalf("got %s/%s", net, env)
	}
	net, env = splitNodesFileName("mainnet.json")
	if net != "tron" || env != "mainnet" {
		t.Fatalf("legacy tron got %s/%s", net, env)
	}
}
