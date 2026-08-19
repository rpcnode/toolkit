package main

import (
	"strings"
	"testing"
	"time"
)

func TestPerNodeAgentUnitsNeverHostBootstrap(t *testing.T) {
	cases := []struct {
		network string
		env     string
	}{
		{"bitcoin", "mainnet"},
		{"bitcoin", "testnet4"},
		{"tron", "mainnet"},
		{"tron", "nile"},
		{"doge", "mainnet"},
		{"doge", "testnet"},
		{"ltc", "regtest"},
		{"dash", "regtest"},
		{"bch", "regtest"},
		{"cardano", "preprod"},
		{"hyperliquid", "testnet"},
		{"xrpl", "mainnet"},
		{"bsc", "mainnet"},
		{"ethereum", "sepolia"},
		{"solana", "mainnet"},
		{"ton", "testnet"},
		{"arb", "mainnet"},
		{"robinhood", "mainnet"},
		{"optimism", "mainnet"},
		{"base", "mainnet"},
	}
	for _, tc := range cases {
		units := filterTeardownUnits(perNodeAgentUnits(tc.network, tc.env))
		if len(units) == 0 {
			t.Fatalf("%s/%s: expected per-node units", tc.network, tc.env)
		}
		for _, u := range units {
			if isHostBootstrapUnit(u) {
				t.Fatalf("%s/%s: teardown must not include host unit %s", tc.network, tc.env, u)
			}
		}
	}
}

func TestRemoveTargetsSelfAgentHostTipNeverSelf(t *testing.T) {
	t.Setenv("TRON_NETWORK", "")
	t.Setenv("TRON_STATE_DIR", "/var/lib/rpcnode/host")
	t.Setenv("TRON_ENV", "mainnet")
	if !isHostTipProcess() {
		t.Fatal("expected host tip detection")
	}
	if removeTargetsSelfAgent("tron", "mainnet") {
		t.Fatal("host tip must not treat tron/mainnet remove as self-teardown")
	}
	if removeTargetsSelfAgent("doge", "mainnet") {
		t.Fatal("host tip must not treat doge remove as self")
	}
}

func TestUnitBelongsToThisProcessHostTipFalse(t *testing.T) {
	t.Setenv("TRON_NETWORK", "")
	t.Setenv("TRON_STATE_DIR", "/var/lib/rpcnode/host")
	if unitBelongsToThisProcess("rpcnode-api-agent-doge-mainnet.service") {
		t.Fatal("host tip must not claim leaf api-agent units")
	}
}

func TestBitcoinTeardownUnitsScoped(t *testing.T) {
	units := filterTeardownUnits(perNodeAgentUnits("bitcoin", "mainnet"))
	want := map[string]bool{
		"rpcnode-api-agent-bitcoin-mainnet.service":    true,
		"rpcnode-system-agent-bitcoin-mainnet.service": true,
	}
	if len(units) != len(want) {
		t.Fatalf("units=%v want exactly %v", units, want)
	}
	for _, u := range units {
		if !want[u] {
			t.Fatalf("unexpected unit %s", u)
		}
	}
}

func TestTronTeardownUnitsCanonAndLegacy(t *testing.T) {
	units := filterTeardownUnits(perNodeAgentUnits("tron", "mainnet"))
	want := map[string]bool{
		"rpcnode-api-agent-tron-mainnet.service":    true,
		"rpcnode-system-agent-tron-mainnet.service": true,
		"rpcnode-api-agent-mainnet.service":         true, // legacy teardown
		"rpcnode-system-agent-mainnet.service":      true,
	}
	if len(units) != len(want) {
		t.Fatalf("units=%v want %v", units, want)
	}
	if units[0] != "rpcnode-api-agent-tron-mainnet.service" {
		t.Fatalf("canon api unit must be first, got %v", units)
	}
	for _, u := range units {
		if !want[u] {
			t.Fatalf("unexpected unit %s", u)
		}
	}
}

func TestFilterTeardownUnitsDropsHostBootstrap(t *testing.T) {
	in := []string{
		"rpcnode-api-agent.service",
		"rpcnode-system-agent.service",
		"rpcnode-api-agent-bitcoin-mainnet.service",
		"tron-api-agent.service",
	}
	out := filterTeardownUnits(in)
	if len(out) != 1 || out[0] != "rpcnode-api-agent-bitcoin-mainnet.service" {
		t.Fatalf("got %v", out)
	}
}

