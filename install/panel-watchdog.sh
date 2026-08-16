#!/usr/bin/env bash
# RpcNode panel Docker watchdog — restarts panel / panel-collector when they look dead.
#
# Compose service: panel-watchdog (docker-compose.panel.yml).
# Image copies this script to /usr/local/bin/rpcnode-panel-watchdog.
#
# Panel: curl healthz. Collector: mtime of /var/lib/rpcnode/collector.heartbeat
# (written by panel-collector every enqueue / ~2s). Stale after 120s → docker restart.
# NEVER restarts this watchdog container.
set -euo pipefail

INTERVAL_SEC="${INTERVAL_SEC:-20}"
STALE_SEC="${STALE_SEC:-120}"
RATE_LIMIT_SEC="${RATE_LIMIT_SEC:-90}"
PANEL_HEALTH_URL="${PANEL_HEALTH_URL:-http://panel:8093/healthz}"
PANEL_CONTAINER="${PANEL_CONTAINER:-rpcnode-panel}"
COLLECTOR_CONTAINER="${COLLECTOR_CONTAINER:-rpcnode-panel-collector}"
HEARTBEAT="${COLLECTOR_HEARTBEAT:-/var/lib/rpcnode/collector.heartbeat}"
STATE_DIR="${WATCHDOG_STATE_DIR:-/var/lib/rpcnode/panel-watchdog}"
SELF_CONTAINER="${SELF_CONTAINER:-rpcnode-panel-watchdog}"

mkdir -p "$STATE_DIR"
started_at="$(date +%s)"

log() { printf '%s panel-watchdog: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

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

is_self() {
  local name="${1:-}"
  [[ "$name" == "$SELF_CONTAINER" || "$name" == "panel-watchdog" ]]
}

restart_container() {
  local name="${1:-}"
  local reason="${2:-}"
  if is_self "$name"; then
    log "refuse to restart self ($name)"
    return 0
  fi
  if ! rate_ok "$name"; then
    log "skip restart $name (rate limit ${RATE_LIMIT_SEC}s) — $reason"
    return 0
  fi
  log "restart $name ($reason)"
  if ! docker restart "$name"; then
    log "docker restart $name failed"
  fi
}

panel_alive() {
  curl -fsS -m 3 -o /dev/null "$PANEL_HEALTH_URL"
}

heartbeat_mtime() {
  if [[ ! -f "$HEARTBEAT" ]]; then
    echo 0
    return
  fi
  stat -c %Y "$HEARTBEAT" 2>/dev/null || stat -f %m "$HEARTBEAT" 2>/dev/null || echo 0
}

collector_stale() {
  local now mtime age
  now="$(date +%s)"
  if [[ ! -f "$HEARTBEAT" ]]; then
    (( now - started_at > STALE_SEC ))
    return
  fi
  mtime="$(heartbeat_mtime)"
  if [[ ! "$mtime" =~ ^[0-9]+$ ]]; then
    mtime=0
  fi
  age=$(( now - mtime ))
  (( age > STALE_SEC ))
}

if [[ ! -S /var/run/docker.sock && -z "${DOCKER_HOST:-}" ]]; then
  log "docker.sock not mounted and DOCKER_HOST unset — cannot restart containers"
fi

log "start interval=${INTERVAL_SEC}s stale=${STALE_SEC}s panel=${PANEL_CONTAINER} collector=${COLLECTOR_CONTAINER}"

while true; do
  if ! panel_alive; then
    restart_container "$PANEL_CONTAINER" "healthz failed (${PANEL_HEALTH_URL})"
  fi
  if collector_stale; then
    restart_container "$COLLECTOR_CONTAINER" "heartbeat older than ${STALE_SEC}s (${HEARTBEAT})"
  fi
  sleep "$INTERVAL_SEC"
done
