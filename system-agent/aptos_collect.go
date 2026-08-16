package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// collectAptos — aptos-node catch-up via inspection metrics + public tip ledger_version.
// Honest %: lag-closed vs peak (Solana lesson — ❌ me/tip as fake %).
// Primary UI metric = versions behind.
func collectAptos(cfg Config) map[string]any {
	network := cfg.Network
	if network == "" {
		network = "aptos"
	}
	prof := LookupNetworkProfile(network, cfg.Env)

	nodeState := systemctlActive(cfg.NodeService)
	procOK, _ := aptosNodeRunningFor(cfg)
	startErr, startBad := aptosStartFailureDetail(cfg, procOK)
	nodeActive := procOK || nodeState == "active" || nodeState == "activating"
	if startBad {
		nodeActive = false
	}

	metricsPort := aptosMetricsPort(cfg, prof)
	var synced, tip int64
	var metricsOK, rpcOK bool
	var clientVer string
	var nodePortOpen bool

	if nodeActive {
		nodePortOpen = portOpen(cfg.UpstreamHost, cfg.UpstreamPort)
		synced, metricsOK = scrapeAptosSyncedVersion(cfg, metricsPort)
		if nodePortOpen {
			localTip, ok := aptosLocalLedgerVersion(cfg)
			if ok && localTip > synced {
				synced = localTip
			}
			rpcOK = ok || metricsOK
			clientVer = aptosClientVersion(cfg)
		}
		tip = aptosPublicTipLedgerVersion(cfg)
		// ❌ Do NOT use journal "Adding a new epoch … Version: N" as local —
		// that Version is the network epoch-ending ledger tip being catalogued,
		// not applied state (lied at ~99.9% while real synced was still millions).
		// Honest signal: aptos_state_sync_version{type="synced"} / REST ledger_version.
	}

	behind := int64(0)
	catchingUp := false
	if tip > 0 && synced >= 0 {
		behind = tip - synced
		if behind < 0 {
			behind = 0
		}
		catchingUp = behind > 50
	}
	healthy := (rpcOK || metricsOK) && !catchingUp && synced > 0 && tip > 0
	if (rpcOK || metricsOK) && tip > 0 && behind <= 50 && synced > 0 {
		healthy = true
		catchingUp = false
	}

	verifyPct, verifyOK := aptosVerificationPct(cfg, healthy, catchingUp, behind)
	if !verifyOK {
		verifyPct = 0
	}

	nodeSvcEffective := nodeState
	switch {
	case startBad:
		nodeSvcEffective = "failed"
	case nodeActive && healthy:
		nodeSvcEffective = "active"
	case nodeActive || nodePortOpen:
		if nodeState != "active" {
			nodeSvcEffective = "activating"
		} else {
			nodeSvcEffective = "active"
		}
	}

	agentPort := cfg.AgentAPIPort()
	apiProbePort := agentPort
	if apiProbePort <= 0 {
		apiProbePort = cfg.PublicRPCPort()
	}
	apiPortOpen := apiProbePort > 0 && portOpen("127.0.0.1", apiProbePort)
	apiHealth := apiProbePort > 0 && httpOK(fmt.Sprintf("http://127.0.0.1:%d/healthz", apiProbePort))
	apiSvc := "inactive"
	if apiHealth || apiPortOpen {
		apiSvc = "active"
	} else if g := systemctlActive(cfg.APIService); g == "active" {
		apiSvc = g
	}

	instRegistered := fileExists(cfg.RegistryFile) || fileExists(cfg.InstanceFile)
	apiUp := apiHealth || apiPortOpen
	publicPort := cfg.PublicRPCPort()
	publicPortOpen := publicPort > 0 && portOpen("127.0.0.1", publicPort)
	agentPortOpen := apiProbePort > 0 && (apiPortOpen || apiHealth)

	diskOK, freeGiB, needGiB, diskDetail := aptosDiskGateOK(cfg, prof)

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
		SnapEnabled:    false,
		NodeActive:     nodeActive,
		StartError:     startErr,
		RPCOK:          rpcOK || metricsOK,
		IBD:            catchingUp,
		VerifyPct:      verifyPct / 100,
		Progress:       prog,
	}
	if synced > 0 {
		lcIn.Height = synced
	}
	lifecycle := buildNodeLifecycle(lcIn)
	saveLifecycleProgress(cfg, prog)

	uiPhase, _ := lifecycle["phase"].(string)
	if uiPhase == "" {
		uiPhase = "setup"
	}
	nodeStatus, _ := lifecycle["node_status"].(string)
	if nodeStatus == "" {
		nodeStatus = "unknown"
	}

	syncDetail := ""
	switch {
	case !nodeActive:
		syncDetail = "aptos-node not running"
	case healthy:
		syncDetail = fmt.Sprintf("Synced · ledger version %d", synced)
		clearAptosCatchupMaxBehind(cfg)
	case catchingUp:
		if synced <= 0 && (metricsOK || tip > 0) {
			syncDetail = "Bootstrap · synced metric still 0 (ExecuteOrApplyFromGenesis)"
		} else {
			syncDetail = fmt.Sprintf("Catch-up · %d versions behind · local %d", behind, synced)
		}
		if tip > 0 {
			syncDetail = fmt.Sprintf("%s · tip %d", syncDetail, tip)
		}
		if verifyOK {
			syncDetail = fmt.Sprintf("%s · %s", syncDetail, formatCoreSyncPct(verifyPct))
		}
	default:
		syncDetail = "Waiting for metrics/REST"
	}

	setupSteps := []map[string]any{
		{"id": "registry", "title": "Instance registered", "done": instRegistered,
			"detail": "INSTANCE.json + /etc/rpcnode/instances.d"},
		{"id": "disk", "title": "Disk floor for fullnode", "done": diskOK,
			"detail": diskDetail, "active": !diskOK && apiUp},
		{"id": "node", "title": "aptos-node running", "done": nodeActive,
			"detail": "process/systemd", "active": apiUp && !nodeActive},
		{"id": "rpc", "title": "REST / metrics", "done": rpcOK || metricsOK,
			"detail": "aptos_state_sync_version + /v1 ledger_version",
			"active": nodeActive && !(rpcOK || metricsOK)},
		{"id": "sync", "title": "Ledger catch-up", "done": healthy,
			"detail": syncDetail, "active": catchingUp,
			"pct": map[bool]any{true: verifyPct, false: nil}[catchingUp && verifyOK]},
		{"id": "api", "title": "API agent up", "done": apiUp,
			"detail": fmt.Sprintf(":%d /healthz", apiProbePort)},
	}

	logTail := strings.Split(journalUnitSnippet(cfg.NodeService, 80), "\n")

	return map[string]any{
		"ok": true, "ts": time.Now().UTC().Format(time.RFC3339),
		"network": network, "env": cfg.Env,
		"ui_phase": uiPhase, "node_status": nodeStatus,
		"lifecycle":   lifecycle,
		"setup_steps": setupSteps,
		"disk_gate": map[string]any{
			"ok": diskOK, "free_gib": freeGiB, "need_gib": needGiB, "detail": diskDetail,
		},
		"sync": map[string]any{
			"ok": healthy, "syncing": catchingUp, "ibd": catchingUp,
			"ledger_version": synced, "version": synced,
			"tip_version": tip, "behind": behind, "versions_behind": behind,
			"verification_pct": verifyPct, "detail": syncDetail,
			"network": network, "log_tail": logTail,
		},
		"rpc": map[string]any{
			"ok": rpcOK || metricsOK, "ledger_version": synced,
			"behind": behind, "verification_pct": verifyPct,
			"client_version": clientVer,
		},
		"services": map[string]any{
			"node": nodeSvcEffective, "api": apiSvc,
		},
		"checks": map[string]any{
			"node_process_up": procOK, "aptos_node_process": procOK,
			"node_port_open": nodePortOpen, "metrics_ok": metricsOK,
		},
		"ports": map[string]any{
			"public": publicPort, "agent": agentPort, "node_http": cfg.UpstreamPort,
			"metrics": metricsPort, "p2p": LookupNetworkProfile(network, cfg.Env).DefaultP2PPort,
		},
		"start_error": startErr,
		"logs": map[string]any{
			"title":  "Logs",
			"source": "aptos-sync",
			"lines":  logTail,
		},
		"version":        agentVersion(),
		"client_version": clientVer,
	}
}

