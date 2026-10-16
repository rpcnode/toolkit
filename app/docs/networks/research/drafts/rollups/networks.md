# Networks — compact facts (2026-10-13/14). Status: READY / NEEDS-DECISION / DEFERRED. VERIFIED(how) / INFERRED.

Ports are taken from `specs/PORTS.md` blocks (offset +0 http, +1 ws, +2 p2p, +3 engine, +4 metrics, +5 cl rpc/api, +6 cl p2p, +7 cl metrics, +8 spare).

| id | label | envs | status |
|---|---|---|---|
| xai | Xai | mainnet | **READY** (Nitro, same recipe as arb/robinhood) |
| starknet | Starknet | mainnet, sepolia | **READY** (only native binary) |
| zksync | ZKsync Era | mainnet, sepolia | NEEDS-DECISION (container-only; drafts assume registry pack) |
| abstract | Abstract | mainnet, testnet | NEEDS-DECISION (same) |
| lens | Lens Chain | mainnet, testnet | NEEDS-DECISION (same + Avail DA) |
| scroll | Scroll | mainnet, sepolia | NEEDS-DECISION (container-only, amd64 image; 2.4 TB snapshot) |
| immutable | Immutable zkEVM | mainnet, testnet | NEEDS-DECISION (container-only, amd64 image) |
| morph | Morph | mainnet, hoodi | NEEDS-DECISION (container-only, 2 processes) |
| xlayer | X Layer | mainnet, testnet | NEEDS-DECISION (container-only; OP Stack — belongs to the OP family) |
| sophon | Sophon | — | **DEFERRED** (chain being wound down) |
| polygonzkevm | Polygon zkEVM | — | **DEFERRED** (sunset 2026-07-01, chain halted) |

---
## xai — Xai (Arbitrum Orbit AnyTrust, parent = Arbitrum One) — READY
* **Client**: Nitro `OffchainLabs/nitro` v3.12.1 (VERIFIED `gh api releases/latest`, 2026-10-02). No release assets; image `offchainlabs/nitro-node:v3.12.1-70fa99a` (VERIFIED: release body; manifest list amd64+arm64). Artifact URL = `docker://offchainlabs/nitro-node:{tag}` (existing `ArbNitroDockerPack`).
* **Chain selection**: `--chain.info-files=<xai-chain-info.json> --chain.name=Xai`. Chain-info (chain id 660279, parent 42161, rollup `0xC47D…1336`, sequencer-inbox `0x995a…8B1`, deployed-at 166757506) extracted from Offchain Labs community-helm-charts nitro README "Xai" section (VERIFIED: file `xai/xai-chain-info.json`; the sequencer-inbox contract code exists on Arbitrum One, `eth_getCode` non-empty; chain id `0xa1337` = 660279 from `xai-chain.net/rpc`; same values as Xai gitbook "Connect to Xai Mainnet").
* **Required flags**: `--parent-chain.connection.url=<Arbitrum One RPC>` (no blob/beacon: parent is Arbitrum), `--node.feed.input.url=wss://xai-chain.net/feed`, `--execution.forwarding-target=https://xai-chain.net/rpc`, AnyTrust: `--node.data-availability.enable --node.data-availability.sequencer-inbox-address=0x995a9d3c… --node.data-availability.parent-chain-node-url=<same Arb One RPC> --node.data-availability.rest-aggregator.enable --node.data-availability.rest-aggregator.online-url-list=https://xai-chain.net/das-servers` (list endpoint returns 6 REST servers, VERIFIED curl).
* **Ports** (block 42710): http 42710, ws 42711, metrics 42714. Nitro defaults 8547/8548/9642 not used.
* **Disks/sizing**: execution NVMe; min 1 TB / 16 GB / 4 cores per Xai gitbook (stale, Nitro v2.3.3 era). Draft: hint 2048, full 1024, archive 3072 (INFERRED), 4 cores, 16 GB.
* **Snapshot**: none (Xai gitbook lists none; `snapshot.arbitrum.foundation/xai/…` 404). Sync from genesis through L1 batches + DAS (days; chain at block 137M). `snapshot: never`.
* **Tip**: `https://xai-chain.net/rpc` (VERIFIED eth_blockNumber `0x831f5d4`; ~2.2 s/block).
* **Drafts**: `xai/network.yml`, `xai/clients.yml`, `xai/node.service.tmpl`, `xai/xai-chain-info.json`.

