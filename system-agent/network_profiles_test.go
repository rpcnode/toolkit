package main

import (
	"slices"
	"strings"
	"testing"
)

func TestBuiltinTronProfiles(t *testing.T) {
	mainnet := LookupNetworkProfile("tron", "mainnet")
	if mainnet.ID != "tron/mainnet" {
		t.Fatalf("id=%q", mainnet.ID)
	}
	if mainnet.SnapshotPolicy != SnapshotRequired {
		t.Fatalf("mainnet policy=%v want SnapshotRequired", mainnet.SnapshotPolicy)
	}
	if mainnet.DefaultSnapshotURL == "" {
		t.Fatal("mainnet DefaultSnapshotURL must be set")
	}
	if !mainnet.HasExtra(StepSnapshot) {
		t.Fatal("mainnet must list StepSnapshot in ExtraSteps")
	}
	if !mainnet.AutoSnapshot || !mainnet.AutoStartNode {
		t.Fatal("mainnet must auto-advance snapshot and start")
	}
	// Must match api-agent canonicalPorts — never legacy Docker :8093.
	if mainnet.DefaultAgentPort != 39190 || mainnet.DefaultPublicPort != 39090 {
		t.Fatalf("mainnet ports pub=%d agent=%d", mainnet.DefaultPublicPort, mainnet.DefaultAgentPort)
	}

	nile := LookupNetworkProfile("tron", "nile")
	if nile.SnapshotPolicy != SnapshotRequired {
		t.Fatalf("nile policy=%v", nile.SnapshotPolicy)
	}
	if nile.DefaultAgentPort != 39191 || nile.DefaultNodeHTTP != 18091 || nile.DefaultP2PPort != 18889 {
		t.Fatalf("nile ports mismatch: %+v", nile)
	}
	if nile.DefaultSnapshotURL == "" || nile.DefaultSnapshotURL == mainnet.DefaultSnapshotURL {
		t.Fatalf("nile must have its own snapshot URL, got %q", nile.DefaultSnapshotURL)
	}
	if !strings.Contains(nile.DefaultSnapshotURL, "nileex.io") {
		t.Fatalf("nile snapshot should be from nileex.io: %q", nile.DefaultSnapshotURL)
	}

	shasta := LookupNetworkProfile("tron", "shasta")
	if shasta.SnapshotPolicy != SnapshotOptional {
		t.Fatalf("shasta policy=%v want SnapshotOptional", shasta.SnapshotPolicy)
	}
	if shasta.DefaultSnapshotURL != "" {
		t.Fatal("shasta should not ship a default snapshot URL")
	}
	if shasta.DefaultAgentPort != 39192 {
		t.Fatalf("shasta agent port=%d want 39192", shasta.DefaultAgentPort)
	}
}

func TestListKnownEnvsFromCatalog(t *testing.T) {
	envs := ListKnownEnvs("tron")
	for _, want := range []string{"mainnet", "nile", "shasta"} {
		if !slices.Contains(envs, want) {
			t.Fatalf("ListKnownEnvs(tron) missing %q: %v", want, envs)
		}
	}
	if len(ListKnownEnvs("no-such-network")) != 0 {
		t.Fatal("unknown network must list zero envs (no hardcoded fallback)")
	}
}

