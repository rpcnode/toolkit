package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

var (
	pctRe      = regexp.MustCompile(`(\d+)%`)
	wgetETARe  = regexp.MustCompile(`(\d+)%\s+\S+\s+((?:\d+d)?(?:\d+h)?(?:\d+m)?(?:\d+s)?)\s*$`)
	etaTokenRe = regexp.MustCompile(`^(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$`)
)

func runCmd(timeout time.Duration, name string, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	out, err := cmd.Output()
	return string(out), err
}

func readJSONFile(path string) map[string]any {
	b, err := os.ReadFile(path)
	if err != nil {
		return map[string]any{}
	}
	var out map[string]any
	if err := json.Unmarshal(b, &out); err != nil {
		return map[string]any{"error": err.Error()}
	}
	return out
}

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func systemctlActive(unit string) string {
	out, err := runCmd(2*time.Second, "systemctl", "is-active", unit)
	s := strings.TrimSpace(out)
	if s != "" {
		return s
	}
	if err != nil {
		return "n/a"
	}
	return s
}

func systemctlFailed(unit string) bool {
	out, _ := runCmd(2*time.Second, "systemctl", "is-failed", unit)
	return strings.TrimSpace(out) == "failed"
}

func latestSnapshotErrorFromLog(path string) string {
	tail := snapshotLogTail(path, 30)
	for i := len(tail) - 1; i >= 0; i-- {
		ln := strings.TrimSpace(tail[i])
		if ln == "" {
			continue
		}
		low := strings.ToLower(ln)
		if strings.Contains(low, "error") || strings.Contains(low, "fail") ||
			strings.Contains(low, "denied") || strings.Contains(low, "unable") ||
			strings.Contains(low, "no space") {
			if len(ln) > 240 {
				ln = ln[:240] + "…"
			}
			return ln
		}
	}
	if len(tail) > 0 {
		ln := strings.TrimSpace(tail[len(tail)-1])
		if len(ln) > 240 {
			ln = ln[:240] + "…"
		}
		return ln
	}
	return ""
}

func portOpen(host string, port int) bool {
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(port)), 800*time.Millisecond)
	if err != nil {
		return false
	}
	_ = conn.Close()
	return true
}

func javaTronRunning() (bool, string) {
	out, err := runCmd(2*time.Second, "bash", "-lc", `ps -eo pid=,args= | grep -E '[j]ava.*(FullNode\.jar|java-tron)' | head -1`)
	if err != nil || strings.TrimSpace(out) == "" {
		return false, ""
	}
	return true, strings.TrimSpace(out)
}

func duHuman(path string) string {
	out, err := runCmd(2500*time.Millisecond, "du", "-sh", path)
	if err != nil {
		if _, e := os.Stat(path); e != nil {
			return "missing"
		}
		return "…"
	}
	fields := strings.Fields(out)
	if len(fields) == 0 {
		return "?"
	}
	return fields[0]
}

func diskRoot() map[string]any {
	out, err := runCmd(2*time.Second, "df", "-B1", "/")
	if err != nil {
		return map[string]any{"total_gb": 0.0, "used_gb": 0.0, "free_gb": 0.0, "used_pct": 0.0}
	}
	lines := strings.Split(strings.TrimSpace(out), "\n")
	if len(lines) < 2 {
		return map[string]any{"total_gb": 0.0, "used_gb": 0.0, "free_gb": 0.0, "used_pct": 0.0}
	}
	fields := strings.Fields(lines[1])
	if len(fields) < 4 {
		return map[string]any{"total_gb": 0.0, "used_gb": 0.0, "free_gb": 0.0, "used_pct": 0.0}
	}
	total, _ := strconv.ParseFloat(fields[1], 64)
	used, _ := strconv.ParseFloat(fields[2], 64)
	free, _ := strconv.ParseFloat(fields[3], 64)
	pct := 0.0
	if total > 0 {
		pct = used * 100 / total
	}
	return map[string]any{
		"total_gb": round1(total / (1024 * 1024 * 1024)),
		"used_gb":  round1(used / (1024 * 1024 * 1024)),
		"free_gb":  round1(free / (1024 * 1024 * 1024)),
		"used_pct": round1(pct),
	}
}

func round1(v float64) float64 { return float64(int(v*10+0.5)) / 10 }

// snapshotLogTail reads only the last ~256 KiB of the log (never the whole file).
// Snapshot wget logs can grow to multi‑GB; ReadFile of the full path caused ~12GB RSS.
func snapshotLogTail(path string, n int) []string {
	const maxTail = 256 * 1024
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()

	st, err := f.Stat()
	if err != nil {
		return nil
	}
	size := st.Size()
	start := int64(0)
	if size > maxTail {
		start = size - maxTail
	}
	if _, err := f.Seek(start, io.SeekStart); err != nil {
		return nil
	}
	b, err := io.ReadAll(io.LimitReader(f, maxTail+1))
	if err != nil {
		return nil
	}
	// If we started mid-line, drop the partial first line.
	if start > 0 {
		if i := bytes.IndexByte(b, '\n'); i >= 0 && i+1 < len(b) {
			b = b[i+1:]
		}
	}

	lines := strings.Split(string(b), "\n")
	var prog []string
	for _, ln := range lines {
		if pctRe.MatchString(ln) {
			prog = append(prog, ln)
		}
	}
	src := prog
	if len(src) == 0 {
		src = lines
	}
	if len(src) > n {
		src = src[len(src)-n:]
	}
	out := make([]string, 0, len(src))
	for _, ln := range src {
		ln = strings.TrimSpace(ln)
		if ln != "" {
			out = append(out, ln)
		}
	}
	return out
}

func latestPctFromLog(path string) string {
	tail := snapshotLogTail(path, 40)
	for i := len(tail) - 1; i >= 0; i-- {
		if m := pctRe.FindStringSubmatch(tail[i]); len(m) == 2 {
			return m[1]
		}
	}
	return ""
}

