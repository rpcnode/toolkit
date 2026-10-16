# networks.md — one section per network (VERIFIED 2026-10-13/14 unless tagged INFERRED)

Ports are the plan blocks of `specs/PORTS.md` (appended there). Drafts: `<id>/{network.yml,clients.yml,node.service.tmpl}`.

| id | envs | client | status |
|---|---|---|---|
| polkadot | mainnet(polkadot), paseo, westend | polkadot + 2 workers | READY (linux-x86_64 only; aarch64 NEEDS-DECISION) |
| peaq | mainnet, agung | peaq-node (built) | NEEDS-DECISION (no upstream binary: build-from-source via `build:`) |
| near | mainnet, testnet | neard | READY |
| stacks | mainnet, testnet(krypton) | stacks-node | READY, with a Bitcoin-parent decision for mainnet |
| aleo | mainnet, testnet | snarkos | READY mainnet (x86_64); testnet NEEDS-DECISION (source build) |
| fuel | mainnet(ignition), testnet(ignition-test) | fuel-core | READY |
| hedera | — | none | DEFERRED |

---
## polkadot
- **Client / repo:** `polkadot`, `polkadot-prepare-worker`, `polkadot-execute-worker` — `paritytech/polkadot-sdk` releases, tag `polkadot-stable<YYMM>[-n]`. Latest `polkadot-stable2609` (2026-10-12): the three assets return HTTP 200 (124.6 / 22.6 / 20.6 MB). The three must be same version, same dir.
- **Artifact URL:** `https://github.com/paritytech/polkadot-sdk/releases/download/{tag}/polkadot` (+ `-prepare-worker`, `-execute-worker`). Bare binaries; each has `.asc`/`.sha256`. No linux-aarch64 asset (only `aarch64-apple-darwin`).
- **Chain selection:** `--chain polkadot|paseo|westend` (all built in, VERIFIED in `polkadot/cli/src/command.rs`; `paseo` id `paseo`, `westend` id `westend2`).
- **Ports:** mainnet 42200 rpc(HTTP+WS) / 42202 p2p (tcp+udp) / 42204 metrics · paseo 42210/42212/42214 · westend 42220/42222/42224.
- **Disks/size:** one NVMe role `state`. Pruned (paritydb) ~25 GB at snapshot → hint 300 GiB; archive rocksdb 4.17 TB → 6 TiB; 8 c / 32 GB (docs.polkadot.com RPC: 8+ c, 64 GB for heavy traffic).
- **Snapshot:** optional — snapshots.polkadot.io (official). Types `pruned` (22 GB paritydb) / `archive`; resolver + file-manifest runner in FAMILY.md §6. Warp sync works without it.
- **Local height:** `chain_getHeader` → `result.number` (hex). **Tip:** same, `https://rpc.polkadot.io`, `https://polkadot.api.onfinality.io/public`, `https://polkadot-rpc.n.dwellir.com` (mainnet); `https://pas-rpc.stakeworld.io`, `https://paseo-rpc.n.dwellir.com` (paseo); `https://westend-rpc.polkadot.io`, `https://westend.api.onfinality.io/public`, `https://westend-rpc.n.dwellir.com` (westend) — all returned a header.
- **Status:** READY. Open: aarch64 hosts, snapshot runner is new agent code.

## peaq
- **Client / repo:** `peaq-node` from `peaqnetwork/peaq-network-node`. Releases `peaq-v0.0.112/111/110` carry **0 assets**; the only artifact upstream publishes is the Docker image `peaq/parachain:peaq-v0.0.113` (Docker Hub, updated 2026-09-04). Git tag `peaq-v0.0.113` exists (no GitHub release object). Testnet tag `peaq-dev-v0.0.109`.
- **Artifact:** none downloadable → `build:` (`peaq.Dockerfile`, mirrors upstream scripts/Dockerfile: `cargo build --release --features on-chain-release-build`, Rust 1.82 from `rust-toolchain`, wasm32 target) → `peaq-node-<arch>`. Chain spec raw URL at the tag: `https://raw.githubusercontent.com/peaqnetwork/peaq-network-node/{tag}/node/src/chain-specs/peaq-raw.json` (2.5 MB; `relayChain: polkadot`, `paraId: 3338`, 3 bootnodes mpcn1-3.peaq.network). agung: `async-agung-parachain-specs.json` + `async-agung-relaychain-specs.json` from the `async-agung-chain-specs-01-2025` release.
- **Chain selection:** `--chain <file> --parachain-id 3338` (agung 2000); relay flags after `--`.
- **Ports:** mainnet 42140 rpc(HTTP+WS, Substrate + `eth_*`) / 42142 p2p / 42144 metrics / 42145 relay rpc(127.0.0.1) / 42146 relay p2p · agung 42150/42152/42154/42155/42156.
- **Disks/size:** `state` NVMe; peaq docs: 1 TB SSD, 8 GB RAM (INFERRED headroom: 4 c/16 GB). Archive state is peaq's own RPC setting.
- **Snapshot:** never (none published).
- **Prerequisite node:** Polkadot relay chain — default **embedded** (warp sync, second DB ~100 GB inside the node), optional **reuse** of a panel `polkadot` node through `--relay-chain-rpc-urls ws://127.0.0.1:42200` (needs the generic `parentNode` picker; INFERRED to work with `--rpc-methods safe`).
- **Local height:** `chain_getHeader` (same as polkadot). EVM tip alternative `eth_blockNumber` on the same port. **Tip:** `https://quicknode1.peaq.xyz`, `https://peaq.api.onfinality.io/public`, `https://peaq-rpc.publicnode.com` (mainnet); `https://peaq-agung.api.onfinality.io/public` (agung) — all returned the header.
- **Status:** NEEDS-DECISION — build-from-source is the only non-Docker route (cost: ~1 h Rust build on a ≥16 GB build agent, pinned by hand).

