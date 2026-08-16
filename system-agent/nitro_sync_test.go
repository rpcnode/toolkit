package main

import (
	"strings"
	"testing"
)

func TestApplyNitroEthSyncingBatches(t *testing.T) {
	out := ethereumRPCResult{Peers: -1, Syncing: true}
	applyNitroEthSyncing(&out, map[string]any{
		"batchProcessed":      float64(0),
		"batchSeen":           float64(36380),
		"blockNum":            float64(97509339),
		"executionSyncTarget": float64(97509340),
		"maxMessageCount":     float64(97509340),
	})
	if out.CurrentBlock != 97509339 {
		t.Fatalf("current=%d", out.CurrentBlock)
	}
	if !strings.Contains(out.SyncDetail, "batches") || !strings.Contains(out.SyncDetail, "0 / 36380") {
		t.Fatalf("detail=%q want batches progress", out.SyncDetail)
	}
	// Stalled local tip must not look like 100%.
	if out.HighestBlock <= out.CurrentBlock {
		t.Fatalf("highest=%d current=%d — need headroom while batches lag", out.HighestBlock, out.CurrentBlock)
	}
}

func TestIsNitroOrbitNetwork(t *testing.T) {
	if !isNitroOrbitNetwork("robinhood") || !isNitroOrbitNetwork("arb") {
		t.Fatal("expected arb/robinhood")
	}
	if isNitroOrbitNetwork("ethereum") {
		t.Fatal("ethereum is not nitro orbit")
	}
}