func aptosMetricsPort(cfg Config, prof NetworkProfile) int {
	if v := envOr("TRON_METRICS_PORT", ""); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return n
		}
	}
	if strings.EqualFold(cfg.Env, "testnet") {
		return 9102
	}
	if prof.DefaultNodeHTTP == 8081 {
		return 9102
	}

	return 9101
}

func scrapeAptosSyncedVersion(cfg Config, metricsPort int) (synced int64, ok bool) {
	if metricsPort <= 0 {
		return 0, false
	}
	url := envOr("APTOS_METRICS_URL", fmt.Sprintf("http://127.0.0.1:%d/metrics", metricsPort))
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return 0, false
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return 0, false
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return 0, false
	}
	sc := bufio.NewScanner(io.LimitReader(resp.Body, 4<<20))
	for sc.Scan() {
		ln := strings.TrimSpace(sc.Text())
		if ln == "" || strings.HasPrefix(ln, "#") {
			continue
		}
		name, labels, val, parsed := parsePromSampleLabeled(ln)
		if !parsed || name != "aptos_state_sync_version" {
			continue
		}
		if labels["type"] == "synced" {
			synced = val
			ok = true
		}
	}

	return synced, ok
}

// parsePromSampleLabeled — metric{k="v",…} value → name + label map.
func parsePromSampleLabeled(line string) (name string, labels map[string]string, value int64, ok bool) {
	space := strings.LastIndex(line, " ")
	if space <= 0 {
		return "", nil, 0, false
	}
	left := line[:space]
	right := strings.TrimSpace(line[space+1:])
	f, err := strconv.ParseFloat(right, 64)
	if err != nil {
		return "", nil, 0, false
	}
	labels = map[string]string{}
	name = left
	if i := strings.IndexByte(left, '{'); i >= 0 {
		name = strings.TrimSpace(left[:i])
		end := strings.LastIndexByte(left, '}')
		if end > i {
			inner := left[i+1 : end]
			for _, part := range strings.Split(inner, ",") {
				part = strings.TrimSpace(part)
				k, v, cut := strings.Cut(part, "=")
				if !cut {
					continue
				}
				labels[strings.TrimSpace(k)] = strings.Trim(strings.TrimSpace(v), `"`)
			}
		}
	}
	name = strings.TrimSpace(name)
	if name == "" {
		return "", nil, 0, false
	}

	return name, labels, int64(f), true
}

