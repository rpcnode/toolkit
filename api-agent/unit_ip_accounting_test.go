package main

import (
	"strings"
	"testing"
)

func TestApplyNodeUnitIPAccounting(t *testing.T) {
	in := "[Service]\nLimitNOFILE=1048576\n"
	out := applyNodeUnitIPAccounting(in)
	want := "[Service]\nIPAccounting=yes\nCPUAccounting=yes\nMemoryAccounting=yes\nLimitNOFILE=1048576\n"
	if out != want {
		t.Fatalf("got %q", out)
	}
	if applyNodeUnitIPAccounting(out) != out {
		t.Fatal("not idempotent")
	}
	legacy := "[Service]\nIPAccounting=yes\nLimitNOFILE=1048576\n"
	got := applyNodeUnitIPAccounting(legacy)
	if !strings.Contains(got, "CPUAccounting=yes") || !strings.Contains(got, "MemoryAccounting=yes") {
		t.Fatalf("backfill: %q", got)
	}
}

func TestNodeUnitNamesFromInstance(t *testing.T) {
	got := nodeUnitNamesFromInstance(map[string]any{
		"network": "aptos",
		"env":     "testnet",
		"units": []any{
			"aptos-testnet.service",
			"rpcnode-api-agent-aptos-testnet.service",
			"rpcnode-system-agent-aptos-testnet.service",
		},
	})
	want := map[string]bool{"aptos-testnet.service": true}
	for _, u := range got {
		if !want[u] {
			t.Fatalf("unexpected unit %q in %v", u, got)
		}
		delete(want, u)
	}
	if len(want) != 0 {
		t.Fatalf("missing %v", want)
	}
}
