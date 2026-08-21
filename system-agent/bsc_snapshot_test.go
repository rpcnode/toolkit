package main

import (
	"strings"
	"testing"
)

func TestParseBSCLatestSnapshotName(t *testing.T) {
	readme := `
| Full Snapshot | [mainnet-geth-pbss-20260805](dist/mainnet-geth-pbss-20260805.csv) | **~6.6TB** |
| Pruned Snapshot | [mainnet-geth-pbss-20260805-pruneancient](dist/mainnet-geth-pbss-20260805-pruneancient.csv) |
| Full Snapshot | [testnet-geth-pbss-20260407](dist/testnet-geth-pbss-20260407.csv) |
`
	if got := parseBSCLatestSnapshotName(readme, "mainnet"); got != "mainnet-geth-pbss-20260805" {
		t.Fatalf("mainnet=%q", got)
	}
	if got := parseBSCLatestSnapshotName(readme, "testnet"); got != "testnet-geth-pbss-20260407" {
		t.Fatalf("testnet=%q", got)
	}
}

func TestParseBSCSnapshotProgress_Aria2CommaGiB(t *testing.T) {
	line := "[#842252 1,347GiB/1,742GiB(77%) CN:14 DL:359MiB ETA:18m45s] · FILE: [SWAP]/bsc/mainnet/geth/snapshots/mainnet-geth-pbss-base-113615622.tar.lz4"
	p, ok := parseBSCSnapshotProgress(line, "")
	if !ok || p < 76 || p > 78 {
		t.Fatalf("aria2 comma GiB pct=%v ok=%v", p, ok)
	}
	d := bscAria2ProgressDetail(line)
	if !strings.Contains(d, "1347GiB") || !strings.Contains(d, "77%") {
		t.Fatalf("detail=%q", d)
	}
}

func TestParseBSCSnapshotProgress_LargeFileBeatsEqualParts(t *testing.T) {
	csv := "filename,url,md5,size\n" + strings.Repeat("x.tar.lz4,http://x,1,1\n", 10)
	line := "[#842252 1,347GiB/1,742GiB(77%) CN:14 DL:359MiB ETA:18m45s]"
	p, ok := parseBSCSnapshotProgress(line, csv)
	if !ok || p < 76 || p > 78 {
		t.Fatalf("large file should win pct=%v ok=%v", p, ok)
	}
}

func TestParseBSCSnapshotProgress_Aria2AndParts(t *testing.T) {
	csv := "filename,url,md5,size\na.tar.lz4,http://x,1,1\nb.tar.lz4,http://y,2,2\n"
	log := "[#ab12 1.0GiB/2.0GiB(40%)] CN:8\nDownloading a.tar.lz4 from http://x\n"
	p, ok := parseBSCSnapshotProgress(log, csv)
	if !ok || p < 15 || p > 45 {
		t.Fatalf("in-progress pct=%v ok=%v", p, ok)
	}
	log2 := "Download complete: a.tar.lz4\nExtraction complete and removed: a.tar.lz4\n[#cd34 100MiB/200MiB(10%)]\n"
	p, ok = parseBSCSnapshotProgress(log2, csv)
	if !ok || p < 50 || p > 70 {
		t.Fatalf("one part done pct=%v ok=%v", p, ok)
	}
	p, ok = parseBSCSnapshotProgress("DONE mainnet-geth-pbss-20260805\n", csv)
	if !ok || p < 99 {
		t.Fatalf("done pct=%v ok=%v", p, ok)
	}
}

func TestRenderBSCSnapshotHealScript_Pruned(t *testing.T) {
	s := renderBSCSnapshotHealScript(
		"mainnet", "/data/bsc/mainnet", "/data/bsc/mainnet/snapshots", "/opt/bsc/mainnet",
		"/data/bsc/mainnet/.snapshot-ready", "/data/bsc/mainnet/.snapshot-state.json",
		"/var/log/bsc/mainnet-snapshot.log", "pruned", "mainnet-geth-pbss",
	)
	for _, need := range []string{
		"fetch-snapshot.sh", " -p", "--auto-delete", "rm -rf \"$DATA/geth\"",
		"geth/chaindata/ancient/chain", "writing marker",
		"SNAPSHOT_DIAG", "snapdiag", ".snapshot-keep", "pin_keep",
	} {
		if !strings.Contains(s, need) {
			t.Fatalf("heal script missing %q", need)
		}
	}
}
