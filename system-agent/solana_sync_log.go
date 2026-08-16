package main

import (
	"bytes"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

const solanaLogTailBytes = 512 * 1024

var solanaDownloadRe = regexp.MustCompile(`(?i)downloaded\s+(\d+)\s+bytes\s+([0-9.]+)%\s+([0-9.]+)\s+bytes/s`)

func solanaValidatorLogPath(cfg Config) string {
	env := normalizeEnvName(cfg.Env)
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		data = filepath.Join("/data/solana", env)
	}

	return filepath.Join(data, "solana-"+env+".log")
}

// solanaLogTail returns recent agent-owned lines for the panel Logs card.
// Prefers snapshot-download / bootstrap / warn-error lines from the Agave file log;
// falls back to raw tail; pads with unit journal when the file is empty.
func solanaLogTail(cfg Config, n int) []string {
	if n <= 0 {
		n = 60
	}
	path := solanaValidatorLogPath(cfg)
	raw := solanaRawLogTail(path, solanaLogTailBytes)
	lines := filterSolanaLogLines(raw, n)
	if len(lines) > 0 {
		return lines
	}
	unit := strings.TrimSpace(cfg.NodeService)
	if unit == "" {
		unit = "solana-" + normalizeEnvName(cfg.Env)
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	snip := journalUnitSnippet(unit, n)
	if snip == "" {
		return nil
	}
	out := make([]string, 0, n)
	for _, ln := range strings.Split(snip, "\n") {
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

func solanaRawLogTail(path string, maxTail int) []string {
	if maxTail <= 0 {
		maxTail = solanaLogTailBytes
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
	size := st.Size()
	start := int64(0)
	if size > int64(maxTail) {
		start = size - int64(maxTail)
	}
	if _, err := f.Seek(start, io.SeekStart); err != nil {
		return nil
	}
	b, err := io.ReadAll(io.LimitReader(f, int64(maxTail)+1))
	if err != nil {
		return nil
	}
	if start > 0 {
		if i := bytes.IndexByte(b, '\n'); i >= 0 && i+1 < len(b) {
			b = b[i+1:]
		}
	}
	parts := strings.Split(string(b), "\n")
	out := make([]string, 0, len(parts))
	for _, ln := range parts {
		ln = strings.TrimSpace(ln)
		if ln != "" {
			out = append(out, ln)
		}
	}

	return out
}

func solanaInterestingLogLine(ln string) bool {
	l := strings.ToLower(ln)
	if solanaDownloadRe.MatchString(ln) {
		return true
	}
	keys := []string{
		"downloading snapshot",
		"loading snapshot",
		"snapshot download",
		"finished downloading",
		"reconstructing accounts",
		"starting validator",
		"bootstrapping",
		"waiting for",
		"error",
		" warn ",
		"failed",
		"abort",
		"capability",
		"operation not permitted",
		"xdp",
		"no space",
		"rpc service",
		"gethealth",
	}
	for _, k := range keys {
		if strings.Contains(l, k) {
			return true
		}
	}
	// rust log levels without surrounding spaces
	if strings.Contains(ln, " WARN ") || strings.Contains(ln, " ERROR ") ||
		strings.Contains(ln, "WARN ") || strings.Contains(ln, "ERROR ") {
		return true
	}

	return false
}

func filterSolanaLogLines(raw []string, n int) []string {
	if len(raw) == 0 || n <= 0 {
		return nil
	}
	interesting := make([]string, 0, n)
	for _, ln := range raw {
		if solanaInterestingLogLine(ln) {
			interesting = append(interesting, ln)
		}
	}
	src := interesting
	if len(src) == 0 {
		src = raw
	}
	// Keep download progress dense but cap other noise: take last n.
	if len(src) > n {
		src = src[len(src)-n:]
	}
	out := make([]string, len(src))
	copy(out, src)

	return out
}

// solanaWarmupDetail builds lifecycle start detail while Agave has no RPC yet.
func solanaWarmupDetail(cfg Config, fallback string) string {
	path := solanaValidatorLogPath(cfg)
	raw := solanaRawLogTail(path, 256*1024)
	for i := len(raw) - 1; i >= 0; i-- {
		if d := formatSolanaDownloadProgress(raw[i]); d != "" {
			return d
		}
	}
	fb := strings.TrimSpace(fallback)
	if fb != "" {
		return fb
	}

	return "agave-validator warming up · waiting for RPC"
}

func formatSolanaDownloadProgress(line string) string {
	m := solanaDownloadRe.FindStringSubmatch(line)
	if len(m) != 4 {
		return ""
	}
	done, _ := strconv.ParseFloat(m[1], 64)
	pct, _ := strconv.ParseFloat(m[2], 64)
	rate, _ := strconv.ParseFloat(m[3], 64)
	if pct <= 0 || done <= 0 {
		return fmt.Sprintf("snapshot download %.1f%%", pct)
	}
	total := done / (pct / 100.0)
	doneGiB := done / (1024 * 1024 * 1024)
	totalGiB := total / (1024 * 1024 * 1024)
	rateMBs := rate / (1024 * 1024)
	eta := ""
	remain := total - done
	if rate > 0 && remain > 0 {
		sec := remain / rate
		if sec >= 3600 {
			eta = fmt.Sprintf(" · ETA ~%.1fh", sec/3600)
		} else if sec >= 60 {
			eta = fmt.Sprintf(" · ETA ~%.0fm", sec/60)
		} else {
			eta = fmt.Sprintf(" · ETA ~%.0fs", sec)
		}
	}

	return fmt.Sprintf("snapshot download %.1f%% · %.1f/%.0f GiB · %.0f MB/s%s",
		pct, doneGiB, totalGiB, rateMBs, eta)
}
