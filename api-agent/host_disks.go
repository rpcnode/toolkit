package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// HostDisk — one block device (disk or partition) from lsblk inventory.
type HostDisk struct {
	Name         string `json:"name"`
	Path         string `json:"path,omitempty"`
	Model        string `json:"model,omitempty"`
	SizeBytes    int64  `json:"size_bytes"`
	SizeHuman    string `json:"size_human,omitempty"`
	Tran         string `json:"tran,omitempty"` // nvme, sata, …
	Rota         *bool  `json:"rota,omitempty"` // true = HDD
	Type         string `json:"type,omitempty"` // disk, part, lvm, …
	Mountpoint   string `json:"mountpoint,omitempty"`
	Fstype       string `json:"fstype,omitempty"`
	FsAvailBytes int64  `json:"fsavail_bytes,omitempty"`
	FsAvailHuman string `json:"fsavail_human,omitempty"`
	FsSizeBytes  int64  `json:"fssize_bytes,omitempty"`
	FsUsedPct    float64 `json:"fsused_pct,omitempty"`
	Parent       string `json:"parent,omitempty"`
	Preferred    bool   `json:"preferred,omitempty"` // NVMe / non-rotational SSD
}

// HostMount — usable filesystem mount for datadir placement.
type HostMount struct {
	Target       string `json:"target"`
	Source       string `json:"source,omitempty"`
	Fstype       string `json:"fstype,omitempty"`
	SizeBytes    int64  `json:"size_bytes,omitempty"`
	AvailBytes   int64  `json:"avail_bytes,omitempty"`
	AvailHuman   string `json:"avail_human,omitempty"`
	UsedPct      float64 `json:"used_pct,omitempty"`
	DiskName     string `json:"disk_name,omitempty"`
	DiskPath     string `json:"disk_path,omitempty"`
	Model        string `json:"model,omitempty"`
	Tran         string `json:"tran,omitempty"`
	Rota         *bool  `json:"rota,omitempty"`
	Preferred    bool   `json:"preferred,omitempty"`
}

// SolanaDiskLayoutPlan — recommended / confirmed JBOD paths for Agave.
type SolanaDiskLayoutPlan struct {
	Strategy       string   `json:"strategy"` // single | jbod_2 | jbod_3
	LedgerMount    string   `json:"ledger_mount"`
	AccountsMount  string   `json:"accounts_mount"`
	SnapshotsMount string   `json:"snapshots_mount"`
	LedgerDir      string   `json:"ledger_dir"`
	AccountsDir    string   `json:"accounts_dir"`
	SnapshotsDir   string   `json:"snapshots_dir"`
	Notes          []string `json:"notes,omitempty"`
}

type lsblkDoc struct {
	Blockdevices []lsblkNode `json:"blockdevices"`
}

type lsblkNode struct {
	Name       string      `json:"name"`
	Path       string      `json:"path"`
	Size       json.Number `json:"size"`
	Type       string      `json:"type"`
	Rota       any         `json:"rota"`
	Tran       string      `json:"tran"`
	Model      string      `json:"model"`
	Mountpoint any         `json:"mountpoint"`
	Fstype     string      `json:"fstype"`
	Pkname     string      `json:"pkname"`
	Children   []lsblkNode `json:"children"`
}

type findmntDoc struct {
	Filesystems []findmntNode `json:"filesystems"`
}

type findmntNode struct {
	Target   string        `json:"target"`
	Source   string        `json:"source"`
	Fstype   string        `json:"fstype"`
	Size     json.Number   `json:"size"`
	Avail    json.Number   `json:"avail"`
	Used     json.Number   `json:"used"`
	Children []findmntNode `json:"children,omitempty"`
}

