# Network intake — Cardano

Filled from the Go sibling `toolkit-go/internal/networks/cardano/` plus live checks on 2026-10-13.
Legend: **[V]** verified now (how in brackets) · **[I]** inferred / judgement · **[Go]** fact ported from the Go sibling.
Companion files in this folder: `network.yml`, `clients.yml`, `node.service.tmpl`, `mithril-snapshot.sh.tmpl`, `kotlin-design.md`.

## 0. Meta

| Field | Answer |
|---|---|
| Network id | `cardano` (`NetworkId.CARDANO` — new companion val) |
| Display label | Cardano |
| Author | research agent (port from Go facts) |
| Date | 2026-10-13 |
| Status | draft |

**Go sibling:** `internal/networks/cardano/` exists. Ported facts: envs mainnet/preprod/preview; cardano-node as the core process; P2P 3003/3004/3005 (replaced by the central port plan);
IOG `book.world.dev.cardano.org` config set; Mithril `cardano-db download latest --include-ancillary` flow with per-env aggregator + genesis/ancillary vkeys; Ogmios as a sidecar `/health` height source;
unit flags `run --topology --database-path --socket-path --host-addr 0.0.0.0 --port --config`, `LimitNOFILE=1048576`, `TimeoutStopSec=600`.
**Not ported:** `/opt`, `/etc/cardano`, `/var/log/cardano`, user `nodeop`, `systemctl` from scripts, the Ogmios unit (deferred), Go "Assemble/Observe" status envelope.

| Section | Status | Notes |
|---|---|---|
| 1 Product scope | proposed | node + Mithril in v1; Ogmios + db-sync added as optional indexer companions (section 16) |
| 2 Environments | proposed | mainnet, preprod, preview |
| 3 Artifacts | proposed [V] | tarballs from GitHub, configs from the tarball |
| 4 Ports | proposed | PORTS.md block 41210 |
| 5 Disks | proposed | numbers partly [I] |
| 6 Client config / prod | proposed | `flags`, generated config.json |
| 7 Snapshot | proposed [V] | `required` x3, Mithril, tool-driven |
| 8–9 Start / proc | proposed | Sui-style |
| 10–11 Height / tip | proposed [V] | Prometheus on 127.0.0.1, Koios tip |
| 12–13 Lifecycle / options | proposed | install option `indexer` (section 16) |
| 15 Risks | listed | |
| 16 Indexer (Ogmios, db-sync) | implemented [V upstream docs/source, not run on Linux] | optional companion units, PostgreSQL reused from the host |

---

## 1. Product scope

1. **Why:** operators run Cardano relay/full nodes; Go had it with mainnet/preprod/preview, so parity + admin already knows the id (`NetworkIcon`, `networkDocs`, `networkPaths`, `MULTI_DISK_NETWORKS` already contain `cardano`).
2. **MVP decision (honest):**
   - **v1 IN:** `cardano-node` (+ `cardano-cli` extracted next to it, config/topology/genesis from the tarball), generated `config.json` with production literals, systemd unit, Prometheus-based local height + sync %, Koios public tip, release resolver, **Mithril snapshot** (`mithril-client` program + `mithril://` resolver + runner).
   - **Why Mithril is in v1:** mainnet from genesis takes days [I]; the certified snapshot is ~222 GiB and the verification runs inside the static `mithril-client` binary — no Docker needed [V: tarball listing, CLI source at tag 2630.1-hotfix]. Go made it `required` on all envs.
   - **Staging inside v1:** milestone M1 = node only (can ship with the snapshot step failing "not supported" → do NOT ship that); milestone M2 = Mithril runner. Implement M1+M2 together; if M2 slips, set `snapshot: never` on preprod/preview only (they sync from genesis in hours [I]) and keep mainnet blocked.
   - **Indexer (added later, section 16):** Ogmios (JSON-RPC/WebSocket sidecar) and cardano-db-sync (PostgreSQL) as optional companion units behind the install option `indexer`.
   - **v2 DEFERRED:** `V2LSM` ledger backend (needs `snapshot-converter` on the Mithril ledger snapshot), block-producer/stake-pool (KES/VRF/opcert keys), `cardano-submit-api`, a db-sync snapshot restore, CDN mirror of Mithril files. Reasons: separate products / not needed to sync or report height.
3. **Pin-only?** No. Public GitHub release tarballs exist [V]. No Docker.
4. **One env per host?** `oneEnvPerHost: false` — separate ports and dirs per env (3 env blocks in PORTS.md). RAM is the practical limit on a shared host (mainnet 24+ GiB in memory).

## 2. Environments

| Env id | Label | Production? | Notes |
|---|---|---|---|
| `mainnet` | Cardano Mainnet | yes | magic 764824073 (implicit in config) |
| `preprod` | Cardano Preprod | test | |
| `preview` | Cardano Preview | test | |

1. Env ids = book/Mithril/Koios names [V]. `EnvId.PREPROD` and `EnvId.PREVIEW` must be added (only `MAINNET` exists). `Chain.normalizeEnv` needs no change; admin has no env special-case.
2. Same binary for all envs [V: one tarball, `share/<env>/` inside]; env is chosen only by which config/genesis set is passed.
3. All three have a public tip (Koios) [V].

## 3. Client binary & artifacts (`clients.yml`)

1. **Upstream:** `IntersectMBO/cardano-node` GitHub releases; `IntersectMBO/mithril` GitHub releases. [V gh api]
2. **Latest rule:** newest non-draft, non-prerelease release (`HttpGitHubReleaseClient` default). cardano-node tags are bare (`11.1.3`, no `v`), `11.1.0` was a pre-release and is skipped [V]. Mithril: `2630.1-hotfix` is the latest stable; the rolling `unstable` pre-release is skipped [V]; the `-hotfix` suffix is not caught by the client's alpha/beta/rc/preview regex [V: read `pickLatest`].
3. **Artifacts (HEAD 200 after redirect) [V curl -sIL]:**

