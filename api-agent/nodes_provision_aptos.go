package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// Aptos provision — native aptos-node (systemd), aptos-core release tarball + genesis/waypoint.
// Config: /etc/aptos/<env>/fullnode.yaml — REST loopback, inspection metrics loopback, full history.

func provisionAptosNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
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
	stateDir := fmt.Sprintf("/var/lib/rpcnode/aptos-%s", env)
	metricsPort := prof.Metrics
	if metricsPort <= 0 {
		if normalizeEnv(env) == "testnet" {
			metricsPort = 9102
		} else {
			metricsPort = 9101
		}
	}
	// aptos-node always binds admin_service (default 0.0.0.0:9102) even when disabled —
	// must be unique per env or testnet crash-loops on "Address already in use".
	adminPort := aptosAdminServicePort(metricsPort)

	stateDB, indexDir := resolveAptosDiskDirs(req, data, env)
	if err := ensureSolanaLayoutDirs(stateDB, indexDir); err != nil {
		return nil, err
	}
	steps = append(steps, fmt.Sprintf("disk_layout state=%s index=%s", stateDB, indexDir))

	for _, d := range []string{opt, etc, data, stateDir, stateDB, indexDir,
		"/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system"} {
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

	bin, err := ensureAptosNodeInstalled(opt, env)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "aptos-node="+bin)
	_ = ensureNodeopUser()

	genesisPath := filepath.Join(etc, "genesis.blob")
	if !fileExists(genesisPath) {
		if err := downloadNamedConf("aptos", env, "genesis.blob", aptosGenesisURL(env), genesisPath); err != nil {
			return nil, fmt.Errorf("download genesis.blob: %w", err)
		}
		steps = append(steps, "downloaded genesis.blob")
	} else {
		steps = append(steps, "genesis.blob present")
	}

	waypointPath := filepath.Join(etc, "waypoint.txt")
	if !fileExists(waypointPath) {
		if err := downloadNamedConf("aptos", env, "waypoint.txt", aptosWaypointURL(env), waypointPath); err != nil {
			return nil, fmt.Errorf("download waypoint.txt: %w", err)
		}
		steps = append(steps, "downloaded waypoint.txt")
	} else {
		steps = append(steps, "waypoint.txt present")
	}

	yamlPath, err := writeAptosFullnodeYAML(prof, req, genesisPath, waypointPath, stateDB, metricsPort, adminPort)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+yamlPath)

	tipURL := aptosPublicTipREST(env)
	if err := os.WriteFile(filepath.Join(etc, "public_tip.url"), []byte(tipURL+"\n"), 0o644); err != nil {
		return nil, err
	}

	binDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))
	if token == "" {
		if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
			token = strings.TrimSpace(string(b))
		}
	}

	sysListen := systemAgentListenPort("aptos", env)

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (aptos)
%sTRON_NETWORK=aptos
TRON_NODE_HTTP_HOST=127.0.0.1
TRON_NODE_HTTP_PORT=%d
TRON_P2P_PORT=%d
TRON_METRICS_PORT=%d
APTOS_ADMIN_PORT=%d
TRON_SYSTEM_AGENT_LISTEN=127.0.0.1:%d
TRON_SYSTEM_AGENT_URL=http://127.0.0.1:%d
TRON_STATE_DIR=%s
TRON_OPT=%s
TRON_ETC=%s
TRON_DATA=%s
APTOS_STATE_DIR=%s
APTOS_INDEX_DIR=%s
TRON_AGENT_STATE=%s/agent-state.json
TRON_INSTANCE_FILE=%s/INSTANCE.json
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/aptos-%s.json
TRON_SERVICE=aptos-%s
TRON_SNAPSHOT_ENABLED=0
APTOS_PUBLIC_TIP_REST=%s
APTOS_METRICS_URL=http://127.0.0.1:%d/metrics
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		productEnvVars(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, req.P2PPort, metricsPort, adminPort,
		sysListen, sysListen, stateDir,
		opt, etc, data, stateDB, indexDir, stateDir, stateDir, env, env,
		tipURL, metricsPort,
		toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+envPath)

	apiBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-system-agent")

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-aptos-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-aptos-%s.service", env)
	nodeUnitName := fmt.Sprintf("aptos-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (aptos/%s) — Go RPC :%d + Agent API :%d → aptos-node :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=aptos
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
Description=RpcNode per-node system-agent (aptos/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=aptos
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
	if err := os.WriteFile(nodeUnitPath, []byte(renderAptosNodeUnit(prof, yamlPath)), 0o644); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+nodeUnitPath)

	_ = exec.Command("chown", "-R", "nodeop:nodeop", etc, data, opt, stateDB, indexDir).Run()

	agentURL := resolvePublicAgentURL(req.AgentPort)
	inst := map[string]any{
		"id":             "aptos-" + env,
		"network":        "aptos",
		"env":            env,
		"name":           req.Name,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"metrics_port":   metricsPort,
		"state_dir":      stateDB,
		"index_dir":      indexDir,
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
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "aptos-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "aptos-"+env+".json"), inst); err != nil {
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
		"network":        "aptos",
		"env":            env,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"metrics_port":   metricsPort,
		"state_dir":      stateDB,
		"index_dir":      indexDir,
		"rpc_mode":       "go_proxy",
		"agent_url":      agentURL,
		"etc_dir":        etc,
		"data_dir":       data,
		"units":          []string{nodeUnitName, apiUnitName, sysUnitName},
		"units_started":  false,
		"status":         "provisioned",
		"snapshot":       false,
		"lifecycle":      "ports→install→start→run(catch-up from genesis)",
		"message":        "aptos per-node agents written; unit activation scheduled (Server agent left running)",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "aptos-"+env+".json"),
	}, nil
}

func activateAptosUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-aptos-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-aptos-%s.service", env)
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

// aptosAdminServicePort — unique loopback admin bind (default Aptos admin is :9102).
func aptosAdminServicePort(metricsPort int) int {
	if metricsPort <= 0 {
		return 9111
	}
	return metricsPort + 10
}

// aptosBackupServicePort — unique loopback storage backup bind (default :6186).
func aptosBackupServicePort(p2pPort int) int {
	if p2pPort <= 0 {
		return 6186
	}
	return p2pPort + 6
}

func writeAptosFullnodeYAML(prof networkPortProfile, req nodeProvisionRequest, genesisPath, waypointPath, stateDB string, metricsPort, adminPort int) (string, error) {
	etc := prof.EtcPath
	if etc == "" {
		etc = fmt.Sprintf("/etc/aptos/%s", normalizeEnv(prof.Env))
	}
	rpc := req.NodeHTTPPort
	if rpc <= 0 {
		rpc = prof.NodeHTTP
	}
	p2p := req.P2PPort
	if p2p <= 0 {
		p2p = prof.P2P
	}
	if stateDB == "" {
		stateDB = filepath.Join(prof.DataPath, "db")
	}
	if adminPort <= 0 {
		adminPort = aptosAdminServicePort(metricsPort)
	}
	backupPort := aptosBackupServicePort(p2p)

	// Full-history product: sync from genesis + disable all pruners.
	// Docs: https://aptos.dev/network/nodes/configure/state-sync#archival-pfns
	// Defaults that collide when multiple envs share a host:
	//   admin_service :9102 (binds even when disabled)
	//   storage.backup_service_address :6186
	body := fmt.Sprintf(`# Generated by rpcnode provision — aptos/%s
# Docs: https://aptos.dev/network/nodes/full-node/deployments/using-source-code
base:
  role: "full_node"
  data_dir: %q
  waypoint:
    from_file: %q

execution:
  genesis_file_location: %q

api:
  enabled: true
  address: "127.0.0.1:%d"

full_node_networks:
  - network_id: "public"
    discovery_method: "onchain"
    listen_address: "/ip4/0.0.0.0/tcp/%d"

inspection_service:
  address: "127.0.0.1"
  port: %d

admin_service:
  enabled: false
  address: "127.0.0.1"
  port: %d

state_sync:
  state_sync_driver:
    bootstrapping_mode: ExecuteOrApplyFromGenesis
    continuous_syncing_mode: ExecuteTransactionsOrApplyOutputs

storage:
  backup_service_address: "127.0.0.1:%d"
  storage_pruner_config:
    ledger_pruner_config:
      enable: false
    state_merkle_pruner_config:
      enable: false
    epoch_snapshot_pruner_config:
      enable: false
`, normalizeEnv(prof.Env), stateDB, waypointPath, genesisPath, rpc, p2p, metricsPort, adminPort, backupPort)

	path := filepath.Join(etc, "fullnode.yaml")
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		return "", err
	}

	return path, nil
}

func renderAptosNodeUnit(prof networkPortProfile, yamlPath string) string {
	bin := resolveAptosNodeBinary(prof.OptPath)

	return fmt.Sprintf(`[Unit]
Description=Aptos fullnode (%s) — RpcNode
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
ExecStart=%s -f %s
TimeoutStopSec=90
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
`, prof.Env, bin, yamlPath)
}

func resolveAptosNodeBinary(optPath string) string {
	for _, cand := range []string{
		filepath.Join(optPath, "bin", "aptos-node"),
		"/opt/aptos/bin/aptos-node",
		"/usr/local/bin/aptos-node",
	} {
		if fileExists(cand) {
			return cand
		}
	}

	return filepath.Join(optPath, "bin", "aptos-node")
}

