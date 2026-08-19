# Developer API — RpcNode toolkit (node agent)

Stable HTTP JSON API for integrations: node health, metrics, update status, alerting webhooks.

**Base URL = node agent port** (not the ops panel): `http://<host>:<agent_port>` (often same as public Go RPC when combined).  

Ops SPA runs on the **standalone panel** (`docker-compose.panel.yml`, `:8093`) and proxies here with `AGENT_API_TOKEN`.

Public chain RPC (catch-all) shares the listen port with `/api/v1/*` — chain paths need no auth; agent JSON paths use the token.

Legacy aliases: `/api/status.json`, `/api/metrics.json` on the agent.

## Multiple environments (ports)

Each env is a separate **agent** stack (per network/env). The panel is one control-plane process that talks to many agents.

| Example | Public Go RPC / agent | Upstream node (loopback) | Panel (control host) |
|---------|----------------------|--------------------------|----------------------|
| tron/mainnet | `:39090` | `:18090` | `:8093` (standalone) |
| bitcoin/mainnet | `:39390` | `:8332` | `:8093` (standalone) |
| solana/mainnet | `:39590` | `:8899` | `:8093` (standalone) |

Connect / RPC clients use the public Go RPC base. Panel humans use `http://127.0.0.1:8093/` (`PANEL_PORT`).

## Auth

| Method | Header / creds | When |
|--------|----------------|------|
| Panel session | Cookie `rpcnode_session` **or** `Authorization: Bearer <token>` after `POST /api/auth/login` on **panel** `:8093` (TTL **30 days**) | Humans (SPA + curl/API inspect) |
| Panel HTTP Basic | `-u user:pass` against panel htpasswd | Non-browser API tools (legacy) |
| Agent API key | `X-Api-Token` **or** `Authorization: Bearer` | Panel → agent / machines — `AGENT_API_TOKEN` |

Panel first start: empty htpasswd → open `http://127.0.0.1:8093/setup-password`.  
Agent install one-liner: `curl -fsSL "$AGENT_DOWNLOAD_URL" | sudo bash`.
Network/env is bound later in the panel when adding a node.

### Forgot / reset panel password (Docker)

Standalone panel (`docker-compose.panel.yml`, container `rpcnode-panel`) stores human logins in htpasswd (`PANEL_HTPASSWD=/etc/rpcnode/panel.htpasswd`, bind-mounted from `config/nginx/htpasswd/panel.htpasswd`). Reset by login:

```bash
# Reset password for login (min 8 chars)
docker exec rpcnode-panel rpcnode-panel passwd admin --password 'new-secret'

# Drop in-memory sessions (htpasswd reloads ~every 5s without restart)
docker restart rpcnode-panel
```

Host install (not compose panel): `sudo ./rpcnodectl panel-auth set --user admin --password 'new-secret'`.

Prefer API-first panel inspect (no browser):

```bash
# Fresh panel session token (prints token only; uses PANEL_USER/PANEL_PASS — see .env.example)
TOKEN=$(./scripts/panel-token.sh)

# Same JSON the UI uses for node detail / lifecycle / sync
curl -sS -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8093/api/status.json?node=<NODE_UUID>" | jq .

curl -sS -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8093/api/workloads | jq .
curl -sS -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8093/api/nodes | jq .
```

```bash
# Panel (control plane) — login returns { token, expires_at } (+ Set-Cookie for SPA)
curl -s http://127.0.0.1:8093/api/auth/status | jq .
curl -s -X POST http://127.0.0.1:8093/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"secret"}' | jq '{ok, token, expires_at}'

# Node agent API (agent / public RPC port)
curl -H "Authorization: Bearer $AGENT_API_TOKEN" http://127.0.0.1:39090/api/v1/updates | jq .
curl -H "X-Api-Token: $AGENT_API_TOKEN" http://127.0.0.1:39090/api/v1/events?limit=20 | jq .

# Public RPC (same port — no agent key; method depends on network)
curl -s http://127.0.0.1:39090/wallet/getnowblock | head -c 200   # tron example
```

