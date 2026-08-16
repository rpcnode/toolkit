package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

func isSolanaLocalnet(env string) bool {
	return strings.EqualFold(strings.TrimSpace(env), "localnet")
}

func resolveAgaveBin(cfg Config) string {
	localnet := isSolanaLocalnet(cfg.Env)
	name := "agave-validator"
	if localnet {
		name = "solana-test-validator"
	}
	opt := cfg.OptDir
	cands := []string{
		filepath.Join(opt, "bin", name),
		"/home/nodeop/.local/share/solana/install/active_release/bin/" + name,
		"/home/nodeop/agave/bin/" + name,
		"/opt/solana/bin/" + name,
		"/usr/local/bin/" + name,
	}
	for _, c := range cands {
		if fileExists(c) {
			return c
		}
	}
	if p, err := exec.LookPath(name); err == nil {
		return p
	}

	return ""
}

func solanaRunScriptPath(cfg Config) string {
	opt := cfg.OptDir
	if opt == "" {
		opt = fmt.Sprintf("/opt/solana/%s", normalizeEnvName(cfg.Env))
	}

	return filepath.Join(opt, "run-validator.sh")
}

func solanaProcessRunning(cfg Config) (bool, string) {
	data := cfg.DataDir
	etc := cfg.EtcDir
	opt := cfg.OptDir
	env := normalizeEnvName(cfg.Env)
	out, _ := runCmd(3*time.Second, "bash", "-lc", fmt.Sprintf(
		`pgrep -af 'agave-validator|solana-test-validator' | grep -E '%s|%s|%s|solana-%s' | head -1`,
		data, etc, opt, env,
	))
	line := strings.TrimSpace(out)
	if line == "" {
		return false, ""
	}

	return true, line
}

func solanaStartFailureDetail(cfg Config, procOK bool) (string, bool) {
	probe := probeSystemdUnit(cfg.NodeService)
	if procOK && probe.ActiveState == "active" {
		return "", false
	}
	if probe.Failed || probe.ActiveState == "failed" || probe.NRestarts >= 3 {
		snip := journalUnitSnippet(cfg.NodeService+".service", 16)
		msg := fmt.Sprintf("solana unit %s state=%s result=%s restarts=%d",
			cfg.NodeService, probe.ActiveState, probe.Result, probe.NRestarts)
		if snip != "" {
			msg += " — " + snip
		}

		return msg, true
	}
	if !procOK && (probe.ActiveState == "activating" || probe.ActiveState == "active") {
		// Still warming — not an error yet.
		return "", false
	}
	if !fileExists(solanaRunScriptPath(cfg)) {
		return "run-validator.sh missing — re-provision solana/" + cfg.Env, true
	}
	if resolveAgaveBin(cfg) == "" {
		return "agave-validator / solana-test-validator binary missing", true
	}

	return "", false
}

func solanaDiskGateOK(cfg Config) (ok bool, freeGiB, needGiB float64, detail string) {
	prof := LookupNetworkProfile(cfg.Network, cfg.Env)
	needGiB = prof.DiskHintGiB
	if needGiB <= 0 {
		needGiB = 64
	}
	// Soft gate: require ~20% of hint free (same spirit as bitcoin).
	needFree := needGiB * 0.2
	if needFree < 4 {
		needFree = 4
	}
	data := cfg.DataDir
	if data == "" {
		data = prof.DataPath
	}
	freeGiB = diskUsageGiB(data)
	if freeGiB <= 0 {
		return true, freeGiB, needGiB, "disk free unknown — allowing start"
	}
	if freeGiB < needFree {
		return false, freeGiB, needGiB, fmt.Sprintf("insufficient free disk on %s: %.1f GiB < %.1f GiB (hint %.0f GiB)",
			data, freeGiB, needFree, needGiB)
	}

	return true, freeGiB, needGiB, fmt.Sprintf("%.1f GiB free (need ≥%.1f for auto-start)", freeGiB, needFree)
}

func ensureSolanaDirs(cfg Config) error {
	data := cfg.DataDir
	etc := cfg.EtcDir
	opt := cfg.OptDir
	for _, d := range []string{
		etc, opt, data,
		filepath.Join(data, "ledger"),
		filepath.Join(data, "accounts"),
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
