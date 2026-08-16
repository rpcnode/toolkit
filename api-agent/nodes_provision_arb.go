package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// Arbitrum provision — nitro-node full (pruned init), L1 from ethereum host.

func provisionArbNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
	env := req.Env
	steps := []string{}
	cluster := lookupArbNetwork(env)

	if err := arbProvisionEnvOK(env); err != nil {
		return nil, err
	}

	if prof.NodeHTTP > 0 {
		req.NodeHTTPPort = prof.NodeHTTP
	}

	opt := prof.OptPath
	etc := prof.EtcPath
	data := resolveNetworkRoleDir(req, "arb", env, "execution", prof.DataPath)
	snapDir := resolveNetworkRoleDir(req, "arb", env, "snapshots", filepath.Join(prof.DataPath, "snapshots"))
	stateDir := fmt.Sprintf("/var/lib/rpcnode/arb-%s", env)
	binDir := filepath.Join(opt, "bin")

	for _, d := range []string{opt, binDir, etc, data, snapDir, stateDir, "/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system"} {
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

	l1 := defaultL1RPCURLFor(arbL1Env(env))
	beacon := defaultL1BeaconURLFor(arbL1Env(env))
	if err := writeArbEnvFile(etc, l1, beacon, cluster); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote L1 env")

	rpcBinDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))
	if token == "" {
		if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
			token = strings.TrimSpace(string(b))
		}
	}

	sysListen := systemAgentListenPort("arb", env)
	wsPort := prof.SolHTTP
	if wsPort <= 0 {
		wsPort = req.NodeHTTPPort + 1
	}

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (arb)
%sTRON_NETWORK=arb
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
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/arb-%s.json
TRON_SERVICE=arb-%s
TRON_SNAPSHOT_ENABLED=0
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		productEnvVars(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, wsPort, l1,
		sysListen, sysListen, stateDir,
		opt, etc, data, stateDir, stateDir, env, env,
		toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+envPath)

	apiBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-system-agent")

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-arb-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-arb-%s.service", env)
	nodeUnitName := fmt.Sprintf("arb-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (arb/%s) — Go RPC :%d + Agent API :%d → nitro :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=arb
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
Description=RpcNode per-node system-agent (arb/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=arb
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
	nodeUnit := renderArbUnit(env, nitroBin, data, etc, req, prof, cluster, l1, beacon, wsPort, wasmRoots)

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
		"id":             "arb-" + env,
		"network":        "arb",
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
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "arb-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "arb-"+env+".json"), inst); err != nil {
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
		"network":        "arb",
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
		"snapshot":       false,
		"lifecycle":      "ports→install→start→run(init.latest=pruned)",
		"message":        "arb per-node agents written; nitro init.latest=pruned on first start",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "arb-"+env+".json"),
	}, nil
}

func renderArbUnit(
	env, bin, datadir, etc string,
	req nodeProvisionRequest,
	prof networkPortProfile,
	cluster arbNetwork,
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

	return fmt.Sprintf(`[Unit]
Description=Arbitrum Nitro full node (%s, pruned) — RpcNode
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
EnvironmentFile=-%s
EnvironmentFile=-%s/toolkit.env
ExecStart=%s \
  --parent-chain.connection.url=%s \
  --parent-chain.blob-client.beacon-url=%s \
  --chain.id=%s \
  --http.addr=127.0.0.1 \
  --http.port=%d \
  --http.api=net,web3,eth,debug \
  --http.vhosts=* \
  --http.corsdomain=* \
  --ws.addr=127.0.0.1 \
  --ws.port=%d \
  --ws.api=net,web3,eth,debug \
  --init.latest=%s \
  --persistent.chain=%s \
  --validation.wasm.allowed-wasm-module-roots=%s
Restart=on-failure
RestartSec=30
TimeoutStartSec=infinity
TimeoutStopSec=600
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
LimitNOFILE=1048576
KillSignal=SIGTERM

[Install]
WantedBy=multi-user.target
`, env, envFile, etc, bin, l1, beacon, cluster.ChainID, rpcPort, wsPort, cluster.InitLatest, datadir, wasmRoots)
}

func activateArbUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-arb-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-arb-%s.service", env)
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

