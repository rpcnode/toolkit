# Network intake — Polygon

Filled from Polygon PoS docs (Bor + Heimdall-v2). No Go sibling package.

| Field | Answer |
|---|---|
| Network id | `polygon` |
| Display label | Polygon |
| Author | toolkit |
| Date | 2026-09-02 |
| Status | **approved** (operator request to add polygon) |

**Go sibling:** none under `../toolkit-go/internal/networks/polygon/`.

---

## Review table

| Section | Status | Notes |
|---|---|---|
| 1 Product scope | OK | Full sentry node (bor + heimdall-v2) |
| 2 Environments | OK | mainnet, amoy |
| 3 Artifacts | OK | `.deb` from 0xPolygon/bor + heimdall-v2 |
| 4 Ports | OK | bor p2p/http/ws + heimdall p2p/rpc/api |
| 5 Disks | OK | bor (NVMe) + heimdall (SSD) |
| 6 Client config | OK | `format: flags` — systemd + extracted profile configs |
| 7 Snapshot | OK | `never` MVP (community snapshots later) |
| 8–9 Start / proc | OK | Dual units; heimdall before bor |
| 10–11 Height / tip | OK | `eth_blockNumber` on bor HTTP |
| 12–13 Lifecycle / options | OK | sync→active; node=full\|archive |
| **Overall** | Ready to implement | |

---

## 1. Product scope

1. **Why:** Operators run Polygon PoS RPC (Bor EL + Heimdall-v2 CL).
2. **MVP:** download both clients, disks, ports, start both units (heimdall then bor), push height, public tip lag, full vs archive. **Out of scope:** validator duties, PBSS, toolkit-managed community snapshots, Erigon.
3. **Pin-only?** No — artifacts under `clients/polygon.yml`.
4. **One env per host?** `false`.

---

## 2. Environments

| Env id | Label | Production? | Notes |
|---|---|---|---|
| `mainnet` | Polygon Mainnet | yes | chain id 137 |
| `amoy` | Polygon Amoy | test | chain id 80002 |

Same binary debs; network via profile config packages + genesis.

---

## 3. Client binary & artifacts

1. **Bor:** GitHub `0xPolygon/bor` → `bor-{tag}-amd64.deb` / arm64 + sentry/archive config debs + genesis JSON.
2. **Heimdall:** GitHub `0xPolygon/heimdall-v2` → `heimdall-{tag}-*.deb` + sentry config deb; genesis from GCS buckets.
3. Stable names: `bor-amd64.deb` / `bor-arm64.deb`, `heimdall-amd64.deb` / `heimdall-arm64.deb`.
4. Program ids: `bor` (primary), `heimdall`.

---

## 4. Fixed ports

| Env | Role | Port |
|---|---|---|
| mainnet | p2p | 30333 |
| mainnet | http | 8548 |
| mainnet | ws | 8549 |
| mainnet | heimdall_p2p | 26656 |
| mainnet | heimdall_rpc | 26657 |
| mainnet | heimdall_api | 1317 |
| amoy | p2p | 30343 |
| amoy | http | 8558 |
| amoy | ws | 8559 |
| amoy | heimdall_p2p | 26756 |
| amoy | heimdall_rpc | 26757 |
| amoy | heimdall_api | 1327 |

Height uses **http** role (`eth_blockNumber` on Bor).

---

## 5. Disks & sizing

| Role | Media | Leaf |
|---|---|---|
| bor | nvme | Bor datadir |
| heimdall | ssd | Heimdall home |

| Env | diskHint / full | archive | CPU | RAM |
|---|---|---|---|---|
| mainnet | 8192 | 16384 | 16 | 64 |
| amoy | 2048 | 2048 | 8 | 16 |

---

## 7. Snapshot

`never` for MVP (IBD). Community snapshots (all4nodes etc.) later.

---

## 8–9. Start / proc

- Dual units:
  - `rpcnode-polygon-<env>` → Bor (primary)
  - `rpcnode-polygon-heimdall-<env>` → Heimdall companion (started first)
- Extract `.deb` with `dpkg-deb -x` on Start.
- Heimdall needs an Ethereum L1 JSON-RPC URL (default publicnode).

---

## 10–11. Height / tip

- Local: `POST http://127.0.0.1:{http}/` → `eth_blockNumber`.
- Tip: publicnode Polygon URLs in YAML.

---

## 13. Install options

`node=full|archive` (admin `POLYGON_NODE_OPTIONS`).
