package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// Ethereum Classic provision — Core-Geth archive fullnode (PoW, no CL).
// Canonical: deploy/nodes/etc/DESIGN.md

func provisionETCNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
	env := normalizeEnv(req.Env)
	if env != "mainnet" && env != "mordor" {
		return nil, fmt.Errorf("etc provision supports mainnet/mordor (got %s)", env)
	}
	steps := []string{}
	cluster := lookupETCNetwork(env)

	if prof.NodeHTTP > 0 && req.NodeHTTPPort <= 0 {
		req.NodeHTTPPort = prof.NodeHTTP
	}
	if req.P2PPort <= 0 && prof.P2P > 0 {
		req.P2PPort = prof.P2P
	}

	opt := prof.OptPath
	etc := prof.EtcPath
	data := prof.DataPath
	stateDir := fmt.Sprintf("/var/lib/rpcnode/etc-%s", env)
	binDir := filepath.Join(opt, "bin")

	for _, d := range []string{opt, binDir, etc, data, stateDir, "/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system"} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return nil, fmt.Errorf("mkdir %s: %w", d, err)
		}
		steps = append(steps, "mkdir "+d)
	}

	gethBin, err := ensureCoreGethInstalled(opt)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "core-geth="+gethBin)
	_ = ensureNodeopUser()

	rpcBinDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))
	if token == "" {
		if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
			token = strings.TrimSpace(string(b))
		}
	}

	sysListen := systemAgentListenPort("etc", env)

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (etc)
%sTRON_NETWORK=etc
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
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/etc-%s.json
TRON_SERVICE=etc-%s
TRON_SNAPSHOT_ENABLED=0
ETC_CHAIN_FLAG=%s
CORE_GETH_BIN=%s
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		productEnvVars(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, req.P2PPort,
		sysListen, sysListen, stateDir,
		opt, etc, data, stateDir, stateDir, env, env,
		cluster.ChainFlag, gethBin, toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+envPath)

	apiBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-system-agent")

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-etc-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-etc-%s.service", env)
	nodeUnitName := fmt.Sprintf("etc-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (etc/%s) — Go RPC :%d + Agent API :%d → Core-Geth :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=etc
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
Description=RpcNode per-node system-agent (etc/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=etc
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
		req.NodeHTTPPort, stateDir, toolkitDir, sysBin)

	nodeUnit := renderETCUnit(env, gethBin, data, etc, req, cluster)

	for _, item := range []struct{ name, body string }{
		{apiUnitName, apiUnit},
		{sysUnitName, sysUnit},
		{nodeUnitName, nodeUnit},
	} {
		if err := os.WriteFile(filepath.Join("/etc/systemd/system", item.name), []byte(item.body), 0o644); err != nil {
			return nil, err
		}
		steps = append(steps, "wrote "+item.name)
	}

	_ = exec.Command("chown", "-R", "nodeop:nodeop", opt, etc, data).Run()

	agentURL := resolvePublicAgentURL(req.AgentPort)
	watch := cluster.WatchSlug
	if watch == "" {
		watch = prof.WatchSlug
	}
	inst := map[string]any{
		"id":             "etc-" + env,
		"network":        "etc",
		"env":            env,
		"name":           req.Name,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"watch_slug":     watch,
		"chain_flag":     cluster.ChainFlag,
		"client":         "core-geth",
		"client_version": etcCoreGethVersion,
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
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "etc-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "etc-"+env+".json"), inst); err != nil {
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
		"network":        "etc",
		"env":            env,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"rpc_mode":       "go_proxy",
		"client":         "core-geth",
		"client_version": etcCoreGethVersion,
		"agent_url":      agentURL,
		"etc_dir":        etc,
		"data_dir":       data,
		"units":          []string{nodeUnitName, apiUnitName, sysUnitName},
		"units_started":  false,
		"status":         "provisioned",
		"snapshot":       false,
		"lifecycle":      "ports→install→start→run",
		"message":        "etc leaf agents written; Core-Geth archive unit ready",
		"steps":          steps,
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "etc-"+env+".json"),
	}, nil
}

func renderETCUnit(env, bin, datadir, etcDir string, req nodeProvisionRequest, cluster etcNetwork) string {
	rpcPort := req.NodeHTTPPort
	p2p := req.P2PPort
	cache := cluster.CacheMB
	if cache <= 0 {
		cache = 2048
	}
	return fmt.Sprintf(`[Unit]
Description=Ethereum Classic Core-Geth archive (%s) — RpcNode
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
EnvironmentFile=-%s/toolkit.env
ExecStart=%s %s \
  --datadir %s \
  --http --http.addr 127.0.0.1 --http.port %d \
  --http.api eth,net,web3,debug,txpool \
  --http.vhosts=* --http.corsdomain=* \
  --syncmode full --gcmode archive \
  --cache %d \
  --port %d \
  --maxpeers 50
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
`, env, etcDir, bin, cluster.ChainFlag, datadir, rpcPort, cache, p2p)
}

func activateETCUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-etc-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-etc-%s.service", env)
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

func ensureCoreGethInstalled(optPath string) (string, error) {
	dest := filepath.Join(optPath, "bin", "geth")
	if fileExists(dest) {
		return dest, nil
	}
	arch := runtimeGOARCH()
	asset := "core-geth-linux-" + etcCoreGethVersion + ".zip"
	if strings.Contains(arch, "arm64") || strings.Contains(arch, "aarch64") {
		asset = "core-geth-arm64-" + etcCoreGethVersion + ".zip"
	}
	url := fmt.Sprintf("https://github.com/etclabscore/core-geth/releases/download/%s/%s", etcCoreGethVersion, asset)

	tmp, err := os.MkdirTemp("", "core-geth-*")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(tmp)

	zipPath := filepath.Join(tmp, asset)
	if err := downloadFile(url, zipPath); err != nil {
		return "", fmt.Errorf("core-geth download (%s): %w", url, err)
	}
	out, err := exec.Command("unzip", "-o", zipPath, "-d", tmp).CombinedOutput()
	if err != nil {
		out2, err2 := exec.Command("python3", "-c",
			fmt.Sprintf("import zipfile; zipfile.ZipFile(%q).extractall(%q)", zipPath, tmp),
		).CombinedOutput()
		if err2 != nil {
			return "", fmt.Errorf("extract core-geth: %v (%s); python: %v (%s)",
				err, strings.TrimSpace(string(out)), err2, strings.TrimSpace(string(out2)))
		}
	}
	src := ""
	_ = filepath.Walk(tmp, func(p string, info os.FileInfo, err error) error {
		if err != nil || info == nil || info.IsDir() {
			return nil
		}
		if filepath.Base(p) == "geth" && src == "" {
			src = p
		}
		return nil
	})
	if src == "" {
		return "", fmt.Errorf("geth binary missing in %s", asset)
	}
	_ = os.MkdirAll(filepath.Dir(dest), 0o755)
	if err := copyFile(src, dest); err != nil {
		_ = exec.Command("install", "-m", "0755", src, dest).Run()
	}
	_ = os.Chmod(dest, 0o755)
	if !fileExists(dest) {
		return "", fmt.Errorf("install core-geth to %s failed", dest)
	}
	return dest, nil
}
