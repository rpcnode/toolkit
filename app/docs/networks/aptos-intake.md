# Network intake - Aptos

Status: **draft** (ready for review). Filled from the Go sibling `toolkit-go/internal/networks/aptos/` plus live checks done 2026-10-13.
Legend: **[V]** verified now (command / source named), **[I]** inferred or judgement, **[Go]** fact ported from the Go sibling.

## Review table

| Section | Status | Notes |
|---|---|---|
| 1 Product scope | Needs decision | default sync mode (fast vs genesis), see risks R1 |
| 2 Environments | OK | mainnet, testnet (Testnet V2) |
| 3 Artifacts | OK | x86_64 only, flat tgz, genesis/waypoint from aptos-networks |
| 4 Ports | OK | central plan 41060-41089 |
| 5 Disks | Needs decision | numbers are estimates, R2 |
| 6 Client config | OK | fullnode.yaml generated, flags format |
| 7 Snapshot | OK | `never` v1, no usable snapshot exists |
| 8-9 Start / proc | OK | |
| 10-11 Height / tip | OK | `GET /v1` -> `ledger_version` |
| 12-13 Lifecycle / options | Needs decision | tip-lag promotion, bootstrap option |
| 15 Risks | see below | |

## 0. Meta

| Field | Answer |
|---|---|
| Network id | `aptos` |
| Display label | Aptos |
| Author | toolkit (port from Go facts) |
| Date | 2026-10-13 |
| Status | draft |

**Go sibling:** exists (`toolkit-go/internal/networks/aptos/`). Portable facts: release asset name, fullnode.yaml shape, port-collision pins (admin/backup), archival pruner pins, lag-based sync logic, public tip REST URLs. NOT portable as-is: S3 restore (dead), `/opt` + `/etc` provision paths, `nodeop` user, 6180/6182 + 8080/8081 + 9101/9102 ports, Go public-RPC proxy ports 42890/42891.
Go facts: `Chain()` = roles `state` (leaf `db`, 97 %) + `index` (leaf `index`, 3 %); hints mainnet 2048 GiB / 8 cores / 32 GiB, testnet 512 GiB / 4 / 16; `Snapshot: never`; unit `aptos-node -f fullnode.yaml`, `LimitNOFILE=1048576`, `TimeoutStopSec=90`; install options `bootstrap = genesis|restore`.

## 1. Product scope

1. **Why:** Aptos REST RPC (public fullnode) for operators. Ported because the Go toolkit shipped it and the admin already references it.
2. **MVP (v1):** Clients download (tarball + genesis + waypoint), disk role, ports, start unit, height via REST `ledger_version`, public tip, `sync -> active`. Install option `bootstrap = fast | genesis`.
   **Out of scope v1:** `restore` (bootstrap-db from backups, see section 7 for why it needs a separate phase), indexer / indexer-grpc, validator / VFN roles, telemetry opt-out, aarch64 (no upstream build).
3. **Pin-only?** No - GitHub release assets exist [V].
4. **One env per host?** `false` - mainnet and testnet use disjoint ports; the two implicit loopback binds (admin, backup) are given unique ports too.

## 2. Environments

| Env id | Label | Production? | Notes |
|---|---|---|---|
| `mainnet` | Aptos Mainnet | yes | chain_id 1 [V] |
| `testnet` | Aptos Testnet | test | chain_id 2 [V]. **Testnet V2**: the chain was reset and relaunched on 2026-10-06 (genesis in `aptos-networks/testnet-v2/`; old data is incompatible) [V: aptos-networks git log + README + live epoch 858]. Needs aptos-node >= 1.49.4 [V: README]. |

Same binary family. Genesis + waypoint differ per env. No env without a public tip. (Aptos also has devnet, wiped weekly - not shipped.)

## 3. Client binary and artifacts

