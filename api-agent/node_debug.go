package main

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// Node Debug is a read-only tip snapshot for the panel bug icon.
// ❌ No apt-get update, no ufw, no remote shell, no heal from this handler.

type nodeDebugFinding struct {
	Severity string `json:"severity"` // error | warn | info | ok
	Scope    string `json:"scope"`    // host | network
	Code     string `json:"code"`
	Title    string `json:"title"`
	Detail   string `json:"detail,omitempty"`
	Hint     string `json:"hint,omitempty"`
}

type nodeDebugLog struct {
	ID    string   `json:"id"`
	Label string   `json:"label"`
	Path  string   `json:"path,omitempty"`
	Lines []string `json:"lines,omitempty"`
	Note  string   `json:"note,omitempty"`
}

type nodeDebugUnit struct {
	Name      string `json:"name"`
	Active    string `json:"active,omitempty"`
	Sub       string `json:"sub,omitempty"`
	Result    string `json:"result,omitempty"`
	NRestarts int    `json:"nrestarts,omitempty"`
}

type nodeDebugReport struct {
	OK          bool               `json:"ok"`
	Network     string             `json:"network"`
	Env         string             `json:"env"`
	CollectedAt string             `json:"collected_at"`
	ErrorCount  int                `json:"error_count"`
	WarnCount   int                `json:"warn_count"`
	Findings    []nodeDebugFinding `json:"findings"`
	Units       []nodeDebugUnit    `json:"units"`
	Procs       []string           `json:"procs,omitempty"`
	Logs        []nodeDebugLog     `json:"logs"`
}

var (
	aptReleaseMissingRe = regexp.MustCompile(`(?i)The repository '([^']+)' does not have a Release file`)
	aptLockLineRe       = regexp.MustCompile(`(?i)Could not get lock|Unable to acquire the dpkg frontend lock|dpkg frontend is locked|Could not get lock /var/lib/(?:dpkg|apt)`)
	tonInstallExitRe    = regexp.MustCompile(`(?i)install\.sh\s+attempt=(\d+)\s+exit=(\d+)`)
)

func (s *Server) handleNodesDebug(w http.ResponseWriter, r *http.Request) {
	network := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("network")))
	env := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("env")))
	if network == "" || env == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"ok": false, "error": "network_env_required",
			"message": "GET /api/v1/nodes/debug?network=&env=",
		})
		return
	}
	if network == "avalanche" {
		env = normalizeAvalancheEnv(env)
	}
	writeJSON(w, http.StatusOK, collectNodeDebug(network, env))
}

func collectNodeDebug(network, env string) nodeDebugReport {
	rep := nodeDebugReport{
		OK:          true,
		Network:     network,
		Env:         env,
		CollectedAt: time.Now().UTC().Format(time.RFC3339),
		Findings:    []nodeDebugFinding{},
		Units:       []nodeDebugUnit{},
		Logs:        []nodeDebugLog{},
	}

	rep.Findings = append(rep.Findings, debugHostAptFindings()...)
	rep.Findings = append(rep.Findings, debugHostDiskFindings(network, env)...)
	rep.Findings = append(rep.Findings, debugMissingConfFindings(network, env)...)
	rep.Procs = debugInterestingProcs(network)
	rep.Units = debugUnitStates(debugUnitsFor(network, env))
	rep.Findings = append(rep.Findings, debugUnitFindings(network, env, rep.Units)...)
	rep.Findings = append(rep.Findings, debugNetworkFindings(network, env, rep.Units)...)
	rep.Logs = append(rep.Logs, debugCollectLogs(network, env)...)
	rep.Findings = append(rep.Findings, debugHostAuditFindings(network)...)

	if len(rep.Findings) == 0 {
		rep.Findings = append(rep.Findings, nodeDebugFinding{
			Severity: "ok",
			Scope:    "host",
			Code:     "no_signals",
			Title:    "No host or install errors parsed",
			Detail:   "Units, apt sources, and recent logs look quiet. Open a log tab below if you still need the raw tail.",
		})
	}

	for _, f := range rep.Findings {
		switch f.Severity {
		case "error":
			rep.ErrorCount++
		case "warn":
			rep.WarnCount++
		}
	}
	return rep
}

