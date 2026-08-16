package main

import "testing"

func TestPipelineMayUseTronctl(t *testing.T) {
	if !pipelineMayUseTronctl("tron") || !pipelineMayUseTronctl("") {
		t.Fatal("tron should allow tronctl fallback")
	}
	if pipelineMayUseTronctl("bitcoin") || pipelineMayUseTronctl("Bitcoin") {
		t.Fatal("bitcoin must never fall back to tronctl")
	}
	if pipelineMayUseTronctl("ethereum") || pipelineMayUseTronctl("Ethereum") {
		t.Fatal("ethereum must never fall back to tronctl")
	}
	if pipelineMayUseTronctl("bsc") || pipelineMayUseTronctl("BSC") {
		t.Fatal("bsc must never fall back to tronctl")
	}
	if pipelineMayUseTronctl("solana") {
		t.Fatal("solana must never fall back to tronctl")
	}
}

func TestKeepHostTipUnitsNoPanic(t *testing.T) {
	// Smoke: must not attempt systemctl stop on tip (no-op log when inactive/missing).
	keepHostTipUnits("rpcnode-api-agent-doge-mainnet.service")
}
