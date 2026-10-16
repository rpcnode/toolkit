# Network intake — Stellar (stellar)

Filled from the Go sibling `toolkit-go/internal/networks/stellar/` and live upstream checks made 2026-10-13.
Legend: **[V]** verified now (how in brackets) · **[I]** inferred / judgement / not run.
Drafts that go with this document (same folder): `network.yml`, `clients.yml`, `node.service.tmpl`, `heal.sh.tmpl`. Code design: `kotlin-design.md`.

| Field | Answer |
|---|---|
| Network id | `stellar` (`NetworkId.STELLAR`, new) |
| Display label | Stellar |
| Author / Date | research agent / 2026-10-13 |
| Status | draft — ready for review |

**Go sibling:** `toolkit-go/internal/networks/stellar/` exists. Go ran `stellar-rpc` (Soroban RPC) with an embedded captive `stellar-core` as `User=nodeop`
under `/opt/stellar`, `/etc/stellar`, `/data/stellar`, installing via SDF apt → docker-image extract → `go build` fallbacks
(three envs mainnet/testnet/futurenet, never-prune retention, TOML heal, journal-marker reset). Port: product model (stellar-rpc, not Horizon, not a validator),
TOML shape, captive-core config port patching, reset markers, public-tip list, height = `getHealth.latestLedger`. **Not ported:** docker/apt/go-build install paths,
`/opt|/etc` layout, the "Synced only when oldestLedger reaches genesis" rule (cannot be satisfied by stellar-rpc; see 10-11), futurenet.

---

## Review table

| Section | Status | Notes |
|---|---|---|
| 1 Product scope | proposed | stellar-rpc + captive core only; Horizon and standalone core deferred |
| 2 Environments | proposed | mainnet (= pubnet), testnet; futurenet deferred |
| 3 Artifacts | proposed | SDF apt `.deb` files fetched by URL (no GitHub assets exist) |
| 4 Ports | proposed | central plan, block 42560 |
| 5 Disks | proposed | one role `blockchain` |
| 6 Client config + prod | proposed | generated `stellar-rpc.toml` + patched captive-core cfg |
| 7 Snapshot | proposed | `never` for both; datastore backfill is a client feature (mainnet) |
| 8-9 Start / proc | proposed | binary kind, custom unit, ExecStartPre heal |
| 10-11 Height / tip | proposed | `getHealth` / `getLatestLedger` JSON-RPC |
| 12-13 Lifecycle / options | proposed | install option `history` |
| **Overall** | **needs decision** | see 15 (arm64, Ubuntu-only, retention default) |

---

## 1. Product scope

1. **Why:** operators want a Soroban/Stellar RPC node (the Go product shipped it). Stellar's public API surface = `stellar-rpc` (JSON-RPC, smart-contract oriented, retention-window based).
2. **MVP (v1):** install `stellar-rpc` + `stellar-core` from the SDF `.deb`s, generated `stellar-rpc.toml` and patched captive-core config under node_dir, systemd unit,
   self-heal of the captive-core bucket dir, height probe (`getHealth`), public tip (public Soroban RPCs), install option `history` (week / month / forward-only),
   mainnet backfill from the AWS public data lake, prod limits as bindings, flag overrides for the CLI-capable knobs.
   **Deferred (and why):**
   - **Horizon** (classic REST API): needs a PostgreSQL server (stellar-horizon deb `Depends: postgresql-client`; Horizon ingests into Postgres) plus its own captive core.
     The Kotlin precedent for a host database is the TON indexer (`chains/ton/indexer.sh.tmpl`: reuse/install host PostgreSQL, own role + tablespace + database). That is a second product with
     a different API; do it after v1 as a separate program id `stellar-horizon` and a role `index`. Horizon .debs exist in the same repo (`stellar-horizon_29.0.0-563_amd64.deb`, 33 MB) [V Packages.gz].
   - **Standalone stellar-core** (watcher/validator, `DATABASE=sqlite3://` or Postgres, own history archive publishing): not an RPC product.
   - **futurenet** (ids exist in Go): reachable with the same stable debs today (public futurenet RPC reports protocol 29 / rpc 29.0.0) [V getVersionInfo], but futurenet regularly needs
     `~vnext` builds from the `testing` channel (Go: `HealVNextBinaries`). Add later as env 3 (port block env2 = 42580-42589).
   - **aarch64:** no arm64 `.deb` exists (see 3). Would need a `build:` block (Go + Rust + C++ toolchains).
   - Snapshot download, CDN mirror, diagnostics beyond `path_mtu`, Prometheus wiring.
3. **Pin-only?** No — real downloadable artifacts exist (apt pool URLs). They are not GitHub assets, so a URL resolver is required (see 3). No Docker anywhere.
4. **One env per host?** `false` — ports/dirs are per env (Go also ran them side by side).

---

## 2. Environments

