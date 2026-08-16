package main

import (
	"encoding/json"
	"strconv"
	"strings"
	"time"
)

// leafStatusLooksLive — leaf agent already past Confirm/Install (re-add / stuck
// awaiting_ports while units keep running). Used to heal SQLite + skip synthetic
// NODE SETUP shell.
func leafStatusLooksLive(doc map[string]any, wantNet string) bool {
	if doc == nil {
		return false
	}
	want := strings.ToLower(strings.TrimSpace(wantNet))
	if want != "" {
		got := strings.ToLower(strings.TrimSpace(agentReportedNetwork(doc)))
		if got != "" && got != want && !agentSupportsNetwork(doc, want) {
			return false
		}
		// Tip host_tip answering on leaf port is not a live leaf for this network.
		if truthyAny(doc["host_tip"]) {
			return false
		}
	}
	if truthyAny(doc["needs_provision"]) {
		return false
	}
	if conn, _ := doc["connect"].(map[string]any); conn != nil && truthyAny(conn["ready"]) {
		return true
	}
	lc, _ := doc["lifecycle"].(map[string]any)
	phase := strings.ToLower(strFieldMap(doc, "ui_phase"))
	if phase == "" && lc != nil {
		phase = strings.ToLower(strFieldMap(lc, "phase"))
	}
	ns := strings.ToLower(strFieldMap(doc, "node_status"))
	if ns == "" && lc != nil {
		ns = strings.ToLower(strFieldMap(lc, "node_status"))
	}
	cur := ""
	if lc != nil {
		cur = strings.ToLower(strFieldMap(lc, "current", "current_step_id"))
		if truthyAny(lc["complete"]) {
			return true
		}
	}
	switch phase {
	case "healthy", "run", "syncing":
		return true
	}
	switch ns {
	case "running", "healthy", "syncing", "online":
		return true
	}
	switch cur {
	case "run", "healthy", "ibd":
		return true
	// Bare current=start is often a leftover unit after re-add (SQLite still
	// awaiting_ports). Require RPC/process evidence below — do not skip Confirm.
	}
	rpc, _ := doc["rpc"].(map[string]any)
	if rpc != nil && (truthyAny(rpc["ok"]) || truthyAny(rpc["reachable"]) || truthyAny(rpc["http_ok"])) {
		return true
	}
	sync, _ := doc["sync"].(map[string]any)
	if sync != nil {
		if truthyAny(sync["ok"]) && !truthyAny(sync["ibd"]) && !truthyAny(sync["syncing"]) {
			if pct, ok := sync["verification_pct"].(float64); ok && pct >= 99.9 {
				return true
			}
			if blocks, ok := sync["blocks"].(float64); ok && blocks > 0 {
				return true
			}
			if h, ok := sync["height"].(float64); ok && h > 0 {
				return true
			}
		}
		if truthyAny(sync["ibd"]) || truthyAny(sync["syncing"]) {
			return true // leaf is mid-IBD — still provisioned
		}
	}
	return false
}

// verificationPct100 — honest 0..100 from sync or rpc (agent contract).
func verificationPct100(sync, rpc map[string]any) (float64, bool) {
	for _, m := range []map[string]any{sync, rpc} {
		if m == nil {
			continue
		}
		if pct, ok := m["verification_pct"].(float64); ok {
			return pct, true
		}
		// Some agents emit 0..1 verificationprogress.
		if vp, ok := m["verificationprogress"].(float64); ok {
			if vp <= 1.0 {
				return vp * 100, true
			}
			return vp, true
		}
	}
	return 0, false
}

// leafHonestlySynced — Sync UI would paint SYNCED (no IBD, ~100%).
// Contract: this MUST imply lifecycle.complete after healStuckRunLifecycle.
// Explicit sync.ok=false (Sui tip-dead) never counts — keep NODE SETUP.
func leafHonestlySynced(doc map[string]any) bool {
	if doc == nil || truthyAny(doc["needs_provision"]) {
		return false
	}
	sync, _ := doc["sync"].(map[string]any)
	rpc, _ := doc["rpc"].(map[string]any)
	if sync == nil && rpc == nil {
		return false
	}
	if sync != nil {
		if t, err := time.Parse(time.RFC3339, strings.TrimSpace(strFieldMap(sync, "block_time"))); err == nil && time.Since(t) > 3*time.Minute {
			return false
		}
	}
	if sync != nil && (truthyAny(sync["ibd"]) || truthyAny(sync["syncing"])) {
		return false
	}
	if rpc != nil && (truthyAny(rpc["syncing"]) || truthyAny(rpc["initialblockdownload"])) {
		return false
	}
	// Explicit false from agent — not synced (Sui genesis / tip probe dead).
	if sync != nil {
		if _, has := sync["ok"]; has && !truthyAny(sync["ok"]) {
			return false
		}
	}
	rpcOK := rpc != nil && (truthyAny(rpc["ok"]) || truthyAny(rpc["reachable"]) || truthyAny(rpc["http_ok"]))
	syncOK := sync != nil && truthyAny(sync["ok"])
	procUp := rpc != nil && (truthyAny(rpc["process_up"]) || truthyAny(rpc["port_open"]))
	if pct, ok := verificationPct100(sync, rpc); ok && pct >= 99.9 {
		// ~100% without IBD is SYNCED for SyncStatusCard — even when rpc.ok
		// blips false on a stale cache sample (TON out_of_sync + pct).
		if rpcOK || syncOK || procUp {
			return true
		}
		if sync != nil {
			if d := strings.TrimSpace(strFieldMap(sync, "detail")); strings.HasPrefix(strings.ToLower(d), "synced") {
				return true
			}
		}
		if rpc != nil && truthyAny(rpc["synced"]) {
			return true
		}
	}
	// Tip-only health (sync.ok + height) is not Synced without ~100% history proof.
	return false
}

