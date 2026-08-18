#!/usr/bin/env bash
# RpcNode — remove host tip + leaf agents + watchdog ONLY.
# Fullnode chain units and /data|/etc|/opt/<network> are left untouched.
#
#   curl -fsSL "https://toolkit.rpcnode.dev/install/uninstall-agents.sh" | sudo bash
#
# Use after Panel → Servers → Remove (panel only drops the registry row).
# For full wipe (agents + fullnodes + datadirs) use:
#   curl -fsSL "https://toolkit.rpcnode.dev/install/agent.sh" | sudo bash -s -- --uninstall
set -euo pipefail

BIN_DIR="${BIN_DIR:-/opt/rpcnode/bin}"
INSTALL_ROOT="${INSTALL_ROOT:-/opt/rpcnode}"

hostlog() {
  local level="$1"
  shift
  local ts f
  ts="$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date)"
  f="${RPCNODE_HOST_LOG:-/var/log/rpcnode.log}"
  printf '%s %-5s [uninstall-agents] %s\n' "$ts" "$level" "$*" >>"$f" 2>/dev/null || true
}
log() { printf '+ %s\n' "$*"; hostlog INFO "$*"; }
warn() { printf '! %s\n' "$*" >&2; hostlog WARN "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; hostlog ERROR "$*"; exit 1; }

if [[ "$(id -u)" -ne 0 ]]; then
  die "run as root (sudo), e.g. curl -fsSL \"https://toolkit.rpcnode.dev/install/uninstall-agents.sh\" | sudo bash"
fi

purge_unit() {
  local u="${1%.service}.service"
  [[ -n "$u" && "$u" != ".service" ]] || return 0
  systemctl disable --now "$u" 2>/dev/null || true
  systemctl stop "$u" 2>/dev/null || true
  systemctl kill -s SIGKILL --kill-who=all "$u" 2>/dev/null || true
  rm -f "/etc/systemd/system/${u}" "/lib/systemd/system/${u}"
  rm -rf "/etc/systemd/system/${u}.d"
}

is_agent_unit() {
  # tip / leaf / watchdog only — not chain fullnodes (bitcoin-mainnet, tron-mainnet, …)
  local base="${1%.service}"
  case "$base" in
    rpcnode-agent-watchdog) return 0 ;;
    rpcnode-api-agent|rpcnode-system-agent) return 0 ;;
    rpcnode-api-agent-*|rpcnode-system-agent-*) return 0 ;;
    *) return 1 ;;
  esac
}

log "uninstall-agents: tip + leaf agents + watchdog (fullnodes untouched)"

if command -v systemctl >/dev/null 2>&1; then
  # Watchdog first so it cannot restart tip/leaves during teardown.
  purge_unit rpcnode-agent-watchdog.service

  shopt -s nullglob
  for f in /etc/systemd/system/rpcnode-*.service
  do
    [[ -f "$f" ]] || continue
    base="$(basename "$f")"
    is_agent_unit "$base" || continue
    purge_unit "$base"
  done
  shopt -u nullglob

  # Drop-ins left behind without a unit file.
  shopt -s nullglob
  for f in /etc/systemd/system/rpcnode-*.service.d
  do
    [[ -d "$f" ]] || continue
    base="$(basename "$f" .service.d).service"
    is_agent_unit "$base" || continue
    rm -rf "$f"
  done
  shopt -u nullglob

  # Kill stray agent PIDs by binary path only (never match this script / curl|bash argv).
  if command -v pgrep >/dev/null 2>&1; then
    for pid in $(pgrep -f '/opt/rpcnode/bin/rpcnode-(api|system)-agent( |$)' 2>/dev/null || true); do
      kill -9 "$pid" 2>/dev/null || true
    done
    for pid in $(pgrep -f '/opt/rpcnode/bin/rpcnode-agent-watchdog( |$)' 2>/dev/null || true); do
      kill -9 "$pid" 2>/dev/null || true
    done
    for pid in $(pgrep -f '/usr/local/bin/rpcnode-(api|system)-agent( |$)' 2>/dev/null || true); do
      kill -9 "$pid" 2>/dev/null || true
    done
  fi

  systemctl daemon-reload 2>/dev/null || true
  systemctl reset-failed 2>/dev/null || true
fi

# Agent binaries + symlinks (leave INSTALL_ROOT dir if empty of other stuff).
rm -f \
  "${BIN_DIR}/rpcnode-api-agent" \
  "${BIN_DIR}/rpcnode-system-agent" \
  "${BIN_DIR}/rpcnode-agent-watchdog" \
  /usr/local/bin/rpcnode-api-agent \
  /usr/local/bin/rpcnode-system-agent
rm -f "${BIN_DIR}"/rpcnode-api-agent-* "${BIN_DIR}"/rpcnode-system-agent-* 2>/dev/null || true
rm -f "${BIN_DIR}"/rpcnode-system-agent.bak.* 2>/dev/null || true

# Tip/leaf control-plane state — not chain datadirs.
rm -rf /etc/rpcnode
rm -rf /var/log/rpcnode
rm -f /etc/logrotate.d/rpcnode-agents
if [[ -d /var/lib/rpcnode ]]; then
  find /var/lib/rpcnode -mindepth 1 -maxdepth 1 \
    ! -name 'panel' ! -name 'panel.db' 2>/dev/null | while read -r d; do
    rm -rf "$d"
  done
fi

# Empty install root if nothing else remains.
rmdir "${BIN_DIR}" 2>/dev/null || true
rmdir "${INSTALL_ROOT}" 2>/dev/null || true

cat <<'EOF'

OK  RpcNode agents removed (tip + leaves + watchdog).
    Fullnode units and /data|/etc|/opt/<network> were NOT touched.
    Re-install tip:  curl -fsSL "https://toolkit.rpcnode.dev/install/agent.sh" | sudo bash

EOF
