package main

import "testing"

func TestZcashNormalizeChainIBDFromVerify(t *testing.T) {
	c := zcashNormalizeChainIBD(bitcoinChainInfo{OK: true, Blocks: 100, Verify: 0.42}, 1000, false)
	if !c.IBD {
		t.Fatal("verify<1 must imply syncing")
	}
	c = zcashNormalizeChainIBD(bitcoinChainInfo{OK: true, Blocks: 1000, Verify: 1.0}, 1000, true)
	if c.IBD {
		t.Fatal("ibdComplete must clear IBD")
	}
}

func TestZcashNormalizeChainIBDFromEstimatedHeight(t *testing.T) {
	c := zcashNormalizeChainIBD(bitcoinChainInfo{OK: true, Blocks: 10, Headers: 10, Verify: 0}, 500, false)
	if !c.IBD {
		t.Fatal("blocks behind estimatedheight must sync")
	}
}

func TestParseZebraSyncPct(t *testing.T) {
	snip := `zebrad::commands::start: estimated progress to chain tip sync_percent=10.783 %`
	if pct := parseZebraSyncPct(snip); pct < 10 || pct > 11 {
		t.Fatalf("pct=%v", pct)
	}
	// Live Zebra 6.3 journal: no space before %.
	snip2 := `zebrad::components::sync::progress: estimated progress to chain tip sync_percent=3.073% current_height=Height(106462)`
	if pct := parseZebraSyncPct(snip2); pct < 3 || pct > 3.2 {
		t.Fatalf("compact pct=%v", pct)
	}
}

func TestZcashNormalizeChainIBDFromHeadersBehindTip(t *testing.T) {
	// Zebra often keeps headers==blocks while verificationprogress / estimatedheight show IBD.
	c := zcashNormalizeChainIBD(bitcoinChainInfo{OK: true, Blocks: 100_000, Headers: 100_000, Verify: 0.03}, 3_400_000, false)
	if !c.IBD {
		t.Fatal("verify≈0.03 must imply IBD even when headers==blocks")
	}
}

func TestJournalLineIsSystemdNoise(t *testing.T) {
	// Callers pass already-lowercased lines (see journalUnitSnippet).
	if !journalLineIsSystemdNoise("zcash-mainnet.service: main process exited, code=exited, status=1/failure") {
		t.Fatal("main process exited is noise")
	}
	if !journalLineIsSystemdNoise("failed with result 'exit-code'.") {
		t.Fatal("failed with result is noise")
	}
	if journalLineIsSystemdNoise("error: zcashd has reached its end-of-support halt") {
		t.Fatal("app EOL error must not be noise")
	}
}
