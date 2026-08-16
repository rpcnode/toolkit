package main

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	l2SyncLogMaxLines = 120
	l2SyncLogMinGap   = 30 * time.Second
)

var (
	nitroTransferRe = regexp.MustCompile(
		`(?i)transferred\s+(\d+)\s*/\s*(\d+)\s*bytes\s*\(([0-9.]+)%\)\s*\[([^,\]]+),\s*([^\]]+?)\s*remaining\]`,
	)
	nitroPctRe = regexp.MustCompile(`(?i)([\d.]+)\s*%`)
	l2SyncLog  = &l2SyncLogState{}
)

type l2SyncLogState struct {
	mu        sync.Mutex
	lastLine  string
	lastWrite time.Time
}

func l2SyncLogPath(cfg Config) string {
	return filepath.Join(filepath.Dir(cfg.StateFile), "sync.log")
}

func l2LogSource(network string) string {
	switch strings.ToLower(strings.TrimSpace(network)) {
	case "arb", "robinhood":
		return "nitro"
	case "hyperliquid":
		return "hl-visor"
	case "optimism":
		return "op-geth"
	case "base":
		return "base-reth-node"
	default:
		return network
	}
}

// l2EVMLogTail returns agent-owned lines for the panel Logs card during
// install/start (nitro pruned download, HL bootstrap, op-geth journal) and run.
func l2EVMLogTail(cfg Config, network string, n int) []string {
	if n <= 0 {
		n = 80
	}
	fileLines := textLogTail(l2SyncLogPath(cfg), n)
	journalLines := l2JournalLogLines(cfg, network, n*2)
	// Journal context first, sampled progress last (UI auto-scrolls to bottom).
	merged := mergeLogTails(journalLines, fileLines, n)
	if len(merged) > 0 {
		return promoteLatestDownloadLine(merged)
	}

	return promoteLatestDownloadLine(journalLines)
}

// promoteLatestDownloadLine moves the newest init-download sample to the end.
func promoteLatestDownloadLine(lines []string) []string {
	if len(lines) == 0 {
		return lines
	}
	idx := -1
	for i, ln := range lines {
		if strings.Contains(strings.ToLower(ln), "init download") {
			idx = i
		}
	}
	if idx < 0 || idx == len(lines)-1 {
		return lines
	}
	out := make([]string, 0, len(lines))
	out = append(out, lines[:idx]...)
	out = append(out, lines[idx+1:]...)
	out = append(out, lines[idx])

	return out
}

