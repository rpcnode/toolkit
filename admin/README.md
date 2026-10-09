# Toolkit Admin

React + Vite + Mantine. First-run setup asks for the **server** (`http://host:8094`),
probes `/healthz`, then sets the admin password. `VITE_API_URL` is optional
(see `.env.example`).

```
VITE_API_URL=                        # same origin; Vite proxies to :8094
VITE_API_URL=http://127.0.0.1:8094   # skip the origin prompt (local Ktor)
```

```bash
cp .env.example .env   # if you do not have .env yet
npm install && npm run dev
# http://127.0.0.1:5173/
```

The server is started from IntelliJ (not `./gradlew run`). Ktor must allow the Vite origin (`PANEL_CORS_ORIGINS`, default `http://127.0.0.1:5173` and `http://localhost:5173`).

## Run without Docker (pm2)

`server.mjs` serves `dist/` and proxies `/api`, `/install`, `/healthz` to the panel
(`PANEL_URL`, default `http://127.0.0.1:8094`), listening on `0.0.0.0:8093`:

```bash
npm ci && npm run build
pm2 start ecosystem.config.cjs && pm2 save
```

Update: `git pull && npm ci && npm run build` (no restart needed — `dist/` is read per request).
Restart only after changing `server.mjs`, `ecosystem.config.cjs` or the env:
`pm2 restart rpcnode-admin --update-env`.

See "Admin UI without Docker (pm2)" in the root README (firewall, first-run origin, CORS).
