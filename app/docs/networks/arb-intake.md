# Network intake — Arbitrum

Filled from Go sibling `../toolkit-go/internal/networks/arb/` and admin helpers.

| Field | Answer |
|---|---|
| Network id | `arb` |
| Display label | Arbitrum |
| Author | toolkit (port from Go facts) |
| Date | 2026-09-02 |
| Status | **implemented** (Clients: Docker Hub → panel tarball → host sync) |

**Go sibling:** facts / ports / nitro unit / L1 parent / pruned vs archive init. Kotlin panel Download pulls `offchainlabs/nitro-node` **layers via Registry HTTP** (no Docker daemon), packs `nitro-*.tar.gz`, then syncs to the host. Do not edit or run Go.

---

## Review table

| Section | Status | Notes |
|---|---|---|
| 1 Product scope | OK | Nitro full node (single process) |
| 2 Environments | OK | mainnet (42161), sepolia (421614) |
| 3 Artifacts | OK | Docker Hub image → panel tarball; GH release notes for image tag |
| 4 Ports | OK | http + ws (catalog-fixed) |
| 5 Disks | OK | execution (NVMe) + snapshots (SSD aux) |
| 6 Client config | OK | `format: flags` — systemd CLI; LimitNOFILE binding |
| 7 Snapshot | OK | `never` toolkit aria2; Nitro self-init pruned/archive |
| 8–9 Start / proc | OK | Single unit; L1 execution RPC + beacon |
| 10–11 Height / tip | OK | `eth_blockNumber` JSON-RPC |
| 12–13 Lifecycle / options | OK | sync→active; snapshot=pruned\|archive |
| **Overall** | Ready to implement | |

---

## 1. Product scope

1. **Why:** Operators run Arbitrum One / Sepolia Nitro RPC.
2. **MVP:** Clients Add/Download (Registry HTTP extract on panel), disks, ports, L1 parent, nitro unit with pruned or PathDB archive init, start, height, public tip. **Out of scope:** Classic pre-Nitro, validator duties, toolkit CDN snapshot mirror.
3. **Pin-only?** No — artifact from Docker Hub Registry (HTTPS), packed on panel.
4. **One env per host?** `false`.

---

## 2. Environments

| Env id | Label | Production? | Notes |
|---|---|---|
| `mainnet` | Arbitrum One Mainnet | yes | chain id 42161 |
| `sepolia` | Arbitrum Sepolia | test | chain id 421614 |

Same binary; network via `--chain.id`.

---

## 3. Client binary & artifacts

1. **Upstream version:** GitHub `OffchainLabs/nitro` release notes pin `offchainlabs/nitro-node:vX.Y.Z-<hash>` (`ArbClientReleaseResolver`; Hub tag list as fallback).
2. **Artifact:** Panel Registry HTTPS pull of `offchainlabs/nitro-node` layers → pack `nitro-*-linux.tar.gz` (`clients.yml` `docker://…:{tag}`). **No Docker daemon** on panel or host.
3. **Layout inside tarball:** `bin/nitro`, `nitro-legacy/machines/`, `target/machines/`.
4. **On node install:** wizard **Clients** step downloads onto the panel, then syncs to the host `node_dir`.
5. Program id: `nitro`.
6. **Docker:** image is the upstream distribution channel only; toolkit never calls `docker` CLI.

---

## 4. Fixed ports

| Env | Role | Port |
|---|---|---|
| mainnet | http | 8547 |
| mainnet | ws | 8548 |
| sepolia | http | 8657 |
| sepolia | ws | 8658 |

Height uses **http** role (`eth_blockNumber`).

---

## 5. Disks & sizing

| Role | Media | Leaf |
|---|---|---|
| execution | nvme | Nitro `--persistent.chain` |
| snapshots | ssd | aux (unused by pruned init writer) |

| Env | diskHint | full (pruned) | archive | CPU | RAM |
|---|---|---|---|---|---|
| mainnet | 1024 | 1024 | 3800 | 8 | 32 |
| sepolia | 400 | 400 | 900 | 4 | 16 |

**dataRoot:** `arbitrum` (paths `/data/rpcnode/arbitrum/…`, `/etc/arbitrum` legacy sanitize).

---

## 6. Client config

`format: flags`. Bindings: `snapshot` (install_option), `datadir` (execution), `snapshots` (aux), `http-port`, `ws-port`, `LimitNOFILE=1048576`.

---

## 7. Snapshot

`never` for toolkit/CDN. Nitro bootstraps itself:

| Type id | Kind | Default | Hint |
|---|---|---|---|
| pruned | pruned | yes | `--init.latest=pruned` |
| archive | archive | no | PathDB via foundation `latest-archive-path.txt` + `--execution.caching.archive` |

---

## 8–9. Start / proc

- Unit `rpcnode-arb-<env>` → nitro binary under node_dir.
- L1: `RPCNODE_L1_*` → local ethereum → public sepolia defaults (mainnet L1 required from operator).
- Env file under `{node_dir}/.toolkit/nitro.env`.

---

## 10–11. Height / tip

- Local: `POST http://127.0.0.1:{http}/` → `eth_blockNumber`.
- Tip: publicnode Arbitrum URLs in YAML.

---

## 13. Install options

`snapshot=pruned|archive` (admin `ARB_SNAPSHOT_OPTIONS`).
