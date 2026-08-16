package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

func tronUnitPath(cfg Config) string {
	unit := strings.TrimSpace(cfg.NodeService)
	if unit == "" {
		unit = "tron-" + normalizeEnvName(cfg.Env)
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	return filepath.Join("/etc/systemd/system", unit)
}

func isTronUnitStubFile(path string) bool {
	b, err := os.ReadFile(path)
	if err != nil {
		return false
	}
	s := string(b)
	return strings.Contains(s, "ExecStart=/bin/false") || strings.Contains(s, "provisioned stub")
}

func tronAppLogSnippet(cfg Config, n int) string {
	if n <= 0 {
		n = 20
	}
	opt := strings.TrimSpace(cfg.OptDir)
	if opt == "" {
		opt = filepath.Join("/opt/tron", normalizeEnvName(cfg.Env))
	}
	path := filepath.Join(opt, "logs", "tron.log")
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	lines := strings.Split(string(b), "\n")
	var picked []string
	for _, ln := range lines {
		if strings.Contains(ln, "[Exit]") || strings.Contains(ln, "CHECKPOINT") ||
			strings.Contains(ln, "Java 1.8") || strings.Contains(ln, "ERROR [main]") {
			picked = append(picked, strings.TrimSpace(ln))
		}
	}
	if len(picked) == 0 {
		if len(lines) > n {
			lines = lines[len(lines)-n:]
		}
		return strings.TrimSpace(strings.Join(lines, " | "))
	}
	if len(picked) > 4 {
		picked = picked[len(picked)-4:]
	}
	return strings.Join(picked, " | ")
}

// tronStartFailureDetail — stub unit / Java / checkpoint crash → start=error (not "Ready to start").
func tronStartFailureDetail(cfg Config, procOK bool) string {
	if !strings.EqualFold(cfg.Network, "tron") && cfg.Network != "" {
		return ""
	}
	if procOK {
		return ""
	}
	unitPath := tronUnitPath(cfg)
	probe := probeSystemdUnit(cfg.NodeService)
	logSnip := tronAppLogSnippet(cfg, 24)
	jSnip := journalUnitSnippet(cfg.NodeService, 12)

	switch {
	case isTronUnitStubFile(unitPath):
		return "tron unit is still a stub (ExecStart=/bin/false) — install FullNode.jar + Java 8, then Start again"
	case strings.Contains(logSnip, "checkpoint") || strings.Contains(logSnip, "CHECKPOINT"):
		detail := "java-tron checkpoint mismatch — set storage.checkpoint.version = 2 in main_net_config.conf"
		if logSnip != "" {
			detail = detail + " — " + logSnip
		}
		return detail
	case strings.Contains(jSnip, "Java 1.8 is required") || strings.Contains(logSnip, "Java 1.8"):
		return "Java 8 required for amd64 java-tron — Start again so the agent installs Java 8 (PATH java 17 is ignored)"
	case probe.Failed || probe.ActiveState == "failed" ||
		(!procOK && (probe.Result == "exit-code" || probe.Result == "signal") && probe.NRestarts >= 1):
		detail := fmt.Sprintf("tron-%s unit failed (Result=%s, restarts=%d)",
			normalizeEnvName(cfg.Env), probe.Result, probe.NRestarts)
		if logSnip != "" {
			return detail + ": " + logSnip
		}
		if jSnip != "" {
			return detail + ": " + jSnip
		}
		return detail
	}
	return ""
}
