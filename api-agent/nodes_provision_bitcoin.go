package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Bitcoin provision on the unified Server agent.
// Writes per-node units (network-scoped names) using rpcnode-*-agent binaries
// with TRON_NETWORK=bitcoin — does not install a separate bitcoin agent product.
// Does not stop host Server agent or TRON per-node units.

func provisionBitcoinNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
	env := req.Env
	steps := []string{}

	// Identity=network → upstream always from bitcoin profile (e.g. mainnet :8332).
	if prof.NodeHTTP > 0 {
		req.NodeHTTPPort = prof.NodeHTTP
	}
	if prof.P2P > 0 {
		req.P2PPort = prof.P2P
	}

	opt := prof.OptPath
	etc := prof.EtcPath
	data := resolveNetworkRoleDir(req, "bitcoin", env, "blockchain", prof.DataPath)
	indexDir := resolveNetworkRoleDir(req, "bitcoin", env, "index", filepath.Join(prof.DataPath, "index"))
	stateDir := fmt.Sprintf("/var/lib/rpcnode/bitcoin-%s", env)

	coreData := bitcoinCoreDatadirSetting(data, env)
	for _, d := range []string{opt, etc, data, coreData, indexDir, stateDir, "/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system"} {
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

	bin, err := ensureBitcoindInstalled(opt)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "bitcoind="+bin)
	_ = ensureNodeopUser()

	confPath, err := ensureBitcoinConf(prof, req)
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

	sysListen := systemAgentListenPort("bitcoin", env)
	// Cookie lives in the chain dir (DataPath), not under a double-nested folder.
	cookie := filepath.Join(data, ".cookie")

	// Same env var names as TRON path — unified system-agent reads TRON_NETWORK.
	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (bitcoin)
# Per-node agent: Go RPC :%d → bitcoind :%d; Agent API :%d
%sTRON_NETWORK=bitcoin
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
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/bitcoin-%s.json
TRON_SERVICE=bitcoin-%s
TRON_COOKIE=%s
TRON_SNAPSHOT_ENABLED=0
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		req.PublicPort, req.NodeHTTPPort, req.AgentPort,
		productEnvVars(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, req.P2PPort,
		sysListen, sysListen, stateDir,
		opt, etc, data, stateDir, stateDir, env, env, cookie,
		toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+envPath)

	apiBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-system-agent")

	// Network-scoped unit names — coexist with tron-{env} on the same host.
	apiUnitName := fmt.Sprintf("rpcnode-api-agent-bitcoin-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-bitcoin-%s.service", env)
	nodeUnitName := fmt.Sprintf("bitcoin-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (bitcoin/%s) — Go RPC :%d + Agent API :%d → bitcoind :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=bitcoin
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
Description=RpcNode per-node system-agent (bitcoin/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=bitcoin
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
	if err := os.WriteFile(nodeUnitPath, []byte(renderBitcoindUnit(prof, confPath)), 0o644); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+nodeUnitPath)

	agentURL := resolvePublicAgentURL(req.AgentPort)
	inst := map[string]any{
		"id":             "bitcoin-" + env,
		"network":        "bitcoin",
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
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "bitcoin-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "bitcoin-"+env+".json"), inst); err != nil {
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
		"network":        "bitcoin",
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
		"message":        "bitcoin per-node agents written; unit activation scheduled (Server agent left running)",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "bitcoin-"+env+".json"),
	}, nil
}

func activateBitcoinUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-bitcoin-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-bitcoin-%s.service", env)
	// NEVER stop host tip Server (rpcnode-api-agent.service). Older code stopped
	// "conflicting" tip and left panel agents at :39290 connection refused.
	keepHostTipOnLeafActivate(apiUnit)
	units := []string{sysUnit, apiUnit}
	_ = exec.Command("systemctl", "daemon-reload").Run()
	for _, u := range units {
		_ = exec.Command("systemctl", "enable", u).Run()
		if err := exec.Command("systemctl", "restart", u).Run(); err != nil {
			return fmt.Errorf("restart %s: %w", u, err)
		}
	}
	// Leave TRON per-node units alone. bitcoind start is pipeline / disk gate.
	return nil
}

// keepHostTipOnLeafActivate — tip bootstrap units stay up; leaf has its own ports.
func keepHostTipOnLeafActivate(forLeafUnit string) {
	for _, u := range []string{
		"rpcnode-api-agent.service",
		"tron-api-agent.service",
		"rpcnode-system-agent.service",
		"tron-system-agent.service",
	} {
		if u == forLeafUnit {
			continue
		}
		active, _ := exec.Command("systemctl", "is-active", u).CombinedOutput()
		if strings.TrimSpace(string(active)) == "active" {
			fmt.Fprintf(os.Stderr, "activate leaf: keeping host tip %s (leaf %s)\n", u, forLeafUnit)
		}
	}
}

