#!/usr/bin/env bash
# Toolkit self-update (agents/UI/nginx compose) — NOT java-tron jar upgrade.
# Channel: TOOLKIT_VERSION_URL (plain text version) + optional TOOLKIT_UPDATE_URL (tarball/git).
#
# Docker-first apply needs:
#   - docker CLI + compose plugin in the agent image
#   - /var/run/docker.sock mounted into system-agent
#   - TOOLKIT_DIR bind-mounted :rw (fetch into tree)
# Host fallback: write update-requested.json → `rpcnodectl toolkit-update watch`

toolkit_update_state_path() {
  printf '%s' "${TRON_TOOLKIT_UPDATE_STATE:-/var/lib/rpcnode/tron-${TRON_ENV:-mainnet}/toolkit-update.json}"
}

toolkit_update_request_path() {
  printf '%s' "${TRON_TOOLKIT_UPDATE_REQUEST:-/var/lib/rpcnode/tron-${TRON_ENV:-mainnet}/update-requested.json}"
}

toolkit_local_version() {
  local f="${TOOLKIT_DIR}/TOOLKIT_VERSION"
  if [[ -f "$f" ]]; then
    tr -d '[:space:]' <"$f"
  else
    echo "0.0.0"
  fi
}

_toolkit_default_state() {
  local local_v
  local_v="$(toolkit_local_version)"
  cat <<EOF
{
  "auto": false,
  "hour_utc": 4,
  "minute_utc": 15,
  "channel": "${TOOLKIT_VERSION_URL:-}",
  "update_url": "${TOOLKIT_UPDATE_URL:-}",
  "local_version": "${local_v}",
  "remote_version": "",
  "update_available": false,
  "status": "idle",
  "message": "",
  "last_check_at": "",
  "last_apply_at": "",
  "last_auto_day": "",
  "progress": "",
  "apply_mode": "",
  "apply_ready": false
}
EOF
}

read_toolkit_update_state() {
  local path
  path="$(toolkit_update_state_path)"
  if [[ -f "$path" ]] && command -v jq >/dev/null 2>&1; then
    cat "$path"
  else
    _toolkit_default_state
  fi
}

write_toolkit_update_state() {
  # Usage: write_toolkit_update_state key=val key=val ...
  local path tmp
  path="$(toolkit_update_state_path)"
  mkdir -p "$(dirname "$path")"
  tmp="${path}.tmp.$$"
  local base
  base="$(read_toolkit_update_state)"
  if ! command -v jq >/dev/null 2>&1; then
    printf '%s\n' "$base" >"$path"
    return 0
  fi
  printf '%s\n' "$base" >"$tmp"
  local kv k v
  for kv in "$@"; do
    k="${kv%%=*}"
    v="${kv#*=}"
    case "$v" in
      true|false)
        jq --arg k "$k" --argjson v "$v" '.[$k]=$v' "$tmp" >"${tmp}.2" && mv "${tmp}.2" "$tmp"
        ;;
      ''|*[!0-9]*)
        jq --arg k "$k" --arg v "$v" '.[$k]=$v' "$tmp" >"${tmp}.2" && mv "${tmp}.2" "$tmp"
        ;;
      *)
        jq --arg k "$k" --argjson v "$v" '.[$k]=$v' "$tmp" >"${tmp}.2" && mv "${tmp}.2" "$tmp"
        ;;
    esac
  done
  mv -f "$tmp" "$path"
  chmod 644 "$path" 2>/dev/null || true
}

# Echoes empty string if apply can run in this environment; otherwise a reason.
toolkit_update_apply_blocker() {
  if [[ -z "${TOOLKIT_DIR:-}" || ! -d "$TOOLKIT_DIR" ]]; then
    echo "TOOLKIT_DIR missing"
    return 0
  fi
  if [[ ! -w "$TOOLKIT_DIR" ]]; then
    echo "toolkit dir not writable — mount TOOLKIT_DIR :rw"
    return 0
  fi
  if ! command -v docker >/dev/null 2>&1; then
    echo "docker CLI missing in agent image"
    return 0
  fi
  if ! docker compose version >/dev/null 2>&1; then
    echo "docker compose plugin missing"
    return 0
  fi
  if [[ ! -S /var/run/docker.sock && -z "${DOCKER_HOST:-}" ]]; then
    echo "docker.sock not mounted (and DOCKER_HOST unset)"
    return 0
  fi
  if [[ ! -x "${TOOLKIT_DIR}/rpcnodectl" && ! -x "${TOOLKIT_DIR}/tronctl" ]]; then
    echo "rpcnodectl not found under TOOLKIT_DIR"
    return 0
  fi
  echo ""
  return 1
}

