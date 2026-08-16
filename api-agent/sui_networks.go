package main

import "strings"

func suiSysListen(env string) int {
	switch normalizeEnv(env) {
	case "testnet":
		return 8701
	default:
		return 8700
	}
}

func networkIsSui(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "sui")
}

func suiReleaseTag(env string) string {
	switch normalizeEnv(env) {
	case "testnet":
		return envOr("SUI_TESTNET_VERSION", "testnet-v1.77.1")
	default:
		return envOr("SUI_MAINNET_VERSION", "mainnet-v1.76.1")
	}
}

func suiCheckpointArchiveURL(env string) string {
	switch normalizeEnv(env) {
	case "testnet":
		return "https://checkpoints.testnet.sui.io"
	default:
		return "https://checkpoints.mainnet.sui.io"
	}
}

func suiPublicTipRPC(env string) string {
	// Mysten fullnode.*.sui.io JSON-RPC is deprecated (method not found).
	// Prefer a tip that still serves sui_getLatestCheckpointSequenceNumber.
	switch normalizeEnv(env) {
	case "testnet":
		return envOr("SUI_PUBLIC_TIP_RPC", "https://rpc-testnet.suiscan.xyz")
	default:
		return envOr("SUI_PUBLIC_TIP_RPC", "https://rpc-mainnet.suiscan.xyz")
	}
}

func suiGenesisURL(env string) string {
	net := "mainnet"
	if normalizeEnv(env) == "testnet" {
		net = "testnet"
	}
	return "https://github.com/MystenLabs/sui-genesis/raw/main/" + net + "/genesis.blob"
}