func (s *Server) handleHostDisks(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "error": "method_not_allowed"})
		return
	}
	network := normalizeNetwork(strings.TrimSpace(r.URL.Query().Get("network")))
	env := normalizeEnv(strings.TrimSpace(r.URL.Query().Get("env")))
	disks, mounts, err := collectHostDiskInventory()
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "disks": []HostDisk{}, "mounts": []HostMount{},
			"error": err.Error(), "message": "disk inventory unavailable (lsblk/findmnt)",
		})
		return
	}
	out := map[string]any{
		"ok":     true,
		"disks":  disks,
		"mounts": mounts,
		"count":  len(disks),
	}
	if network != "" && networkHasMultiDiskRoles(network) {
		if env == "" {
			env = "mainnet"
		}
		plan := recommendMultiDiskLayout(network, env, mounts)
		out["recommended"] = plan
		out["network"] = network
		out["env"] = env
		out["layout_rules"] = multiDiskLayoutRules(network)
		out["multi_disk_roles"] = multiDiskRolesForNetwork(network)
	}
	writeJSON(w, http.StatusOK, out)
}

func solanaDiskLayoutRules() []string {
	return multiDiskLayoutRules("solana")
}

func collectHostDiskInventory() ([]HostDisk, []HostMount, error) {
	raw, err := exec.Command("lsblk", "-J", "-b", "-o",
		"NAME,PATH,SIZE,TYPE,ROTA,TRAN,MODEL,MOUNTPOINT,FSTYPE,PKNAME").CombinedOutput()
	if err != nil {
		return nil, nil, fmt.Errorf("lsblk: %w (%s)", err, strings.TrimSpace(string(raw)))
	}
	var doc lsblkDoc
	if err := json.Unmarshal(raw, &doc); err != nil {
		return nil, nil, fmt.Errorf("lsblk json: %w", err)
	}
	fsByTarget := map[string]findmntNode{}
	if fm, err := collectFindmnt(); err == nil {
		for _, n := range fm {
			if n.Target != "" {
				fsByTarget[n.Target] = n
			}
		}
	}

	var disks []HostDisk
	var flat []HostDisk
	var walk func(n lsblkNode, parentTran, parentModel string, parentRota *bool, parentName string)
	walk = func(n lsblkNode, parentTran, parentModel string, parentRota *bool, parentName string) {
		tran := strings.TrimSpace(n.Tran)
		if tran == "" {
			tran = parentTran
		}
		model := strings.TrimSpace(n.Model)
		if model == "" {
			model = parentModel
		}
		rota := parseRota(n.Rota)
		if rota == nil {
			rota = parentRota
		}
		mp := mountpointString(n.Mountpoint)
		size := jsonNumberInt64(n.Size)
		path := strings.TrimSpace(n.Path)
		if path == "" && n.Name != "" {
			path = "/dev/" + n.Name
		}
		hd := HostDisk{
			Name:       n.Name,
			Path:       path,
			Model:      model,
			SizeBytes:  size,
			SizeHuman:  humanBytes(size),
			Tran:       tran,
			Rota:       rota,
			Type:       strings.TrimSpace(n.Type),
			Mountpoint: mp,
			Fstype:     strings.TrimSpace(n.Fstype),
			Parent:     parentName,
			Preferred:  isPreferredDisk(tran, rota),
		}
		if mp != "" {
			if fs, ok := fsByTarget[mp]; ok {
				hd.FsAvailBytes = jsonNumberInt64(fs.Avail)
				hd.FsSizeBytes = jsonNumberInt64(fs.Size)
				hd.FsAvailHuman = humanBytes(hd.FsAvailBytes)
				if hd.FsSizeBytes > 0 {
					used := hd.FsSizeBytes - hd.FsAvailBytes
					if used < 0 {
						used = 0
					}
					hd.FsUsedPct = round2(float64(used) / float64(hd.FsSizeBytes) * 100)
				}
			}
		}
		flat = append(flat, hd)
		if hd.Type == "disk" || hd.Type == "nvme" {
			disks = append(disks, hd)
		}
		for _, c := range n.Children {
			walk(c, tran, model, rota, n.Name)
		}
	}
	for _, n := range doc.Blockdevices {
		walk(n, "", "", nil, "")
	}

	mounts := buildHostMounts(fsByTarget, flat)
	sort.Slice(disks, func(i, j int) bool {
		if disks[i].Preferred != disks[j].Preferred {
			return disks[i].Preferred
		}
		return disks[i].SizeBytes > disks[j].SizeBytes
	})
	sort.Slice(mounts, func(i, j int) bool {
		if mounts[i].Preferred != mounts[j].Preferred {
			return mounts[i].Preferred
		}
		return mounts[i].AvailBytes > mounts[j].AvailBytes
	})
	return disks, mounts, nil
}

