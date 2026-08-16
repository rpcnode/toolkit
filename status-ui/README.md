# RpcNode status-ui (React + Mantine)

Ops console — AppShell with Servers (host agents) vs Nodes (chain workloads), install wizard, live metrics.  
**Served by the standalone `panel` service** (not by the node `api-agent`).

Routes: `/` (dashboard), `/servers`, `/nodes`, `/nodes/:id`, `/settings`, `/install`.

## Stack

- Vite + React + TypeScript
- **Mantine** + `@mantine/charts`
- Embedded into Go `panel/` (`rpcnode-panel`) at `/`

## Dev

```bash
cd .. && ./scripts/up-panel.sh
cd status-ui && npm install && npm run dev
# http://127.0.0.1:5173/  (proxy → panel :8093)
```

## Build into panel

The SPA is compiled in Docker and embedded into `rpcnode-panel`. Do not commit `panel/ui` or `dist/`.

```bash
docker compose -f docker-compose.panel.yml up -d --build --pull never
# or from toolkit root:
./scripts/rebuild-panel.sh
```
