# COSMOS / CometBFT family — shared design

Facts are marked VERIFIED (how) or INFERRED. Research date 2026-10-13. Networks: `cosmos osmosis injective sei celestia xrplevm` (pure CometBFT single process),
`zerog` (CometBFT CL + reth EL, two processes — OP/Base-shaped, shares only the CometBFT parts). `gravity` is NOT a Cosmos chain (see networks.md).

## 1. Process model

Single unit `rpcnode-<network>-<env>.service`, `ExecStart=<node_dir>/<bin> start --home <home> [mandatory flags]`, Type=simple, KillSignal=SIGTERM,
`TimeoutStopSec=180` (app DB flush), `LimitNOFILE=1048576`, log `logs/<network>.log` (same tmpl shape as `chains/sui/node.service.tmpl`; placeholders
`description node_dir exec_start log_file nofile`).

Layout (all under node_dir or the disk-role leaf; nothing in /etc or /usr/local):
```
<node_dir>/gaiad | osmosisd | injectived(+libwasmvm.x86_64.so) | seid | celestia-appd | exrpd   # versionless names
<home>   = disk role "home" leaf (NVMe)        # --home: config/ data/ wasm/   (snapshot extracts here)
<node_dir>/logs/<network>.log   <node_dir>/.toolkit/...
```
First Start (idempotent, per-network `*NodeProcessStarter` delegating to family code):
1. `<bin> init <moniker> --chain-id <id> --home <home>` only if `<home>/config/config.toml` is missing (moniker = `rpcnode-<nodeId>`).
2. Genesis: `clients.yml configs:` downloads `genesis.json` (plain JSON) next to the binary; starter copies it to `<home>/config/genesis.json` if absent. Sei: `seid init` writes genesis + seeds itself (v6.6.2+, VERIFIED docs.sei.io/node).
3. TOML patch (below) — every Start, so ports/limits always equal the YAML bindings.
4. Bootstrap: snapshot job (restore into `<home>`) OR state-sync config (below) — decided by the snapshot type picked in the wizard.
5. install the unit, `systemctl start`.

Cosmovisor: NOT used in v1. CometBFT/SDK chains halt at a governance upgrade height ("UPGRADE NEEDED"); the existing *Update client* flow replaces the binary
(and restarts). Celestia says explicitly not to use Cosmovisor for the v9->v10 transition (VERIFIED release notes v10.4.0). Optional future: detect the halt in
the log and offer Update.

## 2. Height / tip probe (VERIFIED against live public RPCs: cosmoshub, osmosis, injective, sei, celestia, xrplevm, mocha)

Request: `GET http://127.0.0.1:<rpc_port>/status`  (no auth, CometBFT RPC; local bind 0.0.0.0, probe uses loopback)
```json
{"jsonrpc":"2.0","id":-1,"result":{"node_info":{"network":"osmosis-1", ...},
 "sync_info":{"latest_block_hash":"…","latest_block_height":"72476692","latest_block_time":"2026-10-13T20:37:43.49Z",
              "earliest_block_height":"72058040","catching_up":false}, ...}}
```
Parse `result.sync_info.latest_block_height` (string -> Long), `catching_up` (Boolean), `latest_block_time` (ISO, for lag). Identical shape on CometBFT 0.38, celestia-core
0.38/0.42 and Sei's tendermint fork (all seen live). While state-syncing, `latest_block_height` is "0" and `catching_up=true`.
`HostNodeHeightReading(height, syncPct = SyncCatchUp.pct(startLag, ageOfLatestBlock), syncing = catching_up)`; "synced" lag threshold per profile
(`syncedLagSec`: cosmos/osmosis/celestia 30, injective/sei/zerog 15, xrplevm 30). Failure -> `null`.
`NodeHeightSpec(kind = "cometbft_status", portRole = "http")` (pure-Cosmos) or `portRole = "cl_rpc"` (EVM chains, see ports). New `kind` string `cometbft_status` must be
accepted by the agent height-probe map (`AgentMain.kt` ChainNodeRuntime). `PolygonHeimdallStatus.parse/fetch` is exactly this parser: promote it to
`chains/cometbft/infrastructure/http/CometbftStatus.kt` and let polygon delegate (optional, no behaviour change).

Tip: panel-side `CometbftNetworkTipProbe(urls)` = same `/status` fetch against `publicTip.urls`, first URL with `catching_up=false` and height>0 (iterate; public RPCs rate-limit —
rpc.sei-apis.com answered null on a 2nd call). zerog uses the existing EVM probe (`eth_blockNumber`, tip `https://evmrpc.0g.ai`).

