package main

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

func tronJSONRPCPort(env string) int {
	switch strings.ToLower(strings.TrimSpace(env)) {
	case "nile":
		return 18546
	case "shasta":
		return 18547
	default:
		return 18545
	}
}

func patchTronJSONRPC(t string, port int) string {
	if port <= 0 {
		port = 18545
	}

	t = regexp.MustCompile(`(?m)^\s*#?\s*httpFullNodeEnable\s*=\s*(true|false)\s*$`).
		ReplaceAllString(t, "    httpFullNodeEnable = true")
	t = regexp.MustCompile(`(?m)^\s*#?\s*httpFullNodePort\s*=\s*\d+\s*$`).
		ReplaceAllString(t, fmt.Sprintf("    httpFullNodePort = %d", port))

	if !regexp.MustCompile(`(?m)^\s*httpFullNodeEnable\s*=\s*true\s*$`).MatchString(t) {
		if strings.Contains(t, "jsonrpc {") {
			t = strings.Replace(t, "jsonrpc {", fmt.Sprintf("jsonrpc {\n    httpFullNodeEnable = true\n    httpFullNodePort = %d", port), 1)
		} else if strings.Contains(t, "node {") {
			t = strings.Replace(t, "node {", fmt.Sprintf("node {\n  jsonrpc {\n    httpFullNodeEnable = true\n    httpFullNodePort = %d\n  }", port), 1)
		}
	}

	return t
}

func ensureTronJSONRPCConfFile(conf, env string) (bool, error) {
	raw, err := os.ReadFile(conf)
	if err != nil {
		return false, err
	}

	next := patchTronJSONRPC(string(raw), tronJSONRPCPort(env))
	if next == string(raw) {
		return false, nil
	}

	if err := os.WriteFile(conf, []byte(next), 0o640); err != nil {
		return false, err
	}

	return true, nil
}

func ensureTronJSONRPCConf(cfg Config) (bool, error) {
	if !strings.EqualFold(strings.TrimSpace(cfg.Network), "tron") && strings.TrimSpace(cfg.Network) != "" {
		return false, nil
	}

	etc := strings.TrimSpace(cfg.EtcDir)
	if etc == "" {
		return false, nil
	}

	conf := filepath.Join(etc, "main_net_config.conf")
	if _, err := os.Stat(conf); err != nil {
		conf = filepath.Join(etc, "config.conf")
	}
	if _, err := os.Stat(conf); err != nil {
		return false, nil
	}

	return ensureTronJSONRPCConfFile(conf, cfg.Env)
}
