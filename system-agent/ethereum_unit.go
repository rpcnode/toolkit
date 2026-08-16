package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

func ethereumGethUnit(env string) string {
	return fmt.Sprintf("ethereum-geth-%s", normalizeEnvName(env))
}

func ethereumLighthouseUnit(env string) string {
	return fmt.Sprintf("ethereum-lighthouse-%s", normalizeEnvName(env))
}

func ethereumEnginePort(cfg Config) int {
	if v := envOr("TRON_ENGINE_PORT", ""); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return n
		}
	}
	return ethereumPortFallback(cfg.Env, "engine")
}

func ethereumBeaconPort(cfg Config) int {
	if v := envOr("TRON_BEACON_PORT", ""); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return n
		}
	}
	return ethereumPortFallback(cfg.Env, "beacon")
}

// ethereumPortFallback — canonical ports when toolkit.env vars missing.
// Profile reuse: SolHTTP=Engine, PBFTHTTP=Beacon, Metrics=ConsensusP2P.
func ethereumPortFallback(env, kind string) int {
	switch normalizeEnvName(env) {
	case "sepolia":
		switch kind {
		case "engine":
			return 8552
		case "beacon":
			return 5053
		default:
			return 9100
		}
	case "hoodi":
		switch kind {
		case "engine":
			return 8553
		case "beacon":
			return 5054
		default:
			return 9200
		}
	default:
		switch kind {
		case "engine":
			return 8551
		case "beacon":
			return 5052
		default:
			return 9000
		}
	}
}

func ethereumJWTPath(cfg Config) string {
	if p := strings.TrimSpace(envOr("TRON_JWT", "")); p != "" {
		return p
	}
	etc := strings.TrimSpace(cfg.EtcDir)
	if etc == "" {
		etc = LookupNetworkProfile(cfg.Network, cfg.Env).EtcPath
	}
	if etc == "" {
		etc = fmt.Sprintf("/etc/ethereum/%s", normalizeEnvName(cfg.Env))
	}

	return filepath.Join(etc, "jwt.hex")
}

func gethProcessRunning(cfg Config) (bool, string) {
	data := cfg.DataDir
	if data == "" {
		data = LookupNetworkProfile(cfg.Network, cfg.Env).DataPath
	}
	gethDir := filepath.Join(data, "geth")
	env := normalizeEnvName(cfg.Env)
	out, _ := runCmd(3*time.Second, "bash", "-lc", fmt.Sprintf(
		`pgrep -af '[g]eth' | grep -E '%s|%s|ethereum-geth-%s' | head -1`,
		gethDir, data, env,
	))
	line := strings.TrimSpace(out)
	if line == "" {
		return false, ""
	}

	return true, line
}

func lighthouseProcessRunning(cfg Config) (bool, string) {
	data := cfg.DataDir
	if data == "" {
		data = LookupNetworkProfile(cfg.Network, cfg.Env).DataPath
	}
	lhDir := filepath.Join(data, "lighthouse")
	env := normalizeEnvName(cfg.Env)
	out, _ := runCmd(3*time.Second, "bash", "-lc", fmt.Sprintf(
		`pgrep -af '[l]ighthouse' | grep -E '%s|%s|ethereum-lighthouse-%s' | head -1`,
		lhDir, data, env,
	))
	line := strings.TrimSpace(out)
	if line == "" {
		return false, ""
	}

	return true, line
}

func ethereumStartFailureDetail(cfg Config, gethOK, lhOK bool) (detail string, bad bool) {
	gethUnit := cfg.NodeService
	if gethUnit == "" {
		gethUnit = ethereumGethUnit(cfg.Env)
	}
	lhUnit := envOr("TRON_LIGHTHOUSE_SERVICE", ethereumLighthouseUnit(cfg.Env))

	jwt := ethereumJWTPath(cfg)
	if !fileExists(jwt) {
		return fmt.Sprintf("JWT missing: %s", jwt), true
	}

	for _, u := range []string{gethUnit, lhUnit} {
		probe := probeSystemdUnit(u)
		snip := journalUnitSnippet(u+".service", 16)
		resultBad := probe.Result == "exit-code" || probe.Result == "signal" ||
			probe.Result == "resources" || probe.Result == "timeout"
		procOK := gethOK
		if strings.Contains(u, "lighthouse") {
			procOK = lhOK
		}
		crashLoop := !procOK && resultBad && (probe.NRestarts >= 1 || probe.ActiveState == "activating")
		failed := probe.Failed || probe.ActiveState == "failed"

		switch {
		case failed || crashLoop:
			msg := fmt.Sprintf("%s unit failed (Result=%s, restarts=%d)", u, probe.Result, probe.NRestarts)
			if snip != "" {
				msg += ": " + snip
			}

			return msg, true
		}
	}

	return "", false
}