func debugUnitsFor(network, env string) []string {
	seen := map[string]bool{}
	var out []string
	add := func(u string) {
		u = strings.TrimSpace(u)
		if u == "" {
			return
		}
		if !strings.HasSuffix(u, ".service") {
			u += ".service"
		}
		if seen[u] {
			return
		}
		seen[u] = true
		out = append(out, u)
	}
	for _, u := range nodeUnitsForRemove(network, env) {
		add(u)
	}
	for _, u := range perNodeAgentUnits(network, env) {
		add(u)
	}
	if network == "xrpl" {
		add("scylla-server.service")
	}
	return out
}

func debugUnitStates(units []string) []nodeDebugUnit {
	out := make([]nodeDebugUnit, 0, len(units))
	for _, name := range units {
		u := nodeDebugUnit{Name: name}
		raw, err := cmdOut("systemctl", "show",
			"-p", "ActiveState", "-p", "SubState", "-p", "Result", "-p", "NRestarts",
			name)
		if err != nil {
			u.Active = "unknown"
			out = append(out, u)
			continue
		}
		for _, line := range strings.Split(string(raw), "\n") {
			k, v, ok := strings.Cut(line, "=")
			if !ok {
				continue
			}
			switch k {
			case "ActiveState":
				u.Active = strings.TrimSpace(v)
			case "SubState":
				u.Sub = strings.TrimSpace(v)
			case "Result":
				u.Result = strings.TrimSpace(v)
			case "NRestarts":
				fmt.Sscanf(strings.TrimSpace(v), "%d", &u.NRestarts)
			}
		}
		out = append(out, u)
	}
	return out
}

func debugUnitFindings(network, env string, units []nodeDebugUnit) []nodeDebugFinding {
	_ = env
	var out []nodeDebugFinding
	for _, u := range units {
		// TON oneshot linger / bootstrap / stock validator — debugTonFindings.
		if network == "ton" && (strings.Contains(u.Name, "-bootstrap") ||
			u.Name == "validator.service" || u.Name == "mytoncore.service") {
			continue
		}
		// Inactive oneshot (snapshot) with leftover Result is not a live fault.
		if u.Active != "failed" && u.Active != "activating" {
			continue
		}
		if u.Active == "activating" && u.NRestarts < 3 && u.Result != "timeout" && u.Result != "failed" {
			continue
		}
		if u.Active != "failed" && u.NRestarts < 3 {
			continue
		}
		sev := "error"
		code := "unit_failed"
		title := u.Name + " failed"
		if u.Active != "failed" && u.NRestarts >= 3 {
			sev = "warn"
			code = "unit_crash_loop"
			title = u.Name + " restarting (" + fmt.Sprintf("%d", u.NRestarts) + ")"
		}
		out = append(out, nodeDebugFinding{
			Severity: sev,
			Scope:    "network",
			Code:     code,
			Title:    title,
			Detail:   strings.TrimSpace(u.Active + "/" + u.Sub + " result=" + u.Result),
			Hint:     "Open the unit / signals tab. Do not Restart from the panel while a snapshot or bootstrap is still applying.",
		})
	}
	return out
}

func debugHostAptFindings() []nodeDebugFinding {
	var out []nodeDebugFinding
	dir := "/etc/apt/sources.list.d"
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		name := e.Name()
		path := filepath.Join(dir, name)
		low := strings.ToLower(name)
		if strings.HasSuffix(low, ".rpcnode-disabled") {
			out = append(out, nodeDebugFinding{
				Severity: "info",
				Scope:    "host",
				Code:     "apt_source_disabled",
				Title:    "Leftover apt source already disabled",
				Detail:   path,
			})
			continue
		}
		if !strings.HasSuffix(low, ".list") && !strings.HasSuffix(low, ".sources") {
			continue
		}
		b, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		body := string(b)
		if aptSourceLooksLeftover(name, body) {
			out = append(out, nodeDebugFinding{
				Severity: "error",
				Scope:    "host",
				Code:     "apt_leftover_repo",
				Title:    "Broken leftover apt repository",
				Detail:   path + " — " + firstNonEmptyLine(body),
				Hint:     "This is not a Toolkit package. Disable the list (rename to *.rpcnode-disabled) so apt-get update / MyTonCtrl install.sh can proceed. Debug does not change apt sources.",
			})
		}
	}

	aptText := debugReadTailText("/var/log/apt/term.log", 80*1024)
	out = append(out, parseAptLogFindings(aptText)...)
	return out
}

