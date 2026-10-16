# OTHER ROLLUPS — shared design (research 2026-10-13/14; every fact tagged VERIFIED(how) or INFERRED)

Networks: xai, zksync, abstract, lens, sophon, polygonzkevm, scroll, xlayer, immutable, starknet, morph. Per-network table: `networks.md`. Open decisions: `risks.md`.

## 0. Headline findings

* **Only Starknet (Juno) ships a native release binary** (VERIFIED: `gh api releases` + `curl -sIL`: `juno-v0.16.8-linux-{amd64,arm64}.zip`, 62.8 / 58.4 MB, one static ELF inside). Every other client here is container-only upstream (GitHub releases have 0 assets: nitro, zksync-era, scroll go-ethereum, immutable-geth, morph go-ethereum/morph, xlayer-reth, okx/optimism).
* **Two networks are effectively dead -> DEFERRED**: Polygon zkEVM (Mainnet Beta sequencer sunset 2026-07-01; public RPCs freeze at block time 2026-07-03, VERIFIED by `eth_getBlockByNumber latest`) and Sophon (docs: chain "being wound down", deposits off since 2026-06-25; sophon-docs migration.mdx, commit 2026-10-11).
* **X Layer is not CDK/erigon any more**: it is an OP Stack chain (xlayer-reth + okx/optimism op-node; rollup.json genesis l2_time 1761567143 = 2025-10-27, L2 genesis block 42,810,021). It belongs with the OP family; drafted here only because it was assigned.
* **Delivery for container-only clients** — three precedents already exist in the repo; this family picks per client:
  * **R. Registry-layer pack** (arb/robinhood: `ArbNitroDockerPack`, `docker://` URL, no Docker daemon). Verified feasible for every image below (anonymous token + manifest list + layer blobs for docker.io AND ghcr.io).
  * **B. Fleet build** (`build:` Dockerfile in clients.yml; optimism, solana agave) — needed for arm64 where the image is amd64-only (scroll l2geth, immutable-geth) and for Rust/Go forks without a CI image.
  * **S. On-host source build** (optimism run.sh fallback) — last resort.
  The brief says "no Docker install path"; R is not Docker (HTTPS blob download + tar), same as the shipped Arbitrum recipe. Treated as acceptable but listed as NEEDS-DECISION in `risks.md`.

## 1. Family packages (what is shared)

```
rpcnode.toolkit.shared.infrastructure.registry      NEW  (generalised from chains.arb.infrastructure.docker.*)
    RegistryImageClient(host: docker.io | ghcr.io)  token + manifest-list (amd64/arm64) + blob download
    RegistryImagePack(spec: ImagePackSpec)          apply layers in order (whiteouts), keep only wanted paths, repack tar.gz
    ImagePackSpec(imageRef, wanted: List<Pick(srcPrefix, destPrefix, required)>, mustExist: List<String>)
    RegistryAwareArtifactDownloader                 routes docker://<host?>/<repo>:<tag> (ArbAwareArtifactDownloader becomes a thin subclass)
rpcnode.toolkit.chains.nitro                         NEW thin sharing of arb pieces (ArbNodeHeightProbe -> NitroNodeHeightProbe, ArbL1Parent, ArbNitroDockerTags, ArbClientReleaseResolver)
rpcnode.toolkit.chains.zkstack                       NEW  (zksync, abstract, lens, [sophon])
    ZkStackCluster(env, l2ChainId, l1ChainId, mainNodeUrl, gatewayUrl?, snapshotBucket, imageTag, validium: DaClient?, components, extraEnv)
    ZkStackEnvFile.render(cluster, ports, dirs, options)      renders zkstack/en.env.tmpl
    ZkStackUnitBodies.node(...)                                renders zkstack/node.service.tmpl
    ZkStackPgProvision.script(...)                             renders zkstack/pg-provision.sh.tmpl
    ZkStackNodeProcessStarter (abstract)                       unpack tar -> <node_dir>/zk-root, write env + db env, installCustomUnits
    ZkStackNodeHeightProbe / ZkStackNetworkTipProbe            = EthereumEthRpc (eth_blockNumber) + snapshot-recovery progress
    ZkStackNodeStart(abstract)                                 launch.kind="binary", height.kind="eth_rpc"
  per network (thin): chains/zksync|abstract|lens/…  *Clusters.kt, *Ports.kt, *NodeStart.kt (object overriding networkId + cluster table)
rpcnode.toolkit.chains.gethfork                      OPTIONAL: scroll, immutable, morph-geth share  GethForkUnitBodies(flag list) + EthereumEthRpc probes
```

