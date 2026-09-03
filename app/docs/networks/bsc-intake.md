# Network intake — BNB Smart Chain (BSC)

Filled from Go sibling `../toolkit-go/internal/networks/bsc/` and admin helpers.

| Field | Answer |
|---|---|
| Network id | `bsc` |
| Display label | BNB Smart Chain |
| Author | toolkit (port from Go facts) |
| Date | 2026-09-02 |
| Status | **implemented** (operator request to add BNB) |

**Go sibling:** facts / ports / Parlia geth fork / official `bnb-chain/bsc-snapshots` oneshot. Do not edit or run Go.

---

## Review table

| Section | Status | Notes |
|---|---|---|
| 1 Product scope | OK | Full RPC node (bsc-geth, Parlia in-process) |
| 2 Environments | OK | mainnet, testnet (chapel alias) |
| 3 Artifacts | OK | `bnb-chain/bsc` geth binary + genesis zip |
| 4 Ports | OK | p2p + http (catalog-fixed) |
| 5 Disks | OK | chaindata (NVMe) + snapshots (SSD) |
| 6 Client config | OK | `format: flags` — systemd CLI + config.toml |
| 7 Snapshot | OK | `required` — official multi-part fetch-snapshot.sh |
| 8–9 Start / proc | OK | Template `resources/chains/bsc/node.service.tmpl` |
| 10–11 Height / tip | OK | `eth_blockNumber` JSON-RPC |
| 12–13 Lifecycle / options | OK | sync→active; snapshot=pruned\|full |
| **Overall** | Ready to implement | |

---

## 1. Product scope

1. **Why:** Operators run BNB Smart Chain full RPC nodes (geth fork, Parlia — no separate CL).
2. **MVP:** download client + genesis zip, disks, ports, official snapshot ExtraStep, start unit, push height, public tip lag, pruned vs full ancient picker. **Out of scope:** validator duties, Erigon, unofficial mirrors.
3. **Pin-only?** No — artifacts under `chains/bsc/clients.yml`.
4. **One env per host?** `false`.

---

## 2. Environments

| Env id | Label | Production? | Notes |
|---|---|---|---|
| `mainnet` | BNB Smart Chain Mainnet | yes | chain id 56 |
| `testnet` | BNB Smart Chain Testnet | test | chain id 97; `chapel` aliases testnet |

Same binary; network via genesis/config zip from release.

---

## 3. Client binary & artifacts

1. **Upstream:** GitHub `bnb-chain/bsc` — `geth_linux` / `geth-linux-arm64` + `mainnet.zip` / `testnet.zip`.
2. **Latest:** newest GitHub release (catalog pin ≥ v1.7.2 for current snaps).
3. Stable names: `geth_linux` (both arches), zip as `mainnet.zip` / `testnet.zip`.
4. Program id: `geth`.

---

## 4. Fixed ports

| Env | Role | Port |
|---|---|---|
| mainnet | p2p | 30311 |
| mainnet | http | 8575 |
| testnet | p2p | 30312 |
| testnet | http | 8576 |

Height uses **http** role (`eth_blockNumber`).

---

## 5. Disks & sizing

| Role | Media | Leaf |
|---|---|---|
| chaindata | nvme | geth `--datadir` (creates `<datadir>/geth/`) |
| snapshots | ssd | CSV / aria2 parts workspace (`fetch-snapshot.sh -D`) |

| Env | diskHint / full | archive (full ancient) | CPU | RAM |
|---|---|---|---|---|
| mainnet | 2048 | 6600 | 8 | 32 |
| testnet | 400 | 440 | 4 | 16 |

---

## 7. Snapshot

`required` for mainnet/testnet — official `bnb-chain/bsc-snapshots` via `fetch-snapshot.sh` (multi-part CSV). Not toolkit single-tarball aria2. Resolver returns `bsc-official://` sentinel; agent runs heal script.

| Type id | Kind | Default | Hint |
|---|---|---|---|
| pruned | full | yes | pruneancient (~1.7 TB mainnet / ~180 GB testnet) |
| full | archive | no | full ancient (~6.6 TB / ~440 GB) |

---

## 8–9. Start / proc

- Unit `rpcnode-bsc-<env>` from `resources/chains/bsc/node.service.tmpl`.
- Install binary as `geth`, unzip genesis/config under `/etc/bsc/<env>/`, `geth init` when chaindata missing.
- Flags: `--syncmode full --gcmode full --tries-verify-mode none` + http/parlia APIs.

---

## 10–11. Height / tip

- Local: `POST http://127.0.0.1:{http}/` → `eth_blockNumber`.
- Tip: publicnode BSC URLs in YAML (same JSON-RPC).

---

## 13. Install options

`snapshot=pruned|full` (admin `BSC_SNAPSHOT_OPTIONS`).
