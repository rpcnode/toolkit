# Network intake — Base

Filled from Go sibling `../toolkit-go/internal/networks/base/` and admin helpers.

| Field | Answer |
|---|---|
| Network id | `base` |
| Display label | Base |
| Author | toolkit (port from Go facts) |
| Date | 2026-09-02 |
| Status | **implemented** (GitHub releases — no Docker) |

**Go sibling:** facts / ports / dual-unit base-reth-node + base-consensus / V2 snapshot. Kotlin uses GitHub `base/base` tarballs instead of Go's docker extract. Do not edit or run Go.

---

## Review table

| Section | Status | Notes |
|---|---|---|
| 1 Product scope | OK | Full OP Stack L2 (base-reth-node + base-consensus) |
| 2 Environments | OK | mainnet, sepolia |
| 3 Artifacts | OK | GitHub `base/base` — base-reth-node + base-consensus tar.gz |
| 4 Ports | OK | p2p / http / ws / engine / consensus_p2p / discovery_v5 |
| 5 Disks | OK | execution (NVMe) + snapshots (SSD) |
| 6 Client config | OK | `format: flags` — systemd CLI, no conf patch |
| 7 Snapshot | OK | `required` — official V2 via base-reth-node download |
| 8–9 Start / proc | OK | Dual units + consensus wrapper; L1 parent RPC/beacon |
| 10–11 Height / tip | OK | `eth_blockNumber` JSON-RPC |
| 12–13 Lifecycle / options | OK | sync→active; snapshot=archive\|full\|minimal |
| **Overall** | Ready to implement | |

---

## 1. Product scope

1. **Why:** Operators run Base L2 RPC (Coinbase OP Stack: base-reth-node EL + base-consensus).
2. **MVP:** Clients download, disks, ports, JWT, L1 parent, official V2 snapshot ExtraStep, start both units, push height, public tip lag, archive/full/minimal snapshot picker. **Out of scope:** validator / sequencer duties, op-geth path, Docker extract.
3. **Pin-only?** No — artifacts under `chains/base/clients.yml`.
4. **One env per host?** `false`.

---

## 2. Environments

| Env id | Label | Production? | Notes |
|---|---|---|---|
| `mainnet` | Base Mainnet | yes | chain id 8453 |
| `sepolia` | Base Sepolia | test | chain id 84532 |

Same binaries; network via `--chain` / consensus `BASE_NODE_NETWORK`.

---

## 3. Client binary & artifacts

1. **Upstream:** GitHub `base/base` — `base-reth-node-{tag}-*-unknown-linux-gnu.tar.gz` + `base-consensus-{tag}-*-unknown-linux-gnu.tar.gz`.
2. **Latest:** newest GitHub release (`BaseClientReleaseResolver`).
3. Stable names: `base-reth-node-x86_64.tar.gz` / `base-reth-node-aarch64.tar.gz`, same for consensus.
4. Program ids: `base-reth-node` (primary), `base-consensus`.

---

## 4. Fixed ports

| Env | Role | Port |
|---|---|---|
| mainnet | p2p | 30353 |
| mainnet | http | 8571 |
| mainnet | ws | 8581 |
| mainnet | engine | 8572 |
| mainnet | consensus_p2p | 9023 |
| mainnet | discovery_v5 | 9203 |
| sepolia | p2p | 30354 |
| sepolia | http | 8573 |
| sepolia | ws | 8583 |
| sepolia | engine | 8574 |
| sepolia | consensus_p2p | 9033 |
| sepolia | discovery_v5 | 9213 |

Height uses **http** role (`eth_blockNumber`).

---

## 5. Disks & sizing

| Role | Media | Leaf |
|---|---|---|
| execution | nvme | base-reth `--datadir` |
| snapshots | ssd | aux / snapshot workspace |

| Env | diskHint | full (reth --full) | archive | CPU | RAM |
|---|---|---|---|---|---|
| mainnet | 9216 | 3145 | 3711 | 8 | 32 |
| sepolia | 2304 | 828 | 956 | 4 | 16 |

---

## 7. Snapshot

`required` for mainnet/sepolia — official V2 via `base-reth-node download --archive|--full|--minimal`. Not toolkit single-tarball aria2. Resolver returns `base-official://` sentinel; agent runs heal script.

| Type id | Kind | Default | Hint |
|---|---|---|---|
| archive | archive | yes | Full history (~3711 GiB mainnet / ~956 GiB sepolia) |
| full | full | no | reth --full preset (~3145 / ~828 GiB) |
| minimal | pruned | no | State + headers (~677 / ~283 GiB) |

---

## 8–9. Start / proc

- Dual units:
  - `rpcnode-base-<env>` → base-reth-node (primary)
  - `rpcnode-base-consensus-<env>` → consensus companion (after reth)
- JWT at `/etc/base/<env>/jwt.hex`.
- Consensus needs L1 execution RPC + beacon (env override, local ethereum, or public sepolia defaults).

---

## 10–11. Height / tip

- Local: `POST http://127.0.0.1:{http}/` → `eth_blockNumber`.
- Tip: publicnode Base URLs in YAML.

---

## 13. Install options

`snapshot=archive|full|minimal` (admin `BASE_SNAPSHOT_OPTIONS`).
