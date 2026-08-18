#!/usr/bin/env bash
# Per-environment path layout (mainnet / nile / shasta coexist on one host).
# Override via /etc/tron/<env>/toolkit.env or env vars.
# Ports: see lib/ports.sh (mainnet 8090/18090, nile 8091/18091, shasta 8092/18092).

# Instance env (mainnet/nile/…). Canonical RPCNODE_ENV; TRON_ENV is legacy alias.
RPCNODE_ENV="${RPCNODE_ENV:-${TRON_ENV:-mainnet}}"
TRON_ENV="${TRON_ENV:-$RPCNODE_ENV}"

load_env_file() {
  local f="/etc/tron/${TRON_ENV}/toolkit.env"
  if [[ -f "$f" ]]; then
    # shellcheck disable=SC1090
    set -a; source "$f"; set +a
  fi
  # Re-sync after source (file may only define one of the names).
  RPCNODE_ENV="${RPCNODE_ENV:-${TRON_ENV:-mainnet}}"
  TRON_ENV="${TRON_ENV:-$RPCNODE_ENV}"
}

load_env_file

# shellcheck disable=SC1091
source "${TOOLKIT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}/lib/ports.sh"
apply_env_port_defaults

# Defaults — keep each env under …/<env> so stacks can coexist.
TRON_ENV_NAME="${TRON_ENV_NAME:-$TRON_ENV}"
TRON_OPT="${TRON_OPT:-/opt/tron/${TRON_ENV}}"
TRON_ETC="${TRON_ETC:-/etc/tron/${TRON_ENV}}"
TRON_DATA="${TRON_DATA:-/data/tron/${TRON_ENV}}"
TRON_OUTPUT="${TRON_OUTPUT:-${TRON_DATA}/output-directory}"
TRON_LOGS="${TRON_LOGS:-${TRON_DATA}/logs}"
TRON_JAR="${TRON_JAR:-${TRON_OPT}/FullNode.jar}"
TRON_CONFIG="${TRON_CONFIG:-${TRON_ETC}/main_net_config.conf}"
TRON_VERSION_FILE="${TRON_VERSION_FILE:-${TRON_OPT}/VERSION}"
TRON_SERVICE="${TRON_SERVICE:-tron-${TRON_ENV}}"
TRON_SNAPSHOT_SERVICE="${TRON_SNAPSHOT_SERVICE:-tron-${TRON_ENV}-snapshot}"
TRON_GATEWAY_SERVICE="${TRON_GATEWAY_SERVICE:-tron-${TRON_ENV}-gateway}" # legacy name
TRON_API_SERVICE="${TRON_API_SERVICE:-tron-${TRON_ENV}-api}"
TRON_SYSTEM_SERVICE="${TRON_SYSTEM_SERVICE:-tron-${TRON_ENV}-system}"
TRON_UPDATER_SERVICE="${TRON_UPDATER_SERVICE:-tron-${TRON_ENV}-updater}"
TRON_UPDATER_TIMER="${TRON_UPDATER_TIMER:-tron-${TRON_ENV}-updater.timer}"

# java-tron HTTP (internal). Public entry is Go api-agent RPCNODE_PUBLIC_PORT.
TRON_NODE_HTTP_HOST="${TRON_NODE_HTTP_HOST:-127.0.0.1}"
RPCNODE_GATEWAY_LISTEN="${RPCNODE_GATEWAY_LISTEN:-${TRON_GATEWAY_LISTEN:-0.0.0.0}}"
TRON_GATEWAY_LISTEN="$RPCNODE_GATEWAY_LISTEN"
# PUBLIC_BASE alias accepted by agents (RPCNODE_* canonical).
RPCNODE_PUBLIC_BASE="${RPCNODE_PUBLIC_BASE:-${TRON_PUBLIC_BASE:-${PUBLIC_BASE:-}}}"
TRON_PUBLIC_BASE="$RPCNODE_PUBLIC_BASE"
PUBLIC_BASE="${PUBLIC_BASE:-$RPCNODE_PUBLIC_BASE}"
RPCNODE_PANEL_BASE="${RPCNODE_PANEL_BASE:-${TRON_PANEL_BASE:-}}"
TRON_PANEL_BASE="$RPCNODE_PANEL_BASE"