toolkit_update_can_apply() {
  local reason
  reason="$(toolkit_update_apply_blocker || true)"
  [[ -z "$reason" ]]
}

fetch_remote_toolkit_version() {
  local url="${TOOLKIT_VERSION_URL:-}"
  if [[ -z "$url" ]]; then
    echo ""
    return 0
  fi
  curl -fsSL --max-time 15 "$url" 2>/dev/null | head -1 | tr -d '[:space:]' || true
}

toolkit_update_check() {
  local local_v remote
  local_v="$(toolkit_local_version)"
  remote="$(fetch_remote_toolkit_version)"
  local available=false
  local status="ok" msg="up to date"
  local blocker mode="unavailable" ready=false
  blocker="$(toolkit_update_apply_blocker || true)"
  if [[ -z "$blocker" ]]; then
    mode="docker-sock"
    ready=true
  else
    mode="host-queue"
    ready=false
  fi
  if [[ -z "${TOOLKIT_VERSION_URL:-}" ]]; then
    status="idle"
    msg="no TOOLKIT_VERSION_URL configured — set channel to enable remote checks"
    remote=""
  elif [[ -z "$remote" ]]; then
    status="error"
    msg="cannot fetch remote version from TOOLKIT_VERSION_URL"
  elif [[ "$remote" != "$local_v" ]]; then
    available=true
    status="available"
    msg="update available: ${local_v} → ${remote}"
  else
    msg="up to date (${local_v})"
  fi
  if [[ -n "$blocker" && "$available" == "true" ]]; then
    msg="${msg}; apply needs: ${blocker} (or host rpcnodectl toolkit-update watch)"
  fi
  write_toolkit_update_state \
    "local_version=${local_v}" \
    "remote_version=${remote}" \
    "update_available=${available}" \
    "status=${status}" \
    "message=${msg}" \
    "last_check_at=$(ts)" \
    "channel=${TOOLKIT_VERSION_URL:-}" \
    "update_url=${TOOLKIT_UPDATE_URL:-}" \
    "apply_mode=${mode}" \
    "apply_ready=${ready}"
  printf '%s\n' "$(read_toolkit_update_state)"
}

_toolkit_busy_skip() {
  local maint="${TRON_MAINTENANCE_FILE:-/run/tron-${TRON_ENV}/maintenance.json}"
  if [[ -f "$maint" ]] && command -v jq >/dev/null 2>&1; then
    if [[ "$(jq -r '.enabled // false' "$maint" 2>/dev/null)" == "true" ]]; then
      echo "maintenance active"
      return 0
    fi
  fi
  if pgrep -af 'FullNode_output-directory' 2>/dev/null | grep -q wget; then
    echo "snapshot download running"
    return 0
  fi
  echo ""
  return 1
}

_toolkit_fetch_content() {
  local remote="$1"
  if [[ -n "${TOOLKIT_UPDATE_URL:-}" ]]; then
    write_toolkit_update_state "progress=2/5 fetch ${TOOLKIT_UPDATE_URL}"
    local tmp
    tmp="$(mktemp -d /tmp/toolkit-upd.XXXXXX)"
    if [[ "${TOOLKIT_UPDATE_URL}" == git+* || "${TOOLKIT_UPDATE_URL}" == *.git ]]; then
      write_toolkit_update_state "status=error" "message=source checkout updates are disabled; use CDN archive" "progress="
      rm -rf "$tmp"
      return 1
    fi
    curl -fL --max-time 120 -o "$tmp/upd.tgz" "$TOOLKIT_UPDATE_URL" || {
      write_toolkit_update_state "status=error" "message=download failed" "progress="
      rm -rf "$tmp"
      return 1
    }
    mkdir -p "$tmp/src"
    tar -xzf "$tmp/upd.tgz" -C "$tmp/src" --strip-components=1 2>/dev/null \
      || tar -xzf "$tmp/upd.tgz" -C "$tmp/src"
    rsync -a --exclude 'config/nginx/htpasswd/' "$tmp/src"/ "$TOOLKIT_DIR"/
    rm -rf "$tmp"
    if [[ -n "$remote" ]]; then
      printf '%s\n' "$remote" >"${TOOLKIT_DIR}/TOOLKIT_VERSION"
    fi
  elif [[ -n "$remote" ]]; then
    printf '%s\n' "$remote" >"${TOOLKIT_DIR}/TOOLKIT_VERSION"
  fi
  return 0
}