## starknet — Starknet (Nethermind Juno) — READY
* **Client**: `NethermindEth/juno` v0.16.8 (2026-10-06). Assets VERIFIED (`gh api` + `curl -sIL` 200): `https://github.com/NethermindEth/juno/releases/download/{tag}/juno-{tag}-linux-amd64.zip` (62,807,342 B) and `…-linux-arm64.zip` (58,411,873 B); zip holds one static ELF `juno-v0.16.8-linux-amd64` (+ `.sha256`). Pathfinder (`eqlabs/pathfinder` v0.24.2, tarballs `pathfinder-{x86_64,aarch64}-unknown-linux-gnu.tar.gz`) is an equally valid alternative, not chosen (Juno has pruning + public snapshots with a stable URL).
* **Chain selection**: `--network mainnet|sepolia`. L1: `--eth-node wss://…` (Juno verifies against the core contract; `--disable-l1-verification` exists). Public default `wss://ethereum-rpc.publicnode.com` (WS INFERRED; HTTP side verified).
* **Ports** (block 42530): http 42530, ws 42531, metrics 42534; sepolia 42540/42541/42544. Juno defaults 6060/6061/9090/6062/6064 not used. p2p (experimental) and grpc off.
* **Disks/sizing**: db NVMe + snapshots SSD. Snapshot sizes (HEAD 2026-10): mainnet 548.9 GB, mainnet-pruned 101.8 GB, sepolia 100.2 GB, sepolia-pruned 26.0 GB. Hardware per docs: normal 4 cores / 8 GB, heavy 8 cores / 16 GB.
* **Snapshot**: required, `juno-snapshots.nethermind.io/files/<slug>/latest` (see FAMILY §7). Pruned snapshot + `--prune-mode=N` (N = retention blocks, irreversible, not combinable with `--new-state`).
* **Height**: `starknet_blockNumber` -> integer. **Tip**: `https://starknet-rpc.publicnode.com` (16335870 VERIFIED), `https://api.zan.top/public/starknet-mainnet`, sepolia `https://starknet-sepolia-rpc.publicnode.com` (VERIFIED). Dead: blastapi, lava.build, free-rpc.nethermind.io (no answer).
* **Drafts**: `starknet/{network.yml,clients.yml,node.service.tmpl}`.

