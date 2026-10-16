# evml1 — shared design (EVM L1s and other chains)

Scope: gnosis, joc, flare, sonic, kaia, vana, tempo, berachain, data, fluent, gravity are READY (drafts in `<id>/`). shibarium, moca = NEEDS-DECISION; fantom, plasma, monad, megaeth, ault, sahara = DEFERRED. Status table: `networks.md`. Open questions: `risks.md`.
Fact tags: **VERIFIED** = fetched/HEAD-checked/read on 2026-10-13; **INFERRED** = not directly checkable here (named where it matters).

## 1. Recipes and what is shared

Every READY network is a systemd-unit recipe (same family as `ethereum`, `bsc`, `base`, `polygon`, `avalanche`). They fall into five recipes; only the recipe classes below are new shared code, everything else is a thin per-network package `chains/<id>/` as the architecture rule demands (no `when(network)` monolith).

| Recipe | Networks | Process model | Existing class it generalises |
|---|---|---|---|
| **nethermind** | gnosis (+lighthouse), joc | 1 unit (joc) / 2 units (gnosis: nethermind + lighthouse). `nethermind -c <config> --data-dir …` + PascalCase `--Section.Key value` flags | `EthereumUnitBodies` / lighthouse unit |
| **gethfork** (geth-style flag list, one execution unit, EVM JSON-RPC) | vana (patched geth + Prysm companion), data (story-geth + story companion), kaia (`ken`), sonic (`sonicd`) | flag list in the unit; extra one-shot step (geth `init`, `sonictool genesis`, `story init`) | `BscUnitBodies`, `BscNodeProcessStarter.ensureDatadirInit`, `EthereumGethFlags` |
| **reth** | tempo, fluent, berachain (bera-reth + beacond companion), gravity (reth + AptosBFT in one process) | reth flag list; engine/JWT only where a CL exists (berachain) | `BaseUnitBodies` (+ consensus unit), op/arc `run.sh` presets |
| **avalanchego-fork** | flare (flare / songbird / coston2) | `avalanchego` flags + C-Chain `config.json`; bootstrap ip/id looked up at every Start | `AvalancheUnitBodies`, `AvalancheNodeProcessStarter` (flare can literally reuse them, see §3) |
| **cometbft companion** (CL side) | berachain (`beacond`), data (`story`) | second unit, `Requires=` the EL unit, config.toml patched once after `init` | `PolygonNodeProcessStarter` (heimdall), `BaseNodeProcessStarter` (companion wiring via `HostNodeLaunchSupport.installCustomUnits`) |

