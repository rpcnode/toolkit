#!/usr/bin/env bash
# Print a fresh panel session token (Bearer) to stdout — API-first ops, no browser.
#
# Usage (from toolkit root):
#   TOKEN=$(./scripts/panel-token.sh)
#   curl -sS -H "Authorization: Bearer $TOKEN" \
#     "http://127.0.0.1:8093/api/status.json?node=<NODE_UUID>" | jq .
#
# Also useful:
#   curl -sS -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8093/api/workloads | jq .
#   curl -sS -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8093/api/nodes | jq .
#
# Auth env (never commit secrets):
#   PANEL_URL          default http://127.0.0.1:8093
#   PANEL_USER         default admin
#   PANEL_PASS         required (or from pass file)
#   PANEL_PASS_FILE    optional path to env file with PANEL_USER/PANEL_PASS
#   Secrets template:  copy placeholders from .env.example; local file e.g.
#     .cursor/secrets/rpcnode-panel-pass.env  or  /tmp/rpcnode-panel-pass.env
set -euo pipefail

PANEL_URL="${PANEL_URL:-http://127.0.0.1:8093}"
PANEL_USER="${PANEL_USER:-admin}"
PANEL_PASS="${PANEL_PASS:-}"
PANEL_PASS_FILE="${PANEL_PASS_FILE:-}"

usage() {
  cat <<'EOF'
panel-token — login to RpcNode panel and print session token (30d TTL).

Usage:
  ./scripts/panel-token.sh
  PANEL_PASS=… ./scripts/panel-token.sh
  ./scripts/panel-token.sh --panel-url http://127.0.0.1:8093

Stdout: raw token only (suitable for TOKEN=$(…)).
Stderr: progress / errors.

Example:
  TOKEN=$(./scripts/panel-token.sh)
  curl -sS -H "Authorization: Bearer $TOKEN" \
    "http://127.0.0.1:8093/api/status.json?node=<NODE_UUID>" | jq .

Env: PANEL_URL, PANEL_USER, PANEL_PASS, PANEL_PASS_FILE
EOF
}

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
log() { printf '+ %s\n' "$*" >&2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --panel-url) PANEL_URL="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown arg: $1 (see --help)" ;;
  esac
done

PANEL_URL="${PANEL_URL%/}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLKIT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$TOOLKIT_DIR/../../../.." && pwd 2>/dev/null || true)"

if [[ -z "$PANEL_PASS" ]]; then
  for f in \
    "$PANEL_PASS_FILE" \
    "${REPO_ROOT}/.cursor/secrets/rpcnode-panel-pass.env" \
    "${TOOLKIT_DIR}/.cursor/secrets/rpcnode-panel-pass.env" \
    "/tmp/rpcnode-panel-pass.env"
  do
    [[ -n "$f" && -f "$f" ]] || continue
    # shellcheck disable=SC1090
    set -a; source "$f"; set +a
    PANEL_PASS="${PANEL_PASS:-}"
    PANEL_USER="${PANEL_USER:-admin}"
    break
  done
fi
[[ -n "$PANEL_PASS" ]] || die "PANEL_PASS not set (env or rpcnode-panel-pass.env — see .env.example)"

login_body="$(PANEL_USER="$PANEL_USER" PANEL_PASS="$PANEL_PASS" python3 - <<'PY'
import json, os
print(json.dumps({"username": os.environ["PANEL_USER"], "password": os.environ["PANEL_PASS"]}))
PY
)"

resp="$(curl -sS -X POST "${PANEL_URL}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$login_body")" || die "login request failed (panel up at ${PANEL_URL}?)"

token="$(printf '%s' "$resp" | python3 -c '
import sys, json
d = json.load(sys.stdin)
tok = (d.get("token") or "").strip()
if not tok or not (d.get("ok") or d.get("authenticated")):
    raise SystemExit("LOGIN_FAILED")
print(tok)
' 2>/dev/null)" || die "panel login failed: $resp"

log "login ok user=${PANEL_USER} expires=$(printf '%s' "$resp" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("expires_at",""))' 2>/dev/null || true)"
printf '%s\n' "$token"
