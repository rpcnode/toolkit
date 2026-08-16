package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

const (
	pfMinCPUs   = 8
	pfWarnCPUs  = 16
	pfMinRAMGB  = 32
	pfWarnRAMGB = 48
	pfMinDiskGB = 800
	pfWarnDisk  = 1500
)

type pfCheck struct {
	Level     string `json:"level"`
	Name      string `json:"name"`
	Detail    string `json:"detail"`
	Recommend string `json:"recommend"`
}

type pfReport struct {
	OK        int       `json:"ok"`
	Warn      int       `json:"warn"`
	Fail      int       `json:"fail"`
	Suitable  bool      `json:"suitable"`
	CheckedAt string    `json:"checked_at"`
	Env       string    `json:"env"`
	Checks    []pfCheck `json:"checks"`
	Blocking  bool      `json:"blocking"`
	Source    string    `json:"source"`
	Platform  string    `json:"platform"`
	Hostname  string    `json:"hostname,omitempty"`
	Context   string    `json:"context"`
	Hint      string    `json:"hint,omitempty"`
}

func (r *pfReport) add(level, name, detail, recommend string) {
	r.Checks = append(r.Checks, pfCheck{Level: level, Name: name, Detail: detail, Recommend: recommend})
	switch level {
	case "OK":
		r.OK++
	case "WARN":
		r.Warn++
	case "FAIL":
		r.Fail++
	}
}

// preflightLivePath — only place we write a real machine snapshot (state dir).
func preflightLivePath(cfg Config) string {
	return filepath.Join(filepath.Dir(cfg.StateFile), "preflight.json")
}

// preflightReadPaths — live state first, then optional legacy toolkit copy (may be Mac leftover).
func preflightReadPaths(cfg Config) []string {
	out := []string{preflightLivePath(cfg)}
	if cfg.ToolkitDir != "" {
		out = append(out, filepath.Join(cfg.ToolkitDir, "config", "preflight.json"))
	}
	return out
}

func preflightDetailBlob(pf map[string]any) string {
	platform, _ := pf["platform"].(string)
	blob := platform
	if hn, _ := pf["hostname"].(string); hn != "" {
		blob += " " + hn
	}
	if ctx, _ := pf["context"].(string); ctx != "" {
		blob += " " + ctx
	}
	if checks, ok := pf["checks"].([]any); ok {
		for _, raw := range checks {
			m, _ := raw.(map[string]any)
			if m == nil {
				continue
			}
			blob += " " + fmt.Sprint(m["detail"])
		}
	}
	return strings.ToLower(blob)
}

// preflightForeignOS — runtime uname OS ≠ snapshot OS (e.g. Mac JSON on Linux server).
func preflightForeignOS(pf map[string]any) bool {
	if len(pf) == 0 {
		return false
	}
	liveOS, _, _ := liveUname()
	liveOS = normalizeUnameOS(liveOS)
	blob := preflightDetailBlob(pf)
	platform, _ := pf["platform"].(string)
	plat := normalizeUnameOS(platform)

	pfLooksDarwin := plat == "Darwin" ||
		strings.Contains(blob, "darwin") ||
		strings.Contains(blob, "apfs") ||
		strings.Contains(blob, "local-mac")

	if liveOS == "Linux" && pfLooksDarwin {
		return true
	}
	// Explicit Linux snapshot on Darwin host (wrong machine).
	if liveOS == "Darwin" && plat == "Linux" {
		return true
	}
	return false
}

func normalizeUnameOS(s string) string {
	s = strings.TrimSpace(s)
	switch strings.ToLower(s) {
	case "linux":
		return "Linux"
	case "darwin":
		return "Darwin"
	default:
		return s
	}
}

