#!/usr/bin/env bash
# RpcNode host-wide agent watchdog (all networks × envs).
#
# Installed by:
#   - install/agent.sh → install_agent_watchdog (fresh install / curl|bash)
#   - panel Servers → Update agent → POST /api/v1/agent/update
#     (ensureAgentWatchdog + --heal-provisioned on the new binary)
# → /opt/rpcnode/bin/rpcnode-agent-watchdog
# + systemd unit rpcnode-agent-watchdog.service (enabled by default).
#
# Discovers:
#   1) tip units rpcnode-{api,system}-agent.service + tip /healthz
#   2) every leaf unit file rpcnode-{api,system}-agent-<network>-<env>.service
#   3) /etc/rpcnode/nodes/*.json (agent_port listen check when present)
#
# NEVER re-provisions missing units, NEVER stops tip for leaf issues,
# skips remove-pending + intentionally disabled units.
set -euo pipefail

INTERVAL_SEC="${RPCNODE_WATCHDOG_INTERVAL_SEC:-30}"
RATE_LIMIT_SEC="${RPCNODE_WATCHDOG_RATE_LIMIT_SEC:-60}"
STATE_DIR="${RPCNODE_WATCHDOG_STATE_DIR:-/var/lib/rpcnode/watchdog}"
TIP_PORT_FILE="${RPCNODE_AGENT_PORT_FILE:-/etc/rpcnode/agent.port}"
NODES_DIR="${RPCNODE_NODES_DIR:-/etc/rpcnode/nodes}"
REMOVE_JOBS_DIR="${RPCNODE_REMOVE_JOBS_DIR:-/var/lib/rpcnode/remove-jobs}"

mkdir -p "$STATE_DIR"

log() { logger -t rpcnode-agent-watchdog -- "$*" 2>/dev/null || printf '%s\n' "$*"; }

rate_ok() {
  local key="${1:-}"
  local stamp_file now last
  [[ -n "$key" ]] || return 1
  stamp_file="${STATE_DIR}/${key}.last"
  now="$(date +%s)"
  if [[ -f "$stamp_file" ]]; then
    last="$(cat "$stamp_file" 2>/dev/null || echo 0)"
    if [[ "$last" =~ ^[0-9]+$ ]] && (( now - last < RATE_LIMIT_SEC )); then
      return 1
    fi
  fi
  printf '%s\n' "$now" >"$stamp_file"
  return 0
}

unit_remove_pending() {
  local unit="${1%.service}"
  local slug="" st="" job=""
  case "$unit" in
    rpcnode-api-agent-*|rpcnode-system-agent-*)
      slug="${unit#rpcnode-api-agent-}"
      slug="${slug#rpcnode-system-agent-}"
      ;;
    *)
      return 1
      ;;
  esac
  [[ -n "$slug" ]] || return 1
  for job in "${REMOVE_JOBS_DIR}/${slug}.json" "${REMOVE_JOBS_DIR}/tron-${slug}.json"; do
    [[ -f "$job" ]] || continue
    st="$(sed -n 's/.*"status"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$job" | head -1)"
    case "$st" in
      deleting|started|wiped|error) return 0 ;;
    esac
  done
  return 1
}

unit_enabled_or_static() {
  local u="$1" st
  st="$(systemctl is-enabled "$u" 2>/dev/null || true)"
  case "$st" in
    enabled|enabled-runtime|static|alias|indirect) return 0 ;;
    *) return 1 ;;
  esac
}

ensure_unit() {
  local u="$1" reason="$2"
  [[ -f "/etc/systemd/system/${u}" || -f "/lib/systemd/system/${u}" ]] || return 0
  unit_remove_pending "$u" && {
    log "skip ${u} — remove-job pending"
    return 0
  }
  unit_enabled_or_static "$u" || {
    log "skip ${u} — not enabled (intentional stop)"
    return 0
  }
  if systemctl is-active --quiet "$u" 2>/dev/null; then
    return 0
  fi
  rate_ok "$u" || {
    log "rate-limit ${u} (${reason})"
    return 0
  }
  log "start ${u} — ${reason}"
  systemctl reset-failed "$u" 2>/dev/null || true
  systemctl start "$u" 2>/dev/null || log "failed to start ${u}"
}

tip_port() {
  local p=""
  if [[ -f "$TIP_PORT_FILE" ]]; then
    p="$(tr -d '[:space:]' <"$TIP_PORT_FILE" || true)"
  fi
  if [[ ! "$p" =~ ^[0-9]+$ ]]; then
    p=39090
  fi
  printf '%s' "$p"
}

tip_healthy() {
  local port="$1"
  curl -fsS --max-time 2 "http://127.0.0.1:${port}/healthz" >/dev/null 2>&1
}

