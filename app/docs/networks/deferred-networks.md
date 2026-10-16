# Networks not shipped yet — register (2026-10-14)

Status of every network from the "add the missing networks" request. Nothing here is abandoned: each row says why it is not
done and what unblocks it. Detail lives in the research folders (`docs/networks/research/<family>/{FAMILY,networks,risks}.md`
and the drafts under `research/drafts/`) and in the per-family intake docs (`*-intake.md`).

Nothing shipped was run on a Linux host: every network below marked SHIPPED is verified by unit tests and upstream
docs / source only. See the `INFERRED` lists in each intake doc.

## 1. Shipped

| Family | Networks |
|---|---|
| Already in the product | bitcoin, bch, dash, doge, ltc, zcash, ethereum, polygon, bsc, avalanche, solana, ton, tron, sui, xrpl, hyperliquid, arc, arb, base, optimism, robinhood |
| Added in this round, single recipes | aptos, etc (Ethereum Classic), stellar, cardano (+ Ogmios by default, optional db-sync), starknet, xai |
| CometBFT family (`chains/cometbft`) | cosmos (mainnet), osmosis, injective (mainnet), sei, celestia (mainnet, consensus node only), xrplevm |
| OP Stack family (`chains/opstack`) | unichain, ink, soneium, worldchain, lisk (mainnet) |
| EVM L1 family (`chains/evmfamily`) | gnosis, joc (Japan Open Chain), flare, sonic, kaia, tempo, berachain (mainnet), fluent, gravity (L1), monad (see section 2) |
| Non-EVM | polkadot (+ `rest-api` and `history` indexers), near, fuel, stacks, aleo (mainnet) |

## 2. Shipped but partial