## zksync — ZKsync Era (ZK Stack external node) — NEEDS-DECISION
* **Client**: `matter-labs/zksync-era` external node; **container-only** (`matterlabs/external-node:<tag>` Docker Hub, amd64+arm64 ~170 MB; releases `core-v31.5.0` have 0 assets; VERIFIED). Docs: Docker/compose only; "run it outside Docker (not supported)". Source build possible (`cargo build --bin zksync_external_node`, Rust + the contracts submodule artifacts) but heavy and unsupported -> not proposed. Pin: `v29.4.0` (docs compose on main; VERIFIED tag exists).
* **Decision**: A) registry pack (recommended: ~80 MB of layers incl. binary, sqlx, contracts, migrations; verified contents) B) build fleet (needs zkstack contract build) C) skip. Alternatives for Postgres: system apt PG (recommended, TON pattern) vs external `DATABASE_URL` option.
* **Chain config**: `EN_MAIN_NODE_URL=https://zksync2-mainnet.zksync.io` (docs; `https://mainnet.era.zksync.io` also VERIFIED alive), `EN_L2_CHAIN_ID=324`, `EN_L1_CHAIN_ID=1`, `EN_GATEWAY_URL=https://rpc.era-gateway-mainnet.zksync.dev`; sepolia: `https://sepolia.era.zksync.dev`, 300, 11155111, gateway `https://rpc.era-gateway-testnet.zksync.dev`.
* **Ports** (block 42800): http 42800, ws 42801, metrics 42804, health 42805 (PG: host server; consensus gossip not enabled — would be +2); sepolia 42810…42815.
* **Sizing**: docs: 32 GB RAM + modern CPU; mainnet 500 GB (snapshot+pruning), testnet 50 GB; archive/dump: 3 TB node + 8 TB Postgres, 32 cores/64 GB. Pruned+recovered Postgres < 500 GB, +15 GB/day unpruned.
* **Snapshot**: GCS `zksync-era-mainnet-external-node-snapshots` / `zksync-era-boojnet-external-node-snapshots` (VERIFIED listing). Recovery 5-6 h mainnet (Postgres 1 h, tree 3 h, catch-up 1-2 h, docs). Via node. No public Era PG dump.
* **Tip**: `https://mainnet.era.zksync.io` (VERIFIED), `https://sepolia.era.zksync.dev`.
* **Drafts**: `zksync/{network.yml,clients.yml}` + shared `zkstack/{node.service.tmpl,en.env.tmpl,pg-provision.sh.tmpl}`.

## abstract — Abstract — NEEDS-DECISION (same as zksync)
* Chain: id 2741 (`0xab5` VERIFIED), L1 1, main node `https://api.mainnet.abs.xyz` (VERIFIED), EN image `v31.1.0` from `Abstract-Foundation/abstract-node` `docker/.env.mainnet` (VERIFIED, tag exists amd64+arm64). Testnet: `https://api.testnet.abs.xyz`, chain 11124 (`0x2b74` VERIFIED), L1 11155111. Snapshot buckets `raas-abstract-mainnet-external-node-snapshots`, `abstract-testnet-external-node-snapshots` (VERIFIED). Extra env: `EN_PRUNING_ENABLED` default true, `EN_STATE_KEEPER_L2_BLOCK_COMMIT_DEADLINE=200 ms`, `EN_API_SEND_RAW_TX_SYNC_DEFAULT_TIMEOUT_MS=2000`, `EN_API_SEND_RAW_TX_SYNC_MAX_TIMEOUT_MS=10000`; consensus disabled upstream. Docs: 32 GB RAM, 300 GB mainnet, +1 TB/month unpruned.
* **Ports** (block 41000): 41000/41001/41004/41005; testnet 41010…41015.
* **Drafts**: `abstract/{network.yml,clients.yml}`.

