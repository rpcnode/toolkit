package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Stellar provision — native stellar-rpc + Captive Core (stellar-core subprocess).
// ❌ Never Docker. Install via SDF apt (stellar-core + stellar-rpc), systemd unit.
// Docs: https://developers.stellar.org/docs/data/apis/rpc/admin-guide/installing

func provisionStellarNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
	env := req.Env
	steps := []string{}
	cluster := lookupStellarNetwork(env)

	if prof.NodeHTTP > 0 && req.NodeHTTPPort <= 0 {
		req.NodeHTTPPort = prof.NodeHTTP
	}
	if req.P2PPort <= 0 && prof.P2P > 0 {
		req.P2PPort = prof.P2P
	}
	if req.P2PPort <= 0 {
		req.P2PPort = cluster.PeerPort
	}
	// Confirm ports wins: captive-core.cfg PEER_PORT must match planned/remapped P2P.
	if req.P2PPort > 0 {
		cluster.PeerPort = req.P2PPort
	}

	opt := prof.OptPath
	etc := prof.EtcPath
	data := prof.DataPath
	stateDir := fmt.Sprintf("/var/lib/rpcnode/stellar-%s", env)
	binDir := filepath.Join(opt, "bin")

	for _, d := range []string{
		opt, binDir, etc, data,
		filepath.Join(data, "captive-core"),
		stateDir,
		"/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system",
	} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return nil, fmt.Errorf("mkdir %s: %w", d, err)
		}
		steps = append(steps, "mkdir "+d)
	}

	rpcBin, coreBin, err := ensureStellarNativeBinariesForEnv(env)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "stellar-rpc="+rpcBin, "stellar-core="+coreBin)

	_ = ensureNodeopUser()

	coreCfg, err := ensureStellarCaptiveCoreCfg(etc, cluster)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+coreCfg)

	// Confirm ports: overlay UI-confirmed primaries onto live profile so captive
	// HTTP_QUERY (SolHTTP) / admin remap when foreign-busy — not stuck on 11628 default.
	live := resolveLivePortProfile("stellar", env)
	live.Public, live.Agent, live.NodeHTTP, live.P2P = req.PublicPort, req.AgentPort, req.NodeHTTPPort, req.P2PPort
	queryPort := live.SolHTTP
	if queryPort <= 0 {
		queryPort = cluster.CoreHTTPPort
	}
	adminPort := live.Metrics
	if adminPort <= 0 {
		adminPort = cluster.AdminPort
	}

	rpcToml, err := writeStellarRPCToml(etc, data, req, cluster, coreBin, queryPort, adminPort)
	if err != nil {
		return nil, err
	}
	steps = append(steps, fmt.Sprintf("wrote %s (captive HTTP_QUERY=:%d admin=:%d)", rpcToml, queryPort, adminPort))

	tipPath := filepath.Join(etc, "public_tip.url")
	if tip := strings.TrimSpace(cluster.PublicTipRPC); tip != "" {
		_ = os.WriteFile(tipPath, []byte(tip+"\n"), 0o644)
	}

	rpcBinDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))
	if token == "" {
		if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
			token = strings.TrimSpace(string(b))
		}
	}

	sysListen := systemAgentListenPort("stellar", env)
	clientVer := stellarPackageVersion()

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (stellar)
%sTRON_NETWORK=stellar
TRON_NODE_HTTP_HOST=127.0.0.1
TRON_NODE_HTTP_PORT=%d
TRON_P2P_PORT=%d
TRON_SYSTEM_AGENT_LISTEN=127.0.0.1:%d
TRON_SYSTEM_AGENT_URL=http://127.0.0.1:%d
TRON_STATE_DIR=%s
TRON_OPT=%s
TRON_ETC=%s
TRON_DATA=%s
TRON_AGENT_STATE=%s/agent-state.json
TRON_INSTANCE_FILE=%s/INSTANCE.json
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/stellar-%s.json
TRON_SERVICE=stellar-%s
TRON_SNAPSHOT_ENABLED=0
STELLAR_RPC_BIN=%s
STELLAR_CORE_BIN=%s
STELLAR_PUBLIC_TIP=%s
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		productEnvVars(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, req.P2PPort,
		sysListen, sysListen, stateDir,
		opt, etc, data, stateDir, stateDir, env, env,
		rpcBin, coreBin, cluster.PublicTipRPC,
		toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+envPath)

	apiBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-system-agent")

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-stellar-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-stellar-%s.service", env)
	nodeUnitName := fmt.Sprintf("stellar-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (stellar/%s) — Go RPC :%d + Agent API :%d → stellar-rpc :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=stellar
Environment=TRON_NODE_HTTP_HOST=127.0.0.1
Environment=TRON_NODE_HTTP_PORT=%d
Environment=TRON_SYSTEM_AGENT_URL=http://127.0.0.1:%d
Environment=TRON_STATE_DIR=%s
Environment=TOOLKIT_DIR=%s
ExecStart=%s
Restart=always
RestartSec=2
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
`, env, req.PublicPort, req.AgentPort, req.NodeHTTPPort, envPath,
		productSystemdAPIListenEnv(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, sysListen, stateDir, toolkitDir, apiBin)

	sysUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node system-agent (stellar/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=stellar
Environment=TRON_SYSTEM_AGENT_LISTEN=127.0.0.1:%d
Environment=TRON_NODE_HTTP_HOST=127.0.0.1
Environment=TRON_NODE_HTTP_PORT=%d
Environment=TRON_STATE_DIR=%s
Environment=TOOLKIT_DIR=%s
ExecStart=%s
Restart=always
RestartSec=2
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
`, env, envPath, productSystemdSysListenEnv(env, req.PublicPort, req.AgentPort), sysListen,
		req.NodeHTTPPort,
		stateDir, toolkitDir, sysBin)

	nodeUnit := renderStellarUnit(env, etc, data, rpcBin, rpcToml)

	if err := os.WriteFile(filepath.Join("/etc/systemd/system", apiUnitName), []byte(apiUnit), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", sysUnitName), []byte(sysUnit), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", nodeUnitName), []byte(nodeUnit), 0o644); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+apiUnitName, "wrote "+sysUnitName, "wrote "+nodeUnitName)

	_ = exec.Command("chown", "-R", "nodeop:nodeop", opt, etc, data).Run()

	agentURL := resolvePublicAgentURL(req.AgentPort)
	watch := cluster.WatchSlug
	if watch == "" {
		watch = prof.WatchSlug
	}
	inst := map[string]any{
		"id":                           "stellar-" + env,
		"network":                      "stellar",
		"env":                          env,
		"name":                         req.Name,
		"public_port":                  req.PublicPort,
		"agent_port":                   req.AgentPort,
		"node_http_port":               req.NodeHTTPPort,
		"node_rpc_port":                req.NodeHTTPPort,
		"p2p_port":                     req.P2PPort,
		"admin_port":                   adminPort,
		"captive_core_http_query_port": queryPort,
		"watch_slug":                   watch,
		"agent_url":                    agentURL,
		"data_dir":                     data,
		"etc_dir":                      etc,
		"opt_dir":                      opt,
		"rpc_bin":                      rpcBin,
		"core_bin":                     coreBin,
		"client_version":               clientVer,
		"units":                        []string{nodeUnitName, apiUnitName, sysUnitName},
		"created_at":                   time.Now().UTC().Format(time.RFC3339),
		"hostname":                     hostnameOrEmpty(),
	}
	if err := writeJSONFile(filepath.Join(stateDir, "INSTANCE.json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "stellar-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "stellar-"+env+".json"), inst); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote instance+registry")

	persistProvisionedPorts(req, agentURL)
	if migrateHostBootstrapSystemAgent() {
		steps = append(steps, "migrated host system-agent :29090")
	}

	if _, err := exec.LookPath("systemctl"); err == nil {
		_ = exec.Command("systemctl", "daemon-reload").Run()
		steps = append(steps, "daemon-reload")
	}

	return map[string]any{
		"ok":             true,
		"network":        "stellar",
		"env":            env,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"rpc_mode":       "go_proxy",
		"rpc_bin":        rpcBin,
		"core_bin":       coreBin,
		"client_version": clientVer,
		"agent_url":      agentURL,
		"etc_dir":        etc,
		"data_dir":       data,
		"units":          []string{nodeUnitName, apiUnitName, sysUnitName},
		"units_started":  false,
		"status":         "provisioned",
		"snapshot":       false,
		"lifecycle":      "ports→install→start→run",
		"message":        "stellar per-node agents written; native stellar-rpc unit ready",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "stellar-"+env+".json"),
	}, nil
}

