package main

import (
	"encoding/json"
	"testing"
)

func TestMergeAgentIdentityFromHealthzOverwritesStaleTron(t *testing.T) {
	status, _ := json.Marshal(map[string]any{
		"ok":       true,
		"instance": map[string]any{"network": "tron", "env": "mainnet"},
		"lifecycle": map[string]any{
			"profile": map[string]any{"network": "tron"},
		},
	})
	healthz, _ := json.Marshal(map[string]any{
		"ok":                 true,
		"network":            "bitcoin",
		"supported_networks": []any{"bitcoin", "tron"},
		"capabilities":       map[string]any{"ibd": true, "snapshot": false},
		"upstream":           "127.0.0.1:18090",
	})
	merged := mergeAgentIdentityFromHealthz(status, healthz)
	var doc map[string]any
	if err := json.Unmarshal(merged, &doc); err != nil {
		t.Fatal(err)
	}
	if doc["network"] != "bitcoin" {
		t.Fatalf("network=%v want bitcoin", doc["network"])
	}
	if agentReportedNetwork(doc) != "bitcoin" {
		t.Fatalf("agentReportedNetwork=%q", agentReportedNetwork(doc))
	}
	wl := WorkloadRef{ID: "btc", Network: "bitcoin", Env: "mainnet", AgentPort: 39390}
	out := sanitizeStatusForWorkload(merged, wl, "http://203.0.113.10:39390")
	var sanitized map[string]any
	if err := json.Unmarshal(out, &sanitized); err != nil {
		t.Fatal(err)
	}
	if truthy(sanitized["network_mismatch"]) {
		t.Fatal("must not cache Wrong agent when healthz.network=bitcoin")
	}
}
