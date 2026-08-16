package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// Solana provision on the unified Server agent.
// Writes per-node units with TRON_NETWORK=solana — not a separate agent product.

func provisionSolanaNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
	env := req.Env
	steps := []string{}
	cluster := lookupSolanaCluster(env)

	if prof.NodeHTTP > 0 {
		req.NodeHTTPPort = prof.NodeHTTP
	}
	if prof.P2P > 0 {
		req.P2PPort = prof.P2P
	}

	opt := prof.OptPath
	etc := prof.EtcPath
	data := prof.DataPath
	stateDir := fmt.Sprintf("/var/lib/rpcnode/solana-%s", env)
	ledger, accounts, snapshots := resolveSolanaDiskDirs(req, data, env)
	if err := ensureSolanaLayoutDirs(ledger, accounts, snapshots); err != nil {
		return nil, err
	}
	logPath := filepath.Join(data, "solana-"+env+".log")
	// Keep log under canonical data (or ledger parent) so journal paths stay stable.
	if data == "" || !dirWritableParent(data) {
		logPath = filepath.Join(filepath.Dir(ledger), "solana-"+env+".log")
	}

	for _, d := range []string{opt, etc, data, ledger, accounts, snapshots, stateDir, "/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system"} {
		if d == "" {
			continue
		}
		if err := os.MkdirAll(d, 0o755); err != nil {
			return nil, fmt.Errorf("mkdir %s: %w", d, err)
		}
		steps = append(steps, "mkdir "+d)
	}
	steps = append(steps, fmt.Sprintf("disk_layout ledger=%s accounts=%s snapshots=%s", ledger, accounts, snapshots))

	bin, err := ensureSolanaBinaryInstalled(opt, cluster.Localnet)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "solana_bin="+bin)
	if !cluster.Localnet {
		if err := ensureSolanaBinaryCaps(bin); err != nil {
			steps = append(steps, "setcap_warn="+err.Error())
		} else {
			steps = append(steps, "setcap="+bin)
		}
	}
	_ = ensureNodeopUser()
	_ = ensureSolanaSysctl()

	identity, err := ensureSolanaIdentity(etc, env, cluster.Localnet)
	if err != nil {
		return nil, err
	}
	if identity != "" {
		steps = append(steps, "identity="+identity)
	}

	scriptPath, err := ensureSolanaRunScript(prof, req, cluster, bin, identity, ledger, accounts, snapshots, logPath)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+scriptPath)

	binDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))
	if token == "" {
		if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
			token = strings.TrimSpace(string(b))
		}
	}

	sysListen := systemAgentListenPort("solana", env)

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (solana)
# Per-node agent: Go RPC :%d → agave :%d; Agent API :%d
%sTRON_NETWORK=solana
TRON_NODE_HTTP_HOST=127.0.0.1
TRON_NODE_HTTP_PORT=%d
TRON_P2P_PORT=%d
TRON_SYSTEM_AGENT_LISTEN=127.0.0.1:%d
TRON_SYSTEM_AGENT_URL=http://127.0.0.1:%d
TRON_STATE_DIR=%s
TRON_OPT=%s
TRON_ETC=%s
TRON_DATA=%s
SOLANA_LEDGER_DIR=%s
SOLANA_ACCOUNTS_DIR=%s
SOLANA_SNAPSHOTS_DIR=%s
TRON_AGENT_STATE=%s/agent-state.json
TRON_INSTANCE_FILE=%s/INSTANCE.json
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/solana-%s.json
TRON_SERVICE=solana-%s
TRON_SNAPSHOT_ENABLED=0
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		req.PublicPort, req.NodeHTTPPort, req.AgentPort,
		productEnvVars(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, req.P2PPort,
		sysListen, sysListen, stateDir,
		opt, etc, data, ledger, accounts, snapshots, stateDir, stateDir, env, env,
		toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+envPath)

	apiBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(binDir, toolkitDir, "rpcnode-system-agent")

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-solana-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-solana-%s.service", env)
	nodeUnitName := fmt.Sprintf("solana-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (solana/%s) — Go RPC :%d + Agent API :%d → Agave :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=solana
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
Description=RpcNode per-node system-agent (solana/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=solana
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
	if err := os.WriteFile(nodeUnitPath, []byte(renderSolanaUnit(env, scriptPath, cluster.Localnet)), 0o644); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+nodeUnitPath)

	chownArgs := []string{"-R", "nodeop:nodeop", opt, etc, data, ledger, accounts, snapshots}
	_ = exec.Command("chown", chownArgs...).Run()

	agentURL := resolvePublicAgentURL(req.AgentPort)
	watch := cluster.WatchSlug
	if watch == "" {
		watch = prof.WatchSlug
	}
	inst := map[string]any{
		"id":             "solana-" + env,
		"network":        "solana",
		"env":            env,
		"name":           req.Name,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"watch_slug":     watch,
		"cluster":        cluster.Cluster,
		"agent_url":      agentURL,
		"data_dir":       data,
		"ledger_dir":     ledger,
		"accounts_dir":   accounts,
		"snapshots_dir":  snapshots,
		"etc_dir":        etc,
		"opt_dir":        opt,
		"units":          []string{nodeUnitName, apiUnitName, sysUnitName},
		"created_at":     time.Now().UTC().Format(time.RFC3339),
		"hostname":       hostnameOrEmpty(),
	}
	if err := writeJSONFile(filepath.Join(stateDir, "INSTANCE.json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "solana-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "solana-"+env+".json"), inst); err != nil {
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
		"network":        "solana",
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
		"ledger_dir":     ledger,
		"accounts_dir":   accounts,
		"snapshots_dir":  snapshots,
		"units":          []string{nodeUnitName, apiUnitName, sysUnitName},
		"units_started":  false,
		"status":         "provisioned",
		"snapshot":       false,
		"lifecycle":      "ports→install→start→run(catchup)",
		"message":        "solana per-node agents written; unit activation scheduled (Server agent left running)",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "solana-"+env+".json"),
	}, nil
}

func activateSolanaUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-solana-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-solana-%s.service", env)
	// Solana per-node agents use 3959x — do NOT stop host Server agent (:39090).
	// (Bitcoin path may stop host when it collides with :39390.)
	_ = exec.Command("systemctl", "daemon-reload").Run()
	for _, u := range []string{sysUnit, apiUnit} {
		_ = exec.Command("systemctl", "enable", u).Run()
		if err := exec.Command("systemctl", "restart", u).Run(); err != nil {
			return fmt.Errorf("restart %s: %w", u, err)
		}
	}
	// Ensure host control-plane stays up for panel plan/provision of other envs.
	_ = exec.Command("systemctl", "start", "rpcnode-api-agent.service").Run()

	return nil
}

func ensureSolanaGoRPC(env string, publicPort int) error {
	env = normalizeEnv(env)
	if publicPort <= 0 {
		if p := lookupPortProfile("solana", env); p.Public > 0 {
			publicPort = p.Public
		}
	}
	if publicPort > 0 && portOpenLocal(publicPort) {
		return nil
	}
	if err := activateSolanaUnits(env); err != nil {
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
	unit := fmt.Sprintf("rpcnode-api-agent-solana-%s.service", env)
	jOut, _ := exec.Command("journalctl", "-u", unit, "-n", "16", "--no-pager", "-o", "cat").CombinedOutput()
	msg := fmt.Sprintf("Go RPC :%d not listening after restart %s", publicPort, unit)
	if snip := strings.TrimSpace(string(jOut)); snip != "" {
		msg += " — " + snip
	}

	return fmt.Errorf("%s", msg)
}

func renderSolanaUnit(env, scriptPath string, localnet bool) string {
	desc := fmt.Sprintf("Solana Agave RPC (%s, non-voting) — RpcNode", env)
	timeout := 600
	restartSec := 30
	if localnet {
		desc = fmt.Sprintf("Solana test-validator (%s) — RpcNode", env)
		timeout = 120
		restartSec = 5
	}

	// Agave 4.x enables XDP and requires CAP_NET_RAW + CAP_NET_ADMIN as non-root.
	caps := ""
	if !localnet {
		caps = `AmbientCapabilities=CAP_NET_RAW CAP_NET_ADMIN
CapabilityBoundingSet=CAP_NET_RAW CAP_NET_ADMIN
`
	}

	return fmt.Sprintf(`[Unit]
Description=%s
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
%sExecStart=%s
Restart=on-failure
RestartSec=%d
TimeoutStopSec=%d
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
LimitNOFILE=1000000
LimitMEMLOCK=infinity
KillSignal=SIGTERM

[Install]
WantedBy=multi-user.target
`, desc, caps, scriptPath, restartSec, timeout)
}

func dirWritableParent(p string) bool {
	p = strings.TrimSpace(p)
	if p == "" {
		return false
	}
	if st, err := os.Stat(p); err == nil && st.IsDir() {
		return true
	}
	return os.MkdirAll(p, 0o755) == nil
}

func ensureSolanaRunScript(
	prof networkPortProfile,
	req nodeProvisionRequest,
	cluster solanaCluster,
	bin, identity, ledger, accounts, snapshots, logPath string,
) (string, error) {
	opt := prof.OptPath
	if opt == "" {
		opt = fmt.Sprintf("/opt/solana/%s", normalizeEnv(prof.Env))
	}
	if err := os.MkdirAll(opt, 0o755); err != nil {
		return "", err
	}
	scriptPath := filepath.Join(opt, "run-validator.sh")
	binDir := filepath.Dir(bin)
	rpcPort := req.NodeHTTPPort
	if rpcPort <= 0 {
		rpcPort = prof.NodeHTTP
	}
	p2p := req.P2PPort
	if p2p <= 0 {
		p2p = prof.P2P
	}

	var body strings.Builder
	body.WriteString("#!/usr/bin/env bash\nset -euo pipefail\n")
	body.WriteString("# Generated by rpcnode provision (solana) — do not hand-edit; re-provision to refresh.\n")
	body.WriteString(fmt.Sprintf("export PATH=%q:${PATH}\n", binDir))
	body.WriteString(fmt.Sprintf("BIN=%q\n", bin))
	body.WriteString("if [[ ! -x \"$BIN\" ]]; then echo \"solana binary missing: $BIN\" >&2; exit 127; fi\n")

	if cluster.Localnet {
		faucet := cluster.FaucetPort
		if faucet <= 0 {
			faucet = 19900
		}
		body.WriteString(fmt.Sprintf(`exec "$BIN" \
  --bind-address 127.0.0.1 \
  --rpc-port %d \
  --faucet-port %d \
  --ledger %s \
  --log
`, rpcPort, faucet, shellQuote(ledger)))
	} else {
		body.WriteString("exec \"$BIN\" \\\n")
		body.WriteString(fmt.Sprintf("  --identity %s \\\n", shellQuote(identity)))
		for _, kv := range cluster.KnownValidators {
			body.WriteString(fmt.Sprintf("  --known-validator %s \\\n", kv))
		}
		if cluster.OnlyKnownRPC && len(cluster.KnownValidators) > 0 {
			body.WriteString("  --only-known-rpc \\\n")
		}
		body.WriteString("  --no-voting \\\n")
		body.WriteString("  --no-poh-speed-test \\\n")
		body.WriteString("  --private-rpc \\\n")
		body.WriteString(fmt.Sprintf("  --rpc-port %d \\\n", rpcPort))
		body.WriteString("  --rpc-bind-address 127.0.0.1 \\\n")
		if rng := solanaP2PRange(p2p, cluster.P2PRangeSpan); rng != "" {
			body.WriteString(fmt.Sprintf("  --dynamic-port-range %s \\\n", rng))
		}
		for _, ep := range cluster.Entrypoints {
			body.WriteString(fmt.Sprintf("  --entrypoint %s \\\n", ep))
		}
		if cluster.Genesis != "" {
			body.WriteString(fmt.Sprintf("  --expected-genesis-hash %s \\\n", cluster.Genesis))
		}
		body.WriteString("  --full-rpc-api \\\n")
		// High-load RPC behind Go proxy (day-one defaults, not a later retrofit).
		body.WriteString("  --rpc-threads 64 \\\n")
		body.WriteString("  --rpc-pubsub-worker-threads 16 \\\n")
		body.WriteString("  --rpc-pubsub-max-active-subscriptions 1000000 \\\n")
		body.WriteString("  --rpc-max-request-body-size 104857600 \\\n")
		body.WriteString(fmt.Sprintf("  --ledger %s \\\n", shellQuote(ledger)))
		body.WriteString(fmt.Sprintf("  --accounts %s \\\n", shellQuote(accounts)))
		if strings.TrimSpace(snapshots) != "" {
			body.WriteString(fmt.Sprintf("  --snapshots %s \\\n", shellQuote(snapshots)))
		}
		body.WriteString("  --limit-ledger-size \\\n")
		body.WriteString("  --wal-recovery-mode skip_any_corrupted_record \\\n")
		body.WriteString(fmt.Sprintf("  --log %s\n", shellQuote(logPath)))
	}

	if err := os.WriteFile(scriptPath, []byte(body.String()), 0o755); err != nil {
		return scriptPath, err
	}
	_ = exec.Command("chown", "nodeop:nodeop", scriptPath).Run()

	return scriptPath, nil
}

func shellQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}