func aptSourceLooksLeftover(name, body string) bool {
	blob := strings.ToLower(name + "\n" + body)
	return strings.Contains(blob, "ookla") ||
		strings.Contains(blob, "speedtest-cli") ||
		strings.Contains(blob, "packagecloud.io/ookla")
}

func parseAptLogFindings(text string) []nodeDebugFinding {
	var out []nodeDebugFinding
	seenURL := map[string]bool{}
	for _, m := range aptReleaseMissingRe.FindAllStringSubmatch(text, 8) {
		url := strings.TrimSpace(m[1])
		if url == "" || seenURL[url] {
			continue
		}
		seenURL[url] = true
		out = append(out, nodeDebugFinding{
			Severity: "error",
			Scope:    "host",
			Code:     "apt_release_missing",
			Title:    "apt source has no Release file",
			Detail:   url,
			Hint:     "A leftover .list is 404. Disable that source — install.sh exit 100 here is not a dpkg lock.",
		})
	}
	if aptLockLineRe.MatchString(text) && !strings.Contains(strings.ToLower(text), "does not have a release file") {
		out = append(out, nodeDebugFinding{
			Severity: "warn",
			Scope:    "host",
			Code:     "apt_lock",
			Title:    "apt/dpkg lock seen recently",
			Detail:   lastMatchingLine(text, aptLockLineRe),
			Hint:     "Wait for unattended-upgrades / another apt to finish. Retry is OK only when this is a real lock.",
		})
	}
	return out
}

func debugHostDiskFindings(network, env string) []nodeDebugFinding {
	prof := lookupPortProfile(network, env)
	paths := []string{"/data", "/opt", "/var"}
	if p := strings.TrimSpace(prof.DataPath); p != "" {
		paths = append(paths, p)
	}
	seen := map[string]bool{}
	var out []nodeDebugFinding
	for _, p := range paths {
		total, avail, ok := statfsBytes(p)
		if !ok || total == 0 {
			continue
		}
		key := fmt.Sprintf("%d/%d", total, avail)
		if seen[key] {
			continue
		}
		seen[key] = true
		freeGiB := float64(avail) / (1024 * 1024 * 1024)
		totalGiB := float64(total) / (1024 * 1024 * 1024)
		usedPct := 100 * (1 - float64(avail)/float64(total))
		detail := fmt.Sprintf("%s · %.0f / %.0f GiB free (%.0f%% used)", p, freeGiB, totalGiB, usedPct)
		switch {
		case freeGiB < 5:
			out = append(out, nodeDebugFinding{
				Severity: "error", Scope: "host", Code: "disk_full",
				Title: "Disk almost full", Detail: detail,
				Hint: "Install / dump / IBD will fail. Free space or use another mount before retry.",
			})
		case freeGiB < 20 && totalGiB > 40:
			out = append(out, nodeDebugFinding{
				Severity: "warn", Scope: "host", Code: "disk_low",
				Title: "Low free disk", Detail: detail,
			})
		}
	}
	return out
}