**Tip-lag problem (decision):** status goes sync->active only if `tip - height <= 3` (`GetNodeHeightUseCase.tipLagActive`, `MarkNodeStartedUseCase.tipLagActive`, constants). Block times:
sei ~0.4 s, injective ~0.65 s, 0G ~0.5 s -> 3 blocks = 1-2 s, below public-RPC latency; the node would flap/stay `sync`. Proposal: add `tipLagBlocks` per env in network.yml
(default 3); suggested cosmos 3, osmosis 3, celestia 3, xrplevm 3, injective 15, sei 25, zerog 25. (INFERRED values, from block times.)

## 3. TOML patch (config.toml + app.toml) — one shared helper

`ClientConfigLeafPatch` only knows HOCON/INI (it would not quote TOML strings), so add `chains/cometbft/infrastructure/proc/CometbftTomlPatch`:
`set(text, section, key, tomlValue)` — find `[section]` range, replace `^\s*#?\s*key\s*=` line, else append inside the section (same approach as `PolygonConfigPatch`, line-level, keeps comments).
`KeyStyle` per profile: `SNAKE` (CometBFT 0.38/1.x: gaia, osmosis, injective, celestia, xrplevm, 0G) vs `KEBAB` (Sei tendermint fork: `max-open-connections`, `persistent-peers`, `rpc-servers`, `trust-height`, `pprof-laddr`) — VERIFIED from the Sei default config on docs.sei.io.
All constants live in one `CometbftConfigDefaults` object; `network.yml clientConfig.bindings` literals equal those constants (a test, like the Sui one, asserts it).
Binding `key` naming: `config.toml` keys as `rpc.max_open_connections`, `app.toml` keys as `app:api.max-open-connections` is not needed — YAML keys are just display labels; use `<section>.<key>`.

Applied every Start (SNAKE names shown; `E` = EVM chains only; values = prod defaults, see §7):

| file | section.key | value | why |
|---|---|---|---|
| config.toml | rpc.laddr | `tcp://0.0.0.0:<http|cl_rpc>` | catalog port |
| | rpc.unsafe | false | never public (dial_seeds/dial_peers/unsafe_flush_mempool) |
| | rpc.pprof_laddr | "" | default `localhost:6060` collides between nodes (0G default is `0.0.0.0:6060`!) |
| | rpc.max_open_connections | 2000 | default 900 |
| | rpc.max_subscription_clients / max_subscriptions_per_client | 100 / 5 | defaults, kept |
| | rpc.timeout_broadcast_tx_commit | 10s | default |
| | rpc.max_body_bytes | 1000000 | default |
| | p2p.laddr / external_address | `tcp://0.0.0.0:<p2p>` / (blank) | |
| | p2p.max_num_inbound_peers / outbound | 40 / 10 | defaults |
| | p2p.seeds / persistent_peers | profile seeds / blank | |
| | mempool.size / max_txs_bytes / cache_size | 5000 / 1073741824 / 10000 | defaults |
| | tx_index.indexer | kv | tx-by-hash needs it |
| | instrumentation.prometheus / prometheus_listen_addr | true / `127.0.0.1:<metrics>` | default `:26660` is all-interfaces |
| | statesync.* | only when snapshot type = statesync | |
| app.toml | minimum-gas-prices | profile (cosmos `0.0025uatom`, osmosis `0uosmo`, celestia `0.002utia`, sei `0.02usei`, injective `160000000inj`, xrplevm `0axrp`) | Polkachu `minimum_gas_price` (VERIFIED API) |
| | pruning / pruning-keep-recent / pruning-interval | default / "0" / "0" | |
| | min-retain-blocks | profile (§7) | |
| | api.enable / swagger / address | true / false / `tcp://0.0.0.0:<api>` | |
| | api.max-open-connections / rpc-read-timeout / rpc-max-body-bytes / enabled-unsafe-cors | 1000 / 10 / 1000000 / false | |
| | grpc.enable / address | true / `0.0.0.0:<grpc>` | |
| | grpc-web.enable | false | (Sei has a separate :9091 grpc-web port) |
| | state-sync.snapshot-interval | 0 | |
| E | json-rpc.address / ws-address / api / gas-cap / evm-timeout / txfee-cap / filter-cap / logs-cap / block-range-cap / allow-unprotected-txs / batch-request-limit / enable-profiling | `0.0.0.0:<http>` / `0.0.0.0:<ws>` / `eth,net,web3` / 25000000 / 5s / 1 / 200 / 10000 / 10000 / false / 1000 / false | VERIFIED defaults from the XRPL EVM config reference (cosmos/evm app.toml) |
| E (sei) | evm.http_port / ws_port / cors_origins / max_blocks_for_log … | catalog / catalog / `*` -> see sei notes | VERIFIED docs.sei.io app.toml |

