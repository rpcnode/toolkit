#!/usr/bin/env bash
# Detect an already-running / already-installed java-tron FullNode.
# Populates DETECT_* variables for the setup wizard. Never mutates chain DB.

DETECT_JAVA_PID=""
DETECT_JAVA_CMD=""
DETECT_JAR=""
DETECT_CONFIG=""
DETECT_DATA_DIR=""
DETECT_OUTPUT=""
DETECT_HTTP_PORT=""
DETECT_P2P_PORT=""
DETECT_SYSTEMD_UNIT=""
DETECT_USER=""
DETECT_NODE_RUNNING=0
DETECT_HAS_DATA=0
DETECT_TOOLKIT_ENV=""

detect_reset() {
  DETECT_JAVA_PID=""
  DETECT_JAVA_CMD=""
  DETECT_JAR=""
  DETECT_CONFIG=""
  DETECT_DATA_DIR=""
  DETECT_OUTPUT=""
  DETECT_HTTP_PORT=""
  DETECT_P2P_PORT=""
  DETECT_SYSTEMD_UNIT=""
  DETECT_USER=""
  DETECT_NODE_RUNNING=0
  DETECT_HAS_DATA=0
  DETECT_TOOLKIT_ENV=""
}

# Best-effort parse of java cmdline for -jar / -c / -d.
_detect_from_cmdline() {
  local cmd="$1"
  DETECT_JAVA_CMD="$cmd"
  # jar
  if [[ "$cmd" =~ -jar[[:space:]]+([^[:space:]]+) ]]; then
    DETECT_JAR="${BASH_REMATCH[1]}"
  fi
  # config -c
  if [[ "$cmd" =~ [[:space:]]-c[[:space:]]+([^[:space:]]+) ]]; then
    DETECT_CONFIG="${BASH_REMATCH[1]}"
  fi
  # data -d
  if [[ "$cmd" =~ [[:space:]]-d[[:space:]]+([^[:space:]]+) ]]; then
    DETECT_OUTPUT="${BASH_REMATCH[1]}"
    DETECT_DATA_DIR="$(dirname "$DETECT_OUTPUT")"
  fi
}

_detect_ports_from_config() {
  local cfg="$1"
  [[ -f "$cfg" ]] || return 0
  local http p2p
  http="$(grep -E 'fullNodePort[[:space:]]*=' "$cfg" 2>/dev/null | head -1 | grep -oE '[0-9]+' | head -1 || true)"
  p2p="$(grep -E 'listen\.port[[:space:]]*=' "$cfg" 2>/dev/null | head -1 | grep -oE '[0-9]+' | head -1 || true)"
  [[ -n "$http" ]] && DETECT_HTTP_PORT="$http"
  [[ -n "$p2p" ]] && DETECT_P2P_PORT="$p2p"
}

_detect_systemd_units() {
  local u
  for u in tron-mainnet tron-nile tron-shasta java-tron fullnode FullNode tron; do
    if systemctl cat "${u}.service" >/dev/null 2>&1; then
      if [[ -z "$DETECT_SYSTEMD_UNIT" ]]; then
        DETECT_SYSTEMD_UNIT="$u"
      fi
      # Prefer active unit
      if systemctl is-active --quiet "${u}.service" 2>/dev/null; then
        DETECT_SYSTEMD_UNIT="$u"
        break
      fi
    fi
  done
  # Also scan units that mention FullNode.jar
  if [[ -z "$DETECT_SYSTEMD_UNIT" ]] && command -v systemctl >/dev/null 2>&1; then
    local found
    found="$(systemctl list-unit-files --type=service --no-legend 2>/dev/null \
      | awk '{print $1}' | grep -iE 'tron|fullnode|java-tron' | head -5 || true)"
    if [[ -n "$found" ]]; then
      DETECT_SYSTEMD_UNIT="${found%%.service}"
      DETECT_SYSTEMD_UNIT="${DETECT_SYSTEMD_UNIT%%$'\n'*}"
    fi
  fi
}

