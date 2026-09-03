# RpcNode CDN public site

Next.js (App Router) SSR front for the Snapshot CDN. Reads
`$SNAPSHOT_CDN_DIR/snapshots/index.json` written by `rpcnode-cdn.jar`. Does not
sync archives — nginx should still serve `/snapshots/*` from disk.

## Requirements

- Node.js ≥ 22.12 (host run) **or** Docker
- `SNAPSHOT_CDN_DIR` (same root the JAR uses)

## Docker (recommended)

```bash
cd cdn-site
export SNAPSHOT_CDN_DIR=/data/rpcnode-cdn   # host path, same as the JAR
./scripts/docker-up.sh
# or: docker compose up -d --build
```

- **8095** — nginx (HTML + `/snapshots/*`)
- **7090** — Next.js directly (optional)

Compose mounts `$SNAPSHOT_CDN_DIR` into the site at `/data` and the snapshots
tree into nginx. Create an empty tree first if needed:

```bash
mkdir -p "$SNAPSHOT_CDN_DIR/snapshots"
```

## Host run (without Docker)

```bash
cd cdn-site
npm ci
npm run build
npm start   # prompts for SNAPSHOT_CDN_DIR (default: cwd) if unset
```

Config is written to `/etc/rpcnode/rpcnode-cdn-site.env` when writable, otherwise
`./rpcnode-cdn-site.env`. Key: `SNAPSHOT_CDN_DIR`. Sitemap/canonical use the
request `Host` header (no separate origin env).

Listens on **7090**. Put nginx in front (see `deploy/nginx-cdn/`).

## Routes

| Path | Purpose |
|------|---------|
| `/` | Network index |
| `/networks/{network}` | Snapshots for one network (download) |
| `/mirrors/{network}/{env}/{type}` | Single mirror detail |
| `/snapshots/...` | Archive files (nginx) |
