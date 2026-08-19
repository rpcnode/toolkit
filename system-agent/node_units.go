package main

import (
	"strings"
	"time"
)

// cfgNodeUnits — primary + aux units that recycle together (CL / op-node / consensus).
func cfgNodeUnits(cfg Config) []string {
	unit := strings.TrimSuffix(strings.TrimSpace(cfg.NodeService), ".service")
	if unit == "" {
		np := LookupNetworkProfile(cfg.Network, cfg.Env)
		unit = strings.TrimSuffix(np.ServiceUnit(), ".service")
	}
	if unit == "" {
		return nil
	}
	net := strings.ToLower(strings.TrimSpace(cfg.Network))
	env := strings.ToLower(strings.TrimSpace(cfg.Env))
	units := []string{unit}
	switch net {
	case "ethereum":
		units = append(units, "ethereum-lighthouse-"+env)
	case "optimism":
		units = append(units, "optimism-op-node-"+env)
	case "base":
		units = append(units, "base-consensus-"+env)
	}
	seen := map[string]bool{}
	out := make([]string, 0, len(units))
	for _, u := range units {
		u = strings.TrimSuffix(strings.TrimSpace(u), ".service")
		if u == "" || seen[u] {
			continue
		}
		seen[u] = true
		out = append(out, u)
	}
	return out
}

func cfgStopBudget(network string) time.Duration {
	switch strings.ToLower(strings.TrimSpace(network)) {
	case "bitcoin", "doge", "ltc", "dash", "bch", "zcash":
		return 25 * time.Second
	case "xrpl":
		return 35 * time.Second
	case "solana", "optimism", "stellar", "sui", "aptos":
		return 35 * time.Second
	case "avalanche":
		return 50 * time.Second
	default:
		return 50 * time.Second
	}
}
