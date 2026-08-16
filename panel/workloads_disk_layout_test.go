package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/ali3/tron-toolkit/panel/store"
)

func TestWorkloadDiskLayoutAPI_GetPut(t *testing.T) {
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
		ID: "tip-1", Name: "tip", AgentURL: "http://127.0.0.1:39190",
	})
	wl := srv.workloads.Upsert(WorkloadRef{
		ServerID: "tip-1", Network: "aptos", Env: "mainnet", Status: "ready_to_install",
	})

	putBody := `{"disk_layout":{"strategy":"jbod_2","roles":{"state":{"dir":"/mnt/a/aptos/mainnet/db","mount":"/mnt/a"},"index":{"dir":"/mnt/b/aptos/mainnet/index","mount":"/mnt/b"}}}}`
	req := httptest.NewRequest(http.MethodPut, "/api/workloads/"+wl.ID+"/disk-layout", strings.NewReader(putBody))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	srv.handleWorkloadsAPI(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("PUT status=%d body=%s", rr.Code, rr.Body.String())
	}

	reqGet := httptest.NewRequest(http.MethodGet, "/api/workloads/"+wl.ID+"/disk-layout", nil)
	rrGet := httptest.NewRecorder()
	srv.handleWorkloadsAPI(rrGet, reqGet)
	if rrGet.Code != http.StatusOK {
		t.Fatalf("GET status=%d body=%s", rrGet.Code, rrGet.Body.String())
	}
	var out map[string]any
	_ = json.Unmarshal(rrGet.Body.Bytes(), &out)
	dl, _ := out["disk_layout"].(map[string]any)
	if dl["strategy"] != "jbod_2" {
		t.Fatalf("disk_layout=%v", dl)
	}

	reqItem := httptest.NewRequest(http.MethodGet, "/api/workloads/"+wl.ID, nil)
	rrItem := httptest.NewRecorder()
	srv.handleWorkloadsAPI(rrItem, reqItem)
	var itemOut map[string]any
	_ = json.Unmarshal(rrItem.Body.Bytes(), &itemOut)
	item, _ := itemOut["item"].(map[string]any)
	if item["disk_layout"] == nil {
		t.Fatalf("item missing disk_layout: %s", rrItem.Body.String())
	}
}

func TestWorkloadProvision_PersistsAndReusesDiskLayout(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/panel.db")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })

	var gotBody map[string]any
	agent := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &gotBody)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true,"public_port":42890,"agent_port":42990,"node_http_port":8080,"p2p_port":6180}`))
	}))
	t.Cleanup(agent.Close)

	srv := &Server{
		db:        db,
		registry:  NewNodeRegistry(db),
		workloads: NewWorkloadRegistry(db),
		client:    agent.Client(),
	}
	_ = srv.registry.Upsert(NodeRef{
		ID: "tip-aptos", Name: "tip", AgentURL: agent.URL,
	})
	wl := srv.workloads.Upsert(WorkloadRef{
		ServerID: "tip-aptos", Network: "aptos", Env: "mainnet",
		Status: "ready_to_install", PublicPort: 42890, AgentPort: 42990,
	})

	body1 := `{
		"server_id":"tip-aptos","network":"aptos","env":"mainnet",
		"public_port":42890,"agent_port":42990,"node_http_port":8080,"p2p_port":6180,
		"disk_layout":{"strategy":"jbod_2","state_dir":"/mnt/a/aptos/mainnet/db",
			"roles":{"state":{"dir":"/mnt/a/aptos/mainnet/db","mount":"/mnt/a"}}}
	}`
	req1 := httptest.NewRequest(http.MethodPost, "/api/workloads/provision", strings.NewReader(body1))
	req1.Header.Set("Content-Type", "application/json")
	rr1 := httptest.NewRecorder()
	srv.handleWorkloadsAPI(rr1, req1)
	if rr1.Code != http.StatusOK {
		t.Fatalf("provision1 status=%d body=%s", rr1.Code, rr1.Body.String())
	}
	if gotBody["disk_layout"] == nil {
		t.Fatalf("agent payload missing disk_layout: %v", gotBody)
	}

	saved, ok := srv.workloads.Get(wl.ID)
	if !ok || saved.DiskLayout == nil {
		t.Fatalf("not persisted: ok=%v layout=%v", ok, saved.DiskLayout)
	}

	gotBody = nil
	body2 := `{
		"server_id":"tip-aptos","network":"aptos","env":"mainnet",
		"public_port":42890,"agent_port":42990,"node_http_port":8080,"p2p_port":6180
	}`
	req2 := httptest.NewRequest(http.MethodPost, "/api/workloads/provision", strings.NewReader(body2))
	req2.Header.Set("Content-Type", "application/json")
	rr2 := httptest.NewRecorder()
	srv.handleWorkloadsAPI(rr2, req2)
	if rr2.Code != http.StatusOK {
		t.Fatalf("provision2 status=%d body=%s", rr2.Code, rr2.Body.String())
	}
	dl, _ := gotBody["disk_layout"].(map[string]any)
	if dl == nil || dl["strategy"] != "jbod_2" {
		t.Fatalf("re-provision must reuse saved layout, got %v", gotBody)
	}
}

func TestMergeProvisionDiskLayout(t *testing.T) {
	out := mergeProvisionDiskLayout(map[string]any{"strategy": "x"}, "/l", "/a", "/s")
	if out["ledger_dir"] != "/l" || out["accounts_dir"] != "/a" || out["snapshots_dir"] != "/s" {
		t.Fatalf("%v", out)
	}
	if mergeProvisionDiskLayout(nil, "", "", "") != nil {
		t.Fatal("want nil")
	}
}
