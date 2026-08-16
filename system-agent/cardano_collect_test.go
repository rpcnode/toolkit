package main

import (
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestProbeOgmiosHealthSyncPct(t *testing.T) {
	doc := map[string]any{
		"networkSynchronization": 0.04866,
		"lastKnownTip": map[string]any{
			"slot":   float64(681774),
			"height": float64(681697),
			"id":     "abc",
		},
		"currentEpoch":     float64(31),
		"slotInEpoch":      float64(12174),
		"connectionStatus": "connected",
		"network":          "mainnet",
	}
	body, _ := json.Marshal(doc)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(body)
	}))
	defer srv.Close()

	port := srv.Listener.Addr().(*net.TCPAddr).Port
	h := probeOgmiosHealth(Config{UpstreamHost: "127.0.0.1", UpstreamPort: port})
	if !h.OK {
		t.Fatalf("expected OK, err=%s", h.Error)
	}
	if h.Synced {
		t.Fatalf("expected not synced at 4.8%%")
	}
	if h.SyncPct < 0.048 || h.SyncPct > 0.049 {
		t.Fatalf("SyncPct=%v want ~0.04866", h.SyncPct)
	}
	if h.TipSlot != 681774 {
		t.Fatalf("TipSlot=%d", h.TipSlot)
	}
	if h.TipHeight != 681697 {
		t.Fatalf("TipHeight=%d", h.TipHeight)
	}
	if h.Epoch != 31 {
		t.Fatalf("Epoch=%d", h.Epoch)
	}
}

func TestClamp01PercentPayload(t *testing.T) {
	if got := clamp01(4.866); got < 0.048 || got > 0.049 {
		t.Fatalf("clamp01(4.866)=%v", got)
	}
	if got := clamp01(0.5); got != 0.5 {
		t.Fatalf("clamp01(0.5)=%v", got)
	}
}
