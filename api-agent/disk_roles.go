package main

import (
	"fmt"
	"path/filepath"
	"sort"
	"strings"
)

// DiskRoleDef — one JBOD data role for a network (profile-driven, not Solana-only).
type DiskRoleDef struct {
	ID          string `json:"id"`
	Label       string `json:"label"`
	Description string `json:"description,omitempty"`
	Leaf        string `json:"leaf"` // path leaf under <mount>/<network>/<env>/
}

// DiskRolePlacement — recommended/confirmed mount+dir for one role.
type DiskRolePlacement struct {
	ID     string `json:"id"`
	Label  string `json:"label,omitempty"`
	Mount  string `json:"mount"`
	Dir    string `json:"dir"`
	Leaf   string `json:"leaf,omitempty"`
	Desc   string `json:"description,omitempty"`
}

// MultiDiskLayoutPlan — generic recommended layout for any network with multi_disk_roles.
type MultiDiskLayoutPlan struct {
	Strategy string              `json:"strategy"` // single | jbod_2 | jbod_3 | custom
	Network  string              `json:"network,omitempty"`
	Env      string              `json:"env,omitempty"`
	Roles    []DiskRolePlacement `json:"roles"`
	Notes    []string            `json:"notes,omitempty"`
	// Solana-compat flat fields (wizard + older clients).
	LedgerMount    string `json:"ledger_mount,omitempty"`
	AccountsMount  string `json:"accounts_mount,omitempty"`
	SnapshotsMount string `json:"snapshots_mount,omitempty"`
	LedgerDir      string `json:"ledger_dir,omitempty"`
	AccountsDir    string `json:"accounts_dir,omitempty"`
	SnapshotsDir   string `json:"snapshots_dir,omitempty"`
	StateMount     string `json:"state_mount,omitempty"`
	IndexMount     string `json:"index_mount,omitempty"`
	StateDir       string `json:"state_dir,omitempty"`
	IndexDir       string `json:"index_dir,omitempty"`
}

// multiDiskRolesForNetwork — canonical NVMe role table (docs/add-network.md).
// Polygon is intentionally absent (no product profile yet).
func multiDiskRolesForNetwork(network string) []DiskRoleDef {
	switch normalizeNetwork(network) {
	case "solana":
		return []DiskRoleDef{
			{ID: "ledger", Label: "Ledger", Description: "Agave --ledger (highest IOPS)", Leaf: "ledger"},
			{ID: "accounts", Label: "Accounts", Description: "Agave --accounts", Leaf: "accounts"},
			{ID: "snapshots", Label: "Snapshots", Description: "Agave --snapshots", Leaf: "snapshots"},
		}
	case "ethereum":
		return []DiskRoleDef{
			{ID: "execution", Label: "Execution Client DB", Description: "Geth datadir", Leaf: "geth"},
			{ID: "consensus", Label: "Consensus Client DB / snapshots", Description: "Lighthouse datadir", Leaf: "lighthouse"},
		}
	case "bsc":
		return []DiskRoleDef{
			{ID: "chaindata", Label: "chaindata", Description: "BSC geth datadir", Leaf: "geth"},
			{ID: "snapshots", Label: "snapshots / additional", Description: "Snapshot / aux path", Leaf: "snapshots"},
		}
	case "arb", "robinhood":
		return []DiskRoleDef{
			{ID: "execution", Label: "Execution DB", Description: "Nitro datadir", Leaf: "nitro"},
			{ID: "snapshots", Label: "snapshots / additional", Description: "Snapshot / aux path", Leaf: "snapshots"},
		}
	case "base":
		return []DiskRoleDef{
			{ID: "execution", Label: "Execution DB", Description: "base-reth datadir", Leaf: "reth"},
			{ID: "snapshots", Label: "snapshots / additional", Description: "Snapshot / aux path", Leaf: "snapshots"},
		}
	case "optimism":
		return []DiskRoleDef{
			{ID: "execution", Label: "Execution DB", Description: "op-geth datadir", Leaf: "op-geth"},
			{ID: "snapshots", Label: "snapshots / additional", Description: "op-node / aux", Leaf: "snapshots"},
		}
	case "tron":
		return []DiskRoleDef{
			{ID: "fullnode", Label: "FullNode DB", Description: "java-tron output-directory", Leaf: "output-directory"},
			{ID: "solidity", Label: "SolidityNode / additional", Description: "Reserved Solidity/aux path", Leaf: "solidity"},
		}
	case "ton":
		return []DiskRoleDef{
			{ID: "blockchain", Label: "Blockchain DB", Description: "validator-engine / ton-work", Leaf: "db"},
			{ID: "archive", Label: "Archive / additional", Description: "Archive / aux path", Leaf: "archive"},
		}
	case "sui":
		return []DiskRoleDef{
			{ID: "state", Label: "State DB", Description: "sui-node db-path", Leaf: "db"},
			{ID: "index", Label: "Index / auxiliary", Description: "Index / aux path", Leaf: "index"},
		}
	case "aptos":
		return []DiskRoleDef{
			{ID: "state", Label: "State DB", Description: "aptos-node base.data_dir", Leaf: "db"},
			{ID: "index", Label: "Index / auxiliary", Description: "Index / aux path", Leaf: "index"},
		}
	case "avalanche":
		return []DiskRoleDef{
			{ID: "chain", Label: "Chain DB", Description: "avalanchego data-dir", Leaf: ""},
			{ID: "snapshots", Label: "snapshots / additional", Description: "Snapshots / aux path", Leaf: "snapshots"},
		}
	case "bitcoin":
		// Electrs not in product — NVMe#2 reserved as index/aux.
		return []DiskRoleDef{
			{ID: "blockchain", Label: "Blockchain data", Description: "bitcoind datadir", Leaf: ""},
			{ID: "index", Label: "Index / auxiliary", Description: "Reserved index/aux (Electrs not provisioned)", Leaf: "index"},
		}
	case "ltc":
		return []DiskRoleDef{
			{ID: "blockchain", Label: "Blockchain data", Description: "litecoind datadir", Leaf: ""},
			{ID: "index", Label: "Index / auxiliary", Description: "Reserved index/aux (Electrs not provisioned)", Leaf: "index"},
		}
	case "dash":
		return []DiskRoleDef{
			{ID: "blockchain", Label: "Blockchain data", Description: "dashd datadir", Leaf: ""},
			{ID: "index", Label: "Index", Description: "Index / aux path", Leaf: "index"},
		}
	case "bch":
		// Same shape as ltc/dash when multi-disk is used; Electrs not in product.
		return []DiskRoleDef{
			{ID: "blockchain", Label: "Blockchain data", Description: "bitcoincash datadir", Leaf: ""},
			{ID: "index", Label: "Index / auxiliary", Description: "Reserved index/aux", Leaf: "index"},
		}
	default:
		return nil
	}
}