# Docker-first state + registry (bind-mounted into containers).
RPCNODE_LIB_DIR="${RPCNODE_LIB_DIR:-/var/lib/rpcnode}"
RPCNODE_REGISTRY_DIR="${RPCNODE_REGISTRY_DIR:-/etc/rpcnode/instances.d}"
TRON_AGENT_STATE="${TRON_AGENT_STATE:-${RPCNODE_LIB_DIR}/tron-${TRON_ENV}/agent-state.json}"
TRON_INSTANCE_FILE="${TRON_INSTANCE_FILE:-${RPCNODE_LIB_DIR}/tron-${TRON_ENV}/INSTANCE.json}"
TRON_REGISTRY_FILE="${TRON_REGISTRY_FILE:-${RPCNODE_REGISTRY_DIR}/tron-${TRON_ENV}.json}"
TRON_ENV_FILE="${TRON_ENV_FILE:-/etc/tron/${TRON_ENV}/toolkit.env}"
# Agents run via docker compose (primary). Optional compose systemd wrapper.
TRON_DOCKER_FIRST="${TRON_DOCKER_FIRST:-1}"
TRON_COMPOSE_WRAPPER="${TRON_COMPOSE_WRAPPER:-1}"
# Ops panel nginx basic auth (hash only — never commit plaintext).
TRON_PANEL_HTPASSWD="${TRON_PANEL_HTPASSWD:-/etc/rpcnode/panel.htpasswd}"
TRON_PANEL_USER="${TRON_PANEL_USER:-admin}"

# Toolkit self-update channel (agents/UI/nginx — not java-tron).
# VERSION URL returns plain text version; UPDATE_URL optional tarball or git repo.
TOOLKIT_VERSION_URL="${TOOLKIT_VERSION_URL:-https://toolkit.rpcnode.dev/install/TOOLKIT_VERSION}"
TOOLKIT_UPDATE_URL="${TOOLKIT_UPDATE_URL:-}"
TRON_TOOLKIT_UPDATE_STATE="${TRON_TOOLKIT_UPDATE_STATE:-${RPCNODE_LIB_DIR}/tron-${TRON_ENV}/toolkit-update.json}"

TRON_MAINTENANCE_FILE="${TRON_MAINTENANCE_FILE:-/run/tron-${TRON_ENV}/maintenance.json}"
TRON_UPGRADE_LOG="${TRON_UPGRADE_LOG:-/var/log/tron/${TRON_ENV}-upgrades.log}"
TRON_SNAPSHOT_LOG="${TRON_SNAPSHOT_LOG:-/var/log/tron/${TRON_ENV}-snapshot.log}"
TRON_SNAPSHOT_MARKER="${TRON_SNAPSHOT_MARKER:-${TRON_DATA}/.snapshot-ready}"
TRON_SNAPSHOT_STATE="${TRON_SNAPSHOT_STATE:-${TRON_DATA}/.snapshot-state.json}"
TRON_UPDATER_STATE="${TRON_UPDATER_STATE:-${TRON_DATA}/.updater-state.json}"
# Daily check hour (UTC) for systemd OnCalendar.
TRON_UPDATER_HOUR_UTC="${TRON_UPDATER_HOUR_UTC:-3}"
# Auto jar+config upgrade when newer GreatVoyage tag appears (snapshot still opt-in).
TRON_UPDATER_AUTO_APPLY="${TRON_UPDATER_AUTO_APPLY:-1}"
TRON_UPDATER_WITH_SNAPSHOT="${TRON_UPDATER_WITH_SNAPSHOT:-0}"

# Defaults for mainnet LevelDB Full snapshot mirror (override in toolkit.env).
TRON_TAG="${TRON_TAG:-GreatVoyage-v4.8.2.1}"
TRON_JAR_URL="${TRON_JAR_URL:-https://github.com/tronprotocol/java-tron/releases/download/${TRON_TAG}/FullNode.jar}"
TRON_CONFIG_URL="${TRON_CONFIG_URL:-https://raw.githubusercontent.com/tronprotocol/java-tron/${TRON_TAG}/framework/src/main/resources/config.conf}"
TRON_SNAPSHOT_URL="${TRON_SNAPSHOT_URL:-http://34.86.86.229/backup20260808/FullNode_output-directory.tgz}"
TRON_GITHUB_REPO="${TRON_GITHUB_REPO:-tronprotocol/java-tron}"

TRON_JAVA_XMX="${TRON_JAVA_XMX:-48g}"
TRON_JAVA_XMS="${TRON_JAVA_XMS:-48g}"
TRON_USER="${TRON_USER:-nodeop}"
TRON_GROUP="${TRON_GROUP:-nodeop}"

# java-tron high-load API limits (private node behind gateway).
TRON_MAX_HTTP_CONNECT="${TRON_MAX_HTTP_CONNECT:-2000}"
TRON_GLOBAL_QPS="${TRON_GLOBAL_QPS:-200000}"
TRON_GLOBAL_IP_QPS="${TRON_GLOBAL_IP_QPS:-200000}"

# Host-built Go binaries (optional; Docker image is primary).
TRON_GATEWAY_BIN="${TRON_GATEWAY_BIN:-${TRON_OPT}/bin/rpcnode-api-agent}"
TRON_GATEWAY_SRC="${TRON_GATEWAY_SRC:-${TOOLKIT_DIR:-}/api-agent}"
TRON_SYSTEM_AGENT_SRC="${TRON_SYSTEM_AGENT_SRC:-${TOOLKIT_DIR:-}/system-agent}"
# Python gateway removed from supported path (see gateway/legacy/).
