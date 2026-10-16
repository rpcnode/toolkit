# Family design — Substrate + other non-EVM L1s (polkadot, peaq, near, stacks, aleo, fuel; hedera deferred)

Facts are tagged **VERIFIED** (curl / GitHub API / source at the named tag, 2026-10-13) or **INFERRED**.
Shape copied from `sui` (sui-node + generated config + unit), `cardano` (sentinel snapshot scheme `mithril://`), `optimism`/`xrpl`
(`build:` Dockerfile when upstream ships no binary, `l1Parent` picker), `bitcore` (spec-driven family package).

## 1. Shared vs per-network Kotlin

Two family packages, everything else is a thin per-network package (intake rule: no `when (network)` monolith).

```
chains/substrate/            SHARED by polkadot + peaq
  SubstrateChainSpec.kt        data class: networkId, programId, binary, workers[], chainArg(env), chainDirId(env),
                               dbKind(mode), relay model, supportedEnvs        (like BitcoreChainSpec)
  SubstrateFlags.kt            builds the flag list (parachain flags [-- relay flags]) from spec + install options + ports
  SubstrateUnitBodies.kt       renders node.service.tmpl (HostSystemdUnitTemplate) — same placeholders as SuiUnitBodies
  SubstrateHostBinaries.kt     ensure bin/<binary> (+ workers), chmod +x, same-version check (`--version` of the three)
  SubstrateNodeProcessStarter  create dirs, ensure binaries, chain spec file present (peaq), installCustomUnits
  SubstrateRpc.kt              chain_getHeader / system_health JSON-RPC (shared by height AND tip probe)
  SubstrateNodeHeightProbe / SubstrateNetworkTipProbe
  SubstrateSnapshotResolver    snapshots.polkadot.io table -> `substrate-files://<dated url>?db=paritydb|rocksdb`
chains/nonevm/               SHARED by near, stacks, aleo, fuel (+ hedera later) — only the probe plumbing
  HeightReader.kt              sealed: JsonRpcField(method,params,path[,hex]) | GetJsonField(path) | GetPlainLong() | GraphQlField(query,path)
  ReaderHeightProbe / ReaderTipProbe   HostNodeHeightProbe / NetworkTipProbe over a HeightReader (one reader instance is
                               used by BOTH, as the intake requires: tip reuses the local-height reader)
  NonEvmUnit.kt                `render(chainDirName, env, nodeDir, execStart, logFile, nofile)` = the sui unit renderer
chains/{polkadot,peaq,near,stacks,aleo,fuel}/infrastructure/
  <Id>NodeStart (ChainNodeStart, 15 lines), <Id>NodeProcessStarter, <Id>NodeHeightProbe / <Id>NetworkTipProbe (adapters),
  <Id>ClientReleaseResolver, <Id>UnitBodies (flags + literals mirrored from network.yml), <Id>Chains (env facts),
  + snapshot resolver where a snapshot exists (polkadot, stacks)
```
`PolkadotNodeStart = SubstrateNodeStart(SubstrateSpecs.POLKADOT)`; `PeaqNodeStart = SubstrateNodeStart(SubstrateSpecs.PEAQ)`.
Per-network (NOT shareable): `NearConfigPatch` (neard init + config.json), `StacksConfigToml` (+ Bitcoin parent), `AleoKeyless` start (no key),
`FuelEnvFile` (KEYPAIR/RELAYER), the release resolvers (four different rules, §5).

New `EnvId` vals: `paseo`, `westend`, `agung` (`mainnet`/`testnet` exist). New `NetworkId` vals: POLKADOT, PEAQ, NEAR, STACKS, ALEO, FUEL.

## 2. Process model
One systemd unit per node (`HostNodeLaunchSupport.installCustomUnits`, `NodeLaunchSpec.kind="binary"`, entry `bin/<binary>`), everything
under `node_dir`: `bin/`, `logs/`, `.toolkit/` (0600 secrets), generated configs, state role dir for the DB. Units are the sui template
(`Restart=on-failure`, `KillMode=control-group`, `LimitNOFILE={{nofile}}`); only `TimeoutStopSec` differs (polkadot 120, peaq 180, near 300
RocksDB flush, stacks 180, aleo 120, fuel 180). fuel's unit adds `EnvironmentFile={{node_dir}}/.toolkit/fuel.env`.
Install = binary download → (zip/tar extract where needed) → `Start` writes config + unit. No Docker anywhere; peaq and aleo-testnet use the
existing `build:` fleet (Dockerfile run on a build agent, binary synced as `<name>-<arch>`), as `op-reth`/`xrpld` do.

## 3. Start args (exact; every literal mirrors `network.yml` clientConfig.bindings and the unit)

**polkadot** (`bin/polkadot`; `polkadot-prepare-worker` + `polkadot-execute-worker` in the SAME dir — VERIFIED workers.rs: looked up next to current_exe, version-checked)
```
polkadot --chain <polkadot|paseo|westend> --base-path <state> --database paritydb --sync warp --state-pruning 256 --blocks-pruning 256
  --name rpcnode-<env> --no-telemetry --port <p2p> --rpc-port <http> --rpc-external --rpc-methods safe --rpc-cors all
  --rpc-max-connections 100 --rpc-max-request-size 15 --rpc-max-response-size 15 --rpc-max-subscriptions-per-connection 1024
  --rpc-message-buffer-capacity-per-connection 64 --trie-cache-size 1073741824 --in-peers 32 --out-peers 8 --prometheus-port <metrics>