// preflightStale — empty / placeholder / foreign-OS / zeroed parsers / no checks.
func preflightStale(pf map[string]any) bool {
	if len(pf) == 0 {
		return true
	}
	if src, _ := pf["source"].(string); src == "placeholder" || src == "unavailable" {
		return true
	}
	if preflightForeignOS(pf) {
		return true
	}
	blob := preflightDetailBlob(pf)
	if strings.Contains(blob, "0 cores") || strings.Contains(blob, "0 gb") || strings.Contains(blob, "free≈0") {
		return true
	}
	if checks, ok := pf["checks"].([]any); !ok || len(checks) == 0 {
		return true
	}
	return false
}

func discardPreflightFile(path string) {
	if path == "" {
		return
	}
	_ = os.Remove(path)
	_ = os.Remove(path + ".tmp")
}

func discardForeignOrStaleFiles(cfg Config) {
	live := preflightLivePath(cfg)
	if pf := readJSONFile(live); len(pf) > 0 {
		if preflightForeignOS(pf) || preflightStale(pf) {
			fmt.Fprintf(os.Stderr, "preflight: discarding stale/foreign %s\n", live)
			discardPreflightFile(live)
		}
	}
	// Toolkit tree: reset leaked real/Mac snapshots back to placeholder (do not delete).
	resetToolkitPreflightPlaceholder(cfg)
}

// loadPreflightFile — never returns foreign-OS or placeholder junk for the UI.
func loadPreflightFile(cfg Config) map[string]any {
	for _, p := range preflightReadPaths(cfg) {
		pf := readJSONFile(p)
		if len(pf) == 0 {
			continue
		}
		if preflightForeignOS(pf) {
			fmt.Fprintf(os.Stderr, "preflight: foreign-OS snapshot at %s — discarded\n", p)
			discardPreflightFile(p)
			continue
		}
		if src, _ := pf["source"].(string); src == "placeholder" {
			continue
		}
		if preflightStale(pf) {
			continue
		}
		return pf
	}
	return nil
}

func preflightUnavailableMap(cfg Config, reason string) map[string]any {
	liveOS, arch, kernel := liveUname()
	platform, context, hint := hostContextLabel(liveOS, kernel)
	if reason == "" {
		reason = "preflight unavailable"
	}
	return map[string]any{
		"ok":         0,
		"warn":       0,
		"fail":       0,
		"suitable":   false,
		"blocking":   false,
		"env":        cfg.Env,
		"source":     "unavailable",
		"platform":   platform,
		"hostname":   hostname(),
		"context":    context,
		"hint":       hint + " — " + reason + ". Click Re-run preflight or restart agents.",
		"checked_at": time.Now().UTC().Format("2006-01-02T15:04:05Z"),
		"checks":     []any{},
		"arch":       arch,
	}
}

func liveUname() (osName, arch, kernel string) {
	osName = runtime.GOOS
	arch = runtime.GOARCH
	if out, err := exec.Command("uname", "-s").Output(); err == nil {
		if s := strings.TrimSpace(string(out)); s != "" {
			osName = s
		}
	}
	if out, err := exec.Command("uname", "-m").Output(); err == nil {
		if s := strings.TrimSpace(string(out)); s != "" {
			arch = s
		}
	}
	if out, err := exec.Command("uname", "-r").Output(); err == nil {
		kernel = strings.TrimSpace(string(out))
	}
	return normalizeUnameOS(osName), arch, kernel
}

func hostContextLabel(osName, kernel string) (platform, context, hint string) {
	platform = normalizeUnameOS(osName)
	kl := strings.ToLower(kernel)
	switch {
	case platform == "Darwin":
		context = "local-mac-toolkit"
		hint = "This is the local Mac toolkit panel. For the TRON server open http://<server-ip>:8093/status (not localhost on Mac)."
	case strings.Contains(kl, "linuxkit") || strings.Contains(kl, "docker-desktop"):
		context = "docker-desktop-vm"
		hint = "Agents see the Docker Desktop Linux VM (local Mac). Server facts: open http://<server-ip>:8093/status on the Linux host."
	case os.Getenv("TRON_COMPOSE_HOST") == "1": // legacy env; treat as bare-metal host
		context = "linux-server-host"
		hint = "Host-network Linux install — panel should be http://<this-server-ip>:8093/status"
	default:
		context = "linux"
		hint = "Open the server panel at http://<server-ip>:8093/status (RPC is :8090, panel is :8093)."
	}
	return platform, context, hint
}

