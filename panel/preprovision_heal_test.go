package main

import (
	"encoding/json"
	"testing"
)

func TestLeafStatusLooksLiveSynced(t *testing.T) {
	doc := map[string]any{
		"connect": map[string]any{"ready": true},
		"rpc":     map[string]any{"ok": true},
		"sync": map[string]any{
			"ok": true, "ibd": false, "syncing": false,
			"verification_pct": 100.0, "blocks": float64(3158609),
			"detail": "Synced · height 3158609",
		},
		"network": "ltc",
	}
	if !leafStatusLooksLive(doc, "ltc") {
		t.Fatal("synced leaf must look live")
	}
	if got := healWorkloadStatusFromLeafDoc(doc, "awaiting_ports"); got != "online" {
		t.Fatalf("heal=%q want online", got)
	}
}

func TestLeafStatusLooksLiveNeedsProvision(t *testing.T) {
	doc := map[string]any{
		"needs_provision": true,
		"connect":         map[string]any{"ready": false},
		"lifecycle":       map[string]any{"phase": "ports", "current": "ports"},
	}
	if leafStatusLooksLive(doc, "ltc") {
		t.Fatal("needs_provision must not look live")
	}
}

func TestLeafStatusLooksLiveIBD(t *testing.T) {
	doc := map[string]any{
		"rpc":  map[string]any{"ok": true},
		"sync": map[string]any{"ibd": true, "blocks": float64(1000)},
	}
	if !leafStatusLooksLive(doc, "ltc") {
		t.Fatal("IBD leaf is provisioned")
	}
	if got := healWorkloadStatusFromLeafDoc(doc, "awaiting_ports"); got != "syncing" {
		t.Fatalf("heal=%q want syncing", got)
	}
}

func TestLeafHonestlySyncedStaleTronBlockTime(t *testing.T) {
	doc := map[string]any{
		"rpc": map[string]any{"ok": true, "reachable": true},
		"sync": map[string]any{
			"ok": true, "ibd": false, "syncing": false,
			"verification_pct": 100.0, "blocks": float64(563259),
			"block_time": "2018-07-14T15:25:54Z",
		},
	}
	if leafHonestlySynced(doc) {
		t.Fatal("2018 getnowblock time must not count as Synced")
	}
}

func TestHealStuckRunLifecycleETCSyncedHeight0(t *testing.T) {
	// Real mordor bug: eth_syncing=false → verification_pct=100 / SYNCED UI, but
	// lifecycle Height=0 kept Run active ("Syncing · height 0").
	doc := map[string]any{
		"network":  "etc",
		"ui_phase": "run",
		"health":   "degraded",
		"rpc":      map[string]any{"ok": true, "reachable": true, "syncing": false, "current_block": 0},
		"sync": map[string]any{
			"ok": true, "ibd": false, "syncing": false,
			"verification_pct": 100.0, "blocks": float64(0),
		},
		"lifecycle": map[string]any{
			"phase": "run", "current": "run", "complete": false,
			"node_status": "syncing", "busy": true,
			"detail": "Syncing · height 0",
			"steps": []any{
				map[string]any{"id": "ports", "status": "done", "done": true},
				map[string]any{"id": "install", "status": "done", "done": true},
				map[string]any{"id": "start", "status": "done", "done": true},
				map[string]any{"id": "run", "status": "active", "active": true, "done": false,
					"detail": "Syncing · height 0"},
			},
		},
	}
	if !leafHonestlySynced(doc) {
		t.Fatal("verification_pct=100 + !syncing must be honest sync")
	}
	if !healStuckRunLifecycle(doc) {
		t.Fatal("must heal stuck Run")
	}
	lc, _ := doc["lifecycle"].(map[string]any)
	if !truthyAny(lc["complete"]) || strFieldMap(lc, "phase") != "healthy" {
		t.Fatalf("lifecycle after heal: %+v", lc)
	}
	if got := healWorkloadStatusFromLeafDoc(doc, "syncing"); got != "online" {
		t.Fatalf("sqlite heal=%q want online", got)
	}
}

func TestHealStuckRunLifecycleStillIBD(t *testing.T) {
	doc := map[string]any{
		"rpc": map[string]any{"ok": true},
		"sync": map[string]any{
			"ok": true, "ibd": true, "syncing": true,
			"verification_pct": 67.0, "blocks": float64(1_000_000),
		},
		"lifecycle": map[string]any{
			"phase": "run", "current": "run", "complete": false,
		},
	}
	if leafHonestlySynced(doc) {
		t.Fatal("IBD must not look synced")
	}
	if healStuckRunLifecycle(doc) {
		t.Fatal("must not complete Run during IBD")
	}
}

