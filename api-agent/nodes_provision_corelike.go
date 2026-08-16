package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// provisionCoreLikeNodeEnv — Litecoin / Dash / Bitcoin Cash (dogecoin-shaped Core forks).

func provisionCoreLikeNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
	client, ok := lookupCoreLike(req.Network)
	if !ok {
		return nil, fmt.Errorf("unsupported core-like network %q", req.Network)
	}
	network := client.Network
	env := req.Env
	steps := []string{}

	if prof.NodeHTTP > 0 {
		req.NodeHTTPPort = prof.NodeHTTP
	}
	if prof.P2P > 0 {
		req.P2PPort = prof.P2P
	}

	opt := prof.OptPath
	etc := prof.EtcPath
	data := resolveNetworkRoleDir(req, network, env, "blockchain", prof.DataPath)
	indexDir := resolveNetworkRoleDir(req, network, env, "index", filepath.Join(prof.DataPath, "index"))
	stateDir := fmt.Sprintf("/var/lib/rpcnode/%s-%s", network, env)

	for _, d := range []string{opt, etc, data, indexDir, stateDir, "/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system"} {
		if d == "" {
			continue
		}
		if err := os.MkdirAll(d, 0o755); err != nil {
			return nil, fmt.Errorf("mkdir %s: %w", d, err)
		}
		steps = append(steps, "mkdir "+d)
	}
	// Fresh provision must re-walk NODE SETUP (ports→…→run). Stale progress from a
	// prior instant-healthy collapse would skip straight to Healthy again.
	if err := os.Remove(filepath.Join(stateDir, "lifecycle-progress.json")); err == nil {
		steps = append(steps, "reset lifecycle-progress")
	}

	bin, err := ensureCoreLikeInstalled(client, opt)
	if err != nil {
		return nil, err
	}
	steps = append(steps, client.Daemon+"="+bin)
	_ = ensureNodeopUser()

	rpcUser, rpcPass, confPath, err := ensureCoreLikeConf(client, prof, req)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+confPath)

	binDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))
	if token == "" {
		if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
			token = strings.TrimSpace(string(b))
		}
	}

	sysListen := systemAgentListenPort(network, env)

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (%s)
%sTRON_NETWORK=%s
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
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/%s-%s.json
TRON_SERVICE=%s-%s
TRON_SNAPSHOT_ENABLED=0
BITCOIN_RPC_USER=%s
BITCOIN_RPC_PASSWORD=%s
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339), network,
		productEnvVars(env, req.PublicPort, req.AgentPort),
		network,
		req.NodeHTTPPort, req.P2PPort,
		sysListen, sysListen, stateDir,
		opt, etc, data, stateDir, stateDir, network, env, network, env,
		rpcUser, rpcPass,
		toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+envPath)

	apiBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-system-agent")

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-%s-%s.service", network, env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-%s-%s.service", network, env)
	nodeUnitName := fmt.Sprintf("%s-%s.service", network, env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (%s/%s) — Go RPC :%d + Agent API :%d → %s :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=%s
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
`, network, env, req.PublicPort, req.AgentPort, client.Daemon, req.NodeHTTPPort, envPath,
		productSystemdAPIListenEnv(env, req.PublicPort, req.AgentPort),
		network,
		req.NodeHTTPPort, sysListen, stateDir, toolkitDir, apiBin)

	sysUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node system-agent (%s/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=%s
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
`, network, env, envPath, productSystemdSysListenEnv(env, req.PublicPort, req.AgentPort), network, sysListen,
		req.NodeHTTPPort,
		stateDir, toolkitDir, sysBin)

	if err := os.WriteFile(filepath.Join("/etc/systemd/system", apiUnitName), []byte(apiUnit), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", sysUnitName), []byte(sysUnit), 0o644); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+apiUnitName, "wrote "+sysUnitName)

	nodeUnitPath := filepath.Join("/etc/systemd/system", nodeUnitName)
	if err := os.WriteFile(nodeUnitPath, []byte(renderCoreLikeUnit(client, prof, confPath)), 0o644); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+nodeUnitPath)

	agentURL := resolvePublicAgentURL(req.AgentPort)
	inst := map[string]any{
		"id":             network + "-" + env,
		"network":        network,
		"env":            env,
		"name":           req.Name,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"watch_slug":     prof.WatchSlug,
		"agent_url":      agentURL,
		"data_dir":       data,
		"etc_dir":        etc,
		"opt_dir":        opt,
		"units":          []string{nodeUnitName, apiUnitName, sysUnitName},
		"created_at":     time.Now().UTC().Format(time.RFC3339),
		"hostname":       hostnameOrEmpty(),
	}
	if err := writeJSONFile(filepath.Join(stateDir, "INSTANCE.json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", network+"-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", network+"-"+env+".json"), inst); err != nil {
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
		"network":        network,
		"env":            env,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"rpc_mode":       "go_proxy",
		"agent_url":      agentURL,
		"etc_dir":        etc,
		"data_dir":       data,
		"units":          []string{nodeUnitName, apiUnitName, sysUnitName},
		"units_started":  false,
		"status":         "provisioned",
		"snapshot":       false,
		"lifecycle":      "ports→install→start→run(IBD)",
		"message":        network + " per-node agents written; unit activation scheduled (Server agent left running)",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", network+"-"+env+".json"),
	}, nil
}

