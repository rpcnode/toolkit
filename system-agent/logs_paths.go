package main

import (
	"bytes"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// resolveNodeLogPaths — host paths / journalctl targets for the Logs modal.
// Primary path is first (shown as logs.path).
func resolveNodeLogPaths(cfg Config) []string {
	net := strings.ToLower(strings.TrimSpace(cfg.Network))
	env := normalizeEnvName(cfg.Env)
	var out []string

	unit := strings.TrimSpace(cfg.NodeService)
	if unit != "" {
		if !strings.HasSuffix(unit, ".service") {
			unit += ".service"
		}
	}

	syncLog := ""
	if dir := filepath.Dir(strings.TrimSpace(cfg.StateFile)); dir != "" && dir != "." {
		syncLog = filepath.Join(dir, "sync.log")
	}

	switch net {
	case "ton":
		// ton-<env>.service is a oneshot wrapper — empty journal. Prefer real tails.
		out = append(out,
			filepath.Join("/var/log/ton", env, "bootstrap.log"),
			filepath.Join(cfg.EtcDir, "sync-progress.log"),
			"journalctl -u validator.service",
			"journalctl -u ton_http_api.service",
		)
	case "solana":
		if unit != "" {
			out = append(out, "journalctl -u "+unit)
		}
		out = append(out, solanaValidatorLogPath(cfg))
		if syncLog != "" {
			out = append(out, syncLog)
		}
	case "bitcoin", "doge", "ltc", "dash", "bch", "zcash":
		if unit != "" {
			out = append(out, "journalctl -u "+unit)
		}
		if syncLog != "" {
			out = append(out, syncLog)
		}
		if p := coreLikeDebugLogPath(cfg); p != "" {
			out = append(out, p)
		}
	case "tron":
		opt := strings.TrimSpace(cfg.OptDir)
		if opt == "" {
			opt = filepath.Join("/opt/tron", env)
		}
		out = append(out, filepath.Join(opt, "logs", "tron.log"))
		if unit != "" {
			out = append(out, "journalctl -u "+unit)
		}
		if cfg.SnapshotLog != "" {
			out = append(out, cfg.SnapshotLog)
		}
		if syncLog != "" {
			out = append(out, syncLog)
		}
	case "sui":
		if unit != "" {
			out = append(out, "journalctl -u "+unit)
		}
		if syncLog != "" {
			out = append(out, syncLog)
		}
		if cfg.EtcDir != "" {
			out = append(out, filepath.Join(cfg.EtcDir, "sync-progress.log"))
		}
	case "aptos":
		if unit != "" {
			out = append(out, "journalctl -u "+unit)
		}
		if syncLog != "" {
			out = append(out, syncLog)
		}
		if cfg.EtcDir != "" {
			out = append(out, filepath.Join(cfg.EtcDir, "sync-progress.log"))
		}
	case "avalanche":
		if unit != "" {
			out = append(out, "journalctl -u "+unit)
		}
		if syncLog != "" {
			out = append(out, syncLog)
		}
		env := normalizeAvalancheEnvName(cfg.Env)
		out = append(out, filepath.Join("/var/log/avalanche", env))
	default:
		if unit != "" {
			out = append(out, "journalctl -u "+unit)
		}
		if syncLog != "" {
			out = append(out, syncLog)
		}
		if cfg.SnapshotLog != "" {
			out = append(out, cfg.SnapshotLog)
		}
	}

	return uniqNonEmptyStrings(out)
}

// attachLogPaths fills logs.path / logs.paths on a collect payload (all networks).
func attachLogPaths(cfg Config, st map[string]any) map[string]any {
	if st == nil {
		return st
	}
	paths := resolveNodeLogPaths(cfg)
	if len(paths) == 0 {
		return st
	}
	logs, _ := st["logs"].(map[string]any)
	if logs == nil {
		logs = map[string]any{}
	}
	if _, ok := logs["path"]; !ok {
		logs["path"] = paths[0]
	}
	existing, _ := logs["paths"].([]string)
	if len(existing) == 0 {
		// JSON round-trip may store []any — only set when absent.
		if _, ok := logs["paths"]; !ok {
			logs["paths"] = paths
		}
	}
	st["logs"] = logs
	return st
}

// fileLogTail — last n non-empty lines, last 256 KiB only (tron.log can be huge).
func fileLogTail(path string, n int) []string {
	const maxTail = 256 * 1024
	if n <= 0 {
		return nil
	}
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return nil
	}
	start := int64(0)
	if st.Size() > maxTail {
		start = st.Size() - maxTail
	}
	if _, err := f.Seek(start, 0); err != nil {
		return nil
	}
	b, err := io.ReadAll(io.LimitReader(f, maxTail+1))
	if err != nil {
		return nil
	}
	if start > 0 {
		if i := bytes.IndexByte(b, '\n'); i >= 0 && i+1 < len(b) {
			b = b[i+1:]
		}
	}
	raw := strings.Split(string(b), "\n")
	out := make([]string, 0, n)
	for _, ln := range raw {
		ln = strings.TrimSpace(ln)
		if ln == "" {
			continue
		}
		out = append(out, ln)
	}
	if len(out) > n {
		out = out[len(out)-n:]
	}
	return out
}

func uniqNonEmptyStrings(in []string) []string {
	seen := map[string]bool{}
	var out []string
	for _, s := range in {
		s = strings.TrimSpace(s)
		if s == "" || seen[s] {
			continue
		}
		seen[s] = true
		out = append(out, s)
	}
	return out
}
