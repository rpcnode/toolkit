package store

import (
	"testing"
)

func TestNodeInstallOptions_PersistAndClearOnAwaitingPorts(t *testing.T) {
	db := openTestDB(t)
	srv, err := db.UpsertServer(Server{
		ID: "srv-opts", Name: "s", Network: "tron", AgentURL: "http://10.0.0.9:38990",
	})
	if err != nil {
		t.Fatal(err)
	}

	n, err := db.UpsertNode(Node{
		ServerID: srv.ID, Network: "tron", Env: "mainnet",
		Status: "ready_to_install",
		InstallOptions: map[string]string{"snapshot": "internal_tx"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if n.InstallOptions["snapshot"] != "internal_tx" {
		t.Fatalf("install_options=%v", n.InstallOptions)
	}

	got, ok, err := db.GetNode(n.ID)
	if err != nil || !ok {
		t.Fatalf("get: ok=%v err=%v", ok, err)
	}
	if got.InstallOptions["snapshot"] != "internal_tx" {
		t.Fatalf("snapshot=%v", got.InstallOptions)
	}

	next, err := db.UpsertNode(Node{
		ID: n.ID, ServerID: srv.ID, Network: "tron", Env: "mainnet",
		Status: "installing", AgentPort: 39190,
	})
	if err != nil {
		t.Fatal(err)
	}
	if next.InstallOptions["snapshot"] != "internal_tx" {
		t.Fatalf("preserved install_options=%v", next.InstallOptions)
	}

	if err := db.SetNodeInstallOptions(n.ID, map[string]string{"snapshot": "balance_history"}); err != nil {
		t.Fatal(err)
	}
	got2, _, _ := db.GetNode(n.ID)
	if got2.InstallOptions["snapshot"] != "balance_history" {
		t.Fatalf("after Set: %v", got2.InstallOptions)
	}

	cleared, err := db.UpsertNode(Node{
		ServerID: srv.ID, Network: "tron", Env: "mainnet",
		Status: "awaiting_ports",
	})
	if err != nil {
		t.Fatal(err)
	}
	if cleared.InstallOptions != nil {
		t.Fatalf("want cleared install_options, got %v", cleared.InstallOptions)
	}
}

func TestMigrateV12_InstallOptionsColumn(t *testing.T) {
	db := openTestDB(t)
	var ver int
	if err := db.sql.QueryRow(`SELECT version FROM schema_version LIMIT 1`).Scan(&ver); err != nil {
		t.Fatal(err)
	}
	if ver < 12 {
		t.Fatalf("schema_version=%d want >=12", ver)
	}
	rows, err := db.sql.Query(`PRAGMA table_info(nodes)`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	found := false
	for rows.Next() {
		var cid int
		var name, ctype string
		var notnull, pk int
		var dflt any
		if err := rows.Scan(&cid, &name, &ctype, &notnull, &dflt, &pk); err != nil {
			t.Fatal(err)
		}
		if name == "install_options_json" {
			found = true
		}
	}
	if !found {
		t.Fatal("nodes.install_options_json missing")
	}
}