func l2JournalLogLines(cfg Config, network string, n int) []string {
	unit := strings.TrimSpace(cfg.NodeService)
	if unit == "" {
		unit = fmt.Sprintf("%s-%s", strings.ToLower(network), normalizeEnvName(cfg.Env))
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	if n <= 0 {
		n = 80
	}
	// Large nitro \r progress blobs — pull enough MESSAGE bytes, then normalize.
	out, _ := runCmd(5*time.Second, "journalctl", "-u", unit, "-n", "40",
		"--no-pager", "-o", "cat")
	raw := expandCarriageProgress(out)
	filtered := filterL2JournalLines(raw, network, n)
	if len(filtered) > 0 {
		return filtered
	}
	// Fallback: last non-empty journal lines (trimmed).
	out2 := make([]string, 0, n)
	for _, ln := range raw {
		ln = strings.TrimSpace(ln)
		if ln == "" {
			continue
		}
		if len(ln) > 400 {
			ln = ln[:400] + "…"
		}
		out2 = append(out2, ln)
	}
	if len(out2) > n {
		out2 = out2[len(out2)-n:]
	}

	return out2
}

// expandCarriageProgress splits journal MESSAGE blobs that use \r progress.
func expandCarriageProgress(raw string) []string {
	parts := strings.Split(raw, "\n")
	out := make([]string, 0, len(parts)+8)
	for _, part := range parts {
		part = strings.ReplaceAll(part, "\x1b[2K", "")
		if strings.Contains(part, "\r") {
			chunks := strings.Split(part, "\r")
			// Keep leading INFO prefix (before first progress) + last progress.
			prefix := strings.TrimSpace(chunks[0])
			if prefix != "" && !nitroTransferRe.MatchString(prefix) {
				out = append(out, prefix)
			}
			last := ""
			for i := len(chunks) - 1; i >= 0; i-- {
				c := strings.TrimSpace(chunks[i])
				if c == "" {
					continue
				}
				last = c
				break
			}
			if last != "" {
				out = append(out, last)
			}
			continue
		}
		ln := strings.TrimSpace(part)
		if ln != "" {
			out = append(out, ln)
		}
	}

	return out
}

func filterL2JournalLines(raw []string, network string, n int) []string {
	if len(raw) == 0 || n <= 0 {
		return nil
	}
	interesting := make([]string, 0, n)
	for _, ln := range raw {
		if l2InterestingLogLine(ln, network) {
			interesting = append(interesting, summarizeL2LogLine(ln))
		}
	}
	src := interesting
	if len(src) == 0 {
		return nil
	}
	if len(src) > n {
		src = src[len(src)-n:]
	}
	out := make([]string, len(src))
	copy(out, src)

	return out
}

func l2InterestingLogLine(ln, network string) bool {
	l := strings.ToLower(ln)
	if nitroTransferRe.MatchString(ln) {
		return true
	}
	keys := []string{
		"downloading initial database",
		"downloading database part",
		"set latest snapshot url",
		"file not found; trying to download",
		"init.latest",
		"snapshot",
		"bootstrapping",
		"bootstrap",
		"error",
		"failed",
		"warn",
		"abort",
		"no space",
		"unexpected files",
		"connected to l1",
		"running arbitrum nitro",
		"hl-visor",
		"hl-node",
		"op-geth",
		"op-node",
		"syncing",
		"imported new",
		"looking for peers",
	}
	net := strings.ToLower(network)
	if net == "hyperliquid" {
		keys = append(keys, "visor", "non-validator", "serve-eth-rpc", "gossip",
			"applied block", "finished bootstrap")
	}
	if net == "base" {
		keys = append(keys, "received headers", "received new payload", `"stage"`,
			"checkpoint", "base-reth-node", "pipeline")
	}
	for _, k := range keys {
		if strings.Contains(l, k) {
			return true
		}
	}
	if strings.Contains(ln, " ERROR") || strings.Contains(ln, " WARN") ||
		strings.HasPrefix(l, "error") {
		return true
	}

	return false
}

func summarizeL2LogLine(ln string) string {
	if prog := formatNitroTransferProgress(ln); prog != "" {
		return prog
	}
	ln = strings.TrimSpace(ln)
	if len(ln) > 360 {
		return ln[:360] + "…"
	}

	return ln
}

// nitroDownloadPct extracts percent from a formatted init-download detail, or -1.
func nitroDownloadPct(detail string) float64 {
	m := nitroPctRe.FindStringSubmatch(detail)
	if len(m) != 2 || !strings.Contains(strings.ToLower(detail), "download") {
		return -1
	}
	pct, err := strconv.ParseFloat(m[1], 64)
	if err != nil {
		return -1
	}

	return pct
}

func formatNitroTransferProgress(line string) string {
	m := nitroTransferRe.FindStringSubmatch(line)
	if len(m) != 6 {
		return ""
	}
	done, _ := strconv.ParseFloat(m[1], 64)
	total, _ := strconv.ParseFloat(m[2], 64)
	pct, _ := strconv.ParseFloat(m[3], 64)
	rate := strings.TrimSpace(m[4])
	eta := strings.TrimSpace(m[5])
	doneGiB := done / (1024 * 1024 * 1024)
	totalGiB := total / (1024 * 1024 * 1024)
	ts := time.Now().UTC().Format("15:04:05Z")

	return fmt.Sprintf("%s  init download  %.2f%%  ·  %.1f/%.0f GiB  ·  %s  ·  ETA %s",
		ts, pct, doneGiB, totalGiB, rate, eta)
}

// l2WarmupDetailFromJournal prefers nitro/HL download progress over generic warming copy.
func l2WarmupDetailFromJournal(cfg Config, network, fallback string) string {
	rawOut, _ := runCmd(4*time.Second, "journalctl", "-u", l2NodeUnit(cfg, network),
		"-n", "20", "--no-pager", "-o", "cat")
	raw := expandCarriageProgress(rawOut)
	for i := len(raw) - 1; i >= 0; i-- {
		if prog := formatNitroTransferProgress(raw[i]); prog != "" {
			// Drop timestamp prefix for lifecycle detail.
			if idx := strings.Index(prog, "  init download"); idx >= 0 {
				return strings.TrimSpace(prog[idx+2:])
			}

			return prog
		}
		l := strings.ToLower(raw[i])
		if strings.Contains(l, "downloading database part") ||
			strings.Contains(l, "downloading initial database") ||
			strings.Contains(l, "set latest snapshot url") {
			return summarizeL2LogLine(raw[i])
		}
	}
	fb := strings.TrimSpace(fallback)
	if fb != "" {
		return fb
	}

	return network + " warming up · waiting for JSON-RPC"
}

func l2NodeUnit(cfg Config, network string) string {
	unit := strings.TrimSpace(cfg.NodeService)
	if unit == "" {
		unit = fmt.Sprintf("%s-%s", strings.ToLower(network), normalizeEnvName(cfg.Env))
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}

	return unit
}

// maybeAppendL2ProgressLog samples journal progress into sync.log (rate-limited).
func maybeAppendL2ProgressLog(cfg Config, network string, nodeActive bool) {
	if !nodeActive {
		return
	}
	detail := l2WarmupDetailFromJournal(cfg, network, "")
	if detail == "" || strings.Contains(strings.ToLower(detail), "warming up") {
		return
	}
	if !strings.Contains(strings.ToLower(detail), "download") &&
		!strings.Contains(strings.ToLower(detail), "snapshot") &&
		!strings.Contains(strings.ToLower(detail), "bootstrap") {
		return
	}
	now := time.Now()
	line := detail
	if !strings.Contains(line, "Z  ") {
		line = now.UTC().Format("15:04:05Z") + "  " + detail
	}

	l2SyncLog.mu.Lock()
	defer l2SyncLog.mu.Unlock()

	same := line == l2SyncLog.lastLine || syncLogSameProgress(l2SyncLog.lastLine, line)
	gapOK := l2SyncLog.lastWrite.IsZero() || now.Sub(l2SyncLog.lastWrite) >= l2SyncLogMinGap
	if same && !gapOK {
		return
	}

	path := l2SyncLogPath(cfg)
	_ = os.MkdirAll(filepath.Dir(path), 0o755)
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	_, _ = f.WriteString(line + "\n")
	_ = f.Close()

	l2SyncLog.lastLine = line
	l2SyncLog.lastWrite = now
	trimBitcoinSyncLogFile(path, l2SyncLogMaxLines)
}

// maybeAppendL2SyncRPCLog samples eth_syncing progress once RPC is up.
func maybeAppendL2SyncRPCLog(cfg Config, _ string, rpc ethereumRPCResult, syncing bool) {
	if !rpc.OK {
		return
	}
	now := time.Now()
	ts := now.UTC().Format("15:04:05Z")
	var line string
	if syncing {
		line = fmt.Sprintf("%s  syncing  block %d", ts, rpc.Block)
		if rpc.Peers >= 0 {
			line += fmt.Sprintf("  ·  peers %d", rpc.Peers)
		}
		if rpc.SyncDetail != "" {
			line += "  ·  " + rpc.SyncDetail
		}
	} else {
		line = fmt.Sprintf("%s  synced  height %d", ts, rpc.Block)
		if rpc.Peers >= 0 {
			line += fmt.Sprintf("  ·  peers %d", rpc.Peers)
		}
		if rpc.ChainID != "" {
			line += "  ·  chain " + rpc.ChainID
		}
	}

	l2SyncLog.mu.Lock()
	defer l2SyncLog.mu.Unlock()

	same := line == l2SyncLog.lastLine || syncLogSameProgress(l2SyncLog.lastLine, line)
	gapOK := l2SyncLog.lastWrite.IsZero() || now.Sub(l2SyncLog.lastWrite) >= l2SyncLogMinGap
	if !syncing {
		alreadySynced := l2SyncLog.lastLine != "" && strings.Contains(l2SyncLog.lastLine, " synced ")
		if alreadySynced {
			return
		}
		if l2SyncLog.lastLine != "" && !gapOK {
			return
		}
	} else if same && !gapOK {
		return
	}

	path := l2SyncLogPath(cfg)
	_ = os.MkdirAll(filepath.Dir(path), 0o755)
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	_, _ = f.WriteString(line + "\n")
	_ = f.Close()

	l2SyncLog.lastLine = line
	l2SyncLog.lastWrite = now
	trimBitcoinSyncLogFile(path, l2SyncLogMaxLines)
}

func mergeLogTails(a, b []string, n int) []string {
	if len(a) == 0 {
		if len(b) > n {
			return b[len(b)-n:]
		}
		return b
	}
	if len(b) == 0 {
		if len(a) > n {
			return a[len(a)-n:]
		}
		return a
	}
	seen := map[string]struct{}{}
	out := make([]string, 0, n)
	for _, ln := range a {
		ln = strings.TrimSpace(ln)
		if ln == "" {
			continue
		}
		key := syncLogProgressKey(ln)
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, ln)
	}
	for _, ln := range b {
		ln = strings.TrimSpace(ln)
		if ln == "" {
			continue
		}
		key := syncLogProgressKey(ln)
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, ln)
	}
	if len(out) > n {
		out = out[len(out)-n:]
	}

	return out
}

func syncLogProgressKey(ln string) string {
	// Collapse timestamped duplicate progress samples for merge dedupe.
	if i := strings.Index(ln, "  init download"); i >= 0 {
		return strings.TrimSpace(ln[i+2:])
	}
	if i := strings.Index(ln, "  IBD  "); i >= 0 {
		return strings.TrimSpace(ln[i+2:])
	}
	if i := strings.Index(ln, "  syncing  "); i >= 0 {
		return strings.TrimSpace(ln[i+2:])
	}

	return ln
}
