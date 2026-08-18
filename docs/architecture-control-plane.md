# Architecture: standalone panel ≠ node agent

English product architecture for RpcNode toolkits (TRON first; Solana / ETH later).

**Critical rule:** the ops panel is a **standalone control plane**. It does **not** live inside the node agent.

## Goals

1. Run a **panel / control plane** on a Mac or ops host (own binary, own compose, own state).
2. Install a **thin host agent** on blockchain servers (RPC proxy + system checks + command API).
3. Authenticate the panel to agents with an **agent API key** (URL + key per node).
4. Central UI owns dashboards, install wizards, multi-server / multi-network views.

## Components

### Panel / control plane (Mac or ops host)

Binary: `rpcnode-panel` · compose: `docker-compose.panel.yml` · default port **`:8093`**

| Area | What it does |
|------|----------------|
| **SPA** | React ops console at `/`, `/login`, `/setup-password`, `/install`, `/nodes/:id` (compiled in the panel image, not committed) |
| **Human auth** | First-run `/setup-password`, then session cookie / Bearer (TTL **30d**); reset: `docker exec rpcnode-panel rpcnode-panel passwd <login>` |
| **Collector** | `rpcnode-panel-collector` polls host agents → SQLite; UI lists read DB only |
| **Watchdog** | `rpcnode-panel-watchdog` restarts panel if `/healthz` fails, collector if heartbeat > 2 min |
| **Nodes registry** | Local JSON registry of remote agents (`POST /api/nodes`) |
| **Proxy** | Forwards ops APIs to a chosen agent (`PANEL_DEFAULT_AGENT_URL` or registry) |

Panel does **not** run chain clients, does **not** proxy public fullnode RPC for end users.

```bash
# Enough to start the panel (asks for admin login, builds image, starts containers).
cd deploy/nodes/tron/toolkit
./scripts/up-panel.sh
open http://127.0.0.1:8093/login
```

### Host agent (on each blockchain server)

Binaries: `rpcnode-api-agent` + `rpcnode-system-agent` · **systemd** (Go binaries).  
`ExecStart` always points at `/opt/rpcnode/bin/rpcnode-*-agent` (real file). Optional compat symlink `tron-*-agent` → `rpcnode-*-agent` — **same process**, not a second TRON agent.  
**One Server agent per host** (knows `tron`, `bitcoin`, …).  
**Per-node agents** after provision (own `agent_port` / units).  
**No Docker on node servers for agents. No ops SPA. No separate bitcoin installer.**

### Server agent URL vs node agent URL

| Layer | panel.db | Typical port | Used for |
|-------|----------|--------------|----------|
| **Server** (host control plane) | `servers.agent_url` | TRON default **`:39190`** | `POST …/nodes/plan`, `POST …/nodes/provision`, server check / agent update |
| **Node** (per `network/env`) | `nodes.agent_port` (+ `nodes.agent_url`) | Bitcoin **`:39390`** | `status.json` / metrics / start **after** ports are planned |

Panel rules:

1. **Plan / provision / Confirm ports** → always `servers.agent_url` (never rewrite Server URL to the node `agent_port`).
2. **Status / Install start** → resolve by node UUID → rewrite host to `nodes.agent_port` (`agentControlBase`).
3. UI must **not** pass `?server=` on bitcoin status polls when `node=` is present (host CP may still identify as tron).

**Ideal split (two listeners):** host CP on `:39190`, bitcoin per-node on `:39390`.

**Merged host (observed `bitcoin-1` / `185.44.207.104`):** only **`:39390`** is listening — host CP is the same process as the bitcoin agent port (`healthz.network=bitcoin`, `supported_networks` includes bitcoin+tron). In that case keep `servers.agent_url` on **`:39390`** (do **not** point it at a dead `:39190`). Plan/provision with `network=bitcoin` against that URL is correct; node status also uses `:39390`. TRON hosts stay on their own Server URL (e.g. `:39190`) and must not be rewritten.

