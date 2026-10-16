# Production RPC tuning (thousands of requests per second)

Source of every flag below: the client's own flag definitions / docs at the version the toolkit installs
(checked 2026-10-13). "Judgement" values are starting points, not benchmarks — measure before relying on them.

## What one node can and cannot do

Tuning raises the ceiling of ONE node. Cheap calls (`eth_blockNumber`, `eth_call` on recent state, receipts from cache)
can reach thousands per second; wide `eth_getLogs`, `debug_*` and `trace_*` cost orders of magnitude more. For a sustained
thousands-per-second public endpoint, put a rate-limiting reverse proxy with a response cache in front of several
nodes. None of the clients below has per-IP or per-key limits that scale.

## How the toolkit applies it

| Mechanism | Networks | Where |
|---|---|---|
| **Flag overrides** (key/value, replaces the unit's flag or appends one; applied on restart) | arb, robinhood, xai, starknet, base, optimism, arc, bsc, xrpl (cfg sections), unichain, ink, soneium, worldchain, lisk, mode, zora | Node config → Flag overrides → *Prod recommendations* |
| **Geth flags** (key/value table with defaults) | ethereum | Node config → Geth flags |
| **Config file keys** (draft, then Save) | bitcoin, bch, ltc, dash, zcash, tron | Node config document editor → *Prod recommendations* |

Ports, paths, keys and network selection are locked and cannot be overridden. Presets live in
`admin/src/lib/prodPresets.ts`; the host side is `UnitFlagOverrides` (units), the `apply_extra_flags` function in the
optimism / arc `run.sh.tmpl`, and `XrplUnitBodies.applyCfgOverrides`.

## Applied by the presets

- **Nitro (arb, robinhood)** — `http.api`/`ws.api` = `net,web3,eth` (the unit exposed `debug`), `rpc.max-batch-response-size`
  25 MB, `execution.rpc.filter-log-cache-size` 256, `persistent.handles` 16384, smaller L1 read window
  (`node.inbox-reader.default-blocks-to-read` 25, `target-messages-read` 200) to stay under geth's hard-coded
  1000-values-per-topic `eth_getLogs` limit. Cache sizes are not touched (use the GOMEMLIMIT formula in the Arbitrum guide).
- **Xai** — same Nitro flags as above without the two L1-reader window keys (the parent is Arbitrum One): `net,web3,eth` only, 25 MB batch response,
  log cache 256, 16384 handles. The unit already ships them (network.yml bindings); the preset re-applies them after manual edits. The Arbitrum One RPC URL lives in a 0600 env file.
- **Starknet (Juno)** — `db-cache-size` 4096 (default 1024), `rpc-max-block-scan` 10000 (unlimited), `rpc-request-timeout` 30s (1m), `rpc-max-concurrent-requests` and
  `rpc-max-request-queue` 4096 (256000). Juno has no debug namespace; pprof / gRPC / p2p / the log-level port stay off, metrics on 127.0.0.1, the L1 WebSocket in a 0600 env file.
- **reth family (base, optimism, arc)** — `rpc.max-connections` 3000 (default 500, Arc 250), `rpc.max-response-size` 64 MB (160),
  `rpc.max-blocks-per-filter` 50000, `rpc.evm-memory-limit` 128 MiB, `rpc-cache.*` raised, public namespaces
  `eth,net,web3,rpc`. Base/OP also reduce EL peers; OP adds `disable-tx-gossip`; Arc adds `public-api`.
- **OP Stack family (unichain, ink, soneium, worldchain, lisk, mode, zora)** — same RPC limits as the reth family plus fewer EL peers; the namespaces
  (`eth,net,web3`), tx-gossip off and the loopback binds of the Engine API / metrics / op-node RPC are already the unit's defaults (`chains/opstack/run.sh.tmpl`), so
  the preset only raises limits. `chains/optimism` is not part of this and still exposes `debug,trace,txpool` / metrics on `0.0.0.0` until its own preset is applied.
- **BSC** — `http.api` without `txpool`, `rangelimit`, `rpc.batch-request-limit` 1000 (unit used 2000), `cache.blocklogs` 128,
  `history.transactions=0` (README command line), `maxpeers` 50 (unit used 100), `rpc.gascap` 25M, `rpc.evmtimeout` 3s, `metrics`
  (127.0.0.1:6060). The unit no longer passes `--rpc.allow-unprotected-txs`. HTTP server timeouts are config.toml-only (not changed).
- **XRPL** — `[crawl]` 0, `[peers_max]` 50. `node_size` is deliberately left alone (set it after the first sync; `huge` on 64 GB).
- **Bitcoin family** — `disablewallet=1`, `dbcache=2048`; Litecoin also `blockfilterindex=0` + `peerblockfilters=0`;
  zcashd `rpcthreads=64`, `rpcworkqueue=4096` (its defaults are 4 / 16).
- **Tron** — `vm.supportConstant=true` (view calls are rejected without it), `node.maxHttpConnectNumber=1000` (default 50).

- **polkadot** (Substrate) — the unit runs `--rpc-methods=safe`, `rpc-max-connections` 1000 (vendor default 100; docs.polkadot.com's RPC recipe uses 1000),
  Prometheus on loopback. Preset: 2000 connections, `rpc-message-buffer-capacity-per-connection` 256, `trie-cache-size` 4 GiB (judgement).
  The optional REST API (`polkadot-rest-api`) keeps `/metrics` off because it is served on the public API port.
- **fuel** — `--debug` never passed; preset raises `graphql-number-of-threads` 8, `graphql-max-concurrent-queries` 4096, `max-database-cache-size` 8 GiB
  (vendor defaults 2 / 1024 / default cache; judgement). `/v1/metrics` is on the public GraphQL port: block it at the proxy.
- **aleo** — `rest-rps` ships as 1000 (vendor default 10 per source IP is unusable behind a proxy); JWT stays on.
- **near / stacks** — generated config files (`config.json`, `stacks.toml`): Start applies `rpc.enable_debug_rpc=false`, `view_client_threads=16` and
  `max_http_clients=3000` (judgement values; vendor defaults 4 and 1000). No flag preset exists for them.

## Found, NOT changed automatically (needs a decision)

Security / exposure:
- Public namespaces: base/arc expose `debug` (+`trace`, `txpool`) on `0.0.0.0` until the preset is applied; Avalanche exposes `debug`. Optimism now serves `eth,net,web3` only; its metrics, op-node RPC and Engine API are on 127.0.0.1, it has its own discv5 UDP port (9461 / 9464) and no IPC socket (same fixes as the new OP Stack family; existing nodes pick them up at the next Start).
- Solana RPC binds `0.0.0.0` with `--full-rpc-api`, `--rpc-max-request-body-size` 100 MiB, `--rpc-pubsub-max-active-subscriptions` 5M,
  `--rpc-threads` 128. Anza says not to expose it directly; CORS is hard-coded to any.
- Sui `json-rpc-address` and `metrics-address` on `0.0.0.0`, traffic control is dry-run only; pruning is effectively off
  (`num-epochs-to-retain` = u64::MAX, no checkpoint pruning) → unbounded disk. JSON-RPC is being removed in 1.82.x.
- XRPL `port_rpc` is `0.0.0.0` with `admin = 127.0.0.1`: a same-host proxy gets admin. Use a separate `secure_gateway` port. `port_grpc` is on `0.0.0.0`.
- TON: ton-http-api (8081/8082) and the v3 indexer (8187/8188) are bound to `0.0.0.0`, bypassing the router.
- Heimdall REST (swagger on) on `0.0.0.0:1317`.
- Bitcoin-family login-free proxy binds `0.0.0.0` and passes every method (incl. `stop`, wallet RPC) unless `RPCNODE_RPC_PROXY_METHODS=safe`.
- `rpcbind` / `rpcallowip` are editable rows in the Bitcoin-family config.

Functional / install:
- **BSC `latestRelease` returned `v1.8.0-alpha`** (published as a normal release). Fixed: tags ending in `-alpha/-beta/-rc/-preview`
  are skipped unless prereleases are requested.
- Base, Arc, Agave, Sui, bor, BSC, Avalanche and Bitcoin Core resolve "latest" at install time: flags can differ between installs.
- **Dogecoin 1.14.9 ignores keys under `[main]`** (boost parser), so `rpcthreads`, `rpcworkqueue`, `txindex`… from the toolkit
  template are probably not applied (source reading, not run — check `debug.log` for "starting 64 worker threads").
- `-chain=test` is passed to BCHN/Dogecoin/zcashd, which do not have `-chain`.
- Arc: `--rpc.forwarder` is prohibited by Arc's docs for provider nodes; `--arc-rpc-upstream-url` is never passed (certificate RPC points at 31000).
- Avalanche after Helicon (active on mainnet 2026-09-22): `rpc-gas-cap`, `rpc-tx-fee-cap`, `ws-cpu-*`, `api-max-blocks-per-request`
  are no longer read by the new C-Chain VM; `eth-apis` is deprecated. `config.json` is rewritten on every Start.
- Polygon: no CLI flags; tuning needs a `config.toml` patch step (not implemented). Execution pool `ep-size` 40 caps in-flight calls.
- Tron: no JVM flags at all (default heap, default GC); official guidance is in the java-tron install docs.
- Hyperliquid: no RPC tuning flags exist; `--replica-cmds-style actions` writes ~100 GB/day, `recent-actions` keeps two files
  (effect on serving not documented); the catalog sizing (8 cores / 32 GB) is below the documented 16 vCPU / 128 GB.
- TON: single-process `ton-http-api` is the bottleneck (gunicorn workers would be the largest gain); install scripts are unpinned (`master`).

## Not covered by flag overrides (script- or generator-based)

Solana (`run-validator.sh` flags are built in Kotlin), Sui (`fullnode.yaml` regenerated each Start), TON (bootstrap scripts),
Hyperliquid, Polygon (`config.toml`), Avalanche (`config.json`). Their findings are listed above; each needs its own
generator change.

## EVM family (gnosis, joc, flare, sonic, kaia, vana, tempo, berachain, fluent, gravity)

Production defaults are in the units themselves (`chains/evmfamily`, mirrored as `clientConfig.bindings` and compared by `EvmCatalogTest`);
the presets in `prodPresets.ts` only tighten them further. What is applied everywhere: public namespaces `eth,net,web3` (+ `rpc` on reth,
`sonic` on Sonic, `klay` on Kaia, `Subscribe,Rpc` on Nethermind), IPC off, Engine API / metrics / consensus RPC / inspection on `127.0.0.1`,
`LimitNOFILE` 1048576 (Sonic 934073 as its docs). Capacity: Nethermind batch 1000 / queue 500 / 50 peers; reth family 3000 connections,
64 MB responses, 50000 blocks per filter; Kaia `rpc.concurrencylimit` 3000 + the kend.conf timeouts; Sonic `--cache 12000`, `--maxpeers 50`,
batch 1000, `GOMEMLIMIT` 90 % of RAM; Vana geth batch 1000 / 25 MB / `evmtimeout 5s` (the official compose: 5000 / 125 MB / unlimited);
Gravity 3000 connections (upstream template 20000), `prune.transactionlookup.distance 10064` (docs minimum).

Found, NOT changed:
- Flare (go-flare, avalanchego fork): tuning is `config.json` (rendered on Start, not an override target). `/ext/health`, `/ext/info`,
  `/ext/metrics` are served on the public HTTP port by avalanchego itself: put a proxy that forwards only `/ext/bc/C/rpc` and `/ext/bc/C/ws` in front.
- Kaia: `--prometheusport` binds ALL interfaces (the exporter does `ListenAndServe(":port")`): firewall it.
- Tempo: besides RPC a follower binds the consensus metrics server (default 8001), reth's authrpc (8551) and an IPC socket (`/tmp/reth.ipc`):
  the unit pins the first two to catalog ports and disables IPC. Its pinned reth revision may lack some `rpc-cache.*` flags the generic reth preset names.
- Gravity: txpool and consensus keys live in `public_full_node.yaml` / the unit and are not override targets.
- Public RPC ports have no auth, TLS or rate limit on any of these clients: a caching, rate-limiting proxy is required for public traffic.

