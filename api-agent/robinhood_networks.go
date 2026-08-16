package main

import (
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Robinhood Chain — Arbitrum Nitro (Orbit) full node. Same nitro-node binary as arb
// (offchainlabs/nitro-node via ensureNitroInstalled), Robinhood chain-info/genesis CDN
// + official pruned nitro snapshots (required — L1 beacon without archive blobs stalls IBD).
// See deploy/nodes/robinhood/DESIGN.md.

const (
	robinhoodSnapBaseURL = "https://robinhood-snapshots.offchainlabs.com"
	// latest-pruned.txt bodies are relative dirs under the base (spaces in path).
	robinhoodMainnetLatestPruned = "robinhood%20chain/latest-pruned.txt"
	robinhoodTestnetLatestPruned = "robinhood%20chain%20sepolia/latest-pruned.txt"
	// Pinned fallbacks when latest pointer is unreachable (explorer 2026-08).
	robinhoodMainnetInitFallback = "https://robinhood-snapshots.offchainlabs.com/robinhood%20chain/2026-08-03-1432f687/"
	robinhoodTestnetInitFallback = "https://robinhood-snapshots.offchainlabs.com/robinhood%20chain%20sepolia/2026-08-06-dacda195/"
)

// robinhoodNetwork — per-env chain metadata + official config/snapshot CDN URLs.
type robinhoodNetwork struct {
	Env          string
	WatchSlug    string
	ChainID      string
	ChainInfoURL string
	GenesisURL   string // mainnet only; empty = no --init.genesis-json-file
	FeedURL      string
	// LatestPrunedPath is URL path (already %-encoded) of latest-pruned.txt under SnapBaseURL.
	LatestPrunedPath string
	InitURLFallback  string
	SnapshotURL      string // resolved directory URL for nitro --init.url
}

func lookupRobinhoodNetwork(env string) robinhoodNetwork {
	switch normalizeEnv(env) {
	case "testnet":
		n := robinhoodNetwork{
			Env:              "testnet",
			WatchSlug:        "robinhood-testnet",
			ChainID:          "46630",
			ChainInfoURL:     "https://cdn.robinhood.com/assets/generated_assets/hoodchain_docsite/chain-node-configs/robinhood-chain-testnet-info.json",
			FeedURL:          "wss://feed.testnet.chain.robinhood.com",
			LatestPrunedPath: robinhoodTestnetLatestPruned,
			InitURLFallback:  robinhoodTestnetInitFallback,
		}
		n.SnapshotURL = resolveRobinhoodInitURL(n)
		return n
	default:
		n := robinhoodNetwork{
			Env:              "mainnet",
			WatchSlug:        "robinhood",
			ChainID:          "4663",
			ChainInfoURL:     "https://cdn.robinhood.com/assets/generated_assets/hoodchain_docsite/chain-node-configs/robinhood-chain-info.json",
			GenesisURL:       "https://cdn.robinhood.com/assets/generated_assets/hoodchain_docsite/chain-node-configs/robinhood-genesis.json",
			FeedURL:          "wss://feed.mainnet.chain.robinhood.com",
			LatestPrunedPath: robinhoodMainnetLatestPruned,
			InitURLFallback:  robinhoodMainnetInitFallback,
		}
		n.SnapshotURL = resolveRobinhoodInitURL(n)
		return n
	}
}

// resolveRobinhoodInitURL fetches latest-pruned.txt and builds a directory URL for --init.url.
// Falls back to a pinned explorer snapshot when the pointer is unreachable.
func resolveRobinhoodInitURL(n robinhoodNetwork) string {
	if fb := strings.TrimSpace(envOr("RPCNODE_ROBINHOOD_INIT_URL", "")); fb != "" {
		return ensureTrailingSlash(fb)
	}
	pointer := strings.TrimRight(robinhoodSnapBaseURL, "/") + "/" + strings.TrimLeft(n.LatestPrunedPath, "/")
	rel, err := fetchRobinhoodLatestPruned(pointer)
	if err != nil || rel == "" {
		return ensureTrailingSlash(n.InitURLFallback)
	}
	// Encode each path segment (spaces → %20) but keep slashes.
	parts := strings.Split(strings.Trim(rel, "/"), "/")
	enc := make([]string, 0, len(parts))
	for _, p := range parts {
		if p == "" {
			continue
		}
		enc = append(enc, url.PathEscape(p))
	}
	if len(enc) == 0 {
		return ensureTrailingSlash(n.InitURLFallback)
	}
	return ensureTrailingSlash(strings.TrimRight(robinhoodSnapBaseURL, "/") + "/" + strings.Join(enc, "/"))
}

func fetchRobinhoodLatestPruned(pointerURL string) (string, error) {
	client := &http.Client{Timeout: 20 * time.Second}
	resp, err := client.Get(pointerURL)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("latest-pruned HTTP %d", resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if err != nil {
		return "", err
	}
	line := strings.TrimSpace(string(b))
	if i := strings.IndexByte(line, '\n'); i >= 0 {
		line = strings.TrimSpace(line[:i])
	}
	if line == "" {
		return "", fmt.Errorf("empty latest-pruned")
	}
	return line, nil
}

func ensureTrailingSlash(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return s
	}
	if !strings.HasSuffix(s, "/") {
		return s + "/"
	}
	return s
}

func robinhoodSysListen(env string) int {
	switch normalizeEnv(env) {
	case "testnet":
		return 8671
	default:
		return 8670
	}
}

func isRobinhoodNetwork(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "robinhood")
}
