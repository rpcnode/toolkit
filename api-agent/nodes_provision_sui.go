package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// Sui provision — native sui-node (systemd), MystenLabs release tarball + genesis.blob.
// Config: /etc/sui/<env>/fullnode.yaml — JSON-RPC loopback, metrics loopback, archival fallback.

func provisionSuiNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
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
	stateDir := fmt.Sprintf("/var/lib/rpcnode/sui-%s", env)
	metricsPort := prof.Metrics
	if metricsPort <= 0 {
		if normalizeEnv(env) == "testnet" {
			metricsPort = 9185
		} else {
			metricsPort = 9184
		}
	}

	for _, d := range []string{opt, etc, data, stateDir, filepath.Join(data, "db"),
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

	bin, err := ensureSuiNodeInstalled(opt, env)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "sui-node="+bin)
	toolBin, err := ensureSuiToolInstalled(opt, env)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "sui-tool="+toolBin)
	_ = ensureNodeopUser()

	genesisPath := filepath.Join(etc, "genesis.blob")
	if !fileExists(genesisPath) {
		if err := downloadNamedConf("sui", env, "genesis.blob", suiGenesisURL(env), genesisPath); err != nil {
			return nil, fmt.Errorf("download genesis.blob: %w", err)
		}
		steps = append(steps, "downloaded genesis.blob")
	} else {
		steps = append(steps, "genesis.blob present")
	}

	yamlPath, dbPath, err := writeSuiFullnodeYAML(prof, req, genesisPath, metricsPort)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+yamlPath)

	snapUnitPath, snapScript, err := ensureSuiSnapshotUnit(prof, toolBin, genesisPath, dbPath)
	if err != nil {
		return nil, err
	}
	snapUnitName := fmt.Sprintf("sui-%s-snapshot.service", normalizeEnv(env))
	steps = append(steps, "wrote "+snapUnitPath, "wrote "+snapScript)

	tipURL := suiPublicTipRPC(env)
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

	sysListen := systemAgentListenPort("sui", env)
	snapURL := strings.TrimSpace(prof.SnapshotURL)
	if snapURL == "" {
		snapURL = "formal-r2://" + normalizeEnv(env)
	}
	snapService := fmt.Sprintf("sui-%s-snapshot", normalizeEnv(env))
	snapLog := fmt.Sprintf("/var/log/sui/%s-snapshot.log", normalizeEnv(env))
	_ = os.MkdirAll(filepath.Dir(snapLog), 0o755)

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (sui)
%sTRON_NETWORK=sui
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
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/sui-%s.json
TRON_SERVICE=sui-%s
TRON_SNAPSHOT_ENABLED=1
TRON_SNAPSHOT_URL=%s
TRON_SNAPSHOT_SERVICE=%s
TRON_SNAPSHOT_LOG=%s
TRON_SNAPSHOT_MARKER=%s/.snapshot-ready
TRON_SNAPSHOT_STATE=%s/.snapshot-state.json
SUI_PUBLIC_TIP_RPC=%s
SUI_METRICS_URL=http://127.0.0.1:%d/metrics
SUI_SNAPSHOT_DB=%s
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		productEnvVars(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, req.P2PPort, metricsPort,
		sysListen, sysListen, stateDir,
		opt, etc, data, stateDir, stateDir, env, env,
		snapURL, snapService, snapLog, data, data,
		tipURL, metricsPort, dbPath,
		toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+envPath)

	apiBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-system-agent")

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-sui-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-sui-%s.service", env)
	nodeUnitName := fmt.Sprintf("sui-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (sui/%s) — Go RPC :%d + Agent API :%d → sui-node :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=sui
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
Description=RpcNode per-node system-agent (sui/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=sui
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
	if err := os.WriteFile(nodeUnitPath, []byte(renderSuiNodeUnit(prof, yamlPath)), 0o644); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+nodeUnitPath)

	_ = exec.Command("chown", "-R", "nodeop:nodeop", etc, data, opt).Run()

	agentURL := resolvePublicAgentURL(req.AgentPort)
	inst := map[string]any{
		"id":             "sui-" + env,
		"network":        "sui",
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
		"etc_dir":        etc,
		"opt_dir":        opt,
		"units":          []string{nodeUnitName, snapUnitName, apiUnitName, sysUnitName},
		"created_at":     time.Now().UTC().Format(time.RFC3339),
		"hostname":       hostnameOrEmpty(),
	}
	if err := writeJSONFile(filepath.Join(stateDir, "INSTANCE.json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "sui-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "sui-"+env+".json"), inst); err != nil {
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
		"network":        "sui",
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
		"units":          []string{nodeUnitName, snapUnitName, apiUnitName, sysUnitName},
		"units_started":  false,
		"status":         "provisioned",
		"snapshot":       true,
		"snapshot_url":   snapURL,
		"lifecycle":      "ports→install→snapshot→start→run(catch-up)",
		"message":        "sui per-node agents written; formal snapshot unit ready (Server agent left running)",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "sui-"+env+".json"),
	}, nil
}

func activateSuiUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-sui-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-sui-%s.service", env)
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

func writeSuiFullnodeYAML(prof networkPortProfile, req nodeProvisionRequest, genesisPath string, metricsPort int) (yamlPath, dbPath string, err error) {
	etc := prof.EtcPath
	data := prof.DataPath
	if etc == "" {
		etc = fmt.Sprintf("/etc/sui/%s", normalizeEnv(prof.Env))
	}
	if data == "" {
		data = fmt.Sprintf("/data/sui/%s", normalizeEnv(prof.Env))
	}
	rpc := req.NodeHTTPPort
	if rpc <= 0 {
		rpc = prof.NodeHTTP
	}
	p2p := req.P2PPort
	if p2p <= 0 {
		p2p = prof.P2P
	}
	dbPath = resolveNetworkRoleDir(req, "sui", normalizeEnv(prof.Env), "state", filepath.Join(data, "db"))
	indexDir := resolveNetworkRoleDir(req, "sui", normalizeEnv(prof.Env), "index", filepath.Join(data, "index"))
	_ = os.MkdirAll(indexDir, 0o755)
	archive := suiCheckpointArchiveURL(prof.Env)

	// Full-history product: keep local epochs as long as practical + archival fallback
	// for historical checkpoints (mainnet disk = hundreds of GB → TB).
	// Bootstrap = Mysten formal snapshot (§1a); archive covers older checkpoint lookups.
	body := fmt.Sprintf(`# Generated by rpcnode provision — sui/%s
# Docs: https://docs.sui.io/operators/full-node/sui-full-node
db-path: %q

network-address: "/ip4/127.0.0.1/tcp/8080/http"
metrics-address: "127.0.0.1:%d"
json-rpc-address: "127.0.0.1:%d"
enable-event-processing: true

p2p-config:
  listen-address: "0.0.0.0:%d"

genesis:
  genesis-file-location: %q

# Retain as many epochs as practical locally; archival fallback for older history.
authority-store-pruning-config:
  num-latest-epoch-dbs-to-retain: 3
  epoch-db-pruning-period-secs: 3600
  num-epochs-to-retain: 18446744073709551615
  max-checkpoints-in-batch: 10
  max-transactions-in-batch: 1000
  pruning-run-delay-seconds: 60

state-archive-read-config:
  - ingestion-url: %q
    concurrency: 5
`, normalizeEnv(prof.Env), dbPath, metricsPort, rpc, p2p, genesisPath, archive)

	yamlPath = filepath.Join(etc, "fullnode.yaml")
	if err = os.WriteFile(yamlPath, []byte(body), 0o644); err != nil {
		return "", "", err
	}

	return yamlPath, dbPath, nil
}

