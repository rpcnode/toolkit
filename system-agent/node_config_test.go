package main

import (
	"strings"
	"testing"
)

func TestParseLooseKV(t *testing.T) {
	in := `# comment
dbcache=4096
txindex=1
[main]
rpcport=8332
rpcuser = alice
`
	kv := parseLooseKV(in)
	if kv["dbcache"] != "4096" {
		t.Fatalf("dbcache=%q", kv["dbcache"])
	}
	if kv["rpcport"] != "8332" {
		t.Fatalf("rpcport=%q", kv["rpcport"])
	}
	if kv["rpcuser"] != "alice" {
		t.Fatalf("rpcuser=%q", kv["rpcuser"])
	}
}

func TestUpsertLooseKV(t *testing.T) {
	in := "dbcache=1024\ntxindex=1\n"
	out := upsertLooseKV(in, map[string]string{"dbcache": "4096", "rpcthreads": "16"})
	kv := parseLooseKV(out)
	if kv["dbcache"] != "4096" {
		t.Fatalf("dbcache=%q out=%q", kv["dbcache"], out)
	}
	if kv["rpcthreads"] != "16" {
		t.Fatalf("rpcthreads missing: %q", out)
	}
	if kv["txindex"] != "1" {
		t.Fatalf("txindex lost")
	}
}

func TestAssertProtectedUnchanged(t *testing.T) {
	old := "rpcuser=a\nrpcpassword=secret\ndbcache=1\n"
	good := "rpcuser=a\nrpcpassword=secret\ndbcache=4096\n"
	bad := "rpcuser=b\nrpcpassword=secret\ndbcache=4096\n"
	if err := assertProtectedUnchanged("ini", old, good, []string{"rpcuser", "rpcpassword"}); err != nil {
		t.Fatal(err)
	}
	if err := assertProtectedUnchanged("ini", old, bad, []string{"rpcuser", "rpcpassword"}); err == nil {
		t.Fatal("expected protected error")
	}
}

func TestAssertPortBindingsUnchanged(t *testing.T) {
	old := "dbcache=1024\nrpcport=8332\nport=8333\n"
	ok := "dbcache=4096\nrpcport=8332\nport=8333\n"
	bad := "dbcache=4096\nrpcport=9999\nport=8333\n"
	if err := assertPortBindingsUnchanged(old, ok); err != nil {
		t.Fatal(err)
	}
	if err := assertPortBindingsUnchanged(old, bad); err == nil {
		t.Fatal("expected port change rejected")
	}
	unitOld := "ExecStart=/usr/bin/geth --http.port 8545 --port 30303\n"
	unitBad := "ExecStart=/usr/bin/geth --http.port 8555 --port 30303\n"
	if err := assertPortBindingsUnchanged(unitOld, unitBad); err == nil {
		t.Fatal("expected unit http.port change rejected")
	}
}

func TestAssertDataDirBindingsUnchanged(t *testing.T) {
	old := "dbcache=1024\ndatadir=/data/bitcoin/mainnet\nrpcport=8332\n"
	ok := "dbcache=4096\ndatadir=/data/bitcoin/mainnet\nrpcport=8332\n"
	bad := "dbcache=4096\ndatadir=/tmp/evil\nrpcport=8332\n"
	if err := assertLockedBindingsUnchanged(old, ok); err != nil {
		t.Fatal(err)
	}
	if err := assertLockedBindingsUnchanged(old, bad); err == nil {
		t.Fatal("expected datadir change rejected")
	}
	unitOld := "ExecStart=/usr/bin/solana-validator --ledger-path /data/solana/mainnet/ledger --rpc-port 8899\n"
	unitBad := "ExecStart=/usr/bin/solana-validator --ledger-path /tmp/evil --rpc-port 8899\n"
	if err := assertLockedBindingsUnchanged(unitOld, unitBad); err == nil {
		t.Fatal("expected --ledger-path change rejected")
	}
	if err := assertProtectedUnchanged("ini", old, bad, mergeProtectedKeys()); err == nil {
		t.Fatal("expected assertProtectedUnchanged to reject datadir")
	}
}

