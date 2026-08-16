package main

import "strings"

func aptosSysListen(env string) int {
	switch normalizeEnv(env) {
	case "testnet":
		return 8711
	default:
		return 8710
	}
}

func networkIsAptos(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "aptos")
}

func aptosReleaseTag(env string) string {
	switch normalizeEnv(env) {
	case "testnet":
		return envOr("APTOS_TESTNET_VERSION", "aptos-node-v1.48.6-rc")
	default:
		return envOr("APTOS_MAINNET_VERSION", "aptos-node-v1.48.6-hotfix")
	}
}

func aptosReleaseAssetName() string {
	return "aptos-node-performance-ubuntu-22.04.tgz"
}

func aptosPublicTipREST(env string) string {
	switch normalizeEnv(env) {
	case "testnet":
		return "https://fullnode.testnet.aptoslabs.com/v1"
	default:
		return "https://fullnode.mainnet.aptoslabs.com/v1"
	}
}

func aptosGenesisURL(env string) string {
	net := "mainnet"
	if normalizeEnv(env) == "testnet" {
		net = "testnet"
	}
	return "https://raw.githubusercontent.com/aptos-labs/aptos-networks/main/" + net + "/genesis.blob"
}

func aptosWaypointURL(env string) string {
	net := "mainnet"
	if normalizeEnv(env) == "testnet" {
		net = "testnet"
	}
	return "https://raw.githubusercontent.com/aptos-labs/aptos-networks/main/" + net + "/waypoint.txt"
}