func TestIsHostBootstrapUnit(t *testing.T) {
	if !isHostBootstrapUnit("rpcnode-api-agent.service") || !isHostBootstrapUnit("rpcnode-system-agent") {
		t.Fatal("host bootstrap not detected")
	}
	if isHostBootstrapUnit("rpcnode-api-agent-bitcoin-mainnet.service") {
		t.Fatal("per-node bitcoin unit misclassified as host")
	}
	if isHostBootstrapUnit("rpcnode-api-agent-mainnet.service") {
		t.Fatal("per-env tron unit misclassified as host")
	}
}

func TestNodeUnitsForRemoveBitcoin(t *testing.T) {
	got := nodeUnitsForRemove("bitcoin", "mainnet")
	if len(got) != 1 || got[0] != "bitcoin-mainnet.service" {
		t.Fatalf("got %v", got)
	}
}

func TestStopTimeoutForNetwork(t *testing.T) {
	// Remove ACK must stay snappy — short graceful then escalate (not multi-minute flush).
	if stopTimeoutForNetwork("bitcoin") != coreGracefulStopTimeout ||
		stopTimeoutForNetwork("doge") != coreGracefulStopTimeout ||
		stopTimeoutForNetwork("ltc") != coreGracefulStopTimeout ||
		stopTimeoutForNetwork("dash") != coreGracefulStopTimeout ||
		stopTimeoutForNetwork("bch") != coreGracefulStopTimeout {
		t.Fatal("corelike remove stop budget mismatch")
	}
	if stopTimeoutForNetwork("zcash") != 30*time.Second {
		t.Fatalf("zcash/zebrad stop budget want 30s got %s", stopTimeoutForNetwork("zcash"))
	}
	if stopTimeoutForNetwork("sui") != 30*time.Second {
		t.Fatalf("sui stop budget want 30s got %s", stopTimeoutForNetwork("sui"))
	}
	if stopTimeoutForNetwork("aptos") != 30*time.Second {
		t.Fatalf("aptos stop budget want 30s got %s", stopTimeoutForNetwork("aptos"))
	}
	if stopTimeoutForNetwork("avalanche") != 45*time.Second {
		t.Fatalf("avalanche stop budget want 45s got %s", stopTimeoutForNetwork("avalanche"))
	}
	if coreGracefulStopTimeout > 30*time.Second {
		t.Fatalf("core graceful remove wait too long: %s", coreGracefulStopTimeout)
	}
	if stopTimeoutForNetwork("tron") < systemctlStopTimeout {
		t.Fatal("tron stop budget too small")
	}
}

func TestNetworkUsesCLIStopMatrix(t *testing.T) {
	cli := map[string]bool{
		"bitcoin": true, "doge": true, "ltc": true, "dash": true, "bch": true, "xrpl": true,
	}
	for _, net := range supportedNetworks() {
		want := cli[net]
		if got := networkUsesCLIStop(net); got != want {
			t.Fatalf("%s networkUsesCLIStop=%v want %v", net, got, want)
		}
	}
}

func TestGracefulStopNodeBitcoinSteps(t *testing.T) {
	// Without binaries/conf on CI — still returns a step (missing cli/conf), never panics.
	steps := gracefulStopNode("bitcoin", "mainnet")
	if len(steps) == 0 {
		t.Fatal("expected graceful stop steps")
	}
	joined := strings.Join(steps, " ")
	if !strings.Contains(joined, "bitcoin-cli") {
		t.Fatalf("want bitcoin-cli in steps: %v", steps)
	}
}

func TestGracefulStopCoveredForAllSupported(t *testing.T) {
	// Every supported network must have an explicit stop budget + path (CLI or systemd).
	for _, net := range supportedNetworks() {
		budget := stopTimeoutForNetwork(net)
		min := systemctlStopTimeout
		if networkUsesCLIStop(net) && net != "xrpl" {
			min = coreGracefulStopTimeout // corelike: short wait then SIGKILL
		}
		if budget < min {
			t.Fatalf("%s stop budget too small: %s < %s", net, budget, min)
		}
		if networkUsesCLIStop(net) {
			steps := gracefulStopNode(net, "mainnet")
			if len(steps) == 0 {
				t.Fatalf("%s CLI stop returned no steps", net)
			}
		}
	}
}