func formatETAToken(token string) string {
	token = strings.TrimSpace(token)
	if token == "" {
		return "—"
	}
	m := etaTokenRe.FindStringSubmatch(token)
	if m == nil {
		return "—"
	}
	atoi := func(s string) int { n, _ := strconv.Atoi(s); return n }
	d, h, mi, s := atoi(m[1]), atoi(m[2]), atoi(m[3]), atoi(m[4])
	if d == 0 && h == 0 && mi == 0 && s == 0 {
		return "—"
	}
	total := d*86400 + h*3600 + mi*60 + s
	if total < 90 {
		return "finishing…"
	}
	parts := make([]string, 0, 2)
	if d > 0 {
		parts = append(parts, fmt.Sprintf("%dd", d))
	}
	if h > 0 {
		parts = append(parts, fmt.Sprintf("%dh", h))
	}
	if mi > 0 && len(parts) < 2 {
		parts = append(parts, fmt.Sprintf("%dm", mi))
	}
	if len(parts) == 0 && s > 0 {
		parts = append(parts, fmt.Sprintf("%ds", s))
	}
	return "~" + strings.Join(parts, " ") + " left"
}

func latestETAFromLog(path string) string {
	tail := snapshotLogTail(path, 80)
	for i := len(tail) - 1; i >= 0; i-- {
		m := wgetETARe.FindStringSubmatch(strings.TrimRight(tail[i], " \t\r"))
		if m == nil {
			continue
		}
		pct, _ := strconv.Atoi(m[1])
		if pct >= 100 {
			return "—"
		}
		if pct >= 99 {
			return "finishing…"
		}
		return formatETAToken(m[2])
	}
	return "—"
}

func tronClientVersion(cfg Config, versionFile map[string]string) string {
	host := cfg.UpstreamHost
	if host == "" {
		host = "127.0.0.1"
	}
	port := cfg.UpstreamPort
	if port <= 0 {
		port = 18090
	}
	client := &http.Client{Timeout: 2 * time.Second}
	resp, err := client.Get(fmt.Sprintf("http://%s:%d/wallet/getnodeinfo", host, port))
	if err == nil {
		defer resp.Body.Close()
		var data map[string]any
		if json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&data) == nil {
			if cfgNode, ok := data["configNodeInfo"].(map[string]any); ok {
				if s, ok := cfgNode["codeVersion"].(string); ok {
					if t := formatClientVersion(s); t != "" {
						return t
					}
				}
			}
		}
	}
	for _, k := range []string{"codeVersion", "version", "VERSION", "git.version"} {
		if s := formatClientVersion(versionFile[k]); s != "" {
			return s
		}
	}

	return ""
}

func rpcHeight(host string, port int) any {
	n, _ := fetchTronNowBlock(host, port)
	if n > 0 {
		return n
	}
	return nil
}

func fetchTronNowBlock(host string, port int) (number int64, ts time.Time) {
	if host == "" || port <= 0 {
		return 0, time.Time{}
	}
	client := &http.Client{Timeout: 1500 * time.Millisecond}
	resp, err := client.Get(fmt.Sprintf("http://%s:%d/wallet/getnowblock", host, port))
	if err != nil {
		return 0, time.Time{}
	}
	defer resp.Body.Close()
	var data map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&data); err != nil {
		return 0, time.Time{}
	}
	return parseTronBlockNumber(data), parseTronBlockTimestamp(data)
}

// wgetRunning reports snapshot download for THIS env only.
// Host-wide pgrep on "FullNode_output-directory" is wrong on multi-env hosts:
// Nile/mainnet share that archive name in the URL, so one wget looked like every env.
func wgetRunning(cfg Config) bool {
	if st := systemctlActive(cfg.SnapshotService); st == "active" || st == "activating" {
		return true
	}
	// wget -O - URL | tar — wget argv has no datadir. Match the oneshot script.
	if env := strings.TrimSpace(cfg.Env); env != "" && strings.EqualFold(cfg.Network, "tron") {
		if out, err := runCmd(2*time.Second, "pgrep", "-af", "tron-snapshot-"+env+".sh"); err == nil && strings.TrimSpace(out) != "" {
			return true
		}
	}

	out, err := runCmd(2*time.Second, "pgrep", "-af", "wget")
	if err != nil || strings.TrimSpace(out) == "" {
		return false
	}

	needles := make([]string, 0, 6)
	for _, n := range []string{cfg.DataDir, cfg.SnapshotLog, cfg.Output, cfg.Env, cfg.SnapshotService} {
		n = strings.TrimSpace(n)
		if n == "" {
			continue
		}
		needles = append(needles, n)
	}
	if env := strings.TrimSpace(cfg.Env); env != "" && strings.EqualFold(cfg.Network, "tron") {
		needles = append(needles, "tron-snapshot-"+env+".sh")
	}

	for _, line := range strings.Split(out, "\n") {
		low := strings.ToLower(line)
		if !strings.Contains(low, "wget") {
			continue
		}
		// Ignore other envs' downloads that only share the archive basename.
		for _, n := range needles {
			if n != "" && strings.Contains(line, n) {
				return true
			}
		}
	}

	return false
}

func readVersionFile(path string) map[string]string {
	b, err := os.ReadFile(path)
	if err != nil {
		return map[string]string{}
	}
	out := map[string]string{}
	for _, line := range strings.Split(string(b), "\n") {
		if i := strings.IndexByte(line, '='); i > 0 {
			out[strings.TrimSpace(line[:i])] = strings.TrimSpace(line[i+1:])
		}
	}
	return out
}

func detectPublicIP() string {
	// Linux
	out, err := runCmd(2*time.Second, "bash", "-lc", `ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}'`)
	if ip := strings.TrimSpace(out); err == nil && ip != "" {
		return ip
	}
	// macOS (local compose)
	out, err = runCmd(2*time.Second, "bash", "-lc", `ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true`)
	if ip := strings.TrimSpace(out); err == nil && ip != "" {
		return ip
	}
	return ""
}

func hostname() string {
	h, err := os.Hostname()
	if err != nil {
		return "unknown"
	}
	return h
}

func httpOK(url string) bool {
	client := &http.Client{Timeout: 1500 * time.Millisecond}
	resp, err := client.Get(url)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode == 200 || resp.StatusCode == 503
}