```
archive mode: `--database rocksdb --sync full --state-pruning archive --blocks-pruning archive`. All flags VERIFIED in sc-cli at polkadot-stable2609
(defaults 100 / 15 MB / 15 MB / 1024 / 64 VERIFIED in `substrate/client/cli/src/config.rs`). HTTP and WS share `--rpc-port`. Default network
backend in stable2609 is litep2p, which also listens `udp/<port>/webrtc-direct` on the same number → `protocol: both` in clients.yml.
Prometheus defaults to 127.0.0.1:9615 (clashes across nodes) so `--prometheus-port` is always passed; keep it loopback (no `--prometheus-external`).
Chain dir ids: `polkadot`, `paseo`, `westend2` (VERIFIED from the built-in specs). DB lives at `<base>/chains/<id>/paritydb` or `/db`.
Pruning mode is stored in the DB on first start → the wizard must warn that pruned↔archive needs a wipe.

**peaq** (`bin/peaq-node`, built; chain spec `peaq-raw.json` from the repo tag, 2.5 MB VERIFIED)
```
peaq-node --parachain-id 3338 --chain <node_dir>/peaq-raw.json --base-path <state> --name rpcnode-peaq --no-telemetry --port <p2p>
  --rpc-port <http> --rpc-external --rpc-methods safe --rpc-cors all --rpc-max-connections 100 --runtime-cache-size 64 --state-pruning archive
  --in-peers 32 --out-peers 8 --prometheus-port <metrics>
  -- --port <cl_p2p> --rpc-port <cl_rpc> --sync warp --state-pruning 256 --blocks-pruning 256 --no-telemetry
```
Args before `--` = parachain, after = embedded Polkadot relay chain (VERIFIED README + command.rs: `peaq-node [parachain-args] -- [relaychain-args]`,
relay chain = `polkadot` from the spec's `relayChain`). Upstream's own commands (`--rpc-cors=all --state-pruning archive … -- --port 30343 --rpc-port 9977 --sync warp`)
VERIFIED on docs.peaq.xyz. **Relay modelling:** default `relay=embedded` (self-contained, a second pruned DB ~100 GB inside the node); option
`relay=external` replaces the `-- …` block with `--relay-chain-rpc-urls ws://127.0.0.1:<polkadot http port>` and reuses a `polkadot` node of the same
panel (picker identical to `l1Parent`: a new generic `parentNode` facts field `{network: polkadot, env: mainnet, role: http}`; same-host → 127.0.0.1).
The external mode is INFERRED to work with `--rpc-methods safe` on the relay node (cumulus RPC relay interface uses chain_/state_ reads) — test before offering it.
agung: `--parachain-id 2000 --chain async-agung-parachain-specs.json … -- --chain async-agung-relaychain-specs.json --port <cl_p2p> --rpc-port <cl_rpc>` (no warp; private relay), tag `peaq-dev-v0.0.109`.
Do NOT add `--ethapi debug,trace,txpool` by default (heavy; tracing needs the evm-tracing runtime overrides).

