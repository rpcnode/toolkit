# Agent install — CDN / archive only (no git)

**Agent install** puts **runtime artifacts** on the node host. It never `git clone`s the toolkit and never unpacks `.go` sources.

**Node client install** (java-tron, bitcoind, mytonctrl, …) is separate: provision may still download **vendor** binaries/scripts. That is not “agent sources”.

## What runs on the server

| Path | Role |
|---|---|
| `/opt/rpcnode/bin/rpcnode-api-agent` | Tip + leaf shared binary |
| `/opt/rpcnode/bin/rpcnode-system-agent` | Tip + leaf shared binary |
| `/opt/rpcnode/bin/rpcnode-agent-watchdog` | Host-wide tip+leaf supervisor |
| systemd `rpcnode-*-agent*.service` + `rpcnode-agent-watchdog.service` | Autostart |
| `/var/log/rpcnode/*.log` | Tip/leaf/watchdog agent stdout/stderr (file) |
| `/etc/logrotate.d/rpcnode-agents` | Rotate when file ≥ **100M** (7 copies, `copytruncate`) |

Version truth = **embedded** in the binary (`rpcnode-api-agent -version`). CDN `TOOLKIT_VERSION` is the update channel only.

## CDN layout

```text
https://toolkit.rpcnode.dev/install/agent.sh
https://toolkit.rpcnode.dev/install/rpcnode-agent-watchdog.sh
https://toolkit.rpcnode.dev/install/TOOLKIT_VERSION
https://toolkit.rpcnode.dev/install/VERSIONS.json
https://toolkit.rpcnode.dev/versions/
https://toolkit.rpcnode.dev/install/binaries/rpcnode-*-agent-<os>-<arch>
https://toolkit.rpcnode.dev/install/binaries/sha256sums.txt
https://toolkit.rpcnode.dev/install/archives/rpcnode-agent-<VERSION>.tar.gz
https://toolkit.rpcnode.dev/install/archives/rpcnode-agent-latest.tar.gz
```

Archive contents (runtime only):

- `agent.sh`
- `rpcnode-agent-watchdog.sh`
- `TOOLKIT_VERSION`
- `binaries/` (api + system agents for all published os/arch + `sha256sums.txt`)

❌ No `api-agent/*.go`, ❌ no `.git`, ❌ no panel / status-ui.

## Publish (CI or laptop)

Origin is **`frontend/toolkit`** — same host as clients (`toolkit.rpcnode.dev`):

```text
frontend/toolkit/public/install/          →  https://toolkit.rpcnode.dev/install/
  agent.sh
  uninstall-agents.sh
  rpcnode-agent-watchdog.sh
  TOOLKIT_VERSION
  binaries/
  archives/
frontend/toolkit/public/install/clients/  →  /install/clients/ and /clients/ (alias)
```

`rpcnode.dev/install/…` must **serve** the file (connect proxies to toolkit, HTTP 200 — not a 301). Old agents still request the connect host; a 404 there breaks Servers → Update. Agent ≥ 0.4.187 rewrites the host to `toolkit.rpcnode.dev`.

❌ Marketing `/toolkit` page is `frontend/site` — not this CDN.

```bash
# from toolkit repo — stage + commit frontend/toolkit + git push (no rsync)
./scripts/release.sh publish
# or:
./scripts/build-agent-binaries.sh
./scripts/publish-install.sh
./scripts/publish-install.sh --local-only   # stage only
./scripts/publish-install.sh --no-push      # commit, no push
```

Toolkit CDN deploy from that git push serves `https://toolkit.rpcnode.dev/install/agent.sh`.

Override local dest: `CONNECT_PUBLIC_INSTALL=/path/to/public/install`.

## Install on a server (no git)

### Online (preferred)

```bash
curl -fsSL "https://toolkit.rpcnode.dev/install/agent.sh" | sudo bash
```

Fetches matching binaries + watchdog from CDN, writes systemd units, enables tip agents + watchdog, installs **file logging** drop-ins + logrotate.

### Offline / air-gap (scp archive)

```bash
# on build machine after publish (or after build+pack):
scp dist/archives/rpcnode-agent-VERSION.tar.gz root@NODE:/tmp/

# on node:
cd /tmp && tar -xzf rpcnode-agent-VERSION.tar.gz
sudo LOCAL_ARTIFACT_DIR=/tmp/rpcnode-agent-VERSION bash /tmp/rpcnode-agent-VERSION/agent.sh
```

## Panel Update (same artifacts)

**Servers → Update agent** → tip `POST /api/v1/agent/update`:

1. Downloads CDN binaries into `/opt/rpcnode/bin`
2. Installs/enables `rpcnode-agent-watchdog` from CDN
3. Ensures `/var/log/rpcnode` + logrotate + per-unit `file-log.conf` drop-ins
4. Restarts tip + leaf agent units

Same channel as fresh install — still **no git**.

### Agent logs on the host

```bash
ls -lah /var/log/rpcnode/
tail -f /var/log/rpcnode/rpcnode-api-agent.log
tail -f /var/log/rpcnode/rpcnode-api-agent-bitcoin-mainnet.log
```

Rotation is **by size** (`100M`), not only by day — `copytruncate` so open FDs stay valid.

## Agent install vs node client

| | Agent install / Update | Node client (provision) |
|---|---|---|
| Source | CDN / local archive | Vendor release URLs (GitHub, etc.) |
| On disk | `/opt/rpcnode/bin/*` | `/opt/<network>/…`, jars, myton, … |
| Git clone toolkit? | **Never** | Never (clients may use vendor installers) |

## Related

- Watchdog / tip durability: [`docs/agent-invariants.md`](./agent-invariants.md) §4a
- Remote tip Update after publish: `.cursor/rules/rpcnode-agent-remote-update.mdc`
- Add network CDN bump: [`docs/add-network.md`](./add-network.md)
