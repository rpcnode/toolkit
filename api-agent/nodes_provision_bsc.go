package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// BSC provision on the unified Server agent.
// bnb-chain/bsc geth fork (Parlia) — TRON_NETWORK=bsc, not a separate agent product.
// Full pruned node (--syncmode=full), not archive / not ethereum EL+CL.

func provisionBSCNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
	env := req.Env
	steps := []string{}
	cluster := lookupBSCNetwork(env)

	if prof.NodeHTTP > 0 {
		req.NodeHTTPPort = prof.NodeHTTP
	}
	if prof.P2P > 0 {
		req.P2PPort = prof.P2P
	}

	opt := prof.OptPath
	etc := prof.EtcPath
	data := resolveNetworkRoleDir(req, "bsc", env, "chaindata", prof.DataPath)
	snapDir := resolveNetworkRoleDir(req, "bsc", env, "snapshots", filepath.Join(prof.DataPath, "snapshots"))
	stateDir := fmt.Sprintf("/var/lib/rpcnode/bsc-%s", env)
	binDir := filepath.Join(opt, "bin")

	for _, d := range []string{opt, binDir, etc, data, snapDir, stateDir, "/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system"} {
		if d == "" {
			continue
		}
		if err := os.MkdirAll(d, 0o755); err != nil {
			return nil, fmt.Errorf("mkdir %s: %w", d, err)
		}
		steps = append(steps, "mkdir "+d)
	}

	gethBin, err := ensureBSCGethInstalled(opt)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "bsc-geth="+gethBin)

	_ = ensureNodeopUser()

	genesisPath, configPath, err := ensureBSCGenesisAndConfig(etc, cluster)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "genesis="+genesisPath, "config="+configPath)

	if err := ensureBSCDatadirInit(gethBin, data, genesisPath); err != nil {
		return nil, err
	}
	steps = append(steps, "datadir_init="+data)

	snapUnitPath, snapScript, err := ensureBSCSnapshotUnit(prof, snapDir)
	if err != nil {
		return nil, fmt.Errorf("bsc snapshot unit: %w", err)
	}
	steps = append(steps, "snapshot_unit="+snapUnitPath, "snapshot_script="+snapScript)

	rpcBinDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))
	if token == "" {
		if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
			token = strings.TrimSpace(string(b))
		}
	}

	sysListen := systemAgentListenPort("bsc", env)

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (bsc)
# Per-node agent: Go RPC :%d → bsc-geth :%d; Agent API :%d
%sTRON_NETWORK=bsc
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
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/bsc-%s.json
TRON_SERVICE=bsc-%s
TRON_SNAPSHOT_ENABLED=1
TRON_SNAPSHOT_URL=%s
TRON_SNAPSHOT_SERVICE=bsc-%s-snapshot
TRON_SNAPSHOT_LOG=/var/log/bsc/%s-snapshot.log
TRON_SNAPSHOT_MARKER=%s/.snapshot-ready
TRON_SNAPSHOT_STATE=%s/.snapshot-state.json
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		req.PublicPort, req.NodeHTTPPort, req.AgentPort,
		productEnvVars(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, req.P2PPort,
		sysListen, sysListen, stateDir,
		opt, etc, data, stateDir, stateDir, env, env,
		bscSnapshotsRepo, env, env, data, data,
		toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+envPath)

	apiBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-system-agent")

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-bsc-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-bsc-%s.service", env)
	nodeUnitName := fmt.Sprintf("bsc-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (bsc/%s) — Go RPC :%d + Agent API :%d → bsc-geth :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=bsc
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
Description=RpcNode per-node system-agent (bsc/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=bsc
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

	nodeUnit := renderBSCUnit(env, gethBin, data, configPath, req, prof)

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
		"id":             "bsc-" + env,
		"network":        "bsc",
		"env":            env,
		"name":           req.Name,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"watch_slug":     watch,
		"chain_id":       cluster.ChainID,
		"agent_url":      agentURL,
		"data_dir":       data,
		"etc_dir":        etc,
		"opt_dir":        opt,
		"units":          []string{nodeUnitName, apiUnitName, sysUnitName, fmt.Sprintf("bsc-%s-snapshot.service", env)},
		"created_at":     time.Now().UTC().Format(time.RFC3339),
		"hostname":       hostnameOrEmpty(),
	}
	if err := writeJSONFile(filepath.Join(stateDir, "INSTANCE.json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "bsc-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "bsc-"+env+".json"), inst); err != nil {
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
		"network":        "bsc",
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
		"units":          []string{nodeUnitName, apiUnitName, sysUnitName, fmt.Sprintf("bsc-%s-snapshot.service", env)},
		"units_started":  false,
		"status":         "provisioned",
		"snapshot":       true,
		"lifecycle":      "ports→install→snapshot(official)→start→run",
		"message":        "bsc per-node agents written; official snapshot unit ready (Server agent left running)",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "bsc-"+env+".json"),
	}, nil
}