// ensureBitcoinGoRPC brings per-node api-agent up so confirmed public_port (Go RPC) listens.
// Call after provision and on every nodes/start — Agent API alone (:39390) is not enough.
// When Go RPC already listens, skip restart — restarting our own unit kills the HTTP request
// (signal: terminated → panel sees go_rpc_down).
func ensureBitcoinGoRPC(env string, publicPort int) error {
	env = normalizeEnv(env)
	if publicPort <= 0 {
		if p := lookupPortProfile("bitcoin", env); p.Public > 0 {
			publicPort = p.Public
		}
	}
	if publicPort > 0 && portOpenLocal(publicPort) {
		return nil
	}
	if err := activateBitcoinUnits(env); err != nil {
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
	unit := fmt.Sprintf("rpcnode-api-agent-bitcoin-%s.service", env)
	jOut, _ := exec.Command("journalctl", "-u", unit, "-n", "16", "--no-pager", "-o", "cat").CombinedOutput()
	msg := fmt.Sprintf("Go RPC :%d not listening after restart %s", publicPort, unit)
	if snip := strings.TrimSpace(string(jOut)); snip != "" {
		msg += " — " + snip
	}

	return fmt.Errorf("%s", msg)
}

func portOpenLocal(port int) bool {
	if port <= 0 {
		return false
	}
	c, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), 400*time.Millisecond)
	if err != nil {
		return false
	}
	_ = c.Close()

	return true
}

func memTotalMB() int {
	b, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return 0
	}

	for _, ln := range strings.Split(string(b), "\n") {
		if !strings.HasPrefix(ln, "MemTotal:") {
			continue
		}

		fields := strings.Fields(ln)
		if len(fields) < 2 {
			return 0
		}

		kb, err := strconv.Atoi(fields[1])
		if err != nil {
			return 0
		}

		return kb / 1024
	}

	return 0
}

// bitcoinDBCacheMB — ~25% RAM, floor 2048 MiB, cap 8192; default 4096 when RAM unknown.
func bitcoinDBCacheMB() int {
	mem := memTotalMB()
	if mem <= 0 {
		return 4096
	}

	c := mem / 4
	if c < 2048 {
		c = 2048
	}
	if c > 8192 {
		c = 8192
	}

	return c
}

// bitcoinDBCacheMBForEnv — regtest/signet/testnet must not steal multi‑GiB dbcache
// from a co-hosted mainnet IBD (OOM → unit exit-code, empty-looking journal).
func bitcoinDBCacheMBForEnv(env string) int {
	switch normalizeEnv(env) {
	case "regtest":
		return 256
	case "signet", "testnet4", "testnet":
		return 512
	default:
		return bitcoinDBCacheMB()
	}
}

func isAgentGeneratedBitcoinConf(body []byte) bool {
	s := string(body)

	return strings.Contains(s, "Generated by rpcnode") ||
		strings.Contains(s, "Generated by bitcoin-api-agent")
}

func preserveBitcoinRPCAuthLines(old []byte) []string {
	var out []string
	for _, ln := range strings.Split(string(old), "\n") {
		t := strings.TrimSpace(ln)
		if strings.HasPrefix(t, "rpcauth=") {
			out = append(out, t)
		}
	}

	return out
}

func renderBitcoinConf(prof networkPortProfile, req nodeProvisionRequest) string {
	return renderBitcoinConfWithCache(prof, req, bitcoinDBCacheMBForEnv(prof.Env))
}

