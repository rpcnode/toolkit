package main

import (
	"fmt"
	"path/filepath"
	"strings"
)

// HostDiskInsight — operator-facing conclusion from lsblk/findmnt (Install wizard).
type HostDiskInsight struct {
	Level  string `json:"level"` // good | warn | info
	Code   string `json:"code"`
	Title  string `json:"title"`
	Detail string `json:"detail"`
}

func annotateHostMounts(mounts []HostMount, flat []HostDisk) {
	for i := range mounts {
		kind, raid, layer := classifyBlockLayer(mounts[i].Source, mounts[i], flat)
		mounts[i].Kind = kind
		mounts[i].RaidLevel = raid
		mounts[i].Layer = layer
	}
}

func classifyBlockLayer(source string, m HostMount, flat []HostDisk) (kind, raid, layer string) {
	src := strings.TrimSpace(source)
	base := filepath.Base(strings.TrimPrefix(src, "/dev/"))
	if i := strings.Index(base, "["); i >= 0 { // /dev/md2[0]
		base = base[:i]
	}

	var match HostDisk
	found := false
	for _, d := range flat {
		if d.Name == base || filepath.Base(d.Path) == base {
			match = d
			found = true
			break
		}
	}
	if !found && m.DiskPath != "" {
		b := filepath.Base(m.DiskPath)
		for _, d := range flat {
			if d.Name == b || filepath.Base(d.Path) == b {
				match = d
				found = true
				break
			}
		}
	}

	if found {
		t := strings.ToLower(strings.TrimSpace(match.Type))
		if raid = raidTypeOf(match, flat); raid != "" {
			return "md_raid", raid, match.Name
		}
		if t == "lvm" || strings.HasPrefix(match.Name, "dm-") || strings.Contains(match.Path, "/mapper/") {
			return "lvm", "", firstNonEmpty(match.Name, base)
		}
		layer = firstNonEmpty(match.Name, base)
	}

	low := strings.ToLower(src)
	if strings.Contains(low, "/dev/md") || strings.HasPrefix(base, "md") {
		return "md_raid", raidTypeByName(base, flat), firstNonEmpty(base, "md")
	}
	if strings.Contains(low, "/dev/mapper/") || strings.HasPrefix(base, "dm-") {
		return "lvm", "", firstNonEmpty(base, "lvm")
	}
	if strings.Contains(low, "nvme") || strings.EqualFold(m.Tran, "nvme") {
		return "raw_nvme", "", firstNonEmpty(m.DiskName, base)
	}
	if m.Rota != nil && *m.Rota {
		return "hdd", "", firstNonEmpty(m.DiskName, base)
	}
	if m.Preferred {
		return "ssd", "", firstNonEmpty(m.DiskName, base)
	}
	return "other", "", firstNonEmpty(layer, base)
}

func raidTypeOf(d HostDisk, flat []HostDisk) string {
	t := strings.ToLower(strings.TrimSpace(d.Type))
	if strings.HasPrefix(t, "raid") {
		return t
	}
	cur := d
	for i := 0; i < 8 && cur.Parent != ""; i++ {
		found := false
		for _, x := range flat {
			if x.Name != cur.Parent {
				continue
			}
			xt := strings.ToLower(strings.TrimSpace(x.Type))
			if strings.HasPrefix(xt, "raid") {
				return xt
			}
			cur = x
			found = true
			break
		}
		if !found {
			break
		}
	}
	return raidTypeByName(d.Name, flat)
}

func raidTypeByName(name string, flat []HostDisk) string {
	name = strings.TrimSpace(name)
	if name == "" {
		return ""
	}
	for _, d := range flat {
		if d.Name == name || filepath.Base(d.Path) == name {
			t := strings.ToLower(strings.TrimSpace(d.Type))
			if strings.HasPrefix(t, "raid") {
				return t
			}
		}
	}
	return ""
}

func unusedHostDisks(disks []HostDisk, mounts []HostMount, flat []HostDisk) []HostDisk {
	used := map[string]bool{}
	for _, m := range mounts {
		if m.DiskName != "" {
			used[m.DiskName] = true
		}
	}
	for _, d := range flat {
		if strings.TrimSpace(d.Mountpoint) == "" {
			continue
		}
		used[d.Name] = true
		if root := rootDiskName(d, flat); root != "" {
			used[root] = true
		}
	}
	var out []HostDisk
	for _, d := range disks {
		t := strings.ToLower(strings.TrimSpace(d.Type))
		if t != "disk" && t != "nvme" {
			continue
		}
		if used[d.Name] {
			continue
		}
		if diskSubtreeBusy(d.Name, flat) {
			continue
		}
		out = append(out, d)
	}
	return out
}