## lens — Lens Chain — NEEDS-DECISION (same + Validium/Avail)
* Chain id 232 (`0xe8` VERIFIED), L1 1, main node `https://rpc.lens.xyz`; testnet 37111 (`0x90f7` VERIFIED), `https://rpc.testnet.lens.dev`, L1 Sepolia. Image `v29.1.2` (`lens-protocol/lens-chain-node` compose, repo untouched since 2025-09-18 — compatibility with today's main node INFERRED). Components `core,api,da_fetcher,tree_fetcher` mainnet (treeless) / `all,da_fetcher` testnet. Avail: mainnet app id 26, bridge `https://bridge-api.avail.so`, node `wss://mainnet.avail-rpc.com`; testnet app id 247, `https://turing-bridge-api.avail.so`, `wss://turing-rpc.avail.so/ws` (all from the repo compose, VERIFIED). Snapshot bucket `raas-lens-mainnet-external-node-snapshots` (VERIFIED, ~23 GB); testnet snapshots disabled. Archive: `https://lens-chain-db-dump.s3.us-east-1.amazonaws.com/endb.dump` 98,946,775,665 B, Last-Modified 2025-06-19 (stale). Hardware: ~16 cores, 64 GB, 2 TB.
* **Ports** (block 41840): 41840/41841/41844/41845; testnet 41850…41855.
* **Drafts**: `lens/{network.yml,clients.yml}`.

## scroll — Scroll (l2geth) — NEEDS-DECISION
* **Client**: `scroll-tech/go-ethereum` ("l2geth"), GitHub tags `scroll-v5.10.x` (releases 0 assets, latest release v5.10.2 2026-01-15); Docker Hub `scrolltech/l2geth:scroll-v5.10.10` (2026-09-30; **amd64 only**, single binary `usr/local/bin/geth`, dynamic glibc; VERIFIED by downloading the layer). Go build possible (upstream Dockerfile: `go run build/ci.go install ./cmd/geth`, golang 1.26.8) -> option B for arm64.
* **Chain selection**: `--scroll` (chain 534352) / `--scroll-sepolia` (534351), `--scroll-mpt` with the official MPT snapshot (VERIFIED in `cmd/utils/flags.go`). Mainnet needs `--l1.endpoint <eth>` + blob source (`--da.blob.beaconnode`, `--da.blob.blobscan`, `--da.blob.awss3 https://scroll-mainnet-blob-data.s3.us-west-2.amazonaws.com/` — docs; bucket answers 403 at root = fine). Min binary per docs: ≥ scroll-v5.8.38.
* **Ports** (block 42320): http 42320, ws 42321, p2p 42322 (tcp+udp), metrics 42324 (`--metrics.port`, 127.0.0.1); sepolia 42330… Defaults 8545/8546/30303/6060 not used.
* **Sizing**: docs: 8-core (t3.2xlarge class), 4 TB SSD; the archive snapshot is ~2 TB on disk (tar 2.47 TB) -> need ≥ 4 TB if downloaded then extracted; stream to avoid.
* **Snapshot**: optional, mainnet only, `https://snapshot.scroll.io/mpt/latest.tar` (VERIFIED size, Range, tar layout `data/geth/…`, last-modified 2026-06-09 = ~4 months of catch-up). No sepolia snapshot found.
* **Tip**: `https://rpc.scroll.io` (VERIFIED, ~7 s/block avg), `https://scroll-rpc.publicnode.com`; sepolia `https://sepolia-rpc.scroll.io` (returned 526 at test time; publicnode sepolia unsupported).
* **Drafts**: `scroll/{network.yml,clients.yml,node.service.tmpl}`.

## immutable — Immutable zkEVM (Immutable Chain) — NEEDS-DECISION
* **Client**: `immutable/immutable-geth` v1.0.0-beta.17 (2026-01-14; releases 0 assets). Image `ghcr.io/immutable/immutable-geth/immutable-geth` (**amd64 only**, alpine, static: Dockerfile builds `-static`). Source build: golang 1.21, `go run build/ci.go install -static ./cmd/geth` (VERIFIED Dockerfile) -> B for arm64.
* **Not Polygon CDK anymore**: public RPC reports `Geth/v1.0.0-beta.17-952166b1/linux-amd64/go1.21.13` (VERIFIED), Clique PoA, chain id 13371 mainnet / 13473 testnet (VERIFIED `eth_chainId` on both public RPCs).
* **Run** (docs): `geth immutable bootstrap rpc --zkevm mainnet --datadir D` once; then `geth --zkevm mainnet --config mainnet-public.toml --datadir D --http … --gossipdefault --disabletxpoolgossip --rpcproxy`. `immutable/config/{mainnet,testnet}-public.toml` fetched from repo raw at the tag (VERIFIED 200; static/trusted nodes `partner-public-{0,1,2}.p2p.immutable.com:30300`; `SyncMode=full`, `NoPruning=true`, `StateScheme=hash`).
* **Ports** (block 41690): http 41690, ws 41691, p2p 41692, metrics 41694; testnet 41700… (upstream p2p 30300 not used).
* **Sizing**: README: ≥ 1 TB, 16 GB+, 4 cores recommended (2 cores/4 GB minimum); docs page: 100 GB minimal. No snapshot; full sync from genesis (duration unknown).
* **Tip**: `https://rpc.immutable.com` (VERIFIED `0x2abe815`), `https://rpc.testnet.immutable.com` (VERIFIED). No L1 needed.
* **Drafts**: `immutable/{network.yml,clients.yml,node.service.tmpl}`.

## morph — Morph — NEEDS-DECISION
* **Client**: execution `morph-l2/go-ethereum` (`morph-v2.2.6`, image `ghcr.io/morph-l2/go-ethereum:2.2.6`, amd64+arm64, binary `usr/local/bin/geth`) + consensus/derivation `morph-l2/morph` (`v0.6.3`, `ghcr.io/morph-l2/node:0.6.3`, amd64+arm64, `usr/local/bin/morphnode`) (all VERIFIED by layer listing; releases have 0 assets). Official non-Docker path = build with Go (`make geth`, `make morphnode`; CGO) per run-morph-node Makefile. v0.6.0 was a hard fork on 2026-07-28 ("every node must upgrade"). `morph-reth` alternative exists but "internal testing, not for production" (README).
* **Run**: geth flags `--morph|--morph-hoodi --morph-mpt --datadir … --authrpc.jwtsecret … --gcmode=archive`; morphnode env `MORPH_NODE_*` + `--home <node-data> --mainnet|--hoodi` (all from `entrypoint-geth.sh` / `entrypoint-node.sh`, VERIFIED). `L1_BEACON_CHAIN_RPC` is mandatory in both verify modes. Constants mainnet: L1 id 1, rollup `0x759894ced0e6af42c26668076ffa84d02e3cef60`, L1MessageQueue `0x3931ade842f5bb8763164bdd81e5361dce6cc1ef`; hoodi: L1 id 560048, rollup `0x57e0e6dde89dc52c01fe785774271504b1e04664`, queue `0xd7f39d837f4790b215ba67e0ab63665912648dbe`.
* **Config files**: repo `morph-l2/run-morph-node` (no tags; use `main`): `mainnet|hoodi/node-data/config/{config.toml,genesis.json}`, `…/geth-data/static-nodes.json`. config.toml ports to patch: `proxy_app` 26658, `[rpc] laddr` 26657, `[p2p] laddr` 26656, prometheus 26660 (VERIFIED defaults) -> catalog.
* **Ports** (block 42050): http 42050, ws 42051, p2p 42052, engine 42053, geth metrics 42054, cl rpc 42055, cl p2p 42056, cl metrics 42057, abci 42058; hoodi 42060…42068.
* **Sizing**: snapshots 68 GB full / 368 GB archive (mainnet), 15 / 28 GB (hoodi). Draft hints 512 / 128 GiB, 8 cores/32 GB (INFERRED; Morph publishes no requirements in README).
* **Tip**: `https://rpc.morphl2.io` (VERIFIED `0x1aee712`), `https://rpc-quicknode.morphl2.io`; hoodi `https://rpc-hoodi.morph.network` (VERIFIED `0x9cc338`, chain id 2910; mainnet chain id 2818).
* **Drafts**: `morph/{network.yml,clients.yml,node.service.tmpl,morphnode.service.tmpl}`.

## xlayer — X Layer (OKX; OP Stack) — NEEDS-DECISION
* **Client**: `okx/xlayer-reth` (Rust 1.94; releases v0.0.7 testnet-only prerelease, v0.0.6 …; **0 assets**; Docker Hub `xlayer/xlayer-reth:v0.0.6.3` amd64+arm64) + op-node fork `okx/optimism` tag `xlayer/v0.1.6[.1]` (Docker Hub `xlayer/op-node:v0.1.5.1`/`v0.1.6.1`). Pair used by OKX's own `xlayer-toolkit/rpc-setup/presets/network-presets.env`: reth v0.0.6.3 + op-node v0.1.5.1 (VERIFIED). OKX also still publishes an op-geth path (`xlayer/op-geth:v0.1.5.3`) — not proposed.
* **Run** (VERIFIED from `rpc-setup/docker-compose.yml`): reth `node --chain xlayer-mainnet --config <toml> --rollup.sequencer-http https://rpc.xlayer.tech --rpc.legacy-url https://xlayerrpc.okx.com --disable-discovery --trusted-peers <enodes> --authrpc…`; op-node `--rollup.config rollup-mainnet.json --syncmode=execution-layer --l2.enginekind=reth --p2p.bootnodes … --p2p.static … --l1 … --l1.beacon …`. rollup/TOML presets from `okx/xlayer-toolkit/rpc-setup/presets/` (raw GitHub). Chain ids mainnet 196 (`0xc4` VERIFIED), testnet 1952 (`0x7a0` VERIFIED via testrpc.xlayer.tech).
* **Ports** (block 42740): http 42740, ws 42741, p2p 42742, engine 42743, metrics 42744, cl rpc 42745, cl p2p 42746, cl metrics 42747, reth discv5 UDP 42748 (`--discovery.v5.port`, op-reth always binds discv5); testnet 42750…42758.
* **Snapshots**: `static.okx.com/cdn/chain/xlayer/snapshot/reth-mainnet-latest` -> `xlayer-mainnet-reth-202610091200.tar.gz` 149,508,884,104 B; testnet `…202610090300.tar.gz` 75,994,488,914 B (VERIFIED). Genesis sync available only on mainnet; testnet snapshot-only. geth snapshot pointer returned 500.
* **Sizing**: docs 8 GB min / 16 GB+; 200-500 GB SSD; archive reth ~150 GB at snapshot time. Draft: 1024 hint, 8 cores/32 GB.
* **Tip**: `https://rpc.xlayer.tech` (VERIFIED `0x45c41b8`, 1 s/block), `https://xlayerrpc.okx.com`; testnet `https://testrpc.xlayer.tech` (VERIFIED).
* **Drafts**: `xlayer/{network.yml,clients.yml,node.service.tmpl,cl.service.tmpl}` (+ two build Dockerfiles still to write, modelled on `chains/optimism/*.Dockerfile` and the repo's `DockerfileOp`).

## sophon — DEFERRED
Reason: official docs (sophon-org/sophon-docs, `migration.mdx`, `nodes/full-nodes.mdx`, last commit 2026-10-11) say the chain "is being wound down", deposits disabled since 2026-06-25, "no path for launching a new Sophon full node", operational until "late 2026". `rpc.sophon.xyz` still answers (`0xc3b8`/`0x1c4cb41` VERIFIED) and `sophon-org/sophon-en-config` exists (EN `v29.14.0`, chain 50104 / testnet 531050104, Avail app 102, consensus gossip on, bucket `raas-sophon-mainnet-external-node-snapshots` EMPTY listing). If forced: identical to `lens` with those parameters; block 42470 reserved.

## polygonzkevm — DEFERRED
Reason: Polygon zkEVM Mainnet Beta sunset on 2026-07-01 (forum.polygon.technology "Sunsetting Polygon zkEVM Mainnet Beta in 2026"; search result 2026-10-14). Public RPC `zkevm-rpc.com` still answers but the latest block (33,391,890) is timestamped 2026-07-03 15:55 UTC and Cardona (24,180,081) 2026-07-03 12:03 UTC (VERIFIED) -> chain halted, nothing to sync. Software for the record: `0xPolygonHermez/zkevm-node` v0.7.3 (2024-08-07, native `zkevm-node_0.7.3_linux_{amd64,arm64}.tar.gz` 28 MB VERIFIED; needs its own PostgreSQL + prover) and `0xPolygon/cdk-erigon` v2.65.0-RC3 (no assets) — neither useful now. Block 42230 reserved.
