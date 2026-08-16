package main

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// Hyperliquid provision — hl-visor run-non-validator + --serve-eth-rpc / --serve-info.

func provisionHyperliquidNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
	env := req.Env
	steps := []string{}
	cluster := lookupHyperliquidNetwork(env)

	env = normalizeEnv(env)
	if env != "mainnet" && env != "testnet" {
		return nil, fmt.Errorf("hyperliquid provision supports mainnet/testnet (got %s)", env)
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
	stateDir := fmt.Sprintf("/var/lib/rpcnode/hyperliquid-%s", env)
	binDir := filepath.Join(opt, "bin")

	for _, d := range []string{opt, binDir, etc, data, stateDir, "/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system"} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return nil, fmt.Errorf("mkdir %s: %w", d, err)
		}
		steps = append(steps, "mkdir "+d)
	}

	visor, err := ensureHLVisorInstalled(opt, cluster)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "hl-visor="+visor)

	_ = ensureNodeopUser()
	_ = ensureHyperliquidGPG()
	// Official HL layout is $HOME/hl (binary hardcodes that path). With
	// one_env_per_host we use the canonical /home/nodeop/hl workdir and a
	// distinct binary basename (hl-visor-<env>) so journalctl -u hl-visor
	// substring checks do not false-positive.
	homeHL := "/home/nodeop/hl"
	_ = os.MkdirAll(filepath.Join(homeHL, "tmp"), 0o755)
	_ = os.RemoveAll(filepath.Join(homeHL, "hyperliquid_data"))
	if err := os.Symlink(data, filepath.Join(homeHL, "hyperliquid_data")); err != nil {
		return nil, fmt.Errorf("hyperliquid_data symlink: %w", err)
	}
	visorNamed := filepath.Join(homeHL, "hl-visor-"+env)
	_ = os.Remove(visorNamed)
	if err := copyFile(visor, visorNamed); err != nil {
		_ = os.Symlink(visor, visorNamed)
	}
	_ = os.Chmod(visorNamed, 0o755)
	_ = exec.Command("chown", "-R", "nodeop:nodeop", homeHL).Run()

	if err := writeHyperliquidConfigs(etc, data, binDir, cluster); err != nil {
		return nil, err
	}
	_ = writeHyperliquidConfigs(homeHL, homeHL, homeHL, cluster)
	steps = append(steps, "wrote visor+gossip configs")

	rpcBinDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))
	if token == "" {
		if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
			token = strings.TrimSpace(string(b))
		}
	}

	sysListen := systemAgentListenPort("hyperliquid", env)

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (hyperliquid)
%sTRON_NETWORK=hyperliquid
TRON_NODE_HTTP_HOST=127.0.0.1
TRON_NODE_HTTP_PORT=%d
TRON_NODE_HTTP_PATH=/evm
TRON_P2P_PORT=%d
TRON_SYSTEM_AGENT_LISTEN=127.0.0.1:%d
TRON_SYSTEM_AGENT_URL=http://127.0.0.1:%d
TRON_STATE_DIR=%s
TRON_OPT=%s
TRON_ETC=%s
TRON_DATA=%s
TRON_AGENT_STATE=%s/agent-state.json
TRON_INSTANCE_FILE=%s/INSTANCE.json
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/hyperliquid-%s.json
TRON_SERVICE=hyperliquid-%s
TRON_SNAPSHOT_ENABLED=0
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		productEnvVars(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, req.P2PPort,
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

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-hyperliquid-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-hyperliquid-%s.service", env)
	nodeUnitName := fmt.Sprintf("hyperliquid-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (hyperliquid/%s) — Go RPC :%d + Agent API :%d → hl-visor :%d/evm
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=hyperliquid
Environment=TRON_NODE_HTTP_HOST=127.0.0.1
Environment=TRON_NODE_HTTP_PORT=%d
Environment=TRON_NODE_HTTP_PATH=/evm
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
Description=RpcNode per-node system-agent (hyperliquid/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=hyperliquid
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

	nodeUnit := renderHyperliquidUnit(env, visorNamed, homeHL, req, prof)

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
		"id":             "hyperliquid-" + env,
		"network":        "hyperliquid",
		"env":            env,
		"name":           req.Name,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"node_http_path": "/evm",
		"p2p_port":       req.P2PPort,
		"watch_slug":     watch,
		"chain_id":       cluster.ChainID,
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
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "hyperliquid-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "hyperliquid-"+env+".json"), inst); err != nil {
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

	// Gossip ports 4001-4002 (P2P + next).

	return map[string]any{
		"ok":             true,
		"network":        "hyperliquid",
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
		"lifecycle":      "ports→install→start→run",
		"message":        "hyperliquid per-node agents written; unit activation scheduled",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "hyperliquid-"+env+".json"),
	}, nil
}

func renderHyperliquidUnit(env, visor, workdir string, req nodeProvisionRequest, prof networkPortProfile) string {
	_ = req
	_ = prof

	return fmt.Sprintf(`[Unit]
Description=Hyperliquid non-validator full RPC (%s) — RpcNode
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
WorkingDirectory=%s
Environment=HOME=/home/nodeop
ExecStart=%s run-non-validator \
  --replica-cmds-style actions \
  --serve-eth-rpc \
  --serve-info
Restart=on-failure
RestartSec=15
TimeoutStopSec=300
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
LimitNOFILE=1048576
KillSignal=SIGTERM

[Install]
WantedBy=multi-user.target
`, env, workdir, visor)
}

func activateHyperliquidUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-hyperliquid-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-hyperliquid-%s.service", env)
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

func ensureHyperliquidGoRPC(env string, publicPort int) error {
	env = normalizeEnv(env)
	if publicPort <= 0 {
		if p := lookupPortProfile("hyperliquid", env); p.Public > 0 {
			publicPort = p.Public
		}
	}
	if publicPort > 0 && portOpenLocal(publicPort) {
		return nil
	}
	if err := activateHyperliquidUnits(env); err != nil {
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
	unit := fmt.Sprintf("rpcnode-api-agent-hyperliquid-%s.service", env)
	jOut, _ := exec.Command("journalctl", "-u", unit, "-n", "16", "--no-pager", "-o", "cat").CombinedOutput()
	msg := fmt.Sprintf("Go RPC :%d not listening after restart %s", publicPort, unit)
	if snip := strings.TrimSpace(string(jOut)); snip != "" {
		msg += " — " + snip
	}

	return fmt.Errorf("%s", msg)
}

func ensureHLVisorInstalled(optPath string, cluster hyperliquidNetwork) (string, error) {
	dest := filepath.Join(optPath, "bin", "hl-visor")
	if fileExists(dest) {
		return dest, nil
	}
	_ = os.MkdirAll(filepath.Dir(dest), 0o755)
	tmp, err := os.MkdirTemp("", "hl-visor-*")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(tmp)
	binPath := filepath.Join(tmp, "hl-visor")
	if err := downloadFile(cluster.BinaryURL, binPath); err != nil {
		return "", fmt.Errorf("hl-visor download (%s): %w", cluster.BinaryURL, err)
	}
	_ = exec.Command("install", "-m", "0755", binPath, dest).Run()
	if !fileExists(dest) {
		if err := copyFile(binPath, dest); err != nil {
			return "", err
		}
		_ = os.Chmod(dest, 0o755)
	}
	link := "/usr/local/bin/hl-visor"
	_ = os.MkdirAll(filepath.Dir(link), 0o755)
	_ = os.Remove(link)
	_ = os.Symlink(dest, link)

	return dest, nil
}

func writeHyperliquidConfigs(etc, data, binDir string, cluster hyperliquidNetwork) error {
	visor := map[string]any{"chain": cluster.ChainName}
	raw, _ := json.MarshalIndent(visor, "", "  ")
	// hl-visor resolves visor.json next to the binary (…/bin/visor.json).
	for _, dir := range []string{data, etc, binDir} {
		if strings.TrimSpace(dir) == "" {
			continue
		}
		_ = os.MkdirAll(dir, 0o755)
		if err := os.WriteFile(filepath.Join(dir, "visor.json"), append(raw, '\n'), 0o644); err != nil {
			return err
		}
	}

	peerIPs, err := resolveHyperliquidGossipPeers(cluster)
	if err != nil {
		return err
	}
	ips := make([]map[string]string, 0, len(peerIPs))
	for _, ip := range peerIPs {
		ips = append(ips, map[string]string{"Ip": ip})
	}
	gossip := map[string]any{
		"root_node_ips": ips,
		"try_new_peers": true,
		"chain":         cluster.ChainName,
	}
	gRaw, _ := json.MarshalIndent(gossip, "", "  ")
	for _, dir := range []string{data, etc, binDir} {
		if strings.TrimSpace(dir) == "" {
			continue
		}
		if err := os.WriteFile(filepath.Join(dir, "override_gossip_config.json"), append(gRaw, '\n'), 0o644); err != nil {
			return err
		}
	}

	return nil
}

func rewriteHyperliquidUnit(prof networkPortProfile, req nodeProvisionRequest) error {
	cluster := lookupHyperliquidNetwork(prof.Env)
	bin, err := ensureHLVisorInstalled(prof.OptPath, cluster)
	if err != nil {
		return err
	}
	_ = ensureHyperliquidGPG()
	env := normalizeEnv(prof.Env)
	homeHL := "/home/nodeop/hl"
	_ = os.MkdirAll(filepath.Join(homeHL, "tmp"), 0o755)
	_ = os.RemoveAll(filepath.Join(homeHL, "hyperliquid_data"))
	_ = os.Symlink(prof.DataPath, filepath.Join(homeHL, "hyperliquid_data"))
	visorNamed := filepath.Join(homeHL, "hl-visor-"+env)
	_ = os.Remove(visorNamed)
	if err := copyFile(bin, visorNamed); err != nil {
		_ = os.Symlink(bin, visorNamed)
	}
	_ = os.Chmod(visorNamed, 0o755)
	if err := writeHyperliquidConfigs(prof.EtcPath, prof.DataPath, filepath.Join(prof.OptPath, "bin"), cluster); err != nil {
		return err
	}
	_ = writeHyperliquidConfigs(homeHL, homeHL, homeHL, cluster)
	_ = exec.Command("chown", "-R", "nodeop:nodeop", homeHL, prof.DataPath).Run()
	unitPath := filepath.Join("/etc/systemd/system", prof.ServiceUnit)

	return os.WriteFile(unitPath, []byte(renderHyperliquidUnit(env, visorNamed, homeHL, req, prof)), 0o644)
}

func ensureHyperliquidGPG() error {
	url := "https://raw.githubusercontent.com/hyperliquid-dex/node/main/pub_key.asc"
	tmp, err := os.MkdirTemp("", "hl-gpg-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)
	keyPath := filepath.Join(tmp, "pub_key.asc")
	if err := downloadFile(url, keyPath); err != nil {
		return err
	}
	_ = exec.Command("gpg", "--import", keyPath).Run()
	_ = exec.Command("sudo", "-u", "nodeop", "gpg", "--import", keyPath).Run()

	return nil
}
