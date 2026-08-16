package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Base provision — base-reth-node + base-consensus; L1 RPC+Beacon from ethereum host.

func provisionBaseNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
	env := req.Env
	steps := []string{}
	cluster := lookupBaseNetwork(env)

	if prof.NodeHTTP > 0 {
		req.NodeHTTPPort = prof.NodeHTTP
	}
	if prof.P2P > 0 {
		req.P2PPort = prof.P2P
	}

	opt := prof.OptPath
	etc := prof.EtcPath
	data := prof.DataPath
	stateDir := fmt.Sprintf("/var/lib/rpcnode/base-%s", env)
	binDir := filepath.Join(opt, "bin")
	rethData := resolveNetworkRoleDir(req, "base", env, "execution", filepath.Join(data, "reth"))
	snapDir := resolveNetworkRoleDir(req, "base", env, "snapshots", filepath.Join(data, "snapshots"))
	jwtPath := filepath.Join(etc, "jwt.hex")

	for _, d := range []string{opt, binDir, etc, data, rethData, snapDir, stateDir, "/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system"} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return nil, fmt.Errorf("mkdir %s: %w", d, err)
		}
		steps = append(steps, "mkdir "+d)
	}

	rethBin, err := ensureBaseRethInstalled(opt)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "base-reth-node="+rethBin)

	consensusBin, err := ensureBaseConsensusInstalled(opt)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "base-consensus="+consensusBin)

	_ = ensureNodeopUser()

	if err := ensureJWT(jwtPath); err != nil {
		return nil, err
	}
	steps = append(steps, "jwt="+jwtPath)

	jwtRaw, err := readJWTHex(jwtPath)
	if err != nil {
		return nil, err
	}

	l1 := defaultL1RPCURLFor(baseL1Env(env))
	beacon := defaultL1BeaconURLFor(baseL1Env(env))

	enginePort := prof.SolHTTP
	consensusP2P := prof.PBFTHTTP
	if enginePort <= 0 {
		enginePort = 8572
	}
	if consensusP2P <= 0 {
		consensusP2P = 9023
	}
	// Prefer profile Metrics as WS (NodeHTTP+100 collided with other localhost RPC on tip hosts).
	wsPort := prof.Metrics
	if wsPort <= 0 {
		wsPort = req.NodeHTTPPort + 10
	}
	if wsPort <= 0 || wsPort > 65535 {
		wsPort = 8581
	}
	discoveryV5 := 9203
	if normalizeEnv(env) == "sepolia" {
		discoveryV5 = 9213
	}

	if err := writeBaseConsensusEnv(etc, cluster, l1, beacon, jwtPath, jwtRaw, enginePort, consensusP2P); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote consensus env")

	wrapper := filepath.Join(binDir, "run-base-consensus.sh")
	if err := writeBaseConsensusWrapper(wrapper, consensusBin); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+wrapper)

	rpcBinDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))
	if token == "" {
		if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
			token = strings.TrimSpace(string(b))
		}
	}

	sysListen := systemAgentListenPort("base", env)

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (base)
%sTRON_NETWORK=base
TRON_NODE_HTTP_HOST=127.0.0.1
TRON_NODE_HTTP_PORT=%d
TRON_P2P_PORT=%d
TRON_ENGINE_PORT=%d
TRON_BASE_CONSENSUS_P2P_PORT=%d
TRON_JWT=%s
L1_RPC_URL=%s
L1_BEACON_URL=%s
TRON_SYSTEM_AGENT_LISTEN=127.0.0.1:%d
TRON_SYSTEM_AGENT_URL=http://127.0.0.1:%d
TRON_STATE_DIR=%s
TRON_OPT=%s
TRON_ETC=%s
TRON_DATA=%s
TRON_AGENT_STATE=%s/agent-state.json
TRON_INSTANCE_FILE=%s/INSTANCE.json
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/base-%s.json
TRON_SERVICE=base-%s
TRON_BASE_CONSENSUS_SERVICE=base-consensus-%s
TRON_SNAPSHOT_ENABLED=0
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		productEnvVars(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, req.P2PPort,
		enginePort, consensusP2P, jwtPath, l1, beacon,
		sysListen, sysListen, stateDir,
		opt, etc, data, stateDir, stateDir, env, env, env,
		toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+envPath)

	apiBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-system-agent")

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-base-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-base-%s.service", env)
	rethUnitName := fmt.Sprintf("base-%s.service", env)
	consensusUnitName := fmt.Sprintf("base-consensus-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (base/%s) — Go RPC :%d + Agent API :%d → base-reth-node :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=base
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
Description=RpcNode per-node system-agent (base/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=base
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

	rethUnit := renderBaseRethUnit(env, rethBin, rethData, jwtPath, req, prof, cluster, enginePort, wsPort, discoveryV5)
	consensusUnit := renderBaseConsensusUnit(env, wrapper, etc, rethUnitName)

	if err := os.WriteFile(filepath.Join("/etc/systemd/system", apiUnitName), []byte(apiUnit), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", sysUnitName), []byte(sysUnit), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", rethUnitName), []byte(rethUnit), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", consensusUnitName), []byte(consensusUnit), 0o644); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+apiUnitName, "wrote "+sysUnitName, "wrote "+rethUnitName, "wrote "+consensusUnitName)

	_ = exec.Command("chown", "-R", "nodeop:nodeop", opt, etc, data).Run()
	_ = exec.Command("chown", "root:nodeop", jwtPath).Run()
	_ = os.Chmod(jwtPath, 0o640)

	agentURL := resolvePublicAgentURL(req.AgentPort)
	watch := cluster.WatchSlug
	if watch == "" {
		watch = prof.WatchSlug
	}
	inst := map[string]any{
		"id":             "base-" + env,
		"network":        "base",
		"env":            env,
		"name":           req.Name,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"engine_port":    enginePort,
		"consensus_p2p":  consensusP2P,
		"watch_slug":     watch,
		"chain_id":       cluster.ChainID,
		"l1_rpc_url":     l1,
		"l1_beacon_url":  beacon,
		"agent_url":      agentURL,
		"data_dir":       data,
		"reth_dir":       rethData,
		"etc_dir":        etc,
		"opt_dir":        opt,
		"jwt_path":       jwtPath,
		"units":          []string{rethUnitName, consensusUnitName, apiUnitName, sysUnitName},
		"created_at":     time.Now().UTC().Format(time.RFC3339),
		"hostname":       hostnameOrEmpty(),
	}
	if err := writeJSONFile(filepath.Join(stateDir, "INSTANCE.json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "base-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "base-"+env+".json"), inst); err != nil {
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
		"network":        "base",
		"env":            env,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"rpc_mode":       "go_proxy",
		"agent_url":      agentURL,
		"l1_rpc_url":     l1,
		"l1_beacon_url":  beacon,
		"etc_dir":        etc,
		"data_dir":       data,
		"units":          []string{rethUnitName, consensusUnitName, apiUnitName, sysUnitName},
		"units_started":  false,
		"status":         "provisioned",
		"snapshot":       false,
		"lifecycle":      "ports→install→start→run",
		"message":        "base per-node agents written; base-reth-node+base-consensus activation scheduled",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "base-"+env+".json"),
	}, nil
}

