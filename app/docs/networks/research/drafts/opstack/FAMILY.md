# OP Stack family — shared design

Scope: unichain, ink, soneium, worldchain, mode, zora, lisk, redstone, fraxtal, cyber, celo, mantle, blast, b3, hemi.
Checked 2026-10-13. Tags: **VERIFIED** (how) / **INFERRED**. Companion files: `networks.md` (per-network), `risks.md`, drafts in `<id>/`, shared template + Dockerfiles in `shared/`.

## 0. Verdict

| group | networks | route |
|---|---|---|
| **A — registry chains, upstream op-reth + op-node** | unichain, ink, soneium, lisk, mode, zora | one recipe, `--chain <name>` / `--network <name>-<net>`; no genesis / rollup.json to ship |
| **A' — same, but the EL is the project's own reth fork with release assets** | worldchain | `world-chain` GitHub tarball + upstream op-node |
| **B — own op-geth/reth fork, not in registry (or registry-skipped)** | celo, mantle (hemi) | different binaries, rollup.json, DA side-car → separate variant, later |
| **DEFERRED** | redstone (shut down), blast (shutting down), b3 (archived), cyber, fraxtal, hemi | see networks.md |

Facts that shape everything (all VERIFIED):
1. **op-geth is dead for OP chains**: end-of-support 2026-05-31; Karst (active; registry `karst_time` 1781712001-1789992000 on every registry chain) is not implemented in it. op-reth is "the primary supported client" (docs.optimism.io execution-clients page). Old op-geth docs (Ink repo, Mode/Zora L2BEAT pages, Mantle docs) are stale — do not model them.
2. **Superchain Registry names are compiled into both binaries.** `op-node --network <file>-<superchain>` where file = registry toml basename (`op-core/superchain/sync-superchain.sh`: `name=$(basename toml)`, network = mainnet|sepolia dir). `op-reth --chain <file>` for mainnet, `<file>-sepolia` for sepolia (`key_for!` macro in `op-reth/crates/chainspec/src/superchain/chain_spec_macro.rs`). Present in v2.6.0 / v1.19.9: celo\*, cyber, fraxtal, ink, lisk, mode, redstone, soneium(-minato), unichain, worldchain, zora (+op). **Not** in the registry at all: mantle, blast, b3, hemi. **Celo mainnet is explicitly dropped by `sync-superchain.sh`** (chain 42220, "non-standard genesis") → upstream op-node cannot run Celo.
3. op-reth ships **default OP Stack discv5 bootnodes for every chain** (`chainspec/src/bootnodes.rs`: OP Labs + Base + Uniswap pools) — Unichain's own compose passes the same list explicitly for older versions only.
4. `op-node` ≥ v1.19.1 **removed the req/resp CL sync** (`--syncmode.req-resp` is a no-op, source `flags.go`; Conduit README). A node that falls behind catches up through **EL peers**, or by re-deriving from L1. So `--syncmode=execution-layer` only works where EL peers exist; for small chains the safe default is `consensus-layer` + snapshot.
5. **op-reth always runs discv5** (UDP) and prints `--discovery.v5.port` default **9200**; it also opens an IPC socket (reth default `/tmp/reth.ipc`, INFERRED from reth defaults). `chains/optimism/run.sh.tmpl` passes neither → two op-reth nodes on one host (optimism mainnet + sepolia, or any family member) fight over 9200/udp. The shared script passes `--discovery.v5.port=<+8>` and `--ipcdisable`; same fix should land in optimism.
6. Same pins everywhere: `op-reth/v2.6.0` + `op-node/v1.19.9` (published 2026-10-07 on ethereum-optimism/optimism; **no release assets**, source only — `gh api releases` → `assets=0`). They equal the pins already in `chains/optimism` and in `LiskHQ/lisk-node` README, so the family adds **zero new build recipes** for group A.

## 1. Should `optimism` become the family base? — Yes

