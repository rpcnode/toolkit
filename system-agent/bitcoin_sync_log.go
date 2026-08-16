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
	bitcoinSyncLogMaxLines = 120
	bitcoinSyncLogMinGap   = 30 * time.Second
)

type bitcoinSyncLogState struct {
	mu          sync.Mutex
	lastLine    string
	lastBlocks  int64
	lastHeaders int64
	lastWrite   time.Time
}

var bitcoinSyncLog = &bitcoinSyncLogState{}

func bitcoinSyncLogPath(cfg Config) string {
	return filepath.Join(filepath.Dir(cfg.StateFile), "sync.log")
}

func formatBitcoinSyncLogLine(chain bitcoinChainInfo, at time.Time, env string) string {
	ts := at.UTC().Format("15:04:05Z")
	pct := chain.Verify * 100
	if pct < 0 {
		pct = 0
	}
	if pct > 100 {
		pct = 100
	}
	diskGiB := float64(chain.SizeOnDisk) / (1024 * 1024 * 1024)
	// Regtest: never label local tip as IBD.
	if isBitcoinRegtest(env) {
		line := fmt.Sprintf("%s  regtest  blocks %d", ts, chain.Blocks)
		if chain.Peers >= 0 {
			line += fmt.Sprintf("  ·  peers %d", chain.Peers)
		}
		return line
	}
	if chain.IBD {
		line := fmt.Sprintf("%s  IBD  blocks %d / headers %d  ·  %s",
			ts, chain.Blocks, chain.Headers, formatCoreSyncPct(pct))
		if chain.Peers >= 0 {
			line += fmt.Sprintf("  ·  peers %d", chain.Peers)
		}
		if chain.SizeOnDisk > 0 {
			line += fmt.Sprintf("  ·  disk %.1f GiB", diskGiB)
		}
		return line
	}
	line := fmt.Sprintf("%s  synced  height %d", ts, chain.Blocks)
	if chain.Peers >= 0 {
		line += fmt.Sprintf("  ·  peers %d", chain.Peers)
	}
	if chain.Chain != "" {
		line += "  ·  " + chain.Chain
	}
	return line
}

// maybeAppendBitcoinSyncLog writes a progress line while IBD/syncing.
// Emits when blocks advance, IBD flag flips, or min gap elapsed (heartbeat).
func maybeAppendBitcoinSyncLog(cfg Config, chain bitcoinChainInfo) {
	if !chain.OK {
		return
	}
	regtest := isBitcoinRegtest(cfg.Env)
	displayIBD := chain.IBD && !regtest
	// Keep a short post-IBD confirmation line; stop once synced and already logged.
	now := time.Now()
	line := formatBitcoinSyncLogLine(chain, now, cfg.Env)

	bitcoinSyncLog.mu.Lock()
	defer bitcoinSyncLog.mu.Unlock()

	blocksMoved := chain.Blocks != bitcoinSyncLog.lastBlocks
	headersMoved := chain.Headers != bitcoinSyncLog.lastHeaders
	progressMoved := blocksMoved || headersMoved
	sameLine := line == bitcoinSyncLog.lastLine || syncLogSameProgress(bitcoinSyncLog.lastLine, line)
	gapOK := bitcoinSyncLog.lastWrite.IsZero() || now.Sub(bitcoinSyncLog.lastWrite) >= bitcoinSyncLogMinGap

	if !displayIBD {
		// One final synced/regtest line; stay quiet once already logged.
		wasIBD := bitcoinSyncLog.lastLine != "" && strings.Contains(bitcoinSyncLog.lastLine, " IBD ")
		alreadySynced := bitcoinSyncLog.lastLine != "" && (strings.Contains(bitcoinSyncLog.lastLine, " synced ") ||
			strings.Contains(bitcoinSyncLog.lastLine, " regtest "))
		if alreadySynced || (!wasIBD && bitcoinSyncLog.lastLine != "") {
			return
		}
		if !wasIBD {
			// Never saw IBD in this process — still emit one steady line.
			if bitcoinSyncLog.lastLine != "" && !gapOK {
				return
			}
		}
	} else if !progressMoved && sameLine && !gapOK {
		return
	} else if !progressMoved && sameLine && gapOK {
		// heartbeat with same numbers — still useful ("still syncing")
	} else if !progressMoved && !gapOK {
		return
	}

	path := bitcoinSyncLogPath(cfg)
	_ = os.MkdirAll(filepath.Dir(path), 0o755)
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	_, _ = f.WriteString(line + "\n")
	_ = f.Close()

	bitcoinSyncLog.lastLine = line
	bitcoinSyncLog.lastBlocks = chain.Blocks
	bitcoinSyncLog.lastHeaders = chain.Headers
	bitcoinSyncLog.lastWrite = now

	trimBitcoinSyncLogFile(path, bitcoinSyncLogMaxLines)
}

func syncLogSameProgress(prev, next string) bool {
	// Compare without timestamp prefix (first token).
	ap := strings.SplitN(strings.TrimSpace(prev), "  ", 2)
	bp := strings.SplitN(strings.TrimSpace(next), "  ", 2)
	if len(ap) < 2 || len(bp) < 2 {
		return prev == next
	}
	return ap[1] == bp[1]
}

func trimBitcoinSyncLogFile(path string, maxLines int) {
	if maxLines <= 0 {
		return
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return
	}
	lines := strings.Split(strings.TrimRight(string(b), "\n"), "\n")
	if len(lines) <= maxLines {
		return
	}
	lines = lines[len(lines)-maxLines:]
	_ = os.WriteFile(path, []byte(strings.Join(lines, "\n")+"\n"), 0o644)
}

func bitcoinSyncLogTail(cfg Config, n int) []string {
	fileLines := textLogTail(bitcoinSyncLogPath(cfg), n)
	if len(fileLines) > 0 {
		return fileLines
	}
	// Install/start before getblockchaininfo samples — surface bitcoind journal.
	unit := strings.TrimSpace(cfg.NodeService)
	if unit == "" {
		unit = "bitcoin-" + normalizeEnvName(cfg.Env)
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	if n <= 0 {
		n = 60
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

// textLogTail returns the last n non-empty lines (no wget-% filter).
func textLogTail(path string, n int) []string {
	if n <= 0 {
		return nil
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return nil
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
