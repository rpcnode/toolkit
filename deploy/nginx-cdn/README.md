# Snapshot CDN (host nginx)

Public origin for mirrored node snapshots. The sync JAR is **panel-independent**:
you pick a download disk (`SNAPSHOT_CDN_DIR`), then network/env in the menu; the
daemon mirrors official archives under `<SNAPSHOT_CDN_DIR>/snapshots/`. nginx
serves the **Next.js** public site (HTML) and those archive files from disk.

## Enable

```bash
./scripts/build-rpcnode-cdn.sh 0
# on the CDN host:
sudo java -jar rpcnode-cdn.jar install   # asks which disk to use
# add what to mirror (TTY menu):
sudo java -jar /opt/rpcnode/lib/rpcnode-cdn.jar menu
sudo systemctl restart rpcnode-cdn

# public site via Docker (Next :7090 + nginx :8095)
export SNAPSHOT_CDN_DIR=/data/rpcnode-cdn   # same root as the JAR
cd cdn-site && ./scripts/docker-up.sh
# or: docker compose up -d --build

# host alternative (no Docker):
#   cd cdn-site && npm ci && npm run build && npm start   # :7090
#   then host nginx from deploy/nginx-cdn/snapshots.conf
```

In the panel Settings → **Snapshot CDN**, set origin to `http://<host>:8095`.

Legacy static files under `deploy/nginx-cdn/www/` remain as a fallback reference;
operators should run the Next app as the public site.

### Commands

```bash
sudo java -jar rpcnode-cdn.jar install   # pick disk + /opt/rpcnode + systemd
sudo java -jar rpcnode-cdn.jar uninstall # remove unit + jar (keeps snapshots)
java -jar rpcnode-cdn.jar                # daemon (SNAPSHOT_CDN_DIR from env)
java -jar rpcnode-cdn.jar menu           # targets + change download directory
java -jar rpcnode-cdn.jar status         # version, download %, size table
java -jar rpcnode-cdn.jar help

# site (Docker)
cd cdn-site && ./scripts/docker-up.sh

# site (host Node)
cd cdn-site && npm ci && npm run build && npm start   # :7090
```

Layout on disk (example `SNAPSHOT_CDN_DIR=/data/rpcnode-cdn`):

```
/opt/rpcnode/lib/rpcnode-cdn.jar
/etc/rpcnode/rpcnode-cdn.env              # SNAPSHOT_CDN_DIR=…
/etc/rpcnode/rpcnode-cdn-site.env         # site: SNAPSHOT_CDN_DIR
/etc/rpcnode/rpcnode-cdn.targets.json
/data/rpcnode-cdn/snapshots/<network>/<env>/<type>/VERSION
/data/rpcnode-cdn/snapshots/<network>/<env>/<type>/<filename>
/data/rpcnode-cdn/snapshots/index.json
```

Official mirror recipes ship in the JAR (`cdn/mirrors.json`). Sync polls the local
targets file; `CDN_POLL_SEC` (default 60 / install 3600) picks up menu changes;
`CDN_DOWNLOAD_JOBS` (default 4) caps parallel fetches. A dropped GET keeps the
`.tmp` and resumes with `Range`.

Public **Download** links go through the Next site (`/api/download/…`), which
increments `snapshots/<network>/<env>/<type>/downloads.json` and redirects to
the archive. `rpcnode-cdn status` shows a **DOWNLOADS** column from that file.
Direct `/snapshots/…` hits (e.g. PreferCdn agents) are not counted.
