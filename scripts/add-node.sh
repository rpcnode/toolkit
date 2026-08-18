#!/usr/bin/env bash
# Panel-flow Add node: registry (no host install) → plan → provision (Confirm ports) → start.
# Uses ONLY panel HTTP APIs (panel → tip agent). Never SSH / systemctl on the node host.
#
# Usage (from toolkit root):
#   ./scripts/add-node.sh --server bitcoin-1 --network hyperliquid --env testnet
#   PANEL_PASS=... ./scripts/add-node.sh --server bitcoin-1 --network doge --env mainnet --name doge-main
#
# Auth: PANEL_USER / PANEL_PASS, or file PANEL_PASS_FILE (default:
#   $REPO/.cursor/secrets/rpcnode-panel-pass.env or /tmp/rpcnode-panel-pass.env)
# For ad-hoc curl inspect (Bearer, 30d session): ./scripts/panel-token.sh
set -euo pipefail

PANEL_URL="${PANEL_URL:-http://127.0.0.1:8093}"
PANEL_USER="${PANEL_USER:-admin}"
PANEL_PASS="${PANEL_PASS:-}"
PANEL_PASS_FILE="${PANEL_PASS_FILE:-}"
SERVER=""
NETWORK=""
ENV_NAME=""
DISPLAY_NAME=""
START=1
TIMEOUT_SEC="${TIMEOUT_SEC:-180}"
SNAPSHOT=""
COOKIE_JAR="${COOKIE_JAR:-}"

usage() {
  cat <<'EOF'
panel-add-node — Add / resume a chain node via RpcNode panel APIs only.

Usage:
  add-node.sh --server <id|name> --network <slug> --env <env> [options]

Required:
  --server ID|NAME     Panel server (e.g. bitcoin-1)
  --network SLUG       Network catalog slug (hyperliquid, doge, cardano, …)
  --env ENV            Environment (mainnet, testnet, preprod, …)

Options:
  --panel-url URL      Default http://127.0.0.1:8093
  --name NAME          Display name (default: <network>-<env>)
  --no-start           Stop after provision (Confirm ports); skip start ACK
  --timeout SEC        Lifecycle poll timeout (default 180)
  --snapshot FLAVOR    TRON mainnet: standard | internal_tx | balance_history
  --help               This help

Environment:
  PANEL_URL, PANEL_USER, PANEL_PASS, PANEL_PASS_FILE, COOKIE_JAR

Flow (must match rpcnode-multi-chain-agent.mdc):
  1) Login panel
  2) Resolve server
  3) If node exists for server+network+env → resume (no second card)
  4) POST /api/workloads                 (panel row only — awaiting_ports)
  5) POST /api/workloads/plan            (tip nodes/plan — free ports)
  6) POST /api/workloads/provision       (Confirm ports — host units/binaries)
  7) Poll /api/status.json until ports|install ACK
  8) POST /api/workloads/start           (tip start) + poll start|run ACK

❌ Does not SSH to the node host or run systemctl.
❌ Add/register must not call provision (host install only after Confirm ports).
EOF
}

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
log() { printf '+ %s\n' "$*"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --server) SERVER="${2:-}"; shift 2 ;;
    --network) NETWORK="${2:-}"; shift 2 ;;
    --env) ENV_NAME="${2:-}"; shift 2 ;;
    --name) DISPLAY_NAME="${2:-}"; shift 2 ;;
    --panel-url) PANEL_URL="${2:-}"; shift 2 ;;
    --no-start) START=0; shift ;;
    --timeout) TIMEOUT_SEC="${2:-}"; shift 2 ;;
    --snapshot) SNAPSHOT="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown arg: $1 (see --help)" ;;
  esac
done

[[ -n "$SERVER" && -n "$NETWORK" && -n "$ENV_NAME" ]] || {
  usage >&2
  die "--server, --network, --env are required"
}

DISPLAY_NAME="${DISPLAY_NAME:-${NETWORK}-${ENV_NAME}}"
PANEL_URL="${PANEL_URL%/}"

# Resolve password file
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLKIT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$TOOLKIT_DIR/../../../.." && pwd 2>/dev/null || true)"
if [[ -z "$PANEL_PASS" ]]; then
  for f in \
    "$PANEL_PASS_FILE" \
    "${REPO_ROOT}/.cursor/secrets/rpcnode-panel-pass.env" \
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
[[ -n "$PANEL_PASS" ]] || die "PANEL_PASS not set (env or rpcnode-panel-pass.env)"

if [[ -z "$COOKIE_JAR" ]]; then
  COOKIE_JAR="$(mktemp -t panel-add-node.XXXXXX)"
  trap 'rm -f "$COOKIE_JAR"' EXIT
fi

api() {
  local method="$1" path="$2"
  shift 2
  curl -sS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X "$method" \
    -H 'Content-Type: application/json' \
    "$@" \
    "${PANEL_URL}${path}"
}

json_get() {
  python3 -c 'import sys,json; d=json.load(sys.stdin); print(d'"$1"')' 2>/dev/null
}

