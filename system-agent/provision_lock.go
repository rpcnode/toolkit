package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

var provisionLocksDir = "/var/lib/rpcnode/provision-locks"

// provisionLockPending — tip is still writing units/cfg (Scylla apt etc.).
// Leaf must not systemctl start xrpl-*.service or recycle while files appear/disappear.
func provisionLockPending(network, env string) bool {
	network = strings.ToLower(strings.TrimSpace(network))
	env = normalizeEnvName(env)
	if network == "" {
		return false
	}

	path := filepath.Join(provisionLocksDir, network+"-"+env+".json")
	b, err := os.ReadFile(path)
	if err != nil {
		return false
	}

	var doc map[string]any
	if json.Unmarshal(b, &doc) != nil {
		return false
	}

	st, _ := doc["status"].(string)

	return strings.EqualFold(strings.TrimSpace(st), "running")
}
