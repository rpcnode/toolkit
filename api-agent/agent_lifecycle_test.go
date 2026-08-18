package main

import (
	"os"
	"strings"
	"testing"
)

func TestCanonToolkitCDNHost(t *testing.T) {
	if got := canonToolkitCDNHost("https://rpcnode.dev/install/TOOLKIT_VERSION"); got != "https://toolkit.rpcnode.dev/install/TOOLKIT_VERSION" {
		t.Fatalf("got %q", got)
	}
	if got := canonToolkitCDNHost("https://rpcnode.dev/install"); got != "https://toolkit.rpcnode.dev/install" {
		t.Fatalf("got %q", got)
	}
	if got := canonToolkitCDNHost("https://toolkit.rpcnode.dev/install"); got != "https://toolkit.rpcnode.dev/install" {
		t.Fatalf("must keep toolkit host: %q", got)
	}
}

func TestAgentInstallBaseURLRewritesLegacyHost(t *testing.T) {
	t.Setenv("INSTALL_BASE_URL", "https://rpcnode.dev/install")
	t.Setenv("AGENT_DOWNLOAD_URL", "")
	if got := agentInstallBaseURL(); got != "https://toolkit.rpcnode.dev/install" {
		t.Fatalf("got %q", got)
	}
}

func TestAgentUnitNamesStellarLeaf(t *testing.T) {
	t.Setenv("TRON_NETWORK", "stellar")
	got := agentUnitNames("mainnet")
	wantAPI := "rpcnode-api-agent-stellar-mainnet.service"
	wantSys := "rpcnode-system-agent-stellar-mainnet.service"
	if !containsString(got, wantAPI) || !containsString(got, wantSys) {
		t.Fatalf("stellar leaf units missing: %v", got)
	}
	// Must not invent bare tron env units when network is stellar.
	for _, u := range got {
		if u == "rpcnode-api-agent-mainnet.service" || u == "rpcnode-system-agent-mainnet.service" {
			t.Fatalf("unexpected tron env-only unit for stellar leaf: %v", got)
		}
	}
}

func TestAgentUnitNamesHostTipNoDefaultTron(t *testing.T) {
	t.Setenv("TRON_NETWORK", "")
	t.Setenv("RPCNODE_HOST_TIP", "1")
	got := agentUnitNames("mainnet")
	// Tip lists canon tron-<env> + legacy env-only (restart if file exists) + bootstrap.
	if !containsString(got, "rpcnode-api-agent.service") {
		t.Fatalf("tip bootstrap unit missing: %v", got)
	}
	if !containsString(got, "rpcnode-api-agent-tron-mainnet.service") {
		t.Fatalf("canon tron leaf missing: %v", got)
	}
	for _, u := range got {
		if strings.Contains(u, "stellar") {
			t.Fatalf("agentUnitNames must not invent stellar without glob: %v", got)
		}
	}
	_ = os.Unsetenv("RPCNODE_HOST_TIP")
}

func TestAgentUnitNamesTronLeafCanon(t *testing.T) {
	t.Setenv("TRON_NETWORK", "tron")
	got := agentUnitNames("mainnet")
	if !containsString(got, "rpcnode-api-agent-tron-mainnet.service") ||
		!containsString(got, "rpcnode-system-agent-tron-mainnet.service") {
		t.Fatalf("tron canon leaf missing: %v", got)
	}
}

func containsString(ss []string, want string) bool {
	for _, s := range ss {
		if s == want {
			return true
		}
	}
	return false
}

func TestOrderUnitsLeavesBeforeTip(t *testing.T) {
	in := []string{
		"rpcnode-system-agent-mainnet.service",
		"rpcnode-api-agent.service",
		"rpcnode-api-agent-stellar-mainnet.service",
		"rpcnode-system-agent.service",
		"rpcnode-system-agent-stellar-mainnet.service",
	}
	others, tip := orderUnitsLeavesBeforeTip(in)
	if len(tip) != 2 || !containsString(tip, "rpcnode-api-agent.service") || !containsString(tip, "rpcnode-system-agent.service") {
		t.Fatalf("tip=%v", tip)
	}
	if containsString(others, "rpcnode-api-agent.service") || containsString(others, "rpcnode-system-agent.service") {
		t.Fatalf("tip leaked into others: %v", others)
	}
	if !containsString(others, "rpcnode-api-agent-stellar-mainnet.service") {
		t.Fatalf("stellar leaf missing from others: %v", others)
	}
}

func TestTipBootstrapUnit(t *testing.T) {
	if !tipBootstrapUnit("rpcnode-api-agent.service") {
		t.Fatal("expected tip api")
	}
	if tipBootstrapUnit("rpcnode-api-agent-stellar-mainnet.service") {
		t.Fatal("stellar leaf is not tip bootstrap")
	}
}

func TestAgentWatchdogUnitBody(t *testing.T) {
	body := agentWatchdogUnitBody("/opt/rpcnode/bin/rpcnode-agent-watchdog")
	for _, want := range []string{
		"Description=RpcNode agent watchdog",
		"ExecStart=/opt/rpcnode/bin/rpcnode-agent-watchdog",
		"StartLimitIntervalSec=0",
		"WantedBy=multi-user.target",
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("unit missing %q:\n%s", want, body)
		}
	}
}