log "panel=$PANEL_URL server=$SERVER network=$NETWORK env=$ENV_NAME"

# 1) Login
login_body="$(PANEL_USER="$PANEL_USER" PANEL_PASS="$PANEL_PASS" python3 - <<'PY'
import json, os
print(json.dumps({"username": os.environ["PANEL_USER"], "password": os.environ["PANEL_PASS"]}))
PY
)"
login=$(api POST /api/auth/login -d "$login_body")
echo "$login" | python3 -c 'import sys,json; d=json.load(sys.stdin); assert d.get("ok") or d.get("authenticated"), d' \
  || die "panel login failed: $login"
log "login ok (user=$PANEL_USER)"

# 2) Resolve server id
servers=$(api GET /api/nodes)
# registry is /api/nodes for servers in this panel
SERVER_ID="$(echo "$servers" | python3 -c '
import sys,json
want=sys.argv[1].strip().lower()
d=json.load(sys.stdin)
items=d.get("items") or d.get("nodes") or (d if isinstance(d,list) else [])
for s in items:
  sid=str(s.get("id") or "")
  name=str(s.get("name") or "")
  if sid.lower()==want or name.lower()==want:
    print(sid); raise SystemExit
raise SystemExit("NOT_FOUND")
' "$SERVER")" || die "server not found: $SERVER"
log "server_id=$SERVER_ID"

# 3) Existing workload?
workloads=$(api GET /api/workloads)
EXISTING_JSON="$(echo "$workloads" | python3 -c '
import sys,json
sid,net,env=sys.argv[1:4]
d=json.load(sys.stdin)
for w in d.get("items") or []:
  if w.get("server_id")==sid and str(w.get("network","")).lower()==net.lower() and str(w.get("env",""))==env:
    import json as J; print(J.dumps(w)); raise SystemExit
print("")
' "$SERVER_ID" "$NETWORK" "$ENV_NAME")"

UUID=""
if [[ -n "$EXISTING_JSON" ]]; then
  UUID="$(echo "$EXISTING_JSON" | json_get '["id"]')"
  log "resume existing node id=$UUID (no second card)"
else
  # 4) Register + tip catalog ports (no host install yet)
  log "register panel node (tip plan → ready_to_install)…"
  reg=$(api POST /api/workloads -d "$(python3 -c "
import json
print(json.dumps({
  'server_id': '''$SERVER_ID''',
  'network': '''$NETWORK''',
  'env': '''$ENV_NAME''',
  'name': '''$DISPLAY_NAME''',
}))
")")
  echo "$reg" | python3 -c 'import sys,json; d=json.load(sys.stdin); assert d.get("ok") and d.get("item"), d' \
    || die "register failed: $reg"
  UUID="$(echo "$reg" | json_get '["item"]["id"]')"
  log "registered id=$UUID status=$(echo "$reg" | json_get '["item"].get("status")')"
fi

# 5) Catalog ports from row (saved at Add) or tip plan
wl=$(api GET "/api/workloads/${UUID}")
PUB="$(echo "$wl" | python3 -c 'import sys,json;d=json.load(sys.stdin);i=d.get("item") or d;print(i.get("public_port") or 0)')"
AGENT="$(echo "$wl" | python3 -c 'import sys,json;d=json.load(sys.stdin);i=d.get("item") or d;print(i.get("agent_port") or 0)')"
NHTTP="$(echo "$wl" | python3 -c 'import sys,json;d=json.load(sys.stdin);i=d.get("item") or d;print(i.get("node_http_port") or 0)')"
P2P="$(echo "$wl" | python3 -c 'import sys,json;d=json.load(sys.stdin);i=d.get("item") or d;print(i.get("p2p_port") or 0)')"
if [[ "${PUB:-0}" -le 0 || "${AGENT:-0}" -le 0 ]]; then
  log "plan (tip catalog)…"
  plan=$(api POST /api/workloads/plan -d "$(python3 -c "import json;print(json.dumps({'server_id':'''$SERVER_ID''','network':'''$NETWORK''','env':'''$ENV_NAME'''}))")")
  echo "$plan" | python3 -c 'import sys,json; d=json.load(sys.stdin); assert d.get("ok"), d' \
    || die "plan failed: $plan"
  PUB="$(echo "$plan" | json_get '.get("public_port")')"
  AGENT="$(echo "$plan" | json_get '.get("agent_port")')"
  NHTTP="$(echo "$plan" | json_get '.get("node_http_port")')"
  P2P="$(echo "$plan" | json_get '.get("p2p_port")')"
fi
log "catalog ports public=$PUB agent=$AGENT node_http=$NHTTP p2p=$P2P"

log "check-ports…"
chk=$(api POST /api/workloads/check-ports -d "$(python3 -c "import json;print(json.dumps({'server_id':'''$SERVER_ID''','network':'''$NETWORK''','env':'''$ENV_NAME'''}))")")
echo "$chk" | python3 -c 'import sys,json; d=json.load(sys.stdin); assert d.get("ok") and d.get("ports_free") is not False, d' \
  || die "ports busy: $chk"

