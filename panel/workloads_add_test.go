package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/ali3/tron-toolkit/panel/store"
)

func TestWorkloadAddRejectsDuplicateServerNetworkEnv(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/panel.db")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })

	srv := &Server{
		db:        db,
		registry:  NewNodeRegistry(db),
		workloads: NewWorkloadRegistry(db),
		client:    http.DefaultClient,
	}
	_ = srv.registry.Upsert(NodeRef{
		ID: "dash-1", Name: "dash-1", AgentURL: "http://127.0.0.1:39190",
	})
	existing := srv.workloads.Upsert(WorkloadRef{
		ServerID: "dash-1",
		Network:  "dash",
		Env:      "testnet",
		Status:   "error",
	})

	body := `{"server_id":"dash-1","network":"dash","env":"testnet","status":"awaiting_ports"}`
	req := httptest.NewRequest(http.MethodPost, "/api/workloads", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	srv.handleWorkloadsAPI(rr, req)

	if rr.Code != http.StatusConflict {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	var out map[string]any
	_ = json.Unmarshal(rr.Body.Bytes(), &out)
	if out["error"] != "already_exists" {
		t.Fatalf("error=%v body=%s", out["error"], rr.Body.String())
	}
	msg, _ := out["message"].(string)
	if !strings.Contains(msg, "dash/testnet already exists") {
		t.Fatalf("message=%q", msg)
	}
	if out["occupied_node_id"] != existing.ID {
		t.Fatalf("occupied_node_id=%v want %s", out["occupied_node_id"], existing.ID)
	}
}
