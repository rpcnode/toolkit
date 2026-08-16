package main

import (
	"fmt"
	"os"
	"os/exec"
	"strings"
)

// network_constraints — host-level product limits advertised on tip healthz / plan / provision.
//
// Hyperliquid: the official hl-node binary panics if another process matching the
// keyword "hl-node" exists anywhere on the host (net_utils/child.rs). Renaming units
// / datadirs is not enough — only one Hyperliquid env per host.

const constraintOneEnvPerHost = "one_env_per_host"

// networkHostConstraints — static map for tip healthz (all networks that have limits).
func networkHostConstraints() map[string]any {
	out := map[string]any{}
	for _, n := range supportedNetworks() {
		if c := networkConstraint(n); c != nil {
			out[n] = c
		}
	}

	return out
}

func networkConstraint(network string) map[string]any {
	switch strings.ToLower(strings.TrimSpace(network)) {
	case "hyperliquid":
		return map[string]any{
			"one_env_per_host": true,
			"code":            "process_name_singleton",
			"reason":          "hl-node panics if another process matching keyword \"hl-node\" exists on the host (Hyperliquid client check)",
		}
	case "ton":
		return map[string]any{
			"one_env_per_host": true,
			"code":            "mytonctrl_global_workdir",
			"reason":          "MyTonCtrl uses host-global /var/ton-work + validator/mytoncore units — only one TON env per host",
		}
	default:
		return nil
	}
}

func networkOneEnvPerHost(network string) bool {
	c := networkConstraint(network)
	if c == nil {
		return false
	}
	v, _ := c["one_env_per_host"].(bool)

	return v
}

// occupiedEnvsForNetwork — envs already provisioned on this host (instances.d / nodes).
func occupiedEnvsForNetwork(network string) []string {
	network = strings.ToLower(strings.TrimSpace(network))
	if network == "" {
		return nil
	}
	seen := map[string]bool{}
	var out []string
	add := func(env string) {
		env = normalizeEnv(env)
		if env == "" || seen[env] {
			return
		}
		seen[env] = true
		out = append(out, env)
	}
	for _, dir := range []string{"/etc/rpcnode/instances.d", "/etc/rpcnode/nodes"} {
		entries, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		prefix := network + "-"
		for _, e := range entries {
			name := e.Name()
			if !strings.HasPrefix(name, prefix) || !strings.HasSuffix(name, ".json") {
				continue
			}
			env := strings.TrimSuffix(strings.TrimPrefix(name, prefix), ".json")
			add(env)
		}
	}
	// Live Hyperliquid unit even if JSON was wiped mid-flight.
	if network == "hyperliquid" {
		for _, env := range []string{"mainnet", "testnet"} {
			if unitIsActive(fmt.Sprintf("hyperliquid-%s.service", env)) {
				add(env)
			}
		}
	}

	return out
}

// checkOneEnvPerHost — nil if OK; error describing conflict otherwise.
// Idempotent re-provision of the *same* env is allowed.
func checkOneEnvPerHost(network, wantEnv string) error {
	if !networkOneEnvPerHost(network) {
		return nil
	}
	wantEnv = normalizeEnv(wantEnv)
	for _, occ := range occupiedEnvsForNetwork(network) {
		if occ == wantEnv {
			continue
		}
		c := networkConstraint(network)
		reason, _ := c["reason"].(string)
		code, _ := c["code"].(string)
		if reason == "" {
			reason = "only one environment of this network is allowed per host"
		}

		return fmt.Errorf("%s: %s/%s already on host — cannot add %s/%s (%s; code=%s)",
			constraintOneEnvPerHost, network, occ, network, wantEnv, reason, code)
	}

	return nil
}

func unitIsActive(unit string) bool {
	unit = strings.TrimSpace(unit)
	if unit == "" {
		return false
	}
	out, err := exec.Command("systemctl", "is-active", unit).CombinedOutput()
	if err != nil {
		return false
	}

	return strings.TrimSpace(string(out)) == "active"
}
