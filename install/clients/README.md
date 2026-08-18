# Chain client update channel

Primary source of truth is the **vendored CDN catalog** (RpcNode.app → `frontend/toolkit/public/clients`):

```text
GET {CLIENTS_BASE_URL}/clients/<network>/<env>/manifest.json
```

Default `CLIENTS_BASE_URL` is `https://toolkit.rpcnode.dev`. Agent does **not** invent jar/conf URLs when this file exists. Agent binaries: `https://toolkit.rpcnode.dev/install`.

| field | meaning |
|-------|---------|
| `artifact_url` | linux x86_64 client |
| `artifact_url_aarch64` | linux aarch64 client (`GOARCH` pick) |
| `conf_url` | official config (or first `files[]` with `role=config` → `{base}/clients/…/conf/<name>`) |

Used for **provision install**, **check**, and **apply**.

Host overrides still win: `TRON_TAG`, `TRON_JAR_URL`, `TRON_CONFIG_URL`.

GitHub / pin is **fallback only** if CDN miss:

| network | fallback |
|---------|----------|
| tron mainnet / shasta | GitHub `tronprotocol/java-tron` `GreatVoyage-v*` → `FullNode.jar`; pin `GreatVoyage-v4.8.2.1`. Shasta conf = stock GreatVoyage `config.conf` (official Shasta does not support joining). |
| **tron nile** | **`tron-nile-testnet/nile-testnet` PQ jar** (`GreatVoyage-Nile-v4.8.2.1-PQ1-build1`) + `config-nile.conf`. Not stock GreatVoyage. Not tron-docker `nile_net_config.conf`. |
| other networks | error until `clients/<net>/<env>/manifest.json` is published |

## Local vendor cache (fetch script)

`install/clients/catalog.json` lists official client + config URLs for every shipped network/env.

macOS UI (RpcNode.app — клиенты + выпуск агента):

```bash
./scripts/open-rpcnode.sh
```

Таблица **сейчас / новая**. Скачивание пишет в `frontend/toolkit/public/clients/<сеть>/<env>/`. После деплоя `frontend/toolkit` агент качает с `https://toolkit.rpcnode.dev/clients/…`. См. `tools/FetchClients/README.md`.

Терминал:

```bash
./scripts/fetch-clients.sh --network tron --yes
./scripts/fetch-clients.sh --dry-run
```

Layout after a fetch:

```text
# toolkit repo (git): catalog + conf templates only — dist/ ignored
install/clients/catalog.json
install/clients/<network>/<env>/conf/

# toolkit CDN repo (git + deploy):
frontend/toolkit/public/clients/<network>/<env>/VERSION
frontend/toolkit/public/clients/<network>/<env>/manifest.json
frontend/toolkit/public/clients/<network>/<env>/conf/
frontend/toolkit/public/clients/<network>/<env>/dist/   # jars / tarballs — publish these
```

Snapshots are **not** fetched here (lifecycle Snapshot step). Apt clients (geth, stellar-rpc, rippled) are recorded, not downloaded.
