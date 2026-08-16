package main

import (
	"strings"
	"testing"
)

func TestAgentLogPathForUnit(t *testing.T) {
	got := agentLogPathForUnit("rpcnode-api-agent-bitcoin-mainnet.service")
	want := "/var/log/rpcnode/rpcnode-api-agent-bitcoin-mainnet.log"
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
	if agentLogPathForUnit("rpcnode-system-agent") != "/var/log/rpcnode/rpcnode-system-agent.log" {
		t.Fatal("tip system-agent path")
	}
	if agentLogPathForUnit("") != "" {
		t.Fatal("empty")
	}
}

func TestAgentFileLogDropInBody(t *testing.T) {
	body := agentFileLogDropInBody("rpcnode-api-agent.service")
	if !strings.Contains(body, "StandardOutput=append:/var/log/rpcnode/rpcnode-api-agent.log") {
		t.Fatalf("stdout missing: %s", body)
	}
	if !strings.Contains(body, "StandardError=append:/var/log/rpcnode/rpcnode-api-agent.log") {
		t.Fatalf("stderr missing: %s", body)
	}
}

func TestAgentLogrotateBody(t *testing.T) {
	body := agentLogrotateBody()
	for _, needle := range []string{
		"/var/log/rpcnode.log",
		"/var/log/rpcnode/*.log",
		"size 100M",
		"rotate 7",
		"copytruncate",
	} {
		if !strings.Contains(body, needle) {
			t.Fatalf("missing %q in:\n%s", needle, body)
		}
	}
}