### 1.1 Family-shared Kotlin (new, package `rpcnode.toolkit.chains.family.evm` — proposal)
1. `EvmRpcHeightProbe` : `HostNodeHeightProbe` — exactly today's `BscNodeHeightProbe` (eth_blockNumber + eth_syncing + `SyncCatchUp`), constructor takes an optional `path` (flare: `/ext/bc/C/rpc`). Each network: `class XNodeHeightProbe : HostNodeHeightProbe by EvmRpcHeightProbe()`.
2. `EvmNetworkTipProbe(networkId)` — `publicTip.urls` + eth_blockNumber (today copied per network, e.g. `BscNetworkTipProbe`). Gnosis keeps beacon URLs for the CL check like `EthereumNetworkTipProbe`.
3. `GithubClientReleaseResolver(repo, envs, tagPrefix, tagSuffix, prerelease)` — generic `ClientReleaseResolver`; replaces the identical per-network `*ClientReleaseResolver` copies (bsc, polygon, … all are the same 10 lines). Needs **one small core change**: `ClientVersionSource.GitHubRelease` + `HttpGitHubReleaseClient.pickLatest` get a `tagSuffix` filter (gravity testnet `-testnet`; shibarium `-bone`, and `-bone-beta` must not match `-bone`). Today only `tagPrefix` exists (`clients/infrastructure/github/HttpGitHubReleaseClient.kt`, `YamlClientProgramCatalog.parseSource`). `releases/latest` is never used (the client lists `?per_page=20`): good, because for tempoxyz/tempo and shibaone/bor `releases/latest` is the WRONG release (VERIFIED: tempo returns a `tempo-primitives@…` crate tag, shibaone/bor returns `v1.3.9-bone` while v2.x exist).
4. `GitHubAssetUrlResolver` : `ClientArtifactUrlResolver` for `urlTemplate: "github-asset:<glob>"` — picks the matching asset of the resolved `GitHubRelease.assets` (already carried by `GitHubRelease`). Needed by nethermind (asset names embed a commit hash: `nethermind-2.1.0-b3e7e84c-linux-x64.zip`); same idea as `EthereumGethArtifactUrlResolver` ("gethstore").
5. `HostJwt.ensure(nodeDir)` → `<node_dir>/.toolkit/jwt.hex` (0600, `openssl rand -hex 32`). Today `BaseNodeProcessStarter` / `EthereumNodeProcessStarter` write `/etc/<chain>/<env>/jwt.hex` and `/opt/<chain>/…` — the brief (and the intake doc) forbid that for new chains; move the helper here and let old chains migrate later.
6. Binary installers (per-artifact-shape, all write into `<node_dir>/bin/`, chmod 755, stamp file like `EtcHostBinaries`): `ZipBundleInstaller` (nethermind: whole zip incl. `configs/`, `chainspec/`, dlls; flare: one file `avalanchego`), `RawBinaryInstaller` (vana geth, sonicd/sonictool, story/story-geth, gravity_node/gravity_cli, vana prysm: asset is already the executable; `BscNodeProcessStarter.ensureGeth` pattern), tgz = existing shared tar extractor (tempo, fluent, berachain, kaia — kaia/`ken-linux-amd64.tar.gz` unpacks `ken-linux-amd64/bin/ken`, use `NodeLaunchSpec.extractArchiveGlob`/`normalizeDir`).
7. `CompanionUnits.install(...)` — thin wrapper over `HostNodeLaunchSupport.installCustomUnits(primaryBody, companions=[cl], startCompanionsFirst=false)` (EL first, CL `Requires=` EL: berachain, data, vana, gnosis).
8. `OneShotJob` script scaffold = the existing snapshot-job script protocol (JSON state file `{"phase","pct","detail","updated_at"}` + marker file + `systemctl stop` first), as used by `bsc/snapshot-heal.sh.tmpl`. Drafts: `sonic/genesis-import.sh.tmpl`, `kaia/snapshot-restore.sh.tmpl`, `berachain/snapshot-restore.sh.tmpl`.
9. `UnitFlagOverrides` is reused as-is for the prod-preset "Flag overrides" panel, with ONE required change for nethermind: `KEY` is `^[a-z][a-z0-9._\-]{0,63}$` (rejects `JsonRpc.MaxBatchSize`) and the locked-token check is case-sensitive. Make KEY `[A-Za-z]…` and lower-case before `isLocked`.

### 1.2 Per-network thin classes (every READY network gets the same eight; copy `chains/bsc` as the template)
`<Id>Ports` (catalog numbers), `<Id>Cluster` (static facts per env: chain flag/name/id, checkpoint URL, bootnodes), `<Id>InstallOptions`, `<Id>UnitBodies` (render `node.service.tmpl` + companions via `HostSystemdUnitTemplate`), `start/<Id>NodeStart` (ChainNodeStart → `NodeLaunchSpec(kind="binary", entry=…, args=--toolkit-env=…/--toolkit-<role>=<dir>)`, `NodeHeightSpec(kind="eth_rpc", portRole="http")`), `proc/<Id>NodeProcessStarter`, `http/<Id>NodeHeightProbe` + `<Id>NetworkTipProbe` (one-line delegates), `http/<Id>ClientReleaseResolver` (one-line delegate), snapshot resolver where §5 says so.

