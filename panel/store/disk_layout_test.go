package store

import (
	"testing"
)

func TestNodeDiskLayout_PersistAndClearOnAwaitingPorts(t *testing.T) {
	db := openTestDB(t)
	srv, err := db.UpsertServer(Server{
		ID: "srv-disk", Name: "s", Network: "solana", AgentURL: "http://10.0.0.9:39190",
	})
	if err != nil {
		t.Fatal(err)
	}

	n, err := db.UpsertNode(Node{
		ServerID: srv.ID, Network: "solana", Env: "mainnet",
		Status: "ready_to_install",
		DiskLayout: map[string]any{
			"strategy":   "jbod_3",
			"ledger_dir": "/mnt/nvme0/solana/mainnet/ledger",
			"roles": map[string]any{
				"ledger": map[string]any{"dir": "/mnt/nvme0/solana/mainnet/ledger", "mount": "/mnt/nvme0"},
			},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if n.DiskLayout == nil || n.DiskLayout["strategy"] != "jbod_3" {
		t.Fatalf("disk_layout=%v", n.DiskLayout)
	}

	got, ok, err := db.GetNode(n.ID)
	if err != nil || !ok {
		t.Fatalf("get: ok=%v err=%v", ok, err)
	}
	if got.DiskLayout["ledger_dir"] != "/mnt/nvme0/solana/mainnet/ledger" {
		t.Fatalf("ledger_dir=%v", got.DiskLayout["ledger_dir"])
	}

	// Unrelated upsert without DiskLayout must preserve.
	next, err := db.UpsertNode(Node{
		ID: n.ID, ServerID: srv.ID, Network: "solana", Env: "mainnet",
		Status: "installing", AgentPort: 39990,
	})
	if err != nil {
		t.Fatal(err)
	}
	if next.DiskLayout == nil || next.DiskLayout["strategy"] != "jbod_3" {
		t.Fatalf("preserved disk_layout=%v", next.DiskLayout)
	}

	if err := db.SetNodeDiskLayout(n.ID, map[string]any{
		"strategy": "custom",
		"roles":    map[string]any{"ledger": map[string]any{"dir": "/data/x", "mount": "/data"}},
	}); err != nil {
		t.Fatal(err)
	}
	got2, _, _ := db.GetNode(n.ID)
	if got2.DiskLayout["strategy"] != "custom" {
		t.Fatalf("after Set: %v", got2.DiskLayout)
	}

	// Fresh Add → awaiting_ports clears layout.
	cleared, err := db.UpsertNode(Node{
		ServerID: srv.ID, Network: "solana", Env: "mainnet",
		Status: "awaiting_ports",
	})
	if err != nil {
		t.Fatal(err)
	}
	if cleared.DiskLayout != nil {
		t.Fatalf("want cleared disk_layout, got %v", cleared.DiskLayout)
	}
}

func TestMigrateV10_DiskLayoutColumn(t *testing.T) {
	db := openTestDB(t)
	var ver int
	if err := db.sql.QueryRow(`SELECT version FROM schema_version LIMIT 1`).Scan(&ver); err != nil {
		t.Fatal(err)
	}
	if ver < 10 {
		t.Fatalf("schema_version=%d want >=10", ver)
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
		if name == "disk_layout_json" {
			found = true
		}
	}
	if !found {
		t.Fatal("nodes.disk_layout_json missing")
	}
}
