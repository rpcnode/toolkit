package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	xrplSyncLogMaxLines = 120
	xrplSyncLogMinGap   = 30 * time.Second
)

var xrplSyncLog = &xrplSyncLogState{}

type xrplSyncLogState struct {
	mu        sync.Mutex
	lastLine  string
	lastWrite time.Time
}

func xrplSyncLogPath(cfg Config) string {
	return filepath.Join(filepath.Dir(cfg.StateFile), "sync.log")
}

// xrplLogTail — panel Logs during install/start/run (unit journal + sync samples).
func xrplLogTail(cfg Config, n int) []string {
	if n <= 0 {
		n = 80
	}
	fileLines := textLogTail(xrplSyncLogPath(cfg), n)
	journalLines := xrplJournalLogLines(cfg, n*2)
	merged := mergeLogTails(journalLines, fileLines, n)
	if len(merged) > 0 {
		return merged
	}
	return journalLines
}

func xrplJournalLogLines(cfg Config, n int) []string {
	unit := strings.TrimSpace(cfg.NodeService)
	if unit == "" {
		unit = fmt.Sprintf("xrpl-%s", normalizeEnvName(cfg.Env))
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	if n <= 0 {
		n = 80
	}
	out, _ := runCmd(5*time.Second, "journalctl", "-u", unit, "-n", "60",
		"--no-pager", "-o", "cat")
	lines := strings.Split(out, "\n")
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

func maybeAppendXRPLProgressLog(cfg Config, syncing bool) {
	xrplSyncLog.mu.Lock()
	defer xrplSyncLog.mu.Unlock()
	if time.Since(xrplSyncLog.lastWrite) < xrplSyncLogMinGap {
		return
	}
	info := probeXRPLServerInfo(cfg)
	line := ""
	key := ""
	switch {
	case info.OK && syncing:
		key = fmt.Sprintf("sync %s %d %d %s", info.State, info.Seq, info.Peers, info.Complete)
		line = fmt.Sprintf("%s xrpl sync state=%s seq=%d peers=%d complete=%s",
			time.Now().UTC().Format(time.RFC3339), info.State, info.Seq, info.Peers, info.Complete)
	case info.OK:
		key = fmt.Sprintf("synced %s %d %d", info.State, info.Seq, info.Peers)
		line = fmt.Sprintf("%s xrpl synced state=%s seq=%d peers=%d",
			time.Now().UTC().Format(time.RFC3339), info.State, info.Seq, info.Peers)
	default:
		// Still warming — journal already covers install/start; sample a short note.
		j := xrplJournalLogLines(cfg, 3)
		if len(j) == 0 {
			return
		}
		key = j[len(j)-1]
		line = fmt.Sprintf("%s xrpld warming · %s", time.Now().UTC().Format(time.RFC3339), key)
	}
	if line == "" || key == "" || key == xrplSyncLog.lastLine {
		return
	}
	path := xrplSyncLogPath(cfg)
	_ = os.MkdirAll(filepath.Dir(path), 0o755)
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	_, _ = f.WriteString(line + "\n")
	_ = f.Close()
	xrplSyncLog.lastLine = key
	xrplSyncLog.lastWrite = time.Now()
	trimBitcoinSyncLogFile(path, xrplSyncLogMaxLines)
}