func activateCoreLikeUnits(network, env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-%s-%s.service", network, env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-%s-%s.service", network, env)
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

func ensureCoreLikeConf(client coreLikeClient, prof networkPortProfile, req nodeProvisionRequest) (rpcUser, rpcPass, confPath string, err error) {
	etc := prof.EtcPath
	data := prof.DataPath
	if etc == "" {
		etc = fmt.Sprintf("/etc/%s/%s", client.Network, normalizeEnv(prof.Env))
	}
	if data == "" {
		data = fmt.Sprintf("/data/%s/%s", client.Network, normalizeEnv(prof.Env))
	}
	// Parent + nest Core will use (ltc testnet → /data/ltc/testnet4) must exist as nodeop.
	coreParent := bitcoinCoreDatadirSetting(data, prof.Env)
	coreNest := coreLikeProvisionNestDir(client.Network, prof.Env, data)
	for _, d := range []string{etc, data, coreParent, coreNest} {
		if d == "" {
			continue
		}
		if err := os.MkdirAll(d, 0o755); err != nil {
			return "", "", "", fmt.Errorf("mkdir %s: %w", d, err)
		}
	}

	confPath = filepath.Join(etc, client.ConfName)
	rpcUser = "rpcnode"
	rpcPass = randomHex(24)

	if fileExists(confPath) {
		if old, rerr := os.ReadFile(confPath); rerr == nil {
			for _, ln := range strings.Split(string(old), "\n") {
				t := strings.TrimSpace(ln)
				if strings.HasPrefix(t, "rpcpassword=") {
					rpcPass = strings.TrimPrefix(t, "rpcpassword=")
				}
				if strings.HasPrefix(t, "rpcuser=") {
					rpcUser = strings.TrimPrefix(t, "rpcuser=")
				}
			}
		}
	}

	p2p := req.P2PPort
	if p2p <= 0 {
		p2p = prof.P2P
	}
	rpc := req.NodeHTTPPort
	if rpc <= 0 {
		rpc = prof.NodeHTTP
	}
	dbcache := bitcoinDBCacheMBForEnv(prof.Env)
	if dbcache > 4096 {
		dbcache = 4096
	}

	body := renderCoreLikeConf(client, prof, p2p, rpc, dbcache, rpcUser, rpcPass)
	if err := os.WriteFile(confPath, []byte(body), 0o640); err != nil {
		return "", "", confPath, err
	}
	chownPaths := []string{etc, data}
	for _, d := range []string{coreParent, coreNest} {
		if d != "" && d != data {
			chownPaths = append(chownPaths, d)
		}
	}
	_ = exec.Command("chown", append([]string{"-R", "nodeop:nodeop"}, chownPaths...)...).Run()
	_ = exec.Command("chown", "root:nodeop", confPath).Run()
	_ = os.Chmod(confPath, 0o640)

	return rpcUser, rpcPass, confPath, nil
}

