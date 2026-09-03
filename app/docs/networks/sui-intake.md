# Network intake — Sui

Filled from Go sibling `../toolkit-go/internal/networks/sui/` and admin helpers.

| Field | Answer |
|---|---|
| Network id | `sui` |
| Display label | Sui |
| Author | toolkit (port from Go facts) |
| Date | 2026-09-02 |
| Status | **implemented** (operator request to add sui) |

**Go sibling:** facts / formal R2 snapshot via `sui-tool` / `sui-node --config-path` / checkpoint metrics+RPC tip. Kotlin keeps binaries + `fullnode.yaml` + genesis under **node_dir** only (never `/opt/sui` or `/etc/sui`). Do not edit or run Go.

---

## Review table

| Section | Status | Notes |
|---|---|---|
| 1 Product scope | OK | Full-history fullnode RPC (sui-node) |
| 2 Environments | OK | mainnet, testnet |
| 3 Artifacts | OK | MystenLabs/sui ubuntu tgz + genesis.blob |
| 4 Ports | OK | http + metrics + p2p |
| 5 Disks | OK | state (NVMe db) + index (SSD) |
| 6 Client config | OK | `format: flags` — generated fullnode.yaml |
| 7 Snapshot | OK | `required` — formal R2 via sui-tool (not aria2) |
| 8–9 Start / proc | OK | Extract under node_dir → unit → sui-node |
| 10–11 Height / tip | OK | checkpoint metrics + `sui_getLatestCheckpointSequenceNumber` |
| 12–13 Lifecycle / options | OK | sync→active; single formal snapshot type |
| **Overall** | Ready to implement | |

---

## 1. Product scope

1. **Why:** Operators run Sui fullnode RPC (MystenLabs sui-node).
2. **MVP:** Clients download (tarball + genesis), JBOD disks, ports, formal snapshot ExtraStep (`sui-tool download-formal-snapshot`), start unit, push checkpoint height, public tip lag. **Host layout:** bins, yaml, genesis, logs under **node_dir** only.
   **Out of scope:** validator / staking duties, Docker, Go `/opt`/`/etc` provision paths.
3. **Pin-only?** No — artifacts under `chains/sui/clients.yml`.
4. **One env per host?** `false` (catalog ports offset per env).

---

## 2. Environments

| Env id | Label | Production? | Notes |
|---|---|---|---|
| `mainnet` | Sui Mainnet | yes | tags `mainnet-v*` |
| `testnet` | Sui Testnet | test | tags `testnet-v*` |

Same binary family; network via genesis + `--network` for formal snapshot + yaml archive URL.

---

## 3. Client binary & artifacts

1. **Upstream:** GitHub `MystenLabs/sui` — `sui-{tag}-ubuntu-{x86_64\|aarch64}.tgz`.
2. **Latest:** newest release with tag prefix `mainnet-` / `testnet-`.
3. Stable names: `sui-ubuntu-x86_64.tgz` / `sui-ubuntu-aarch64.tgz`.
4. Config: `genesis.blob` from `MystenLabs/sui-genesis` (`…/{env}/genesis.blob`).
5. Program id: `sui-node`. Also ships `sui-tool` (formal snapshot) from the same tarball.
6. Requirements: `logFile: logs/sui.log`.
7. **No Docker.**

---

## 4. Fixed ports

| Env | Role | Port |
|---|---|---|
| mainnet | http | 9000 |
| mainnet | metrics | 9184 |
| mainnet | p2p | 8084 |
| testnet | http | 9001 |
| testnet | metrics | 9185 |
| testnet | p2p | 8085 |

Height prefers **metrics** (`highest_synced_checkpoint`), fallback **http** JSON-RPC. Internal `network-address` stays `127.0.0.1:8080` (vendor pin, not catalog).

---

## 5. Disks & sizing

| Role | Media | Leaf |
|---|---|---|
| state | nvme | sui-node `db-path` |
| index | ssd | index / aux |

| Env | diskHint / full | CPU | RAM |
|---|---|---|---|
| mainnet | 2048 | 8 | 32 |
| testnet | 512 | 4 | 16 |

---

## 6. Client config

`format: flags` — Start preview bindings; process starter writes `fullnode.yaml` with the same literals:

| Key | Source | Applied |
|---|---|---|
| db-path | disk_role_dir state | fullnode.yaml |
| json-rpc-address | catalog_port http | yaml |
| metrics-address | catalog_port metrics | yaml |
| p2p listen | catalog_port p2p | yaml |
| enable-event-processing | literal true | yaml |
| num-epochs-to-retain | literal max u64 (full history) | yaml |
| archive concurrency | literal 5 | yaml |
| LimitNOFILE | literal 1048576 | systemd unit |

---

## 7. Snapshot

`required` for mainnet/testnet — Mysten formal R2 via `sui-tool download-formal-snapshot --latest`. Not toolkit aria2. Resolver returns `formal-r2://{env}`; agent runs heal script.

| Type id | Kind | Default | Hint |
|---|---|---|---|
| formal | full | yes | Formal checkpoint snapshot into db-path |

---

## 8–9. Start / proc

- Extract tarball → `{node_dir}/bin/{sui-node,sui-tool,sui}`
- Ensure `{node_dir}/genesis.blob` (synced config)
- Render `{node_dir}/fullnode.yaml`
- Unit `rpcnode-sui-<env>` → `sui-node --config-path …/fullnode.yaml`
- **Never** write `/opt/sui` or `/etc/sui`

---

## 10–11. Height / tip

- Local: Prometheus `highest_synced_checkpoint` on metrics port; else JSON-RPC `sui_getLatestCheckpointSequenceNumber` on http.
- Tip: same JSON-RPC against YAML `publicTip.urls` (suiscan; Mysten fullnode JSON-RPC often deprecated). GraphQL fallback in tip probe.
- Checkpoint 0 without formal marker must not look “synced” (admin already treats tip-dead).

---

## 12–13. Lifecycle / options

sync → active on tip lag. No extra install options beyond formal snapshot type.
