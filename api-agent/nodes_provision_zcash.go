package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

// Zcash provision — Zebra (zebrad). zcashd 6.20.0 reached EOS halt 2026-07-18
// (height 3417100) and refuses to restart; mainnet follows NU6.3 on Zebra only.
// Canonical: deploy/nodes/zcash/DESIGN.md

func provisionZcashNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
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
	stateDir := fmt.Sprintf("/var/lib/rpcnode/zcash-%s", env)

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

	bin, err := ensureZebradInstalled(opt)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "zebrad="+bin)
	_ = ensureNodeopUser()

	confPath, err := ensureZebradConf(prof, req)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+confPath)
	// Drop stale zcashd conf so Config UI / operators do not chase a dead client.
	oldConf := filepath.Join(etc, "zcash.conf")
	if fileExists(oldConf) {
		_ = os.Rename(oldConf, oldConf+".zcashd-eol.bak")
		steps = append(steps, "retired "+oldConf+" (zcashd EOL)")
	}

	binDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))
	if token == "" {
		if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
			token = strings.TrimSpace(string(b))
		}
	}

	sysListen := systemAgentListenPort("zcash", env)

	// Loopback RPC with cookie auth disabled — Go proxy → 127.0.0.1 needs no Basic auth.
	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (zcash / zebrad)
%sTRON_NETWORK=zcash
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
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/zcash-%s.json
TRON_SERVICE=zcash-%s
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

	apiBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-system-agent")

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-zcash-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-zcash-%s.service", env)
	nodeUnitName := fmt.Sprintf("zcash-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (zcash/%s) — Go RPC :%d + Agent API :%d → zebrad :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=zcash
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
Description=RpcNode per-node system-agent (zcash/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=zcash
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
	if err := os.WriteFile(nodeUnitPath, []byte(renderZebradUnit(prof, confPath)), 0o644); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+nodeUnitPath)

	agentURL := resolvePublicAgentURL(req.AgentPort)
	inst := map[string]any{
		"id":             "zcash-" + env,
		"network":        "zcash",
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
		"client":         "zebrad",
		"units":          []string{nodeUnitName, apiUnitName, sysUnitName},
		"created_at":     time.Now().UTC().Format(time.RFC3339),
		"hostname":       hostnameOrEmpty(),
	}
	if err := writeJSONFile(filepath.Join(stateDir, "INSTANCE.json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "zcash-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "zcash-"+env+".json"), inst); err != nil {
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
		"network":        "zcash",
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
		"client":         "zebrad",
		"units":          []string{nodeUnitName, apiUnitName, sysUnitName},
		"units_started":  false,
		"status":         "provisioned",
		"snapshot":       false,
		"lifecycle":      "ports→install→start→run(sync)",
		"message":        "zcash zebrad per-node agents written; unit activation scheduled (Server agent left running)",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "zcash-"+env+".json"),
	}, nil
}

func activateZcashUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-zcash-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-zcash-%s.service", env)
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

func ensureZebradConf(prof networkPortProfile, req nodeProvisionRequest) (string, error) {
	etc := prof.EtcPath
	data := prof.DataPath
	if etc == "" {
		etc = fmt.Sprintf("/etc/zcash/%s", normalizeEnv(prof.Env))
	}
	if data == "" {
		data = fmt.Sprintf("/data/zcash/%s", normalizeEnv(prof.Env))
	}
	for _, d := range []string{etc, data} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return "", fmt.Errorf("mkdir %s: %w", d, err)
		}
	}

	confPath := filepath.Join(etc, "zebrad.toml")
	p2p := req.P2PPort
	if p2p <= 0 {
		p2p = prof.P2P
	}
	rpc := req.NodeHTTPPort
	if rpc <= 0 {
		rpc = prof.NodeHTTP
	}

	body := renderZebradToml(prof.Env, data, rpc, p2p)
	if err := os.WriteFile(confPath, []byte(body), 0o640); err != nil {
		return confPath, err
	}
	_ = exec.Command("chown", "-R", "nodeop:nodeop", etc, data).Run()
	_ = exec.Command("chown", "root:nodeop", confPath).Run()
	_ = os.Chmod(confPath, 0o640)

	return confPath, nil
}

func renderZebradToml(env, data string, rpcPort, p2pPort int) string {
	netName := "Mainnet"
	if normalizeEnv(env) == "testnet" {
		netName = "Testnet"
	}
	var b strings.Builder
	b.WriteString("# Generated by rpcnode provision — Zebra (zebrad)\n")
	b.WriteString("# zcashd EOL 2026-07-18; RpcNode fullnode = Zebra full state (no prune).\n\n")
	fmt.Fprintf(&b, "[network]\nnetwork = %q\nlisten_addr = \"0.0.0.0:%d\"\n\n", netName, p2pPort)
	fmt.Fprintf(&b, "[state]\ncache_dir = %q\n\n", data)
	fmt.Fprintf(&b, `[rpc]
listen_addr = "127.0.0.1:%d"
# Loopback-only: public clients use Go proxy. Cookie off so proxy needs no Basic auth.
enable_cookie_auth = false
parallel_cpu_threads = 0
`, rpcPort)

	return b.String()
}

