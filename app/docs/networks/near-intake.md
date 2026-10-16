# Network intake — NEAR (neard RPC node)

VERIFIED = read from upstream source / curl at the named version (2026-10-13/14); INFERRED = not checked. Not run on a Linux host.

| Field | Answer |
|---|---|
| Network id / envs | `near` — `mainnet`, `testnet` |
| Status | **implemented** (`chains/near`), snapshot `never` |
| Ports | mainnet 42080 RPC / 42082 p2p / 42084 Prometheus (127.0.0.1); testnet 42090 / 42092 / 42094 |

- **Binary:** GitHub releases carry no assets; `neard` is on the official build bucket `…/build.nearprotocol.com/nearcore/Linux-<x86_64|aarch64>/<version>/neard` (VERIFIED HEAD 200 for 2.13.4 and 2.14.0-rc.3, both arches). `NearClientReleaseResolver`: mainnet = highest final release (`2.13.4`), testnet = highest including `-rc.N` (`2.14.0-rc.3`), only when the bucket object exists. Saved as `neard-<arch>`, Start copies it to `bin/neard`. Minimum glibc of the bucket binary is INFERRED.
- **Start** (`NearNodeProcessStarter`): once per home `neard --home <state> init --chain-id <env> --download-genesis --download-config rpc` (flags VERIFIED in `neard/src/cli.rs` 2.13.4; the `rpc` profile `config.json` VERIFIED on the bucket); then on every Start the boot nodes are refreshed from the public `network_info` — `peer_id@addr` of every active peer that is also a known producer, exactly the recipe of near-nodes.io/intro/node-epoch-sync (VERIFIED against the live RPC). An empty `boot_nodes` leaves the node at 0 peers, so Start fails when none can be found and none are configured. Keys Start owns in `config.json`: `network.boot_nodes`, `rpc.enable_debug_rpc=false` (the testnet profile ships `true`), `view_client_threads=16` (vendor default 4; judgement). Everything else is the operator's.
- **Run:** `neard --home=<state> run --rpc-addr=0.0.0.0:<rpc> --network-addr=0.0.0.0:<p2p> --rpc-prometheus-addr=127.0.0.1:<metrics>`; `LimitNOFILE=1048576`, `TimeoutStopSec=300` (RocksDB flush). No validator key is kept.
- **Sync:** epoch sync + state sync from peers; the free nearcore snapshots were deprecated 2025-06-01, so no snapshot resolver. Disk: 8 c / 32 GB / 4 TB NVMe recommended (official).
- **Height / tip:** JSON-RPC `status` → `result.sync_info.latest_block_height` (+ `syncing`) on the node and on `rpc.mainnet.near.org`, `rpc.mainnet.fastnear.com`, `near.drpc.org` (testnet equivalents).
- **Must not be public:** `node_key.json`, the debug RPC, Prometheus (loopback). The RPC listens on `0.0.0.0` by design.