func renderStellarUnit(env, etc, data, rpcBin, rpcToml string) string {
	return fmt.Sprintf(`[Unit]
Description=Stellar RPC + Captive Core (%s) — RpcNode (native)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
WorkingDirectory=%s
EnvironmentFile=-%s/toolkit.env
ExecStart=%s --config-path %s
Restart=on-failure
RestartSec=10
TimeoutStopSec=90
KillMode=control-group
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
`, env, data, etc, rpcBin, rpcToml)
}

func activateStellarUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-stellar-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-stellar-%s.service", env)
	_ = exec.Command("systemctl", "daemon-reload").Run()
	for _, u := range []string{sysUnit, apiUnit} {
		_ = exec.Command("systemctl", "enable", u).Run()
		if err := exec.Command("systemctl", "restart", u).Run(); err != nil {
			return fmt.Errorf("restart %s: %w", u, err)
		}
	}
	_ = exec.Command("systemctl", "start", "rpcnode-api-agent.service").Run()
	return nil
}

func writeStellarRPCToml(etc, data string, req nodeProvisionRequest, cluster stellarNetwork, coreBin string, queryPort, adminPort int) (string, error) {
	rpcPort := req.NodeHTTPPort
	if rpcPort <= 0 {
		rpcPort = 8000
	}
	if adminPort <= 0 {
		adminPort = cluster.AdminPort
	}
	if adminPort <= 0 {
		adminPort = 8100
	}
	if queryPort <= 0 {
		queryPort = cluster.CoreHTTPPort
	}
	if queryPort <= 0 {
		queryPort = 11626
	}
	if strings.TrimSpace(coreBin) == "" {
		coreBin = "/usr/bin/stellar-core"
	}

	// HTTP_PORT=0 disables captive admin HTTP. HTTP_QUERY_PORT is REQUIRED (>0) by
	// stellar-rpc ≥27 — default is 11628 for ALL envs if unset → bind fights on testnet.
	// Confirm ports plans SolHTTP per env and remaps when foreign-busy.
	body := fmt.Sprintf(`# managed by RpcNode — Stellar RPC (%s) native
ENDPOINT = "127.0.0.1:%d"
ADMIN_ENDPOINT = "127.0.0.1:%d"
NETWORK_PASSPHRASE = %q
HISTORY_ARCHIVE_URLS = [%q]
STELLAR_CORE_BINARY_PATH = %q
CAPTIVE_CORE_CONFIG_PATH = %q
CAPTIVE_CORE_STORAGE_PATH = %q
DB_PATH = %q
STELLAR_CAPTIVE_CORE_HTTP_PORT = 0
STELLAR_CAPTIVE_CORE_HTTP_QUERY_PORT = %d
# Full history (RpcNode): max uint32 → never prune local tx/events.
# Fresh stellar-rpc still starts ingest at tip; this keeps everything from first start.
HISTORY_RETENTION_WINDOW = %d
# stellar-rpc ≥27 caps fee-stats windows at 1000 ledgers (larger → O(n²) startup / hard error).
SOROBAN_FEE_STATS_RETENTION_WINDOW = 1000
CLASSIC_FEE_STATS_RETENTION_WINDOW = 1000
MAX_GET_HEALTH_EXECUTION_DURATION = "5s"
PREFLIGHT_WORKER_COUNT = 16
PREFLIGHT_WORKER_QUEUE_SIZE = 32
REQUEST_BACKLOG_GLOBAL_QUEUE_LIMIT = 10000
LOG_LEVEL = "info"
LOG_FORMAT = "text"
`,
		cluster.Env, rpcPort, adminPort, cluster.Passphrase, cluster.HistoryArchive,
		coreBin,
		filepath.Join(etc, "stellar-core.cfg"),
		filepath.Join(data, "captive-core"),
		filepath.Join(data, "stellar_rpc.sqlite"),
		queryPort,
		stellarHistoryRetentionWindow,
	)

	path := filepath.Join(etc, "stellar-rpc.toml")
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		return "", err
	}
	return path, nil
}

