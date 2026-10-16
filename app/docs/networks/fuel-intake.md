# Network intake — Fuel Ignition (fuel-core)

VERIFIED = read from upstream source / docs at fuel-core v0.48.3 or curl (2026-10-13/14); INFERRED = not checked. Not run on a Linux host.

| Field | Answer |
|---|---|
| Network id / envs | `fuel` — `mainnet` (Ignition), `testnet` (Ignition-test) |
| Status | **implemented** (`chains/fuel`), snapshot `never` (Fuel publishes none) |
| Ports | mainnet 41540 GraphQL / 41542 p2p; testnet 41550 / 41552 (`+7` stays free: gRPC `--rpc-port` must stay off) |

- **Binary:** `fuel-core-<ver>-<x86_64|aarch64>-unknown-linux-gnu.tar.gz` of `FuelLabs/fuel-core` (`fuel-core`, `fuel-core-keygen`, `fuel-core-wasm-executor.wasm`); resolver = highest final `v<number>` release with both tarballs (by version: `v0.47.4` came after `v0.48.0`).
- **Chain config:** the four files `metadata.json, chain_config.json, state_config.json, state_transition_bytecode.wasm` from `chain-config-assets.fuel.network/<ignition|ignition-test>/` (VERIFIED 200), synced flat and copied to `<node_dir>/chain/` (`--snapshot`).
- **Flags** (VERIFIED against the clap definitions and docs.fuel.network mainnet/testnet pages): `run --service-name --ip=0.0.0.0 --port --peering-port --db-path=<state>/db --snapshot --utxo-validation --poa-instant=false --enable-p2p --bootstrap-nodes=/dnsaddr/<net>.fuel.network --sync-header-batch-size=50 --sync-block-stream-buffer-size=30 --enable-relayer --relayer-v2-listening-contracts --relayer-da-deploy-height --relayer-log-page-size` + vendor-default capacity (`graphql-number-of-threads 2`, `graphql-max-concurrent-queries 1024`, body limit 1 MiB, timeout 30s, `max-peers-connected 50`). `--poa-instant` defaults to **true** upstream and must be passed `false`. Per-env values (portal contract, deploy height, page size, bootstrap) are `onlyEnv` bindings.
- **Secrets:** the relayer needs an Ethereum RPC (mainnet → Ethereum mainnet, testnet → Sepolia; the wizard's `l1_rpc` picker, public default publicnode, or a panel Ethereum node). Its URL may carry an API key and the libp2p secret is a key: both live in `<node_dir>/.toolkit/fuel.env` (0600; `KEYPAIR`, `RELAYER` are clap env vars) loaded by `EnvironmentFile=`; the URL is stripped from the recorded launch (`.toolkit/launch.json` is world-readable). The libp2p secret is generated once by `fuel-core-keygen new --key-type peering` (output field `secret`: hex format INFERRED, validated) into `.toolkit/fuel-p2p.key`.
- **Must not be public:** `--debug` is never passed; metrics are served on the GraphQL port at `/v1/metrics` (no separate port) — block the path at the proxy. The prod preset raises GraphQL threads (8), concurrent queries (4096) and `max-database-cache-size`.
- **Height / tip:** GraphQL `{ chain { latestBlock { height } } }` → `data.chain.latestBlock.height` on the node (`/v1/graphql`) and on `mainnet.fuel.network` / `testnet.fuel.network`; `tipLagBlocks: 10`. Hardware: 8 c / 16 GB / 1 TB (official). Sync from genesis over P2P takes long (no snapshot).
- **INFERRED:** that the `chain/` sub-directory flow needs nothing else, the relayer's eth_getLogs load on a public L1 (use a node you control).