## 2. Height probe, tip probe, status
- **Local height (all 11 networks, EL):** `POST http://127.0.0.1:{http}/` body `{"jsonrpc":"2.0","id":1,"method":"eth_blockNumber","params":[]}` → `{"result":"0x…"}` (hex → Long). Catch-up bar: `eth_syncing` → `false` or `{"currentBlock","highestBlock"}` (`SyncCatchUp`). Failure → `null` (never throw). Flare: same body to `/ext/bc/C/rpc` (VERIFIED on `https://flare-api.flare.network/ext/C/rpc`).
- **Not enough alone for CL chains:** berachain/data additionally expose CometBFT `GET http://127.0.0.1:{cl_rpc}/status` → `result.sync_info.{latest_block_height,catching_up}`; use it only as a secondary "CL alive" diagnostic (the EL height is what compares to the public tip). Gnosis: Lighthouse `GET /eth/v1/node/syncing` the same way.
- **Tip:** `publicTip.urls` in network.yml, same eth_blockNumber protocol (all URLs below were answered live on 2026-10-13, see `networks.md`). `sync` → `active` when `tip - height <= tipLagBlocks` (default 3). Sub-second chains need `tipLagBlocks` in network.yml (drafts set 5-30; gravity 200 ms blocks → 30, tempo/sonic/fluent 10).
- Public tips are rate-limited/third-party: list ≥2 where a second one answered (publicnode answered for gnosis, chiado, sonic, berachain, data; it did NOT answer for kaia, flare, songbird, vana, joc, tempo, fluent, gravity — single official URL there).

## 3. Start args and units (one table; full text in `<id>/node.service.tmpl`)
| Network | Binary / entry (`NodeLaunchSpec`) | Chain selection | Companion |
|---|---|---|---|
| gnosis | `bin/nethermind` (zip bundle) | `-c gnosis` / `gnosis_archive` / `chiado` / `chiado_archive` | `lighthouse bn --network gnosis|chiado --checkpoint-sync-url …` |
| joc | `bin/nethermind` | `-c joc-mainnet` / `joc-mainnet_archive` / `joc-testnet` / `…_archive` | none (Clique, Merge off) |
| flare | `bin/avalanchego` (from `go-flare-<tag>-linux-amd64.zip`) | `--network-id=flare|songbird|costwo` | none |
| sonic | `sonicd` + `sonictool` (raw) | genesis file picks the chain (`latest-sonic-*.g` / `latest-testnet-*.g`) | none |
| kaia | `ken-linux-amd64/bin/ken` | `--mainnet` / `--kairos` | none |
| vana | `geth` (patched on mainnet) | `--networkid 1480|14800` + `geth init genesis.json` | `prysm beacon-chain` (Vana build on mainnet) |
| tempo | `tempo node --follow` | `--chain mainnet|moderato` | none (follow mode; no Engine API) |
| berachain | `bera-reth node` | `--chain mainnet|bepolia` | `beacond start --home` (Requires bera-reth) |
| data | `story-geth` | `--story` / `--aeneid` | `story run --home` (Requires story-geth) |
| fluent | `fluent node` | `--chain=fluent-mainnet|fluent-testnet` | none |
| gravity | `gravity_node node` | genesis.json + waypoint.txt pair | none (consensus in-process) |

