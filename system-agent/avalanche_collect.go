package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// collectAvalanche — AvalancheGo C-Chain bootstrap + catch-up.
// Honest %:
//  1) Bootstrap (info.isBootstrapped chain=C false): AvalancheGo journal
//     `"pctComplete": N` / numFetchedBlocks÷numTotalBlocks (P/X/C) or prose "fetched X of Y"
//     ❌ lag-closed alone while bootstrapping → stuck UI 0.1% (local=0, tip=huge)
//  2) Catch-up: eth_syncing or eth_blockNumber vs public tip → lag-closed vs peak (❌ blind local/tip)
//  3) Healthy: bootstrapped && !eth_syncing && behind ≤ 12 → 100
// Primary UI: bootstrap % then blocks behind.
func collectAvalanche(cfg Config) map[string]any {
	network := cfg.Network
	if network == "" {
		network = "avalanche"
	}
	env := normalizeAvalancheEnvName(cfg.Env)
	prof := LookupNetworkProfile(network, env)

	nodeState := systemctlActive(cfg.NodeService)
	procOK, _ := avalancheNodeRunningFor(cfg)
	startErr, startBad := avalancheStartFailureDetail(cfg, procOK)
	nodeActive := procOK || nodeState == "active" || nodeState == "activating"
	if startBad {
		nodeActive = false
	}

	var (
		bootstrapped bool
		bootOK       bool
		rpcOK        bool
		syncing      bool
		localBlock   int64
		tipBlock     int64
		clientVer    string
		nodePortOpen bool
		bootPct      float64
		bootPctOK    bool
		bootDetail   string
	)

	if nodeActive {
		nodePortOpen = portOpen(cfg.UpstreamHost, cfg.UpstreamPort)
		if nodePortOpen {
			bootstrapped, bootOK = avalancheIsBootstrapped(cfg)
			clientVer = avalancheClientVersion(cfg)
			if eth, ok := avalancheProbeCChain(cfg); ok {
				rpcOK = true
				syncing = eth.Syncing
				localBlock = eth.Block
				if eth.CurrentBlock > localBlock {
					localBlock = eth.CurrentBlock
				}
			}
		}
		bootPct, bootPctOK, bootDetail = avalancheBootstrapPctFromLogs(cfg)
		tipBlock = avalanchePublicTipBlock(cfg)
	}

	behind := int64(0)
	catchingUp := false
	if tipBlock > 0 && localBlock >= 0 {
		behind = tipBlock - localBlock
		if behind < 0 {
			behind = 0
		}
	}
	if bootOK && !bootstrapped {
		catchingUp = true // bootstrap phase
	} else if syncing || behind > 12 {
		catchingUp = true
	}
	healthy := bootOK && bootstrapped && rpcOK && !syncing && behind <= 12 && localBlock > 0

	verifyPct, verifyOK := avalancheVerificationPct(cfg, healthy, bootOK && !bootstrapped, bootPct, bootPctOK, catchingUp, behind)

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

	diskOK, freeGiB, needGiB, diskDetail := avalancheDiskGateOK(cfg, prof)

	prog := loadLifecycleProgress(cfg)
	lcIn := nodeLifecycleInput{
		Network:        network,
		Env:            env,
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
		RPCOK:          rpcOK || (bootOK && nodePortOpen),
		IBD:            catchingUp,
		VerifyPct:      verifyPct / 100,
		Progress:       prog,
	}
	if localBlock > 0 {
		lcIn.Height = localBlock
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
		syncDetail = "avalanchego not running"
	case healthy:
		syncDetail = fmt.Sprintf("Synced · C-Chain block %d", localBlock)
		clearAvalancheCatchupMaxBehind(cfg)
	case bootOK && !bootstrapped:
		syncDetail = "Bootstrap · C-Chain not bootstrapped"
		if bootDetail != "" {
			syncDetail = fmt.Sprintf("%s · %s", syncDetail, bootDetail)
		}
		if verifyOK {
			syncDetail = fmt.Sprintf("%s · %s", syncDetail, formatCoreSyncPct(verifyPct))
		}
	case catchingUp:
		syncDetail = fmt.Sprintf("Catch-up · %d blocks behind · local %d", behind, localBlock)
		if tipBlock > 0 {
			syncDetail = fmt.Sprintf("%s · tip %d", syncDetail, tipBlock)
		}
		if verifyOK {
			syncDetail = fmt.Sprintf("%s · %s", syncDetail, formatCoreSyncPct(verifyPct))
		}
	default:
		syncDetail = "Waiting for AvalancheGo HTTP / C-Chain RPC"
	}

	setupSteps := []map[string]any{
		{"id": "registry", "title": "Instance registered", "done": instRegistered,
			"detail": "INSTANCE.json + /etc/rpcnode/instances.d"},
		{"id": "disk", "title": "Disk floor for archive C-Chain", "done": diskOK,
			"detail": diskDetail, "active": !diskOK && apiUp},
		{"id": "node", "title": "avalanchego running", "done": nodeActive,
			"detail": "process/systemd", "active": apiUp && !nodeActive},
		{"id": "rpc", "title": "C-Chain JSON-RPC", "done": rpcOK || (bootOK && nodePortOpen),
			"detail": "info.isBootstrapped + /ext/bc/C/rpc",
			"active": nodeActive && !(rpcOK || bootOK)},
		{"id": "sync", "title": "Bootstrap / catch-up", "done": healthy,
			"detail": syncDetail, "active": catchingUp,
			"pct": map[bool]any{true: verifyPct, false: nil}[catchingUp && verifyOK]},
		{"id": "api", "title": "API agent up", "done": apiUp,
			"detail": fmt.Sprintf(":%d /healthz", apiProbePort)},
	}

	logTail := strings.Split(journalUnitSnippet(cfg.NodeService, 80), "\n")

	return map[string]any{
		"ok": true, "ts": time.Now().UTC().Format(time.RFC3339),
		"network": network, "env": env,
		"ui_phase": uiPhase, "node_status": nodeStatus,
		"lifecycle":   lifecycle,
		"setup_steps": setupSteps,
		"disk_gate": map[string]any{
			"ok": diskOK, "free_gib": freeGiB, "need_gib": needGiB, "detail": diskDetail,
		},
		"sync": map[string]any{
			"ok": healthy, "syncing": catchingUp, "ibd": catchingUp,
			"bootstrapped": bootstrapped, "bootstrap_ok": bootOK,
			"height": localBlock, "block": localBlock, "tip": tipBlock,
			"behind": behind, "blocks_behind": behind,
			"verification_pct": verifyPct, "detail": syncDetail,
			"bootstrap_pct": bootPct, "bootstrap_detail": bootDetail,
			"network": network, "log_tail": logTail,
		},
		"rpc": map[string]any{
			"ok": rpcOK || bootOK, "block": localBlock,
			"behind": behind, "verification_pct": verifyPct,
			"client_version": clientVer, "bootstrapped": bootstrapped,
		},
		"services": map[string]any{
			"node": nodeSvcEffective, "api": apiSvc,
		},
		"checks": map[string]any{
			"node_process_up": procOK, "avalanchego_process": procOK,
			"node_port_open": nodePortOpen, "bootstrapped": bootstrapped,
		},
		"ports": map[string]any{
			"public": publicPort, "agent": agentPort, "node_http": cfg.UpstreamPort,
			// Prometheus scrape is NodeHTTP /ext/metrics; catalog Metrics is inventory-only.
			"metrics": avalancheMetricsInventoryPort(env),
			"p2p":     LookupNetworkProfile(network, env).DefaultP2PPort,
		},
		"start_error": startErr,
		"logs": map[string]any{
			"title":  "Logs",
			"source": "avalanche-sync",
			"lines":  logTail,
		},
		"version":        agentVersion(),
		"client_version": clientVer,
	}
}

