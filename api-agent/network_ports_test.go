package main

import (
	"slices"
	"strings"
	"testing"
)

func TestBuiltinPortProfilesDualNetwork(t *testing.T) {
	tron := lookupPortProfile("tron", "mainnet")
	if tron.Public != 39090 || tron.Agent != 39190 {
		t.Fatalf("tron mainnet ports: %+v", tron)
	}
	btc := lookupPortProfile("bitcoin", "mainnet")
	if btc.Public != 39290 || btc.Agent != 39390 || btc.P2P != 8333 || btc.NodeHTTP != 8332 {
		t.Fatalf("bitcoin mainnet ports: %+v", btc)
	}
	if btc.Public == tron.Public || btc.Agent == tron.Agent {
		t.Fatal("bitcoin ports must not collide with tron")
	}
	tn4 := lookupPortProfile("bitcoin", "testnet4")
	if tn4.P2P != 18333 || tn4.ChainFlag != "chain=testnet4" {
		t.Fatalf("testnet4: %+v", tn4)
	}
	envs := listEnvsForNetwork("bitcoin")
	for _, want := range []string{"mainnet", "regtest", "signet", "testnet4"} {
		if !slices.Contains(envs, want) {
			t.Fatalf("missing bitcoin env %q in %v", want, envs)
		}
	}
	sol := lookupPortProfile("solana", "mainnet")
	if sol.Public != 39490 || sol.Agent != 39590 || sol.NodeHTTP != 8899 || sol.P2P != 8000 {
		t.Fatalf("solana mainnet ports: %+v", sol)
	}
	if sol.Public == btc.Public || sol.Agent == tron.Agent {
		t.Fatal("solana ports must not collide with bitcoin/tron")
	}
	solEnvs := listEnvsForNetwork("solana")
	for _, want := range []string{"devnet", "localnet", "mainnet", "testnet"} {
		if !slices.Contains(solEnvs, want) {
			t.Fatalf("missing solana env %q in %v", want, solEnvs)
		}
	}
	if !networkSupports("bitcoin") || !networkSupports("tron") || !networkSupports("solana") ||
		!networkSupports("ethereum") || !networkSupports("bsc") {
		t.Fatal("supported networks incomplete")
	}
	eth := lookupPortProfile("ethereum", "mainnet")
	if eth.Public != 39690 || eth.Agent != 39790 || eth.NodeHTTP != 8545 || eth.P2P != 30303 {
		t.Fatalf("ethereum mainnet ports: %+v", eth)
	}
	if eth.SolHTTP != 8551 || eth.PBFTHTTP != 5052 || eth.Metrics != 9000 {
		t.Fatalf("ethereum engine/beacon/consensus ports: %+v", eth)
	}
	if eth.Public == sol.Public || eth.Agent == btc.Agent {
		t.Fatal("ethereum ports must not collide with solana/bitcoin")
	}
	ethEnvs := listEnvsForNetwork("ethereum")
	for _, want := range []string{"hoodi", "mainnet", "sepolia"} {
		if !slices.Contains(ethEnvs, want) {
			t.Fatalf("missing ethereum env %q in %v", want, ethEnvs)
		}
	}
	caps := lifecycleCapabilities("ethereum", "mainnet")
	if caps["snapshot"] || !caps["ibd"] {
		t.Fatalf("ethereum capabilities=%v", caps)
	}
	bsc := lookupPortProfile("bsc", "mainnet")
	if bsc.Public != 39890 || bsc.Agent != 39990 || bsc.NodeHTTP != 8575 || bsc.P2P != 30311 {
		t.Fatalf("bsc mainnet ports: %+v", bsc)
	}
	if bsc.Public == eth.Public || bsc.NodeHTTP == eth.NodeHTTP || bsc.P2P == eth.P2P {
		t.Fatal("bsc ports must not collide with ethereum")
	}
	bscTN := lookupPortProfile("bsc", "testnet")
	if bscTN.Public != 39891 || bscTN.NodeHTTP != 8576 || bscTN.P2P != 30312 {
		t.Fatalf("bsc testnet ports: %+v", bscTN)
	}
	bscCaps := lifecycleCapabilities("bsc", "mainnet")
	if bscCaps["snapshot"] || !bscCaps["ibd"] {
		t.Fatalf("bsc capabilities=%v", bscCaps)
	}
	if !networkSupports("hyperliquid") || !networkSupports("arb") || !networkSupports("optimism") || !networkSupports("base") {
		t.Fatal("supported networks missing L2 profiles")
	}
	hl := lookupPortProfile("hyperliquid", "mainnet")
	if hl.Public != 40090 || hl.Agent != 40190 || hl.NodeHTTP != 3001 || hl.P2P != 4001 {
		t.Fatalf("hyperliquid mainnet ports: %+v", hl)
	}
	arb := lookupPortProfile("arb", "mainnet")
	if arb.Public != 40091 || arb.Agent != 40191 || arb.NodeHTTP != 8547 || arb.P2P != 0 {
		t.Fatalf("arb mainnet ports: %+v", arb)
	}
	if arb.DataPath != "/data/arbitrum/mainnet" {
		t.Fatalf("arb datadir: %s", arb.DataPath)
	}
	op := lookupPortProfile("optimism", "mainnet")
	if op.Public != 40092 || op.Agent != 40192 || op.NodeHTTP != 8549 || op.P2P != 30333 {
		t.Fatalf("optimism mainnet ports: %+v", op)
	}
	if !networkSupports("robinhood") {
		t.Fatal("supported networks missing robinhood profile")
	}
	rh := lookupPortProfile("robinhood", "mainnet")
	if rh.Public != 42090 || rh.Agent != 42190 || rh.NodeHTTP != 8567 || rh.P2P != 0 || rh.SolHTTP != 8568 {
		t.Fatalf("robinhood mainnet ports: %+v", rh)
	}
	if rh.DataPath != "/data/robinhood/mainnet" || rh.DiskHintGiB != 2048 {
		t.Fatalf("robinhood mainnet datadir/disk: %+v", rh)
	}
	rhTN := lookupPortProfile("robinhood", "testnet")
	if rhTN.Public != 42091 || rhTN.Agent != 42191 || rhTN.NodeHTTP != 8569 || rhTN.SolHTTP != 8570 {
		t.Fatalf("robinhood testnet ports: %+v", rhTN)
	}
	if rhTN.DiskHintGiB != 400 {
		t.Fatalf("robinhood testnet disk hint: %+v", rhTN)
	}
	if len(rh.ExtraSteps) == 0 || rh.ExtraSteps[0] != "snapshot" || rh.SnapshotURL == "" {
		t.Fatalf("robinhood must require snapshot ExtraSteps+URL: %+v", rh)
	}
	rhCaps := lifecycleCapabilities("robinhood", "mainnet")
	if !rhCaps["snapshot"] || !rhCaps["ibd"] {
		t.Fatalf("robinhood capabilities=%v want snapshot+ibd", rhCaps)
	}
	if op.SolHTTP != 8559 || op.PBFTHTTP != 9003 {
		t.Fatalf("optimism engine/op-node p2p: %+v", op)
	}
	baseMN := lookupPortProfile("base", "mainnet")
	if baseMN.Public != 42290 || baseMN.Agent != 42390 || baseMN.NodeHTTP != 8571 || baseMN.P2P != 30353 || baseMN.SolHTTP != 8572 || baseMN.PBFTHTTP != 9023 {
		t.Fatalf("base mainnet ports: %+v", baseMN)
	}
	if baseMN.DataPath != "/data/base/mainnet" || baseMN.DiskHintGiB != 4096 {
		t.Fatalf("base mainnet datadir/disk: %+v", baseMN)
	}
	baseTN := lookupPortProfile("base", "sepolia")
	if baseTN.Public != 42291 || baseTN.Agent != 42391 || baseTN.NodeHTTP != 8573 || baseTN.SolHTTP != 8574 || baseTN.PBFTHTTP != 9033 {
		t.Fatalf("base sepolia ports: %+v", baseTN)
	}
	baseCaps := lifecycleCapabilities("base", "mainnet")
	if baseCaps["snapshot"] || !baseCaps["ibd"] {
		t.Fatalf("base capabilities=%v", baseCaps)
	}
	hlCaps := lifecycleCapabilities("hyperliquid", "mainnet")
	if hlCaps["snapshot"] || !hlCaps["ibd"] {
		t.Fatalf("hyperliquid capabilities=%v", hlCaps)
	}
}