Optimism is already a generic OP-Stack recipe with one hard-coded cluster table. Proposal: **extract `chains/opstack` (shared package + `resources/chains/opstack/`), make `optimism` its first spec instance.** `chains/optimism` keeps its public class names as thin subclasses (`OptimismNodeStart`, `OptimismNodeProcessStarter`, `OptimismNodeHeightProbe`, `OptimismNetworkTipProbe`, `OptimismUnitBodies` object delegating) so `Toolkit.kt`, `AgentMain.kt`, `L2ParentChainStartTest`, `OptimismUnitBodiesTest`, `OptimismNodeStartTest`, `OptimismSyncLogTest`, `DownloadClientProgramBuildTest` keep compiling; their assertions are the regression net for the refactor. OP-Mainnet-only logic (pre-Bedrock `snapshotAlways`, `datadirs.optimism.io` listing) becomes `OpstackSnapshotSource.OpLabs`. Do the refactor as PR 1 with *no* behaviour change except the two fixes in fact 5; add the new networks in PR 2+.

## 2. Process model (identical to optimism)

One systemd unit per node → `bin/rpcnode-<id>-run.sh` (written under node_dir by `OpstackNodeProcessStarter`) which: (1) picks `op-reth-<arch>`/`op-node-<arch>` prebuilt by the panel build hosts (`use_prebuilt`) else builds from the pinned tags (Rust + Go + yq, toolchains under `node_dir/tools`); (2) restores a snapshot once (`.toolkit/snapshot-<full|archive>.done`); (3) generates `jwt.hex`; (4) supervises `op-reth` (or `world-chain`) first, then `op-node`, restarting as optimism does. Draft: `shared/run.sh.tmpl` (placeholders listed in its header; `bash -n` clean). Unit template = optimism's, byte-identical (`<id>/node.service.tmpl`; loader falls back per file, so a shared `chains/opstack/node.service.tmpl` is also possible if `HostSystemdUnitTemplate.load("opstack", …)` is used).

### Start args (EL)
`op-reth node --chain=<registry> --datadir=<execution> --http --http.addr=0.0.0.0 --http.port=<+0> --http.api=eth,net,web3,txpool,debug,trace --ws … --ws.port=<+1> --ipcdisable --authrpc.addr=127.0.0.1 --authrpc.port=<+3> --authrpc.jwtsecret=<state>/jwt.hex --rollup.sequencer=<registry sequencer_rpc> --rollup.disable-tx-pool-gossip --metrics=0.0.0.0:<+4> --port=<+2> --discovery.port=<+2> --discovery.v5.port=<+8> [--trusted-peers=<enodes>] [<flavor flags>] [prune flags when MODE=full]`
Prune (full): `--prune.minimum-distance=N --prune.receipts.distance=N --prune.account-history.distance=N --prune.storage-history.distance=N`, N = 30 d / block time (2 s → 1 296 000, 1 s → 2 592 000); bodies never pruned (op-reth rejects `--minimal`; docs.optimism.io). Flags `--rollup.sequencer`, `--rollup.disable-tx-pool-gossip` VERIFIED in `op-reth/crates/node/src/args.rs`; prune flags are the ones optimism already ships; `--ipcdisable`/`--discovery.v5.port` are reth-core flags (Unichain compose uses `--discovery.v5.port`; `--ipcdisable` INFERRED).
World Chain flavor: binary `world-chain` (same CLI) + `--flashblocks.enabled` (official compose).

### Start args (CL)
`op-node --l1=<L1 rpc> --l1.rpckind=basic --l1.beacon=<beacon> --l2=ws://127.0.0.1:<+3> --l2.jwt-secret=… --network=<registry> --syncmode=<per network> --l2.enginekind=reth --verifier.l1-confs=4 --rpc.addr=0.0.0.0 --rpc.port=<+5> --metrics.enabled --metrics.addr=0.0.0.0 --metrics.port=<+7> --p2p.listen.tcp=<+6> --p2p.listen.udp=<+6> --p2p.priv.path/--p2p.peerstore.path/--p2p.discovery.path=<consensus>/… [--p2p.static=<multiaddrs>] [--p2p.bootnodes=<enr|enode,…>]`. All flag names VERIFIED in `op-node/flags` v1.19.9. L1 = Ethereum mainnet for mainnets, Sepolia for the testnets (every group-A chain, registry `parent.chain`), handled exactly like optimism (`l1_rpc` / `l1_beacon` install options, `arbL1FromInstallOptions`, `ArbL1Parent`, `NodeParentChain` picker).