Per-network thin classes (all follow the intake naming): `<Id>NodeStart`, `<Id>NodeProcessStarter`, `<Id>NodeHeightProbe`, `<Id>NetworkTipProbe`, `<Id>ClientReleaseResolver`, `<Id>UnitBodies`, `<Id>Clusters`, `<Id>Ports` — implemented as 10-30 line classes over the family code. Templates: shared ZK templates live in `resources/chains/zkstack/` (a directory without network.yml, same as `chains/default/`; `HostSystemdUnitTemplate.load("zkstack", ...)`).

## 2. Process model per sub-family

| Family | Units | Entry | Notes |
|---|---|---|---|
| Orbit (xai) | 1: `nitro` | like arb | parent = Arbitrum One (an Orbit L3), not Ethereum -> no beacon; AnyTrust DAS flags; chain-info as classpath resource written to `<node_dir>/.toolkit/xai-chain-info.json` |
| ZK Stack (zksync, abstract, lens) | 1 unit + ExecStartPre x2 | `zksync_external_node` | ExecStartPre 1 = `pg-provision.sh` (role/db/tablespace under node_dir, idempotent), 2 = `sqlx database setup --source <zk_root>/migrations` (= what upstream `entrypoint.sh` does). WorkingDirectory = `zk_root` (VERIFIED in source: contracts are read relative to CWD, see §4). |
| Scroll | 1 | `l2geth` (geth fork, `--scroll`) | single process, no CL |
| Immutable | 1 (+ one-shot ExecStartPre bootstrap) | `geth` fork | `geth immutable bootstrap rpc --zkevm mainnet --datadir D` once (VERIFIED docs), then run with `*-public.toml` |
| Morph | 2: geth + morphnode (+ JWT shared) | geth fork + `morphnode --home` | morphnode Requires geth; config.toml ports patched; derivation heights from snapshot table |
| X Layer | 2: xlayer-reth + op-node (+ JWT) | OP shape (optimism recipe) | + `--rpc.legacy-url` for pre-OP blocks |
| Starknet | 1 | `juno` | needs L1 WebSocket (`--eth-node wss://`) |

## 3. Start args / launch spec (all `kind = "binary"`)

