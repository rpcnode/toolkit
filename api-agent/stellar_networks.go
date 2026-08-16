package main

import (
	"math"
	"strings"
)

// stellarHistoryRetentionWindow — RpcNode full history: never prune local
// transactions/events. stellar-rpc type is uint32; MaxUint32 disables trim
// (see db.trim* when latest+1 <= retention). Stock SDF default 120960 ≈ 7d
// is ❌ for prod fullnodes.
const stellarHistoryRetentionWindow = uint32(math.MaxUint32)

// Futurenet runs ahead of stable (protocol 28+ / vnext). Stable apt stellar-core
// 27.x fails catchup with "unsupported ledger version". Install per-env under
// /opt/stellar/futurenet/bin so host /usr/bin can stay on stable for testnet/mainnet.
const (
	stellarStableRPCVersion    = "27.1.1"
	stellarFuturenetRPCImage   = "stellar/stellar-rpc:28.0.0-vnext-200"
	stellarFuturenetMinProtocol = 28
)

func stellarNeedsVNext(env string) bool {
	return normalizeEnv(env) == "futurenet"
}

// stellarNetwork — Stellar RPC + Captive Core metadata per env.
type stellarNetwork struct {
	Env            string
	WatchSlug      string
	Passphrase     string
	HistoryArchive string
	CaptiveCoreURL string
	PublicTipRPC   string // optional; used by system-agent for sync %
	CoreHTTPPort   int
	PeerPort       int
	AdminPort      int
}

func lookupStellarNetwork(env string) stellarNetwork {
	switch normalizeEnv(env) {
	case "testnet":
		return stellarNetwork{
			Env:            "testnet",
			WatchSlug:      "stellar-testnet",
			Passphrase:     "Test SDF Network ; September 2015",
			HistoryArchive: "https://history.stellar.org/prd/core-testnet/core_testnet_001",
			CaptiveCoreURL: "https://raw.githubusercontent.com/stellar/go-stellar-sdk/master/ingest/ledgerbackend/configs/captive-core-testnet.cfg",
			PublicTipRPC:   "https://soroban-testnet.stellar.org",
			CoreHTTPPort:   11628,
			PeerPort:       11627,
			AdminPort:      8101,
		}
	case "futurenet":
		return stellarNetwork{
			Env:            "futurenet",
			WatchSlug:      "stellar-futurenet",
			Passphrase:     "Test SDF Future Network ; October 2022",
			HistoryArchive: "http://history.stellar.org/dev/core-futurenet/core_futurenet_001",
			CaptiveCoreURL: "https://raw.githubusercontent.com/stellar/go-stellar-sdk/master/ingest/ledgerbackend/configs/captive-core-futurenet.cfg",
			PublicTipRPC:   "https://rpc-futurenet.stellar.org",
			CoreHTTPPort:   11630,
			PeerPort:       11629,
			AdminPort:      8102,
		}
	default:
		return stellarNetwork{
			Env:            "mainnet",
			WatchSlug:      "stellar",
			Passphrase:     "Public Global Stellar Network ; September 2015",
			HistoryArchive: "http://history.stellar.org/prd/core-live/core_live_001",
			CaptiveCoreURL: "https://raw.githubusercontent.com/stellar/go-stellar-sdk/master/ingest/ledgerbackend/configs/captive-core-pubnet.cfg",
			// SDF has no official public mainnet RPC — community tip; agent also
			// falls back to Horizon history_latest_ledger when RPC tip is down.
			PublicTipRPC: "https://mainnet.sorobanrpc.com",
			CoreHTTPPort: 11626,
			PeerPort:     11625,
			AdminPort:    8100,
		}
	}
}

func stellarSysListen(env string) int {
	// After Cardano 8620–8622.
	switch normalizeEnv(env) {
	case "testnet":
		return 8631
	case "futurenet":
		return 8632
	default:
		return 8630
	}
}

func networkIsStellar(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "stellar")
}
