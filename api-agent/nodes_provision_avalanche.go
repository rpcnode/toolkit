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

// Avalanche provision — native avalanchego (systemd), C-Chain archive product RPC.
// Config: /etc/avalanche/<env>/config.json + configs/chains/C/config.json.

func provisionAvalancheNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
	env := normalizeAvalancheEnv(req.Env)
	req.Env = env
	steps := []string{}

	if prof.NodeHTTP > 0 {
		req.NodeHTTPPort = prof.NodeHTTP
	}
	if prof.P2P > 0 {
		req.P2PPort = prof.P2P
	}

	opt := prof.OptPath
	etc := prof.EtcPath
	defaultData := prof.DataPath
	chainDir, snapshotsDir := resolveAvalancheDiskPaths(req, env, defaultData)
	data := chainDir
	stateDir := fmt.Sprintf("/var/lib/rpcnode/avalanche-%s", env)
	logDir := fmt.Sprintf("/var/log/avalanche/%s", env)
	metricsPort := prof.Metrics
	if metricsPort <= 0 {
		if env == "fuji" {
			metricsPort = 9691
		} else {
			metricsPort = 9690
		}
	}

	for _, d := range []string{
		opt, etc, data, snapshotsDir, stateDir, logDir,
		filepath.Join(data, "db"),
		filepath.Join(etc, "configs", "chains", "C"),
		"/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system",
	} {
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

	bin, err := ensureAvalancheGoInstalled(opt)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "avalanchego="+bin)
	_ = ensureNodeopUser()

	cfgPath, err := writeAvalancheNodeConfig(prof, req, data, snapshotsDir, logDir, metricsPort)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+cfgPath)

	cChainPath, err := writeAvalancheCChainConfig(etc)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+cChainPath)

	tipURL := avalanchePublicTipRPC(env)
	if err := os.WriteFile(filepath.Join(etc, "public_tip.url"), []byte(tipURL+"\n"), 0o644); err != nil {
		return nil, err
	}
	_ = os.WriteFile(filepath.Join(etc, "disk_layout.json"), mustJSON(map[string]any{
		"roles": map[string]string{
			"chain":     data,
			"snapshots": snapshotsDir,
		},
	}), 0o644)
	steps = append(steps, fmt.Sprintf("disk_layout chain=%s snapshots=%s", data, snapshotsDir))

	binDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))
	if token == "" {
		if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
			token = strings.TrimSpace(string(b))
		}
	}

	sysListen := systemAgentListenPort("avalanche", env)

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (avalanche)
%sTRON_NETWORK=avalanche
TRON_NODE_HTTP_HOST=127.0.0.1
TRON_NODE_HTTP_PORT=%d
TRON_P2P_PORT=%d
TRON_METRICS_PORT=%d
TRON_SYSTEM_AGENT_LISTEN=127.0.0.1:%d
TRON_SYSTEM_AGENT_URL=http://127.0.0.1:%d
TRON_STATE_DIR=%s
TRON_OPT=%s
TRON_ETC=%s
TRON_DATA=%s
TRON_AGENT_STATE=%s/agent-state.json
TRON_INSTANCE_FILE=%s/INSTANCE.json
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/avalanche-%s.json
TRON_SERVICE=avalanche-%s
TRON_SNAPSHOT_ENABLED=0
AVALANCHE_PUBLIC_TIP_RPC=%s
AVALANCHE_SNAPSHOTS_DIR=%s
AVALANCHE_NETWORK_ID=%s
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		productEnvVars(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, req.P2PPort, metricsPort,
		sysListen, sysListen, stateDir,
		opt, etc, data, stateDir, stateDir, env, env,
		tipURL, snapshotsDir, avalancheNetworkID(env),
		toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+envPath)

	apiBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-system-agent")

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-avalanche-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-avalanche-%s.service", env)
	nodeUnitName := fmt.Sprintf("avalanche-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (avalanche/%s) — Go RPC :%d + Agent API :%d → C-Chain :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=avalanche
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
Description=RpcNode per-node system-agent (avalanche/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=avalanche
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
	if err := os.WriteFile(nodeUnitPath, []byte(renderAvalancheNodeUnit(prof, cfgPath)), 0o644); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+nodeUnitPath)

	_ = exec.Command("chown", "-R", "nodeop:nodeop", etc, data, opt, snapshotsDir, logDir).Run()

	agentURL := resolvePublicAgentURL(req.AgentPort)
	inst := map[string]any{
		"id":             "avalanche-" + env,
		"network":        "avalanche",
		"env":            env,
		"name":           req.Name,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"metrics_port":   metricsPort,
		"watch_slug":     prof.WatchSlug,
		"agent_url":      agentURL,
		"data_dir":       data,
		"snapshots_dir":  snapshotsDir,
		"etc_dir":        etc,
		"opt_dir":        opt,
		"units":          []string{nodeUnitName, apiUnitName, sysUnitName},
		"created_at":     time.Now().UTC().Format(time.RFC3339),
		"hostname":       hostnameOrEmpty(),
	}
	if err := writeJSONFile(filepath.Join(stateDir, "INSTANCE.json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "avalanche-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "avalanche-"+env+".json"), inst); err != nil {
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
		"network":        "avalanche",
		"env":            env,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"metrics_port":   metricsPort,
		"rpc_mode":       "go_proxy",
		"agent_url":      agentURL,
		"etc_dir":        etc,
		"data_dir":       data,
		"snapshots_dir":  snapshotsDir,
		"units":          []string{nodeUnitName, apiUnitName, sysUnitName},
		"units_started":  false,
		"status":         "provisioned",
		"snapshot":       false,
		"lifecycle":      "ports→install→start→run(bootstrap+catch-up)",
		"message":        "avalanche per-node agents written; unit activation scheduled (Server agent left running)",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "avalanche-"+env+".json"),
	}, nil
}

func activateAvalancheUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeAvalancheEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-avalanche-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-avalanche-%s.service", env)
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

func resolveAvalancheDiskPaths(req nodeProvisionRequest, env, defaultData string) (chainDir, snapshotsDir string) {
	env = normalizeAvalancheEnv(env)
	chainDir = defaultData
	if chainDir == "" {
		chainDir = fmt.Sprintf("/data/avalanche/%s", env)
	}
	snapshotsDir = filepath.Join(chainDir, "snapshots")

	if req.DiskLayout == nil {
		return chainDir, snapshotsDir
	}
	dl := req.DiskLayout
	if dl.Roles != nil {
		if r, ok := dl.Roles["chain"]; ok {
			if d := strings.TrimSpace(r.Dir); d != "" {
				chainDir = d
			} else if m := strings.TrimSpace(r.Mount); m != "" {
				chainDir = avalanchePathFromRole(m, env, "")
			}
		}
		if r, ok := dl.Roles["snapshots"]; ok {
			if d := strings.TrimSpace(r.Dir); d != "" {
				snapshotsDir = d
			} else if m := strings.TrimSpace(r.Mount); m != "" {
				snapshotsDir = avalanchePathFromRole(m, env, "snapshots")
			}
		}
	}
	if d := strings.TrimSpace(dl.LedgerDir); d != "" {
		chainDir = d
	}
	if d := strings.TrimSpace(dl.SnapshotsDir); d != "" {
		snapshotsDir = d
	}
	if chainDir == "" {
		chainDir = defaultData
	}
	if snapshotsDir == "" {
		snapshotsDir = filepath.Join(chainDir, "snapshots")
	}

	return chainDir, snapshotsDir
}

// avalanchePathFromRole — absolute data path, or mount root → /<mount>/avalanche/<env>[/role].
func avalanchePathFromRole(value, env, role string) string {
	v := strings.TrimSpace(value)
	if v == "" {
		return ""
	}
	base := filepath.Base(v)
	if strings.Contains(v, "/avalanche/") || base == "avalanche" || strings.HasSuffix(v, "/avalanche") {
		return v
	}
	// Treat as mount point.
	if role == "" {
		return filepath.Join(v, "avalanche", env)
	}

	return filepath.Join(v, "avalanche", env, role)
}

func writeAvalancheNodeConfig(prof networkPortProfile, req nodeProvisionRequest, data, snapshotsDir, logDir string, metricsPort int) (string, error) {
	etc := prof.EtcPath
	if etc == "" {
		etc = fmt.Sprintf("/etc/avalanche/%s", normalizeAvalancheEnv(prof.Env))
	}
	rpc := req.NodeHTTPPort
	if rpc <= 0 {
		rpc = prof.NodeHTTP
	}
	p2p := req.P2PPort
	if p2p <= 0 {
		p2p = prof.P2P
	}
	chainCfgDir := filepath.Join(etc, "configs", "chains")
	dbDir := filepath.Join(data, "db")

	doc := map[string]any{
		"network-id":          avalancheNetworkID(prof.Env),
		"http-host":           "127.0.0.1",
		"http-port":           rpc,
		"staking-port":        p2p,
		"data-dir":            data,
		"db-dir":              dbDir,
		"chain-config-dir":    chainCfgDir,
		"log-dir":             logDir,
		"api-metrics-enabled": true,
		// Reserved catalog Metrics port — scrape primary HTTP /ext/metrics (loopback).
		// Keep field for ops inventory; avalanchego serves metrics on http-port.
		"http-allowed-hosts": []string{"*"},
	}
	_ = metricsPort
	_ = snapshotsDir

	raw, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return "", err
	}
	path := filepath.Join(etc, "config.json")
	if err := os.WriteFile(path, append(raw, '\n'), 0o644); err != nil {
		return "", err
	}

	return path, nil
}

