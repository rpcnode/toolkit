package main

import (
	"path/filepath"
	"strings"
	"testing"
)

func TestTonCelldbCacheBytes(t *testing.T) {
	if tonCelldbCacheBytes(8) != 1<<30 {
		t.Fatal("8 GiB → 1G")
	}
	if tonCelldbCacheBytes(24) != 2<<30 {
		t.Fatal("24 GiB → 2G")
	}
	if tonCelldbCacheBytes(48) != 4<<30 {
		t.Fatal("48 GiB → 4G")
	}
	if tonCelldbCacheBytes(128) != 8<<30 {
		t.Fatal("128 GiB → 8G")
	}
}

func TestHealTonValidatorExecStart(t *testing.T) {
	src := `[Service]
ExecStart=/usr/bin/ton/validator-engine/validator-engine --threads 16 --daemonize --db /var/ton-work/db/ --celldb-preload-all --celldb-cache-size=64424509440 --celldb-direct-io --fast-state-serializer
`
	out, changed := healTonValidatorExecStart(src, 2<<30)
	if !changed {
		t.Fatal("want change")
	}
	if !strings.Contains(out, "--celldb-cache-size=2147483648") {
		t.Fatalf("want 2G cache:\n%s", out)
	}
	for _, bad := range []string{"--celldb-preload-all", "--fast-state-serializer", "--celldb-direct-io", "64424509440"} {
		if strings.Contains(out, bad) {
			t.Fatalf("must strip %s:\n%s", bad, out)
		}
	}
	again, changed := healTonValidatorExecStart(out, 2<<30)
	if changed || again != out {
		t.Fatal("second heal must be no-op")
	}
}

func TestHealTonValidatorExecStart_Force1G(t *testing.T) {
	src := `[Service]
ExecStart=/usr/bin/ton/validator-engine/validator-engine --db /var/ton-work/db/ --celldb-cache-size=8589934592
`
	out, changed := healTonValidatorExecStart(src, 1<<30)
	if !changed || !strings.Contains(out, "--celldb-cache-size=1073741824") {
		t.Fatalf("want 1G after crash-loop cap:\n%s", out)
	}
}

func TestTonOOMCapSticky(t *testing.T) {
	cfg := Config{Network: "ton", Env: "mainnet", StateFile: filepath.Join(t.TempDir(), "agent-state.json")}
	if tonOOMCapSticky(cfg) {
		t.Fatal("empty must not force 1G")
	}
	markTonOOMCap(cfg)
	if !tonOOMCapSticky(cfg) {
		t.Fatal("want sticky 1G after OOM")
	}
	if tonCelldbHealCache(cfg) != 1<<30 {
		t.Fatal("sticky must keep 1G")
	}
}

func TestTonCatchupNotSyncedWithoutSeqno(t *testing.T) {
	if tonCatchupHonest(1, 0, false) {
		t.Fatal("oos=1 seqno=0 is not tip")
	}
	if !tonCatchupHonest(2, 85643642, false) {
		t.Fatal("oos=2 with seqno is tip")
	}
	if tonCatchupHonest(2, 100, true) {
		t.Fatal("OOM is not tip")
	}
}