func diskLayerBusy(d HostDisk) bool {
	if strings.TrimSpace(d.Mountpoint) != "" {
		return true
	}
	fs := strings.ToLower(strings.TrimSpace(d.Fstype))
	switch fs {
	case "linux_raid_member", "lvm2_member", "crypto_luks", "swap":
		return true
	}
	t := strings.ToLower(strings.TrimSpace(d.Type))
	return strings.HasPrefix(t, "raid") || t == "lvm" || t == "crypt"
}

func diskSubtreeBusy(name string, flat []HostDisk) bool {
	seen := map[string]bool{}
	var walk func(string) bool
	walk = func(n string) bool {
		if n == "" || seen[n] {
			return false
		}
		seen[n] = true
		for _, d := range flat {
			if d.Name == n && diskLayerBusy(d) {
				return true
			}
			if d.Parent == n && walk(d.Name) {
				return true
			}
		}
		return false
	}
	return walk(name)
}

func analyzeHostDisks(network string, disks []HostDisk, mounts []HostMount, unused []HostDisk) (insights []HostDiskInsight, summary string) {
	network = normalizeNetwork(network)
	if network == "" {
		network = "node"
	}

	var raidMounts []HostMount
	var rawMounts []HostMount
	var lvmRoot []HostMount
	var dataOnRoot bool
	usableData := 0
	for _, m := range mounts {
		if m.Kind == "md_raid" {
			raidMounts = append(raidMounts, m)
		}
		if m.Kind == "raw_nvme" && m.Target != "/" {
			rawMounts = append(rawMounts, m)
		}
		if m.Kind == "lvm" && (m.Target == "/" || strings.HasPrefix(m.Layer, "vg")) {
			if m.Target == "/" {
				lvmRoot = append(lvmRoot, m)
			}
		}
		if m.Target != "/" && (m.Kind == "raw_nvme" || m.Kind == "ssd" || (m.Preferred && m.Kind != "md_raid" && m.Kind != "lvm")) {
			usableData++
		}
		if m.Target == "/" && m.Kind != "raw_nvme" {
			dataOnRoot = true
		}
	}

	if len(rawMounts) > 0 {
		names := mountTargets(rawMounts)
		insights = append(insights, HostDiskInsight{
			Level: "good",
			Code:  "raw_nvme",
			Title: "Raw NVMe (no md / no LVM)",
			Detail: fmt.Sprintf(
				"%s — single-disk ext4/xfs. Best place for %s ledger/DB. Not striped through software RAID.",
				strings.Join(names, ", "), network,
			),
		})
		best := pickBestRawMount(rawMounts)
		insights = append(insights, HostDiskInsight{
			Level: "good",
			Code:  "recommend",
			Title: "Recommended data mount",
			Detail: recommendDataCopy(network, best),
		})
	}

	if len(raidMounts) > 0 {
		parts := make([]string, 0, len(raidMounts))
		levels := map[string]bool{}
		for _, m := range raidMounts {
			lvl := m.RaidLevel
			if lvl == "" {
				lvl = "md"
			}
			levels[lvl] = true
			parts = append(parts, fmt.Sprintf("%s on %s (%s)", m.Target, firstNonEmpty(m.Layer, m.Source), lvl))
		}
		detail := fmt.Sprintf(
			"%s. Software RAID is the bottleneck: the md device sits at ~100%% util while member NVMe stay at 50–70%%. ",
			strings.Join(parts, "; "),
		)
		if levels["raid0"] {
			detail += "RAID0 splits every write across members — no mirror, no extra IOPS for random 4k. "
		}
		if levels["raid1"] {
			detail += "RAID1 writes every block twice. "
		}
		detail += fmt.Sprintf("Do not put %s data here if a raw NVMe mount exists.", network)
		insights = append(insights, HostDiskInsight{
			Level:  "warn",
			Code:   "md_raid",
			Title:  "Software RAID (md) on a data mount",
			Detail: strings.TrimSpace(detail),
		})
	}

	if len(lvmRoot) > 0 {
		layer := firstNonEmpty(lvmRoot[0].Layer, lvmRoot[0].Source)
		insights = append(insights, HostDiskInsight{
			Level: "info",
			Code:  "lvm_os",
			Title: "OS disk is LVM (Ubuntu/curtin default)",
			Detail: fmt.Sprintf(
				"/ is %s — typical installer layout (root + swap). Fine for the system. Do not put %s ledger/accounts on /.",
				layer, network,
			),
		})
	}

	if len(unused) > 0 {
		parts := make([]string, 0, len(unused))
		for _, d := range unused {
			label := d.Name
			if d.SizeHuman != "" {
				label += " " + d.SizeHuman
			}
			parts = append(parts, label)
		}
		insights = append(insights, HostDiskInsight{
			Level: "info",
			Code:  "unused_nvme",
			Title: "Unused NVMe (no filesystem)",
			Detail: strings.Join(parts, ", ") +
				" — empty. Not used until formatted and mounted (e.g. /data/nvmeN).",
		})
	}

	if usableData == 0 && dataOnRoot && len(rawMounts) == 0 {
		insights = append(insights, HostDiskInsight{
			Level: "warn",
			Code:  "data_on_root",
			Title: "Only the OS disk looks usable",
			Detail: fmt.Sprintf(
				"No separate data NVMe mount. %s would land under /data on the system disk.",
				network,
			),
		})
	}

	summary = summarizeDiskInsights(rawMounts, raidMounts, lvmRoot, unused)
	return insights, summary
}