| Network | What is missing |
|---|---|
| mode, zora | No public snapshot exists (Conduit's bucket is requester-pays): works only with the operator's `snapshot_url`, otherwise weeks of L1 derivation. Sepolia has no known peers. Documented mode `execution-layer` with Conduit's EL peer not used (open choice). |
| vana | Units done, snapshot `never`: official GCS tarballs (238 GB + 82 GB) exist but it is unconfirmed which network they belong to and which geth state scheme they use. Mainnet is x86_64 only. Needs a confirmation, then wire as a snapshot. |
| berachain | Mainnet only; bepolia needs `beacond v1.4.2-rc.0` pinned by hand from the docs table (newer prereleases have no assets). |
| cosmos, injective, celestia | Mainnet only. Testnets (cosmos `provider`, injective `injective-888`, celestia `mocha-5`) ship prerelease / mismatched builds and have no verified seeds. |
| sei | No state sync: bootstrap is the Polkachu snapshot (about 234 GB lz4). |
| polkadot | `history` indexer indexes from the head by default (genesis backfill is weeks; measured 9–12 blocks/s once). The relay chain has almost no transfers after the Asset Hub migration: transfer history needs an Asset Hub node (below). The GraphQL API listens on all interfaces by decision (no auth, no rate limit; Apollo Server v3 is end of life, 9 npm advisories). |
| monad | Shipped 2026-10-14 (`monad-intake.md`), **never run on hardware**. Bare metal Ubuntu 24.04 amd64 only, ≥ 16 CPUs, a dedicated NVMe partition for TrieDB given as an install option (the toolkit never partitions or wipes a drive; the Snapshot step reformats the empty partition you name). Pinned to the versions of the Monad docs (mainnet 0.16.4, testnet 0.16.3), 737 MB .deb unpacked under node_dir. INFERRED: `monad-rpc` flag names, optional `--otel-endpoint`, `monad-sign-name-record` output keys. No metrics collector, no firewall rules. |
| cardano | db-sync and Ogmios archives are synced to every Cardano node (shared clients dir); no aarch64 db-sync; no db-sync snapshots (IOG); Ogmios and db-sync were tested upstream against node 11.0.1, the toolkit installs 11.1.x. |

## 3. Not done — decided by the user

| Network | Decision |
|---|---|
| data (DATA Network, ex Story Protocol, chain 1514) | Not now (user, 2026-10-14). Research kept in `research/evml1`. |
| fantom (Fantom Opera) | Not now, note for the future. Separate chain from Sonic; client Fantom-foundation/Sonic `v1.2.1-k` ships no binary assets, genesis URLs in its docs return 404 → needs a source build. |
| shibarium ("Shiba Inu") | Not now. bor / heimdall `-bone` debs (Polygon v1 fork); newest Shibarium tags date from Feb / Nov 2024 and master mirrors Polygon: ask the ShibaOne team which versions the live network needs. |
| moca | Not now. `mocad` v1.4.1 (Cosmos EVM) is public, but no genesis, seeds or state-sync config is published and the README says not production-ready. |

## 4. Blocked by a missing build mechanism (user: "docker we don't use")

The existing `build:` mechanism (`FleetClientBuilder`, used by optimism / xrpl) builds from a Dockerfile on a build host. The user
does not want Docker. Unblock = a **native source-build step** (pinned Go / Rust toolchain under `node_dir`, `git clone` of the
pinned tag, `go build` / `cargo build`, output kept as a versionless artifact), shared by these:

| Network | Why it needs a build |
|---|---|
| celo | Needs three source builds (celo-reth / op-node / eigenda-proxy forks); upstream op-node cannot run Celo mainnet (`sync-superchain.sh` skips chain 42220); the EigenDA proxy has no arm64 binary. |
| mantle | Not in the OP registry; own forks; mainnet version unclear (v1.5.4 live vs v1.6.3, mainnet date unpublished). |
| peaq | No release assets (only a Docker image); tag `peaq-v0.0.113` has no GitHub release object; `chains/substrate` is reusable. Relay chain embedded (or external polkadot node). |
| aleo testnet | `testnet-v*` tags carry no assets and use a different snarkVM: source build. (aleo mainnet is shipped.) |
| xlayer | Now an OP Stack chain (xlayer-reth + op-node fork, builds from `okx/optimism`) — would also go into `chains/opstack`. |
| fantom | see section 3. |

## 5. Blocked by container-only clients

Upstream releases carry no assets; the client is distributed only as a Docker image. Unblock = **registry-layer extraction**
(generalising `ArbNitroDockerPack` and its registry client: download the image layers over the registry API and unpack them under
`node_dir`, no Docker daemon). The user has not yet said whether this counts as "no Docker". Sizing from the research: about 1.5–2
days for the generic layer plus about 0.5 day per client, per-network recipes dominate.

| Network | Notes |
|---|---|
| zksync (ZK Stack external node), abstract, lens | Shared recipe `rollups/zkstack/`. Needs PostgreSQL (existing host server, tablespace under `node_dir`), `WorkingDirectory` with `contracts/`, `etc/`, `migrations/`, `sqlx database setup` before start; the EN restores its own GCS snapshot (no resolver); Era also needs `EN_GATEWAY_URL`. |
| scroll | `scrolltech/l2geth` image is amd64 only; only snapshot is a 2.47 TB archive tar (layout `data/geth/...`, strip one level). |
| immutable (zkEVM) | `immutable-geth` image, amd64 only, no snapshot. |
| morph | Two processes (geth fork + morphnode); snapshots must match three derivation heights listed only in a GitHub README table. |
| xlayer | see section 4. |

## 6. Halted, closed or without a public client

| Network | Reason |
|---|---|
| sophon | Official docs say the chain is being wound down (deposits off since 2026-06-25); snapshot bucket listing is empty. |
| polygonzkevm | Mainnet Beta sunset 2026-07-01; mainnet and Cardona RPCs froze at blocks timestamped 2026-07-03. |
| redstone | Shut down 2026-05-15, RPC dead. |
| blast | Shutdown announced 2026-10-02, withdrawals until 2026-10-26. |
| b3 | Archived (L2BEAT); an L3 on Base with Celestia DA; RPC dead or stale. |
| cyber | Custom alt-DA server; no node docs, no snapshot. |
| fraxtal | op-node fork published only as a ghcr image (no public source); testnet runs on Hoodi. |
| hemi | Multi-daemon stack with Docker-first docs. |
| hedera | Consensus nodes are permissioned; the mirror node needs paid requester-pays buckets plus PostgreSQL; the JSON-RPC relay is a proxy, not a node. Ports block 41630–41659 stays reserved. |
| plasma | Reth plus the closed `plasma-consensus-public` (container image only). |
| megaeth | Only a stateless validator exists, no full node. |
| ault | `aultd` is not public; every artifact URL in its docs is a placeholder. |
| sahara | `saharad` is docker-image only; only a docker-compose testnet node exists. |
| zerog (0G Chain) | Researched in the CometBFT family (reth + `0gchaind`, two units, `snapshot: never`, needs a public-IP source for reth `--nat extip`), not implemented: the reth / `0gchaind` flags beyond the docs are INFERRED and no official snapshot exists (genesis blocksync, duration unmeasured). Research: `research/cosmos/networks.md` (zerog section), drafts `research/drafts/cosmos/zerog/`. |

## 7. Smaller leftovers inside shipped networks

- **CometBFT:** state sync (Sei would avoid the 234 GB snapshot; Injective cannot use it without `wasm/`), Cosmovisor / halted-upgrade detection (a governance upgrade halts the node until *Update client*), Celestia DA nodes (bridge / full / light; a bridge needs an archival consensus node), Injective `[json-rpc]` key names are INFERRED (check `app.toml` after the first `init`).
- **OP Stack:** Alchemy snapshots (unichain / ink / soneium) are third party, no checksum, found by scraping a page; the op-reth datadirs of Alchemy / World / Gelato snapshots were not opened with the pinned binaries. Unichain defaults to `restore=none`.
- **Stacks mainnet:** goes through the Bitcoin node's login-free RPC proxy (port 8432, no credentials; they only matter for a miner — VERIFIED in the stacks-core 4.0.5 docs, but the follower path without credentials was not run).
- **Starknet:** pruned snapshot runs with a 100000-block window (disk effect not measured).
- **EVM L1:** x86_64-only networks (flare, sonic, fluent, gravity, vana mainnet) get "no files for arch" on aarch64 hosts instead of a friendly wizard refusal; Kaia mainnet restore needs about 2.8 TB free and `zstd`.
- **Production RPC tuning not yet generated** for solana, sui, ton, hyperliquid, polygon (`config.toml`), avalanche (`config.json`); Dogecoin `[main]` section bug (see `prod-rpc-tuning.md`).
- **Admin parent picker:** `chains/optimism` was updated to the new OP Stack safe defaults (narrow API, loopback, own discv5 port); `base` / `arc` still expose `debug` until the prod preset is applied.
- **Polkadot Asset Hub** (para 1000; Westend / Paseo have their own) is not a shipped network: the history squid could index it, it needs a node.

## 8. How to pick one up later

1. Read the family's research folder and the row above; surface the open decision first.
2. Add the id to `NetworkId` (done for the whole original list), `ShippedNetworkIds.ALL`, the wiring map of its family package, `AgentMain` runtime map.
3. Keep literal `clientConfig.bindings` equal to the code constants (see `CometbftCatalogTest` for the pattern) and every port unique (`research/PORTS.md`).
4. Snapshots (resolver + agent runner or a justified `never`), prod config (prodPresets, nothing admin on 0.0.0.0), tests, intake doc with VERIFIED / INFERRED marks.
