# Risks, unknowns, decisions (COSMOS family)

## Decisions the user must make
1. **Tip-lag threshold per network** — `tipLagActive = 3` is a global constant (`GetNodeHeightUseCase`, `MarkNodeStartedUseCase`). Sei (0.4 s), Injective (0.65 s), 0G (0.5 s) blocks make 3 blocks = 1–2 s; a node at tip will flap or never go `active`. Propose `tipLagBlocks` in network.yml (sei 25, injective 15, zerog 25, others 3). Needs a shared-code change.
2. **Snapshot extractor generalisation** — shared streaming extractor is gzip-only; every provider here ships `.tar.lz4`. Choose: generalise `SnapshotStreamExtract` (recommended, one change serves all 6 networks) vs per-network heal scripts like base/arc/bsc (`snapshot-heal.sh.tmpl`). Also needs `lz4` in `hostPackages` (zstd only if Cumulo is added).
3. **Sei bootstrap default** — state sync (small, docs say "4–5 attempts", needs `[statesync]` kebab keys, trust height rounded to 100000) vs Polkachu snapshot **234 GB** lz4 (+ ~2 TB node). Recommended default: state sync, snapshot as the fallback type.
4. **Celestia scope** — ship consensus node only (READY) or also DA nodes (light/bridge/full)? A bridge needs an ARCHIVAL consensus node (`min-retain-blocks=0`, `indexer=null`) which contradicts the pruned default; a light node is trivial (~20 GB, 500 MB RAM) but a different program, RPC (26658) and key. Suggest phase 2 with install option `node=consensus|light|bridge`.
5. **Testnet envs** — cosmos `provider` (gaia v29.0.0-rc0 prerelease), osmosis `osmo-test-5` (runs v31.0.0-rc1, behind mainnet v31.0.3), injective `injective-888` (no stable testnet binary release found after Feb 2026; live testnet == mainnet version string), sei `atlantic-2`. Ship them or mainnet-only? Release resolver for testnets needs `includePrerelease`/`tagSuffix`/pin — each is guess-prone.
6. **0G EL client** — reth only (recommended, but flags beyond the docs are INFERRED) vs geth (docs' original, config-file driven, `geth-config.toml`). Plan: reth only.
7. **0G without snapshots** — no official snapshot; mainnet is ~47 M fast blocks. Accept genesis sync (duration unmeasured) or allow a user-supplied snapshot URL (Stakeme ~90 GB, unverified)? Also whether `snapshot: optional` with a custom URL field should exist.
8. **Public IP for 0G reth `--nat extip:`** — host agent needs a public-IP source (or use `--nat any`/none). Not available as an install option today.
9. **Cosmovisor / auto-upgrade** — not in v1. Every governance upgrade halts the node until the operator hits Update client; for Celestia the v9->v10 activation (upcoming) requires the multiplexer binary BEFORE the activation height. Decide whether a halted-upgrade detector + "Update available" nag is wanted.
10. **arm64** — gaia (cosmos) and Injective, 0G ship amd64 only; Sei docs call the prebuilt amd64-only (asset for arm64 exists); osmosis/celestia/xrplevm have arm64. Mark cosmos/injective/zerog amd64-only in the wizard (reject arm64 hosts early).

## Unknowns / INFERRED items to verify at implementation (run the binary once)
- Injective post-EVM `app.toml` key names (`[json-rpc]` vs old `[evm-rpc]`, `address`/`ws-address`, api default 10337, grpc 9090) — only an archived pre-EVM app.toml was seen. Run `injectived init` and diff.
- Sei `config.toml` kebab keys for seid v6.7.x (`pprof-laddr`, `[p2p] laddr`, `[instrumentation] prometheus-listen-addr`, `[statesync] rpc-servers/trust-height/trust-hash/temp-dir`), `[evm] http_port/ws_port`, admin_server — read from docs default config (VERIFIED text), not from a generated file.
- CometBFT env/flag overrides were NOT used on purpose (not verified that `<APP>_RPC_…` env works for config.toml keys); everything goes through the TOML patch.
- reth flags not in the 0G docs: `--http.port`, `--ws*`, `--authrpc.port`, `--port`, `--discovery.port`, `--metrics <addr>`; 0gchaind flag `--p2p.laddr` (we patch config.toml instead). The 0G reth/0gchaind binaries were not executed (no `--help` run).
- Whether `wasm/` is inside the Osmosis/Polkachu snapshots (docs say yes for Injective and Sei; listing of the first 40 MB only showed `data/`). If `wasm/` is outside the tar the node fails with AppHash/`wasm` errors.
- Polkachu `api/v2/chain_snapshots/<slug>` answers "A secret header is required" for all slugs except `cosmos` (flaky) -> HTML scrape is the working path (regex `snapshots.polkachu.com/(testnet-)?snapshots/<slug>/<slug>_<h>.tar.lz4`); site markup change breaks it. Polkachu testnet osmosis snapshot absent (page empty).
- `GitHubReleaseClient` looks only at the first 20 releases newest-first; celestia pages are flooded with `-mocha`/`-corto` prereleases (not an issue for mainnet because prereleases are skipped, but mocha needs a suffix filter + the first page may not contain a `-mocha` stable). Check page size.
- Tag->`{version}`: osmosis assets use `osmosisd-31.0.3-…` (no `v`), xrplevm `node_11.2.0_…`, sei `sei-chain_6.7.1_…`, 0G `aristotle-v1.0.7` — and 0G tags mix `v1.0.7`/`1.0.4`; `version = tag.removePrefix("v")` handles both (checked against `HttpGitHubReleaseClient`).
- Latest-stable vs chain-live mismatch (a newly published release can precede the governance upgrade). Install-time check vs `/abci_info` is only informational (osmosis returns `local`, sei null).
- Sizing numbers other than Injective/Sei/Celestia/0G (docs) are judgement.
- `min-retain-blocks=100000` (cosmos/osmosis/injective/sei) is a judgement; Celestia 3000 is the docs-measured setting but makes `/block` history ~5 h on 6 s blocks — a public RPC may need more (celestia-app docs recommend 600000 for validators; storage impact unmeasured, blocks up to 128 MB).

## Security / operations
- Ports: `ShippedPortCatalogTest` must pass; cosmos family uses 41240-41258, 41300-41317, 41720-41738, 42110-42127, 42350-42368, 42770-42789, 42860-42878; no upstream default port used (26656/26657/1317/9090/8545/8546/6060/26660/3500 are all remapped or disabled).
- Defaults dangerous if left: CometBFT `rpc.unsafe`, `pprof_laddr` (0G ships `0.0.0.0:6060`), Prometheus `:26660` (all interfaces), Sei `[api] swagger = true`, EVM `cors "*"`, 0G geth-config exposes `engine` namespace + `AllowUnprotectedTxs`, docs' reth sample exposes `admin` + Engine API on 0.0.0.0. The toolkit patch overrides all of these; the admin Node-config editor must lock them.
- Public RPC rate limits (rpc.osmosis.zone 5 req/s/IP; sei-apis intermittently empty) — tip probe iterates a URL list and caches (`NetworkTipCache`).
- A non-validator node created by `init` owns a `priv_validator_key.json`; delete it after init (never share across nodes; 0G docs warn about double-sign slashing 2%).
- Snapshots from a single community mirror (Polkachu) are unauthenticated tarballs (no checksum published for Cosmos Hub per chain-registry `checksum_available:false`; Injective official has none either) — integrity relies on TLS and the node's own verification (AppHash/light client).
- Injective is closed-source binary-only (release zip), HTTP-only genesis URL -> pin sha256.
- Celestia multiplexer needs glibc >= 2.38 (Ubuntu 24.04); older hosts fail at start — add a host check (`ldd --version`).
- Injective: do NOT offer state sync (AppHash mismatch without wasm/).
- Disk growth: Injective 10–15 GB/day; Sei needs 2 TB NVMe + 256 GB RAM; 0G testnet 4 TB.

## Small shared-code changes this family needs (all in one PR ideally)
`SnapshotStreamExtract` compression (lz4/zst); `GitHubReleaseClient` semver-max + `tagSuffix`/`includePrerelease` from YAML; per-env `tipLagBlocks`; YAML per-env binding values (xrplevm `evm-chain-id`); agent height-probe kind `cometbft_status`; `CometbftTomlPatch`; optional promotion of `PolygonHeimdallStatus` to the family package.