func renderBSCUnit(
	env, bin, datadir, configPath string,
	req nodeProvisionRequest,
	prof networkPortProfile,
) string {
	rpcPort := req.NodeHTTPPort
	if rpcPort <= 0 {
		rpcPort = prof.NodeHTTP
	}
	p2p := req.P2PPort
	if p2p <= 0 {
		p2p = prof.P2P
	}
	cacheMB := bscGethCacheMB(env)
	configFlag := ""
	if strings.TrimSpace(configPath) != "" {
		configFlag = fmt.Sprintf(" \\\n  --config %s", configPath)
	}

	return fmt.Sprintf(`[Unit]
Description=BNB Smart Chain geth (%s, full) — RpcNode
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
ExecStart=%s \
  --datadir %s%s \
  --http --http.addr 127.0.0.1 --http.port %d \
  --http.api eth,net,web3,txpool,parlia \
  --http.vhosts localhost \
  --rpc.allow-unprotected-txs \
  --syncmode full \
  --gcmode full \
  --tries-verify-mode none \
  --cache %d \
  --maxpeers 100 \
  --rpc.batch-request-limit 2000 \
  --port %d
Restart=on-failure
RestartSec=15
TimeoutStopSec=600
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
LimitNOFILE=1048576
KillSignal=SIGINT

[Install]
WantedBy=multi-user.target
`, env, bin, datadir, configFlag, rpcPort, cacheMB, p2p)
}

func activateBSCUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-bsc-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-bsc-%s.service", env)
	// BSC per-node agents use 3999x — do NOT stop host Server agent (:39090).
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