Install (CDN / archive only — ❌ no toolkit git clone):

```bash
curl -fsSL "https://toolkit.rpcnode.dev/install/agent.sh" | sudo bash
# prebuilt agents: /install/binaries/ + watchdog; see docs/agent-install.md
# offline: LOCAL_ARTIFACT_DIR=/path/to/unpacked archive
```

| Area | What it does |
|------|----------------|
| **RPC proxy** | Public catch-all FullNode / chain HTTP (no panel login) |
| **Agent API** | Authenticated `/api/v1/*` (+ legacy `/api/status.json`) for status, metrics, controls |
| **Preflight** | Host suitability (CPU/RAM/disk/OS/ports) |
| **Execute** | Snapshot, maintenance, toolkit update, refresh |

Listen:

- **Host tip** (`RPCNODE_PUBLIC_PORT`, default `:38990`) — one control-plane port for the host (`/healthz`, `/api/v1`). Frozen after host install. Must not equal a leaf public/agent catalog port. Add/Install node never changes it.
- **TRON leaf public** `:39090` — Go RPC → `127.0.0.1:18090` (clients).
- **TRON leaf Agent API** `:39190` — panel status / lifecycle (`TRON_AGENT_PORT` / `RPCNODE_AGENT_PORT`).
- Tip `TRON_AGENT_PORT=0` — agent JSON shares the tip listen (not a second port).

```bash
# On the node server
curl -s http://127.0.0.1:38990/healthz
curl -H "Authorization: Bearer $AGENT_API_TOKEN" http://127.0.0.1:38990/api/v1/node
curl -s http://127.0.0.1:39090/wallet/getnowblock | head -c 200
```

Install host agent from RpcNode (no network/env at install time):

```bash
curl -fsSL "${AGENT_DOWNLOAD_URL:-https://toolkit.rpcnode.dev/install/agent.sh}" | sudo bash
```

Choose network + env later in the panel when **adding a node**.
Panel UI: dashboard → **Add agent** (modal: install → optional host checks → agent key).

## Auth model

```text
Human operator  →  PANEL :8093
  └─ /setup-password or /login → cookie rpcnode_session
     or Authorization: Bearer <token> (TTL 30d; ./scripts/panel-token.sh)
  └─ forgot password → docker exec rpcnode-panel rpcnode-panel passwd <login>

Panel → host tip :38990 (Servers `agent_url`) or leaf Agent API :39190
  └─ AGENT_API_TOKEN (Bearer / X-Api-Token) on /api/*

Public RPC clients → leaf Go RPC :39090
  └─ chain RPC paths (no panel login, no agent key)
```

## Local UI routes (panel only)

| Route | Role |
|-------|------|
| `/setup-password` | First-run create admin |
| `/login` | Human sign-in |
| `/` | Nodes dashboard |
| `/install` | Opens dashboard + Add agent modal |
| `/nodes/:id` | Network-specific ops UI (proxied to agent) |
| `/api/auth/*` | Panel auth |
| `/api/nodes` | Panel-owned agent registry |

Legacy `/status/*` bookmarks redirect into the routes above **on the panel**.

## What runs where

| Host | Processes | Ports |
|------|-----------|-------|
| **Mac / control** | `rpcnode-panel` | `:8093` SPA + panel APIs |
| **Node server** | `api-agent` + `system-agent` (+ chain client) | public RPC + agent API ports |

## Migration path

```text
Phase 0 (now)     Standalone panel binary/compose; node agent stripped of SPA
Phase 1           Multi-agent registry UX; panel drives remote install/status
Phase 2           Central SaaS / SSO; local panel remains optional control surface
Phase 3           Shared host-agent protocol across networks (TRON template first)
```

## Non-goals (this slice)

- Embedding the SPA back into `api-agent`
- Requiring the panel to run on the same machine as the chain client
- Multi-tenant billing
