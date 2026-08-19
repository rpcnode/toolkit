package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// Robinhood Chain provision — nitro-node full (same binary as arb), official
// chain-info/genesis CDN + required pruned --init.url snapshot (Orbit IBD needs
// archive L1 blobs otherwise). See deploy/nodes/robinhood/DESIGN.md.

func provisionRobinhoodNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
	env := req.Env
	steps := []string{}
	cluster := lookupRobinhoodNetwork(env)

	if prof.NodeHTTP > 0 {
		req.NodeHTTPPort = prof.NodeHTTP
	}

	opt := prof.OptPath
	etc := prof.EtcPath
	data := resolveNetworkRoleDir(req, "robinhood", env, "execution", prof.DataPath)
	_ = os.MkdirAll(resolveNetworkRoleDir(req, "robinhood", env, "snapshots", filepath.Join(prof.DataPath, "snapshots")), 0o755)
	stateDir := fmt.Sprintf("/var/lib/rpcnode/robinhood-%s", env)
	binDir := filepath.Join(opt, "bin")

	for _, d := range []string{opt, binDir, etc, data, stateDir, "/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system"} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return nil, fmt.Errorf("mkdir %s: %w", d, err)
		}
		steps = append(steps, "mkdir "+d)
	}

	nitroBin, err := ensureNitroInstalled(opt)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "nitro="+nitroBin)

	_ = ensureNodeopUser()

	l1 := defaultL1RPCURLFor(robinhoodL1Env(env))
	beacon := defaultL1BeaconURLFor(robinhoodL1Env(env))
	if err := downloadRobinhoodConfigs(etc, cluster); err != nil {
		return nil, err
	}
	steps = append(steps, "downloaded chain-info"+map[bool]string{true: "+genesis", false: ""}[cluster.GenesisURL != ""])

	rpcBinDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))
	if token == "" {
		if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
			token = strings.TrimSpace(string(b))
		}
	}

	sysListen := systemAgentListenPort("robinhood", env)
	wsPort := prof.SolHTTP
	if wsPort <= 0 {
		wsPort = req.NodeHTTPPort + 1
	}

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (robinhood)
%sTRON_NETWORK=robinhood
TRON_NODE_HTTP_HOST=127.0.0.1
TRON_NODE_HTTP_PORT=%d
TRON_P2P_PORT=0
TRON_WS_PORT=%d
L1_RPC_URL=%s
TRON_SYSTEM_AGENT_LISTEN=127.0.0.1:%d
TRON_SYSTEM_AGENT_URL=http://127.0.0.1:%d
TRON_STATE_DIR=%s
TRON_OPT=%s
TRON_ETC=%s
TRON_DATA=%s
TRON_AGENT_STATE=%s/agent-state.json
TRON_INSTANCE_FILE=%s/INSTANCE.json
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/robinhood-%s.json
TRON_SERVICE=robinhood-%s
TRON_SNAPSHOT_ENABLED=1
TRON_SNAPSHOT_URL=%s
TRON_SNAPSHOT_SERVICE=robinhood-%s-snapshot
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		productEnvVars(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, wsPort, l1,
		sysListen, sysListen, stateDir,
		opt, etc, data, stateDir, stateDir, env, env,
		cluster.SnapshotURL, env,
		toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+envPath)

	apiBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-system-agent")

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-robinhood-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-robinhood-%s.service", env)
	nodeUnitName := fmt.Sprintf("robinhood-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (robinhood/%s) — Go RPC :%d + Agent API :%d → nitro :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=robinhood
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
Description=RpcNode per-node system-agent (robinhood/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=robinhood
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

	wasmRoots := nitroWasmRoots(opt)
	nodeUnit := renderRobinhoodUnit(env, nitroBin, data, etc, req, prof, cluster, l1, beacon, wsPort, wasmRoots)

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
	if err := ensureRobinhoodSnapshotUnit(env); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote robinhood-"+env+"-snapshot.service")
	if err := writeRobinhoodNitroEnv(etc, cluster); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote nitro.env init.url")

	_ = exec.Command("chown", "-R", "nodeop:nodeop", opt, etc, data).Run()

	agentURL := resolvePublicAgentURL(req.AgentPort)
	watch := cluster.WatchSlug
	if watch == "" {
		watch = prof.WatchSlug
	}
	inst := map[string]any{
		"id":             "robinhood-" + env,
		"network":        "robinhood",
		"env":            env,
		"name":           req.Name,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"ws_port":        wsPort,
		"p2p_port":       0,
		"watch_slug":     watch,
		"chain_id":       cluster.ChainID,
		"l1_rpc_url":     l1,
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
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "robinhood-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "robinhood-"+env+".json"), inst); err != nil {
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
		"network":        "robinhood",
		"env":            env,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       0,
		"rpc_mode":       "go_proxy",
		"agent_url":      agentURL,
		"l1_rpc_url":     l1,
		"etc_dir":        etc,
		"data_dir":       data,
		"units":          []string{nodeUnitName, apiUnitName, sysUnitName},
		"units_started":  false,
		"status":         "provisioned",
		"snapshot":       true,
		"snapshot_url":   cluster.SnapshotURL,
		"lifecycle":      "ports→install→snapshot→start→run",
		"message":        "robinhood per-node agents written; nitro --init.url pruned snapshot then catch-up",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "robinhood-"+env+".json"),
	}, nil
}