func renderBitcoinConfWithCache(prof networkPortProfile, req nodeProvisionRequest, dbcacheMB int) string {
	p2p := req.P2PPort
	if p2p <= 0 {
		p2p = prof.P2P
	}
	rpc := req.NodeHTTPPort
	if rpc <= 0 {
		rpc = prof.NodeHTTP
	}
	if dbcacheMB <= 0 {
		dbcacheMB = 4096
	}

	var b strings.Builder
	b.WriteString("# Generated by rpcnode provision (unified agent) — bitcoin\n")
	b.WriteString("# Profile: full node + txindex (prune forbidden). txindex ≈ extra disk vs chain-only.\n")
	b.WriteString("server=1\n")
	b.WriteString("txindex=1\n")
	b.WriteString("prune=0\n")
	b.WriteString("disablewallet=1\n")
	b.WriteString("daemon=0\n")
	fmt.Fprintf(&b, "datadir=%s\n", bitcoinCoreDatadirSetting(prof.DataPath, prof.Env))
	fmt.Fprintf(&b, "dbcache=%d\n", dbcacheMB)
	// High-load from day one: Go-proxy fan-out of thousands concurrent JSON-RPC.
	// Core defaults 4/16 and prior 32/256 still overflow under heavy public RPC.
	b.WriteString("rpcthreads=64\n")
	b.WriteString("rpcworkqueue=1024\n")
	b.WriteString("maxconnections=125\n")
	b.WriteString("rest=1\n")
	if prof.ChainFlag != "" {
		b.WriteString(prof.ChainFlag + "\n")
	}
	// Core requires network-only keys (port/rpcport/…) under [regtest]/[signet]/…
	if sec := bitcoinConfNetworkSection(prof.Env); sec != "" {
		b.WriteString("\n[" + sec + "]\n")
	}
	fmt.Fprintf(&b, "port=%d\n", p2p)
	fmt.Fprintf(&b, "rpcport=%d\n", rpc)
	b.WriteString("rpcbind=127.0.0.1\n")
	b.WriteString("rpcallowip=127.0.0.1\n")
	if prof.ZMQRawBlock > 0 {
		fmt.Fprintf(&b, "zmqpubrawblock=tcp://127.0.0.1:%d\n", prof.ZMQRawBlock)
	}
	if prof.ZMQRawTx > 0 {
		fmt.Fprintf(&b, "zmqpubrawtx=tcp://127.0.0.1:%d\n", prof.ZMQRawTx)
	}

	return b.String()
}

// bitcoinConfNetworkSection — INI section for network-specific options (empty = main).
func bitcoinConfNetworkSection(env string) string {
	switch normalizeEnv(env) {
	case "regtest":
		return "regtest"
	case "signet":
		return "signet"
	case "testnet4":
		return "testnet4"
	case "testnet", "testnet3":
		return "test"
	default:
		return ""
	}
}

// ensureBitcoinConf creates /etc/bitcoin/<env> + datadir and writes bitcoin.conf.
// Missing or agent-generated confs are (re)written; hand-edited left alone; rpcauth preserved.
func ensureBitcoinConf(prof networkPortProfile, req nodeProvisionRequest) (string, error) {
	etc := prof.EtcPath
	data := prof.DataPath
	if etc == "" {
		etc = fmt.Sprintf("/etc/bitcoin/%s", normalizeEnv(prof.Env))
	}
	if data == "" {
		data = fmt.Sprintf("/data/bitcoin/%s", normalizeEnv(prof.Env))
	}
	coreData := bitcoinCoreDatadirSetting(data, prof.Env)
	for _, d := range []string{etc, data, coreData} {
		if d == "" {
			continue
		}
		if err := os.MkdirAll(d, 0o755); err != nil {
			return "", fmt.Errorf("mkdir %s: %w", d, err)
		}
	}

	confPath := filepath.Join(etc, "bitcoin.conf")
	body := renderBitcoinConf(prof, req)
	write := !fileExists(confPath)

	if fileExists(confPath) {
		old, err := os.ReadFile(confPath)
		if err == nil && isAgentGeneratedBitcoinConf(old) {
			write = true
			for _, auth := range preserveBitcoinRPCAuthLines(old) {
				if !strings.Contains(body, auth) {
					body = strings.TrimRight(body, "\n") + "\n" + auth + "\n"
				}
			}
		}
	}

	if write {
		if err := os.WriteFile(confPath, []byte(body), 0o644); err != nil {
			return confPath, fmt.Errorf("write %s: %w", confPath, err)
		}
	}

	_ = exec.Command("chown", "-R", "nodeop:nodeop", etc, data).Run()
	_ = os.Chmod(confPath, 0o644)

	return confPath, nil
}

