package main

import (
	"fmt"
	"strings"
)

// solanaCluster — static Agave / test-validator metadata for one env.
// Genesis / entrypoints must stay aligned with deploy/nodes/solana/*.md (Anza clusters).
type solanaCluster struct {
	Cluster         string
	Genesis         string
	Entrypoints     []string
	KnownValidators []string
	OnlyKnownRPC    bool
	Localnet        bool
	P2PRangeSpan    int // inclusive end offset from P2P base (0 → base-base)
	FaucetPort      int
	WatchSlug       string
}

func lookupSolanaCluster(env string) solanaCluster {
	switch normalizeEnv(env) {
	case "testnet":
		return solanaCluster{
			Cluster: "testnet",
			Genesis: "4uhcVJyU9pJkvQyS88uRDiswHXSCkY3zQawwpjk2NsNY",
			Entrypoints: []string{
				"entrypoint.testnet.solana.com:8001",
				"entrypoint2.testnet.solana.com:8001",
				"entrypoint3.testnet.solana.com:8001",
			},
			// Agave requires ≥27 ports in the dynamic range (MIN..MAX inclusive).
			P2PRangeSpan: 26,
			WatchSlug:    "solana-testnet",
		}
	case "devnet":
		return solanaCluster{
			Cluster: "devnet",
			Genesis: "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG",
			Entrypoints: []string{
				"entrypoint.devnet.solana.com:8001",
				"entrypoint2.devnet.solana.com:8001",
				"entrypoint3.devnet.solana.com:8001",
			},
			P2PRangeSpan: 26,
			WatchSlug:    "solana-devnet",
		}
	case "localnet":
		return solanaCluster{
			Cluster:      "localnet",
			Localnet:     true,
			FaucetPort:   19900,
			P2PRangeSpan: 0,
			WatchSlug:    "solana-localnet",
		}
	default: // mainnet → mainnet-beta
		return solanaCluster{
			Cluster: "mainnet-beta",
			Genesis: "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d",
			Entrypoints: []string{
				"entrypoint.mainnet-beta.solana.com:8001",
				"entrypoint2.mainnet-beta.solana.com:8001",
				"entrypoint3.mainnet-beta.solana.com:8001",
				"entrypoint4.mainnet-beta.solana.com:8001",
				"entrypoint5.mainnet-beta.solana.com:8001",
			},
			KnownValidators: []string{
				"7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2",
				"GdnSyH3YtwcxFvQrVVJMm1JhTS4QVX7MFsX56uJLUfiZ",
				"DE1bawNcRJB9rVm3buyMVfr8mBEoyyu73NBovf2oXJsJ",
				"CakcnaRDHka2gXyfbEd2d3xsvkJkqsLw2akB3zsN1D2S",
			},
			OnlyKnownRPC: true,
			P2PRangeSpan: 26,
			WatchSlug:    "solana",
		}
	}
}

func solanaSysListen(env string) int {
	switch normalizeEnv(env) {
	case "testnet":
		return 8291
	case "devnet":
		return 8292
	case "localnet":
		return 8293
	default:
		return 8290
	}
}

func solanaP2PRange(base, span int) string {
	if base <= 0 {
		return ""
	}
	if span < 0 {
		span = 0
	}

	return fmt.Sprintf("%d-%d", base, base+span)
}

func isSolanaLocalnet(env string) bool {
	return strings.EqualFold(strings.TrimSpace(env), "localnet")
}
