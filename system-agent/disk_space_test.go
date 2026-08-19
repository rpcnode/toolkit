package main

import "testing"

func TestWholeDiskFromBase(t *testing.T) {
	cases := map[string]string{
		"nvme2n1":   "nvme2n1",
		"nvme2n1p1": "nvme2n1",
		"nvme0n1p3": "nvme0n1",
		"sda":       "sda",
		"sda1":      "sda",
		"vda2":      "vda",
		"mmcblk0p1": "mmcblk0",
		"loop0":     "",
		"dm-0":      "",
	}
	for in, want := range cases {
		if got := wholeDiskFromBase(in); got != want {
			t.Fatalf("%s: got %q want %q", in, got, want)
		}
	}
}

func TestSkipSpaceMount(t *testing.T) {
	if !skipSpaceMount("/boot", "ext4") || !skipSpaceMount("/boot/efi", "vfat") {
		t.Fatal("boot")
	}
	if !skipSpaceMount("/run/foo", "tmpfs") || !skipSpaceMount("/", "tmpfs") {
		t.Fatal("tmpfs")
	}
	if skipSpaceMount("/", "ext4") || skipSpaceMount("/data/nvme3", "ext4") {
		t.Fatal("keep data mounts")
	}
}

func TestDiskSpaceFromMounts_PicksLargestOnDisk(t *testing.T) {
	mounts := `
/dev/nvme0n1p2 /boot ext4 rw 0 0
/dev/mapper/vg0-root / ext4 rw 0 0
/dev/nvme2n1p1 /data/nvme2 ext4 rw 0 0
/dev/nvme3n1p1 /data/nvme3 ext4 rw 0 0
`
	stat := func(path string) (uint64, uint64, bool) {
		switch path {
		case "/":
			return 500 << 30, 400 << 30, true
		case "/data/nvme2":
			return 3500 << 30, 3400 << 30, true
		case "/data/nvme3":
			return 3500 << 30, 2900 << 30, true
		default:
			return 0, 0, false
		}
	}
	// vg0-root won't resolve without sysfs; nvme2/3 partitions should.
	got := diskSpaceFromMounts(mounts, stat)
	if _, ok := got["nvme2n1"]; !ok {
		t.Fatalf("missing nvme2n1: %+v", got)
	}
	if got["nvme3n1"].Mount != "/data/nvme3" {
		t.Fatalf("nvme3: %+v", got["nvme3n1"])
	}
	if got["nvme3n1"].FreeGB < 2800 || got["nvme3n1"].FreeGB > 3000 {
		t.Fatalf("free %v", got["nvme3n1"].FreeGB)
	}
	if _, ok := got["nvme0n1"]; ok {
		t.Fatal("/boot skipped and LVM not resolved in this test")
	}
}
