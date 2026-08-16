#!/usr/bin/env bash
# Load-test a panel node's public Go RPC fullnode endpoint.
# Resolves host:public_port from panel.db (same source as check-fullnodes.sh).
# Watch metrics on the node page → Fullnode Go RPC (RPS / p95 / errors).
#
# Usage (from toolkit root):
#   ./scripts/loadtest-fullnode.sh --node 66407d81-1013-4dc9-aeeb-eb74ee6b8d60
#   ./scripts/loadtest-fullnode.sh --network tron --env mainnet
#   ./scripts/loadtest-fullnode.sh --node <uuid> --concurrency 32 --duration 60
#   ./scripts/loadtest-fullnode.sh --url http://140.150.232.20:39090 --network tron
#
# Ctrl+C to stop early. Exit 0 always after summary (non-zero only on resolve/probe fail).
set -euo pipefail

CONTAINER="${PANEL_CONTAINER:-rpcnode-panel}"
DB_PATH=""
NODE_ID=""
NETWORK=""
ENV_NAME=""
URL_OVERRIDE=""
CONCURRENCY="${CONCURRENCY:-16}"
DURATION_SEC="${DURATION_SEC:-60}"
TIMEOUT_SEC="${TIMEOUT_SEC:-8}"
METHOD="" # empty = network default

usage() {
  cat <<'EOF'
loadtest-fullnode — hammer public Go RPC (fullnode proxy) for panel metrics.

Resolve target (one of):
  --node UUID              Panel node id
  --network SLUG --env ENV Panel node by network+env (unique match)
  --url URL                Skip DB; still pass --network for request shape

Options:
  --concurrency N          Parallel workers (default 16)
  --duration SEC           Run length (default 60)
  --timeout SEC            Per-request timeout (default 8)
  --method NAME            Override: getnowblock | eth_blockNumber | getblockcount | getHealth | getSlot | server_info | health
  --container NAME         Docker panel container (default rpcnode-panel)
  --db PATH                Local panel.db instead of docker exec
  --help

Examples:
  ./scripts/loadtest-fullnode.sh --node 66407d81-1013-4dc9-aeeb-eb74ee6b8d60
  ./scripts/loadtest-fullnode.sh --network tron --env mainnet -c 32 -d 120
  Open panel node page and watch Fullnode Go RPC RPS / p95 while it runs.
EOF
}

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
log() { printf '+ %s\n' "$*"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --node) NODE_ID="${2:-}"; shift 2 ;;
    --network) NETWORK="${2:-}"; shift 2 ;;
    --env) ENV_NAME="${2:-}"; shift 2 ;;
    --url) URL_OVERRIDE="${2:-}"; shift 2 ;;
    --concurrency|-c) CONCURRENCY="${2:-}"; shift 2 ;;
    --duration|-d) DURATION_SEC="${2:-}"; shift 2 ;;
    --timeout) TIMEOUT_SEC="${2:-}"; shift 2 ;;
    --method) METHOD="${2:-}"; shift 2 ;;
    --container) CONTAINER="${2:-}"; shift 2 ;;
    --db) DB_PATH="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown arg: $1 (see --help)" ;;
  esac
done

command -v python3 >/dev/null 2>&1 || die "python3 required"

resolve_sql() {
  if [[ -n "$NODE_ID" ]]; then
    cat <<SQL
SELECT n.id, n.name, n.network, n.env, n.public_port,
       coalesce(nullif(n.agent_url,''), s.agent_url) AS agent_url
FROM nodes n
LEFT JOIN servers s ON s.id = n.server_id
WHERE n.id = '${NODE_ID//\'/\'\'}'
LIMIT 1;
SQL
  elif [[ -n "$NETWORK" && -n "$ENV_NAME" ]]; then
    cat <<SQL
SELECT n.id, n.name, n.network, n.env, n.public_port,
       coalesce(nullif(n.agent_url,''), s.agent_url) AS agent_url
FROM nodes n
LEFT JOIN servers s ON s.id = n.server_id
WHERE lower(n.network) = lower('${NETWORK//\'/\'\'}')
  AND lower(n.env) = lower('${ENV_NAME//\'/\'\'}')
  AND n.public_port > 0
ORDER BY n.updated_at DESC
LIMIT 2;
SQL
  else
    return 1
  fi
}

fetch_rows() {
  local sql="$1"
  if [[ -n "$DB_PATH" ]]; then
    [[ -f "$DB_PATH" ]] || die "db not found: $DB_PATH"
    if command -v sqlite3 >/dev/null 2>&1; then
      sqlite3 -separator $'\t' "$DB_PATH" "$sql"
    else
      python3 - "$DB_PATH" "$sql" <<'PY'
import sqlite3, sys
db, sql = sys.argv[1], sys.argv[2]
con = sqlite3.connect(db)
for row in con.execute(sql):
    print("\t".join("" if v is None else str(v) for v in row))
PY
    fi
  else
    command -v docker >/dev/null 2>&1 || die "docker not found (use --db PATH or --url)"
    docker exec "$CONTAINER" sqlite3 -separator $'\t' /var/lib/rpcnode/panel.db "$sql" \
      || die "sqlite query failed (container=$CONTAINER)"
  fi
}

