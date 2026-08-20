package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// Toncoin provision — MyTonCtrl liteserver full (~30d) + TON HTTP API behind Go proxy.
// ❌ Never Docker. Install is async (dump can take hours) — provision ACK must not block.
// Canonical: deploy/nodes/ton/DESIGN.md

func provisionTonNodeEnv(req nodeProvisionRequest, prof networkPortProfile) (map[string]any, error) {
	env := normalizeEnv(req.Env)
	if env != "mainnet" && env != "testnet" {
		return nil, fmt.Errorf("ton provision supports mainnet/testnet (got %s)", env)
	}
	steps := []string{}
	cluster := lookupTonNetwork(env)

	if prof.NodeHTTP > 0 && req.NodeHTTPPort <= 0 {
		req.NodeHTTPPort = prof.NodeHTTP
	}
	if req.P2PPort <= 0 && prof.P2P > 0 {
		req.P2PPort = prof.P2P
	}
	if req.NodeHTTPPort <= 0 {
		if env == "testnet" {
			req.NodeHTTPPort = 8082
		} else {
			req.NodeHTTPPort = 8081
		}
	}
	if req.P2PPort <= 0 {
		if env == "testnet" {
			req.P2PPort = 30311
		} else {
			req.P2PPort = 30310
		}
	}

	opt := prof.OptPath
	etc := prof.EtcPath
	data := resolveNetworkRoleDir(req, "ton", env, "blockchain", prof.DataPath)
	archiveDir := resolveNetworkRoleDir(req, "ton", env, "archive", filepath.Join(prof.DataPath, "archive"))
	stateDir := fmt.Sprintf("/var/lib/rpcnode/ton-%s", env)
	binDir := filepath.Join(opt, "bin")
	logDir := filepath.Join("/var/log/ton", env)

	for _, d := range []string{
		opt, binDir, etc, data, archiveDir, stateDir, logDir,
		"/etc/rpcnode/instances.d", "/etc/rpcnode/nodes", "/etc/systemd/system",
	} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return nil, fmt.Errorf("mkdir %s: %w", d, err)
		}
		steps = append(steps, "mkdir "+d)
	}

	if err := ensureTonWorkdirLink(data); err != nil {
		return nil, err
	}
	steps = append(steps, "ton-work → "+data)

	_ = ensureNodeopUser()

	bootScript := filepath.Join(binDir, "rpcnode-ton-bootstrap.sh")
	if err := writeTonBootstrapScript(bootScript, env, cluster.ChainFlag, req.P2PPort, req.NodeHTTPPort, data, etc, logDir); err != nil {
		return nil, err
	}
	_ = os.Chmod(bootScript, 0o755)
	steps = append(steps, "wrote "+bootScript)

	metaPath := filepath.Join(etc, "rpcnode-ton.json")
	meta := map[string]any{
		"network":        "ton",
		"env":            env,
		"chain":          cluster.ChainFlag,
		"archive_ttl":    tonArchiveTTLSec,
		"state_ttl":      tonStateTTLSec,
		"validator_port": req.P2PPort,
		"tha_port":       req.NodeHTTPPort,
		"data_dir":       data,
		"mode":           "liteserver",
		"history":        "recent_30d",
	}
	if err := writeJSONFile(metaPath, meta); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+metaPath)

	rpcBinDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))
	if token == "" {
		if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
			token = strings.TrimSpace(string(b))
		}
	}

	sysListen := systemAgentListenPort("ton", env)

	envBody := fmt.Sprintf(`# managed by rpcnode provision %s (ton)
%sTRON_NETWORK=ton
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
TRON_REGISTRY_FILE=/etc/rpcnode/instances.d/ton-%s.json
TRON_SERVICE=ton-%s
TRON_SNAPSHOT_ENABLED=0
TON_ARCHIVE_TTL=%d
TON_STATE_TTL=%d
TON_THA_PORT=%d
TON_VALIDATOR_PORT=%d
TON_BOOTSTRAP=%s
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		productEnvVars(env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, req.P2PPort,
		sysListen, sysListen, stateDir,
		opt, etc, data, stateDir, stateDir, env, env,
		tonArchiveTTLSec, tonStateTTLSec, req.NodeHTTPPort, req.P2PPort,
		bootScript, toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}
	steps = append(steps, "wrote "+envPath)

	apiBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-api-agent")
	sysBin := resolveAgentBinary(rpcBinDir, toolkitDir, "rpcnode-system-agent")

	apiUnitName := fmt.Sprintf("rpcnode-api-agent-ton-%s.service", env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-ton-%s.service", env)
	nodeUnitName := fmt.Sprintf("ton-%s.service", env)
	bootUnitName := fmt.Sprintf("ton-%s-bootstrap.service", env)

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (ton/%s) — Go RPC :%d + Agent API :%d → TON HTTP API :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=ton
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
Description=RpcNode per-node system-agent (ton/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=ton
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
		req.NodeHTTPPort, stateDir, toolkitDir, sysBin)

	bootUnit := fmt.Sprintf(`[Unit]
Description=RpcNode MyTonCtrl liteserver bootstrap (ton/%s)
After=network-online.target
Wants=network-online.target
# Apt lock / dump flakes — keep retrying (oneshot + RemainAfterExit).
StartLimitIntervalSec=0

[Service]
Type=oneshot
RemainAfterExit=yes
# install.sh git submodule fails with "fatal: $HOME not set" under systemd.
Environment=HOME=/root
Environment=USER=root
EnvironmentFile=-%s
ExecStart=%s
TimeoutStartSec=0
Restart=on-failure
RestartPreventExitStatus=2
RestartSec=60
Nice=10
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
`, env, envPath, bootScript)

	nodeUnit := fmt.Sprintf(`[Unit]
Description=Toncoin liteserver + TON HTTP API (%s) — RpcNode
After=network-online.target ton-%s-bootstrap.service
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
EnvironmentFile=-%s
ExecStart=/bin/bash -lc 'set -e; marker=%s/bootstrap.done; if [[ ! -f "$marker" ]]; then systemctl start --no-block ton-%s-bootstrap.service; exit 0; fi; systemctl start --no-block validator.service mytoncore.service 2>/dev/null || true; for u in ton-http-api.service ton_http_api.service; do systemctl start --no-block "$u" 2>/dev/null || true; done; exit 0'
# No ExecStop: panel remove SIGTERMs validator/mytoncore/http-api (systemctl stop hangs).
TimeoutStopSec=30
RemainAfterExit=yes
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
`, env, env, envPath, etc, env)

	for _, item := range []struct {
		name, body string
	}{
		{apiUnitName, apiUnit},
		{sysUnitName, sysUnit},
		{bootUnitName, bootUnit},
		{nodeUnitName, nodeUnit},
	} {
		if err := os.WriteFile(filepath.Join("/etc/systemd/system", item.name), []byte(item.body), 0o644); err != nil {
			return nil, err
		}
		steps = append(steps, "wrote "+item.name)
	}

	// THA port drop-in (applied after installer creates the unit).
	dropInDir := "/etc/systemd/system/ton-http-api.service.d"
	_ = os.MkdirAll(dropInDir, 0o755)
	dropIn := fmt.Sprintf(`[Service]
Environment=TON_API_HTTP_PORT=%d
Environment=TON_API_PORT=%d
LimitNOFILE=1048576
`, req.NodeHTTPPort, req.NodeHTTPPort)
	_ = os.WriteFile(filepath.Join(dropInDir, "rpcnode.conf"), []byte(dropIn), 0o644)
	steps = append(steps, "wrote ton-http-api drop-in port "+fmt.Sprint(req.NodeHTTPPort))

	agentURL := resolvePublicAgentURL(req.AgentPort)
	watch := cluster.WatchSlug
	if watch == "" {
		watch = prof.WatchSlug
	}
	inst := map[string]any{
		"id":             "ton-" + env,
		"network":        "ton",
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
		"archive_ttl":    tonArchiveTTLSec,
		"mode":           "liteserver",
		"units":          []string{nodeUnitName, bootUnitName, apiUnitName, sysUnitName},
		"created_at":     time.Now().UTC().Format(time.RFC3339),
		"hostname":       hostnameOrEmpty(),
	}
	if err := writeJSONFile(filepath.Join(stateDir, "INSTANCE.json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/instances.d", "ton-"+env+".json"), inst); err != nil {
		return nil, err
	}
	if err := writeJSONFile(filepath.Join("/etc/rpcnode/nodes", "ton-"+env+".json"), inst); err != nil {
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
		"network":        "ton",
		"env":            env,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"node_rpc_port":  req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"rpc_mode":       "go_proxy",
		"upstream":       "ton_http_api",
		"archive_ttl":    tonArchiveTTLSec,
		"history":        "recent_30d",
		"agent_url":      agentURL,
		"etc_dir":        etc,
		"data_dir":       data,
		"units":          []string{nodeUnitName, bootUnitName, apiUnitName, sysUnitName},
		"units_started":  false,
		"status":         "provisioned",
		"snapshot":       false,
		"lifecycle":      "ports→install→start→run",
		"message":        "ton leaf agents written; MyTonCtrl bootstrap scheduled (async)",
		"steps":          steps,
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "ton-"+env+".json"),
	}, nil
}

func activateTonUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env = normalizeEnv(env)
	apiUnit := fmt.Sprintf("rpcnode-api-agent-ton-%s.service", env)
	sysUnit := fmt.Sprintf("rpcnode-system-agent-ton-%s.service", env)
	bootUnit := fmt.Sprintf("ton-%s-bootstrap.service", env)
	_ = exec.Command("systemctl", "daemon-reload").Run()
	for _, u := range []string{sysUnit, apiUnit} {
		_ = exec.Command("systemctl", "enable", u).Run()
		if err := exec.Command("systemctl", "restart", u).Run(); err != nil {
			return fmt.Errorf("restart %s: %w", u, err)
		}
	}
	_ = exec.Command("systemctl", "enable", bootUnit).Run()
	// Kick bootstrap without blocking provision activate.
	_ = exec.Command("systemctl", "start", "--no-block", bootUnit).Run()
	_ = exec.Command("systemctl", "start", "rpcnode-api-agent.service").Run()
	return nil
}

func ensureTonWorkdirLink(data string) error {
	data = strings.TrimSpace(data)
	if data == "" {
		return fmt.Errorf("ton data path empty")
	}
	if err := os.MkdirAll(data, 0o755); err != nil {
		return err
	}
	const work = "/var/ton-work"
	st, err := os.Lstat(work)
	if err != nil {
		if os.IsNotExist(err) {
			return os.Symlink(data, work)
		}
		return err
	}
	if st.Mode()&os.ModeSymlink != 0 {
		target, _ := os.Readlink(work)
		if filepath.Clean(target) == filepath.Clean(data) {
			return nil
		}
		// Re-point only if current target is missing.
		if _, err := os.Stat(target); err != nil {
			_ = os.Remove(work)
			return os.Symlink(data, work)
		}
		return fmt.Errorf("%s already points to %s (want %s) — remove other TON env first", work, target, data)
	}
	// Existing directory from prior MyTonCtrl — leave it; agent uses that tree.
	return nil
}

func writeTonBootstrapScript(path, env, chain string, p2p, thaPort int, data, etc, logDir string) error {
	logDownload("GET", "https://raw.githubusercontent.com/ton-blockchain/mytonctrl/master/scripts/install.sh", "ton/"+env+" install.sh")
	body := fmt.Sprintf(`#!/bin/bash
# RpcNode Toncoin bootstrap — MyTonCtrl liteserver (not archive).
set -euo pipefail
ENV=%q
CHAIN=%q
P2P=%d
THA_PORT=%d
DATA=%q
ETC=%q
LOG_DIR=%q
MARKER="$ETC/bootstrap.done"
LOG="$LOG_DIR/bootstrap.log"
ARCHIVE_TTL=%d
STATE_TTL=%d
INSTALL_URL="https://raw.githubusercontent.com/ton-blockchain/mytonctrl/master/scripts/install.sh"

# Wait out concurrent apt/dpkg via real package tools + lock fuser only.
# ❌ Never pgrep unattended-upgr* — comm truncates to 15 chars and matches idle
# unattended-upgrade-shutdown; ❌ Never -f 'unattended-upgrade' — also matches
# path …/unattended-upgrades/… on that same helper.
wait_apt_lock() {
  local deadline=$((SECONDS + 900))
  while true; do
    local busy=0
    if pgrep -x apt-get >/dev/null 2>&1 \
       || pgrep -x apt >/dev/null 2>&1 \
       || pgrep -x dpkg >/dev/null 2>&1; then
      busy=1
    elif fuser /var/lib/dpkg/lock-frontend >/dev/null 2>&1 \
       || fuser /var/lib/dpkg/lock >/dev/null 2>&1 \
       || fuser /var/lib/apt/lists/lock >/dev/null 2>&1 \
       || fuser /var/cache/apt/archives/lock >/dev/null 2>&1; then
      busy=1
    fi
    if [[ $busy -eq 0 ]]; then
      return 0
    fi
    if (( SECONDS >= deadline )); then
      echo "timeout waiting for apt/dpkg lock (900s) — continuing anyway" >&2
      return 0
    fi
    echo "[$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)] waiting for apt/dpkg lock..."
    sleep 15
  done
}

# apt-get update exit 100 is also "repo has no Release file" (ookla speedtest 404
# on noble). That is not a dpkg lock — do not retry for hours.
disable_broken_apt_release_sources() {
  local err urc f host
  set +e
  err="$(apt-get update -y 2>&1)"
  urc=$?
  set -e
  printf '%%s\n' "$err"
  if [[ $urc -eq 0 ]] || ! printf '%%s\n' "$err" | grep -qi 'does not have a Release file'; then
    return 0
  fi
  echo "disabling apt lists that 404 (no Release file)"
  for f in /etc/apt/sources.list.d/*.list; do
    [[ -f "$f" ]] || continue
    if printf '%%s\n' "$err" | grep -qiE 'ookla|speedtest-cli' && grep -qiE 'ookla|speedtest-cli' "$f"; then
      echo "disable $f"
      mv -f "$f" "${f}.rpcnode-disabled"
      continue
    fi
    while IFS= read -r host; do
      [[ -n "$host" ]] || continue
      if grep -q "$host" "$f"; then
        echo "disable $f (host $host)"
        mv -f "$f" "${f}.rpcnode-disabled"
        break
      fi
    done < <(printf '%%s\n' "$err" | grep -E '^Err:[0-9]+' | grep -oE 'https://[^/]+' | sed 's|https://||' | sort -u)
  done
  apt-get update -y || true
}

mkdir -p "$DATA" "$ETC" "$LOG_DIR"
exec >>"$LOG" 2>&1
echo "[$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)] ton bootstrap start env=$ENV chain=$CHAIN"

if [[ -f "$MARKER" ]]; then
  echo "bootstrap already done"
  exit 0
fi

if [[ ! -e /var/ton-work ]]; then
  ln -sfn "$DATA" /var/ton-work
fi

export DEBIAN_FRONTEND=noninteractive
# systemd oneshot often has empty HOME — mytonctrl install.sh git submodule needs it.
export HOME="${HOME:-/root}"
export USER="${USER:-root}"
wait_apt_lock
disable_broken_apt_release_sources
wait_apt_lock
apt-get install -y curl wget git ca-certificates python3-pip || true

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
echo "$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ) INFO  [api-agent] download GET $INSTALL_URL  ton/$ENV install.sh" >>/var/log/rpcnode.log
if curl -fsSL "$INSTALL_URL" -o "$TMP/install.sh"; then
  echo "$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ) INFO  [api-agent] download GET ok $INSTALL_URL  ton/$ENV" >>/var/log/rpcnode.log
else
  echo "$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ) ERROR [api-agent] download GET FAIL $INSTALL_URL  ton/$ENV" >>/var/log/rpcnode.log
  exit 1
fi
chmod +x "$TMP/install.sh"

export ARCHIVE_TTL STATE_TTL
export VALIDATOR_PORT="$P2P"
# -d = dump (faster initial sync). Not archive mode.
# Retry a few times — apt lock races still show up as install.sh exit 100.
rc=1
attempt=1
while (( attempt <= 5 )); do
  wait_apt_lock || true
  set +e
  env HOME="$HOME" USER="$USER" bash "$TMP/install.sh" -m liteserver -n "$CHAIN" -d
  rc=$?
  set -e
  echo "install.sh attempt=$attempt exit=$rc"
  if [[ $rc -eq 0 ]]; then
    break
  fi
  if grep -qi 'does not have a Release file' "$LOG" 2>/dev/null; then
    echo "apt repo 404 (no Release file) — not a dpkg lock"
    disable_broken_apt_release_sources
    if [[ "${HEALED_APT:-0}" -eq 0 ]]; then
      HEALED_APT=1
      attempt=$((attempt + 1))
      continue
    fi
    echo "apt still broken after disabling 404 sources — exit 2 (no restart loop)"
    exit 2
  fi
  if grep -qiE 'Could not get lock /var/lib/dpkg|Unable to acquire the dpkg frontend lock' "$LOG" 2>/dev/null; then
    echo "apt/dpkg lock — retry in 60s (attempt $attempt/5)"
    sleep 60
    attempt=$((attempt + 1))
    continue
  fi
  # git submodule "fatal: $HOME not set" → exit 128; fix env and retry once more.
  if [[ $rc -eq 128 ]] && grep -qi 'HOME not set' "$LOG" 2>/dev/null; then
    export HOME=/root
    echo "retry install.sh with HOME=/root (attempt $attempt/5)"
    sleep 5
    attempt=$((attempt + 1))
    continue
  fi
  break
done
echo "install.sh exit=$rc"

# Enable local liteserver config + TON HTTP API via MyTonCtrl console.
# NOTE: mytoninstaller -e is NOT a CLI flag (interactive console only) — use mytonctrl --cmd.
MTC_PY=/usr/local/bin/mytoncore/venv/bin/python
MTC_DB=/usr/local/bin/mytoncore/mytoncore.db
if [[ -x "$MTC_PY" && -f "$MTC_DB" ]]; then
  "$MTC_PY" -m mytonctrl -c "$MTC_DB" --cmd "installer clcf" || true
  "$MTC_PY" -m mytonctrl -c "$MTC_DB" --cmd "installer enable THA" || true
fi

# Pin THA listen port (unit name is ton_http_api; also alias ton-http-api).
for tha_unit in ton_http_api ton-http-api; do
  mkdir -p "/etc/systemd/system/${tha_unit}.service.d"
  cat >"/etc/systemd/system/${tha_unit}.service.d/rpcnode.conf" <<EOF
[Service]
Environment=TON_API_HTTP_PORT=$THA_PORT
Environment=TON_API_PORT=$THA_PORT
LimitNOFILE=1048576
EOF
done
# Stock installer binds :8801 — rewrite ExecStart to RpcNode THA port.
if [[ -f /etc/systemd/system/ton_http_api.service ]]; then
  sed -i "s/--port 8801/--port $THA_PORT/g; s/--port 8080/--port $THA_PORT/g" /etc/systemd/system/ton_http_api.service || true
  ln -sfn /etc/systemd/system/ton_http_api.service /etc/systemd/system/ton-http-api.service || true
fi
systemctl daemon-reload || true

# Raise NOFILE on validator if unit exists.
mkdir -p /etc/systemd/system/validator.service.d
cat >/etc/systemd/system/validator.service.d/rpcnode.conf <<EOF
[Service]
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
LimitNOFILE=1048576
EOF
systemctl daemon-reload || true

# Dump apply OOMs when MyTonCtrl leaves --celldb-preload-all / a 64G cache.
# Cap cache from MemTotal (same table as system-agent healTonValidatorMemory).
if [[ -f /etc/systemd/system/validator.service ]]; then
  python3 - <<'PY' || true
import re
path = "/etc/systemd/system/validator.service"
try:
    text = open(path).read()
except OSError:
    raise SystemExit(0)
mem = 0
for line in open("/proc/meminfo"):
    if line.startswith("MemTotal:"):
        mem = int(line.split()[1]) // 1024  # MiB
        break
gib = mem / 1024
if gib < 16:
    cache = 1 << 30
elif gib < 32:
    cache = 2 << 30
elif gib < 64:
    cache = 4 << 30
else:
    cache = 8 << 30
m = re.search(r"(?m)^ExecStart\s*=\s*(.+)$", text)
if not m or "validator-engine" not in m.group(1):
    raise SystemExit(0)
line = m.group(1).strip()
line = re.sub(r"\s+--celldb-cache-size(?:=|\s+)\S+", "", line)
line = re.sub(r"\s+--celldb-preload-all\b", "", line)
line = re.sub(r"\s+--celldb-in-memory\b", "", line)
line = re.sub(r"\s+--celldb-direct-io\b", "", line)
line = re.sub(r"\s+--fast-state-serializer\b", "", line)
flag = f"--celldb-cache-size={cache}"
if flag not in line:
    line = line.strip() + " " + flag
new = re.sub(r"(?m)^ExecStart\s*=\s*.+$", "ExecStart=" + line, text, count=1)
if new != text:
    open(path, "w").write(new)
    print(f"capped celldb cache at {cache} bytes (RAM ~{gib:.0f} GiB)")
PY
  systemctl daemon-reload || true
fi

systemctl enable validator.service mytoncore.service 2>/dev/null || true
systemctl restart validator.service 2>/dev/null || true
systemctl restart mytoncore.service 2>/dev/null || true
for u in ton_http_api.service ton-http-api.service; do
  systemctl enable "$u" 2>/dev/null || true
  systemctl restart "$u" 2>/dev/null || true
done

# Mark done when the real validator binary exists (not the CMake build dir).
# Mid-install /usr/bin/ton/validator-engine/ is a directory — do not treat as done.
VE_BIN=""
for c in /usr/bin/ton/validator-engine/validator-engine /usr/bin/ton/validator-engine; do
  if [[ -x "$c" && ! -d "$c" ]]; then VE_BIN="$c"; break; fi
done
if [[ -n "$VE_BIN" ]] && systemctl cat validator.service >/dev/null 2>&1; then
  date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ >"$MARKER"
  echo "bootstrap marker written ($VE_BIN)"
  exit 0
fi

if [[ $rc -ne 0 ]]; then
  echo "bootstrap failed rc=$rc" >&2
  exit $rc
fi
date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ >"$MARKER"
echo "bootstrap finished"
`, env, chain, p2p, thaPort, data, etc, logDir, tonArchiveTTLSec, tonStateTTLSec)
	return os.WriteFile(path, []byte(body), 0o755)
}