func collect(cfg Config) map[string]any {
	defer func() {
		if rec := recover(); rec != nil {
			logRecover(rec)
		}
	}()

	if cfg.HostTip {
		return collectHostTip(cfg)
	}
	if strings.EqualFold(cfg.Network, "bitcoin") {
		return collectBitcoin(cfg)
	}
	if strings.EqualFold(cfg.Network, "ethereum") {
		return collectEthereum(cfg)
	}
	if strings.EqualFold(cfg.Network, "bsc") {
		return collectBSC(cfg)
	}
	if strings.EqualFold(cfg.Network, "hyperliquid") {
		return collectL2EVM(cfg, "hyperliquid")
	}
	if strings.EqualFold(cfg.Network, "arb") {
		return collectL2EVM(cfg, "arb")
	}
	if strings.EqualFold(cfg.Network, "robinhood") {
		return collectL2EVM(cfg, "robinhood")
	}
	if strings.EqualFold(cfg.Network, "optimism") {
		return collectL2EVM(cfg, "optimism")
	}
	if strings.EqualFold(cfg.Network, "base") {
		return collectL2EVM(cfg, "base")
	}
	if strings.EqualFold(cfg.Network, "solana") {
		return collectSolana(cfg)
	}
	if strings.EqualFold(cfg.Network, "xrpl") {
		return collectXRPL(cfg)
	}
	if strings.EqualFold(cfg.Network, "doge") {
		return collectDoge(cfg)
	}
	if networkIsCoreLikeSA(cfg.Network) {
		return collectCoreLike(cfg)
	}
	if strings.EqualFold(cfg.Network, "cardano") {
		return collectCardano(cfg)
	}
	if strings.EqualFold(cfg.Network, "stellar") {
		return collectStellar(cfg)
	}
	if strings.EqualFold(cfg.Network, "ton") {
		return collectTon(cfg)
	}
	if strings.EqualFold(cfg.Network, "etc") {
		return collectETC(cfg)
	}
	if strings.EqualFold(cfg.Network, "zcash") {
		return collectZcash(cfg)
	}
	if strings.EqualFold(cfg.Network, "sui") {
		return collectSui(cfg)
	}
	if strings.EqualFold(cfg.Network, "aptos") {
		return collectAptos(cfg)
	}
	if strings.EqualFold(cfg.Network, "avalanche") {
		return collectAvalanche(cfg)
	}

	// Snapshot unit extracts as root; java-tron is User=nodeop. Every collect
	// repairs ownership so Update alone recovers LOCK Permission denied — no SSH.
	ensureNodeopOwned(cfg.DataDir, cfg.Output, cfg.OptDir)

	snapState := readJSONFile(cfg.SnapshotState)
	pct := ""
	if v, ok := snapState["pct"]; ok && v != nil {
		pct = fmt.Sprint(v)
	}
	if pct == "" || pct == "?" {
		if p := latestPctFromLog(cfg.SnapshotLog); p != "" {
			pct = p
		} else {
			pct = "?"
		}
	}
	marker := fileExists(cfg.SnapshotMarker)
	version := readVersionFile(cfg.VersionFile)
	maint := readJSONFile(cfg.MaintenanceFile)
	if _, ok := maint["enabled"]; !ok {
		maint["enabled"] = false
	}
	updater := readJSONFile(cfg.UpdaterState)
	wget := wgetRunning(cfg)
	javaOK, javaCmd := javaTronRunning()

	eta := "—"
	if !marker {
		eta = latestETAFromLog(cfg.SnapshotLog)
		if eta == "—" {
			if ph, _ := snapState["phase"].(string); ph == "extract" || ph == "extracting" {
				eta = "finishing…"
			}
		}
	}

	base, panelBase := effectivePublicBases(cfg)

	enabled, _ := maint["enabled"].(bool)
	nodeState := systemctlActive(cfg.NodeService)
	nodeActive := nodeState == "active" || javaOK
	startErr := tronStartFailureDetail(cfg, nodeActive)
	// Do not dial FullNode until process/systemd says it is up — agent status must not depend on :18090.
	var height any
	var nodePortOpen bool
	var localH, tipH, behind int64
	var blockTime time.Time
	peers := int64(-1)
	catching := false
	verifyPct := 0.0
	if nodeActive {
		nodePortOpen = portOpen(cfg.UpstreamHost, cfg.UpstreamPort)
		if nodePortOpen {
			localH, blockTime = fetchTronNowBlock(cfg.UpstreamHost, cfg.UpstreamPort)
			if localH > 0 {
				height = localH
			}
			if info := tronNodeInfo(cfg.UpstreamHost, cfg.UpstreamPort); info.OK {
				if localH <= 0 && info.BlockNum > 0 {
					localH = info.BlockNum
					height = localH
				}
				if info.Peers >= 0 {
					peers = info.Peers
				}
			}
			tipH = tronPublicTip(cfg)
			if tipH > 0 && localH > 0 {
				behind = tipH - localH
				if behind < 0 {
					behind = 0
				}
				catching = behind > tronSyncedBehindMax
			} else if localH > 0 {
				// Public tip unknown — do not paint Healthy / empty behind.
				catching = true
			}
			// Clock: 2018 genesis headers while tip probe looped to self → behind=0.
			if localH > 0 && tronBlockTimeStale(blockTime) {
				catching = true
			}
			if catching {
				if tipH > 0 && localH > 0 {
					if p, ok := tronLagClosedPct(cfg, behind); ok {
						verifyPct = p
					}
				}
			} else if localH > 0 && tipH > 0 {
				clearTronCatchupMaxBehind(cfg)
				verifyPct = 100
			}
		}
	}
	rpcOK := height != nil
	// UI badge / lifecycle: prefer live process+HTTP over raw systemd (Docker host units).
	nodeSvcEffective := nodeState
	switch {
	case nodeActive && rpcOK:
		nodeSvcEffective = "active"
	case nodeActive || nodePortOpen:
		// Process/port up but getnowblock not ready yet.
		if nodeState != "active" {
			nodeSvcEffective = "activating"
		} else {
			nodeSvcEffective = "active"
		}
	}
	// Probe Node Agent API. Combined mode (TRON_AGENT_PORT=0) serves agent JSON on public RPC port.
	agentPort := cfg.AgentAPIPort()
	apiProbePort := agentPort
	if apiProbePort <= 0 {
		apiProbePort = cfg.PublicRPCPort()
	}
	apiPortOpen := apiProbePort > 0 && portOpen("127.0.0.1", apiProbePort)
	apiHealth := apiProbePort > 0 && httpOK(fmt.Sprintf("http://127.0.0.1:%d/healthz", apiProbePort))
	if !apiHealth && !apiPortOpen && agentPort > 0 && cfg.PublicRPCPort() > 0 && agentPort != cfg.PublicRPCPort() {
		// Fallback: some hosts still answer healthz only on the RPC listen port.
		alt := cfg.PublicRPCPort()
		if portOpen("127.0.0.1", alt) && httpOK(fmt.Sprintf("http://127.0.0.1:%d/healthz", alt)) {
			apiPortOpen = true
			apiHealth = true
			apiProbePort = alt
		}
	}

	// Docker-first: prefer live probes over systemd unit names.
	network := cfg.Network
	if network == "" {
		network = DefaultNetwork
	}
	apiSvc := "inactive"
	if apiHealth || apiPortOpen {
		apiSvc = "active"
	} else if g := systemctlActive(cfg.APIService); g == "active" {
		apiSvc = g
	} else if g := systemctlActive(cfg.GatewayService); g == "active" {
		apiSvc = g
	}
	sysSvc := "active" // this process is the system-agent
	if dockerRunning(network+"-"+cfg.Env+"-system-agent") ||
		dockerRunning("tron-"+cfg.Env+"-system-agent") {
		sysSvc = "active"
	}

	phase := ""
	if v, ok := snapState["phase"].(string); ok {
		phase = v
	}
	if phase == "" {
		switch {
		case marker:
			phase = "done"
		case wget:
			phase = "download"
		default:
			phase = "idle"
		}
	}
	detail, _ := snapState["detail"].(string)
	snapErr, _ := snapState["error"].(string)
	url, _ := snapState["url"].(string)
	if url == "" {
		url = cfg.SnapshotURL
	}

	snapUnitFailed := systemctlFailed(cfg.SnapshotService)
	snapFailed := snapUnitFailed ||
		strings.Contains(strings.ToLower(phase+" "+detail+" "+snapErr), "fail") ||
		strings.EqualFold(phase, "error")
	if snapFailed && !marker && !wget {
		phase = "error"
		if snapErr == "" {
			snapErr = detail
		}
		if snapErr == "" {
			snapErr = latestSnapshotErrorFromLog(cfg.SnapshotLog)
		}
		if snapErr == "" && snapUnitFailed {
			snapErr = "snapshot unit failed (" + cfg.SnapshotService + ")"
		}
		if detail == "" {
			detail = snapErr
		}
	} else if marker || wget {
		snapFailed = false
		snapErr = ""
	}

	snapEnabled := snapshotFeatureEnabled(cfg)
	instRegistered := fileExists(cfg.RegistryFile) || fileExists(cfg.InstanceFile)
	apiUp := apiHealth || apiPortOpen
	snapBusy := !marker && !snapFailed && (wget ||
		strings.EqualFold(phase, "download") ||
		strings.EqualFold(phase, "extract") ||
		strings.EqualFold(phase, "extracting"))

	publicPort := cfg.PublicRPCPort()
	publicPortOpen := publicPort > 0 && portOpen("127.0.0.1", publicPort)
	agentPortOpen := apiProbePort > 0 && (apiPortOpen || apiHealth)

	prog := loadLifecycleProgress(cfg)
	lcIn := nodeLifecycleInput{
		Network:        network,
		Env:            cfg.Env,
		PublicPort:     publicPort,
		AgentPort:      agentPort,
		UpstreamPort:   cfg.UpstreamPort,
		PublicPortOpen: publicPortOpen,
		AgentPortOpen:  agentPortOpen,
		InstRegistered: instRegistered,
		APIUp:          apiUp,
		SnapEnabled:    snapEnabled,
		Marker:         marker,
		SnapBusy:       snapBusy,
		SnapFailed:     snapFailed,
		SnapPhase:      phase,
		SnapDetail:     detail,
		SnapErr:        snapErr,
		Pct:            pct,
		NodeActive:     nodeActive,
		StartError:     startErr,
		RPCOK:          rpcOK,
		Height:         height,
		Maintenance:    enabled,
		Progress:       prog,
	}
	if tipH > 0 {
		lcIn.Headers = tipH
	}
	lcIn.IBD = catching
	if verifyPct > 0 {
		lcIn.VerifyPct = verifyPct / 100
	}
	lcIn.Peers = -1
	if peers >= 0 {
		lcIn.Peers = int(peers)
	}
	diskBytes := int64(0)
	if p := strings.TrimSpace(cfg.Output); p != "" {
		diskBytes = tronDataSizeBytes(p)
	}
	if diskBytes <= 0 && strings.TrimSpace(cfg.DataDir) != "" {
		diskBytes = tronDataSizeBytes(cfg.DataDir)
	}
	if diskBytes > 0 {
		lcIn.SizeOnDisk = diskBytes
	}
	lifecycle := buildNodeLifecycle(lcIn)
	// Persist timestamps / current step observed this tick (pipeline Tick may enrich further).
	saveLifecycleProgress(cfg, prog)

	uiPhase, _ := lifecycle["phase"].(string)
	if uiPhase == "" {
		uiPhase = "setup"
	}
	nodeStatus, _ := lifecycle["node_status"].(string)
	if nodeStatus == "" {
		nodeStatus = "unknown"
	}

	// Prefer lifecycle profile over raw snapEnabled so mainnet checklist never omits snapshot.
	lcProfile := resolveLifecycleProfile(lcIn)
	includeSnapStep := lcProfile.IncludeSnapshot

	// Detailed checklist (OverviewCards); snapshot.active includes extract.
	setupSteps := []map[string]any{
		{"id": "registry", "title": "Instance registered", "done": instRegistered,
			"detail": "INSTANCE.json + /etc/rpcnode/instances.d"},
	}
	if includeSnapStep {
		setupSteps = append(setupSteps, map[string]any{
			"id": "snapshot", "title": "Chain data ready", "done": marker, "active": snapBusy,
			"detail": "snapshot marker or existing output-directory",
			"pct":    map[bool]any{true: "100", false: pct}[marker],
		})
	}
	setupSteps = append(setupSteps,
		map[string]any{"id": "node", "title": "java-tron running", "done": nodeActive,
			"detail": "process/systemd (agents stay up if down)",
			"active": (!lcProfile.SnapshotRequired || marker) && !nodeActive && !snapBusy && !snapFailed},
		map[string]any{"id": "rpc", "title": "RPC responding", "done": height != nil,
			"detail": "wallet/getnowblock", "active": nodeActive && height == nil},
		map[string]any{"id": "api", "title": "API agent up", "done": apiUp,
			"detail": fmt.Sprintf(":%d /healthz", apiProbePort)},
	)

	health := "ok"
	degraded := false
	switch {
	case enabled:
		health = "maintenance"
		degraded = true
	case snapFailed || uiPhase == "error":
		health = "error"
		degraded = true
	case uiPhase == "install" || uiPhase == "setup" || uiPhase == "snapshot" || uiPhase == "ports":
		health = "setup"
		degraded = true
	case uiPhase == "start" || uiPhase == "run":
		health = "degraded"
		degraded = true
	case !nodeActive || height == nil:
		health = "degraded"
		degraded = true
	}

	// Ready only when required snapshot (profile) is done — not when URL happens to be empty.
	nodeReady := nodeActive && !enabled && (!lcProfile.SnapshotRequired || marker) && height != nil

	agentActivity := "idle"
	agentStatus := "ok"
	agentLastErr := ""
	switch {
	case snapFailed:
		agentActivity = "snapshot_error"
		agentStatus = "error"
		agentLastErr = snapErr
		if agentLastErr == "" {
			agentLastErr = detail
		}
	case snapBusy:
		agentActivity = "snapshot_download"
		agentStatus = "ok"
	case uiPhase == "start" || (marker && (!nodeActive || height == nil)):
		agentActivity = "node_starting"
		if health == "degraded" {
			agentStatus = "degraded"
		}
	case uiPhase == "run":
		agentActivity = "syncing"
		agentStatus = "degraded"
	case nodeReady || uiPhase == "healthy":
		agentActivity = "online"
	case enabled:
		agentActivity = "idle"
		agentStatus = "degraded"
	default:
		if health == "degraded" || health == "setup" {
			agentStatus = "degraded"
		}
	}

	pauseMsg := ""
	if enabled {
		if r, ok := maint["reason"].(string); ok && r != "" {
			pauseMsg = r
		} else {
			pauseMsg = "RPC sleeping (503 Retry-After) during upgrade"
		}
	}
	pausePhase, _ := maint["phase"].(string)

	host := hostname()
	inst := map[string]any{
		"id":              fmt.Sprintf("%s-%s", network, cfg.Env),
		"network":         network,
		"env":             cfg.Env,
		"hostname":        host,
		"public_base_url": base,
		"panel_base_url":  panelBase,
		"status_url":      strings.TrimRight(panelBase, "/") + "/status",
		"gateway_listen":  fmt.Sprintf("%s:%d", cfg.APIListenHost, cfg.PublicRPCPort()),
		"gateway_port":    cfg.PublicRPCPort(),
		"public_port":     cfg.PublicRPCPort(),
		"panel_port":      cfg.AgentAPIPort(),
		"agent_port":      cfg.AgentAPIPort(),
		"rpc_mode":        map[bool]string{true: "go_proxy", false: "fullnode_direct"}[cfg.RPCProxyEnabled()],
		"node_http":       fmt.Sprintf("%s:%d", cfg.UpstreamHost, cfg.UpstreamPort),
		"node_http_port":  cfg.UpstreamPort,
		"p2p_port":        cfg.P2PPort,
		"data_dir":        cfg.DataDir,
		"output_dir":      cfg.Output,
		"opt_dir":         cfg.OptDir,
		"etc_dir":         cfg.EtcDir,
		"toolkit_root":    cfg.ToolkitDir,
		"state_file":      cfg.StateFile,
		"managed_by":      "RpcNode toolkit",
		"registered":      instRegistered,
		"services": []string{
			cfg.SystemService + ".service",
			cfg.APIService + ".service",
			cfg.NodeService + ".service",
		},
	}

	np := LookupNetworkProfile(cfg.Network, cfg.Env)
	snapLogTail := snapshotLogTail(cfg.SnapshotLog, 80)
	tronLogPath := filepath.Join(cfg.OptDir, "logs", "tron.log")
	if strings.TrimSpace(cfg.OptDir) == "" {
		tronLogPath = filepath.Join("/opt/tron", normalizeEnvName(cfg.Env), "logs", "tron.log")
	}
	tronLogTail := fileLogTail(tronLogPath, 80)
	logLines := snapLogTail
	logSource := "snapshot"
	logTitle := "Snapshot"
	if len(tronLogTail) > 0 {
		logLines = tronLogTail
		logSource = "tron.log"
		logTitle = "TRON · " + normalizeEnvName(cfg.Env)
	}
	clientVer := tronClientVersion(cfg, version)
	out := map[string]any{
		"ok":              true,
		"degraded":        degraded,
		"health":          health,
		"ui_phase":        uiPhase,
		"node_status":     nodeStatus,
		"lifecycle":       lifecycle,
		"supported_steps": np.SupportedLifecycleSteps(),
		"capabilities":    np.LifecycleCapabilities(),
		"env":             cfg.Env,
		"network":         network,
		"updated_at":      time.Now().UTC().Format("2006-01-02T15:04:05Z"),
		"managed_by":      "RpcNode toolkit",
		"agent_version":   agentVersion(),
		"client_version":  clientVer,
		"agent": map[string]any{
			"role":       "system",
			"version":    agentVersion(),
			"status":     agentStatus,
			"activity":   agentActivity,
			"last_error": agentLastErr,
			"interval":   cfg.Interval.String(),
			"internal":   cfg.InternalListen,
		},
		"logs": map[string]any{
			"title":  logTitle,
			"source": logSource,
			"lines":  logLines,
		},
		"instance": inst,
		"setup": map[string]any{
			"complete": uiPhase == "healthy",
			"phase":    uiPhase,
			"steps":    setupSteps,
			// Prefer lifecycle.steps for the install→snapshot→start→run stepper.
			"lifecycle_steps": lifecycle["steps"],
		},
		"disk":        diskRoot(),
		"output_size": duHuman(cfg.Output),
		"output_path": cfg.Output,
		"paths": map[string]any{
			"opt":          cfg.OptDir,
			"etc":          cfg.EtcDir,
			"data":         cfg.DataDir,
			"output":       cfg.Output,
			"toolkit_root": cfg.ToolkitDir,
			"instance":     cfg.InstanceFile,
			"registry":     cfg.RegistryFile,
			"state":        cfg.StateFile,
		},
		"checks": map[string]any{
			"java_tron_process": javaOK,
			"java_tron_cmd":     trimCmd(javaCmd),
			"node_process_up":   nodeActive,
			"node_port_open":    nodePortOpen,
			"node_http_ok":      rpcOK,
			"api_port_open":     apiPortOpen,
			"api_healthz":       apiHealth,
			"p2p_port":          cfg.P2PPort,
			"systemd_node":      nodeState,
		},
		"snapshot": map[string]any{
			"enabled":      snapEnabled,
			"ready":        marker,
			"pct":          map[bool]string{true: "100", false: pct}[marker],
			"eta":          eta,
			"phase":        phase,
			"detail":       detail,
			"error":        snapErr,
			"url":          url,
			"wget_running": wget,
			"can_start":    snapEnabled && !marker && !wget,
			"can_stop":     snapEnabled && wget,
			"manual":       true,
			"failed":       snapFailed,
			"log_tail":     snapLogTail,
			// Disk hint for UI (df only — no Content-Length probe on collect tick).
			"disk_free_gb":        round1(gib(mustDiskFreeBytes(cfg.DataDir))),
			"disk_required_gb":    round1(gib(requiredSnapshotFreeBytes(cachedOrDefaultArchiveBytes(cfg), cfg.Network))),
			"disk_abort_floor_gb": snapDiskAbortFloorGiB,
		},
		"actions": map[string]any{
			"snapshot": map[string]any{
				"can_start": snapEnabled && !marker && !wget,
				"can_stop":  snapEnabled && wget,
				"manual":    true,
				"enabled":   snapEnabled,
			},
		},
		"instances": listLocalInstances(cfg),
		"services": map[string]any{
			"node":     nodeSvcEffective,
			"snapshot": systemctlActive(cfg.SnapshotService),
			"api":      apiSvc,
			"system":   sysSvc,
			"gateway":  systemctlActive(cfg.GatewayService), // legacy
		},
		"maintenance": maint,
		"updater":     updater,
		"pause": map[string]any{
			"active":  enabled,
			"title":   map[bool]string{true: "UPDATE PAUSE", false: ""}[enabled],
			"message": pauseMsg,
			"phase":   pausePhase,
		},
		"version": func() map[string]any {
			out := map[string]any{"toolkit": agentVersion(), "agent": agentVersion(), "node": clientVer}
			for k, v := range version {
				out[k] = v
			}
			return out
		}(),
		"rpc": map[string]any{
			"gateway_port":   cfg.PublicRPCPort(),
			"rpc_port":       cfg.PublicRPCPort(),
			"panel_port":     cfg.AgentAPIPort(),
			"rpc_mode":       map[bool]string{true: "go_proxy", false: "fullnode_direct"}[cfg.RPCProxyEnabled()],
			"node":           fmt.Sprintf("%s:%d", cfg.UpstreamHost, cfg.UpstreamPort),
			"node_height":    height,
			"p2p_port":       cfg.P2PPort,
			"reachable":      rpcOK,
			"process_up":     nodeActive,
			"port_open":      nodePortOpen,
			"http_ok":        rpcOK,
			"client_version": clientVer,
		},
		"connect": tronConnectMap(cfg, base, nodeReady),
	}
	rpcBlock, _ := out["rpc"].(map[string]any)
	if localH > 0 {
		rpcBlock["blocks"] = localH
		rpcBlock["height"] = localH
	}
	if tipH > 0 {
		rpcBlock["headers"] = tipH
	}
	if peers >= 0 {
		rpcBlock["peers"] = peers
	}
	if verifyPct > 0 {
		rpcBlock["verification_pct"] = verifyPct
		rpcBlock["syncing"] = catching
	}
	if diskBytes > 0 {
		rpcBlock["size_on_disk"] = diskBytes
	}
	if localH > 0 || catching || rpcOK {
		syncBlock := map[string]any{
			"ok":      rpcOK && !catching,
			"syncing": catching,
			"ibd":     catching,
		}
		if localH > 0 {
			syncBlock["blocks"] = localH
			syncBlock["block"] = localH
			syncBlock["height"] = localH
		}
		if tipH > 0 {
			syncBlock["headers"] = tipH
			syncBlock["behind"] = behind
			syncBlock["blocks_behind"] = behind
		}
		if peers >= 0 {
			syncBlock["peers"] = peers
		}
		if diskBytes > 0 {
			syncBlock["size_on_disk"] = diskBytes
			syncBlock["size_on_disk_gb"] = round1(float64(diskBytes) / (1024 * 1024 * 1024))
		}
		if verifyPct > 0 {
			syncBlock["verification_pct"] = verifyPct
		}
		if !blockTime.IsZero() {
			syncBlock["block_time"] = blockTime.Format(time.RFC3339)
			syncBlock["updated_at"] = blockTime.Format(time.RFC3339)
		} else {
			syncBlock["updated_at"] = time.Now().UTC().Format(time.RFC3339)
		}
		switch {
		case catching && localH > 0 && tipH > 0:
			syncBlock["detail"] = fmt.Sprintf("node %d · tip %d · %d behind", localH, tipH, behind)
			if verifyPct > 0 {
				syncBlock["detail"] = fmt.Sprintf("%s · %.1f%% lag closed", syncBlock["detail"], verifyPct)
			}
		case catching && localH > 0:
			syncBlock["detail"] = fmt.Sprintf("height %d · waiting for public tip", localH)
		case rpcOK && localH > 0:
			syncBlock["detail"] = fmt.Sprintf("Synced · block %d", localH)
		default:
			syncBlock["detail"] = "Waiting for java-tron RPC"
		}
		if peers >= 0 && syncBlock["detail"] != nil {
			syncBlock["detail"] = fmt.Sprintf("%s · peers %d", syncBlock["detail"], peers)
		}
		out["sync"] = syncBlock
	}
	return out
}

