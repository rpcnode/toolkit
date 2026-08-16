package main

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

const nodeIPAccountingDropIn = "ip-accounting.conf"

// applyNodeUnitIPAccounting inserts IP/CPU/MemoryAccounting before the first LimitNOFILE
// in a node (chain client) unit body. Idempotent. Do not use on tip/leaf agent units.
func applyNodeUnitIPAccounting(unitBody string) string {
	if strings.Contains(unitBody, "IPAccounting=") {
		// Backfill CPU/MemoryAccounting on older templates that only had IP.
		if !strings.Contains(unitBody, "CPUAccounting=") {
			unitBody = strings.Replace(unitBody, "IPAccounting=yes\n", "IPAccounting=yes\nCPUAccounting=yes\nMemoryAccounting=yes\n", 1)
		}
		return unitBody
	}
	return strings.Replace(unitBody, "LimitNOFILE=",
		"IPAccounting=yes\nCPUAccounting=yes\nMemoryAccounting=yes\nLimitNOFILE=", 1)
}

func nodeIPAccountingDropInBody() string {
	return `[Service]
# RpcNode: per-node NIC / CPU / Memory via systemd cgroup accounting (leaf metrics).
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
`
}

// writeNodeUnitIPAccountingDropIn installs unit.d/ip-accounting.conf for an existing node unit.
func writeNodeUnitIPAccountingDropIn(unitBasename string) error {
	base := strings.TrimSuffix(strings.TrimSpace(unitBasename), ".service")
	if base == "" {
		return fmt.Errorf("empty unit name")
	}
	unitFile := base + ".service"
	unitPath := filepath.Join("/etc/systemd/system", unitFile)
	if _, err := os.Stat(unitPath); err != nil {
		return err
	}
	dropDir := filepath.Join("/etc/systemd/system", unitFile+".d")
	if err := os.MkdirAll(dropDir, 0o755); err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dropDir, nodeIPAccountingDropIn), []byte(nodeIPAccountingDropInBody()), 0o644)
}

// enableNodeUnitIPAccountingLive — runtime property so counters start without
// restarting a syncing fullnode (drop-in still covers reboot / next start).
func enableNodeUnitIPAccountingLive(unitBasename string) {
	base := strings.TrimSuffix(strings.TrimSpace(unitBasename), ".service")
	if base == "" {
		return
	}
	unit := base + ".service"
	ctxTimeout := 5 * time.Second
	done := make(chan struct{})
	go func() {
		defer close(done)
		cmd := exec.Command("systemctl", "set-property", unit,
			"IPAccounting=yes", "CPUAccounting=yes", "MemoryAccounting=yes")
		_ = cmd.Run()
	}()
	select {
	case <-done:
	case <-time.After(ctxTimeout):
	}
}

func listNodeUnitsForIPAccounting() []string {
	dir := "/etc/rpcnode/instances.d"
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	seen := map[string]bool{}
	var out []string
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		b, err := os.ReadFile(filepath.Join(dir, e.Name()))
		if err != nil {
			continue
		}
		var doc map[string]any
		if json.Unmarshal(b, &doc) != nil {
			continue
		}
		for _, u := range nodeUnitNamesFromInstance(doc) {
			if seen[u] {
				continue
			}
			seen[u] = true
			out = append(out, u)
		}
	}
	return out
}

// nodeUnitNamesFromInstance — primary chain unit(s) for IP accounting (not agents).
func nodeUnitNamesFromInstance(doc map[string]any) []string {
	if doc == nil {
		return nil
	}
	var out []string
	add := func(s string) {
		s = strings.TrimSpace(s)
		if s == "" {
			return
		}
		if !strings.HasSuffix(s, ".service") {
			s += ".service"
		}
		// Never tip/leaf agent units.
		base := strings.TrimSuffix(s, ".service")
		if strings.HasPrefix(base, "rpcnode-api-agent") ||
			strings.HasPrefix(base, "rpcnode-system-agent") ||
			strings.HasPrefix(base, "rpcnode-agent-watchdog") {
			return
		}
		out = append(out, s)
	}
	for _, k := range []string{"node_unit", "service", "systemd_unit"} {
		if s, _ := doc[k].(string); s != "" {
			add(s)
		}
	}
	if arr, ok := doc["units"].([]any); ok {
		for _, x := range arr {
			s, _ := x.(string)
			add(s)
		}
	}
	network, _ := doc["network"].(string)
	env, _ := doc["env"].(string)
	network = strings.ToLower(strings.TrimSpace(network))
	env = strings.ToLower(strings.TrimSpace(env))
	if network != "" && env != "" {
		switch network {
		case "tron":
			add(fmt.Sprintf("tron-%s.service", env))
		case "ton":
			// MyTonCtrl validator is the heavy NIC consumer.
			add("validator.service")
			add(fmt.Sprintf("ton-%s.service", env))
		case "ethereum":
			add(fmt.Sprintf("ethereum-geth-%s.service", env))
			add(fmt.Sprintf("ethereum-lighthouse-%s.service", env))
		case "optimism":
			add(fmt.Sprintf("optimism-%s.service", env))
			add(fmt.Sprintf("optimism-op-node-%s.service", env))
		case "base":
			add(fmt.Sprintf("base-%s.service", env))
			add(fmt.Sprintf("base-consensus-%s.service", env))
		case "cardano":
			add(fmt.Sprintf("cardano-%s.service", env))
			add(fmt.Sprintf("cardano-ogmios-%s.service", env))
		default:
			add(fmt.Sprintf("%s-%s.service", network, env))
		}
	}
	seen := map[string]bool{}
	dedup := make([]string, 0, len(out))
	for _, u := range out {
		if seen[u] {
			continue
		}
		seen[u] = true
		dedup = append(dedup, u)
	}
	return dedup
}

// ensureAllNodeIPAccounting — drop-ins + live set-property for every INSTANCE node unit.
// Idempotent; safe on tip Update (does not restart chain units).
func ensureAllNodeIPAccounting() ([]string, error) {
	units := listNodeUnitsForIPAccounting()
	n := 0
	for _, u := range units {
		if err := writeNodeUnitIPAccountingDropIn(u); err != nil {
			if os.IsNotExist(err) {
				continue
			}
			return nil, fmt.Errorf("ip-accounting drop-in %s: %w", u, err)
		}
		enableNodeUnitIPAccountingLive(u)
		n++
	}
	if n > 0 {
		_ = exec.Command("systemctl", "daemon-reload").Run()
	}
	return []string{fmt.Sprintf("node IPAccounting drop-ins: %d units", n)}, nil
}

// ensureNodeUnitIPAccounting — single unit after provision write.
func ensureNodeUnitIPAccounting(unitBasename string) error {
	if err := writeNodeUnitIPAccountingDropIn(unitBasename); err != nil {
		return err
	}
	enableNodeUnitIPAccountingLive(unitBasename)
	return nil
}