func recommendDataCopy(network, mount string) string {
	switch network {
	case "ton":
		return fmt.Sprintf(
			"Put validator DB (ton-work) on %s. One raw NVMe, not md, not the LVM / disk.",
			mount,
		)
	case "solana":
		return fmt.Sprintf(
			"Put ledger on a raw NVMe (start with %s). Put accounts on a second raw NVMe when present. Avoid md RAID.",
			mount,
		)
	default:
		return fmt.Sprintf(
			"Put %s data on %s (raw NVMe, not md, not LVM /).",
			network, mount,
		)
	}
}

func pickBestRawMount(raw []HostMount) string {
	if len(raw) == 0 {
		return "/data"
	}
	best := raw[0]
	for _, m := range raw[1:] {
		if m.AvailBytes > best.AvailBytes {
			best = m
		}
	}
	return best.Target
}

func mountTargets(ms []HostMount) []string {
	out := make([]string, 0, len(ms))
	for _, m := range ms {
		out = append(out, m.Target)
	}
	return out
}

func summarizeDiskInsights(raw, raid, lvmRoot []HostMount, unused []HostDisk) string {
	var parts []string
	if len(raw) > 0 {
		parts = append(parts, "raw NVMe "+strings.Join(mountTargets(raw), "+"))
	}
	if len(raid) > 0 {
		parts = append(parts, "md RAID "+strings.Join(mountTargets(raid), "+"))
	}
	if len(lvmRoot) > 0 {
		parts = append(parts, "OS LVM /")
	}
	if len(unused) > 0 {
		names := make([]string, 0, len(unused))
		for _, d := range unused {
			names = append(names, d.Name)
		}
		parts = append(parts, "unused "+strings.Join(names, "+"))
	}
	if len(parts) == 0 {
		return "no extra disk layout beyond /"
	}
	return strings.Join(parts, "; ")
}

func mountIsSoftwareRaid(m HostMount) bool {
	if m.Kind == "md_raid" {
		return true
	}
	src := strings.ToLower(strings.TrimSpace(m.Source))
	base := filepath.Base(strings.TrimPrefix(src, "/dev/"))
	return strings.Contains(src, "/dev/md") || strings.HasPrefix(base, "md")
}

// mountQuality — higher is better for data roles (raw NVMe first, md last).
func mountQuality(m HostMount) int {
	score := 0
	if m.Preferred {
		score += 20
	}
	switch m.Kind {
	case "raw_nvme":
		score += 100
	case "ssd":
		score += 60
	case "lvm":
		score -= 20
	case "md_raid":
		score -= 80
	case "hdd":
		score -= 30
	}
	if mountIsSoftwareRaid(m) {
		score -= 80
	}
	if m.Target == "/" {
		score -= 40
	}
	if m.Target == "/data" && !mountIsSoftwareRaid(m) && m.Kind != "lvm" {
		score += 10
	}
	return score
}