func ensureArbGoRPC(env string, publicPort int) error {
	env = normalizeEnv(env)
	if publicPort <= 0 {
		if p := lookupPortProfile("arb", env); p.Public > 0 {
			publicPort = p.Public
		}
	}
	if publicPort > 0 && portOpenLocal(publicPort) {
		return nil
	}
	if err := activateArbUnits(env); err != nil {
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
	unit := fmt.Sprintf("rpcnode-api-agent-arb-%s.service", env)
	jOut, _ := exec.Command("journalctl", "-u", unit, "-n", "16", "--no-pager", "-o", "cat").CombinedOutput()
	msg := fmt.Sprintf("Go RPC :%d not listening after restart %s", publicPort, unit)
	if snip := strings.TrimSpace(string(jOut)); snip != "" {
		msg += " — " + snip
	}

	return fmt.Errorf("%s", msg)
}

func ensureNitroInstalled(optPath string) (string, error) {
	dest := filepath.Join(optPath, "bin", "nitro")
	if !fileExists(dest) {
		bin, err := ensureBinaryFromDocker(nitroDockerImage, "/usr/local/bin/nitro", dest)
		if err != nil {
			return "", fmt.Errorf("nitro from docker %s: %w", nitroDockerImage, err)
		}
		dest = bin
	}
	// Wasm machine roots referenced by nitro entrypoint — copy beside binary if missing.
	if err := ensureNitroMachines(optPath); err != nil {
		return "", err
	}
	link := "/usr/local/bin/nitro"
	_ = os.MkdirAll(filepath.Dir(link), 0o755)
	_ = os.Remove(link)
	_ = os.Symlink(dest, link)

	return dest, nil
}

func nitroWasmRoots(optPath string) string {
	legacy := filepath.Join(optPath, "nitro-legacy", "machines")
	target := filepath.Join(optPath, "target", "machines")

	return legacy + "," + target
}

func ensureNitroMachines(optPath string) error {
	legacy := filepath.Join(optPath, "nitro-legacy", "machines")
	target := filepath.Join(optPath, "target", "machines")
	if dirExists(legacy) && dirExists(target) {
		return nil
	}
	if err := ensureDockerInstalled(); err != nil {
		return err
	}
	pull := exec.Command("docker", "pull", nitroDockerImage)
	if out, err := pull.CombinedOutput(); err != nil {
		return fmt.Errorf("docker pull nitro for machines: %v (%s)", err, strings.TrimSpace(string(out)))
	}
	cidOut, err := exec.Command("docker", "create", nitroDockerImage).CombinedOutput()
	if err != nil {
		return fmt.Errorf("docker create nitro: %v (%s)", err, strings.TrimSpace(string(cidOut)))
	}
	cid := strings.TrimSpace(string(cidOut))
	defer func() { _ = exec.Command("docker", "rm", "-f", cid).Run() }()

	_ = os.MkdirAll(filepath.Dir(legacy), 0o755)
	_ = os.MkdirAll(filepath.Dir(target), 0o755)
	for _, pair := range [][2]string{
		{"/home/user/nitro-legacy/machines", legacy},
		{"/home/user/target/machines", target},
	} {
		_ = os.RemoveAll(pair[1])
		cp := exec.Command("docker", "cp", cid+":"+pair[0], pair[1])
		if out, err := cp.CombinedOutput(); err != nil {
			// Some tags nest differently — tolerate missing and let nitro error clearly.
			fmt.Fprintf(os.Stderr, "nitro machines copy %s: %v (%s)\n", pair[0], err, strings.TrimSpace(string(out)))
		}
	}

	return nil
}

func arbProvisionEnvOK(env string) error {
	switch normalizeEnv(env) {
	case "mainnet", "sepolia":
		return nil
	default:
		return fmt.Errorf("arb provision supports mainnet/sepolia (got %s)", env)
	}
}

func writeArbEnvFile(etc, l1, beacon string, cluster arbNetwork) error {
	body := fmt.Sprintf(`# managed by rpcnode arb provision
L1_RPC_URL=%s
L1_BEACON_URL=%s
INIT_URL=%s
CHAIN_ID=%s
`, l1, beacon, cluster.InitURL, cluster.ChainID)
	path := filepath.Join(etc, "nitro.env")

	return os.WriteFile(path, []byte(body), 0o644)
}

func rewriteArbUnit(prof networkPortProfile, req nodeProvisionRequest) error {
	bin, err := ensureNitroInstalled(prof.OptPath)
	if err != nil {
		return err
	}
	cluster := lookupArbNetwork(prof.Env)
	l1 := defaultL1RPCURLFor(arbL1Env(prof.Env))
	beacon := defaultL1BeaconURLFor(arbL1Env(prof.Env))
	if err := writeArbEnvFile(prof.EtcPath, l1, beacon, cluster); err != nil {
		return err
	}
	ws := prof.SolHTTP
	if ws <= 0 {
		ws = req.NodeHTTPPort + 1
	}
	unitPath := filepath.Join("/etc/systemd/system", prof.ServiceUnit)

	return os.WriteFile(unitPath, []byte(renderArbUnit(prof.Env, bin, prof.DataPath, prof.EtcPath, req, prof, cluster, l1, beacon, ws, nitroWasmRoots(prof.OptPath))), 0o644)
}
