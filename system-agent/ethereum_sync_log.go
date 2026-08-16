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
	ethereumSyncLogMaxLines = 120
	ethereumSyncLogMinGap   = 30 * time.Second
)

type ethereumSyncLogState struct {
	mu         sync.Mutex
	lastLine   string
	lastBlocks int64
	lastWrite  time.Time
}

var ethereumSyncLog = &ethereumSyncLogState{}

func ethereumSyncLogPath(cfg Config) string {
	return filepath.Join(filepath.Dir(cfg.StateFile), "sync.log")
}

func formatEthereumSyncLogLine(rpc ethereumRPCResult, syncing bool, at time.Time) string {
	ts := at.UTC().Format("15:04:05Z")
	if syncing {
		line := fmt.Sprintf("%s  syncing  block %d", ts, rpc.Block)
		if rpc.Peers >= 0 {
			line += fmt.Sprintf("  ·  peers %d", rpc.Peers)
		}
		if rpc.SyncDetail != "" {
			line += "  ·  " + rpc.SyncDetail
		}
		return line
	}
	line := fmt.Sprintf("%s  synced  height %d", ts, rpc.Block)
	if rpc.Peers >= 0 {
		line += fmt.Sprintf("  ·  peers %d", rpc.Peers)
	}
	if rpc.ChainID != "" {
		line += "  ·  chain " + rpc.ChainID
	}

	return line
}

func maybeAppendEthereumSyncLog(cfg Config, rpc ethereumRPCResult, syncing bool) {
	if !rpc.OK {
		return
	}
	now := time.Now()
	line := formatEthereumSyncLogLine(rpc, syncing, now)

	ethereumSyncLog.mu.Lock()
	defer ethereumSyncLog.mu.Unlock()

	blocksMoved := rpc.Block != ethereumSyncLog.lastBlocks
	sameLine := line == ethereumSyncLog.lastLine || syncLogSameProgress(ethereumSyncLog.lastLine, line)
	gapOK := ethereumSyncLog.lastWrite.IsZero() || now.Sub(ethereumSyncLog.lastWrite) >= ethereumSyncLogMinGap

	if !syncing {
		alreadySynced := ethereumSyncLog.lastLine != "" && strings.Contains(ethereumSyncLog.lastLine, " synced ")
		if alreadySynced {
			return
		}
		if ethereumSyncLog.lastLine != "" && !gapOK {
			return
		}
	} else if !blocksMoved && sameLine && !gapOK {
		return
	} else if !blocksMoved && !gapOK {
		return
	}

	path := ethereumSyncLogPath(cfg)
	_ = os.MkdirAll(filepath.Dir(path), 0o755)
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	_, _ = f.WriteString(line + "\n")
	_ = f.Close()

	ethereumSyncLog.lastLine = line
	ethereumSyncLog.lastBlocks = rpc.Block
	ethereumSyncLog.lastWrite = now

	trimBitcoinSyncLogFile(path, ethereumSyncLogMaxLines)
}

func ethereumSyncLogTail(cfg Config, n int) []string {
	fileLines := textLogTail(ethereumSyncLogPath(cfg), n)
	if len(fileLines) > 0 {
		return fileLines
	}
	// During install/start warmup (before eth_syncing samples), surface geth journal.
	unit := strings.TrimSpace(cfg.NodeService)
	if unit == "" {
		unit = "ethereum-geth-" + normalizeEnvName(cfg.Env)
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
