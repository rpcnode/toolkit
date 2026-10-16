# OP Stack family (unichain, ink, soneium, worldchain, lisk, mode, zora) — what shipped

Code: `chains/opstack/infrastructure` (one shared package; the per-network data is `OpstackNetworks`), resources `chains/opstack/run.sh.tmpl`
(the one run script) + `chains/<id>/{network.yml,clients.yml,node.service.tmpl,*.Dockerfile}`.
`chains/optimism` is a separate package and was **not touched** (its run script still lacks the discv5 / IPC fix, see below).
Research + verification notes: `research/opstack/{FAMILY,networks,risks}.md` (written before implementation).

## Shipped
| id | envs | execution client | snapshot (default `restore`) | op-node sync | status |
|---|---|---|---|---|---|
| unichain | mainnet, sepolia | op-reth `op-reth/v2.6.0` (built) | Alchemy R2 reth datadir, **third party, no checksum** (206 / 216 GB .tar.gz) | execution-layer | READY |
| ink | mainnet, sepolia | op-reth | Alchemy R2, **third party, no checksum** (201 / 163 GB) | consensus-layer + Ink's published CL peers | READY |
| soneium | mainnet, sepolia (= Minato) | op-reth | mainnet: Alchemy R2, **third party, no checksum** (715 GB); Minato: none | consensus-layer | READY (Minato: no snapshot) |
| worldchain | mainnet, sepolia | `world-chain` release tarball (v2.4.5 at writing; no build) | **official** S3 `reth_{full,archive}.tar.lz4` (892 GB / 1.84 TB; testnet 18.7 / 27 GB), no checksum published | execution-layer | READY |
| lisk | mainnet only | op-reth | **official** Gelato ChainSnap `.tar.lz4` + `.sha256` (41 / 61 GB), checked | consensus-layer + Lisk's CL peer + bootnode | READY |
| mode | mainnet, sepolia | op-reth | **none public** — only `snapshot_url`, else weeks of L1 derivation | consensus-layer + Conduit CL peer + EL trusted peer (mainnet) | PARTIAL |
| zora | mainnet, sepolia | op-reth | **none public** — same as Mode | consensus-layer + Conduit peers (mainnet) | PARTIAL |

Deferred (not here): celo, mantle (need forks / a user decision), redstone (shut down), blast (shutting down), b3 (archived), cyber, fraxtal, hemi.

