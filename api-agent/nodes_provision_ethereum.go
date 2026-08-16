package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

const lighthouseVersion = "v8.2.1"

// Ethereum provision on the unified Server agent.
// Geth (EL) + Lighthouse (CL) per env — TRON_NETWORK=ethereum, not a separate agent product.
// Profile field reuse: SolHTTP=Engine, PBFTHTTP=Beacon, Metrics=ConsensusP2P (CL gossip).

func provisionEthereumNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
	env := req.Env
	steps := []string{}
	cluster := lookupEthereumNetwork(env)

	if prof.NodeHTTP > 0 {
		req.NodeHTTPPort = prof.NodeHTTP
	}
	if prof.P2P > 0 {
		req.P2PPort = prof.P2P
	}

	opt := prof.OptPath
	etc := prof.EtcPath
	data := prof.DataPath
	stateDir := fmt.Sprintf("/var/lib/rpcnode/ethereum-%s", env)
	gethData := resolveNetworkRoleDir(req, "ethereum", env, "execution", filepath.Join(data, "geth"))
	lhData := resolveNetworkRoleDir(req, "ethereum", env, "consensus", filepath.Join(data, "lighthouse"))
	jwtPath := filepath.Join(etc, "jwt.hex")

	for _, d := range []string{opt, etc, data, gethData, lhData, stateDir, "/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system"} {
		if d == "" {
			continue
		}
		if err := os.MkdirAll(d, 0o755); err != nil {
			return nil, fmt.Errorf("mkdir %s: %w", d, err)
		}
		steps = append(steps, "mkdir "+d)
	}

	gethBin, err := ensureGethInstalled()
	if err != nil {
		return nil, err
	}
	steps = append(steps, "geth="+gethBin)

	lhBin, err := ensureLighthouseInstalled()
	if err != nil {
		return nil, err
	}
	steps = append(steps, "lighthouse="+lhBin)

	_ = ensureNodeopUser()

	if err := ensureJWT(jwtPath); err != nil {
		return nil, err
	}
	steps = append(steps, "jwt="+jwtPath)

	enginePort := prof.SolHTTP
	beaconPort := prof.PBFTHTTP
	clP2P := prof.Metrics
	if enginePort <= 0 || beaconPort <= 0 || clP2P <= 0 {
		return nil, fmt.Errorf("ethereum profile missing engine/beacon/consensus ports for %s", env)
	}

	binDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))
	if token == "" {
		if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
			token = strings.TrimSpace(string(b))
		}
	}

	sysListen := systemAgentListenPort("ethereum", env)

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (ethereum)
# Per-node agent: Go RPC :%d → geth :%d; Agent API :%d
# SolHTTP=Engine, PBFTHTTP=Beacon, Metrics=ConsensusP2P (profile field reuse)
%sTRON_NETWORK=ethereum
TRON_NODE_HTTP_HOST=127.0.0.1
TRON_NODE_HTTP_PORT=%d
TRON_P2P_PORT=%d
TRON_ENGINE_PORT=%d
TRON_BEACON_PORT=%d
TRON_CONSENSUS_P2P_PORT=%d
TRON_JWT=%s
TRON_SYSTEM_AGENT_LISTEN=127.0.0.1:%d
TRON_SYSTEM_AGENT_URL=http://127.0.0.1:%d
TRON_STATE_DIR=%s
TRON_OPT=%s
TRON_ETC=%s
TRON_DATA=%s
TRON_AGENT_STATE=%s/agent-state.json
TRON_INSTANCE_FILE=%s/INSTANCE.json
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/ethereum-%s.json
TRON_SERVICE=ethereum-geth-%s
TRON_LIGHTHOUSE_SERVICE=ethereum-lighthouse-%s
TRON_SNAPSHOT_ENABLED=0
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		req.PublicPort, req.NodeHTTPPort, req.AgentPort,
		productEnvVars(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, req.P2PPort,
		enginePort, beaconPort, clP2P, jwtPath,
		sysListen, sysListen, stateDir,
		opt, etc, data, stateDir, stateDir, env, env, env,
		toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+envPath)

	apiBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-system-agent")

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-ethereum-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-ethereum-%s.service", env)
	gethUnitName := fmt.Sprintf("ethereum-geth-%s.service", env)
	lhUnitName := fmt.Sprintf("ethereum-lighthouse-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (ethereum/%s) — Go RPC :%d + Agent API :%d → geth :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=ethereum
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
Description=RpcNode per-node system-agent (ethereum/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=ethereum
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

	gethUnit := renderGethUnit(env, gethBin, gethData, jwtPath, req, prof, cluster)
	lhUnit := renderLighthouseUnit(env, lhBin, lhData, jwtPath, enginePort, beaconPort, clP2P, cluster)

	if err := os.WriteFile(filepath.Join("/etc/systemd/system", apiUnitName), []byte(apiUnit), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", sysUnitName), []byte(sysUnit), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", gethUnitName), []byte(gethUnit), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", lhUnitName), []byte(lhUnit), 0o644); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+apiUnitName, "wrote "+sysUnitName, "wrote "+gethUnitName, "wrote "+lhUnitName)

	_ = exec.Command("chown", "-R", "nodeop:nodeop", opt, etc, data).Run()

	agentURL := resolvePublicAgentURL(req.AgentPort)
	watch := cluster.WatchSlug
	if watch == "" {
		watch = prof.WatchSlug
	}
	inst := map[string]any{
		"id":                  "ethereum-" + env,
		"network":             "ethereum",
		"env":                 env,
		"name":                req.Name,
		"public_port":         req.PublicPort,
		"agent_port":          req.AgentPort,
		"node_http_port":      req.NodeHTTPPort,
		"node_rpc_port":       req.NodeHTTPPort,
		"p2p_port":            req.P2PPort,
		"engine_port":         enginePort,
		"beacon_port":         beaconPort,
		"consensus_p2p_port":  clP2P,
		"watch_slug":          watch,
		"chain_id":            cluster.ChainID,
		"agent_url":           agentURL,
		"data_dir":            data,
		"geth_dir":            gethData,
		"lighthouse_dir":      lhData,
		"etc_dir":             etc,
		"opt_dir":             opt,
		"jwt_path":            jwtPath,
		"units":               []string{gethUnitName, lhUnitName, apiUnitName, sysUnitName},
		"created_at":          time.Now().UTC().Format(time.RFC3339),
		"hostname":            hostnameOrEmpty(),
	}
	if err := writeJSONFile(filepath.Join(stateDir, "INSTANCE.json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "ethereum-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "ethereum-"+env+".json"), inst); err != nil {
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
		"network":        "ethereum",
		"env":            env,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"engine_port":    enginePort,
		"beacon_port":    beaconPort,
		"rpc_mode":       "go_proxy",
		"agent_url":      agentURL,
		"etc_dir":        etc,
		"data_dir":       data,
		"geth_dir":       gethData,
		"lighthouse_dir": lhData,
		"units":          []string{gethUnitName, lhUnitName, apiUnitName, sysUnitName},
		"units_started":  false,
		"status":         "provisioned",
		"snapshot":       false,
		"lifecycle":      "ports→install→start→run(EL/CL sync)",
		"message":        "ethereum per-node agents written; unit activation scheduled (Server agent left running)",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "ethereum-"+env+".json"),
	}, nil
}