Per-network sync mode (literal in the Kotlin spec **and** in `clientConfig` binding `syncmode`): `execution-layer` for unichain + worldchain (their official composes use it; many EL peers), `consensus-layer` for ink, soneium, lisk, mode, zora (snapshot + derive; EL-sync needs peers nobody publishes). INFERRED for ink/soneium; VERIFIED for lisk (README: "snap sync requires reachable peers… recommend full/archive").

## 3. Kotlin design — one shared package + thin per-network classes

```
chains/opstack/infrastructure/
  OpstackSpec.kt          data: OpstackNetworkSpec(networkId, label, elFlavor, envs[]) · OpstackEnvSpec(env, rethChain, nodeNetwork,
                          sequencer, blockTimeSec, syncMode, snapshot: OpstackSnapshotSource, clStatic, clBootnodes, elTrusted,
                          elExtraFlags, ports: OpstackPorts) · sealed OpstackSnapshotSource{None, OpLabs(host,net), Alchemy(slug,path),
                          Gelato(host), Fixed(full,archive)} · enum ElFlavor{OP_RETH, WORLD_CHAIN}
  OpstackToolchain.kt     const RETH_TAG / NODE_TAG / GO_VERSION (moved from OptimismUnitBodies; the clients.yml pin test reads these)
  OpstackNodeMode.kt      full|archive (moved from OptimismNodeMode) + snapshot option (restore|none) + snapshot_url option
  OpstackUnitBodies.kt    unit(spec,env,…) / runScript(spec,env,…) — renders chains/opstack/run.sh.tmpl (+ node.service.tmpl)
  start/OpstackNodeStart.kt                     (open class; spec injected)
  proc/OpstackNodeProcessStarter.kt             (open class; spec injected)
  http/OpstackRpc.kt  OpstackSyncLog.kt  OpstackNodeHeightProbe.kt  OpstackNetworkTipProbe.kt   (moved from optimism; log path from spec)
chains/<id>/infrastructure/<Id>Opstack.kt       object <Id>Opstack { val SPEC = OpstackNetworkSpec(…) }       ← the only per-network data
chains/<id>/infrastructure/<Id>NodeStart.kt     class <Id>NodeStart : OpstackNodeStart(<Id>Opstack.SPEC)      ← 1 line each (x4: start, starter,
chains/<id>/…/<Id>NodeProcessStarter.kt, <Id>NodeHeightProbe.kt, <Id>NetworkTipProbe.kt                          height, tip) keeps the intake's class-name contract
chains/worldchain/…/WorldchainClientReleaseResolver.kt   (only networks with a github source: worldchain = github.latestRelease("worldcoin/world-chain", tagPrefix="v"))
```
Shared test: `OpstackSpecMatchesYamlTest` iterates `ALL_SPECS` and asserts, per network/env, that `clients.yml` ports == `spec.ports`, pinned tags == `OpstackToolchain`, `network.yml` literals (`prune.distance`, `syncmode`, `http.api`, `LimitNOFILE`) == the unit/script constants, and `ShippedPortCatalogTest` stays green. `OpstackRunScriptTest` renders the template for every spec/env/mode and runs `bash -n` on it.