// ensureStellarFullHistoryToml patches HISTORY_RETENTION_WINDOW + captive-core
// HTTP=0 + HTTP_QUERY_PORT on an existing managed toml (re-provision / start / restart).
// queryPort<=0 → keep existing QUERY_PORT or fall back to env canonical.
// Returns true when the file was changed.
func ensureStellarFullHistoryToml(etc string, queryPort int) (bool, error) {
	path := filepath.Join(etc, "stellar-rpc.toml")
	b, err := os.ReadFile(path)
	if err != nil {
		return false, err
	}
	changed := false
	want := strconv.FormatUint(uint64(stellarHistoryRetentionWindow), 10)
	re := regexp.MustCompile(`(?m)^(\s*HISTORY_RETENTION_WINDOW\s*=\s*)(\d+)\s*$`)
	m := re.FindSubmatch(b)
	if m == nil {
		// Missing key — append (stellar-rpc defaults to 7d otherwise).
		b = []byte(strings.TrimRight(string(b), "\n") + "\nHISTORY_RETENTION_WINDOW = " + want + "\n")
		changed = true
	} else if string(m[2]) != want {
		b = re.ReplaceAll(b, []byte("${1}"+want))
		changed = true
	}
	// Force captive-core admin HTTP off (avoids bind-in-use during catchup prepare).
	reHTTP := regexp.MustCompile(`(?m)^(\s*STELLAR_CAPTIVE_CORE_HTTP_PORT\s*=\s*)(\d+)\s*$`)
	if hm := reHTTP.FindSubmatch(b); hm == nil {
		b = []byte(strings.TrimRight(string(b), "\n") + "\nSTELLAR_CAPTIVE_CORE_HTTP_PORT = 0\n")
		changed = true
	} else if string(hm[2]) != "0" {
		b = reHTTP.ReplaceAll(b, []byte("${1}0"))
		changed = true
	}
	// HTTP_QUERY is required (>0). Unset → stellar-rpc default 11628 for every env.
	if queryPort <= 0 {
		queryPort = stellarQueryPortFromTomlOrEnv(etc, string(b))
	}
	wantQ := strconv.Itoa(queryPort)
	reQ := regexp.MustCompile(`(?m)^(\s*STELLAR_CAPTIVE_CORE_HTTP_QUERY_PORT\s*=\s*)(\d+)\s*$`)
	if qm := reQ.FindSubmatch(b); qm == nil {
		b = []byte(strings.TrimRight(string(b), "\n") + "\nSTELLAR_CAPTIVE_CORE_HTTP_QUERY_PORT = " + wantQ + "\n")
		changed = true
	} else if queryPort > 0 && string(qm[2]) != wantQ {
		b = reQ.ReplaceAll(b, []byte("${1}"+wantQ))
		changed = true
	}
	if !changed {
		return false, nil
	}
	if err := os.WriteFile(path, b, 0o644); err != nil {
		return false, err
	}
	return true, nil
}

func stellarQueryPortFromTomlOrEnv(etc, tomlBody string) int {
	reQ := regexp.MustCompile(`(?m)^\s*STELLAR_CAPTIVE_CORE_HTTP_QUERY_PORT\s*=\s*(\d+)\s*$`)
	if m := reQ.FindStringSubmatch(tomlBody); len(m) == 2 {
		if n, err := strconv.Atoi(m[1]); err == nil && n > 0 {
			return n
		}
	}
	env := filepath.Base(etc)
	if n := lookupStellarNetwork(env).CoreHTTPPort; n > 0 {
		return n
	}
	return lookupPortProfile("stellar", env).SolHTTP
}

func ensureStellarCaptiveCoreCfg(etc string, cluster stellarNetwork) (string, error) {
	dest := filepath.Join(etc, "stellar-core.cfg")
	tmp := filepath.Join(os.TempDir(), fmt.Sprintf("stellar-core-%s.cfg", cluster.Env))
	if err := downloadNamedConf("stellar", cluster.Env, filepath.Base(cluster.CaptiveCoreURL), cluster.CaptiveCoreURL, tmp); err != nil {
		return "", fmt.Errorf("captive-core.cfg download: %w", err)
	}
	b, err := os.ReadFile(tmp)
	if err != nil {
		return "", err
	}
	cfg := applyStellarCaptiveCorePorts(string(b), cluster)
	if err := os.WriteFile(dest, []byte(cfg), 0o644); err != nil {
		return "", err
	}
	return dest, nil
}

// patchStellarCaptiveCorePorts — heal an existing cfg without re-download.
func patchStellarCaptiveCorePorts(etc string, cluster stellarNetwork) (string, error) {
	dest := filepath.Join(etc, "stellar-core.cfg")
	b, err := os.ReadFile(dest)
	if err != nil {
		return "", err
	}
	cfg := applyStellarCaptiveCorePorts(string(b), cluster)
	if err := os.WriteFile(dest, []byte(cfg), 0o644); err != nil {
		return "", err
	}
	return dest, nil
}