func renderGethUnit(
	env, bin, datadir, jwtPath string,
	req nodeProvisionRequest,
	prof networkPortProfile,
	cluster ethereumNetwork,
) string {
	rpcPort := req.NodeHTTPPort
	if rpcPort <= 0 {
		rpcPort = prof.NodeHTTP
	}
	p2p := req.P2PPort
	if p2p <= 0 {
		p2p = prof.P2P
	}
	engine := prof.SolHTTP
	historyFlag := ""
	if cluster.HistoryPostMerge {
		historyFlag = " \\\n  --history.chain postmerge"
	}
	netFlag := ""
	if cluster.GethFlag != "" {
		netFlag = " \\\n  " + cluster.GethFlag
	}

	cacheMB := ethereumGethCacheMB(env)

	return fmt.Sprintf(`[Unit]
Description=Ethereum Geth EL (%s) — RpcNode
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
ExecStart=%s \
  --datadir %s \
  --http --http.addr 127.0.0.1 --http.port %d \
  --http.api eth,net,web3,txpool \
  --http.vhosts localhost \
  --authrpc.addr 127.0.0.1 --authrpc.port %d \
  --authrpc.jwtsecret %s \
  --authrpc.vhosts localhost \
  --syncmode snap \
  --gcmode full \
  --state.scheme path \
  --cache %d \
  --maxpeers 100 \
  --rpc.batch-request-limit 2000%s \
  --port %d%s
Restart=on-failure
RestartSec=10
TimeoutStopSec=600
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
LimitNOFILE=1048576
KillSignal=SIGINT

[Install]
WantedBy=multi-user.target
`, env, bin, datadir, rpcPort, engine, jwtPath, cacheMB, historyFlag, p2p, netFlag)
}