func networkHasMultiDiskRoles(network string) bool {
	return len(multiDiskRolesForNetwork(network)) > 0
}

func multiDiskLayoutRules(network string) []string {
	roles := multiDiskRolesForNetwork(network)
	if len(roles) == 0 {
		return nil
	}
	labels := make([]string, 0, len(roles))
	for _, r := range roles {
		labels = append(labels, r.Label)
	}
	out := []string{
		"Prefer separate NVMe as JBOD (not one RAID volume).",
		"Exclude small OS/root SSD from data roles when larger data mounts exist.",
		fmt.Sprintf("%s roles: %s", normalizeNetwork(network), strings.Join(labels, " → ")),
		"Single disk → all roles under one mount with a note.",
	}
	if normalizeNetwork(network) == "solana" {
		out = append(out, "Put ledger and accounts on different disks when ≥2 NVMe/SSD mounts exist.")
	}
	if normalizeNetwork(network) == "bitcoin" || normalizeNetwork(network) == "ltc" {
		out = append(out, "Electrs not in product — second role is reserved index/aux path only.")
	}
	if normalizeNetwork(network) == "polygon" || normalizeNetwork(network) == "matic" {
		out = append(out, "Polygon PoS profile not in agent yet — disk roles documented for later.")
	}
	return out
}