func TestHealSyncedCannotCoexistWithIncompletePorts(t *testing.T) {
	// Real TON cache desync: Sync badge SYNCED (pct + detail) while lifecycle stuck
	// on ports / NODE SETUP. healStuckRunLifecycle must complete any phase.
	doc := map[string]any{
		"network":  "ton",
		"ui_phase": "ports",
		"health":   "setup",
		"rpc": map[string]any{
			"ok": false, "reachable": false, "http_ok": false,
			"process_up": false, "port_open": false,
			"synced": false, "verification_pct": 99.9,
			"out_of_sync_sec": 4.0,
		},
		"sync": map[string]any{
			"ibd": false, "syncing": false,
			"detail": "Synced · 4 sec behind · seqno 85643642",
			"verification_pct": 99.9,
		},
		"lifecycle": map[string]any{
			"phase": "ports", "current": "ports", "complete": false,
			"node_status": "awaiting_ports", "busy": true,
			"detail": "Assigned public :41690 · agent :41790 · waiting for listen",
			"steps": []any{
				map[string]any{"id": "ports", "status": "active", "active": true, "done": false},
				map[string]any{"id": "install", "status": "active", "active": true, "done": false},
				map[string]any{"id": "start", "status": "pending", "done": false},
				map[string]any{"id": "run", "status": "pending", "done": false},
			},
		},
	}
	if !leafHonestlySynced(doc) {
		t.Fatal("pct=99.9 + Synced detail + !ibd must be honest sync")
	}
	if !healStuckRunLifecycle(doc) {
		t.Fatal("must heal incomplete ports while SYNCED")
	}
	lc, _ := doc["lifecycle"].(map[string]any)
	if !truthyAny(lc["complete"]) {
		t.Fatal("lifecycle.complete must be true — SYNCED∩NODE SETUP forbidden")
	}
	if strFieldMap(lc, "phase") != "healthy" {
		t.Fatalf("phase=%q want healthy", strFieldMap(lc, "phase"))
	}
	if got := healWorkloadStatusFromLeafDoc(doc, "agent_error"); got != "online" {
		t.Fatalf("sqlite heal=%q want online", got)
	}
}

func TestHealSyncedSanitizeContract(t *testing.T) {
	// sanitizeStatusForWorkload must apply the same heal (API contract for UI).
	raw, _ := json.Marshal(map[string]any{
		"network": "etc", "ui_phase": "start", "health": "setup",
		"rpc": map[string]any{"ok": true, "reachable": true, "syncing": false},
		"sync": map[string]any{
			"ok": true, "ibd": false, "syncing": false,
			"verification_pct": 100.0, "blocks": float64(0),
			"detail": "Synced · height 0",
		},
		"lifecycle": map[string]any{
			"phase": "start", "current": "start", "complete": false,
			"node_status": "starting",
			"steps": []any{
				map[string]any{"id": "ports", "status": "done", "done": true},
				map[string]any{"id": "install", "status": "done", "done": true},
				map[string]any{"id": "start", "status": "active", "active": true, "done": false},
				map[string]any{"id": "run", "status": "pending", "done": false},
			},
		},
	})
	wl := WorkloadRef{ID: "n1", Network: "etc", Env: "mordor", AgentPort: 41991, PublicPort: 41891}
	out := sanitizeStatusForWorkload(raw, wl, "http://203.0.113.10:41991")
	doc := decodeStatusDoc(out)
	lc, _ := doc["lifecycle"].(map[string]any)
	if !truthyAny(lc["complete"]) {
		t.Fatalf("sanitize must complete lifecycle when SYNCED: %+v", lc)
	}
	if leafHonestlySynced(doc) && !truthyAny(lc["complete"]) {
		t.Fatal("invariant broken: honestly synced with incomplete lifecycle")
	}
}

func TestLeafHonestlySyncedRejectsTipOnlyWithoutPct(t *testing.T) {
	doc := map[string]any{
		"rpc":  map[string]any{"ok": true, "reachable": true},
		"sync": map[string]any{"ok": true, "ibd": false, "syncing": false, "blocks": float64(106333417)},
	}
	if leafHonestlySynced(doc) {
		t.Fatal("live tip without verification_pct must not be Synced")
	}
}

func TestLeafHonestlySyncedRejectsExplicitSyncOKFalse(t *testing.T) {
	doc := map[string]any{
		"rpc":  map[string]any{"ok": true, "verification_pct": 100.0},
		"sync": map[string]any{"ok": false, "ibd": false, "verification_pct": 100.0},
	}
	if leafHonestlySynced(doc) {
		t.Fatal("sync.ok=false must not count as synced (Sui tip-dead)")
	}
}