1. **Upstream:** GitHub `aptos-labs/aptos-core` releases. The repo also publishes `aptos-cli-v*` (7 assets) so the tag prefix `aptos-node-v` is mandatory [V: /releases API].
2. **Latest rule** (`AptosClientReleaseResolver`):
   - mainnet: newest release with tag prefix `aptos-node-v`, stable only (`includePrerelease=false`). The shared GitHub client skips GitHub prereleases and tags ending `-rc` / `-hotfix-rc`; `-hotfix` tags are kept. Today -> `aptos-node-v1.49.6-hotfix` [V: API]. Cross-check: live mainnet fullnode REST reports `git_hash 433c185c...` = the commit in that release's notes [V].
   - testnet: newest tag with prefix `aptos-node-v` of any kind (`includePrerelease=true`). Aptos publishes testnet candidates as `...-rc` / `...-hotfix-rc` tags (release titles `[Testnet] ...`), mainnet as the non-rc tags (`[Mainnet] ...`) [V: release names]. Live testnet runs `git_hash 4c17e93...` = v1.49.5-rc [V]. "Newest of any kind" is a safe superset (never older than the network) and equals v1.49.6-hotfix today, which is newer than the rc the network runs.
   - Gotcha [V]: some prerelease entries have 0 assets (`aptos-node-v1.44.2-rc`); the newest one today is fine, but a bare release can appear.
3. **Artifacts:** `aptos-node-performance-ubuntu-22.04.tgz` (and `-24.04.tgz`), URL `https://github.com/aptos-labs/aptos-core/releases/download/{tag}/aptos-node-performance-ubuntu-22.04.tgz`. HEAD 200 for v1.49.6-hotfix (391,296,583 B), v1.49.5-rc (390,321,071 B), v1.49.5 (390,322,816 B) [V: curl -I]. **No aarch64 asset** in any of the last 100 releases (assets seen: `aptos-node[-performance]-ubuntu-{22.04,24.04}.tgz`, one `.zip`) [V]. We pick 22.04 (glibc 2.35, runs on 22.04+ and 24.04); the 24.04 build needs glibc 2.39 [I].
4. **On-disk name:** `aptos-node-performance-ubuntu-22.04.tgz` (already versionless). No `nameAarch64`.
5. **Archive layout:** flat - one `aptos-node` executable (1,928,943,072 B = 1.8 GiB) at the archive root [V: `curl | tar -tzvf`]. The starter extracts only that member straight into `{node_dir}/bin/` (never `/tmp`, 1.8 GiB would not fit tmpfs on small hosts).
6. **Configs (clients.yml `configs:`):** `genesis.blob` and `waypoint.txt` from `aptos-labs/aptos-networks`:
   | env | genesis.blob | waypoint.txt |
   |---|---|---|
   | mainnet | `main/mainnet/genesis.blob` 996,225 B sha256 `d481ae99f8d6...c876a5` | `main/mainnet/waypoint.txt` 75 B `1638030465:c2b8b050...a5476e` (2024 waypoint, valid) |
   | testnet | `main/testnet-v2/genesis.blob` 1,461,149 B sha256 `38fe1af71697...13ae381` | `main/testnet-v2/waypoint.txt` 66 B `0:ea4bcba7...1b62` (genesis waypoint) |
   All four fetched and hashed now; hashes equal the Go manifest (mainnet) and the README (testnet) [V]. **`main/testnet/...` is a git symlink and returns HTTP 404 on raw.githubusercontent.com; the Go sibling's testnet URLs are dead** [V]. `testnet-v1/` is the decommissioned chain.
   `digest_key.bin` / `pp.bin` in the networks repo are validator-only consensus blobs (`consensus.digest_key_blob_path`, "Required for validators") - not needed for a fullnode [V: config source v1.49.5].
7. **Program id:** `aptos-node` (`clientConfig.program`).
8. **Requirements:** `logFile: logs/aptos.log`. Host: x86_64 Linux, glibc >= 2.35, `tar`. No Java.
9. **No Docker:** confirmed; tarball only.

## 4. Fixed ports (central plan, base 41060)

| Env | Role | Port | Label | Bound by product? |
|---|---|---|---|---|
| mainnet | http | 41060 | REST API | yes `api.address` 0.0.0.0 |
| mainnet | p2p | 41062 | Public network P2P (tcp) | yes `listen_address` |
| mainnet | metrics | 41064 | Inspection service | yes, 127.0.0.1 |
| mainnet | admin | 41067 | Admin service (disabled) | yes, 127.0.0.1 |
| mainnet | backup | 41068 | Storage backup service | yes, 127.0.0.1 |
| testnet | http / p2p / metrics / admin / backup | 41070 / 41072 / 41074 / 41077 / 41078 | same | same |