Launch args from `OpstackNodeStart.plan` (superset of optimism's): `--toolkit-env`, `--toolkit-mode=full|archive`, `--toolkit-snapshot=restore|none`, `--toolkit-snapshot-url=<custom>`, `--toolkit-l1-rpc`, `--toolkit-l1-beacon`, `--toolkit-execution/-consensus/-snapshots=<role dirs>`; `NodeLaunchSpec(kind="binary", entry="bin/rpcnode-<id>-run.sh", logFile="logs/<id>.log")`, `NodeHeightSpec(kind="eth_rpc", portRole="http")`. Env normalisation: spec decides valid env ids (`mainnet`, `sepolia`), unknown → mainnet (as optimism).

Wiring (per network, ~6 lines): `NetworkId.<ID>`; `Toolkit.kt` tip map, start map (+ release map for worldchain); `AgentMain.kt` `"<id>" to ChainNodeRuntime("<id>", <Id>NodeProcessStarter(), <Id>NodeHeightProbe())`; `ShippedNetworkIds.ALL`; `YamlNetworkFactsRepositoryTest` + `NetworksRoutesTest` id lists; `L2ParentChainStartTest` one line each. Do **not** add EnvIds (mainnet/sepolia exist).

## 4. Height / tip probes (exact)

Local: `POST http://127.0.0.1:<http>` body `{"jsonrpc":"2.0","id":1,"method":"eth_blockNumber","params":[]}` → `{"jsonrpc":"2.0","id":1,"result":"0x3a5f2b1"}`; parse hex → Long (`OptimismRpc.parseBlockNumber`, reused). Null (agent skips sample) when the node is still building / restoring. `reading()`: (1) op-reth pipeline % from `logs/<id>.log` (`Status … stage=… target=…` + `Executed block range … end=…`, `OpstackSyncLog` unchanged — world-chain is the same reth log format, INFERRED); (2) else head age via `eth_getBlockByNumber("latest",false).timestamp` ≤ `SYNCED_LAG_SEC` (30 s; 15 blocks at 2 s, 30 at 1 s — consider 15×blockTime) ⇒ 100 %/synced, else `SyncCatchUp`.
Tip: same call against YAML `publicTip.urls` (first that answers); lists in `networks.md` all answered `eth_getBlockByNumber latest` with age ≤ 3 s on 2026-10-13. `sync → active` at the default tip lag ≤ 3 blocks: on **1 s chains (unichain, ink) 3 blocks = 3 s, within jitter of two different RPC providers** → see risks.md R7.

## 5. Release resolver rule

- op-reth / op-node: `source: { type: pinned }` — no resolver (as optimism). Bump = edit `OpstackToolchain` + every `clients.yml` pin (+ `GO_VERSION` from the monorepo `go.mod` at the new tag) after verifying the pair on the *newest hardfork any family chain has scheduled* (registry is compiled in: a chain's new fork time is only known to a binary built from a registry commit that contains it). Candidate automation: `GitHubRelease(repo=ethereum-optimism/optimism, tagPrefix="op-reth/v")` and `"op-node/v"` → propose a bump, never auto-apply.
- world-chain: `GitHubRelease(worldcoin/world-chain, tagPrefix="v")` → latest `vX.Y.Z` (the repo also tags `proofs/v…` — excluded by the prefix). Asset template VERIFIED: `https://github.com/worldcoin/world-chain/releases/download/{tag}/world-chain-{tag}-{x86_64|aarch64}-unknown-linux-gnu.tar.gz` (v2.4.5: 44.98 MB, one file `world-chain`, 200 on HEAD).

## 6. Builds — avoid 12 identical compiles

Today `FleetClientBuilder`/`DockerfileSource` resolve `build.dockerfile` as a plain file beside the network's `clients.yml` (loader rejects `/` and `..`) and the build job id is per `network-env-program-tag-arch`. Seven networks × 2 envs × (op-reth + op-node) = 28 builds of two binaries. Options:
- (a) interim, zero core change: copy the two Dockerfiles into each network folder (28 builds; the host script fallback is also an option but compiles on every node).
- (b) **recommended core change (small):** allow `dockerfile: "opstack:op-reth.Dockerfile"` (resolved from `chains/opstack/`) and key the build result by `(dockerfile hash, tag, platform)` so envs/networks that share it reuse one artifact; publish under each network's `<output>-<arch>` names. INFERRED effort: `YamlClientProgramCatalog` (path check), `DockerfileSource`, job-id/dedupe in `FleetClientBuilder`/`DownloadClientProgramUseCase.publishBuilt`.
Dockerfiles in `shared/` are verbatim copies of optimism's (Ubuntu 22.04 base → low glibc; op-reth needs the registry submodule, op-node runs `sync-superchain.sh`).

## 7. Snapshots

Policy for the family: `network.yml snapshot: never` (the toolkit's snapshot job machinery is not used), restore is owned by the run script exactly like optimism; install options `snapshot=restore|none` (default `restore` when a source exists) and optional `snapshot_url` (custom archive). Provider kinds (script `resolve_snapshot`, mirrored by `OpstackSnapshotSource`):

| kind | used by | discovery (VERIFIED 2026-10-13) | integrity | formats / layout |
|---|---|---|---|---|
| `oplabs` | optimism | `https://datadirs.optimism.io/` fancyindex → newest `mainnet-op-<net>-reth-<kind>-storage-v2-YYYY-MM-DD.tar.zst` + `.sha256sum` | sha256 (aria2 `--checksum`) | .tar.zst, wrapper dir (strip 1) |
| `gelato` | **lisk** (official: `snapshots.lisk.com/mainnet` 302 → ChainSnap) | `https://lisk.snapshots.gelato.cloud/index.html` → `…/reth/{full,archival}/datadir/lisk-reth-<k>-datadir-<block>-<epoch>.tar.lz4`, newest by epoch; latest 2026-10-09: full 41.4 GB, archival 60.6 GB | `<url>.sha256` | .tar.lz4, root `./` (strip 0), `db/database.version`=2 |
| `fixed` | **worldchain** (official, `simple-worldchain-node`) | stable S3 URLs rewritten daily: `world-chain-snapshots.s3.eu-central-2.amazonaws.com/reth_{full,archive}.tar.lz4` (full 891.7 GB, archive 1.84 TB, 2026-10-13); testnet bucket `world-chain-testnet-snapshots` (18.7 / 27.2 GB) | none published (S3 ETag only) | .tar.lz4 (their entrypoint: `lz4 -dc \| tar --strip-components=1`; first member name not inspected — script autodetects) |
| `alchemy` | unichain, ink, soneium (+ unichain/ink sepolia) — **third party, free, no SLA** | page scrape: `https://www.alchemy.com/snapshots/<slug>` embeds one public R2 URL `https://pub-c5ad1adfc7a74a99a19b1c7f3d621fa8.r2.dev/rpc-platform/<chain>/<main\|sep>/reth/reth-<YYYYMMDDHHMMSS>.tar.gz`; sizes: unichain 205.8 GB, ink 200.9 GB, soneium 715 GB (2026-10-09), unichain-sep 216 GB, ink-sep 163 GB; HEAD 200 + `Accept-Ranges: bytes` | none (R2 ETag only) | .tar.gz, root `./` (strip 0); contains `static_files/`, sometimes `p2p-peerstore.db/`, `proofs-storage/`, `start.sh` (ignored); "archive pruning values" datadir |
| `custom` | any | operator's `snapshot_url` (any of .tar/.tar.gz/.tar.lz4/.tar.zst) | none | autodetect strip |
| not used | mode, zora | Conduit `gs://conduit-networks-snapshots/<slug>/latest.tar` is **requester-pays** (anonymous GET → HTTP 400) | | |
| optional later | unichain/ink/soneium/celo/fraxtal | PublicNode `snapshots.publicnode.com/<chain>-reth-base.tar.lz4` + `-part-<block>` diff (302 → signed S3; base 91 GB + part 43 GB for unichain) | | two-step apply (INFERRED), skipped |

Restore rules (script): resolve → size via HEAD → if free space on `snapshots` role > size×1.05: aria2c resumable download (+checksum when published) → detect first member (`./`, `db`, `static_files`… ⇒ strip 0) → extract into `execution` role → delete archive; else **stream** (`curl | tar -x`, not resumable) to avoid 2× disk (World Chain 0.9–1.8 TB). Post-check `db/mdbx.dat` or `static_files/`. Marker `.toolkit/snapshot-<kind>.done`. One-time; a Full node restores the archive-style datadir and relies on the running pruner (disk = archive size until pruned) — except lisk/worldchain/optimism which publish full flavors.
Can the node serve RPC during restore? No (script restores before starting op-reth) → agent height probe returns null; wizard "ready" unaffected (same as optimism). Optional panel enhancement: a HEAD-only `OpstackSnapshotSizeProbe` to show the size in the wizard (not required).

## 8. Production config

Capacity literals shipped in `clientConfig.bindings` (identical to the script/unit): `http.addr 0.0.0.0`, `http.api eth,net,web3,txpool,debug,trace` (optimism parity — **decision, risks R5**), `ipcdisable`, `rollup.disable-tx-pool-gossip`, `prune.distance`, `syncmode`, `LimitNOFILE 1048576`, plus the ports. Prod presets (apply from Node config → Flag overrides; keys are the reth flags already verified in `prodPresets.ts`): add one preset object and reuse for every family id:

```ts
const OPSTACK: ProdPreset = {            // spread of the existing `optimism` preset
  flags: { ...RETH_RPC, 'http.api': 'eth,net,web3,rpc', 'ws.api': 'eth,net,web3,rpc',
           'max-outbound-peers': '40', 'max-inbound-peers': '20' },   // 'disable-tx-gossip' no longer needed: the script passes --rollup.disable-tx-pool-gossip
  notes: [...RETH_NOTES, 'op-reth / world-chain only (op-node flags are not overridden).'] }
export const PROD_PRESETS = { …, optimism: OPSTACK, unichain: OPSTACK, ink: OPSTACK, soneium: OPSTACK, worldchain: OPSTACK, lisk: OPSTACK, mode: OPSTACK, zora: OPSTACK }
```
Reference production settings that exist in upstream guides (docs.optimism.io / chain repos): `--rollup.disable-tx-pool-gossip`, sequencer forward (`--rollup.sequencer`), `--authrpc.addr` loopback (every upstream compose binds 0.0.0.0 inside Docker — we use 127.0.0.1), op-node `--verifier.l1-confs=4` (Unichain/Lisk/Conduit/Celo/Mantle all set 3-4; op-node default is 0, VERIFIED in `flags.go`) — **added to the draft script**, `--l1.trustrpc` only for a trusted own L1 (Celo/World set it; default off), `--syncmode.offset-el-safe` default 12 h (World: 4h — leave).
Do **not** expose publicly: engine API (loopback already), op-reth `debug,trace,txpool,admin` namespaces on the public RPC port, op-reth/op-node **metrics on 0.0.0.0** (`--metrics`, `--metrics.addr`; optimism parity — bind 127.0.0.1 or firewall), op-node RPC `0.0.0.0:<+5>` (read-only unless `--rpc.enable-admin`, still firewall), the JWT file. Public-RPC front proxy as in prod-rpc-tuning.md ("what one node can and cannot do").
Hardware: see `networks.md` (INFERRED from snapshot sizes; re-measure after the first install). Chain-specific: World Chain official minimum 8 cores / 32 GB / >4 TB NVMe; Lisk 16-32 GB; Celo 16 GB+/1 TB (variant B).

## 9. Variant B (celo, mantle, hemi) — design hook only
Same `chains/opstack` package, second `ElFlavor` + second template (`run-geth.sh.tmpl`): EL = geth-class fork with **its own rollup.json/genesis** (`--rollup.config`, no `--network`), optional side-cars (EigenDA proxy for Celo, none for Mantle). Needs a spec field `rollupConfigUrl` and a `sidecars` list. Not drafted — see networks.md for the exact component list and pins.

## 10. Admin touch list (YAML-driven where possible)
- `lib/network.ts` `isNoSnapshotNetwork`, `NodeInstallWizard/utils.ts` `MULTI_DISK_NETWORKS`, `DiskLayoutPanel.tsx` `expectedDiskRolesHint`, `useWizardOrchestration.ts` `wantsL1ParentPicker`, `NodeParentChain.tsx` `L2_NETWORKS`: each hard-codes `'optimism'`. **Replace with one `isOpstackNetwork(id)` (set of the ids above + optimism)**; better: a `family: opstack` key in `network.yml` surfaced by `/networks` so no id list is needed (needs a YAML schema addition — INFERRED, check `YamlNetworkFactsRepository` strictness).
- `InstallOptionsPicker.tsx`: generalise `OPTIMISM_NODE_OPTIONS` → `OPSTACK_NODE_OPTIONS` (+ `OPSTACK_SNAPSHOT_OPTIONS` restore/none), return them for every family id × (mainnet|sepolia); hints per network from the YAML sizes.
- `prodPresets.ts` (section 8), `networkPaths.ts` (`/data/rpcnode/<id>` etc. per id), `networkDocs.ts`, `NetworkIcon.tsx` META (slug/ticker/colour per id; cryptocurrency-icons has none of these, falls back to ticker disc).
- These admin files currently have uncommitted local edits in the working tree (git status) — rebase the generalisation onto them.