// connectBaseMap — ports / bases only. Chain-specific paths and curl examples
// are added by the network collect (tronConnectMap, bitcoinConnectMap, …).
func connectBaseMap(cfg Config, rpcBase string, ready bool) map[string]any {
	rpcBase = strings.TrimRight(rpcBase, "/")
	rpcPort := cfg.PublicRPCPort()
	agentPort := cfg.AgentAPIPort()
	_, panelBase := effectivePublicBases(cfg)
	if rpcBase != "" {
		panelBase = swapURLPort(rpcBase, agentPort)
	}
	if panelBase == "" {
		panelBase = fmt.Sprintf("http://127.0.0.1:%d", agentPort)
	}
	note := "Clients → Go RPC (public_port) → upstream FullNode (node_http). " +
		"On update Go sleeps RPC with 503 Retry-After. Agent API on agent_port."
	if !cfg.RPCProxyEnabled() {
		note = "Misconfig: RPCNODE_PUBLIC_PORT=0 — no Go sleep control on RPC. Prefer go_proxy mode."
	}
	return map[string]any{
		"ready":            ready,
		"base_url":         rpcBase,
		"rpc_base":         rpcBase,
		"panel_base":       panelBase,
		"public_port":      rpcPort,
		"agent_port":       agentPort,
		"rpc_port":         rpcPort,
		"panel_port":       agentPort,
		"rpc_mode":         map[bool]string{true: "go_proxy", false: "fullnode_direct"}[cfg.RPCProxyEnabled()],
		"http_fullnode":    rpcBase,
		"internal_node":    fmt.Sprintf("http://%s:%d", cfg.UpstreamHost, cfg.UpstreamPort),
		"p2p":              fmt.Sprintf("0.0.0.0:%d", cfg.P2PPort),
		"rpcnode_upstream": rpcBase,
		"note":             note,
	}
}

