package main

import (
	"strings"
	"testing"
)

func TestExpandCarriageProgressKeepsLastTransfer(t *testing.T) {
	raw := "INFO downloading\r" +
		"  transferred 1000 / 10000 bytes (10.00%) [100Mbps, 1h remaining]\r" +
		"  transferred 5000 / 10000 bytes (50.00%) [200Mbps, 30m remaining]\n" +
		"INFO done part\n"
	got := expandCarriageProgress(raw)
	if len(got) < 2 {
		t.Fatalf("expected expanded lines, got %#v", got)
	}
	lastProg := ""
	for _, ln := range got {
		if nitroTransferRe.MatchString(ln) {
			lastProg = ln
		}
	}
	if !strings.Contains(lastProg, "50.00%") {
		t.Fatalf("want last transfer 50%%, got %q from %#v", lastProg, got)
	}
}

func TestFormatNitroTransferProgress(t *testing.T) {
	ln := "transferred 5368709120 / 536870912000 bytes (1.00%) [458.86Mbps, 2h34m26s remaining]"
	got := formatNitroTransferProgress(ln)
	if got == "" {
		t.Fatal("empty progress")
	}
	if !strings.Contains(got, "init download") || !strings.Contains(got, "1.00%") {
		t.Fatalf("unexpected: %q", got)
	}
	if !strings.Contains(got, "GiB") || !strings.Contains(got, "ETA") {
		t.Fatalf("missing size/eta: %q", got)
	}
}

func TestFilterL2JournalLinesNitro(t *testing.T) {
	raw := []string{
		"INFO connected to l1 chain",
		"noise line ignore me",
		"INFO Downloading initial database",
		"transferred 100 / 1000 bytes (10.00%) [10Mbps, 1m remaining]",
	}
	got := filterL2JournalLines(raw, "arb", 10)
	if len(got) < 3 {
		t.Fatalf("expected filtered interesting lines, got %#v", got)
	}
	joined := strings.Join(got, "\n")
	if !strings.Contains(joined, "init download") && !strings.Contains(joined, "Downloading") {
		t.Fatalf("missing download lines: %q", joined)
	}
}
