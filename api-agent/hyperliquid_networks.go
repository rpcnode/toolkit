package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// hyperliquidNetwork — hl-visor non-validator metadata.
type hyperliquidNetwork struct {
	Env         string
	WatchSlug   string
	ChainID     string // HyperEVM chain id hex-friendly decimal
	ChainName   string // Mainnet | Testnet for visor.json / gossip
	BinaryURL   string
	GossipPeers []string
}

func lookupHyperliquidNetwork(env string) hyperliquidNetwork {
	switch normalizeEnv(env) {
	case "testnet":
		return hyperliquidNetwork{
			Env:       "testnet",
			WatchSlug: "hyperliquid-testnet",
			ChainID:   "998",
			ChainName: "Testnet",
			BinaryURL: "https://binaries.hyperliquid-testnet.xyz/Testnet/hl-visor",
			// hl-node asserts !root_node_ips.is_empty() (gossip_config.rs) — empty → crash loop.
			// Seeds: community peer lists (api.hyperliquid-testnet.xyz gossipRootIps is often []).
			GossipPeers: []string{
				"23.81.40.132",    // imperator testnet peers.json
				"199.254.199.190", // all4nodes testnet
				"199.254.199.243",
				"202.182.101.169",
				"45.12.134.122",
				"45.250.255.44",
				"72.46.86.237",
				"72.46.86.39",
			},
		}
	default:
		return hyperliquidNetwork{
			Env:       "mainnet",
			WatchSlug: "hyperliquid",
			ChainID:   "999",
			ChainName: "Mainnet",
			BinaryURL: "https://binaries.hyperliquid.xyz/Mainnet/hl-visor",
			GossipPeers: []string{
				"72.46.86.185",
				"72.46.86.159",
			},
		}
	}
}

func hyperliquidSysListen(env string) int {
	switch normalizeEnv(env) {
	case "testnet":
		return 8593
	default:
		return 8590
	}
}

func isHyperliquidNetwork(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "hyperliquid")
}

func networkIsHyperliquid(cfg Config) bool {
	n := strings.ToLower(strings.TrimSpace(envOr("TRON_NETWORK", "")))
	if n == "hyperliquid" {
		return true
	}
	// Fallback: HyperEVM upstream :3001 / :3002.
	return cfg.UpstreamPort == 3001 || cfg.UpstreamPort == 3002
}

// resolveHyperliquidGossipPeers prefers a live peer list, falls back to cluster seeds.
// Returns error if still empty — writing empty override_gossip_config.json makes hl-node panic.
func resolveHyperliquidGossipPeers(cluster hyperliquidNetwork) ([]string, error) {
	// Merge live seeds with compiled-in fallbacks (live-only lists can be a single slow peer).
	peers := dedupeNonEmpty(append(fetchHyperliquidGossipPeers(cluster.ChainName), cluster.GossipPeers...))
	if len(peers) == 0 {
		return nil, fmt.Errorf(
			"hyperliquid/%s: root_node_ips empty — hl-node panics (gossip_config assertion); set GossipPeers",
			cluster.Env,
		)
	}

	return peers, nil
}

func dedupeNonEmpty(in []string) []string {
	seen := map[string]struct{}{}
	out := make([]string, 0, len(in))
	for _, s := range in {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		if _, ok := seen[s]; ok {
			continue
		}
		seen[s] = struct{}{}
		out = append(out, s)
	}

	return out
}

func fetchHyperliquidGossipPeers(chainName string) []string {
	chainName = strings.TrimSpace(chainName)
	client := &http.Client{Timeout: 8 * time.Second}

	switch chainName {
	case "Testnet":
		for _, url := range []string{
			"https://hyperliquid-testnet.imperator.co/peers.json",
			"https://hyperliquid-peers.all4nodes.io/",
		} {
			if ips := httpFetchGossipRootIPs(client, url); len(ips) > 0 {
				return ips
			}
		}
	case "Mainnet":
		if ips := httpFetchGossipRootIPsPOST(client, "https://api.hyperliquid.xyz/info", `{"type":"gossipRootIps"}`); len(ips) > 0 {
			return ips
		}
	}

	return nil
}

func httpFetchGossipRootIPs(client *http.Client, url string) []string {
	resp, err := client.Get(url)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil
	}

	return parseGossipRootIPsJSON(body)
}

func httpFetchGossipRootIPsPOST(client *http.Client, url, payload string) []string {
	resp, err := client.Post(url, "application/json", strings.NewReader(payload))
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil
	}

	return parseGossipRootIPsJSON(body)
}

func parseGossipRootIPsJSON(body []byte) []string {
	// Shape A: ["1.2.3.4", ...]
	var asStrings []string
	if err := json.Unmarshal(body, &asStrings); err == nil {
		return dedupeNonEmpty(asStrings)
	}

	// Shape B: {"root_node_ips":[{"Ip":"..."}, ...], ...}
	var wrap struct {
		RootNodeIPs []struct {
			IP string `json:"Ip"`
		} `json:"root_node_ips"`
	}
	if err := json.Unmarshal(body, &wrap); err == nil {
		out := make([]string, 0, len(wrap.RootNodeIPs))
		for _, e := range wrap.RootNodeIPs {
			out = append(out, e.IP)
		}

		return dedupeNonEmpty(out)
	}

	return nil
}
