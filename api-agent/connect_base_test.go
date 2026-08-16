package main

import "testing"

func TestEnsureURLPortReplacesAgentPort(t *testing.T) {
	// Panel dials leaf Agent API; Connect RPC must force public Go RPC port.
	got := ensureURLPort("http://185.44.207.104:41392", 41292)
	want := "http://185.44.207.104:41292"
	if got != want {
		t.Fatalf("ensureURLPort=%q want %q", got, want)
	}
	if ensureURLPort("http://example.com", 8090) != "http://example.com:8090" {
		t.Fatalf("missing port not added: %q", ensureURLPort("http://example.com", 8090))
	}
}

func TestApplyConnectBasePortsOnly(t *testing.T) {
	// No network-specific paths invented — empty collect connect stays ports-only.
	st := map[string]any{"network": "dash"}
	applyConnectBase(st, "http://203.0.113.10:41292", "http://203.0.113.10:41392", 41292, 41392, true)
	conn, _ := st["connect"].(map[string]any)
	if conn["rpc_base"] != "http://203.0.113.10:41292" {
		t.Fatalf("rpc_base=%v", conn["rpc_base"])
	}
	if conn["panel_base"] != "http://203.0.113.10:41392" {
		t.Fatalf("panel_base=%v", conn["panel_base"])
	}
	for _, k := range []string{"wallet", "walletsolidity", "getnowblock", "getnodeinfo"} {
		if _, ok := conn[k]; ok {
			t.Fatalf("must not invent %s: %+v", k, conn)
		}
	}
	if _, ok := conn["examples"]; ok {
		t.Fatalf("must not invent examples: %+v", conn)
	}
}

func TestApplyConnectBaseRebasesCollectPaths(t *testing.T) {
	st := map[string]any{
		"network": "tron",
		"connect": map[string]any{
			"wallet":      "http://old:1/wallet",
			"getnowblock": "http://old:1/wallet/getnowblock",
			"examples": map[string]any{
				"curl_height": "curl -s http://old:1/wallet/getnowblock",
			},
			"note": "collect-owned note",
		},
	}
	applyConnectBase(st, "http://203.0.113.10:8090", "http://203.0.113.10:8091", 8090, 8091, true)
	conn, _ := st["connect"].(map[string]any)
	if conn["rpc_base"] != "http://203.0.113.10:8090" {
		t.Fatalf("rpc_base=%v", conn["rpc_base"])
	}
	if conn["wallet"] != "http://203.0.113.10:8090/wallet" {
		t.Fatalf("wallet=%v want rebased path", conn["wallet"])
	}
	if conn["getnowblock"] != "http://203.0.113.10:8090/wallet/getnowblock" {
		t.Fatalf("getnowblock=%v", conn["getnowblock"])
	}
	if conn["note"] != "collect-owned note" {
		t.Fatalf("note=%v", conn["note"])
	}
	ex, _ := conn["examples"].(map[string]any)
	if ex["curl_height"] != "curl -s http://203.0.113.10:8090/wallet/getnowblock" {
		t.Fatalf("examples=%v", ex)
	}
}

func TestResolveConnectBaseUsesPublicPort(t *testing.T) {
	// Request Host is Agent API; listenPort is public Go RPC.
	got := resolveConnectBase("", "http://203.0.113.10:41392", 41292)
	if got != "http://203.0.113.10:41292" {
		t.Fatalf("resolveConnectBase=%q", got)
	}
}