// tronConnectMap — TRON FullNode HTTP API paths (only the tron collect uses this).
func tronConnectMap(cfg Config, rpcBase string, ready bool) map[string]any {
	m := connectBaseMap(cfg, rpcBase, ready)
	rpcBase = strings.TrimRight(rpcBase, "/")
	m["wallet"] = rpcBase + "/wallet"
	m["walletsolidity"] = rpcBase + "/walletsolidity"
	m["getnowblock"] = rpcBase + "/wallet/getnowblock"
	m["getnodeinfo"] = rpcBase + "/wallet/getnodeinfo"
	m["examples"] = map[string]string{
		"curl_height":  fmt.Sprintf("curl -s %s/wallet/getnowblock | jq '.block_header.raw_data.number'", rpcBase),
		"curl_version": fmt.Sprintf("curl -s %s/wallet/getnodeinfo | jq '.configNodeInfo.codeVersion'", rpcBase),
		"curl_account": fmt.Sprintf(`curl -s -X POST %s/wallet/getaccount -H 'content-type: application/json' -d '{"address":"T…","visible":true}'`, rpcBase),
	}
	m["note"] = "Clients → Go RPC (public_port) → java-tron FullNode HTTP. Agent API on agent_port."
	return m
}

// bitcoinFamilyConnectMap — Core JSON-RPC via Go proxy (bitcoin / doge / ltc / dash / bch).
func bitcoinFamilyConnectMap(cfg Config, rpcBase string, ready bool, daemon string, example string) map[string]any {
	m := connectBaseMap(cfg, rpcBase, ready)
	rpcBase = strings.TrimRight(rpcBase, "/")
	m["public_port"] = cfg.PublicRPCPort()
	m["agent_port"] = cfg.AgentAPIPort()
	m["p2p_port"] = cfg.P2PPort
	m["http_fullnode"] = rpcBase
	m["internal_node"] = fmt.Sprintf("http://%s:%d", cfg.UpstreamHost, cfg.UpstreamPort)
	if example == "" {
		example = fmt.Sprintf(
			`curl -s -X POST %s -H 'Content-Type: application/json' -d '{"jsonrpc":"1.0","id":"1","method":"getblockchaininfo","params":[]}'`,
			rpcBase,
		)
	}
	m["examples"] = map[string]string{"curl_rest": example}
	if daemon == "" {
		daemon = "bitcoind"
	}
	m["note"] = "Clients → Go RPC (public_port) → " + daemon + " (node_http). Agent API on agent_port."
	return m
}

