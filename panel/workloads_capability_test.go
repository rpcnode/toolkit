package main

import (
	"net/http"
	"strings"
	"testing"
)

func TestRewriteAgentCapabilityErrorUnsupportedNetwork(t *testing.T) {
	code, out := rewriteAgentCapabilityError(
		map[string]any{
			"ok": false, "error": "unsupported_network",
			"message": "supported: tron, bitcoin", "agent_version": "0.4.20",
		},
		"plan_failed", "ltc", "regtest", "0.4.20", "",
	)
	if code != http.StatusBadRequest {
		t.Fatalf("status=%d", code)
	}
	if out["error"] != "unsupported_network" {
		t.Fatalf("error=%v", out["error"])
	}
	msg, _ := out["message"].(string)
	if !strings.Contains(msg, "ltc/regtest") || !strings.Contains(msg, "Update the host agent") {
		t.Fatalf("message=%q", msg)
	}
	if out["agent_version"] != "0.4.20" {
		t.Fatalf("agent_version=%v", out["agent_version"])
	}
}

func TestRewriteAgentCapabilityErrorNoCanonicalPorts(t *testing.T) {
	code, out := rewriteAgentCapabilityError(
		map[string]any{
			"ok": false, "error": "no_free_ports",
			"message": "no canonical ports for dash/regtest",
		},
		"plan_failed", "dash", "regtest", "0.4.10", "",
	)
	if code != http.StatusBadRequest {
		t.Fatalf("status=%d", code)
	}
	if out["error"] != "unsupported_env" {
		t.Fatalf("error=%v", out["error"])
	}
	msg, _ := out["message"].(string)
	if !strings.Contains(msg, "dash/regtest") || !strings.Contains(msg, "v0.4.10") {
		t.Fatalf("message=%q", msg)
	}
}