func TestLookupDefaultsAndFallback(t *testing.T) {
	def := LookupNetworkProfile("", "")
	if def.Network != DefaultNetwork || def.Env != DefaultEnv {
		t.Fatalf("empty lookup got %s/%s want %s/%s", def.Network, def.Env, DefaultNetwork, DefaultEnv)
	}

	btc := LookupNetworkProfile("bitcoin", "mainnet")
	if btc.SnapshotPolicy != SnapshotNever || btc.HasExtra(StepSnapshot) {
		t.Fatalf("bitcoin must be IBD-only (no snapshot): %+v", btc)
	}
	btcSteps := btc.SupportedLifecycleSteps()
	if slices.Contains(btcSteps, StepSnapshot) {
		t.Fatalf("bitcoin supported_steps must omit snapshot: %v", btcSteps)
	}
	wantBTC := []string{"ports", "install", "start", "run"}
	if !slices.Equal(btcSteps, wantBTC) {
		t.Fatalf("bitcoin supported_steps=%v want %v", btcSteps, wantBTC)
	}
	caps := btc.LifecycleCapabilities()
	if caps["snapshot"] || !caps["ibd"] {
		t.Fatalf("bitcoin capabilities=%v", caps)
	}
	regCaps := LookupNetworkProfile("bitcoin", "regtest").LifecycleCapabilities()
	if regCaps["snapshot"] || regCaps["ibd"] {
		t.Fatalf("bitcoin regtest must not advertise IBD: %v", regCaps)
	}
	for _, net := range []string{"ltc", "dash", "bch"} {
		rc := LookupNetworkProfile(net, "regtest").LifecycleCapabilities()
		if rc["snapshot"] || rc["ibd"] {
			t.Fatalf("%s regtest must not advertise IBD: %v", net, rc)
		}
		mc := LookupNetworkProfile(net, "mainnet").LifecycleCapabilities()
		if mc["snapshot"] || !mc["ibd"] {
			t.Fatalf("%s mainnet capabilities=%v", net, mc)
		}
	}
	tronSteps := LookupNetworkProfile("tron", "mainnet").SupportedLifecycleSteps()
	wantTron := []string{"ports", "install", "snapshot", "start", "run"}
	if !slices.Equal(tronSteps, wantTron) {
		t.Fatalf("tron supported_steps=%v want %v", tronSteps, wantTron)
	}
	if !LookupNetworkProfile("tron", "mainnet").LifecycleCapabilities()["snapshot"] {
		t.Fatal("tron mainnet must advertise capabilities.snapshot")
	}
	if btc.NodeBinaryHint != "bitcoind" || !btc.AutoStartNode {
		t.Fatalf("bitcoin profile incomplete: %+v", btc)
	}
	if btc.DefaultPublicPort != 39290 || btc.DefaultAgentPort != 39390 {
		t.Fatalf("bitcoin ports must not collide with tron: pub=%d agent=%d", btc.DefaultPublicPort, btc.DefaultAgentPort)
	}
	tron := LookupNetworkProfile("tron", "mainnet")
	if btc.DefaultPublicPort == tron.DefaultPublicPort || btc.DefaultAgentPort == tron.DefaultAgentPort {
		t.Fatal("bitcoin/tron mainnet ports collide")
	}
	if !slices.Contains(ListKnownNetworks(), "bitcoin") || !slices.Contains(ListKnownNetworks(), "tron") {
		t.Fatalf("ListKnownNetworks incomplete: %v", ListKnownNetworks())
	}

	sol := LookupNetworkProfile("solana", "mainnet")
	if sol.SnapshotPolicy != SnapshotNever {
		t.Fatalf("solana must not use TRON snapshot: %+v", sol)
	}
	if sol.HasExtra(StepSnapshot) {
		t.Fatal("solana must not list snapshot extra")
	}
	if sol.AdvertiseIBD() || sol.LifecycleCapabilities()["ibd"] {
		t.Fatal("solana SkipIBD: AdvertiseIBD must be false")
	}
	if sol.DefaultPublicPort != 39490 || sol.DefaultAgentPort != 39590 || sol.DefaultNodeHTTP != 8899 {
		t.Fatalf("solana mainnet ports: %+v", sol)
	}
	if !slices.Contains(ListKnownNetworks(), "solana") {
		t.Fatalf("ListKnownNetworks missing solana: %v", ListKnownNetworks())
	}

	eth := LookupNetworkProfile("ethereum", "mainnet")
	if eth.SnapshotPolicy != SnapshotNever || eth.HasExtra(StepSnapshot) {
		t.Fatalf("ethereum must be EL/CL sync only (no snapshot): %+v", eth)
	}
	if eth.DefaultPublicPort != 39690 || eth.DefaultAgentPort != 39790 || eth.DefaultNodeHTTP != 8545 {
		t.Fatalf("ethereum mainnet ports: %+v", eth)
	}
	ethCaps := eth.LifecycleCapabilities()
	if ethCaps["snapshot"] || !ethCaps["ibd"] {
		t.Fatalf("ethereum capabilities=%v", ethCaps)
	}
	if eth.ServiceUnit() != "ethereum-geth-mainnet.service" {
		t.Fatalf("ethereum service unit=%q", eth.ServiceUnit())
	}
	if !slices.Contains(ListKnownNetworks(), "ethereum") {
		t.Fatalf("ListKnownNetworks missing ethereum: %v", ListKnownNetworks())
	}

	bsc := LookupNetworkProfile("bsc", "mainnet")
	if bsc.SnapshotPolicy != SnapshotRequired || !bsc.HasExtra(StepSnapshot) {
		t.Fatalf("bsc must require official snapshot ExtraStep: %+v", bsc)
	}
	if !bsc.AutoSnapshot || bsc.DefaultSnapshotURL == "" {
		t.Fatalf("bsc must auto-start official snapshot: %+v", bsc)
	}
	if bsc.DefaultPublicPort != 39890 || bsc.DefaultAgentPort != 39990 || bsc.DefaultNodeHTTP != 8575 {
		t.Fatalf("bsc mainnet ports: %+v", bsc)
	}
	if bsc.DefaultPublicPort == eth.DefaultPublicPort || bsc.DefaultNodeHTTP == eth.DefaultNodeHTTP {
		t.Fatal("bsc/ethereum mainnet ports collide")
	}
	bscCaps := bsc.LifecycleCapabilities()
	if !bscCaps["snapshot"] || !bscCaps["ibd"] {
		t.Fatalf("bsc capabilities=%v", bscCaps)
	}
	if bsc.ServiceUnit() != "bsc-mainnet.service" {
		t.Fatalf("bsc service unit=%q", bsc.ServiceUnit())
	}
	if !slices.Contains(ListKnownNetworks(), "bsc") {
		t.Fatalf("ListKnownNetworks missing bsc: %v", ListKnownNetworks())
	}
	if !slices.Contains(ListKnownEnvs("bsc"), "testnet") || !slices.Contains(ListKnownEnvs("bsc"), "mainnet") {
		t.Fatalf("bsc envs incomplete: %v", ListKnownEnvs("bsc"))
	}

	unknown := LookupNetworkProfile("otherchain", "mainnet")
	if unknown.SnapshotPolicy != SnapshotNever {
		t.Fatalf("unknown network must not inherit TRON snapshot: %+v", unknown)
	}
	if unknown.HasExtra(StepSnapshot) {
		t.Fatal("unknown network must not list snapshot extra")
	}
	if !unknown.AutoStartNode {
		t.Fatal("fallback should still allow AutoStartNode")
	}
}