// applyStellarCaptiveCorePorts — strip any HTTP_PORT/PEER_PORT (incl. under
// [[VALIDATORS]] — append-at-EOF nested there → fatal unmarshal), then set
// root-level PEER_PORT from Confirm ports (cluster.PeerPort / remapped P2P).
// HTTP listen stays in stellar-rpc.toml (HTTP_PORT + HTTP_QUERY_PORT); do not
// put HTTP_PORT in cfg. Without PEER_PORT core defaults to 11625 for every env.
func applyStellarCaptiveCorePorts(cfg string, cluster stellarNetwork) string {
	cfg = stripCaptiveCoreKey(cfg, "HTTP_PORT")
	cfg = stripCaptiveCoreKey(cfg, "PEER_PORT")
	peer := cluster.PeerPort
	if peer <= 0 {
		return cfg
	}
	insert := fmt.Sprintf("PEER_PORT=%d\n", peer)
	if idx := strings.Index(cfg, "[["); idx >= 0 {
		return cfg[:idx] + insert + cfg[idx:]
	}
	return strings.TrimRight(cfg, "\n") + "\n" + insert
}

func stripCaptiveCoreKey(cfg, key string) string {
	re := regexp.MustCompile(`(?m)^\s*` + regexp.QuoteMeta(key) + `\s*=\s*.*\n?`)
	return re.ReplaceAllString(cfg, "")
}

// resetStellarCaptiveCoreRuntime — stop unit, kill orphan stellar-core (port steal /
// bind: Address already in use), drop generated conf so it rebuilds from healed cfg.
func resetStellarCaptiveCoreRuntime(env, etc, data string, cluster stellarNetwork) {
	unit := fmt.Sprintf("stellar-%s.service", normalizeEnv(env))
	if _, err := exec.LookPath("systemctl"); err == nil {
		_ = exec.Command("systemctl", "stop", unit).Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
	}
	killNodeProcesses("stellar", env)
	// Free captive-core HTTP_QUERY / peer ports held by zombies outside the unit cgroup.
	ports := []int{cluster.CoreHTTPPort, cluster.PeerPort}
	if q := stellarQueryPortFromTomlOrEnv(etc, ""); q > 0 {
		ports = append(ports, q)
	}
	if b, err := os.ReadFile(filepath.Join(etc, "stellar-rpc.toml")); err == nil {
		if q := stellarQueryPortFromTomlOrEnv(etc, string(b)); q > 0 {
			ports = append(ports, q)
		}
	}
	seen := map[int]bool{}
	for _, port := range ports {
		if port <= 0 || seen[port] {
			continue
		}
		seen[port] = true
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`fuser -k %d/tcp >/dev/null 2>&1 || true`, port,
		)).Run()
	}
	// Wipe half-init captive-core storage (not stellar_rpc.sqlite) so catchup
	// does not reuse a broken core DB / generated conf after crash-loop.
	// Must chown to nodeop — unit runs as User=nodeop (root-owned dir → permission denied).
	storage := filepath.Join(data, "captive-core")
	_ = os.RemoveAll(storage)
	_ = os.MkdirAll(storage, 0o755)
	_ = exec.Command("chown", "-R", "nodeop:nodeop", data, storage).Run()
	time.Sleep(500 * time.Millisecond)
}

// ensureStellarNativeBinariesForEnv — futurenet needs protocol-28 / vnext binaries
// under /opt/stellar/futurenet/bin (do not upgrade host /usr/bin used by testnet).
func ensureStellarNativeBinariesForEnv(env string) (rpcBin, coreBin string, err error) {
	if stellarNeedsVNext(env) {
		return ensureStellarVNextBinaries(env)
	}
	return ensureStellarNativeBinaries()
}

// ensureStellarVNextBinaries — image-extract (preferred) or apt testing → copy into
// /opt/stellar/<env>/bin. Leaves /usr/bin stellar-* alone when possible.
func ensureStellarVNextBinaries(env string) (rpcBin, coreBin string, err error) {
	env = normalizeEnv(env)
	destDir := filepath.Join("/opt/stellar", env, "bin")
	if err := os.MkdirAll(destDir, 0o755); err != nil {
		return "", "", err
	}
	rpcDest := filepath.Join(destDir, "stellar-rpc")
	coreDest := filepath.Join(destDir, "stellar-core")
	if fileExists(rpcDest) && fileExists(coreDest) && stellarCoreMaxProtocol(coreDest) >= stellarFuturenetMinProtocol {
		return rpcDest, coreDest, nil
	}

	image := resolveStellarRPCImageForEnv(env)
	_ = os.Remove(rpcDest)
	_ = os.Remove(coreDest)
	var notes []string
	if errExt := installStellarFromImageExtractTo(destDir, image); errExt != nil {
		notes = append(notes, "image-extract: "+errExt.Error())
	} else if fileExists(rpcDest) && fileExists(coreDest) && stellarCoreMaxProtocol(coreDest) >= stellarFuturenetMinProtocol {
		_ = exec.Command("chown", "-R", "nodeop:nodeop", filepath.Dir(destDir)).Run()
		return rpcDest, coreDest, nil
	}

	if errApt := installStellarVNextFromAptTesting(destDir); errApt != nil {
		notes = append(notes, "apt-testing: "+errApt.Error())
	} else if fileExists(rpcDest) && fileExists(coreDest) {
		_ = exec.Command("chown", "-R", "nodeop:nodeop", filepath.Dir(destDir)).Run()
		return rpcDest, coreDest, nil
	}

	return "", "", fmt.Errorf("stellar %s vnext install failed (need protocol≥%d); tried: %s",
		env, stellarFuturenetMinProtocol, strings.Join(notes, " | "))
}

