# Network intake — Ethereum

Filled from Go sibling `../toolkit-go/internal/networks/ethereum/` and admin helpers.

| Field | Answer |
|---|---|
| Network id | `ethereum` |
| Display label | Ethereum |
| Author | toolkit (port from Go facts) |
| Date | 2026-09-02 |
| Status | **approved** (operator request to add eth) |

**Go sibling:** facts / ports / dual-unit geth+lighthouse / gethstore artifacts. Do not edit or run Go.

---

## Review table

| Section | Status | Notes |
|---|---|---|
| 1 Product scope | OK | Full EL/CL node (geth + lighthouse) |
| 2 Environments | OK | mainnet, sepolia, hoodi |
| 3 Artifacts | OK | geth (gethstore) + lighthouse (GitHub) |
| 4 Ports | OK | p2p / http / engine / beacon / consensus_p2p |
| 5 Disks | OK | execution (NVMe) + consensus (SSD) |
| 6 Client config | OK | `format: flags` — systemd CLI, no conf patch |
| 7 Snapshot | OK | `never` all envs |
| 8–9 Start / proc | OK | Templates `resources/chains/ethereum/*.tmpl` + dual units |

| 10–11 Height / tip | OK | `eth_blockNumber` JSON-RPC |
| 12–13 Lifecycle / options | OK | sync→active; node=full\|archive |
| **Overall** | Ready to implement | |

---

## 1. Product scope

1. **Why:** Operators run post-merge Ethereum full nodes (geth EL + lighthouse CL).
2. **MVP:** download both clients, disks, ports, JWT, start both units, push height, public tip lag, full vs archive install option. **Out of scope:** reth, validator duties, mev-boost.
3. **Pin-only?** No — artifacts under `clients/ethereum.yml`.
4. **One env per host?** `false`.

---

## 2. Environments

| Env id | Label | Production? | Notes |
|---|---|---|---|
| `mainnet` | Ethereum Mainnet | yes | |
| `sepolia` | Ethereum Sepolia | test | |
| `hoodi` | Ethereum Hoodi | test | |

Same binaries; network via geth/lighthouse flags.

---

## 3. Client binary & artifacts

1. **Geth:** GitHub `ethereum/go-ethereum` for version; tarball from gethstore blob list.
2. **Lighthouse:** GitHub `sigp/lighthouse` release assets (`*-unknown-linux-gnu.tar.gz`).
3. Stable names: `geth-linux-amd64.tar.gz` / `geth-linux-arm64.tar.gz`, `lighthouse-x86_64.tar.gz` / `lighthouse-aarch64.tar.gz`.
4. Program ids: `geth` (primary), `lighthouse`.

---

## 4. Fixed ports

| Env | Role | Port |
|---|---|---|
| mainnet | p2p | 30303 |
| mainnet | http | 8545 |
| mainnet | engine | 8551 |
| mainnet | beacon | 5052 |
| mainnet | consensus_p2p | 9000 |
| sepolia | p2p | 30313 |
| sepolia | http | 8546 |
| sepolia | engine | 8552 |
| sepolia | beacon | 5053 |
| sepolia | consensus_p2p | 9100 |
| hoodi | p2p | 30323 |
| hoodi | http | 8547 |
| hoodi | engine | 8553 |
| hoodi | beacon | 5054 |
| hoodi | consensus_p2p | 9200 |

Height uses **http** role (`eth_blockNumber`).

---

## 5. Disks & sizing

| Role | Media | Leaf |
|---|---|---|
| execution | nvme | geth datadir |
| consensus | ssd | lighthouse datadir |

| Env | diskHint / full | archive | CPU | RAM |
|---|---|---|---|---|
| mainnet | 2048 | 14336 | 8 | 32 |
| sepolia | 400 | 2048 | 4 | 16 |
| hoodi | 400 | 2048 | 4 | 16 |

---

## 7. Snapshot

`never` for all envs (IBD / checkpoint sync via lighthouse).

---

## 8–9. Start / proc

- Dual units from classpath templates:
  - `resources/chains/ethereum/node.service.tmpl` → `rpcnode-ethereum-<env>` (geth)
  - `resources/chains/ethereum/lighthouse.service.tmpl` → companion lighthouse
- Both use `WantedBy=multi-user.target` (same as bitcoin/tron — run as root, no `User=`).
- JWT at `/etc/ethereum/<env>/jwt.hex`.
- Archive: `--syncmode full --gcmode archive`; else snap + gcmode full.

---

## 10–11. Height / tip

- Local: `POST http://127.0.0.1:{http}/` → `eth_blockNumber`.
- Tip: publicnode URLs in YAML (same JSON-RPC).

---

## 13. Install options

`node=full|archive` (admin `ETHEREUM_NODE_OPTIONS`).
