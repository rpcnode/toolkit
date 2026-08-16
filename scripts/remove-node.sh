#!/usr/bin/env bash
# Panel-flow Remove node: POST /api/workloads/remove → tip agent (never SSH).
#
# Usage (from toolkit root):
#   ./scripts/remove-node.sh --id 453ab902-…
#   ./scripts/remove-node.sh --server bitcoin-1 --network hyperliquid --env mainnet --delete-files
# Ad-hoc API inspect (no browser): TOKEN=$(./scripts/panel-token.sh) then curl Bearer.
set -euo pipefail

PANEL_URL="${PANEL_URL:-http://127.0.0.1:8093}"
PANEL_USER="${PANEL_USER:-admin}"
PANEL_PASS="${PANEL_PASS:-}"
NODE_ID=""
SERVER=""
NETWORK=""
ENV_NAME=""
DELETE_FILES=1
MODE=""
FORCE=0
COOKIE_JAR="${COOKIE_JAR:-}"

usage() {
  cat <<'EOF'
panel-remove-node — Remove a node via panel → tip agent API only.

Usage:
  remove-node.sh --id <uuid>
  remove-node.sh --server <id|name> --network <slug> --env <env>

Options:
  --panel-url URL
  --mode wipe|agents|panel        Default: wipe (full host + files)
  --delete-files / --keep-files   Legacy aliases for wipe / agents
  --force                         Drop panel row + best-effort tip wipe
  --help

❌ Does not SSH or systemctl on the node host.
EOF
}

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
log() { printf '+ %s\n' "$*"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --id) NODE_ID="${2:-}"; shift 2 ;;
    --server) SERVER="${2:-}"; shift 2 ;;
    --network) NETWORK="${2:-}"; shift 2 ;;
    --env) ENV_NAME="${2:-}"; shift 2 ;;
    --panel-url) PANEL_URL="${2:-}"; shift 2 ;;
    --mode) MODE="${2:-}"; shift 2 ;;
    --delete-files) DELETE_FILES=1; MODE=wipe; shift ;;
    --keep-files) DELETE_FILES=0; MODE=agents; shift ;;
    --force) FORCE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown arg: $1" ;;
  esac
done

PANEL_URL="${PANEL_URL%/}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLKIT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$TOOLKIT_DIR/../../../.." && pwd 2>/dev/null || true)"
if [[ -z "$PANEL_PASS" ]]; then
  for f in \
    "${REPO_ROOT}/.cursor/secrets/rpcnode-panel-pass.env" \
    "/tmp/rpcnode-panel-pass.env"
  do
    [[ -f "$f" ]] || continue
    set -a; # shellcheck disable=SC1090
    source "$f"; set +a
    break
  done
fi
[[ -n "$PANEL_PASS" ]] || die "PANEL_PASS not set"

COOKIE_JAR="${COOKIE_JAR:-$(mktemp -t panel-rm.XXXXXX)}"
trap 'rm -f "$COOKIE_JAR"' EXIT

api() {
  local method="$1" path="$2"; shift 2
  curl -sS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X "$method" \
    -H 'Content-Type: application/json' "$@" "${PANEL_URL}${path}"
}

log "login…"
login_body="$(PANEL_USER="$PANEL_USER" PANEL_PASS="$PANEL_PASS" python3 - <<'PY'
import json, os
print(json.dumps({"username": os.environ["PANEL_USER"], "password": os.environ["PANEL_PASS"]}))
PY
)"
api POST /api/auth/login -d "$login_body" | python3 -c 'import sys,json;d=json.load(sys.stdin); assert d.get("ok") or d.get("authenticated"), d' \
  || die "login failed"

if [[ -z "$NODE_ID" ]]; then
  [[ -n "$SERVER" && -n "$NETWORK" && -n "$ENV_NAME" ]] || die "need --id or --server/--network/--env"
  servers=$(api GET /api/nodes)
  SERVER_ID="$(echo "$servers" | python3 -c '
import sys,json
want=sys.argv[1].lower()
items=(json.load(sys.stdin).get("items") or [])
for s in items:
  if str(s.get("id","")).lower()==want or str(s.get("name","")).lower()==want:
    print(s["id"]); raise SystemExit
raise SystemExit(1)
' "$SERVER")" || die "server not found"
  workloads=$(api GET /api/workloads)
  NODE_ID="$(echo "$workloads" | python3 -c '
import sys,json
sid,net,env=sys.argv[1:4]
for w in json.load(sys.stdin).get("items") or []:
  if w.get("server_id")==sid and str(w.get("network","")).lower()==net.lower() and str(w.get("env"))==env:
    print(w["id"]); raise SystemExit
print("")
' "$SERVER_ID" "$NETWORK" "$ENV_NAME")"
  [[ -n "$NODE_ID" ]] || { log "no panel node for $NETWORK/$ENV_NAME on $SERVER — nothing to remove"; exit 0; }
fi

[[ -n "$MODE" ]] || MODE=wipe
log "remove id=$NODE_ID mode=$MODE delete_files=$DELETE_FILES"
body="$(python3 -c "import json;print(json.dumps({'id':'''$NODE_ID''','mode':'''$MODE''','delete_files':bool($DELETE_FILES),'force':bool($FORCE)}))")"
res=$(api POST /api/workloads/remove -d "$body")
echo "$res" | python3 -m json.tool
echo "$res" | python3 -c 'import sys,json;d=json.load(sys.stdin); assert d.get("ok"), d' \
  || die "remove failed"
log "removed"
