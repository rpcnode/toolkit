package main

import (
	"fmt"
	"path/filepath"
	"sort"
	"strings"
)

// Port / profile defaults for plan+provision.
// MUST stay aligned with system-agent/network_profiles.go builtinNetworkProfiles().

type networkPortProfile struct {
	Network  string
	Env      string
	Public   int
	Agent    int
	NodeHTTP int // java-tron HTTP or bitcoind JSON-RPC
	P2P      int
	// TRON aux (multi-env on one host) — see deploy/nodes/tron/DESIGN.md.
	// SolHTTP/PBFTHTTP are reserved + disabled; must never equal another env's NodeHTTP.
	SolHTTP     int
	PBFTHTTP    int
	GRPC        int
	GRPCSol     int
	GRPCPbft    int
	Metrics     int
	SnapshotURL string // TRON only — written into toolkit.env on provision
	ChainFlag   string
	WatchSlug   string
	ZMQRawBlock int
	ZMQRawTx    int
	DiskHintGiB float64
	OptPath     string
	EtcPath     string
	DataPath    string
	ServiceUnit string   // bitcoin-mainnet.service / tron-mainnet.service
	ExtraSteps  []string // after install, before start — must match system-agent ExtraSteps
}

// isCanonicalPerNodeAgentPort — leaf Agent API ports (never host tip :38990).
// Keep in sync with install/agent.sh is_per_node_agent_port.
func isCanonicalPerNodeAgentPort(port int) bool {
	if port <= 0 {
		return false
	}
	for _, p := range builtinPortProfiles() {
		if p.Agent == port {
			return true
		}
	}
	return false
}

// supportedLifecycleSteps — static plan: ports → install → [ExtraSteps…] → start → run.
func supportedLifecycleSteps(network, env string) []string {
	p := lookupPortProfile(network, env)
	steps := []string{"ports", "install"}
	if len(p.ExtraSteps) > 0 {
		steps = append(steps, p.ExtraSteps...)
	}
	return append(steps, "start", "run")
}

// lifecycleCapabilities — boolean flags for UI (snapshot / ibd).
func lifecycleCapabilities(network, env string) map[string]bool {
	p := lookupPortProfile(network, env)
	snap := false
	for _, s := range p.ExtraSteps {
		if s == "snapshot" {
			snap = true
			break
		}
	}
	// Snapshot-then-catch-up (robinhood) keeps ibd=true so Sync UI stays after snapshot.
	ibdCore := strings.EqualFold(p.Network, "bitcoin") ||
		strings.EqualFold(p.Network, "doge") ||
		strings.EqualFold(p.Network, "ltc") ||
		strings.EqualFold(p.Network, "dash") ||
		strings.EqualFold(p.Network, "bch") ||
		strings.EqualFold(p.Network, "cardano") ||
		strings.EqualFold(p.Network, "ethereum") ||
		strings.EqualFold(p.Network, "bsc") ||
		strings.EqualFold(p.Network, "hyperliquid") ||
		strings.EqualFold(p.Network, "arb") ||
		strings.EqualFold(p.Network, "robinhood") ||
		strings.EqualFold(p.Network, "optimism") ||
		strings.EqualFold(p.Network, "base") ||
		strings.EqualFold(p.Network, "xrpl") ||
		strings.EqualFold(p.Network, "stellar") ||
		strings.EqualFold(p.Network, "ton") ||
		strings.EqualFold(p.Network, "etc") ||
		strings.EqualFold(p.Network, "zcash") ||
		strings.EqualFold(p.Network, "sui") ||
		strings.EqualFold(p.Network, "aptos") ||
		strings.EqualFold(p.Network, "avalanche")
	// Snapshot-then-catch-up keeps ibd=true so Sync UI stays after Snapshot (Robinhood / Sui).
	ibd := ibdCore && (!snap || strings.EqualFold(p.Network, "robinhood") || strings.EqualFold(p.Network, "sui"))
	// Regtest is local — do not advertise IBD sync UI.
	if ibd && strings.EqualFold(strings.TrimSpace(env), "regtest") {
		ibd = false
	}
	// Solana catch-up is not Bitcoin/Ethereum IBD — keep capabilities.ibd false for solana.
	return map[string]bool{
		"snapshot":         snap,
		"ibd":              ibd,
		"one_env_per_host": networkOneEnvPerHost(network),
	}
}

func supportedNetworks() []string {
	return []string{"aptos", "arb", "avalanche", "base", "bch", "bitcoin", "bsc", "cardano", "dash", "doge", "etc", "ethereum", "hyperliquid", "ltc", "optimism", "robinhood", "solana", "stellar", "sui", "ton", "tron", "xrpl", "zcash"}
}

func networkSupports(network string) bool {
	n := strings.ToLower(strings.TrimSpace(network))
	for _, s := range supportedNetworks() {
		if s == n {
			return true
		}
	}
	return false
}

// networkEnvSupported — true when builtin catalog has canonical ports for network/env.
func networkEnvSupported(network, env string) bool {
	if !networkSupports(network) {
		return false
	}
	return lookupPortProfile(network, env).Public > 0
}