_detect_common_paths() {
  local cand
  for cand in \
    "/data/tron/${TRON_ENV:-mainnet}/output-directory" \
    "/data/tron/output-directory" \
    "/data/java-tron/output-directory" \
    "/home/nodeop/output-directory" \
    "/opt/tron/${TRON_ENV:-mainnet}/output-directory"
  do
    if [[ -d "$cand" ]]; then
      DETECT_OUTPUT="${DETECT_OUTPUT:-$cand}"
      DETECT_DATA_DIR="$(dirname "$DETECT_OUTPUT")"
      DETECT_HAS_DATA=1
      break
    fi
  done

  for cand in \
    "/opt/tron/${TRON_ENV:-mainnet}/FullNode.jar" \
    "/opt/tron/FullNode.jar" \
    "/opt/java-tron/FullNode.jar"
  do
    if [[ -f "$cand" ]]; then
      DETECT_JAR="${DETECT_JAR:-$cand}"
      break
    fi
  done

  for cand in \
    "/etc/tron/${TRON_ENV:-mainnet}/main_net_config.conf" \
    "/etc/tron/${TRON_ENV:-mainnet}/config.conf" \
    "/etc/tron/main_net_config.conf" \
    "/opt/tron/${TRON_ENV:-mainnet}/config.conf"
  do
    if [[ -f "$cand" ]]; then
      DETECT_CONFIG="${DETECT_CONFIG:-$cand}"
      break
    fi
  done

  if [[ -f "/etc/tron/${TRON_ENV:-mainnet}/toolkit.env" ]]; then
    DETECT_TOOLKIT_ENV="/etc/tron/${TRON_ENV:-mainnet}/toolkit.env"
  fi
}

detect_existing_node() {
  detect_reset
  _detect_common_paths
  _detect_systemd_units

  local line pid cmd
  # java process with FullNode.jar
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    pid="${line%% *}"
    cmd="${line#* }"
    if [[ "$cmd" == *FullNode.jar* ]] || [[ "$cmd" == *java-tron* ]]; then
      DETECT_JAVA_PID="$pid"
      DETECT_NODE_RUNNING=1
      _detect_from_cmdline "$cmd"
      DETECT_USER="$(ps -o user= -p "$pid" 2>/dev/null | awk '{print $1}' || true)"
      break
    fi
  done < <(ps -eo pid=,args= 2>/dev/null | grep -E '[j]ava.*(FullNode\.jar|java-tron)' || true)

  # systemd ExecStart if unit known and jar still empty
  if [[ -n "$DETECT_SYSTEMD_UNIT" ]]; then
    local exec
    exec="$(systemctl show -p ExecStart --value "${DETECT_SYSTEMD_UNIT}.service" 2>/dev/null || true)"
    if [[ -n "$exec" && -z "$DETECT_JAR" ]]; then
      _detect_from_cmdline "$exec"
    fi
    if systemctl is-active --quiet "${DETECT_SYSTEMD_UNIT}.service" 2>/dev/null; then
      DETECT_NODE_RUNNING=1
    fi
  fi

  if [[ -n "$DETECT_CONFIG" ]]; then
    _detect_ports_from_config "$DETECT_CONFIG"
  fi

  # Listening ports fallback
  if [[ -z "$DETECT_HTTP_PORT" ]]; then
    if ss -lnt 2>/dev/null | grep -q ':18090'; then
      DETECT_HTTP_PORT=18090
    elif ss -lnt 2>/dev/null | grep -q ':8090'; then
      # Ambiguous: might be gateway or node — only use if no gateway unit
      if ! systemctl cat "tron-${TRON_ENV:-mainnet}-gateway.service" >/dev/null 2>&1; then
        DETECT_HTTP_PORT=8090
      fi
    fi
  fi

  if [[ -d "${DETECT_OUTPUT:-}" ]]; then
    DETECT_HAS_DATA=1
  fi
}

print_detection_report() {
  cat <<EOF
=== detected java-tron / FullNode ===
node_running:   ${DETECT_NODE_RUNNING}
java_pid:       ${DETECT_JAVA_PID:-—}
user:           ${DETECT_USER:-—}
systemd_unit:   ${DETECT_SYSTEMD_UNIT:-—}
jar:            ${DETECT_JAR:-—}
config:         ${DETECT_CONFIG:-—}
data_dir:       ${DETECT_DATA_DIR:-—}
output:         ${DETECT_OUTPUT:-—}
http_port:      ${DETECT_HTTP_PORT:-—}
p2p_port:       ${DETECT_P2P_PORT:-—}
has_chain_data: ${DETECT_HAS_DATA}
toolkit_env:    ${DETECT_TOOLKIT_ENV:-—}
EOF
}