// recommendMultiDiskLayout — JBOD plan from role catalog + usable data mounts.
func recommendMultiDiskLayout(network, env string, mounts []HostMount) MultiDiskLayoutPlan {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	if env == "" {
		env = "mainnet"
	}
	defs := multiDiskRolesForNetwork(network)
	plan := MultiDiskLayoutPlan{Network: network, Env: env, Roles: nil}

	if len(defs) == 0 {
		plan.Strategy = "none"
		plan.Notes = []string{"Network has no multi_disk_roles"}
		return plan
	}

	cands := filterDataMountCandidates(mounts)
	if len(cands) == 0 {
		baseMount := "/data"
		plan.Strategy = "single"
		plan.Notes = []string{"No usable mounts detected — default /data/" + network + "/" + env}
		for _, d := range defs {
			dir := pathOnMountForRole(baseMount, network, env, d.Leaf)
			plan.Roles = append(plan.Roles, DiskRolePlacement{
				ID: d.ID, Label: d.Label, Mount: baseMount, Dir: dir, Leaf: d.Leaf, Desc: d.Description,
			})
		}
		fillCompatFields(&plan)
		return plan
	}

	sort.SliceStable(cands, func(i, j int) bool {
		if cands[i].Preferred != cands[j].Preferred {
			return cands[i].Preferred
		}
		return cands[i].AvailBytes > cands[j].AvailBytes
	})
	unique := uniqueMountsByDisk(cands)

	nRoles := len(defs)
	nDisks := len(unique)
	switch {
	case nDisks == 1:
		plan.Strategy = "single"
		m := unique[0].Target
		plan.Notes = []string{"Single disk — all roles share " + m}
		for _, d := range defs {
			plan.Roles = append(plan.Roles, DiskRolePlacement{
				ID: d.ID, Label: d.Label, Mount: m,
				Dir: pathOnMountForRole(m, network, env, d.Leaf), Leaf: d.Leaf, Desc: d.Description,
			})
		}
	case nDisks >= nRoles:
		if nRoles >= 3 {
			plan.Strategy = "jbod_3"
		} else {
			plan.Strategy = "jbod_2"
		}
		plan.Notes = []string{
			fmt.Sprintf("JBOD %d disks for %d roles — prefer NVMe, not one RAID volume", minInt(nDisks, nRoles), nRoles),
		}
		for i, d := range defs {
			m := unique[i].Target
			plan.Roles = append(plan.Roles, DiskRolePlacement{
				ID: d.ID, Label: d.Label, Mount: m,
				Dir: pathOnMountForRole(m, network, env, d.Leaf), Leaf: d.Leaf, Desc: d.Description,
			})
		}
	default:
		// 2 disks, 3 roles (solana): first role on disk0, rest on disk1.
		plan.Strategy = "jbod_2"
		a, b := unique[0].Target, unique[1].Target
		plan.Notes = []string{
			fmt.Sprintf("JBOD 2 disks: %s on %s, remaining roles on %s", defs[0].Label, a, b),
			"Prefer separate NVMe — not RAID-0/1 as one volume",
		}
		for i, d := range defs {
			m := a
			if i > 0 {
				m = b
			}
			plan.Roles = append(plan.Roles, DiskRolePlacement{
				ID: d.ID, Label: d.Label, Mount: m,
				Dir: pathOnMountForRole(m, network, env, d.Leaf), Leaf: d.Leaf, Desc: d.Description,
			})
		}
	}
	fillCompatFields(&plan)
	return plan
}

func fillCompatFields(plan *MultiDiskLayoutPlan) {
	for _, r := range plan.Roles {
		switch r.ID {
		case "ledger":
			plan.LedgerMount, plan.LedgerDir = r.Mount, r.Dir
		case "accounts":
			plan.AccountsMount, plan.AccountsDir = r.Mount, r.Dir
		case "snapshots":
			plan.SnapshotsMount, plan.SnapshotsDir = r.Mount, r.Dir
		case "state":
			plan.StateMount, plan.StateDir = r.Mount, r.Dir
		case "index":
			plan.IndexMount, plan.IndexDir = r.Mount, r.Dir
		case "chain":
			// Avalanche chain ≈ ledger_dir flat field for transport reuse.
			plan.LedgerMount, plan.LedgerDir = r.Mount, r.Dir
		case "execution", "chaindata", "fullnode", "blockchain":
			if plan.LedgerDir == "" {
				plan.LedgerMount, plan.LedgerDir = r.Mount, r.Dir
			}
		case "consensus", "solidity", "archive":
			if plan.AccountsDir == "" {
				plan.AccountsMount, plan.AccountsDir = r.Mount, r.Dir
			}
		}
	}
}

func pathOnMountForRole(mount, network, env, leaf string) string {
	mount = filepath.Clean(strings.TrimSpace(mount))
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	leaf = strings.Trim(strings.TrimSpace(leaf), "/")
	base := ""
	switch {
	case mount == "" || mount == "." || mount == "/":
		base = filepath.Join("/data", network, env)
	case mount == "/data":
		base = filepath.Join("/data", network, env)
	default:
		base = filepath.Join(mount, network, env)
	}
	if leaf == "" {
		return base
	}
	return filepath.Join(base, leaf)
}

