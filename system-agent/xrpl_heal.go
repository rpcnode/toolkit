package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var (
	xrplNodeSizeRe      = regexp.MustCompile(`(?m)^\[node_size\]\s*\n[^\n#[]*`)
	xrplLedgerHistoryRe = regexp.MustCompile(`(?m)^\[ledger_history\]\s*\n[^\n#[]*`)
	xrplOnlineDeleteRe  = regexp.MustCompile(`(?m)^online_delete\s*=\s*\S+\s*\n`)
	xrplPeersMaxRe      = regexp.MustCompile(`(?m)^\[peers_max\]\s*\n(\d+)`)
)

const xrplPeersMaxHistory = 100

func xrplMainnetHubs() []string {
	return []string{
		"r.ripple.com 51235",
		"sahyadri.isrdc.in 51235",
		"hubs.xrpkuwait.com 51235",
		"hub.xrpl-commons.org 51235",
	}
}

func xrplMainnetFixedPeers() []string {
	return append(xrplMainnetHubs(), "s2.ripple.com 51235")
}

// xrplNodeSizeForRAMGiB — same table as api-agent (capacity-planning).
func xrplNodeSizeForRAMGiB(gib float64) string {
	switch {
	case gib <= 0:
		return "medium"
	case gib < 8:
		return "tiny"
	case gib < 16:
		return "small"
	case gib < 32:
		return "medium"
	default:
		return "huge"
	}
}

func xrplNodeSize(ramGiB float64, hasLedger bool) string {
	if !hasLedger {
		return "medium"
	}
	return xrplNodeSizeForRAMGiB(ramGiB)
}

func xrplStatusHasLedger(st map[string]any) bool {
	if st == nil {
		return false
	}
	rpc, _ := st["rpc"].(map[string]any)
	if rpc == nil {
		return false
	}
	switch v := rpc["ledger_seq"].(type) {
	case float64:
		if v > 0 {
			return true
		}
	case int64:
		if v > 0 {
			return true
		}
	case int:
		if v > 0 {
			return true
		}
	}
	c := strings.TrimSpace(fmt.Sprint(rpc["complete_ledgers"]))
	if c != "" && c != "empty" && c != "<nil>" {
		return true
	}
	return false
}

func xrplDatadirHasLedger(data string) bool {
	data = strings.TrimSpace(data)
	if data == "" {
		return false
	}
	nudb := filepath.Join(data, "db", "nudb")
	entries, err := os.ReadDir(nudb)
	if err != nil {
		return false
	}
	var total int64
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		total += info.Size()
	}
	return total >= 8<<20
}

// xrplReinitStaleNuDB — one-shot: leftover NuDB from LoadManager FTL first-acquire
// keeps seq=0. Official advice is a fresh empty NuDB path.
func xrplReinitStaleNuDB(data string) (bool, error) {
	data = strings.TrimSpace(data)
	if data == "" || xrplDatadirHasLedger(data) {
		return false, nil
	}

	marker := filepath.Join(data, ".nudb-reinit")
	if info, err := os.Stat(marker); err == nil && time.Since(info.ModTime()) < 15*time.Minute {
		return false, nil
	}
	_ = os.Remove(marker)

	return xrplRotateNuDB(data, ".nudb-reinit", "reinit after failed first ledger acquire\n")
}

func xrplUnitHasLoadStall(unit string) bool {
	return xrplJournalHasLoadStall(strings.Split(journalUnitSnippet(unit, 60), "\n"))
}

func xrplCooldownReady(dir, name string, d time.Duration) bool {
	p := filepath.Join(strings.TrimSpace(dir), name)
	if info, err := os.Stat(p); err == nil && time.Since(info.ModTime()) < d {
		return false
	}
	return true
}

func xrplMarkCooldown(dir, name, note string) {
	dir = strings.TrimSpace(dir)
	if dir == "" || name == "" {
		return
	}
	_ = os.WriteFile(filepath.Join(dir, name), []byte(note), 0o644)
}

