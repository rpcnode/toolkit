# RpcNode panel (standalone control plane)

Ops console + human auth + servers/nodes registry. **Not** the node agent.  
Multi-network: TRON, Bitcoin, and future chains via host agents.

## Start (Mac / control host / Linux ops server)

One command is enough — admin login, image, network, and all three containers:

```bash
./scripts/up-panel.sh
# prompts for login/password on first run, then http://127.0.0.1:8093/login
# non-interactive: PANEL_USER=admin PANEL_PASS='...' ./scripts/up-panel.sh
```

`rpcnode-panel:local` is built from this checkout (`./Dockerfile`). Not on Docker Hub — never `docker compose pull`. If `:8093` is busy, stop the old stack first (`docker compose -p tron-toolkit-mainnet stop api-agent`).

## Update / rebuild

```bash
docker compose -f docker-compose.panel.yml up -d --build --pull never
```

That rebuild compiles `status-ui` inside the image (`go:embed`). `panel/ui` is not in git and is not mounted.

## Add network (UI)

Checklist: [`docs/add-network.md`](../docs/add-network.md). Short form: `.cursor/rules/rpcnode-multi-chain-agent.mdc`.  
New network: implement in the agent first (**capacity for thousands of RPC from day one**), then test **only this flow**.  
Do not bring the node up by SSH around the panel.

On the Server (Agent URL = host tip):

1. **Add node** in the panel — registry.
2. **Confirm ports** — tip `plan` → `provision` + ACK.
3. Agent syncs / does what the network needs — UI **logs** + **sync %**.
4. Verify the **fullnode endpoint through the Go proxy** (`:<public_port>`).

Do not use the leaf agent port as the Server URL; do not stop tip when removing a leaf.

### CLI (agents / automation)

Same panel flow without SSH on the node:

```bash
# secrets: PANEL_USER / PANEL_PASS (or .cursor/secrets/rpcnode-panel-pass.env)
cd deploy/nodes/tron/toolkit

./scripts/add-node.sh --server bitcoin-1 --network hyperliquid --env testnet
./scripts/remove-node.sh --server bitcoin-1 --network hyperliquid --env mainnet --delete-files
./scripts/check-fullnodes.sh
./scripts/check-fullnodes.sh --network solana

./scripts/add-node.sh --help
```

`add-node.sh`: plan → provision (Confirm ports) → start + lifecycle poll.  
`remove-node.sh`: `POST /api/workloads/remove` → tip.  
`check-fullnodes.sh`: SQLite `nodes.public_port` + host → probe Go proxy (no panel login).

| Docker | Value |
|--------|--------|
| Compose project | `rpcnode-panel` (`name:` in yml) |
| Image / containers | `rpcnode-panel:local`, `rpcnode-panel`, `rpcnode-panel-collector`, `rpcnode-panel-watchdog` |
| Network | `rpcnode-panel_net` (compose creates it) |
| Data volume | `rpcnode-panel_panel-lib` → `/var/lib/rpcnode` (`panel.db` + `panel.notify.key`) |

| URL | Purpose |
|-----|---------|
| `http://127.0.0.1:8093/` | SPA (redirects to `/setup-password` or `/login`) |
| `http://127.0.0.1:8093/login` | Sign-in |
| `http://127.0.0.1:8093/api/auth/status` | Auth bootstrap JSON |
| `http://127.0.0.1:8093/notifications` | Telegram bot + subscriptions |

### Auth / API token / forgot password

- Session TTL **30 days**. SPA: cookie `rpcnode_session`. Scripts: `Authorization: Bearer <token>`.
- Fresh token (stdout): `TOKEN=$(./scripts/panel-token.sh)` then curl panel APIs (see [`../docs/developer-api.md`](../docs/developer-api.md)).
- Forgot password (compose panel):

```bash
docker exec rpcnode-panel rpcnode-panel passwd admin --password 'new-secret'
docker restart rpcnode-panel
```

Recreate without wiping data: keep volume `panel-lib` / `rpcnode-panel_panel-lib`.

## Collector watchdog

`panel-collector` writes `/var/lib/rpcnode/collector.heartbeat` on every poll loop (~2s). If that file (or SQLite `last_tick_at`) is older than **2 minutes**, the UI shows a red banner at the top of the console.

Compose service **`panel-watchdog`** (`install/panel-watchdog.sh`) watches:

- panel `GET /healthz` → `docker restart rpcnode-panel`
- collector heartbeat → `docker restart rpcnode-panel-collector`

Rate-limit 90s. It never restarts itself. Needs `/var/run/docker.sock`.

Manual:

```bash
docker restart rpcnode-panel-collector
docker restart rpcnode-panel   # only if the UI itself is frozen
```

## Notifications (Telegram)

Menu **Notifications**: bot token + chat id → **Send test code** → enter code → **Verify**.

- Alerts fire from **panel-collector** (client update, lifecycle, node down/up, agent behind CDN, disk/CPU/fullnode Go RPC). Thresholds in UI (defaults: disk/CPU 90%, RPC RPS 1000/s, p95 2000 ms, errors 10%).
- Bot token is **AES-GCM** ciphertext in SQLite (`collector_meta`); decryption key is **outside** the DB:
  - file `/var/lib/rpcnode/panel.notify.key` (mode `0600`, auto-created), or
  - env `RPCNODE_NOTIFY_KEY` (base64 32 bytes), optional `RPCNODE_NOTIFY_KEY_FILE`.
- Backup: copy `panel.db` **and** `panel.notify.key` (or keep the env key). DB-only backup cannot recover the token.
- API: `GET/PUT /api/notifications/settings`, `POST /api/notifications/test`, `POST /api/notifications/verify`.

## vs node agent

| | Panel | Node agent (`api-agent`) |
|--|-------|---------------------------|
| Where | Mac / ops host | Blockchain server |
| Port | `:8093` | network-specific (TRON often `:39090`) |
| SPA | yes | **no** |
| State | users, sessions, servers/nodes registry | agent-state, maintenance, metrics |

See [`../docs/architecture-control-plane.md`](../docs/architecture-control-plane.md).