| What | x86_64 | aarch64 | Size |
|---|---|---|---|
| cardano-node 11.1.3 | `.../releases/download/{tag}/cardano-node-{tag}-linux-amd64.tar.gz` | `...-linux-arm64.tar.gz` | 233,694,227 / 270,624,203 B |
| mithril 2630.1-hotfix | `.../releases/download/{tag}/mithril-{tag}-linux-x64.tar.gz` | `...-linux-arm64.tar.gz` | 69,257,102 / 66,989,310 B |

4. **On-disk stable names:** `cardano-node-linux-amd64.tar.gz` / `cardano-node-linux-arm64.tar.gz`; `mithril-linux-x64.tar.gz` / `mithril-linux-arm64.tar.gz`. Extract globs `cardano-node-linux-*.tar.gz`, `mithril-linux-*.tar.gz`.
5. **Config files: from the tarball, not from the book.** The node tarball contains `./share/{mainnet,preprod,preview}/{config.json, topology.json, byron/shelley/alonzo/conway-genesis.json, peer-snapshot.json, checkpoints.json (mainnet+preview)}` [V: extracted]. Compared with `book.world.dev.cardano.org/environments/<env>/` today [V cmp]: identical except mainnet `config.json` (`ConsensusMode` PraosMode in the release vs GenesisMode on the book). The release set is version-matched (config `MinNodeVersion` 11.1.0), needs no extra URLs and cannot drift from the binary; so `clients.yml` has **no `configs:`** block. Book URL patterns (all 200 [V]): `https://book.world.dev.cardano.org/environments/{env}/{config,topology,byron-genesis,shelley-genesis,alonzo-genesis,conway-genesis,peer-snapshot,checkpoints}.json` (preprod has no checkpoints.json: 404). Kept only as a documented fallback.
6. **Program ids:** `cardano-node` (= `clientConfig.program`), `mithril-client`. Tarball layout: node `./bin/cardano-node`, `./bin/cardano-cli`, ... (all under `./bin`, `./share`, no top-level dir); mithril: flat, binary `mithril-client` [V].
7. **Requirements:** `logFile: logs/cardano.log`. No Java.
8. **No Docker:** confirmed. Mithril's docs show Docker; the release tarball needs none.
9. **Code changes outside the chain package:** `InstallPlanWriter.inferLaunch` add `cardano-node` → `bin/cardano-node`; `preferredInstallPlanProgram` prefer `cardano-node` when `cardano-node-linux-*` files exist (otherwise the plan of the last downloaded program, mithril, wins).

## 4. Fixed ports (`clients.yml` → `programs[].ports`)

Central plan `specs/PORTS.md` (nothing in 41000–42999 is used by shipped networks): block 41210; offsets `+2` p2p, `+4` metrics. Upstream defaults (p2p 3001 = Hyperliquid, metrics 12798) are not used. Go ports (3003–3005, 12798–12800) are dropped.

| Env | Role | Port | Label | Bound by product? |
|---|---|---|---|---|
| mainnet | p2p | 41212 | P2P (node-to-node) TCP, 0.0.0.0 | yes (`--port`) |
| mainnet | metrics | 41214 | Prometheus, 127.0.0.1 | yes (config.json backend) |
| preprod | p2p | 41222 | | yes |
| preprod | metrics | 41224 | | yes |
| preview | p2p | 41232 | | yes |
| preview | metrics | 41234 | | yes |
| mainnet / preprod / preview | ogmios | 41210 / 41220 / 41230 | Ogmios JSON-RPC + WebSocket, 127.0.0.1 (flag `--host`) | optional (`indexer` = ogmios, both); `config: none` |
| mainnet / preprod / preview | dbsync_metrics | 41217 / 41227 / 41237 (offset +7) | db-sync Prometheus: the product binds ALL interfaces, the unit is confined to loopback by systemd | optional (`indexer` = dbsync, both); `config: none` |

1. All catalog-fixed (no dynamic ports). 2. Height uses `metrics` (`NodeHeightSpec.portRole = "metrics"`); the P2P port is advertised to peers; the node-to-client socket is a file, not a port. 3. No implicit extra ports (cardano-node opens only p2p + the metrics backend; verified in config). `oneEnvPerHost=false`, ports are unique per env. **Run `ShippedPortCatalogTest`.**

## 5. Disks & sizing (`network.yml`)

1. `diskRoles`: one role `blockchain` (nvme): `<role>/db` = chain DB + ledger snapshots; `<role>/{bin,config,logs,.toolkit,node.socket}` = everything else (node_dir == role dir, same as Sui/Avalanche).
2. Per env:

| Env | diskHintGiB | fullNodeGiB | cpu | RAM GiB | Basis |
|---|---|---|---|---|---|
| mainnet | 512 | 512 | 4 | 32 | DB 220 GiB now [V aggregator: 236,461,263,953 B] + ancillary 2.4 GiB [V] + 2 on-disk ledger snapshots + growth; IOG: 300 GB free required, 350 recommended, 24 GB RAM with InMemory backend [V release notes 11.1.3] |
| preprod | 100 | 100 | 2 | 16 | DB 18.4 GiB [V], RAM [I] |
| preview | 100 | 100 | 2 | 16 | DB 14.9 GiB [V], RAM [I] |

Go had 400/80/80 GiB and 16/8/8 GiB RAM: stale — 11.x holds the UTxO set in memory by default (`LedgerDB.Backend: V2InMemory` in the shipped config [V]). RAM numbers for preprod/preview are [I]: measure.
3. `diskNotes` are in `network.yml`. 4. Sources: Mithril aggregator API (live sizes), cardano-node 11.1.3 release notes, Go facts.

## 6. Client config shape (`network.yml` → `clientConfig`) and PRODUCTION CONFIG