// ensureSuiSnapshotUnit — oneshot formal snapshot via Mysten free R2 (--no-sign-request).
// Marker: /data/sui/<env>/.snapshot-ready. Node must be stopped during restore.
func ensureSuiSnapshotUnit(prof networkPortProfile, toolBin, genesisPath, dbPath string) (unitPath, scriptPath string, err error) {
	env := normalizeEnv(prof.Env)
	data := prof.DataPath
	if data == "" {
		data = fmt.Sprintf("/data/sui/%s", env)
	}
	opt := prof.OptPath
	if opt == "" {
		opt = fmt.Sprintf("/opt/sui/%s", env)
	}
	marker := filepath.Join(data, ".snapshot-ready")
	stateJSON := filepath.Join(data, ".snapshot-state.json")
	logPath := fmt.Sprintf("/var/log/sui/%s-snapshot.log", env)
	_ = os.MkdirAll(filepath.Dir(logPath), 0o755)
	_ = os.MkdirAll(filepath.Join(opt, "bin"), 0o755)
	_ = os.MkdirAll(dbPath, 0o755)

	scriptPath = filepath.Join(opt, "bin", "sui-formal-snapshot.sh")
	script := fmt.Sprintf(`#!/bin/bash
set -euo pipefail
ENV=%q
NODE_UNIT="sui-${ENV}.service"
TOOL=%q
GENESIS=%q
DB=%q
DATA=%q
MARKER=%q
STATE=%q
LOG=%q
mkdir -p "$(dirname "$LOG")" "$DB" "$DATA"
echo "{\"phase\":\"download\",\"pct\":0,\"updated_at\":\"$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)\"}" >"$STATE"
systemctl stop "$NODE_UNIT" 2>/dev/null || true
# Drop empty/partial genesis DB so formal restore can write cleanly.
if [ ! -f "$MARKER" ]; then
  find "$DB" -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || true
fi
chown -R nodeop:nodeop "$DB" "$DATA" 2>/dev/null || true
# sui-tool opens mysten-metrics (default bind) — two envs at once → AddrInUse panic.
while true; do
	others="$(pgrep -af 'sui-tool.*download-formal-snapshot' 2>/dev/null | grep -vF -- "--network $ENV" | grep -v grep || true)"
  if [ -z "$others" ]; then break; fi
  echo "waiting for another env formal snapshot (metrics bind)…" | tee -a "$LOG"
  sleep 45
done
set -o pipefail
# Mysten free formal snapshot (Cloudflare R2). Progress → journal + log for agent %%.
runuser -u nodeop -- "$TOOL" download-formal-snapshot \
  --latest \
  --genesis "$GENESIS" \
  --network "$ENV" \
  --path "$DB" \
  --num-parallel-downloads 50 \
  --no-sign-request 2>&1 | tee -a "$LOG"
touch "$MARKER"
echo "{\"phase\":\"done\",\"pct\":100,\"updated_at\":\"$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)\"}" >"$STATE"
chown nodeop:nodeop "$MARKER" "$STATE" 2>/dev/null || true
`, env, toolBin, genesisPath, dbPath, data, marker, stateJSON, logPath)
	if err = os.WriteFile(scriptPath, []byte(script), 0o755); err != nil {
		return "", "", err
	}

	unitName := fmt.Sprintf("sui-%s-snapshot.service", env)
	unitPath = filepath.Join("/etc/systemd/system", unitName)
	body := fmt.Sprintf(`[Unit]
Description=Sui %s formal snapshot download (sui-tool --no-sign-request)
After=network-online.target
Wants=network-online.target
ConditionPathExists=!%s

[Service]
Type=oneshot
User=root
Nice=10
TimeoutStartSec=0
ExecStart=%s
RemainAfterExit=yes
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
`, env, marker, scriptPath)
	if err = os.WriteFile(unitPath, []byte(body), 0o644); err != nil {
		return "", "", err
	}
	_ = exec.Command("systemctl", "daemon-reload").Run()

	return unitPath, scriptPath, nil
}

