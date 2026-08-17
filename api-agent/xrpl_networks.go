package main

import "strings"

// xrplNetwork — stock xrpld metadata (mainnet / AltNet testnet).
type xrplNetwork struct {
	Env       string
	WatchSlug string
	NetworkID string // empty = mainnet default; "1" = XRPL Testnet
	IPSFixed  []string
}

func lookupXRPLNetwork(env string) xrplNetwork {
	switch normalizeEnv(env) {
	case "testnet":
		return xrplNetwork{
			Env:       "testnet",
			WatchSlug: "xrpl-testnet",
			NetworkID: "1",
			IPSFixed: []string{
				"s.altnet.rippletest.net 51235",
			},
		}
	default:
		return xrplNetwork{
			Env:       "mainnet",
			WatchSlug: "xrpl",
			NetworkID: "",
			// Official full-history pool — peer-to-peer backfill needs a direct
			// history peer (https://xrpl.org/docs/infrastructure/configuration/data-retention/configure-full-history).
			// Hubs in ips_fixed — [ips] is bootstrap-only and drops. s2 alone
			// is the history pool; first ledger needs a stock hub (r.ripple.com).
			IPSFixed: append(xrplMainnetHubs(), "s2.ripple.com 51235"),
		}
	}
}

func xrplSysListen(env string) int {
	switch normalizeEnv(env) {
	case "testnet":
		return 8601
	default:
		return 8600
	}
}

func isXRPLNetwork(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "xrpl")
}

// xrplMainnetHubs — official example.cfg starter list (port 51235 required).
func xrplMainnetHubs() []string {
	return []string{
		"r.ripple.com 51235",
		"sahyadri.isrdc.in 51235",
		"hubs.xrpkuwait.com 51235",
		"hub.xrpl-commons.org 51235",
	}
}
