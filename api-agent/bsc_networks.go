package main

import "strings"

const bscGethReleaseTag = "v1.7.7"

// bscNetwork — static metadata for one BSC env (bnb-chain/bsc geth fork, Parlia).
type bscNetwork struct {
	Env       string
	WatchSlug string
	ChainID   string
	ZipAsset  string // mainnet.zip / testnet.zip from release
}

func lookupBSCNetwork(env string) bscNetwork {
	switch normalizeEnv(env) {
	case "testnet", "chapel":
		return bscNetwork{
			Env:       "testnet",
			WatchSlug: "bsc-testnet",
			ChainID:   "97",
			ZipAsset:  "testnet.zip",
		}
	default:
		return bscNetwork{
			Env:       "mainnet",
			WatchSlug: "bsc",
			ChainID:   "56",
			ZipAsset:  "mainnet.zip",
		}
	}
}

func bscSysListen(env string) int {
	switch normalizeEnv(env) {
	case "testnet", "chapel":
		return 8491
	default:
		return 8490
	}
}

func isBSCNetwork(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "bsc")
}

func bscGethCacheMB(env string) int {
	return bscGethCacheMBFor(env, memTotalMB())
}

// bscGethCacheMBFor — profile default, then cap at ~25% RAM.
// --cache 4096 on a 4 GiB smoke box OOMs during mmap (journal dies at
// "Smartcard socket not found", same empty-looking start as bitcoin dbcache).
func bscGethCacheMBFor(env string, memMB int) int {
	want := 8192
	switch normalizeEnv(env) {
	case "testnet", "chapel":
		want = 4096
	}
	return capGethCacheMB(want, memMB)
}

func capGethCacheMB(want, memMB int) int {
	if memMB <= 0 {
		return want
	}
	capAt := memMB / 4
	if capAt < 256 {
		capAt = 256
	}
	if want > capAt {
		return capAt
	}
	return want
}