Bindings visible in Start "Config preview" with identical values; `LimitNOFILE=1048576` is a binding too.

## 4. Snapshots / bootstrap (the decisive part — nobody syncs these from genesis)

All providers publish **`.tar.lz4` rooted at `data/`** (VERIFIED by listing the first 40 MB of Polkachu xrp/celestia, Injective OVH, Osmosis official, Peersyst xrplevm: entries `data/…`; Polkachu also ships
`data/priv_validator_state.json`; Peersyst includes `data/.state_sync_done`). Restore = stop unit; keep `<home>/config/*`; delete `<home>/data` (+`<home>/wasm` for CosmWasm chains); `curl -L <url> | lz4 -dc | tar -x -C <home>`.
Docs say Injective and Sei snapshots include `wasm/` (VERIFIED docs); Osmosis/Polkachu CosmWasm — INFERRED (check `wasm/` at implementation).

**Agent change (needed):** `SnapshotStreamExtract.fetchAndExtractStreaming` is gzip-only (`tar -xzf -`); BSC has its own lz4 path (`BscStreamSnapshot`, `tar -I lz4 -x`). Generalise the streaming extractor with a
`compression` derived from the URL suffix (`.tar.lz4` -> `tar -I lz4 -x`, `.tar.zst` -> `tar -I zstd -x`, `.tar.gz`, `.tar`), keep the `ResumableStreamCopier` Range-resume, and add `hostPackages: [lz4]` (zstd optional) in network.yml. Pre-clean must remove only `data/` and `wasm/` (and for Sei `data/state_store`, etc. — inside data/), never `config/`. `SnapshotArchive(url, streamUnpack = true, sizeBytes)`; `streamUnpack` is required for these sizes (archive 15–220 GB would otherwise need a second copy).

`snapshotTypes` per env (ids): `pruned` (default; provider snapshot), `statesync` (sentinel archive `statesync://<env>`, no download — the agent writes `[statesync]` and starts), optionally `archive` (never auto-resolved; omitted).

Resolver sources (all VERIFIED live 2026-10-13 unless noted):

| source kind | where | URL discovery | size |
|---|---|---|---|
| `LatestText` | Osmosis official `https://snapshots.osmosis.zone/latest` (testnet `https://snapshots.testnet.osmosis.zone/latest`) | body = one URL line | HEAD Content-Length (29.5 GB) |
| `StatusJson` | Injective official OVH `http://injective-mainnet-snapshots.s3-website.gra.io.cloud.ovh.net/status.json` and GCS `https://storage.googleapis.com/injective-mainnet-snapshots-asia/status.json` | `snapshot.url`, `snapshot.block_height`, `snapshot.size` | HEAD |
| `PolkachuPage` | `https://polkachu.com/tendermint_snapshots/<slug>` (testnet `https://polkachu.com/testnets/<slug>/snapshots`) | regex `https://snapshots\.polkachu\.com/(testnet-)?snapshots/<slug>/<slug>_\d+\.tar\.lz4`; API `…/api/v2/chain_snapshots/<slug>` works for `cosmos` but answers "secret header required" for other chains (flaky) -> scrape the page, API as first try | HEAD (cosmos 16.3 GB, osmosis 33.5 GB, injective 15.8 GB, **sei 234 GB**, celestia 3.2 GB, xrp 0.28 GB) |
| `FixedUrl` | Peersyst XRPL EVM `https://xrpl-evm-snapshots.s3.us-east-1.amazonaws.com/{mainnet,testnet,devnet}.tar.lz4` (daily; mainnet 99.9 MB, testnet 10.1 GB) | static | HEAD |
| `StateSync` | Polkachu/other CometBFT RPCs that serve snapshots | n/a | n/a |

Slugs: cosmos=`cosmos`, osmosis=`osmosis`, injective=`injective`, sei=`sei`, celestia=`celestia`, xrplevm=`xrp`.
Polkachu keeps one snapshot (older deleted) -> always resolve at job start, never cache a URL. Polkachu is a single-operator mirror: acceptable as default for cosmos/celestia/xrplevm/sei where no official one exists (resolved at runtime; mark "community" in the label).