// coreLikeProvisionNestDir — on-disk nest litecoind/dashd create under datadir=.
// LTC testnet=1 → testnet4; Dash/BCH → testnet3; regtest → regtest.
func coreLikeProvisionNestDir(network, env, dataPath string) string {
	dataPath = strings.TrimRight(strings.TrimSpace(dataPath), "/")
	nest := ""
	switch normalizeEnv(env) {
	case "regtest":
		nest = "regtest"
	case "signet":
		nest = "signet"
	case "testnet4":
		nest = "testnet4"
	case "testnet", "testnet3":
		switch strings.ToLower(strings.TrimSpace(network)) {
		case "ltc", "litecoin":
			nest = "testnet4"
		default:
			nest = "testnet3"
		}
	}
	if nest == "" || dataPath == "" {
		return ""
	}
	parent := bitcoinCoreDatadirSetting(dataPath, env)
	if parent == "" {
		parent = dataPath
	}
	return filepath.Join(parent, nest)
}

// renderCoreLikeConf — Dash/LTC/BCH Core conf.
// Network-only keys (port/rpcport/…) MUST sit under [test]/[regtest] when testnet/regtest
// (Dash 23+: "Config setting for -port only applied on test network when in [test] section").
func renderCoreLikeConf(
	client coreLikeClient,
	prof networkPortProfile,
	p2p, rpc, dbcache int,
	rpcUser, rpcPass string,
) string {
	var b strings.Builder
	fmt.Fprintf(&b, "# Generated by rpcnode provision — %s\n", client.Network)
	b.WriteString("server=1\n")
	b.WriteString("txindex=1\n")
	b.WriteString("prune=0\n")
	b.WriteString("disablewallet=1\n")
	b.WriteString("daemon=0\n")
	// Core nests testnet/regtest under datadir — profile DataPath is the final chain dir.
	fmt.Fprintf(&b, "datadir=%s\n", bitcoinCoreDatadirSetting(prof.DataPath, prof.Env))
	fmt.Fprintf(&b, "dbcache=%d\n", dbcache)
	// High-load RPC day one (thousands of concurrent via Go proxy).
	b.WriteString("rpcthreads=64\n")
	b.WriteString("rpcworkqueue=1024\n")
	b.WriteString("maxconnections=125\n")
	if prof.ChainFlag != "" {
		b.WriteString(prof.ChainFlag + "\n")
	}
	if sec := bitcoinConfNetworkSection(prof.Env); sec != "" {
		b.WriteString("\n[" + sec + "]\n")
	}
	fmt.Fprintf(&b, "port=%d\n", p2p)
	fmt.Fprintf(&b, "rpcport=%d\n", rpc)
	b.WriteString("rpcbind=127.0.0.1\n")
	b.WriteString("rpcallowip=127.0.0.1\n")
	fmt.Fprintf(&b, "rpcuser=%s\n", rpcUser)
	fmt.Fprintf(&b, "rpcpassword=%s\n", rpcPass)
	return b.String()
}

func renderCoreLikeUnit(client coreLikeClient, prof networkPortProfile, confPath string) string {
	bin := resolveCoreLikeBinary(client, prof.OptPath)

	return fmt.Sprintf(`[Unit]
Description=%s Core (%s) — RpcNode
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
ExecStart=%s -conf=%s
# No ExecStop: panel remove calls *-cli stop, then SIGKILL.
TimeoutStopSec=30
KillMode=mixed
KillSignal=SIGTERM
Restart=on-failure
RestartSec=10
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
`, client.DisplayName, prof.Env, bin, confPath)
}