func renderZebradUnit(prof networkPortProfile, confPath string) string {
	bin := resolveZebradBinary(prof.OptPath)

	return fmt.Sprintf(`[Unit]
Description=Zcash Zebra zebrad (%s) — RpcNode
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
ExecStart=%s --config %s start
TimeoutStopSec=120
KillMode=control-group
KillSignal=SIGTERM
FinalKillSignal=SIGKILL
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

func resolveZebradBinary(optPath string) string {
	for _, cand := range []string{
		filepath.Join(optPath, "bin", "zebrad"),
		"/opt/zcash/bin/zebrad",
		"/usr/local/bin/zebrad",
	} {
		if fileExists(cand) {
			return cand
		}
	}

	return filepath.Join(optPath, "bin", "zebrad")
}

func ensureZebradInstalled(optPath string) (string, error) {
	if bin := resolveZebradBinary(optPath); fileExists(bin) {
		return bin, nil
	}
	if p, err := exec.LookPath("zebrad"); err == nil && p != "" {
		return p, nil
	}

	ver := strings.TrimPrefix(envOr("ZEBRA_VERSION", "6.3.0"), "v")
	arch := "x86_64"
	switch runtime.GOARCH {
	case "arm64", "aarch64":
		arch = "aarch64"
	}
	name := fmt.Sprintf("zebrad-%s-%s-unknown-linux-gnu.tar.gz", ver, arch)
	url := preferVendoredArtifact("zcash", "mainnet", envOr("ZEBRA_TARBALL_URL",
		fmt.Sprintf("https://github.com/ZcashFoundation/zebra/releases/download/v%s/%s", ver, name)))
	tmp := filepath.Join(os.TempDir(), name)
	destBin := filepath.Join(optPath, "bin")
	if err := os.MkdirAll(destBin, 0o755); err != nil {
		return "", fmt.Errorf("mkdir %s: %w", destBin, err)
	}

	extractDir := filepath.Join(os.TempDir(), "rpcnode-zebra-"+ver)
	_ = os.RemoveAll(extractDir)
	cmd := exec.Command("bash", "-lc", fmt.Sprintf(
		`set -euo pipefail
if ! command -v curl >/dev/null; then echo "curl required to fetch zebrad" >&2; exit 1; fi
curl -fsSL --connect-timeout 30 --max-time 900 -A 'rpcnode-toolkit' -o %q %q
mkdir -p %q
tar -xzf %q -C %q
SRC=""
for cand in %q/zebrad %q/bin/zebrad %q/*/zebrad %q/*/bin/zebrad; do
  if [ -x "$cand" ] || [ -f "$cand" ]; then SRC="$cand"; break; fi
done
if [ -z "$SRC" ]; then
  echo "zebrad binary not found in tarball" >&2
  find %q -maxdepth 3 -type f | head -40 >&2
  exit 1
fi
install -m 755 "$SRC" %q/zebrad
rm -rf %q %q
`, tmp, url, extractDir, tmp, extractDir,
		extractDir, extractDir, extractDir, extractDir, extractDir,
		destBin, extractDir, tmp))
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("install zebrad %s: %v (%s)", ver, err, strings.TrimSpace(string(out)))
	}
	bin := filepath.Join(destBin, "zebrad")
	if !fileExists(bin) {
		return "", fmt.Errorf("zebrad missing after install at %s", bin)
	}

	return bin, nil
}

// Legacy helpers kept for remove of half-migrated hosts that still have zcashd.
func resolveZcashdBinary(optPath string) string {
	for _, cand := range []string{
		filepath.Join(optPath, "bin", "zcashd"),
		"/opt/zcash/bin/zcashd",
		"/usr/local/bin/zcashd",
	} {
		if fileExists(cand) {
			return cand
		}
	}

	return filepath.Join(optPath, "bin", "zcashd")
}

func resolveZcashCLI(optPath, zcashdBin string) string {
	for _, cand := range []string{
		filepath.Join(filepath.Dir(zcashdBin), "zcash-cli"),
		filepath.Join(optPath, "bin", "zcash-cli"),
		"/usr/local/bin/zcash-cli",
	} {
		if fileExists(cand) {
			return cand
		}
	}

	return filepath.Join(filepath.Dir(zcashdBin), "zcash-cli")
}