func renderBitcoindUnit(prof networkPortProfile, confPath string) string {
	bin := resolveBitcoindBinary(prof.OptPath)
	return fmt.Sprintf(`[Unit]
Description=Bitcoin Core (%s) — RpcNode
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
ExecStart=%s -conf=%s
# No ExecStop: panel remove calls bitcoin-cli stop, then SIGKILL.
# systemctl stop + ExecStop hangs on flush / Job canceled.
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

func resolveBitcoindBinary(optPath string) string {
	for _, cand := range []string{
		filepath.Join(optPath, "bin", "bitcoind"),
		"/opt/bitcoin/bin/bitcoind",
		"/usr/local/bin/bitcoind",
	} {
		if fileExists(cand) {
			return cand
		}
	}
	return filepath.Join(optPath, "bin", "bitcoind")
}

func resolveBitcoinCLI(optPath, bitcoindBin string) string {
	for _, cand := range []string{
		filepath.Join(filepath.Dir(bitcoindBin), "bitcoin-cli"),
		filepath.Join(optPath, "bin", "bitcoin-cli"),
		"/opt/bitcoin/bin/bitcoin-cli",
		"/usr/local/bin/bitcoin-cli",
	} {
		if fileExists(cand) {
			return cand
		}
	}
	return filepath.Join(filepath.Dir(bitcoindBin), "bitcoin-cli")
}

// defaultBitcoinCoreVersion — Core 28+ is required for chain=testnet4 (BIP94).
// 27.1 exits with "Unknown chain testnet4".
const defaultBitcoinCoreVersion = "28.1"

func parseBitcoinCoreVersion(s string) (major, minor int, ok bool) {
	low := strings.ToLower(s)
	rest := low
	if i := strings.Index(low, "version"); i >= 0 {
		rest = strings.TrimSpace(low[i+len("version"):])
	}
	rest = strings.TrimPrefix(rest, "v")
	var maj, min int
	n, err := fmt.Sscanf(rest, "%d.%d", &maj, &min)
	if err != nil || n < 1 {
		return 0, 0, false
	}
	return maj, min, true
}

func bitcoindVersionAtLeast(bin string, major, minor int) bool {
	out, err := exec.Command(bin, "-version").CombinedOutput()
	if err != nil {
		return false
	}
	maj, min, ok := parseBitcoinCoreVersion(string(out))
	if !ok {
		return false
	}
	if maj != major {
		return maj > major
	}
	return min >= minor
}

// ensureBitcoindInstalled finds or downloads Bitcoin Core into optPath/bin.
func ensureBitcoindInstalled(optPath string) (string, error) {
	if bin := resolveBitcoindBinary(optPath); fileExists(bin) && bitcoindVersionAtLeast(bin, 28, 0) {
		return bin, nil
	}
	if p, err := exec.LookPath("bitcoind"); err == nil && p != "" && bitcoindVersionAtLeast(p, 28, 0) {
		return p, nil
	}

	ver := envOr("BITCOIN_CORE_VERSION", defaultBitcoinCoreVersion)
	arch := "x86_64-linux-gnu"
	switch runtimeGOARCH() {
	case "arm64", "aarch64":
		arch = "aarch64-linux-gnu"
	}
	name := fmt.Sprintf("bitcoin-%s-%s.tar.gz", ver, arch)
	url := preferVendoredArtifact("bitcoin", "mainnet",
		fmt.Sprintf("https://bitcoincore.org/bin/bitcoin-core-%s/%s", ver, name))
	tmp := filepath.Join(os.TempDir(), name)
	logDownload("GET", url, "bitcoin dest="+tmp)
	destBin := filepath.Join(optPath, "bin")
	if err := os.MkdirAll(destBin, 0o755); err != nil {
		return "", fmt.Errorf("mkdir %s: %w", destBin, err)
	}

	extractDir := filepath.Join(os.TempDir(), "rpcnode-bitcoin-"+ver)
	_ = os.RemoveAll(extractDir)
	cmd := exec.Command("bash", "-lc", fmt.Sprintf(
		`set -euo pipefail
if ! command -v curl >/dev/null; then echo "curl required to fetch bitcoind" >&2; exit 1; fi
curl -fsSL --connect-timeout 30 --max-time 600 -o %q %q
mkdir -p %q
tar -xzf %q -C %q
install -m 755 %q/bitcoin-%s/bin/bitcoind %q/bitcoind
install -m 755 %q/bitcoin-%s/bin/bitcoin-cli %q/bitcoin-cli
rm -rf %q %q
`, tmp, url, extractDir, tmp, extractDir, extractDir, ver, destBin, extractDir, ver, destBin, extractDir, tmp))
	out, err := cmd.CombinedOutput()
	logDownloadDone("GET", url, "bitcoin dest="+tmp, out, err)
	if err != nil {
		return "", fmt.Errorf("install bitcoind %s: %v (%s)", ver, err, strings.TrimSpace(string(out)))
	}
	bin := filepath.Join(destBin, "bitcoind")
	if !fileExists(bin) {
		return "", fmt.Errorf("bitcoind missing after install at %s", bin)
	}
	return bin, nil
}

func rewriteBitcoinUnitBinary(prof networkPortProfile, _ string) error {
	req := nodeProvisionRequest{
		Network: "bitcoin", Env: prof.Env,
		NodeHTTPPort: prof.NodeHTTP, P2PPort: prof.P2P,
	}
	confPath, err := ensureBitcoinConf(prof, req)
	if err != nil {
		return err
	}
	unitPath := filepath.Join("/etc/systemd/system", prof.ServiceUnit)
	return os.WriteFile(unitPath, []byte(renderBitcoindUnit(prof, confPath)), 0o644)
}

// ensureNodeopUser creates the nodeop system user (chain clients) and grants
// journal read groups so journalctl / bootstrap % parsers work when units run
// as User=nodeop (or tooling runs as nodeop). Idempotent — always repairs groups
// even if the user already exists (old installs skipped usermod).
func ensureNodeopUser() error {
	if _, err := exec.LookPath("id"); err != nil {
		return nil
	}
	if exec.Command("id", "nodeop").Run() != nil {
		_ = exec.Command("useradd", "--system", "--home", "/var/lib/nodeop",
			"--shell", "/usr/sbin/nologin", "nodeop").Run()
	}
	ensureNodeopJournalGroups()

	return nil
}

// ensureNodeopJournalGroups — systemd-journal (+ adm when present) so nodeop can
// read unit journals. Installer runs as root/sudo; call on every provision.
func ensureNodeopJournalGroups() {
	if exec.Command("id", "nodeop").Run() != nil {
		return
	}
	for _, g := range []string{"systemd-journal", "adm"} {
		if exec.Command("getent", "group", g).Run() != nil {
			continue
		}
		// Already a member?
		out, _ := exec.Command("id", "-nG", "nodeop").CombinedOutput()
		groups := " " + strings.TrimSpace(string(out)) + " "
		if strings.Contains(groups, " "+g+" ") {
			continue
		}
		_ = exec.Command("usermod", "-aG", g, "nodeop").Run()
	}
}

func runtimeGOARCH() string {
	out, err := exec.Command("uname", "-m").CombinedOutput()
	if err != nil {
		return "x86_64"
	}
	return strings.TrimSpace(string(out))
}

func bitcoinSysListen(env string) int {
	switch env {
	case "testnet4":
		return 8192
	case "signet":
		return 8193
	case "regtest":
		return 8194
	default:
		return 8191
	}
}

func resolveAgentBinary(binDir, toolkitDir, name string) string {
	// ExecStart must be /opt/rpcnode/bin/rpcnode-* (real binary).
	// /usr/local/bin and tron-* are PATH/compat symlinks only — never write them into units.
	canonOpt := filepath.Join("/opt/rpcnode/bin", name)
	if fileExists(canonOpt) {
		return canonOpt
	}
	names := []string{name}
	switch name {
	case "rpcnode-api-agent":
		names = append(names, "tron-api-agent")
	case "rpcnode-system-agent":
		names = append(names, "tron-system-agent")
	}
	for _, n := range names {
		for _, cand := range []string{
			filepath.Join(binDir, n),
			filepath.Join("/opt/rpcnode/bin", n),
			filepath.Join(toolkitDir, "bin", n),
			// /usr/local/bin last — if only a symlink exists, resolve to /opt when possible.
			filepath.Join("/usr/local/bin", n),
		} {
			if !fileExists(cand) {
				continue
			}
			if resolved, err := filepath.EvalSymlinks(cand); err == nil && resolved != "" {
				if filepath.Base(resolved) == name || strings.Contains(filepath.Base(resolved), "rpcnode-") {
					// Prefer canonical name beside the resolved real file.
					dir := filepath.Dir(resolved)
					canon := filepath.Join(dir, name)
					if fileExists(canon) {
						return canon
					}
					return resolved
				}
			}
			dir := filepath.Dir(cand)
			if dir == "/usr/local/bin" {
				continue
			}
			canon := filepath.Join(dir, name)
			if fileExists(canon) {
				return canon
			}
			return filepath.Join(binDir, name)
		}
	}
	return filepath.Join("/opt/rpcnode/bin", name)
}

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func writeJSONFile(path string, doc map[string]any) error {
	raw, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(raw, '\n'), 0o644)
}

func hostnameOrEmpty() string {
	h, err := os.Hostname()
	if err != nil {
		return ""
	}
	return h
}