| Env id | Label | Production? | Notes |
|---|---|---|---|
| `mainnet` | Stellar Mainnet (Pubnet) | yes | passphrase `Public Global Stellar Network ; September 2015`; protocol 29 today [V getLatestLedger] |
| `testnet` | Stellar Testnet | test | passphrase `Test SDF Network ; September 2015`; protocol 29 today [V] |

1. Env ids = Go ids (`mainnet`, `testnet`); both exist as `EnvId.MAINNET/TESTNET` — no new `EnvId`. Stellar calls mainnet "pubnet": only the captive-core file name (`captive-core-pubnet.cfg`) and the data-lake path (`.../pubnet`) use that word.
2. Same binaries for both envs; they differ by passphrase, history archives, captive-core cfg, datastore. The SDF testnet may be a protocol ahead of mainnet during an upgrade window — a stale core then fails with `unsupported ledger version` (heal markers cover it; the fix is a client update).
3. All envs expose a public tip.

---

## 3. Client binary & artifacts

**Upstream facts (all [V] curl/API 2026-10-13):**

| What | Result |
|---|---|
| GitHub `stellar/stellar-rpc` releases | latest `v29.0.1` (2026-10-12), `v29.0.0`, `v28.0.1`… — **assets: none** (source only) |
| GitHub `stellar/stellar-core` releases | latest `v29.0.0` (2026-10-05); a `v29.0.0-internal` tag/release also exists (not a prerelease flag!) — **assets: none** |
| `stellar/go` | no releases (Horizon moved to `stellar/go-stellar-sdk`; `v0.7.3`, no assets) |
| SDF apt `https://apt.stellar.org` | suites `focal jammy noble`, components `stable testing unstable`, **architecture amd64 only** (`Release`: `Architectures: amd64`; `binary-arm64/Packages.gz` → 403) |
| `dists/noble/stable/binary-amd64/Packages.gz` | 200, 10 KB — newest stable: `stellar-core 29.0.0-3589.4eb833373.noble`, `stellar-rpc 29.0.1-213` |
| stellar-rpc deb | `https://apt.stellar.org/pool/focal/s/st/stellar-rpc_29.0.1-213_amd64.deb` — 200, 35 909 040 B, SHA256 `a93561dbe72515e289f48775852a6be9f0cb15a690d5be97d3fe36c3bf9aca16`, `Depends:` none, built once on focal (same Filename listed for jammy/noble; `pool/noble/...` path is 403). ar members: `control.tar.xz` |
| stellar-core deb (noble) | `https://apt.stellar.org/pool/noble/s/st/stellar-core_29.0.0-3589.4eb833373.noble_amd64.deb` — 200, 9 684 566 B, SHA256 `ff4bd2ddaf097b4d40dd93f8254bbffedebb7a027daecf66615bdf7b6c8fbc65`, ar member `control.tar.zst` |
| stellar-core deb (jammy) | `…/pool/jammy/s/st/stellar-core_29.0.0-3589.4eb833373.jammy_amd64.deb` 9 686 684 B (depends on apt.llvm.org's unversioned `libc++1`/`libunwind`; not used) |
| core `Depends` (noble) | `libsqlite3-0 (>=3.11), stellar-core-utils, libc++1-20 (>=1:20.1.2), libc++abi1-20, libc6 (>=2.38), libgcc-s1, libpq5 (>=9.5.2), libunwind-20` |
| Ubuntu 24.04 archive | `pool/universe/l/llvm-toolchain-20/libc++1-20_20.1.2-0ubuntu1~24.04.3_amd64.deb` exists → `libc++1-20` etc. install from stock noble `universe` (no apt.llvm.org needed on 24.04) [V directory listing] |
| `apt.stellar.org/SDF.asc` | 200 (the apt signing key; not needed — plain HTTPS download, optional SHA256 check against the Packages index) |
| Public SDF RPCs | all report `captiveCoreVersion: stellar-core 29.0.0`, `version: 29.0.0-…`, protocol 29 [V getVersionInfo] |

1. **Upstream source / route:** the only no-Docker, no-compile binary route is the SDF apt pool downloaded as plain files (what `apt-get install` fetches). GitHub release assets do not exist; Docker images (`stellar/stellar-rpc`) are
   forbidden by the rules. Go got the binaries via apt → docker extract → source build; only the apt files route survives.
2. **Latest rule:** version = newest `stellar-rpc` in `dists/noble/stable/binary-amd64/Packages.gz` (e.g. `29.0.1-213` → version `29.0.1`, tag `v29.0.1`, sourceLabel `stellar/stellar-rpc`).
   stellar-core = newest stable `stellar-core*.noble` in the same index (`29.0.0`). GitHub feed (`stellar/stellar-rpc`) is only the YAML fallback. Never use `testing`/`unstable`/`~vnext` (testing channel today already carries `30.0.0` builds).
   One program (`stellar-rpc`) holds both debs: `ClientReleaseResolver` can serve only one program id per network, and a GitHub-fed `stellar-core` program would pick the `v29.0.0-internal` release.
3. **Artifacts per arch:** x86_64 only.
   | on-disk name | resolves to | size |
   |---|---|---|
   | `stellar-rpc-amd64.deb` | `https://apt.stellar.org/<Filename of newest stellar-rpc>` | ~36 MB |
   | `stellar-core-noble-amd64.deb` | `https://apt.stellar.org/<Filename of newest stellar-core .noble>` | ~9.7 MB |
   | `captive-core-pubnet.cfg` (mainnet) | `https://raw.githubusercontent.com/stellar/go-stellar-sdk/master/ingest/ledgerbackend/configs/captive-core-pubnet.cfg` — 200, 6 097 B | 6 KB |
   | `captive-core-testnet.cfg` (testnet) | same dir `captive-core-testnet.cfg` — 200, 961 B | 1 KB |
   The `.deb` URLs cannot be a `urlTemplate` (apt build numbers/hashes in the file name) → marker templates `stellar-apt:stellar-rpc` / `stellar-apt:stellar-core:noble` + `StellarArtifactUrlResolver` (same hook as `EthereumGethArtifactUrlResolver`).
   `aarch64` flag handed to the resolver is ignored (the panel passes its own CPU arch); an arm64 host fails at Start with a clear message.
4. **Stable on-disk names:** above; arch is inferred from `amd64` in the name (`inferArchFromFileName`) so arm64 agents skip them.
5. **Shipped config template:** the SDK captive-core cfg per env (pinned to `master` like Go; `v0.7.2` tag is byte-identical to master today [V diff]; stellar-rpc 29.0.1 builds against `go-stellar-sdk v0.7.2` [V go.mod]). Patched on Start (ports), never edited under `public/`.
6. **Program id:** `stellar-rpc`; `clientConfig.program: stellar-rpc`.
7. **Requirements:** `logFile: logs/stellar-rpc.log`; no Java. Host libs via `hostPackages` (libc++1-20, libc++abi1-20, libunwind-20, libpq5, libsqlite3-0) + common `curl` (captive core's `HISTORY="curl -sf …"` commands). `dpkg-deb` must exist (Ubuntu: yes) — same dependency as the Polygon recipe.
8. **No Docker:** confirmed. Binaries come from `dpkg-deb -x` into `{node_dir}/.toolkit/deb-*` and are copied to `{node_dir}/bin/`. Package maintainer scripts/units are NOT run (no apt install) — nothing is written to `/etc/stellar` or `/usr/bin` [I: file paths inside the debs (`/usr/bin/stellar-rpc`, `/usr/bin/stellar-core`) follow Go's `findBin` which found them after `apt install`; confirm with `dpkg-deb -c` when implementing].

---

## 4. Fixed ports (`clients.yml`) — from `specs/PORTS.md`, block base 42560

| Env | Role | Port | Label | Bound by product? |
|---|---|---|---|---|
| mainnet | http | 42560 | Stellar RPC (JSON-RPC) | yes, `ENDPOINT = 0.0.0.0:42560` — **height probe port** |
| mainnet | p2p | 42562 | Captive core overlay P2P | yes, `PEER_PORT` in captive-core.cfg (tcp) |
| mainnet | core_query | 42563 | Captive core query (local) | yes, `STELLAR_CAPTIVE_CORE_HTTP_QUERY_PORT` |
| mainnet | admin | 42564 | Admin / metrics (local) | yes, `ADMIN_ENDPOINT = 127.0.0.1:42564` |
| testnet | http / p2p / core_query / admin | 42570 / 42572 / 42573 / 42574 | same | same |

1. All four are catalog-fixed (`config: required` = generated into the config). Offsets follow the plan (+0 http, +2 p2p, +3 local engine-style port, +4 metrics). Upstream defaults (8000 RPC, 11625 peer, 11626 core HTTP, 11628 query) are not used — they clash (Solana p2p 8000).
2. Roles for the panel: `http` = height/RPC; `p2p` = inbound peers (optional for function: captive core also dials out); `core_query` and `admin` are local-only (no firewall opening; `HostNodeDiagnostics`/port-check lists them as local).
3. Conflicts: none — nothing in `chains/*/clients.yml` is in 42560-42579 [V grep]; `ShippedPortCatalogTest` will confirm. stellar-core opens **no** implicit extra ports (its command HTTP port is disabled with `STELLAR_CAPTIVE_CORE_HTTP_PORT=0`) [I from SDK `HTTPPort` semantics, see 6]. Spare 42565-42569 / 42575-42579 stay free.

---

## 5. Disks & sizing

| Role | Media | What lives there |
|---|---|---|
| `blockchain` | ssd | `{role}/stellar_rpc.sqlite`, `{role}/captive-core/` (BUCKET_DIR) |

| Env | diskHint / full | archive (30 d) | CPU | RAM |
|---|---|---|---|---|
| mainnet | 512 GiB | 1536 GiB | 8 | 16 |
| testnet | 128 GiB | — | 4 | 8 |

Sources: SDF "Prerequisites" page [V]: 4-8 vCPU, 16 GB RAM, **350 GB for the 7-day window, +40 GB per extra retention day**, local SSD ≥3K IOPS, network disks work but hurt. mainnet 512 GiB = 350 GB + captive-core buckets + headroom
(Go hint was 512 as well); month = 350 + 23×40 ≈ 1.3 TB → 1536. testnet figures = Go facts [I]. Forward-only (never prune) has no ceiling (~40 GB/day mainnet) — the `diskNotes` say so.

---

## 6. Client config shape + PRODUCTION CONFIG

**Format:** `flags` (generated). One generated TOML for stellar-rpc, one patched copy of the SDK captive-core cfg; `clientConfig.bindings` mirror every literal (see `network.yml`).
No per-env template mapping — the generator switches on env (passphrase, archives, cfg name, datastore).

### 6.1 Files written on Start (all under node_dir)

| File | Content |
|---|---|
| `stellar-rpc.toml` | `ENDPOINT`, `ADMIN_ENDPOINT`, `NETWORK_PASSPHRASE`, `HISTORY_ARCHIVE_URLS`, `STELLAR_CORE_BINARY_PATH`, `CAPTIVE_CORE_CONFIG_PATH`, `CAPTIVE_CORE_STORAGE_PATH`, `DB_PATH`, retention/backfill/datastore, prod limits (exact text in `kotlin-design.md`) |
| `captive-core.cfg` | the synced `captive-core-{pubnet,testnet}.cfg` with every `HTTP_PORT` and `PEER_PORT` line removed and a root-level `PEER_PORT=<p2p>` inserted before the first `[[` table (Go `ApplyCaptiveCorePorts`) |
| `bin/stellar-rpc`, `bin/stellar-core` | unpacked from the debs |
| `bin/rpcnode-stellar-heal.sh` | `heal.sh.tmpl` |
| `.toolkit/*`, `logs/stellar-rpc.log`, `tmp/` | state, log, scratch |

Why strip `HTTP_PORT`/`PEER_PORT` [V SDK `ingest/ledgerbackend/toml.go` v0.7.2]: the SDK **rejects** a captive-core file whose `HTTP_PORT` differs from the value stellar-rpc passes (`STELLAR_CAPTIVE_CORE_HTTP_PORT`, here 0) — the shipped pubnet cfg has `HTTP_PORT=11626`; `PEER_PORT` in the file wins over the default (pubnet file ships `11725`), so it is set to the catalog p2p port.
Also verified: captive-core TOML keys the SDK understands (so no `TARGET_PEER_CONNECTIONS`/`MAX_*_PEER_CONNECTIONS` knobs are available): `BUCKETLIST_DB_INDEX_PAGE_SIZE_EXPONENT`, `BUCKETLIST_DB_INDEX_CUTOFF`, `BUCKETLIST_DB_MEMORY_FOR_CACHING`, `QUERY_THREAD_POOL_SIZE`, `QUERY_SNAPSHOT_LEDGERS`, `HTTP_QUERY_PORT`, `PEER_PORT`, `FAILURE_SAFETY`, `UNSAFE_QUORUM`, quorum tables.

### 6.2 Production settings (stellar-rpc v29.0.1 option table = `cmd/stellar-rpc/internal/config/options.go` [V raw source]; docs [V developers.stellar.org])

| Setting (toml key) | Stock default [V] | Value we write | Class | Why |
|---|---|---|---|---|
| `ENDPOINT` | `localhost:8000` | `0.0.0.0:{http}` | judgement | RPC product; Go bound loopback behind its own proxy, Kotlin chains (xrpl/avalanche) bind public. No TLS/auth/rate-limit/CORS options exist in stellar-rpc [V: not in option table] → proxy in front |
| `ADMIN_ENDPOINT` | disabled | `127.0.0.1:{admin}` | verified+judgement | option help: "should not be accessible from the Internet and does not use TLS"; serves `/metrics` + pprof [V docs Monitoring]. Judgement: enable it (Go did) for load diagnostics |
| `STELLAR_CAPTIVE_CORE_HTTP_PORT` | 11626 | `0` | verified | 0 disables core's HTTP (`/tx`, `/stop`, `/manualclose`, `/ll`… command port) [V option help "0 disables the HTTP server"]; Go set 0 too |
| `STELLAR_CAPTIVE_CORE_HTTP_QUERY_PORT` | 11628, `positive` required | `{core_query}` | verified | must differ from the unset default or two envs clash (Go heal comment) |
| `HISTORY_RETENTION_WINDOW` | 120960 (7 d, 17280 ledgers/day) | week 120960 / month 518400 / forward 4294967295 | verified (default) / judgement | option validates `positive`; uint32 max = never prune (Go) |
| `BACKFILL` + `SERVE_LEDGERS_FROM_DATASTORE` + `[datastore_config]` | false | mainnet+week/month: true + S3 lake | verified in docs/source | see 7; backfill requires datastore serving or startup fails [V option validate message] |
| `SOROBAN_FEE_STATS_RETENTION_WINDOW` / `CLASSIC_…` | 50 / 10, max 1000 | stock (not written) | verified | Go wrote 1000; the source comment says windows > 1000 cause O(n²) startup and give no better estimates → keep stock |
| `PREFLIGHT_WORKER_COUNT` | NumCPU | `8` | judgement | simulateTransaction is CPU-bound; ≈ mainnet profile cores (Go 16) |
| `PREFLIGHT_WORKER_QUEUE_SIZE` | NumCPU (docs example 8) | `32` | judgement (= Go) | |
| `PREFLIGHT_ENABLE_DEBUG` | **true** | `false` | verified | option help: "should not be enabled in production deployments" |
| `REQUEST_BACKLOG_GLOBAL_QUEUE_LIMIT` | 5000 | `10000` | judgement (= Go) | per-method limits stay stock: getHealth/events/network/version/latest-ledger/ledger-entries/transaction(s)/ledgers 1000, sendTransaction 500, simulateTransaction 100, getFeeStats 100 [V] |
| `MAX_EVENTS_LIMIT` / `DEFAULT_EVENTS_LIMIT` | 10000 / 100 | stock, written | verified | |
| `MAX_TRANSACTIONS_LIMIT` / `MAX_LEDGERS_LIMIT` | 200 / 200 | stock, written | verified | |
| `MAX_REQUEST_EXECUTION_DURATION` | 25s | stock, written | verified | per-method limits stock (getHealth 5 s, getEvents 10 s, getLedgers 10 s, sendTransaction 15 s, simulateTransaction 15 s) |
| `MAX_HEALTHY_LEDGER_LATENCY` | 30s | stock, written | verified | `getHealth` returns an **error** (not `unhealthy`) beyond this — drives the probe, see 10 |
| `INGESTION_TIMEOUT` | 50m | stock (not written) | verified | first bootstrap on mainnet must finish inside it |
| `LOG_LEVEL` / `LOG_FORMAT` | info / text | `info` / `text` | verified | |
| DB pool | — | — | verified | stellar-rpc exposes **no** DB-pool knob (SQLite, not in option table) |
| `LimitNOFILE` | — | `1048576` | judgement (= Go) | unit |
| captive core `BUCKETLIST_DB_MEMORY_FOR_CACHING` | 0 | not set | judgement | stock; a RAM-for-speed tradeoff the operator may set later through the cfg editor |

CLI flags win over the TOML (stellar-rpc order: defaults → env/flags → toml → env/flags again [V `SetValues`]) — so the toolkit's **Flag overrides** (`UnitFlagOverrides`, appended to `ExecStart`) can tune every option that has a CLI flag
(`max-events-limit`, `preflight-worker-count`, `preflight-worker-queue-size`, `history-retention-window`, `max-healthy-ledger-latency`, `ingestion-timeout`, `stellar-captive-core-http-query-thread-pool-size`, `log-level`…). TOML-only keys (the `request-backlog-*`, `max-*-execution-duration` family) cannot be overridden that way.
`config-path`, `*-port`, `endpoint`, `admin-endpoint`, `network-passphrase`, `*-path`, `history-archive-urls` are locked by `UnitFlagOverrides.isLocked` (tokens port/path/config/network).

### 6.3 Prod preset proposal (`admin/src/lib/prodPresets.ts`)

```ts
// stellar-rpc v29.0.1 option table (cmd/stellar-rpc/internal/config/options.go). Flags beat the generated stellar-rpc.toml.
// Judgement values: measure before relying on them. No TLS / auth / rate limit exists in stellar-rpc — front it with a proxy.
const STELLAR: ProdPreset = {
  flags: {
    // simulateTransaction is the CPU hog: size the pool to the cores you give the node, bound the waiting line
    'preflight-worker-count': '16',
    'preflight-worker-queue-size': '64',
    // a smaller getEvents page bounds response size / DB scan time per call (stock 10000)
    'max-events-limit': '5000',
    // captive-core query server threads for getLedgerEntries (stock = CPU count)
    'stellar-captive-core-http-query-thread-pool-size': '8',
  },
  prefix: '--',
  notes: [
    'Values are for a 16-core / 32 GB host; the generated file already runs 8 / 32 on the 8-core profile.',
    'ENDPOINT is public with no TLS, auth or rate limit: put a rate-limiting reverse proxy with a response cache in front.',
    'Keep the admin port (metrics + pprof) and the captive-core query port on 127.0.0.1 — they have no auth.',
    'Request-backlog and per-method execution limits are TOML-only (not flag-overridable).',
  ],
}
// PROD_PRESETS: stellar: STELLAR
```
Nothing here is benchmarked (class: judgement); flag names and stock defaults are verified in the v29.0.1 source.

### 6.4 Do not expose publicly (defaults chosen so)

- `ADMIN_ENDPOINT` (metrics, pprof, no TLS) — loopback only. [V option help]
- Captive core command HTTP port — disabled (`0`); query port `{core_query}` — firewall it (stellar-core binds query HTTP like its admin port: local unless `PUBLIC_HTTP_PORT=true`, which the SDK writes as false) [I].
- The JSON-RPC port itself is public on purpose; `simulateTransaction`/`getEvents` are the abuse vectors; CORS is whatever stellar-rpc hard-codes (no option).
- Never add `PUBLIC_HTTP_PORT=true`, `NODE_IS_VALIDATOR=true` or `RUN_STANDALONE` to the captive-core cfg.
- Heal wipes only `captive-core/`, never `stellar_rpc.sqlite`.

### 6.5 Horizon (deferred) — settings to carry when it is added [I, not verified here]
`DATABASE_URL` (separate role + database on the host PostgreSQL, TON-indexer pattern), `--captive-core-*` same as above, `--history-retention-count`, `--max-db-connections`, `--per-hour-rate-limit`, `--connection-timeout`. Verify against the `stellar-horizon` flag table of the then-current release before writing the Kotlin; none of it is in the v1 files.

---

## 7. Snapshot policy (REQUIRED research — done)

**Decision: `snapshot: never` for mainnet and testnet. No resolver, no `snapshots:` block, no CDN mirror.**

Why, by candidate:

| Candidate | Exists? | Verdict |
|---|---|---|
| Database snapshot of `stellar_rpc.sqlite` | **None found**: not in the SDF admin guide (Prerequisites/Installing/Configuring/Monitoring/Data lake pages [V]), no public bucket/CDN found [I: absence of evidence] | nothing to restore |
| Captive-core bucket snapshot | none; captive core always **catches up from the history archives** on start (it needs only the latest checkpoint's buckets, not full history) | built in, no toolkit step |
| **Public history archives** (SDF) | mainnet `https://history.stellar.org/prd/core-live/core_live_001/` (+ `_002`, `_003`); testnet `https://history.stellar.org/prd/core-testnet/core_testnet_001/` (+ `_002`, `_003`) — `.well-known/stellar-history.json` 200, `currentLedger 64927487` / `5179903`, `server: stellar-core 29.0.0` [V]. Plus community archives inside the captive-core cfgs (publicnode, lobstr, franklintempleton, obsrvr, creit, blockdaemon…) | used by captive core for the catch-up (minutes, a few GB [I]); the node can't serve RPC until ingest is up |
| **AWS Open Data ledger data lake** (galexie output, "datastore") | **mainnet only**: `s3://aws-public-blockchain/v1.1/stellar/ledgers/pubnet/` region `us-east-2`, anonymous read [V S3 list + GET]. `.config.json`: `{"networkPassphrase":"Public Global Stellar Network ; September 2015","version":"1.0","compression":"zstd","ledgersPerBatch":1,"batchesPerPartition":64000}` [V]. Layout: one object per ledger `…/FC21C3FF--64896000-64959999/FC2148CD--64927538.xdr.zst` (hex key = `0xFFFFFFFF - seq`, so listings are newest-first), 170-240 KB each, newest object timestamp within ~5 s of the tip [V]. **Testnet: 404** (no public lake). A GCS bucket `sdf-ledger-close-meta` exists but denies anonymous access [V 403]. | the real fast-bootstrap source — consumed by **stellar-rpc itself** (`BACKFILL`), not by the toolkit |

How the datastore path works [V docs "Data lake integration" + "Configuring" + source]: stellar-rpc ≥ 25.1.0 `BACKFILL = true` fills the DB with a `HISTORY_RETENTION_WINDOW` window **synchronously on startup, before live ingestion**; it requires
`SERVE_LEDGERS_FROM_DATASTORE = true` (startup error otherwise); S3 type supports anonymous access to public buckets (SDK `s3.go`: falls back to `AnonymousCredentials` when no AWS credentials exist) — so no keys are needed. Config:

```toml
SERVE_LEDGERS_FROM_DATASTORE = true
BACKFILL = true
[datastore_config]
type = "S3"
[datastore_config.params]
destination_bucket_path = "aws-public-blockchain/v1.1/stellar/ledgers/pubnet"
region = "us-east-2"
[datastore_config.schema]
ledgers_per_file = 1
files_per_partition = 64000
[buffered_storage_backend_config]
buffer_size = 100
num_workers = 10
retry_limit = 3
retry_wait = "5s"
```
(doc example values for buffer/worker/retry — [V docs]). Cost: 7 days = 120 960 ledgers × ~200 KB ≈ **24 GB** download (month ≈ 100 GB), anonymous S3 egress from us-east-2; time = ingest-bound, "significantly faster" since v28.0.0 [V changelog], hours not days [I]. Side benefit: with `SERVE_LEDGERS_FROM_DATASTORE` `getLedgers` can also answer ledgers older than the local window from the lake [V docs "verify" step]. **RPC serving during backfill:** the backfill is synchronous before live ingest, so `getHealth` errors (`data stores are not initialized`) until it ends [I]; the wizard must not treat the node as ready — the height probe returns `height=0, syncing=true` in that state.

Mapping to the product: this is an **install option (`history`), not a toolkit snapshot** — same pattern as XRPL (`xrpl_history`, `snapshot: never`). `week`/`month` on mainnet ⇒ `BACKFILL=true`; testnet and `forward` ⇒ `BACKFILL=false` (no lake / never-prune would try to backfill 64 M ledgers = ~12 TB — **hard rule: never emit BACKFILL with the forward window**).
Destination directory/`destLeaf`: n/a (no extraction). Resolver sketch (only if a toolkit-level snapshot is ever wanted): none exists today; if SDF ever publishes DB dumps, copy `BscSnapshotResolver`/`TronSnapshotResolver` — nothing to build now.
`snapshot: never` keeps the wizard's Snapshot step hidden for both envs; the `history` picker (Node type step) carries the choice.

---

## 8. Start recipe (`chains/stellar/infrastructure/start`)

1. Process: native binary under node_dir: `{node_dir}/bin/stellar-rpc --config-path {node_dir}/stellar-rpc.toml` (foreground; stellar-rpc forks `bin/stellar-core` as captive core; `KillMode=control-group`).
2. Args: launch args `--toolkit-env=`, `--toolkit-history=`, `--toolkit-blockchain=` (+ `--toolkit-extra-flags=` added by the panel).
3. No archive extract by the generic helper (`extractArchiveGlob=null`): the starter unpacks the two `.deb`s itself with `dpkg-deb -x` (Polygon precedent).
4. `NodeLaunchSpec.kind = "binary"`, `entry = "bin/stellar-rpc"`, `logFile = "logs/stellar-rpc.log"`; height kind `stellar_rpc`, port role `http`.
5. Class `StellarNodeStart : ChainNodeStart`.

## 9. Host process start (`.../proc`)

1. Beyond shared support: unpack debs (re-unpack when the deb SHA256 changed), check `stellar-core version` runs (missing libs → clear "run Host deps" error; also yields the protocol), write cfg/toml/heal script, `Environment=TMPDIR={node_dir}/tmp`, `ExecStartPre=-bin/rpcnode-stellar-heal.sh`.
2. `StellarNodeProcessStarter`; everything under node_dir (nothing in `/opt/stellar`, `/etc/stellar`, `/usr/bin`). No `User=nodeop` (Kotlin units run as the agent user, like xrpl).
Heal (Go `JournalNeedsReset`/`resetCaptiveCore`, ported to a script because there is no journal tail in the unit): markers `invalid captive core toml | validators.N. | address already in use | permission denied | failed to create storage directory | unsupported ledger version | upgrade this stellar-core | error running stellar-core catchup` in the log lines written since the last start → wipe `captive-core/`, max once per 30 min. Not ported: `PatchBinaryPaths`/vnext handling (futurenet), TOML patch-in-place (toml is regenerated every Start), `fuser -k` port freeing (control-group kill covers it).

---

## 10. Local height (`.../http`, host)

- **Endpoint:** `POST http://127.0.0.1:{http}/` `{"jsonrpc":"2.0","id":1,"method":"getHealth"}` — **omit `params`** (stellar-rpc answers `-32602` to `params:{}`; Go `JSONRPCBody`).
- **Healthy** [V live response of a public node]:
  `{"jsonrpc":"2.0","id":1,"result":{"status":"healthy","latestLedger":64927483,"latestLedgerCloseTime":"1791923197","oldestLedger":64806524,"oldestLedgerCloseTime":"1791318402","ledgerRetentionWindow":120960}}`
  → height = `latestLedger`, `syncing=false`.
- **Not healthy** [V source `get_health.go`]: getHealth returns a JSON-RPC **error** (code -32603): `data stores are not initialized[: …]` (ingest/backfill not finished) or `latency (45s) since last known ledger closed is too high (>30s)` (behind).
  Probe rule: error contains `latency` → call `getLatestLedger` (`{"result":{"id":"…","protocolVersion":29,"sequence":64927486,"closeTime":"1791923212","headerXdr":"…","metadataXdr":"…"}}`, large payload) and report `height = sequence, syncing = true`;
  error contains `not initialized` → `height = 0, syncing = true`; connection refused/timeout → `null`.
- Failure → `null`, never throws. `syncPct = null` (panel falls back to height vs public tip). Sync % from retention-window coverage is deliberately **not** reported: the Go rule (synced only if `oldestLedger ≤ 1`) cannot be met by stellar-rpc.
- Class `StellarNodeHeightProbe : HostNodeHeightProbe`.

## 11. Public network tip

- Same protocol: `getHealth.latestLedger` (fallback `getLatestLedger.sequence`) against `publicTip.urls`. All URLs below answered `getHealth` healthy on 2026-10-13 [V]: mainnet `https://mainnet.sorobanrpc.com` (ledger 64927483), `https://rpc.lightsail.network`, `https://soroban-rpc.mainnet.stellar.gateway.fm`, `https://rpc.ankr.com/stellar_soroban`; also OK `https://stellar.api.onfinality.io/public`;
  testnet `https://soroban-testnet.stellar.org` (5179923), `https://soroban-rpc.testnet.stellar.gateway.fm`, `https://rpc.ankr.com/stellar_testnet_soroban`; futurenet (deferred) `https://rpc-futurenet.stellar.org`. Dead/unusable: `stellar-soroban-public.nodies.app` (404), `soroban.rpc.blockdaemon.com` (no connection). Go's Horizon fallback (`history_latest_ledger`) is dropped (second protocol; five JSON-RPC endpoints are enough).
- Class `StellarNetworkTipProbe` reuses `StellarRpc` (same request builder + parser as the height probe).
- `sync → active` at the default tip lag ≤ 3 ledgers (≈15 s). A healthy local node is always < 30 s behind (otherwise getHealth errors), so lag ≤ 3 is reachable; expect occasional flapping of 1-2 ledgers between the two reads [I].

## 12. Lifecycle & panel status

1. After Start: `sync` (backfill / catch-up / error responses) → `active` when `getHealth` healthy and lag ≤ 3. Fits.
2. No extra statuses. A long first phase is normal: captive-core catch-up (minutes) [I] + backfill (hours on mainnet) [I].
3. Admin: header height poll while `sync`/`active` (existing).

## 13. Install options (wizard)

1. Group `history` ("History window", Node type step): `week` (default, 120 960 ledgers ≈ 7 d, mainnet backfilled) · `month` (518 400 ledgers ≈ 30 d, mainnet backfilled, ~1.3 TB) · `forward` (never prune from the first start — Go product behaviour, unbounded disk, no backfill).
2. Default `week` (stock SDF window, matches `diskHintGiB`). **Decision for the owner:** Go defaulted to never-prune (`MaxUint32`); that grows ~40 GB/day and outgrows 512 GiB in ~12 days → the Kotlin default is the safe stock window.
3. Mapping: binding `history` (install_option) → `HISTORY_RETENTION_WINDOW` + `BACKFILL` (+ datastore block) in the generated toml. Validation: unknown value → `week`; testnet ignores backfill.

---

## 14. Wiring checklist

Resources (`app/src/main/resources/chains/stellar/`): `network.yml`, `clients.yml`, `node.service.tmpl`, `heal.sh.tmpl` (drafts in this folder).
Catalog: `NetworkId.STELLAR`; no new `EnvId`. Chain package + wiring + tests + admin touch list: see `kotlin-design.md`.
`ShippedPortCatalogTest` must pass; add `stellar` to `YamlNetworkFactsRepositoryTest` + `NetworksRoutesTest` id lists; copy this intake to `app/docs/networks/stellar-intake.md` when approved.

---

## 15. Open risks

| Risk | Impact | Mitigation |
|---|---|---|
| SDF apt publishes **amd64 only** | no arm64 hosts | Start fails with a clear message; later a `build:` block (Go + Rust + C++) or wait for SDF |
| stellar-core noble build needs glibc 2.38 + libc++ 20 | only Ubuntu 24.04+ works; jammy/Debian need LLVM-repo libs (jammy build exists and is a one-line artifact change + apt.llvm.org setup) | state it in `diskNotes`; host-deps fails visibly elsewhere |
| `.deb` URLs need the apt index (no stable URL) | download fails if apt.stellar.org is down/changes layout; no signature check (plain HTTPS) | resolver caches; verify SHA256 from the index (`Size`/`SHA256` fields available) — optional hardening |
| Protocol upgrades (SDF mainnet vote) | a node on an old stellar-core stops ingesting (`unsupported ledger version`) | heal wipes buckets but the real fix is **client update**; the update flow exists; alert via Clients page |
| `BACKFILL` end-to-end behaviour not run (config verified from docs/source only) | RPC unhealthy for hours; wrong datastore keys would abort start | test on a testnet-sized host first; fall back to `BACKFILL=false` (live-only) via option |
| Public JSON-RPC bind without TLS/auth/rate limit | abuse of `simulateTransaction`/`getEvents`, open CORS | proxy in front (prod notes); preset; consider binding loopback by default if the product always fronts it |
| Retention default differs from Go (week vs never-prune) | product expectation of "full history" | owner decision (13.2); `forward` option keeps the Go behaviour; true history before the node's start only via the data lake `getLedgers` |
| `stellar/stellar-core` tag `v29.0.0-internal` is a normal (non-prerelease) release | GitHub-feed fallback could pick it | the primary version feed is the apt index; never use GitHub for core |
| captive-core cfg tracked on `master` | validator list changes between installs | acceptable (Go did the same); `v0.7.2` pinned URL available if reproducibility is wanted |
| path inside the debs not inspected (full download not done) | extraction path guess wrong | `dpkg-deb -c` on first implementation; starter searches by file name (`findNamed`, as Polygon) |
| captive-core `HISTORY="curl -sf …"` needs `curl` on the host | catch-up fails without it | `curl` is in `COMMON_HOST_PACKAGES` |
| heal marker `permission denied` is broad | an unrelated log line could wipe buckets (rate-limited 1 / 30 min) | accepted; wipe is non-destructive for the RPC DB |

## Review sign-off
(left blank — to be filled by reviewers)
