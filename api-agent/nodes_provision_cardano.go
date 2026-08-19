package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// Cardano provision — cardano-node + Ogmios JSON-RPC sidecar.

func provisionCardanoNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
	env := req.Env
	if env != "mainnet" && env != "preprod" && env != "preview" {
		return nil, fmt.Errorf("cardano provision supports mainnet/preprod/preview (got %s)", env)
	}
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
	stateDir := fmt.Sprintf("/var/lib/rpcnode/cardano-%s", env)
	socket := filepath.Join(data, "node.socket")

	for _, d := range []string{opt, filepath.Join(opt, "bin"), etc, data, stateDir,
		"/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system"} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return nil, fmt.Errorf("mkdir %s: %w", d, err)
		}
		steps = append(steps, "mkdir "+d)
	}

	nodeBin, err := ensureCardanoNodeInstalled(opt)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "cardano-node="+nodeBin)

	ogmiosBin, err := ensureOgmiosInstalled(opt)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "ogmios="+ogmiosBin)
	_ = ensureNodeopUser()

	if err := writeCardanoConfigs(etc, env); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote cardano configs")

	mithrilBin, err := ensureMithrilClientInstalled(opt)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "mithril-client="+mithrilBin)
	snapUnitPath, snapScript, err := ensureCardanoSnapshotUnit(prof, mithrilBin)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+snapUnitPath, "wrote "+snapScript)

	binDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))
	if token == "" {
		if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
			token = strings.TrimSpace(string(b))
		}
	}

	sysListen := systemAgentListenPort("cardano", env)
	metrics := prof.Metrics
	if metrics <= 0 {
		metrics = 12798
	}
	mithril := cardanoMithrilParams(env)
	snapURL := mithril.Aggregator
	if strings.TrimSpace(prof.SnapshotURL) != "" {
		snapURL = strings.TrimSpace(prof.SnapshotURL)
	}
	if snapURL != "" {
		logDownload("snapshot", snapURL, "cardano/"+env+" toolkit.env")
	}
	snapService := fmt.Sprintf("cardano-%s-snapshot", env)
	snapLog := fmt.Sprintf("/var/log/cardano/%s-snapshot.log", env)
	_ = os.MkdirAll(filepath.Dir(snapLog), 0o755)

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (cardano)
%sTRON_NETWORK=cardano
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
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/cardano-%s.json
TRON_SERVICE=cardano-%s
TRON_SNAPSHOT_ENABLED=1
TRON_SNAPSHOT_URL=%s
TRON_SNAPSHOT_SERVICE=%s
TRON_SNAPSHOT_LOG=%s
TRON_SNAPSHOT_MARKER=%s/.snapshot-ready
TRON_SNAPSHOT_STATE=%s/.snapshot-state.json
CARDANO_NODE_SOCKET_PATH=%s
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		productEnvVars(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, req.P2PPort,
		sysListen, sysListen, stateDir,
		opt, etc, data, stateDir, stateDir, env, env,
		snapURL, snapService, snapLog, data, data,
		socket, toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+envPath)

	apiBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-system-agent")

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-cardano-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-cardano-%s.service", env)
	nodeUnitName := fmt.Sprintf("cardano-%s.service", env)
	ogmiosUnitName := fmt.Sprintf("cardano-ogmios-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (cardano/%s) — Go RPC :%d + Agent API :%d → Ogmios :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=cardano
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
Description=RpcNode per-node system-agent (cardano/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=cardano
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

	nodeUnit := fmt.Sprintf(`[Unit]
Description=Cardano node (%s) — RpcNode
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
WorkingDirectory=%s
Environment=CARDANO_NODE_SOCKET_PATH=%s
ExecStart=%s run +RTS -N -RTS --topology %s/topology.json --database-path %s/db --socket-path %s --host-addr 0.0.0.0 --port %d --config %s/config.json
Restart=on-failure
RestartSec=15
TimeoutStopSec=600
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
`, env, data, socket, nodeBin, etc, data, socket, req.P2PPort, etc)

	ogmiosUnit := fmt.Sprintf(`[Unit]
Description=Ogmios JSON-RPC for cardano/%s — RpcNode
After=network-online.target %s
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
Environment=CARDANO_NODE_SOCKET_PATH=%s
ExecStart=%s --node-socket %s --node-config %s/config.json --host 127.0.0.1 --port %d
Restart=on-failure
RestartSec=10
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
`, env, nodeUnitName, socket, ogmiosBin, socket, etc, req.NodeHTTPPort)

	_ = metrics // reserved / future prometheus scrape

	for name, body := range map[string]string{
		apiUnitName: apiUnit, sysUnitName: sysUnit,
		nodeUnitName: nodeUnit, ogmiosUnitName: ogmiosUnit,
	} {
		if err := os.WriteFile(filepath.Join("/etc/systemd/system", name), []byte(body), 0o644); err != nil {
			return nil, err
		}
		steps = append(steps, "wrote "+name)
	}

	_ = exec.Command("chown", "-R", "nodeop:nodeop", etc, data, opt).Run()

	agentURL := resolvePublicAgentURL(req.AgentPort)
	inst := map[string]any{
		"id":             "cardano-" + env,
		"network":        "cardano",
		"env":            env,
		"name":           req.Name,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"watch_slug":     prof.WatchSlug,
		"agent_url":      agentURL,
		"data_dir":       data,
		"etc_dir":        etc,
		"opt_dir":        opt,
		"socket":         socket,
		"units":          []string{nodeUnitName, ogmiosUnitName, snapService + ".service", apiUnitName, sysUnitName},
		"created_at":     time.Now().UTC().Format(time.RFC3339),
		"hostname":       hostnameOrEmpty(),
	}
	if err := writeJSONFile(filepath.Join(stateDir, "INSTANCE.json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "cardano-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "cardano-"+env+".json"), inst); err != nil {
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
		"network":        "cardano",
		"env":            env,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"rpc_mode":       "go_proxy",
		"agent_url":      agentURL,
		"etc_dir":        etc,
		"data_dir":       data,
		"units":          []string{nodeUnitName, ogmiosUnitName, snapService + ".service", apiUnitName, sysUnitName},
		"units_started":  false,
		"status":         "provisioned",
		"snapshot":       true,
		"lifecycle":      "ports→install→snapshot(mithril)→start→run",
		"message":        "cardano per-node agents written; unit activation scheduled (Server agent left running)",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "cardano-"+env+".json"),
	}, nil
}

func activateCardanoUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-cardano-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-cardano-%s.service", env)
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

func writeCardanoConfigs(etc, env string) error {
	book := cardanoBookEnv(env)
	base := "https://book.world.dev.cardano.org/environments/" + book
	required := []string{
		"config.json", "topology.json",
		"byron-genesis.json", "shelley-genesis.json",
		"alonzo-genesis.json", "conway-genesis.json",
	}
	for _, f := range required {
		dest := filepath.Join(etc, f)
		url := base + "/" + f
		if err := downloadNamedConf("cardano", env, f, url, dest); err != nil {
			return fmt.Errorf("download %s: %w", f, err)
		}
	}
	// checkpoints.json is present for mainnet; preprod/preview may 404 — optional.
	ckpt := filepath.Join(etc, "checkpoints.json")
	if err := downloadFile(base+"/checkpoints.json", ckpt); err != nil {
		_ = os.WriteFile(ckpt, []byte("{}\n"), 0o644)
	}
	// peer-snapshot.json required by cardano-node ≥10 Genesis sync (missing → crash-loop).
	snap := filepath.Join(etc, "peer-snapshot.json")
	if err := downloadFile(base+"/peer-snapshot.json", snap); err != nil {
		_ = os.WriteFile(snap, []byte("[]\n"), 0o644)
	}

	return nil
}

