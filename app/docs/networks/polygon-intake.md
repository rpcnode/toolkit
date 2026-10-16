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

---

## Snapshots (added 2026-10-16)

Polygon is now `snapshot: required` (both envs, one type `full`; it was `pruned` until the correction below). Reason, measured on amoy: Heimdall v2 starts at the genesis height
8 788 501 (2025-06-24) while the public peers keep blocks only from 47 344 508 (2026-09-26) to the tip, so it can never block-sync (the
node sat at `catching_up: true` for good, Bor got `500` on `bor/spans/0`); Bor from block 0 takes days.

Restore = `PolygonSnapshot` (`chains/polygon`) + `snapshot.sh.tmpl`, run by the host agent through the same `evm-snapshot://` mechanism as
Sonic / Tempo (`ScriptedSnapshots` registry, `ScriptedSnapshot` interface): the **community snapshots of PublicNode (Allnodes)**: Heimdall pruned, Bor `base` + `part`.
- The newest file names are read from `https://www.publicnode.com/snapshots` at restore time (names carry the heights and change about daily;
  the link redirects to a signed, expiring URL, HEAD is refused, so the size comes from a one-byte range request and a retry asks for a
  fresh signature). Families per env: amoy `polygon-amoy-heimdall-pruned-*` + `polygon-amoy-bor-base-*` + `polygon-amoy-bor-part-*`, mainnet
  `polygon-heimdall-pruned-*` + `polygon-bor-base-*` + `polygon-bor-part-*`. The script refuses a `part` that does not start at `base` end + 1.
- Order: Heimdall (`data/application.db/…` into the Heimdall home; only `data/` is replaced, `config/` and keys stay), then Bor
  (`bor/chaindata/…` into `<bor>/datadir/data`; stale `bor/chaindata` and `bor/triedb` journal removed first, `bor/nodes` stays).
  Every archive is unpacked from a resumable download in `<bor>/snapshots` (one at a time), which is deleted afterwards. Both units are stopped first.
- Directories come from the node's disk layout as `dir_<role>` parameters of the sentinel URL (`StartNodeSnapshotUseCase`, for every scripted
  snapshot); without them the node-start defaults apply (`<bor dir>/datadir`, `<parent of bor dir>/heimdall`).
- Sizes on 2026-10-16 (compressed): amoy Heimdall 7.9 GB + Bor base 21 GB + part 81 GB (about 14 + 130 GB unpacked); mainnet Heimdall 6.5 GB +
  Bor base 2.7 TB + part 3.4 TB, i.e. a mainnet restore needs a 12 TB class host and days of download.
- After the restore start the node: Heimdall must catch up (the amoy gap after the snapshot is hours of blocks) before Bor syncs.

Verified: the archive layouts (first MBs of both amoy archives), the restore script end to end on stand-ins and real tar archives
(`PolygonSnapshotRestoreTest`), the link picking on the real page links (`PolygonSnapshotTest`). Mainnet checked the same way on 2026-10-16: both archives have the same layouts (`data/application.db/…`, `bor/chaindata/…`), and the Heimdall
snapshot (height 55 659 346) is within what the public peers keep (earliest 53 661 198, tip 55 661 318: about 3 weeks), so Heimdall can block-sync the rest.
**Not verified on a host:** a full real restore (amoy or mainnet); that Bor accepts the snapshot's state scheme (PublicNode's PBSS + Pebble snapshot is "beta" per Polygon's docs, the
node config uses `scheme=path` + pebble); no checksum is published for these files (integrity is only the transport's).
Fix made on the way: Heimdall v0.12.2 for amoy ships `moniker = ""` and refuses to start (`PolygonConfigPatch.ensureMoniker`).


### Correction 2026-10-16: the PublicNode `pruned` Bor archive does not start

Found on a real amoy host (`rpcnode-polygon-amoy.service` exited with status 1 right after the pruned restore; Bor's own log, in `<bor>/logs/node.out`,
said `ancient chain segments already extracted, please set --datadir.ancient to the correct path`). Reproduced on a test host with a bare
`bor server --chain amoy` on the unpacked archive, with **Bor 2.10.3 and 2.10.2**: the archive's `bor/chaindata/ancient/chain` holds only empty freezer
files (`*.cdat` are 0 bytes, `*.meta` = RLP `{version 2, tail 0, offset 6}`), while the key-value store lacks block 1, so geth's freezer/kv consistency check
rejects it. The mainnet pruned archive has the same empty `ancient/chain` (its listing was read on 2026-10-16). It is a state-only snapshot made
for a differently built Bor, not for the public release.

What works (verified on a Tokyo test host, 2026-10-16): the `base` + `part` families. `base` is the chain freezer for blocks 0..B (`ancient/chain/*.cdat`),
`part` continues it and carries the key-value store and the state; unpacked in that order into one tree, Bor 2.10.3 opens it (`frozen=24,226,240`),
repairs the head state and loads the local header 50 285 368. The real `snapshot.sh` (rendered by `PolygonSnapshot`) ran end to end on that host:
Heimdall (pruned) + Bor base + part, 11 minutes at the PublicNode download speed, phase `done`.
`PolygonSnapshot` therefore restores base + part, and the script checks `ancient/chain/bodies.0000.cdat` is not empty, so a hollow Bor archive stops the
restore with a message instead of leaving a node that cannot start.

Still open: nothing was run against a live Heimdall (Bor was started with `--bor.withoutheimdall` to prove the database opens); mainnet base + part is about
6 TB compressed and was not downloaded. Polygon's own snapshot service (docs.polygon.technology/pos/how-to/snapshots, `snapdown.sh`) is the other
source; its mainnet Bor snapshot is about 1.5 TB compressed per the docs table and was not tested.