State sync config (agent, no download): pick `rpc1 = rpc2 = chain's state-sync RPC` (Polkachu `polkachu_services.state_sync.node`, VERIFIED API; duplicates are allowed), `H = latest - 2000` rounded down to the provider's
snapshot interval (Sei script rounds to 100000), `trust_hash = /block?height=H -> result.block_id.hash`, `trust_period = 168h0m0s`, `enable=true`, `temp_dir` on the node disk. Keys: snake (`rpc_servers`, `trust_height`, `trust_hash`) or kebab for Sei. After the first successful block set `statesync.enable=false` on next Start (marker `.toolkit/statesync-done`).
Injective: state sync is **broken without the wasm dir** (AppHash mismatch, VERIFIED docs.injective.network) -> no `statesync` type for injective. Sei docs: state sync "can take 4–5 attempts" — default to snapshot for Sei? (see risks).

## 5. Release resolver rule

`CometbftReleaseResolver(repo, tagFilter, includePrerelease)` over `GitHubReleaseClient`. Needed shared change: `GitHubReleaseClient.latestRelease` takes only `tagPrefix`; add `tagSuffix`/regex (celestia testnet tags `v10.4.0-mocha`; assets for `-mocha`/`-corto` tags are flagged prerelease) and make "latest" = **highest semver among stable releases in the page**, not newest-by-date: sei published v6.6.4 (Oct 6) AFTER v6.7.0 (Oct 5) and xrplevm v10.2.1 (Sep 15) after v11.1.1 (Sep 8) -> date order would install an old line for days. Also GitHub "latest stable" can be AHEAD of the network (upgrade proposals pass later): the binary must match the live chain version; sanity check at install: compare with the public RPC `/abci_info.response.version` where it is informative (gaia `v28.3.1`, celestia `10.4.0-mocha`, xrplevm `11.2.0`, injective `v1.20.4-bl.2`; osmosis answers `local`, sei null) and show a warning, never block.
Per network: cosmos `cosmos/gaia`; osmosis `osmosis-labs/osmosis`; injective `InjectiveFoundation/injective-core` (tags `v1.20.4-1789748350`; version = tag without the trailing `-<digits>` suffix; the old `InjectiveLabs/injective-chain-releases` is stale at v1.17.2 and `mainnet-config` is archived); sei `sei-protocol/sei-chain`; celestia `celestiaorg/celestia-app` (mainnet = no suffix, mocha = `-mocha`); xrplevm `xrplevm/node`; zerog = pinned tags per release repo (`0gfoundation/0gchain-Aristotle` tags `v1.0.7` / `1.0.4`-style mixed, `0gfoundation/0gchain-NG` `v3.0.8`) — resolver = latest release of the repo.
Source code of Injective is private since 2026-09-09 (`SOURCE_CODE_IS_PRIVATE`); binary zip only.

## 6. Kotlin class plan

Family package `rpcnode.toolkit.chains.cometbft` (shared, no `when(network)`), thin per-network classes in `chains/<id>/` that delegate with Kotlin `by`:

| shared (chains/cometbft/…) | per-network thin (chains/<id>/…) |
|---|---|
| `domain/CometbftProfile` (data: binary, chainId per env, KeyStyle, minGasPrice, seeds, genesisFile, bootstrap sources, syncedLagSec, evm?, extraFlags(env), extraPorts) | `<Id>Profile` object (data only) |
| `infrastructure/CometbftPorts` (role->port resolved from YAML catalog / `<Id>PortTable`) | `<Id>PortTable` (same numbers as clients.yml; polygon precedent) |
| `infrastructure/start/CometbftNodeStart(profile)` : `ChainNodeStart` | `class OsmosisNodeStart : ChainNodeStart by CometbftNodeStart(OsmosisProfile)` (networkId override) |
| `infrastructure/proc/CometbftNodeProcessStarter(profile)` (init, genesis, patch, unit, start) + `CometbftTomlPatch`, `CometbftConfigDefaults`, `CometbftUnitBodies` (renders `<id>/node.service.tmpl`) | `<Id>NodeProcessStarter` |
| `infrastructure/http/CometbftStatus`, `CometbftNodeHeightProbe`, `CometbftNetworkTipProbe` | `<Id>NodeHeightProbe`, `<Id>NetworkTipProbe` |
| `infrastructure/http/CometbftReleaseResolver` | `<Id>ClientReleaseResolver` |
| `infrastructure/http/snapshot/{LatestText,StatusJson,PolkachuPage,FixedUrl,StateSync}Source` + `CometbftSnapshotResolver(sources by env/type)` | `<Id>SnapshotResolver` |
| `infrastructure/proc/CometbftStateSync` (trust height/hash fetch + toml) | |
`zerog` reuses `CometbftTomlPatch`, `CometbftStatus` (optional CL cross-check) and the Base-style dual-unit starter (`HostNodeLaunchSupport.installCustomUnits`, EL primary + CL companion).