1. **Format:** `flags` — the toolkit generates `<node_dir>/config/config.json` on every Start from the release's pristine `share/<env>/config.json`, plus a command line. No conf patch step.
2. **Template mapping:** one generator for all envs; env only selects the share dir and the cluster constants.
3. **Bindings:** see `network.yml` (23 bindings): disk role dir (`database-path`, `socket-path`, `config`, `topology`), catalog ports (`port`, `PrometheusSimple.port`), literals for everything else. All literals are constants in `CardanoUnitBodies` and asserted equal to the YAML by test.
4. **Operator-editable later:** nothing in v1. `config.json` is regenerated on every Start, so hand edits would be lost; the Node-config editor must not offer it until a "config.json keys" overlay exists (then the preset below applies). Ports, paths, socket, topology and the Prometheus bind stay locked.
5. **Capacity / production knobs.** cardano-node is a relay: it has **no RPC thread pool, request-size limit or subscription cap**. The capacity surface is peer targets, inbound connection caps, RTS threads and file descriptors.

| Config key | Source | Value | Applied where | Basis |
|---|---|---|---|---|
| `database-path` | disk_role_dir blockchain + `db` | `<role>/db` | flag | [V] flag exists (Go unit + `run --help` shape) |
| `socket-path` | disk_role_dir + `node.socket` | `<role>/node.socket` | flag + `CARDANO_NODE_SOCKET_PATH` | [V Go] |
| `config` / `topology` | disk_role_dir | `<role>/config/{config,topology}.json` | flags | [V] |
| `port` | catalog p2p | 41212/41222/41232 | flag | [V] |
| `host-addr` | literal | `0.0.0.0` | flag | [Go] — P2P must be reachable |
| `PrometheusSimple.port/host` | catalog metrics / literal | 4121x–4123x / `127.0.0.1` | config.json `TraceOptions[""].backends` entry `PrometheusSimple suffix 127.0.0.1 <port>` | [V] upstream ships `127.0.0.1 12798` |
| `Stdout.format` | literal | `HumanFormatUncoloured` | backends entry `Stdout ...` | [I] avoids ANSI in the log file |
| `LedgerDB.Backend` | literal | `V2InMemory` | config.json | [V] POM.hs: default, other value `V2LSM` |
| `TargetNumberOf{Root 60, Known 150, Established 30, Active 20, KnownBigLedger 15, EstablishedBigLedger 10, ActiveBigLedger 5}Peers` | literal | relay defaults | config.json top-level keys | [V] `ouroboros-network` `defaultDeadlineTargets Relay` (master); key names [V] POM.hs |
| `AcceptedConnectionsLimit.{hardLimit 512, softLimit 384, delay 5}` | literal | defaults | config.json object | [V] key names OrphanInstances.hs; defaults `defaultAcceptedConnectionsLimit` |
| `RTS.N` | literal | `2` → `+RTS -N2 -RTS` | flags | [V] binary default `-with-rtsopts=-T -I0 -A16m -qg1 -qb1 -N2 --disable-delayed-os-memory-return` (cabal 11.1.3, non-arm32) |
| `LimitNOFILE` | literal | `1048576` | unit | [Go] |
| `TimeoutStopSec` | literal | `600` | unit | [Go]; template default 90 is too short: clean stop writes the ledger snapshot |

Explicit defaults are written so the Start "Config preview" shows them and a later preset changes only values, never structure. Differences vs upstream config: only the Prometheus entry (port), the Stdout format, and the explicit keys above.

**What is NOT exposed / unsafe by default [V unless noted]:**
- Prometheus endpoint: unauthenticated; bound to **127.0.0.1** (upstream default). Never bind 0.0.0.0.
- `node.socket` (node-to-client): full ledger queries + tx submission for anyone who can open the file → kept under `node_dir`, never mounted/forwarded. [I on file mode: created with the process umask]
- P2P port is public by design (relay). `PeerSharing` enabled for relays by default [V `defaultPeerSharing Relay`]. 
- Experimental node-side gRPC (`EnableRpc`/`RpcSocketPath`, new in 11.x [V POM.hs]) stays off (not set).
- Ogmios binds `127.0.0.1` (flag `--host`, also its default [V Options.hs]) and must sit behind an authenticating, rate-limiting proxy if it is ever exposed (section 16).
- Logs: `logs/cardano.log` is appended forever (same as every chain) — no rotation in the toolkit [I].
- Runs as root like all toolkit units (Go used `nodeop`) [I].

**Preset proposal (`admin/src/lib/prodPresets.ts`)** — judgement values, not benchmarks; needs the Node config editor to accept `config/config.json` top-level scalar keys (nested `AcceptedConnectionsLimit` cannot be expressed in the flat `conf` map). Default recommendation: **ship no preset in v1** (defaults are the verified IOG relay defaults); add only for a relay with many downstream peers:

```ts
PROD_PRESETS.cardano = {
  conf: {
    // more inbound/outbound warm peers for a public relay; each adds bandwidth + file descriptors (LimitNOFILE is 1048576)
    TargetNumberOfEstablishedPeers: '50',
    TargetNumberOfActivePeers: '25',
    TargetNumberOfKnownPeers: '200',
  },
  notes: [
    'Judgement values (defaults: 30 / 20 / 150, checked against ouroboros-network master). Raise the inbound caps (AcceptedConnectionsLimit hard 1024 / soft 768) in config.json by hand: nested keys are not editable here.',
    'Block producers keep the defaults (100 root / 100 known) and must not be public relays.',
    'The ledger backend (V2InMemory, 24+ GiB RAM) is not a preset knob in v1.',
  ],
}
```
Source for every key: `cardano-node/src/Cardano/Node/Configuration/POM.hs` @11.1.3 (key names) and `ouroboros-network/.../Diffusion/Configuration.hs` (defaults).

## 7. Snapshot policy — Mithril

Verified 2026-10-13 against the live aggregators and the mithril-client source at tag `2630.1-hotfix`.

