package main

import (
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

func findStellarRPCBin(cfg Config) string {
	if v := strings.TrimSpace(os.Getenv("STELLAR_RPC_BIN")); v != "" && fileExists(v) {
		return v
	}
	for _, p := range []string{
		filepath.Join(cfg.OptDir, "bin", "stellar-rpc"),
		"/opt/stellar/bin/stellar-rpc",
		"/usr/bin/stellar-rpc",
		"/usr/local/bin/stellar-rpc",
	} {
		if fileExists(p) {
			return p
		}
	}
	if path, err := exec.LookPath("stellar-rpc"); err == nil && path != "" {
		return path
	}
	return ""
}

// Must match api-agent stellarHistoryRetentionWindow — never prune local tx/events.
const stellarHistoryRetentionWindow = uint32(math.MaxUint32)

// ensureStellarFullHistoryToml patches HISTORY_RETENTION_WINDOW + captive-core HTTP=0
// + HTTP_QUERY_PORT (required by stellar-rpc; default 11628 if unset).
func ensureStellarFullHistoryToml(etc string) (bool, error) {
	path := filepath.Join(etc, "stellar-rpc.toml")
	b, err := os.ReadFile(path)
	if err != nil {
		return false, err
	}
	changed := false
	want := strconv.FormatUint(uint64(stellarHistoryRetentionWindow), 10)
	re := regexp.MustCompile(`(?m)^(\s*HISTORY_RETENTION_WINDOW\s*=\s*)(\d+)\s*$`)
	m := re.FindSubmatch(b)
	if m == nil {
		b = []byte(strings.TrimRight(string(b), "\n") + "\nHISTORY_RETENTION_WINDOW = " + want + "\n")
		changed = true
	} else if string(m[2]) != want {
		b = re.ReplaceAll(b, []byte("${1}"+want))
		changed = true
	}
	reHTTP := regexp.MustCompile(`(?m)^(\s*STELLAR_CAPTIVE_CORE_HTTP_PORT\s*=\s*)(\d+)\s*$`)
	if hm := reHTTP.FindSubmatch(b); hm == nil {
		b = []byte(strings.TrimRight(string(b), "\n") + "\nSTELLAR_CAPTIVE_CORE_HTTP_PORT = 0\n")
		changed = true
	} else if string(hm[2]) != "0" {
		b = reHTTP.ReplaceAll(b, []byte("${1}0"))
		changed = true
	}
	// Only add QUERY_PORT when missing — remapping busy ports is tip Confirm/provision.
	reQ := regexp.MustCompile(`(?m)^\s*STELLAR_CAPTIVE_CORE_HTTP_QUERY_PORT\s*=\s*\d+\s*$`)
	if !reQ.Match(b) {
		q := stellarDefaultQueryPort()
		b = []byte(strings.TrimRight(string(b), "\n") + "\nSTELLAR_CAPTIVE_CORE_HTTP_QUERY_PORT = " + strconv.Itoa(q) + "\n")
		changed = true
	}
	if !changed {
		return false, nil
	}
	if err := os.WriteFile(path, b, 0o644); err != nil {
		return false, err
	}
	return true, nil
}

func stellarDefaultQueryPort() int {
	env := strings.ToLower(strings.TrimSpace(os.Getenv("TRON_ENV")))
	if env == "" {
		env = strings.ToLower(strings.TrimSpace(os.Getenv("RPCNODE_ENV")))
	}
	switch env {
	case "testnet":
		return 11628
	case "futurenet":
		return 11630
	default:
		return 11626
	}
}
