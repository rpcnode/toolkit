package main

import (
	"strings"
	"testing"
)

func TestFormatNotifyAlertNode(t *testing.T) {
	msg := formatNotifyAlert("rpc.rps_high", notifyTarget{
		Server:   "tron-1",
		ServerID: "tron-1",
		NodeID:   "66407d81-1013-4dc9-aeeb-eb74ee6b8d60",
		NodeName: "tron/mainnet",
		Network:  "tron",
		Env:      "mainnet",
	}, "Fullnode RPC RPS high",
		"rps_1m: 17 (threshold 10)",
		"p95: 23 ms · inflight: 17",
	)
	for _, want := range []string{
		"RpcNode · rpc.rps_high",
		"Server: tron-1",
		"Node: 66407d81-1013-4dc9-aeeb-eb74ee6b8d60",
		"Name: tron/mainnet",
		"Network / env: tron / mainnet",
		"Fullnode RPC RPS high",
		"rps_1m: 17 (threshold 10)",
	} {
		if !strings.Contains(msg, want) {
			t.Fatalf("missing %q in:\n%s", want, msg)
		}
	}
}

func TestFormatNotifyAlertServerOnly(t *testing.T) {
	msg := formatNotifyAlert("disk.low", notifyTarget{
		Server:   "btc-1",
		ServerID: "btc-1",
	}, "Disk space low", "used: 92% (threshold 90%)")
	if !strings.Contains(msg, "Node: —") {
		t.Fatalf("expected empty node line:\n%s", msg)
	}
	if !strings.Contains(msg, "Network / env: —") {
		t.Fatalf("expected empty network line:\n%s", msg)
	}
}
