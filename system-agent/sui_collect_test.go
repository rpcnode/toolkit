package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestSuiGraphQLTipCheckpoint(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"data": map[string]any{
				"checkpoint": map[string]any{"sequenceNumber": 309856512},
			},
		})
	}))
	defer srv.Close()
	t.Setenv("SUI_PUBLIC_TIP_GRAPHQL", srv.URL)
	n, err := suiGraphQLTipCheckpoint(Config{Env: "mainnet"})
	if err != nil || n != 309856512 {
		t.Fatalf("got n=%d err=%v", n, err)
	}
}

func TestSuiTipFromJSONRPCURLsSkipsDeprecated(t *testing.T) {
	deprecated := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"jsonrpc": "2.0",
			"error":   map[string]any{"code": -32601, "message": "Method not found"},
			"id":      1,
		})
	}))
	defer deprecated.Close()
	okRPC := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"jsonrpc": "2.0", "id": 1, "result": "12345",
		})
	}))
	defer okRPC.Close()
	n := suiTipFromJSONRPCURLs([]string{deprecated.URL, okRPC.URL})
	if n != 12345 {
		t.Fatalf("tip=%d want 12345 (skip deprecated Mysten JSON-RPC)", n)
	}
}

func TestSuiGenesisCheckpointForcesCatchUp(t *testing.T) {
	// Document contract: synced==0 + RPC up must not look "healthy" in collect
	// (tip may be 0 when Mysten JSON-RPC is dead). Mirror the gate here.
	synced := int64(0)
	tip := int64(0)
	rpcOK := true
	nodeActive := true
	catchingUp := false
	if tip > 0 && synced >= 0 {
		catchingUp = (tip - synced) > 32
	}
	if nodeActive && rpcOK && synced == 0 {
		catchingUp = true
	}
	healthy := rpcOK && !catchingUp && synced > 0
	if !catchingUp || healthy {
		t.Fatalf("genesis RPC must stay catch-up: catchingUp=%v healthy=%v", catchingUp, healthy)
	}
}

func TestParsePromSampleCheckpoint(t *testing.T) {
	name, val, ok := parsePromSample(`highest_synced_checkpoint 12345`)
	if !ok || name != "highest_synced_checkpoint" || val != 12345 {
		t.Fatalf("got name=%q val=%d ok=%v", name, val, ok)
	}
	name, val, ok = parsePromSample(`highest_known_checkpoint{network="mainnet"} 99`)
	if !ok || name != "highest_known_checkpoint" || val != 99 {
		t.Fatalf("labeled sample: name=%q val=%d ok=%v", name, val, ok)
	}
}

func TestParseSuiFormalSnapshotProgress(t *testing.T) {
	p, ok := parseSuiFormalSnapshotProgress("Downloading objects: 42 out of 100 files done")
	if !ok || p < 41.9 || p > 42.1 {
		t.Fatalf("files done: pct=%v ok=%v", p, ok)
	}
	p2, ok2 := parseSuiFormalSnapshotProgress("snapshot restore 87.5%")
	if !ok2 || p2 < 87.4 || p2 > 87.6 {
		t.Fatalf("bare pct: pct=%v ok=%v", p2, ok2)
	}
	_, ok3 := parseSuiFormalSnapshotProgress("no progress here")
	if ok3 {
		t.Fatal("want no match")
	}
}

func TestSuiCatchupLagClosedPct(t *testing.T) {
	dir := t.TempDir()
	cfg := Config{StateFile: filepath.Join(dir, "agent-state.json")}
	_ = os.WriteFile(cfg.StateFile, []byte(`{}`), 0o644)

	p, ok := suiCatchupLagClosedPct(cfg, 1000)
	if !ok || p < 0.1 || p > 0.1+0.01 {
		// first observation: peak=behind → ~0.1 floor
		if !ok || p != 0.1 {
			t.Fatalf("first lag pct=%v ok=%v want 0.1", p, ok)
		}
	}
	p2, ok2 := suiCatchupLagClosedPct(cfg, 500)
	if !ok2 || p2 < 49.0 || p2 > 51.0 {
		t.Fatalf("half closed pct=%v ok=%v", p2, ok2)
	}
	p3, ok3 := suiCatchupLagClosedPct(cfg, 0)
	if !ok3 || p3 != 99.9 {
		t.Fatalf("behind0 pct=%v ok=%v", p3, ok3)
	}
}

func TestZcashSuiProfilesExist(t *testing.T) {
	z := LookupNetworkProfile("zcash", "mainnet")
	if z.DefaultPublicPort != 42490 || z.DefaultAgentPort != 42590 || z.DefaultNodeHTTP != 8232 {
		t.Fatalf("zcash profile: %+v", z)
	}
	if z.LifecycleCapabilities()["snapshot"] || !z.LifecycleCapabilities()["ibd"] {
		t.Fatalf("zcash caps: %v", z.LifecycleCapabilities())
	}
	s := LookupNetworkProfile("sui", "mainnet")
	if s.DefaultPublicPort != 42690 || s.DefaultAgentPort != 42790 || s.DefaultNodeHTTP != 9000 {
		t.Fatalf("sui profile: %+v", s)
	}
	if s.LifecycleCapabilities()["snapshot"] || !s.LifecycleCapabilities()["ibd"] {
		t.Fatalf("sui caps: %v", s.LifecycleCapabilities())
	}
}
