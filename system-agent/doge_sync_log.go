package main

import (
	"fmt"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// dogeLogTail — agent-owned panel lines during doge IBD.
// dogecoind writes to datadir/debug.log (not journal); also sample sync.log like bitcoin.
func dogeLogTail(cfg Config, n int) []string {
	if n <= 0 {
		n = 80
	}
	samples := textLogTail(bitcoinSyncLogPath(cfg), n)
	debugLines := dogeDebugLogTail(cfg, n)
	journalLines := dogeJournalLogLines(cfg, n)
	// Journal/start noise first, debug mid, sync samples last (UI scrolls to bottom).
	merged := mergeLogTails(journalLines, debugLines, n)
	merged = mergeLogTails(merged, samples, n)
	if len(merged) > 0 {
		return merged
	}
	if len(samples) > 0 {
		return samples
	}
	if len(debugLines) > 0 {
		return debugLines
	}

	return journalLines
}

func dogeJournalLogLines(cfg Config, n int) []string {
	unit := strings.TrimSpace(cfg.NodeService)
	if unit == "" {
		unit = fmt.Sprintf("doge-%s.service", normalizeEnvName(cfg.Env))
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

func dogeDebugLogPath(cfg Config) string {
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		data = LookupNetworkProfile(cfg.Network, cfg.Env).DataPath
	}
	if data == "" {
		return ""
	}

	return filepath.Join(data, "debug.log")
}

// dogeDebugLogTail reads dogecoind debug.log (large file — tail via shell).
func dogeDebugLogTail(cfg Config, n int) []string {
	path := dogeDebugLogPath(cfg)
	if path == "" || !fileExists(path) {
		return nil
	}
	if n <= 0 {
		n = 80
	}
	// Pull extra raw lines then keep interesting IBD/progress noise.
	out, err := runCmd(4*time.Second, "tail", "-n", strconv.Itoa(n*4), path)
	if err != nil || strings.TrimSpace(out) == "" {
		return nil
	}
	raw := strings.Split(out, "\n")
	interesting := make([]string, 0, n)
	fallback := make([]string, 0, n)
	for _, ln := range raw {
		ln = strings.TrimSpace(ln)
		if ln == "" {
			continue
		}
		if len(ln) > 400 {
			ln = ln[:400] + "…"
		}
		fallback = append(fallback, ln)
		low := strings.ToLower(ln)
		if strings.Contains(low, "updatetip") ||
			strings.Contains(low, "progress=") ||
			strings.Contains(low, "leaving initialblockdownload") ||
			strings.Contains(low, "update tip") ||
			strings.Contains(low, "error") ||
			strings.Contains(low, "warning") ||
			strings.Contains(low, "loaded block") ||
			strings.Contains(low, "synchronizing") ||
			strings.Contains(low, "connectblock") {
			interesting = append(interesting, ln)
		}
	}
	pick := interesting
	if len(pick) == 0 {
		pick = fallback
	}
	if len(pick) > n {
		pick = pick[len(pick)-n:]
	}

	return pick
}
