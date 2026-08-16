package main

import (
	"fmt"
	"regexp"
	"strings"
)

// Port / datadir keys are never editable in the node config UI.
// Catalog ports + provision data paths stay owned by RpcNode.

var (
	rePortFlag    = regexp.MustCompile(`(?i)((?:--)?[a-z0-9_.-]*port[a-z0-9_.-]*)[=:\s]+(\d{2,5})\b`)
	reJSONPort    = regexp.MustCompile(`(?i)"([^"]*port[^"]*)"\s*:\s*(\d{2,5})\b`)
	reHostPortKV  = regexp.MustCompile(`(?i)^\s*([A-Za-z0-9_.-]*(?:ENDPOINT|LISTEN|ADDR|BIND|URL|HOST)[A-Za-z0-9_.-]*)\s*=\s*"?([^"\n]*:(\d{2,5}))"?`)
	reZmqURL      = regexp.MustCompile(`(?i)^\s*(zmq[a-z0-9_]*)\s*=\s*(\S+)`)
	reDataDirFlag = regexp.MustCompile(`(?i)((?:--)?(?:[a-z0-9_.-]*(?:data[_.-]?dir|db[_.-]?path|blocks[_.-]?dir|wallet[_.-]?dir|ledger[_.-]?path|accounts[_.-]?path|chain[_.-]?data)|wallet))[=:\s]+("([^"]+)"|'([^']+)'|(\S+))`)
	reJSONDataDir = regexp.MustCompile(`(?i)"([^"]*(?:data[_.-]?dir|db[_.-]?path|blocks[_.-]?dir|wallet[_.-]?dir|ledger[_.-]?path|accounts[_.-]?path|chain[_.-]?data)|wallet)"\s*:\s*"([^"]*)"`)
)

func mergeProtectedKeys(extra ...string) []string {
	base := []string{
		// Core-like
		"rpcport", "rpcbind", "port", "bind", "whitebind",
		"zmqpubrawblock", "zmqpubrawtx", "zmqpubhashtx", "zmqpubhashblock",
		// Geth / BSC / EVM toml
		"HTTPPort", "HTTPHost", "WSPort", "WSHost", "AuthPort", "AuthAddr",
		"ListenAddr", "DiscoveryPort", "Port", "DiscPort",
		// Stellar / XRPL / generic
		"ENDPOINT", "ADMIN_ENDPOINT", "PEER_PORT", "HTTP_PORT", "SERVER_PORT",
		"port_rpc_admin_local", "port_ws_public", "port_peer",
		// Auth (also locked — not ports, but agent wiring)
		"rpcuser", "rpcpassword", "rpcauth", "rpcallowip",
		// Chain data location (provision-owned — same as ports)
		"datadir", "data_dir", "DataDir", "dbPath", "db_path", "DbPath", "db-path",
		"blocksdir", "walletdir", "wallet",
		"ledger-path", "ledger_path", "accounts-path", "accounts_path",
		"--datadir", "--ledger-path", "--accounts-path",
		// Sui fullnode.yaml
		"json-rpc-address", "metrics-address",
		// AvalancheGo
		"http-port", "staking-port", "http-host", "data-dir", "db-dir",
		"chain-config-dir", "network-id",
	}
	seen := map[string]bool{}
	out := make([]string, 0, len(base)+len(extra))
	for _, k := range append(base, extra...) {
		k = strings.TrimSpace(k)
		if k == "" {
			continue
		}
		lk := strings.ToLower(k)
		if seen[lk] {
			continue
		}
		seen[lk] = true
		out = append(out, k)
	}
	return out
}

func isPortLikeKey(key string) bool {
	k := strings.ToLower(strings.TrimSpace(key))
	if k == "" {
		return false
	}
	// Avoid false positives.
	if strings.Contains(k, "password") || strings.Contains(k, "passport") || strings.Contains(k, "opportunity") {
		return false
	}
	if k == "port" || strings.HasPrefix(k, "port_") || strings.HasPrefix(k, "port.") {
		return true
	}
	if strings.HasSuffix(k, "port") || strings.Contains(k, "_port") || strings.Contains(k, ".port") || strings.Contains(k, "-port") {
		return true
	}
	if strings.HasPrefix(k, "zmq") {
		return true
	}
	// host:port endpoints / listen binds owned by catalog.
	switch {
	case strings.Contains(k, "endpoint"):
		return true
	case strings.HasSuffix(k, "bind") || strings.Contains(k, "rpcbind") || strings.Contains(k, "whitebind"):
		return true
	case strings.Contains(k, "listen") && (strings.Contains(k, "addr") || strings.Contains(k, "port")):
		return true
	case k == "httphost" || k == "wshost" || k == "authaddr" || k == "listenaddr":
		return true
	}
	return false
}

