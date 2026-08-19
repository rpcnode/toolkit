package main

import "testing"

func TestIsWholeDiskName(t *testing.T) {
	keep := []string{"nvme0n1", "nvme2n1", "sda", "sdb", "vda", "xvda", "mmcblk0"}
	skip := []string{
		"nvme0n1p1", "nvme0c0n1", "sda1", "sdb2", "vda1", "mmcblk0p1",
		"loop0", "dm-0", "md0", "md127", "zram0", "sr0", "nbd0",
	}
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

func TestParseDiskstats_SkipsPartitionsAndMd(t *testing.T) {
	sample := `
 259       0 nvme0n1 100 0 200 1 50 0 80 2 0 10 0 0 0 0
 259       1 nvme0n1p1 10 0 20 1 5 0 8 1 0 2 0 0 0 0
 259       2 nvme2n1 400 0 800 3 200 0 400 4 0 30 0 0 0 0
   8       0 sda 1 0 2 0 1 0 2 0 0 1 0 0 0 0
   8       1 sda1 1 0 2 0 1 0 2 0 0 1 0 0 0 0
 253       0 dm-0 9 0 9 0 9 0 9 0 0 9 0 0 0 0
   9       0 md0 9 0 9 0 9 0 9 0 0 9 0 0 0 0
   7       0 loop0 1 0 1 0 0 0 0 0 0 0 0 0 0 0
`
	devs := parseDiskstats(sample)
	if len(devs) != 3 {
		t.Fatalf("devs=%d %+v", len(devs), devs)
	}
	if devs[0].Name != "nvme0n1" || devs[0].Reads != 100 || devs[0].Writes != 50 {
		t.Fatalf("nvme0n1: %+v", devs[0])
	}
	if devs[1].Name != "nvme2n1" || devs[1].IOMs != 30 {
		t.Fatalf("nvme2n1: %+v", devs[1])
	}
	if devs[2].Name != "sda" {
		t.Fatalf("sda: %+v", devs[2])
	}
}

func TestDiskRatesFromDelta(t *testing.T) {
	prev := []diskDevSnap{
		{Name: "nvme0n1", Reads: 100, Writes: 50, RSect: 200, WSect: 80, IOMs: 10},
		{Name: "nvme2n1", Reads: 400, Writes: 200, RSect: 800, WSect: 400, IOMs: 30},
	}
	cur := []diskDevSnap{
		{Name: "nvme0n1", Reads: 200, Writes: 150, RSect: 1200, WSect: 1080, IOMs: 110},
		{Name: "nvme2n1", Reads: 400, Writes: 200, RSect: 800, WSect: 400, IOMs: 30},
	}
	got := diskRatesFromDelta(prev, cur, 1)
	if got.ReadIOPS != 100 || got.WriteIOPS != 100 {
		t.Fatalf("iops r=%v w=%v", got.ReadIOPS, got.WriteIOPS)
	}
	// 1000 sectors * 512 / 1e6 = 0.512 MB/s
	if got.ReadMBs < 0.51 || got.ReadMBs > 0.52 {
		t.Fatalf("read MB/s %v", got.ReadMBs)
	}
	if got.UtilPct < 9.9 || got.UtilPct > 10.1 {
		t.Fatalf("util %v busy=%s", got.UtilPct, got.BusyName)
	}
	if got.BusyName != "nvme0n1" {
		t.Fatalf("busy %s", got.BusyName)
	}
	if len(got.Devices) != 2 {
		t.Fatalf("devices %d", len(got.Devices))
	}
	if got.Devices[0].Name != "nvme0n1" || got.Devices[0].ReadIOPS != 100 {
		t.Fatalf("dev0 %+v", got.Devices[0])
	}
	if got.Devices[1].Name != "nvme2n1" || got.Devices[1].ReadIOPS != 0 {
		t.Fatalf("dev1 %+v", got.Devices[1])
	}
}

func TestParseCgroupIOStat(t *testing.T) {
	s := `
8:0 rbytes=1000 wbytes=2000 rios=10 wios=20 dbytes=0 dios=0
259:3 rbytes=4000 wbytes=8000 rios=40 wios=80 dbytes=1 dios=1
`
	r, w, ri, wi := parseCgroupIOStat(s)
	if r != 5000 || w != 10000 || ri != 50 || wi != 100 {
		t.Fatalf("r=%d w=%d ri=%d wi=%d", r, w, ri, wi)
	}
}