func ensureBSCGoRPC(env string, publicPort int) error {
	env = normalizeEnv(env)
	if publicPort <= 0 {
		if p := lookupPortProfile("bsc", env); p.Public > 0 {
			publicPort = p.Public
		}
	}
	if publicPort > 0 && portOpenLocal(publicPort) {
		return nil
	}
	if err := activateBSCUnits(env); err != nil {
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
	unit := fmt.Sprintf("rpcnode-api-agent-bsc-%s.service", env)
	jOut, _ := exec.Command("journalctl", "-u", unit, "-n", "16", "--no-pager", "-o", "cat").CombinedOutput()
	msg := fmt.Sprintf("Go RPC :%d not listening after restart %s", publicPort, unit)
	if snip := strings.TrimSpace(string(jOut)); snip != "" {
		msg += " — " + snip
	}

	return fmt.Errorf("%s", msg)
}

func ensureBSCGethInstalled(optPath string) (string, error) {
	dest := filepath.Join(optPath, "bin", "geth")
	link := "/usr/local/bin/bsc-geth"
	if fileExists(dest) {
		_ = os.MkdirAll(filepath.Dir(link), 0o755)
		_ = os.Remove(link)
		_ = os.Symlink(dest, link)
		return dest, nil
	}
	for _, cand := range []string{link, "/opt/bsc/bin/geth", "/usr/local/bin/geth"} {
		if fileExists(cand) && strings.Contains(cand, "bsc") {
			_ = os.MkdirAll(filepath.Dir(dest), 0o755)
			if err := copyFile(cand, dest); err == nil {
				_ = os.Chmod(dest, 0o755)
				return dest, nil
			}
		}
	}
	if p, err := exec.LookPath("bsc-geth"); err == nil && p != "" {
		_ = os.MkdirAll(filepath.Dir(dest), 0o755)
		if err := copyFile(p, dest); err == nil {
			_ = os.Chmod(dest, 0o755)
			return dest, nil
		}
	}

	arch := runtimeGOARCH()
	asset := "geth_linux"
	if strings.Contains(arch, "aarch64") || strings.Contains(arch, "arm64") {
		asset = "geth-linux-arm64"
	}
	url := preferVendoredArtifact("bsc", envOr("BSC_NETWORK", "mainnet"),
		fmt.Sprintf("https://github.com/bnb-chain/bsc/releases/download/%s/%s", bscGethReleaseTag, asset))

	tmp, err := os.MkdirTemp("", "bsc-geth-*")
	if err != nil {
		return "", fmt.Errorf("bsc-geth temp: %w", err)
	}
	defer os.RemoveAll(tmp)

	binPath := filepath.Join(tmp, asset)
	if err := downloadFile(url, binPath); err != nil {
		return "", fmt.Errorf("bsc-geth download (%s): %w", url, err)
	}
	_ = os.MkdirAll(filepath.Dir(dest), 0o755)
	_ = exec.Command("install", "-m", "0755", binPath, dest).Run()
	if !fileExists(dest) {
		if err := copyFile(binPath, dest); err != nil {
			return "", fmt.Errorf("install bsc-geth to %s: %w", dest, err)
		}
		_ = os.Chmod(dest, 0o755)
	}
	_ = os.MkdirAll(filepath.Dir(link), 0o755)
	_ = os.Remove(link)
	_ = os.Symlink(dest, link)
	if fileExists(dest) {
		return dest, nil
	}

	return "", fmt.Errorf("bsc-geth not found and download/install failed from %s", url)
}

func ensureBSCGenesisAndConfig(etc string, cluster bscNetwork) (genesisPath, configPath string, err error) {
	genesisPath = filepath.Join(etc, "genesis.json")
	configPath = filepath.Join(etc, "config.toml")
	if fileExists(genesisPath) && fileExists(configPath) {
		return genesisPath, configPath, nil
	}

	url := preferVendoredConf("bsc", cluster.Env,
		fmt.Sprintf("https://github.com/bnb-chain/bsc/releases/download/%s/%s",
			bscGethReleaseTag, cluster.ZipAsset))

	tmp, err := os.MkdirTemp("", "bsc-cfg-*")
	if err != nil {
		return "", "", fmt.Errorf("bsc config temp: %w", err)
	}
	defer os.RemoveAll(tmp)

	zipPath := filepath.Join(tmp, cluster.ZipAsset)
	if err := downloadFile(url, zipPath); err != nil {
		return "", "", fmt.Errorf("bsc %s download: %w", cluster.ZipAsset, err)
	}
	out, err := exec.Command("unzip", "-o", zipPath, "-d", tmp).CombinedOutput()
	if err != nil {
		// Fallback: some hosts use bsdtar / busybox.
		out2, err2 := exec.Command("python3", "-c",
			fmt.Sprintf("import zipfile; zipfile.ZipFile(%q).extractall(%q)", zipPath, tmp),
		).CombinedOutput()
		if err2 != nil {
			return "", "", fmt.Errorf("extract %s: %v (%s); python: %v (%s)",
				cluster.ZipAsset, err, strings.TrimSpace(string(out)), err2, strings.TrimSpace(string(out2)))
		}
	}

	_ = os.MkdirAll(etc, 0o755)
	foundGenesis := findNamedFile(tmp, "genesis.json")
	foundConfig := findNamedFile(tmp, "config.toml")
	if foundGenesis == "" {
		return "", "", fmt.Errorf("genesis.json missing in %s", cluster.ZipAsset)
	}
	if err := copyFile(foundGenesis, genesisPath); err != nil {
		return "", "", err
	}
	if foundConfig != "" {
		if err := copyFile(foundConfig, configPath); err != nil {
			return "", "", err
		}
	} else {
		configPath = ""
	}
	_ = exec.Command("chown", "-R", "nodeop:nodeop", etc).Run()

	return genesisPath, configPath, nil
}

func findNamedFile(root, name string) string {
	var found string
	_ = filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil || info == nil || info.IsDir() {
			return nil
		}
		if strings.EqualFold(info.Name(), name) {
			found = path
			return filepath.SkipAll
		}
		return nil
	})

	return found
}

func ensureBSCDatadirInit(gethBin, datadir, genesisPath string) error {
	marker := filepath.Join(datadir, "geth", "chaindata")
	if dirExists(marker) || fileExists(filepath.Join(datadir, "geth", "LOCK")) {
		return nil
	}
	if err := os.MkdirAll(datadir, 0o755); err != nil {
		return err
	}
	cmd := exec.Command(gethBin, "--datadir", datadir, "init", genesisPath)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("bsc-geth init: %v (%s)", err, strings.TrimSpace(string(out)))
	}
	_ = exec.Command("chown", "-R", "nodeop:nodeop", datadir).Run()

	return nil
}

func dirExists(path string) bool {
	st, err := os.Stat(path)
	return err == nil && st.IsDir()
}

func rewriteBSCUnit(prof networkPortProfile, req nodeProvisionRequest) error {
	bin, err := ensureBSCGethInstalled(prof.OptPath)
	if err != nil {
		return err
	}
	cluster := lookupBSCNetwork(prof.Env)
	genesisPath, configPath, err := ensureBSCGenesisAndConfig(prof.EtcPath, cluster)
	if err != nil {
		return err
	}
	if err := ensureBSCDatadirInit(bin, prof.DataPath, genesisPath); err != nil {
		return err
	}
	unitPath := filepath.Join("/etc/systemd/system", prof.ServiceUnit)

	return os.WriteFile(unitPath, []byte(renderBSCUnit(prof.Env, bin, prof.DataPath, configPath, req, prof)), 0o644)
}