Rules applied in every draft: RPC/WS on `0.0.0.0` with public namespaces only; Engine API, metrics, CL RPC, inspection on `127.0.0.1`; IPC disabled; `LimitNOFILE=1048576` (sonic 934073 as upstream); `KillSignal` SIGINT for geth-derived, SIGTERM otherwise; log append to `<node_dir>/logs/*.out`; JWT under `<node_dir>/.toolkit/`; versionless on-disk artifact names (`nethermind-linux-x64.zip`, `go-flare-linux-amd64.zip`, `sonicd`, `ken-linux-amd64.tar.gz` …); capacity literals identical in `clientConfig.bindings` and the unit.
Flare reuses avalanche code directly: `AvalancheUnitBodies.args(env, dataDir, dbDir, logDir, httpPort, stakingPort)` + two extra args `--bootstrap-ips=` / `--bootstrap-ids=` resolved at Start from `https://{flare|songbird|coston2}-bootstrap.flare.network/ext/info` (`info.getNodeIP` / `info.getNodeID`, VERIFIED); `AvalancheUnitBodies.cChainConfig` is valid as is (Flare's docs list the same keys). Only `networkFlag` (`flare|songbird|costwo`), the artifact installer (zip) and the resolver differ.

## 4. Release resolver rule
Default: newest non-draft, non-prerelease GitHub release (client skips `-alpha/-beta/-rc/-preview` tags too). Exceptions (all encoded in clients.yml, see drafts):
- tempo: `tagPrefix: v` (crate tags share the repo). - vana mainnet geth: `tagPrefix: geth-v` (the monorepo also has `v2.x` app tags). - gravity testnet: `tagSuffix: -testnet` + prerelease. - berachain bepolia beacond: `prerelease: true` (docs pin v1.4.2-rc.0; follows to stable). - kaia: GitHub only supplies the TAG (no assets there); the URL is `https://packages.kaia.io/kaia/{tag}/ken-{tag}-0-linux-{amd64,arm64}.tar.gz` — probe Content-Length > 1 (the host answers 200 with a 1-byte body for unknown paths). - nethermind: asset glob. - flare: stable only (v1.14.2-rc.0 exists). - vana moksha prysm: `pinned` 5.1.0.
Where upstream publishes a required-version table the toolkit cannot read (gravity hardfork schedule, tempo network-upgrades page, berachain EVM-execution page, kaia hardfork notes), "latest stable" is the default and the Start screen must expose the pin.

## 5. Snapshots (summary; per-network rows in `networks.md`)
| Pattern | Networks | Resolver / job |
|---|---|---|
| none (client-native sync: snap sync, checkpoint sync, bootstrap) | gnosis, joc, flare, vana, data, fluent mainnet | `snapshot: never`; keep the existing "custom snapshot URL" box (`CustomSnapshotUrl`) for community bundles |
| **CLI download** (agent runs the client) | tempo (`tempo download --chain X --archive|--full|--minimal`), fluent testnet (`fluent download`) | like Base/Sui: `via=<bin> download`, no resolver; progress parsed from output; datadir must be empty/`--force` |
| **genesis import** | sonic | `SonicSnapshotResolver`: `GET https://genesis.soniclabs.com/latest-{sonic|testnet}-{pruned|archive}.g` (302 → dated object, md5 at `…g.md5`); job `genesis-import.sh.tmpl` (`sonictool genesis`, state-root line check, marker) |
| **stream tar.zst** | kaia | `KaiaSnapshotResolver`: `GET https://snapshots.node.kaia.io/{mainnet|kairos}/pruning-chaindata/latest.txt` → URL; `manifest.json` → `latest.{bytes,uncompressed_bytes,md5}`; job `curl | tar --zstd -xf - -C <datadir>` (needs `zstd`, `pv`) |
| **index CSV pair** | berachain | `BerachainSnapshotResolver`: `GET https://snapshots.berachain.com/index.csv` (`type,size_bytes,block_number,version,created_at,sha256,url,url_s3`), pick `reth-{pruned|archive}` + `beacon-kit-{pruned|archive}` rows with the SAME `block_number` (top-ordered), sha256 verified, `lz4 -d | tar -x` into `<reth datadir>` and `<beacond home>`; bepolia rows absent today → "none, sync normally" |
| **stable tar URL** | gravity | `GET https://gravity-snapshots.b-cdn.net/gravity-{mainnet|testnet}-data/latest.tar` (HEAD gives size; resume with `-C -`), `tar -xf -C <storage_dir>`; never carries `rand_db` / `secure_storage.json` (docs) |
Restore targets the node's own datadir; RPC is NOT servable until extract is finished (wizard "ready" = marker file). Restores always `systemctl stop` the unit(s) first (BSC pattern) and never touch identity files (`priv_validator_key.json`, `identity.yaml`, nodekey).

## 6. Production config (what the drafts already apply, and the preset proposal)
Verified sources: Nethermind `IJsonRpcConfig`/`INetworkConfig`/`IMetricsConfig` + docs.nethermind.io configuration; reth flags from the trees base/op/arc are built from (see `app/docs/prod-rpc-tuning.md`); Kaia `kend.conf` + `cmd/utils/flags.go`; Sonic hardening docs (tiers 1-4); Flare `dev.flare.network/run-node/from-source`; Gravity cluster templates.
- **Public namespaces only** in every draft: eth,net,web3 (+rpc/subscribe where the client has them; Kaia `klay,eth,net,web3`; Sonic `eth,net,web3,sonic`). NEVER expose: debug, trace, txpool, admin, personal, miner, engine; Sonic `ftm` (deprecated) and JS tracers (`--rpc.allow-js-tracers`, off by default since v2.2.5); Kaia `--rpc.unsafe-debug`; Flare admin/index APIs.
- **Binding:** Engine/auth, metrics, CL RPC, CometBFT rpc/proxy_app, Lighthouse/Prysm APIs, gravity inspection → `127.0.0.1`. Known exceptions the toolkit cannot fix by flag: Kaia `--prometheusport` binds all interfaces (firewall it); Avalanche-family serves `/ext/health`, `/ext/info`, `/ext/metrics` on the same public HTTP port (use a proxy that only forwards `/ext/bc/C/rpc` + `/ext/bc/C/ws`).
- **Capacity literals in the drafts** (also visible as bindings): Nethermind `MaxBatchSize 1000`, `RequestQueueLimit 500`, `MaxActivePeers 50`; reth family `rpc.max-connections 3000`, `rpc.max-response-size 64`, `rpc.max-blocks-per-filter 50000`; Kaia `rpc.concurrencylimit 3000`, timeouts 30/30/120/30 s; Sonic `maxpeers 50`, `rpc.batch-request-limit 1000`, `--cache 12000` + `GOMEMLIMIT` 90 % RAM; Story-geth `maxpeers 50`, batch 1000; Vana geth batch 5000/125 MB (official values; preset lowers them); Gravity `rpc.max-connections 3000` (upstream template 20000), `prune.transactionlookup.distance 10064` (docs: must be ≥ 10064).
- **prodPresets.ts proposal** (add to `admin/src/lib/prodPresets.ts`; reuse `RETH_RPC`/`RETH_NOTES`):
```ts
// reth family additions: tempo, fluent, berachain, gravity (same keys as base/optimism; no engine peers flags — they have none or no CL gossip)
tempo:     { flags: { ...RETH_RPC, 'http.api': 'eth,net,web3', 'ws.api': 'eth,net,web3' }, notes: [...RETH_NOTES, 'tempo node flags INFERRED from reth: check `tempo node --help` before enabling.'] },
fluent:    { flags: { ...RETH_RPC, 'http.api': 'eth,net,web3,rpc', 'ws.api': 'eth,net,web3,rpc' }, notes: RETH_NOTES },
berachain: { flags: { ...RETH_RPC, 'http.api': 'eth,net,web3,rpc', 'ws.api': 'eth,net,web3,rpc', 'max-outbound-peers': '40', 'max-inbound-peers': '20' }, notes: [...RETH_NOTES, 'bera-reth only; beacond is not overridden.'] },
gravity:   { flags: { 'rpc.max-connections': '3000', 'http.api': 'eth,net,web3', 'ws.api': 'eth,net,web3' }, notes: ['gravity_node txpool/consensus keys are NOT overridable (public_full_node.yaml).'] },
// nethermind (needs the UnitFlagOverrides KEY fix, §1.1.9; keys are case-sensitive)
gnosis:    { flags: { 'JsonRpc.MaxBatchSize': '500', 'JsonRpc.RequestQueueLimit': '1000', 'Network.MaxActivePeers': '50', 'JsonRpc.EnabledModules': 'Eth,Net,Web3' }, notes: ['Nethermind: values are starting points; EnabledModules removes Subscribe+Rpc (no eth_subscribe over WS).'] },
joc:       { flags: { 'JsonRpc.MaxBatchSize': '500', 'JsonRpc.RequestQueueLimit': '1000', 'JsonRpc.EnabledModules': 'Eth,Net,Web3' } },
// geth-style
kaia:      { flags: { 'rpc.concurrencylimit': '2000', rpcexecutiontimeout: '10', 'rpc.gascap': '25000000', 'rpc.evmtimeout': '3s' }, notes: ['Flag names from kaiachain/kaia cmd/utils/flags.go (rpc.gascap, rpc.evmtimeout exist); values are judgement.'] },
sonic:     { flags: { 'rpc.gascap': '25000000', 'rpc.evmtimeout': '3s', 'rpc.batch-request-limit': '500', maxpeers: '30' }, notes: ['rpc.gascap / rpc.evmtimeout exist in sonic config/flags (VERIFIED names).'] },
vana:      { flags: { 'rpc.batch-request-limit': '1000', 'rpc.batch-response-max-size': '25000000', 'rpc.evmtimeout': '3s', 'rpc.gascap': '25000000' }, notes: ['Replaces the official compose values (5000 / 125 MB / evmtimeout 0 = unlimited).'] },
data:      { flags: { 'rpc.batch-request-limit': '500', 'rpc.evmtimeout': '3s', 'rpc.gascap': '25000000' } },
```
(`flare`: no flag presets — avalanchego tuning is in `config.json`, same as avalanche: "Not covered by flag overrides"; add to the "Found, NOT changed" list in `prod-rpc-tuning.md`.)
- **Throughput caveat** (as in prod-rpc-tuning.md): one node ≠ thousands of rps for wide `eth_getLogs`; recommend a caching, rate-limiting proxy in front. Gnosis/Kaia/Sonic public tips are third-party.

## 7. Wiring points (per network, after approval — intake §14)
Resources (drafted here): `network.yml`, `clients.yml`, `node.service.tmpl` (+ companion/one-shot tmpls). Catalog: `NetworkId` companion vals (gnosis joc flare sonic kaia vana tempo berachain data fluent gravity); `EnvId` for new env ids only: `chiado`, `flare`, `songbird`, `coston2`, `kairos`, `moksha`, `moderato`, `bepolia`, `aeneid` (`mainnet`, `testnet` exist). Code: the §1.2 classes + `Toolkit.production()` maps (release, tip, start, snapshot/artifact URL) + agent `AgentMain` `ChainNodeRuntime` (proc + height). Tests: `ShippedPortCatalogTest` (ports in `PORTS.md` — drafts pass a manual uniqueness check against the 25 shipped clients.yml), `YamlNetworkFactsRepositoryTest` + `NetworksRoutesTest` id lists, start-plan + eth_blockNumber parse tests, one resolver test per snapshot pattern.
**Admin touch list** (YAML-driven mostly; special cases only): `lib/networksCatalog.ts`, `lib/network.ts`, `lib/networkDocs.ts` (doc URLs), `lib/networkPaths.ts` (legacy paths), `lib/router.ts`, `lib/prodPresets.ts` (§6), `components/NetworkIcon.tsx` (ticker/colour), `components/InstallOptionsPicker.tsx` (snapshot-type/node-mode pickers: sonic pruned/archive, tempo archive/full/minimal, berachain pruned/archive, nethermind full/archive), `components/DiskLayoutPanel.tsx` (role ids: execution/consensus, state/scratch, data, chaindata/snapshots, blockchain), `components/NodeInstallWizard/utils.ts`, `App.tsx`, `api.ts`, `pages/NotificationsPage.tsx` (only if it lists networks). Aarch64 gating: flare, sonic, vana(mainnet geth), fluent, gravity ship x86_64 only — the wizard must refuse/warn on aarch64 hosts (`nameAarch64` absent).

## 8. Reuse of existing references
`ethereum` → gnosis (units, JWT, lighthouse tmpl, Cluster/Ports/UnitBodies layout). `bsc` → kaia, sonic, vana, data (geth-ish flags, init step, snapshot script protocol, release resolver). `base` → berachain, data, vana (EL+CL unit pair, companion install, snapshot via client CLI). `polygon` → shibarium (bor+heimdall `.deb`, config patch) if approved. `avalanche` → flare. `etc` (core-geth zip) → nethermind/flare zip installers.