toolkit_update_queue_host() {
  local reason="${1:-docker apply unavailable}"
  local path
  path="$(toolkit_update_request_path)"
  mkdir -p "$(dirname "$path")"
  cat >"$path" <<EOF
{
  "requested_at": "$(ts)",
  "reason": "${reason}",
  "source": "system-agent",
  "env": "${TRON_ENV:-mainnet}"
}
EOF
  write_toolkit_update_state \
    "status=queued" \
    "progress=" \
    "apply_mode=host-queue" \
    "apply_ready=false" \
    "message=queued for host apply (${reason}) — run: rpcnodectl toolkit-update watch"
  echo "$(read_toolkit_update_state)"
}

toolkit_update_apply() {
  local force="${1:-0}"
  local st remote local_v
  st="$(toolkit_update_check)"
  local_v="$(echo "$st" | jq -r '.local_version // empty' 2>/dev/null || toolkit_local_version)"
  remote="$(echo "$st" | jq -r '.remote_version // empty' 2>/dev/null || true)"
  local avail
  avail="$(echo "$st" | jq -r '.update_available // false' 2>/dev/null || echo false)"

  if [[ "$force" != "1" && "$avail" != "true" ]]; then
    if [[ -z "$remote" || "$remote" == "$local_v" ]]; then
      write_toolkit_update_state "status=ok" "message=nothing to apply" "progress="
      echo "$(read_toolkit_update_state)"
      return 0
    fi
  fi

  local blocker
  blocker="$(toolkit_update_apply_blocker || true)"
  if [[ -n "$blocker" ]]; then
    toolkit_update_queue_host "$blocker"
    return 0
  fi

  write_toolkit_update_state \
    "status=updating" \
    "apply_mode=systemd-binaries" \
    "apply_ready=true" \
    "message=updating host agents (binaries + systemd) — java-tron NOT touched" \
    "progress=1/3 prepare"

  _toolkit_fetch_content "$remote" || return 1

  write_toolkit_update_state "progress=2/3 reinstall agents"
  # Re-run public installer (downloads matching OS/arch binaries, restarts units).
  if ! curl -fsSL "${AGENT_DOWNLOAD_URL:-https://toolkit.rpcnode.dev/install/agent.sh}" | bash; then
    write_toolkit_update_state "status=error" "message=agent.sh reinstall failed" "progress="
    return 1
  fi

  write_toolkit_update_state \
    "status=ok" \
    "progress=" \
    "message=toolkit updated to $(toolkit_local_version) (java-tron untouched)" \
    "local_version=$(toolkit_local_version)" \
    "update_available=false" \
    "last_apply_at=$(ts)" \
    "apply_mode=systemd-binaries" \
    "apply_ready=true"
  rm -f "$(toolkit_update_request_path)" 2>/dev/null || true
  echo "$(read_toolkit_update_state)"
}

toolkit_update_set_schedule() {
  local auto="$1" hour="$2" minute="$3"
  [[ "$hour" =~ ^[0-9]+$ ]] || hour=4
  [[ "$minute" =~ ^[0-9]+$ ]] || minute=15
  (( hour >= 0 && hour <= 23 )) || hour=4
  (( minute >= 0 && minute <= 59 )) || minute=15
  case "$auto" in true|1|yes|on) auto=true ;; *) auto=false ;; esac
  write_toolkit_update_state \
    "auto=${auto}" \
    "hour_utc=${hour}" \
    "minute_utc=${minute}" \
    "message=schedule saved (daily ${hour}:$(printf '%02d' "$minute") UTC)"
  echo "$(read_toolkit_update_state)"
}

