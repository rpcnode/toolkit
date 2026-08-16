package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestParseRethJournalProgressReverseHeaders(t *testing.T) {
	lines := []string{
		`{"timestamp":"2026-08-12T09:06:58Z","level":"INFO","fields":{"message":"Received headers","total":10000,"from_block":49868135,"to_block":49858136},"target":"sync::stages::headers"}`,
		`{"timestamp":"2026-08-12T12:13:17Z","level":"INFO","fields":{"message":"Received headers","total":10000,"from_block":1408135,"to_block":1398136},"target":"sync::stages::headers"}`,
		`{"timestamp":"2026-08-12T12:13:17Z","level":"INFO","fields":{"message":"Status","connected_peers":130,"stage":"Headers","checkpoint":0,"target":"None"},"target":"reth::cli"}`,
		`{"timestamp":"2026-08-12T12:13:17Z","level":"INFO","fields":{"message":"Received new payload from consensus engine","number":49873695},"target":"reth_node_events::node"}`,
	}
	p := parseRethJournalProgress(lines)
	if !p.OK {
		t.Fatal("expected OK")
	}
	if p.Tip < 49868135 {
		t.Fatalf("tip=%d want >= 49868135", p.Tip)
	}
	if p.Cursor != 1398136 {
		t.Fatalf("cursor=%d want 1398136", p.Cursor)
	}
	if p.StagePct < 95 || p.StagePct > 99.9 {
		t.Fatalf("stagePct=%.1f want ~97", p.StagePct)
	}
	if p.VerifyPct <= 0 || p.VerifyPct > rethHeadersOverallBand {
		t.Fatalf("verifyPct=%.1f want in (0, %.0f]", p.VerifyPct, rethHeadersOverallBand)
	}
	if !strings.Contains(p.Detail, "Headers") || !strings.Contains(p.Detail, "%") {
		t.Fatalf("detail=%q", p.Detail)
	}
}

func TestParseRethJournalProgressEmpty(t *testing.T) {
	p := parseRethJournalProgress(nil)
	if p.OK {
		t.Fatal("expected not OK")
	}
}

func TestRethStagesProgressBodiesPhase(t *testing.T) {
	// Live Base mainnet shape: eth_syncing current/highest=0x0, stages carry work.
	stages := []ethSyncStage{
		{Name: "Headers", Block: 49_868_135},
		{Name: "Bodies", Block: 4_500_000},
		{Name: "SenderRecovery", Block: 0},
		{Name: "Execution", Block: 0},
		{Name: "Finish", Block: 0},
	}
	p := rethStagesProgress(stages, 49_874_627)
	if !p.OK {
		t.Fatal("expected OK")
	}
	if p.VerifyPct < 10 || p.VerifyPct > 40 {
		t.Fatalf("verifyPct=%.1f want ~12 (Headers band + early Bodies)", p.VerifyPct)
	}
	if p.Stage != "Bodies" {
		t.Fatalf("stage=%q want Bodies", p.Stage)
	}
	if p.Tip < 49_868_135 {
		t.Fatalf("tip=%d", p.Tip)
	}
	if !strings.Contains(p.Detail, "Bodies") || !strings.Contains(p.Detail, "%") {
		t.Fatalf("detail=%q", p.Detail)
	}
}

func TestApplyBaseRethProgressPrefersStagesOverJournal(t *testing.T) {
	stages := []ethSyncStage{
		{Name: "Headers", Block: 40_000_000},
		{Name: "Bodies", Block: 1_000_000},
	}
	journal := rethJournalProgress{OK: true, VerifyPct: 9.5, Tip: 40_000_000, Detail: "Headers · journal"}
	use, syn, pct, detail, tip, cursor := applyBaseRethProgress(true, 0, 0, stages, journal)
	if !use || !syn {
		t.Fatalf("use=%v syn=%v", use, syn)
	}
	if pct <= 0 || pct > 40 {
		t.Fatalf("pct=%.1f", pct)
	}
	if tip < 40_000_000 {
		t.Fatalf("tip=%d", tip)
	}
	if cursor != 1_000_000 {
		t.Fatalf("cursor=%d", cursor)
	}
	if strings.Contains(detail, "journal") {
		t.Fatalf("should prefer stages detail, got %q", detail)
	}
}

func TestApplyBaseRethProgressJournalFallback(t *testing.T) {
	journal := rethJournalProgress{OK: true, VerifyPct: 8.2, Tip: 50_000_000, Cursor: 1_000_000, Detail: "Headers · 1.0M left / 50.0M · 98.0%"}
	use, syn, pct, detail, tip, _ := applyBaseRethProgress(true, 0, 0, nil, journal)
	if !use || !syn || pct != 8.2 || tip != 50_000_000 {
		t.Fatalf("use=%v syn=%v pct=%.1f tip=%d", use, syn, pct, tip)
	}
	if !strings.Contains(detail, "Headers") {
		t.Fatalf("detail=%q", detail)
	}
}

func TestApplyBaseRethProgressSkipsWhenEthProgressPresent(t *testing.T) {
	stages := []ethSyncStage{{Name: "Headers", Block: 100}}
	use, _, _, _, _, _ := applyBaseRethProgress(true, 1_000_000, 55.5, stages, rethJournalProgress{})
	if use {
		t.Fatal("must not override real eth_syncing current/highest %")
	}
}

func TestParseEthSyncStages(t *testing.T) {
	raw := []any{
		map[string]any{"name": "Headers", "block": "0x2f8ed67"},
		map[string]any{"name": "Bodies", "block": "0x44aa20"},
		map[string]any{"name": "Execution", "checkpoint": float64(1_000_000)},
	}
	st := parseEthSyncStages(raw)
	if len(st) != 3 {
		t.Fatalf("len=%d", len(st))
	}
	if st[0].Block != 0x2f8ed67 || st[1].Block != 0x44aa20 || st[2].Block != 1_000_000 {
		t.Fatalf("blocks=%+v", st)
	}
}

func TestBaseRethProgressPersist(t *testing.T) {
	dir := t.TempDir()
	cfg := Config{EtcDir: dir, Env: "mainnet"}
	saveBaseRethProgress(cfg, 13.6, "Bodies · 7.1M / 49.9M · 13.6%")
	pct, detail := loadBaseRethProgress(cfg)
	if pct != 13.6 || !strings.Contains(detail, "Bodies") {
		t.Fatalf("pct=%.1f detail=%q", pct, detail)
	}
	if _, err := os.Stat(filepath.Join(dir, "reth-progress.json")); err != nil {
		t.Fatal(err)
	}
	clearBaseRethProgress(cfg)
	if p, _ := loadBaseRethProgress(cfg); p != 0 {
		t.Fatalf("cleared pct=%v", p)
	}
}
