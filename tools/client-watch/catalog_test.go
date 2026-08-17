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

func TestRewriteURL(t *testing.T) {
	got := rewriteURL(
		"https://github.com/sigp/lighthouse/releases/download/v8.2.1/lighthouse-v8.2.1-x86_64-unknown-linux-gnu.tar.gz",
		"v8.2.1",
		"v8.3.0",
	)
	if got != "https://github.com/sigp/lighthouse/releases/download/v8.3.0/lighthouse-v8.3.0-x86_64-unknown-linux-gnu.tar.gz" {
		t.Fatalf("got %s", got)
	}
}