Wiring: `NetworkId` vals (`COSMOS, OSMOSIS, INJECTIVE, SEI, CELESTIA, XRPLEVM, ZEROG`), no new `EnvId` vals: every network uses the existing `mainnet` and `testnet` (labels carry the chain name: provider, osmo-test-5, mocha-5, atlantic-2, injective-888, xrplevm testnet, Galileo), `Toolkit.production()` maps (release, tip, start, snapshot), `AgentMain` `ChainNodeRuntime` (proc + height), `YamlNetworkFactsRepositoryTest` + `NetworksRoutesTest` id lists, `ShippedPortCatalogTest` (ports below), start-plan + `/status` parse + TOML patch + binding==constant tests.

## 7. Production config (verified settings + presets proposal)

Verified sources: CometBFT default config.toml as shipped in the 0G package (v0.38-style; keys/defaults read from the file), Sei docs "Node Operations Guide" (default app.toml/config.toml, VERIFIED), XRPL EVM "Configuration reference" (cosmos/evm app.toml, VERIFIED), Celestia docs (pruning/min-retain-blocks measurements, VERIFIED), Injective docs (hardware, snapshots).

Per-network prod values:
- cosmos / osmosis: defaults above with `min-retain-blocks = 100000` (~7 d at ~6 s blocks) to bound `blockstore.db` (INFERRED judgement; Polkachu publishes pruned nodes too).
- celestia: `pruning=default`, `min-retain-blocks=3000` (docs: measured 206 GB pruned store with exactly these), `tx_index.indexer=kv`, `--rpc.grpc_laddr tcp://127.0.0.1:<grpc_laddr>` MANDATORY since v4 (VERIFIED docs), `db_backend` PebbleDB default for new nodes (v9+).
- sei: docs-tuned: `rpc.max-open-connections` 900 (we 2000), `p2p.max-connections` 200, `min-retain-blocks=100000`, `concurrency-workers` default, `occ-enabled=true`, `[state-commit] sc-enable=true`, `[state-store] ss-enable=true ss-backend="pebbledb" ss-keep-recent=100000`, `[receipt-store] rs-backend="pebbledb"`, `[evm] max_log_no_block 10000 / max_blocks_for_log 2000 / max_concurrent_trace_calls 10 / max_subscriptions_new_head 10000 / max_subscriptions_logs 1000 / simulation_gas_limit 10000000 / read_timeout 30s / cors_origins "*" (leave behind proxy) / deny_list [] / enable_simulation true`, `[api] swagger=false` (default true), `[grpc-web] enable=false`, `[admin_server] admin_enabled=false`, `[giga_executor] enabled/occ_enabled = true` (v6.6+ default). Hardware per docs: 16 cores / 256 GB / 2 TB NVMe.
- xrplevm: `evm-chain-id` under `[evm]` is MANDATORY (v10+: mainnet "1440000", testnet "1449000") — VERIFIED docs; `[json-rpc] enable=true api="eth,net,web3"`, `pruning=default`.
- injective: `timeout_commit=300ms` (docs), `minimum-gas-prices=160000000inj`, official snapshot wasm included; hardware 12–16 cores / 128 GB / 2 TB NVMe, 10–15 GB/day growth (docs).
- zerog: see zerog section in networks.md (reth flags corrected: docs sample exposes `--http.api eth,net,admin` and `--authrpc.addr 0.0.0.0`; we ship `eth,net,web3` / `127.0.0.1`).