# Called by system-agent or cron — apply if auto+time match.
toolkit_update_tick() {
  local st auto hour minute day last
  st="$(read_toolkit_update_state)"
  command -v jq >/dev/null 2>&1 || return 0
  auto="$(echo "$st" | jq -r '.auto // false')"
  [[ "$auto" == "true" ]] || return 0
  hour="$(echo "$st" | jq -r '.hour_utc // 4')"
  minute="$(echo "$st" | jq -r '.minute_utc // 15')"
  local now_h now_m today
  now_h="$(date -u +%H)"
  now_m="$(date -u +%M)"
  today="$(date -u +%Y-%m-%d)"
  now_h=$((10#$now_h))
  now_m=$((10#$now_m))
  hour=$((10#$hour))
  minute=$((10#$minute))
  (( now_h == hour && now_m == minute )) || return 0
  last="$(echo "$st" | jq -r '.last_auto_day // empty')"
  [[ "$last" == "$today" ]] && return 0

  local reason
  reason="$(_toolkit_busy_skip || true)"
  if [[ -n "$reason" ]]; then
    write_toolkit_update_state "message=auto-update skipped: ${reason}" "last_auto_day=${today}"
    return 0
  fi

  write_toolkit_update_state "last_auto_day=${today}" "message=auto-update starting"
  toolkit_update_check >/dev/null
  st="$(read_toolkit_update_state)"
  if [[ "$(echo "$st" | jq -r '.update_available')" != "true" ]]; then
    write_toolkit_update_state "message=auto-update: already up to date"
    return 0
  fi
  warn "AUTO toolkit update $(echo "$st" | jq -r '.local_version') → $(echo "$st" | jq -r '.remote_version') (java-tron untouched)"
  toolkit_update_apply 0 || true
}

# Host-side: apply queued requests from system-agent (when sock unavailable).
toolkit_update_watch_once() {
  local path
  path="$(toolkit_update_request_path)"
  [[ -f "$path" ]] || return 0
  info "host apply: found $(basename "$path")"
  rm -f "$path"
  toolkit_update_apply 1
}

toolkit_update_watch_loop() {
  info "watching $(toolkit_update_request_path) (Ctrl+C to stop)"
  while true; do
    toolkit_update_watch_once || true
    sleep "${TRON_TOOLKIT_WATCH_INTERVAL_SEC:-5}"
  done
}

cmd_toolkit_update() {
  local sub="${1:-status}"
  shift || true
  # shellcheck disable=SC1091
  source "$TOOLKIT_DIR/lib/paths.sh"
  case "$sub" in
    status|show)
      read_toolkit_update_state | { command -v jq >/dev/null && jq . || cat; }
      ;;
    check)
      toolkit_update_check | { command -v jq >/dev/null && jq . || cat; }
      ;;
    apply)
      require_root
      local yes=0
      [[ "${1:-}" == "--yes" || "${1:-}" == "-y" ]] && yes=1
      if [[ "$yes" != "1" && "${SETUP_NONINTERACTIVE:-0}" != "1" && -t 0 ]]; then
        warn "Updates nginx + system-agent + api-agent images/UI. java-tron on host is NOT upgraded."
        local go
        _setup_yn "Apply toolkit update now?" "n" go
        [[ "$go" == "y" ]] || die "aborted"
      fi
      toolkit_update_apply 1 | { command -v jq >/dev/null && jq . || cat; }
      ;;
    schedule)
      require_root
      local auto="false" hour=4 minute=15
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --auto|--enable) auto=true; shift ;;
          --off|--disable) auto=false; shift ;;
          --hour) hour="$2"; shift 2 ;;
          --minute) minute="$2"; shift 2 ;;
          --time)
            hour="${2%%:*}"
            minute="${2##*:}"
            shift 2
            ;;
          *) shift ;;
        esac
      done
      toolkit_update_set_schedule "$auto" "$hour" "$minute" | { command -v jq >/dev/null && jq . || cat; }
      ;;
    tick)
      toolkit_update_tick
      ;;
    watch)
      require_root
      if [[ "${1:-}" == "--once" ]]; then
        toolkit_update_watch_once
      else
        toolkit_update_watch_loop
      fi
      ;;
    *)
      die "toolkit-update: status|check|apply|schedule|tick|watch"
      ;;
  esac
}
