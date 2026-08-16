package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const (
	agentLogDir          = "/var/log/rpcnode"
	agentLogrotatePath   = "/etc/logrotate.d/rpcnode-agents"
	agentLogMaxSize      = "100M"
	agentLogRotateCount  = 7
)

// agentLogPathForUnit — on-disk log for a tip/leaf/watchdog unit basename
// (with or without .service).
func agentLogPathForUnit(unitBasename string) string {
	base := strings.TrimSuffix(strings.TrimSpace(unitBasename), ".service")
	if base == "" {
		return ""
	}
	return filepath.Join(agentLogDir, base+".log")
}

func agentFileLogDropInBody(unitBasename string) string {
	logPath := agentLogPathForUnit(unitBasename)
	return fmt.Sprintf(`[Service]
# RpcNode: agent stdout/stderr → file; logrotate rotates by size (copytruncate).
StandardOutput=append:%s
StandardError=append:%s
`, logPath, logPath)
}

func agentLogrotateBody() string {
	return fmt.Sprintf(`# Managed by rpcnode (install / agent Update).
# Agents append here; copytruncate keeps open FDs valid across rotate.
/var/log/rpcnode.log
%s/*.log {
	size %s
	rotate %d
	compress
	delaycompress
	missingok
	notifempty
	copytruncate
	create 0640 root root
}
`, agentLogDir, agentLogMaxSize, agentLogRotateCount)
}

func writeAgentFileLogDropIn(unitBasename string) error {
	base := strings.TrimSuffix(strings.TrimSpace(unitBasename), ".service")
	if base == "" {
		return fmt.Errorf("empty unit name")
	}
	unitFile := base + ".service"
	unitPath := filepath.Join("/etc/systemd/system", unitFile)
	if _, err := os.Stat(unitPath); err != nil {
		return err
	}
	if err := os.MkdirAll(agentLogDir, 0o755); err != nil {
		return err
	}
	// Touch log so permissions exist before first append.
	logPath := agentLogPathForUnit(unitFile)
	if f, err := os.OpenFile(logPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o640); err == nil {
		_ = f.Close()
	}
	dropDir := filepath.Join("/etc/systemd/system", unitFile+".d")
	if err := os.MkdirAll(dropDir, 0o755); err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dropDir, "file-log.conf"), []byte(agentFileLogDropInBody(unitFile)), 0o644)
}

func listAgentUnitsForFileLogging() []string {
	matches := []string{}
	globs := []string{
		"/etc/systemd/system/rpcnode-api-agent.service",
		"/etc/systemd/system/rpcnode-system-agent.service",
		"/etc/systemd/system/rpcnode-agent-watchdog.service",
		"/etc/systemd/system/rpcnode-api-agent-*.service",
		"/etc/systemd/system/rpcnode-system-agent-*.service",
	}
	for _, g := range globs {
		if !strings.Contains(g, "*") {
			if _, err := os.Stat(g); err == nil {
				matches = append(matches, filepath.Base(g))
			}
			continue
		}
		found, err := filepath.Glob(g)
		if err != nil {
			continue
		}
		for _, p := range found {
			matches = append(matches, filepath.Base(p))
		}
	}
	// Dedupe.
	seen := map[string]bool{}
	out := make([]string, 0, len(matches))
	for _, u := range matches {
		if seen[u] {
			continue
		}
		seen[u] = true
		out = append(out, u)
	}
	return out
}

// ensureAgentFileLogging installs logrotate + systemd drop-ins so tip/leaf/watchdog
// agents write under /var/log/rpcnode/*.log (rotated by size). Idempotent.
func ensureAgentFileLogging() ([]string, error) {
	steps := []string{}
	if err := os.MkdirAll(agentLogDir, 0o755); err != nil {
		return steps, fmt.Errorf("mkdir %s: %w", agentLogDir, err)
	}
	steps = append(steps, "logdir "+agentLogDir)

	if f, err := os.OpenFile(defaultHostLogPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o640); err == nil {
		_ = f.Close()
		steps = append(steps, "hostlog "+defaultHostLogPath)
	}

	if err := os.WriteFile(agentLogrotatePath, []byte(agentLogrotateBody()), 0o644); err != nil {
		return steps, fmt.Errorf("write logrotate: %w", err)
	}
	steps = append(steps, "logrotate "+agentLogrotatePath)

	n := 0
	for _, unit := range listAgentUnitsForFileLogging() {
		if err := writeAgentFileLogDropIn(unit); err != nil {
			if os.IsNotExist(err) {
				continue
			}
			return steps, fmt.Errorf("drop-in %s: %w", unit, err)
		}
		n++
	}
	steps = append(steps, fmt.Sprintf("file-log drop-ins: %d units", n))
	return steps, nil
}