func readProcFile(name string) ([]byte, error) {
	// Prefer host procfs bind-mount (/host/proc) when present — avoids cgroup-zeroed views.
	for _, root := range []string{"/host/proc", "/proc"} {
		b, err := os.ReadFile(filepath.Join(root, name))
		if err == nil {
			return b, nil
		}
	}
	return nil, os.ErrNotExist
}

func countCPUs() (n int, model string) {
	if b, err := readProcFile("cpuinfo"); err == nil {
		for _, line := range strings.Split(string(b), "\n") {
			if strings.HasPrefix(line, "processor") {
				n++
			}
			if model == "" && strings.HasPrefix(line, "model name") {
				parts := strings.SplitN(line, ":", 2)
				if len(parts) == 2 {
					model = strings.TrimSpace(parts[1])
				}
			}
		}
	}
	if n == 0 {
		if out, err := exec.Command("nproc").Output(); err == nil {
			if v, e := strconv.Atoi(strings.TrimSpace(string(out))); e == nil && v > 0 {
				n = v
			}
		}
	}
	if n == 0 {
		for _, args := range [][]string{
			{"/usr/sbin/sysctl", "-n", "hw.logicalcpu"},
			{"/usr/sbin/sysctl", "-n", "hw.ncpu"},
			{"sysctl", "-n", "hw.logicalcpu"},
			{"sysctl", "-n", "hw.ncpu"},
			{"getconf", "_NPROCESSORS_ONLN"},
		} {
			if out, err := exec.Command(args[0], args[1:]...).Output(); err == nil {
				if v, e := strconv.Atoi(strings.TrimSpace(string(out))); e == nil && v > 0 {
					n = v
					break
				}
			}
		}
	}
	if model == "" {
		for _, args := range [][]string{
			{"/usr/sbin/sysctl", "-n", "machdep.cpu.brand_string"},
			{"sysctl", "-n", "machdep.cpu.brand_string"},
		} {
			if out, err := exec.Command(args[0], args[1:]...).Output(); err == nil {
				if s := strings.TrimSpace(string(out)); s != "" {
					model = s
					break
				}
			}
		}
	}
	if n == 0 {
		n = runtime.NumCPU()
	}
	if model == "" {
		model = "unknown"
	}
	return n, model
}

func ramGB() int {
	if b, err := readProcFile("meminfo"); err == nil {
		for _, line := range strings.Split(string(b), "\n") {
			if strings.HasPrefix(line, "MemTotal:") {
				fields := strings.Fields(line)
				if len(fields) >= 2 {
					kb, _ := strconv.ParseUint(fields[1], 10, 64)
					if kb > 0 {
						return int(kb / 1024 / 1024)
					}
				}
			}
		}
	}
	for _, args := range [][]string{
		{"/usr/sbin/sysctl", "-n", "hw.memsize"},
		{"sysctl", "-n", "hw.memsize"},
	} {
		if out, err := exec.Command(args[0], args[1:]...).CombinedOutput(); err == nil {
			bytes, _ := strconv.ParseUint(strings.TrimSpace(string(out)), 10, 64)
			if bytes > 0 {
				return int(bytes / 1024 / 1024 / 1024)
			}
		}
	}
	return 0
}

