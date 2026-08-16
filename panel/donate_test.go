package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestDonateURLDefault(t *testing.T) {
	u := donateURL()
	if !strings.HasSuffix(u, "/donate.json") {
		t.Fatalf("donateURL=%q", u)
	}
}

func TestHandleDonateEmptyCache(t *testing.T) {
	donateMu.Lock()
	donateCached = nil
	donateAt = time.Time{}
	donateMu.Unlock()

	// Point at a nonsense URL so fetch fails; expect ok:false empty wallets.
	t.Setenv("DONATE_JSON_URL", "http://127.0.0.1:1/donate.json")
	s := &Server{}
	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/donate", nil)
	s.handleDonate(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("status %d", rr.Code)
	}
	var doc map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &doc); err != nil {
		t.Fatal(err)
	}
	if doc["ok"] != false {
		t.Fatalf("want ok=false got %#v", doc)
	}
}