// stellarCoreMaxProtocol — first "ledger protocol version: N" from `stellar-core version`
// (binary max). Returns 0 if unknown.
func stellarCoreMaxProtocol(bin string) int {
	if strings.TrimSpace(bin) == "" || !fileExists(bin) {
		return 0
	}
	out, err := exec.Command(bin, "version").CombinedOutput()
	if err != nil && len(out) == 0 {
		return 0
	}
	re := regexp.MustCompile(`(?m)^ledger protocol version:\s*(\d+)\s*$`)
	if m := re.FindSubmatch(out); len(m) == 2 {
		n, _ := strconv.Atoi(string(m[1]))
		return n
	}
	// vnext builds sometimes only advertise via version string.
	low := strings.ToLower(string(out))
	if strings.Contains(low, "vnext") || strings.Contains(low, "28.0") {
		return stellarFuturenetMinProtocol
	}
	return 0
}

func installStellarVNextFromAptTesting(destDir string) error {
	if _, err := exec.LookPath("apt-get"); err != nil {
		return err
	}
	host := ubuntuCodename()
	if host == "" {
		host = "noble"
	}
	if err := ensureStellarAptKey(); err != nil {
		return err
	}
	// Temporarily point SDF list at testing; restore stable after copy.
	prev, _ := os.ReadFile("/etc/apt/sources.list.d/stellar.list")
	if err := writeStellarAptSourceChannel(host, "testing"); err != nil {
		return err
	}
	defer func() {
		if len(prev) > 0 {
			_ = os.WriteFile("/etc/apt/sources.list.d/stellar.list", prev, 0o644)
		} else {
			_ = writeStellarAptSource(host)
		}
		_ = exec.Command("apt-get", "-y", "update").Run()
	}()
	_ = ensureStellarLibCXXDeps(host, host)
	if out, err := exec.Command("apt-get", "-y", "update").CombinedOutput(); err != nil {
		return fmt.Errorf("apt update testing: %v (%s)", err, strings.TrimSpace(string(out)))
	}
	// Install newest testing packages into /usr then copy — shared /usr briefly
	// holds vnext; we restore stable list so future apt upgrades don't surprise.
	pkgs := []string{"stellar-core", "stellar-rpc"}
	if out, err := exec.Command("apt-get", "-y", "install", "-o", "Dpkg::Options::=--force-confdef",
		"-o", "Dpkg::Options::=--force-confold", pkgs[0], pkgs[1]).CombinedOutput(); err != nil {
		return fmt.Errorf("apt install testing: %v (%s)", err, strings.TrimSpace(string(out)))
	}
	maskStellarPackageUnits()
	if err := os.MkdirAll(destDir, 0o755); err != nil {
		return err
	}
	for _, name := range []string{"stellar-rpc", "stellar-core"} {
		src := findStellarBinary(name)
		if src == "" {
			return fmt.Errorf("%s missing after apt testing install", name)
		}
		raw, err := os.ReadFile(src)
		if err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(destDir, name), raw, 0o755); err != nil {
			return err
		}
	}
	return nil
}

// ensureStellarNativeBinaries — install native binaries for any host OS.
// Order (runtime is always systemd, never docker run):
//  1. already on disk
//  2. SDF apt for host VERSION_CODENAME (noble/jammy/focal) + libc++ deps
//  3. extract binaries from official image into /opt/stellar/bin (install aid only)
//  4. build stellar-rpc from source (Go); stellar-core still from 2/3
func ensureStellarNativeBinaries() (rpcBin, coreBin string, err error) {
	rpcBin = findStellarBinary("stellar-rpc")
	coreBin = findStellarBinary("stellar-core")
	if rpcBin != "" && coreBin != "" {
		return rpcBin, coreBin, nil
	}

	optBin := "/opt/stellar/bin"
	_ = os.MkdirAll(optBin, 0o755)
	var notes []string

	if errApt := installStellarFromApt(); errApt != nil {
		notes = append(notes, "apt: "+errApt.Error())
	} else {
		rpcBin = findStellarBinary("stellar-rpc")
		coreBin = findStellarBinary("stellar-core")
		if rpcBin != "" && coreBin != "" {
			return rpcBin, coreBin, nil
		}
		notes = append(notes, "apt: packages installed but binaries not found on PATH")
	}

	// Portable fallback: copy binaries out of SDF image (same pattern as arb/op-geth).
	// ❌ We do not run the node in Docker — only extract → /opt/stellar/bin.
	if errExt := installStellarFromImageExtractTo(optBin, resolveStellarRPCImageForEnv("mainnet")); errExt != nil {
		notes = append(notes, "image-extract: "+errExt.Error())
	} else {
		rpcBin = findStellarBinary("stellar-rpc")
		coreBin = findStellarBinary("stellar-core")
		if rpcBin != "" && coreBin != "" {
			return rpcBin, coreBin, nil
		}
	}

	// Build RPC ourselves when apt/image failed for rpc; core must already exist.
	rpcDest := filepath.Join(optBin, "stellar-rpc")
	if !fileExists(rpcDest) {
		if errBuild := buildStellarRPCFromSource(rpcDest); errBuild != nil {
			notes = append(notes, "build-rpc: "+errBuild.Error())
		}
	}
	rpcBin = findStellarBinary("stellar-rpc")
	coreBin = findStellarBinary("stellar-core")
	if rpcBin != "" && coreBin != "" {
		return rpcBin, coreBin, nil
	}

	return "", "", fmt.Errorf("stellar native install failed (rpc=%q core=%q); tried: %s",
		rpcBin, coreBin, strings.Join(notes, " | "))
}

func findStellarBinary(name string) string {
	for _, c := range []string{
		filepath.Join("/opt/stellar/bin", name),
		filepath.Join("/usr/bin", name),
		filepath.Join("/usr/local/bin", name),
	} {
		if fileExists(c) {
			return c
		}
	}
	if p, err := exec.LookPath(name); err == nil && p != "" {
		return p
	}
	return ""
}

