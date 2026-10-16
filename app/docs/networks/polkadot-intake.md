# Network intake — Polkadot (relay chain; Paseo, Westend) + optional REST API / PostgreSQL history indexer

Facts are tagged **VERIFIED** (read from upstream source / curl at the named version, 2026-10-13/14) or **INFERRED**.
Research: `docs/networks/research/nonevm/`. Nothing here was run on a Linux host (see the last section).

| Field | Answer |
|---|---|
| Network id | `polkadot` (envs `mainnet` = Polkadot, `paseo`, `westend`) |
| Status | **implemented** (node, Parity snapshots, optional REST API, optional PostgreSQL history indexer); nothing run on a Linux host, the indexer project was run end to end on macOS against a public RPC |
| Package | `chains/substrate` (shared Substrate recipe, reusable by peaq later) + `chains/polkadot` (release rule, indexer) |

## Node

- **Client:** `polkadot`, `polkadot-prepare-worker`, `polkadot-execute-worker` from `paritytech/polkadot-sdk` releases `polkadot-stable<YYMM>[-n]` — bare binaries, same version, same directory (`bin/`). VERIFIED: the node checks the workers' `--version` (`workers.rs`). Linux x86_64 only: Parity ships no linux-arm64 binary. Release rule `PolkadotClientReleaseResolver`: not a prerelease, all three assets, highest `(YYMM, n)` — never by date (`polkadot-stable2603-7` was published after `…2609` and has 2 assets).
- **Flags** (`SubstrateFlags`, VERIFIED in sc-cli at polkadot-stable2609): `--chain polkadot|paseo|westend`, `--base-path`, `--database`, `--sync`, `--state-pruning`, `--blocks-pruning`, `--rpc-external --rpc-methods=safe --rpc-cors=all`, the `--rpc-max-*` set, `--trie-cache-size`, `--in-peers/--out-peers`, `--prometheus-port` (loopback; `--prometheus-external` is never passed), `--no-telemetry`. HTTP and WS share the RPC port; litep2p (default backend) also listens `udp/<p2p port>` (webrtc-direct) → `protocol: both`.
- **Node type** = the wizard's Snapshot type: `pruned` (paritydb, `--sync warp`, state+blocks 256) or `archive` (rocksdb on Polkadot/Paseo — Parity publishes no paritydb archive of them —, paritydb on Westend; `--sync full`, pruning archive). Pruning is stored in the DB at its first start; Start refuses to switch an existing DB (a marker in `.toolkit/substrate-mode`) instead of letting the node crash.
- **`--database rocksdb`** exists only in builds with the `rocksdb` feature (VERIFIED: `arg_enums.rs`; the crate's default features include it). Start runs `polkadot --help` and fails with a clear message when an archive node needs rocksdb and the binary lacks it. That the release binary is built with default features is INFERRED.
- **Capacity:** `rpc-max-connections 1000` (vendor default 100; docs.polkadot.com's own RPC recipe runs 1000 — VERIFIED in `parachain-rpc.md`), other vendor defaults written explicitly; the prod preset raises connections to 2000, the WS buffer to 256 and the trie cache to 4 GiB (judgement). `LimitNOFILE=1048576`.
- **Must not be public:** `--rpc-methods unsafe`, Prometheus, `network/secret_ed25519`, `keystore/`. The unit locks the method set to `safe`.
- **Height / tip:** `chain_getHeader` → `result.number` (hex) locally and on `rpc.polkadot.io`, `polkadot.api.onfinality.io/public`, `polkadot-rpc.n.dwellir.com`, `pas-rpc.stakeworld.io`, `paseo-rpc.n.dwellir.com`, `westend-rpc.polkadot.io`, … ; `system_health.isSyncing` feeds the `syncing` flag (during warp sync the head stays 0).
- **Ports** (block 42200 / 42210 / 42220): `+0` RPC, `+2` p2p, `+4` metrics, `+5` the optional REST API, `+6` the history indexer's GraphQL API (all interfaces, by the operator's decision), `+7` its processor metrics (loopback).

## Snapshots (snapshots.polkadot.io)

Official Parity/IBP store (docs.polkadot.com). It is a file tree with a `files.txt` manifest (~478 files for the 22 GB pruned Polkadot DB, ~7.7k for an archive), not an archive, and it has no JSON index: `SubstrateSnapshotIndex` scrapes the HTML table (name, version, timestamp, last block — which may print as `1.47109E+6` —, size, pruning, dated URL). The dated URL rotates daily and is resolved fresh every time.

| env | pruned | archive |
|---|---|---|
| mainnet | `polkadot-paritydb-state-blocks-pruned` (22 GB) | `polkadot-rocksdb-archive` (4.17 TB) |
| paseo | `paseo-paritydb-prune` (19 GB) | `paseo-rocksdb-archive` (92 GB) |
| westend | `westend-paritydb-prune` (131 GB) | `westend-paritydb-archive` (1.08 TB) |

The resolver answers a `substrate-files://` sentinel (like `mithril://`); the host agent runs `SubstrateFilesSnapshot`: `GET files.txt`, a HEAD per file for sizes, then 16 parallel resumable GETs (`ResumableStreamCopier`), skipping files whose size already matches (the official recipe is `rclone copy --transfers 20 --size-only`; no rclone on the host). Files land in `<state>/chains/<chain id>/paritydb/` or `…/db/` (VERIFIED: `database_config` in sc-cli joins `paritydb|db` + `full`; the manifest paths start with `full/`; docs.polkadot.com's parachain recipe restores into `chains/<id>/db/`). The node and its REST API are stopped first; a different snapshot than the recorded one wipes the target DB directory. The store publishes no checksums, so completeness = every manifest file at its published size. A pruned→archive switch needs a different DB: the snapshot step is for a fresh node.
Warp sync is the zero-config alternative for pruned nodes (hours); the snapshot is the fast path and the only way to an archive node.

## Indexers — what was evaluated, what ships

Two companions, one install option `indexer = none | rest-api | history` (wizard group on the Node-type step; at most one):

| Option | What it is | Database | Needs |
|---|---|---|---|
| `rest-api` | Parity `polkadot-rest-api`: a stateless REST layer over the node's RPC (blocks, extrinsics, account state, fees, tx submit) | none | any node; history beyond 256 blocks needs Archive |
| `history` | blocks, extrinsics, events and balance transfers of this node **kept in PostgreSQL over time**, queried as GraphQL (Subsquid) | the host's PostgreSQL (TON / Cardano pattern) | **Archive node** (Start refuses a pruned one), Node.js 24 (downloaded), the build below |

### Evaluation (upstream facts, 2026-10-14)

The question was: a full indexer with history in PostgreSQL, suited for production load, no Docker. Candidates, each checked against upstream source or the registry:

| Candidate | Finding | Verdict |
|---|---|---|
| **Subscan Essentials** (`subscan-explorer/subscan-essentials`, Go) | VERIFIED at `bcb39fb` (last commit 2026-04-27; no releases, one 2021 tag): Go 1.25 (`go.mod`; `go build ./cmd` produced a 40 MB binary on darwin-arm64 with `CGO_ENABLED=1`, while the makefile's `CGO_ENABLED=0` failed there with `undefined: NewSr25519` in `substrate-api-rpc` — a C compiler is needed, INFERRED for linux), **Redis is mandatory** (`configs.Init` panics without it; the work queue `go-workers` and the indexing progress live in Redis) plus MySQL 8 or PostgreSQL 16+, three processes (`start subscribe`, `start worker`, the API on 4399). Complete explorer-grade parser, a ready API incl. transfers per address (`/api/plugin/balance/transfer`). **No start-block option**: `subscribe` publishes every block from the Redis key `…FillFinalizedBlockNum` (0 on a new install) up to the finalized head, so it backfills from genesis from an archive node; a recent start would mean seeding that Redis key by hand (INFERRED to work, not tried). Redis would have to be built from source under `node_dir` and run with persistence, or a restart forgets the position. | not chosen: 4 daemons incl. a stateful Redis, genesis-only, no releases |
| **SubQuery** (`@subql/node` + `@subql/query`, Postgres) | A framework: somebody must write the manifest, GraphQL schema and mapping code. Latest `@subql/node` on npm is 6.4.6 (2025-11-25) and depends on `@subql/node-core ~19.0.0`, while node-core 19.3.1 (2026-04-01) fixes a SQL injection in the dynamic-datasource path (`009dee05`): a pinned `@subql/node` cannot take that fix. Needs the `btree_gist` extension in PostgreSQL (INFERRED from upstream docs, not checked here). | not chosen: stale runner with a known unpatched issue, project still to write |
| **Subsquid** (`@subsquid/substrate-processor` 8.8.2, 2026-09-14; `@subsquid/graphql-server` 4.12.1, 2026-07-10; PostgreSQL via typeorm) | Also a framework (project = schema + mapping), but the processor ingests **straight from a node's RPC** with no gateway (`setRpcEndpoint` only; VERIFIED by running it: Polkadot genesis era, 1.0M / 1.5M / 5M / 10M / 20M / 28M / head, Paseo, Westend ≥ 7,752,190). Two processes (processor, API) + PostgreSQL, no Redis. The SQD "gateway" `v2.archive.subsquid.io/network/polkadot` is alive (`/height` answered 33,417,949) but is a third-party store and is **not used**. Metadata older than v14 is decoded with a built-in bundle for Polkadot; **not for Westend** (processor stops with "types bundle is required for westend chain" below block 7,752,190, VERIFIED). | **chosen** |
| **substrate-api-sidecar** | frozen by Parity (20.14.1: "final feature release"). | rejected |
| **polkadot-rest-api** alone | stateless: it answers per-block and per-account-state questions from the node (including a block's extrinsics and events) but keeps no history of its own, so "all transfers of an account over time" is not something it can answer; beyond 256 blocks it needs the archive node. | ships as `rest-api`, not a history indexer |

Subsquid wins on moving parts (Node.js + PostgreSQL, two processes, progress in the database, resumable per block transaction, hot-block/fork handling built in) and on maintenance (releases in 2026). Its costs, stated plainly:

- **Project is ours.** `chains/polkadot/history/` is a ~10-file squid written for this toolkit (blocks, extrinsics, events, `Balances.Transfer` as a `Transfer` table; schema in `schema.graphql`), pinned by `package-lock.json` (389 packages, sha512 integrity; installed with `npm ci --ignore-scripts`, no install script runs). A schema change needs a new migration (see *Maintaining the project*).
- **`@subsquid/graphql-server` ships Apollo Server v3 (end of life since 2024) and `npm audit --omit=dev` reports 9 advisories (6 high; `@graphql-tools/utils` `mergeDeep` prototype pollution, `uuid`) with no fix available upstream (MEASURED 2026-10-14).** Mitigations in the product: no custom resolvers, request/response/SQL limits, a reverse proxy in front. Re-run `npm audit` when bumping.
- Licences: Subsquid packages are GPL-3.0-or-later (they run as separate processes from our GPL project files).
- Backfill speed is whatever the node's RPC allows (see *Sizing*); there is no upstream estimate, and nothing here is a promise.

## History indexer (`indexer=history`)

**What runs** (per env, three units, written by Start next to the node's unit; `NODE_DIR` = node dir, `IDX` = the `indexer` disk role, else `NODE_DIR/indexer`):

| Unit | Does | Network |
|---|---|---|
| `rpcnode-polkadot-<env>-history-prepare.service` | idempotent on every Start: PostgreSQL role + database + tablespace (the TON / Cardano mechanism: `resources/chains/default/postgres-functions.sh`, password only in `0600` files under `NODE_DIR/.toolkit/polkadot-history/`, SQL through psql stdin, removal hook drops everything), Node.js 24.21.0 into `IDX/node` (tarball sha256 pinned in the script), `npm ci --ignore-scripts` + `tsc` into `IDX/squid` once per project version, schema migration, `ready` marker. A host without PostgreSQL gets it from apt (tuned); an existing server is only added to. | full (downloads, apt) |
| `rpcnode-polkadot-<env>-history.service` | the processor: waits for `ready` and a synced node, then `node lib/main.js` reading blocks over the node's own RPC on 127.0.0.1 and writing batches to PostgreSQL (one transaction per batch, unfinalized blocks tracked and rolled back on forks) | systemd `IPAddressDeny=any` + `IPAddressAllow=localhost` |
| `rpcnode-polkadot-<env>-history-api.service` | `squid-graphql-server` (GraphQL over the tables, POST `/graphql`, `squidStatus { height finalizedHeight }`) | no IP confinement: listens on all interfaces |

Nothing is installed system-wide except what the PostgreSQL provisioning already does. No Docker. Files: `bin/rpcnode-polkadot-history-{prepare,run}.sh`, `bin/rpcnode-on-remove.sh` (drops database, tablespace, role, the tablespace directory and the runtime; changing the option to `none` leaves the data, removing the node drops it), logs `logs/polkadot-history{,-api,-prepare}.log`.

**Archive required.** A pruned node keeps 256 blocks of state: it cannot serve a backfill, nor a restart after more than ~25 minutes of downtime. Start fails with a message when `indexer=history` is combined with a pruned node, and the wizard switches the node type to Archive when History is picked (and disables Pruned while it is). Westend cannot start at genesis (above); the Westend default start is `head`.

**Start block** (`install_options.indexer_start_block`, text next to the choice): `genesis` (default; Westend: `head`), `head` (the finalized head when the processor first runs) or a block number. It applies to a **first run only**: `src/start.ts` reads `squid_processor.status`, and when blocks are stored the processor resumes at the last stored block + 1 and ignores the option (VERIFIED: without this guard a larger START_BLOCK silently leaves a gap, the range bound wins over the stored state). Indexing earlier blocks than the first run chose needs a fresh database (remove the node, or drop and recreate the database by hand); that is not automated.

**Schema** (`schema.graphql`; addresses are SS58 with the chain's prefix, 0 for Polkadot and 42 for Paseo / Westend, VERIFIED via `system_properties`; amounts are plancks in a numeric column, strings in GraphQL):

| Table | Rows | Indexes that matter |
|---|---|---|
| `block` | every block: height, hash, parent, timestamp, spec version, extrinsic and event counts (block 0 has no timestamp: 1970-01-01) | height, hash, timestamp |
| `extrinsic` | every extrinsic: block, index, hash, pallet, call, signer, success, tip, timestamp, args (JSON; default only for signed ones, `INDEX_ARGS`) | (signer, block), (pallet, call, block), hash |
| `event` | events, default all **except** `ParaInclusion` (`INDEX_EVENTS=relevant`; `all` and `none` exist): pallet, name, phase, extrinsic index, args | (pallet, name, block) |
| `transfer` | every `Balances.Transfer` event: from, to, amount, block, timestamp, extrinsic hash | (from, block), (to, block), hash |

Example (account history, newest first): `{ transfers(where:{OR:[{from_eq:"14QK…"},{to_eq:"14QK…"}]}, orderBy: blockNumber_DESC, limit: 50){ blockNumber timestamp from to amount extrinsicHash } }`; deep paging should use the `…Connection` queries (`first`/`after`). A query without `limit` is rejected ("response might exceed the size limit"), VERIFIED.
**Scope honesty:** `Transfer` is `Balances.Transfer` only (no assets, no XCM transfers; their events are in `event` unless `ParaInclusion`). Since the Asset Hub migration the relay chain holds almost no user balances: the 530 recent blocks measured below contain **0** transfers, while the sampled older ranges (blocks 1.5M … 28M) hold 0.2 – 1.2 per block. User-balance history after the migration lives on Asset Hub, which is a different chain and node (not shipped); the same squid would index it unchanged for `Balances`, an `Assets` handler would be a project change.

**Production configuration** — every value is a visible `clientConfig.bindings` row with `whenInstallOption: indexer=history` and equals what the script applies (`PolkadotHistoryTest`). Flags verified in `@subsquid/graphql-server` 4.12.1 (`--help`, `server.js`) and `src/config.ts`:

| Setting | Value | Why |
|---|---|---|
| API bind | ALL interfaces (decided by the user; the server has no bind option) | serves the GraphiQL console on GET, no authentication, no rate limit: put a rate-limiting proxy (POST /graphql only) in front or firewall the port |
| `--max-request-size` / `--max-root-fields` | 64 KB / 10 | vendor 256 KB / unlimited |
| `--max-response-size` | 10000 nodes (rows × fields) | no unbounded dumps |
| `--sql-statement-timeout` | 10000 ms | a runaway query cannot hold a connection |
| `--isolation-level` | `REPEATABLE READ` | read-only API transactions (vendor default SERIALIZABLE) |
| `--dumb-cache in-memory`, TTL / max-age / size | 3000 ms / 3000 ms / 128 MB | identical queries within half a block are answered from memory; `cache-control: max-age=3, public` reaches the proxy (VERIFIED) |
| `GQL_DB_CONNECTION_POOL_SIZE` | 20 (vendor 5) | API connections to PostgreSQL |
| PostgreSQL role connection limit | 60 | processor pool (10) + API pool + tools; other databases on a shared server keep theirs |
| Node heap | processor 4096 MB, API 2048 MB | `--max-old-space-size` |
| Processor → node | capacity 10, batch 50 calls, timeout 60 s, no rate limit | a recent Polkadot block is ~3 calls and ~260 KB (MEASURED: `chain_getBlock` 171 KB + events storage 87 KB); the node answers at most 15 MB per response, so 50 calls ≈ 17 blocks ≈ 4.4 MB. Lower `RPC_CAPACITY` on a node that also serves public traffic |
| `LimitNOFILE` | 65536 | |

Not public: processor Prometheus (loopback); the GraphQL API IS reachable from the network by decision (see the API bind row), PostgreSQL (the host's own listener rules), the password (generated on the host, `0600`; passed to the Node.js processes in their environment, never in argv, unit files or `launch.json`). The node RPC the processor uses stays `--rpc-methods=safe`: the processor calls only `chain_getBlock`, `chain_getBlockHash`, `chain_getHeader`, `chain_getFinalizedHead`, `state_getStorageAt`, `state_getRuntimeVersion`, `state_getMetadata`, `state_call` (read from the packages; `state_traceBlock` exists in the library but is only used when a project asks for traces, ours does not).

**Reverse proxy** (INFERRED, not run): expose only `POST /graphql` from `127.0.0.1:<+6>`, reject GET (GraphiQL), cap the body at 64 KB, rate-limit per client, cache by body hash for 1–3 s, and set connection / read timeouts above the SQL timeout. nginx sketch: `limit_req_zone`, `proxy_cache_key "$request_body"`, `client_max_body_size 64k`, `if ($request_method != POST) { return 405; }`.

**Node page** (`polkadot_history`, port role `history_api`): not installed / being prepared (first Start: PostgreSQL, Node.js download, `npm ci`) / preparation failed / API not answering / processor stopped / nothing stored yet / **catching up N blocks behind (stored height of the node's best head, a backfill is information, not an error)** / in sync (lag ≤ 40 blocks). Height comes from `squidStatus` (`squid_processor.status`).

### Sizing and speed (what is measured, what is not)

- **MEASURED** (local PostgreSQL 18, 530 consecutive recent Polkadot blocks, `INDEX_EVENTS=relevant`): 1.7 MB with indexes in total (block 0.3 MB, extrinsic 0.7 MB, event 0.7 MB), i.e. ~3.3 KB per block; the data alone is ~1.3 KB per block and the fixed overhead of 11 tiny indexes inflates the rest. **With `INDEX_EVENTS=all` the same blocks need 76 MB for events alone: 144 KB per block**, because `ParaInclusion` (CandidateBacked / CandidateIncluded, ~1.6 KB args each) is 37,456 of the 38,957 events (96%).
- **INFERRED** extrapolation to ~33.4 M blocks at 2.5 – 3.3 KB per block: roughly 85 – 110 GB, plus the transfer table (0.2 – 1.2 rows per block in the sampled older eras, ~400 B per row with its three indexes: single-digit to ~10 GB), plus WAL and bloat. Plan **200 GB** on the indexer disk for a full-history `relevant` index; the real figure is unknown until a backfill has run. `all` events would be ~4.8 TB and is not recommended.
- **Speed.** Upstream states no backfill rate. MEASURED once, against a public RPC endpoint from a laptop (network-bound): 9 – 12 blocks/s ingestion, mapping + PostgreSQL writes 400 – 2000 blocks/s on the same batches, so the database side was not the limit there; against a local archive node the RPC side is expected to be much faster, but **how fast is unknown**. At the measured 12 blocks/s a genesis backfill of 33.4 M blocks would take ~32 days; a local node should do better, which is why the start block option exists (a first deployment can index from `head` or from a recent block).
- **API throughput.** MEASURED: one `squid-graphql-server` process, `wrk -t2 -c32`, an account-transfers query returning 20 rows (2.5 KB) from a ~240-row table in memory, no cache hits possible between (the in-memory cache was off in this run): **6,880 requests/s, p50 latency 4.7 ms** on an Apple M4 Max. That says the Node process is not the first bottleneck for point queries; it does not say what PostgreSQL does on 100 GB (an account with millions of transfers needs a bitmap-OR over two indexes plus a sort) — not load-tested at scale. The server is a single Node.js process; beyond one core use the proxy cache (identical bodies) and, if needed, a second node.

### Maintaining the project

`chains/polkadot/history/` was produced with `@subsquid/typeorm-codegen` (models from `schema.graphql`) and `squid-typeorm-migration generate` against an empty database (the one migration is renamed `1700000000000-Data.js`; its class name equals that). To change the schema: edit `schema.graphql`, run codegen, generate a **new** migration (never edit an applied one), add the files to `PolkadotHistory.PROJECT_FILES` (a test asserts the list equals the resource tree), re-run `npm install --package-lock-only` and `npm audit`. The project hash changes with any file, so the next Start rebuilds (`npm ci` + `tsc`) before the processor restarts. Run the processor from a checkout with `START_BLOCK`/`END_BLOCK` (bounded runs exit 0) against a scratch database to test a change.

## REST API (`indexer=rest-api`)

`polkadot-rest-api` (Parity, v0.3.2 2026-10-07, Rust, GPL-3.0, "1:1 API compatibility" with sidecar, `/v1/…`, same `SAS_*` settings; Parity's own benchmark claims, not measured by us; ships a Docker image and a crate but no release binaries; young: 5 GitHub stars) is **stateless and has no database**: it answers *per-block / per-account-state* questions from the node, not "all transfers of an account over time" — that is what `history` is for. For production load it scales by the node it sits on: raise `--rpc-max-connections`, use an archive node for history, and put several nodes behind a rate-limiting proxy. Since the Asset Hub migration the relay chain holds no user balances/staking; those APIs belong on an Asset Hub node (not shipped here; sidecar/rest-api can pair both with `SAS_SUBSTRATE_MULTI_CHAIN_URL`).

**Install option** `indexer = rest-api`. With `rest-api`, Start writes `bin/rpcnode-polkadot-indexer.sh` and the unit `rpcnode-polkadot-<env>-indexer.service` (a companion of the node, found by name when the node is removed). The script — same shape as the TON indexer's — (1) installs build tools via apt only if missing, (2) installs Rust 1.90.0 with a checksum-verified `rustup-init` under `node_dir/indexer`, (3) clones `paritytech/polkadot-rest-api` at the pinned tag `v0.3.2` (`RPCNODE_POLKADOT_API_TAG` in `/etc/rpcnode/polkadot.env` overrides), (4) `cargo build --release --locked -p polkadot-rest-api` niced and capped at min(cores/2, RAM/4 GB) jobs, once per tag, (5) waits until the node is not syncing and past block 0, (6) `exec`s the API. Turning the option off removes the unit.
- **Port:** `+5` of the env block (42205 / 42215 / 42225), role `indexer`, listens on `0.0.0.0` like the node RPC. **Health:** `GET /v1/health` (202), `GET /v1/blocks/head/header`; the node-page check `polkadot_indexer` reports installed / building / answering / head lag vs the node.
- **Prod config** (visible bindings, all in `PolkadotIndexer.environment`): `SAS_EXPRESS_REQUEST_LIMIT 512000`, `SAS_EXPRESS_KEEP_ALIVE_TIMEOUT 5000`, `SAS_EXPRESS_BLOCK_FETCH_CONCURRENCY 10` (vendor defaults), `SAS_LOG_STRIP_ANSI true`, `SAS_METRICS_ENABLED false`.
- **Must not be public / known exposure:** `/metrics` is served on the API port itself (VERIFIED `app.rs`), so metrics stay **off**. CORS is `permissive` (VERIFIED), `POST /v1/transaction` submits extrinsics (a safe RPC method), `/docs` is public. The node's `unsafe` RPC methods are not used: `/v1/node/network` and the experimental trace endpoints need them and will fail by design. A rate-limiting proxy belongs in front of both ports.
- **INFERRED / not run:** the build on a real host (RAM/time), `rustup-init` URL layout (checked by curl), that a `safe`-method node satisfies every endpoint the API uses at start.

## Untested

No Linux host was available: verification is unit tests (flags ↔ network.yml, sentinel parsing, snapshot restore against a local HTTP server, script `bash -n`) plus upstream source/docs. First real-host checks: `polkadot --version` workers, one pruned snapshot restore + start (paritydb layout), archive `--database rocksdb`, the REST API build.

**History indexer — what was actually run, and where.** On macOS (arm64, Node 24.21.0, PostgreSQL 18, public Polkadot / Paseo / Westend RPC endpoints, never a local node): the squid project built from its lock file with `npm ci --ignore-scripts` + `tsc`; the migration applied; the processor ran bounded ranges at genesis, 1.0M, 1.5M, 5M, 10M, 20M, 28M and at the live head (hot blocks, restart/resume, the START_BLOCK guard), Paseo, Westend from 7,752,190; the GraphQL server answered account-history queries, enforced its limits and sent the cache headers; the real `history-run.sh` / `history-prepare.sh` functions (build + schema) ran against a local PostgreSQL with a fake node-RPC proxy. **Not run:** anything under systemd (units, `IPAddressDeny` confinement, `Restart=`), the apt / `setpriv` / tablespace / role provisioning of `postgres-functions.sh` on Linux (the same shared code the Cardano and TON companions use), the Node.js linux tarball download, a local archive node as the source, a multi-day backfill, PostgreSQL at 100 GB.