func collectFindmnt() ([]findmntNode, error) {
	// -l flattens; without it -J is a tree (children under /). We flatten either way
	// so /data/nvme2, /data/nvme3, … are not dropped.
	raw, err := exec.Command("findmnt", "-J", "-l", "-b", "-o", "TARGET,SOURCE,FSTYPE,SIZE,AVAIL,USED").CombinedOutput()
	if err != nil {
		raw, err = exec.Command("findmnt", "-J", "-b", "-o", "TARGET,SOURCE,FSTYPE,SIZE,AVAIL,USED").CombinedOutput()
		if err != nil {
			return nil, err
		}
	}
	var doc findmntDoc
	if err := json.Unmarshal(raw, &doc); err != nil {
		return nil, err
	}
	return flattenFindmnt(doc.Filesystems), nil
}

func flattenFindmnt(nodes []findmntNode) []findmntNode {
	var out []findmntNode
	var walk func([]findmntNode)
	walk = func(ns []findmntNode) {
		for _, n := range ns {
			kids := n.Children
			n.Children = nil
			out = append(out, n)
			if len(kids) > 0 {
				walk(kids)
			}
		}
	}
	walk(nodes)
	return out
}

func buildHostMounts(fsByTarget map[string]findmntNode, flat []HostDisk) []HostMount {
	byMount := map[string]HostDisk{}
	for _, d := range flat {
		if d.Mountpoint != "" {
			byMount[d.Mountpoint] = d
		}
	}
	var out []HostMount
	seen := map[string]bool{}
	for target, fs := range fsByTarget {
		if !usableMountTarget(target, fs.Fstype) {
			continue
		}
		if seen[target] {
			continue
		}
		seen[target] = true
		avail := jsonNumberInt64(fs.Avail)
		size := jsonNumberInt64(fs.Size)
		m := HostMount{
			Target:     target,
			Source:     fs.Source,
			Fstype:     fs.Fstype,
			SizeBytes:  size,
			AvailBytes: avail,
			AvailHuman: humanBytes(avail),
		}
		if size > 0 {
			used := size - avail
			if used < 0 {
				used = 0
			}
			m.UsedPct = round2(float64(used) / float64(size) * 100)
		}
		if d, ok := byMount[target]; ok {
			m.DiskName = rootDiskName(d, flat)
			m.DiskPath = d.Path
			m.Model = d.Model
			m.Tran = d.Tran
			m.Rota = d.Rota
			m.Preferred = d.Preferred
		} else {
			// Match by source device basename.
			src := filepath.Base(strings.TrimPrefix(fs.Source, "/dev/"))
			for _, d := range flat {
				if d.Name == src || filepath.Base(d.Path) == src {
					m.DiskName = rootDiskName(d, flat)
					m.DiskPath = d.Path
					m.Model = d.Model
					m.Tran = d.Tran
					m.Rota = d.Rota
					m.Preferred = d.Preferred
					break
				}
			}
		}
		out = append(out, m)
	}
	// lsblk already has mountpoints on partitions (nvme2n1p1 → /data/nvme2).
	// If findmnt was a tree and we missed children, still surface them.
	for _, d := range flat {
		mp := strings.TrimSpace(d.Mountpoint)
		if mp == "" || seen[mp] || !usableMountTarget(mp, d.Fstype) {
			continue
		}
		seen[mp] = true
		m := HostMount{
			Target:     mp,
			Source:     d.Path,
			Fstype:     d.Fstype,
			SizeBytes:  d.SizeBytes,
			AvailBytes: d.FsAvailBytes,
			AvailHuman: d.FsAvailHuman,
			UsedPct:    d.FsUsedPct,
			DiskName:   rootDiskName(d, flat),
			DiskPath:   d.Path,
			Model:      d.Model,
			Tran:       d.Tran,
			Rota:       d.Rota,
			Preferred:  d.Preferred,
		}
		if m.AvailHuman == "" && m.AvailBytes > 0 {
			m.AvailHuman = humanBytes(m.AvailBytes)
		}
		out = append(out, m)
	}
	return out
}