| | mainnet | preprod | preview |
|---|---|---|---|
| Aggregator (`AGGREGATOR_ENDPOINT`) | `https://aggregator.release-mainnet.api.mithril.network/aggregator` | `https://aggregator.release-preprod.api.mithril.network/aggregator` | `https://aggregator.pre-release-preview.api.mithril.network/aggregator` |
| Key dir (raw.githubusercontent.com/IntersectMBO/mithril/main/mithril-infra/configuration/…) | `release-mainnet` | `release-preprod` | `pre-release-preview` |
| `genesis.vkey`, `ancillary.vkey` | both 200 | both 200 | both 200 |
| Latest list | `GET {agg}/artifact/cardano-database` → 20 entries, newest first (`hash`, `beacon.epoch`, `beacon.immutable_file_number`, `total_db_size_uncompressed`, `cardano_node_version`, `created_at`) | same | same |
| Now | epoch 661, imm 9272, 236,461,263,953 B, node 11.1.3 | epoch 319, imm 6304, 19,764,837,561 B | epoch 1449, imm 28993, 16,005,387,202 B |
| Ancillary (ledger state) | 2,542,989,935 B | 1,041,361,854 B | 710,978,245 B |

- **Format:** per-immutable-file `.tar.zst` (zstd) from `storage.googleapis.com/cdn.aggregator.<net>.api.mithril.network/cardano-database/immutable/{n}.tar.zst`, one `digests` archive, one `ancillary` archive (last ledger snapshot in **InMemory** format + last immutable file), downloaded 20 files in parallel [V source: `max_parallel_downloads: 20`].
- **Checksum / authenticity:** client verifies the certificate chain back to the genesis vkey, a Merkle proof over the digests, and the ancillary Ed25519 signature with `ANCILLARY_VERIFICATION_KEY` (IOG-owned key, *not* Mithril-signed; the client prints a warning) [V docs + source]. The toolkit must not skip these (no `--skip-*`; there is none) and must not download the `.tar.zst` files by itself.
- **Discovery of "latest":** `mithril-client cardano-db download latest` resolves the alias itself (`expand_eventual_id_alias`) [V]. The resolver only needs the aggregator list for an optional size hint.
- **Restore:** `mithril-client --json --aggregator-endpoint $AGG cardano-db download latest --include-ancillary --download-dir <blockchain role dir> --allow-override` → files land in `<role>/db/{immutable,ledger,...}` plus a `clean` marker [V source: `db_dir = download_dir/db`, `File::create(db/clean)`]. Env: `GENESIS_VERIFICATION_KEY`, `ANCILLARY_VERIFICATION_KEY` (the file contents, hex-encoded JSON arrays [V]). `CARDANO_NETWORK` (Go) is not read by the v2 command. The v2 backend is the only one in 2630.x [V: `CardanoDbCommandsBackend::V2` default].
- **Disk check:** the client aborts early when free space < `total_db_size_uncompressed` (+ margin for ranges) [V source]; no staging copy, files are unpacked in place, so peak disk ≈ final DB.
- **Progress (machine readable, `--json`, stderr):** step lines `{"timestamp": …, "step_num": 3, "total_steps": 7, "message": "Downloading and unpacking the cardano db snapshot"}` and bar lines `{"label": "Files", "timestamp": …, "files_downloaded": N, "files_total": M, "seconds_left": …, "seconds_elapsed": …}` [V source `progress_reporter.rs`]. Steps: 1 disk, 2 certificate chain, **3 download+unpack (the long one)**, 4 digests, 5 verify DB, 6 compute message, 7 verify signature. Sample lines below are reconstructed from that source, not captured from a Linux run [I].
- **Resume:** none. Without `--allow-override` a non-empty `db/immutable` aborts; with it everything is overwritten and re-downloaded [V source]. `--start <immutable no>` exists and could resume the download phase (v1.1; needs care: [I]). v1 = wipe `db` and restart. Mainnet restart cost ≈ 220 GiB again.
- **Can the node serve during restore?** No — the unit is stopped by the script and the DB is incomplete. Local height probe returns `null` until Start. The snapshot job reports its own pct. Wizard "ready" only after `.snapshot-ready` + exit 0.
- **Binary runs without Docker:** yes, static release binary [V: tarball has `mithril-client`]. Not executed here (macOS host, Linux ELF); flags verified from source. First real run on a Linux host is a required acceptance step.
- **Fallback if mithril-client cannot run** (e.g. glibc/arch issue): there is no verified alternative. Community snapshot sites were not evaluated and are unverified → do not use. Operator fallback: set the env to genesis sync (not available in v1 because `required`); document in risks.
- **Node/snapshot compatibility:** the ancillary ledger state must be readable by the node: node ≥ the snapshot's `cardano_node_version` (now 11.1.3 = latest release) [V docs: "node 11.1.3 or later"]. Resolver rule "latest stable node" keeps this true; a snapshot produced by a newer node than the installed one is a failure mode (risk).
- **Per env policy:** `snapshot: required` on all three — mainnet is days from genesis [I]; preprod/preview are 15–20 GiB, minutes with Mithril [V sizes]. Note: `optional` currently has no wizard behaviour (`nodeNeedsSnapshot` is `== "required"`; no shipped chain uses `optional`), so it cannot give "skippable"; use `never` only if M2 slips.
- **Types:** one, `mithril` (kind `full`, default). No `destLeaf` — destination = the blockchain role dir (what `ResolveSnapshotDestDirUseCase` returns); Mithril appends `/db` itself, matching `database-path = <role>/db`. No `snapshots:` mirror block in `clients.yml` (cannot be CDN-mirrored: verification is client-side and the files are per-immutable GCS objects).
- **Resolver sketch:** `CardanoSnapshotResolver : SnapshotResolver` → `SnapshotArchive(url = "mithril://<env>", streamUnpack = false, sizeBytes = <total_db_size_uncompressed from the list endpoint, or null>)`, same shape as `SuiSnapshotResolver` (`formal-r2://`) / `BscSnapshotResolver` (`bsc-official://`). Agent: new `CardanoMithrilSnapshotRunner` + branch in `SnapshotDownloadUseCases` + `mithril://` in `SnapshotArchiveRef.isBaseManifestUrl`. Details in `kotlin-design.md` §7.
- **Stop caveat:** `StartSnapshotDownloadUseCase.stop(wipeDest=true)` wipes the whole dest dir = the role dir = node_dir (bins, config). Same as Sui; clients are re-synced at Start (`ensureClients`). Accepted.