func swapURLPort(base string, port int) string {
	base = strings.TrimRight(strings.TrimSpace(base), "/")
	if base == "" || port <= 0 {
		return base
	}
	proto := "http"
	rest := base
	if strings.HasPrefix(base, "https://") {
		proto = "https"
		rest = base[len("https://"):]
	} else if strings.HasPrefix(base, "http://") {
		rest = base[len("http://"):]
	}
	host := rest
	path := ""
	if i := strings.IndexByte(rest, '/'); i >= 0 {
		host = rest[:i]
		path = rest[i:]
	}
	if strings.HasPrefix(host, "[") {
		if i := strings.Index(host, "]:"); i >= 0 {
			host = host[:i+1]
		}
	} else if i := strings.LastIndexByte(host, ':'); i >= 0 {
		host = host[:i]
	}
	return fmt.Sprintf("%s://%s:%d%s", proto, host, port, path)
}

func trimCmd(s string) string {
	if len(s) > 180 {
		return s[:180] + "…"
	}
	return s
}

// listLocalInstances — registry + known envs for ops console env switcher.
func listLocalInstances(cfg Config) []map[string]any {
	network := cfg.Network
	if network == "" {
		network = DefaultNetwork
	}
	known := ListKnownEnvs(network)
	prefix := network + "-"
	byEnv := map[string]map[string]any{}

	regDir := "/etc/rpcnode/instances.d"
	if cfg.RegistryFile != "" {
		regDir = filepath.Dir(cfg.RegistryFile)
	}
	if ents, err := os.ReadDir(regDir); err == nil {
		for _, e := range ents {
			if e.IsDir() || !strings.HasPrefix(e.Name(), prefix) || !strings.HasSuffix(e.Name(), ".json") {
				continue
			}
			doc := readJSONFile(filepath.Join(regDir, e.Name()))
			env, _ := doc["env"].(string)
			if env == "" {
				env = strings.TrimSuffix(strings.TrimPrefix(e.Name(), prefix), ".json")
			}
			byEnv[env] = normalizeInstanceDoc(env, doc, cfg)
		}
	}

	libRoot := filepath.Dir(filepath.Dir(cfg.StateFile)) // /var/lib/rpcnode
	if libRoot == "" || libRoot == "." {
		libRoot = "/var/lib/rpcnode"
	}
	if ents, err := os.ReadDir(libRoot); err == nil {
		for _, e := range ents {
			if !e.IsDir() || !strings.HasPrefix(e.Name(), prefix) {
				continue
			}
			env := strings.TrimPrefix(e.Name(), prefix)
			if _, ok := byEnv[env]; ok {
				continue
			}
			sidecar := readJSONFile(filepath.Join(libRoot, e.Name(), "INSTANCE.json"))
			byEnv[env] = normalizeInstanceDoc(env, sidecar, cfg)
		}
	}

	for _, env := range known {
		if _, ok := byEnv[env]; !ok {
			byEnv[env] = normalizeInstanceDoc(env, nil, cfg)
		}
	}

	out := make([]map[string]any, 0, len(byEnv))
	// Prefer known order, then extras.
	seen := map[string]bool{}
	for _, env := range known {
		if doc, ok := byEnv[env]; ok {
			out = append(out, doc)
			seen[env] = true
		}
	}
	extras := make([]string, 0)
	for env := range byEnv {
		if !seen[env] {
			extras = append(extras, env)
		}
	}
	sort.Strings(extras)
	for _, env := range extras {
		out = append(out, byEnv[env])
	}
	return out
}