- Upstream defaults NOT used: 8080 (REST, also used implicitly by Sui), 6180 (P2P), 9101 inspection, 9102 admin, 6186 backup [V: config source]. **The admin service binds its port even when `enabled: false`** [V: `aptos-admin-service/src/server/mod.rs` `Server::bind` before the enabled check] and the backup service always binds, so both get unique loopback ports - this is why they are in `clients.yml`.
- No implicit extra listeners (indexer-grpc and tokio-console are off by default [V: config source]).
- Clash check: none of 41060-41089 appears in any shipped `clients.yml` [V: grep]; `ShippedPortCatalogTest` must stay green.

## 5. Disks and sizing

`diskRoles`: **one** role `state` (nvme) -> `base.data_dir`; RocksDB at `<state>/db`. The Go `index` role was never wired into the node config (only mkdir) so it is dropped; `db_path_overrides` (second disk for `ledger_db`) is possible later [I: field exists, needs sharding which is default on].

| Env | diskHintGiB | fullNodeGiB (fast) | archiveGiB (genesis) | cpu | RAM | Source |
|---|---|---|---|---|---|---|
| mainnet | 3072 | 1024 | 12288 | 16 | 64 | vendor reference spec 48 thr / 128 GB / 3.0 TB NVMe 60K IOPS for production PFNs [V: aptos.dev node-requirements]; PFN pruned "several 100's of GB" [V: pfn-requirements]; archive = Aptos backup size ~0.8-1.3 GiB per 1M versions x 7.6B versions = 7-9 TiB compressed [V: sampled 5 transaction backups via GCS API; total extrapolated = I]; CPU/RAM are judgement between Go (8/32) and vendor (48/128) |
| testnet | 512 | 256 | 512 | 8 | 32 | Go 512 GiB; chain is 55.8M versions old [V], so tiny - numbers are headroom [I] |

Go mainnet hint (2048 GiB) cannot hold a full-history node. The doc page "From Backup" lists "Mainnet 1 TB" - stale [I].

## 6. Client config shape and production config

**Format:** `flags` - there is no CLI flag surface; the starter writes `{node_dir}/fullnode.yaml` and the unit is `aptos-node -f <yaml>`. `network.yml` bindings document each yaml key; values are identical to `AptosUnitBodies` (tested). One template for both envs.

### 6a. Written config (verified against aptos-core `aptos-node-v1.49.5`, `deny_unknown_fields` structs)

```yaml
base: { role: "full_node", data_dir: <state>, waypoint: { from_file: <node_dir>/waypoint.txt } }
execution: { genesis_file_location: <node_dir>/genesis.blob }
api: { enabled: true, address: "0.0.0.0:<http>", runtime_worker_multiplier: 2, max_gas_view_function: 2000000,
       wait_by_hash_max_active_connections: 100, content_length_limit: 8388608, transaction_submission_enabled: true }
full_node_networks: [ { network_id: "public", discovery_method: "onchain", listen_address: "/ip4/0.0.0.0/tcp/<p2p>",
                        max_inbound_connections: 50, max_outbound_connections: 5 } ]
inspection_service: { address: "127.0.0.1", port: <metrics> }
admin_service: { enabled: false, address: "127.0.0.1", port: <admin> }
state_sync: { state_sync_driver: { bootstrapping_mode: DownloadLatestStates | ExecuteOrApplyFromGenesis,
                                   continuous_syncing_mode: ExecuteTransactionsOrApplyOutputs } }
storage: { backup_service_address: "127.0.0.1:<backup>",
           storage_pruner_config: <genesis mode only: ledger_pruner_config/state_merkle_pruner_config/epoch_snapshot_pruner_config each { enable: false }> }
```
Field names and value spellings [V] from `api_config.rs`, `base_config.rs`, `storage_config.rs`, `state_sync_config.rs`, `network_config.rs`, `inspection_service_config.rs`, `admin_service_config.rs`. `-f/--config` is the config flag [V: `aptos-node/src/lib.rs`]. Not runtime-tested here (no Linux host) [I].

### 6b. Capacity / production knobs