// healStuckRunLifecycle — Sync paints SYNCED while lifecycle.complete=false
// (any phase: ports/install/start/run). Mutate doc so NODE SETUP cannot coexist.
// Name kept: collector/sanitize already call this; behavior is phase-agnostic.
func healStuckRunLifecycle(doc map[string]any) bool {
	if !leafHonestlySynced(doc) {
		return false
	}
	lc, _ := doc["lifecycle"].(map[string]any)
	if lc == nil {
		return false
	}
	if truthyAny(lc["complete"]) {
		// Still normalize ui/health if agent left degraded while complete+synced.
		if h := strings.ToLower(strFieldMap(doc, "health")); h == "degraded" || h == "setup" {
			doc["health"] = "ok"
			doc["degraded"] = false
		}
		if ui := strings.ToLower(strFieldMap(doc, "ui_phase")); ui != "healthy" && ui != "" {
			doc["ui_phase"] = "healthy"
		}
		return false
	}

	height := any(nil)
	detail := "Synced · RPC online"
	if sync, _ := doc["sync"].(map[string]any); sync != nil {
		if d := strings.TrimSpace(strFieldMap(sync, "detail")); d != "" {
			detail = d
		}
		if blocks, ok := sync["blocks"].(float64); ok && blocks > 0 {
			height = int64(blocks)
			if detail == "Synced · RPC online" {
				detail = "Synced · height " + strconv.FormatInt(int64(blocks), 10)
			}
		}
	}
	if h, ok := doc["height"].(float64); ok && h > 0 {
		height = int64(h)
	}

	if steps, ok := lc["steps"].([]any); ok {
		for _, raw := range steps {
			step, _ := raw.(map[string]any)
			if step == nil {
				continue
			}
			id := strings.ToLower(strFieldMap(step, "id"))
			if id != "run" && id != "start" && id != "install" && id != "ports" && id != "snapshot" {
				continue
			}
			step["status"] = "done"
			step["done"] = true
			step["active"] = false
			step["error"] = false
			if id == "run" {
				step["detail"] = detail
			}
		}
	}

	lc["phase"] = "healthy"
	lc["label"] = "Running"
	lc["detail"] = detail
	lc["busy"] = false
	lc["node_status"] = "running"
	lc["complete"] = true
	lc["current"] = "run"
	lc["current_step_id"] = "run"
	if height != nil {
		lc["height"] = height
	}
	doc["lifecycle"] = lc
	doc["ui_phase"] = "healthy"
	doc["node_status"] = "running"
	if h := strings.ToLower(strFieldMap(doc, "health")); h == "degraded" || h == "setup" || h == "" {
		doc["health"] = "ok"
		doc["degraded"] = false
	}
	return true
}

// healWorkloadStatusFromLeafDoc — SQLite status to leave awaiting_ports/ready_to_install
// (or stuck syncing while leaf is already honestly synced).
func healWorkloadStatusFromLeafDoc(doc map[string]any, prev string) string {
	if !leafStatusLooksLive(doc, "") {
		return prev
	}
	if leafHonestlySynced(doc) {
		return "online"
	}
	sync, _ := doc["sync"].(map[string]any)
	if sync != nil && (truthyAny(sync["ibd"]) || truthyAny(sync["syncing"])) {
		return "syncing"
	}
	ns := strings.ToLower(strFieldMap(doc, "node_status"))
	if lc, _ := doc["lifecycle"].(map[string]any); lc != nil && ns == "" {
		ns = strings.ToLower(strFieldMap(lc, "node_status"))
	}
	phase := strings.ToLower(strFieldMap(doc, "ui_phase"))
	if lc, _ := doc["lifecycle"].(map[string]any); lc != nil && phase == "" {
		phase = strings.ToLower(strFieldMap(lc, "phase"))
	}
	if phase == "run" || phase == "syncing" || ns == "syncing" {
		return "syncing"
	}
	if phase == "start" || ns == "starting" || ns == "ready_to_start" {
		return "starting"
	}
	return "online"
}

func decodeStatusDoc(raw []byte) map[string]any {
	var doc map[string]any
	if err := json.Unmarshal(raw, &doc); err != nil || doc == nil {
		return map[string]any{}
	}
	return doc
}
