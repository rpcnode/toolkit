package main

import (
	"fmt"
	"strings"
)

// OP Stack Docker images (GitHub releases are image-only for recent tags).
const (
	opGethDockerImage = "us-docker.pkg.dev/oplabs-tools-artifacts/images/op-geth:v1.101511.1"
	opNodeDockerImage = "us-docker.pkg.dev/oplabs-tools-artifacts/images/op-node:v1.13.5"
)

// optimismNetwork — op-geth + op-node metadata.
type optimismNetwork struct {
	Env         string
	WatchSlug   string
	ChainID     string
	NetworkFlag string // --network / --op-network preset
}

func lookupOptimismNetwork(env string) optimismNetwork {
	switch normalizeEnv(env) {
	case "sepolia":
		return optimismNetwork{
			Env:         "sepolia",
			WatchSlug:   "optimism-sepolia",
			ChainID:     "11155420",
			NetworkFlag: "op-sepolia",
		}
	default:
		return optimismNetwork{
			Env:         "mainnet",
			WatchSlug:   "optimism",
			ChainID:     "10",
			NetworkFlag: "op-mainnet",
		}
	}
}

func optimismSysListen(env string) int {
	switch normalizeEnv(env) {
	case "sepolia":
		return 8595
	default:
		return 8592
	}
}

func isOptimismNetwork(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "optimism")
}

func optimismGethCacheMB(env string) int {
	return optimismGethCacheMBFor(env, memTotalMB())
}

func optimismGethCacheMBFor(env string, memMB int) int {
	want := 8192
	if normalizeEnv(env) == "sepolia" {
		want = 4096
	}
	return capGethCacheMB(want, memMB)
}

func optimismProvisionEnvOK(env string) error {
	switch normalizeEnv(env) {
	case "mainnet", "sepolia":
		return nil
	default:
		return fmt.Errorf("optimism provision supports mainnet/sepolia (got %s)", env)
	}
}

// optimismL1Env maps OP env → ethereum L1 env. Sepolia MUST use sepolia L1.
func optimismL1Env(env string) string {
	if normalizeEnv(env) == "sepolia" {
		return "sepolia"
	}
	return "mainnet"
}
