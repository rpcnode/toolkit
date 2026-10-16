# Xai — intake

Status: **implemented**, not run on a live host (no Linux host in the build environment). Env `mainnet` only (no Xai Sepolia is documented).
Every fact below is tagged VERIFIED (how) or INFERRED.

## 1. Scope
Xai is an Arbitrum Orbit AnyTrust chain (chain id 660279) whose PARENT chain is Arbitrum One (42161): an L3. It runs stock Nitro, so it reuses the
Arbitrum pieces (`ArbHostBinaries` unpack of the Nitro tarball, `ArbNodeHeightProbe`, `ArbNetworkTipProbe`, `ArbClientReleaseResolver`) and has no
beacon / blob API. Out of scope: sequencer, validator, archive mode (not offered: no archive snapshot exists and a genesis replay as archive is not planned).

## 3. Client
Nitro from the Docker Hub registry over HTTP (`docker://offchainlabs/nitro-node:{tag}`, no Docker daemon), tag from the newest `OffchainLabs/nitro` release
(`XaiClientReleaseResolver` -> `ArbClientReleaseResolver`; VERIFIED v3.12.1 = `v3.12.1-70fa99a`, 2026-10-02).
The chain-info JSON has no public CDN copy: it is a classpath resource (`resources/chains/xai/xai-chain-info.json`, from OffchainLabs/community-helm-charts
`charts/nitro/README.md` "Xai" section, VERIFIED identical to the README text) written by the starter to `<node_dir>/.toolkit/xai-chain-info.json`.
Cross-checks (VERIFIED): `eth_chainId` of `https://xai-chain.net/rpc` = 0xa1337 = 660279; the feed `wss://xai-chain.net/feed` answers the WebSocket upgrade (101);
`https://xai-chain.net/das-servers` lists 6 REST servers; `eth_getCode` of the sequencer inbox on Arbitrum One is non-empty (research session).

## 4. Ports
http 42710, ws 42711, metrics 42714 (127.0.0.1). Nitro opens no P2P port (feed + parent RPC are outbound).

## 5. Disks
Role `execution` (nvme) = `--persistent.chain`. Hints (INFERRED, gitbook is from the Nitro v2.3.3 era): disk 2048 GiB, full 1024 GiB, 4 cores, 16 GB.

## 6. Nitro flags (checked against the Nitro v3.12.1 source: `cmd/nitro`, `cmd/genericconf`, `arbnode/node.go`, `daprovider/anytrust`)
`--chain.info-files=<file> --chain.name=Xai` (`ProcessChainInfo` selects by name), `--node.feed.input.url=wss://xai-chain.net/feed`,
`--execution.forwarding-target=https://xai-chain.net/rpc` (Nitro refuses to start without it), AnyTrust:
`--node.da.anytrust.enable --node.da.anytrust.rest-aggregator.enable --node.da.anytrust.rest-aggregator.online-url-list=https://xai-chain.net/das-servers`.

**The helm README is stale.** It lists `node.data-availability.sequencer-inbox-address` and `node.data-availability.parent-chain-node-url`: neither flag exists
in Nitro >= 3.10 (VERIFIED: not registered in `daprovider/anytrust/config.go` at v3.10.0 / v3.11.0 / v3.11.5 / v3.12.1; the sequencer inbox comes from the
chain-info `rollup` block and the reader from `--parent-chain.connection.url`). `node.data-availability.*` is the DEPRECATED alias (migrated at startup with an
ERROR log); the unit uses the `node.da.anytrust.*` names that exist since 3.10. A Nitro older than 3.10 is not supported by this unit.

Public surface: `--http.api` / `--ws.api` = `net,web3,eth` (no `debug`), RPC and WS on 0.0.0.0, metrics `--metrics-server.addr=127.0.0.1`.
Capacity (all also literal bindings in network.yml, checked by `XaiUnitBodiesTest`): `--rpc.max-batch-response-size=25000000`,
`--execution.rpc.filter-log-cache-size=256`, `--persistent.handles=16384`, `LimitNOFILE=1048576`.

## 7. Parent-chain RPC (secret handling)
The Arbitrum One RPC usually carries a provider key. It is NOT in argv or the unit: `--conf.env-prefix=NITRO` + `EnvironmentFile=<node_dir>/.toolkit/xai-parent.env`
(mode 0600, created 0600) with `NITRO_PARENT__CHAIN_CONNECTION_URL="<url>"` (Nitro maps `__` -> `-` and `_` -> `.`; VERIFIED in `confighelpers.loadEnvironmentVariables`).
`--toolkit-l1-rpc` is dropped from the args before `launch.json` (world-readable) is written; a restart reads the URL back from the env file.
Default when nothing is set: `https://arb1.arbitrum.io/rpc` (public, rate limited — INFERRED stall behaviour from the research, see risk R-7).

## 8. Snapshot
`never`. No Xai snapshot exists (VERIFIED: the Xai gitbook lists none, `snapshot.arbitrum.foundation/xai/` 404). The first sync replays the chain from genesis through the
Arbitrum One inbox and the DAS: days, and it hammers the parent RPC with `eth_getLogs` — **use your own / a private Arbitrum One RPC** (an active `arb` node of this panel is ideal:
the wizard's parent picker lists them). The `arb_l1` diagnostic ("error reading inbox") applies and warns when the parent RPC stalls.

## 9. Height / tip / lag
Local: `eth_blockNumber` (+ `eth_syncing` / head age via `ArbNodeHeightProbe`: Nitro reports `eth_syncing=false` while still behind). Tip: `https://xai-chain.net/rpc` (VERIFIED `0x831f856`).
~2.2 s per block (research, avg of the last 10k blocks): `tipLagBlocks: 30` (~66 s) so sync -> active does not flap.

## 10. Admin
Parent picker: `lib/l1Parent.ts` (`xai` = Arbitrum One, no beacon), `GET /api/networks/ethereum/nodes?network=arb` lists `arb` nodes (`ListEthereumNodesUseCase(parent = ARB)`), Node right pane edits `l1_rpc` only.
Prod preset `xai` (flag overrides) restates the unit's values.

## 11. Open
First start / sync duration on a real host; whether Arbitrum One's public RPCs tolerate the inbox reader at all; Xai owner migrating contracts (re-check the chain-info on every Nitro release); the feed URL is from the helm README (answers the upgrade, behaviour under load not tested).
