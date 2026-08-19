package main

import (
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"unicode"
)

type diskSpaceInfo struct {
	FreeGB  float64
	TotalGB float64
	UsedPct float64
	Mount   string
}

func attachDiskSpace(devs []diskDevRate) {
	if len(devs) == 0 {
		return
	}
	spaces := readDiskSpaceByDisk()
	for i := range devs {
		s, ok := spaces[devs[i].Name]
		if !ok {
			continue
		}
		devs[i].FreeGB = s.FreeGB
		devs[i].TotalGB = s.TotalGB
		devs[i].UsedPct = s.UsedPct
		devs[i].Mount = s.Mount
	}
}

func readDiskSpaceByDisk() map[string]diskSpaceInfo {
	b, err := readProcPathHost("mounts")
	if err != nil {
		return nil
	}
	return diskSpaceFromMounts(string(b), statfsBytes)
}

func diskSpaceFromMounts(mounts string, statfn func(string) (total, avail uint64, ok bool)) map[string]diskSpaceInfo {
	out := map[string]diskSpaceInfo{}
	for _, line := range strings.Split(mounts, "\n") {
		fields := strings.Fields(line)
		if len(fields) < 3 {
			continue
		}
		src, mp, fstype := fields[0], fields[1], fields[2]
		if skipSpaceMount(mp, fstype) {
			continue
		}
		disk := wholeDiskForDev(src)
		if disk == "" {
			continue
		}
		total, avail, ok := statfn(mp)
		if !ok || total == 0 {
			continue
		}
		totalGB := float64(total) / (1024 * 1024 * 1024)
		freeGB := float64(avail) / (1024 * 1024 * 1024)
		usedPct := 0.0
		if total > 0 && total >= avail {
			usedPct = float64(total-avail) * 100 / float64(total)
		}
		prev, exists := out[disk]
		if exists && prev.TotalGB >= totalGB {
			continue
		}
		out[disk] = diskSpaceInfo{
			FreeGB: freeGB, TotalGB: totalGB, UsedPct: usedPct, Mount: mp,
		}
	}
	return out
}

func skipSpaceMount(mp, fstype string) bool {
	fs := strings.ToLower(fstype)
	for _, p := range []string{
		"tmpfs", "devtmpfs", "proc", "sysfs", "cgroup", "cgroup2", "overlay",
		"squashfs", "autofs", "fuse", "nfs", "tracefs", "debugfs", "securityfs",
		"ramfs", "efivarfs", "bpf", "nsfs",
	} {
		if fs == p || strings.HasPrefix(fs, p+".") {
			return true
		}
	}
	n := strings.TrimSpace(mp)
	if n == "" || n == "/boot" || strings.HasPrefix(n, "/boot/") {
		return true
	}
	for _, p := range []string{"/snap", "/run", "/sys", "/proc", "/dev", "/var/lib/docker"} {
		if n == p || strings.HasPrefix(n, p+"/") {
			return true
		}
	}
	return false
}

func wholeDiskForDev(source string) string {
	source = strings.TrimSpace(source)
	if source == "" || source == "none" {
		return ""
	}
	base := filepath.Base(source)
	if w := wholeDiskFromBase(base); w != "" {
		return w
	}
	if strings.HasPrefix(source, "/dev/") {
		if resolved, err := filepath.EvalSymlinks(source); err == nil {
			base = filepath.Base(resolved)
			if w := wholeDiskFromBase(base); w != "" {
				return w
			}
			if w := wholeDiskViaSlaves(base); w != "" {
				return w
			}
		}
	}
	return wholeDiskViaSlaves(base)
}

func wholeDiskFromBase(base string) string {
	base = strings.ToLower(strings.TrimSpace(base))
	if isWholeDiskName(base) {
		return base
	}
	if strings.HasPrefix(base, "nvme") {
		i := strings.LastIndex(base, "p")
		if i > 0 && diskDigitsOnly(base[i+1:]) {
			parent := base[:i]
			if isWholeDiskName(parent) {
				return parent
			}
		}
	}
	if strings.HasPrefix(base, "mmcblk") {
		i := strings.Index(base, "p")
		if i > 0 && isWholeDiskName(base[:i]) {
			return base[:i]
		}
	}
	for _, p := range []string{"sd", "vd", "hd", "xvd"} {
		if !strings.HasPrefix(base, p) || len(base) <= len(p) {
			continue
		}
		rest := base[len(p):]
		i := 0
		for i < len(rest) && unicode.IsLetter(rune(rest[i])) {
			i++
		}
		parent := p + rest[:i]
		if isWholeDiskName(parent) {
			return parent
		}
	}
	return ""
}

func wholeDiskViaSlaves(dev string) string {
	dev = strings.TrimSpace(dev)
	if dev == "" {
		return ""
	}
	for _, dir := range []string{
		"/sys/block/" + dev + "/slaves",
		"/sys/class/block/" + dev + "/slaves",
	} {
		entries, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		for _, e := range entries {
			if w := wholeDiskFromBase(e.Name()); w != "" {
				return w
			}
		}
	}
	return ""
}

func statfsBytes(path string) (total, avail uint64, ok bool) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(path, &st); err != nil {
		return 0, 0, false
	}
	bsize := uint64(st.Bsize)
	if bsize == 0 {
		return 0, 0, false
	}
	return uint64(st.Blocks) * bsize, uint64(st.Bavail) * bsize, true
}
