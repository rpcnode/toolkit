package store

import (
	"path/filepath"
	"testing"
)

func TestUpsertNode_AssignsUUID(t *testing.T) {
	db := openTestDB(t)
	srv, err := db.UpsertServer(Server{
		ID: "srv-1", Name: "s1", Network: "tron", AgentURL: "http://10.0.0.1:39190",
	})
	if err != nil {
		t.Fatal(err)
	}

	a, err := db.UpsertNode(Node{ServerID: srv.ID, Network: "tron", Env: "mainnet"})
	if err != nil {
		t.Fatal(err)
	}
	if !IsNodeUUID(a.ID) {
		t.Fatalf("id=%q", a.ID)
	}
	if a.Name != "Tron mainnet" {
		t.Fatalf("name=%q", a.Name)
	}

	b, err := db.UpsertNode(Node{ServerID: srv.ID, Network: "tron", Env: "mainnet", AgentPort: 39190})
	if err != nil {
		t.Fatal(err)
	}
	if b.ID != a.ID {
		t.Fatalf("upsert changed id %q → %q", a.ID, b.ID)
	}
	if b.AgentPort != 39190 {
		t.Fatalf("agent_port=%d", b.AgentPort)
	}

	// Same env, different network on same server — allowed.
	btc, err := db.UpsertNode(Node{ServerID: srv.ID, Network: "bitcoin", Env: "mainnet", AgentPort: 39390})
	if err != nil {
		t.Fatal(err)
	}
	if btc.ID == a.ID || !IsNodeUUID(btc.ID) {
		t.Fatalf("bitcoin id=%q tron id=%q", btc.ID, a.ID)
	}
}

func TestUpsertNode_AwaitingPortsClearsLeftoverPorts(t *testing.T) {
	db := openTestDB(t)
	srv, err := db.UpsertServer(Server{
		ID: "srv-2", Name: "s2", Network: "stellar", AgentURL: "http://10.0.0.2:39190",
	})
	if err != nil {
		t.Fatal(err)
	}
	prev, err := db.UpsertNode(Node{
		ServerID: srv.ID, Network: "stellar", Env: "testnet",
		PublicPort: 40891, AgentPort: 40991, NodeHTTPPort: 8001, P2PPort: 11627,
		AgentURL: "http://10.0.0.2:40991", Status: "online",
	})
	if err != nil {
		t.Fatal(err)
	}
	// Re-register as Add node would: awaiting_ports + no ports → wipe leftovers.
	next, err := db.UpsertNode(Node{
		ServerID: srv.ID, Network: "stellar", Env: "testnet",
		Status: "awaiting_ports",
	})
	if err != nil {
		t.Fatal(err)
	}
	if next.ID != prev.ID {
		t.Fatalf("id changed %q → %q", prev.ID, next.ID)
	}
	if next.AgentPort != 0 || next.PublicPort != 0 || next.NodeHTTPPort != 0 || next.P2PPort != 0 {
		t.Fatalf("ports not cleared: %+v", next)
	}
	if next.AgentURL != "" {
		t.Fatalf("agent_url=%q want empty", next.AgentURL)
	}
	if next.Status != "awaiting_ports" {
		t.Fatalf("status=%q", next.Status)
	}
}

func TestEnsureNodesUniqueServerNetworkEnv(t *testing.T) {
	db := openTestDB(t)
	// Fresh schema already has UNIQUE(server_id, network, env).
	legacy, err := db.nodesHasLegacyServerEnvUnique()
	if err != nil {
		t.Fatal(err)
	}
	if legacy {
		t.Fatal("fresh schema must not look legacy")
	}
	if err := db.ensureNodesUniqueServerNetworkEnv(); err != nil {
		t.Fatal(err)
	}
}

func TestMigrateNodesToUUID_FromSlugs(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "panel.db")

	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.UpsertServer(Server{
		ID: "tron-1", Name: "tron", Network: "tron", AgentURL: "http://1.1.1.1:39190",
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := db.UpsertServer(Server{
		ID: "bitcoin-1", Name: "btc", Network: "bitcoin", AgentURL: "http://2.2.2.2:39190",
	}); err != nil {
		t.Fatal(err)
	}
	// Simulate pre-v4 slug rows.
	_, err = db.sql.Exec(`
INSERT INTO nodes(id, server_id, name, network, env, public_port, agent_port, node_http_port, p2p_port,
                  agent_url, status, created_at, updated_at)
VALUES
 ('tron-mainnet','tron-1','TRON mainnet','tron','mainnet',0,0,0,0,'','',datetime('now'),datetime('now')),
 ('bitcoin-mainnet','bitcoin-1','Bitcoin mainnet','bitcoin','mainnet',0,39390,0,0,'','',datetime('now'),datetime('now'))`)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = db.sql.Exec(`INSERT INTO node_status(node_id, phase, label, detail, health, raw_json, error, collected_at, last_seen_at)
VALUES('bitcoin-mainnet','syncing','IBD','','','','','','')`)

	if err := db.migrateNodesToUUID(); err != nil {
		t.Fatal(err)
	}

	if _, ok, _ := db.getNodeExact("tron-mainnet"); ok {
		t.Fatal("slug tron-mainnet still present")
	}
	if _, ok, _ := db.getNodeExact("bitcoin-mainnet"); ok {
		t.Fatal("slug bitcoin-mainnet still present")
	}

	tron, ok, err := db.FindNodeByServerNetworkEnv("tron-1", "tron", "mainnet")
	if err != nil || !ok || !IsNodeUUID(tron.ID) {
		t.Fatalf("tron: ok=%v id=%q err=%v", ok, tron.ID, err)
	}
	btc, ok, err := db.FindNodeByServerNetworkEnv("bitcoin-1", "bitcoin", "mainnet")
	if err != nil || !ok || !IsNodeUUID(btc.ID) {
		t.Fatalf("btc: ok=%v id=%q err=%v", ok, btc.ID, err)
	}

	st, ok, err := db.GetNodeStatus(btc.ID)
	if err != nil || !ok {
		t.Fatalf("status fk: ok=%v err=%v", ok, err)
	}
	if st.Phase != "syncing" {
		t.Fatalf("phase=%q", st.Phase)
	}

	// Legacy slug resolve (unique).
	viaSlug, ok, err := db.GetNode("bitcoin-mainnet")
	if err != nil || !ok || viaSlug.ID != btc.ID {
		t.Fatalf("slug resolve: ok=%v id=%q want=%q err=%v", ok, viaSlug.ID, btc.ID, err)
	}

	_ = db.Close()
}
