package main

import "strings"

// tonArchiveTTLSec — MyTonCtrl liteserver default: ~30 days of archived blocks.
const tonArchiveTTLSec = 2592000

// tonStateTTLSec — state cache GC (~1 day).
const tonStateTTLSec = 86400

// tonNetwork — Toncoin liteserver + THA metadata per env.
type tonNetwork struct {
	Env       string
	WatchSlug string
	ChainFlag string // mainnet | testnet for install.sh -n
}

func lookupTonNetwork(env string) tonNetwork {
	switch normalizeEnv(env) {
	case "testnet":
		return tonNetwork{
			Env:       "testnet",
			WatchSlug: "ton-testnet",
			ChainFlag: "testnet",
		}
	default:
		return tonNetwork{
			Env:       "mainnet",
			WatchSlug: "ton",
			ChainFlag: "mainnet",
		}
	}
}

func tonSysListen(env string) int {
	// After BCH corelike 8644–8648.
	switch normalizeEnv(env) {
	case "testnet":
		return 8651
	default:
		return 8650
	}
}

func networkIsTon(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "ton")
}