## 8. Start recipe (`chains/cardano/infrastructure/start`)

1. Process model: native binary under node_dir: `bin/cardano-node` (extracted from the tarball by `CardanoHostBinaries`), foreground (systemd owns PID).
2. Args (rendered by `CardanoUnitBodies.args`): `run +RTS -N2 -RTS --topology <nd>/config/topology.json --database-path <role>/db --socket-path <nd>/node.socket --host-addr 0.0.0.0 --port <p2p> --config <nd>/config/config.json`.
3. Extract: `tar -xzf cardano-node-linux-*.tar.gz -C <tmp> ./bin/cardano-node ./bin/cardano-cli ./share/<env>` (selective: the full tarball unpacks to ~0.9 GB of tools we do not need) → `<nd>/bin/*`, `<nd>/config/*` (upstream `config.json` kept as `config.upstream.json`).
4. `NodeLaunchSpec(kind="binary", entry="bin/cardano-node", args=["--toolkit-env=<env>","--toolkit-blockchain=<role dir>"], extractArchiveGlob="cardano-node-linux-*.tar.gz", normalizeDir=null, logFile="logs/cardano.log")`; `NodeHeightSpec(kind="cardano_metrics", portRole="metrics")`.
5. Class `CardanoNodeStart` (`NetworkId.CARDANO`).

## 9. Host process start (`chains/cardano/infrastructure/proc`)

`CardanoNodeProcessStarter`: ensure bins + config → generate `config.json` → render unit from `chains/cardano/node.service.tmpl` (extra placeholders `socket_path`, `stop_timeout`) → `HostNodeLaunchSupport.installCustomUnits`. Env var `CARDANO_NODE_SOCKET_PATH` in the unit. Nothing in `/opt`, `/etc`, `/var`. Reject node_dir/role dirs containing whitespace (ExecStart is space-joined).

## 10. Local height (`chains/cardano/infrastructure/http`)

**Evaluated sources**

| Source | HTTP-only? | Verdict |
|---|---|---|
| Prometheus on the node (`PrometheusSimple`, metrics `cardano_node_metrics_blockNum_int`, `…_slotNum_int`, `…_epoch_int`, `…_slotInEpoch_int`, `…_density_real`) | **yes, built in, no extra process** | **chosen** [V names in `Tracing/Tracers/ChainDB.hs` @11.1.3 (`IntM "blockNum"`, `"slotNum"`, `DoubleM "density"`) + prefix `cardano.node.metrics.` in shipped config + bench dashboards using `cardano_node_metrics_blockNum_int`]; exact exposition text not captured from a live node [I] |
| `cardano-cli query tip` over the socket | CLI + socket | rejected for v1: needs `--mainnet`/`--testnet-magic`, a process per sample; JSON has `block`, `slot`, `epoch`, `syncProgress` (documented format [I]) — keep as fallback idea |
| Ogmios `GET /health` | HTTP | deferred with Ogmios (`lastKnownTip.height`, `networkSynchronization` [Go]) |

**Chosen:** `GET http://127.0.0.1:<metrics>/metrics` (3 s timeout). Parse Prometheus text, strip labels:
```
cardano_node_metrics_blockNum_int 14064221
cardano_node_metrics_slotNum_int 200356969
cardano_node_metrics_epoch_int 661
cardano_node_metrics_slotInEpoch_int 168169
```
`height = blockNum`. `syncPct = slotNum / currentSlot * 100` with `currentSlot = shelleyStartSlot + (now - shelleyStartUnix)` (1-second slots after Shelley) — constants **[V against Koios tips today, exact match]**: mainnet `4492800 @ 1596059091`, preprod `86400 @ 1655769600`, preview `0 @ 1666656000`. `syncing = currentSlot - slotNum > 300`. Missing `blockNum` (node still replaying / starting, metrics not yet emitted) → `null`. Never throws.
Bind: loopback only (agent runs on the host).

## 11. Public network tip

