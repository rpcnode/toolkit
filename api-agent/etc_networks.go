package main

import "strings"

// Core-Geth release pin — etclabscore/core-geth (not go-ethereum).
const etcCoreGethVersion = "v1.12.22"

type etcNetwork struct {
	Env       string
	WatchSlug string
	ChainFlag string // --classic | --mordor
	CacheMB   int
}

func lookupETCNetwork(env string) etcNetwork {
	switch normalizeEnv(env) {
	case "mordor":
		return etcNetwork{
			Env:       "mordor",
			WatchSlug: "etc-mordor",
			ChainFlag: "--mordor",
			CacheMB:   1024,
		}
	default:
		return etcNetwork{
			Env:       "mainnet",
			WatchSlug: "etc",
			ChainFlag: "--classic",
			CacheMB:   4096,
		}
	}
}

func etcSysListen(env string) int {
	// After ton 8650–8651; robinhood uses 8670–8671.
	switch normalizeEnv(env) {
	case "mordor":
		return 8661
	default:
		return 8660
	}
}

func networkIsETC(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "etc")
}
