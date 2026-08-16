package main

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// rethJournalProgress — base-reth-node (and similar) journal when eth_syncing
// reports current/highest=0 during reverse Headers download.
type rethJournalProgress struct {
	OK         bool
	Stage      string
	Tip        int64 // tip / max from_block (or payload tip)
	Cursor     int64 // remaining lower bound (to_block while reverse-syncing)
	Downloaded int64
	StagePct   float64 // 0..100 within Headers reverse download
	VerifyPct  float64 // 0..100 overall bar (Headers band 0..10)
	Detail     string
}

var (
	rethHeadersRe = regexp.MustCompile(
		`"message"\s*:\s*"Received headers"[^}]*"from_block"\s*:\s*(\d+)\s*,\s*"to_block"\s*:\s*(\d+)`,
	)
	rethHeadersLooseRe = regexp.MustCompile(
		`Received headers.*"from_block"\s*:\s*(\d+).*"to_block"\s*:\s*(\d+)`,
	)
	// Field order may swap in structured logs.
	rethHeadersSwappedRe = regexp.MustCompile(
		`Received headers.*"to_block"\s*:\s*(\d+).*"from_block"\s*:\s*(\d+)`,
	)
	rethPayloadRe = regexp.MustCompile(
		`"message"\s*:\s*"Received new payload from consensus engine"[^}]*"number"\s*:\s*(\d+)`,
	)
	rethStatusRe = regexp.MustCompile(
		`"message"\s*:\s*"Status"[^}]*"stage"\s*:\s*"([^"]+)"[^}]*"checkpoint"\s*:\s*(\d+)`,
	)
)

// rethHeadersOverallBand — Headers-only progress maps into this share of the
// Sync bar so we don't flash ~97% before Bodies/Execution (eth_syncing zeros).
const rethHeadersOverallBand = 10.0

// Weighted reth pipeline share of the Sync bar (sums to 100). Used when
// eth_syncing current/highest stay 0x0 but stages[] carry real checkpoints.
var rethStageWeights = []struct {
	name   string
	weight float64
}{
	{"Headers", 10},
	{"Bodies", 25},
	{"SenderRecovery", 15},
	{"Execution", 30},
	{"MerkleExecute", 10},
	{"AccountHashing", 5},
	{"StorageHashing", 3},
	{"TransactionLookup", 2},
}

// rethStagesProgress — honest 0..100 from eth_syncing.stages (base-reth).
// tipHint (journal payload / headers tip) raises the denominator when ahead of stages.
func rethStagesProgress(stages []ethSyncStage, tipHint int64) rethJournalProgress {
	out := rethJournalProgress{}
	if len(stages) == 0 {
		return out
	}
	byName := make(map[string]int64, len(stages))
	var tip int64
	for _, st := range stages {
		byName[st.Name] = st.Block
		if st.Block > tip {
			tip = st.Block
		}
	}
	if tipHint > tip {
		tip = tipHint
	}
	if tip <= 0 {
		return out
	}
	var pct float64
	activeName := ""
	var activeBlock int64
	for _, w := range rethStageWeights {
		b := byName[w.name]
		frac := float64(b) / float64(tip)
		if frac > 1 {
			frac = 1
		}
		if frac < 0 {
			frac = 0
		}
		pct += w.weight * frac
		// First major stage not yet caught to tip — UI label (Headers can sit
		// a few thousand behind payload tip while Bodies is the real work).
		if activeName == "" && frac < 0.995 {
			activeName = w.name
			activeBlock = b
		}
	}
	if activeName == "" {
		// All weighted stages near tip — still syncing (Finish/Prune/…).
		activeName = "Pipeline"
		activeBlock = tip
		for _, st := range stages {
			if strings.EqualFold(st.Name, "Finish") && st.Block > 0 {
				activeName = "Finish"
				activeBlock = st.Block
				break
			}
		}
	}
	if pct < 0.1 {
		pct = 0.1
	}
	if pct >= 100 {
		pct = 99.9
	}
	out.OK = true
	out.Tip = tip
	out.Cursor = activeBlock
	out.Downloaded = tip - activeBlock
	if out.Downloaded < 0 {
		out.Downloaded = 0
	}
	out.Stage = activeName
	out.StagePct = float64(activeBlock) / float64(tip) * 100
	if out.StagePct > 99.9 {
		out.StagePct = 99.9
	}
	out.StagePct = float64(int(out.StagePct*10+0.5)) / 10
	out.VerifyPct = float64(int(pct*10+0.5)) / 10
	out.Detail = fmt.Sprintf("%s · %s / %s · %.1f%%",
		activeName, formatCompactInt64(activeBlock), formatCompactInt64(tip), out.VerifyPct)

	return out
}