`publicTip.urls` (one per env, Koios, no API key) [V curl, HTTP 200 today]:
`https://api.koios.rest/api/v1/tip`, `https://preprod.koios.rest/api/v1/tip`, `https://preview.koios.rest/api/v1/tip`.
Response `[{"hash":"…","epoch_no":661,"era":"Conway","abs_slot":200356969,"epoch_slot":168169,"block_height":14064221,"block_no":14064221,"block_time":1791923260}]`. Tip = `block_no` (same quantity as the node's `blockNum`). Not the "same protocol as local height" (local is Prometheus) — this is the section-11.3 exception: a thin adapter over a public HTTP API that returns a block height integer; `CardanoNetworkTipProbe` reuses `CardanoKoios.parseTip`.
Rate limits of the unauthenticated Koios tier are not documented here [I]; the panel polls rarely. 4. `sync` → `active` at the default tip lag ≤ 3 blocks.

## 12. Lifecycle & panel status

After Start: `sync` until local height within 3 of Koios, then `active` (default). Blocks come every ~20 s; Koios `block_no` can lead by one block: within the lag budget. Snapshot phases are covered by the snapshot job (`SnapshotJob`: pct/phase/detail); no extra statuses. Admin Sync step + header poll only while `sync`/`active`. `HostNodeHeightReading.syncing/syncPct` drive the sync card.

## 13. Install options (wizard)

One group, `indexer` (section 16): `none` (default) | `ogmios` | `dbsync` | `both`. Candidate for later: `ledger` (`memory` default | `disk` = V2LSM, requires converted ledger snapshot via `mithril-client tools utxo-hd snapshot-converter … --utxo-hd-flavor LSM` [V docs; LSM needs node ≥ 10.7.0]).

## 14. Wiring checklist

See `kotlin-design.md` §9 (exact lines) and §10 (admin touch list).

## 15. Open risks

| Risk | Impact | Mitigation |
|---|---|---|
| Mithril never executed on Linux in this research (macOS host) | wrong flag/output assumption → snapshot step broken | Flags + output format taken from source at tag 2630.1-hotfix; acceptance test on a Linux box with preview first (16 GiB) |
| No Mithril resume | an interrupted mainnet restore re-downloads ~220 GiB | v1.1: resume with `--start`; document |
| Agent `stop` wipes the whole dest dir (= node_dir) | bins/config removed on operator Stop | same as Sui; `ensureClients` re-syncs at Start |
| Snapshot made by a newer node than the installed one | node refuses/repairs ledger state | resolver = latest stable node; surface `cardano_node_version` in logs (script) — optional check |
| Prometheus metric names/format only verified from source, not from a live node | height probe returns null forever | parser is tolerant (suffix `_int`, labels stripped); fallback idea `cardano-cli query tip`; verify on first live node |
| `blockNum` metric absent until the first chain event after start | brief null readings | `null` is skipped by the push loop |
| Memory: V2InMemory needs ≥24 GiB (mainnet); testnet RAM numbers are [I] | OOM kills on small hosts | sizing in `network.yml`, `diskNotes`; V2LSM = v2 |
| Koios free tier availability / rate limit | no "behind tip" | add a second tip URL later; panel tolerates null |
| Config drift book vs tarball (mainnet `ConsensusMode`) | none functionally; differs from IOG book | documented; node `MinNodeVersion` guards mismatches |
| `raw.githubusercontent.com/.../main/...vkey` path or key rotation | snapshot fails at key fetch | script reports the URL; consider bundling keys per env in resources if it breaks |
| `tar` member names (`./bin/cardano-node`) change in a future release | extraction fails | retry without `./`; error names the archive |
| P2P port public + root unit | standard relay exposure | defaults are IOG relay defaults; no RPC exposed |
| Logs unbounded | disk fill | same as other chains; logrotate is a separate task |
| Indexer: see the table in section 16 | | |
| Admin lists | `NetworksRoutesTest` / `YamlNetworkFactsRepositoryTest` id lists must include `cardano` (merge with other new networks) | listed in design |

## 16. Indexer: Ogmios and cardano-db-sync (install option `indexer`)

Added 2026-10-14. Legend as above; **[V]** = read in the upstream docs / source at the pinned tag or checked against the GitHub API, **[I]** = inferred, **[N]** = not run on a Linux host (this was written on macOS: units, scripts and flags are covered by unit tests and `bash -n`, never executed against a real node).

### 16.1 What it is and the default

`install_options.indexer` = `none` | `ogmios` | `dbsync` | `both`. Applied on every Start by `CardanoNodeProcessStarter` → `CardanoIndexerSetup` (extract binaries, write config/scripts, install companion units; a companion that is switched off is stopped, disabled and deleted; its data stays).

**Default: `ogmios`** (backend and admin picker agree; decided by the user for the RPC-provider use case). cardano-node itself has no query API, Ogmios is the JSON-RPC / WebSocket one: no disk, little RAM, bound to 127.0.0.1. Existing nodes get Ogmios at their next Start (the Ogmios archive must be synced to the clients dir first). `none` stays selectable; db-sync is opt-in because it is heavy (below).

### 16.2 Pinned versions and artifacts [V gh api + asset listings, 2026-10-14]

| | Ogmios | cardano-db-sync |
|---|---|---|
| Repo / latest stable | `CardanoSolutions/ogmios` v7.0.0 (2026-06-20; tag has a `v`) | `IntersectMBO/cardano-db-sync` 13.7.2.1 (2026-06-17; 13.7.0.1–3 are pre-releases) |
| Asset | `ogmios-{tag}-x86_64-linux.zip` (45.6 MB), `ogmios-{tag}-aarch64-linux.zip` (53.7 MB) | `cardano-db-sync-{tag}-linux.tar.gz` (76.6 MB). **x86_64 only**: built by nix `cardano-db-sync-linux = mkDist … projectCross.musl64` (static); no aarch64 asset exists |
| Layout | zip: `bin/ogmios` (199 MB / 261 MB), `share/{bash-completion,zsh}/…` | tar: `./bin/{cardano-db-sync,cardano-db-tool,cardano-smash-server,…}`, `./schema/*.sql` (migrations; **needed at runtime** via `--schema-dir`) |
| On-disk names (versionless) | `ogmios-linux-x86_64.zip` / `ogmios-linux-aarch64.zip` | `cardano-db-sync-linux-x86_64.tar.gz` (arch in the name so aarch64 hosts skip it) |
| Node compatibility | tested upstream with cardano-node 11.0.1; this toolkit installs 11.1.x [I: node-to-client versions are negotiated; not verified live] | 13.7.0.5+ builds against node 11.0.1 [V changelog]; same caveat for 11.1.x. db-sync keeps its own ledger state, so a node hard fork before a db-sync release is a risk (section 16.7) |
| Extraction | Kotlin `ZipInputStream` (no `unzip` needed), only `bin/ogmios` | `tar -xzf … ./bin/cardano-db-sync ./schema` into a temp dir, then `schema/` is replaced as a whole |

All programs of the env are synced to every node of it (the clients dir is shared): a node that never enables the indexer still receives ~120 MB of archives. Filtering per install option needs a change in the shared install-plan / agent sync (not done; see HANDOFF).

### 16.3 Ogmios [V Options.hs @ v7.0.0, docs configuring/monitoring]

Unit `rpcnode-cardano-<env>-ogmios.service` (`PartOf` the node unit, `Restart=always`, `NoNewPrivileges`, `PrivateTmp`, `LimitNOFILE=1048576`):

`bin/ogmios --node-socket <nd>/node.socket --node-config <nd>/config/config.json --host 127.0.0.1 --port <41210|41220|41230> --timeout 90 --max-in-flight 1000 --log-level Info`

- `--node-config` is the generated `config.json`; Ogmios reads `ByronGenesisFile` / `ShelleyGenesisFile` next to it for the network parameters.
- **Production flags that exist:** `--timeout` (idle seconds before a client is dropped, default 90) and `--max-in-flight` (pipelined ChainSync requests per connection, default 1000). Both are written explicitly (= upstream defaults) so the Start preview shows them. **There is NO max-connections flag in v7.0.0** [V: the whole option parser]: connection and rate limits belong in the reverse proxy (`limit_conn`, request rate) and in `LimitNOFILE`.
- Binary built with `-with-rtsopts=-A100m -T -N2` (cabal `production` flag) [V]: a 200 MB nursery per capability, so budget a few hundred MB RSS [I].
- No `--include-cbor`, `--strict-rpc`, `--metadata-detailed-schema`: default JSON shapes; clients that need CBOR must run their own Ogmios.
- Health: `/health` (JSON, needs `Accept: application/json`) and Prometheus `/metrics` on the same port (`ogmios_connected`, `ogmios_network_synchronization`, `ogmios_tip_block`, `ogmios_tip_slot`, `ogmios_active_connections`) [V source + docs]. The host check `cardano_ogmios` reads `/metrics`.

**What must not be public.** The Ogmios WebSocket has no authentication and exposes every ledger query *and* `submitTransaction` / `evaluateTransaction`; `/` is a dashboard, `/health` and `/metrics` are open. It is bound to loopback and the catalog marks the port `config: none` (check-only). If it must be reachable from other hosts: a proxy with TLS + authentication + per-IP connection and rate limits in front, never `--host 0.0.0.0`. The toolkit does not publish it.

### 16.4 cardano-db-sync [V docs configuration / command-line-options / running, source @ 13.7.2.1]

Two units + two scripts, because db-sync is confined to loopback and so cannot run apt:

1. `rpcnode-cardano-<env>-dbsync-prepare.service` (`Type=simple` + `RemainAfterExit=yes`, `Restart=on-failure`; **not** `oneshot`, because `installCustomUnits` restarts companions and would block the Start call for the duration of an `apt install`): runs `bin/rpcnode-cardano-dbsync-prepare.sh` — finds the host PostgreSQL (`pg_lsclusters` online cluster, else `pg_isready` on 5432; installs `postgresql` from apt only when there is none, and only then writes `conf.d/90-rpcnode-cardano-dbsync.conf`; an existing server gets just a suggested `postgresql-recommended.conf`), creates the **role / database / tablespace `rpcnode_cardano_<env>[_ts]`** (UTF8, `template0`, tablespace in `<indexer role dir>/pgdata`), sets role-level `maintenance_work_mem` (≥1 GiB, RAM/16 up to 4 GiB; upstream docker uses 1GB), `max_parallel_maintenance_workers=4` (upstream docker), `work_mem`, `random_page_cost=1.1`, `effective_io_concurrency=200`, writes the `ready` marker. Same mechanism as the TON indexer (shared bash in `chains/default/postgres-functions.sh`, the TON script keeps its own copy).
2. `rpcnode-cardano-<env>-dbsync.service`: `ExecStart=bin/rpcnode-cardano-dbsync.sh` waits for `ready` + the node socket, then `exec`s `bin/cardano-db-sync --config <nd>/config/db-sync-config.json --socket-path <nd>/node.socket --state-dir <indexer dir>/ledger-state --schema-dir <nd>/schema` with `PGPASSFILE` set. No RTS flags: the binary's built-in `-T -I0 -A16m -qg1 -qb1 -N2 --disable-delayed-os-memory-return` [V cabal]. `IPAddressDeny=any` + `IPAddressAllow=localhost`, `PartOf` the node unit, `TimeoutStopSec=300`.

**Secrets.** The password is generated once (24 random bytes, hex) into `<nd>/.toolkit/cardano-dbsync/postgres_password` (0600, dir 0700) and written into `<nd>/.toolkit/cardano-dbsync/pgpass` (0600) as the 5 colon-separated fields db-sync parses (`host:port:db:user:password`, split on `:`, so no `:` in the password) [V PGConfig.hs]. SQL carrying it goes to `psql` through stdin (heredoc), never argv; the unit texts and flags hold no secret. db-sync reads the file through `PGPASSFILE` [V]. The application role is a plain database owner, never `postgres`: db-sync's own NixOS module runs it as an unprivileged owner and the schema scripts need no extension [V: none of the 80 shipped `schema/migration-*.sql` files contains `CREATE EXTENSION`, `GRANT`, `OWNER TO` or a superuser-only call; superuser is only needed by the upstream *setup script*; the claim that db-sync's Haskell code never needs more is [I]].