func aptosLocalLedgerVersion(cfg Config) (int64, bool) {
	url := fmt.Sprintf("http://%s:%d/v1", cfg.UpstreamHost, cfg.UpstreamPort)

	return aptosRESTLedgerVersion(url)
}

func aptosPublicTipLedgerVersion(cfg Config) int64 {
	tipURL := strings.TrimSpace(envOr("APTOS_PUBLIC_TIP_REST", ""))
	if tipURL == "" {
		if b, err := os.ReadFile(filepath.Join(cfg.EtcDir, "public_tip.url")); err == nil {
			tipURL = strings.TrimSpace(string(b))
		}
	}
	if tipURL == "" {
		if strings.EqualFold(cfg.Env, "testnet") {
			tipURL = "https://fullnode.testnet.aptoslabs.com/v1"
		} else {
			tipURL = "https://fullnode.mainnet.aptoslabs.com/v1"
		}
	}
	n, ok := aptosRESTLedgerVersion(tipURL)
	if !ok {
		return 0
	}

	return n
}

func aptosRESTLedgerVersion(url string) (int64, bool) {
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return 0, false
	}
	req.Header.Set("Accept", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return 0, false
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return 0, false
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return 0, false
	}
	var doc map[string]any
	if err := json.Unmarshal(b, &doc); err != nil {
		return 0, false
	}
	n, ok := parseAptosLedgerVersion(doc["ledger_version"])
	return n, ok
}