**Must not be public:** CometBFT `unsafe` routes (off), `pprof_laddr` (off), Prometheus (loopback), `/net_info` + `/dump_consensus_state` (leak peers/validator info — behind the proxy deny-list), gRPC (`:grpc`, plaintext, query-only but reflection + heavy), REST swagger, EVM namespaces beyond `eth,net,web3` (`debug`, `txpool`, `admin`, `personal`, `engine`), EVM `allow-unprotected-txs`, Engine API/JWT (127.0.0.1), `node_key.json`/`priv_validator_key.json` (a non-validator RPC node must never be in the validator set; `priv_validator_key.json` created by `init` is harmless unless someone delegates to it — recommend deleting it after init). Heavy public methods (`/tx_search`, `/block_search`, `eth_getLogs` wide, `/subscribe`) -> rate-limit at a reverse proxy. Same-host proxy sees loopback only (no admin split exists on these RPCs).

prodPresets (`admin/src/lib/prodPresets.ts`, `conf` = config-file keys; proposed key form `<file>:<section>.<key>`; one shared const `COMETBFT_RPC` used by cosmos/osmosis/injective/celestia/xrplevm, Sei variant with kebab names + the Sei `[evm]` extras, zerog = reth `flags`):
```ts
const COMETBFT_RPC: ProdPreset = { conf: {
  'config.toml:rpc.max_open_connections': '3000',      // default 900; websockets count too
  'config.toml:rpc.max_subscription_clients': '200',
  'config.toml:rpc.max_subscriptions_per_client': '5',
  'config.toml:rpc.timeout_broadcast_tx_commit': '10s',
  'config.toml:rpc.max_body_bytes': '1000000',
  'config.toml:mempool.size': '5000',                  // default; raise only with RAM
  'config.toml:p2p.max_num_inbound_peers': '80',
  'config.toml:p2p.max_num_outbound_peers': '20',
  'app.toml:api.max-open-connections': '2000',
  'app.toml:grpc.max-recv-msg-size': '10485760',
  'app.toml:min-retain-blocks': '100000',
  'app.toml:pruning': 'default',
}, notes: ['rpc.unsafe, pprof and swagger stay off','one node: put a rate-limiting proxy with a cache in front'] }
// evm chains add: 'app.toml:json-rpc.api': 'eth,net,web3', 'app.toml:json-rpc.gas-cap': '25000000',
//   'app.toml:json-rpc.logs-cap': '10000', 'app.toml:json-rpc.block-range-cap': '10000', 'app.toml:json-rpc.filter-cap': '200',
//   'app.toml:json-rpc.evm-timeout': '5s', 'app.toml:json-rpc.batch-request-limit': '1000'
```
Locked (not overridable): ports/laddr, home, chain-id, genesis, seeds, `rpc.unsafe`, `pprof_laddr`, prometheus bind, `evm-chain-id`, `json-rpc.allow-unprotected-txs`.

## 8. Ports / roles (PORTS.md blocks)
Pure-Cosmos (cosmos, osmosis, celestia): `+0 http`=CometBFT RPC, `+2 p2p` (tcp), `+4 metrics`, `+5 api` (REST), `+7 grpc`, celestia `+8 grpc_laddr`. 
EVM chains (sei, injective, xrplevm): `+0 http`=EVM JSON-RPC, `+1 ws`, `+2 p2p`, `+4 metrics`, `+5 cl_rpc`=CometBFT RPC, `+7 grpc`, `+8 api`, `+9` xrplevm `json-rpc.metrics-address`. 
zerog: `+0 http +1 ws +2 p2p(tcp/udp) +3 engine +4 metrics(EL) +5 cl_rpc +6 cl_p2p +7 cl_metrics +8 node_api +9 spare(pprof disabled)`.
The default 26656/26657/1317/9090 are never used (polygon heimdall owns them).

## 9. Admin touch list
`InstallOptionsPicker.tsx` (no new options in v1 except none; optional `moniker`), `networkDocs.ts` (doc links), `networkPaths.ts` (+`/data/rpcnode/<id>`), `network.ts` (family helper `isCometbftNetwork` for Sync-step wording "catching_up"), `prodPresets.ts` (COMETBFT_RPC + zerog reth flags), `NodeConfigPanel.tsx` (two documents: `config.toml`, `app.toml`; mirror the Bitcoin-family doc editor; locked keys list), `DiskLayoutPanel.tsx` (role `home`), `NodeErrorsPanel.tsx` (patterns: `UPGRADE NEEDED`, `AppHash`, `wrong Block.Header.AppHash`, `failed to verify`), `nodeLifecycle.ts` (optional `halted-upgrade` hint), `NodeMetaAside.tsx` (catching_up), `networksCatalog.ts`/`labels.ts` (labels), network icons.
