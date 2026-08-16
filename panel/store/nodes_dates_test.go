package store

import (
	"strings"
	"testing"
	"time"
)

func TestNodeLifecycleDates_StampOnceAndClearOnAdd(t *testing.T) {
	db := openTestDB(t)
	srv, err := db.UpsertServer(Server{
		ID: "srv-dates", Name: "s", Network: "bitcoin", AgentURL: "http://10.0.0.8:39190",
	})
	if err != nil {
		t.Fatal(err)
	}
	n, err := db.UpsertNode(Node{
		ServerID: srv.ID, Network: "bitcoin", Env: "mainnet", Status: "awaiting_ports",
	})
	if err != nil {
		t.Fatal(err)
	}
	if n.CreatedAt.IsZero() {
		t.Fatal("created_at required on add")
	}
	if n.InstallStartedAt != "" || n.SyncedAt != "" {
		t.Fatalf("pre-install dates=%q %q", n.InstallStartedAt, n.SyncedAt)
	}

	if err := db.StampNodeInstallStarted(n.ID); err != nil {
		t.Fatal(err)
	}
	got, ok, err := db.GetNode(n.ID)
	if err != nil || !ok {
		t.Fatalf("get: ok=%v err=%v", ok, err)
	}
	if strings.TrimSpace(got.InstallStartedAt) == "" {
		t.Fatal("install_started_at empty after stamp")
	}
	first := got.InstallStartedAt
	time.Sleep(5 * time.Millisecond)
	if err := db.StampNodeInstallStarted(n.ID); err != nil {
		t.Fatal(err)
	}
	got2, _, _ := db.GetNode(n.ID)
	if got2.InstallStartedAt != first {
		t.Fatalf("install stamp not once: %q → %q", first, got2.InstallStartedAt)
	}

	if err := db.StampNodeSynced(n.ID); err != nil {
		t.Fatal(err)
	}
	got3, _, _ := db.GetNode(n.ID)
	if got3.SyncedAt == "" {
		t.Fatal("synced_at empty")
	}

	// Re-add / awaiting_ports reset clears install+synced.
	reset, err := db.UpsertNode(Node{
		ID: n.ID, ServerID: srv.ID, Network: "bitcoin", Env: "mainnet",
		Status: "awaiting_ports", PublicPort: 0, AgentPort: 0,
	})
	if err != nil {
		t.Fatal(err)
	}
	if reset.InstallStartedAt != "" || reset.SyncedAt != "" {
		t.Fatalf("reset must clear dates: install=%q synced=%q", reset.InstallStartedAt, reset.SyncedAt)
	}
	if reset.CreatedAt.IsZero() {
		t.Fatal("created_at must survive reset")
	}
}