## How it works
- One systemd unit per node → `bin/rpcnode-<id>-run.sh` (written 0700: it holds the L1 endpoint and the operator's snapshot URL). It (1) installs the execution
  client for this CPU — `op-reth-<arch>` prebuilt by the panel's build fleet (`build:` Dockerfile beside `clients.yml`, no Docker on the node), else compiled from the
  pinned monorepo tag; `world-chain` from `world-chain-<arch>.tar.gz` (GitHub release, latest `vX.Y.Z`, `proofs/v…` and `-rc` excluded); op-node the same as op-reth;
  (2) restores the datadir snapshot once; (3) creates `jwt.hex` (0600); (4) supervises the EL first, then op-node.
- Start args: `--toolkit-env`, `--toolkit-mode=full|archive`, `--toolkit-restore=snapshot|none`, `--toolkit-snapshot-url`, `--toolkit-l1-rpc/-beacon`, disk roles.
  Install options: `node` (full|archive), `restore` (snapshot|none; **not** called `snapshot`: the wizard treats `snapshot*` groups as its own snapshot-type step),
  `snapshot_url` (text), `l1_rpc`, `l1_beacon`. A `snapshot_url` wins over the built-in source; `restore=none` wins over a URL.
- `snapshot: never` in `network.yml`: the restore is owned by the run script (like optimism), not by the toolkit's snapshot jobs. Providers: `alchemy` (page scrape,
  link `…/rpc-platform/<chain>/<main|sep>/reth/reth-<ts>.tar.gz`), `gelato` (index page, newest epoch + `.sha256`), `fixed` (World's S3), operator URL.
  Disk free > archive×1.05 → resumable `aria2c` download (+ sha256 when published), extract, delete the archive; otherwise the archive is **streamed** (not resumable).
  The wrapper directory is detected from the first member (`./`, `db`, `static_files` → none; `reth/` → strip 1; for a stream from the first 4 MiB of the remote archive).
  After the restore the provider's `discovery-secret` (devp2p node key) and `jwt.hex` are deleted. An existing `db/mdbx.dat` is never restored over; an interrupted
  extraction (`.toolkit/snapshot-<kind>.inprogress`) is redone.
- Production config (literal `clientConfig.bindings`, identical to the script — `OpstackCatalogTest`): public `--http.api/--ws.api=eth,net,web3` on `0.0.0.0`;
  Engine API, op-reth `--metrics`, op-node `--rpc.addr` and `--metrics.addr` on `127.0.0.1`; `--rollup.disable-tx-pool-gossip`; prune window 30 days
  (`--prune.minimum-distance` + `--prune.{receipts,account-history,storage-history}.distance` = 2 592 000 at 1 s, 1 296 000 at 2 s; bodies never pruned);
  op-node `--verifier.l1-confs=4`; `LimitNOFILE=1048576`. Prod preset (`admin/src/lib/prodPresets.ts` `OPSTACK`): `rpc.max-connections 3000`,
  `rpc.max-response-size 64`, `rpc.max-blocks-per-filter 50000`, `rpc.evm-memory-limit`, `rpc-cache.*`, `max-{in,out}bound-peers`.
- **Port collision fix** (op-reth runs discv5 on UDP 9200 and an IPC socket at `/tmp/reth.ipc` by default — both shared by every reth on a host): each node passes
  `--discovery.v5.port=<its +8>` and `--ipcdisable`. VERIFIED in the reth trees of op-reth v2.6.0 (`ethereum-optimism/reth@0fbe428`: `network.rs` `discovery.v5.port`,
  `rpc_server.rs` `ipcdisable`, `DEFAULT_IPC_ENDPOINT = "/tmp/reth.ipc"`) and of world-chain v2.4.5 (`op-rs/reth@aef8d3e`). discv4 is off by default
  (`--rollup.discovery.v4` false), so `--discovery.port` is inert but harmless. `chains/optimism` should get the same two flags (not done: out of scope).
- Height: `eth_blockNumber` on the EL http port; sync % from op-reth's pipeline log lines, then head age ≤ 30 s ⇒ synced. Tip: first answering `publicTip` URL.
- `tipLagBlocks` (sync → active needs `tip - height <= tipLagBlocks`): **12** for unichain and ink (1 s blocks: the default 3 blocks = 3 s is inside the jitter of two
  RPC providers; 12 s = one L1 slot, the node-side 30 s height push and the 30 s tip cache add at most that much), default 3 (6 s) for the 2 s chains.

## Verified against upstream (2026-10-13/14)
- op-reth `v2.6.0` args (`--rollup.sequencer`, `--rollup.disable-tx-pool-gossip`, `--trusted-peers`, `--prune.*`, `--ipcdisable`, `--discovery.v5.port`, `--metrics`, `RPC` limits used by the
  preset), op-node `v1.19.9` flags (`--syncmode` consensus-layer|execution-layer, `--p2p.static`, `--p2p.bootnodes`, `--verifier.l1-confs`, `--l2.enginekind`, `--network`, peerstore paths).
- World Chain v2.4.5: release assets (x86_64 + aarch64 `.tar.gz`), CLI reference (`--rollup.sequencer` with alias `--rollup.sequencer-http`, `--flashblocks.enabled`),
  op-rs/reth flags used here. Registry names in op-reth (`key_for!`) and the registry `block_time` / `sequencer_rpc` of every env.
- Snapshot files: Alchemy pages (slugs `unichain|ink|soneium` and `unichain-testnet|ink-testnet`), first archive members of unichain / ink / soneium (`./` root), World S3 HEAD (all 4 URLs 200,
  ranges) + testnet first members (`reth/…`), Lisk Gelato index + first members (`./db/database.version`).
- Run script: `bash -n`; the restore section (all archive formats, wrapper / no wrapper, download and streaming path, marker handling) was run in an Ubuntu 22.04 container against
  local archives; `resolve_snapshot` is unit-tested against page excerpts of the real providers.

## INFERRED / unverified (say so before relying on it)
- **Nothing was run on a Linux node**: no op-reth / op-node / world-chain process was started, no real snapshot restored (hundreds of GB).
- Whether the Alchemy / World / Gelato datadirs open with the pinned op-reth 2.6.0 / world-chain 2.4.5 (reth storage version) — only Lisk's `database.version` was read (=2).
  If a node refuses the DB: `restore=none`.
- Alchemy discovery is a scrape of a public page; its R2 account id or page layout may change (the script then retries every 5 min and logs it). No checksum anywhere on that path.
- `consensus-layer` for ink / soneium (no operator publishes EL peers, `execution-layer` needs them); for Lisk the operator itself recommends full/archive nodes over snap sync.
  `execution-layer` for unichain / worldchain follows their official composes. Conduit's own default for its chains is `execution-layer` + their EL peer; Mode / Zora use
  `consensus-layer` here with the EL peer as `--trusted-peers` (reth then back-fills a gap from it) — INFERRED to work.
- Peers (Ink, Lisk, Conduit's Mode / Zora) are the operators' published ones and their IPs may rotate. Mode / Zora Sepolia have **no** known peers (Conduit's API has no Sepolia network).
- Flashblocks: on for World Chain mainnet only (official compose enables it everywhere; sentry peers are default-mainnet in the tests); off on Sepolia.
- op-node `v1.19.9` with World Chain (their compose runs 1.19.5) — registry config equals their rollup.json (operator-verified), not run.
- Disk / CPU / RAM numbers are estimates from snapshot sizes ×1.3-1.5, not measured.

## Not done
- Shared Dockerfiles: copied per network (interim). A shared `dockerfile: "opstack:op-reth.Dockerfile"` would also need the build job id and the output dir (`clients/<network>/<env>/`) to be
  shared; not trivial in `FleetClientBuilder` (job id is `network-env-program-tag-arch`), so each of the 6 op-reth networks × 2 envs is compiled separately on the build fleet.
- `chains/optimism` still binds op-reth metrics / op-node RPC on `0.0.0.0`, serves `debug,trace,txpool` and runs the default discv5 port / IPC path.
- Auto-detection of a stale Alchemy snapshot (the page is rewritten ~daily; a very old one just means more catch-up).
- celo, mantle, redstone, blast, b3, cyber, fraxtal, hemi (see above).