func asString(v any, def string) string {
	if v == nil {
		return def
	}
	s, ok := v.(string)
	if !ok || s == "" {
		return def
	}
	return s
}

func normalizeInstanceDoc(env string, doc map[string]any, cfg Config) map[string]any {
	if doc == nil {
		doc = map[string]any{}
	}
	network := cfg.Network
	if network == "" {
		network = DefaultNetwork
	}
	id := asString(doc["id"], network+"-"+env)
	state := fmt.Sprintf("/var/lib/rpcnode/%s-%s/agent-state.json", network, env)
	if v := asString(doc["state_file"], ""); v != "" {
		state = v
	} else if env == cfg.Env {
		state = cfg.StateFile
	}
	localState := fileExists(state)
	base := asString(doc["public_base_url"], "")
	if base == "" && env == cfg.Env {
		base = cfg.PublicBase
	}
	snapURL := ""
	if env == cfg.Env {
		snapURL = cfg.SnapshotURL
	}
	if v := asString(doc["snapshot_url"], ""); v != "" {
		snapURL = v
	}
	snapEnabled := snapURL != ""
	if v, has := doc["snapshot_enabled"]; has {
		snapEnabled = truthy(v) && snapURL != ""
	} else if env == cfg.Env {
		snapEnabled = snapshotFeatureEnabled(cfg)
	}

	panelBase := asString(doc["panel_base_url"], "")
	if panelBase == "" && env == cfg.Env && cfg.PanelBase != "" {
		panelBase = cfg.PanelBase
	}
	panelPort := cfg.PanelPort
	if env != cfg.Env {
		// Same default panel for every env; registry may override below.
		panelPort = 8093
	}
	if v, ok := doc["panel_port"].(float64); ok && int(v) > 0 {
		panelPort = int(v)
	}
	if panelBase == "" && base != "" {
		panelBase = swapURLPort(base, panelPort)
	}

	statusURL := asString(doc["status_url"], "")
	if statusURL == "" && panelBase != "" {
		statusURL = strings.TrimRight(panelBase, "/") + "/status"
	}
	statusJSON := asString(doc["status_json_url"], "")
	if statusJSON == "" && panelBase != "" {
		statusJSON = strings.TrimRight(panelBase, "/") + "/status.json"
	}

	out := map[string]any{
		"id":               id,
		"env":              env,
		"hostname":         asString(doc["hostname"], ""),
		"public_base_url":  base,
		"panel_base_url":   panelBase,
		"status_url":       statusURL,
		"status_json_url":  statusJSON,
		"state_file":       state,
		"local_state":      localState,
		"current":          env == cfg.Env,
		"snapshot_enabled": snapEnabled,
		"registered":       asString(doc["id"], "") != "",
		"panel_port":       panelPort,
	}
	if v, ok := doc["gateway_port"]; ok {
		out["gateway_port"] = v
	}
	if v, ok := doc["public_port"]; ok {
		out["public_port"] = v
	} else if v, ok := doc["gateway_port"]; ok {
		out["public_port"] = v
	}
	if v, ok := doc["node_http_port"]; ok {
		out["node_http_port"] = v
	}
	if v, ok := doc["gateway_listen"]; ok {
		out["gateway_listen"] = v
	}
	if v, ok := doc["node_http"]; ok {
		out["node_http"] = v
	}
	return out
}

