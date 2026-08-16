package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// Optimism provision — op-geth (full) + op-node; L1 RPC+Beacon from ethereum host.

func provisionOptimismNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
	env := req.Env
	steps := []string{}
	cluster := lookupOptimismNetwork(env)

	if err := optimismProvisionEnvOK(env); err != nil {
		return nil, err
	}

	if prof.NodeHTTP > 0 {
		req.NodeHTTPPort = prof.NodeHTTP
	}
	if prof.P2P > 0 {
		req.P2PPort = prof.P2P
	}

	opt := prof.OptPath
	etc := prof.EtcPath
	data := prof.DataPath
	stateDir := fmt.Sprintf("/var/lib/rpcnode/optimism-%s", env)
	binDir := filepath.Join(opt, "bin")
	gethData := resolveNetworkRoleDir(req, "optimism", env, "execution", filepath.Join(data, "op-geth"))
	nodeData := resolveNetworkRoleDir(req, "optimism", env, "snapshots", filepath.Join(data, "op-node"))
	jwtPath := filepath.Join(etc, "jwt.hex")

	for _, d := range []string{opt, binDir, etc, data, gethData, nodeData, stateDir, "/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system"} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return nil, fmt.Errorf("mkdir %s: %w", d, err)
		}
		steps = append(steps, "mkdir "+d)
	}

	gethBin, err := ensureOpGethInstalled(opt)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "op-geth="+gethBin)

	opNodeBin, err := ensureOpNodeInstalled(opt)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "op-node="+opNodeBin)

	_ = ensureNodeopUser()

	if err := ensureJWT(jwtPath); err != nil {
		return nil, err
	}
	steps = append(steps, "jwt="+jwtPath)

	l1 := defaultL1RPCURLFor(optimismL1Env(env))
	beacon := defaultL1BeaconURLFor(optimismL1Env(env))
	if err := writeOptimismEnvFile(etc, l1, beacon); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote L1 env")

	enginePort := prof.SolHTTP
	opNodeP2P := prof.PBFTHTTP
	opNodeRPC := prof.Metrics
	if enginePort <= 0 {
		enginePort = 8559
	}
	if opNodeP2P <= 0 {
		opNodeP2P = 9003
	}
	if opNodeRPC <= 0 {
		opNodeRPC = 9545
	}

	rpcBinDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))
	if token == "" {
		if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
			token = strings.TrimSpace(string(b))
		}
	}

	sysListen := systemAgentListenPort("optimism", env)

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (optimism)
%sTRON_NETWORK=optimism
TRON_NODE_HTTP_HOST=127.0.0.1
TRON_NODE_HTTP_PORT=%d
TRON_P2P_PORT=%d
TRON_ENGINE_PORT=%d
TRON_OP_NODE_P2P_PORT=%d
TRON_OP_NODE_RPC_PORT=%d
TRON_JWT=%s
L1_RPC_URL=%s
L1_BEACON_URL=%s
TRON_SYSTEM_AGENT_LISTEN=127.0.0.1:%d
TRON_SYSTEM_AGENT_URL=http://127.0.0.1:%d
TRON_STATE_DIR=%s
TRON_OPT=%s
TRON_ETC=%s
TRON_DATA=%s
TRON_AGENT_STATE=%s/agent-state.json
TRON_INSTANCE_FILE=%s/INSTANCE.json
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/optimism-%s.json
TRON_SERVICE=optimism-%s
TRON_OP_NODE_SERVICE=optimism-op-node-%s
TRON_SNAPSHOT_ENABLED=0
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		productEnvVars(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, req.P2PPort,
		enginePort, opNodeP2P, opNodeRPC, jwtPath, l1, beacon,
		sysListen, sysListen, stateDir,
		opt, etc, data, stateDir, stateDir, env, env, env,
		toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+envPath)

	apiBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-system-agent")

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-optimism-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-optimism-%s.service", env)
	gethUnitName := fmt.Sprintf("optimism-%s.service", env)
	opNodeUnitName := fmt.Sprintf("optimism-op-node-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (optimism/%s) — Go RPC :%d + Agent API :%d → op-geth :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=optimism
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
Description=RpcNode per-node system-agent (optimism/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=optimism
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

	gethUnit := renderOpGethUnit(env, gethBin, gethData, jwtPath, req, prof, cluster, enginePort)
	opNodeUnit := renderOpNodeUnit(env, opNodeBin, jwtPath, enginePort, opNodeP2P, opNodeRPC, cluster, l1, beacon, gethUnitName)

	if err := os.WriteFile(filepath.Join("/etc/systemd/system", apiUnitName), []byte(apiUnit), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", sysUnitName), []byte(sysUnit), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", gethUnitName), []byte(gethUnit), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", opNodeUnitName), []byte(opNodeUnit), 0o644); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+apiUnitName, "wrote "+sysUnitName, "wrote "+gethUnitName, "wrote "+opNodeUnitName)

	_ = exec.Command("chown", "-R", "nodeop:nodeop", opt, etc, data).Run()
	_ = exec.Command("chown", "root:nodeop", jwtPath).Run()
	_ = os.Chmod(jwtPath, 0o640)

	agentURL := resolvePublicAgentURL(req.AgentPort)
	watch := cluster.WatchSlug
	if watch == "" {
		watch = prof.WatchSlug
	}
	inst := map[string]any{
		"id":             "optimism-" + env,
		"network":        "optimism",
		"env":            env,
		"name":           req.Name,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"engine_port":    enginePort,
		"op_node_p2p":    opNodeP2P,
		"watch_slug":     watch,
		"chain_id":       cluster.ChainID,
		"l1_rpc_url":     l1,
		"l1_beacon_url":  beacon,
		"agent_url":      agentURL,
		"data_dir":       data,
		"geth_dir":       gethData,
		"op_node_dir":    nodeData,
		"etc_dir":        etc,
		"opt_dir":        opt,
		"jwt_path":       jwtPath,
		"units":          []string{gethUnitName, opNodeUnitName, apiUnitName, sysUnitName},
		"created_at":     time.Now().UTC().Format(time.RFC3339),
		"hostname":       hostnameOrEmpty(),
	}
	if err := writeJSONFile(filepath.Join(stateDir, "INSTANCE.json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "optimism-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "optimism-"+env+".json"), inst); err != nil {
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
		"network":        "optimism",
		"env":            env,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"rpc_mode":       "go_proxy",
		"agent_url":      agentURL,
		"l1_rpc_url":     l1,
		"l1_beacon_url":  beacon,
		"etc_dir":        etc,
		"data_dir":       data,
		"units":          []string{gethUnitName, opNodeUnitName, apiUnitName, sysUnitName},
		"units_started":  false,
		"status":         "provisioned",
		"snapshot":       false,
		"lifecycle":      "ports→install→start→run",
		"message":        "optimism per-node agents written; op-geth+op-node activation scheduled",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "optimism-"+env+".json"),
	}, nil
}