// ethereumGethCacheMB — mainnet gets a larger cache; testnets stay modest so co-hosted sync does not OOM.
func ethereumGethCacheMB(env string) int {
	switch normalizeEnv(env) {
	case "sepolia", "hoodi", "holesky", "goerli":
		return 2048
	default:
		return 4096
	}
}

func renderLighthouseUnit(
	env, bin, datadir, jwtPath string,
	enginePort, beaconPort, clP2P int,
	cluster ethereumNetwork,
) string {
	// PeerDAS (Fulu): default Lighthouse only custodies a column subset and cannot
	// serve /eth/v1/beacon/blobs for Orbit L2s (arb/robinhood/…). --supernode
	// subscribes to all data-column subnets; --prune-blobs false keeps history for
	// nitro inbox catch-up. See Arbitrum "beacon-nodes-historical-blobs".
	return fmt.Sprintf(`[Unit]
Description=Ethereum Lighthouse CL (%s) — RpcNode
After=network-online.target ethereum-geth-%s.service
Wants=network-online.target ethereum-geth-%s.service

[Service]
Type=simple
User=nodeop
Group=nodeop
ExecStart=%s bn \
  --network %s \
  --datadir %s \
  --execution-endpoint http://127.0.0.1:%d \
  --execution-jwt %s \
  --checkpoint-sync-url %s \
  --http --http-address 127.0.0.1 --http-port %d \
  --port %d \
  --supernode \
  --prune-blobs false
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
`, env, env, env, bin, cluster.LHNetwork, datadir, enginePort, jwtPath,
		cluster.CheckpointURL, beaconPort, clP2P)
}

func activateEthereumUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-ethereum-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-ethereum-%s.service", env)
	// Ethereum per-node agents use 3979x — do NOT stop host Server agent (:39090).
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

