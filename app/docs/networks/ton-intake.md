# Network intake — Toncoin (TON)

Filled from Go sibling `../toolkit-go/internal/networks/ton/` and admin helpers.

| Field | Answer |
|---|---|
| Network id | `ton` |
| Display label | Toncoin |
| Author | toolkit (port from Go facts) |
| Date | 2026-09-02 |
| Status | **implemented** (operator request — full MyTonCtrl port) |

**Go sibling:** MyTonCtrl liteserver via `install.sh`, host-global `/var/ton-work`, stock units `validator` / `mytoncore` / `ton_http_api`. Kotlin keeps rpcnode scripts, markers, and primary units under **node_dir**; bootstrap creates `/var/ton-work` → blockchain disk (documented exception — MyTonCtrl cannot run node_dir-only). Do not edit or run Go.

---

## Review table

| Section | Status | Notes |
|---|---|---|
| 1 Product scope | OK | Liteserver + TON HTTP API (not validator staking) |
| 2 Environments | OK | mainnet, testnet; oneEnvPerHost |
| 3 Artifacts | OK | Pin-only — MyTonCtrl install.sh (no CDN tarball) |
| 4 Ports | OK | http (THA) + p2p |
| 5 Disks | OK | blockchain (NVMe) + archive (SSD) |
| 6 Client config | OK | `format: flags` — bindings for Start preview |
| 7 Snapshot | OK | `never` — dump/archive via install options |
| 8–9 Start / proc | OK | bootstrap oneshot → stock units |
| 10–11 Height / tip | OK | getMasterchainInfo seqno; tip via toncenter |
| 12–13 Lifecycle / options | OK | sync→active; history dump\|archive |
| **Overall** | Ready / implemented | |

---

## 1. Product scope

1. **Why:** Operators run Toncoin liteserver RPC (MyTonCtrl + TON HTTP API).
2. **MVP:** pin-only enable, JBOD disks, ports, history option, MyTonCtrl bootstrap, start units, push seqno height, public tip lag via toncenter. **Host layout:** rpcnode scripts/markers under **node_dir**; `/var/ton-work` symlink required by MyTonCtrl.
   **Out of scope:** validator staking duties, Docker, SelfHeal OOM tick, public JSON-RPC rewrite proxy.
3. **Pin-only?** Yes — no public toolkit tarball; host `install.sh` from `ton-blockchain/mytonctrl`. Listed in `NetworkPinOnly`.
4. **One env per host?** `true` (`mytonctrl_global_workdir`).

---

## 2. Environments

| Env id | Label | Production? | Notes |
|---|---|---|---|
| `mainnet` | Toncoin Mainnet | yes | `-n mainnet` |
| `testnet` | Toncoin Testnet | test | `-n testnet` |

Same install path; chain flag selects network.

---

## 3. Client binary & artifacts

1. **Upstream:** MyTonCtrl `https://raw.githubusercontent.com/ton-blockchain/mytonctrl/master/scripts/install.sh` (`-m liteserver`).
2. **Latest:** host installer owns versions (pin reference `v2.18.0` in clients.yml for UI).
3. Artifacts: **none** (pin-only).
4. Program id: `validator-engine`.
5. Requirements: `logFile: logs/ton.log`.
6. **No Docker.**

---

## 4. Fixed ports

| Env | Role | Port |
|---|---|---|
| mainnet | http | 8081 |
| mainnet | p2p | 31310 |
| testnet | http | 8082 |
| testnet | p2p | 31311 |

Height/tip use **http** (TON HTTP API). P2P = validator listen.

---

## 5. Disks & sizing

| Role | Media | Leaf |
|---|---|---|
| blockchain | nvme | `/var/ton-work` target (db) |
| archive | ssd | archive / aux |

| Env | diskHint / full | archiveGiB | CPU | RAM |
|---|---|---|---|---|
| mainnet | 1024 | 12288 | 8 | 32 |
| testnet | 256 | 4096 | 4 | 16 |

---

## 6. Client config

`format: flags` — Start preview bindings; bootstrap/unit apply the same literals:

| Key | Source | Applied |
|---|---|---|
| blockchain | disk_role_dir blockchain | workdir symlink |
| archive | disk_role_dir archive | aux |
| http | catalog_port http | THA port |
| p2p | catalog_port p2p | VALIDATOR_PORT |
| history | install_option history | `-d` / `--archive` |
| LimitNOFILE | literal 4194304 | validator drop-in + unit |
| archive_ttl | literal 2592000 | env / meta |
| state_ttl | literal 86400 | env / meta |
| fs.nr_open | literal 8388608 | sysctl |

---

## 7. Snapshot

`never` for both envs. Dump (~30d) or archive is MyTonCtrl install flavor, not toolkit aria2.

---

## 8–9. Start / proc

- Write `{node_dir}/bin/rpcnode-ton-{bootstrap,node-start}.sh`
- Units: `rpcnode-ton-{env}-bootstrap.service` (oneshot) + `rpcnode-ton-{env}.service`
- Node-start: if no `.toolkit/bootstrap.done` → start bootstrap; else start `validator` / `mytoncore` / THA
- Symlink `/var/ton-work` → blockchain disk role

---

## 10–11. Height / tip

- Local: `GET http://127.0.0.1:{http}/getMasterchainInfo` (fallback `/api/v2/…`) → `result.last.seqno`
- Tip: same parse against YAML `publicTip.urls` (toncenter)
- Optional: local oos may set `syncing` on height reading

---

## 12–13. Lifecycle / options

sync → active on tip lag ≤ 3. Install option group `history` default `dump` (`archive` → `--archive`).

---

## Indexer database (changed 2026-10-15)

The v3 indexer no longer shares a PostgreSQL server with the rest of the host. A new install creates a **PostgreSQL cluster of its own** for
the node (`pg_createcluster`, no Docker): data **and WAL** in `<indexer dir>/pgcluster` (the indexer disk role), loopback-only on the
catalog port `indexer_pg` (mainnet 15432, testnet 15433), tuned for the load, named `rpcnode-ton-<env>`
(systemd `postgresql@<version>-rpcnode-ton-<env>`). The host's other servers are never touched; if PostgreSQL was not installed the packages
are installed and the stock `main` cluster that the package creates is dropped again. Removing the node drops the cluster and its directory.

Why: with the earlier layout (role + database + **tablespace** on the indexer disk in the host's shared server) only tables and indexes went
to the indexer disk. The WAL stayed in the shared server's data directory (on `/`), so every commit's fsync and up to `max_wal_size = 16GB` of
WAL landed on the system disk (measured on a production host: pg_wal 17 GB on a consumer SSD, ~56 ms write latency, 80 % utilisation).

A node installed with the earlier layout (`ton-indexer/pg.env` contains `PG_TS=`) **keeps working unchanged**: it is not migrated. To get its
WAL off the system disk without a migration use `scripts/move-postgres-wal.sh`. Not done: the same change for the Cardano db-sync and
Polkadot history indexers, which still use `chains/default/postgres-functions.sh` (tablespace on the shared server).
Tests: `TonIndexerScriptTest`, `TonIndexerClusterTest` (runs the real `provision_database` against stand-ins for `pg_createcluster`, `psql`, `apt-get`).
