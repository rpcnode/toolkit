# Network intake — Ethereum Classic (`etc`)

Facts marked **[V]** were verified on 2026-10-13 (how is given); **[I]** = inferred / judgement; **[G]** = old Go toolkit fact (read-only).
Drafts that go with this file: `network.yml`, `clients.yml`, `node.service.tmpl`; implementation design: `kotlin-design.md`.

## 0. Meta

| Field | Answer |
|---|---|
| Network id (`NetworkId`) | `etc` — Go calls it `etc` (`spec.Chain{Name:"etc"}`, package `etcnet`); the admin already knows `etc` (NetworkIcon, networkDocs, networkPaths, MULTI_DISK_NETWORKS, isNoSnapshotNetwork) |
| Display label | Ethereum Classic |
| Env ids | `mainnet`, `mordor` (new `EnvId.MORDOR`) |
| Author / date | toolkit research agent / 2026-10-13 |
| Status | draft — needs decisions in section 15 (client repo, RPC bind address) |

**Go sibling** (`toolkit-go/internal/networks/etcnet/`): ports to Kotlin as facts only. Go facts: client = Core-Geth (`etclabscore/core-geth` zip, `core-geth-linux-<tag>.zip` / `core-geth-arm64-<tag>.zip`, binary `geth`); chain flags `--classic` / `--mordor`; one disk role `chaindata` (NVMe); mainnet 1024 GiB / 4 cores / 16 GiB, mordor 128 GiB / 2 cores / 8 GiB; unit = archive (`--syncmode full --gcmode archive`), `--cache 4096` (mainnet) / `1024` (mordor), `--maxpeers 50`, `--http.api eth,net,web3,debug,txpool`, `--http.vhosts=* --http.corsdomain=*`, `LimitNOFILE=1048576`, `User=nodeop`, binaries under `/opt/etc/bin`, data `/data/rpcnode/etc/<env>`; snapshot `never`. Not ported: `User=nodeop`, `/opt` + `/etc` paths, `debug` namespace, CORS `*` (unsafe / forbidden by the Kotlin rules).

## 1. Product scope

1. **Why:** operators asked for ETC RPC nodes (already on the Go product). Proof of work, single binary, simplest EVM chain in the catalog.
2. **MVP:** install client (zip), start unit, height + sync % (`eth_blockNumber` / `eth_syncing` / snap-sync log), public tip lag, `node` install option full|archive, flag overrides + prod preset. **Out of scope:** mining, WebSocket / GraphQL, metrics, Nethermind/Fukuii, snapshots (none exist).
3. **Pin-only?** No. GitHub release assets exist **[V]** (`curl -sI` + full download of both zips, sha256 matches the `.sha256` sidecar for linux and arm64).
4. **One env per host?** `oneEnvPerHost: false` — mainnet and mordor have separate ports (+ separate unit names).

