package main

import "testing"

func TestPortHolderProtectedName(t *testing.T) {
	if got := portHolderProtectedName("sshd", "/usr/sbin/sshd", "sshd.service", "xrpl", "mainnet"); got == "" {
		t.Fatal("sshd must be protected")
	}
	if got := portHolderProtectedName("rpcnode-api-agent", "/opt/rpcnode/bin/rpcnode-api-agent", "rpcnode-api-agent.service", "xrpl", "mainnet"); got == "" {
		t.Fatal("tip api-agent must be protected")
	}
	if got := portHolderProtectedName("xrpld", "/opt/xrpl/mainnet/bin/xrpld --conf=/etc/xrpl/mainnet/xrpld.cfg", "xrpl-mainnet.service", "xrpl", "mainnet"); got == "" {
		t.Fatal("this env unit must be protected (use Remove)")
	}
	if got := portHolderProtectedName("xrpld", "/usr/bin/rippled", "rippled.service", "xrpl", "mainnet"); got != "" {
		t.Fatalf("foreign rippled should be killable, got %q", got)
	}
}

func TestCatalogRoleForPortXRPL(t *testing.T) {
	role, ok := catalogRoleForPort("xrpl", "mainnet", 5005)
	if !ok || role.Role != "node_http_port" {
		t.Fatalf("5005 role=%+v ok=%v", role, ok)
	}
	if _, ok := catalogRoleForPort("xrpl", "mainnet", 22); ok {
		t.Fatal("ssh port must not be a catalog role")
	}
}

func TestPortHolderKillBlockedNotListening(t *testing.T) {
	got := portHolderKillBlocked(portHolderInfo{Port: 5005, Listening: false}, "xrpl", "mainnet")
	if got == "" {
		t.Fatal("expected blocked when not listening")
	}
}