func TestResolveLifecycleUsesCatalogNotEnvSwitch(t *testing.T) {
	req := resolveLifecycleProfile(nodeLifecycleInput{
		Network: "tron", Env: "mainnet", SnapEnabled: false,
	})
	if !req.SnapshotRequired || !req.IncludeSnapshot || !req.AutoSnapshot {
		t.Fatalf("required profile: %+v", req)
	}

	opt := resolveLifecycleProfile(nodeLifecycleInput{
		Network: "tron", Env: "shasta", SnapEnabled: false,
	})
	if opt.IncludeSnapshot || opt.SnapshotRequired {
		t.Fatalf("optional idle shasta must omit snapshot: %+v", opt)
	}

	foreign := resolveLifecycleProfile(nodeLifecycleInput{
		Network: "solana", Env: "mainnet", SnapEnabled: true,
	})
	if foreign.IncludeSnapshot || foreign.SnapshotRequired {
		t.Fatalf("foreign network must not inherit TRON snapshot: %+v", foreign)
	}
}

func TestRegisterNetworkProfileExtension(t *testing.T) {
	RegisterNetworkProfile(NetworkProfile{
		Network:        "testchain",
		Env:            "devnet",
		DisplayName:    "Testchain Devnet",
		SnapshotPolicy: SnapshotNever,
		AutoStartNode:  true,
		ServicePrefix:  "testchain",
	})
	p := LookupNetworkProfile("testchain", "devnet")
	if p.DisplayName != "Testchain Devnet" {
		t.Fatalf("got %+v", p)
	}
	if p.HasExtra(StepSnapshot) {
		t.Fatal("SnapshotNever must not invent ExtraSteps")
	}
	if !slices.Contains(ListKnownEnvs("testchain"), "devnet") {
		t.Fatal("registered env must appear in ListKnownEnvs")
	}
}

func TestAllNetworkProfilesSortedUnique(t *testing.T) {
	all := AllNetworkProfiles()
	if len(all) < 3 {
		t.Fatalf("len=%d", len(all))
	}
	seen := map[string]bool{}
	prev := ""
	for _, p := range all {
		if p.ID == "" {
			t.Fatalf("empty id: %+v", p)
		}
		if seen[p.ID] {
			t.Fatalf("duplicate id %q", p.ID)
		}
		seen[p.ID] = true
		if prev != "" && p.ID < prev {
			t.Fatalf("not sorted: %q after %q", p.ID, prev)
		}
		prev = p.ID
	}
}

func TestCatalogUpstreamHTTP(t *testing.T) {
	stellar := LookupNetworkProfile("stellar", "mainnet")
	if got := stellar.CatalogUpstreamHTTP(); got != 8000 {
		t.Fatalf("stellar CatalogUpstreamHTTP=%d want 8000", got)
	}
	tron := LookupNetworkProfile("tron", "mainnet")
	if !tron.PreferEnvUpstream {
		t.Fatal("tron profile must PreferEnvUpstream (return 0)")
	}
	if got := tron.CatalogUpstreamHTTP(); got != 0 {
		t.Fatalf("tron CatalogUpstreamHTTP=%d want 0 (keep env)", got)
	}
	empty := NetworkProfile{}
	if got := empty.CatalogUpstreamHTTP(); got != 0 {
		t.Fatalf("empty CatalogUpstreamHTTP=%d want 0", got)
	}
	upPort := 18090
	if p := stellar.CatalogUpstreamHTTP(); p > 0 {
		upPort = p
	}
	if upPort != 8000 {
		t.Fatalf("stellar stale env not overridden: upPort=%d", upPort)
	}
}