Optional: `AGENT_API_TOKEN_REQUIRED=1` — require agent key for `/api/*`.

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1` | Catalog + auth hints + event type list |
| `GET` | `/api/v1/node` | Health, sync/RPC, versions, instance, services, disk, snapshot summary |
| `GET` | `/api/v1/status` | Alias of `/api/v1/node` |
| `GET` | `/api/v1/metrics` | Current CPU/mem/load/RPS + short history (same shape as `/api/metrics.json`) |
| `GET` | `/api/v1/host/disks` | Host block devices + mounts (`lsblk`/`findmnt`); `?network=solana&env=` adds recommended JBOD layout |
| `GET` | `/api/v1/updates` | Toolkit agent + chain client update availability (`needs_attention`) |
| `POST` | `/api/v1/node/restart` | Soft-restart fullnode (RPC sleep → `systemctl stop`→`start` / ExecStop → wake) |
| `POST` | `/api/v1/node/stop` | Soft-stop fullnode (RPC sleep → CLI/RPC then `systemctl stop`; stays down until Restart) |
| `GET` | `/api/v1/node/config` | Leaf chain config documents + field schema (per network) |
| `PUT` | `/api/v1/node/config` | Save config (`confirm=true`) then soft stop→start (`restart` default true) |
| `GET` | `/api/v1/events?limit=50` | Last N alerts (newest first) |
| `GET` | `/api/v1/webhooks` | Outbound webhook URLs |
| `PUT` | `/api/v1/webhooks` | Replace list: `{"urls":["https://…"]}` |
| `POST` | `/api/v1/webhooks` | Append one: `{"url":"https://…"}` |

Panel-only (unchanged): `/api/toolkit/*`, `/api/snapshot/*`, `/api/maintenance/*` — basic auth, not part of v1 contract.

Panel proxy (ops SPA): `GET /api/workloads/host-disks?server_id=&network=solana&env=` → tip `GET /api/v1/host/disks`.

Panel disk layout (SQLite, per node UUID):

- `GET /api/workloads/{uuid}/disk-layout` → `{ ok, node_id, disk_layout, install_options }`
- `PUT /api/workloads/{uuid}/disk-layout` body `{ "disk_layout": { … } }`
- Also on `GET /api/workloads/{uuid}` / list items as `disk_layout` and `install_options`
- `POST /api/workloads/provision` persists body `disk_layout` (and flat `ledger_dir` / …) and `install_options`; omit on retry → panel reuses saved layout / options toward tip
- Tip `POST /api/v1/nodes/plan` may return `install_options` groups (TRON mainnet snapshot flavors; XRPL `xrpl_history`). Provision body `{ "install_options": { "snapshot": "internal_tx" } }` or `{ "install_options": { "xrpl_history": "weeks" } }`

## Host disks (`GET /api/v1/host/disks`)

Inventory for multi-disk JBOD layout (Solana + eth/bsc/arb/… with `multi_disk_roles`). Source: `lsblk -J` + `findmnt -J`.

```json
{
  "ok": true,
  "disks": [
    {
      "name": "nvme0n1",
      "path": "/dev/nvme0n1",
      "model": "Samsung SSD 990 PRO",
      "size_bytes": 2000398934016,
      "size_human": "1.8TiB",
      "tran": "nvme",
      "rota": false,
      "type": "disk",
      "preferred": true
    }
  ],
  "mounts": [
    {
      "target": "/mnt/nvme0",
      "source": "/dev/nvme0n1p1",
      "fstype": "ext4",
      "avail_bytes": 1500000000000,
      "avail_human": "1.4TiB",
      "disk_name": "nvme0n1",
      "tran": "nvme",
      "rota": false,
      "preferred": true
    }
  ],
  "network": "solana",
  "env": "mainnet",
  "recommended": {
    "strategy": "jbod_2",
    "ledger_mount": "/mnt/nvme0",
    "accounts_mount": "/mnt/nvme1",
    "snapshots_mount": "/mnt/nvme1",
    "ledger_dir": "/mnt/nvme0/solana/mainnet/ledger",
    "accounts_dir": "/mnt/nvme1/solana/mainnet/accounts",
    "snapshots_dir": "/mnt/nvme1/solana/mainnet/snapshots",
    "notes": ["JBOD 2 disks: ledger on …, accounts+snapshots on …"]
  },
  "layout_rules": [
    "Prefer separate NVMe as JBOD (not one RAID volume).",
    "Put ledger and accounts on different disks when ≥2 NVMe/SSD mounts exist.",
    "Put snapshots on a third disk when possible; else co-locate with accounts.",
    "Single disk → all under /data/solana/<env>/{ledger,accounts,snapshots}."
  ]
}
```

Provision accepts the same paths: `POST /api/v1/nodes/provision` with `ledger_dir` / `accounts_dir` / `snapshots_dir` or nested `disk_layout` (`roles` map for any `multi_disk_roles` network).

## Updates payload (`GET /api/v1/updates`)

```json
{
  "ok": true,
  "needs_attention": true,
  "toolkit": {
    "local_version": "0.3.0",
    "remote_version": "0.3.1",
    "update_available": true,
    "apply_ready": true,
    "apply_mode": "docker-sock"
  },
  "node_jar": {
    "update_available": false,
    "local_version": "…",
    "remote_version": null
  }
}
```

`toolkit` = RpcNode agent binaries. `node_jar` = optional chain-client updater (network-specific; may be empty).

## Notifications

### Outbound webhooks

Configure via env and/or API:

```bash
# compose / toolkit.env (comma-separated)
RPCNODE_WEBHOOK_URLS=https://hooks.example.com/rpc,https://alerts.example.com/rpc
# legacy alias still accepted: TRON_WEBHOOK_URLS=…

# or at runtime (via agent with token)
curl -H "Authorization: Bearer $AGENT_API_TOKEN" -X PUT http://127.0.0.1:39090/api/v1/webhooks \
  -H 'Content-Type: application/json' \
  -d '{"urls":["https://hooks.example.com/rpc"]}'
```

system-agent `POST`s JSON on state edges (deduped). Header: `X-RpcNode-Event: <type>`.

### Event types

| type | severity | When |
|------|----------|------|
| `node.down` | critical | chain node / RPC unhealthy (panel: continuous hold, default 45s) |
| `node.up` | info | recovered (panel: continuous healthy hold, default 20s) |
| `disk.low` | warning | root disk ≥ 90% |
| `disk.ok` | info | below threshold again |
| `maintenance.on` / `maintenance.off` | warning / info | panel maintenance |
| `snapshot.failed` | critical | snapshot phase/detail contains fail/error |
| `snapshot.running` | info | wget download started |
| `toolkit.update_available` | warning | toolkit channel newer than local |
| `node.update_available` | warning | chain client updater reports available |

### Event schema (webhook body + events feed)

```json
{
  "id": "evt_a1b2c3d4e5f60708",
  "ts": "2026-08-09T07:40:00Z",
  "type": "toolkit.update_available",
  "severity": "warning",
  "env": "mainnet",
  "instance_id": "tron-mainnet",
  "message": "toolkit update available: 0.3.0 → 0.3.1",
  "data": {
    "local_version": "0.3.0",
    "remote_version": "0.3.1"
  }
}
```

### Inbound polling

```bash
curl -H "Authorization: Bearer $AGENT_API_TOKEN" \
  'http://127.0.0.1:39090/api/v1/events?limit=50' | jq .
```

Persisted under the per-node state dir (ring ~200), e.g. `/var/lib/rpcnode/<network>-<env>/events.jsonl`.

### Sample webhook receiver

```bash
#!/usr/bin/env bash
# nc -l 9999 style: use any HTTP catcher
python3 - <<'PY'
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
class H(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(n)
        print(json.dumps(json.loads(body), indent=2))
        self.send_response(204); self.end_headers()
HTTPServer(("0.0.0.0", 9999), H).serve_forever()
PY
# then: POST /api/v1/webhooks {"url":"http://host.docker.internal:9999/"}
```

## Toolkit self-update (ops, not v1)

Preferred: panel **Servers → Update agent** (or `POST /api/v1/agent/update` on the host tip with `AGENT_API_TOKEN`) — downloads CDN binaries, ensures host-wide `rpcnode-agent-watchdog.service` (install + enable --now), and restarts agent units. Chain client / datadir are not touched. Full `install/agent.sh` is not required for watchdog on existing tips.

Legacy host path (if present): `update-requested.json` / host watch helpers — same rule: agent binaries only.
