package store

import (
	"path/filepath"
	"testing"
	"time"
)

func openTestDB(t *testing.T) *DB {
	t.Helper()
	db, err := Open(filepath.Join(t.TempDir(), "panel.db"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	return db
}

func TestUpsertServer_DifferentURLsInsertSeparateRows(t *testing.T) {
	db := openTestDB(t)

	a, err := db.UpsertServer(Server{
		Name:     "tron-host",
		AgentURL: "http://10.0.0.1:39090",
		AgentKey: "key-a",
		Network:  "tron",
		Env:      "mainnet",
	})
	if err != nil {
		t.Fatalf("upsert a: %v", err)
	}

	b, err := db.UpsertServer(Server{
		Name:     "btc-host",
		AgentURL: "http://10.0.0.2:39090",
		AgentKey: "key-b",
		Network:  "bitcoin",
		Env:      "mainnet",
	})
	if err != nil {
		t.Fatalf("upsert b: %v", err)
	}

	if a.ID == "" || b.ID == "" {
		t.Fatalf("empty ids: a=%q b=%q", a.ID, b.ID)
	}
	if a.ID == b.ID {
		t.Fatalf("servers collapsed to same id %q", a.ID)
	}
	if a.ID == "tron-mainnet" || b.ID == "tron-mainnet" || b.ID == "bitcoin-mainnet" {
		t.Fatalf("legacy network-env id used: a=%q b=%q", a.ID, b.ID)
	}

	list, err := db.ListServers(false)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(list) != 2 {
		t.Fatalf("want 2 servers, got %d", len(list))
	}

	gotA, ok, err := db.GetServer(a.ID)
	if err != nil || !ok {
		t.Fatalf("get a: ok=%v err=%v", ok, err)
	}
	if gotA.AgentURL != "http://10.0.0.1:39090" {
		t.Fatalf("a url overwritten: %q", gotA.AgentURL)
	}
}

func TestUpsertServer_SameAgentURLUpdatesExisting(t *testing.T) {
	db := openTestDB(t)

	first, err := db.UpsertServer(Server{
		Name:     "edge-1",
		AgentURL: "http://203.0.113.10:39090",
		AgentKey: "secret-1",
	})
	if err != nil {
		t.Fatalf("first: %v", err)
	}

	second, err := db.UpsertServer(Server{
		Name:     "edge-1-renamed",
		AgentURL: "http://203.0.113.10:39090/",
		AgentKey: "secret-2",
	})
	if err != nil {
		t.Fatalf("second: %v", err)
	}

	if first.ID != second.ID {
		t.Fatalf("same url should reuse id: %q vs %q", first.ID, second.ID)
	}

	list, err := db.ListServers(false)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(list) != 1 {
		t.Fatalf("want 1 server, got %d", len(list))
	}
	if list[0].Name != "edge-1-renamed" {
		t.Fatalf("name not updated: %q", list[0].Name)
	}
	if list[0].AgentKey != "secret-2" {
		t.Fatalf("key not updated: %q", list[0].AgentKey)
	}
}

func TestUpsertServer_ExplicitIDStillUpdates(t *testing.T) {
	db := openTestDB(t)

	first, err := db.UpsertServer(Server{
		ID:       "custom-id",
		Name:     "one",
		AgentURL: "http://1.2.3.4:39090",
		AgentKey: "k1",
	})
	if err != nil {
		t.Fatalf("first: %v", err)
	}
	if first.ID != "custom-id" {
		t.Fatalf("id=%q", first.ID)
	}

	second, err := db.UpsertServer(Server{
		ID:       "custom-id",
		Name:     "two",
		AgentURL: "http://5.6.7.8:39090",
		AgentKey: "k2",
	})
	if err != nil {
		t.Fatalf("second: %v", err)
	}
	if second.ID != "custom-id" {
		t.Fatalf("id changed: %q", second.ID)
	}

	list, err := db.ListServers(false)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(list) != 1 {
		t.Fatalf("want 1, got %d", len(list))
	}
	if list[0].AgentURL != "http://5.6.7.8:39090" {
		t.Fatalf("url=%q", list[0].AgentURL)
	}
}

func TestUpsertServer_PreservesExistingWhenAddingSecond(t *testing.T) {
	db := openTestDB(t)

	// Simulate legacy row that already used tron-mainnet id.
	legacy, err := db.UpsertServer(Server{
		ID:       "tron-mainnet",
		Name:     "legacy-tron",
		AgentURL: "http://10.0.0.1:39090",
		AgentKey: "tron-key",
		Network:  "tron",
		Env:      "mainnet",
	})
	if err != nil {
		t.Fatalf("legacy: %v", err)
	}

	added, err := db.UpsertServer(Server{
		Name:     "bitcoin",
		AgentURL: "http://10.0.0.2:39090",
		AgentKey: "btc-key",
		Network:  "tron", // UI historically hardcodes network=tron
		Env:      "mainnet",
	})
	if err != nil {
		t.Fatalf("add: %v", err)
	}

	if added.ID == legacy.ID {
		t.Fatalf("new server replaced legacy id %q", legacy.ID)
	}

	still, ok, err := db.GetServer("tron-mainnet")
	if err != nil || !ok {
		t.Fatalf("legacy row missing: ok=%v err=%v", ok, err)
	}
	if still.AgentURL != "http://10.0.0.1:39090" {
		t.Fatalf("legacy url replaced: %q", still.AgentURL)
	}
	if still.AgentKey != "tron-key" {
		t.Fatalf("legacy key replaced: %q", still.AgentKey)
	}
}

func TestSlugAndHostHelpers(t *testing.T) {
	if got := slugServerID("Edge Host #1"); got != "edge-host-1" {
		t.Fatalf("slug=%q", got)
	}
	if got := hostFromAgentURL("http://203.0.113.10:39090/"); got != "203.0.113.10" {
		t.Fatalf("host=%q", got)
	}
}

func TestRenameServerID_UpdatesFKs(t *testing.T) {
	db := openTestDB(t)

	srv, err := db.UpsertServer(Server{
		ID:       "tron-mainnet",
		Name:     "bitcoin-1",
		Network:  "bitcoin",
		Env:      "mainnet",
		AgentURL: "http://203.0.113.10:39190",
		AgentKey: "btc-key",
	})
	if err != nil {
		t.Fatalf("server: %v", err)
	}

	tronSrv, err := db.UpsertServer(Server{
		ID:       "tron-1",
		Name:     "tron 1",
		Network:  "tron",
		AgentURL: "http://198.51.100.20:39190",
		AgentKey: "tron-key",
	})
	if err != nil {
		t.Fatalf("tron server: %v", err)
	}

	if _, err := db.UpsertNode(Node{
		ServerID: srv.ID,
		Name:     "bitcoin/mainnet",
		Network:  "bitcoin",
		Env:      "mainnet",
		AgentURL: srv.AgentURL,
	}); err != nil {
		t.Fatalf("btc node: %v", err)
	}
	if _, err := db.UpsertNode(Node{
		ServerID: tronSrv.ID,
		Name:     "tron/mainnet",
		Network:  "tron",
		Env:      "mainnet",
		AgentURL: tronSrv.AgentURL,
	}); err != nil {
		t.Fatalf("tron node: %v", err)
	}
	if _, err := db.UpsertServerMetrics(ServerMetrics{
		ServerID:    srv.ID,
		AgentURL:    srv.AgentURL,
		CPUPct:      1.5,
		CollectedAt: time.Now().UTC(),
		LastSeenAt:  time.Now().UTC(),
	}); err != nil {
		t.Fatalf("metrics: %v", err)
	}

	if err := db.RenameServerID("tron-mainnet", "bitcoin-1"); err != nil {
		t.Fatalf("rename: %v", err)
	}

	if _, ok, err := db.GetServer("tron-mainnet"); err != nil || ok {
		t.Fatalf("old server still present: ok=%v err=%v", ok, err)
	}
	got, ok, err := db.GetServer("bitcoin-1")
	if err != nil || !ok {
		t.Fatalf("new server missing: ok=%v err=%v", ok, err)
	}
	if got.AgentURL != "http://203.0.113.10:39190" || got.AgentKey != "btc-key" {
		t.Fatalf("server payload lost: %+v", got)
	}

	btcNode, ok, err := db.FindNodeByServerNetworkEnv("bitcoin-1", "bitcoin", "mainnet")
	if err != nil || !ok {
		t.Fatalf("btc node: ok=%v err=%v", ok, err)
	}
	if !IsNodeUUID(btcNode.ID) {
		t.Fatalf("btc node id=%q want uuid", btcNode.ID)
	}
	if btcNode.ServerID != "bitcoin-1" {
		t.Fatalf("btc node server_id=%q", btcNode.ServerID)
	}

	tronNode, ok, err := db.FindNodeByServerNetworkEnv("tron-1", "tron", "mainnet")
	if err != nil || !ok {
		t.Fatalf("tron node missing after rename: ok=%v err=%v", ok, err)
	}
	if !IsNodeUUID(tronNode.ID) {
		t.Fatalf("tron node id=%q want uuid", tronNode.ID)
	}
	if tronNode.ServerID != "tron-1" {
		t.Fatalf("tron node server_id mutated: %q", tronNode.ServerID)
	}

	m, ok, err := db.GetServerMetrics("bitcoin-1", "")
	if err != nil || !ok {
		t.Fatalf("metrics: ok=%v err=%v", ok, err)
	}
	if m.ServerID != "bitcoin-1" {
		t.Fatalf("metrics server_id=%q", m.ServerID)
	}
}

func TestFixLegacyBitcoinServerIDs_OnMigrate(t *testing.T) {
	db := openTestDB(t)
	if _, err := db.UpsertServer(Server{
		ID:       "tron-mainnet",
		Name:     "bitcoin-1",
		Network:  "bitcoin",
		AgentURL: "http://10.0.0.9:39190",
	}); err != nil {
		t.Fatalf("seed: %v", err)
	}
	if err := db.fixLegacyBitcoinServerIDs(); err != nil {
		t.Fatalf("fix: %v", err)
	}
	if _, ok, _ := db.GetServer("tron-mainnet"); ok {
		t.Fatal("legacy id still present")
	}
	if _, ok, _ := db.GetServer("bitcoin-1"); !ok {
		t.Fatal("expected bitcoin-1")
	}
}
