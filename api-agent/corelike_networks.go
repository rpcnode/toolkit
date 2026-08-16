package main

import "strings"

// Core-like UTXO clients (dogecoin-shaped): rpcuser auth, flat conf, *-cli stop, IBD via getblockchaininfo.

type coreLikeClient struct {
	Network        string
	DisplayName    string // unit Description short name
	Daemon         string // process / install name under /opt/<net>/<env>/bin
	CLI            string
	ConfName       string
	VersionEnv     string
	DefaultVersion string
	// DownloadURL builds full tarball URL; ArchiveDir is directory inside the tarball.
	DownloadURL func(ver, arch string) string
	ArchiveDir  func(ver string) string
	// TarballDaemon/CLI — names inside archive (BCH ships bitcoind/bitcoin-cli).
	TarballDaemon string
	TarballCLI    string
	SysListenMain    int
	SysListenTest    int
	SysListenRegtest int
}

func coreLikeClients() []coreLikeClient {
	return []coreLikeClient{
		{
			Network: "ltc", DisplayName: "Litecoin",
			Daemon: "litecoind", CLI: "litecoin-cli", ConfName: "litecoin.conf",
			VersionEnv: "LITECOIN_VERSION", DefaultVersion: "0.21.5.6",
			DownloadURL: func(ver, arch string) string {
				return "https://download.litecoin.org/litecoin-" + ver + "/linux/litecoin-" + ver + "-" + arch + ".tar.gz"
			},
			ArchiveDir:    func(ver string) string { return "litecoin-" + ver },
			TarballDaemon: "litecoind", TarballCLI: "litecoin-cli",
			SysListenMain: 8640, SysListenTest: 8641, SysListenRegtest: 8646,
		},
		{
			Network: "dash", DisplayName: "Dash",
			Daemon: "dashd", CLI: "dash-cli", ConfName: "dash.conf",
			VersionEnv: "DASH_VERSION", DefaultVersion: "23.1.8",
			DownloadURL: func(ver, arch string) string {
				return "https://github.com/dashpay/dash/releases/download/v" + ver + "/dashcore-" + ver + "-" + arch + ".tar.gz"
			},
			ArchiveDir:    func(ver string) string { return "dashcore-" + ver },
			TarballDaemon: "dashd", TarballCLI: "dash-cli",
			SysListenMain: 8642, SysListenTest: 8643, SysListenRegtest: 8647,
		},
		{
			Network: "bch", DisplayName: "Bitcoin Cash",
			// BCHN ships as bitcoind — keep under /opt/bch only (never PATH /usr).
			Daemon: "bitcoind", CLI: "bitcoin-cli", ConfName: "bitcoin.conf",
			VersionEnv: "BCH_VERSION", DefaultVersion: "29.1.0",
			DownloadURL: func(ver, arch string) string {
				return "https://github.com/bitcoin-cash-node/bitcoin-cash-node/releases/download/v" + ver +
					"/bitcoin-cash-node-" + ver + "-" + arch + ".tar.gz"
			},
			ArchiveDir:    func(ver string) string { return "bitcoin-cash-node-" + ver },
			TarballDaemon: "bitcoind", TarballCLI: "bitcoin-cli",
			SysListenMain: 8644, SysListenTest: 8645, SysListenRegtest: 8648,
		},
	}
}

func lookupCoreLike(network string) (coreLikeClient, bool) {
	n := strings.ToLower(strings.TrimSpace(network))
	for _, c := range coreLikeClients() {
		if c.Network == n {
			return c, true
		}
	}
	return coreLikeClient{}, false
}

func networkIsCoreLike(network string) bool {
	_, ok := lookupCoreLike(network)
	return ok
}

// networkUsesRPCUserAuth — public Go proxy injects BITCOIN_RPC_* toward localhost.
func networkUsesRPCUserAuth(network string) bool {
	n := strings.ToLower(strings.TrimSpace(network))
	return n == "doge" || networkIsCoreLike(n)
}

func coreLikeSysListen(network, env string) int {
	c, ok := lookupCoreLike(network)
	if !ok {
		return 0
	}
	switch normalizeEnv(env) {
	case "testnet":
		return c.SysListenTest
	case "regtest":
		if c.SysListenRegtest > 0 {
			return c.SysListenRegtest
		}
		return c.SysListenTest + 1
	default:
		return c.SysListenMain
	}
}
