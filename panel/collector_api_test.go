package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/ali3/tron-toolkit/panel/store"
)

func TestCollectorStats_StaleFlag(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "panel.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })

	s := &Server{db: db}
	req := httptest.NewRequest(http.MethodGet, "/api/collector/stats", nil)
	rec := httptest.NewRecorder()
	s.handleCollectorAPI(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status=%d", rec.Code)
	}
	var empty map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &empty); err != nil {
		t.Fatal(err)
	}
	if empty["stale"] != true {
		t.Fatalf("empty collector must be stale: %+v", empty)
	}
	if empty["has_tick"] != false {
		t.Fatalf("empty collector must not has_tick: %+v", empty)
	}

	if err := db.SetMeta(store.MetaLastTickAt, time.Now().UTC().Format(time.RFC3339)); err != nil {
		t.Fatal(err)
	}
	req2 := httptest.NewRequest(http.MethodGet, "/api/collector/stats", nil)
	rec2 := httptest.NewRecorder()
	s.handleCollectorAPI(rec2, req2)
	var fresh map[string]any
	if err := json.Unmarshal(rec2.Body.Bytes(), &fresh); err != nil {
		t.Fatal(err)
	}
	if fresh["stale"] != false {
		t.Fatalf("fresh tick must not be stale: %+v", fresh)
	}
	if fresh["has_tick"] != true {
		t.Fatalf("fresh tick must has_tick: %+v", fresh)
	}
}
