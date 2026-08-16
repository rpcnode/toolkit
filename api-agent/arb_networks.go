package main

import "strings"

const (
	// nitro Docker image — Offchain Labs ships nitro via Docker Hub only (no GH assets).
	nitroDockerImage = "offchainlabs/nitro-node:v3.11.2-3599aca"

	// Sepolia L1 execution RPC — publicnode until ethereum-host :39691 is actually listening.
	// Same reason as sepolia beacon: product sepolia Go RPC was connection-refused and Nitro
	// crash-looped (`couldn't read L1 chainid`). Override: RPCNODE_L1_RPC_URL.
	publicSepoliaL1RPCURL = "https://ethereum-sepolia-rpc.publicnode.com"

	ethereumHostMainnetRPCURL = "http://185.44.207.117:39690"
)

// arbNetwork — Arbitrum Nitro full-node metadata (slug=arb, paths under arbitrum/).
type arbNetwork struct {
	Env         string
	WatchSlug   string
	ChainID     string
	InitLatest  string // pruned (= full, not lite/archive)
	InitURL     string
	SnapshotURL string
}

func lookupArbNetwork(env string) arbNetwork {
	switch normalizeEnv(env) {
	case "sepolia":
		return arbNetwork{
			Env:         "sepolia",
			WatchSlug:   "arb-sepolia",
			ChainID:     "421614",
			InitLatest:  "pruned",
			InitURL:     "https://snapshot.arbitrum.foundation/sepolia/nitro-pruned.tar",
			SnapshotURL: "https://snapshot.arbitrum.foundation/sepolia/nitro-pruned.tar",
		}
	default:
		return arbNetwork{
			Env:         "mainnet",
			WatchSlug:   "arb",
			ChainID:     "42161",
			InitLatest:  "pruned",
			InitURL:     "https://snapshot.arbitrum.foundation/arb1/nitro-pruned.tar",
			SnapshotURL: "https://snapshot.arbitrum.foundation/arb1/nitro-pruned.tar",
		}
	}
}

func arbSysListen(env string) int {
	switch normalizeEnv(env) {
	case "sepolia":
		return 8594
	default:
		return 8591
	}
}

func isArbNetwork(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "arb")
}

// arbL1Env maps Arbitrum env → ethereum L1 env for parent-chain RPC/beacon.
// Sepolia MUST use sepolia — mainnet chainId=1 fails nitro config.
func arbL1Env(env string) string {
	if normalizeEnv(env) == "sepolia" {
		return "sepolia"
	}
	return "mainnet"
}

// defaultL1RPCURL — ethereum-host Go RPC mainnet (override via RPCNODE_L1_RPC_URL).
func defaultL1RPCURL() string {
	return defaultL1RPCURLFor("mainnet")
}

// defaultL1BeaconURL — ethereum-host beacon proxy mainnet (override via RPCNODE_L1_BEACON_URL).
func defaultL1BeaconURL() string {
	return defaultL1BeaconURLFor("mainnet")
}

// defaultL1RPCURLFor — L1 execution RPC by env.
// Mainnet: ethereum-host Go RPC :39690.
// Sepolia / testnet: publicnode (not ethereum-host :39691 until that port is up).
// Robinhood testnet / arb sepolia MUST use sepolia — mainnet chainId=1 fails Orbit config.
func defaultL1RPCURLFor(l1Env string) string {
	if v := strings.TrimSpace(envOr("RPCNODE_L1_RPC_URL", "")); v != "" {
		return v
	}
	switch normalizeEnv(l1Env) {
	case "sepolia", "testnet":
		return publicSepoliaL1RPCURL
	default:
		return ethereumHostMainnetRPCURL
	}
}

// defaultL1BeaconURLFor — L1 consensus / blob API by env.
// Mainnet: ethereum-host beacon proxy :15052 → lighthouse :5052 (needs --supernode).
// Sepolia: public blob-capable beacon until ethereum-host :15053 is PeerDAS supernode
// and can reconstruct /eth/v1/beacon/blobs (else Orbit inbox stalls: "Insufficient data columns").
// Override: RPCNODE_L1_BEACON_URL.
func defaultL1BeaconURLFor(l1Env string) string {
	if v := strings.TrimSpace(envOr("RPCNODE_L1_BEACON_URL", "")); v != "" {
		return v
	}
	switch normalizeEnv(l1Env) {
	case "sepolia", "testnet":
		return "https://ethereum-sepolia-beacon-api.publicnode.com"
	default:
		return "http://185.44.207.117:15052"
	}
}

// robinhoodL1Env maps robinhood env → ethereum L1 env for parent-chain RPC/beacon.
func robinhoodL1Env(env string) string {
	if normalizeEnv(env) == "testnet" {
		return "sepolia"
	}
	return "mainnet"
}