func renderBaseRethUnit(
	env, bin, datadir, jwtPath string,
	req nodeProvisionRequest,
	prof networkPortProfile,
	cluster baseNetwork,
	enginePort, wsPort, discoveryV5 int,
) string {
	rpcPort := req.NodeHTTPPort
	if rpcPort <= 0 {
		rpcPort = prof.NodeHTTP
	}
	p2p := req.P2PPort
	if p2p <= 0 {
		p2p = prof.P2P
	}

	return fmt.Sprintf(`[Unit]
Description=Base base-reth-node (%s, full history) — RpcNode
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
ExecStart=%s node \
  --datadir=%s \
  --log.stdout.format=json \
  --http \
  --http.addr=127.0.0.1 \
  --http.port=%d \
  --http.api=eth,net,web3,txpool,debug \
  --http.corsdomain=* \
  --ws \
  --ws.addr=127.0.0.1 \
  --ws.port=%d \
  --ws.api=eth,net,web3,txpool,debug \
  --ws.origins=* \
  --authrpc.addr=127.0.0.1 \
  --authrpc.port=%d \
  --authrpc.jwtsecret=%s \
  --chain=%s \
  --rollup.sequencer-http=%s \
  --rollup.disable-tx-pool-gossip \
  --max-outbound-peers=100 \
  --discovery.port=%d \
  --discovery.v5.port=%d \
  --port=%d
Restart=on-failure
RestartSec=10
TimeoutStopSec=300
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
LimitNOFILE=1048576
KillSignal=SIGTERM

[Install]
WantedBy=multi-user.target
`, env, bin, datadir, rpcPort, wsPort, enginePort, jwtPath, cluster.RethChain, cluster.SequencerHTTP, p2p, discoveryV5, p2p)
}

func renderBaseConsensusUnit(env, wrapper, etc, rethUnit string) string {
	return fmt.Sprintf(`[Unit]
Description=Base base-consensus (%s) — RpcNode
After=network-online.target %s
Wants=network-online.target
Requires=%s

[Service]
Type=simple
User=nodeop
Group=nodeop
EnvironmentFile=-%s/consensus.env
ExecStart=%s
Restart=on-failure
RestartSec=10
TimeoutStopSec=120
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
LimitNOFILE=1048576
KillSignal=SIGTERM

[Install]
WantedBy=multi-user.target
`, env, rethUnit, rethUnit, etc, wrapper)
}

func activateBaseUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-base-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-base-%s.service", env)
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

func ensureBaseGoRPC(env string, publicPort int) error {
	env = normalizeEnv(env)
	if publicPort <= 0 {
		if p := lookupPortProfile("base", env); p.Public > 0 {
			publicPort = p.Public
		}
	}
	if publicPort > 0 && portOpenLocal(publicPort) {
		return nil
	}
	if err := activateBaseUnits(env); err != nil {
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
	unit := fmt.Sprintf("rpcnode-api-agent-base-%s.service", env)
	jOut, _ := exec.Command("journalctl", "-u", unit, "-n", "16", "--no-pager", "-o", "cat").CombinedOutput()
	msg := fmt.Sprintf("Go RPC :%d not listening after restart %s", publicPort, unit)
	if snip := strings.TrimSpace(string(jOut)); snip != "" {
		msg += " — " + snip
	}

	return fmt.Errorf("%s", msg)
}

func ensureBaseRethInstalled(optPath string) (string, error) {
	dest := filepath.Join(optPath, "bin", "base-reth-node")
	if fileExists(dest) {
		return dest, nil
	}
	bin, err := ensureBinaryFromDocker(baseNodeRethDockerImage, "/app/base-reth-node", dest)
	if err != nil {
		return "", fmt.Errorf("base-reth-node from docker %s: %w", baseNodeRethDockerImage, err)
	}
	link := "/usr/local/bin/base-reth-node"
	_ = os.MkdirAll(filepath.Dir(link), 0o755)
	_ = os.Remove(link)
	_ = os.Symlink(bin, link)

	return bin, nil
}

func ensureBaseConsensusInstalled(optPath string) (string, error) {
	dest := filepath.Join(optPath, "bin", "base-consensus")
	if fileExists(dest) {
		return dest, nil
	}
	bin, err := ensureBinaryFromDocker(baseNodeRethDockerImage, "/app/base-consensus", dest)
	if err != nil {
		return "", fmt.Errorf("base-consensus from docker %s: %w", baseNodeRethDockerImage, err)
	}
	link := "/usr/local/bin/base-consensus"
	_ = os.MkdirAll(filepath.Dir(link), 0o755)
	_ = os.Remove(link)
	_ = os.Symlink(bin, link)

	return bin, nil
}

func readJWTHex(path string) (string, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	hex := strings.TrimSpace(string(b))
	if hex == "" {
		return "", fmt.Errorf("empty jwt at %s", path)
	}

	return hex, nil
}

func writeBaseConsensusEnv(
	etc string,
	cluster baseNetwork,
	l1, beacon, jwtPath, jwtRaw string,
	enginePort, consensusP2P int,
) error {
	body := fmt.Sprintf(`# managed by rpcnode base provision
BASE_NODE_NETWORK=%s
BASE_NODE_L1_ETH_RPC=%s
BASE_NODE_L1_BEACON=%s
BASE_NODE_L1_TRUST_RPC=false
BASE_NODE_L2_ENGINE_RPC=http://127.0.0.1:%d
BASE_NODE_L2_ENGINE_AUTH=%s
BASE_NODE_L2_ENGINE_AUTH_RAW=%s
BASE_NODE_P2P_LISTEN_IP=0.0.0.0
BASE_NODE_P2P_ADVERTISE_TCP_PORT=%d
BASE_NODE_P2P_ADVERTISE_UDP_PORT=%d
BASE_NODE_P2P_BOOTNODES=%s
BASE_NODE_LOG_VERBOSITY=3
BASE_NODE_LOG_FORMAT=json
`,
		cluster.NetworkFlag, l1, beacon, enginePort, jwtPath, strconv.Quote(jwtRaw),
		consensusP2P, consensusP2P, baseBootnodes)
	if err := os.WriteFile(filepath.Join(etc, "consensus.env"), []byte(body), 0o640); err != nil {
		return err
	}

	return nil
}

func renderBaseConsensusWrapper(consensusBin string) string {
	// Write JWT before wait. Official docker waits for HTTP 401 on authrpc;
	// base-reth often returns 200 JSON-RPC error instead, so the loop never
	// exits and floods jwt-validator ("Authorization header is missing")
	// while consensus never execs. Wait on TCP only — no unauthenticated
	// engine HTTP.
	return fmt.Sprintf(`#!/bin/bash
set -eu
get_public_ip() {
  local PROVIDERS=(
    "http://ifconfig.me"
    "http://api.ipify.org"
    "http://ipecho.net/plain"
    "http://v4.ident.me"
  )
  local provider IP
  for provider in "${PROVIDERS[@]}"; do
    IP=$(curl -s --max-time 10 --connect-timeout 5 "$provider" || true)
    if [[ $IP =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      echo "$IP"
      return 0
    fi
  done
  return 1
}
if [[ -z "${BASE_NODE_L2_ENGINE_AUTH:-}" || -z "${BASE_NODE_L2_ENGINE_AUTH_RAW:-}" ]]; then
  echo "BASE_NODE_L2_ENGINE_AUTH* required" >&2
  exit 1
fi
mkdir -p "$(dirname "$BASE_NODE_L2_ENGINE_AUTH")"
printf '%%s\n' "$BASE_NODE_L2_ENGINE_AUTH_RAW" > "$BASE_NODE_L2_ENGINE_AUTH"
chmod 640 "$BASE_NODE_L2_ENGINE_AUTH" || true
ENGINE_HTTP="${BASE_NODE_L2_ENGINE_RPC/ws:\/\//http:\/\/}"
ENGINE_HTTP="${ENGINE_HTTP/wss:\/\//https:\/\/}"
ENGINE_HOSTPORT="${ENGINE_HTTP#http://}"
ENGINE_HOSTPORT="${ENGINE_HOSTPORT#https://}"
ENGINE_HOSTPORT="${ENGINE_HOSTPORT%%/*}"
ENGINE_HOST="${ENGINE_HOSTPORT%%:*}"
ENGINE_PORT="${ENGINE_HOSTPORT##*:}"
until (echo >/dev/tcp/"$ENGINE_HOST"/"$ENGINE_PORT") 2>/dev/null; do
  echo "waiting for base-reth-node engine ${ENGINE_HOST}:${ENGINE_PORT}"
  sleep 2
done
if PUBLIC_IP=$(get_public_ip); then
  export BASE_NODE_P2P_ADVERTISE_IP="$PUBLIC_IP"
  echo "BASE_NODE_P2P_ADVERTISE_IP=$PUBLIC_IP"
fi
exec %s node
`, consensusBin)
}

func writeBaseConsensusWrapper(path, consensusBin string) error {
	if err := os.WriteFile(path, []byte(renderBaseConsensusWrapper(consensusBin)), 0o755); err != nil {
		return err
	}

	return nil
}

func rewriteBaseUnits(prof networkPortProfile, req nodeProvisionRequest) error {
	rethBin, err := ensureBaseRethInstalled(prof.OptPath)
	if err != nil {
		return err
	}
	consensusBin, err := ensureBaseConsensusInstalled(prof.OptPath)
	if err != nil {
		return err
	}
	jwtPath := filepath.Join(prof.EtcPath, "jwt.hex")
	if err := ensureJWT(jwtPath); err != nil {
		return err
	}
	jwtRaw, err := readJWTHex(jwtPath)
	if err != nil {
		return err
	}
	cluster := lookupBaseNetwork(prof.Env)
	l1 := defaultL1RPCURLFor(baseL1Env(prof.Env))
	beacon := defaultL1BeaconURLFor(baseL1Env(prof.Env))
	engine := prof.SolHTTP
	if engine <= 0 {
		engine = 8572
	}
	consensusP2P := prof.PBFTHTTP
	if consensusP2P <= 0 {
		consensusP2P = 9023
	}
	wsPort := prof.Metrics
	if wsPort <= 0 {
		wsPort = prof.NodeHTTP + 10
	}
	if wsPort <= 0 || wsPort > 65535 {
		wsPort = 8581
	}
	discoveryV5 := 9203
	if normalizeEnv(prof.Env) == "sepolia" {
		discoveryV5 = 9213
	}
	if err := writeBaseConsensusEnv(prof.EtcPath, cluster, l1, beacon, jwtPath, jwtRaw, engine, consensusP2P); err != nil {
		return err
	}
	wrapper := filepath.Join(prof.OptPath, "bin", "run-base-consensus.sh")
	if err := writeBaseConsensusWrapper(wrapper, consensusBin); err != nil {
		return err
	}
	rethData := filepath.Join(prof.DataPath, "reth")
	rethUnitName := fmt.Sprintf("base-%s.service", prof.Env)
	consensusUnitName := fmt.Sprintf("base-consensus-%s.service", prof.Env)
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", rethUnitName),
		[]byte(renderBaseRethUnit(prof.Env, rethBin, rethData, jwtPath, req, prof, cluster, engine, wsPort, discoveryV5)), 0o644); err != nil {
		return err
	}

	return os.WriteFile(filepath.Join("/etc/systemd/system", consensusUnitName),
		[]byte(renderBaseConsensusUnit(prof.Env, wrapper, prof.EtcPath, rethUnitName)), 0o644)
}
