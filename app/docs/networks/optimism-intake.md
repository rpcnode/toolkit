# Optimism (OP Mainnet, OP Sepolia) — intake

Status: **implemented**, not yet run on a live host. Envs `mainnet`, `sepolia`.

## 1. Scope
OP Stack with **op-reth + op-node** (OP Labs: op-geth is end-of-support since 2026-05-31 and cannot follow the chain after Karst). Full and archive nodes with snapshots.

## 3. Client
`ethereum-optimism/optimism` publishes no binaries (only container images), so the run script builds both from source on the host, once per pinned tag:
`op-reth/v2.6.0` (Rust, toolchain from `rust/rust-toolchain.toml` through rustup) and `op-node/v1.19.9` (Go 1.26.6). Tags are pinned in `OptimismUnitBodies` and can be
overridden in `/etc/rpcnode/optimism.env`. The panel shows the newest `op-reth/` release as "latest". Pin-only entries in `clients.yml` (they only carry the ports).

## 4. Ports
mainnet: http 8561, ws 8562, engine 8563, metrics 9261, p2p 30361; op-node rpc 9561, metrics 7361, p2p 9361. sepolia: 8564-8566, 9264, 30364; 9564, 7364, 9364.

## 5. Disks
Roles `execution` (nvme), `consensus` (ssd: op-node p2p key, peerstore), `snapshots` (ssd: the `.tar.zst` is downloaded here and deleted after extraction).
Estimates: full mainnet ~0.7 TiB (OP docs), archive several TiB.

## 6. Flags (checked against the OP docs and the op-node source at v1.19.9)
op-reth: `--chain optimism|optimism_sepolia --datadir --http/--ws --authrpc.* --rollup.sequencer --metrics --port/--discovery.port`; full adds
`--prune.minimum-distance/receipts/account-history/storage-history.distance 1296000` (~30 days) — block bodies are never pruned (op-node needs them), `--minimal` is rejected by op-reth.
op-node: `--l1 --l1.rpckind=basic --l1.beacon --l2=ws://127.0.0.1:<engine> --l2.jwt-secret --network op-mainnet|op-sepolia --syncmode=execution-layer --l2.enginekind=reth`, own p2p/rpc/metrics ports and key paths.

## 7. Snapshots
OP Labs data directories (`datadirs.optimism.io`): `mainnet-op-<mainnet|sepolia>-reth-<full|archive>-storage-v2-<date>.tar.zst` + `.sha256sum` — the newest listed one is chosen,
downloaded with aria2 (resumable, SHA-256 checked) and extracted with `tar -I zstd` (wrapper directory detected from the first entry). Mandatory on OP Mainnet (pre-Bedrock state import), only for archive on OP Sepolia.

## 8. Open
Build times/RAM on the target host; whether the snapshot tarball has a wrapper directory (the script inspects the first entry); op-reth version pairing with op-node when the pins move.