func debugTonFindings(env string, units []nodeDebugUnit) []nodeDebugFinding {
	var out []nodeDebugFinding
	marker := filepath.Join("/etc/ton", env, "bootstrap.done")
	done := fileExists(marker)
	logPath := filepath.Join("/var/log/ton", env, "bootstrap.log")
	text := debugReadTailText(logPath, 256*1024)
	out = append(out, parseTonBootstrapFindings(text)...)

	var boot, validator nodeDebugUnit
	for _, u := range units {
		switch u.Name {
		case fmt.Sprintf("ton-%s-bootstrap.service", env):
			boot = u
		case "validator.service":
			validator = u
		}
	}
	bootLive := boot.Active == "activating" || boot.Active == "active" || boot.Sub == "start" || boot.Sub == "running"
	if !done && boot.Active == "failed" {
		out = append(out, nodeDebugFinding{
			Severity: "error",
			Scope:    "network",
			Code:     "ton_bootstrap_failed",
			Title:    "TON bootstrap unit failed",
			Detail:   boot.Name + " " + boot.Active + "/" + boot.Sub + " result=" + boot.Result,
			Hint:     "Open TON signals / bootstrap.log. If the cause is a leftover apt Release file, disable that .list and restart only the bootstrap unit.",
		})
	}
	if !done && bootLive {
		if hasFindingCode(out, "ton_apt_release") || hasFindingCode(out, "apt_release_missing") {
			out = append(out, nodeDebugFinding{
				Severity: "error",
				Scope:    "network",
				Code:     "ton_bootstrap_apt_loop",
				Title:    "TON bootstrap looping on a broken apt repo",
				Detail:   "ton-" + env + "-bootstrap is still activating; install.sh exit 100 is the leftover Release-file source, not dpkg busy.",
				Hint:     "Disable the leftover apt .list on the host, then restart only ton-" + env + "-bootstrap. Do not Start / Restart the validator from the panel.",
			})
		} else if !hasFindingCode(out, "ton_install_exit") {
			out = append(out, nodeDebugFinding{
				Severity: "info",
				Scope:    "network",
				Code:     "ton_bootstrap_running",
				Title:    "TON bootstrap still running",
				Detail:   boot.Active + "/" + boot.Sub + " · no bootstrap.done yet",
			})
		}
	}
	if validator.Active == "failed" && (done || !bootLive) {
		out = append(out, nodeDebugFinding{
			Severity: "error",
			Scope:    "network",
			Code:     "ton_validator_failed",
			Title:    "validator.service failed",
			Detail:   validator.Active + "/" + validator.Sub + " result=" + validator.Result,
			Hint:     "OOM or unit rewrite — see TON OPS. Do not Restart during dump apply (seqno=0).",
		})
	}
	if !done && validator.Active == "inactive" && bootLive {
		out = append(out, nodeDebugFinding{
			Severity: "info",
			Scope:    "network",
			Code:     "ton_validator_waiting",
			Title:    "validator not started yet",
			Detail:   "Expected while MyTonCtrl install.sh / dump is still running.",
		})
	}
	return out
}

func parseTonBootstrapFindings(text string) []nodeDebugFinding {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	var out []nodeDebugFinding
	low := strings.ToLower(text)
	if m := aptReleaseMissingRe.FindStringSubmatch(text); len(m) >= 2 {
		out = append(out, nodeDebugFinding{
			Severity: "error",
			Scope:    "network",
			Code:     "ton_apt_release",
			Title:    "install.sh apt update failed (no Release file)",
			Detail:   m[1],
			Hint:     "Leftover host apt source. Not an apt lock. TON bootstrap ≥0.4.215 disables 404 lists; this stuck job still needs the list disabled + bootstrap restart.",
		})
	}
	if m := tonInstallExitRe.FindAllStringSubmatch(text, -1); len(m) > 0 {
		last := m[len(m)-1]
		exit := last[2]
		if exit != "0" {
			sev := "warn"
			if exit == "100" && strings.Contains(low, "does not have a release file") {
				sev = "error"
			}
			out = append(out, nodeDebugFinding{
				Severity: sev,
				Scope:    "network",
				Code:     "ton_install_exit",
				Title:    "install.sh exit " + exit,
				Detail:   "attempt=" + last[1] + " exit=" + exit,
				Hint:     map[bool]string{true: "Exit 100 + Release file = broken apt repo, not dpkg lock.", false: "See bootstrap.log signals."}[exit == "100"],
			})
		}
	}
	if strings.Contains(low, "failed setrlimit") ||
		(strings.Contains(low, "setrlimit") && strings.Contains(low, "not permitted")) {
		out = append(out, nodeDebugFinding{
			Severity: "warn", Scope: "network", Code: "ton_setrlimit",
			Title:  "validator-engine setrlimit NOFILE denied",
			Detail: lastLineContaining(text, []string{"setrlimit"}),
			Hint:   "Engine wants 1.5M fds. Agent sets LimitNOFILE=4M and fs.nr_open=8M. Do not Restart during dump apply.",
		})
	}
	if strings.Contains(low, "home not set") {
		out = append(out, nodeDebugFinding{
			Severity: "error", Scope: "network", Code: "ton_home_unset",
			Title: "HOME not set during bootstrap",
		})
	}
	if strings.Contains(low, "timeout waiting for apt") {
		out = append(out, nodeDebugFinding{
			Severity: "warn", Scope: "network", Code: "ton_apt_wait_timeout",
			Title: "Bootstrap timed out waiting for apt/dpkg",
		})
	}
	return dedupeFindings(out)
}

