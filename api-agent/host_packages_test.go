package main

import (
	"strings"
	"testing"
)

func TestHostPackagesForNetwork_TronNeedsJava8(t *testing.T) {
	pkgs := hostPackagesForNetwork("tron")
	joined := strings.Join(pkgs, " ")
	if !strings.Contains(joined, "openjdk-8-jre-headless") {
		t.Fatalf("tron must apt-install Java 8, got %v", pkgs)
	}
	for _, need := range []string{"wget", "tar", "ca-certificates"} {
		if !strings.Contains(joined, need) {
			t.Fatalf("tron snapshot needs %s, got %v", need, pkgs)
		}
	}
}

func TestHostPackagesForNetwork_SolanaNeedsBzip2(t *testing.T) {
	pkgs := hostPackagesForNetwork("solana")
	if !strings.Contains(strings.Join(pkgs, " "), "bzip2") {
		t.Fatalf("solana Agave tarball is .bz2, got %v", pkgs)
	}
}

func TestHostPackagesForNetwork_BSCNeedsAria2Lz4(t *testing.T) {
	joined := strings.Join(hostPackagesForNetwork("bsc"), " ")
	for _, need := range []string{"aria2", "lz4"} {
		if !strings.Contains(joined, need) {
			t.Fatalf("bsc official snapshot needs %s, got %s", need, joined)
		}
	}
}

func TestHostPackagesForNetwork_UnknownStillGetsCommon(t *testing.T) {
	pkgs := hostPackagesForNetwork("bitcoin")
	if len(pkgs) < 3 {
		t.Fatalf("common packages missing: %v", pkgs)
	}
	if strings.Contains(strings.Join(pkgs, " "), "openjdk-8") {
		t.Fatalf("bitcoin must not pull Java 8: %v", pkgs)
	}
}

func TestUniqStrings(t *testing.T) {
	got := uniqStrings([]string{"wget", "wget", " tar ", ""})
	if len(got) != 2 || got[0] != "wget" || got[1] != "tar" {
		t.Fatalf("uniq: %v", got)
	}
}

func TestRenderTronSnapshotScript_WgetAndMarker(t *testing.T) {
	s := renderTronSnapshotScript(
		"https://snapshots.nileex.io/backup.tgz",
		"/data/tron/nile",
		"/data/tron/nile/.snapshot-ready",
		"/var/log/tron/nile-snapshot.log",
	)
	for _, need := range []string{
		"wget -O -",
		"tar -xzf -",
		"chown -R nodeop:nodeop",
		"/data/tron/nile/.snapshot-ready",
		"https://snapshots.nileex.io/backup.tgz",
	} {
		if !strings.Contains(s, need) {
			t.Fatalf("script missing %q:\n%s", need, s)
		}
	}
	if strings.Contains(s, "tronctl") || strings.Contains(s, "rpcnodectl") {
		t.Fatal("CDN hosts have no host CLI — script must be self-contained")
	}
}

func TestRenderTronSnapshotScript_EmptyURLSkips(t *testing.T) {
	s := renderTronSnapshotScript("", "/data/tron/shasta", "/data/tron/shasta/.snapshot-ready", "/tmp/x.log")
	if !strings.Contains(s, "exit 0") {
		t.Fatalf("empty URL must skip, not fail:\n%s", s)
	}
}