const xrplStateDBReinitMarker = ".nudb-reinit-statedb"

// xrplReinitCorruptStateDB — SHAMapStore abort. xrpld asks to remove db/state*
// and empty db/nudb. One-shot marker avoids wiping a live NuDB because old
// journal lines still say "state db error".
func xrplReinitCorruptStateDB(data string) (bool, error) {
	return xrplHealCorruptStateDB(data, "")
}

func xrplHealCorruptStateDB(data, unit string) (bool, error) {
	data = strings.TrimSpace(data)
	if data == "" {
		return false, nil
	}

	marker := filepath.Join(data, xrplStateDBReinitMarker)
	force := false
	if fileExists(marker) {
		if info, err := os.Stat(marker); err == nil && strings.TrimSpace(unit) != "" {
			force = xrplJournalHasStateDBError(journalUnitSnippetSince(unit, info.ModTime(), 40)) ||
				(xrplUnitDumpedCore(unit) && xrplNuDBNewerThan(data, info.ModTime()))
		}
	}

	wipedState := xrplWipeStateSidecars(data)
	if fileExists(marker) && !force {
		return wipedState, nil
	}

	if force {
		_ = os.Remove(marker)
	}

	rotated, err := xrplRotateNuDB(data, xrplStateDBReinitMarker, "reinit after SHAMapStore state db error\n")
	if err != nil {
		return wipedState, err
	}

	return wipedState || rotated, nil
}

func xrplStateSidecarGlob(data string) []string {
	matches, err := filepath.Glob(filepath.Join(data, "db", "state*"))
	if err != nil {
		return nil
	}

	return matches
}

func xrplWipeStateSidecars(data string) bool {
	changed := false
	for _, p := range xrplStateSidecarGlob(data) {
		if err := os.RemoveAll(p); err == nil {
			changed = true
		}
	}

	return changed
}

func xrplRotateNuDB(data, markerName, note string) (bool, error) {
	if xrpldHoldsNuDB(data) {
		return false, fmt.Errorf("refuse NuDB rotate: xrpld still has %s/db/nudb open", data)
	}

	marker := filepath.Join(data, markerName)
	if fileExists(marker) {
		return false, nil
	}

	nudb := filepath.Join(data, "db", "nudb")
	entries, err := os.ReadDir(nudb)
	if err != nil || len(entries) == 0 {
		return false, nil
	}

	bak := nudb + ".stale"
	_ = os.RemoveAll(bak)
	if err := os.Rename(nudb, bak); err != nil {
		return false, err
	}

	if err := os.MkdirAll(nudb, 0o755); err != nil {
		return false, err
	}

	_ = exec.Command("chown", "-R", "nodeop:nodeop", filepath.Dir(nudb), nudb).Run()
	_ = os.WriteFile(marker, []byte(note), 0o644)

	return true, nil
}

func xrplShouldHealStateDB(data, unit string) bool {
	if xrplJournalHasStateDBError(journalUnitSnippet(unit, 40)) {
		return true
	}

	return xrplUnitDumpedCore(unit) && (xrplNuDBHasFiles(data) || len(xrplStateSidecarGlob(data)) > 0)
}

func xrplNuDBHasFiles(data string) bool {
	entries, err := os.ReadDir(filepath.Join(strings.TrimSpace(data), "db", "nudb"))
	if err != nil {
		return false
	}

	return len(entries) > 0
}

func xrplUnitDumpedCore(unit string) bool {
	unit = strings.TrimSpace(unit)
	if unit == "" {
		return false
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}

	out, _ := exec.Command("systemctl", "show", unit,
		"-p", "Result", "-p", "ExecMainStatus", "--no-pager").CombinedOutput()
	result, status := "", ""
	for _, ln := range strings.Split(string(out), "\n") {
		k, v, ok := strings.Cut(strings.TrimSpace(ln), "=")
		if !ok {
			continue
		}
		switch k {
		case "Result":
			result = v
		case "ExecMainStatus":
			status = v
		}
	}

	return xrplSystemdLooksLikeAbort(result, status)
}

