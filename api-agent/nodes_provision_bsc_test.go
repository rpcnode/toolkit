package main

import (
	"strings"
	"testing"
)

func TestRenderBSCUnitHighLoad(t *testing.T) {
	prof := lookupPortProfile("bsc", "mainnet")
	req := nodeProvisionRequest{
		Network: "bsc", Env: "mainnet",
		NodeHTTPPort: prof.NodeHTTP, P2PPort: prof.P2P,
	}
	body := renderBSCUnit("mainnet", "/opt/bsc/mainnet/bin/geth", "/data/bsc/mainnet",
		"/etc/bsc/mainnet/config.toml", req, prof)
	for _, want := range []string{
		"--syncmode full",
		"--gcmode full",
		"--cache 8192",
		"--maxpeers 100",
		"--rpc.batch-request-limit 2000",
		"IPAccounting=yes",
		"LimitNOFILE=1048576",
		"--http.port 8575",
		"--port 30311",
		"--config /etc/bsc/mainnet/config.toml",
		"parlia",
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("unit missing %q\n%s", want, body)
		}
	}
	if bscGethCacheMB("testnet") != 4096 {
		t.Fatalf("testnet cache=%d (unknown RAM keeps profile default)", bscGethCacheMB("testnet"))
	}
}

func TestBscGethCacheCappedOnSmallRAM(t *testing.T) {
	if got := bscGethCacheMBFor("testnet", 4096); got != 1024 {
		t.Fatalf("4GiB testnet cache=%d want 1024 (25%% RAM)", got)
	}
	if got := bscGethCacheMBFor("testnet", 32768); got != 4096 {
		t.Fatalf("32GiB testnet cache=%d want 4096", got)
	}
	if got := bscGethCacheMBFor("mainnet", 32768); got != 8192 {
		t.Fatalf("32GiB mainnet cache=%d want 8192", got)
	}
	if got := bscGethCacheMBFor("mainnet", 16384); got != 4096 {
		t.Fatalf("16GiB mainnet cache=%d want 4096 cap", got)
	}
	if got := bscGethCacheMBFor("testnet", 0); got != 4096 {
		t.Fatalf("unknown RAM keeps default, got %d", got)
	}
}

func TestRenderBSCSnapshotScript_OfficialFetch(t *testing.T) {
	s := renderBSCSnapshotScript(
		"mainnet", "/data/bsc/mainnet", "/data/bsc/mainnet/snapshots", "/opt/bsc/mainnet",
		"/data/bsc/mainnet/.snapshot-ready", "/data/bsc/mainnet/.snapshot-state.json",
		"/var/log/bsc/mainnet-snapshot.log", "pruned", "mainnet-geth-pbss",
	)
	for _, need := range []string{
		"fetch-snapshot.sh",
		" -p",
		"--auto-delete",
		"/data/bsc/mainnet/.snapshot-ready",
		"rm -rf \"$DATA/geth\"",
		"geth/chaindata",
		"geth/chaindata/ancient/chain",
		"bnb-chain/bsc-snapshots",
		"aria2",
		"lz4",
		"SNAPSHOT_DIAG",
		"snapdiag",
		".snapshot-keep",
		"pin_keep",
	} {
		if !strings.Contains(s, need) {
			t.Fatalf("script missing %q:\n%s", need, s)
		}
	}
	full := renderBSCSnapshotScript(
		"mainnet", "/data/bsc/mainnet", "/data/bsc/mainnet/snapshots", "/opt/bsc/mainnet",
		"/data/bsc/mainnet/.snapshot-ready", "/data/bsc/mainnet/.snapshot-state.json",
		"/var/log/bsc/mainnet-snapshot.log", "full", "mainnet-geth-pbss",
	)
	if strings.Contains(full, " -p") {
		t.Fatal("full flavor must not pass -p (pruneancient)")
	}
}

func TestLookupBSCNetwork(t *testing.T) {
	tn := lookupBSCNetwork("testnet")
	if tn.ChainID != "97" || tn.WatchSlug != "bsc-testnet" || tn.ZipAsset != "testnet.zip" {
		t.Fatalf("testnet: %+v", tn)
	}
	mn := lookupBSCNetwork("mainnet")
	if mn.ChainID != "56" || mn.WatchSlug != "bsc" {
		t.Fatalf("mainnet: %+v", mn)
	}
}