// applyBaseRethProgress chooses stages[] (preferred) or journal Headers fallback
// when eth_syncing current/highest are empty zeros.
func applyBaseRethProgress(
	syncing bool,
	highestBlock int64,
	verifyPct float64,
	stages []ethSyncStage,
	journal rethJournalProgress,
) (use bool, newSyncing bool, pct float64, detail string, tip int64, cursor int64) {
	newSyncing = syncing
	pct = verifyPct
	ethEmpty := highestBlock <= 0
	if !ethEmpty && verifyPct > 0 {
		return false, newSyncing, pct, "", 0, 0
	}
	tipHint := journal.Tip
	if st := rethStagesProgress(stages, tipHint); st.OK && st.VerifyPct > 0 {
		return true, true, st.VerifyPct, st.Detail, st.Tip, st.Cursor
	}
	if journal.OK && journal.VerifyPct > 0 && (ethEmpty || verifyPct <= 0) {
		return true, true, journal.VerifyPct, journal.Detail, journal.Tip, journal.Cursor
	}

	return false, newSyncing, pct, "", 0, 0
}

func parseRethJournalProgress(lines []string) rethJournalProgress {
	out := rethJournalProgress{Stage: "Headers"}
	var tip, cursor, payloadTip int64
	cursorSet := false
	for _, ln := range lines {
		if m := rethStatusRe.FindStringSubmatch(ln); len(m) == 3 {
			out.Stage = m[1]
			if ck, err := strconv.ParseInt(m[2], 10, 64); err == nil && ck > 0 && out.Stage != "Headers" {
				// Non-headers checkpoint — prefer eth_syncing when available;
				// keep tip floor from checkpoint.
				if ck > tip {
					tip = ck
				}
			}
		}
		if m := rethPayloadRe.FindStringSubmatch(ln); len(m) == 2 {
			if n, err := strconv.ParseInt(m[1], 10, 64); err == nil && n > payloadTip {
				payloadTip = n
			}
		}
		from, to, ok := parseRethHeaderBatch(ln)
		if !ok {
			continue
		}
		if from > tip {
			tip = from
		}
		if to > tip {
			tip = to
		}
		// Reverse download: from_block > to_block; cursor = lower edge.
		low := to
		if from < to {
			low = from
		}
		if !cursorSet || low < cursor {
			cursor = low
			cursorSet = true
		}
	}
	if payloadTip > tip {
		tip = payloadTip
	}
	if tip <= 0 || !cursorSet {
		return out
	}
	out.OK = true
	out.Tip = tip
	out.Cursor = cursor
	if cursor > tip {
		cursor = tip
		out.Cursor = cursor
	}
	out.Downloaded = tip - cursor
	out.StagePct = float64(out.Downloaded) / float64(tip) * 100
	if out.StagePct < 0 {
		out.StagePct = 0
	}
	if out.StagePct >= 100 {
		out.StagePct = 99.9
	}
	out.StagePct = float64(int(out.StagePct*10+0.5)) / 10
	// Map Headers stage into a small overall band; eth_syncing takes over later.
	out.VerifyPct = out.StagePct * (rethHeadersOverallBand / 100)
	if out.VerifyPct < 0.1 && out.Downloaded > 0 {
		out.VerifyPct = 0.1
	}
	out.VerifyPct = float64(int(out.VerifyPct*10+0.5)) / 10
	out.Detail = fmt.Sprintf("Headers · %s left / %s · %.1f%%",
		formatCompactInt64(cursor), formatCompactInt64(tip), out.StagePct)

	return out
}

