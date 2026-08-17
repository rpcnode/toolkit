package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"time"
)

var provisionLocksDir = "/var/lib/rpcnode/provision-locks"

func provisionLockPath(network, env string) string {
	network = strings.ToLower(strings.TrimSpace(network))
	env = normalizeEnv(env)

	return filepath.Join(provisionLocksDir, network+"-"+env+".json")
}

func beginProvisionLock(network, env string) error {
	if err := os.MkdirAll(provisionLocksDir, 0o755); err != nil {
		return err
	}

	doc := map[string]any{
		"network":    strings.ToLower(strings.TrimSpace(network)),
		"env":        normalizeEnv(env),
		"status":     "running",
		"started_at": time.Now().UTC().Format(time.RFC3339),
	}

	raw, err := json.Marshal(doc)
	if err != nil {
		return err
	}

	return os.WriteFile(provisionLockPath(network, env), append(raw, '\n'), 0o644)
}

func endProvisionLock(network, env string) {
	_ = os.Remove(provisionLockPath(network, env))
}