func coreLikeBinaryCandidates(client coreLikeClient, optPath string) []string {
	cands := []string{
		filepath.Join(optPath, "bin", client.Daemon),
		filepath.Join("/opt", client.Network, "bin", client.Daemon),
	}
	// BCHN ships as "bitcoind" — never /usr/local (Bitcoin Core on shared hosts).
	if client.Network == "bch" {
		return cands
	}
	return append(cands, "/usr/local/bin/"+client.Daemon)
}

func resolveCoreLikeBinary(client coreLikeClient, optPath string) string {
	for _, cand := range coreLikeBinaryCandidates(client, optPath) {
		if fileExists(cand) {
			return cand
		}
	}
	return filepath.Join(optPath, "bin", client.Daemon)
}

func resolveCoreLikeCLI(client coreLikeClient, optPath, daemonBin string) string {
	cands := []string{
		filepath.Join(filepath.Dir(daemonBin), client.CLI),
		filepath.Join(optPath, "bin", client.CLI),
	}
	if client.Network == "bch" {
		for _, cand := range cands {
			if fileExists(cand) {
				return cand
			}
		}
		return filepath.Join(optPath, "bin", client.CLI)
	}
	cands = append(cands, "/usr/local/bin/"+client.CLI)
	for _, cand := range cands {
		if fileExists(cand) {
			return cand
		}
	}

	return filepath.Join(filepath.Dir(daemonBin), client.CLI)
}

func ensureCoreLikeInstalled(client coreLikeClient, optPath string) (string, error) {
	// resolveCoreLikeBinary for bch only looks under /opt/bch — never /usr/local
	// Bitcoin Core symlink (same bitcoind name).
	if bin := resolveCoreLikeBinary(client, optPath); fileExists(bin) {
		return bin, nil
	}
	// Never pick PATH bitcoind for BCH — would be Bitcoin Core.
	if client.Network != "bch" {
		if p, err := exec.LookPath(client.Daemon); err == nil && p != "" {
			return p, nil
		}
	}

	ver := envOr(client.VersionEnv, client.DefaultVersion)
	arch := "x86_64-linux-gnu"
	switch runtimeGOARCH() {
	case "arm64", "aarch64":
		arch = "aarch64-linux-gnu"
	}
	url := preferVendoredArtifact(client.Network, "mainnet", client.DownloadURL(ver, arch))
	name := filepath.Base(url)
	tmp := filepath.Join(os.TempDir(), name)
	destBin := filepath.Join(optPath, "bin")
	if err := os.MkdirAll(destBin, 0o755); err != nil {
		return "", fmt.Errorf("mkdir %s: %w", destBin, err)
	}

	extractDir := filepath.Join(os.TempDir(), "rpcnode-"+client.Network+"-"+ver)
	_ = os.RemoveAll(extractDir)
	archiveRoot := client.ArchiveDir(ver)
	srcDaemon := client.TarballDaemon
	srcCLI := client.TarballCLI
	if srcDaemon == "" {
		srcDaemon = client.Daemon
	}
	if srcCLI == "" {
		srcCLI = client.CLI
	}

	cmd := exec.Command("bash", "-lc", fmt.Sprintf(
		`set -euo pipefail
if ! command -v curl >/dev/null; then echo "curl required to fetch %s" >&2; exit 1; fi
curl -fsSL --connect-timeout 30 --max-time 900 -o %q %q
mkdir -p %q
tar -xzf %q -C %q
install -m 755 %q/%s/bin/%s %q/%s
install -m 755 %q/%s/bin/%s %q/%s
rm -rf %q %q
`, client.Daemon, tmp, url, extractDir, tmp, extractDir,
		extractDir, archiveRoot, srcDaemon, destBin, client.Daemon,
		extractDir, archiveRoot, srcCLI, destBin, client.CLI,
		extractDir, tmp))
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("install %s %s: %v (%s)", client.Daemon, ver, err, strings.TrimSpace(string(out)))
	}
	bin := filepath.Join(destBin, client.Daemon)
	if !fileExists(bin) {
		return "", fmt.Errorf("%s missing after install at %s", client.Daemon, bin)
	}

	return bin, nil
}