**near** (`bin/neard`, home = state role dir)
```
neard --home <state> run --rpc-addr 0.0.0.0:<http> --network-addr 0.0.0.0:<p2p> --rpc-prometheus-addr 127.0.0.1:<metrics>
```
(flags VERIFIED in neard/src/cli.rs v2.13.4: `--home` global, `run --boot-nodes --network-addr --rpc-addr --rpc-prometheus-addr`.)
Start (once per home, `NearNodeProcessStarter`): (1) `neard --home H init --chain-id <mainnet|testnet> --download-genesis --download-config rpc`
(VERIFIED flags; downloads `nearcore-deploy/<chain>/genesis.json` 324 KB / 18 KB and `<chain>/rpc/config.json` ~5 KB, HTTP 200; the `archive` profile is 403) — creates
node_key.json; (2) bootstrap: POST `network_info` to the first working `publicTip.urls`, `boot_nodes = peer_id@addr` for each `active_peers` entry that is also in `known_producers`
(official epoch-sync guide, near-nodes.io/intro/node-epoch-sync, which does it with curl|jq) and write it to `config.json network.boot_nodes` — refuse to start with an empty list (node hangs at 0 peers);
refresh it on every Start while the node has not caught up; (3) set `store.path` to `<state>/data` (if the state role is not the home), `rpc.enable_debug_rpc=false`, plus the capacity literals from network.yml.
`tracked_shards_config = "AllShards"` already in the rpc profile (VERIFIED). No snapshot: epoch sync + state sync from peers is the official path (FastNEAR: free snapshots deprecated 2025-06-01).

**stacks** (`bin/stacks-node` out of `linux-glibc-x64.zip`)
```
stacks-node start --config <node_dir>/stacks.toml
```
`stacks.toml` = the tag's `sample/conf/<mainnet|testnet>-follower-conf.toml` (VERIFIED at 4.0.5) with patched `[node] working_dir, rpc_bind=0.0.0.0:<http>, p2p_bind=0.0.0.0:<p2p>, prometheus_bind=127.0.0.1:<metrics>`
(+ `[burnchain] peer_host/rpc_port/peer_port/username/password` on mainnet, + `[connection_options] max_http_clients/num_clients/num_neighbors`). Testnet `[burnchain]` is left as the sample
(`mode="krypton"`, `peer_host=bitcoin.regtest.hiro.so`, 18443/18444 — Hiro hosts the Bitcoin regtest, nothing local). **Bitcoin dependency (mainnet):** modelled as a *reused* panel node, not bundled:
install options `burnchain_host` (default `127.0.0.1`), `burnchain_rpc_port` (8332), `burnchain_p2p_port` (8333), `burnchain_user/password`, plus a `parentNode` picker `{network: bitcoin, env: mainnet}`.
stacks-node cannot read bitcoind's `.cookie` (only `username`/`password`, VERIFIED BurnchainConfig) and the toolkit's Bitcoin Core is cookie-only (`BitcoreRpcAuth` note) → either Bitcoin Start gains an optional dedicated `rpcauth` line for
`stacks` (recommended; `rpcauth` stores only a hash, the Stacks password stays in `stacks.toml` 0600), or Stacks Start reads the cookie at every start (breaks silently when bitcoind restarts). Bitcoin must be reachable on P2P **and** RPC,
non-pruned for a from-genesis sync; after the Hiro snapshot a pruned Bitcoin (`prune=1024`, Core ≥25) works (Stacks docs: pruned cannot sync from genesis, must not be offline >~2 days).

**aleo** (`bin/snarkos` out of `aleo-<tag>-x86_64-unknown-linux-gnu.zip`; the zip holds one file `snarkos`)
```
snarkos start --network <0|1> --client --nodisplay --node 0.0.0.0:<p2p> --rest 0.0.0.0:<http> --rest-rps 10 --metrics --metrics-ip 127.0.0.1:<metrics>
  --ledger-storage <ledger> --node-data-storage <nodedata> --logfile <node_dir>/logs/snarkos.log --verbosity 1
```
All flags VERIFIED in cli/src/commands/start.rs at v4.12.0 (`--network` 0 mainnet / 1 testnet / 2 canary is a runtime flag; a client without `--private-key` draws an ephemeral account — VERIFIED parse_private_key).
Unit stdout goes to `logs/snarkos.stdout` so it does not duplicate `--logfile`. **Bootstrap = built-in CDN prefetch** (`--cdn` default `https://cdn.provable.com/v0/blocks/<net>`, VERIFIED CDN_BASE_URL; off with `--nocdn/--dev/--prover`) — no snapshot object exists.
Keep JWT ON (do not pass `--nojwt`): it is the only guard of `/db_backup`, `/node/address`, `/program/{id}/mapping/{name}` (VERIFIED auth_routes).

