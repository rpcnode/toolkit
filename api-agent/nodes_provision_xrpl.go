package main

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// XRPL provision — stock (non-validator) xrpld + Go RPC proxy.
// Official Ubuntu package: https://xrpl.org/docs/infrastructure/installation/install-xrpld-on-ubuntu

func provisionXRPLNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
	env := req.Env
	if err := beginProvisionLock("xrpl", env); err != nil {
		return nil, err
	}
	defer endProvisionLock("xrpl", env)

	steps := []string{}
	cluster := lookupXRPLNetwork(env)

	if prof.NodeHTTP > 0 {
		req.NodeHTTPPort = prof.NodeHTTP
	}
	if prof.P2P > 0 {
		req.P2PPort = prof.P2P
	}

	opt := prof.OptPath
	etc := prof.EtcPath
	data := prof.DataPath
	stateDir := fmt.Sprintf("/var/lib/rpcnode/xrpl-%s", env)
	binDir := filepath.Join(opt, "bin")

	for _, d := range []string{opt, binDir, etc, data, filepath.Join(data, "db"), stateDir,
		"/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system"} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return nil, fmt.Errorf("mkdir %s: %w", d, err)
		}
		steps = append(steps, "mkdir "+d)
	}

	bin, err := ensureXRPLDInstalled(opt)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "xrpld="+bin)

	_ = ensureNodeopUser()

	// Disable package unit so it does not fight our per-env unit / ports.
	if _, err := exec.LookPath("systemctl"); err == nil {
		_ = exec.Command("systemctl", "disable", "--now", "xrpld.service").Run()
		_ = exec.Command("systemctl", "disable", "--now", "rippled.service").Run()
		steps = append(steps, "disabled package xrpld/rippled unit")
	}

	confPath, err := writeXRPLConfig(etc, data, req, cluster)
	if err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+confPath)

	if err := ensureXRPLValidators(etc, cluster); err != nil {
		return nil, err
	}
	steps = append(steps, "validators.txt ready")

	// Units + instance before Scylla. Apt/iotune can take minutes; leftover
	// system-agent must see xrpl-mainnet.service instead of "unit not found".
	rpcBinDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))
	if token == "" {
		if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
			token = strings.TrimSpace(string(b))
		}
	}

	sysListen := systemAgentListenPort("xrpl", env)
	clioPort := xrplClioHTTPPort(env)

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (xrpl)
%sTRON_NETWORK=xrpl
TRON_NODE_HTTP_HOST=127.0.0.1
TRON_NODE_HTTP_PORT=%d
TRON_XRPLD_HTTP_PORT=%d
TRON_P2P_PORT=%d
TRON_SYSTEM_AGENT_LISTEN=127.0.0.1:%d
TRON_SYSTEM_AGENT_URL=http://127.0.0.1:%d
TRON_STATE_DIR=%s
TRON_OPT=%s
TRON_ETC=%s
TRON_DATA=%s
TRON_AGENT_STATE=%s/agent-state.json
TRON_INSTANCE_FILE=%s/INSTANCE.json
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/xrpl-%s.json
TRON_SERVICE=xrpl-%s
TRON_SNAPSHOT_ENABLED=0
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		productEnvVars(env, req.PublicPort, req.AgentPort),
		clioPort, req.NodeHTTPPort, req.P2PPort,
		sysListen, sysListen, stateDir,
		opt, etc, data, stateDir, stateDir, env, env,
		toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+envPath)

	apiBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-system-agent")

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-xrpl-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-xrpl-%s.service", env)
	nodeUnitName := fmt.Sprintf("xrpl-%s.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (xrpl/%s) — Go RPC :%d + Agent API :%d → Clio :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=xrpl
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
`, env, req.PublicPort, req.AgentPort, clioPort, envPath,
		productSystemdAPIListenEnv(env, req.PublicPort, req.AgentPort),
		clioPort, sysListen, stateDir, toolkitDir, apiBin)

	sysUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node system-agent (xrpl/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=xrpl
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

	nodeUnit := renderXRPLUnit(env, bin, confPath)

	if err := os.WriteFile(filepath.Join("/etc/systemd/system", apiUnitName), []byte(apiUnit), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", sysUnitName), []byte(sysUnit), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", nodeUnitName), []byte(nodeUnit), 0o644); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+apiUnitName, "wrote "+sysUnitName, "wrote "+nodeUnitName)

	_ = exec.Command("chown", "-R", "nodeop:nodeop", opt, etc, data).Run()

	agentURL := resolvePublicAgentURL(req.AgentPort)
	watch := cluster.WatchSlug
	if watch == "" {
		watch = prof.WatchSlug
	}
	inst := map[string]any{
		"id":             "xrpl-" + env,
		"network":        "xrpl",
		"env":            env,
		"name":           req.Name,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"watch_slug":     watch,
		"agent_url":      agentURL,
		"data_dir":       data,
		"etc_dir":        etc,
		"opt_dir":        opt,
		"units":          []string{nodeUnitName, xrplClioUnitName(env), apiUnitName, sysUnitName},
		"created_at":     time.Now().UTC().Format(time.RFC3339),
		"hostname":       hostnameOrEmpty(),
	}
	if err := writeJSONFile(filepath.Join(stateDir, "INSTANCE.json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "xrpl-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "xrpl-"+env+".json"), inst); err != nil {
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

	if err := provisionXRPLClioStack(env, etc, data); err != nil {
		return nil, fmt.Errorf("clio stack: %w", err)
	}
	steps = append(steps, "scylla+clio ready")

	return map[string]any{
		"ok":             true,
		"network":        "xrpl",
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
		"units":          []string{nodeUnitName, xrplClioUnitName(env), apiUnitName, sysUnitName},
		"units_started":  false,
		"status":         "provisioned",
		"snapshot":       false,
		"lifecycle":      "ports→install→start→run",
		"message":        "xrpl per-node agents written; unit activation scheduled",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "xrpl-"+env+".json"),
	}, nil
}

func renderXRPLUnit(env, bin, confPath string) string {
	return fmt.Sprintf(`[Unit]
Description=XRP Ledger stock xrpld (%s) — RpcNode
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
ExecStart=%s --conf %s
# Bounded server_stop so NuDB can close. timeout 15s — stalled RPC must not hang systemd.
# After TimeoutStopSec systemd SIGTERM (not SIGINT/Ctrl+C), then SIGKILL.
ExecStop=-/usr/bin/timeout 15 %s --conf %s server_stop
Restart=on-failure
RestartSec=10
TimeoutStopSec=45
KillMode=mixed
KillSignal=SIGTERM
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
`, env, bin, confPath, bin, confPath)
}

// recycleXRPLUnit — never systemctl restart (ExecStop hang / SIGKILL auxiliaries).
func recycleXRPLUnit(unit string) error {
	unit = strings.TrimSpace(unit)
	if unit == "" {
		return nil
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	if !fileExists("/etc/systemd/system/"+unit) && !fileExists("/lib/systemd/system/"+unit) {
		return fmt.Errorf("systemctl start %s: unit not installed yet", unit)
	}

	env := strings.TrimSuffix(strings.TrimPrefix(unit, "xrpl-"), ".service")
	conf := filepath.Join("/etc/xrpl", env, "xrpld.cfg")
	bin := filepath.Join("/opt/xrpl", env, "bin", "xrpld")
	if !fileExists(bin) {
		bin = "/usr/bin/xrpld"
	}
	active, _ := exec.Command("systemctl", "is-active", unit).Output()
	if strings.TrimSpace(string(active)) == "active" && fileExists(bin) && fileExists(conf) {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		_ = exec.CommandContext(ctx, bin, "--conf", conf, "server_stop").Run()
		cancel()
	}
	_ = exec.Command("systemctl", "kill", "-s", "SIGTERM", "--kill-who=main", unit).Run()
	time.Sleep(2 * time.Second)
	_ = exec.Command("systemctl", "kill", "-s", "SIGKILL", "--kill-who=main", unit).Run()
	_ = exec.Command("systemctl", "reset-failed", unit).Run()
	out, err := exec.Command("systemctl", "start", unit).CombinedOutput()
	if err != nil {
		return fmt.Errorf("systemctl start %s: %v (%s)", unit, err, strings.TrimSpace(string(out)))
	}

	return nil
}

func activateXRPLUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-xrpl-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-xrpl-%s.service", env)
	_ = exec.Command("systemctl", "daemon-reload").Run()
	for _, u := range []string{sysUnit, apiUnit} {
		_ = exec.Command("systemctl", "enable", u).Run()
		if err := exec.Command("systemctl", "restart", u).Run(); err != nil {
			return fmt.Errorf("restart %s: %w", u, err)
		}
	}
	_ = exec.Command("systemctl", "start", "rpcnode-api-agent.service").Run()
	if err := startXRPLClioUnits(env); err != nil {
		return err
	}

	return nil
}

func ensureXRPLDInstalled(optPath string) (string, error) {
	link := filepath.Join(optPath, "bin", "xrpld")
	_ = os.MkdirAll(filepath.Dir(link), 0o755)

	candidates := []string{
		"/usr/bin/xrpld",
		"/opt/ripple/bin/xrpld",
		"/opt/ripple/bin/rippled",
		"/usr/bin/rippled",
	}
	for _, c := range candidates {
		if fileExists(c) {
			_ = os.Remove(link)
			_ = os.Symlink(c, link)
			return c, nil
		}
	}

	if err := installXRPLDFromApt(env); err != nil {
		return "", err
	}
	for _, c := range candidates {
		if fileExists(c) {
			_ = os.Remove(link)
			_ = os.Symlink(c, link)
			return c, nil
		}
	}

	return "", fmt.Errorf("xrpld binary missing after apt install")
}

func ensureRippleAptRepo() error {
	_ = exec.Command("apt-get", "-y", "install", "apt-transport-https", "ca-certificates", "wget", "gnupg").Run()
	_ = os.MkdirAll("/etc/apt/keyrings", 0o755)

	keyPath := "/etc/apt/keyrings/ripple.gpg"
	if !fileExists(keyPath) {
		tmp := "/tmp/ripple-apt-key.asc"
		if err := downloadFile("https://repos.ripple.com/repos/api/gpg/key/public", tmp); err != nil {
			return fmt.Errorf("ripple gpg key: %w", err)
		}
		_ = os.Remove(keyPath)
		if out, err := exec.Command("bash", "-lc",
			fmt.Sprintf(`gpg --dearmor < %q > %q && chmod 644 %q`, tmp, keyPath, keyPath),
		).CombinedOutput(); err != nil {
			return fmt.Errorf("gpg --dearmor: %v (%s)", err, strings.TrimSpace(string(out)))
		}
	}

	codename := "noble"
	if out, err := exec.Command("bash", "-lc", `. /etc/os-release 2>/dev/null; echo "${VERSION_CODENAME:-noble}"`).Output(); err == nil {
		if c := strings.TrimSpace(string(out)); c != "" {
			codename = c
		}
	}
	switch codename {
	case "jammy", "noble", "bookworm", "bullseye", "resolute":
	default:
		codename = "noble"
	}

	listLine := fmt.Sprintf(
		"deb [signed-by=/etc/apt/keyrings/ripple.gpg] https://repos.ripple.com/repos/rippled-deb %s stable\n",
		codename,
	)
	if err := os.WriteFile("/etc/apt/sources.list.d/ripple.list", []byte(listLine), 0o644); err != nil {
		return err
	}

	if out, err := exec.Command("apt-get", "-y", "update").CombinedOutput(); err != nil {
		return fmt.Errorf("apt-get update: %v (%s)", err, strings.TrimSpace(string(out)))
	}

	return nil
}

func xrplDebFromCatalog(env string) (pkg, ver string) {
	rel, err := fetchVendoredClientRelease("xrpl", env)
	pkg, ver = "xrpld", "3.3.0"
	if err == nil && strings.TrimSpace(rel.Version) != "" {
		ver = strings.TrimSpace(rel.Version)
	}
	if err == nil {
		u := strings.ToLower(rel.ArtifactURL)
		if strings.Contains(u, "rippled") && !strings.Contains(u, "xrpld") {
			pkg = "rippled"
		}
	}
	return pkg, ver
}

func installXRPLDFromApt(env string) error {
	if _, err := exec.LookPath("apt-get"); err != nil {
		return fmt.Errorf("apt-get required to install xrpld: %w", err)
	}

	if err := ensureRippleAptRepo(); err != nil {
		return err
	}
	pkg, ver := xrplDebFromCatalog(env)
	pref := fmt.Sprintf("Package: %s\nPin: version %s*\nPin-Priority: 1001\n", pkg, ver)
	_ = os.WriteFile("/etc/apt/preferences.d/rpcnode-xrpl", []byte(pref), 0o644)
	if err := aptInstallPinnedDeb(pkg, ver); err != nil {
		return err
	}
	_ = exec.Command("apt-mark", "hold", pkg).Run()
	return nil
}

func aptInstallPinnedDeb(pkg, ver string) error {
	ver = strings.TrimSpace(ver)
	cands := []string{pkg}
	if ver != "" {
		cands = []string{pkg + "=" + ver + "-1", pkg + "=" + ver, pkg}
	}
	var lastOut []byte
	var lastErr error
	for _, spec := range cands {
		cmd := exec.Command("apt-get", "-y", "-o", "Dpkg::Options::=--force-confold", "install", spec)
		cmd.Env = append(os.Environ(), "DEBIAN_FRONTEND=noninteractive")
		out, err := cmd.CombinedOutput()
		if err == nil {
			return nil
		}
		lastOut, lastErr = out, err
	}
	return fmt.Errorf("apt-get install %s (catalog %s): %v (%s)",
		pkg, ver, lastErr, strings.TrimSpace(string(lastOut)))
}

func writeXRPLConfig(etc, data string, req nodeProvisionRequest, cluster xrplNetwork) (string, error) {
	rpcPort := req.NodeHTTPPort
	if rpcPort <= 0 {
		rpcPort = 5005
	}
	p2p := req.P2PPort
	if p2p <= 0 {
		p2p = 51235
	}
	wsPort := 6006
	if normalizeEnv(cluster.Env) == "testnet" {
		wsPort = 6007
	}

	dbPath := filepath.Join(data, "db")
	nudbPath := filepath.Join(dbPath, "nudb")
	_ = os.MkdirAll(nudbPath, 0o755)

	var b strings.Builder
	b.WriteString("# managed by RpcNode — stock xrpld (non-validator)\n")
	b.WriteString("# https://xrpl.org/docs/infrastructure/configuration/server-modes/run-xrpld-as-a-stock-server\n\n")
	wsPublic := xrplWSPublicPort(cluster.Env)
	grpcPort := xrplGRPCPort(cluster.Env)

	b.WriteString("[server]\n")
	b.WriteString("port_rpc_admin_local\n")
	b.WriteString("port_peer\n")
	b.WriteString("port_ws_admin_local\n")
	b.WriteString("port_ws_public\n")
	b.WriteString("port_grpc\n\n")

	b.WriteString("[port_rpc_admin_local]\n")
	b.WriteString(fmt.Sprintf("port = %d\n", rpcPort))
	b.WriteString("ip = 127.0.0.1\n")
	b.WriteString("admin = 127.0.0.1\n")
	b.WriteString("protocol = http\n\n")

	b.WriteString("[port_peer]\n")
	b.WriteString(fmt.Sprintf("port = %d\n", p2p))
	b.WriteString("ip = 0.0.0.0\n")
	b.WriteString("protocol = peer\n\n")

	b.WriteString("[port_ws_admin_local]\n")
	b.WriteString(fmt.Sprintf("port = %d\n", wsPort))
	b.WriteString("ip = 127.0.0.1\n")
	b.WriteString("admin = 127.0.0.1\n")
	b.WriteString("protocol = ws\n")
	b.WriteString("send_queue_limit = 500\n\n")

	b.WriteString("[port_ws_public]\n")
	b.WriteString(fmt.Sprintf("port = %d\n", wsPublic))
	b.WriteString("ip = 127.0.0.1\n")
	b.WriteString("protocol = ws\n\n")

	b.WriteString("[port_grpc]\n")
	b.WriteString(fmt.Sprintf("port = %d\n", grpcPort))
	b.WriteString("ip = 127.0.0.1\n")
	b.WriteString("secure_gateway = 127.0.0.1\n\n")

	// Empty NuDB: medium even on 390 GiB hosts. huge cache init + first ledger
	// write stalls the job queue >90s → LoadManager FTL, seq=0, complete=empty.
	// After the first ledger exists, heal promotes to RAM size (huge on ≥32 GiB).
	b.WriteString("[node_size]\n")
	b.WriteString(xrplNodeSize(hostMemTotalGiB(), xrplDatadirHasLedger(data)) + "\n\n")

	pol := resolveXRPLHistoryPolicy(etc, req.XRPLHistory)
	_ = writeXRPLHistoryPolicy(etc, pol)

	b.WriteString("[node_db]\n")
	b.WriteString("type=NuDB\n")
	b.WriteString("path=" + nudbPath + "\n")
	if pol.Mode != "full" && pol.Ledgers > 0 && xrplDatadirHasLedger(data) {
		b.WriteString(fmt.Sprintf("online_delete=%d\n", pol.Ledgers))
	}
	b.WriteString("advisory_delete=0\n\n")

	if pol.Mode == "full" || pol.Ledgers <= 0 {
		b.WriteString("[ledger_history]\nfull\n\n")
	} else {
		b.WriteString(fmt.Sprintf("[ledger_history]\n%d\n\n", pol.Ledgers))
	}
	// Default peers_max=21 keeps ~10 outgoing. <68 does not raise outgoing.
	// History fetch is parallel across outgoing + ips_fixed (fixed do not count).
	b.WriteString("[peers_max]\n100\n\n")
	b.WriteString("[fetch_depth]\nfull\n\n")
	b.WriteString("[database_path]\n" + dbPath + "\n\n")
	b.WriteString("[debug_logfile]\n" + filepath.Join(data, "debug.log") + "\n\n")

	b.WriteString("[sntp_servers]\n")
	b.WriteString("time.windows.com\ntime.apple.com\ntime.nist.gov\npool.ntp.org\n\n")

	b.WriteString("[validators_file]\n")
	b.WriteString(filepath.Join(etc, "validators.txt") + "\n\n")

	b.WriteString("[rpc_startup]\n")
	b.WriteString("{ \"command\": \"log_level\", \"severity\": \"warning\" }\n\n")

	b.WriteString("[ssl_verify]\n0\n")

	if cluster.NetworkID != "" {
		b.WriteString("\n[network_id]\n")
		b.WriteString(cluster.NetworkID + "\n")
	}
	if normalizeEnv(cluster.Env) != "testnet" {
		b.WriteString("\n[ips]\n")
		for _, hub := range xrplMainnetHubs() {
			b.WriteString(hub + "\n")
		}
	}
	if len(cluster.IPSFixed) > 0 {
		b.WriteString("\n[ips_fixed]\n")
		for _, ip := range cluster.IPSFixed {
			b.WriteString(ip + "\n")
		}
	}

	confPath := filepath.Join(etc, "xrpld.cfg")
	if err := os.WriteFile(confPath, []byte(b.String()), 0o644); err != nil {
		return "", err
	}

	return confPath, nil
}

func ensureXRPLValidators(etc string, cluster xrplNetwork) error {
	dest := filepath.Join(etc, "validators.txt")
	if fileExists(dest) {
		return nil
	}

	// Prefer packaged validators from Ripple/XRPLF deb.
	for _, src := range []string{
		"/etc/opt/ripple/validators.txt",
		"/etc/xrpld/validators.txt",
		"/opt/ripple/etc/validators.txt",
	} {
		if !fileExists(src) {
			continue
		}
		b, err := os.ReadFile(src)
		if err != nil {
			continue
		}
		return os.WriteFile(dest, b, 0o644)
	}

	url := "https://raw.githubusercontent.com/XRPLF/rippled/master/cfg/validators-example.txt"
	if normalizeEnv(cluster.Env) == "testnet" {
		// Same example file; AltNet relies on network_id + ips_fixed + VL fetch.
		url = "https://raw.githubusercontent.com/XRPLF/rippled/master/cfg/validators-example.txt"
	}
	tmp := filepath.Join(os.TempDir(), "xrpl-validators.txt")
	if err := downloadNamedConf("xrpl", cluster.Env, "validators-example.txt", url, tmp); err != nil {
		return fmt.Errorf("validators.txt download: %w", err)
	}
	b, err := os.ReadFile(tmp)
	if err != nil {
		return err
	}

	return os.WriteFile(dest, b, 0o644)
}

// xrplNodeSizeForRAMGiB — official capacity-planning table. Skip `large`
// (docs: worse than huge). Unknown RAM → medium (example.cfg default).
// 390 GiB host → huge is correct *after* the first ledger exists.
func xrplNodeSizeForRAMGiB(gib float64) string {
	switch {
	case gib <= 0:
		return "medium"
	case gib < 8:
		return "tiny"
	case gib < 16:
		return "small"
	case gib < 32:
		return "medium"
	default:
		return "huge"
	}
}

// xrplNodeSize — bootstrap medium until NuDB has a ledger; then RAM profile.
func xrplNodeSize(ramGiB float64, hasLedger bool) string {
	if !hasLedger {
		return "medium"
	}
	return xrplNodeSizeForRAMGiB(ramGiB)
}

func xrplDatadirHasLedger(data string) bool {
	data = strings.TrimSpace(data)
	if data == "" {
		return false
	}
	nudb := filepath.Join(data, "db", "nudb")
	entries, err := os.ReadDir(nudb)
	if err != nil {
		return false
	}
	var total int64
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		total += info.Size()
	}
	return total >= 8<<20
}

func hostMemTotalGiB() float64 {
	mb := memTotalMB()
	if mb <= 0 {
		return 0
	}
	return float64(mb) / 1024.0
}

// healXRPLConfig rewrites xrpld.cfg from the live port profile (node_size from
// RAM, history from history.json / existing cfg). Called on /nodes/start.
func healXRPLConfig(env string) (string, error) {
	env = normalizeEnv(env)
	prof := lookupPortProfile("xrpl", env)
	cluster := lookupXRPLNetwork(env)
	req := nodeProvisionRequest{
		Network: "xrpl", Env: env,
		NodeHTTPPort: prof.NodeHTTP,
		P2PPort:      prof.P2P,
	}
	if doc := readJSONFile(filepath.Join("/etc/rpcnode/nodes", "xrpl-"+env+".json")); doc != nil {
		if v := intFromJSON(doc["node_http_port"]); v > 0 {
			req.NodeHTTPPort = v
		}
		if v := intFromJSON(doc["p2p_port"]); v > 0 {
			req.P2PPort = v
		}
	}
	return writeXRPLConfig(prof.EtcPath, prof.DataPath, req, cluster)
}