func parseRethHeaderBatch(ln string) (from, to int64, ok bool) {
	m := rethHeadersRe.FindStringSubmatch(ln)
	if len(m) != 3 {
		m = rethHeadersLooseRe.FindStringSubmatch(ln)
	}
	swapped := false
	if len(m) != 3 {
		m = rethHeadersSwappedRe.FindStringSubmatch(ln)
		swapped = len(m) == 3
	}
	if len(m) != 3 {
		return 0, 0, false
	}
	var err error
	a, err := strconv.ParseInt(m[1], 10, 64)
	if err != nil {
		return 0, 0, false
	}
	b, err := strconv.ParseInt(m[2], 10, 64)
	if err != nil {
		return 0, 0, false
	}
	if swapped {
		// m1=to_block, m2=from_block
		return b, a, true
	}

	return a, b, true
}

func formatCompactInt64(n int64) string {
	switch {
	case n >= 1_000_000:
		return fmt.Sprintf("%.1fM", float64(n)/1_000_000)
	case n >= 1_000:
		return fmt.Sprintf("%.1fK", float64(n)/1_000)
	default:
		return strconv.FormatInt(n, 10)
	}
}

func baseRethProgressPath(cfg Config) string {
	base := strings.TrimSpace(cfg.EtcDir)
	if base == "" {
		base = filepath.Join("/etc/base", cfg.Env)
	}
	return filepath.Join(base, "reth-progress.json")
}

func saveBaseRethProgress(cfg Config, pct float64, detail string) {
	if pct <= 0 || pct > 100 {
		return
	}
	path := baseRethProgressPath(cfg)
	_ = ensureDir(filepath.Dir(path))
	_ = writeJSONFile(path, map[string]any{
		"pct":        pct,
		"detail":     detail,
		"updated_at": time.Now().UTC().Format(time.RFC3339),
	})
}

func loadBaseRethProgress(cfg Config) (pct float64, detail string) {
	doc := readJSONFile(baseRethProgressPath(cfg))
	if doc == nil {
		return 0, ""
	}
	switch v := doc["pct"].(type) {
	case float64:
		pct = v
	case int:
		pct = float64(v)
	case json.Number:
		f, _ := v.Float64()
		pct = f
	}
	if pct <= 0 || pct > 100 {
		return 0, ""
	}
	if s, ok := doc["detail"].(string); ok {
		detail = s
	}
	return pct, detail
}

func clearBaseRethProgress(cfg Config) {
	_ = os.Remove(baseRethProgressPath(cfg))
}

func rethJournalSnapshot(cfg Config, network string) rethJournalProgress {
	if !strings.EqualFold(network, "base") {
		return rethJournalProgress{}
	}
	unit := strings.TrimSpace(cfg.NodeService)
	if unit == "" {
		unit = fmt.Sprintf("base-%s", normalizeEnvName(cfg.Env))
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	cmd := exec.Command("journalctl", "-u", unit, "-n", "250",
		"--no-pager", "-o", "cat")
	out, err := cmd.CombinedOutput()
	if err != nil && len(out) == 0 {
		return rethJournalProgress{}
	}
	raw := strings.Split(string(out), "\n")
	// Prefer lines that carry sync signal; keep order.
	interesting := make([]string, 0, len(raw))
	for _, ln := range raw {
		if strings.Contains(ln, "Received headers") ||
			strings.Contains(ln, "Received new payload") ||
			strings.Contains(ln, `"message":"Status"`) ||
			strings.Contains(ln, `"message": "Status"`) {
			interesting = append(interesting, ln)
		}
	}
	if len(interesting) == 0 {
		interesting = raw
	}

	return parseRethJournalProgress(interesting)
}