func debugInterestingProcs(network string) []string {
	pat := debugProcPattern(network)
	raw, err := cmdOut("pgrep", "-af", pat)
	if err != nil {
		return nil
	}
	var out []string
	for _, ln := range strings.Split(string(raw), "\n") {
		ln = strings.TrimSpace(ln)
		if ln == "" || strings.Contains(ln, "pgrep") {
			continue
		}
		out = append(out, ln)
		if len(out) >= 20 {
			break
		}
	}
	return out
}

func parseTonBootstrapSignalLines(text string, n int) []string {
	if n <= 0 {
		n = 20
	}
	var pick []string
	for _, ln := range strings.Split(text, "\n") {
		ln = strings.TrimSpace(ln)
		if ln == "" {
			continue
		}
		low := strings.ToLower(ln)
		if strings.Contains(low, "install.sh attempt") ||
			strings.Contains(low, "install.sh exit") ||
			strings.Contains(low, "gib/") ||
			strings.Contains(low, "waiting for apt") ||
			strings.Contains(low, "error") ||
			strings.Contains(low, "fatal") ||
			strings.Contains(low, "could not get lock") ||
			strings.Contains(low, "home not set") ||
			strings.Contains(low, "bootstrap marker") ||
			strings.Contains(low, "does not have a release file") {
			pick = append(pick, ln)
		}
	}
	if len(pick) > n {
		pick = pick[len(pick)-n:]
	}
	return pick
}

func debugHostAuditFindings(network string) []nodeDebugFinding {
	text := debugReadTailText("/var/log/rpcnode.log", 64*1024)
	if text == "" {
		return nil
	}
	net := strings.ToLower(strings.TrimSpace(network))
	var out []nodeDebugFinding
	for _, ln := range reverseNonEmpty(strings.Split(text, "\n"), 120) {
		low := strings.ToLower(ln)
		if !strings.Contains(low, " error ") && !strings.Contains(low, " fail") &&
			!strings.Contains(low, "fatal") {
			continue
		}
		if net != "" && !strings.Contains(low, net) && !strings.Contains(low, "host") {
			continue
		}
		out = append(out, nodeDebugFinding{
			Severity: "warn",
			Scope:    "host",
			Code:     "host_audit_error",
			Title:    "Recent host audit error",
			Detail:   truncateRunesAPI(strings.TrimSpace(ln), 240),
		})
		break
	}
	return out
}

func debugReadTailText(path string, maxBytes int) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	if maxBytes > 0 && len(b) > maxBytes {
		b = b[len(b)-maxBytes:]
		if i := strings.IndexByte(string(b), '\n'); i >= 0 && i+1 < len(b) {
			b = b[i+1:]
		}
	}
	return strings.ReplaceAll(string(b), "\r", "\n")
}

func firstNonEmptyLine(s string) string {
	for _, ln := range strings.Split(s, "\n") {
		ln = strings.TrimSpace(ln)
		if ln != "" && !strings.HasPrefix(ln, "#") {
			return ln
		}
	}
	return ""
}

func lastMatchingLine(text string, re *regexp.Regexp) string {
	var last string
	for _, ln := range strings.Split(text, "\n") {
		if re.MatchString(ln) {
			last = strings.TrimSpace(ln)
		}
	}
	return last
}

func nonEmptyTailLines(text string, n int) []string {
	var out []string
	for _, ln := range strings.Split(text, "\n") {
		ln = strings.TrimSpace(ln)
		if ln == "" {
			continue
		}
		out = append(out, ln)
	}
	if n > 0 && len(out) > n {
		out = out[len(out)-n:]
	}
	return out
}

func reverseNonEmpty(lines []string, maxScan int) []string {
	var out []string
	for i := len(lines) - 1; i >= 0 && len(out) < maxScan; i-- {
		ln := strings.TrimSpace(lines[i])
		if ln == "" {
			continue
		}
		out = append(out, ln)
	}
	return out
}

func hasFindingCode(in []nodeDebugFinding, code string) bool {
	for _, f := range in {
		if f.Code == code {
			return true
		}
	}
	return false
}

func dedupeFindings(in []nodeDebugFinding) []nodeDebugFinding {
	seen := map[string]bool{}
	var out []nodeDebugFinding
	for _, f := range in {
		k := f.Code + "|" + f.Detail
		if seen[k] {
			continue
		}
		seen[k] = true
		out = append(out, f)
	}
	return out
}

func truncateRunesAPI(s string, max int) string {
	if max <= 0 || len(s) <= max {
		return s
	}
	return s[:max] + "…"
}
