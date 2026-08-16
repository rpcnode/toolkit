#!/usr/bin/env bash
# Rebuild local RpcNode panel after status-ui / panel changes.
#
# UI is compiled inside Docker (Dockerfile `ui` stage → go:embed).
# panel/ui is not committed and is not mounted into the container.
#
# Usage:
#   ./scripts/rebuild-panel.sh
#   ./scripts/rebuild-panel.sh --no-cache
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.panel.yml}"
NO_CACHE=0
OPEN=0

log() { printf '+ %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

for arg in "$@"; do
  case "$arg" in
    --no-cache) NO_CACHE=1 ;;
    --open) OPEN=1 ;;
    --ui-only|--skip-ui)
      die "UI is compiled in Docker now (not copied to panel/ui). Use: $0  or  docker compose -f docker-compose.panel.yml up -d --build --pull never"
      ;;
    -h|--help)
      sed -n '2,12p' "$0"
      exit 0
      ;;
    *) die "unknown arg: $arg (try --help)" ;;
  esac
done

cd "$ROOT"
[[ -f "$ROOT/$COMPOSE_FILE" ]] || die "missing $COMPOSE_FILE"
[[ -f "$ROOT/Dockerfile" ]] || die "missing Dockerfile"
command -v docker >/dev/null 2>&1 || die "docker not found"
mkdir -p "$ROOT/config/nginx/htpasswd"
touch "$ROOT/config/nginx/htpasswd/panel.htpasswd"

if [[ "$NO_CACHE" -eq 1 ]]; then
  log "docker compose build --no-cache --pull=false"
  docker compose -f "$COMPOSE_FILE" build --no-cache --pull=false
  log "docker compose -f $COMPOSE_FILE up -d --pull never"
  docker compose -f "$COMPOSE_FILE" up -d --pull never
else
  log "docker compose -f $COMPOSE_FILE up -d --build --pull never"
  docker compose -f "$COMPOSE_FILE" up -d --build --pull never
fi

ok=0
for _ in 1 2 3 4 5 6 7 8 9 10; do
  if curl -fsS -o /dev/null --max-time 2 "http://127.0.0.1:${PANEL_PORT:-8093}/healthz" 2>/dev/null; then
    ok=1
    break
  fi
  sleep 1
done

if [[ "$ok" -eq 1 ]]; then
  log "panel ready → http://127.0.0.1:${PANEL_PORT:-8093}/ (healthz ok)"
else
  log "compose up finished; healthz not ready yet — check: docker compose -f $COMPOSE_FILE ps"
fi

if [[ "$OPEN" -eq 1 ]]; then
  if command -v open >/dev/null 2>&1; then
    open "http://127.0.0.1:${PANEL_PORT:-8093}/" || true
  fi
fi
