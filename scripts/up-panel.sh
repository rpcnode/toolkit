#!/usr/bin/env bash
# Start the control-plane panel from THIS toolkit checkout.
# Builds rpcnode-panel:local from ./Dockerfile — never docker pull rpcnode-panel.
#
#   ./scripts/up-panel.sh
#   ./scripts/up-panel.sh --no-cache
#
# First run asks for admin login/password and writes
# config/nginx/htpasswd/panel.htpasswd on the host (no /setup-password in the UI).
# Non-interactive: PANEL_USER=admin PANEL_PASS='...' ./scripts/up-panel.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.panel.yml}"
HTPASSWD_FILE="${PANEL_HTPASSWD_FILE:-$ROOT/config/nginx/htpasswd/panel.htpasswd}"
NO_CACHE=0

log() { printf '+ %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

for arg in "$@"; do
  case "$arg" in
    --no-cache) NO_CACHE=1 ;;
    -h|--help)
      sed -n '2,16p' "$0"
      exit 0
      ;;
    *) die "unknown arg: $arg (try --help)" ;;
  esac
done

cd "$ROOT"
[[ -f "$ROOT/$COMPOSE_FILE" ]] || die "missing $COMPOSE_FILE"
[[ -f "$ROOT/Dockerfile" ]] || die "missing Dockerfile (panel image is built from this repo)"
command -v docker >/dev/null 2>&1 || die "docker not found"

htpasswd_has_user() {
  [[ -s "$1" ]] && grep -qE '^[^#[:space:]][^:]*:' "$1"
}

hash_htpasswd_line() {
  local user="$1" pass="$2"
  if command -v htpasswd >/dev/null 2>&1; then
    htpasswd -nbB "$user" "$pass"
    return 0
  fi
  if command -v openssl >/dev/null 2>&1; then
    printf '%s:%s\n' "$user" "$(openssl passwd -apr1 "$pass")"
    return 0
  fi
  die "need htpasswd (apache) or openssl to hash the panel password"
}

ensure_admin_htpasswd() {
  mkdir -p "$(dirname "$HTPASSWD_FILE")"
  if htpasswd_has_user "$HTPASSWD_FILE"; then
    log "panel login already in $HTPASSWD_FILE"
    return 0
  fi

  local user pass pass2
  user="${PANEL_USER:-admin}"
  if [[ -n "${PANEL_PASS:-}" ]]; then
    pass="$PANEL_PASS"
  elif [[ -r /dev/tty ]] && : >/dev/tty 2>/dev/null; then
    printf 'Panel admin login [%s]: ' "$user" >/dev/tty
    read -r got </dev/tty || true
    [[ -n "${got:-}" ]] && user="$got"
    printf 'Panel admin password: ' >/dev/tty
    read -rs pass </dev/tty
    printf '\n' >/dev/tty
    printf 'Repeat password: ' >/dev/tty
    read -rs pass2 </dev/tty
    printf '\n' >/dev/tty
    [[ "$pass" == "$pass2" ]] || die "passwords do not match"
  else
    die "no panel user yet — run in a terminal, or set PANEL_USER and PANEL_PASS"
  fi

  user="$(printf '%s' "$user" | tr -d '[:space:]')"
  [[ -n "$user" && "$user" != *:* ]] || die "invalid username"
  [[ ${#pass} -ge 8 ]] || die "password must be at least 8 characters"

  local line tmp
  line="$(hash_htpasswd_line "$user" "$pass")"
  [[ -n "$line" ]] || die "failed to hash password"
  tmp="${HTPASSWD_FILE}.tmp"
  printf '%s\n' "$line" >"$tmp"
  mv -f "$tmp" "$HTPASSWD_FILE"
  chmod 640 "$HTPASSWD_FILE" 2>/dev/null || true
  log "wrote panel login user=${user} → $HTPASSWD_FILE"
}

ensure_admin_htpasswd

build=(build --pull=false)
if [[ "$NO_CACHE" -eq 1 ]]; then
  build+=(--no-cache)
fi
log "docker compose -f $COMPOSE_FILE ${build[*]}  (rpcnode-panel:local from ./Dockerfile)"
docker compose -f "$COMPOSE_FILE" "${build[@]}"

log "docker compose -f $COMPOSE_FILE up -d --pull never"
docker compose -f "$COMPOSE_FILE" up -d --pull never

ok=0
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 15; do
  if curl -fsS -o /dev/null --max-time 2 "http://127.0.0.1:${PANEL_PORT:-8093}/healthz" 2>/dev/null; then
    ok=1
    break
  fi
  sleep 1
done

if [[ "$ok" -eq 1 ]]; then
  log "panel ready → http://127.0.0.1:${PANEL_PORT:-8093}/"
else
  log "compose up finished; healthz not ready — docker compose -f $COMPOSE_FILE ps"
fi