func normalizeAvalancheEnvName(env string) string {
	e := normalizeEnvName(env)
	if e == "testnet" {
		return "fuji"
	}
	return e
}

func avalancheMetricsInventoryPort(env string) int {
	if v := envOr("TRON_METRICS_PORT", ""); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return n
		}
	}
	if normalizeAvalancheEnvName(env) == "fuji" {
		return 9691
	}

	return 9690
}

type avalancheEthProbe struct {
	Syncing      bool
	Block        int64
	CurrentBlock int64
	HighestBlock int64
}

func avalancheHTTPBase(cfg Config) string {
	host := cfg.UpstreamHost
	if host == "" {
		host = "127.0.0.1"
	}
	port := cfg.UpstreamPort
	if port <= 0 {
		port = LookupNetworkProfile(cfg.Network, normalizeAvalancheEnvName(cfg.Env)).DefaultNodeHTTP
	}
	if port <= 0 {
		port = 9650
	}

	return fmt.Sprintf("http://%s:%d", host, port)
}

func avalancheIsBootstrapped(cfg Config) (bool, bool) {
	url := avalancheHTTPBase(cfg) + "/ext/info"
	body := map[string]any{
		"jsonrpc": "2.0",
		"id":      1,
		"method":  "info.isBootstrapped",
		"params":  map[string]any{"chain": "C"},
	}
	raw, err := json.Marshal(body)
	if err != nil {
		return false, false
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(raw))
	if err != nil {
		return false, false
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return false, false
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return false, false
	}
	var envelope struct {
		Result struct {
			IsBootstrapped bool `json:"isBootstrapped"`
		} `json:"result"`
		Error any `json:"error"`
	}
	if err := json.Unmarshal(b, &envelope); err != nil {
		return false, false
	}
	if envelope.Error != nil && fmt.Sprint(envelope.Error) != "<nil>" && fmt.Sprint(envelope.Error) != "null" {
		return false, false
	}

	return envelope.Result.IsBootstrapped, true
}

