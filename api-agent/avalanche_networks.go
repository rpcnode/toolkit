package main

import "strings"

func avalancheSysListen(env string) int {
	switch normalizeAvalancheEnv(env) {
	case "fuji":
		return 8721
	default:
		return 8720
	}
}

func networkIsAvalanche(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "avalanche")
}

func networkIsAvalancheCfg(cfg Config) bool {
	n := strings.ToLower(strings.TrimSpace(envOr("TRON_NETWORK", "")))
	if n == "avalanche" {
		return true
	}
	return cfg.UpstreamPort == 9650 || cfg.UpstreamPort == 9660
}

// normalizeAvalancheEnv — product env is fuji (official Avalanche testnet name).
// Alias testnet→fuji for plan/provision/lookup only.
func normalizeAvalancheEnv(env string) string {
	e := normalizeEnv(env)
	if e == "testnet" {
		return "fuji"
	}
	return e
}

func avalancheReleaseVersion() string {
	return envOr("AVALANCHE_VERSION", "v1.14.2")
}

func avalancheNetworkID(env string) string {
	switch normalizeAvalancheEnv(env) {
	case "fuji":
		return "fuji"
	default:
		return "mainnet"
	}
}

func avalanchePublicTipRPC(env string) string {
	switch normalizeAvalancheEnv(env) {
	case "fuji":
		return "https://api.avax-test.network/ext/bc/C/rpc"
	default:
		return "https://api.avax.network/ext/bc/C/rpc"
	}
}

func avalancheReleaseTarballURL(version string) string {
	ver := strings.TrimSpace(version)
	if ver == "" {
		ver = "v1.14.2"
	}
	if !strings.HasPrefix(ver, "v") {
		ver = "v" + ver
	}
	arch := "amd64"
	switch runtimeGOARCH() {
	case "arm64", "aarch64":
		arch = "arm64"
	}
	return "https://github.com/ava-labs/avalanchego/releases/download/" + ver +
		"/avalanchego-linux-" + arch + "-" + ver + ".tar.gz"
}
