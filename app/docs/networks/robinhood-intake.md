# Robinhood Chain — intake

Status: **implemented**, not yet run on a live host. Envs `mainnet`, `testnet` (= Robinhood Chain Sepolia).

## 1. Scope
An Arbitrum Orbit chain on stock Nitro, parent chain Ethereum (mainnet) / Sepolia (testnet). Reuses the Arbitrum Nitro tarball, wasm roots and
L1-parent helpers (`ArbHostBinaries`, `ArbL1Parent`, `ArbNodeHeightProbe`). Out of scope: validator.

## 3. Client
Nitro from the Docker Hub registry over HTTP (`docker://offchainlabs/nitro-node:{tag}`, no Docker daemon) like Arbitrum; the tag follows `ArbClientReleaseResolver`.
Chain-info JSON from the Robinhood CDN, synced as a client `config` (`robinhood-chain-info.json`, `robinhood-chain-testnet-info.json`; checked: chain-id 4663 / 46630).

## 4. Ports
mainnet http 8667 / ws 8668; testnet 8677 / 8678. (Nitro has no P2P; the sequencer feeds are outbound.)

## 5. Disks
Role `execution` (nvme). Snapshots restored by Nitro itself: pruned mainnet ~0.9 TiB, archive-path ~1.3 TiB; testnet pruned ~0.26 TiB, archive ~4.2 TiB (explorer API, 2026-10).
Hints are estimates (docs: 2× chain size + 20 %).

## 6. Nitro flags
`--chain.info-files`, `--parent-chain.connection.url`, `--parent-chain.blob-client.beacon-url`, `--node.feed.input.url` (mainnet: primary + delayed backup, testnet: one),
`--init.latest={pruned|archive} --init.latest-base=https://robinhood-snapshots.offchainlabs.com/`; archive adds `--execution.caching.archive` and, on mainnet,
`--execution.caching.state-scheme=path` (the mainnet archive snapshot is PathDB, the testnet one is hash-scheme). No genesis file is passed (the snapshot carries it).
Verified: `<mirror>/<chain name>/latest-{pruned,archive,archive-path,full-path}.txt` pointers exist and the chain names in the info files match the mirror prefixes.

## 7. Open
`--init.latest` resolution of the PathDB archive on mainnet (`latest-archive-path.txt` is picked by Nitro from the state scheme); first start on a live host.