**Config** `<nd>/config/db-sync-config.json` is generated on every Start from the bundled `chains/cardano/db-sync-config.base.json` (IOG's `book.world.dev.cardano.org/environments/mainnet/db-sync-config.json`, byte-identical to preprod/preview except `NetworkName`/`RequiresNetworkMagic` [V diff]; it is **not** in either release tarball) with `NetworkName`, `RequiresNetworkMagic`, `PrometheusPort`, `insert_options`, `ledger_backend` set. `NodeConfigFile: config.json` points at the node's generated config in the same dir, so genesis files, protocol and hard-fork settings follow the node [V `Cardano.DbSync.Config`: genesis paths are resolved next to the node config].

**insert_options (all explicit, = bindings)**: upstream's documented recommended defaults — `tx_cbor disable`, `tx_out.value enable`, `use_address_table false`, `ledger enable`, `shelley/multi_asset/metadata/plutus enable`, `governance enable`, `pool_stat enable`, `json_type text`, `disable_epoch false` — except that the off-chain fetchers stay `disable` (upstream default since 13.7.0.1): they make outbound HTTPS requests to URLs taken from the chain, and the unit has no network. `ledger_backend inmemory` (the LSM backend is marked experimental with open known issues in the 13.7.2.1 release notes). Cheaper database shapes exist (`tx_out.value` = `consumed` / `prune` / `bootstrap`, `ledger disable`) but several are irreversible once chosen on a database ("if this is set once, it must always be set"), so they are not offered in v1.

**Prometheus.** db-sync starts its metrics server on `PrometheusPort` with no bind option (`serveMetricsT port` = all interfaces) [V source]. Hence the systemd confinement (`IPAddressDeny=any`, `IPAddressAllow=localhost`; db-sync needs only 127.0.0.1 PostgreSQL and the node's UNIX socket). On a systemd without cgroup-BPF IP filtering the directives are ignored [I]: then block the `dbsync_metrics` port in the firewall. Port 41217/41227/41237 is a catalog port (`config: none`) so the Ports step checks it is free.

### 16.5 Health and sync percentage

The node height stays the Prometheus probe (`cardano_metrics`, unchanged). The indexers are reported by two host checks (declared in `network.yml` → `diagnostics`, run by the agent; the node page shows only non-`ok` items, so a healthy or disabled indexer is silent):

- `cardano_dbsync` reads db-sync's own Prometheus, **not** PostgreSQL (no credentials needed on the agent): `cardano_db_sync_db_block_height`, `cardano_db_sync_db_slot_height`, `cardano_db_sync_node_block_height`, `cardano_db_sync_db_queue_length`, `cardano_db_sync_db_epoch_sync_number` [V Metrics.hs]. Sync % = (db slot − Shelley start slot) / (wall-clock slot − Shelley start slot), the same 1-second slot clock the node probe uses (constants verified against Koios); at tip when the DB slot is within 300 s of the wall clock. States: `info` "waiting for PostgreSQL" (no `ready` marker), `info` "starting" (gauges still 0: migrations / ledger state), `info` "syncing: x%" with `block X of Y`, behind-time and queue length, `ok` at tip, `warn` when the metrics port does not answer although the preparation is done.
- `cardano_ogmios` reads `/metrics`: `warn` unreachable / not connected to the node, `info` while `ogmios_network_synchronization` < 0.999 (the node is behind), `ok` otherwise.
- The SQL equivalent for an operator: `select max(block_no) from block;` (as used by upstream's own NixOS wait script) — not used by the toolkit [V].

Both are re-run every 5 minutes by the admin node page.

### 16.6 Disk, RAM and the sizing facts [V upstream Readme "System Requirements", May 2025, db-sync 13.6.0.5]

| | mainnet | preprod |
|---|---|---|
| PostgreSQL database (default insert options, off-chain pool data on) | 438 GB | 16 GB |
| db-sync ledger state files | ~10 GB | ~2 GB |
| db-sync RSS | 21 GB | 3.5 GB |
| cardano-node RSS | 24 GB | 5.5 GB |
| Host | 64 GB RAM, 4+ cores, 700 GB+ SSD, ≥60k IOPS ("lower IOPS = slower sync or falling behind the tip") | – |

Upstream also documents `ledger: disable` as saving up to 10 GB RAM, and `ledger_backend: lsm` as ~2–3 GB instead of ~16 GB for the UTxO set — both rejected for v1 (see above). Preview has no published figure [I: like preprod]. A first sync from genesis takes **days** on mainnet [I: no figure in the docs; IOG publishes restorable snapshots at `update-cardano-mainnet.iohk.io/cardano-db-sync/`, restoring them is not implemented]. The `network.yml` env sizing (32 GiB RAM mainnet) is the node only; `diskNotes` carry the db-sync numbers. New disk role `indexer` (nvme): `<role>/ledger-state` and `<role>/pgdata`; without a separate disk the layout falls back to `<node_dir>/indexer`. Placing the tablespace on the indexer disk keeps the 440+ GB off the node's NVMe, the server's own data directory is untouched.

### 16.7 Risks and unverified items

| Item | Status |
|---|---|
| Nothing here was executed on Linux: unit semantics (`PartOf`, `RemainAfterExit`, `IPAddressDeny`), the prepare script against a real PostgreSQL, db-sync/Ogmios against a node | [N]. The prepare script was run against mocked `psql`/`setpriv` on macOS (flow, SQL on stdin, file modes) and all scripts pass `bash -n`. First real run: preview env on a Linux box with PostgreSQL. |
| Ogmios v7.0.0 / db-sync 13.7.2.1 with cardano-node 11.1.x | [I] upstream tested/built with 11.0.1; newer node must keep serving the negotiated node-to-client versions. Check the release notes when bumping. |
| db-sync binary is static musl [I from flake `musl64` + changelog 13.7.0.4 "restore static (musl) Linux binary"], so it needs no libpq / glibc | not verified with `ldd` (no Linux host) |
| db-sync as a non-superuser DB owner | [I] consistent with upstream's NixOS module and migrations without extensions; if a migration needs a privilege, run `alter role rpcnode_cardano_<env> superuser` and report it |
| Role-level `maintenance_work_mem` / `max_parallel_maintenance_workers` | [V] values from upstream's docker-compose; applied per role, not server-wide |
| A later hard fork needs a newer db-sync before the node upgrade | operational: bump `cardano-db-sync` with `cardano-node` (both are catalog programs; "Update" in Clients) |
| Mithril snapshot restore wipes `<node_dir>` including `.toolkit/cardano-dbsync` (password, `pg.env`) | the password is regenerated and the role updated on the next Start, but the remove hook then no longer knows the database: drop `rpcnode_cardano_<env>` by hand. The indexer role dir is separate and survives. |
| Changing `indexer` from db-sync to none | the DB, ledger state and hook stay on purpose; they go with the node removal (the hook is generic `bin/rpcnode-on-remove.sh`) |
| No per-option artifact filtering | every node of the env gets the Ogmios and db-sync archives (~120 MB) |
| Ogmios has no connection limit flag | proxy-level limits (16.3) |
| `/proc/meminfo`-based sizing in the prepare script | Linux only, like the rest |

## Review sign-off
(blank — to be filled by reviewers)
