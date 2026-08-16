package main

import "testing"

func TestNetworkOneEnvPerHostHyperliquid(t *testing.T) {
	if !networkOneEnvPerHost("hyperliquid") {
		t.Fatal("hyperliquid must be one_env_per_host")
	}
	if !networkOneEnvPerHost("ton") {
		t.Fatal("ton must be one_env_per_host")
	}
	if networkOneEnvPerHost("bitcoin") {
		t.Fatal("bitcoin must allow multi-env")
	}
	c := networkConstraint("hyperliquid")
	if c == nil || c["code"] != "process_name_singleton" {
		t.Fatalf("constraint: %#v", c)
	}
	m := networkHostConstraints()
	if _, ok := m["hyperliquid"]; !ok {
		t.Fatal("host constraints missing hyperliquid")
	}
}

func TestCheckOneEnvPerHostSameEnvOK(t *testing.T) {
	// Without host files, no conflict — same-env re-provision always OK.
	if err := checkOneEnvPerHost("hyperliquid", "testnet"); err != nil {
		t.Fatal(err)
	}
}