// unsupportedNetworkEnvPayload — plan/provision rejection when tip agent lacks network or env.
func unsupportedNetworkEnvPayload(network, env string) map[string]any {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	ver := agentVersion()
	base := map[string]any{
		"ok":            false,
		"network":       network,
		"env":           env,
		"agent_version": ver,
		"hint":          "update_agent",
	}
	if !networkSupports(network) {
		base["error"] = "unsupported_network"
		base["message"] = fmt.Sprintf(
			"%s/%s is not supported by this agent (v%s). Update the host agent to the latest version.",
			network, env, ver,
		)
		base["supported_networks"] = supportedNetworks()
		return base
	}
	base["error"] = "unsupported_env"
	base["message"] = fmt.Sprintf(
		"%s/%s is not supported by this agent (v%s). Update the host agent to the latest version.",
		network, env, ver,
	)
	base["supported_envs"] = listEnvsForNetwork(network)
	base["supported_networks"] = supportedNetworks()
	return base
}

func lookupPortProfile(network, env string) networkPortProfile {
	net := strings.ToLower(strings.TrimSpace(network))
	if net == "" {
		net = "tron"
	}
	e := strings.ToLower(strings.TrimSpace(env))
	if e == "" {
		e = "mainnet"
	}
	if net == "avalanche" {
		e = normalizeAvalancheEnv(e)
	}

	for _, p := range builtinPortProfiles() {
		if p.Network == net && p.Env == e {
			return p
		}
	}

	// Unknown: safe empty ports (caller falls back / errors).
	return networkPortProfile{
		Network:     net,
		Env:         e,
		ServiceUnit: net + "-" + e + ".service",
		OptPath:     "/opt/" + net + "/" + e,
		EtcPath:     "/etc/" + net + "/" + e,
		DataPath:    "/data/" + net + "/" + e,
	}
}

func listEnvsForNetwork(network string) []string {
	net := strings.ToLower(strings.TrimSpace(network))
	seen := map[string]struct{}{}
	out := []string{}
	for _, p := range builtinPortProfiles() {
		if p.Network != net {
			continue
		}
		if _, ok := seen[p.Env]; ok {
			continue
		}
		seen[p.Env] = struct{}{}
		out = append(out, p.Env)
	}
	sort.Strings(out)
	return out
}