**Client choice [V]:** Core-Geth is the only production ETC client with release binaries. Besu removed ETC/Mordor (`hyperledger/besu` CHANGELOG: "ETC Classic support in Besu is removed", PR #9671); Fukuii (`chippr-robotics/fukuii`, Scala/JVM) ships only pre-releases (`v0.9.78`, all `prerelease: true`); Nethermind/getc appear only in a Nix experiment repo. Core-Geth moved: **`ethereumclassic/core-geth` is the maintained repo** — v1.13.0 (2026-09-14) is "the first release published from this repository" and the release notes call it the **recommended client**; `etclabscore/core-geth` last released v1.12.23 (2026-08-14), which carries open CVEs (RLPx handshake crash CVE-2026-22862, P2P key leak CVE-2026-26315 — two were exploited against ETC bootnodes in Mar 2026), Go 1.21, and the `eth_syncing` regression. **Use `ethereumclassic/core-geth`** (differs from Go).

## 2. Environments

| Env id | Label | Production? | Notes |
|---|---|---|---|
| `mainnet` | Ethereum Classic Mainnet | yes | `--classic`, chain id 61 (`0x3d`), network id 1 **[V]** docs + live `eth_chainId`/`net_version` on etc.drpc.org |
| `mordor` | Ethereum Classic Mordor | test | `--mordor`, chain id 63 (`0x3f`), network id 7 **[V]** live on rpc.mordor.etccooperative.org |

Same binary and artifact for both; only the chain flag differs. Every public tip exists for both (no local-only env). Chain data of the two networks is incompatible (genesis mismatch) — always pass the flag **[V]** docs.

## 3. Client binary & artifacts (`clients.yml`)

1. **Upstream:** GitHub `ethereumclassic/core-geth` release assets. No Docker (the release also ships `core-geth-docker-*.tar.gz` and a ghcr image — not used).
2. **Latest rule:** `github.latestRelease(repo, tagPrefix = null)` = newest non-draft, non-prerelease, tag not ending `-alpha|beta|rc|preview` — ETC has one stable tag now: `v1.13.0` **[V]** (`gh api repos/ethereumclassic/core-geth/releases` → 1 release, `prerelease:false`). Tag keeps the `v` (`{tag}` = `v1.13.0`, `version` = `1.13.0`). No channel / prefix.
3. **Artifacts** **[V]** (HTTP 200, sizes: linux 34 121 982 B, arm64 31 035 493 B; sha256 linux `c5e88eab…40a77c`, arm64 `a5de7e1f…3948df842` match the `.sha256` sidecars):

| Arch | On-disk name (versionless) | URL |
|---|---|---|
| x86_64 | `core-geth-linux.zip` | `https://github.com/ethereumclassic/core-geth/releases/download/{tag}/core-geth-linux-{tag}.zip` |
| aarch64 | `core-geth-arm64.zip` (`nameAarch64`) | `https://github.com/ethereumclassic/core-geth/releases/download/{tag}/core-geth-arm64-{tag}.zip` |

   Linux x86_64 needs glibc ≥ 2.17 (statically friendly). 32-bit ARM assets exist (`arm5/6/7`) but are not offered.
4. **Zip layout [V]** (`unzip -l`, `unzip -Z`): `geth` (ELF, `-rwxr-xr-x`, ~100 MB) + `COPYING` at the zip root, no directory. **A zip, not a tarball** — `HostNodeLaunchSupport.prepareEntry`/`extractArchiveGlob` only does `tar -xzf`, so the chain extracts it itself (`EtcHostBinaries`, java.util.zip, like BSC's genesis zip).
5. **Config templates:** none — all configuration is CLI flags.
6. **Program id:** `core-geth` (`clientConfig.program`). Note `InstallPlanWriter.inferLaunch` special-cases program id `geth` (entry `geth`); for `core-geth` it falls through to `kind=artifact` — add a `core-geth` branch → `InstallPlanLaunch("binary", "bin/geth")` (cosmetic; the host only reads `javaMajor` from the plan).
7. **Requirements:** none (`javaMajor` n/a). Process log = default `logs/node.out` (geth logs to stderr → unit `StandardError=append:`), so no `requirements.logFile`.
8. **No Docker:** confirmed.

## 4. Fixed ports (`clients.yml`)

Central plan `specs/PORTS.md`: `etc` owns 41390-41419 (env0 mainnet 41390-41399, env1 mordor 41400-41409). `grep` of all shipped `chains/*/clients.yml`: nothing in 41000-42999 is used **[V]**.

| Env | Role | Port | Label | Bound by product? |
|---|---|---|---|---|
| mainnet | http | 41390 | HTTP RPC | yes (`--http.port`) |
| mainnet | p2p | 41392 | P2P (TCP + UDP discovery) | yes (`--port`; discovery UDP follows it **[V]** docs) |
| mordor | http | 41400 | HTTP RPC | yes |
| mordor | p2p | 41402 | P2P | yes |

Reserved, not started: +1 ws (41391 / 41401), +4 metrics (41394 / 41404). **No +3 engine port**: v1.13 starts the Engine API only on merged chains, so ETC/Mordor open no 8551 and ignore `--authrpc.*` **[V]** docs. IPC is disabled (no socket). No implicit extra ports (no QUIC); with default `--nat any` geth asks the router (UPnP/NAT-PMP) to forward only the p2p port **[V]** docs. Upstream defaults 8545/30303 are NOT used (taken by ethereum). Old Go ports (8555/8556, 30323/30324) are dropped — 30323 clashes with ethereum hoodi.

## 5. Disks & sizing (`network.yml`)

1. `diskRoles`: one role `chaindata` (nvme) — binary + zip + `datadir/` (chaindata + ancient) + logs. No second role (`--datadir.ancient` exists, not used; matches Go).
2. Numbers:

| Env | diskHintGiB = fullNodeGiB | archiveGiB | CPU | RAM | Source |
|---|---|---|---|---|---|
| mainnet | 256 | 1024 | 4 | 16 | full: **[V]** vendor measured 51.18 GiB whole datadir (chaindata 50.98, ancient 27.24) at block 25 337 078, 2026-09-13, v1.13.0, snap sync, peak RSS 2.33 GiB at `--cache 1024` ⇒ 5× headroom **[I]**; archive 1024 **[G]**, vendor "not measured" |
| mordor | 64 | 128 | 2 | 8 | full: **[V]** 9.76 GiB at block 16 972 715 (peak RSS 1.13 GiB) ⇒ 6× **[I]**; archive 128 **[G]** |

3. `diskNotes`: see `network.yml`. 4. Chain tips now: mainnet block ≈ 25.53 M, Mordor ≈ 17.18 M **[V]** (`eth_blockNumber` live).

## 6. Client config shape + PRODUCTION CONFIG

1. **Format:** `flags` (systemd unit). One template, per-env values via `onlyEnv` bindings (`chain`, `cache`). No conf patch.
2. **Operator-editable later:** generic **Flag overrides** (`install_options.extra_flags`, applied to the unit by `HostNodeLaunchSupport.installCustomUnits` → `UnitFlagOverrides`; locked tokens `port|datadir|addr|chain|network|config…`) + `node` option. The Ethereum-only `geth_flags` editor is NOT reused. Bare/boolean flags in the unit are written `--mess=true` (with `=`) so an override renders `--mess=false` (a space-separated bool would break urfave/cli).
3. **Bindings** = `network.yml` (24 rows). Every one is applied in `node.service.tmpl` via `EtcUnitBodies` constants:

| Key | Source | Value | Applied as | Verified? |
|---|---|---|---|---|
| node | install_option | full \| archive | `--syncmode snap --gcmode full` \| `--syncmode full --gcmode archive` | [V] docs sync-modes/archive-node |
| chain | literal (onlyEnv) | `--classic` / `--mordor` | first flag | [V] run-cli |
| datadir | disk_role_dir chaindata/datadir | — | `--datadir` | [V] |
| http-port / p2p-port | catalog_port | 41390/41392, 41400/41402 | `--http.port` / `--port` | [V] |
| http-host | literal | `0.0.0.0` | `--http.addr` (vendor default `localhost`) | [I] parity with ethereum; see risk R2 |
| http-api | literal | `eth,net,web3` | `--http.api` | [V] vendor public-RPC guide: serve eth, net, web3; empty list stops the node |
| http-vhosts | literal | `*` | `--http.vhosts` (default `localhost`, else 403 `invalid host specified`) | [V] flag; value [I] |
| cache | literal (onlyEnv) | 4096 mainnet / 1024 mordor | `--cache` | [V] flag, default 1024, auto-capped at RAM/3; values [G] |
| maxpeers | literal | 50 | `--maxpeers` | [V] default 50 |
| history-transactions | literal | 0 | `--history.transactions` (default 2 350 000) | [V] semantics; value [I] |
| mess | literal | true | `--mess=true` | [V] default true in v1.13.0; pinned [I] |
| rpc-gascap | literal | 50000000 | `--rpc.gascap` | [V] default |
| rpc-evmtimeout | literal | 5s | `--rpc.evmtimeout` | [V] default |
| rpc-batch-request-limit | literal | 1000 | `--rpc.batch-request-limit` | [V] default |
| rpc-batch-response-max-size | literal | 25000000 | `--rpc.batch-response-max-size` | [V] default |
| rpc-txfeecap | literal | 1 | `--rpc.txfeecap` (ether) | [V] default |
| txpool-globalslots / globalqueue | literal | 5120 / 1024 | `--txpool.globalslots/globalqueue` | [V] defaults |
| ipcdisable | literal | true | `--ipcdisable` | [I] (as ethereum; avoids 108-byte socket path limit) |
| LimitNOFILE | literal | 1048576 | unit | [G] |
| TimeoutStopSec | literal | 300 | unit | [V] vendor systemd example (`TimeoutStopSec=300`; clean stop 42 ms–6 s, first-sync stop ≤ 1.5 min) |

   Pinning defaults is deliberate: capacity knobs show in the Start **Config preview** and survive upstream default changes. Chosen **not** to be set: `--http.corsdomain` (Go set `*` — no CORS headers is the vendor default), `--ws*`, `--graphql`, `--rpc.allow-unprotected-txs` (vendor: "Leave it unset"; BSC's unit sets it — ETC must not), `--metrics`, `--authrpc.*`, `--state.scheme` (new dir gets `hash`; `archive` needs hash; a dir keeps its scheme and refuses the other), `--snapshot`/`--nat`/`--nodiscover` defaults.
4. **Production settings — what is safe / unsafe (public RPC):**
   - **Verified, vendor "Public RPC endpoint" guide:** serve only `eth,net,web3`; `txpool` optional (`txpool_content` dumps the whole pool); `trace` only behind a limiting gateway; `debug` (has `debug_setHead`), `admin` (`admin_startHTTP`), `miner`, `ethash`, `personal` — never; `engine` cannot be served. Node has **no TLS, auth or rate limit** and **no block-range limit on `eth_getLogs`**, no per-IP limits → a proxy must supply them. `finalized`/`safe` tags always fail on ETC (judge finality by depth). `net_version`=1 (mainnet) / 7 (Mordor), `eth_chainId`=0x3d / 0x3f.
   - **Unsafe by default in this draft (decisions):** RPC bound to `0.0.0.0` with `vhosts=*` and no auth (same as ethereum/avalanche in this repo; the vendor says keep it on 127.0.0.1 behind a proxy) → R2. Keep keystore empty (any `eth` caller can use an unlocked account; the node never has accounts here).
   - **Not exposed:** debug, admin, personal, miner, ethash, txpool (dropped vs Go's `eth,net,web3,debug,txpool`), ws, graphql (GraphQL v1.13 refuses nesting >20, still off), CORS.
5. **Prod preset proposal** (`admin/src/lib/prodPresets.ts`, `etc` key; judgement values, flags are real v1.13 flags **[V]**, numbers **[I]**):
   ```ts
   etc: {
     flags: {
       // public namespaces: eth,net,web3 only (empty list would stop the node; txpool_content dumps the pool)
       'http.api': 'eth,net,web3',
       // vendor default 1000 calls per batch; a smaller batch limits per-request amplification (judgement)
       'rpc.batch-request-limit': '500',
       // vendor default 25000000 B per batch response (judgement: 10 MB)
       'rpc.batch-response-max-size': '10000000',
       // eth_call is the costliest public call: 5s default -> 3s (judgement)
       'rpc.evmtimeout': '3s',
       // vendor default 50 M gas per eth_call/estimateGas (judgement: 25 M)
       'rpc.gascap': '25000000',
     },
     notes: [
       'Checked against core-geth v1.13.0 (docs.coregeth.com Public RPC endpoint guide + run-cli flag list).',
       'The node has no TLS, auth, rate limit or eth_getLogs block-range limit: put a rate-limiting proxy with a method allow-list in front.',
       'Not changed: --cache (geth caps it at RAM/3), --maxpeers 50 (ETC has ~550 nodes), --mess (keep every node of a fleet the same).',
       'Also removed vs the old toolkit: debug namespace and --http.corsdomain *.',
     ],
   },
   ```
   (`http.api` equals the unit default; it is listed so the editor shows it and re-applies it after an operator widened it.) No `conf` section.

## 7. Snapshot policy

1. **Per env: `snapshot: never` (mainnet and mordor).** There is **no official or community chaindata snapshot** for ETC or Mordor:
   - Vendor docs (`ethereumclassic/core-geth` docs/ tree (~70 pages; read the relevant ones: hardware, sync-modes, maintenance, archive-node, run-classic/mordor): no snapshot download; the documented bootstrap is snap sync **[V]** (grep of all fetched pages).
   - `publicnode.com/snapshots` lists ~75 chains, **no Ethereum Classic / Mordor** [V] (WebFetch); `snapshots.ethpandaops.io` covers Ethereum networks only [V]; `etc-network.info` (community explorer/RPC) has no snapshot link [V]; `snapshots.etc-network.info`, `snapshots.ethereumclassic.{net,org}` do not resolve [V]; web search found none.
   - GitHub releases carry no chain data.
   - Snap sync is fast and measured by the vendor on v1.13.0 (reference machine i7-10710U, NVMe): **mainnet 69–76 min** to `Snap sync complete`, **Mordor 19 min** **[V]** — a snapshot would not pay off.
2. **No resolver, no `snapshots:` block, no `snapshotTypes`.** `network.yml` just says `snapshot: never`; admin already treats `etc` as `isNoSnapshotNetwork`.
3. Archive from genesis is the slow path (full sync of ~25.5 M blocks; vendor "not measured") — **[I]** days to weeks; documented in the `node` option hint, no mitigation exists.
4. Sketch **if a source appears later** (do not build now): copy BSC/TRON — `EtcSnapshotResolver : SnapshotResolver` (`chains/etc/infrastructure/http`) mapping `(env, type)` → `SnapshotArchive(url, name, sizeBytes)` from a `snapshots:` block in `clients.yml` (`mainnet: full: {mirror, filename, discover: listing|dated}`); archive format would need to unpack into `<chaindata>/datadir/geth/` (`chaindata/`, `chaindata/ancient/`, `nodes/`; no `nodekey`) with the unit stopped; RPC is NOT available during extract (node not started); set `snapshot: optional` + `snapshotTypes: [{id: full, kind: full, destLeaf: datadir}]`. Needs a trusted checksum source (none today).
5. RPC while restoring: n/a. Node serves RPC during snap sync (`eth_syncing` object, `eth_blockNumber` = 0 until the pivot head is set) — see section 10.

## 8. Start recipe (`chains/etc/infrastructure/start`)

1. Process model: native binary `bin/geth` under node_dir, systemd unit `rpcnode-etc-<env>.service` (foreground, systemd owns the PID).
2. Args (panel → agent, all `--toolkit-*`, no chain rules in shared code): `--toolkit-env=<env>`, `--toolkit-archive=0|1`, `--toolkit-chain-id=61|63`, `--toolkit-datadir=<chaindata role dir>/datadir` (omitted → `<node_dir>/datadir`).
3. Archive extract: **custom** — `EtcHostBinaries.ensure` unzips `core-geth-*.zip` (entry named `geth`, zip-slip safe) to `bin/geth` (0755); `extractArchiveGlob = null` / `normalizeDir = null` because the shared extractor is tar.gz only.
4. `NodeLaunchSpec(kind = "binary", entry = "bin/geth", args = …)`; `NodeHeightSpec(kind = "eth_rpc", portRole = "http")`.
5. Class `EtcNodeStart : ChainNodeStart` (`NetworkId.ETC`).

## 9. Host process start (`chains/etc/infrastructure/proc`)

1. Nothing beyond `HostNodeLaunchSupport.installCustomUnits` (primary unit only, no companions). No env vars, no ulimit besides `LimitNOFILE`, working dir = node_dir.
2. `EtcNodeProcessStarter : HostNodeProcessStarter`: ensure binary → mkdir `datadir`, `logs` → render `node.service.tmpl` (flags from constants) → `installCustomUnits(primaryBody, companions = emptyList(), launch.copy(entry = "bin/geth"))`.
3. Layout: bin, zip, datadir (incl. `geth/nodekey`), logs, `.toolkit/` all under node_dir. No `/opt/etc`, no `/etc/etc`, no JWT, no `User=nodeop` (unit runs as root like the other chains). Nodekey rotation after a v1.12→v1.13 upgrade (vendor requirement) is NOT needed for a fresh install; a node moved from the Go toolkit must rename `geth/nodekey` once (R6).

## 10. Local height (`chains/etc/infrastructure/http`)

1. HTTP JSON-RPC on `127.0.0.1:<http port>`; no auth.
2. `eth_blockNumber` — request `{"jsonrpc":"2.0","id":1,"method":"eth_blockNumber","params":[]}`, answer `{"jsonrpc":"2.0","id":1,"result":"0x1858db9"}` (hex → Long; `error` → null) **[V]** (live ETC endpoints answer exactly this; same helper `EthereumEthRpc.blockNumber`). Plus `eth_syncing` (`false` | `{currentBlock,highestBlock,…}` hex) **[V]** docs, and the geth sync log lines in `<node_dir>/logs/node.out` **[V]** docs sample (regexes of `EthereumEthRpc.parseSnapSyncPctFromLog` match them — tested on the vendor log text: `state download in progress  synced=0.71%` → 0.71, `chain download in progress  synced=0.00%` → 0.00).
3. `EtcNodeHeightProbe : HostNodeHeightProbe` (`suspend`), `reading()` as in ethereum minus the beacon-headers case: height = max(block, sync.currentBlock); `eth_syncing=false` ⇒ `syncing=false, syncPct=100`; object ⇒ `syncing=true`, pct = min(current/highest %, log %) clipped to 99.9. `eth_syncing` stays an object until the first `Indexed transactions` line after sync (a few minutes) **[V]** docs — acceptable (reads as syncing at 99.9 %).
4. Failure → `null` (agent skips the sample); port ≤ 0 → null.

## 11. Public network tip

```yaml
mainnet: urls: [https://etc.drpc.org, https://etc.etcdesktop.com, https://etc.blockscout.com/api/eth-rpc]
mordor:  urls: [https://rpc.mordor.etccooperative.org]
```
**[V]** all four answered `eth_blockNumber` with HTTP 200 at 25.53 M / 17.18 M (consecutive probes advance by blocks; `etc.drpc.org`, `etc.etcdesktop.com`, blockscout agree within 2 blocks; drpc also answers `eth_chainId` 0x3d). Dead / not live: `etc.rivet.link` (offline Aug 2026), `rpc.ankr.com/etc` (403), `rpc.ethereumclassic.net` + `rpc-mordor.ethereumclassic.net` (announced replacements, DNS does not resolve yet), `etc-network.info` Geth/Besu hosts, `mytokenpocket` (timeout), publicnode ETC (404). Mordor has a single source and it sits on the dissolving ETC Cooperative's domain → R4.
`EtcNetworkTipProbe` = `EthereumEthRpc.blockNumber` over the list, first non-null wins. `sync → active` when tip lag ≤ 3 (default); ETC block time ≈ 13 s.

## 12. Lifecycle & panel status

`sync` after Start, `active` when height ≥ tip − 3. Fits: snap sync shows 0 → height jump at the pivot. No extra statuses. Admin Sync step + header height poll only while `sync`/`active`. Note MESS pauses (and logs `Disabled artificial finality features`) while peers < 5 or head older than 390 s — informational only **[V]** docs.

## 13. Install options

One group `node` (same id as ethereum/avalanche): `full` (default, "Full · snap sync": `--syncmode snap --gcmode full`, ~51 GiB) | `archive` ("Archive · gcmode archive": `--syncmode full --gcmode archive`, syncs from genesis, ~1 TiB est.). Normalisation: anything but `archive` ⇒ `full`. Maps to binding `node`. A data dir keeps its sync mode/scheme: switching full → archive on an existing node does not recover old state (vendor) — the wizard picks it on a new node only. Admin: `ETC_NODE_OPTIONS` + `fallbackInstallGroups` (`net === 'etc' && (mainnet || mordor)`).

## 14. Wiring checklist

See `kotlin-design.md` §8–10 (Toolkit.kt maps, AgentMain, NetworkId/EnvId, InstallPlanWriter, tests, admin).

## 15. Open risks

| # | Risk | Impact | Mitigation / decision needed |
|---|---|---|---|
| R1 | **Client repo changed**: Go used `etclabscore/core-geth`; maintained home is now `ethereumclassic/core-geth` (v1.13.0). Old repo `master` is `1.12.24-unstable`, no new release | installing from the old repo = unpatched CVEs | Draft uses the new repo. Confirm. Go-installed nodes on 1.12.x must upgrade (and rotate `geth/nodekey`) |
| R2 | RPC bound to `0.0.0.0`, `vhosts=*`, no auth/TLS/rate-limit, no `eth_getLogs` range limit (node-side) | public abuse / DoS | Draft mirrors ethereum (0.0.0.0) but only `eth,net,web3`. **Decision:** keep, or bind `127.0.0.1` (needs a local proxy — `http.addr` is locked in Flag overrides) |
| R3 | v1.13.0 enables MESS past the ECIP-1110 deactivation block and makes ETC the default network; fleets must agree on `--mess` | chain-selection divergence | Pinned `--mess=true`; override with `mess=false` (equals-style) |
| R4 | Mordor public tip = one host on the dissolving ETC Cooperative domain; mainnet tips are commercial/community; `rpc(-mordor).ethereumclassic.net` not live yet | "behind tip" unknown | Re-check later; add the new hosts at the front of `publicTip.urls` when they answer |
| R5 | Archive disk/time unknown (vendor not measured; 1 TiB is Go's estimate); path-scheme + archive is unsupported | wrong disk plan | Measure on first archive node; never pass `--state.scheme path` |
| R6 | No checksum verification of the zip by the toolkit (sidecar `.sha256` exists) and `Sync Start` of a persisted record does not re-extract after a client Update | stale binary after update | `EtcHostBinaries` re-extracts when the zip stamp (size+mtime in `.toolkit/etc-geth.stamp`) changed; same gap exists for avalanche — verify Update flow on a host |
| R7 | GitHub API rate limit when resolving the latest tag (unauthenticated 60/h) | resolver returns null | existing GitHub token setting; fallback = clients.yml source |
| R8 | Disk full → geth shuts itself down cleanly (exit 0) and `Restart=on-failure` leaves it stopped | looks like a stopped node | `--datadir.minfreedisk` left default (2× `--cache.gc`); admin disk warning exists |

## Review sign-off (fill on review)

| Section | Status |
|---|---|
| 1–5 scope / envs / artifacts / ports / disks | proposed |
| 6 client config + prod | proposed (R2 decision) |
| 7 snapshot | `never` (none exist) |
| 8–13 | proposed |
| 15 risks | open |