## near
- **Client / repo:** `neard` — `near/nearcore` GitHub releases have 0 assets; binaries are on the official build bucket: `https://s3-us-west-1.amazonaws.com/build.nearprotocol.com/nearcore/Linux-x86_64/{version}/neard` (2.13.4: 93.6 MB; 2.14.0-rc.3: 94.0 MB) and `Linux-aarch64/{version}/neard` (2.13.4: 78.5 MB). No `latest` object, no listing, `.sha256` 403.
- **Version:** mainnet = latest non-prerelease tag (`2.13.4`, 2026-09-03, a security upgrade "CODE_RED"), testnet = latest incl. rc (`2.14.0-rc.3`).
- **Chain selection:** `neard init --chain-id mainnet|testnet --download-genesis --download-config rpc` (config from `https://s3-us-west-1.amazonaws.com/build.nearprotocol.com/nearcore-deploy/<chain>/rpc/config.json`, VERIFIED 200, 5.3 KB; genesis 324 KB / 18 KB).
- **Ports:** mainnet 42080 rpc / 42082 p2p / 42084 prom · testnet 42090/42092/42094 (config defaults 3030 / 24567 overridden by `run --rpc-addr --network-addr --rpc-prometheus-addr`).
- **Disks/size:** `state` NVMe; official RPC: 8 c / 32 GB / 4 TB NVMe recommended (min 16 GB, 2.5 TB). Testnet ~1 TB (INFERRED).
- **Snapshot:** never — free snapshots deprecated 2025-06-01; bootstrap by epoch sync + decentralized state sync after refreshing `network.boot_nodes` from the public `network_info` (official guide).
- **Local height:** `status` → `result.sync_info.latest_block_height` (+ `syncing`). **Tip:** `https://rpc.mainnet.near.org`, `https://rpc.mainnet.fastnear.com`, `https://near.drpc.org` · `https://rpc.testnet.near.org`, `https://rpc.testnet.fastnear.com`, `https://near-testnet.drpc.org` (all answered `status`; `near.lava.build` is discontinued, `1rpc.io/near` lacks `status`).
- **Status:** READY.

## stacks
- **Client / repo:** `stacks-node` in `stacks-network/stacks-core` release zip: `https://github.com/stacks-network/stacks-core/releases/download/{tag}/linux-glibc-x64.zip` (4.0.5: 218 MB; arm64 202 MB; musl variants; `CHECKSUMS.txt` sha512). Zip: clarity-cli marf-squash stacks-cli stacks-inspect stacks-node stacks-signer. `stacks-blockchain-api` (hirosystems, Postgres) is not needed for RPC — out of scope.
- **Chain selection:** `[burnchain] mode = "mainnet"` (default config) or `"krypton"` (testnet; Hiro-hosted regtest Bitcoin, no local Bitcoin) in the tag's `sample/conf/<mainnet|testnet>-follower-conf.toml`. Signet sample is a template for private networks → not an env.
- **Ports:** mainnet 42500 rpc / 42502 p2p / 42504 metrics · testnet 42510/42512/42514 (upstream 20443/20444/9153).
- **Disks/size:** `state` NVMe: mainnet hint 1 TiB (archive alone 275 GB .tar.zst; extracted size INFERRED ≲ 700 GB), 4 c / 16 GB (INFERRED); testnet 64 GiB.
- **Snapshot:** mainnet **required in practice**, testnet optional — Hiro archive (see FAMILY §6). 274.7 GB / 5.9 GB.
- **Prerequisite node:** Bitcoin (mainnet). Model: reuse the panel's Bitcoin mainnet node (same host 127.0.0.1:8332/8333) via the `parentNode` picker + `burnchain_*` install options; bundling a second bitcoind is rejected (disk + duplicate IBD). Credentials need `rpcauth`/`rpcuser` on the Bitcoin side (cookie unsupported by stacks-node).
- **Local height:** `GET /v2/info` → `stacks_tip_height`. **Tip:** `https://api.hiro.so/v2/info`, `https://api.mainnet.hiro.so/v2/info` · `https://api.testnet.hiro.so/v2/info` (both answered; hiro testnet runs stacks-node 4.0.1, mainnet 4.0.4).
- **Status:** READY (mainnet gated on the Bitcoin-parent + zstd/zip agent work).

