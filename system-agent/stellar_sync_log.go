package main

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

const (
	stellarSyncLogMaxLines = 120
	stellarSyncLogMinGap   = 20 * time.Second
)

var stellarSyncLog = &stellarSyncLogState{}

type stellarSyncLogState struct {
	mu        sync.Mutex
	lastLine  string
	lastWrite time.Time
}

var (
	reStellarLedgerApplied = regexp.MustCompile(`(?i)(?:applied|closed|ledger)\D+(\d{4,})`)
	reStellarCatchup       = regexp.MustCompile(`(?i)catch(?:ing)?\s*up|history|download|ingest|bootstrap`)
)

func stellarSyncLogPath(cfg Config) string {
	return filepath.Join(filepath.Dir(cfg.StateFile), "sync.log")
}

// stellarLogTail — panel Sync progress / Logs (sync samples + journalctl).
func stellarLogTail(cfg Config, n int) []string {
	if n <= 0 {
		n = 80
	}
	fileLines := textLogTail(stellarSyncLogPath(cfg), n)
	journalLines := stellarJournalLogLines(cfg, n)
	merged := mergeLogTails(journalLines, fileLines, n)
	if len(merged) > 0 {
		return merged
	}
	return journalLines
}

func stellarJournalLogLines(cfg Config, n int) []string {
	if n <= 0 {
		n = 80
	}
	unit := cfg.NodeService
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	out2, _ := runCmd(5*time.Second, "journalctl", "-u", unit, "-n", fmt.Sprintf("%d", n*2),
		"--no-pager", "-o", "cat")
	lines := strings.Split(out2, "\n")
	filtered := make([]string, 0, n)
	for _, ln := range lines {
		ln = strings.TrimSpace(ln)
		if ln == "" {
			continue
		}
		if len(ln) > 400 {
			ln = ln[:400] + "…"
		}
		filtered = append(filtered, ln)
	}
	if len(filtered) > n {
		filtered = filtered[len(filtered)-n:]
	}
	return filtered
}

// maybeAppendStellarProgressLog — rate-limited Sync progress samples for panel.
// Never dumps raw JSON-RPC error maps into the progress log.
func maybeAppendStellarProgressLog(cfg Config, syncing bool, info stellarRPCInfo) {
	stellarSyncLog.mu.Lock()
	defer stellarSyncLog.mu.Unlock()
	if time.Since(stellarSyncLog.lastWrite) < stellarSyncLogMinGap {
		return
	}

	ts := time.Now().UTC().Format(time.RFC3339)
	line := ""
	switch {
	case info.OK && syncing:
		line = fmt.Sprintf("%s catch-up ledger=%d tip=%d pct=%.1f%%",
			ts, info.LatestLedger, info.TipLedger, info.VerifyPct*100)
		if info.ClientVersion != "" {
			line += " version=" + info.ClientVersion
		}
	case info.OK:
		line = fmt.Sprintf("%s synced ledger=%d", ts, info.LatestLedger)
		if info.ClientVersion != "" {
			line += " version=" + info.ClientVersion
		}
	default:
		// Captive Core still warming — sample docker progress, not RPC error spam.
		sample := stellarCatchupSample(cfg)
		if sample == "" {
			return
		}
		line = fmt.Sprintf("%s catching-up · %s", ts, sample)
	}
	if line == "" || line == stellarSyncLog.lastLine {
		return
	}

	path := stellarSyncLogPath(cfg)
	_ = os.MkdirAll(filepath.Dir(path), 0o755)
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	defer f.Close()
	if _, err := f.WriteString(line + "\n"); err != nil {
		return
	}
	stellarSyncLog.lastLine = line
	stellarSyncLog.lastWrite = time.Now()
	trimBitcoinSyncLogFile(path, stellarSyncLogMaxLines)
}

func stellarCatchupSample(cfg Config) string {
	lines := stellarJournalLogLines(cfg, 40)
	var best string
	for i := len(lines) - 1; i >= 0; i-- {
		ln := lines[i]
		low := strings.ToLower(ln)
		if reStellarLedgerApplied.MatchString(ln) || reStellarCatchup.MatchString(low) {
			best = ln
			break
		}
		if best == "" && (strings.Contains(low, "stellar") || strings.Contains(low, "core") ||
			strings.Contains(low, "rpc") || strings.Contains(low, "listening")) {
			best = ln
		}
	}
	return best
}
