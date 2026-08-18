#!/usr/bin/env bash
# Rebuild rpcnode-client-watch from this tree, install the systemd unit, restart, verify /healthz.
# Run on the CDN/watch host (from this directory):
#   ./update.sh
# `go build` alone does not replace /usr/local/bin — this script does.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
cd "$here"

log() { printf '+ %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

INSTALL_ONLY=0
case "${1:-}" in
  --install-only) INSTALL_ONLY=1 ;;
  -h|--help)
    sed -n '2,8p' "$0"
    exit 0
    ;;
  "") ;;
  *)
    die "unknown flag: $1"
    ;;
esac

find_go() {
  if command -v go >/dev/null 2>&1; then
    command -v go
    return 0
  fi
  local c
  for c in /usr/local/go/bin/go /usr/lib/go/bin/go /usr/lib/go-1.22/bin/go /usr/lib/go-1.23/bin/go /snap/bin/go; do
    if [[ -x $c ]]; then
      printf '%s\n' "$c"
      return 0
    fi
  done
  return 1
}

if [[ $INSTALL_ONLY -eq 0 ]]; then
  GO=$(find_go) || die "нет go в PATH (нужен 1.22+). Поставь Go или позови скрипт без голого sudo, чтобы сохранился PATH."
  log "go $($GO version | awk '{print $3}')"
  log "build $here"
  CGO_ENABLED=0 "$GO" build -trimpath -ldflags='-s -w' -o rpcnode-client-watch .
  chmod +x rpcnode-client-watch
fi

[[ -x $here/rpcnode-client-watch ]] || die "нет $here/rpcnode-client-watch"

built=$("$here/rpcnode-client-watch" -version)
log "$built"
built_ver=${built#*version=}
built_ver=${built_ver%% *}
built_api=${built##*api=}
[[ -n $built_ver && -n $built_api ]] || die "бинарник не печатает version/api — это старый исходник без version.go"

if [[ ${EUID:-0} -ne 0 ]]; then
  log "нужен root для /usr/local/bin + systemd"
  exec sudo --preserve-env=PATH "$here/update.sh" --install-only
fi

"$here/install-systemd.sh"

healthz_match() {
  local raw=$1
  [[ -n $raw ]] || return 1
  if command -v python3 >/dev/null 2>&1; then
    printf '%s' "$raw" | python3 -c '
import json, sys
want_ver, want_api = sys.argv[1], int(sys.argv[2])
h = json.loads(sys.stdin.read())
got_ver = str(h.get("version") or "")
got_api = int(h.get("api") or 0)
sys.exit(0 if got_ver == want_ver and got_api == want_api else 1)
' "$built_ver" "$built_api"
  fi
  [[ $raw == *"\"version\":\"$built_ver\""* && $raw == *"\"api\":$built_api"* ]]
}

log "wait healthz api=$built_api version=$built_ver"
ok=0
body=""
for _ in $(seq 1 25); do
  body=$(curl -fsS --max-time 2 http://127.0.0.1:8094/healthz 2>/dev/null || true)
  if healthz_match "$body"; then
    ok=1
    break
  fi
  sleep 0.4
done

if [[ $ok -ne 1 ]]; then
  echo "healthz: ${body:-пусто}" >&2
  journalctl -u rpcnode-client-watch -n 40 --no-pager >&2 || true
  die "юнит не отдаёт version=$built_ver api=$built_api. На :8094 ещё старый процесс, либо порт не тот."
fi

echo "ok  $body"
echo "юнит $(systemctl is-active rpcnode-client-watch)  /usr/local/bin/rpcnode-client-watch"
echo "лог  journalctl -u rpcnode-client-watch -f"
