package main

import "testing"

func TestIsWholeDiskName(t *testing.T) {
	keep := []string{"nvme0n1", "nvme2n1", "sda", "mmcblk0"}
	skip := []string{"nvme0n1p1", "sda1", "loop0", "dm-0", "md0", "mmcblk0p1"}
	for _, n := range keep {
		if !isWholeDiskName(n) {
			t.Fatalf("expected keep %s", n)
		}
	}
	for _, n := range skip {
		if isWholeDiskName(n) {
			t.Fatalf("expected skip %s", n)
		}
	}
}

func TestDiskRatesFromDelta(t *testing.T) {
	prev := []diskDevSnap{{Name: "nvme0n1", Reads: 0, Writes: 0, RSect: 0, WSect: 0, IOMs: 0}}
	cur := []diskDevSnap{{Name: "nvme0n1", Reads: 200, Writes: 50, RSect: 4000, WSect: 1000, IOMs: 200}}
	got := diskRatesFromDelta(prev, cur, 2)
	if got.ReadIOPS != 100 || got.WriteIOPS != 25 {
		t.Fatalf("iops r=%v w=%v", got.ReadIOPS, got.WriteIOPS)
	}
	if got.UtilPct < 9.9 || got.UtilPct > 10.1 {
		t.Fatalf("util %v", got.UtilPct)
	}
}