log "provision (Install)…"
prov=$(api POST /api/workloads/provision -d "$(python3 -c "
import json
body={
  'server_id': '''$SERVER_ID''',
  'network': '''$NETWORK''',
  'env': '''$ENV_NAME''',
  'name': '''$DISPLAY_NAME''',
  'public_port': int('''$PUB''' or 0),
  'agent_port': int('''$AGENT''' or 0),
  'node_http_port': int('''$NHTTP''' or 0),
  'p2p_port': int('''$P2P''' or 0),
}
snap='''$SNAPSHOT'''
if snap:
  body['install_options']={'snapshot': snap}
print(json.dumps(body))
")")
echo "$prov" | python3 -c 'import sys,json; d=json.load(sys.stdin); assert d.get("ok") and d.get("item"), d' \
  || die "provision failed: $prov"
UUID="$(echo "$prov" | json_get '["item"]["id"]')"
log "provisioned id=$UUID"

# 6) Poll lifecycle until ports done or install/start/run
log "wait lifecycle ports/install ACK…"
deadline=$(( $(date +%s) + TIMEOUT_SEC ))
while [[ $(date +%s) -lt $deadline ]]; do
  st=$(api GET "/api/status.json?node=${UUID}&network=${NETWORK}&env=${ENV_NAME}" || true)
  ok="$(echo "$st" | python3 -c '
import sys,json
try: d=json.load(sys.stdin)
except Exception: print("bad"); raise SystemExit
lc=d.get("lifecycle") or {}
steps={s.get("id"): s for s in (lc.get("steps") or [])}
ports=steps.get("ports") or {}
cur=(lc.get("current") or "")
ports_done = ports.get("status")=="done" or ports.get("done") or bool(ports.get("finished_at"))
advanced = cur in ("install","snapshot","start","ibd","run","healthy")
print("ok" if (d.get("ok") and (ports_done or advanced)) else "wait")
print("current="+cur, file=sys.stderr)
' 2>/tmp/panel-add-node.lc || echo wait)"
  log "lifecycle $(cat /tmp/panel-add-node.lc 2>/dev/null || true)"
  if [[ "$ok" == "ok" ]]; then
    break
  fi
  sleep 3
done

api POST /api/workloads/status -d "$(python3 -c "import json;print(json.dumps({'id':'''$UUID''','status':'installing'}))")" >/dev/null || true

if [[ "$START" -eq 1 ]]; then
  log "start (panel → tip)…"
  start=$(api POST /api/workloads/start -d "$(python3 -c "import json;print(json.dumps({'workload_id':'''$UUID''','server_id':'''$SERVER_ID''','env':'''$ENV_NAME'''}))")")
  echo "$start" | python3 -c 'import sys,json; d=json.load(sys.stdin); assert d.get("ok"), d' \
    || die "start failed: $start"
  log "start ACK ok — waiting lifecycle start/run…"
  deadline=$(( $(date +%s) + TIMEOUT_SEC ))
  while [[ $(date +%s) -lt $deadline ]]; do
    st=$(api GET "/api/status.json?node=${UUID}&network=${NETWORK}&env=${ENV_NAME}" || true)
    done_flag="$(echo "$st" | python3 -c '
import sys,json
try: d=json.load(sys.stdin)
except Exception: print("wait"); raise SystemExit
lc=d.get("lifecycle") or {}
steps={s.get("id"): s for s in (lc.get("steps") or [])}
cur=(lc.get("current") or "")
stt=(steps.get("start") or {}).get("status")
print("ok" if (cur in ("start","run","ibd","healthy") or stt in ("active","done") or d.get("node_up")) else "wait")
print("current="+cur+" start="+str(stt)+" node_up="+str(d.get("node_up"))+" detail="+(lc.get("detail") or "")[:60], file=sys.stderr)
' 2>/tmp/panel-add-node.lc || echo wait)"
    log "lifecycle $(cat /tmp/panel-add-node.lc 2>/dev/null || true)"
    if [[ "$done_flag" == "ok" ]]; then
      break
    fi
    sleep 4
  done
  api POST /api/workloads/status -d "$(python3 -c "import json;print(json.dumps({'id':'''$UUID''','status':'starting'}))")" >/dev/null || true
fi

# Final summary
final=$(api GET "/api/workloads/${UUID}")
echo "$final" | python3 -c '
import sys,json
d=json.load(sys.stdin)
i=d.get("item") or d
print("---")
print("ok=true")
print("id="+str(i.get("id")))
print("server_id="+str(i.get("server_id")))
print("network="+str(i.get("network")))
print("env="+str(i.get("env")))
print("status="+str(i.get("status")))
print("agent_url="+str(i.get("agent_url") or ""))
print("public_port="+str(i.get("public_port") or ""))
print("panel_node=http://127.0.0.1:8093/nodes/"+str(i.get("id")))
'
log "done"