## aleo
- **Client / repo:** `snarkos` — `ProvableHQ/snarkOS`. Mainnet: `https://github.com/ProvableHQ/snarkOS/releases/download/{tag}/aleo-{tag}-x86_64-unknown-linux-gnu.zip` (v4.12.0, 2026-10-13: 36.8 MB; zip = single `snarkos`, 113 MB). Linux x86_64 only. Testnet: tags `testnet-vX.Y.Z` are prereleases with **0 assets**, built against another snarkVM (V22 testnet-only consensus change at height 20,415,000) → source build (`snarkos.Dockerfile`, plain `cargo build --release`, same steps as upstream release.yml).
- **Chain selection:** `--network 0` mainnet / `1` testnet (`2` canary not shipped).
- **Ports:** mainnet 41030 REST / 41032 p2p(`--node`) / 41034 prom · testnet 41040/41042/41044 (upstream 3030 / 4130).
- **Disks/size:** roles `ledger` NVMe + `nodedata` SSD. Official README (client): 24 c (32+ preferred), 128 GiB RAM, 2 TB disk (4+ preferred), 250 Mbps — docs.aleo.org figures are lower (32 c/32 GB/300 GB, unverified: site is behind Cloudflare) → shipped hints follow the README.
- **Snapshot:** never — snarkOS prefetches blocks from `https://cdn.provable.com/v0/blocks/<net>` by itself.
- **Local height:** `GET /<net>/block/height/latest` → plain integer. **Tip:** `https://api.explorer.provable.com/v1/mainnet/block/height/latest`, `https://api.explorer.provable.com/v1/testnet/block/height/latest` (also `/v2/…` and `/v1/mainnet/latest/height` return the number; `api.explorer.aleo.org` is empty).
- **Status:** mainnet READY; testnet NEEDS-DECISION (build vs mainnet-only).

## fuel
- **Client / repo:** `fuel-core` — `FuelLabs/fuel-core` v0.48.3 (2026-08-27): `fuel-core-{version}-x86_64-unknown-linux-gnu.tar.gz` (30.6 MB), `-aarch64-unknown-linux-gnu.tar.gz` (28.9 MB). Tar: `fuel-core`, `fuel-core-keygen`, `fuel-core-wasm-executor.wasm`.
- **Chain selection:** `--snapshot <dir>` with the chain config: `https://chain-config-assets.fuel.network/{ignition|ignition-test}/{metadata.json,chain_config.json,state_config.json,state_transition_bytecode.wasm}` (mirror: `FuelLabs/chain-configuration`). Mainnet chain id 9889 (Ignition).
- **Ports:** mainnet 41540 GraphQL / 41542 p2p · testnet 41550 / 41552 (upstream 4000 / 30333; metrics are `/v1/metrics` on the GraphQL port; `--rpc-port` gRPC default 4001 must stay off).
- **Disks/size:** `state` NVMe; official: 8 c / 16 GB / 1 TB recommended (2 c / 8 GB / 500 GB min).
- **Snapshot:** never (none published; genesis P2P sync, mainnet at block ~66.7 M, testnet ~75.8 M).
- **Prerequisite:** an Ethereum JSON-RPC for the relayer (mainnet → Ethereum mainnet, testnet → Sepolia) — existing `l1Parent` picker (rpc only; public default `ethereum-rpc.publicnode.com` / `ethereum-sepolia-rpc.publicnode.com`), or a panel Ethereum node on the same host.
- **Local height:** GraphQL `{ chain { latestBlock { height } } }` → `data.chain.latestBlock.height`. **Tip:** `https://mainnet.fuel.network/v1/graphql`, `https://testnet.fuel.network/v1/graphql` (`api.fuel.network` empty).
- **Status:** READY.

## hedera — DEFERRED
- **Consensus node:** `hiero-ledger/hiero-consensus-node` is open source (v0.77.5, 2026-10-12, no release assets), but the Hedera mainnet is permissioned: Hedera docs state node requirements apply only to Council members; there is no published route for non-council operators. testnet/previewnet are also council-run (INFERRED, no operator docs found).
- **Mirror node** (`hiero-ledger/hiero-mirror-node` v0.164.1, release has no assets; deployment = Helm/Docker, Java + PostgreSQL/Citus): needs requester-pays GCS/S3 buckets (operator pays egress; Hedera's guide estimates ~USD 515/month), a database (full mainnet history ~50 TB) — not a systemd binary install.
- **JSON-RPC relay** (`hiero-ledger/hiero-json-rpc-relay` v0.79.0): Docker/Node service that needs a mirror node REST + a funded operator account — it proxies, it is not a node.
- **If ever added** (an RPC-gateway recipe, not a node): height probe mirror REST `GET /api/v1/blocks?limit=1&order=desc` → `blocks[0].number` (VERIFIED on mainnet.mirrornode.hedera.com, 101087255); relay `eth_blockNumber` (VERIFIED on mainnet.hashio.io/api). Chain id 295. Ports block 41630-41659 stays unused.
