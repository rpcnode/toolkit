package main

import (
	"strings"
	"testing"
)

func TestParseAptLogFindingsReleaseFile(t *testing.T) {
	text := "Ign:1 https://packagecloud.io/ookla/speedtest-cli/ubuntu noble InRelease\n" +
		"Err:2 https://packagecloud.io/ookla/speedtest-cli/ubuntu noble Release\n" +
		"  404  Not Found\n" +
		"E: The repository 'https://packagecloud.io/ookla/speedtest-cli/ubuntu noble Release' does not have a Release file.\n"
	got := parseAptLogFindings(text)
	if len(got) == 0 || got[0].Code != "apt_release_missing" {
		t.Fatalf("want apt_release_missing, got %+v", got)
	}
	if !strings.Contains(got[0].Detail, "ookla/speedtest-cli") {
		t.Fatalf("detail should keep the repo URL, got %q", got[0].Detail)
	}
}

func TestParseTonSetrlimitFinding(t *testing.T) {
	text := "[validator-engine.cpp:5669]\t[PosixError : Operation not permitted : 1 : failed setrlimit()]"
	got := parseTonBootstrapFindings(text)
	if len(got) == 0 || got[0].Code != "ton_setrlimit" {
		t.Fatalf("want ton_setrlimit, got %+v", got)
	}
}

func TestParseTonBootstrapFindingsReleaseVsLock(t *testing.T) {
	text := "install.sh attempt=3 exit=100\n" +
		"E: The repository 'https://packagecloud.io/ookla/speedtest-cli/ubuntu noble Release' does not have a Release file.\n" +
		"apt/dpkg busy — retry in 60s\n"
	got := parseTonBootstrapFindings(text)
	codes := map[string]bool{}
	for _, f := range got {
		codes[f.Code] = true
	}
	if !codes["ton_apt_release"] || !codes["ton_install_exit"] {
		t.Fatalf("want ton_apt_release + ton_install_exit, got %+v", got)
	}
	for _, f := range got {
		if f.Code == "ton_install_exit" && f.Severity != "error" {
			t.Fatalf("exit 100 + Release file must be error, got %s", f.Severity)
		}
	}
}

func TestParseTonBootstrapSignalLines(t *testing.T) {
	text := "Hit:1 http://archive.ubuntu.com noble InRelease\n" +
		"install.sh attempt=4 exit=100\n" +
		"12.3 GiB/40 GiB (31%)\n" +
		"noise compile line\n" +
		"bootstrap marker written\n"
	got := parseTonBootstrapSignalLines(text, 10)
	if len(got) != 3 {
		t.Fatalf("want 3 signal lines, got %#v", got)
	}
}

func TestAptSourceLooksLeftover(t *testing.T) {
	if !aptSourceLooksLeftover("ookla_speedtest-cli.list", "deb https://packagecloud.io/ookla/speedtest-cli/ubuntu noble main") {
		t.Fatal("ookla list")
	}
	if aptSourceLooksLeftover("official-ubuntu.list", "deb http://archive.ubuntu.com/ubuntu noble main") {
		t.Fatal("stock ubuntu must not be leftover")
	}
}

func TestParseXRPLDebugFindings(t *testing.T) {
	text := "Ledger master: Invalid validator list publisher key: 0\n" +
		"SHAMapStore:ERR state db error:\n"
	got := parseXRPLDebugFindings(text)
	codes := map[string]bool{}
	for _, f := range got {
		codes[f.Code] = true
	}
	if !codes["xrpl_unl_key0"] || !codes["xrpl_state_db"] {
		t.Fatalf("want UNL + state db, got %+v", got)
	}
}

func TestParseTronAndCoreFindings(t *testing.T) {
	tron := parseTronDebugFindings("java.io.IOException: LOCK: Permission denied\n")
	if len(tron) == 0 || tron[0].Code != "tron_leveldb_lock" {
		t.Fatalf("tron lock: %+v", tron)
	}
	core := parseCoreDebugFindings("bitcoin", `Error: specified config file "/etc/bitcoin/mainnet/bitcoin.conf" could not be opened.`)
	if len(core) == 0 || core[0].Code != "core_conf_open" {
		t.Fatalf("core conf: %+v", core)
	}
}

func TestParseGenericLogFindingsOOM(t *testing.T) {
	got := parseGenericLogFindings("solana", "kernel: Out of memory: Killed process 99 (agave-validator)\n")
	if len(got) == 0 || got[0].Code != "oom" {
		t.Fatalf("want oom, got %+v", got)
	}
}

func TestDebugLogSpecsCoverCatalog(t *testing.T) {
	for _, net := range []string{
		"tron", "bitcoin", "doge", "ltc", "dash", "bch", "zcash",
		"ethereum", "bsc", "etc", "arb", "optimism", "base", "robinhood",
		"hyperliquid", "solana", "xrpl", "stellar", "cardano", "ton",
		"sui", "aptos", "avalanche",
	} {
		specs := debugLogSpecs(net, "mainnet")
		if len(specs) < 2 {
			t.Fatalf("%s: want host + network logs, got %d", net, len(specs))
		}
		if debugProcPattern(net) == "" {
			t.Fatalf("%s empty proc pattern", net)
		}
	}
}

func TestParseEVMDebugFindings_IgnoresSnapshotAncientListing(t *testing.T) {
	text := "Extraction complete and removed: mainnet-geth-pbss-base-113615622.tar.lz4\n" +
		"server/data-seed/geth/chaindata/ancient/chain/headers.meta\n" +
		"[#94ae1a 166GiB/1,742GiB(9%) CN:14 DL:341MiB ETA:1h18m52s]\n"
	if got := parseEVMDebugFindings("bsc", text); len(got) != 0 {
		t.Fatalf("snapshot journal must not be evm_datadir, got %+v", got)
	}
	real := "Fatal: Failed to open database: datadir already used by another process"
	got := parseEVMDebugFindings("bsc", real)
	if len(got) == 0 || got[0].Code != "evm_datadir" {
		t.Fatalf("want evm_datadir, got %+v", got)
	}
}