// installStellarFromImageExtractTo — pull image and copy binaries to destDir (install aid only).
func installStellarFromImageExtractTo(destDir, image string) error {
	image = strings.TrimSpace(image)
	if image == "" {
		image = resolveStellarRPCImageForEnv("mainnet")
	}
	host := ubuntuCodename()
	suite := "noble"
	if host != "" {
		suites := stellarAptSuitesForHost(host)
		if len(suites) > 0 {
			suite = suites[0]
		}
	}
	// Dynlinked binaries need libc++ on the host even when extracted.
	_ = ensureStellarLibCXXDeps(suite, host)
	_ = os.MkdirAll(destDir, 0o755)

	rpcDest := filepath.Join(destDir, "stellar-rpc")
	coreDest := filepath.Join(destDir, "stellar-core")
	var errs []string

	rpcOK := fileExists(rpcDest)
	if !rpcOK {
		for _, p := range []string{"/usr/bin/stellar-rpc", "/usr/local/bin/stellar-rpc", "/bin/stellar-rpc"} {
			if _, err := ensureBinaryFromDocker(image, p, rpcDest); err == nil {
				rpcOK = true
				break
			} else {
				errs = append(errs, err.Error())
			}
		}
	}
	coreOK := fileExists(coreDest)
	if !coreOK {
		for _, p := range []string{"/usr/bin/stellar-core", "/usr/local/bin/stellar-core", "/bin/stellar-core"} {
			if _, err := ensureBinaryFromDocker(image, p, coreDest); err == nil {
				coreOK = true
				break
			} else {
				errs = append(errs, err.Error())
			}
		}
	}
	if rpcOK && coreOK {
		return nil
	}
	return fmt.Errorf("extract from %s incomplete rpc=%v core=%v (%s)", image, rpcOK, coreOK, strings.Join(errs, "; "))
}

// buildStellarRPCFromSource — clone tagged release and `go build ./cmd/stellar-rpc`
// (make as fallback). Requires git + go. Does not build stellar-core (too heavy; use apt/extract).
func buildStellarRPCFromSource(dest string) error {
	if _, err := exec.LookPath("git"); err != nil {
		return fmt.Errorf("git required to build stellar-rpc: %w", err)
	}
	if _, err := exec.LookPath("go"); err != nil {
		// Try installing golang from apt (best-effort).
		_ = exec.Command("apt-get", "-y", "install", "golang-go", "make", "gcc", "g++").Run()
		if _, err2 := exec.LookPath("go"); err2 != nil {
			return fmt.Errorf("go required to build stellar-rpc: %w", err)
		}
	}
	_ = exec.Command("apt-get", "-y", "install", "make", "gcc", "g++").Run()

	tag := resolveStellarRPCVersionTag()
	work := filepath.Join(os.TempDir(), "stellar-rpc-src-"+strings.TrimPrefix(tag, "v"))
	_ = os.RemoveAll(work)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Minute)
	defer cancel()

	clone := exec.CommandContext(ctx, "git", "clone", "--depth", "1", "--branch", tag,
		"https://github.com/stellar/stellar-rpc.git", work)
	if out, err := clone.CombinedOutput(); err != nil {
		// tag might be without v prefix or branch name = version
		clone2 := exec.CommandContext(ctx, "git", "clone", "--depth", "1", "--branch", "v"+strings.TrimPrefix(tag, "v"),
			"https://github.com/stellar/stellar-rpc.git", work)
		if out2, err2 := clone2.CombinedOutput(); err2 != nil {
			return fmt.Errorf("git clone stellar-rpc %s: %v (%s); %v (%s)",
				tag, err, strings.TrimSpace(string(out)), err2, strings.TrimSpace(string(out2)))
		}
	}

	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	// Prefer direct go build into dest (portable). make is best-effort fallback.
	build := exec.CommandContext(ctx, "go", "build", "-trimpath", "-ldflags", "-s -w", "-o", dest, "./cmd/stellar-rpc")
	build.Dir = work
	build.Env = append(os.Environ(), "CGO_ENABLED=0", "GOTOOLCHAIN=auto")
	if out, err := build.CombinedOutput(); err != nil {
		makeCmd := exec.CommandContext(ctx, "make", "build-stellar-rpc")
		makeCmd.Dir = work
		makeOut, makeErr := makeCmd.CombinedOutput()
		if makeErr != nil {
			return fmt.Errorf("go build stellar-rpc: %v (%s); make: %v (%s)",
				err, strings.TrimSpace(string(out)), makeErr, strings.TrimSpace(string(makeOut)))
		}
		for _, c := range []string{
			filepath.Join(work, "stellar-rpc"),
			filepath.Join(work, "bin", "stellar-rpc"),
		} {
			if fileExists(c) {
				raw, rerr := os.ReadFile(c)
				if rerr != nil {
					return rerr
				}
				return os.WriteFile(dest, raw, 0o755)
			}
		}
		return fmt.Errorf("make build-stellar-rpc ok but binary missing under %s", work)
	}
	if !fileExists(dest) {
		return fmt.Errorf("go build reported ok but %s missing", dest)
	}
	_ = os.Chmod(dest, 0o755)
	return nil
}

func resolveStellarRPCVersionTag() string {
	v := strings.TrimSpace(readStellarClientVERSION())
	if v == "" {
		v = "27.1.1"
	}
	if strings.Contains(v, "/") {
		// docker image ref — take tag after :
		if i := strings.LastIndex(v, ":"); i >= 0 {
			v = v[i+1:]
		}
	}
	v = strings.TrimPrefix(v, "v")
	return "v" + v
}

func readStellarClientVERSION() string {
	return readStellarClientVERSIONForEnv("mainnet")
}