func isDataDirLikeKey(key string) bool {
	k := strings.ToLower(strings.TrimSpace(key))
	k = strings.TrimPrefix(k, "--")
	if k == "" {
		return false
	}
	// Avoid false positives (dbcache, password, …).
	if strings.Contains(k, "password") || strings.Contains(k, "cache") || strings.Contains(k, "timeout") {
		return false
	}
	switch k {
	case "datadir", "data_dir", "data-dir", "data.dir",
		"dbpath", "db_path", "db-path", "db.path",
		"blocksdir", "blocks_dir", "blocks-dir",
		"walletdir", "wallet_dir", "wallet-dir",
		"wallet",
		"ledger-path", "ledger_path", "ledgerpath", "ledger.path",
		"accounts-path", "accounts_path", "accountspath", "accounts.path",
		"chaindata", "chain_data", "chain-data",
		"dbhome", "db_home", "db-home":
		return true
	}
	if strings.HasSuffix(k, "datadir") || strings.HasSuffix(k, "data_dir") || strings.HasSuffix(k, "data-dir") || strings.HasSuffix(k, "data.dir") {
		return true
	}
	if strings.HasSuffix(k, "dbpath") || strings.HasSuffix(k, "db_path") || strings.HasSuffix(k, "db-path") || strings.HasSuffix(k, "db.path") {
		return true
	}
	if strings.HasSuffix(k, "blocksdir") || strings.HasSuffix(k, "walletdir") {
		return true
	}
	if strings.Contains(k, "ledger") && strings.Contains(k, "path") {
		return true
	}
	if strings.Contains(k, "accounts") && strings.Contains(k, "path") {
		return true
	}
	if strings.Contains(k, "chain") && strings.Contains(k, "data") && !strings.Contains(k, "database") {
		return true
	}
	return false
}

func isLockedConfigKey(key string, protected []string) bool {
	if isPortLikeKey(key) || isDataDirLikeKey(key) {
		return true
	}
	return isProtectedKey(key, protected)
}

// extractPortBindings — fingerprint of port-related settings for drift detection
// across ini/env/toml/unit/shell/json.
func extractPortBindings(content string) map[string]string {
	out := map[string]string{}
	// 1) Loose KV port-like keys.
	for k, v := range parseLooseKV(content) {
		if strings.ToLower(k) != k {
			continue // prefer lowercase entries from parseLooseKV
		}
		if isPortLikeKey(k) {
			out["kv:"+strings.ToLower(k)] = v
		}
	}
	// 2) CLI flags: --http.port 8545 / --rpc-port=8899
	for _, m := range rePortFlag.FindAllStringSubmatch(content, -1) {
		if len(m) < 3 {
			continue
		}
		key := strings.ToLower(strings.TrimSpace(m[1]))
		if !isPortLikeKey(key) && !strings.Contains(key, "port") {
			continue
		}
		out["flag:"+key] = m[2]
	}
	// 3) JSON "httpPort": 8545
	for _, m := range reJSONPort.FindAllStringSubmatch(content, -1) {
		if len(m) < 3 {
			continue
		}
		key := strings.ToLower(strings.TrimSpace(m[1]))
		if isPortLikeKey(key) || strings.Contains(key, "port") {
			out["json:"+key] = m[2]
		}
	}
	// 4) ENDPOINT=host:port style
	for _, line := range strings.Split(content, "\n") {
		if m := reHostPortKV.FindStringSubmatch(line); len(m) >= 4 {
			key := strings.ToLower(strings.TrimSpace(m[1]))
			out["hostport:"+key] = m[3]
		}
		if m := reZmqURL.FindStringSubmatch(line); len(m) >= 3 {
			out["zmq:"+strings.ToLower(m[1])] = strings.TrimSpace(m[2])
		}
	}
	return out
}

// extractDataDirBindings — fingerprint of chain-data path settings.
func extractDataDirBindings(content string) map[string]string {
	out := map[string]string{}
	for k, v := range parseLooseKV(content) {
		if strings.ToLower(k) != k {
			continue
		}
		if isDataDirLikeKey(k) {
			out["kv:"+strings.ToLower(k)] = v
		}
	}
	for _, m := range reDataDirFlag.FindAllStringSubmatch(content, -1) {
		if len(m) < 2 {
			continue
		}
		key := strings.ToLower(strings.TrimSpace(m[1]))
		if !isDataDirLikeKey(key) {
			continue
		}
		val := ""
		for _, g := range m[3:] {
			if g != "" {
				val = g
				break
			}
		}
		if val == "" && len(m) >= 3 {
			val = strings.Trim(m[2], `"'`)
		}
		out["flag:"+key] = val
	}
	for _, m := range reJSONDataDir.FindAllStringSubmatch(content, -1) {
		if len(m) < 3 {
			continue
		}
		key := strings.ToLower(strings.TrimSpace(m[1]))
		if isDataDirLikeKey(key) {
			out["json:"+key] = m[2]
		}
	}
	return out
}

func assertPortBindingsUnchanged(oldContent, newContent string) error {
	return assertLockedBindingsUnchanged(oldContent, newContent)
}

// assertLockedBindingsUnchanged rejects edits to catalog ports and datadir-like paths
// (raw editor included).
func assertLockedBindingsUnchanged(oldContent, newContent string) error {
	if strings.TrimSpace(oldContent) == "" {
		return nil
	}
	checks := []struct {
		kind string
		old  map[string]string
		new  map[string]string
		noun string
	}{
		{"port", extractPortBindings(oldContent), extractPortBindings(newContent), "ports"},
		{"datadir", extractDataDirBindings(oldContent), extractDataDirBindings(newContent), "data directory paths"},
	}
	for _, c := range checks {
		for k, ov := range c.old {
			nv, ok := c.new[k]
			if !ok {
				return fmt.Errorf("%s setting %q removed (was %v) — %s are fixed by RpcNode catalog", c.kind, k, ov, c.noun)
			}
			if nv != ov {
				return fmt.Errorf("%s setting %q changed %v → %v — %s are not editable", c.kind, k, ov, nv, c.noun)
			}
		}
	}
	return nil
}