func avalancheProbeCChain(cfg Config) (avalancheEthProbe, bool) {
	out := avalancheEthProbe{}
	url := avalancheHTTPBase(cfg) + "/ext/bc/C/rpc"

	syncRaw, err := avalancheJSONRPCURL(url, "eth_syncing", nil)
	if err != nil {
		return out, false
	}
	var syncing any
	if err := json.Unmarshal(syncRaw, &syncing); err != nil {
		return out, false
	}
	switch v := syncing.(type) {
	case bool:
		out.Syncing = v
	case map[string]any:
		out.Syncing = true
		if cur, ok := v["currentBlock"].(string); ok {
			if h, err := parseHexInt64(cur); err == nil {
				out.CurrentBlock = h
				out.Block = h
			}
		}
		if hi, ok := v["highestBlock"].(string); ok {
			if h, err := parseHexInt64(hi); err == nil {
				out.HighestBlock = h
			}
		}
	}

	blockRaw, err := avalancheJSONRPCURL(url, "eth_blockNumber", nil)
	if err == nil {
		var hex string
		if json.Unmarshal(blockRaw, &hex) == nil {
			if h, err := parseHexInt64(hex); err == nil && h > out.Block {
				out.Block = h
			}
		}
	}

	return out, true
}

func avalancheJSONRPCURL(url, method string, params []any) (json.RawMessage, error) {
	if params == nil {
		params = []any{}
	}
	body := map[string]any{
		"jsonrpc": "2.0",
		"id":      1,
		"method":  method,
		"params":  params,
	}
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	var envelope struct {
		Result json.RawMessage `json:"result"`
		Error  *struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(b, &envelope); err != nil {
		return nil, err
	}
	if envelope.Error != nil {
		return nil, fmt.Errorf("%s", envelope.Error.Message)
	}

	return envelope.Result, nil
}

func avalanchePublicTipBlock(cfg Config) int64 {
	tipURL := strings.TrimSpace(envOr("AVALANCHE_PUBLIC_TIP_RPC", ""))
	if tipURL == "" {
		if b, err := os.ReadFile(filepath.Join(cfg.EtcDir, "public_tip.url")); err == nil {
			tipURL = strings.TrimSpace(string(b))
		}
	}
	if tipURL == "" {
		if normalizeAvalancheEnvName(cfg.Env) == "fuji" {
			tipURL = "https://api.avax-test.network/ext/bc/C/rpc"
		} else {
			tipURL = "https://api.avax.network/ext/bc/C/rpc"
		}
	}
	raw, err := avalancheJSONRPCURL(tipURL, "eth_blockNumber", nil)
	if err != nil {
		return 0
	}
	var hex string
	if json.Unmarshal(raw, &hex) != nil {
		return 0
	}
	h, err := parseHexInt64(hex)
	if err != nil {
		return 0
	}

	return h
}

func avalancheClientVersion(cfg Config) string {
	bin := filepath.Join(cfg.OptDir, "bin", "avalanchego")
	if !fileExists(bin) {
		bin = "avalanchego"
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

func avalancheVerificationPct(cfg Config, healthy, bootstrapping bool, bootPct float64, bootPctOK, catchingUp bool, behind int64) (float64, bool) {
	if healthy {
		clearAvalancheCatchupMaxBehind(cfg)

		return 100, true
	}
	if bootstrapping {
		if bootPctOK {
			if bootPct > 99.9 {
				bootPct = 99.9
			}
			if bootPct < 0.1 {
				bootPct = 0.1
			}
			bootPct = float64(int(bootPct*10+0.5)) / 10

			return bootPct, true
		}

		return 0.1, true
	}
	if catchingUp {
		return avalancheCatchupLagClosedPct(cfg, behind)
	}

	return 0, false
}

func avalancheCatchupLagClosedPct(cfg Config, behind int64) (float64, bool) {
	if behind < 0 {
		return 0, false
	}
	if behind == 0 {
		return 99.9, true
	}
	maxBehind := loadAvalancheCatchupMaxBehind(cfg)
	if behind > maxBehind {
		maxBehind = behind
		saveAvalancheCatchupMaxBehind(cfg, maxBehind)
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

func avalancheCatchupStatePath(cfg Config) string {
	return filepath.Join(filepath.Dir(cfg.StateFile), "avalanche-catchup.json")
}

func loadAvalancheCatchupMaxBehind(cfg Config) int64 {
	doc := readJSONFile(avalancheCatchupStatePath(cfg))
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

func saveAvalancheCatchupMaxBehind(cfg Config, maxBehind int64) {
	if maxBehind <= 0 || strings.TrimSpace(cfg.StateFile) == "" {
		return
	}
	_ = ensureDir(filepath.Dir(cfg.StateFile))
	_ = writeJSONFile(avalancheCatchupStatePath(cfg), map[string]any{
		"max_behind": maxBehind,
		"updated_at": time.Now().UTC().Format(time.RFC3339),
	})
}

func clearAvalancheCatchupMaxBehind(cfg Config) {
	if strings.TrimSpace(cfg.StateFile) == "" {
		return
	}
	_ = os.Remove(avalancheCatchupStatePath(cfg))
}

var (
	reAvalancheFetchedOf = regexp.MustCompile(`(?i)fetched\s+(\d[\d,]*)\s+of\s+(\d[\d,]*)\s+blocks?`)
	reAvalancheExecuted  = regexp.MustCompile(`(?i)executed\s+(\d[\d,]*)\s+blocks?`)
	reAvalancheFetchedSlash = regexp.MustCompile(`(?i)(?:fetching|fetched)\s+(\d[\d,]*)\s*/\s*(\d[\d,]*)\s+blocks?`)
	// AvalancheGo ≥1.11 structured bootstrap (P/X/C):
	// fetching blocks {"numFetchedBlocks": 7450192, "numTotalBlocks": 25352074, "pctComplete": 29.39}
	reAvalanchePctComplete     = regexp.MustCompile(`(?i)"pctComplete"\s*:\s*([0-9]+(?:\.[0-9]+)?)`)
	reAvalancheNumFetchedTotal = regexp.MustCompile(`(?i)"numFetchedBlocks"\s*:\s*(\d+).*"numTotalBlocks"\s*:\s*(\d+)`)
	reAvalancheChainTag        = regexp.MustCompile(`(?i)<\s*([PXC])\s+Chain\s*>`)
)

// parseAvalancheBootstrapProgress — pct from snowman / AvalancheGo bootstrap log lines.
// Prefer structured pctComplete (current AvalancheGo); fall back to prose fetched X of Y.
func parseAvalancheBootstrapProgress(text string) (pct float64, ok bool, detail string) {
	lines := strings.Split(text, "\n")
	var bestPct float64
	var bestOK bool
	var bestDetail string
	for _, ln := range lines {
		chain := ""
		if m := reAvalancheChainTag.FindStringSubmatch(ln); len(m) == 2 {
			chain = strings.ToUpper(m[1]) + "-Chain"
		}
		if m := reAvalanchePctComplete.FindStringSubmatch(ln); len(m) == 2 {
			if p, err := strconv.ParseFloat(m[1], 64); err == nil && p >= 0 {
				if p > 99.9 {
					p = 99.9
				}
				if !bestOK || p >= bestPct {
					bestPct, bestOK = p, true
					bestDetail = fmt.Sprintf("bootstrap %.1f%%", p)
					if chain != "" {
						bestDetail = fmt.Sprintf("%s · %s", chain, bestDetail)
					}
					if fm := reAvalancheNumFetchedTotal.FindStringSubmatch(ln); len(fm) == 3 {
						x, y := parseCommaInt64(fm[1]), parseCommaInt64(fm[2])
						if y > 0 {
							bestDetail = fmt.Sprintf("%s · fetched %d of %d", bestDetail, x, y)
						}
					}
				}
			}
			continue
		}
		if m := reAvalancheNumFetchedTotal.FindStringSubmatch(ln); len(m) == 3 {
			x, y := parseCommaInt64(m[1]), parseCommaInt64(m[2])
			if y > 0 && x >= 0 {
				p := float64(x) / float64(y) * 100
				if p > 99.9 {
					p = 99.9
				}
				if !bestOK || p >= bestPct {
					bestPct, bestOK = p, true
					bestDetail = fmt.Sprintf("fetched %d of %d blocks", x, y)
					if chain != "" {
						bestDetail = fmt.Sprintf("%s · %s", chain, bestDetail)
					}
				}
			}
		}
		if m := reAvalancheFetchedOf.FindStringSubmatch(ln); len(m) == 3 {
			x, y := parseCommaInt64(m[1]), parseCommaInt64(m[2])
			if y > 0 && x >= 0 {
				p := float64(x) / float64(y) * 100
				if p > 99.9 {
					p = 99.9
				}
				if !bestOK || p >= bestPct {
					bestPct, bestOK = p, true
					bestDetail = fmt.Sprintf("fetched %d of %d blocks", x, y)
				}
			}
		}
		if m := reAvalancheFetchedSlash.FindStringSubmatch(ln); len(m) == 3 {
			x, y := parseCommaInt64(m[1]), parseCommaInt64(m[2])
			if y > 0 && x >= 0 {
				p := float64(x) / float64(y) * 100
				if p > 99.9 {
					p = 99.9
				}
				if !bestOK || p >= bestPct {
					bestPct, bestOK = p, true
					bestDetail = fmt.Sprintf("fetched %d/%d blocks", x, y)
				}
			}
		}
		if m := reAvalancheExecuted.FindStringSubmatch(ln); len(m) == 2 {
			n := parseCommaInt64(m[1])
			if n > 0 {
				// Executed-only: soft progress signal without denominator — keep prior or small floor.
				if !bestOK {
					bestPct, bestOK = 1.0, true
					bestDetail = fmt.Sprintf("executed %d blocks", n)
				} else if bestDetail == "" {
					bestDetail = fmt.Sprintf("executed %d blocks", n)
				}
			}
		}
	}

	return bestPct, bestOK, bestDetail
}

func parseCommaInt64(s string) int64 {
	s = strings.ReplaceAll(strings.TrimSpace(s), ",", "")
	n, _ := strconv.ParseInt(s, 10, 64)

	return n
}

func avalancheBootstrapPctFromLogs(cfg Config) (float64, bool, string) {
	// Prefer grep — peer-version spam can push bootstrap lines out of a short tail.
	snip := journalUnitGrepSnippet(cfg.NodeService, 80, "pctComplete|numFetchedBlocks|fetched .* of .* blocks|fetching [0-9]+/")
	if snip == "" {
		snip = journalUnitSnippet(cfg.NodeService, 400)
	}
	if snip == "" {
		return 0, false, ""
	}

	return parseAvalancheBootstrapProgress(snip)
}

func avalancheNodeRunningFor(cfg Config) (bool, string) {
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
			if cmd != "" && strings.Contains(cmd, "avalanchego") {
				return true, cmd
			}
		}
	}
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		data = LookupNetworkProfile(cfg.Network, normalizeAvalancheEnvName(cfg.Env)).DataPath
	}
	out, err := runCmd(2*time.Second, "bash", "-lc",
		fmt.Sprintf(`ps -eo pid=,args= | grep -E '[a]valanchego' | grep -F %q | head -1`, data))
	if err != nil || strings.TrimSpace(out) == "" {
		return false, ""
	}

	return true, strings.TrimSpace(out)
}

func avalancheStartFailureDetail(cfg Config, procOK bool) (string, bool) {
	if procOK {
		return "", false
	}
	state := systemctlActive(cfg.NodeService)
	if systemctlFailed(cfg.NodeService) || state == "failed" {
		snip := journalUnitSnippet(cfg.NodeService, 16)
		if snip != "" {
			return snip, true
		}

		return "avalanchego unit failed", true
	}

	return "", false
}

func avalancheDiskGateOK(cfg Config, prof NetworkProfile) (ok bool, freeGiB, needGiB float64, detail string) {
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
	detail = fmt.Sprintf("%.0f GiB free (floor %.0f GiB for avalanche archive; hint %.0f GiB)", freeGiB, floor, needGiB)

	return ok, freeGiB, needGiB, detail
}