func TestIsPortLikeKey(t *testing.T) {
	for _, k := range []string{"rpcport", "HTTPPort", "WSPort", "zmqpubrawblock", "ENDPOINT", "PEER_PORT", "port"} {
		if !isPortLikeKey(k) {
			t.Fatalf("%s should be port-like", k)
		}
	}
	for _, k := range []string{"dbcache", "txindex", "rpcpassword", "maxconnections"} {
		if isPortLikeKey(k) {
			t.Fatalf("%s should not be port-like", k)
		}
	}
}

func TestIsDataDirLikeKey(t *testing.T) {
	for _, k := range []string{
		"datadir", "data_dir", "DataDir", "dbPath", "db_path", "DbPath",
		"blocksdir", "walletdir", "wallet", "ledger-path", "ledger_path",
		"accounts-path", "--datadir", "--ledger-path", "chaindata",
	} {
		if !isDataDirLikeKey(k) {
			t.Fatalf("%s should be datadir-like", k)
		}
		if !isLockedConfigKey(k, nil) {
			t.Fatalf("%s should be locked", k)
		}
	}
	for _, k := range []string{"dbcache", "txindex", "rpcpassword", "maxconnections", "rpcthreads"} {
		if isDataDirLikeKey(k) {
			t.Fatalf("%s should not be datadir-like", k)
		}
	}
}

func TestMaterializeDiscoversFileKeys(t *testing.T) {
	content := "dbcache=2048\ncustom_knob=yes\nrpcport=8332\ndatadir=/data/bitcoin/mainnet\n"
	fields := materializeFields("ini", content, coreLikeConfigFields(), mergeProtectedKeys())
	var custom, rpcport, datadir *nodeConfigField
	for i := range fields {
		switch strings.ToLower(fields[i].Key) {
		case "custom_knob":
			custom = &fields[i]
		case "rpcport":
			rpcport = &fields[i]
		case "datadir":
			datadir = &fields[i]
		}
	}
	if custom == nil || custom.Value != "yes" {
		t.Fatalf("custom_knob missing: %+v", fields)
	}
	if rpcport == nil || !rpcport.Protected {
		t.Fatalf("rpcport should be protected: %+v", rpcport)
	}
	if datadir == nil || !datadir.Protected {
		t.Fatalf("datadir should be protected: %+v", datadir)
	}
}

func TestPatchStellarRetentionWindow(t *testing.T) {
	in := "ENDPOINT = \"0.0.0.0:8000\"\nHISTORY_RETENTION_WINDOW = 60480\n"
	changed, out, err := patchStellarRetentionWindow(in)
	if err != nil {
		t.Fatal(err)
	}
	if !changed {
		t.Fatal("expected change")
	}
	kv := parseLooseKV(out)
	if kv["history_retention_window"] != "4294967295" {
		t.Fatalf("got %q", kv["history_retention_window"])
	}
}

func TestNodeConfigCatalogBitcoin(t *testing.T) {
	c := &NodeConfigController{cfg: Config{Network: "bitcoin", Env: "mainnet", EtcDir: "/etc/bitcoin/mainnet"}}
	specs := c.catalog()
	if len(specs) < 1 || specs[0].RelPath != "bitcoin.conf" {
		t.Fatalf("specs=%+v", specs)
	}
}

func TestNodeConfigCatalogAllNetworksHaveDocs(t *testing.T) {
	nets := []string{
		"bitcoin", "doge", "ltc", "dash", "bch", "tron", "bsc", "xrpl", "cardano",
		"stellar", "solana", "ethereum", "optimism", "base", "arbitrum", "robinhood",
		"hyperliquid", "ton", "etc",
	}
	for _, net := range nets {
		c := &NodeConfigController{cfg: Config{
			Network: net, Env: "mainnet",
			EtcDir: "/etc/" + net + "/mainnet",
			OptDir: "/opt/" + net + "/mainnet",
			NodeService: net + "-mainnet",
		}}
		specs := c.catalog()
		if len(specs) == 0 {
			t.Fatalf("%s: empty catalog", net)
		}
	}
}
