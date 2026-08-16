package main

import "strings"

// ethereumNetwork — static EL/CL metadata for one env.
type ethereumNetwork struct {
	Env              string
	WatchSlug        string
	GethFlag         string // e.g. --sepolia, --hoodi; empty for mainnet
	LHNetwork        string
	CheckpointURL    string
	ChainID          string
	HistoryPostMerge bool
}

func lookupEthereumNetwork(env string) ethereumNetwork {
	switch normalizeEnv(env) {
	case "sepolia":
		return ethereumNetwork{
			Env:           "sepolia",
			WatchSlug:     "ethereum-sepolia",
			GethFlag:      "--sepolia",
			LHNetwork:     "sepolia",
			CheckpointURL: "https://checkpoint-sync.sepolia.ethpandaops.io",
			ChainID:       "11155111",
		}
	case "hoodi":
		return ethereumNetwork{
			Env:           "hoodi",
			WatchSlug:     "ethereum-hoodi",
			GethFlag:      "--hoodi",
			LHNetwork:     "hoodi",
			CheckpointURL: "https://checkpoint-sync.hoodi.ethpandaops.io",
			ChainID:       "560048",
		}
	default:
		return ethereumNetwork{
			Env:              "mainnet",
			WatchSlug:        "ethereum",
			GethFlag:         "",
			LHNetwork:        "mainnet",
			CheckpointURL:    "https://sync-mainnet.beaconcha.in",
			ChainID:          "1",
			HistoryPostMerge: true,
		}
	}
}

func ethereumSysListen(env string) int {
	switch normalizeEnv(env) {
	case "sepolia":
		return 8391
	case "hoodi":
		return 8392
	default:
		return 8390
	}
}

func isEthereumNetwork(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "ethereum")
}