// downloadRobinhoodConfigs fetches the official chain-info (+ genesis for mainnet) into etc.
func downloadRobinhoodConfigs(etc string, cluster robinhoodNetwork) error {
	if err := os.MkdirAll(etc, 0o755); err != nil {
		return err
	}
	chainInfoPath := robinhoodChainInfoPath(etc)
	if !fileExists(chainInfoPath) {
		if err := downloadNamedConf("robinhood", cluster.Env, filepath.Base(cluster.ChainInfoURL), cluster.ChainInfoURL, chainInfoPath); err != nil {
			return fmt.Errorf("download robinhood chain-info: %w", err)
		}
	}
	if cluster.GenesisURL != "" {
		genesisPath := robinhoodGenesisPath(etc)
		if !fileExists(genesisPath) {
			if err := downloadNamedConf("robinhood", cluster.Env, filepath.Base(cluster.GenesisURL), cluster.GenesisURL, genesisPath); err != nil {
				return fmt.Errorf("download robinhood genesis: %w", err)
			}
		}
	}
	return nil
}

func robinhoodChainInfoPath(etc string) string {
	return filepath.Join(etc, "chain-info.json")
}

func robinhoodGenesisPath(etc string) string {
	return filepath.Join(etc, "genesis.json")
}

func renderRobinhoodUnit(
	env, bin, datadir, etc string,
	req nodeProvisionRequest,
	prof networkPortProfile,
	cluster robinhoodNetwork,
	l1, beacon string,
	wsPort int,
	wasmRoots string,
) string {
	rpcPort := req.NodeHTTPPort
	if rpcPort <= 0 {
		rpcPort = prof.NodeHTTP
	}
	envFile := filepath.Join(etc, "nitro.env")
	if strings.TrimSpace(wasmRoots) == "" {
		wasmRoots = nitroWasmRoots(filepath.Dir(filepath.Dir(bin)))
	}
	chainInfo := robinhoodChainInfoPath(etc)

	initGenesis := ""
	if cluster.GenesisURL != "" {
		initGenesis = fmt.Sprintf(" \\\n  --init.genesis-json-file=%s", robinhoodGenesisPath(etc))
	}
	initURL := strings.TrimSpace(cluster.SnapshotURL)
	if initURL == "" {
		initURL = resolveRobinhoodInitURL(cluster)
	}
	initURLFlag := ""
	if initURL != "" {
		// systemd treats % as specifiers — escape so %20 in CDN paths survives.
		initURLFlag = fmt.Sprintf(" \\\n  --init.url=%s", strings.ReplaceAll(initURL, "%", "%%"))
		// Only force-reinit when a prior partial nitro/ dir exists (empty datadir must not use
		// --init.force — it exits mid-download and systemd restart fights the transfer).
		// persistent.chain is <datadir>/chain — staging lives under chain/nitro, not datadir/nitro.
		marker := filepath.Join(datadir, ".snapshot-ready")
		chainNitro := filepath.Join(robinhoodPersistentChain(datadir), "nitro")
		if !fileExists(marker) && (dirExists(chainNitro) || dirExists(filepath.Join(datadir, "nitro"))) {
			initURLFlag += " \\\n  --init.force"
		}
	}

	return fmt.Sprintf(`[Unit]
Description=Robinhood Chain Nitro full node (%s) — RpcNode
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
EnvironmentFile=-%s
EnvironmentFile=-%s/toolkit.env
ExecStart=%s \
  --chain.info-files=%s \
  --parent-chain.connection.url=%s \
  --parent-chain.blob-client.beacon-url=%s \
  --execution.forwarding-target=null \
  --http.addr=127.0.0.1 \
  --http.port=%d \
  --http.api=net,web3,eth,debug \
  --http.vhosts=* \
  --http.corsdomain=* \
  --ws.addr=127.0.0.1 \
  --ws.port=%d \
  --ws.api=net,web3,eth,debug \
  --node.feed.input.url=%s \
  --persistent.chain=%s \
  --validation.wasm.allowed-wasm-module-roots=%s%s%s
Restart=on-failure
RestartSec=120
TimeoutStartSec=infinity
TimeoutStopSec=600
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
LimitNOFILE=1048576
KillSignal=SIGTERM

[Install]
WantedBy=multi-user.target
`, env, envFile, etc, bin, chainInfo, l1, beacon, rpcPort, wsPort, cluster.FeedURL, robinhoodPersistentChain(datadir), wasmRoots, initGenesis, initURLFlag)
}