func parseAptosLedgerVersion(v any) (int64, bool) {
	switch t := v.(type) {
	case float64:
		return int64(t), true
	case json.Number:
		n, err := t.Int64()
		return n, err == nil
	case string:
		n, err := strconv.ParseInt(strings.TrimSpace(t), 10, 64)
		return n, err == nil
	case int64:
		return t, true
	case int:
		return int64(t), true
	default:
		return 0, false
	}
}

func aptosClientVersion(cfg Config) string {
	bin := filepath.Join(cfg.OptDir, "bin", "aptos-node")
	if !fileExists(bin) {
		bin = "aptos-node"
	}
	out, err := runCmd(3*time.Second, bin, "--version")
	if err != nil {
		return ""
	}
	s := strings.TrimSpace(out)
	if s == "" {
		return ""
	}
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		s = s[:i]
	}

	return formatClientVersion(s)
}

func aptosVerificationPct(cfg Config, healthy, catchingUp bool, behind int64) (float64, bool) {
	// Healthy threshold: versions behind ≤ 50 → 100 (clear peak).
	if healthy || (catchingUp && behind <= 50) {
		clearAptosCatchupMaxBehind(cfg)

		return 100, true
	}
	if catchingUp {
		return aptosCatchupLagClosedPct(cfg, behind)
	}

	return 0, false
}

func aptosCatchupLagClosedPct(cfg Config, behind int64) (float64, bool) {
	if behind < 0 {
		return 0, false
	}
	if behind == 0 {
		return 99.9, true
	}
	maxBehind := loadAptosCatchupMaxBehind(cfg)
	if behind > maxBehind {
		maxBehind = behind
		saveAptosCatchupMaxBehind(cfg, maxBehind)
	}
	if maxBehind <= 0 {
		return 0, false
	}
	pct := float64(maxBehind-behind) / float64(maxBehind) * 100
	if pct > 99.9 {
		pct = 99.9
	}
	if pct < 0.1 {
		pct = 0.1
	}
	pct = float64(int(pct*10+0.5)) / 10

	return pct, true
}

func aptosCatchupStatePath(cfg Config) string {
	return filepath.Join(filepath.Dir(cfg.StateFile), "aptos-catchup.json")
}

func loadAptosCatchupMaxBehind(cfg Config) int64 {
	doc := readJSONFile(aptosCatchupStatePath(cfg))
	if doc == nil {
		return 0
	}
	switch v := doc["max_behind"].(type) {
	case float64:
		return int64(v)
	case int64:
		return v
	case int:
		return int64(v)
	default:
		return 0
	}
}

func saveAptosCatchupMaxBehind(cfg Config, maxBehind int64) {
	if maxBehind <= 0 || strings.TrimSpace(cfg.StateFile) == "" {
		return
	}
	_ = ensureDir(filepath.Dir(cfg.StateFile))
	_ = writeJSONFile(aptosCatchupStatePath(cfg), map[string]any{
		"max_behind": maxBehind,
		"updated_at": time.Now().UTC().Format(time.RFC3339),
	})
}

func clearAptosCatchupMaxBehind(cfg Config) {
	if strings.TrimSpace(cfg.StateFile) == "" {
		return
	}
	_ = os.Remove(aptosCatchupStatePath(cfg))
}