func rootDiskName(d HostDisk, _ []HostDisk) string {
	if d.Type == "disk" || d.Type == "nvme" {
		return d.Name
	}
	if d.Parent != "" {
		return d.Parent
	}
	return d.Name
}

func usableMountTarget(target, fstype string) bool {
	t := strings.TrimSpace(target)
	if t == "" {
		return false
	}
	fs := strings.ToLower(strings.TrimSpace(fstype))
	switch fs {
	case "tmpfs", "devtmpfs", "sysfs", "proc", "cgroup", "cgroup2", "overlay",
		"squashfs", "fuse.snapfuse", "nsfs", "rpc_pipefs", "autofs", "debugfs",
		"tracefs", "securityfs", "pstore", "bpf", "hugetlbfs", "mqueue", "configfs":
		return false
	}
	for _, p := range []string{
		"/boot", "/boot/efi", "/efi", "/snap", "/var/lib/docker", "/run",
		"/dev", "/sys", "/proc", "/tmp",
	} {
		if t == p || strings.HasPrefix(t, p+"/") {
			return false
		}
	}
	return true
}

func isPreferredDisk(tran string, rota *bool) bool {
	t := strings.ToLower(strings.TrimSpace(tran))
	if t == "nvme" {
		return true
	}
	if rota != nil && !*rota {
		return true
	}
	return false
}

func parseRota(v any) *bool {
	switch t := v.(type) {
	case bool:
		b := t
		return &b
	case float64:
		b := t != 0
		return &b
	case string:
		s := strings.TrimSpace(strings.ToLower(t))
		if s == "1" || s == "true" {
			b := true
			return &b
		}
		if s == "0" || s == "false" {
			b := false
			return &b
		}
	case json.Number:
		n, _ := t.Int64()
		b := n != 0
		return &b
	}
	return nil
}

func mountpointString(v any) string {
	switch t := v.(type) {
	case string:
		return strings.TrimSpace(t)
	case []any:
		if len(t) > 0 {
			if s, ok := t[0].(string); ok {
				return strings.TrimSpace(s)
			}
		}
	}
	return ""
}

func jsonNumberInt64(n json.Number) int64 {
	if n == "" {
		return 0
	}
	v, err := n.Int64()
	if err == nil {
		return v
	}
	f, err := n.Float64()
	if err == nil {
		return int64(f)
	}
	i, _ := strconv.ParseInt(string(n), 10, 64)
	return i
}

func humanBytes(n int64) string {
	if n <= 0 {
		return "0B"
	}
	units := []string{"B", "KiB", "MiB", "GiB", "TiB", "PiB"}
	v := float64(n)
	i := 0
	for v >= 1024 && i < len(units)-1 {
		v /= 1024
		i++
	}
	if i == 0 {
		return fmt.Sprintf("%dB", n)
	}
	return fmt.Sprintf("%.1f%s", v, units[i])
}

// recommendSolanaDiskLayout — thin wrapper over profile-driven multi-disk recommender.
func recommendSolanaDiskLayout(mounts []HostMount, env string) SolanaDiskLayoutPlan {
	p := recommendMultiDiskLayout("solana", env, mounts)
	return SolanaDiskLayoutPlan{
		Strategy:       p.Strategy,
		LedgerMount:    p.LedgerMount,
		AccountsMount:  p.AccountsMount,
		SnapshotsMount: p.SnapshotsMount,
		LedgerDir:      p.LedgerDir,
		AccountsDir:    p.AccountsDir,
		SnapshotsDir:   p.SnapshotsDir,
		Notes:          p.Notes,
	}
}