func ensureSolanaIdentity(etc, env string, localnet bool) (string, error) {
	if localnet {
		return "", nil
	}
	path := filepath.Join(etc, "validator-keypair.json")
	if fileExists(path) {
		return path, nil
	}
	// Reuse legacy nodeop keypair when present (manual installs).
	legacy := fmt.Sprintf("/home/nodeop/validator-keypair-%s.json", env)
	if fileExists(legacy) {
		_ = os.MkdirAll(etc, 0o755)
		if err := copyFile(legacy, path); err == nil {
			_ = os.Chmod(path, 0o600)
			_ = exec.Command("chown", "nodeop:nodeop", path).Run()
			return path, nil
		}
	}
	if fileExists("/home/nodeop/validator-keypair.json") && env == "mainnet" {
		_ = os.MkdirAll(etc, 0o755)
		if err := copyFile("/home/nodeop/validator-keypair.json", path); err == nil {
			_ = os.Chmod(path, 0o600)
			_ = exec.Command("chown", "nodeop:nodeop", path).Run()
			return path, nil
		}
	}

	keygen := resolveSolanaKeygen("")
	if keygen == "" {
		return "", fmt.Errorf("solana-keygen not found; cannot create identity at %s", path)
	}
	_ = os.MkdirAll(etc, 0o755)
	cmd := exec.Command(keygen, "new", "--no-passphrase", "-o", path)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("solana-keygen: %v (%s)", err, strings.TrimSpace(string(out)))
	}
	_ = os.Chmod(path, 0o600)
	_ = exec.Command("chown", "nodeop:nodeop", path).Run()

	return path, nil
}

