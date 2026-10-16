# Avalanche — intake

Status: **implemented** (first slice: avalanchego P/X/C, full + archive C-Chain, mainnet + fuji). Not yet run on a live host.

## 0. Meta
| Field | Answer |
|---|---|
| Network id | `avalanche` |
| Label | Avalanche |
| Envs | `mainnet`, `fuji` |

## 1. Scope
- Install, start, height, public tip, diagnostics (`path_mtu`). No snapshot (`snapshot: never`): the C-Chain bootstraps with state sync (full) or from genesis (archive).
- Out of scope: validator staking, subnets / L1s (`subnet-evm` ships in the same release but is not used), Admin and Indexer APIs.
- `oneEnvPerHost: false` — mainnet and fuji use different ports.

## 3. Client (`clients.yml`)
- `ava-labs/avalanchego` GitHub releases: `avalanchego-linux-{amd64,arm64}-{tag}.tar.gz` (HEAD-checked for `v1.15.1` and `v1.15.0-fuji`). No Docker.
- Latest: mainnet = newest stable; fuji = newest including `-fuji` pre-releases (they carry an upgrade before mainnet) — `AvalancheClientReleaseResolver`.
- The tarball unpacks to `avalanchego-<tag>/avalanchego`; `plugins/` is copied only if present (the C-Chain EVM is built in since coreth moved into the repo).

## 4. Ports
| Env | Role | Port |
|---|---|---|
| mainnet | http (API + C-Chain RPC `/ext/bc/C/rpc`) | 9650 |
| mainnet | p2p (staking, TCP) | 9651 |
| fuji | http | 9660 |
| fuji | p2p | 9661 |
No clash with other networks (`ShippedPortCatalogTest`).

## 5. Disks and sizing
- One role `blockchain` (nvme): `--db-dir=<role>/db`. Staking keys, configs, logs under the node dir (`--data-dir`, `--log-dir`).
- Numbers are estimates from vendor docs (≥1 TiB NVMe for mainnet) and need an ops check: mainnet full 1536 GiB, archive 8192 GiB; fuji 512 / 1536 GiB. 8 cores / 32 GiB mainnet.

## 6. Client config
Format `flags`. Bindings (all in `network.yml`, same values in `AvalancheUnitBodies`): `node` (install option full|archive), `db-dir`, `http-port`, `staking-port`, `http-host=0.0.0.0`, `http-allowed-hosts=*`, `api-admin-enabled=false`, `index-enabled=false`, `eth-apis`, `rpc-gas-cap=50000000`, `rpc-tx-fee-cap=100`, `LimitNOFILE=1048576`.
C-Chain `config.json` (verified against coreth `v1.15.1` keys): `pruning-enabled`, `state-sync-enabled`, `eth-apis`, `rpc-gas-cap`, `rpc-tx-fee-cap`; full = pruned + state sync, archive = pruning off.

## 7. Height / tip
- Local: `info.isBootstrapped` (`/ext/info`) + C-Chain `eth_blockNumber`; not bootstrapped ⇒ syncing.
- Public tip: `eth_blockNumber` on `api.avax.network` / `api.avax-test.network` and publicnode.

## Snapshots
None. avalanchego publishes no database snapshots; the full node bootstraps with C-Chain **state sync** (hours), which is the fast path. The third-party snapshots found
(BWare Labs, 2025 pruned `.tar.lz4`; Alchemy deprecated) are stale or unverified and none is archive — an archive node must replay from genesis with `pruning-enabled: false`.

## Open questions
- Real disk numbers per mode (see §5).
- Whether to expose the Indexer API (`--index-enabled`) as an install option.