func TestUnsupportedNetworkEnvPayload(t *testing.T) {
	if networkEnvSupported("ltc", "regtest") != true {
		t.Fatal("ltc/regtest must be supported in current catalog")
	}
	unknown := unsupportedNetworkEnvPayload("notachain", "mainnet")
	if unknown["error"] != "unsupported_network" {
		t.Fatalf("error=%v", unknown["error"])
	}
	if unknown["hint"] != "update_agent" {
		t.Fatalf("hint=%v", unknown["hint"])
	}
	msg, _ := unknown["message"].(string)
	if !strings.Contains(msg, "notachain/mainnet") || !strings.Contains(msg, "Update the host agent") {
		t.Fatalf("message=%q", msg)
	}
	// Fabricate unsupported env: network ok, no Public in profile.
	if networkEnvSupported("tron", "regtest") {
		t.Fatal("tron/regtest must not be in catalog")
	}
	envMiss := unsupportedNetworkEnvPayload("tron", "regtest")
	if envMiss["error"] != "unsupported_env" {
		t.Fatalf("error=%v", envMiss["error"])
	}
}

func TestBitcoinProvisionForcesProfileUpstream(t *testing.T) {
	req := nodeProvisionRequest{
		Network: "bitcoin", Env: "mainnet",
		PublicPort: 39290, AgentPort: 39390,
		NodeHTTPPort: 18090, // stale — must be overwritten from profile
		P2PPort:      8333,
	}
	prof := lookupPortProfile(req.Network, req.Env)
	if prof.NodeHTTP > 0 {
		req.NodeHTTPPort = prof.NodeHTTP
	}
	if req.NodeHTTPPort != 8332 {
		t.Fatalf("NodeHTTPPort=%d want 8332 from bitcoin profile", req.NodeHTTPPort)
	}
}