func resolveSolanaKeygen(optPath string) string {
	for _, cand := range []string{
		filepath.Join(optPath, "bin", "solana-keygen"),
		"/home/nodeop/.local/share/solana/install/active_release/bin/solana-keygen",
		"/opt/solana/bin/solana-keygen",
		"/usr/local/bin/solana-keygen",
	} {
		if fileExists(cand) {
			return cand
		}
	}
	if p, err := exec.LookPath("solana-keygen"); err == nil {
		return p
	}

	return ""
}

func resolveSolanaBinary(optPath string, localnet bool) string {
	name := "agave-validator"
	if localnet {
		name = "solana-test-validator"
	}
	cands := []string{
		filepath.Join(optPath, "bin", name),
		"/home/nodeop/.local/share/solana/install/active_release/bin/" + name,
		"/home/nodeop/agave/bin/" + name,
		"/opt/solana/bin/" + name,
		"/usr/local/bin/" + name,
	}
	for _, cand := range cands {
		if fileExists(cand) {
			return cand
		}
	}
	if p, err := exec.LookPath(name); err == nil {
		return p
	}

	return filepath.Join(optPath, "bin", name)
}

// ensureSolanaBinaryCaps — Agave XDP needs CAP_NET_RAW/ADMIN when not root.
func ensureSolanaBinaryCaps(bin string) error {
	if bin == "" || !fileExists(bin) {
		return fmt.Errorf("binary missing")
	}
	if _, err := exec.LookPath("setcap"); err != nil {
		return fmt.Errorf("setcap not found")
	}
	target := bin
	if resolved, err := filepath.EvalSymlinks(bin); err == nil && resolved != "" {
		target = resolved
	}
	out, err := exec.Command("setcap", "cap_net_raw,cap_net_admin+ep", target).CombinedOutput()
	if err != nil {
		return fmt.Errorf("setcap %s: %v (%s)", target, err, strings.TrimSpace(string(out)))
	}

	return nil
}