// filterDataMountCandidates — usable mounts for data roles; exclude tiny free + prefer non-OS.
func filterDataMountCandidates(mounts []HostMount) []HostMount {
	const minAvail = 20 * 1024 * 1024 * 1024 // 20 GiB
	const osSizeCeil = 200 * 1024 * 1024 * 1024 // <200 GiB often OS SSD

	var out []HostMount
	for _, m := range mounts {
		if m.Target == "" {
			continue
		}
		if m.AvailBytes > 0 && m.AvailBytes < minAvail {
			continue
		}
		out = append(out, m)
	}
	if len(out) == 0 {
		return out
	}

	// If we have non-root large preferred mounts, drop small root/OS candidates.
	hasAlt := false
	for _, m := range out {
		if m.Target != "/" && m.Preferred && (m.SizeBytes == 0 || m.SizeBytes >= osSizeCeil) {
			hasAlt = true
			break
		}
		if m.Target != "/" && (m.SizeBytes == 0 || m.SizeBytes >= osSizeCeil) && m.AvailBytes >= 100*1024*1024*1024 {
			hasAlt = true
			break
		}
	}
	if hasAlt {
		var filtered []HostMount
		for _, m := range out {
			if m.Target == "/" {
				continue
			}
			// Small SSD that looks like OS disk (mounted at common OS paths already filtered;
			// size heuristic for leftover small data mounts).
			if m.SizeBytes > 0 && m.SizeBytes < osSizeCeil && !strings.HasPrefix(m.Target, "/data") {
				continue
			}
			filtered = append(filtered, m)
		}
		if len(filtered) > 0 {
			out = filtered
		}
	}

	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Target == "/data" && out[j].Target != "/data" {
			return true
		}
		if out[j].Target == "/data" && out[i].Target != "/data" {
			return false
		}
		if out[i].Preferred != out[j].Preferred {
			return out[i].Preferred
		}
		return out[i].AvailBytes > out[j].AvailBytes
	})
	return out
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

// roleDirFromLayout — lookup absolute dir for role id from provision disk_layout.
func roleDirFromLayout(dl *solanaDiskLayoutIn, roleID string) string {
	if dl == nil {
		return ""
	}
	if dl.Roles != nil {
		if r, ok := dl.Roles[roleID]; ok {
			if d := strings.TrimSpace(r.Dir); d != "" {
				return d
			}
		}
	}
	switch roleID {
	case "ledger", "execution", "chaindata", "fullnode", "blockchain", "chain":
		if d := strings.TrimSpace(dl.LedgerDir); d != "" {
			return d
		}
	case "accounts", "consensus", "solidity", "archive":
		if d := strings.TrimSpace(dl.AccountsDir); d != "" {
			return d
		}
	case "snapshots":
		if d := strings.TrimSpace(dl.SnapshotsDir); d != "" {
			return d
		}
	case "state":
		if d := strings.TrimSpace(dl.StateDir); d != "" {
			return d
		}
	case "index":
		if d := strings.TrimSpace(dl.IndexDir); d != "" {
			return d
		}
	}
	return ""
}

// resolveNetworkRoleDir — disk_layout role dir/mount override, else defaultDir.
func resolveNetworkRoleDir(req nodeProvisionRequest, network, env, roleID, defaultDir string) string {
	if d := roleDirFromLayout(req.DiskLayout, roleID); d != "" {
		return filepath.Clean(d)
	}
	if m := roleMountFromLayout(req.DiskLayout, roleID); m != "" {
		leaf := ""
		for _, def := range multiDiskRolesForNetwork(network) {
			if def.ID == roleID {
				leaf = def.Leaf
				break
			}
		}
		return pathOnMountForRole(m, network, env, leaf)
	}
	if defaultDir == "" {
		return ""
	}
	return filepath.Clean(defaultDir)
}

func roleMountFromLayout(dl *solanaDiskLayoutIn, roleID string) string {
	if dl == nil {
		return ""
	}
	if dl.Roles != nil {
		if r, ok := dl.Roles[roleID]; ok {
			if m := strings.TrimSpace(r.Mount); m != "" {
				return m
			}
		}
	}
	switch roleID {
	case "ledger", "execution", "chaindata", "fullnode", "blockchain", "chain":
		return strings.TrimSpace(dl.LedgerMount)
	case "accounts", "consensus", "solidity", "archive":
		return strings.TrimSpace(dl.AccountsMount)
	case "snapshots":
		return strings.TrimSpace(dl.SnapshotsMount)
	case "state":
		return strings.TrimSpace(dl.StateMount)
	case "index":
		return strings.TrimSpace(dl.IndexMount)
	}
	return ""
}