func ensureEthereumGoRPC(env string, publicPort int) error {
	env = normalizeEnv(env)
	if publicPort <= 0 {
		if p := lookupPortProfile("ethereum", env); p.Public > 0 {
			publicPort = p.Public
		}
	}
	if publicPort > 0 && portOpenLocal(publicPort) {
		return nil
	}
	if err := activateEthereumUnits(env); err != nil {
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
	unit := fmt.Sprintf("rpcnode-api-agent-ethereum-%s.service", env)
	jOut, _ := exec.Command("journalctl", "-u", unit, "-n", "16", "--no-pager", "-o", "cat").CombinedOutput()
	msg := fmt.Sprintf("Go RPC :%d not listening after restart %s", publicPort, unit)
	if snip := strings.TrimSpace(string(jOut)); snip != "" {
		msg += " — " + snip
	}

	return fmt.Errorf("%s", msg)
}

func ensureGethInstalled() (string, error) {
	for _, cand := range []string{
		"/usr/bin/geth",
		"/usr/local/bin/geth",
	} {
		if fileExists(cand) {
			return cand, nil
		}
	}
	if p, err := exec.LookPath("geth"); err == nil && p != "" {
		return p, nil
	}

	// Best-effort install via Ethereum PPA (Ubuntu/Debian hosts).
	if _, err := exec.LookPath("apt-get"); err == nil {
		script := `set -e
export DEBIAN_FRONTEND=noninteractive
apt-get install -y software-properties-common
add-apt-repository -y ppa:ethereum/ethereum
apt-get update
apt-get install -y geth
`
		out, err := exec.Command("bash", "-lc", script).CombinedOutput()
		if err == nil {
			if p, err := exec.LookPath("geth"); err == nil {
				return p, nil
			}
			if fileExists("/usr/bin/geth") {
				return "/usr/bin/geth", nil
			}
		}
		_ = out
	}

	return "", fmt.Errorf("geth not found (looked PATH, /usr/bin/geth, /usr/local/bin/geth) — install geth or enable apt PPA")
}

func lighthouseReleaseURL(version, goarch string) string {
	// v8.2+ ships `x86_64-unknown-linux-gnu` (no `-portable` asset).
	return fmt.Sprintf("https://github.com/sigp/lighthouse/releases/download/%s/lighthouse-%s-%s-unknown-linux-gnu.tar.gz",
		version, version, goarch)
}

func lighthousePortableURL(version, goarch string) string {
	return fmt.Sprintf("https://github.com/sigp/lighthouse/releases/download/%s/lighthouse-%s-%s-portable.tar.gz",
		version, version, goarch)
}

func ensureLighthouseInstalled() (string, error) {
	dest := "/usr/local/bin/lighthouse"
	if fileExists(dest) {
		return dest, nil
	}
	if p, err := exec.LookPath("lighthouse"); err == nil && p != "" {
		return p, nil
	}

	arch := runtimeGOARCH()
	goarch := "x86_64"
	if strings.Contains(arch, "aarch64") || strings.Contains(arch, "arm64") {
		goarch = "aarch64"
	}
	urls := []string{
		lighthouseReleaseURL(lighthouseVersion, goarch),
		lighthousePortableURL(lighthouseVersion, goarch),
	}

	tmp, err := os.MkdirTemp("", "lighthouse-*")
	if err != nil {
		return "", fmt.Errorf("lighthouse download temp: %w", err)
	}
	defer os.RemoveAll(tmp)

	tarPath := filepath.Join(tmp, "lighthouse.tar.gz")
	var last error
	used := ""
	for _, url := range urls {
		if err := downloadFile(url, tarPath); err != nil {
			last = err
			continue
		}
		used = url
		break
	}
	if used == "" {
		return "", fmt.Errorf("lighthouse download failed: %w", last)
	}
	out, err := exec.Command("tar", "-xzf", tarPath, "-C", tmp).CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("lighthouse extract: %v (%s)", err, strings.TrimSpace(string(out)))
	}
	bin := filepath.Join(tmp, "lighthouse")
	if !fileExists(bin) {
		// Some tarballs nest one directory level.
		matches, _ := filepath.Glob(filepath.Join(tmp, "*/lighthouse"))
		if len(matches) > 0 {
			bin = matches[0]
		}
	}
	if !fileExists(bin) {
		return "", fmt.Errorf("lighthouse binary missing in tarball from %s", used)
	}
	_ = exec.Command("install", "-m", "0755", bin, dest).Run()
	if fileExists(dest) {
		return dest, nil
	}

	return "", fmt.Errorf("lighthouse not found and download/install to %s failed", dest)
}

func ensureJWT(path string) error {
	if fileExists(path) {
		return nil
	}
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	out, err := exec.Command("openssl", "rand", "-hex", "32").CombinedOutput()
	if err != nil {
		return fmt.Errorf("openssl rand jwt: %v (%s)", err, strings.TrimSpace(string(out)))
	}
	hex := strings.TrimSpace(string(out))
	if err := os.WriteFile(path, []byte(hex+"\n"), 0o640); err != nil {
		return err
	}
	_ = exec.Command("chown", "root:nodeop", path).Run()

	return nil
}
