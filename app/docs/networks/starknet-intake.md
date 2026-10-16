# Starknet — intake

Status: **implemented**, not run on a live host (no Linux host in the build environment). Envs `mainnet`, `sepolia`. Client: Nethermind Juno.
Every fact below is tagged VERIFIED (how) or INFERRED.

## 1. Scope
Full Starknet RPC node (Juno, one static Go binary). Out of scope: sequencer mode, p2p, gRPC, pprof, the log-level update port (all off by default and kept off), Pathfinder (decision D-7: Juno).

## 3. Client
GitHub `NethermindEth/juno` latest release (stock GitHub source, VERIFIED `v0.16.8`, 2026-10-06, no prerelease flag). Assets `juno-v0.16.8-linux-{amd64,arm64}.zip` (62.8 / 58.4 MB, VERIFIED `gh api` + download):
each zip holds one static ELF named `juno-<tag>-linux-<arch>` plus `<name>.sha256` (VERIFIED: the sum matches `shasum -a 256`). On disk the artifact is versionless
(`juno-x86_64-linux.zip` / `juno-aarch64-linux.zip`). `StarknetHostBinaries` unzips in the JVM (no `unzip` on the host), verifies the in-zip `.sha256`, writes `bin/juno`
(re-extracts when a newer zip lands after a client update). `inferLaunch("juno")` = `bin/juno`.

## 4. Ports
mainnet http 42530 / ws 42531 / metrics 42534; sepolia 42540 / 42541 / 42544. Metrics bound to 127.0.0.1.

## 5. Disks
Role `state` (nvme) = `--db-path`; the snapshot is streamed into it. Sizes (VERIFIED by HEAD on 2026-10-14): mainnet 549 GB tar.zst, mainnet-pruned 102 GB, sepolia 100 GB, sepolia-pruned 26 GB.
Unpacked size is INFERRED (~1.2x for full); hints 1024 / 256 GiB (mainnet), 256 / 64 (sepolia).

## 6. Juno flags (Juno v0.16.8 `docs/docs/_config-options.md`, VERIFIED)
`--network`, `--db-path`, `--http --http-host=0.0.0.0 --http-port`, `--ws --ws-host=0.0.0.0 --ws-port`, `--metrics --metrics-host=127.0.0.1 --metrics-port`, `--log-level=info`.
Capacity (literal bindings in network.yml, checked by `StarknetUnitBodiesTest`): `--db-cache-size=4096` (default 1024; docs suggest 2048-4096), `--rpc-max-block-scan=10000` (default unlimited),
`--rpc-request-timeout=30s` (1m), `--rpc-max-concurrent-requests=4096` and `--rpc-max-request-queue=4096` (256000 each), `LimitNOFILE=1048576`. The numbers are judgement, the flags exist.
Not set, defaults kept off: `--pprof`, `--grpc`, `--p2p`, `--http-update-port` (0), `--rpc-cors-enable` (false), `--disable-l1-verification`.

**Pruning.** `--prune-mode[=N]` (bare = 128 blocks; VERIFIED in the options table and in `cmd/juno/juno.go`: pruning is on iff the flag is set, and it requires L1 verification).
The upstream docs start every pruned snapshot with a bare `--prune-mode`, so a `pruned` snapshot runs with `--prune-mode=100000` (Juno bare default is 128: too little for an RPC provider; ~1 week at ~6 s blocks; extra disk NOT measured) unless the `prune_blocks` option sets a window; a `full` snapshot keeps all history unless `prune_blocks` is set.
Pruning is irreversible. Whether a pruned snapshot would also run without `--prune-mode` is INFERRED (nothing in `node.go` forbids it); the default follows upstream.

## 7. Ethereum L1 WebSocket (`--eth-node`)
Juno needs a ws:// or wss:// Ethereum endpoint to verify against the Starknet core contract. Public defaults `wss://ethereum-rpc.publicnode.com` (mainnet) and `wss://ethereum-sepolia-rpc.publicnode.com`
(VERIFIED: both answer the WebSocket upgrade with 101). Like the Xai parent RPC it is NOT in argv / unit: `EnvironmentFile=<node_dir>/.toolkit/juno.env` (0600) with `JUNO_ETH_NODE="…"`
(Juno reads `JUNO_<FLAG>` variables: VERIFIED in `cmd/juno/juno.go`, `AutomaticEnv` + `SetEnvPrefix("JUNO")`). An http(s) URL is refused at Start (Juno would not accept it).
Start's Test connect uses the new kind `eth_ws`: the panel maps ws(s):// to http(s):// and sends `eth_blockNumber` to the same host (proves the host speaks JSON-RPC, not that its WebSocket works).
The panel's own Ethereum nodes are not offered as parents: the Ethereum catalog has no WebSocket port.

## 8. Snapshot
`required`. `https://juno-snapshots.nethermind.io/files/{mainnet,mainnet-pruned,sepolia,sepolia-pruned}/latest` answers **200 directly** with the newest dated tar.zst
(`Content-Disposition: juno_mainnet_v0.16.8_16283169.tar.zst`; VERIFIED by `curl -I`; **no redirect**; `Accept-Ranges: bytes`, range request -> 206; zstd magic `28 b5 2f fd`; the dated file name is NOT downloadable on its own: 404).
So `StarknetSnapshotResolver` returns the `/latest` URL as the archive URL (size by HEAD) with `streamUnpack = true`; the agent streams HTTP (resumable with Range + If-Range) -> `tar -I zstd -xf -` into the state role.
Because the URL has no `.zst` suffix, `SnapshotStreamExtract.streamTarArgs` recognises the official mirror (`StarknetSnapshotResolver.isOfficialUrl`) and picks zstd. Host package: `zstd` (hostPackages).
Archive layout (VERIFIED on the first 20 MB of sepolia-pruned): flat `./<n>.sst` Pebble files at the top level, no wrapper directory. A restore therefore also clears stale top-level Pebble files
(`SnapshotStreamExtract.isKeyValueDbFile`) which the generic "keep top-level regular files" rule would otherwise leave behind.
The file name carries the Juno version that wrote it (pruned ones were v0.16.7 on 2026-10-14, full v0.16.8); the toolkit installs the newest release, so the restore always meets a Juno >= its writer.

## 9. Height / tip / lag
Local: `starknet_blockNumber` on 127.0.0.1 -> `{"jsonrpc":"2.0","result":16337586,"id":1}`, a plain JSON integer, NOT hex (VERIFIED on starknet-rpc.publicnode.com and api.zan.top; an empty DB answers an error -> no reading);
progress from `starknet_syncing` (`false` or `{current_block_num, highest_block_num}` integers; VERIFIED in `rpc/v10/sync.go`). Tip: the same call on `publicTip.urls`
(`starknet-rpc.publicnode.com`, `api.zan.top/public/starknet-mainnet`; sepolia `starknet-sepolia-rpc.publicnode.com`). Dead (VERIFIED earlier): blastapi, lava.build, free-rpc.nethermind.io. `tipLagBlocks: 5`.
Juno also serves `/ready/rpc` and `/ready/sync` (VERIFIED in `node/node.go`; `/ready` = sync, tolerance `--readiness-block-tolerance` 6): not used.

## 10. Admin
Parent row for `starknet` in the node's right pane (`lib/l1Parent.ts`: ws, no beacon, test via `eth_ws`); Start preview rows `snapshot`, `prune-mode` (`prune_blocks`), `eth-node` (`l1_rpc`). Prod preset `starknet`.

## 11. Open
First start on a real host; Juno's resident memory with `db-cache-size=4096` under load; the unpacked sizes; whether the pruned snapshot is usable without `--prune-mode`.
