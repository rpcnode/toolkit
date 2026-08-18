package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

const defaultAgentInstallBase = "https://toolkit.rpcnode.dev/install"
const defaultClientsBase = "https://toolkit.rpcnode.dev"

var agentUpdateMu sync.Mutex

func clientsBaseURL() string {
	if u := strings.TrimSpace(os.Getenv("CLIENTS_BASE_URL")); u != "" {
		return strings.TrimRight(u, "/")
	}
	return defaultClientsBase
}

func agentInstallBaseURL() string {
	if u := strings.TrimSpace(os.Getenv("INSTALL_BASE_URL")); u != "" {
		return strings.TrimRight(u, "/")
	}
	if u := strings.TrimSpace(os.Getenv("AGENT_DOWNLOAD_URL")); u != "" {
		u = strings.TrimRight(u, "/")
		if strings.HasSuffix(u, "/agent.sh") {
			return strings.TrimSuffix(u, "/agent.sh")
		}
		if strings.HasSuffix(u, "/install/agent.sh") {
			return strings.TrimSuffix(u, "/agent.sh")
		}
		return u
	}
	return defaultAgentInstallBase
}

func agentBinDir() string {
	if d := strings.TrimSpace(os.Getenv("RPCNODE_BIN_DIR")); d != "" {
		return d
	}
	// Canonical install root — never prefer /usr/local/bin (PATH symlink only).
	if st, err := os.Stat("/opt/rpcnode/bin"); err == nil && st.IsDir() {
		return "/opt/rpcnode/bin"
	}
	if ex, err := os.Executable(); err == nil {
		dir := filepath.Dir(ex)
		// Ignore /usr/local/bin — that is a symlink helper, not the install dir.
		if dir != "" && dir != "." && dir != "/usr/local/bin" {
			return dir
		}
	}
	return "/opt/rpcnode/bin"
}

func fetchRemoteToolkitVersion() (string, error) {
	url := agentInstallBaseURL() + "/TOOLKIT_VERSION"
	client := &http.Client{Timeout: 20 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return "", fmt.Errorf("HTTP %d fetching %s", resp.StatusCode, url)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 256))
	if err != nil {
		return "", err
	}
	v := strings.TrimSpace(string(b))
	if v == "" {
		return "", fmt.Errorf("empty TOOLKIT_VERSION")
	}
	return v, nil
}

func (s *Server) handleAgentV1(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSuffix(r.URL.Path, "/")
	switch {
	case (path == "/api/v1/agent" || path == "/api/agent") && r.Method == http.MethodGet:
		s.handleAgentInfo(w, r)
	case (path == "/api/v1/agent/logs" || path == "/api/agent/logs") && r.Method == http.MethodGet:
		s.handleAgentLogs(w, r)
	case (path == "/api/v1/agent/check" || path == "/api/agent/check") && r.Method == http.MethodPost:
		s.handleAgentCheck(w, r)
	case (path == "/api/v1/agent/update" || path == "/api/agent/update") && r.Method == http.MethodPost:
		s.handleAgentUpdate(w, r)
	case (path == "/api/v1/agent/restart" || path == "/api/agent/restart") && r.Method == http.MethodPost:
		s.handleAgentRestart(w, r)
	default:
		writeJSON(w, http.StatusNotFound, map[string]any{
			"ok": false, "error": "not_found",
			"message": "GET /api/v1/agent[/logs] | POST /api/v1/agent/check|update|restart",
		})
	}
}

