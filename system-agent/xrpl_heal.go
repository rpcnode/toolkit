package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
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
	_ = os.WriteFile(marker, []byte("reinit after failed first ledger acquire\n"), 0o644)
	return true, nil
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
	s = xrplLedgerHistoryRe.ReplaceAllString(s, "[ledger_history]\nfull")
	s = xrplOnlineDeleteRe.ReplaceAllString(s, "")
	s = xrplEnsurePeersMax(s)
	s = xrplEnsureFetchDepthFull(s)
	if normalizeEnvName(env) != "testnet" {
		s = xrplEnsureStanzaLines(s, "ips", xrplMainnetHubs())
		s = xrplEnsureStanzaLines(s, "ips_fixed", []string{"s2.ripple.com 51235"})
	}
	if s == orig {
		return false, nil
	}
	if err := os.WriteFile(path, []byte(s), 0o644); err != nil {
		return false, err
	}
	return true, nil
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

// recycleXRPLUnit — never `systemctl restart`. ExecStop=server_stop hangs when
// LoadManager stalled / RPC is dead; systemd then SIGKILLs auxiliaries and
// returns "Job canceled" / "Invalid argument". Kill the main process, then start.
func recycleXRPLUnit(unit string) error {
	unit = strings.TrimSpace(unit)
	if unit == "" {
		return nil
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	_ = exec.Command("systemctl", "kill", "-s", "SIGKILL", "--kill-who=main", unit).Run()
	_ = exec.Command("systemctl", "reset-failed", unit).Run()
	out, err := exec.Command("systemctl", "start", unit).CombinedOutput()
	if err != nil {
		return fmt.Errorf("systemctl start %s: %v (%s)", unit, err, strings.TrimSpace(string(out)))
	}
	return nil
}
