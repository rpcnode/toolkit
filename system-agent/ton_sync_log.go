package main

import (
	"fmt"
	"os"
	"path/filepath"
	"time"
)

func maybeAppendTonProgressLog(cfg Config, syncing bool, info tonRPCInfo) {
	path := filepath.Join(cfg.EtcDir, "sync-progress.log")
	_ = os.MkdirAll(cfg.EtcDir, 0o755)
	ts := time.Now().UTC().Format(time.RFC3339)
	line := ""
	pct := ""
	if info.VerifyPct > 0 {
		pct = fmt.Sprintf(" pct=%.1f", info.VerifyPct*100)
	}
	switch {
	case info.OutOfSyncOK && info.Seqno <= 0:
		// Dump apply: oos is dump age, pct=99 is a hold — not lag-closed catch-up.
		line = fmt.Sprintf("%s applying seqno=0 dump_age_sec=%g syncing=1%s\n",
			ts, info.OutOfSyncSec, pct)
	case info.OutOfSyncOK && syncing:
		line = fmt.Sprintf("%s out_of_sync_sec=%g seqno=%d syncing=1%s\n",
			ts, info.OutOfSyncSec, info.Seqno, pct)
	case info.OutOfSyncOK:
		line = fmt.Sprintf("%s out_of_sync_sec=%g seqno=%d syncing=0%s\n",
			ts, info.OutOfSyncSec, info.Seqno, pct)
	case info.OK:
		line = fmt.Sprintf("%s seqno=%d tha=1 syncing=%d%s\n",
			ts, info.Seqno, boolInt(syncing), pct)
	case info.DumpPct > 0 && syncing:
		line = fmt.Sprintf("%s dump_pct=%d tha=0 syncing=1%s\n", ts, info.DumpPct, pct)
	default:
		if !syncing {
			return
		}
		// Prefer phase label over opaque tha=0 when bootstrap.log has a signal.
		if phase := tonBootstrapPhaseDetail(cfg); phase != "" {
			line = fmt.Sprintf("%s phase=%q tha=0 syncing=1%s\n", ts, phase, pct)
		} else {
			line = fmt.Sprintf("%s tha=0 syncing=1%s\n", ts, pct)
		}
	}
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	defer f.Close()
	_, _ = f.WriteString(line)
}

func boolInt(v bool) int {
	if v {
		return 1
	}
	return 0
}