func (s *Server) handleAgentInfo(w http.ResponseWriter, r *http.Request) {
	_ = r
	local := agentVersion()
	remote, _ := fetchRemoteToolkitVersion()
	network := strings.ToLower(strings.TrimSpace(envOr("TRON_NETWORK", "")))
	out := map[string]any{
		"ok":               true,
		"version":          local,
		"local_version":    local,
		"remote_version":   remote,
		"update_available": remote != "" && remote != local,
		"channel":          agentInstallBaseURL() + "/TOOLKIT_VERSION",
		"binaries_base":    agentInstallBaseURL() + "/binaries",
		"bin_dir":          agentBinDir(),
		"env":              s.cfg.Env,
		"units":            agentUnitNames(s.cfg.Env),
		"goos":             runtime.GOOS,
		"goarch":           runtime.GOARCH,
		"supported_networks": supportedNetworks(),
	}
	if network != "" {
		out["network"] = network
		out["supported_steps"] = supportedLifecycleSteps(network, s.cfg.Env)
		out["capabilities"] = lifecycleCapabilities(network, s.cfg.Env)
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleAgentCheck(w http.ResponseWriter, r *http.Request) {
	_ = r
	local := agentVersion()
	remote, err := fetchRemoteToolkitVersion()
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"ok": false, "error": "check_failed", "message": err.Error(),
			"version": local, "local_version": local,
		})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":               true,
		"version":          local,
		"local_version":    local,
		"remote_version":   remote,
		"update_available": remote != local,
		"message": map[bool]string{
			true:  fmt.Sprintf("update available: %s → %s", local, remote),
			false: "up to date (" + local + ")",
		}[remote != local],
	})
}