func xrplSystemdLooksLikeAbort(result, execMainStatus string) bool {
	switch strings.ToLower(strings.TrimSpace(result)) {
	case "core-dump", "coredump":
		return true
	}

	switch strings.TrimSpace(execMainStatus) {
	case "6", "134":
		return true
	}

	return false
}

func xrplNuDBNewerThan(data string, t time.Time) bool {
	entries, err := os.ReadDir(filepath.Join(data, "db", "nudb"))
	if err != nil {
		return false
	}

	for _, e := range entries {
		info, err := e.Info()
		if err != nil {
			continue
		}
		if info.ModTime().After(t) {
			return true
		}
	}

	return false
}

func xrplJournalHasStateDBError(raw string) bool {
	s := strings.ToLower(raw)
	if s == "" {
		return false
	}

	return strings.Contains(s, "state db error") ||
		strings.Contains(s, "corrupted state") ||
		(strings.Contains(s, "shamapstore") && strings.Contains(s, "writabledbexists"))
}

// healXRPLCfgFile patches a stock xrpld.cfg that hardcoded node_size=huge /
// online_delete=512. Returns true when the file changed.
func healXRPLCfgFile(path, env string, hasLedger bool) (bool, error) {
	path = strings.TrimSpace(path)
	if path == "" || !fileExists(path) {
		return false, nil
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return false, err
	}
	orig := string(raw)
	s := orig
	size := xrplNodeSize(float64(ramGB()), hasLedger)
	s = xrplNodeSizeRe.ReplaceAllString(s, "[node_size]\n"+size)
	s = applyXRPLHistoryHeal(s, filepath.Dir(path))
	if !hasLedger {
		s = xrplOnlineDeleteRe.ReplaceAllString(s, "")
	}
	s = xrplEnsurePeersMax(s)
	s = xrplEnsureFetchDepthFull(s)
	s = xrplEnsureClioPorts(s, env)
	if normalizeEnvName(env) != "testnet" {
		s = xrplEnsureStanzaLines(s, "ips", xrplMainnetHubs())
		s = xrplEnsureStanzaLines(s, "ips_fixed", xrplMainnetFixedPeers())
	}
	if s == orig {
		return false, nil
	}
	if err := os.WriteFile(path, []byte(s), 0o644); err != nil {
		return false, err
	}
	return true, nil
}

func applyXRPLHistoryHeal(s, etc string) string {
	if p, ok := loadXRPLHistoryPolicy(etc); ok {
		return applyXRPLHistoryPolicy(s, p)
	}

	if p, ok := parseXRPLHistoryFromCfg(s); ok {
		_ = writeXRPLHistoryPolicy(etc, p)

		return applyXRPLHistoryPolicy(s, p)
	}

	return s
}

func applyXRPLHistoryPolicy(s string, pol xrplHistoryPolicy) string {
	histVal := "full"
	if pol.Mode != "full" && pol.Ledgers > 0 {
		histVal = strconv.Itoa(pol.Ledgers)
	}

	if xrplLedgerHistoryRe.MatchString(s) {
		s = xrplLedgerHistoryRe.ReplaceAllString(s, "[ledger_history]\n"+histVal)
	} else {
		s = strings.TrimRight(s, "\n") + "\n\n[ledger_history]\n" + histVal + "\n"
	}

	if pol.Mode == "full" || pol.Ledgers <= 0 {
		return xrplOnlineDeleteRe.ReplaceAllString(s, "")
	}

	od := fmt.Sprintf("online_delete=%d\n", pol.Ledgers)
	if xrplOnlineDeleteRe.MatchString(s) {
		return xrplOnlineDeleteRe.ReplaceAllString(s, od)
	}

	if strings.Contains(s, "advisory_delete=") {
		return strings.Replace(s, "advisory_delete=", od+"advisory_delete=", 1)
	}

	return strings.TrimRight(s, "\n") + "\n" + od
}