func TestRemoveUsesAsyncFileDeleteBitcoin(t *testing.T) {
	for _, net := range supportedNetworks() {
		if !removeUsesAsyncFileDelete(net) {
			t.Fatalf("%s must use async delete_files (large datadir)", net)
		}
	}
}

func TestRemovePhaseOrderContract(t *testing.T) {
	got := removePhaseOrder()
	want := []string{
		"1_kill_node_and_leaf_agents",
		"2_teardown_systemd_units",
		"3_wipe_files_if_requested",
	}
	if len(got) != len(want) {
		t.Fatalf("order=%v want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("[%d]=%s want %s", i, got[i], want[i])
		}
	}
	// Wipe must be last — units torn down before datadir delete.
	if want[2] != "3_wipe_files_if_requested" {
		t.Fatal("wipe phase must be last")
	}
}

func TestNodeUnitRendersHaveNoExecStop(t *testing.T) {
	units := []string{
		renderBitcoindUnit(networkPortProfile{Env: "mainnet", OptPath: "/opt/bitcoin/mainnet"}, "/etc/bitcoin/mainnet/bitcoin.conf"),
		renderDogecoindUnit(networkPortProfile{Env: "mainnet", OptPath: "/opt/doge/mainnet"}, "/etc/doge/mainnet/dogecoin.conf"),
		renderXRPLUnit("mainnet", "/usr/bin/xrpld", "/etc/xrpl/mainnet/xrpld.cfg"),
	}
	if client, ok := lookupCoreLike("ltc"); ok {
		units = append(units, renderCoreLikeUnit(client, networkPortProfile{Env: "mainnet", OptPath: "/opt/ltc/mainnet"}, "/etc/ltc/mainnet/litecoin.conf"))
	}
	for i, u := range units {
		if strings.Contains(u, "\nExecStop=") {
			t.Fatalf("unit %d has ExecStop — remove must not run ExecStop:\n%s", i, u)
		}
	}
}

func TestRemoveJobDeleteFilesFlag(t *testing.T) {
	prev := removeJobsDir
	removeJobsDir = t.TempDir()
	t.Cleanup(func() { removeJobsDir = prev })

	writeRemoveJobWithWipe("bch", "regtest", "started", "", nil, false)
	if removeJobDeleteFiles("bch", "regtest") {
		t.Fatal("delete_files=false must persist for resume")
	}
	writeRemoveJobWithWipe("sui", "mainnet", "started", "", nil, true)
	if !removeJobDeleteFiles("sui", "mainnet") {
		t.Fatal("delete_files=true must persist for resume")
	}
	// Missing job → prefer wipe so orphan dirs do not block re-add.
	if !removeJobDeleteFiles("missing", "mainnet") {
		t.Fatal("missing job should default wipe=true")
	}
}

func TestNodeUnitsForRemoveAllSupported(t *testing.T) {
	cases := map[string][]string{
		"bitcoin":     {"bitcoin-mainnet.service"},
		"doge":        {"doge-mainnet.service"},
		"ltc":         {"ltc-mainnet.service"},
		"dash":        {"dash-mainnet.service"},
		"bch":         {"bch-mainnet.service"},
		"cardano":     {"cardano-mainnet.service", "cardano-ogmios-mainnet.service", "cardano-mainnet-snapshot.service"},
		"hyperliquid": {"hyperliquid-mainnet.service"},
		"xrpl":        {"xrpl-mainnet.service", "xrpl-clio-mainnet.service"},
		"tron":        {"tron-mainnet.service", "tron-mainnet-snapshot.service"},
		"solana":      {"solana-mainnet.service"},
		"ethereum":    {"ethereum-geth-mainnet.service", "ethereum-lighthouse-mainnet.service"},
		"bsc":         {"bsc-mainnet.service"},
		"arb":         {"arb-mainnet.service"},
		"robinhood":   {"robinhood-mainnet.service", "robinhood-mainnet-snapshot.service"},
		"optimism":    {"optimism-mainnet.service", "optimism-op-node-mainnet.service"},
		"base":        {"base-mainnet.service", "base-consensus-mainnet.service"},
		"stellar":     {"stellar-mainnet.service"},
		"ton":         {"ton-mainnet.service", "ton-mainnet-bootstrap.service", "ton-http-api.service", "ton_http_api.service", "mytoncore.service", "validator.service"},
	}
	for net, want := range cases {
		got := nodeUnitsForRemove(net, "mainnet")
		if len(got) != len(want) {
			t.Fatalf("%s units=%v want %v", net, got, want)
		}
		for i := range want {
			if got[i] != want[i] {
				t.Fatalf("%s[%d]=%s want %s", net, i, got[i], want[i])
			}
		}
	}
}