// robinhoodPersistentChain — dedicated empty subdir so nitro init staging (tmp/)
// is not confused with sibling files under /data/robinhood/<env>.
func robinhoodPersistentChain(datadir string) string {
	return filepath.Join(datadir, "chain")
}

// writeRobinhoodNitroEnv persists init.url for ops / collect (nitro reads flags from unit).
func writeRobinhoodNitroEnv(etc string, cluster robinhoodNetwork) error {
	if err := os.MkdirAll(etc, 0o755); err != nil {
		return err
	}
	url := strings.TrimSpace(cluster.SnapshotURL)
	if url == "" {
		url = resolveRobinhoodInitURL(cluster)
	}
	if url != "" {
		logDownload("snapshot", url, "robinhood/"+cluster.Env+" --init.url")
	}
	body := fmt.Sprintf(`# managed by rpcnode provision (robinhood)
NITRO_INIT_URL=%s
TRON_SNAPSHOT_URL=%s
`, url, url)
	if err := os.WriteFile(filepath.Join(etc, "nitro.env"), []byte(body), 0o644); err != nil {
		return err
	}
	return patchRobinhoodToolkitEnvSnapshot(etc, cluster.Env, url)
}

// patchRobinhoodToolkitEnvSnapshot flips TRON_SNAPSHOT_* on existing toolkit.env (heal/start).
func patchRobinhoodToolkitEnvSnapshot(etc, env, snapURL string) error {
	path := filepath.Join(etc, "toolkit.env")
	b, err := os.ReadFile(path)
	if err != nil {
		return nil // provision will write full env later
	}
	s := string(b)
	s = strings.ReplaceAll(s, "TRON_SNAPSHOT_ENABLED=0", "TRON_SNAPSHOT_ENABLED=1")
	if !strings.Contains(s, "TRON_SNAPSHOT_ENABLED=") {
		s += "TRON_SNAPSHOT_ENABLED=1\n"
	}
	if strings.Contains(s, "TRON_SNAPSHOT_URL=") {
		lines := strings.Split(s, "\n")
		for i, ln := range lines {
			if strings.HasPrefix(ln, "TRON_SNAPSHOT_URL=") {
				lines[i] = "TRON_SNAPSHOT_URL=" + snapURL
			}
		}
		s = strings.Join(lines, "\n")
	} else {
		s += "TRON_SNAPSHOT_URL=" + snapURL + "\n"
	}
	svc := fmt.Sprintf("robinhood-%s-snapshot", normalizeEnv(env))
	if strings.Contains(s, "TRON_SNAPSHOT_SERVICE=") {
		lines := strings.Split(s, "\n")
		for i, ln := range lines {
			if strings.HasPrefix(ln, "TRON_SNAPSHOT_SERVICE=") {
				lines[i] = "TRON_SNAPSHOT_SERVICE=" + svc
			}
		}
		s = strings.Join(lines, "\n")
	} else {
		s += "TRON_SNAPSHOT_SERVICE=" + svc + "\n"
	}
	return os.WriteFile(path, []byte(s), 0o600)
}

