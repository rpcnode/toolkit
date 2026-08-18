package main

import "testing"

func TestNormalizeVer(t *testing.T) {
	if normalizeVer("v3.3.0") != "3.3.0" {
		t.Fatalf("got %q", normalizeVer("v3.3.0"))
	}
	if normalizeVer("3.3.0") != "3.3.0" {
		t.Fatalf("got %q", normalizeVer("3.3.0"))
	}
}

func TestRepoFromSource(t *testing.T) {
	repo, ok := repoFromSource("anza-xyz/agave")
	if !ok || repo != "anza-xyz/agave" {
		t.Fatalf("agave: %q %v", repo, ok)
	}
	repo, ok = repoFromSource("sigp/lighthouse + apt geth")
	if !ok || repo != "sigp/lighthouse" {
		t.Fatalf("lighthouse: %q %v", repo, ok)
	}
	if _, ok = repoFromSource("repos.ripple.com"); ok {
		t.Fatal("hostname should not parse")
	}
}

func TestParseRawGitHub(t *testing.T) {
	repo, tag, ok := parseRawGitHub("https://raw.githubusercontent.com/bitcoin/bitcoin/v28.1/share/examples/bitcoin.conf")
	if !ok || repo != "bitcoin/bitcoin" || tag != "v28.1" {
		t.Fatalf("got %q %q %v", repo, tag, ok)
	}
	if _, _, ok = parseRawGitHub("https://raw.githubusercontent.com/stellar/go-stellar-sdk/master/x.cfg"); ok {
		t.Fatal("master should not parse")
	}
}

func TestWellKnownRepo(t *testing.T) {
	repo, prefix, ok := wellKnownRepo("stellar")
	if !ok || repo != "stellar/stellar-rpc" || prefix != "v" {
		t.Fatalf("stellar: %q %q %v", repo, prefix, ok)
	}
}

func TestHTTPFingerprint(t *testing.T) {
	got := httpFingerprint(`"a955094a3abaa142f4097ea80380cd23"`, "Sat, 20 Jun 2026 09:04:08 GMT")
	if got != "2026-06-20-a955094a" {
		t.Fatalf("got %q", got)
	}
	weak := httpFingerprint(`W/"866bcd5951b93751ac1572248bdaae20"`, "Fri, 19 Jun 2026 10:40:41 GMT")
	if weak != "2026-06-19-866bcd59" {
		t.Fatalf("weak %q", weak)
	}
}

func TestRewriteURL(t *testing.T) {
	got := rewriteURL(
		"https://github.com/sigp/lighthouse/releases/download/v8.2.1/lighthouse-v8.2.1-x86_64-unknown-linux-gnu.tar.gz",
		"v8.2.1",
		"8.2.1",
		"v8.3.0",
	)
	if got != "https://github.com/sigp/lighthouse/releases/download/v8.3.0/lighthouse-v8.3.0-x86_64-unknown-linux-gnu.tar.gz" {
		t.Fatalf("got %s", got)
	}
	btc := rewriteURL(
		"https://bitcoincore.org/bin/bitcoin-core-28.1/bitcoin-28.1-x86_64-linux-gnu.tar.gz",
		"v28.1",
		"28.1",
		"v29.4",
	)
	if btc != "https://bitcoincore.org/bin/bitcoin-core-29.4/bitcoin-29.4-x86_64-linux-gnu.tar.gz" {
		t.Fatalf("bitcoin: %s", btc)
	}
	tronSame := "https://raw.githubusercontent.com/tronprotocol/java-tron/GreatVoyage-v4.8.2.1/framework/src/main/resources/config.conf"
	if got := rewriteURL(tronSame, "GreatVoyage-v4.8.2.1", "4.8.2.1", "GreatVoyage-v4.8.2.1"); got != tronSame {
		t.Fatalf("tron same: %s", got)
	}
	tronNext := rewriteURL(tronSame, "GreatVoyage-v4.8.2.1", "4.8.2.1", "GreatVoyage-v4.8.3.0")
	wantTron := "https://raw.githubusercontent.com/tronprotocol/java-tron/GreatVoyage-v4.8.3.0/framework/src/main/resources/config.conf"
	if tronNext != wantTron {
		t.Fatalf("tron next: %s", tronNext)
	}
	broken := "https://raw.githubusercontent.com/tronprotocol/java-tron/GreatVoyage-vGreatVoyage-vGreatVoyage-v4.8.2.1/framework/src/main/resources/config.conf"
	if got := collapseNestedReleaseTag(broken); got != tronSame {
		t.Fatalf("collapse: %s", got)
	}
}
