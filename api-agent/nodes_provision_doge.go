package main

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// Dogecoin provision — stock dogecoind IBD (same shape as bitcoin, rpcuser auth).

func provisionDogeNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
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
	data := prof.DataPath
	stateDir := fmt.Sprintf("/var/lib/rpcnode/doge-%s", env)

	for _, d := range []string{opt, etc, data, stateDir, "/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system"} {
		if d == "" {
			continue
		}
		if err := os.MkdirAll(d, 0o755); err != nil {
			return nil, fmt.Errorf("mkdir %s: %w", d, err)
		}
		steps = append(steps, "mkdir "+d)
	}
	if err := os.Remove(filepath.Join(stateDir, "lifecycle-progress.json")); err == nil {
		steps = append(steps, "reset lifecycle-progress")
	}

	bin, err := ensureDogecoindInstalled(opt)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "dogecoind="+bin)
	_ = ensureNodeopUser()

	rpcUser, rpcPass, confPath, err := ensureDogeConf(prof, req)
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

	sysListen := systemAgentListenPort("doge", env)

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (doge)
%sTRON_NETWORK=doge
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
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/doge-%s.json
TRON_SERVICE=doge-%s
TRON_SNAPSHOT_ENABLED=0
BITCOIN_RPC_USER=%s
BITCOIN_RPC_PASSWORD=%s
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		productEnvVars(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, req.P2PPort,
		sysListen, sysListen, stateDir,
		opt, etc, data, stateDir, stateDir, env, env,
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

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-doge-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-doge-%s.service", env)
	nodeUnitName := fmt.Sprintf("doge-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (doge/%s) — Go RPC :%d + Agent API :%d → dogecoind :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=doge
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
Description=RpcNode per-node system-agent (doge/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=doge
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

	if err := os.WriteFile(filepath.Join("/etc/systemd/system", apiUnitName), []byte(apiUnit), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", sysUnitName), []byte(sysUnit), 0o644); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+apiUnitName, "wrote "+sysUnitName)

	nodeUnitPath := filepath.Join("/etc/systemd/system", nodeUnitName)
	if err := os.WriteFile(nodeUnitPath, []byte(renderDogecoindUnit(prof, confPath)), 0o644); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+nodeUnitPath)

	agentURL := resolvePublicAgentURL(req.AgentPort)
	inst := map[string]any{
		"id":             "doge-" + env,
		"network":        "doge",
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
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "doge-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "doge-"+env+".json"), inst); err != nil {
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
		"network":        "doge",
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
		"message":        "doge per-node agents written; unit activation scheduled (Server agent left running)",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "doge-"+env+".json"),
	}, nil
}

func activateDogeUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-doge-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-doge-%s.service", env)
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

func ensureDogeConf(prof networkPortProfile, req nodeProvisionRequest) (rpcUser, rpcPass, confPath string, err error) {
	etc := prof.EtcPath
	data := prof.DataPath
	if etc == "" {
		etc = fmt.Sprintf("/etc/doge/%s", normalizeEnv(prof.Env))
	}
	if data == "" {
		data = fmt.Sprintf("/data/doge/%s", normalizeEnv(prof.Env))
	}
	for _, d := range []string{etc, data} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return "", "", "", fmt.Errorf("mkdir %s: %w", d, err)
		}
	}

	confPath = filepath.Join(etc, "dogecoin.conf")
	rpcUser = "rpcnode"
	rpcPass = randomHex(24)

	// Preserve existing rpcpassword when re-provisioning.
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

	var b strings.Builder
	b.WriteString("# Generated by rpcnode provision — doge\n")
	b.WriteString("server=1\n")
	b.WriteString("txindex=1\n")
	b.WriteString("prune=0\n")
	b.WriteString("disablewallet=1\n")
	b.WriteString("daemon=0\n")
	fmt.Fprintf(&b, "datadir=%s\n", data)
	fmt.Fprintf(&b, "dbcache=%d\n", dbcache)
	b.WriteString("rpcthreads=32\n")
	b.WriteString("rpcworkqueue=512\n")
	b.WriteString("maxconnections=125\n")
	if prof.ChainFlag != "" {
		b.WriteString(prof.ChainFlag + "\n")
	}
	// Same Core rule as bitcoin/dash: port/rpcport under [test]/[regtest].
	if sec := bitcoinConfNetworkSection(prof.Env); sec != "" {
		b.WriteString("\n[" + sec + "]\n")
	}
	fmt.Fprintf(&b, "port=%d\n", p2p)
	fmt.Fprintf(&b, "rpcport=%d\n", rpc)
	b.WriteString("rpcbind=127.0.0.1\n")
	b.WriteString("rpcallowip=127.0.0.1\n")
	fmt.Fprintf(&b, "rpcuser=%s\n", rpcUser)
	fmt.Fprintf(&b, "rpcpassword=%s\n", rpcPass)

	if err := os.WriteFile(confPath, []byte(b.String()), 0o640); err != nil {
		return "", "", confPath, err
	}
	_ = exec.Command("chown", "-R", "nodeop:nodeop", etc, data).Run()
	_ = exec.Command("chown", "root:nodeop", confPath).Run()
	_ = os.Chmod(confPath, 0o640)

	return rpcUser, rpcPass, confPath, nil
}