func renderOpGethUnit(
	env, bin, datadir, jwtPath string,
	req nodeProvisionRequest,
	prof networkPortProfile,
	cluster optimismNetwork,
	enginePort int,
) string {
	rpcPort := req.NodeHTTPPort
	if rpcPort <= 0 {
		rpcPort = prof.NodeHTTP
	}
	p2p := req.P2PPort
	if p2p <= 0 {
		p2p = prof.P2P
	}
	cacheMB := optimismGethCacheMB(env)

	return fmt.Sprintf(`[Unit]
Description=Optimism op-geth (%s, full) — RpcNode
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
ExecStart=%s \
  --datadir=%s \
  --http --http.addr=127.0.0.1 --http.port=%d \
  --http.api=eth,net,web3,txpool,debug \
  --http.vhosts=* \
  --http.corsdomain=* \
  --authrpc.addr=127.0.0.1 --authrpc.port=%d \
  --authrpc.jwtsecret=%s \
  --rollup.disabletxpoolgossip=true \
  --gcmode=full \
  --cache=%d \
  --maxpeers=100 \
  --rpc.batch-request-limit=2000 \
  --op-network=%s \
  --port=%d
Restart=on-failure
RestartSec=10
TimeoutStopSec=300
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
LimitNOFILE=1048576
KillSignal=SIGTERM

[Install]
WantedBy=multi-user.target
`, env, bin, datadir, rpcPort, enginePort, jwtPath, cacheMB, cluster.NetworkFlag, p2p)
}

func renderOpNodeUnit(
	env, bin, jwtPath string,
	enginePort, p2pPort, rpcPort int,
	cluster optimismNetwork,
	l1, beacon, gethUnit string,
) string {
	workDir := filepath.Join("/data/optimism", env, "op-node")

	return fmt.Sprintf(`[Unit]
Description=Optimism op-node (%s) — RpcNode
After=network-online.target %s
Wants=network-online.target
Requires=%s

[Service]
Type=simple
User=nodeop
Group=nodeop
WorkingDirectory=%s
EnvironmentFile=-/etc/optimism/%s/env
ExecStart=%s \
  --l1=%s \
  --l1.beacon=%s \
  --l2=http://127.0.0.1:%d \
  --l2.jwt-secret=%s \
  --network=%s \
  --syncmode=execution-layer \
  --rpc.addr=127.0.0.1 --rpc.port=%d \
  --p2p.listen.tcp=%d --p2p.listen.udp=%d
Restart=on-failure
RestartSec=10
TimeoutStopSec=120
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
LimitNOFILE=1048576
KillSignal=SIGTERM

[Install]
WantedBy=multi-user.target
`, env, gethUnit, gethUnit, workDir, env, bin, l1, beacon, enginePort, jwtPath, cluster.NetworkFlag, rpcPort, p2pPort, p2pPort)
}

func activateOptimismUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-optimism-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-optimism-%s.service", env)
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

func ensureOptimismGoRPC(env string, publicPort int) error {
	env = normalizeEnv(env)
	if publicPort <= 0 {
		if p := lookupPortProfile("optimism", env); p.Public > 0 {
			publicPort = p.Public
		}
	}
	if publicPort > 0 && portOpenLocal(publicPort) {
		return nil
	}
	if err := activateOptimismUnits(env); err != nil {
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
	unit := fmt.Sprintf("rpcnode-api-agent-optimism-%s.service", env)
	jOut, _ := exec.Command("journalctl", "-u", unit, "-n", "16", "--no-pager", "-o", "cat").CombinedOutput()
	msg := fmt.Sprintf("Go RPC :%d not listening after restart %s", publicPort, unit)
	if snip := strings.TrimSpace(string(jOut)); snip != "" {
		msg += " — " + snip
	}

	return fmt.Errorf("%s", msg)
}

func ensureOpGethInstalled(optPath string) (string, error) {
	dest := filepath.Join(optPath, "bin", "op-geth")
	if fileExists(dest) {
		return dest, nil
	}
	bin, err := ensureBinaryFromDocker(opGethDockerImage, "/usr/local/bin/geth", dest)
	if err != nil {
		// Some images ship as op-geth.
		bin, err = ensureBinaryFromDocker(opGethDockerImage, "/usr/local/bin/op-geth", dest)
		if err != nil {
			return "", fmt.Errorf("op-geth from docker %s: %w", opGethDockerImage, err)
		}
	}
	link := "/usr/local/bin/op-geth"
	_ = os.MkdirAll(filepath.Dir(link), 0o755)
	_ = os.Remove(link)
	_ = os.Symlink(bin, link)

	return bin, nil
}

func ensureOpNodeInstalled(optPath string) (string, error) {
	dest := filepath.Join(optPath, "bin", "op-node")
	if fileExists(dest) {
		return dest, nil
	}
	bin, err := ensureBinaryFromDocker(opNodeDockerImage, "/usr/local/bin/op-node", dest)
	if err != nil {
		return "", fmt.Errorf("op-node from docker %s: %w", opNodeDockerImage, err)
	}
	link := "/usr/local/bin/op-node"
	_ = os.MkdirAll(filepath.Dir(link), 0o755)
	_ = os.Remove(link)
	_ = os.Symlink(bin, link)

	return bin, nil
}

func writeOptimismEnvFile(etc, l1, beacon string) error {
	body := fmt.Sprintf(`# managed by rpcnode optimism provision
L1_RPC_URL=%s
L1_BEACON_URL=%s
`, l1, beacon)
	if err := os.WriteFile(filepath.Join(etc, "env"), []byte(body), 0o644); err != nil {
		return err
	}

	return nil
}

func rewriteOptimismUnits(prof networkPortProfile, req nodeProvisionRequest) error {
	gethBin, err := ensureOpGethInstalled(prof.OptPath)
	if err != nil {
		return err
	}
	opNodeBin, err := ensureOpNodeInstalled(prof.OptPath)
	if err != nil {
		return err
	}
	jwtPath := filepath.Join(prof.EtcPath, "jwt.hex")
	if err := ensureJWT(jwtPath); err != nil {
		return err
	}
	cluster := lookupOptimismNetwork(prof.Env)
	l1 := defaultL1RPCURLFor(optimismL1Env(prof.Env))
	beacon := defaultL1BeaconURLFor(optimismL1Env(prof.Env))
	_ = writeOptimismEnvFile(prof.EtcPath, l1, beacon)
	engine := prof.SolHTTP
	if engine <= 0 {
		engine = 8559
	}
	opP2P := prof.PBFTHTTP
	if opP2P <= 0 {
		opP2P = 9003
	}
	opRPC := prof.Metrics
	if opRPC <= 0 {
		opRPC = 9545
	}
	gethData := filepath.Join(prof.DataPath, "op-geth")
	gethUnitName := fmt.Sprintf("optimism-%s.service", prof.Env)
	opNodeUnitName := fmt.Sprintf("optimism-op-node-%s.service", prof.Env)
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", gethUnitName),
		[]byte(renderOpGethUnit(prof.Env, gethBin, gethData, jwtPath, req, prof, cluster, engine)), 0o644); err != nil {
		return err
	}

	return os.WriteFile(filepath.Join("/etc/systemd/system", opNodeUnitName),
		[]byte(renderOpNodeUnit(prof.Env, opNodeBin, jwtPath, engine, opP2P, opRPC, cluster, l1, beacon, gethUnitName)), 0o644)
}