// collectHostTip — multi-chain Server control plane. No TRON/BTC/… node lifecycle.
func collectHostTip(cfg Config) map[string]any {
	now := time.Now().UTC().Format(time.RFC3339)
	apiUp := cfg.APIListenPort > 0 && portOpen("127.0.0.1", cfg.APIListenPort) &&
		httpOK(fmt.Sprintf("http://127.0.0.1:%d/healthz", cfg.APIListenPort))

	return map[string]any{
		"ok":            true,
		"alive":         true,
		"host_tip":      true,
		"health":        "host",
		"degraded":      false,
		"ui_phase":      "host",
		"node_status":   "host",
		"env":           cfg.Env,
		"updated_at":    now,
		"agent_version": agentVersion(),
		"agent": map[string]any{
			"role":     "system",
			"status":   "ok",
			"activity": "host_tip",
			"version":  agentVersion(),
			"internal": cfg.InternalListen,
			"interval": cfg.Interval.String(),
		},
		"checks": map[string]any{
			"api_healthz":   apiUp,
			"api_port_open": cfg.APIListenPort > 0 && portOpen("127.0.0.1", cfg.APIListenPort),
			"host_tip":      true,
		},
		"snapshot": map[string]any{
			"enabled": false, "ready": false, "failed": false,
			"phase": "idle", "detail": "host tip — no chain snapshot",
		},
		"actions": map[string]any{
			"snapshot": map[string]any{
				"enabled": false, "can_start": false, "can_stop": false, "manual": false,
			},
		},
		"lifecycle": map[string]any{
			"phase":       "host",
			"node_status": "host",
			"label":       "Host Server",
			"detail":      "Control plane — per-node agents own chain lifecycle",
			"complete":    true,
			"busy":        false,
			"steps":       []map[string]any{},
		},
		"instance": map[string]any{
			"id":          "host",
			"host_tip":    true,
			"env":         cfg.Env,
			"state_file":  cfg.StateFile,
			"public_port": cfg.APIListenPort,
		},
	}
}

func logRecover(rec any) {
	fmt.Fprintf(os.Stderr, "collect panic: %v\n", rec)
}

func dockerRunning(name string) bool {
	out, err := runCmd(2*time.Second, "docker", "inspect", "-f", "{{.State.Running}}", name)
	if err != nil {
		return false
	}
	return strings.TrimSpace(out) == "true"
}