func renderSuiNodeUnit(prof networkPortProfile, yamlPath string) string {
	bin := resolveSuiNodeBinary(prof.OptPath)

	return fmt.Sprintf(`[Unit]
Description=Sui fullnode (%s) — RpcNode
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
ExecStart=%s --config-path %s
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

func resolveSuiNodeBinary(optPath string) string {
	for _, cand := range []string{
		filepath.Join(optPath, "bin", "sui-node"),
		"/opt/sui/bin/sui-node",
		"/usr/local/bin/sui-node",
	} {
		if fileExists(cand) {
			return cand
		}
	}

	return filepath.Join(optPath, "bin", "sui-node")
}

func resolveSuiToolBinary(optPath string) string {
	for _, cand := range []string{
		filepath.Join(optPath, "bin", "sui-tool"),
		"/opt/sui/bin/sui-tool",
		"/usr/local/bin/sui-tool",
	} {
		if fileExists(cand) {
			return cand
		}
	}

	return filepath.Join(optPath, "bin", "sui-tool")
}

func ensureSuiToolInstalled(optPath, env string) (string, error) {
	if bin := resolveSuiToolBinary(optPath); fileExists(bin) {
		return bin, nil
	}
	if p, err := exec.LookPath("sui-tool"); err == nil && p != "" {
		return p, nil
	}
	if err := installSuiReleaseBinaries(optPath, env); err != nil {
		return "", err
	}
	bin := resolveSuiToolBinary(optPath)
	if !fileExists(bin) {
		return "", fmt.Errorf("sui-tool missing after Mysten release install (need formal snapshot); opt=%s", optPath)
	}

	return bin, nil
}

func ensureSuiNodeInstalled(optPath, env string) (string, error) {
	if bin := resolveSuiNodeBinary(optPath); fileExists(bin) {
		return bin, nil
	}
	if p, err := exec.LookPath("sui-node"); err == nil && p != "" {
		return p, nil
	}
	if err := installSuiReleaseBinaries(optPath, env); err != nil {
		return "", err
	}
	bin := filepath.Join(optPath, "bin", "sui-node")
	if !fileExists(bin) {
		return "", fmt.Errorf("sui-node missing after install at %s", bin)
	}

	return bin, nil
}

// installSuiReleaseBinaries fetches Mysten release tarball and installs sui-node + sui-tool.
func installSuiReleaseBinaries(optPath, env string) error {
	tag := suiReleaseTag(env)
	arch := "x86_64"
	switch runtimeGOARCH() {
	case "arm64", "aarch64":
		arch = "aarch64"
	}
	name := fmt.Sprintf("sui-%s-ubuntu-%s.tgz", tag, arch)
	url := preferVendoredArtifact("sui", env,
		fmt.Sprintf("https://github.com/MystenLabs/sui/releases/download/%s/%s", tag, name))
	tmp := filepath.Join(os.TempDir(), name)
	destBin := filepath.Join(optPath, "bin")
	if err := os.MkdirAll(destBin, 0o755); err != nil {
		return fmt.Errorf("mkdir %s: %w", destBin, err)
	}

	extractDir := filepath.Join(os.TempDir(), "rpcnode-sui-"+tag)
	_ = os.RemoveAll(extractDir)
	cmd := exec.Command("bash", "-lc", fmt.Sprintf(
		`set -euo pipefail
if ! command -v curl >/dev/null; then echo "curl required to fetch sui-node" >&2; exit 1; fi
curl -fsSL --connect-timeout 30 --max-time 900 -o %q %q
mkdir -p %q
tar -xzf %q -C %q
SRC=""
if [ -x %q/sui-node ]; then SRC=%q; fi
if [ -z "$SRC" ]; then
  for d in %q %q/*; do
    if [ -x "$d/sui-node" ]; then SRC="$d"; break; fi
    if [ -x "$d/bin/sui-node" ]; then SRC="$d/bin"; break; fi
  done
fi
if [ -z "$SRC" ] || [ ! -x "$SRC/sui-node" ]; then
  echo "sui-node binary not found in tarball" >&2
  find %q -maxdepth 3 -type f | head -40 >&2
  exit 1
fi
install -m 755 "$SRC/sui-node" %q/sui-node
# companion binaries (sui-tool required for formal snapshot)
for b in sui sui-tool; do
  if [ -x "$SRC/$b" ]; then install -m 755 "$SRC/$b" %q/$b; fi
done
rm -rf %q %q
`, tmp, url, extractDir, tmp, extractDir,
		extractDir, extractDir, extractDir, extractDir, extractDir,
		destBin, destBin, extractDir, tmp))
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("install sui release %s: %v (%s)", tag, err, strings.TrimSpace(string(out)))
	}

	return nil
}