func ethereumNodeReallyUp(cfg Config, gethState string, gethOK, lhOK bool) bool {
	if gethOK && lhOK {
		return true
	}
	if _, bad := ethereumStartFailureDetail(cfg, gethOK, lhOK); bad {
		return false
	}
	gethActive := gethState == "active" || gethState == "activating"
	lhState := systemctlActive(envOr("TRON_LIGHTHOUSE_SERVICE", ethereumLighthouseUnit(cfg.Env)))
	lhActive := lhState == "active" || lhState == "activating"

	return gethActive && lhActive
}

func ethereumDiskGateOK(cfg Config) (ok bool, freeGiB, needGiB float64, detail string) {
	prof := LookupNetworkProfile(cfg.Network, cfg.Env)
	needGiB = prof.DiskHintGiB
	if needGiB <= 0 {
		needGiB = 400
	}
	floor := needGiB * 0.2
	if floor < 5 {
		floor = 5
	}
	data := cfg.DataDir
	if data == "" {
		data = prof.DataPath
	}
	freeGiB = diskUsageGiB(data)
	if freeGiB >= floor {
		return true, freeGiB, needGiB, fmt.Sprintf("free %.0f GiB ≥ floor %.0f GiB (plan %.0f GiB)", freeGiB, floor, needGiB)
	}

	return false, freeGiB, needGiB, fmt.Sprintf("free %.0f GiB < floor %.0f GiB before EL/CL sync (plan %.0f GiB for %s)", freeGiB, floor, needGiB, cfg.Env)
}

func resolveGethBin(cfg Config) string {
	opt := strings.TrimSpace(cfg.OptDir)
	for _, cand := range []string{
		filepath.Join(opt, "bin", "geth"),
		"/usr/bin/geth",
		"/usr/local/bin/geth",
	} {
		if fileExists(cand) {
			return cand
		}
	}
	if p, err := exec.LookPath("geth"); err == nil && p != "" {
		return p
	}

	return ""
}

func resolveLighthouseBin(cfg Config) string {
	for _, cand := range []string{
		"/usr/local/bin/lighthouse",
		"/usr/bin/lighthouse",
	} {
		if fileExists(cand) {
			return cand
		}
	}
	if p, err := exec.LookPath("lighthouse"); err == nil && p != "" {
		return p
	}

	return ""
}

func lighthouseBinaryVersion(cfg Config) string {
	bin := resolveLighthouseBin(cfg)
	if bin == "" {
		return ""
	}
	out, err := runCmd(4*time.Second, bin, "--version")
	if err != nil {
		return ""
	}
	return strings.TrimSpace(out)
}

func ensureEthereumJWT(cfg Config) error {
	path := ethereumJWTPath(cfg)
	if fileExists(path) {
		return nil
	}
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	out, err := exec.Command("openssl", "rand", "-hex", "32").CombinedOutput()
	if err != nil {
		return fmt.Errorf("openssl rand jwt: %v (%s)", err, strings.TrimSpace(string(out)))
	}
	hex := strings.TrimSpace(string(out))
	if err := os.WriteFile(path, []byte(hex+"\n"), 0o640); err != nil {
		return err
	}
	_ = exec.Command("chown", "root:nodeop", path).Run()

	return nil
}

func ensureEthereumDirs(cfg Config) error {
	data := cfg.DataDir
	etc := cfg.EtcDir
	opt := cfg.OptDir
	for _, d := range []string{
		etc, opt, data,
		filepath.Join(data, "geth"),
		filepath.Join(data, "lighthouse"),
	} {
		if d == "" {
			continue
		}
		if err := os.MkdirAll(d, 0o755); err != nil {
			return err
		}
	}
	_ = exec.Command("chown", "-R", "nodeop:nodeop", data, etc, opt).Run()

	return nil
}

func isEthereumNetwork(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "ethereum")
}
