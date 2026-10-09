# Network intake — Hyperliquid

Filled from Go sibling `../toolkit-go/internal/networks/hyperliquid/` and
[hyperliquid-dex/node](https://github.com/hyperliquid-dex/node) README.

| Field | Answer |
|---|---|
| Network id | `hyperliquid` |
| Display label | Hyperliquid |
| Author | toolkit (port from Go facts) |
| Date | 2026-09-02 |
| Status | **implemented** |

**Go sibling:** facts / hl-visor CDN binary / HOME=`nodeop` + `~/hl` workdir / gossip peers /
EVM RPC on `:3001/evm` / `oneEnvPerHost` (hl-node process-name singleton). Kotlin keeps
bins + configs + `hl/` workdir under **node_dir** only (`HOME={{node_dir}}`). Do not edit or run Go.

---

## Review table

| Section | Status | Notes |
|---|---|---|
| 1 Product scope | OK | Non-validator full RPC (hl-visor) |
| 2 Environments | OK | mainnet (999), testnet (998) |
| 3 Artifacts | OK | Rolling CDN `hl-visor` + GPG `pub_key.asc` |
| 4 Ports | OK | http 3001 + gossip 4001/4002 (binary-fixed) |
| 5 Disks | OK | single `chain` NVMe role |
| 6 Client config | OK | `format: flags` — unit CLI; LimitNOFILE |
| 7 Snapshot | OK | `never` — node streams from peers |
| 8–9 Start / proc | OK | node_dir HOME + hl/ workdir + gossip JSON |
| 10–11 Height / tip | OK | `eth_blockNumber` on `/evm` |
| 12–13 Lifecycle / options | OK | sync→active; no install options |
| **Overall** | Approved | |

---

## 1. Product scope

1. **Why:** Operators run Hyperliquid non-validator RPC (HyperEVM + local info).
2. **MVP:** Clients download rolling `hl-visor`, disks, ports, gossip config, start unit,
   height on `/evm`, public tip lag. **Host layout:** everything under **node_dir**
   (`HOME=node_dir`, workdir `node_dir/hl`). **Out of scope:** validator duties, toolkit CDN
   snapshot mirror, Docker.
3. **Pin-only?** No — public CDN binary URL.
4. **One env per host?** `true` — hl-node panics if another `hl-node` process exists.

---

## 2. Environments

| Env id | Label | Production? | Notes |
|---|---|---|---|
| `mainnet` | Hyperliquid Mainnet | yes | chain id 999, ChainName Mainnet |
| `testnet` | Hyperliquid Testnet | test | chain id 998, ChainName Testnet |

Same binary family; network via `visor.json` `chain` field + CDN URL host.

---

## 3. Client binary & artifacts

1. **Upstream:** rolling CDN (no GitHub release tag).
   - mainnet: `https://binaries.hyperliquid.xyz/Mainnet/hl-visor`
   - testnet: `https://binaries.hyperliquid-testnet.xyz/Testnet/hl-visor`
2. **Latest:** HEAD Last-Modified + ETag → version `yyyy-MM-dd-<etag8>`
   (`HyperliquidClientReleaseResolver`).
3. Stable name: `hl-visor` (bare binary, both arches — upstream ships one Linux binary).
4. Config: `pub_key.asc` from `hyperliquid-dex/node` (GPG verify / auto-upgrade).
5. Program id: `hl-visor`.
6. Requirements: `logFile: logs/hl-visor.log`.
7. **No Docker.**

---

## 4. Fixed ports

| Env | Role | Port | Notes |
|---|---|---|---|
| * | http | 3001 | HyperEVM `/evm` + info `/info` (binary-fixed) |
| * | p2p | 4001 | Gossip (must be public) |
| * | p2p2 | 4002 | Gossip aux (must be public) |

Height uses **http** → `POST http://127.0.0.1:3001/evm` `eth_blockNumber`.
Same ports for both envs — safe because `oneEnvPerHost`.

---

## 5. Disks & sizing

| Role | Media | Leaf |
|---|---|---|
| chain | nvme | hl data (`hl/hyperliquid_data` → role dir) |

| Env | diskHint / full | CPU | RAM |
|---|---|---|---|
| mainnet | 1024 | 8 | 32 |
| testnet | 512 | 4 | 16 |

Vendor non-validator hint is ~500 GB SSD; ops keep Go diskHintGiB.

---

## 6. Client config

`format: flags`. Bindings:

| Key | Source | Applied |
|---|---|---|
| datadir | disk_role_dir chain | symlink `hl/hyperliquid_data` |
| http-port | catalog_port http | reserved / UI (binary listens 3001) |
| p2p-port | catalog_port p2p | reserved / UI |
| LimitNOFILE | literal 1048576 | systemd unit |

Exec flags (unit): `run-non-validator --replica-cmds-style actions --serve-eth-rpc --serve-info`.

---

## 7. Snapshot

`never` for both envs — node bootstraps by streaming from gossip peers (no toolkit aria2).

---

## 8–9. Start / proc

- Ensure `{node_dir}/bin/hl-visor` (synced bare binary)
- Import `pub_key.asc` via gpg when present (best-effort)
- `{node_dir}/hl/` workdir + `hyperliquid_data` → chain role
- Write `visor.json` + `override_gossip_config.json` (live peers + seeds)
- Unit `rpcnode-hyperliquid-<env>`: `HOME=node_dir`, `WorkingDirectory=node_dir/hl`
- **Never** write `/opt/hyperliquid` or `/etc/hyperliquid`

---

## 10–11. Height / tip

- Local: `eth_blockNumber` on `http://127.0.0.1:{http}/evm`
- Tip: same call against YAML `publicTip.urls`
  (`https://rpc.hyperliquid.xyz/evm` / testnet twin)

---

## 12–13. Lifecycle / options

sync → active on tip lag. No install options.