func renderDogecoindUnit(prof networkPortProfile, confPath string) string {
	bin := resolveDogecoindBinary(prof.OptPath)

	return fmt.Sprintf(`[Unit]
Description=Dogecoin Core (%s) — RpcNode
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
ExecStart=%s -conf=%s
# No ExecStop: panel remove calls dogecoin-cli stop, then SIGKILL.
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
`, prof.Env, bin, confPath)
}

func resolveDogecoindBinary(optPath string) string {
	for _, cand := range []string{
		filepath.Join(optPath, "bin", "dogecoind"),
		"/opt/doge/bin/dogecoind",
		"/usr/local/bin/dogecoind",
	} {
		if fileExists(cand) {
			return cand
		}
	}

	return filepath.Join(optPath, "bin", "dogecoind")
}

func resolveDogecoinCLI(optPath, dogecoindBin string) string {
	for _, cand := range []string{
		filepath.Join(filepath.Dir(dogecoindBin), "dogecoin-cli"),
		filepath.Join(optPath, "bin", "dogecoin-cli"),
		"/usr/local/bin/dogecoin-cli",
	} {
		if fileExists(cand) {
			return cand
		}
	}

	return filepath.Join(filepath.Dir(dogecoindBin), "dogecoin-cli")
}

func ensureDogecoindInstalled(optPath string) (string, error) {
	if bin := resolveDogecoindBinary(optPath); fileExists(bin) {
		return bin, nil
	}
	if p, err := exec.LookPath("dogecoind"); err == nil && p != "" {
		return p, nil
	}

	ver := envOr("DOGECOIN_VERSION", "1.14.9")
	arch := "x86_64-linux-gnu"
	switch runtimeGOARCH() {
	case "arm64", "aarch64":
		arch = "aarch64-linux-gnu"
	}
	name := fmt.Sprintf("dogecoin-%s-%s.tar.gz", ver, arch)
	url := preferVendoredArtifact("doge", "mainnet",
		fmt.Sprintf("https://github.com/dogecoin/dogecoin/releases/download/v%s/%s", ver, name))
	tmp := filepath.Join(os.TempDir(), name)
	logDownload("GET", url, "doge dest="+tmp)
	destBin := filepath.Join(optPath, "bin")
	if err := os.MkdirAll(destBin, 0o755); err != nil {
		return "", fmt.Errorf("mkdir %s: %w", destBin, err)
	}

	extractDir := filepath.Join(os.TempDir(), "rpcnode-doge-"+ver)
	_ = os.RemoveAll(extractDir)
	cmd := exec.Command("bash", "-lc", fmt.Sprintf(
		`set -euo pipefail
if ! command -v curl >/dev/null; then echo "curl required to fetch dogecoind" >&2; exit 1; fi
curl -fsSL --connect-timeout 30 --max-time 600 -o %q %q
mkdir -p %q
tar -xzf %q -C %q
install -m 755 %q/dogecoin-%s/bin/dogecoind %q/dogecoind
install -m 755 %q/dogecoin-%s/bin/dogecoin-cli %q/dogecoin-cli
rm -rf %q %q
`, tmp, url, extractDir, tmp, extractDir, extractDir, ver, destBin, extractDir, ver, destBin, extractDir, tmp))
	out, err := cmd.CombinedOutput()
	logDownloadDone("GET", url, "doge dest="+tmp, out, err)
	if err != nil {
		return "", fmt.Errorf("install dogecoind %s: %v (%s)", ver, err, strings.TrimSpace(string(out)))
	}
	bin := filepath.Join(destBin, "dogecoind")
	if !fileExists(bin) {
		return "", fmt.Errorf("dogecoind missing after install at %s", bin)
	}

	return bin, nil
}

func randomHex(nBytes int) string {
	b := make([]byte, nBytes)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("%d", time.Now().UnixNano())
	}

	return hex.EncodeToString(b)
}