func readStellarClientVERSIONForEnv(env string) string {
	env = normalizeEnv(env)
	if env == "" {
		env = "mainnet"
	}
	for _, p := range []string{
		"/opt/rpcnode/install/clients/stellar/" + env + "/VERSION",
		"/opt/rpcnode/toolkit/install/clients/stellar/" + env + "/VERSION",
	} {
		if b, err := os.ReadFile(p); err == nil {
			if v := strings.TrimSpace(string(b)); v != "" {
				return v
			}
		}
	}
	if stellarNeedsVNext(env) {
		if v := strings.TrimSpace(envOr("STELLAR_FUTURENET_RPC_VERSION", "")); v != "" {
			return v
		}
		return strings.TrimPrefix(stellarFuturenetRPCImage, "stellar/stellar-rpc:")
	}
	return strings.TrimSpace(envOr("STELLAR_RPC_VERSION", ""))
}

func resolveStellarRPCImage() string {
	return resolveStellarRPCImageForEnv("mainnet")
}

func resolveStellarRPCImageForEnv(env string) string {
	if stellarNeedsVNext(env) {
		v := readStellarClientVERSIONForEnv(env)
		if v == "" {
			return stellarFuturenetRPCImage
		}
		if strings.Contains(v, "/") {
			return v
		}
		return "stellar/stellar-rpc:" + strings.TrimPrefix(v, "v")
	}
	v := readStellarClientVERSIONForEnv(env)
	if v == "" {
		v = stellarStableRPCVersion
	}
	if strings.Contains(v, "/") {
		return v
	}
	return "stellar/stellar-rpc:" + strings.TrimPrefix(v, "v")
}

func installStellarFromApt() error {
	if _, err := exec.LookPath("apt-get"); err != nil {
		return fmt.Errorf("apt-get required to install stellar-rpc (native; no Docker): %w", err)
	}

	_ = exec.Command("apt-get", "-y", "install", "apt-transport-https", "ca-certificates", "curl", "gnupg", "wget").Run()
	_ = os.MkdirAll("/etc/apt/keyrings", 0o755)

	if err := ensureStellarAptKey(); err != nil {
		return err
	}

	host := ubuntuCodename()
	var lastErr error
	for _, suite := range stellarAptSuitesForHost(host) {
		if !stellarAptSuiteAvailable(suite) {
			lastErr = fmt.Errorf("SDF apt suite %q not available", suite)
			continue
		}
		if err := writeStellarAptSource(suite); err != nil {
			lastErr = err
			continue
		}
		// libc++ deps for this suite (focal→12, jammy/noble→20 via apt.llvm.org).
		_ = ensureStellarLibCXXDeps(suite, host)
		if out, err := exec.Command("apt-get", "-y", "update").CombinedOutput(); err != nil {
			lastErr = fmt.Errorf("apt-get update (stellar/%s): %v (%s)", suite, err, strings.TrimSpace(string(out)))
			continue
		}
		// Dry-run first — catch unmet deps before half-install.
		if out, err := exec.Command("apt-get", "-y", "-s", "install", "stellar-core", "stellar-rpc").CombinedOutput(); err != nil {
			msg := strings.TrimSpace(string(out))
			lastErr = fmt.Errorf("apt simulate stellar/%s: %v (%s)", suite, err, msg)
			// Retry once after forcing libc++ for this suite.
			_ = ensureStellarLibCXXDeps(suite, host)
			_ = exec.Command("apt-get", "-y", "update").Run()
			if out2, err2 := exec.Command("apt-get", "-y", "-s", "install", "stellar-core", "stellar-rpc").CombinedOutput(); err2 != nil {
				lastErr = fmt.Errorf("apt simulate stellar/%s (retry): %v (%s)", suite, err2, strings.TrimSpace(string(out2)))
				continue
			}
		}
		if out, err := exec.Command("apt-get", "-y", "install", "stellar-core", "stellar-rpc").CombinedOutput(); err != nil {
			lastErr = fmt.Errorf("apt-get install stellar-core stellar-rpc (%s): %v (%s)",
				suite, err, strings.TrimSpace(string(out)))
			continue
		}
		maskStellarPackageUnits()
		return nil
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("no SDF apt suite worked for host %q", host)
	}
	return lastErr
}

func ensureStellarAptKey() error {
	keyPath := "/etc/apt/keyrings/stellar.gpg"
	if fileExists(keyPath) {
		return nil
	}
	tmp := "/tmp/stellar-SDF.asc"
	if err := downloadFile("https://apt.stellar.org/SDF.asc", tmp); err != nil {
		return fmt.Errorf("stellar gpg key: %w", err)
	}
	_ = os.Remove(keyPath)
	if out, err := exec.Command("bash", "-lc",
		fmt.Sprintf(`gpg --dearmor < %q > %q && chmod 644 %q`, tmp, keyPath, keyPath),
	).CombinedOutput(); err != nil {
		return fmt.Errorf("gpg --dearmor stellar: %v (%s)", err, strings.TrimSpace(string(out)))
	}
	return nil
}

func writeStellarAptSource(suite string) error {
	return writeStellarAptSourceChannel(suite, "stable")
}

func writeStellarAptSourceChannel(suite, channel string) error {
	suite = strings.TrimSpace(suite)
	if suite == "" {
		suite = "noble"
	}
	channel = strings.TrimSpace(channel)
	if channel == "" {
		channel = "stable"
	}
	listLine := fmt.Sprintf(
		"deb [signed-by=/etc/apt/keyrings/stellar.gpg] https://apt.stellar.org %s %s\n",
		suite, channel,
	)
	return os.WriteFile("/etc/apt/sources.list.d/stellar.list", []byte(listLine), 0o644)
}

func maskStellarPackageUnits() {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return
	}
	for _, u := range []string{"stellar-core", "stellar-rpc", "stellar-core.service", "stellar-rpc.service"} {
		_ = exec.Command("systemctl", "disable", "--now", u).Run()
		_ = exec.Command("systemctl", "mask", u).Run()
	}
}