func aptosNodeRunningFor(cfg Config) (bool, string) {
	unit := strings.TrimSpace(cfg.NodeService)
	if unit != "" {
		name := unit
		if !strings.HasSuffix(name, ".service") {
			name += ".service"
		}
		out, _ := runCmd(3*time.Second, "systemctl", "show", name,
			"-p", "ActiveState", "-p", "MainPID", "--no-pager")
		state, pid := "", 0
		for _, ln := range strings.Split(out, "\n") {
			ln = strings.TrimSpace(ln)
			if k, v, ok := strings.Cut(ln, "="); ok {
				switch k {
				case "ActiveState":
					state = v
				case "MainPID":
					pid, _ = strconv.Atoi(v)
				}
			}
		}
		if (state == "active" || state == "activating") && pid > 0 {
			cmdOut, _ := runCmd(2*time.Second, "ps", "-p", strconv.Itoa(pid), "-o", "args=")
			cmd := strings.TrimSpace(cmdOut)
			if cmd != "" && strings.Contains(cmd, "aptos-node") {
				return true, cmd
			}
		}
	}
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		data = LookupNetworkProfile(cfg.Network, cfg.Env).DataPath
	}
	out, err := runCmd(2*time.Second, "bash", "-lc",
		fmt.Sprintf(`ps -eo pid=,args= | grep -E '[a]ptos-node' | grep -F %q | head -1`, data))
	if err != nil || strings.TrimSpace(out) == "" {
		return false, ""
	}

	return true, strings.TrimSpace(out)
}

func aptosStartFailureDetail(cfg Config, procOK bool) (string, bool) {
	if procOK {
		return "", false
	}
	state := systemctlActive(cfg.NodeService)
	restarts := aptosUnitNRestarts(cfg.NodeService)
	snip := journalUnitSnippet(cfg.NodeService, 24)
	low := strings.ToLower(snip)
	bindClash := strings.Contains(low, "address already in use") || strings.Contains(low, "os error 98")
	panicLike := strings.Contains(low, "panicked at") || strings.Contains(low, "error binding to")

	// Crash-loop while systemd shows activating/auto-restart — not a silent 0.1% "warming up".
	if restarts >= 3 && (bindClash || panicLike || systemctlFailed(cfg.NodeService) || state == "failed" || state == "activating") {
		if bindClash {
			return "aptos-node crash-loop: port bind failed (Address already in use) — check inspection_service/admin_service ports vs other aptos env on host", true
		}
		if snip != "" {
			return snip, true
		}
		return fmt.Sprintf("aptos-node crash-loop (NRestarts=%d)", restarts), true
	}
	if systemctlFailed(cfg.NodeService) || state == "failed" {
		if snip != "" {
			return snip, true
		}

		return "aptos-node unit failed", true
	}

	return "", false
}

func aptosUnitNRestarts(unit string) int {
	name := strings.TrimSpace(unit)
	if name == "" {
		return 0
	}
	if !strings.HasSuffix(name, ".service") {
		name += ".service"
	}
	out, _ := runCmd(2*time.Second, "systemctl", "show", name, "-p", "NRestarts", "--value")
	n, _ := strconv.Atoi(strings.TrimSpace(out))
	return n
}

func aptosDiskGateOK(cfg Config, prof NetworkProfile) (ok bool, freeGiB, needGiB float64, detail string) {
	needGiB = prof.DiskHintGiB
	if needGiB <= 0 {
		needGiB = 2048
	}
	floor := needGiB * 0.05
	if floor < 80 {
		floor = 80
	}
	freeGiB = freeDiskGiB(cfg.DataDir)
	ok = freeGiB >= floor
	detail = fmt.Sprintf("%.0f GiB free (floor %.0f GiB for aptos fullnode; hint %.0f GiB)", freeGiB, floor, needGiB)

	return ok, freeGiB, needGiB, detail
}