func writeAvalancheCChainConfig(etc string) (string, error) {
	// Full-history archive C-Chain (RpcNode product MUST).
	doc := map[string]any{
		"pruning-enabled":    false,
		"state-sync-enabled": false,
		"eth-apis": []string{
			"eth",
			"eth-filter",
			"net",
			"web3",
			"internal-eth",
			"internal-blockchain",
			"internal-transaction",
			"internal-tx-pool",
			"internal-account",
			"debug-tracer",
		},
	}
	raw, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return "", err
	}
	path := filepath.Join(etc, "configs", "chains", "C", "config.json")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return "", err
	}
	if err := os.WriteFile(path, append(raw, '\n'), 0o644); err != nil {
		return "", err
	}

	return path, nil
}

func renderAvalancheNodeUnit(prof networkPortProfile, cfgPath string) string {
	bin := resolveAvalancheGoBinary(prof.OptPath)

	return fmt.Sprintf(`[Unit]
Description=AvalancheGo fullnode (%s) — RpcNode C-Chain archive
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
ExecStart=%s --config-file=%s
TimeoutStopSec=50
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
`, prof.Env, bin, cfgPath)
}

func resolveAvalancheGoBinary(optPath string) string {
	for _, cand := range []string{
		filepath.Join(optPath, "bin", "avalanchego"),
		"/opt/avalanche/bin/avalanchego",
		"/usr/local/bin/avalanchego",
	} {
		if fileExists(cand) {
			return cand
		}
	}

	return filepath.Join(optPath, "bin", "avalanchego")
}

func ensureAvalancheGoInstalled(optPath string) (string, error) {
	if bin := resolveAvalancheGoBinary(optPath); fileExists(bin) {
		return bin, nil
	}
	if p, err := exec.LookPath("avalanchego"); err == nil && p != "" {
		return p, nil
	}

	ver := avalancheReleaseVersion()
	url := avalancheReleaseTarballURL(ver)
	name := filepath.Base(url)
	tmp := filepath.Join(os.TempDir(), name)
	logDownload("GET", url, "avalanche dest="+tmp)
	destBin := filepath.Join(optPath, "bin")
	if err := os.MkdirAll(destBin, 0o755); err != nil {
		return "", fmt.Errorf("mkdir %s: %w", destBin, err)
	}

	extractDir := filepath.Join(os.TempDir(), "rpcnode-avalanche-"+ver)
	_ = os.RemoveAll(extractDir)
	cmd := exec.Command("bash", "-lc", fmt.Sprintf(
		`set -euo pipefail
if ! command -v curl >/dev/null; then echo "curl required to fetch avalanchego" >&2; exit 1; fi
curl -fsSL --connect-timeout 30 --max-time 900 -o %q %q
mkdir -p %q
tar -xzf %q -C %q
SRC=""
if [ -x %q/avalanchego ]; then SRC=%q; fi
if [ -z "$SRC" ]; then
  for d in %q %q/*; do
    if [ -x "$d/avalanchego" ]; then SRC="$d"; break; fi
    if [ -x "$d/bin/avalanchego" ]; then SRC="$d/bin"; break; fi
  done
fi
if [ -z "$SRC" ] || [ ! -x "$SRC/avalanchego" ]; then
  echo "avalanchego binary not found in tarball" >&2
  find %q -maxdepth 3 -type f | head -40 >&2
  exit 1
fi
install -m 755 "$SRC/avalanchego" %q/avalanchego
# plugins dir if present
if [ -d "$SRC/plugins" ]; then
  mkdir -p %q/../plugins
  cp -a "$SRC/plugins/." %q/../plugins/ 2>/dev/null || true
fi
rm -rf %q %q
`, tmp, url, extractDir, tmp, extractDir,
		extractDir, extractDir, extractDir, extractDir, extractDir,
		destBin, destBin, destBin, extractDir, tmp))
	out, err := cmd.CombinedOutput()
	logDownloadDone("GET", url, "avalanche dest="+tmp, out, err)
	if err != nil {
		return "", fmt.Errorf("install avalanchego %s: %v (%s)", ver, err, strings.TrimSpace(string(out)))
	}
	bin := filepath.Join(destBin, "avalanchego")
	if !fileExists(bin) {
		return "", fmt.Errorf("avalanchego missing after install at %s", bin)
	}

	return bin, nil
}

func mustJSON(v any) []byte {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return []byte("{}\n")
	}
	return append(b, '\n')
}