| Config key | Source | Value | Applied where | Status |
|---|---|---|---|---|
| `bootstrap` | install_option | fast (default) / genesis | yaml `bootstrapping_mode` + pruners | [V] vendor modes; default is a decision (R1) |
| `base.data_dir` | disk_role_dir state | - | yaml | [Go] |
| `api.address` host | literal | 0.0.0.0 | yaml | [I] Go used 127.0.0.1 behind its own proxy; Kotlin chains expose RPC directly. Aptos says to put auth / rate limiting in front [V: pfn-requirements] |
| `inspection_service.address` | literal | 127.0.0.1 | yaml | [Go] never public [V: docs] |
| `admin_service.enabled/address` | literal | false / 127.0.0.1 | yaml | [Go] |
| `storage.backup_service_address` | catalog_port + loopback | 127.0.0.1 | yaml | [Go] |
| pruners (x3) | literal when bootstrap=genesis | false | yaml | [Go] [V: data-pruning doc, "unbounded growth"] |
| `continuous_syncing_mode` | literal | ExecuteTransactionsOrApplyOutputs | yaml | [Go] |
| `api.runtime_worker_multiplier` | literal | 2 | yaml | [V] vendor default, written for visibility |
| `api.max_gas_view_function` | literal | 2000000 | yaml | [V] default |
| `api.wait_by_hash_max_active_connections` | literal | 100 | yaml | [V] default |
| `api.content_length_limit` | literal | 8388608 | yaml | [V] default 8 MiB |
| `api.transaction_submission_enabled` | literal | true | yaml | [V] default |
| `max_inbound_connections` / `max_outbound_connections` | literal | 50 / 5 | yaml | [V] defaults (consts `MAX_INBOUND_CONNECTIONS`, `MAX_FULLNODE_OUTBOUND_CONNECTIONS`) |
| `LimitNOFILE` | literal | 1048576 | unit | [Go] [V] docs ask >= 999999 |

Go set no capacity knob except `LimitNOFILE`; no vendor "production RPC flags" exist beyond the yaml fields above. Defaults are written explicitly so Start shows them and the preset can raise them.

### 6c. Prod preset proposal (`admin/src/lib/prodPresets.ts`)

```ts
aptos: {
  prefix: '',
  flags: {
    // REST worker threads = multiplier x cores (default 2)
    'api.runtime_worker_multiplier': '4',
    // concurrent wait_by_hash (default 100) - many clients waiting for tx commits
    'api.wait_by_hash_max_active_connections': '500',
    // more upstream peers speed up syncing (default 5)
    'p2p.max_outbound_connections': '10',
  },
  notes: [
    'Checked against aptos-node v1.49.5 config structs (api_config.rs / network_config.rs). Values are judgement starting points - measure.',
    'Keys are fullnode.yaml paths, regenerated on every Start; only these keys are accepted. Set api.transaction_submission_enabled=false for a read-only endpoint.',
    'The REST API has no per-IP limits - put a rate-limiting proxy in front. Inspection / admin / backup services stay on 127.0.0.1.',
  ],
},
```
Allowed override keys in the generator (allowlist, anything else dropped): `api.runtime_worker_multiplier`, `api.max_gas_view_function`, `api.wait_by_hash_max_active_connections`, `api.transaction_submission_enabled`, `p2p.max_inbound_connections`, `p2p.max_outbound_connections`. (`network.*` cannot be used: `UnitFlagOverrides.isLocked` rejects the token `network`.) The three numbers are [I]; the field names and defaults are [V].

### 6d. Do not expose publicly (defaults chosen)
- inspection service (metrics, peer / identity / system info) and admin service: 127.0.0.1 [V: Aptos docs "never expose"].
- storage backup service: 127.0.0.1 (stock default is loopback).
- REST API: 0.0.0.0 is what the catalog needs; Aptos recommends auth / rate limiting [V]. `failpoints_enabled` stays default false [V].
- P2P port must be reachable from the Internet for inbound peers [V: docs]; it is optional for serving RPC.
- Remote telemetry (logs/metrics/system info to Aptos Labs) is ON by default (`logger.enable_telemetry_remote_log: true` [V: logger_config.rs]; env `APTOS_DISABLE_TELEMETRY=true` disables [V: docs]). Left on for Go parity; **decision R6**.

## 7. Snapshot policy