// ensureRobinhoodSnapshotUnit — oneshot that starts the node unit so nitro can
// download --init.url. Progress is scraped from the node journal by system-agent.
func ensureRobinhoodSnapshotUnit(env string) error {
	env = normalizeEnv(env)
	marker := filepath.Join("/data/robinhood", env, ".snapshot-ready")
	nodeUnit := fmt.Sprintf("robinhood-%s.service", env)
	unitPath := fmt.Sprintf("/etc/systemd/system/robinhood-%s-snapshot.service", env)
	body := fmt.Sprintf(`[Unit]
Description=Robinhood %s nitro pruned snapshot (--init.url via node unit)
After=network-online.target
Wants=network-online.target
ConditionPathExists=!%s

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/bin/systemctl start %s
Nice=10
`, env, marker, nodeUnit)
	if err := os.WriteFile(unitPath, []byte(body), 0o644); err != nil {
		return err
	}
	if _, err := exec.LookPath("systemctl"); err == nil {
		_ = exec.Command("systemctl", "daemon-reload").Run()
	}
	return nil
}

func activateRobinhoodUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-robinhood-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-robinhood-%s.service", env)
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

func ensureRobinhoodGoRPC(env string, publicPort int) error {
	env = normalizeEnv(env)
	if publicPort <= 0 {
		if p := lookupPortProfile("robinhood", env); p.Public > 0 {
			publicPort = p.Public
		}
	}
	if publicPort > 0 && portOpenLocal(publicPort) {
		return nil
	}
	if err := activateRobinhoodUnits(env); err != nil {
		return err
	}
	if publicPort <= 0 {
		return nil
	}
	deadline := time.Now().Add(12 * time.Second)
	for time.Now().Before(deadline) {
		if portOpenLocal(publicPort) {
			return nil
		}
		time.Sleep(400 * time.Millisecond)
	}
	unit := fmt.Sprintf("rpcnode-api-agent-robinhood-%s.service", env)
	jOut, _ := exec.Command("journalctl", "-u", unit, "-n", "16", "--no-pager", "-o", "cat").CombinedOutput()
	msg := fmt.Sprintf("Go RPC :%d not listening after restart %s", publicPort, unit)
	if snip := strings.TrimSpace(string(jOut)); snip != "" {
		msg += " — " + snip
	}

	return fmt.Errorf("%s", msg)
}

func rewriteRobinhoodUnit(prof networkPortProfile, req nodeProvisionRequest) error {
	bin, err := ensureNitroInstalled(prof.OptPath)
	if err != nil {
		return err
	}
	cluster := lookupRobinhoodNetwork(prof.Env)
	l1 := defaultL1RPCURLFor(robinhoodL1Env(prof.Env))
	beacon := defaultL1BeaconURLFor(robinhoodL1Env(prof.Env))
	if err := downloadRobinhoodConfigs(prof.EtcPath, cluster); err != nil {
		return err
	}
	if err := writeRobinhoodNitroEnv(prof.EtcPath, cluster); err != nil {
		return err
	}
	if err := ensureRobinhoodSnapshotUnit(prof.Env); err != nil {
		return err
	}
	ws := prof.SolHTTP
	if ws <= 0 {
		ws = req.NodeHTTPPort + 1
	}
	unitPath := filepath.Join("/etc/systemd/system", prof.ServiceUnit)

	return os.WriteFile(unitPath, []byte(renderRobinhoodUnit(prof.Env, bin, prof.DataPath, prof.EtcPath, req, prof, cluster, l1, beacon, ws, nitroWasmRoots(prof.OptPath))), 0o644)
}