func diskFreeGB(path string) (availGB int, fstype, device string) {
	// Walk to an existing ancestor for df — ❌ never MkdirAll(path).
	// Host tip used to default DataDir=/data/tron/mainnet and this mkdir
	// created empty TRON trees whenever any other network was provisioned.
	probe := path
	if strings.TrimSpace(probe) == "" {
		probe = "/"
	}
	for {
		if st, err := os.Stat(probe); err == nil && st.IsDir() {
			break
		}
		parent := filepath.Dir(probe)
		if parent == probe {
			probe = "/"
			break
		}
		probe = parent
	}

	out, err := exec.Command("df", "-Pk", probe).Output()
	if err != nil {
		out, err = exec.Command("df", "-Pk", "/").Output()
	}
	if err == nil {
		lines := strings.Split(strings.TrimSpace(string(out)), "\n")
		if len(lines) >= 2 {
			fields := strings.Fields(lines[1])
			if len(fields) >= 4 {
				device = fields[0]
				kb, _ := strconv.ParseUint(fields[3], 10, 64)
				availGB = int(kb / 1024 / 1024)
			}
		}
	}
	if out, err := exec.Command("findmnt", "-n", "-o", "FSTYPE", "--target", probe).Output(); err == nil {
		fstype = strings.TrimSpace(string(out))
	}
	if fstype == "" {
		osName, _, _ := liveUname()
		if osName == "Darwin" {
			fstype = "apfs"
		}
	}
	return availGB, fstype, device
}

func portListening(port int) bool {
	if port <= 0 {
		return false
	}
	conn, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), 250*time.Millisecond)
	if err != nil {
		return false
	}
	_ = conn.Close()
	return true
}

func dockerOK() (clientOK, daemonOK bool, detail string) {
	out, err := exec.Command("docker", "--version").Output()
	if err != nil {
		return false, false, "docker not found"
	}
	detail = strings.TrimSpace(string(out))
	if out, err := exec.Command("docker", "compose", "version").Output(); err == nil {
		detail += "; " + strings.TrimSpace(strings.Split(string(out), "\n")[0])
		clientOK = true
	} else {
		detail += "; compose plugin missing"
	}
	if err := exec.Command("docker", "info").Run(); err == nil {
		daemonOK = true
	}
	return clientOK, daemonOK, detail
}

func javaDetail() string {
	out, err := exec.Command("bash", "-lc", "java -version 2>&1 | head -1").Output()
	if err != nil || strings.TrimSpace(string(out)) == "" {
		return ""
	}
	return strings.TrimSpace(string(out))
}

