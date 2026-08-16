package main

import "strings"

// Base — official Base V1 stack (base-reth-node + base-consensus).
// Extract from ghcr.io/base/node-reth. See deploy/nodes/base/DESIGN.md.

const (
	baseNodeRethDockerImage = "ghcr.io/base/node-reth:v1.2.0"
	// Official P2P bootnodes from base/node .env.mainnet / .env.sepolia (identical ENR set).
	baseBootnodes = `enr:-J24QNz9lbrKbN4iSmmjtnr7SjUMk4zB7f1krHZcTZx-JRKZd0kA2gjufUROD6T3sOWDVDnFJRvqBBo62zuF-hYCohOGAYiOoEyEgmlkgnY0gmlwhAPniryHb3BzdGFja4OFQgCJc2VjcDI1NmsxoQKNVFlCxh_B-716tTs-h1vMzZkSs1FTu_OYTNjgufplG4N0Y3CCJAaDdWRwgiQG,enr:-J24QH-f1wt99sfpHy4c0QJM-NfmsIfmlLAMMcgZCUEgKG_BBYFc6FwYgaMJMQN5dsRBJApIok0jFn-9CS842lGpLmqGAYiOoDRAgmlkgnY0gmlwhLhIgb2Hb3BzdGFja4OFQgCJc2VjcDI1NmsxoQJ9FTIv8B9myn1MWaC_2lJ-sMoeCDkusCsk4BYHjjCq04N0Y3CCJAaDdWRwgiQG,enr:-J24QDXyyxvQYsd0yfsN0cRr1lZ1N11zGTplMNlW4xNEc7LkPXh0NAJ9iSOVdRO95GPYAIc6xmyoCCG6_0JxdL3a0zaGAYiOoAjFgmlkgnY0gmlwhAPckbGHb3BzdGFja4OFQgCJc2VjcDI1NmsxoQJwoS7tzwxqXSyFL7g0JM-KWVbgvjfB8JA__T7yY_cYboN0Y3CCJAaDdWRwgiQG,enr:-J24QHmGyBwUZXIcsGYMaUqGGSl4CFdx9Tozu-vQCn5bHIQbR7On7dZbU61vYvfrJr30t0iahSqhc64J46MnUO2JvQaGAYiOoCKKgmlkgnY0gmlwhAPnCzSHb3BzdGFja4OFQgCJc2VjcDI1NmsxoQINc4fSijfbNIiGhcgvwjsjxVFJHUstK9L1T8OTKUjgloN0Y3CCJAaDdWRwgiQG,enr:-J24QG3ypT4xSu0gjb5PABCmVxZqBjVw9ca7pvsI8jl4KATYAnxBmfkaIuEqy9sKvDHKuNCsy57WwK9wTt2aQgcaDDyGAYiOoGAXgmlkgnY0gmlwhDbGmZaHb3BzdGFja4OFQgCJc2VjcDI1NmsxoQIeAK_--tcLEiu7HvoUlbV52MspE0uCocsx1f_rYvRenIN0Y3CCJAaDdWRwgiQG`
)

type baseNetwork struct {
	Env           string
	WatchSlug     string
	ChainID       string
	RethChain     string // --chain / RETH_CHAIN
	NetworkFlag   string // BASE_NODE_NETWORK
	SequencerHTTP string
}

func lookupBaseNetwork(env string) baseNetwork {
	switch normalizeEnv(env) {
	case "sepolia":
		return baseNetwork{
			Env:           "sepolia",
			WatchSlug:     "base-sepolia",
			ChainID:       "84532",
			RethChain:     "base-sepolia",
			NetworkFlag:   "base-sepolia",
			SequencerHTTP: "https://sepolia-sequencer.base.org",
		}
	default:
		return baseNetwork{
			Env:           "mainnet",
			WatchSlug:     "base",
			ChainID:       "8453",
			RethChain:     "base",
			NetworkFlag:   "base",
			SequencerHTTP: "https://mainnet-sequencer.base.org",
		}
	}
}

func baseSysListen(env string) int {
	switch normalizeEnv(env) {
	case "sepolia":
		return 8681
	default:
		return 8680
	}
}

func isBaseNetwork(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "base")
}

// baseL1Env maps Base env → ethereum L1 env for parent-chain RPC/beacon.
// Sepolia MUST use sepolia — mainnet chainId=1 fails op-node / base-consensus.
func baseL1Env(env string) string {
	if normalizeEnv(env) == "sepolia" {
		return "sepolia"
	}
	return "mainnet"
}