// ensureSolanaBinaryInstalled finds Agave / test-validator. Does not compile from source.
func ensureSolanaBinaryInstalled(optPath string, localnet bool) (string, error) {
	bin := resolveSolanaBinary(optPath, localnet)
	if fileExists(bin) {
		_ = os.MkdirAll(filepath.Join(optPath, "bin"), 0o755)
		link := filepath.Join(optPath, "bin", filepath.Base(bin))
		if !fileExists(link) && bin != link {
			_ = os.Remove(link)
			if err := os.Symlink(bin, link); err == nil {
				return link, nil
			}
		}

		return bin, nil
	}
	name := "agave-validator"
	if localnet {
		name = "solana-test-validator"
	}

	return "", fmt.Errorf("%s not found (looked under %s, nodeop Agave install, PATH) — build/install Agave first (see deploy/nodes/solana/mainnet.md)", name, optPath)
}

func ensureSolanaSysctl() error {
	path := "/etc/sysctl.d/21-solana.conf"
	body := `net.core.rmem_default = 134217728
net.core.rmem_max = 134217728
net.core.wmem_default = 134217728
net.core.wmem_max = 134217728
vm.max_map_count = 1000000
fs.nr_open = 1000000
`
	if fileExists(path) {
		return nil
	}
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		return err
	}
	_ = exec.Command("sysctl", "--system").Run()

	return nil
}

func rewriteSolanaUnit(prof networkPortProfile, req nodeProvisionRequest) error {
	cluster := lookupSolanaCluster(prof.Env)
	bin, err := ensureSolanaBinaryInstalled(prof.OptPath, cluster.Localnet)
	if err != nil {
		return err
	}
	etc := prof.EtcPath
	data := prof.DataPath
	ledger, accounts, snapshots := resolveSolanaDiskDirs(req, data, prof.Env)
	logPath := filepath.Join(data, "solana-"+prof.Env+".log")
	identity, err := ensureSolanaIdentity(etc, prof.Env, cluster.Localnet)
	if err != nil {
		return err
	}
	scriptPath, err := ensureSolanaRunScript(prof, req, cluster, bin, identity, ledger, accounts, snapshots, logPath)
	if err != nil {
		return err
	}
	unitPath := filepath.Join("/etc/systemd/system", prof.ServiceUnit)

	return os.WriteFile(unitPath, []byte(renderSolanaUnit(prof.Env, scriptPath, cluster.Localnet)), 0o644)
}