ENDPOINT=""
RESOLVED_NETWORK="${NETWORK}"
RESOLVED_ENV="${ENV_NAME}"
RESOLVED_ID="${NODE_ID}"
RESOLVED_NAME=""

if [[ -n "$URL_OVERRIDE" ]]; then
  ENDPOINT="${URL_OVERRIDE%/}"
  [[ -n "$RESOLVED_NETWORK" ]] || die "--url requires --network (request shape)"
else
  SQL="$(resolve_sql)" || {
    usage >&2
    die "need --node UUID, or --network + --env, or --url + --network"
  }
  ROWS="$(fetch_rows "$SQL" || true)"
  [[ -n "${ROWS//[$'\t\n\r']/}" ]] || die "node not found in panel.db"
  mapfile -t LINES <<<"$ROWS"
  if [[ ${#LINES[@]} -gt 1 && -z "$NODE_ID" ]]; then
    die "multiple nodes for ${NETWORK}/${ENV_NAME}; use --node <uuid>"
  fi
  IFS=$'\t' read -r RESOLVED_ID RESOLVED_NAME RESOLVED_NETWORK RESOLVED_ENV PUB AGENT_URL <<<"${LINES[0]}"
  PUB_INT=0
  PUB_INT=$((PUB + 0)) || true
  [[ "$PUB_INT" -gt 0 ]] || die "node has no public_port (Go RPC)"
  HOST="$(python3 -c '
from urllib.parse import urlparse
import sys
a = sys.argv[1]
u = urlparse(a if "://" in a else "http://" + a)
print(u.hostname or "")
' "$AGENT_URL")"
  [[ -n "$HOST" ]] || die "cannot parse host from agent_url=$AGENT_URL"
  SCHEME="$(python3 -c '
from urllib.parse import urlparse
import sys
a = sys.argv[1]
u = urlparse(a if "://" in a else "http://" + a)
print(u.scheme or "http")
' "$AGENT_URL")"
  ENDPOINT="${SCHEME}://${HOST}:${PUB_INT}"
fi

log "target node=${RESOLVED_ID:-?} name=${RESOLVED_NAME:-—} ${RESOLVED_NETWORK}/${RESOLVED_ENV}"
log "fullnode Go RPC ${ENDPOINT}"
log "workers=${CONCURRENCY} duration=${DURATION_SEC}s timeout=${TIMEOUT_SEC}s"
log "open panel → /nodes/${RESOLVED_ID:-…} → Fullnode Go RPC"
echo

export ENDPOINT RESOLVED_NETWORK METHOD CONCURRENCY DURATION_SEC TIMEOUT_SEC
python3 - <<'PY'
from __future__ import annotations

import json
import os
import signal
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ENDPOINT = os.environ["ENDPOINT"].rstrip("/")
NETWORK = (os.environ.get("RESOLVED_NETWORK") or "").strip().lower()
METHOD = (os.environ.get("METHOD") or "").strip()
CONCURRENCY = max(1, int(os.environ.get("CONCURRENCY") or "16"))
DURATION = max(1, int(os.environ.get("DURATION_SEC") or "60"))
TIMEOUT = float(os.environ.get("TIMEOUT_SEC") or "8")

stop = threading.Event()
ok_n = 0
fail_n = 0
lat_sum = 0.0
lock = threading.Lock()
started = time.time()


def on_sig(_sig, _frame):
    stop.set()


signal.signal(signal.SIGINT, on_sig)
signal.signal(signal.SIGTERM, on_sig)


def build_request() -> urllib.request.Request:
    net = NETWORK
    method = METHOD.lower() if METHOD else ""

    def post(url: str, payload: dict) -> urllib.request.Request:
        data = json.dumps(payload).encode()
        return urllib.request.Request(
            url,
            data=data,
            method="POST",
            headers={"Content-Type": "application/json", "Accept": "application/json", "User-Agent": "rpcnode-loadtest/1"},
        )

    def get(url: str) -> urllib.request.Request:
        return urllib.request.Request(
            url,
            method="GET",
            headers={"Accept": "application/json", "User-Agent": "rpcnode-loadtest/1"},
        )

    # Explicit method override
    if method in ("getnowblock", "wallet/getnowblock"):
        return get(ENDPOINT + "/wallet/getnowblock")
    if method == "eth_blocknumber":
        return post(ENDPOINT, {"jsonrpc": "2.0", "id": 1, "method": "eth_blockNumber", "params": []})
    if method == "getblockcount":
        return post(ENDPOINT, {"jsonrpc": "1.0", "id": "rpcnode", "method": "getblockcount", "params": []})
    if method == "gethealth":
        return post(ENDPOINT, {"jsonrpc": "2.0", "id": 1, "method": "getHealth", "params": []})
    if method == "getslot":
        return post(ENDPOINT, {"jsonrpc": "2.0", "id": 1, "method": "getSlot", "params": []})
    if method == "server_info":
        return post(ENDPOINT, {"method": "server_info", "params": [{}]})
    if method == "health":
        return get(ENDPOINT + "/health")

    # Network defaults
    if net == "tron":
        return get(ENDPOINT + "/wallet/getnowblock")
    if net in ("bitcoin", "doge"):
        return post(ENDPOINT, {"jsonrpc": "1.0", "id": "rpcnode", "method": "getblockcount", "params": []})
    if net in ("ethereum", "bsc", "arb", "optimism", "base", "hyperliquid", "robinhood", "etc"):
        return post(ENDPOINT, {"jsonrpc": "2.0", "id": 1, "method": "eth_blockNumber", "params": []})
    if net == "solana":
        return post(ENDPOINT, {"jsonrpc": "2.0", "id": 1, "method": "getSlot", "params": []})
    if net == "xrpl":
        return post(ENDPOINT, {"method": "server_info", "params": [{}]})
    if net == "cardano":
        return get(ENDPOINT + "/health")
    # fallback
    return post(ENDPOINT, {"jsonrpc": "2.0", "id": 1, "method": "eth_blockNumber", "params": []})


def one_ok(body: bytes, code: int) -> bool:
    if code < 200 or code >= 300:
        return False
    net = NETWORK
    try:
        doc = json.loads(body.decode("utf-8", "replace"))
    except Exception:
        return False
    if net == "tron":
        return isinstance(doc, dict) and ("block_header" in doc or "blockID" in doc)
    if net in ("bitcoin", "doge"):
        return isinstance(doc, dict) and isinstance(doc.get("result"), (int, float))
    if net in ("ethereum", "bsc", "arb", "optimism", "base", "hyperliquid", "robinhood", "etc"):
        r = doc.get("result") if isinstance(doc, dict) else None
        return isinstance(r, str) and r.startswith("0x")
    if net == "solana":
        return isinstance(doc, dict) and isinstance(doc.get("result"), (int, float, str))
    if net == "xrpl":
        return isinstance(doc, dict) and isinstance(doc.get("result"), dict)
    if net == "cardano":
        return isinstance(doc, dict)
    return isinstance(doc, dict)


def worker():
    global ok_n, fail_n, lat_sum
    while not stop.is_set():
        req = build_request()
        t0 = time.perf_counter()
        ok = False
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
                body = resp.read(1 << 20)
                ok = one_ok(body, resp.status)
        except urllib.error.HTTPError as e:
            body = e.read(1 << 16) if e.fp else b""
            ok = one_ok(body, e.code)
        except Exception:
            ok = False
        dt = time.perf_counter() - t0
        with lock:
            if ok:
                ok_n += 1
            else:
                fail_n += 1
            lat_sum += dt


# Warmup / connectivity
try:
    req = build_request()
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        body = resp.read(1 << 20)
        if not one_ok(body, resp.status):
            print(f"WARN: probe HTTP {resp.status} body={body[:160]!r}", file=sys.stderr)
        else:
            print(f"+ probe ok HTTP {resp.status}")
except Exception as e:
    print(f"ERROR: probe failed: {e}", file=sys.stderr)
    sys.exit(1)

deadline = time.time() + DURATION
with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
    futs = [pool.submit(worker) for _ in range(CONCURRENCY)]
    last = 0
    while time.time() < deadline and not stop.is_set():
        time.sleep(1.0)
        with lock:
            total = ok_n + fail_n
            ok, fail, lsum = ok_n, fail_n, lat_sum
        elapsed = max(0.001, time.time() - started)
        delta = total - last
        last = total
        avg_ms = (lsum / total * 1000.0) if total else 0.0
        print(
            f"t={elapsed:5.1f}s  rps≈{delta:6.1f}  ok={ok}  fail={fail}  "
            f"avg_lat={avg_ms:6.1f}ms  inflight≈{CONCURRENCY}",
            flush=True,
        )
    stop.set()
    for f in futs:
        f.result()

elapsed = max(0.001, time.time() - started)
with lock:
    total = ok_n + fail_n
    ok, fail, lsum = ok_n, fail_n, lat_sum
avg_ms = (lsum / total * 1000.0) if total else 0.0
print()
print(
    f"done  elapsed={elapsed:.1f}s  total={total}  ok={ok}  fail={fail}  "
    f"avg_rps={total/elapsed:.1f}  avg_lat={avg_ms:.1f}ms"
)
print(f"panel: watch Fullnode Go RPC on the node page (rps_1m / p95 / 5xx)")
PY
