#!/usr/bin/env bash
# Check public fullnode endpoints for nodes registered in panel.db.
# Reads SQLite directly (no panel login). Probes Go proxy http://host:public_port.
#
# Usage (from toolkit root):
#   ./scripts/check-fullnodes.sh
#   ./scripts/check-fullnodes.sh --network solana
#   ./scripts/check-fullnodes.sh --online-only --json
#   ./scripts/check-fullnodes.sh --db /path/to/panel.db
#   ./scripts/check-fullnodes.sh --container rpcnode-panel
#
# Exit 0 = all checked OK; 1 = any FAIL.
set -euo pipefail

CONTAINER="${PANEL_CONTAINER:-rpcnode-panel}"
DB_PATH=""
NETWORK_FILTER=""
ENV_FILTER=""
ONLINE_ONLY=0
JSON_OUT=0
TIMEOUT_SEC="${TIMEOUT_SEC:-8}"
SAMPLE_LEN=200

usage() {
  sed -n '2,20p' "$0"
}

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --container) CONTAINER="${2:-}"; shift 2 ;;
    --db) DB_PATH="${2:-}"; shift 2 ;;
    --network) NETWORK_FILTER="${2:-}"; shift 2 ;;
    --env) ENV_FILTER="${2:-}"; shift 2 ;;
    --online-only) ONLINE_ONLY=1; shift ;;
    --json) JSON_OUT=1; shift ;;
    --timeout) TIMEOUT_SEC="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown arg: $1 (see --help)" ;;
  esac
done

SQL=$(cat <<'SQL'
SELECT n.id, n.name, n.network, n.env, n.public_port, n.status,
       coalesce(nullif(n.agent_url,''), s.agent_url) AS agent_url
FROM nodes n
LEFT JOIN servers s ON s.id = n.server_id
WHERE n.public_port > 0
  AND lower(coalesce(n.status,'')) NOT IN ('removing','remove_error')
ORDER BY n.network, n.env, n.name;
SQL
)

fetch_rows() {
  if [[ -n "$DB_PATH" ]]; then
    [[ -f "$DB_PATH" ]] || die "db not found: $DB_PATH"
    if command -v sqlite3 >/dev/null 2>&1; then
      sqlite3 -separator $'\t' "$DB_PATH" "$SQL"
    else
      python3 - "$DB_PATH" "$SQL" <<'PY'
import sqlite3, sys
db, sql = sys.argv[1], sys.argv[2]
con = sqlite3.connect(db)
for row in con.execute(sql):
    print("\t".join("" if v is None else str(v) for v in row))
PY
    fi
  else
    command -v docker >/dev/null 2>&1 || die "docker not found (use --db PATH)"
    docker exec "$CONTAINER" sqlite3 -separator $'\t' /var/lib/rpcnode/panel.db "$SQL" \
      || die "sqlite query failed (container=$CONTAINER)"
  fi
}

ROWS="$(fetch_rows || true)"
if [[ -z "${ROWS//[$'\t\n\r']/}" ]]; then
  if [[ "$JSON_OUT" -eq 1 ]]; then
    echo '[]'
  else
    echo "no nodes with public_port > 0"
  fi
  exit 0
fi

export ROWS NETWORK_FILTER ENV_FILTER ONLINE_ONLY JSON_OUT TIMEOUT_SEC SAMPLE_LEN
python3 - <<'PY'
from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.parse import urlparse

TIMEOUT = float(os.environ.get("TIMEOUT_SEC", "8"))
SAMPLE_LEN = int(os.environ.get("SAMPLE_LEN", "200"))
NETWORK_FILTER = (os.environ.get("NETWORK_FILTER") or "").strip().lower()
ENV_FILTER = (os.environ.get("ENV_FILTER") or "").strip().lower()
ONLINE_ONLY = os.environ.get("ONLINE_ONLY") == "1"
JSON_OUT = os.environ.get("JSON_OUT") == "1"
ONLINE_STATUSES = {
    "online", "syncing", "starting", "working", "running",
    "ports_confirmed", "ready_to_install", "snapshot_running",
}

def clip(s: str, n: int = SAMPLE_LEN) -> str:
    s = re.sub(r"\s+", " ", (s or "").strip())
    if len(s) <= n:
        return s
    return s[: n - 1] + "…"

