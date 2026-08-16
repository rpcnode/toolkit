package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/ali3/tron-toolkit/panel/store"
)

func waitWorkloadGone(t *testing.T, srv *Server, id string, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if _, ok := srv.workloads.Get(id); !ok {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("workload %s still present after %s", id, timeout)
}

func TestWorkloadRemoveUsesServerTipNotPerNodePort(t *testing.T) {
	var gotURL string
	var gotBody map[string]any
	agent := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotURL = r.URL.Path
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &gotBody)
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "env": "testnet4", "network": "bitcoin",
			"delete_files": true, "removed_paths": []string{"/data/bitcoin/testnet4"},
			"agent_teardown_in_sec": 0,
		})
	}))
	defer agent.Close()

	db, err := store.Open(t.TempDir() + "/panel.db")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })

	srv := &Server{
		db:        db,
		registry:  NewNodeRegistry(db),
		workloads: NewWorkloadRegistry(db),
		client:    agent.Client(),
	}
	_ = srv.registry.Upsert(NodeRef{
		ID:       "bitcoin-1",
		Name:     "bitcoin-1",
		AgentURL: agent.URL, // Server tip — remove must hit this host
		AgentKey: "test-key",
	})
	wl := srv.workloads.Upsert(WorkloadRef{
		ServerID:   "bitcoin-1",
		Name:       "Bitcoin testnet4",
		Network:    "bitcoin",
		Env:        "testnet4",
		PublicPort: 39291,
		AgentPort:  39391, // must NOT rewrite remove target to this port
		AgentURL:   "http://203.0.113.10:39391",
		Status:     "syncing",
	})

	body := `{"id":"` + wl.ID + `","delete_files":true}`
	req := httptest.NewRequest(http.MethodPost, "/api/workloads/remove", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	srv.handleWorkloadRemove(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	var out map[string]any
	_ = json.Unmarshal(rr.Body.Bytes(), &out)
	if out["accepted"] != true || out["status"] != "removing" {
		t.Fatalf("want accepted removing: %#v", out)
	}
	if cur, ok := srv.workloads.Get(wl.ID); !ok || cur.Status != "removing" {
		t.Fatalf("should stay listed as removing immediately: ok=%v %#v", ok, cur)
	}

	waitWorkloadGone(t, srv, wl.ID, 2*time.Second)

	if !strings.Contains(gotURL, "/api/v1/networks/bitcoin/envs/testnet4/remove") {
		t.Fatalf("remove path = %q", gotURL)
	}
	if gotBody["delete_files"] != true {
		t.Fatalf("delete_files not set: %#v", gotBody)
	}
}

func TestNormalizeRemoveMode(t *testing.T) {
	cases := []struct {
		mode, wantMode      string
		deleteFiles, force  bool
		wantWipe, wantPanel bool
	}{
		{mode: "wipe", wantMode: "wipe", wantWipe: true},
		{mode: "agents", wantMode: "agents"},
		{mode: "panel", wantMode: "panel", wantPanel: true},
		{mode: "", deleteFiles: true, wantMode: "wipe", wantWipe: true},
		{mode: "", deleteFiles: false, wantMode: "agents"},
		{mode: "", force: true, deleteFiles: true, wantMode: "panel", wantWipe: true},
	}
	for _, tc := range cases {
		gotMode, wipe, panel := normalizeRemoveMode(tc.mode, tc.deleteFiles, tc.force)
		if gotMode != tc.wantMode || wipe != tc.wantWipe || panel != tc.wantPanel {
			t.Fatalf("%+v → mode=%s wipe=%v panel=%v", tc, gotMode, wipe, panel)
		}
	}
}

func TestWorkloadRemoveModePanelSkipsTip(t *testing.T) {
	called := make(chan string, 1)
	agent := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called <- r.URL.Path
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	}))
	defer agent.Close()

	db, err := store.Open(t.TempDir() + "/panel-mode-panel.db")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })

	srv := &Server{
		db: db, registry: NewNodeRegistry(db), workloads: NewWorkloadRegistry(db), client: agent.Client(),
	}
	_ = srv.registry.Upsert(NodeRef{ID: "s1", AgentURL: agent.URL})
	wl := srv.workloads.Upsert(WorkloadRef{
		ServerID: "s1", Network: "tron", Env: "mainnet", Status: "syncing",
	})

	body := `{"id":"` + wl.ID + `","mode":"panel"}`
	req := httptest.NewRequest(http.MethodPost, "/api/workloads/remove", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	srv.handleWorkloadRemove(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	var out map[string]any
	_ = json.Unmarshal(rr.Body.Bytes(), &out)
	if out["panel_only"] != true || out["tip_cleanup"] != "skipped" || out["mode"] != "panel" {
		t.Fatalf("want panel-only skip tip: %#v", out)
	}
	if _, ok := srv.workloads.Get(wl.ID); ok {
		t.Fatal("panel mode should delete the row immediately")
	}
	select {
	case path := <-called:
		t.Fatalf("panel mode must not call tip, path=%q", path)
	case <-time.After(200 * time.Millisecond):
	}
}

func TestWorkloadRemoveModeAgentsKeepsFiles(t *testing.T) {
	var gotBody map[string]any
	agent := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &gotBody)
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "env": "mainnet", "network": "tron",
			"delete_files": false, "agent_teardown_in_sec": 0,
		})
	}))
	defer agent.Close()

	db, err := store.Open(t.TempDir() + "/panel-mode-agents.db")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })

	srv := &Server{
		db: db, registry: NewNodeRegistry(db), workloads: NewWorkloadRegistry(db), client: agent.Client(),
	}
	_ = srv.registry.Upsert(NodeRef{ID: "s1", AgentURL: agent.URL})
	wl := srv.workloads.Upsert(WorkloadRef{
		ServerID: "s1", Network: "tron", Env: "mainnet", Status: "online",
	})

	body := `{"id":"` + wl.ID + `","mode":"agents"}`
	req := httptest.NewRequest(http.MethodPost, "/api/workloads/remove", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	srv.handleWorkloadRemove(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	var out map[string]any
	_ = json.Unmarshal(rr.Body.Bytes(), &out)
	if out["mode"] != "agents" || out["delete_files"] != false {
		t.Fatalf("want agents keep files: %#v", out)
	}
	waitWorkloadGone(t, srv, wl.ID, 2*time.Second)
	if gotBody["delete_files"] != false {
		t.Fatalf("tip must get delete_files=false: %#v", gotBody)
	}
}

func TestWorkloadRemoveForcePanelOnly(t *testing.T) {
	called := make(chan string, 1)
	agent := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called <- r.URL.Path
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	}))
	defer agent.Close()

	db, err := store.Open(t.TempDir() + "/panel-force.db")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })

	srv := &Server{
		db: db, registry: NewNodeRegistry(db), workloads: NewWorkloadRegistry(db), client: agent.Client(),
	}
	_ = srv.registry.Upsert(NodeRef{ID: "s1", AgentURL: agent.URL})
	wl := srv.workloads.Upsert(WorkloadRef{
		ServerID: "s1", Network: "dash", Env: "testnet", Status: "syncing",
	})

	body := `{"id":"` + wl.ID + `","delete_files":true,"force":true}`
	req := httptest.NewRequest(http.MethodPost, "/api/workloads/remove", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	srv.handleWorkloadRemove(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	var out map[string]any
	_ = json.Unmarshal(rr.Body.Bytes(), &out)
	if out["panel_only"] != true {
		t.Fatalf("want panel_only: %#v", out)
	}
	if out["tip_cleanup"] != "enqueued" {
		t.Fatalf("want tip_cleanup enqueued: %#v", out)
	}
	if _, ok := srv.workloads.Get(wl.ID); ok {
		t.Fatal("force should delete immediately")
	}
	select {
	case path := <-called:
		if !strings.Contains(path, "/api/v1/networks/dash/envs/testnet/remove") {
			t.Fatalf("force must best-effort tip remove, path=%q", path)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("force must enqueue best-effort tip wipe (orphans block re-add)")
	}
}

func TestWorkloadRemoveAsyncWipeMessage(t *testing.T) {
	agent := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(80 * time.Millisecond) // simulate Core stop delay
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "env": "mainnet", "network": "bitcoin",
			"delete_files": true, "delete_files_async": true,
			"delete_files_status":   "started",
			"agent_teardown_in_sec": 0,
			"message":               "node stopped; deleting bitcoin/mainnet files in background",
		})
	}))
	defer agent.Close()

	db, err := store.Open(t.TempDir() + "/panel-async.db")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })

	srv := &Server{
		db:        db,
		registry:  NewNodeRegistry(db),
		workloads: NewWorkloadRegistry(db),
		client:    agent.Client(),
	}
	_ = srv.registry.Upsert(NodeRef{
		ID: "bitcoin-1", Name: "bitcoin-1", AgentURL: agent.URL, AgentKey: "k",
	})
	wl := srv.workloads.Upsert(WorkloadRef{
		ServerID: "bitcoin-1", Network: "bitcoin", Env: "mainnet", Status: "syncing",
	})

	body := `{"id":"` + wl.ID + `","delete_files":true}`
	req := httptest.NewRequest(http.MethodPost, "/api/workloads/remove", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	started := time.Now()
	srv.handleWorkloadRemove(rr, req)
	elapsed := time.Since(started)

	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	if elapsed > 50*time.Millisecond {
		t.Fatalf("HTTP must return before tip stop finishes, took %s", elapsed)
	}
	var out map[string]any
	_ = json.Unmarshal(rr.Body.Bytes(), &out)
	if out["accepted"] != true || out["status"] != "removing" {
		t.Fatalf("expected accepted removing: %#v", out)
	}
	msg, _ := out["message"].(string)
	if !strings.Contains(msg, "removing") && !strings.Contains(msg, "background") {
		t.Fatalf("message=%q", msg)
	}
	if cur, ok := srv.workloads.Get(wl.ID); !ok || cur.Status != "removing" {
		t.Fatalf("listed as removing: ok=%v %#v", ok, cur)
	}
	waitWorkloadGone(t, srv, wl.ID, 2*time.Second)
}

