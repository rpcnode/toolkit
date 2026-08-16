package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

type xrplHistoryPolicy struct {
	Mode    string `json:"mode"`
	Ledgers int    `json:"ledgers"`
}

var xrplCfgLedgerHistoryRe = regexp.MustCompile(`(?m)^\[ledger_history\]\s*\n([^\n#[]*)`)

func parseXRPLHistoryMode(raw string) xrplHistoryPolicy {
	s := strings.ToLower(strings.TrimSpace(raw))
	switch s {
	case "stock", "default", "2000":
		return xrplHistoryPolicy{Mode: "stock", Ledgers: 2000}
	case "day", "1d", "25000":
		return xrplHistoryPolicy{Mode: "day", Ledgers: 25000}
	case "weeks", "14d", "2w", "300000":
		return xrplHistoryPolicy{Mode: "weeks", Ledgers: 300000}
	case "full":
		return xrplHistoryPolicy{Mode: "full", Ledgers: 0}
	case "":
		return xrplHistoryPolicy{Mode: "weeks", Ledgers: 300000}
	default:
		if n, err := strconv.Atoi(s); err == nil && n >= 256 {
			return xrplHistoryPolicy{Mode: "custom", Ledgers: n}
		}

		return xrplHistoryPolicy{Mode: "weeks", Ledgers: 300000}
	}
}

func xrplHistoryPolicyPath(etc string) string {
	return filepath.Join(etc, "history.json")
}

func writeXRPLHistoryPolicy(etc string, p xrplHistoryPolicy) error {
	if err := os.MkdirAll(etc, 0o755); err != nil {
		return err
	}

	raw, err := json.MarshalIndent(p, "", "  ")
	if err != nil {
		return err
	}

	return os.WriteFile(xrplHistoryPolicyPath(etc), append(raw, '\n'), 0o644)
}

func loadXRPLHistoryPolicy(etc string) (xrplHistoryPolicy, bool) {
	raw, err := os.ReadFile(xrplHistoryPolicyPath(etc))
	if err != nil {
		return xrplHistoryPolicy{}, false
	}

	var p xrplHistoryPolicy
	if json.Unmarshal(raw, &p) != nil {
		return xrplHistoryPolicy{}, false
	}

	if p.Mode == "full" {
		p.Ledgers = 0

		return p, true
	}

	if p.Ledgers >= 256 {
		if p.Mode == "" {
			p.Mode = "custom"
		}

		return p, true
	}

	if p.Mode != "" {
		return parseXRPLHistoryMode(p.Mode), true
	}

	return xrplHistoryPolicy{}, false
}

func parseXRPLHistoryFromCfg(cfg string) (xrplHistoryPolicy, bool) {
	m := xrplCfgLedgerHistoryRe.FindStringSubmatch(cfg)
	if len(m) < 2 {
		return xrplHistoryPolicy{}, false
	}

	return parseXRPLHistoryMode(strings.TrimSpace(m[1])), true
}

func resolveXRPLHistoryPolicy(etc, requested string) xrplHistoryPolicy {
	req := strings.TrimSpace(requested)
	if req != "" {
		return parseXRPLHistoryMode(req)
	}

	if p, ok := loadXRPLHistoryPolicy(etc); ok {
		return p
	}

	if raw, err := os.ReadFile(filepath.Join(etc, "xrpld.cfg")); err == nil {
		if p, ok := parseXRPLHistoryFromCfg(string(raw)); ok {
			return p
		}
	}

	return parseXRPLHistoryMode("weeks")
}