**fuel** (`bin/fuel-core` out of the tar.gz; `fuel-core-keygen` + `fuel-core-wasm-executor.wasm` ride along)
```
fuel-core run --service-name rpcnode-fuel --ip 0.0.0.0 --port <http> --peering-port <p2p> --db-path <state> --snapshot <node_dir>/chain
  --utxo-validation --poa-instant false --enable-p2p --bootstrap-nodes /dnsaddr/<mainnet|testnet>.fuel.network
  --sync-header-batch-size 50 --sync-block-stream-buffer-size 30 --enable-relayer
  --relayer-v2-listening-contracts=<0xAEB0c00D0125A8a788956ade4f4F12Ead9f65DDf | 0x01855B78C1f8868DE70e84507ec735983bf262dA>
  --relayer-da-deploy-height=<20620434 | 5827607> --relayer-log-page-size=<100 | 500>
  --graphql-number-of-threads 2 --graphql-max-concurrent-queries 1024 --graphql-request-body-bytes-limit 1048576 --api-request-timeout 30s --max-peers-connected 50
```
env file: `KEYPAIR=<secret from fuel-core-keygen new --key-type peering, generated once>`, `RELAYER=<install option l1_rpc>` (both are clap `env` flags, VERIFIED). Flags/addresses from docs.fuel.network mainnet + testnet pages
(VERIFIED fetched), all flag names VERIFIED against v0.48.3 clap sources. `--snapshot` dir = `chain/` with the 4 files from the CDN. Relayer: Ethereum mainnet (mainnet) / Sepolia (testnet) — wrong network => relayer fails; reuse the `l1Parent` picker (rpc only, no beacon).
Do NOT pass `--debug`, `--rpc-port` (gRPC, default 4001 — reserved +7 in PORTS.md, verify with `ss -ltnp` that no 4001/41547 listener appears).

## 4. Height probe (local) and tip probe (public) — exact
Tip = same reader against `publicTip.urls`. Failure ⇒ `null` (agent skips the sample).

| network | request | height field | VERIFIED against |
|---|---|---|---|
| polkadot, peaq | `POST http://127.0.0.1:{http}/` `{"jsonrpc":"2.0","id":1,"method":"chain_getHeader","params":[]}` | `result.number` hex string (`"0x1fdeb31"`) → Long | rpc.polkadot.io, westend-rpc.polkadot.io, quicknode1.peaq.xyz, peaq-agung.api.onfinality.io, pas-rpc.stakeworld.io |
| near | `POST http://127.0.0.1:{http}/` `{"jsonrpc":"2.0","id":"x","method":"status","params":[]}` (GET `/status` also works) | `result.sync_info.latest_block_height` (long); `sync_info.syncing` bool | rpc.mainnet.near.org, rpc.mainnet.fastnear.com, near.drpc.org (POST); GET on first two |
| stacks | `GET http://127.0.0.1:{http}/v2/info` | `stacks_tip_height` (long); `burn_block_height` = local Bitcoin height | api.hiro.so, api.testnet.hiro.so, api.mainnet.hiro.so |
| aleo | `GET http://127.0.0.1:{http}/<mainnet|testnet>/block/height/latest` (also `/v1/…`, `/v2/…`) | body = plain integer (`22782109`) | api.explorer.provable.com/v1/<net>/block/height/latest (+ source routes in rest/src/lib.rs) |
| fuel | `POST http://127.0.0.1:{http}/v1/graphql` `{"query":"{ chain { latestBlock { height } } }"}` | `data.chain.latestBlock.height` (string → Long) | mainnet.fuel.network, testnet.fuel.network |

Substrate best-head vs finalized: `chain_getHeader` without params = best head (public tip uses the same, so lag is apples-to-apples). `system_health` → `result.{isSyncing,peers}` (INFERRED standard Substrate) is an optional `syncing` hint; during warp sync the head stays at the genesis/0 until the state download finishes.
`NodeHeightSpec.kind`: `substrate_rpc`, `near_status`, `stacks_info`, `aleo_rest`, `fuel_graphql`; `portRole = "http"`.
`sync → active` at the default tip lag ≤ 3 works for near (1 s), polkadot (6 s), fuel, aleo; **stacks** post-Nakamoto blocks come every few seconds and the Hiro tip can run ahead of a local node by a handful — see risks.md.