port_listening() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -lnt 2>/dev/null | grep -qE ":${port}\\b"
    return $?
  fi
  curl -fsS --max-time 1 "http://127.0.0.1:${port}/healthz" >/dev/null 2>&1
}

agent_port_from_unit() {
  local u="$1" p=""
  p="$(systemctl show -p Environment --value "$u" 2>/dev/null \
    | tr ' ' '\n' \
    | sed -n 's/^TRON_AGENT_PORT=//p;s/^RPCNODE_AGENT_PORT=//p' \
    | head -1 | tr -d '[:space:]')"
  if [[ ! "$p" =~ ^[0-9]+$ ]]; then
    p="$(grep -E '^Environment=(TRON|RPCNODE)_AGENT_PORT=' "/etc/systemd/system/${u}" 2>/dev/null \
      | head -1 | cut -d= -f3- | tr -d '[:space:]\r' || true)"
  fi
  printf '%s' "$p"
}

check_tip() {
  local port
  port="$(tip_port)"
  ensure_unit "rpcnode-system-agent.service" "tip system-agent down"
  ensure_unit "rpcnode-api-agent.service" "tip api-agent down"
  if ! tip_healthy "$port"; then
    if rate_ok "tip-healthz"; then
      log "tip :${port} healthz failed — restart tip api-agent"
      systemctl reset-failed rpcnode-api-agent.service 2>/dev/null || true
      systemctl restart rpcnode-api-agent.service 2>/dev/null || true
    fi
  fi
}

# Host-wide: every leaf unit on disk (tron/btc/dash/ton/… × mainnet/testnet/…).
check_leaf_units() {
  local f base slug api_unit sys_unit agent_port
  shopt -s nullglob
  for f in /etc/systemd/system/rpcnode-api-agent-*.service; do
    base="$(basename "$f")"
    # Skip tip-style accidental names; leaves are rpcnode-api-agent-<net>-<env>.
    case "$base" in
      rpcnode-api-agent.service) continue ;;
    esac
    slug="${base#rpcnode-api-agent-}"
    slug="${slug%.service}"
    [[ -n "$slug" ]] || continue
    api_unit="rpcnode-api-agent-${slug}.service"
    sys_unit="rpcnode-system-agent-${slug}.service"

    ensure_unit "$sys_unit" "leaf system-agent ${slug} down"
    ensure_unit "$api_unit" "leaf api-agent ${slug} down"

    agent_port="$(agent_port_from_unit "$api_unit")"
    if [[ "$agent_port" =~ ^[0-9]+$ ]] && (( agent_port > 0 )); then
      if systemctl is-active --quiet "$api_unit" 2>/dev/null && ! port_listening "$agent_port"; then
        if rate_ok "${api_unit}-port"; then
          log "leaf ${slug} active but :${agent_port} not listening — restart"
          systemctl reset-failed "$api_unit" 2>/dev/null || true
          systemctl restart "$api_unit" 2>/dev/null || true
        fi
      fi
    fi
  done
}

# Inventory JSON may list ports before/without matching unit scan edge-cases.
check_nodes_inventory() {
  local f network env agent_port slug api_unit
  [[ -d "$NODES_DIR" ]] || return 0
  shopt -s nullglob
  for f in "$NODES_DIR"/*.json; do
    network="$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d.get("network") or "")' "$f" 2>/dev/null || true)"
    env="$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d.get("env") or "")' "$f" 2>/dev/null || true)"
    agent_port="$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(int(d.get("agent_port") or 0))' "$f" 2>/dev/null || echo 0)"
    [[ -n "$network" && -n "$env" ]] || continue
    slug="${network}-${env}"
    api_unit="rpcnode-api-agent-${slug}.service"
    [[ -f "/etc/systemd/system/${api_unit}" ]] || continue
    ensure_unit "rpcnode-system-agent-${slug}.service" "inventory sys ${slug}"
    ensure_unit "$api_unit" "inventory api ${slug}"
    if [[ "$agent_port" =~ ^[0-9]+$ ]] && (( agent_port > 0 )); then
      if systemctl is-active --quiet "$api_unit" 2>/dev/null && ! port_listening "$agent_port"; then
        if rate_ok "${api_unit}-inv-port"; then
          log "inventory ${slug} :${agent_port} refused — restart"
          systemctl reset-failed "$api_unit" 2>/dev/null || true
          systemctl restart "$api_unit" 2>/dev/null || true
        fi
      fi
    fi
  done
}

main_loop() {
  log "watchdog started interval=${INTERVAL_SEC}s rate_limit=${RATE_LIMIT_SEC}s (host-wide tip+all leaf agents)"
  while true; do
    check_tip || true
    check_leaf_units || true
    check_nodes_inventory || true
    sleep "$INTERVAL_SEC"
  done
}

main_loop
