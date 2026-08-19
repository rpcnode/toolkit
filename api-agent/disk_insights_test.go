package main

import (
	"strings"
	"testing"
)

func TestAnalyzeHostDisks_RawNvmePlusLvmOS(t *testing.T) {
	disks := []HostDisk{
		{Name: "nvme0n1", Type: "disk", SizeHuman: "477G", Tran: "nvme", Preferred: true},
		{Name: "nvme1n1", Type: "disk", SizeHuman: "477G", Tran: "nvme", Preferred: true},
		{Name: "nvme2n1", Type: "disk", SizeHuman: "3.5T", Tran: "nvme", Preferred: true},
		{Name: "nvme3n1", Type: "disk", SizeHuman: "3.5T", Tran: "nvme", Preferred: true},
	}
	mounts := []HostMount{
		{Target: "/", Source: "/dev/mapper/vg0-root", Kind: "lvm", Layer: "vg0-root", SizeBytes: 471e9, AvailBytes: 400e9},
		{Target: "/data/nvme2", Source: "/dev/nvme2n1p1", Kind: "raw_nvme", DiskName: "nvme2n1", Tran: "nvme", Preferred: true, AvailBytes: 3e12},
		{Target: "/data/nvme3", Source: "/dev/nvme3n1p1", Kind: "raw_nvme", DiskName: "nvme3n1", Tran: "nvme", Preferred: true, AvailBytes: 3e12},
	}
	unused := []HostDisk{disks[1]}
	got, summary := analyzeHostDisks("ton", disks, mounts, unused)
	codes := map[string]HostDiskInsight{}
	for _, i := range got {
		codes[i.Code] = i
	}
	if codes["raw_nvme"].Level != "good" {
		t.Fatalf("want raw_nvme good, got %+v", got)
	}
	if codes["recommend"].Level != "good" {
		t.Fatalf("want recommend, got %+v", got)
	}
	if codes["lvm_os"].Level != "info" {
		t.Fatalf("want lvm_os, got %+v", got)
	}
	if codes["unused_nvme"].Level != "info" {
		t.Fatalf("want unused_nvme, got %+v", got)
	}
	if codes["md_raid"].Code != "" {
		t.Fatalf("did not expect md: %+v", got)
	}
	if summary == "" || !containsAll(summary, "raw NVMe", "OS LVM", "unused") {
		t.Fatalf("summary=%q", summary)
	}
}

func TestAnalyzeHostDisks_MdRaidData(t *testing.T) {
	mounts := []HostMount{
		{Target: "/", Source: "/dev/nvme0n1p2", Kind: "raw_nvme", DiskName: "nvme0n1"},
		{Target: "/data", Source: "/dev/md2", Kind: "md_raid", RaidLevel: "raid0", Layer: "md2", Preferred: true, AvailBytes: 4e12},
	}
	got, _ := analyzeHostDisks("ton", nil, mounts, nil)
	var raid HostDiskInsight
	for _, i := range got {
		if i.Code == "md_raid" {
			raid = i
		}
	}
	if raid.Level != "warn" {
		t.Fatalf("want md warn, got %+v", got)
	}
	if raid.Detail == "" || !containsAll(raid.Detail, "md2", "RAID0") {
		t.Fatalf("detail=%q", raid.Detail)
	}
}

func TestClassifyBlockLayer_MdAndLvmAndNvme(t *testing.T) {
	flat := []HostDisk{
		{Name: "md2", Path: "/dev/md2", Type: "raid0"},
		{Name: "vg0-root", Path: "/dev/mapper/vg0-root", Type: "lvm", Parent: "nvme0n1p3"},
		{Name: "nvme2n1p1", Path: "/dev/nvme2n1p1", Type: "part", Parent: "nvme2n1", Tran: "nvme"},
		{Name: "nvme2n1", Path: "/dev/nvme2n1", Type: "disk", Tran: "nvme"},
	}
	kind, raid, layer := classifyBlockLayer("/dev/md2", HostMount{Source: "/dev/md2"}, flat)
	if kind != "md_raid" || raid != "raid0" || layer != "md2" {
		t.Fatalf("md: kind=%s raid=%s layer=%s", kind, raid, layer)
	}
	kind, _, layer = classifyBlockLayer("/dev/mapper/vg0-root", HostMount{Source: "/dev/mapper/vg0-root"}, flat)
	if kind != "lvm" || layer != "vg0-root" {
		t.Fatalf("lvm: kind=%s layer=%s", kind, layer)
	}
	kind, _, _ = classifyBlockLayer("/dev/nvme2n1p1", HostMount{Source: "/dev/nvme2n1p1", Tran: "nvme"}, flat)
	if kind != "raw_nvme" {
		t.Fatalf("nvme kind=%s", kind)
	}
}

func TestUnusedHostDisks_EmptyNvme(t *testing.T) {
	disks := []HostDisk{
		{Name: "nvme0n1", Type: "disk"},
		{Name: "nvme1n1", Type: "disk"},
	}
	flat := []HostDisk{
		{Name: "nvme0n1", Type: "disk"},
		{Name: "nvme0n1p1", Type: "part", Parent: "nvme0n1", Mountpoint: "/"},
		{Name: "nvme1n1", Type: "disk"},
	}
	mounts := []HostMount{{Target: "/", DiskName: "nvme0n1"}}
	got := unusedHostDisks(disks, mounts, flat)
	if len(got) != 1 || got[0].Name != "nvme1n1" {
		t.Fatalf("unused=%+v", got)
	}
}

func TestUnusedHostDisks_RaidMemberNotUnused(t *testing.T) {
	disks := []HostDisk{
		{Name: "nvme1n1", Type: "disk"},
		{Name: "md2", Type: "raid0"},
	}
	flat := []HostDisk{
		{Name: "nvme1n1", Type: "disk"},
		{Name: "nvme1n1p1", Type: "part", Parent: "nvme1n1", Fstype: "linux_raid_member"},
		{Name: "md2", Type: "raid0", Mountpoint: "/data"},
	}
	got := unusedHostDisks(disks, nil, flat)
	if len(got) != 0 {
		t.Fatalf("raid member must not be unused: %+v", got)
	}
}

func TestRecommendSolanaDiskLayout_PrefersRawOverMd(t *testing.T) {
	mounts := []HostMount{
		{Target: "/data", AvailBytes: 4e12, Preferred: true, DiskName: "md2", Kind: "md_raid", Source: "/dev/md2"},
		{Target: "/data/nvme2", AvailBytes: 3e12, Preferred: true, DiskName: "nvme2n1", Kind: "raw_nvme", Tran: "nvme"},
	}
	plan := recommendSolanaDiskLayout(mounts, "mainnet")
	if plan.LedgerMount != "/data/nvme2" {
		t.Fatalf("must prefer raw NVMe over md /data, got %s (%s)", plan.LedgerMount, plan.Strategy)
	}
}

func TestMountQuality_RawBeatsMd(t *testing.T) {
	raw := HostMount{Target: "/data/nvme2", Kind: "raw_nvme", Preferred: true}
	md := HostMount{Target: "/data", Kind: "md_raid", Preferred: true, Source: "/dev/md2"}
	if mountQuality(raw) <= mountQuality(md) {
		t.Fatalf("raw=%d md=%d", mountQuality(raw), mountQuality(md))
	}
}

func containsAll(s string, parts ...string) bool {
	for _, p := range parts {
		if !strings.Contains(s, p) {
			return false
		}
	}
	return true
}