func TestStatusSyncableFreezesRemoving(t *testing.T) {
	if statusSyncable("removing") || statusSyncable("remove_error") {
		t.Fatal("removing/remove_error must not be overwritten by collector")
	}
	if !statusSyncable("syncing") {
		t.Fatal("syncing should stay syncable")
	}
}

func TestWorkloadRemoveRetryOnStuckRemoving(t *testing.T) {
	calls := 0
	agent := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "env": "regtest", "network": "bch",
			"delete_files": true, "remove_order": []string{
				"1_kill_node_and_leaf_agents",
				"2_teardown_systemd_units",
				"3_wipe_files_if_requested",
			},
		})
	}))
	defer agent.Close()

	db, err := store.Open(t.TempDir() + "/panel-retry.db")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })

	srv := &Server{
		db: db, registry: NewNodeRegistry(db), workloads: NewWorkloadRegistry(db), client: agent.Client(),
	}
	_ = srv.registry.Upsert(NodeRef{ID: "bitcoin-1", AgentURL: agent.URL})
	wl := srv.workloads.Upsert(WorkloadRef{
		ServerID: "bitcoin-1", Network: "bch", Env: "regtest", Status: "removing",
	})

	body := `{"id":"` + wl.ID + `","delete_files":true}`
	req := httptest.NewRequest(http.MethodPost, "/api/workloads/remove", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	srv.handleWorkloadRemove(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	var out map[string]any
	_ = json.Unmarshal(rr.Body.Bytes(), &out)
	if out["retried"] != true {
		t.Fatalf("stuck removing must re-kick tip: %#v", out)
	}
	waitWorkloadGone(t, srv, wl.ID, 2*time.Second)
	if calls < 1 {
		t.Fatal("tip remove must be called on retry")
	}
}
