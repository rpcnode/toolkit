package main

import (
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/ali3/tron-toolkit/panel/store"
)

func TestCachedStatusPayloadPreservesLifecycle(t *testing.T) {
	dir := t.TempDir()
	db, err := store.Open(filepath.Join(dir, "panel.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	srv, err := db.UpsertServer(store.Server{
		ID: "srv-1", Name: "host", Network: "bitcoin", Env: "mainnet",
		AgentURL: "http://203.0.113.10:39190", AgentKey: "secret",
	})
	if err != nil {
		t.Fatal(err)
	}
	nodeID := "9295c148-98ce-452a-bf19-140b447af57c"
	node, err := db.UpsertNode(store.Node{
		ID: nodeID, ServerID: srv.ID, Network: "bitcoin", Env: "mainnet",
		AgentPort: 39390, Status: "syncing",
		AgentURL: "http://203.0.113.10:39390",
	})
	if err != nil {
		t.Fatal(err)
	}

	raw, _ := json.Marshal(map[string]any{
		"ok": true, "health": "ok", "ui_phase": "sync",
		"lifecycle": map[string]any{
			"phase": "syncing", "label": "IBD", "detail": "blocks 42",
			"node_status": "syncing", "current": "run",
			"steps": []any{
				map[string]any{"id": "ports", "status": "done", "done": true},
				map[string]any{"id": "run", "status": "active"},
			},
		},
		"rpc":  map[string]any{"node_height": 42, "blocks": 42},
		"sync": map[string]any{"ibd": true, "blocks": 42, "headers": 100},
	})
	h := int64(42)
	if err := db.UpsertNodeStatus(store.NodeStatus{
		NodeID: node.ID, Phase: "syncing", Label: "IBD", Detail: "blocks 42",
		Height: &h, Health: "ok", RawJSON: string(raw),
	}); err != nil {
		t.Fatal(err)
	}

	s := &Server{
		db:        db,
		registry:  NewNodeRegistry(db),
		workloads: NewWorkloadRegistry(db),
	}
	req := httptest.NewRequest(
		"GET",
		"/api/status.json?node="+nodeID+"&network=bitcoin&env=mainnet",
		nil,
	)
	doc, ok := s.cachedStatusPayload(req, "http://203.0.113.10:39390", fmt.Errorf("connection refused"))
	if !ok {
		t.Fatal("expected cached payload")
	}
	if doc["agent_reachable"] != false {
		t.Fatalf("agent_reachable=%v", doc["agent_reachable"])
	}
	if doc["cached"] != true {
		t.Fatalf("cached=%v", doc["cached"])
	}
	if doc["error"] != "agent_unreachable" {
		t.Fatalf("error=%v", doc["error"])
	}
	lc, _ := doc["lifecycle"].(map[string]any)
	if lc == nil || lc["phase"] != "syncing" || lc["label"] != "IBD" {
		t.Fatalf("lifecycle wiped: %#v", lc)
	}
	rpc, _ := doc["rpc"].(map[string]any)
	if rpc == nil {
		t.Fatal("rpc missing")
	}
	// json numbers may be float64
	switch v := rpc["node_height"].(type) {
	case float64:
		if v != 42 {
			t.Fatalf("height=%v", v)
		}
	case int64:
		if v != 42 {
			t.Fatalf("height=%v", v)
		}
	default:
		t.Fatalf("height type %T", v)
	}

	// Ensure MarkNodeUnreachable did not wipe raw_json.
	st, found, err := db.GetNodeStatus(nodeID)
	if err != nil || !found {
		t.Fatalf("status after mark: ok=%v err=%v", found, err)
	}
	if st.Phase != "syncing" || st.RawJSON == "" {
		t.Fatalf("collector-style wipe happened: phase=%q raw_len=%d", st.Phase, len(st.RawJSON))
	}
}

func TestFreshCollectorStatus_ServesWithoutMarkingUnreachable(t *testing.T) {
	dir := t.TempDir()
	db, err := store.Open(filepath.Join(dir, "panel.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	srv, err := db.UpsertServer(store.Server{
		ID: "srv-1", Name: "host", Network: "bitcoin", Env: "mainnet",
		AgentURL: "http://203.0.113.10:39190", AgentKey: "secret",
	})
	if err != nil {
		t.Fatal(err)
	}
	nodeID := "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
	if _, err := db.UpsertNode(store.Node{
		ID: nodeID, ServerID: srv.ID, Network: "bitcoin", Env: "mainnet",
		AgentPort: 39390, Status: "installing",
		AgentURL: "http://203.0.113.10:39390",
	}); err != nil {
		t.Fatal(err)
	}
	raw, _ := json.Marshal(map[string]any{
		"ok": true, "health": "ok",
		"lifecycle": map[string]any{"phase": "installing", "current": "install"},
	})
	if err := db.UpsertNodeStatus(store.NodeStatus{
		NodeID: nodeID, Phase: "installing", Label: "Install", Health: "ok", RawJSON: string(raw),
	}); err != nil {
		t.Fatal(err)
	}

	s := &Server{db: db, registry: NewNodeRegistry(db), workloads: NewWorkloadRegistry(db)}
	req := httptest.NewRequest("GET", "/api/status.json?node="+nodeID+"&network=bitcoin&env=mainnet", nil)
	doc, ok := s.freshCollectorStatus(req, 20*time.Second)
	if !ok {
		t.Fatal("expected fresh collector snapshot")
	}
	if doc["source"] != "collector" {
		t.Fatalf("source=%v", doc["source"])
	}
	if doc["error"] == "agent_unreachable" {
		t.Fatal("must not mark unreachable when serving fresh collector cache")
	}
	st, _, _ := db.GetNodeStatus(nodeID)
	if st.Error != "" {
		t.Fatalf("GetNodeStatus error=%q (must not MarkNodeUnreachable)", st.Error)
	}

	rec := httptest.NewRecorder()
	s.proxyToAgent(rec, req)
	if rec.Code != 200 {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
	var out map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	if out["source"] != "collector" {
		t.Fatalf("proxy source=%v body=%s", out["source"], rec.Body.String())
	}
}