def host_from_agent_url(agent_url: str) -> str:
    u = urlparse((agent_url or "").strip())
    if u.hostname:
        return u.hostname
    # bare host:port
    m = re.match(r"^(?:https?://)?([^:/]+)", agent_url or "")
    return m.group(1) if m else ""

def fullnode_url(agent_url: str, public_port: int) -> str:
    host = host_from_agent_url(agent_url)
    if not host or public_port <= 0:
        return ""
    scheme = urlparse(agent_url).scheme or "http"
    return f"{scheme}://{host}:{public_port}"

def http_get(url: str) -> tuple[int, str]:
    req = urllib.request.Request(url, method="GET", headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            body = resp.read(1 << 20).decode("utf-8", "replace")
            return resp.status, body
    except urllib.error.HTTPError as e:
        body = e.read(1 << 16).decode("utf-8", "replace") if e.fp else str(e)
        return e.code, body
    except Exception as e:
        return 0, str(e)

def http_json_post(url: str, payload: dict) -> tuple[int, str]:
    data = json.dumps(payload).encode()
    req = urllib.request.Request(
        url,
        data=data,
        method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            body = resp.read(1 << 20).decode("utf-8", "replace")
            return resp.status, body
    except urllib.error.HTTPError as e:
        body = e.read(1 << 16).decode("utf-8", "replace") if e.fp else str(e)
        return e.code, body
    except Exception as e:
        return 0, str(e)

def parse_json(body: str):
    try:
        return json.loads(body)
    except Exception:
        return None

def probe(network: str, base: str) -> tuple[bool, str, str]:
    """Return (ok, method, sample)."""
    net = (network or "").lower()

    if net == "tron":
        code, body = http_get(base.rstrip("/") + "/wallet/getnowblock")
        doc = parse_json(body)
        ok = False
        if isinstance(doc, dict):
            hdr = doc.get("block_header") or {}
            raw = hdr.get("raw_data") if isinstance(hdr, dict) else None
            num = None
            if isinstance(raw, dict):
                num = raw.get("number")
            ok = num is not None or "block_header" in doc
        return ok, "GET /wallet/getnowblock", clip(body if code else body)

    if net in ("bitcoin", "doge"):
        code, body = http_json_post(base, {"jsonrpc": "1.0", "id": "rpcnode", "method": "getblockcount", "params": []})
        doc = parse_json(body)
        ok = isinstance(doc, dict) and isinstance(doc.get("result"), (int, float)) and doc.get("error") in (None, {})
        if isinstance(doc, dict) and doc.get("error") not in (None, {}):
            ok = False
        return ok, "getblockcount", clip(body if body else f"http={code}")

    if net in ("ethereum", "bsc", "arb", "optimism", "base", "hyperliquid", "robinhood", "etc"):
        code, body = http_json_post(base, {"jsonrpc": "2.0", "id": 1, "method": "eth_blockNumber", "params": []})
        doc = parse_json(body)
        res = doc.get("result") if isinstance(doc, dict) else None
        ok = isinstance(res, str) and res.startswith("0x")
        return ok, "eth_blockNumber", clip(body if body else f"http={code}")

    if net == "solana":
        code, body = http_json_post(base, {"jsonrpc": "2.0", "id": 1, "method": "getHealth", "params": []})
        doc = parse_json(body)
        if isinstance(doc, dict) and doc.get("result") == "ok":
            return True, "getHealth", clip(body)
        # behind / error → try getSlot
        code2, body2 = http_json_post(base, {"jsonrpc": "2.0", "id": 1, "method": "getSlot", "params": []})
        doc2 = parse_json(body2)
        res = doc2.get("result") if isinstance(doc2, dict) else None
        ok = isinstance(res, (int, float))
        sample = body2 if ok else (body2 or body)
        return ok, "getSlot" if ok else "getHealth", clip(sample if sample else f"http={code2 or code}")

    if net == "xrpl":
        code, body = http_json_post(base, {"method": "server_info", "params": [{}]})
        doc = parse_json(body)
        ok = False
        if isinstance(doc, dict):
            result = doc.get("result") or {}
            ok = isinstance(result, dict) and ("info" in result or result.get("status") == "success")
        return ok, "server_info", clip(body if body else f"http={code}")

    if net == "cardano":
        code, body = http_get(base.rstrip("/") + "/health")
        doc = parse_json(body)
        ok = isinstance(doc, dict) and (
            "networkSynchronization" in doc or "lastKnownTip" in doc or doc.get("connectionStatus") is not None
        )
        return ok, "GET /health", clip(body if body else f"http={code}")

    if net == "stellar":
        # stellar-rpc rejects params:{} on getHealth — omit params entirely
        code, body = http_json_post(base, {"jsonrpc": "2.0", "id": 1, "method": "getHealth"})
        doc = parse_json(body)
        ok = False
        if isinstance(doc, dict):
            result = doc.get("result") or {}
            ok = isinstance(result, dict) and (
                str(result.get("status", "")).lower() == "healthy" or result.get("latestLedger") is not None
            )
        return ok, "getHealth", clip(body if body else f"http={code}")

    # unknown network — generic JSON-RPC eth_blockNumber then GET /
    code, body = http_json_post(base, {"jsonrpc": "2.0", "id": 1, "method": "eth_blockNumber", "params": []})
    doc = parse_json(body)
    if isinstance(doc, dict) and isinstance(doc.get("result"), str):
        return True, "eth_blockNumber", clip(body)
    code2, body2 = http_get(base.rstrip("/") + "/")
    ok = code2 > 0 and code2 < 500 and len(body2) > 0
    return ok, "GET /", clip(body2 or body or f"http={code2 or code}")

rows_raw = os.environ.get("ROWS") or ""
nodes = []
for line in rows_raw.splitlines():
    if not line.strip():
        continue
    parts = line.split("\t")
    while len(parts) < 7:
        parts.append("")
    nid, name, network, env, pub, status, agent_url = parts[:7]
    try:
        public_port = int(float(pub or 0))
    except ValueError:
        public_port = 0
    net_l = network.lower()
    env_l = env.lower()
    st_l = (status or "").lower()
    if NETWORK_FILTER and net_l != NETWORK_FILTER:
        continue
    if ENV_FILTER and env_l != ENV_FILTER:
        continue
    if ONLINE_ONLY and st_l not in ONLINE_STATUSES:
        continue
    nodes.append({
        "id": nid,
        "name": name,
        "network": network,
        "env": env,
        "public_port": public_port,
        "status": status,
        "agent_url": agent_url,
        "endpoint": fullnode_url(agent_url, public_port),
    })

results = []

def run_one(n: dict) -> dict:
    endpoint = n["endpoint"]
    if not endpoint:
        return {
            **n,
            "ok": False,
            "check_status": "SKIP",
            "method": "",
            "sample": "no host/public_port",
        }
    ok, method, sample = probe(n["network"], endpoint)
    return {
        **n,
        "ok": ok,
        "check_status": "OK" if ok else "FAIL",
        "method": method,
        "sample": sample,
    }

with ThreadPoolExecutor(max_workers=8) as ex:
    futs = {ex.submit(run_one, n): n for n in nodes}
    for fut in as_completed(futs):
        results.append(fut.result())

# stable order
results.sort(key=lambda r: (r.get("network") or "", r.get("env") or "", r.get("name") or ""))

checked = sum(1 for r in results if r["check_status"] != "SKIP")
ok_n = sum(1 for r in results if r["check_status"] == "OK")
fail_n = sum(1 for r in results if r["check_status"] == "FAIL")
skip_n = sum(1 for r in results if r["check_status"] == "SKIP")

if JSON_OUT:
    out = []
    for r in results:
        out.append({
            "network": r["network"],
            "env": r["env"],
            "name": r.get("name"),
            "id": r.get("id"),
            "endpoint": r.get("endpoint"),
            "status": r["check_status"],
            "ok": r["ok"] if r["check_status"] != "SKIP" else None,
            "method": r.get("method"),
            "sample": r.get("sample"),
            "node_status": r.get("status"),
        })
    print(json.dumps({"checked": checked, "ok": ok_n, "fail": fail_n, "skip": skip_n, "items": out}, indent=2))
else:
    for r in results:
        print(f"{r['network']} / {r['env']}")
        print(f"  endpoint: {r.get('endpoint') or '—'}")
        print(f"  status:   {r['check_status']}")
        sample = r.get("sample") or ""
        if r.get("method"):
            print(f"  method:   {r['method']}")
        print(f"  sample:   {sample}")
        print()
    print(f"checked={checked} ok={ok_n} fail={fail_n} skip={skip_n}")

sys.exit(0 if fail_n == 0 else 1)
PY