**There is no official downloadable Aptos DB snapshot, and no working community one. Verified 2026-10-13:**
- Aptos docs ("Bootstrap from a Snapshot") point only to community providers and say mainnet snapshots are "not recommended" (unverified by the software) [V].
- BWare Labs `bwarelabs.com/snapshots/aptos` -> Cloudflare error 1016 (origin gone); the Blast API docs page says the service "is no longer available" [V].
- Polkachu `polkachu.com/aptos_snapshots/aptos` -> 404, `snapshots.polkachu.com/snapshots/aptos/aptos_<ver>.tar.lz4` -> 404, and `aptos` is absent from `polkachu.com/api/v2/chains` [V].
- The only official recovery path is **Aptos backups** restored with `aptos node bootstrap-db`:
  - Public buckets (read-only, anonymous, listable over HTTPS) `aptos-mainnet-backup-usce1` and `aptos-mainnet-backup-euwe4`, sub-dir `e1` [V: `storage.googleapis.com/storage/v1/b/<bucket>/o?prefix=e1/metadata/`]; metadata refreshed 2026-10-12 [V]. 420 metadata files, 7,636 `transaction_*` backups starting at version 0, 17,624 epoch-ending, 10,589 state-snapshot prefixes [V].
  - The S3 config in `aptos-networks/mainnet/backups/s3-public.yaml` (what the Go restore used) points to bucket `aptos-mainnet-backup-backup-6addc21b`, which returns **NoSuchBucket** [V] - **the Go restore flow is broken**. `gcs.yaml` / `gcs-1.yaml` need `gcloud`.
  - Testnet: backups "Discontinued" in the docs [V]; `testnet-v2/` has no `backups/` dir [V]. No testnet restore.
  - Format: gzip chunk files + manifest + metadata, cryptographic proofs, verified by the CLI (not tar). Size: full history from version 0 ~7-9 TiB compressed (derived from samples), i.e. same order as the archive disk estimate [I].
  - CLI: `aptos-cli-v9.6.0` assets `aptos-cli-9.6.0-Linux-{x86_64,aarch64}.zip` (49.7 / 47.0 MB) [V]. Command: `aptos node bootstrap-db --ledger-history-start-version 0 --target-version use_the_largest_version_in_backup --command-adapter-config <yaml> --target-db-dir <state>/db`; needs open file limit > 999999 [V: docs].
  - The node cannot serve RPC while restoring (DB is written offline, node starts afterwards) [I]. After restore start with `bootstrap=genesis` semantics (pruners off) and p2p catches up the remainder.

**Decision:** `snapshot: never` for mainnet and testnet in v1 (nothing for the toolkit to download; no `clients.yml snapshots:` block, no `AptosSnapshotResolver`). Fast mode is the vendor's own answer to "history already pruned" [V: state-sync doc, "usually completes within a few hours"].

**Phase 2 sketch (mainnet only, `snapshot: optional`):** the BSC / Sui pattern - the toolkit does not download an archive, the host agent runs a tool.
- `network.yml`: `snapshot: optional`, `snapshotTypes: [{id: backup, kind: archive, label: "Official backup (full history)", hint: "aptos node bootstrap-db from Aptos public GCS backups", diskGiB: 12288, destLeaf: db, default: false}]`.
- `chains/aptos/infrastructure/http/AptosSnapshotResolver : SnapshotResolver` returns `SnapshotArchive(url = "aptos-backup://mainnet", streamUnpack = false, sizeBytes = null)` for env mainnet type `backup`, null otherwise (same shape as `SuiSnapshotResolver` / `BscSnapshotResolver`; companion `SCHEME`, `isOfficialUrl`, `parse`).
- Agent side (outside the chain package, same hook Sui/BSC use in `SnapshotDownloadUseCases`): `AptosBackupRestoreRunner` installs the CLI from the `aptos-cli-v*` zip into `{node_dir}/bin/aptos`, writes `{node_dir}/.toolkit/aptos-backup-adapter.yaml` (a curl adapter for the GCS bucket: `open_for_read: curl -fsS https://storage.googleapis.com/$BUCKET/$SUB_DIR/$FILE_HANDLE | gzip -cd`; `list_metadata_files` = paged JSON listing of `e1/metadata/` printed as `metadata/<name>` - one page of 1000 covers all 420 files) so neither `gcloud` nor `aws` is needed, runs `bootstrap-db` into `<state>/db`, writes marker `.snapshot-ready`. Adapter commands are [I] (untested).
- Needs the Install option `bootstrap=genesis` pre-selected, because the DB then holds full history.

## 8. Start recipe

1. **Process model:** binary `bin/aptos-node` under node_dir, systemd unit `rpcnode-aptos-<env>.service` via `HostNodeLaunchSupport.installCustomUnits` (no companions).
2. **Args to the agent:** `--toolkit-env=<env>`, `--toolkit-bootstrap=<fast|genesis>`, `--toolkit-state=<abs state dir>` (omitted -> node_dir). Real process args: `bin/aptos-node -f <node_dir>/fullnode.yaml`.
3. **Extract:** `NodeLaunchSpec.extractArchiveGlob = "aptos-node-performance-ubuntu-*.tgz"`, `normalizeDir = null`. The starter does the extract itself (single member).
4. **`NodeLaunchSpec`:** `kind = "binary"`, `entry = "bin/aptos-node"`, `logFile = "logs/aptos.log"`. **Height spec:** `kind = "aptos_rest"`, `portRole = "http"`.
5. Class: `AptosNodeStart : ChainNodeStart` (`NetworkId.APTOS`).