func runHostPreflight(cfg Config) pfReport {
	osName, arch, kernel := liveUname()
	platform, context, hint := hostContextLabel(osName, kernel)

	r := pfReport{
		CheckedAt: time.Now().UTC().Format("2006-01-02T15:04:05Z"),
		Env:       cfg.Env,
		Blocking:  false,
		Source:    "system-agent",
		Platform:  platform,
		Hostname:  hostname(),
		Context:   context,
		Hint:      hint,
	}

	detail := fmt.Sprintf("%s %s kernel=%s [%s]", osName, arch, kernel, context)
	switch {
	case osName == "Darwin":
		r.add("WARN", "OS/arch", detail,
			"local Mac toolkit — java-tron mainnet usually runs on a Linux server; open http://<server-ip>:8093/status there")
	case context == "docker-desktop-vm":
		r.add("WARN", "OS/arch", detail,
			"Docker Desktop VM (local Mac), not the remote TRON server — use the server panel URL")
	case arch == "x86_64" || arch == "amd64" || arch == "aarch64" || arch == "arm64":
		r.add("OK", "OS/arch", detail, "")
	default:
		r.add("WARN", "OS/arch", detail, "Non-standard arch — verify java-tron compatibility")
	}

	cpus, model := countCPUs()
	cpuDetail := fmt.Sprintf("%d cores (%s)", cpus, model)
	switch {
	case cpus < pfMinCPUs:
		r.add("FAIL", "CPU cores", cpuDetail,
			fmt.Sprintf("Recommend ≥%d (minimum %d) for mainnet FullNode", pfWarnCPUs, pfMinCPUs))
	case cpus < pfWarnCPUs:
		r.add("WARN", "CPU cores", cpuDetail,
			fmt.Sprintf("For comfortable mainnet prefer ≥%d cores", pfWarnCPUs))
	default:
		r.add("OK", "CPU cores", cpuDetail, "")
	}

	gb := ramGB()
	switch {
	case gb < pfMinRAMGB:
		r.add("FAIL", "RAM", fmt.Sprintf("%d GB", gb),
			fmt.Sprintf("Recommend ≥%d GB (minimum %d GB); java-tron often uses Xmx≈48g", pfWarnRAMGB, pfMinRAMGB))
	case gb < pfWarnRAMGB:
		r.add("WARN", "RAM", fmt.Sprintf("%d GB", gb),
			fmt.Sprintf("For mainnet with Xmx 48g prefer ≥%d GB", pfWarnRAMGB))
	default:
		r.add("OK", "RAM", fmt.Sprintf("%d GB", gb), "")
	}

	avail, fstype, device := diskFreeGB(cfg.DataDir)
	diskNote := fmt.Sprintf("free≈%d GB on %s", avail, cfg.DataDir)
	if fstype != "" {
		diskNote += " fstype=" + fstype
	}
	if device != "" {
		diskNote += " device=" + device
	}
	switch {
	case avail < pfMinDiskGB:
		r.add("FAIL", "Disk free", diskNote,
			fmt.Sprintf("Need ≥%d GB free for data (prefer ≥%d GB); LevelDB snapshot ~1TB+", pfMinDiskGB, pfWarnDisk))
	case avail < pfWarnDisk:
		r.add("WARN", "Disk free", diskNote,
			fmt.Sprintf("For mainnet snapshot prefer ≥%d GB free", pfWarnDisk))
	default:
		r.add("OK", "Disk free", diskNote, "")
	}

	clientOK, daemonOK, ddetail := dockerOK()
	switch {
	case !clientOK && strings.Contains(ddetail, "not found"):
		r.add("FAIL", "Docker", ddetail, "Install Docker Engine — toolkit agents run in Docker")
	case !clientOK:
		r.add("FAIL", "Docker Compose", ddetail, "Need docker compose plugin")
	default:
		r.add("OK", "Docker", ddetail, "")
	}
	if clientOK && !daemonOK {
		r.add("WARN", "Docker daemon", "cannot talk to docker (permission or not running)",
			"On the host: systemctl start docker and/or add user to the docker group (inside container this may be a false WARN if sock not mounted)")
	}

	rpcPort := cfg.PublicRPCPort()
	panelPort := cfg.AgentAPIPort()
	nodePort := cfg.UpstreamPort
	ownStack := dockerRunning("tron-"+cfg.Env+"-api-agent") || dockerRunning("tron-"+cfg.Env+"-nginx")

	addPort := func(port int, name string, role string) {
		busy := portListening(port)
		if !busy {
			r.add("OK", fmt.Sprintf("Port %d", port), name+": free", "")
			return
		}
		if ownStack {
			r.add("WARN", fmt.Sprintf("Port %d", port),
				fmt.Sprintf("%s: in use by this env's stack (tron-%s-*)", name, cfg.Env),
				fmt.Sprintf("OK if re-running setup; else: docker compose -p tron-toolkit-%s down", cfg.Env))
			return
		}
		if role == "node" {
			r.add("WARN", fmt.Sprintf("Port %d", port), name+": already listening",
				fmt.Sprintf("Likely existing java-tron — keep for gateway-only, or use --node-http-port %d", port+1))
			return
		}
		r.add("FAIL", fmt.Sprintf("Port %d", port), name+": already listening",
			"Another service/env occupies this port. Use --gateway-port / --panel-port (see rpcnodectl ports) or free it")
	}
	addPort(rpcPort, "RPC public port (api-agent catch-all)", "gateway")
	addPort(panelPort, "Panel ops port (UI + /api)", "gateway")
	addPort(nodePort, "java-tron HTTP (internal)", "node")

	if j := javaDetail(); j != "" {
		r.add("OK", "Java", "optional; found: "+j, "")
	} else {
		r.add("OK", "Java", "not required (gateway-only / node already managed)", "")
	}

	r.Suitable = r.Fail == 0
	return r
}

