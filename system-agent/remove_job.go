package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

var removeJobsDir = "/var/lib/rpcnode/remove-jobs"

// removeJobPending — tip wrote /var/lib/rpcnode/remove-jobs/<net>-<env>.json.
// Leaf pipeline must not enable/restart the node (or rewrite cfg) while wipe
// is in flight — that races systemctl kill and surfaces
// "Job canceled" / "SIGKILL … Invalid argument".
func removeJobPending(network, env string) bool {
	network = strings.ToLower(strings.TrimSpace(network))
	env = normalizeEnvName(env)
	if network == "" {
		return false
	}
	path := filepath.Join(removeJobsDir, network+"-"+env+".json")
	b, err := os.ReadFile(path)
	if err != nil {
		return false
	}
	var doc map[string]any
	if json.Unmarshal(b, &doc) != nil {
		return false
	}
	st, _ := doc["status"].(string)
	switch strings.ToLower(strings.TrimSpace(st)) {
	case "deleting", "started", "wiped", "error":
		return true
	default:
		return false
	}
}