func (s *Server) handleAgentUpdate(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Force bool `json:"force"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)

	if !agentUpdateMu.TryLock() {
		hostLog("WARN", "api-agent", "update", "already running")
		writeJSON(w, http.StatusConflict, map[string]any{
			"ok": false, "error": "update_in_progress", "message": "agent update already running",
		})
		return
	}

	local := agentVersion()
	remote, err := fetchRemoteToolkitVersion()
	if err != nil {
		hostLogf("ERROR", "api-agent", "update", "check_failed local=%s err=%v", local, err)
		agentUpdateMu.Unlock()
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"ok": false, "error": "check_failed", "message": err.Error(), "version": local,
		})
		return
	}
	hostLogf("INFO", "api-agent", "update", "begin local=%s remote=%s force=%v", local, remote, body.Force)
	if remote == local && !body.Force {
		// Idempotent: hosts already on latest but missing watchdog / file logs / nodeop groups.
		wdSteps, wdErr := ensureAgentWatchdog()
		logSteps, logErr := ensureAgentFileLogging()
		ipSteps, ipErr := ensureAllNodeIPAccounting()
		steps := append(wdSteps, logSteps...)
		steps = append(steps, ipSteps...)
		if err := ensureNodeopUser(); err != nil {
			steps = append(steps, "nodeop: "+err.Error())
		} else {
			steps = append(steps, "nodeop user + journal groups ok")
		}
		agentUpdateMu.Unlock()
		if wdErr != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{
				"ok": false, "error": "watchdog_install_failed", "message": wdErr.Error(),
				"version": local, "remote_version": remote, "steps": steps,
			})
			return
		}
		if logErr != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{
				"ok": false, "error": "file_logging_failed", "message": logErr.Error(),
				"version": local, "remote_version": remote, "steps": steps,
			})
			return
		}
		if ipErr != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{
				"ok": false, "error": "ip_accounting_failed", "message": ipErr.Error(),
				"version": local, "remote_version": remote, "steps": steps,
			})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "updated": false, "version": local, "remote_version": remote,
			"steps":   steps,
			"message": "already on " + local + "; watchdog + file logs + nodeop + IPAccounting ensured",
		})
		return
	}

	steps, err := installAgentBinariesFromCDN()
	if err != nil {
		hostLogf("ERROR", "api-agent", "update", "download/install failed %s → %s: %v", local, remote, err)
		agentUpdateMu.Unlock()
		writeJSON(w, http.StatusInternalServerError, map[string]any{
			"ok": false, "error": "update_failed", "message": err.Error(),
			"version": local, "remote_version": remote, "steps": steps,
		})
		return
	}
	// Same product contract as install/agent.sh → install_agent_watchdog + file logs:
	// panel Update must leave rpcnode-agent-watchdog installed+enabled (not binaries-only)
	// and tip/leaf agents writing under /var/log/rpcnode (logrotate by size).
	wdSteps, wdErr := ensureAgentWatchdog()
	steps = append(steps, wdSteps...)
	if wdErr != nil {
		agentUpdateMu.Unlock()
		writeJSON(w, http.StatusInternalServerError, map[string]any{
			"ok": false, "error": "watchdog_install_failed", "message": wdErr.Error(),
			"version": local, "remote_version": remote, "steps": steps,
		})
		return
	}
	logSteps, logErr := ensureAgentFileLogging()
	steps = append(steps, logSteps...)
	if logErr != nil {
		agentUpdateMu.Unlock()
		writeJSON(w, http.StatusInternalServerError, map[string]any{
			"ok": false, "error": "file_logging_failed", "message": logErr.Error(),
			"version": local, "remote_version": remote, "steps": steps,
		})
		return
	}
	ipSteps, ipErr := ensureAllNodeIPAccounting()
	steps = append(steps, ipSteps...)
	if ipErr != nil {
		agentUpdateMu.Unlock()
		writeJSON(w, http.StatusInternalServerError, map[string]any{
			"ok": false, "error": "ip_accounting_failed", "message": ipErr.Error(),
			"version": local, "remote_version": remote, "steps": steps,
		})
		return
	}
	// nodeop + systemd-journal/adm — chain units + journal-based sync % (idempotent).
	if err := ensureNodeopUser(); err != nil {
		steps = append(steps, "nodeop: "+err.Error())
	} else {
		steps = append(steps, "nodeop user + journal groups ok")
	}
	// Version after restart comes from the new binary (embedded toolkitVersion), not a disk file.

	units := unitsForAgentRestart(s.cfg.Env)
	result := map[string]any{
		"ok":             true,
		"updated":        true,
		"version":        remote,
		"local_version":  local,
		"remote_version": remote,
		"steps":          steps,
		"units":          units,
		"restart":        "scheduled",
		"message":        fmt.Sprintf("binaries+watchdog+file-logs installed %s → %s; restarting units", local, remote),
	}
	hostLogf("INFO", "api-agent", "update", "binaries ok %s → %s; restarting units", local, remote)
	writeJSON(w, http.StatusOK, result)
	if f, ok := w.(http.Flusher); ok {
		f.Flush()
	}

	go func() {
		defer agentUpdateMu.Unlock()
		time.Sleep(600 * time.Millisecond)
		// Run heal with the NEW binary (this process still has old code in memory).
		newBin := filepath.Join(agentBinDir(), "rpcnode-api-agent")
		if out, err := exec.Command(newBin, "--heal-provisioned").CombinedOutput(); err != nil {
			hostLogf("ERROR", "api-agent", "heal", "%v (%s)", err, strings.TrimSpace(string(out)))
			log.Printf("heal-provisioned via new binary: %v (%s)", err, strings.TrimSpace(string(out)))
		} else if msg := strings.TrimSpace(string(out)); msg != "" {
			for _, line := range strings.Split(msg, "\n") {
				if strings.TrimSpace(line) != "" {
					log.Printf("heal-provisioned: %s", line)
				}
			}
		}
		_ = restartAgentUnits(s.cfg.Env)
	}()
}

func (s *Server) handleAgentRestart(w http.ResponseWriter, r *http.Request) {
	_ = r
	units := unitsForAgentRestart(s.cfg.Env)
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "version": agentVersion(), "units": units,
		"restart": "scheduled",
		"message": "restarting agent units",
	})
	if f, ok := w.(http.Flusher); ok {
		f.Flush()
	}
	go func() {
		time.Sleep(400 * time.Millisecond)
		_ = restartAgentUnits(s.cfg.Env)
	}()
}

func agentUnitNames(env string) []string {
	env = strings.TrimSpace(strings.ToLower(env))
	// Empty TRON_NETWORK = host tip — do NOT default to "tron" (that skipped stellar/… leaves).
	network := strings.ToLower(strings.TrimSpace(os.Getenv("TRON_NETWORK")))
	if network != "" {
		network = normalizeNetwork(network)
	}
	out := []string{}
	// Canonical per-node leaves: rpcnode-*-agent-<network>-<env>.service
	if network != "" && env != "" {
		out = append(out,
			fmt.Sprintf("rpcnode-system-agent-%s-%s.service", network, env),
			fmt.Sprintf("rpcnode-api-agent-%s-%s.service", network, env),
		)
	}
	// Tron without TRON_NETWORK still uses network slug tron-<env>.
	if (network == "" || network == "tron") && env != "" {
		out = append(out,
			fmt.Sprintf("rpcnode-system-agent-tron-%s.service", env),
			fmt.Sprintf("rpcnode-api-agent-tron-%s.service", env),
		)
		// Legacy env-only (rpcnode-api-agent-mainnet) — restart/cleanup only if unit file exists.
		out = append(out,
			fmt.Sprintf("rpcnode-system-agent-%s.service", env),
			fmt.Sprintf("rpcnode-api-agent-%s.service", env),
		)
	}
	out = append(out, "rpcnode-system-agent.service", "rpcnode-api-agent.service")
	return uniqStrings(out)
}

// provisionedLeafAgentUnits — all per-node agent units on disk (stellar, bitcoin, …).
func provisionedLeafAgentUnits() []string {
	matches, err := filepath.Glob("/etc/systemd/system/rpcnode-*-agent-*.service")
	if err != nil {
		return nil
	}
	out := make([]string, 0, len(matches))
	for _, p := range matches {
		base := filepath.Base(p)
		if base == "" {
			continue
		}
		out = append(out, base)
	}
	return out
}

func uniqStrings(in []string) []string {
	seen := map[string]bool{}
	out := make([]string, 0, len(in))
	for _, s := range in {
		s = strings.TrimSpace(s)
		if s == "" || seen[s] {
			continue
		}
		seen[s] = true
		out = append(out, s)
	}
	return out
}

// tipBootstrapUnit — host Server control-plane unit (no network-env suffix).
func tipBootstrapUnit(name string) bool {
	switch name {
	case "rpcnode-api-agent.service", "rpcnode-system-agent.service",
		"tron-api-agent.service", "tron-system-agent.service":
		return true
	default:
		return false
	}
}

// unitsForAgentRestart — tip Update must include every leaf unit (shared binaries).
func unitsForAgentRestart(env string) []string {
	units := agentUnitNames(env)
	// Tip Update agent must bounce EVERY leaf (shared /opt/rpcnode/bin binaries).
	// Otherwise tip reaches 0.4.N while stellar/doge/… leaves keep the old process.
	if isHostTipProcess() {
		units = append(units, provisionedLeafAgentUnits()...)
	}
	return uniqStrings(units)
}

// orderUnitsLeavesBeforeTip — restart tip LAST. Tip update used to restart
// rpcnode-api-agent.service mid-list and kill this process before stellar/… leaves.
func orderUnitsLeavesBeforeTip(units []string) (others, tip []string) {
	for _, u := range units {
		if tipBootstrapUnit(u) {
			tip = append(tip, u)
		} else {
			others = append(others, u)
		}
	}
	return others, tip
}

func restartAgentUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	units := unitsForAgentRestart(env)
	others, tip := orderUnitsLeavesBeforeTip(units)
	var last error
	restartOne := func(u string) {
		if err := exec.Command("systemctl", "cat", u).Run(); err != nil {
			return
		}
		if err := exec.Command("systemctl", "restart", u).Run(); err != nil {
			last = err
			_ = exec.Command("systemctl", "try-restart", u).Run()
		}
	}
	// Leaves (+ legacy env units) first — must finish before we kill ourselves.
	for _, u := range others {
		restartOne(u)
	}
	// Tip bootstrap last (system then api) — restarting api may not return.
	for _, u := range tip {
		restartOne(u)
	}
	return last
}

func installAgentBinariesFromCDN() ([]string, error) {
	steps := []string{}
	base := agentInstallBaseURL() + "/binaries"
	goos, goarch := runtime.GOOS, runtime.GOARCH
	apiName := fmt.Sprintf("rpcnode-api-agent-%s-%s", goos, goarch)
	sysName := fmt.Sprintf("rpcnode-system-agent-%s-%s", goos, goarch)
	binDir := agentBinDir()
	if err := os.MkdirAll(binDir, 0o755); err != nil {
		return steps, fmt.Errorf("mkdir %s: %w", binDir, err)
	}

	sums, _ := fetchSHA256Sums(base + "/sha256sums.txt")
	for _, item := range []struct{ name, dest string }{
		{apiName, filepath.Join(binDir, "rpcnode-api-agent")},
		{sysName, filepath.Join(binDir, "rpcnode-system-agent")},
	} {
		tmp := item.dest + ".new"
		if err := downloadBinaryFile(base+"/"+item.name, tmp); err != nil {
			return steps, err
		}
		if want, ok := sums[item.name]; ok && want != "" {
			got, err := fileSHA256(tmp)
			if err != nil {
				_ = os.Remove(tmp)
				return steps, err
			}
			if !strings.EqualFold(got, want) {
				_ = os.Remove(tmp)
				return steps, fmt.Errorf("checksum mismatch for %s", item.name)
			}
			steps = append(steps, "checksum ok "+item.name)
		}
		if err := os.Chmod(tmp, 0o755); err != nil {
			_ = os.Remove(tmp)
			return steps, err
		}
		if err := os.Rename(tmp, item.dest); err != nil {
			// Windows-ish fallback; on Linux replace in place
			_ = os.Remove(item.dest)
			if err2 := os.Rename(tmp, item.dest); err2 != nil {
				_ = os.Remove(tmp)
				return steps, fmt.Errorf("install %s: %v / %v", item.dest, err, err2)
			}
		}
		steps = append(steps, "installed "+item.dest)
		baseName := filepath.Base(item.dest)
		legacyName := strings.Replace(baseName, "rpcnode-", "tron-", 1)
		// PATH / legacy helpers: symlink ONLY (never a second real binary).
		// Units must ExecStart /opt/rpcnode/bin/rpcnode-*-agent — not these links.
		for _, link := range []string{
			filepath.Join("/usr/local/bin", baseName),
			filepath.Join(binDir, legacyName),
			filepath.Join("/usr/local/bin", legacyName),
			filepath.Join("/opt/rpcnode/bin", legacyName),
		} {
			if link == item.dest {
				continue
			}
			_ = os.MkdirAll(filepath.Dir(link), 0o755)
			_ = os.Remove(link)
			if err := os.Symlink(item.dest, link); err == nil {
				steps = append(steps, "symlink "+link)
			}
			// No copyFile fallback — a real binary under /usr/local/bin causes
			// duplicate system-agent when an old unit still starts the /opt path.
		}
	}
	return steps, nil
}

// agentWatchdogUnitBody matches install/agent.sh → install_agent_watchdog unit text.
func agentWatchdogUnitBody(execPath string) string {
	return `[Unit]
Description=RpcNode agent watchdog (tip + leaf restart)
After=network-online.target rpcnode-api-agent.service
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
ExecStart=` + execPath + `
Restart=always
RestartSec=5
Nice=10

[Install]
WantedBy=multi-user.target
`
}

func runEnsureWatchdogCLI() int {
	steps, err := ensureAgentWatchdog()
	for _, s := range steps {
		fmt.Println(s)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "ensure-watchdog: %v\n", err)
		return 1
	}
	return 0
}

// ensureAgentWatchdog downloads CDN install/rpcnode-agent-watchdog.sh, drops the
// systemd unit, and enable --now. Idempotent: safe on hosts that already have it
// (panel Update) and required for tips that never ran full agent.sh.
func ensureAgentWatchdog() ([]string, error) {
	steps := []string{}
	binDir := agentBinDir()
	dest := filepath.Join(binDir, "rpcnode-agent-watchdog")
	if err := os.MkdirAll(binDir, 0o755); err != nil {
		return steps, fmt.Errorf("mkdir %s: %w", binDir, err)
	}
	if err := os.MkdirAll("/var/lib/rpcnode/watchdog", 0o755); err != nil {
		return steps, fmt.Errorf("mkdir watchdog state: %w", err)
	}

	url := agentInstallBaseURL() + "/rpcnode-agent-watchdog.sh"
	tmp := dest + ".new"
	if err := downloadBinaryFile(url, tmp); err != nil {
		return steps, fmt.Errorf("download watchdog: %w", err)
	}
	if err := os.Chmod(tmp, 0o755); err != nil {
		_ = os.Remove(tmp)
		return steps, err
	}
	if err := os.Rename(tmp, dest); err != nil {
		_ = os.Remove(dest)
		if err2 := os.Rename(tmp, dest); err2 != nil {
			_ = os.Remove(tmp)
			return steps, fmt.Errorf("install watchdog script: %v / %v", err, err2)
		}
	}
	steps = append(steps, "installed "+dest)

	unitPath := "/etc/systemd/system/rpcnode-agent-watchdog.service"
	body := agentWatchdogUnitBody(dest)
	if err := os.WriteFile(unitPath, []byte(body), 0o644); err != nil {
		return steps, fmt.Errorf("write watchdog unit: %w", err)
	}
	steps = append(steps, "unit "+unitPath)

	if _, err := exec.LookPath("systemctl"); err != nil {
		steps = append(steps, "skip systemctl (not found)")
		return steps, nil
	}
	_ = exec.Command("systemctl", "daemon-reload").Run()
	if err := exec.Command("systemctl", "enable", "--now", "rpcnode-agent-watchdog.service").Run(); err != nil {
		// enable --now can fail if already active with stale ExecStart; restart is enough.
		if err2 := exec.Command("systemctl", "restart", "rpcnode-agent-watchdog.service").Run(); err2 != nil {
			return steps, fmt.Errorf("enable/restart rpcnode-agent-watchdog: %v / %v", err, err2)
		}
	} else {
		_ = exec.Command("systemctl", "restart", "rpcnode-agent-watchdog.service").Run()
	}
	steps = append(steps, "enabled rpcnode-agent-watchdog.service")
	return steps, nil
}

func downloadBinaryFile(url, dest string) error {
	client := &http.Client{Timeout: 3 * time.Minute}
	resp, err := client.Get(url)
	if err != nil {
		return fmt.Errorf("download %s: %w", url, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return fmt.Errorf("download %s: HTTP %d", url, resp.StatusCode)
	}
	f, err := os.OpenFile(dest, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o755)
	if err != nil {
		return err
	}
	defer f.Close()
	n, err := io.Copy(f, io.LimitReader(resp.Body, 128<<20))
	if err != nil {
		return err
	}
	if n < 1024 {
		return fmt.Errorf("download %s: file too small (%d bytes)", url, n)
	}
	// Reject HTML error pages.
	hdr := make([]byte, 16)
	rf, err := os.Open(dest)
	if err == nil {
		_, _ = rf.Read(hdr)
		_ = rf.Close()
		low := strings.ToLower(string(hdr))
		if strings.Contains(low, "<!doctype") || strings.Contains(low, "<html") {
			_ = os.Remove(dest)
			return fmt.Errorf("download %s: got HTML, not a binary", url)
		}
	}
	return nil
}

func fetchSHA256Sums(url string) (map[string]string, error) {
	client := &http.Client{Timeout: 20 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	out := map[string]string{}
	for _, line := range strings.Split(string(b), "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		out[filepath.Base(fields[1])] = fields[0]
	}
	return out, nil
}

func fileSHA256(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o755)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, in)
	return err
}