func writePreflightReport(cfg Config, r pfReport) error {
	b, err := json.MarshalIndent(r, "", "  ")
	if err != nil {
		return err
	}
	b = append(b, '\n')

	// Live snapshot only in state dir — never overwrite git placeholder in toolkit/config.
	dest := preflightLivePath(cfg)
	if err := ensureDir(filepath.Dir(dest)); err != nil {
		return err
	}
	tmp := dest + ".tmp"
	if err := os.WriteFile(tmp, b, 0644); err != nil {
		return err
	}
	if err := os.Rename(tmp, dest); err != nil {
		return err
	}

	// Keep toolkit/config/preflight.json as non-machine placeholder if present.
	resetToolkitPreflightPlaceholder(cfg)
	return nil
}

func resetToolkitPreflightPlaceholder(cfg Config) {
	if cfg.ToolkitDir == "" {
		return
	}
	dest := filepath.Join(cfg.ToolkitDir, "config", "preflight.json")
	pf := readJSONFile(dest)
	if len(pf) == 0 {
		return
	}
	src, _ := pf["source"].(string)
	// Only wipe bind-mount copies that are foreign or a real machine snapshot.
	if src == "placeholder" || src == "unavailable" {
		return
	}
	if !preflightForeignOS(pf) {
		// Non-placeholder with checks = leaked host facts into git tree / volume.
		if checks, ok := pf["checks"].([]any); !ok || len(checks) == 0 {
			return
		}
	}
	fmt.Fprintf(os.Stderr, "preflight: resetting toolkit config copy to placeholder (%s)\n", dest)
	placeholder := []byte(`{
  "ok": 0,
  "warn": 0,
  "fail": 0,
  "suitable": false,
  "blocking": false,
  "env": "mainnet",
  "source": "placeholder",
  "platform": "",
  "context": "pending",
  "hint": "Placeholder — system-agent writes live facts to state dir on start. Do not commit real host snapshots here.",
  "checks": []
}
`)
	_ = ensureDir(filepath.Dir(dest))
	tmp := dest + ".tmp"
	if err := os.WriteFile(tmp, placeholder, 0644); err != nil {
		return
	}
	_ = os.Rename(tmp, dest)
}

func refreshPreflight(cfg Config) (pfReport, error) {
	discardForeignOrStaleFiles(cfg)
	r := runHostPreflight(cfg)
	err := writePreflightReport(cfg, r)
	return r, err
}

// ensurePreflightFresh — always re-run host preflight on system-agent start (Docker-first).
// Fail-open: never serve Mac facts on Linux; expose "unavailable" instead.
func ensurePreflightFresh(cfg Config) {
	discardForeignOrStaleFiles(cfg)
	if _, err := refreshPreflight(cfg); err != nil {
		fmt.Fprintf(os.Stderr, "preflight refresh failed (fail-open unavailable): %v\n", err)
		_ = writePreflightUnavailable(cfg, err.Error())
	}
}

func writePreflightUnavailable(cfg Config, reason string) error {
	m := preflightUnavailableMap(cfg, reason)
	b, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return err
	}
	b = append(b, '\n')
	dest := preflightLivePath(cfg)
	if err := ensureDir(filepath.Dir(dest)); err != nil {
		return err
	}
	tmp := dest + ".tmp"
	if err := os.WriteFile(tmp, b, 0644); err != nil {
		return err
	}
	return os.Rename(tmp, dest)
}

func preflightToMap(r pfReport) map[string]any {
	b, _ := json.Marshal(r)
	out := map[string]any{}
	_ = json.Unmarshal(b, &out)
	return out
}