Arb-style encoded `--toolkit-*` args in `NodeLaunchSpec.args` (env, disk roles, snapshot flavor, L1 urls). `extractArchiveGlob`: nitro `nitro-*.tar.gz`; zk `zksync-external-node-*.tar.gz` (unpack to `<node_dir>/zk-root`); scroll `l2geth-*.tar.gz`; juno `juno-*.zip` (+ link `juno-<tag>-linux-<arch>` -> `bin/juno`; the zip's inner name is versioned). All on-disk artifact names in clients.yml are versionless.

## 4. VERIFIED details that drive the ZK design

* Image = `matterlabs/external-node:<tag>` (Docker Hub, amd64+arm64; the GitHub tags `core-vX` are NOT the image tags; chain operators publish the image tag in their compose files: Era `v29.4.0`, Abstract `v31.1.0`, Lens `v29.1.2`, Sophon `v29.14.0`). amd64 manifest of v29.1.2 downloaded and listed: `usr/bin/zksync_external_node` (47 MB gz), `usr/bin/sqlx`, `contracts/{system-contracts,l1-contracts,l2-contracts}/…zkout|out`, `etc/{tokens,ERC20,multivm_bootloaders}`, `migrations/`; ELF (dynamic, not stripped) needs glibc >= 2.34 (highest symbol GLIBC_2.34), `libssl.so.3`, `libcrypto.so.3` (host: Ubuntu 22.04+/Debian 12+; **not** Ubuntu 20.04).
* `zksync_utils::env::Workspace::locate()` (source read): without a Cargo workspace it returns `Root` = `PathBuf::from(".")`; contracts are opened as `./contracts/...`. => run with `WorkingDirectory=<zk_root>` holding `contracts/`, `etc/`, `migrations/`. (`ZKSYNC_HOME` is only required to be *set* per docs.)
* Env vars (docs prepared configs + docker-compose examples): `DATABASE_URL`, `DATABASE_POOL_SIZE`, `EN_STATE_CACHE_PATH`, `EN_MERKLE_TREE_PATH` (two different dirs), `EN_ETH_CLIENT_URL`, `EN_MAIN_NODE_URL`, `EN_L1_CHAIN_ID`, `EN_L2_CHAIN_ID`, `EN_HTTP_PORT`(default 3060) `EN_WS_PORT`(3061) `EN_HEALTHCHECK_PORT`(3081) `EN_PROMETHEUS_PORT`(3322), snapshot `EN_SNAPSHOTS_RECOVERY_ENABLED` + `EN_SNAPSHOTS_OBJECT_STORE_BUCKET_BASE_URL` + `EN_SNAPSHOTS_OBJECT_STORE_MODE=GCSAnonymousReadOnly`, pruning `EN_PRUNING_ENABLED` / `EN_PRUNING_DATA_RETENTION_SEC`, **Era only** `EN_GATEWAY_URL` (mainnet `https://rpc.era-gateway-mainnet.zksync.dev`, testnet `https://rpc.era-gateway-testnet.zksync.dev`; Era mainnet `zks_getL1BatchDetails.commitChainId` = 955461, i.e. settles through the Gateway — INFERRED from that plus the docs' `EN_GATEWAY_URL`).
* Validium chains (Lens, Sophon): `EN_L1_BATCH_COMMIT_DATA_GENERATOR_MODE=Validium`, `EN_DA_CLIENT=Avail`, `EN_DA_AVAIL_CLIENT_TYPE=FullClient`, `EN_DA_BRIDGE_API_URL`, `EN_DA_API_NODE_URL`, `EN_DA_APP_ID` (Lens mainnet 26 / testnet 247; Sophon 102), `EN_DA_TIMEOUT_MS=20000`, `EN_DA_SECRETS_SEED_PHRASE` (docs: any fresh mnemonic, never signs; generate per host), components `core,api,da_fetcher,tree_fetcher` (treeless) or `all,da_fetcher`.
* Snapshots: GCS buckets are anonymous-readable (VERIFIED `storage.googleapis.com/storage/v1/b/<bucket>/o`): `zksync-era-mainnet-external-node-snapshots` (latest l1_batch 517262, 185 chunks x ~58 MB ~ 11 GB), `zksync-era-boojnet-external-node-snapshots`, `raas-lens-mainnet-external-node-snapshots` (439 chunks x 53 MB ~ 23 GB), `raas-abstract-mainnet-external-node-snapshots`, `abstract-testnet-external-node-snapshots`. `raas-sophon-mainnet-…` listing is empty (no snapshot). **The EN restores them itself on first start -> `snapshotBootstrap: via_node`, no toolkit resolver.**

## 5. Height probe / tip probe

| Family | Local height (127.0.0.1) | Public tip | Notes |
|---|---|---|---|
| all EVM (xai, zk*, scroll, xlayer, immutable, morph) | `POST http://127.0.0.1:{http}/` `{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}` -> `{"jsonrpc":"2.0","id":1,"result":"0x831f5d4"}` (hex) = `EthereumEthRpc.blockNumber` (VERIFIED against every public tip URL) | same call on `publicTip.urls` | Xai: reuse Nitro probe (`eth_syncing=false` lies while behind -> `SyncCatchUp`). ZK: API is down during snapshot recovery -> return null; optional recovery progress: log line `storage logs are divided into N chunk(s)` + metric `snapshots_applier_storage_logs_chunks_left_to_process` (both quoted in zksync docs; metric prefix INFERRED). Scroll: node API unresponsive until the initial L1 message sync is done. |
| starknet | `POST http://127.0.0.1:{http}/` `{"jsonrpc":"2.0","method":"starknet_blockNumber","params":[],"id":1}` -> `{"jsonrpc":"2.0","result":16335870,"id":1}` (**plain integer, not hex** — VERIFIED on starknet-rpc.publicnode.com and api.zan.top) | same call on `https://starknet-rpc.publicnode.com`, `https://api.zan.top/public/starknet-mainnet` (sepolia: `https://starknet-sepolia-rpc.publicnode.com`) | new `StarknetRpc.blockNumber` (JSON integer). Juno also serves `/ready` (flag `readiness-block-tolerance` default 6 blocks; path INFERRED). |
| morph / xlayer | geth / reth `eth_blockNumber` (execution) | public RPC | the CL (morphnode / op-node) height is not the user-facing height |

Status `sync -> active` at default lag <= 3 blocks. Observed avg block interval (last 10k blocks): Xai 2.2 s, Abstract 1.8 s, X Layer 1.0 s, Morph 1.7 s, Scroll 7.1 s, Immutable 2.0 s, Era 4.3 s, Lens 27 s — 3 blocks is a tight window for the 1-2 s chains; consider a per-network `tipLagActive` in network.yml (risk R-9).

## 6. Release resolver rule

| Client | "latest" | Class |
|---|---|---|
| nitro (xai) | newest `OffchainLabs/nitro` release -> Docker Hub tag from release body (`offchainlabs/nitro-node:v3.12.1-70fa99a`, VERIFIED latest = v3.12.1 2026-10-02) | `XaiClientReleaseResolver(ArbClientReleaseResolver)` (Robinhood pattern) |
| zk EN | **pinned per chain**: scrape the chain operator's compose/env at install (Era: `docs/.../mainnet-external-node-docker-compose.yml` `matterlabs/external-node:vX`; Abstract: `docker/.env.mainnet` `EN_VERSION`; Lens: `mainnet-external-node.yml`), fall back to the `pinned:` value in clients.yml | `ZkStackComposeVersionResolver(url, regex)` |
| juno | GitHub latest release (`v0.16.8`, no prereleases) | stock `GitHubRelease` |
| scroll l2geth | newest Docker Hub tag `^scroll-v\d+\.\d+\.\d+$` (v5.10.10, 2026-09-30; GitHub releases lag at v5.10.2) | `ScrollClientReleaseResolver` (Hub tag list, like `ArbNitroDockerHubTags`) |
| immutable-geth | GitHub latest release `v1.0.0-beta.17` (not a prerelease flag) | stock |
| morph | pair `go-ethereum:2.2.6` + `node:0.6.3` read together from run-morph-node `morph-node/docker-compose.yml` | `MorphPairResolver` |
| xlayer | pair from `xlayer-toolkit/rpc-setup/presets/network-presets.env` (`MAINNET_OP_RETH_IMAGE`, `MAINNET_OP_STACK_IMAGE`); v0.0.7 is testnet-only | `XlayerPairResolver` |

## 7. Snapshot resolver design

| Network | Policy | Mechanism | Resolver |
|---|---|---|---|
| xai | never | none exist; sync from genesis via Arbitrum One inbox + DAS | none |
| zksync / abstract / lens | **required, via_node** (optional to opt out = genesis sync) | the EN pulls the GCS bucket itself | none (just env vars) |
| starknet | required | `https://juno-snapshots.nethermind.io/files/{mainnet,mainnet-pruned,sepolia,sepolia-pruned}/latest` -> redirect to `juno_<net>[_pruned]_v<juno ver>_<block>.tar.zst` (sizes 2026-10: mainnet 549 GB, mainnet-pruned 102 GB, sepolia 100 GB, sepolia-pruned 26 GB; all VERIFIED by HEAD). Stream `wget -O- | zstd -d | tar -x -C <db-path>` (resumable with `--tries=0 --retry-connrefused`; per docs). Needs `zstd`. Restore needs Juno >= the snapshot's version. | `JunoSnapshotResolver` (`discover: redirect`: HEAD with redirects, read Content-Disposition filename) |
| scroll mainnet | optional | one fixed URL `https://snapshot.scroll.io/mpt/latest.tar` (2,466,064,476,160 B, Range supported, last-modified 2026-06-09; VERIFIED) tar members `data/geth/...` -> `tar -x --strip-components=1 -C <datadir>` | `ScrollSnapshotResolver` (`discover: fixed`) |
| scroll sepolia, immutable | never | none published | none |
| morph | required | `https://snapshot.morph.network/{mainnet,hoodi}/snapshot[-archive]-YYYYMMDD-N.tar.gz` + `.sha256` (VERIFIED: mainnet full 68.2 GB, archive 368 GB; hoodi 15.2 / 28.5 GB). The name AND heights (`DERIVATION_START_HEIGHT`, `L1_MSG_START_HEIGHT`, `L2_BASE_HEIGHT`) are only in the run-morph-node README table -> parse it (GitHub raw). After extract: `geth/` -> `<datadir>/geth`, `data/*` -> `<node-home>/data`; reset `data/priv_validator_state.json`. No index listing (S3 root AccessDenied). | `MorphSnapshotResolver` (`discover: readme_table`) |
| xlayer | required | pointer file `https://static.okx.com/cdn/chain/xlayer/snapshot/reth-{mainnet,testnet}-latest` -> `xlayer-<net>-reth-<yyyymmddHHMM>.tar.gz` (mainnet 149.5 GB, testnet 76 GB; VERIFIED). geth variant exists but is stale/500. Move extracted `db`, `static_files`, … directly into the datadir. | `XlayerSnapshotResolver` (`discover: pointer`) |

All tarball restores: resumable aria2c/wget with Range (all hosts above return `accept-ranges: bytes` where checked: scroll yes), extract into the role leaf, the node cannot serve RPC until extract is done.

## 8. Production config (starting points; flags verified in the cited source, values are judgement)

`prodPresets.ts` proposal (`flags` = unit flags / generated-config keys via `extra_flags`):

```ts
const ZK_EN: ProdPreset = { prefix: '', flags: {   // en.env keys (generated on Start => flag-override path, like XRPL cfg sections: host side `ZkStackEnvFile.applyOverrides`)
  EN_API_NAMESPACES: 'eth,net,web3,zks,pubsub',   // no `debug` / `en` on a public port
  EN_REQ_ENTITIES_LIMIT: '10000', EN_FILTERS_LIMIT: '10000', EN_SUBSCRIPTIONS_LIMIT: '10000',
  EN_MAX_RESPONSE_BODY_SIZE_MB: '10', EN_MAX_RESPONSE_BODY_SIZE_OVERRIDES_MB: 'eth_getLogs=100',
  DATABASE_POOL_SIZE: '50', EN_LATEST_VALUES_CACHE_SIZE_MB: '512' },
  notes: ['Pruned node (snapshot recovery): RAM ~10 GB per the docs; archive ~60 GB.', 'eth_call gas cap is YAML-only (api.web3_json_rpc.eth_call_gas_cap) - not applied.'] }
zksync: ZK_EN, abstract: ZK_EN, lens: ZK_EN,
xai: NITRO,                                    // same preset as arb/robinhood (debug off, 25 MB batch response, 256 log cache, 16384 handles, L1 read window)
scroll:   { flags: { 'http.api':'eth,net,web3,scroll', 'ws.api':'eth,net,web3,scroll', 'rpc.gascap':'25000000',
            'rpc.evmtimeout':'3s', 'rpc.getlogs.maxrange':'5000', maxpeers:'50', cache:'4096' } },
immutable:{ flags: { 'http.api':'eth,net,web3', 'ws.api':'eth,net,web3', 'rpc.gascap':'25000000', 'rpc.evmtimeout':'3s', cache:'4096' } },
morph:    { flags: { 'http.api':'web3,eth,net,morph', 'ws.api':'web3,eth,net,morph', 'rpc.gascap':'25000000', maxpeers:'50' } },  // geth unit only
xlayer:   { flags: { ...RETH_RPC, 'http.api':'eth,net,web3,rpc', 'ws.api':'eth,net,web3,rpc' } },  // op-reth fork; same reth flags as base/optimism
starknet: { flags: { 'db-cache-size':'4096', 'rpc-max-block-scan':'10000', 'rpc-request-timeout':'30s',
            'rpc-max-concurrent-requests':'4096', 'rpc-max-request-queue':'4096' } },
```
Sources: Juno `_config-options.md` (defaults: rpc-max-concurrent-requests 256000, rpc-max-block-scan unlimited, rpc-request-timeout 1m, db-cache-size 1024; docs tuning page recommends 2048-4096), scroll `cmd/utils/flags.go` (`rpc.gascap`, `rpc.evmtimeout`, `rpc.getlogs.maxrange`, `maxpeers`, `cache`, `metrics.addr/port` all present), zksync `02_configuration.md`. Shipped unit already carries the safe defaults as `clientConfig.bindings` literals (identical numbers), the preset only moves them further.

**Never expose publicly**: ZK: healthcheck 3081 + Prometheus 3322 + consensus gossip 3054 (bind 127.0.0.1 / firewall), `debug` + `en` namespaces, the PostgreSQL port; Scroll: `--pprof`, `--metrics`, `debug` (upstream docs bind pprof/metrics to 0.0.0.0); Juno: `--http-update-port` (changes log level / gateway timeouts), `--pprof`, `--metrics-host`, `--grpc`; Morph: geth `authrpc` (engine) + `admin`,`debug`,`engine`,`txpool` APIs (upstream script exposes them on 0.0.0.0), tendermint RPC 26657 / ABCI 26658 / prometheus; X Layer: engine API 8552, op-node RPC (`--rpc.enable-admin`), `debug`,`miner`,`txpool`; Xai: Nitro `debug` namespace + metrics.

## 9. Wiring points

Kotlin (`app/src/main`): `NetworkId` vals (xai zksync abstract lens scroll xlayer immutable starknet morph); `EnvId` — all env ids already exist (mainnet, testnet, sepolia, hoodi); `NetworkPinOnly` untouched; `Toolkit.production()` maps: `ClientReleaseResolver`, `NetworkTipProbe`, `ChainNodeStart`, snapshot maps (starknet, scroll, morph, xlayer), artifact downloader = `RegistryAwareArtifactDownloader` for every `docker://` URL; `src/agent` `AgentMain` `ChainNodeRuntime` map (process starter + height probe) for each; `HostNodeDiagnostics` entry `zk_pg` (PostgreSQL reachable) optional; tests: `YamlNetworkFactsRepositoryTest`, `NetworksRoutesTest`, `ShippedNetworkIds.kt`, `ShippedPortCatalogTest`, start-plan + height/tip parse tests, unit-body golden tests, a test that Kotlin cluster tables == YAML pins (OP precedent).

Admin touch list: `lib/networkDocs.ts` (doc URLs), `components/NetworkIcon.tsx` (ticker/color), `lib/networkPaths.ts`, `lib/prodPresets.ts` (§8), `lib/network.ts`, `components/NodeInstallWizard/utils.ts`, `useWizardOrchestration.ts` (`wantsL1ParentPicker`: add xai/scroll/morph/xlayer/zksync/abstract/lens/starknet), `components/NodeParentChain.tsx` (`L2_NETWORKS` set; **beacon must become optional** — Xai/ZK/Juno need no beacon; Juno needs a `wss://` L1 and the test must swap the scheme), `components/InstallOptionsPicker.tsx` (snapshot flavor help texts; ZK `pruning` toggle), `lib/networksCatalog.ts` (only if parent picker keys change). Everything else is YAML-driven.

## 10. Host dependencies summary

zk: `postgresql` (apt, or the host's existing server — pattern copied from `chains/ton/indexer.sh.tmpl`), `libssl3`; glibc >= 2.34. starknet: `zstd`, `unzip` (+`wget`). scroll/morph/xlayer: none at runtime beyond glibc. immutable: none (static). L1 RPC (and beacon for scroll/morph/xlayer) for every one except immutable.
