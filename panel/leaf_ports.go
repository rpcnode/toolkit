package main

import (
	"net"
	"net/url"
	"strconv"
	"strings"
)

// Canonical per-node Agent API ports — Server tip must never use these.
// Keep aligned with api-agent/network_ports.go + install/agent.sh is_per_node_agent_port.
var leafAgentPorts = map[int]struct{}{
	39190: {}, 39191: {}, 39192: {}, // tron
	39390: {}, 39391: {}, 39392: {}, 39393: {}, // bitcoin
	39590: {}, 39591: {}, 39592: {}, 39593: {}, // solana
	39790: {}, 39791: {}, 39792: {}, // ethereum
	39990: {}, 39991: {}, // bsc
	40190: {}, 40191: {}, 40192: {}, 40193: {}, 40194: {}, 40195: {}, // hl/arb/op
	40390: {}, 40391: {}, // xrpl
	40590: {}, 40591: {}, // doge
	40790: {}, 40791: {}, 40792: {}, // cardano
	40990: {}, 40991: {}, 40992: {}, // stellar
	41190: {}, 41191: {}, 41192: {}, // ltc
	41390: {}, 41391: {}, 41392: {}, // dash
	41590: {}, 41591: {}, 41592: {}, // bch
	41790: {}, 41791: {}, // ton
	41990: {}, 41991: {}, // etc
	42190: {}, 42191: {}, // robinhood
	42390: {}, 42391: {}, // base
	42590: {}, 42591: {}, // zcash
	42790: {}, 42791: {}, // sui
	42990: {}, 42991: {}, // aptos
	43190: {}, 43191: {}, // avalanche
}

func isLeafAgentPort(port int) bool {
	if port <= 0 {
		return false
	}
	_, ok := leafAgentPorts[port]
	return ok
}

func agentURLPort(agentURL string) int {
	u, err := url.Parse(strings.TrimSpace(agentURL))
	if err != nil || u.Host == "" {
		return 0
	}
	_, portStr, err := net.SplitHostPort(u.Host)
	if err != nil {
		return 0
	}
	n, err := strconv.Atoi(portStr)
	if err != nil {
		return 0
	}
	return n
}