## 9. Host process start

- `AptosNodeProcessStarter`: arch guard (x86_64 only), extract bin, locate genesis + waypoint, mode-change guard, write `fullnode.yaml`, render unit, `installCustomUnits`.
- Must **strip** the `--toolkit-extra-flags=` arg from `launch.args` before `installCustomUnits`: that helper rewrites `ExecStart` with `--key=value` flags and aptos-node would exit on an unknown argument. The overrides are applied to the YAML instead (`AptosUnitBodies.fullnodeYaml(..., overrides)`).
- Layout: everything under node_dir (`bin/`, `genesis.blob`, `waypoint.txt`, `fullnode.yaml`, `logs/aptos.log`, `.toolkit/`); DB under the state role dir. No `/opt/aptos`, `/etc/aptos`, no `nodeop` user (unit runs as the agent's user like the other chains).
- Mode guard: marker `{node_dir}/.toolkit/aptos-bootstrap` stores the mode used for the current DB; if the marker differs from the requested mode and `<state>/db` is non-empty -> `Failed("bootstrap mode changed fast -> genesis: wipe <state> first")` (a fast-synced DB has no history; genesis mode would stall or corrupt expectations) [I].
- Log growth: aptos-node logs at INFO to stdout, appended forever to `logs/aptos.log`; no rotation in the unit [R7].

## 10. Local height

- `GET http://127.0.0.1:<http>/v1` (`Accept: application/json`), loopback only. No auth.
- Response [V: live public fullnode, same schema as a local node]:
  ```json
  {"chain_id":1,"epoch":"17616","ledger_version":"7601542786","oldest_ledger_version":"7451742786","ledger_timestamp":"1791923238143563","node_role":"full_node","oldest_block_height":"1084946096","block_height":"1110785598","git_hash":"433c185c8e9c41205a83dfe289ac1072b7f00d1a","encryption_key":"60850e..."}
  ```
  Height = `ledger_version` (decimal **string**, parse to Long; also accept a JSON number). `ledger_timestamp` is microseconds since epoch [V: matches wall clock to <1 s].
- `syncing = (now - ledger_timestamp) > 60 s`; if `ledger_timestamp` is missing/0 and height > 0 -> `syncing = true`; `syncPct = null`. Non-2xx / bad JSON / port <= 0 -> `null` (during `DownloadLatestStates` the REST layer may not answer yet [I]).
- Class `AptosNodeHeightProbe : HostNodeHeightProbe` using `AptosRest`.

## 11. Public network tip

- Same protocol: `GET <url>` -> `ledger_version`. `publicTip.urls` (all return 200 + the schema above now [V]): mainnet `https://fullnode.mainnet.aptoslabs.com/v1`, `https://api.mainnet.aptoslabs.com/v1`, `https://aptos-rest.publicnode.com/v1`; testnet `https://fullnode.testnet.aptoslabs.com/v1`, `https://api.testnet.aptoslabs.com/v1`. Rejected [V]: blastapi (shut down), ankr (needs key, 403), nodereal (401), 1rpc / alchemy (429). Also 200: `mainnet.aptoslabs.com/v1`, `aptos.drpc.org/v1` (kept as spares, not listed).
- `AptosNetworkTipProbe` iterates urls, first success wins, reuses `AptosRest.ledgerVersion`.
- **Promotion `sync -> active`:** global rule is `tip - height <= 3` and host not syncing. Aptos mainnet advances ~126 versions/s [V: 3 samples] so 3 versions is ~25 ms: promotion relies on the cached tip being older than the local read (negative lag). Works but flaky -> R5.

## 12. Lifecycle

`sync` after Start; `active` when host reports `syncing=false` (ledger_timestamp within 60 s) and tip lag <= 3. No extra statuses. Mainnet fast sync: hours [V: vendor]; genesis: weeks and not completable on mainnet over public p2p (R1).

## 13. Install options (wizard)

One group `bootstrap`, default `fast`, choices:
- `fast` - "Fast - latest state, pruned": `DownloadLatestStates`, pruner vendor defaults (150M-version window). Hours to sync, hundreds of GB.
- `genesis` - "Genesis - full history": `ExecuteOrApplyFromGenesis`, three pruners off. Testnet: works (public peers still hold ~all history: `oldest_ledger_version` 34369 vs tip 55.8M [V]). Mainnet: public peers pruned (`oldest_ledger_version` 7,451,742,786 on Aptos' own PFN; 7.50B publicnode, 7.54B drpc [V]) and docs require archival **seed peers** for genesis sync [V: state-sync doc] - Kotlin ships none, so mainnet genesis stalls unless the operator adds seeds / uses phase-2 restore.
- Go's `restore` choice is removed (S3 backup bucket gone, needs CLI + aws). Validation: unknown / empty -> `fast`.
- Mapping: `AptosBootstrapMode.fromJson(installOptionsJson)`; bindings in section 6.
- Operator extra flags (`extra_flags`): yaml-path overrides from the allowlist in 6c.

## 14. Wiring checklist

Resources (done as drafts in this folder): `network.yml`, `clients.yml`, `node.service.tmpl` (identical to the sui / avalanche template). Kotlin / wiring / tests / admin: see `kotlin-design.md`. Reference counts: new `NetworkId.APTOS`; no new `EnvId` (`MAINNET`, `TESTNET` exist); no Flyway; no snapshot resolver in v1; docs: copy this file to `app/docs/networks/aptos-intake.md` when approved; add the Aptos bullet to `app/docs/prod-rpc-tuning.md`.

## 15. Open risks

| Risk | Impact | Mitigation |
|---|---|---|
| R1 Product default: Go promised full history, but mainnet full history cannot be fetched over public p2p (peers pruned; archival seeds needed) and the backup route is manual/phase 2 | A default of `genesis` would never finish on mainnet | Default `fast`; `genesis` offered with the warning text; phase 2 restore; DECISION for the user |
| R2 Disk / CPU numbers are estimates; archive 12 TiB derived from 5 sampled backup chunks | Wrong hints / disk gate | Re-measure after the first real node; numbers are YAML only |
| R3 Testnet reset: Testnet V2 appeared 2026-10-06 (old S3/Go URLs dead). A future V3 changes the `testnet-v2/` path again; a stale DB then fails on genesis | Testnet nodes break after a reset | Pinned paths + sha256 in tests (`38fe1af7...`); on repeated start failure the log says "wipe and re-sync"; track README of aptos-networks |
| R4 `main` branch URLs are mutable (genesis/waypoint) | Silent change | Test asserts sha256 of the shipped values only when the files are present; operators re-sync clients |
| R5 `sync -> active` needs `tip - height <= 3` at ~126 versions/s | Node may flap in `sync` | Verify on a live node; if flaky add a per-network tip-lag knob (shared `GetNodeHeightUseCase` / `IngestNodeHeightsUseCase`, out of the chain package) |
| R6 Remote telemetry on by default (logs / metrics / system info to Aptos Labs) | Privacy / compliance | Decision: keep (Go parity, vendor advice) or add `Environment=APTOS_DISABLE_TELEMETRY=true` to `node.service.tmpl` |
| R7 Unbounded `logs/aptos.log` (INFO) | Disk fill | Add logrotate / `logger.level` later; not handled by the toolkit today |
| R8 x86_64 only; ubuntu-22.04 build needs glibc >= 2.35 | Fails on aarch64, Debian 11, Ubuntu 20.04 | Starter fails early with a clear message; clients.yml has no aarch64 artifact |
| R9 Release tags: testnet "newest any" can take a hotfix tag meant for mainnet; asset-less prereleases exist | Wrong binary or empty download | Resolver can guard `assets` non-empty (design) |
| R10 Config validity not run on a Linux host; `deny_unknown_fields` rejects typos | Start crash | Fields checked against v1.49.5 source; run one real Start on each env before approving |
| R11 REST API on 0.0.0.0 with transaction submission enabled and no per-IP limit | Abuse | Rate-limiting proxy; preset can disable submission |
| R12 Old 1.8 GiB binary extract needs ~2.2 GiB free in node_dir plus 390 MB archive | Install fails on small root disks | Starter checks `usableSpace` before extracting |

## Review sign-off

| Section | Approved by | Date | Notes |
|---|---|---|---|
| 1-15 | | | pending |