func TestNodeDataPathsScopedPerNetwork(t *testing.T) {
	for _, net := range []string{"doge", "cardano", "hyperliquid", "xrpl", "bitcoin"} {
		paths := nodeDataPaths(net, "mainnet")
		joined := strings.Join(paths, "\n")
		want := "/data/" + net + "/mainnet"
		if !strings.Contains(joined, want) {
			t.Fatalf("%s missing datadir %s in %v", net, want, paths)
		}
		// Must not wipe sibling env or whole /data/<net>.
		for _, p := range paths {
			if p == "/data/"+net || strings.HasSuffix(p, "/data/"+net+"/testnet") {
				t.Fatalf("%s must not wipe sibling path %s", net, p)
			}
		}
		units := filterTeardownUnits(perNodeAgentUnits(net, "mainnet"))
		for _, u := range units {
			if isHostBootstrapUnit(u) {
				t.Fatalf("%s teardown includes host unit %s", net, u)
			}
		}
	}
}

func TestNodeDataPathsBitcoinScoped(t *testing.T) {
	paths := nodeDataPaths("bitcoin", "mainnet")
	joined := strings.Join(paths, "\n")
	if !strings.Contains(joined, "/data/bitcoin/mainnet") {
		t.Fatalf("missing mainnet datadir: %v", paths)
	}
	for _, p := range paths {
		if strings.HasSuffix(p, "/data/bitcoin") || p == "/data/bitcoin/signet" || p == "/data/bitcoin/regtest" {
			t.Fatalf("must not wipe sibling env path %s", p)
		}
	}
}

func TestSystemdUnitBlocksRemove_OneshotLingerIgnored(t *testing.T) {
	if systemdUnitBlocksRemove("active", "exited", "oneshot", "yes") {
		t.Fatal("TON ton-<env>.service RemainAfterExit linger must not block remove ACK")
	}
	if systemdUnitBlocksRemove("inactive", "dead", "oneshot", "yes") {
		t.Fatal("dead oneshot must not block")
	}
	if !systemdUnitBlocksRemove("activating", "start", "oneshot", "yes") {
		t.Fatal("oneshot still starting (bootstrap) must block")
	}
	if !systemdUnitBlocksRemove("active", "running", "simple", "no") {
		t.Fatal("live simple unit must block")
	}
}

func TestStockSharedNodeUnits_TONNotDeleted(t *testing.T) {
	if !isStockSharedNodeUnit("ton", "validator.service") {
		t.Fatal("validator.service is stock MyTonCtrl")
	}
	if isStockSharedNodeUnit("ton", "ton-testnet.service") {
		t.Fatal("rpcnode wrapper is ours — may delete")
	}
	if isStockSharedNodeUnit("bitcoin", "validator.service") {
		t.Fatal("stock TON units are TON-only")
	}
	got := unitsToPinForRemove("ton", "testnet")
	want := map[string]bool{
		"ton-testnet.service":                       true,
		"validator.service":                         true,
		"rpcnode-api-agent-ton-testnet.service":     true,
		"rpcnode-system-agent-ton-testnet.service":  true,
	}
	for name := range want {
		found := false
		for _, u := range got {
			if u == name {
				found = true
				break
			}
		}
		if !found {
			t.Fatalf("pin list missing %s in %v", name, got)
		}
	}
	for _, u := range got {
		if isHostBootstrapUnit(u) {
			t.Fatalf("tip unit pinned: %s", u)
		}
	}
}
