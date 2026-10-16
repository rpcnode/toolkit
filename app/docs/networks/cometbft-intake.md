# CometBFT family (cosmos, osmosis, injective, sei, celestia, xrplevm) — what shipped

Code: `chains/cometbft/infrastructure` (one shared package, profiles in `CometbftProfiles`), resources `chains/<id>/`.
Research + verification notes: `cometbft-family-research.md`, `cometbft-family-risks.md` (written before implementation).

## Shipped
| id | envs | client | snapshot (default) |
|---|---|---|---|
| cosmos | mainnet | gaiad (amd64 only) | Polkachu lz4 (optional) |
| osmosis | mainnet, testnet | osmosisd | official `snapshots.osmosis.zone/latest` |
| injective | mainnet | injectived zip (amd64 only, closed source) | official `status.json` (OVH, GCS) |
| sei | mainnet, testnet | seid | Polkachu lz4 (mainnet ≈ 234 GB) |
| celestia | mainnet | celestia-appd (multiplexer build, glibc ≥ 2.38) | Polkachu lz4 |
| xrplevm | mainnet, testnet | exrpd | Peersyst official lz4 |

## How it works
- Start (host agent): install the binary into `<node_dir>/bin`, `init` once (moniker `rpcnode-<node id>`, `priv_validator_key.json` deleted), copy `genesis.json`, then patch `config.toml` / `app.toml` on EVERY Start (`CometbftTomlPatch`). The literals equal the `clientConfig.bindings` of each network.yml (`CometbftCatalogTest`).
- Height: node `/status` → `latest_block_height`, `catching_up` (a newest block older than 120 s also counts as syncing). Tip: first public RPC that is not catching up.
- Sync → active needs `tip - height <= tipLagBlocks` (network.yml per env; default 3; injective 15, sei 25 because of sub-second blocks).
- Snapshots: `.tar.lz4` streamed into `<home>` (`SnapshotStreamExtract.streamTarArgs` picks lz4 / zstd / gzip from the URL suffix); the URL is looked up when the job starts (providers keep one file).
- Never exposed publicly by the patch: `rpc.unsafe=false`, `pprof` off, Prometheus on 127.0.0.1, EVM namespaces `eth,net,web3`, unprotected txs off, Sei admin server off.

## Not done (v1)
- State sync (Sei would need it to avoid the 234 GB snapshot; Injective cannot use it without `wasm/`).
- Cosmovisor / halted-upgrade detection: a governance upgrade halts the node until *Update client* is run.
- cosmos / injective / celestia testnets, Celestia DA nodes (bridge / full / light), 0G (`zerog`), gravity (not a Cosmos chain).
- Injective `[json-rpc]` key names are INFERRED from cosmos/evm — check `app.toml` after the first `init`.
- Nothing was run on a Linux host: all of the above is verified by unit tests and upstream docs / source only.