func filterSolanaMountCandidates(mounts []HostMount) []HostMount {
	return filterDataMountCandidates(mounts)
}

func uniqueMountsByDisk(mounts []HostMount) []HostMount {
	seenDisk := map[string]bool{}
	var out []HostMount
	for _, m := range mounts {
		key := m.DiskName
		if key == "" {
			key = m.Source
		}
		if key == "" {
			key = m.Target
		}
		if seenDisk[key] {
			continue
		}
		seenDisk[key] = true
		out = append(out, m)
	}
	return out
}

func solanaPathOnMount(mount, env, leaf string) string {
	mount = strings.TrimSpace(mount)
	env = normalizeEnv(env)
	if mount == "" || mount == "/" {
		return filepath.Join("/data", "solana", env, leaf)
	}
	if mount == "/data" {
		return filepath.Join("/data", "solana", env, leaf)
	}
	return filepath.Join(mount, "solana", env, leaf)
}

// resolveSolanaDiskDirs — apply request overrides or recommended defaults.
func resolveSolanaDiskDirs(req nodeProvisionRequest, data, env string) (ledger, accounts, snapshots string) {
	env = normalizeEnv(env)
	ledger = strings.TrimSpace(req.LedgerDir)
	accounts = strings.TrimSpace(req.AccountsDir)
	snapshots = strings.TrimSpace(req.SnapshotsDir)
	if req.DiskLayout != nil {
		if ledger == "" {
			ledger = strings.TrimSpace(req.DiskLayout.LedgerDir)
		}
		if accounts == "" {
			accounts = strings.TrimSpace(req.DiskLayout.AccountsDir)
		}
		if snapshots == "" {
			snapshots = strings.TrimSpace(req.DiskLayout.SnapshotsDir)
		}
		if ledger == "" && req.DiskLayout.LedgerMount != "" {
			ledger = solanaPathOnMount(req.DiskLayout.LedgerMount, env, "ledger")
		}
		if accounts == "" && req.DiskLayout.AccountsMount != "" {
			accounts = solanaPathOnMount(req.DiskLayout.AccountsMount, env, "accounts")
		}
		if snapshots == "" && req.DiskLayout.SnapshotsMount != "" {
			snapshots = solanaPathOnMount(req.DiskLayout.SnapshotsMount, env, "snapshots")
		}
	}
	if ledger == "" {
		ledger = filepath.Join(data, "ledger")
	}
	if accounts == "" {
		accounts = filepath.Join(data, "accounts")
	}
	if snapshots == "" {
		snapshots = filepath.Join(data, "snapshots")
	}
	return filepath.Clean(ledger), filepath.Clean(accounts), filepath.Clean(snapshots)
}

func validateSolanaDataPath(p string) error {
	p = filepath.Clean(strings.TrimSpace(p))
	if p == "" || p == "." || p == "/" {
		return fmt.Errorf("invalid path")
	}
	if !filepath.IsAbs(p) {
		return fmt.Errorf("path must be absolute: %s", p)
	}
	// Refuse obvious system paths.
	for _, bad := range []string{"/bin", "/sbin", "/usr", "/etc", "/boot", "/dev", "/proc", "/sys"} {
		if p == bad || strings.HasPrefix(p, bad+"/") {
			return fmt.Errorf("refusing system path: %s", p)
		}
	}
	return nil
}

func ensureSolanaLayoutDirs(paths ...string) error {
	for _, p := range paths {
		if p == "" {
			continue
		}
		if err := validateSolanaDataPath(p); err != nil {
			return err
		}
		if err := os.MkdirAll(p, 0o755); err != nil {
			return fmt.Errorf("mkdir %s: %w", p, err)
		}
	}
	return nil
}
