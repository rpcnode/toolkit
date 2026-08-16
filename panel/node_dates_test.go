package main

import (
	"testing"

	"github.com/ali3/tron-toolkit/panel/store"
)

func TestShouldStampInstallStarted(t *testing.T) {
	if !shouldStampInstallStarted("awaiting_ports", "installing", "") {
		t.Fatal("awaiting_ports → installing must stamp")
	}
	if shouldStampInstallStarted("installing", "syncing", "") {
		t.Fatal("already past install must not stamp")
	}
	if shouldStampInstallStarted("awaiting_ports", "installing", "2026-01-01T00:00:00Z") {
		t.Fatal("already stamped")
	}
	if shouldStampInstallStarted("awaiting_ports", "ready_to_install", "") {
		t.Fatal("still pre-install")
	}
}

func TestShouldStampSynced(t *testing.T) {
	syncingRaw := `{"sync":{"ok":false,"ibd":true,"verification_pct":12},"rpc":{"ok":true}}`
	syncedRaw := `{"sync":{"ok":true,"ibd":false,"verification_pct":100},"rpc":{"ok":true,"reachable":true}}`
	if !shouldStampSynced("syncing", syncingRaw, true, "") {
		t.Fatal("IBD → synced must stamp")
	}
	if shouldStampSynced("online", syncedRaw, true, "") {
		t.Fatal("already synced prev raw must not stamp")
	}
	if shouldStampSynced("online", "", true, "") {
		t.Fatal("existing online + empty raw must not invent synced_at")
	}
	if !shouldStampSynced("installing", "", true, "") {
		t.Fatal("fast sync (regtest) from installing with empty prev raw must stamp")
	}
	if shouldStampSynced("syncing", syncingRaw, true, "2026-01-01T00:00:00Z") {
		t.Fatal("already stamped")
	}
	if shouldStampSynced("syncing", syncingRaw, false, "") {
		t.Fatal("not synced yet")
	}
	if !store.NodeStatusPreInstall("ports_confirmed") || store.NodeStatusPreInstall("installing") {
		t.Fatal("pre-install helper")
	}
}

func TestShouldClearSynced(t *testing.T) {
	catching := map[string]any{"sync": map[string]any{"ok": false, "ibd": true, "syncing": true}}
	if !shouldClearSynced(false, "2026-08-16T12:42:00Z", catching) {
		t.Fatal("false Synced stamp must clear once leaf is IBD")
	}
	if shouldClearSynced(true, "2026-08-16T12:42:00Z", catching) {
		t.Fatal("honestly synced must keep stamp")
	}
	if shouldClearSynced(false, "", catching) {
		t.Fatal("empty stamp — nothing to clear")
	}
	if shouldClearSynced(false, "2026-08-16T12:42:00Z", map[string]any{"sync": map[string]any{"ok": false}}) {
		t.Fatal("no ibd/syncing — do not clear on a blip")
	}
}