func ensureAptosNodeInstalled(optPath, env string) (string, error) {
	if bin := resolveAptosNodeBinary(optPath); fileExists(bin) {
		return bin, nil
	}
	if p, err := exec.LookPath("aptos-node"); err == nil && p != "" {
		return p, nil
	}

	tag := aptosReleaseTag(env)
	name := aptosReleaseAssetName()
	url := preferVendoredArtifact("aptos", env,
		fmt.Sprintf("https://github.com/aptos-labs/aptos-core/releases/download/%s/%s", tag, name))
	tmp := filepath.Join(os.TempDir(), name)
	destBin := filepath.Join(optPath, "bin")
	if err := os.MkdirAll(destBin, 0o755); err != nil {
		return "", fmt.Errorf("mkdir %s: %w", destBin, err)
	}

	extractDir := filepath.Join(os.TempDir(), "rpcnode-aptos-"+tag)
	_ = os.RemoveAll(extractDir)
	cmd := exec.Command("bash", "-lc", fmt.Sprintf(
		`set -euo pipefail
if ! command -v curl >/dev/null; then echo "curl required to fetch aptos-node" >&2; exit 1; fi
curl -fsSL --connect-timeout 30 --max-time 900 -o %q %q
mkdir -p %q
tar -xzf %q -C %q
SRC=""
if [ -x %q/aptos-node ]; then SRC=%q; fi
if [ -z "$SRC" ]; then
  for d in %q %q/*; do
    if [ -x "$d/aptos-node" ]; then SRC="$d"; break; fi
    if [ -x "$d/bin/aptos-node" ]; then SRC="$d/bin"; break; fi
  done
fi
if [ -z "$SRC" ] || [ ! -x "$SRC/aptos-node" ]; then
  echo "aptos-node binary not found in tarball" >&2
  find %q -maxdepth 3 -type f | head -40 >&2
  exit 1
fi
install -m 755 "$SRC/aptos-node" %q/aptos-node
rm -rf %q %q
`, tmp, url, extractDir, tmp, extractDir,
		extractDir, extractDir, extractDir, extractDir, extractDir,
		destBin, extractDir, tmp))
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("install aptos-node %s: %v (%s)", tag, err, strings.TrimSpace(string(out)))
	}
	bin := filepath.Join(destBin, "aptos-node")
	if !fileExists(bin) {
		return "", fmt.Errorf("aptos-node missing after install at %s", bin)
	}

	return bin, nil
}

// resolveAptosDiskDirs — state DB (base.data_dir) + index/aux from disk_layout roles or fallbacks.
func resolveAptosDiskDirs(req nodeProvisionRequest, data, env string) (stateDB, indexDir string) {
	env = normalizeEnv(env)
	if data == "" {
		data = fmt.Sprintf("/data/aptos/%s", env)
	}

	if req.DiskLayout != nil {
		stateDB = strings.TrimSpace(req.DiskLayout.StateDir)
		indexDir = strings.TrimSpace(req.DiskLayout.IndexDir)
		if stateDB == "" && req.DiskLayout.StateMount != "" {
			stateDB = aptosPathOnMount(req.DiskLayout.StateMount, env, "db")
		}
		if indexDir == "" && req.DiskLayout.IndexMount != "" {
			indexDir = aptosPathOnMount(req.DiskLayout.IndexMount, env, "index")
		}
		if req.DiskLayout.Roles != nil {
			if stateDB == "" {
				if r, ok := req.DiskLayout.Roles["state"]; ok {
					stateDB = strings.TrimSpace(r.Dir)
					if stateDB == "" && strings.TrimSpace(r.Mount) != "" {
						stateDB = aptosPathOnMount(r.Mount, env, "db")
					}
				}
			}
			if indexDir == "" {
				if r, ok := req.DiskLayout.Roles["index"]; ok {
					indexDir = strings.TrimSpace(r.Dir)
					if indexDir == "" && strings.TrimSpace(r.Mount) != "" {
						indexDir = aptosPathOnMount(r.Mount, env, "index")
					}
				}
			}
		}
	}
	if stateDB == "" {
		stateDB = filepath.Join(data, "db")
	}
	if indexDir == "" {
		indexDir = filepath.Join(data, "index")
	}

	return filepath.Clean(stateDB), filepath.Clean(indexDir)
}

func aptosPathOnMount(mount, env, leaf string) string {
	mount = filepath.Clean(strings.TrimSpace(mount))
	env = normalizeEnv(env)
	leaf = strings.TrimSpace(leaf)
	if mount == "" || mount == "." || mount == "/" {
		return filepath.Join("/data/aptos", env, leaf)
	}
	if mount == "/data" {
		return filepath.Join("/data", "aptos", env, leaf)
	}

	return filepath.Join(mount, "aptos", env, leaf)
}