func builtinPortProfiles() []networkPortProfile {
	tronSnap := []string{"snapshot"}
	const mainSnap = "http://34.86.86.229/backup20260808/FullNode_output-directory.tgz"
	const nileSnap = "https://snapshots.nileex.io/backup20260809/FullNode_output-directory.tgz"
	return []networkPortProfile{
		{Network: "tron", Env: "mainnet", Public: 39090, Agent: 39190, NodeHTTP: 18090, P2P: 18888,
			SolHTTP: 18190, PBFTHTTP: 18191, GRPC: 50051, GRPCSol: 50061, GRPCPbft: 50071, Metrics: 9527,
			SnapshotURL: mainSnap, DiskHintGiB: 1024,
			ServiceUnit: "tron-mainnet.service", ExtraSteps: tronSnap,
			OptPath: "/opt/tron/mainnet", EtcPath: "/etc/tron/mainnet", DataPath: "/data/tron/mainnet"},
		{Network: "tron", Env: "nile", Public: 39091, Agent: 39191, NodeHTTP: 18091, P2P: 18889,
			SolHTTP: 18290, PBFTHTTP: 18291, GRPC: 50151, GRPCSol: 50161, GRPCPbft: 50171, Metrics: 9528,
			SnapshotURL: nileSnap, DiskHintGiB: 256,
			ServiceUnit: "tron-nile.service", ExtraSteps: tronSnap,
			OptPath: "/opt/tron/nile", EtcPath: "/etc/tron/nile", DataPath: "/data/tron/nile"},
		{Network: "tron", Env: "shasta", Public: 39092, Agent: 39192, NodeHTTP: 18092, P2P: 18890,
			SolHTTP: 18390, PBFTHTTP: 18391, GRPC: 50251, GRPCSol: 50261, GRPCPbft: 50271, Metrics: 9529,
			DiskHintGiB: 256,
			ServiceUnit: "tron-shasta.service", ExtraSteps: tronSnap,
			OptPath: "/opt/tron/shasta", EtcPath: "/etc/tron/shasta", DataPath: "/data/tron/shasta"},
		// Bitcoin — non-overlapping with TRON (DESIGN §5). ExtraSteps empty = no snapshot.
		{Network: "bitcoin", Env: "mainnet", Public: 39290, Agent: 39390, NodeHTTP: 8332, P2P: 8333,
			WatchSlug: "bitcoin", ZMQRawBlock: 28332, ZMQRawTx: 28333, DiskHintGiB: 1024,
			ServiceUnit: "bitcoin-mainnet.service",
			OptPath:     "/opt/bitcoin/mainnet", EtcPath: "/etc/bitcoin/mainnet", DataPath: "/data/bitcoin/mainnet"},
		{Network: "bitcoin", Env: "testnet4", Public: 39291, Agent: 39391, NodeHTTP: 18332, P2P: 18333,
			ChainFlag: "chain=testnet4", WatchSlug: "bitcoin-testnet4",
			ZMQRawBlock: 28342, ZMQRawTx: 28343, DiskHintGiB: 128,
			ServiceUnit: "bitcoin-testnet4.service",
			OptPath:     "/opt/bitcoin/testnet4", EtcPath: "/etc/bitcoin/testnet4", DataPath: "/data/bitcoin/testnet4"},
		{Network: "bitcoin", Env: "signet", Public: 39292, Agent: 39392, NodeHTTP: 38332, P2P: 38333,
			ChainFlag: "signet=1", WatchSlug: "bitcoin-signet",
			ZMQRawBlock: 28352, ZMQRawTx: 28353, DiskHintGiB: 64,
			ServiceUnit: "bitcoin-signet.service",
			OptPath:     "/opt/bitcoin/signet", EtcPath: "/etc/bitcoin/signet", DataPath: "/data/bitcoin/signet"},
		{Network: "bitcoin", Env: "regtest", Public: 39293, Agent: 39393, NodeHTTP: 18443, P2P: 18444,
			ChainFlag: "regtest=1", WatchSlug: "bitcoin-regtest",
			ZMQRawBlock: 28362, ZMQRawTx: 28363, DiskHintGiB: 8,
			ServiceUnit: "bitcoin-regtest.service",
			OptPath:     "/opt/bitcoin/regtest", EtcPath: "/etc/bitcoin/regtest", DataPath: "/data/bitcoin/regtest"},
		// Solana — non-overlapping with TRON/Bitcoin (DESIGN §5). ExtraSteps empty = no snapshot.
		{Network: "solana", Env: "mainnet", Public: 39490, Agent: 39590, NodeHTTP: 8899, P2P: 8000,
			ChainFlag: "mainnet-beta", WatchSlug: "solana", DiskHintGiB: 2048,
			ServiceUnit: "solana-mainnet.service",
			OptPath:     "/opt/solana/mainnet", EtcPath: "/etc/solana/mainnet", DataPath: "/data/solana/mainnet"},
		{Network: "solana", Env: "testnet", Public: 39491, Agent: 39591, NodeHTTP: 8891, P2P: 8100,
			ChainFlag: "testnet", WatchSlug: "solana-testnet", DiskHintGiB: 1024,
			ServiceUnit: "solana-testnet.service",
			OptPath:     "/opt/solana/testnet", EtcPath: "/etc/solana/testnet", DataPath: "/data/solana/testnet"},
		{Network: "solana", Env: "devnet", Public: 39492, Agent: 39592, NodeHTTP: 8893, P2P: 8200,
			ChainFlag: "devnet", WatchSlug: "solana-devnet", DiskHintGiB: 512,
			ServiceUnit: "solana-devnet.service",
			OptPath:     "/opt/solana/devnet", EtcPath: "/etc/solana/devnet", DataPath: "/data/solana/devnet"},
		{Network: "solana", Env: "localnet", Public: 39493, Agent: 39593, NodeHTTP: 18899, P2P: 0,
			ChainFlag: "localnet", WatchSlug: "solana-localnet", DiskHintGiB: 8,
			ServiceUnit: "solana-localnet.service",
			OptPath:     "/opt/solana/localnet", EtcPath: "/etc/solana/localnet", DataPath: "/data/solana/localnet"},
		// Ethereum — Geth + Lighthouse EL/CL (no TRON snapshot).
		// SolHTTP=Engine, PBFTHTTP=Beacon, Metrics=ConsensusP2P (CL gossip).
		// Metrics=CL gossip base; Lighthouse also binds QUIC on port+1 — leave ≥100 gap between envs.
		{Network: "ethereum", Env: "mainnet", Public: 39690, Agent: 39790, NodeHTTP: 8545, P2P: 30303,
			SolHTTP: 8551, PBFTHTTP: 5052, Metrics: 9000,
			ChainFlag: "mainnet", WatchSlug: "ethereum", DiskHintGiB: 2048,
			ServiceUnit: "ethereum-geth-mainnet.service",
			OptPath:     "/opt/ethereum/mainnet", EtcPath: "/etc/ethereum/mainnet", DataPath: "/data/ethereum/mainnet"},
		{Network: "ethereum", Env: "sepolia", Public: 39691, Agent: 39791, NodeHTTP: 8546, P2P: 30313,
			SolHTTP: 8552, PBFTHTTP: 5053, Metrics: 9100,
			ChainFlag: "sepolia", WatchSlug: "ethereum-sepolia", DiskHintGiB: 400,
			ServiceUnit: "ethereum-geth-sepolia.service",
			OptPath:     "/opt/ethereum/sepolia", EtcPath: "/etc/ethereum/sepolia", DataPath: "/data/ethereum/sepolia"},
		{Network: "ethereum", Env: "hoodi", Public: 39692, Agent: 39792, NodeHTTP: 8547, P2P: 30323,
			SolHTTP: 8553, PBFTHTTP: 5054, Metrics: 9200,
			ChainFlag: "hoodi", WatchSlug: "ethereum-hoodi", DiskHintGiB: 400,
			ServiceUnit: "ethereum-geth-hoodi.service",
			OptPath:     "/opt/ethereum/hoodi", EtcPath: "/etc/ethereum/hoodi", DataPath: "/data/ethereum/hoodi"},
		// BSC — bnb-chain/bsc geth fork (Parlia). Non-overlapping with ethereum 3969x/3979x / 8545–8547 / 30303+.
		{Network: "bsc", Env: "mainnet", Public: 39890, Agent: 39990, NodeHTTP: 8575, P2P: 30311,
			ChainFlag: "56", WatchSlug: "bsc", DiskHintGiB: 2048,
			ServiceUnit: "bsc-mainnet.service",
			OptPath:     "/opt/bsc/mainnet", EtcPath: "/etc/bsc/mainnet", DataPath: "/data/bsc/mainnet"},
		{Network: "bsc", Env: "testnet", Public: 39891, Agent: 39991, NodeHTTP: 8576, P2P: 30312,
			ChainFlag: "97", WatchSlug: "bsc-testnet", DiskHintGiB: 400,
			ServiceUnit: "bsc-testnet.service",
			OptPath:     "/opt/bsc/testnet", EtcPath: "/etc/bsc/testnet", DataPath: "/data/bsc/testnet"},
		// Hyperliquid — hl-visor non-validator. Gossip 4001–4002; NodeHTTP=3001 (EVM at /evm).
		{Network: "hyperliquid", Env: "mainnet", Public: 40090, Agent: 40190, NodeHTTP: 3001, P2P: 4001,
			ChainFlag: "Mainnet", WatchSlug: "hyperliquid", DiskHintGiB: 1024,
			ServiceUnit: "hyperliquid-mainnet.service",
			OptPath:     "/opt/hyperliquid/mainnet", EtcPath: "/etc/hyperliquid/mainnet", DataPath: "/data/hyperliquid/mainnet"},
		// NodeHTTP :3001 — HL --serve-eth-rpc hard-binds HyperEVM (same as mainnet; one_env_per_host).
		{Network: "hyperliquid", Env: "testnet", Public: 40093, Agent: 40193, NodeHTTP: 3001, P2P: 4011,
			ChainFlag: "Testnet", WatchSlug: "hyperliquid-testnet", DiskHintGiB: 512,
			ServiceUnit: "hyperliquid-testnet.service",
			OptPath:     "/opt/hyperliquid/testnet", EtcPath: "/etc/hyperliquid/testnet", DataPath: "/data/hyperliquid/testnet"},
		// Arbitrum — nitro-node full (pruned via --init.latest=pruned on first start). No P2P. SolHTTP=WS.
		{Network: "arb", Env: "mainnet", Public: 40091, Agent: 40191, NodeHTTP: 8547, P2P: 0,
			SolHTTP: 8548, ChainFlag: "42161", WatchSlug: "arb", DiskHintGiB: 1024,
			ServiceUnit: "arb-mainnet.service",
			OptPath:     "/opt/arbitrum/mainnet", EtcPath: "/etc/arbitrum/mainnet", DataPath: "/data/arbitrum/mainnet"},
		{Network: "arb", Env: "sepolia", Public: 40094, Agent: 40194, NodeHTTP: 8657, P2P: 0,
			SolHTTP: 8658, ChainFlag: "421614", WatchSlug: "arb-sepolia", DiskHintGiB: 400,
			ServiceUnit: "arb-sepolia.service",
			OptPath:     "/opt/arbitrum/sepolia", EtcPath: "/etc/arbitrum/sepolia", DataPath: "/data/arbitrum/sepolia"},
		// Robinhood Chain — Arbitrum Nitro (Orbit), same nitro-node binary as arb. No P2P. SolHTTP=WS.
		// Robinhood — required pruned nitro snapshot (--init.url). Explorer CDN offchainlabs.
		{Network: "robinhood", Env: "mainnet", Public: 42090, Agent: 42190, NodeHTTP: 8567, P2P: 0,
			SolHTTP: 8568, ChainFlag: "4663", WatchSlug: "robinhood", DiskHintGiB: 2048,
			SnapshotURL: "https://robinhood-snapshots.offchainlabs.com/robinhood%20chain/2026-08-03-1432f687/",
			ServiceUnit: "robinhood-mainnet.service", ExtraSteps: []string{"snapshot"},
			OptPath: "/opt/robinhood/mainnet", EtcPath: "/etc/robinhood/mainnet", DataPath: "/data/robinhood/mainnet"},
		{Network: "robinhood", Env: "testnet", Public: 42091, Agent: 42191, NodeHTTP: 8569, P2P: 0,
			SolHTTP: 8570, ChainFlag: "46630", WatchSlug: "robinhood-testnet", DiskHintGiB: 400,
			SnapshotURL: "https://robinhood-snapshots.offchainlabs.com/robinhood%20chain%20sepolia/2026-08-06-dacda195/",
			ServiceUnit: "robinhood-testnet.service", ExtraSteps: []string{"snapshot"},
			OptPath: "/opt/robinhood/testnet", EtcPath: "/etc/robinhood/testnet", DataPath: "/data/robinhood/testnet"},
		// Optimism — op-geth + op-node. SolHTTP=Engine, PBFTHTTP=op-node P2P, Metrics=op-node RPC.
		{Network: "optimism", Env: "mainnet", Public: 40092, Agent: 40192, NodeHTTP: 8549, P2P: 30333,
			SolHTTP: 8559, PBFTHTTP: 9003, Metrics: 9545,
			ChainFlag: "op-mainnet", WatchSlug: "optimism", DiskHintGiB: 1024,
			ServiceUnit: "optimism-mainnet.service",
			OptPath:     "/opt/optimism/mainnet", EtcPath: "/etc/optimism/mainnet", DataPath: "/data/optimism/mainnet"},
		{Network: "optimism", Env: "sepolia", Public: 40095, Agent: 40195, NodeHTTP: 8649, P2P: 30343,
			SolHTTP: 8569, PBFTHTTP: 9013, Metrics: 9555,
			ChainFlag: "op-sepolia", WatchSlug: "optimism-sepolia", DiskHintGiB: 400,
			ServiceUnit: "optimism-sepolia.service",
			OptPath:     "/opt/optimism/sepolia", EtcPath: "/etc/optimism/sepolia", DataPath: "/data/optimism/sepolia"},
		// Base — base-reth-node + base-consensus (Base V1). SolHTTP=Engine, PBFTHTTP=consensus P2P, Metrics=WS.
		{Network: "base", Env: "mainnet", Public: 42290, Agent: 42390, NodeHTTP: 8571, P2P: 30353,
			SolHTTP: 8572, PBFTHTTP: 9023, Metrics: 8581,
			ChainFlag: "base", WatchSlug: "base", DiskHintGiB: 4096,
			ServiceUnit: "base-mainnet.service",
			OptPath:     "/opt/base/mainnet", EtcPath: "/etc/base/mainnet", DataPath: "/data/base/mainnet"},
		{Network: "base", Env: "sepolia", Public: 42291, Agent: 42391, NodeHTTP: 8573, P2P: 30354,
			SolHTTP: 8574, PBFTHTTP: 9033, Metrics: 8583,
			ChainFlag: "base-sepolia", WatchSlug: "base-sepolia", DiskHintGiB: 512,
			ServiceUnit: "base-sepolia.service",
			OptPath:     "/opt/base/sepolia", EtcPath: "/etc/base/sepolia", DataPath: "/data/base/sepolia"},
		// Zcash — stock zcashd. Free block after base 4229x/4239x.
		{Network: "zcash", Env: "mainnet", Public: 42490, Agent: 42590, NodeHTTP: 8232, P2P: 8233,
			WatchSlug: "zcash", DiskHintGiB: 300,
			ServiceUnit: "zcash-mainnet.service",
			OptPath:     "/opt/zcash/mainnet", EtcPath: "/etc/zcash/mainnet", DataPath: "/data/zcash/mainnet"},
		{Network: "zcash", Env: "testnet", Public: 42491, Agent: 42591, NodeHTTP: 18232, P2P: 18233,
			ChainFlag: "testnet=1", WatchSlug: "zcash-testnet", DiskHintGiB: 64,
			ServiceUnit: "zcash-testnet.service",
			OptPath:     "/opt/zcash/testnet", EtcPath: "/etc/zcash/testnet", DataPath: "/data/zcash/testnet"},
		// Sui — sui-node. Public/Agent after zcash; Metrics=prometheus (loopback).
		// Formal snapshot via sui-tool (Mysten free R2) — ExtraSteps Snapshot required (§1a).
		{Network: "sui", Env: "mainnet", Public: 42690, Agent: 42790, NodeHTTP: 9000, P2P: 8084,
			Metrics: 9184, WatchSlug: "sui", DiskHintGiB: 2048,
			SnapshotURL: "formal-r2://mainnet",
			ServiceUnit: "sui-mainnet.service", ExtraSteps: []string{"snapshot"},
			OptPath: "/opt/sui/mainnet", EtcPath: "/etc/sui/mainnet", DataPath: "/data/sui/mainnet"},
		{Network: "sui", Env: "testnet", Public: 42691, Agent: 42791, NodeHTTP: 9001, P2P: 8085,
			Metrics: 9185, WatchSlug: "sui-testnet", DiskHintGiB: 512,
			SnapshotURL: "formal-r2://testnet",
			ServiceUnit: "sui-testnet.service", ExtraSteps: []string{"snapshot"},
			OptPath: "/opt/sui/testnet", EtcPath: "/etc/sui/testnet", DataPath: "/data/sui/testnet"},
		// Aptos — aptos-node. Public/Agent after sui; Metrics=inspection (loopback).
		// Also pin admin_service to Metrics+10 (Aptos default admin :9102 binds even when disabled).
		{Network: "aptos", Env: "mainnet", Public: 42890, Agent: 42990, NodeHTTP: 8080, P2P: 6180,
			Metrics: 9101, WatchSlug: "aptos", DiskHintGiB: 2048,
			ServiceUnit: "aptos-mainnet.service",
			OptPath:     "/opt/aptos/mainnet", EtcPath: "/etc/aptos/mainnet", DataPath: "/data/aptos/mainnet"},
		{Network: "aptos", Env: "testnet", Public: 42891, Agent: 42991, NodeHTTP: 8081, P2P: 6182,
			Metrics: 9102, WatchSlug: "aptos-testnet", DiskHintGiB: 512,
			ServiceUnit: "aptos-testnet.service",
			OptPath:     "/opt/aptos/testnet", EtcPath: "/etc/aptos/testnet", DataPath: "/data/aptos/testnet"},
		// Avalanche — avalanchego C-Chain archive. Public/Agent after aptos; Metrics reserved (loopback inventory).
		{Network: "avalanche", Env: "mainnet", Public: 43090, Agent: 43190, NodeHTTP: 9650, P2P: 9651,
			Metrics: 9690, WatchSlug: "avalanche", DiskHintGiB: 4096,
			ServiceUnit: "avalanche-mainnet.service",
			OptPath:     "/opt/avalanche/mainnet", EtcPath: "/etc/avalanche/mainnet", DataPath: "/data/avalanche/mainnet"},
		{Network: "avalanche", Env: "fuji", Public: 43091, Agent: 43191, NodeHTTP: 9660, P2P: 9661,
			Metrics: 9691, ChainFlag: "fuji", WatchSlug: "avalanche-fuji", DiskHintGiB: 512,
			ServiceUnit: "avalanche-fuji.service",
			OptPath:     "/opt/avalanche/fuji", EtcPath: "/etc/avalanche/fuji", DataPath: "/data/avalanche/fuji"},
		// XRPL — stock xrpld. Non-overlapping with BTC 3929x/3939x and L2 4009x/4019x.
		{Network: "xrpl", Env: "mainnet", Public: 40290, Agent: 40390, NodeHTTP: 5005, P2P: 51235,
			SolHTTP: 51233, GRPC: 51251,
			ChainFlag: "mainnet", WatchSlug: "xrpl", DiskHintGiB: 1024,
			ServiceUnit: "xrpl-mainnet.service",
			OptPath:     "/opt/xrpl/mainnet", EtcPath: "/etc/xrpl/mainnet", DataPath: "/data/xrpl/mainnet"},
		{Network: "xrpl", Env: "testnet", Public: 40291, Agent: 40391, NodeHTTP: 5006, P2P: 51236,
			SolHTTP: 51234, GRPC: 51252,
			ChainFlag: "testnet", WatchSlug: "xrpl-testnet", DiskHintGiB: 128,
			ServiceUnit: "xrpl-testnet.service",
			OptPath:     "/opt/xrpl/testnet", EtcPath: "/etc/xrpl/testnet", DataPath: "/data/xrpl/testnet"},
		// Dogecoin — stock dogecoind. Free block after XRPL 4029x/4039x.
		{Network: "doge", Env: "mainnet", Public: 40490, Agent: 40590, NodeHTTP: 22555, P2P: 22556,
			WatchSlug: "doge", DiskHintGiB: 400,
			ServiceUnit: "doge-mainnet.service",
			OptPath:     "/opt/doge/mainnet", EtcPath: "/etc/doge/mainnet", DataPath: "/data/doge/mainnet"},
		{Network: "doge", Env: "testnet", Public: 40491, Agent: 40591, NodeHTTP: 44555, P2P: 44556,
			ChainFlag: "testnet=1", WatchSlug: "doge-testnet", DiskHintGiB: 64,
			ServiceUnit: "doge-testnet.service",
			OptPath:     "/opt/doge/testnet", EtcPath: "/etc/doge/testnet", DataPath: "/data/doge/testnet"},
		// Cardano — cardano-node + Ogmios JSON-RPC. P2P remapped off HL :3001.
		{Network: "cardano", Env: "mainnet", Public: 40690, Agent: 40790, NodeHTTP: 1337, P2P: 3003,
			Metrics: 12798, ChainFlag: "mainnet", WatchSlug: "cardano", DiskHintGiB: 400,
			SnapshotURL: "https://aggregator.release-mainnet.api.mithril.network/aggregator",
			ServiceUnit: "cardano-mainnet.service", ExtraSteps: []string{"snapshot"},
			OptPath:     "/opt/cardano/mainnet", EtcPath: "/etc/cardano/mainnet", DataPath: "/data/cardano/mainnet"},
		{Network: "cardano", Env: "preprod", Public: 40691, Agent: 40791, NodeHTTP: 1338, P2P: 3004,
			Metrics: 12799, ChainFlag: "preprod", WatchSlug: "cardano-preprod", DiskHintGiB: 80,
			SnapshotURL: "https://aggregator.release-preprod.api.mithril.network/aggregator",
			ServiceUnit: "cardano-preprod.service", ExtraSteps: []string{"snapshot"},
			OptPath:     "/opt/cardano/preprod", EtcPath: "/etc/cardano/preprod", DataPath: "/data/cardano/preprod"},
		{Network: "cardano", Env: "preview", Public: 40692, Agent: 40792, NodeHTTP: 1339, P2P: 3005,
			Metrics: 12800, ChainFlag: "preview", WatchSlug: "cardano-preview", DiskHintGiB: 80,
			SnapshotURL: "https://aggregator.pre-release-preview.api.mithril.network/aggregator",
			ServiceUnit: "cardano-preview.service", ExtraSteps: []string{"snapshot"},
			OptPath:     "/opt/cardano/preview", EtcPath: "/etc/cardano/preview", DataPath: "/data/cardano/preview"},
		// Stellar — native stellar-rpc + Captive Core. Full history (never prune).
		// SolHTTP = STELLAR_CAPTIVE_CORE_HTTP_QUERY_PORT (stellar-rpc default 11628 if unset —
		// MUST be per-env + in tip catalog; Install fails with port_busy if foreign-held).
		{Network: "stellar", Env: "mainnet", Public: 40890, Agent: 40990, NodeHTTP: 8000, P2P: 11625,
			SolHTTP: 11626, Metrics: 8100, ChainFlag: "mainnet", WatchSlug: "stellar", DiskHintGiB: 512,
			ServiceUnit: "stellar-mainnet.service",
			OptPath:     "/opt/stellar/mainnet", EtcPath: "/etc/stellar/mainnet", DataPath: "/data/stellar/mainnet"},
		{Network: "stellar", Env: "testnet", Public: 40891, Agent: 40991, NodeHTTP: 8001, P2P: 11627,
			SolHTTP: 11628, Metrics: 8101, ChainFlag: "testnet", WatchSlug: "stellar-testnet", DiskHintGiB: 128,
			ServiceUnit: "stellar-testnet.service",
			OptPath:     "/opt/stellar/testnet", EtcPath: "/etc/stellar/testnet", DataPath: "/data/stellar/testnet"},
		{Network: "stellar", Env: "futurenet", Public: 40892, Agent: 40992, NodeHTTP: 8002, P2P: 11629,
			SolHTTP: 11630, Metrics: 8102, ChainFlag: "futurenet", WatchSlug: "stellar-futurenet", DiskHintGiB: 128,
			ServiceUnit: "stellar-futurenet.service",
			OptPath:     "/opt/stellar/futurenet", EtcPath: "/etc/stellar/futurenet", DataPath: "/data/stellar/futurenet"},
		// Litecoin — stock litecoind. Free block after Stellar 4089x/4099x.
		{Network: "ltc", Env: "mainnet", Public: 41090, Agent: 41190, NodeHTTP: 9332, P2P: 9333,
			WatchSlug: "ltc", DiskHintGiB: 200,
			ServiceUnit: "ltc-mainnet.service",
			OptPath:     "/opt/ltc/mainnet", EtcPath: "/etc/ltc/mainnet", DataPath: "/data/ltc/mainnet"},
		// Litecoin testnet=1 nests under datadir/testnet4 (not testnet3).
		{Network: "ltc", Env: "testnet", Public: 41091, Agent: 41191, NodeHTTP: 19332, P2P: 19333,
			ChainFlag: "testnet=1", WatchSlug: "ltc-testnet", DiskHintGiB: 40,
			ServiceUnit: "ltc-testnet.service",
			OptPath:     "/opt/ltc/testnet", EtcPath: "/etc/ltc/testnet", DataPath: "/data/ltc/testnet4"},
		{Network: "ltc", Env: "regtest", Public: 41092, Agent: 41192, NodeHTTP: 19443, P2P: 19444,
			ChainFlag: "regtest=1", WatchSlug: "ltc-regtest", DiskHintGiB: 8,
			ServiceUnit: "ltc-regtest.service",
			OptPath:     "/opt/ltc/regtest", EtcPath: "/etc/ltc/regtest", DataPath: "/data/ltc/regtest"},
		// Dash — stock dashd.
		{Network: "dash", Env: "mainnet", Public: 41290, Agent: 41390, NodeHTTP: 9998, P2P: 9999,
			WatchSlug: "dash", DiskHintGiB: 100,
			ServiceUnit: "dash-mainnet.service",
			OptPath:     "/opt/dash/mainnet", EtcPath: "/etc/dash/mainnet", DataPath: "/data/dash/mainnet"},
		{Network: "dash", Env: "testnet", Public: 41291, Agent: 41391, NodeHTTP: 19998, P2P: 19999,
			ChainFlag: "testnet=1", WatchSlug: "dash-testnet", DiskHintGiB: 32,
			ServiceUnit: "dash-testnet.service",
			OptPath:     "/opt/dash/testnet", EtcPath: "/etc/dash/testnet", DataPath: "/data/dash/testnet"},
		{Network: "dash", Env: "regtest", Public: 41292, Agent: 41392, NodeHTTP: 19898, P2P: 19899,
			ChainFlag: "regtest=1", WatchSlug: "dash-regtest", DiskHintGiB: 8,
			ServiceUnit: "dash-regtest.service",
			OptPath:     "/opt/dash/regtest", EtcPath: "/etc/dash/regtest", DataPath: "/data/dash/regtest"},
		// Bitcoin Cash (BCHN) — NodeHTTP remapped off bitcoin :8332 (same-host coexistence).
		{Network: "bch", Env: "mainnet", Public: 41490, Agent: 41590, NodeHTTP: 8432, P2P: 8433,
			WatchSlug: "bch", DiskHintGiB: 400,
			ServiceUnit: "bch-mainnet.service",
			OptPath:     "/opt/bch/mainnet", EtcPath: "/etc/bch/mainnet", DataPath: "/data/bch/mainnet"},
		{Network: "bch", Env: "testnet", Public: 41491, Agent: 41591, NodeHTTP: 18432, P2P: 18433,
			ChainFlag: "testnet=1", WatchSlug: "bch-testnet", DiskHintGiB: 64,
			ServiceUnit: "bch-testnet.service",
			OptPath:     "/opt/bch/testnet", EtcPath: "/etc/bch/testnet", DataPath: "/data/bch/testnet"},
		// Regtest RPC remapped off bitcoin regtest :18443.
		{Network: "bch", Env: "regtest", Public: 41492, Agent: 41592, NodeHTTP: 18543, P2P: 18544,
			ChainFlag: "regtest=1", WatchSlug: "bch-regtest", DiskHintGiB: 8,
			ServiceUnit: "bch-regtest.service",
			OptPath:     "/opt/bch/regtest", EtcPath: "/etc/bch/regtest", DataPath: "/data/bch/regtest"},
		// Toncoin — MyTonCtrl liteserver full (~30d) + TON HTTP API. Canonical: deploy/nodes/ton/DESIGN.md.
		// NodeHTTP = THA; P2P = VALIDATOR_PORT (UDP). Not archive (~12 TiB).
		{Network: "ton", Env: "mainnet", Public: 41690, Agent: 41790, NodeHTTP: 8081, P2P: 30310,
			ChainFlag: "mainnet", WatchSlug: "ton", DiskHintGiB: 1024,
			ServiceUnit: "ton-mainnet.service",
			OptPath:     "/opt/ton/mainnet", EtcPath: "/etc/ton/mainnet", DataPath: "/data/ton/mainnet"},
		{Network: "ton", Env: "testnet", Public: 41691, Agent: 41791, NodeHTTP: 8082, P2P: 30311,
			ChainFlag: "testnet", WatchSlug: "ton-testnet", DiskHintGiB: 256,
			ServiceUnit: "ton-testnet.service",
			OptPath:     "/opt/ton/testnet", EtcPath: "/etc/ton/testnet", DataPath: "/data/ton/testnet"},
		// Ethereum Classic — Core-Geth archive. Canonical: deploy/nodes/etc/DESIGN.md.
		// NodeHTTP/P2P remapped off ethereum :8545/:30303.
		{Network: "etc", Env: "mainnet", Public: 41890, Agent: 41990, NodeHTTP: 8555, P2P: 30323,
			ChainFlag: "--classic", WatchSlug: "etc", DiskHintGiB: 1024,
			ServiceUnit: "etc-mainnet.service",
			OptPath:     "/opt/etc/mainnet", EtcPath: "/etc/etc/mainnet", DataPath: "/data/etc/mainnet"},
		{Network: "etc", Env: "mordor", Public: 41891, Agent: 41991, NodeHTTP: 8556, P2P: 30324,
			ChainFlag: "--mordor", WatchSlug: "etc-mordor", DiskHintGiB: 128,
			ServiceUnit: "etc-mordor.service",
			OptPath:     "/opt/etc/mordor", EtcPath: "/etc/etc/mordor", DataPath: "/data/etc/mordor"},
	}
}

func cookieRelPath(env string) string {
	switch strings.ToLower(strings.TrimSpace(env)) {
	case "testnet4":
		return "testnet4/.cookie"
	case "signet":
		return "signet/.cookie"
	case "regtest":
		return "regtest/.cookie"
	default:
		return ".cookie"
	}
}

// bitcoinCoreDatadirSetting — value for bitcoin.conf `datadir=`.
// Core always nests regtest/signet/testnet4 under datadir; our profile DataPath is the
// final chain dir (/data/bitcoin/regtest). Point datadir at the parent so Core does
// not create /data/bitcoin/regtest/regtest.
func bitcoinCoreDatadirSetting(dataPath, env string) string {
	dataPath = strings.TrimRight(strings.TrimSpace(dataPath), "/")
	if dataPath == "" {
		return dataPath
	}
	switch strings.ToLower(strings.TrimSpace(env)) {
	case "regtest", "signet", "testnet4", "testnet", "testnet3":
		parent := filepath.Dir(dataPath)
		if parent != "" && parent != "." && parent != "/" {
			return parent
		}
	}
	return dataPath
}
