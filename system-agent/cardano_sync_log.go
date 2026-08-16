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
	cardanoSyncLogMaxLines = 120
	cardanoSyncLogMinGap   = 30 * time.Second
)

type cardanoSyncLogState struct {
	mu       sync.Mutex
	lastLine string
	lastSlot int64
	lastWrite time.Time
}

var cardanoSyncLog = &cardanoSyncLogState{}

func cardanoSyncLogPath(cfg Config) string {
	return filepath.Join(filepath.Dir(cfg.StateFile), "sync.log")
}

func formatCardanoSyncLogLine(h ogmiosHealth, at time.Time) string {
	ts := at.UTC().Format("15:04:05Z")
	pct := h.SyncPct * 100
	if pct < 0 {
		pct = 0
	}
	if pct > 100 {
		pct = 100
	}
	height := h.TipHeight
	if height <= 0 {
		height = h.TipSlot
	}
	if h.Synced {
		line := fmt.Sprintf("%s  synced  tip slot %d", ts, h.TipSlot)
		if h.TipHeight > 0 {
			line += fmt.Sprintf("  ·  height %d", h.TipHeight)
		}
		if h.Epoch >= 0 {
			line += fmt.Sprintf("  ·  epoch %d", h.Epoch)
		}
		return line
	}
	line := fmt.Sprintf("%s  syncing  tip slot %d", ts, h.TipSlot)
	if height > 0 {
		line += fmt.Sprintf("  ·  height %d", height)
	}
	if pct > 0 || h.NetworkSync != "" {
		line += fmt.Sprintf("  ·  %.1f%%", pct)
	}
	if h.Epoch >= 0 {
		line += fmt.Sprintf("  ·  epoch %d", h.Epoch)
	}
	if h.Peers >= 0 {
		line += fmt.Sprintf("  ·  peers %d", h.Peers)
	}

	return line
}

func maybeAppendCardanoSyncLog(cfg Config, h ogmiosHealth) {
	if !h.OK {
		return
	}
	now := time.Now()
	line := formatCardanoSyncLogLine(h, now)

	cardanoSyncLog.mu.Lock()
	defer cardanoSyncLog.mu.Unlock()

	slotMoved := h.TipSlot != cardanoSyncLog.lastSlot
	sameLine := line == cardanoSyncLog.lastLine || syncLogSameProgress(cardanoSyncLog.lastLine, line)
	gapOK := cardanoSyncLog.lastWrite.IsZero() || now.Sub(cardanoSyncLog.lastWrite) >= cardanoSyncLogMinGap

	if h.Synced {
		already := cardanoSyncLog.lastLine != "" && strings.Contains(cardanoSyncLog.lastLine, " synced ")
		if already {
			return
		}
	} else if !slotMoved && sameLine && !gapOK {
		return
	} else if !slotMoved && !gapOK {
		return
	}

	path := cardanoSyncLogPath(cfg)
	_ = os.MkdirAll(filepath.Dir(path), 0o755)
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	_, _ = f.WriteString(line + "\n")
	_ = f.Close()

	cardanoSyncLog.lastLine = line
	cardanoSyncLog.lastSlot = h.TipSlot
	cardanoSyncLog.lastWrite = now
	trimBitcoinSyncLogFile(path, cardanoSyncLogMaxLines)
}

func cardanoSyncLogTail(cfg Config, n int) []string {
	if n <= 0 {
		n = 80
	}
	samples := textLogTail(cardanoSyncLogPath(cfg), n)
	journal := cardanoJournalLogLines(cfg, n)
	merged := mergeLogTails(journal, samples, n)
	if len(merged) > 0 {
		return merged
	}
	if len(samples) > 0 {
		return samples
	}

	return journal
}

func cardanoJournalLogLines(cfg Config, n int) []string {
	unit := strings.TrimSpace(cfg.NodeService)
	if unit == "" {
		unit = fmt.Sprintf("cardano-%s.service", normalizeEnvName(cfg.Env))
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
		ln = stripANSI(strings.TrimSpace(ln))
		if ln == "" {
			continue
		}
		if len(ln) > 400 {
			ln = ln[:400] + "…"
		}
		out = append(out, ln)
	}
	if len(out) > n {
		out = out[len(out)-n:]
	}

	return out
}

func stripANSI(s string) string {
	if !strings.Contains(s, "\x1b[") {
		return s
	}
	var b strings.Builder
	b.Grow(len(s))
	for i := 0; i < len(s); i++ {
		if s[i] == '\x1b' && i+1 < len(s) && s[i+1] == '[' {
			j := i + 2
			for j < len(s) {
				c := s[j]
				if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') {
					j++
					break
				}
				j++
			}
			i = j - 1
			continue
		}
		b.WriteByte(s[i])
	}

	return b.String()
}