func xrplEnsurePeersMax(s string) string {
	if m := xrplPeersMaxRe.FindStringSubmatch(s); len(m) == 2 {
		n, _ := strconv.Atoi(m[1])
		if n >= 68 {
			return s
		}
		return xrplPeersMaxRe.ReplaceAllString(s, fmt.Sprintf("[peers_max]\n%d", xrplPeersMaxHistory))
	}
	return strings.TrimRight(s, "\n") + fmt.Sprintf("\n\n[peers_max]\n%d\n", xrplPeersMaxHistory)
}

func xrplEnsureClioPorts(s, env string) string {
	s = xrplEnsureServerName(s, "port_ws_public")
	s = xrplEnsureServerName(s, "port_grpc")
	if !strings.Contains(s, "[port_ws_public]") {
		s = strings.TrimRight(s, "\n") + fmt.Sprintf(
			"\n\n[port_ws_public]\nport = %d\nip = 127.0.0.1\nprotocol = ws\n",
			xrplWSPublicPort(env),
		)
	}
	if !strings.Contains(s, "[port_grpc]") {
		s = strings.TrimRight(s, "\n") + fmt.Sprintf(
			"\n\n[port_grpc]\nport = %d\nip = 127.0.0.1\nsecure_gateway = 127.0.0.1\n",
			xrplGRPCPort(env),
		)
	}

	return s
}

func xrplEnsureServerName(s, name string) string {
	if strings.Contains(s, name) {
		return s
	}
	if !strings.Contains(s, "[server]\n") {
		return s
	}

	return strings.Replace(s, "[server]\n", "[server]\n"+name+"\n", 1)
}

func xrplEnsureFetchDepthFull(s string) string {
	if strings.Contains(s, "[fetch_depth]") {
		return s
	}
	return strings.TrimRight(s, "\n") + "\n\n[fetch_depth]\nfull\n"
}

func xrplEnsureStanzaLines(s, name string, lines []string) string {
	missing := make([]string, 0, len(lines))
	for _, line := range lines {
		if !strings.Contains(s, line) {
			missing = append(missing, line)
		}
	}
	if len(missing) == 0 {
		return s
	}
	header := "[" + name + "]\n"
	if idx := strings.Index(s, header); idx >= 0 {
		insertAt := idx + len(header)
		return s[:insertAt] + strings.Join(missing, "\n") + "\n" + s[insertAt:]
	}
	return strings.TrimRight(s, "\n") + "\n\n" + header + strings.Join(missing, "\n") + "\n"
}

func systemdUnitInstalled(unit string) bool {
	unit = strings.TrimSpace(unit)
	if unit == "" {
		return false
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}

	return fileExists("/etc/systemd/system/"+unit) || fileExists("/lib/systemd/system/"+unit)
}

// recycleXRPLUnit — never `systemctl restart`. ExecStop=server_stop hangs when
// LoadManager stalled / RPC is dead; systemd then SIGKILLs auxiliaries and
// returns "Job canceled" / "Invalid argument". Kill the main process, then start.
func recycleXRPLUnit(unit string, cfg Config) error {
	unit = strings.TrimSpace(unit)
	if unit == "" {
		return nil
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	if !systemdUnitInstalled(unit) {
		return fmt.Errorf("systemctl start %s: unit not installed yet", unit)
	}

	if running, _ := xrplProcessRunning(cfg); running {
		xrplGracefulStop(cfg)
	}
	_ = exec.Command("systemctl", "reset-failed", unit).Run()
	out, err := exec.Command("systemctl", "start", unit).CombinedOutput()
	if err != nil {
		return fmt.Errorf("systemctl start %s: %v (%s)", unit, err, strings.TrimSpace(string(out)))
	}
	return nil
}