## 5. Release resolver rule (per network, GitHub/CDN verified)
| network | resolver | rule |
|---|---|---|
| polkadot | `PolkadotClientReleaseResolver` | `/releases`, tag `polkadot-stable<YYMM>[-<n>]`, not prerelease, must carry all 3 assets; order by (YYMM, n) numeric — never by date (`polkadot-stable2603-7` has 2 assets; a late patch of an old branch must not win). Today: `polkadot-stable2609` (2026-10-12). Linux x86_64 only |
| peaq | pinned in clients.yml | `peaq-v0.0.113` (git tag + Docker Hub; no GitHub release object since 0.0.112) — bump by hand with the image tag. agung `peaq-dev-v0.0.109` |
| near | `NearClientReleaseResolver` | GitHub releases (0 assets); mainnet = newest non-prerelease semver (`2.13.4`), testnet = newest incl. `-rc.N` (`2.14.0-rc.3`); then HEAD `…/Linux-<arch>/<ver>/neard` on the build bucket must be 200 (VERIFIED for 2.13.4 both arches, 2.14.0-rc.3 x86_64) |
| stacks | `StacksClientReleaseResolver` | newest non-prerelease semver (compare numerically; tags up to 5 parts `3.4.0.0.4`), asset `linux-glibc-x64.zip` present. Today `4.0.5` (published the same day — consider a min-age) |
| aleo | `AleoClientReleaseResolver` | mainnet: newest non-prerelease `v*` that carries `aleo-<tag>-x86_64-unknown-linux-gnu.zip`; testnet: newest `testnet-v*` incl. prerelease (source only, built). Today `v4.12.0` / `testnet-v4.12.0` |
| fuel | `FuelClientReleaseResolver` | newest non-prerelease `v*` (`v0.48.3`, 2026-08-27); ignore `testnet-v*` prereleases (0 assets) |
`GitHubRelease` source in clients.yml covers stacks/fuel/near as-is; polkadot and aleo need the custom resolver; peaq is `pinned`.

## 6. Snapshots
| network | policy | source | notes |
|---|---|---|---|
| polkadot | optional | **snapshots.polkadot.io** (Parity/IBP, official docs.polkadot.com) | multi-file store, no JSON index |
| peaq | never | none published (searched docs.peaq.xyz llms.txt) | parachain syncs via P2P, relay warp |
| near | never | official free snapshots deprecated 2025-06-01 (FastNEAR); archival request-only | epoch sync + state sync |
| stacks | mainnet required, testnet optional | **archive.hiro.so** `<env>/stacks-blockchain/<env>-stacks-blockchain-latest.tar.zst` + `.sha256` | 274.7 GB mainnet (Content-Range VERIFIED), 5.9 GB testnet |
| aleo | never | built-in CDN prefetch (cdn.provable.com) | not a toolkit job |
| fuel | never | none published | genesis P2P sync |
| hedera | — | DEFERRED | |

**PolkadotSnapshotResolver** (`substrate-files://`, like `mithril://`): GET `https://snapshots.polkadot.io/`, parse the `<table>` rows
`name | version | timestamp | last block | size GB | pruning | url` (VERIFIED layout; "last block" may print as `1.47109E+6` — parse as double, size as `NNN.NN GB`). Env/type → snapshot name:

| env | type `pruned` (default) | type `archive` |
|---|---|---|
| mainnet | `polkadot-paritydb-state-blocks-pruned` (22 GB, paritydb, state+blocks 256) | `polkadot-rocksdb-archive` (4.17 TB; no paritydb archive exists) |
| paseo | `paseo-paritydb-prune` (19 GB) | `paseo-rocksdb-archive` (92 GB) |
| westend | `westend-paritydb-prune` (131 GB, pruning 1000) | `westend-paritydb-archive` (1.08 TB) |
(`polkadot-paritydb-prune` is 1.14 TB — state pruned, blocks kept — not offered.) Dated URLs rotate daily: always resolve fresh; never cache the dated URL in clients.yml.
Returns `SnapshotArchive(url="substrate-files://<dated https url>#paritydb|rocksdb", streamUnpack=false, sizeBytes=size*1e9)`.
**Agent runner** (new, like `SuiFormalSnapshotRunner`): GET `<url>/files.txt` (VERIFIED: ~478 lines, paths like `full/000192.sst`, `full/index_00_16`), download each file with N=16..20 parallel resumable GETs
(`ResumableStreamCopier`, skip when remote Content-Length == local size = rclone `--size-only`) into `<state>/chains/<chain-id>/<paritydb|db>/`, write a `.snapshot-ready` marker, no rclone dependency (the official recipe uses `rclone copy --transfers 20 --http-url … --files-from files.txt`).
Mode must match the snapshot: `pruned` ⇒ `--database paritydb`, `archive` ⇒ `--database rocksdb` (INFERRED: a mismatching `--database` makes the node open an empty DB). Dest layout `chains/<id>/{paritydb|db}/full/*` INFERRED from files.txt + Substrate layout; the Parity page says `chains/[chain_path]` loosely, the docs.polkadot.com parachain example uses `…/db` — confirm with one real restore.
Warp sync is the zero-config default and usually finishes in hours; the snapshot is the fast path and the only archive path.

**StacksSnapshotResolver**: url `https://archive.hiro.so/<env>/stacks-blockchain/<env>-stacks-blockchain-latest.tar.zst` (stable alias; dated copies `…-4.0.4-20261013.tar.zst`), env `mainnet`→`mainnet`, `testnet`→`testnet`. Size: `GET` with `Range: bytes=0-0` and read `Content-Range: bytes 0-0/<total>` — **HEAD fails** (the 302 target is a signed GET-only Cloudflare R2 URL; VERIFIED 403). Companion `.sha256` (133 B, content `<sha256>  /hirosystems/data/<dated name>` — the filename inside is the dated one). `streamUnpack=true`, extract INTO `working_dir` (archive top dir is `mainnet/` / `krypton/`, VERIFIED by streaming the head of both). **Agent change needed:** `SnapshotStreamExtract` is gzip only (`tar -xzf -`); add a codec (`tar -I zstd -x -f - -C dest`; precedent: optimism run.sh `tar -I zstd -xf`, BSC `tar -I lz4`) and put `zstd` into host deps. Restore is destructive: wizard must require an empty `working_dir`, and Stacks must be stopped.

## 7. Production config
VERIFIED = a flag/default read from source or docs at the pinned version; values marked judgement need load testing (see prod-rpc-tuning.md conventions: vendor defaults go into clientConfig, the preset raises them).

**Proposed `prodPresets.ts` entries** (new `type`s; `flags` for flag-driven units, `conf` for generated files):
```ts
const SUBSTRATE_RPC: Record<string,string> = {
  // default 100 (VERIFIED sc-cli); also the size of the RPC blocking-thread pool. docs.polkadot.com's own RPC recipe uses 1000.
  'rpc-max-connections': '1000',
  // default 64 messages per connection; slow WS consumers are dropped when the buffer fills
  'rpc-message-buffer-capacity-per-connection': '256',
  // default 1 GiB trie cache — the single biggest read-path cache (judgement: 4 GiB on a 32 GB host)
  'trie-cache-size': '4294967296',
}
polkadot: { flags: SUBSTRATE_RPC, notes: ['rpc-methods stays safe, prometheus stays on 127.0.0.1',
  'Pruned keeps 256 blocks of state: queries older than ~25 min fail; run archive (rocksdb) for history.'] }
peaq:     { flags: { ...SUBSTRATE_RPC }, notes: ['peaq runs archive state for historical eth_call; relay chain is embedded unless relay=external.'] }
near:     { conf: { 'view_client_threads': '16', 'rpc.limits_config.json_payload_max_size': '10485760' /* default, shown */,
                    'rpc.enable_debug_rpc': 'false' /* the testnet rpc profile ships true (VERIFIED) */ },
            notes: ['view_client_threads default 4 (judgement 16 on 8 cores); gc_num_epochs_to_keep stays 5 (disk grows with it).'] }
stacks:   { conf: { 'connection_options.max_http_clients': '3000' }, notes: ['Default 1000 (VERIFIED). RPC read-only call limits left at vendor defaults.'] }
aleo:     { flags: { 'rest-rps': '1000' },
            notes: ['Vendor default 10 requests/s per source IP (burst 10, VERIFIED): behind the toolkit proxy all requests share one IP, so the default is unusable.'] }
fuel:     { flags: { 'graphql-number-of-threads': '8', 'graphql-max-concurrent-queries': '4096', 'max-database-cache-size': '8589934592' },
            notes: ['threads default 2, concurrent queries default 1024 (VERIFIED v0.48.3); cache is bytes (judgement). Never pass --debug.'] }
```
**Must not be exposed publicly**
- Substrate: `--rpc-methods unsafe` (key rotation, peer admin) — locked to `safe`; Prometheus (`--prometheus-external` never); peaq embedded relay RPC (127.0.0.1 only, binding keeps it so); the base-path `network/secret_ed25519` and `keystore/`.
- NEAR: `node_key.json`/`validator_key.json`; debug RPC (`enable_debug_rpc`, true in the testnet profile); Prometheus port is loopback; `<home>` is mode 0700.
- Stacks: the Bitcoin RPC user/password in `stacks.toml` (0600); `[connection_options] auth_token` must stay unset on a follower; Bitcoin's own RPC must not be bound beyond loopback; Prometheus loopback.
- Aleo: `/db_backup`, `/node/address`, mapping values are JWT-guarded — keep JWT; node-data dir (JWT, account); metrics loopback; REST has CORS `*` and POST routes (tx submit) by design.
- Fuel: `--debug`; the `/v1/metrics` endpoint is on the public GraphQL port (no separate metrics port) → block it at the proxy; `fuel.env` (KEYPAIR/RELAYER url with API key) 0600.
Open files: all units `LimitNOFILE=1048576` (fuel docs ask ≥ 32768, docs.polkadot.com uses 65536).