func ensureCardanoNodeInstalled(optPath string) (string, error) {
	link := filepath.Join(optPath, "bin", "cardano-node")
	if fileExists(link) {
		return link, nil
	}
	if p, err := exec.LookPath("cardano-node"); err == nil && p != "" {
		_ = os.MkdirAll(filepath.Dir(link), 0o755)
		_ = os.Remove(link)
		_ = os.Symlink(p, link)

		return p, nil
	}

	ver := envOr("CARDANO_NODE_VERSION", "11.0.1")
	arch := "linux-amd64"
	switch runtimeGOARCH() {
	case "arm64", "aarch64":
		arch = "linux-arm64"
	}
	name := fmt.Sprintf("cardano-node-%s-%s.tar.gz", ver, arch)
	url := preferVendoredArtifact("cardano", "mainnet",
		fmt.Sprintf("https://github.com/IntersectMBO/cardano-node/releases/download/%s/%s", ver, name))
	tmp := filepath.Join(os.TempDir(), name)
	logDownload("GET", url, "cardano dest="+tmp)
	extractDir := filepath.Join(os.TempDir(), "rpcnode-cardano-"+ver)
	_ = os.RemoveAll(extractDir)
	destBin := filepath.Join(optPath, "bin")
	_ = os.MkdirAll(destBin, 0o755)

	cmd := exec.Command("bash", "-lc", fmt.Sprintf(
		`set -euo pipefail
curl -fsSL --connect-timeout 30 --max-time 900 -o %q %q
mkdir -p %q
tar -xzf %q -C %q
# tarball layout varies — find cardano-node binary
BIN=$(find %q -type f -name cardano-node | head -1)
CLI=$(find %q -type f -name cardano-cli | head -1)
test -n "$BIN"
install -m 755 "$BIN" %q/cardano-node
if [ -n "$CLI" ]; then install -m 755 "$CLI" %q/cardano-cli; fi
rm -rf %q %q
`, tmp, url, extractDir, tmp, extractDir, extractDir, extractDir, destBin, destBin, extractDir, tmp))
	out, err := cmd.CombinedOutput()
	logDownloadDone("GET", url, "cardano dest="+tmp, out, err)
	if err != nil {
		return "", fmt.Errorf("install cardano-node %s: %v (%s)", ver, err, strings.TrimSpace(string(out)))
	}
	if !fileExists(link) {
		return "", fmt.Errorf("cardano-node missing after install at %s", link)
	}

	return link, nil
}

func ensureOgmiosInstalled(optPath string) (string, error) {
	link := filepath.Join(optPath, "bin", "ogmios")
	if fileExists(link) {
		return link, nil
	}
	if p, err := exec.LookPath("ogmios"); err == nil && p != "" {
		_ = os.MkdirAll(filepath.Dir(link), 0o755)
		_ = os.Remove(link)
		_ = os.Symlink(p, link)

		return p, nil
	}

	ver := envOr("OGMIOS_VERSION", "v7.0.0")
	arch := "x86_64-linux"
	switch runtimeGOARCH() {
	case "arm64", "aarch64":
		arch = "aarch64-linux"
	}
	name := fmt.Sprintf("ogmios-%s-%s.zip", ver, arch)
	url := fmt.Sprintf("https://github.com/CardanoSolutions/ogmios/releases/download/%s/%s", ver, name)
	tmp := filepath.Join(os.TempDir(), name)
	logDownload("GET", url, "ogmios dest="+tmp)
	extractDir := filepath.Join(os.TempDir(), "rpcnode-ogmios")
	_ = os.RemoveAll(extractDir)
	destBin := filepath.Join(optPath, "bin")
	_ = os.MkdirAll(destBin, 0o755)

	cmd := exec.Command("bash", "-lc", fmt.Sprintf(
		`set -euo pipefail
curl -fsSL --connect-timeout 30 --max-time 600 -o %q %q
mkdir -p %q
if command -v unzip >/dev/null; then
  unzip -o -q %q -d %q
else
  python3 - <<PY
import zipfile
zipfile.ZipFile(%q).extractall(%q)
PY
fi
BIN=$(find %q -type f -name ogmios | head -1)
test -n "$BIN"
install -m 755 "$BIN" %q/ogmios
rm -rf %q %q
`, tmp, url, extractDir, tmp, extractDir, tmp, extractDir, extractDir, destBin, extractDir, tmp))
	out, err := cmd.CombinedOutput()
	logDownloadDone("GET", url, "ogmios dest="+tmp, out, err)
	if err != nil {
		return "", fmt.Errorf("install ogmios %s: %v (%s)", ver, err, strings.TrimSpace(string(out)))
	}
	if !fileExists(link) {
		return "", fmt.Errorf("ogmios missing after install at %s", link)
	}

	return link, nil
}