// ubuntuCodename — VERSION_CODENAME from /etc/os-release (noble/jammy/focal/…).
func ubuntuCodename() string {
	b, err := os.ReadFile("/etc/os-release")
	if err != nil {
		return ""
	}
	for _, ln := range strings.Split(string(b), "\n") {
		ln = strings.TrimSpace(ln)
		if strings.HasPrefix(ln, "VERSION_CODENAME=") {
			return strings.Trim(strings.TrimPrefix(ln, "VERSION_CODENAME="), `"'`)
		}
	}
	return ""
}

// stellarAptSuitesForHost — prefer matching SDF distro; never pin only focal on newer hosts
// (focal stellar-core needs libc++1-12 which is not installable on jammy/noble).
func stellarAptSuitesForHost(codename string) []string {
	switch strings.ToLower(strings.TrimSpace(codename)) {
	case "focal":
		return []string{"focal", "jammy"}
	case "jammy":
		return []string{"jammy", "noble", "focal"}
	case "noble", "oracular", "plucky", "questing", "resolute":
		return []string{"noble", "jammy", "focal"}
	default:
		// Unknown / rolling → newest SDF suites first.
		return []string{"noble", "jammy", "focal"}
	}
}

func stellarAptSuiteAvailable(suite string) bool {
	suite = strings.TrimSpace(suite)
	if suite == "" {
		return false
	}
	url := "https://apt.stellar.org/dists/" + suite + "/stable/binary-amd64/Packages.gz"
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, url, nil)
	if err != nil {
		return false
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		// Some mirrors reject HEAD — try GET range via GET.
		req2, _ := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
		if req2 == nil {
			return false
		}
		resp2, err2 := http.DefaultClient.Do(req2)
		if err2 != nil {
			return false
		}
		defer resp2.Body.Close()
		return resp2.StatusCode >= 200 && resp2.StatusCode < 300
	}
	defer resp.Body.Close()
	return resp.StatusCode >= 200 && resp.StatusCode < 300
}

// ensureStellarLibCXXDeps — install libc++ required by SDF packages for suite.
func ensureStellarLibCXXDeps(suite, hostCodename string) error {
	suite = strings.ToLower(strings.TrimSpace(suite))
	host := strings.ToLower(strings.TrimSpace(hostCodename))
	if host == "" {
		host = suite
	}

	pkgs := []string{"libsqlite3-0", "libpq5"}
	switch suite {
	case "noble":
		_ = ensureLLVMAptRepo(host, "20")
		pkgs = append(pkgs, "libc++1-20", "libc++abi1-20", "libunwind-20")
	case "jammy":
		_ = ensureLLVMAptRepo(host, "20")
		pkgs = append(pkgs, "libc++1", "libc++abi1", "libunwind")
	case "focal":
		// Prefer distro packages; fall back to llvm-12.
		pkgs = append(pkgs, "libc++1-12", "libc++abi1-12")
	default:
		_ = ensureLLVMAptRepo(host, "20")
		pkgs = append(pkgs, "libc++1-20", "libc++abi1-20")
	}

	_ = exec.Command("apt-get", "-y", "update").Run()
	args := append([]string{"-y", "install"}, pkgs...)
	out, err := exec.Command("apt-get", args...).CombinedOutput()
	if err == nil {
		return nil
	}
	// focal host may need llvm-12 toolchain if libc++1-12 missing from archive.
	if suite == "focal" || strings.Contains(string(out), "libc++1-12") {
		_ = ensureLLVMAptRepo(host, "12")
		_ = exec.Command("apt-get", "-y", "update").Run()
		out2, err2 := exec.Command("apt-get", "-y", "install", "libc++1-12", "libc++abi1-12").CombinedOutput()
		if err2 != nil {
			return fmt.Errorf("libc++ deps (%s): %v (%s); llvm12: %v (%s)",
				suite, err, strings.TrimSpace(string(out)), err2, strings.TrimSpace(string(out2)))
		}
		return nil
	}
	return fmt.Errorf("libc++ deps (%s): %v (%s)", suite, err, strings.TrimSpace(string(out)))
}

func ensureLLVMAptRepo(ubuntuCodename, llvmMajor string) error {
	ubuntuCodename = strings.ToLower(strings.TrimSpace(ubuntuCodename))
	llvmMajor = strings.TrimSpace(llvmMajor)
	if ubuntuCodename == "" || llvmMajor == "" {
		return fmt.Errorf("llvm repo: empty codename/major")
	}
	// Map unknown newer hosts to noble for llvm URLs.
	switch ubuntuCodename {
	case "focal", "jammy", "noble":
	default:
		ubuntuCodename = "noble"
	}
	_ = os.MkdirAll("/etc/apt/keyrings", 0o755)
	keyPath := "/etc/apt/keyrings/apt.llvm.org.asc"
	if !fileExists(keyPath) {
		if err := downloadFile("https://apt.llvm.org/llvm-snapshot.gpg.key", keyPath); err != nil {
			return fmt.Errorf("llvm gpg: %w", err)
		}
		_ = os.Chmod(keyPath, 0o644)
	}
	list := fmt.Sprintf(
		"deb [signed-by=/etc/apt/keyrings/apt.llvm.org.asc] http://apt.llvm.org/%s/ llvm-toolchain-%s-%s main\n",
		ubuntuCodename, ubuntuCodename, llvmMajor,
	)
	path := fmt.Sprintf("/etc/apt/sources.list.d/llvm-%s.list", llvmMajor)
	return os.WriteFile(path, []byte(list), 0o644)
}

func stellarPackageVersion() string {
	out, err := exec.Command("dpkg-query", "-W", "-f", "${Version}", "stellar-rpc").Output()
	if err == nil {
		if v := strings.TrimSpace(string(out)); v != "" {
			return v
		}
	}
	if b := findStellarBinary("stellar-rpc"); b != "" {
		if o, e := exec.Command(b, "version").CombinedOutput(); e == nil {
			line := strings.TrimSpace(string(o))
			if line != "" {
				return strings.Split(line, "\n")[0]
			}
		}
	}
	return strings.TrimSpace(envOr("STELLAR_RPC_VERSION", "apt"))
}