## 8. Wiring points (per network, after approval)
1. `resources/chains/<id>/{network.yml,clients.yml,node.service.tmpl}` — drafts in this folder (+ `peaq.Dockerfile`, `snarkos.Dockerfile`).
2. `NetworkId`/`EnvId` vals; `YamlNetworkFactsRepositoryTest` + `NetworksRoutesTest` id lists; `ShippedPortCatalogTest` (ports checked free against `chains/*/clients.yml`: none of the 41 ports I use are taken).
3. `Toolkit.production()` maps (release, tip, start, snapshot resolver for polkadot/stacks); agent `AgentMain` `ChainNodeRuntime` map (proc + height).
4. Start/height/tip/release/snapshot tests with the VERIFIED JSON samples of §4 as fixtures.
5. Agent: **zip extraction** (stacks, aleo) — today's `extractArchiveGlob` is tar-based; **zstd stream** (stacks); **substrate-files runner** (polkadot); `neard init` + boot-node fetch (near); one-time key/env generation helpers (fuel).
6. Generic: `parentNode` facts field (+ `NetworkFacts`, `YamlNetworkFactsRepository`, `NetworksRoutes` JSON, `InstallOptionsPicker`) generalising `l1Parent` — needed by stacks→bitcoin and peaq→polkadot; fuel only needs the existing `l1Parent.rpc`.
7. A per-env literal source for clientConfig (`chain`, `network`, `parachain-id`, contract addresses differ per env but `bindings` literals are network-wide today) — either `envSections` like bitcoin or an `env_literal` source; fallback: show the mainnet value and let `<Id>UnitBodies` own the per-env value (as sui does with cluster tables).

## 9. Admin touch list
`admin/src/components/InstallOptionsPicker.tsx` (polkadot `mode`; peaq `mode`,`relay`,`relay_rpc`; stacks `burnchain_*` + Bitcoin picker; fuel `l1_rpc` = the optimism picker restricted to `eth_rpc`),
`admin/src/lib/prodPresets.ts` (§7), `admin/src/lib/networkDocs.ts` / `networkPaths.ts` (docs links, disk-path notes: DB dirs of §3/§6), `admin/src/lib/nodeLifecycle.ts` (stacks/polkadot snapshot phases: file-manifest progress, zstd stream),
`NodeConfigPanel` (stacks.toml / near config.json document editors are *generated-file* editors: `conf` presets), `NetworkIcon.tsx` (6 icons), `NodesPage`/`EnvDetailPage` env labels (`paseo`, `westend`, `agung`). Everything else YAML-driven.

## 10. Hedera
DEFERRED — see networks.md. No usable open-node route for a non-council operator; ports block 41630-41659 stays reserved/unused.
