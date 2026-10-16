# evml1 — per-network sheet (verified 2026-10-13)

V = VERIFIED (fetched, HTTP-checked or read from the upstream source/docs), I = INFERRED. Ports are from `specs/PORTS.md` (allocations appended there). Draft files are in `<id>/`.

| id | label | envs (ids) | client | status |
|---|---|---|---|---|
| gnosis | Gnosis Chain | mainnet, chiado | Nethermind 2.1.0 + Lighthouse 8.2.3 | **READY** |
| joc | Japan Open Chain | mainnet, testnet | Nethermind 2.1.0 (built-in joc configs, Clique) | **READY** |
| flare | Flare | flare, songbird, coston2 | go-flare v1.14.0 (avalanchego fork) | **READY** (x86_64 only) |
| sonic | Sonic | mainnet, testnet | 0xsoniclabs/sonic v2.2.5 (`sonicd` + `sonictool`) | **READY** (x86_64 only) |
| kaia | Kaia | mainnet, kairos | kaiachain/kaia v2.2.2 `ken` (binaries on packages.kaia.io) | **READY** |
| vana | Vana | mainnet, moksha | patched geth v1.16.8 + vana-com/prysm v7.1.8-vega.1 | **READY** (mainnet x86_64 only) |
| tempo | Tempo | mainnet, moderato | tempoxyz/tempo v1.16.0 `tempo node --follow` | **READY** |
| berachain | Berachain | mainnet, bepolia | bera-reth v1.4.4 + beacond v1.4.1 (bepolia v1.4.2-rc.0) | **READY** |
| data | DATA Network (ex-Story) | mainnet, aeneid | story-geth v1.2.3 + story v1.8.0 | **READY** |
| fluent | Fluent | mainnet, testnet | fluentbase v1.5.1 `fluent` | **READY** (x86_64 only) |
| gravity | Gravity (L1) | mainnet, testnet (Longevity) | Galxe/gravity-sdk v1.9.3 `gravity_node` | **READY** (x86_64 only, heaviest config) |
| shibarium | Shibarium | shibarium, puppynet | shibaone/bor + heimdall `-bone` tags (Polygon v1 fork) | **NEEDS-DECISION** |
| moca | Moca Chain | mainnet, testnet | mocachain/moca v1.4.1 `mocad` (Cosmos EVM) | **NEEDS-DECISION** |
| fantom | Fantom Opera (legacy) | mainnet | Fantom-foundation/Sonic v1.2.1-k (source only) | **DEFERRED** (separate network, NOT an alias of sonic) |
| plasma | Plasma (XPL, plasma.org / .to) | mainnet, testnet | Reth v1.11.3 + closed `plasma-consensus-public` | **DEFERRED** |
| monad | Monad | mainnet, testnet | category-labs `monad` apt package | **DEFERRED** |
| megaeth | MegaETH | mainnet, testnet | none for a full node | **DEFERRED** |
| ault | Ault Blockchain | mainnet, testnet | `aultd` (not public) | **DEFERRED** |
| sahara | Sahara AI | testnet (mainnet n/a) | saharad (docker image only) | **DEFERRED** |

---
## gnosis — READY
- **Envs/ids:** mainnet = Gnosis Chain (id 100), `chiado` = testnet (10200).
- **Client:** NethermindEth/nethermind (V: 2.1.0, 2026-10-01 stable). Gnosis docs list Nethermind first and call it the supermajority EL (V); Erigon (v3.7.1 tar.gz amd64/arm64 V) is the alternative; `gnosischain/reth_gnosis` v2.2.0 has **no release assets** (V: build from source only) → not offered. CL: sigp/lighthouse v8.2.3 (V), `--network gnosis|chiado`.
- **Artifacts (V):** `https://github.com/NethermindEth/nethermind/releases/download/2.1.0/nethermind-2.1.0-b3e7e84c-linux-x64.zip` (113 MB; arm64 109 MB). The hash is not derivable → `urlTemplate: "github-asset:nethermind-*-linux-x64.zip"` (FAMILY §1.1.4). Zip = whole app (`nethermind`, `Nethermind.Runner`, dlls, `configs/gnosis.json`, `configs/chiado.json`, `gnosis_archive`, `chiado_archive`, `plugins/`, `NLog.config`, `Data/`; V by listing). Lighthouse: `…/lighthouse-{tag}-x86_64-unknown-linux-gnu.tar.gz` (as chains/ethereum).
- **Chain selection:** `-c gnosis | gnosis_archive | chiado | chiado_archive` (V in repo configs: gnosis.json sets FastSync+SnapSync, `Pruning.Mode=None` only in the archive file).
- **CL:** Lighthouse checkpoint sync `https://checkpoint.gnosischain.com` / `https://checkpoint.chiadochain.net` (V: both serve `/eth/v1/beacon/states/finalized/finality_checkpoints`; root `/` 404s, normal).
- **Ports (mainnet 41570 / chiado 41580):** +0 http, +1 ws, +2 p2p tcp+udp, +3 engine, +4 metrics, +5 beacon http, +6 CL p2p, +7 CL QUIC (`--quic-port`), +8 CL metrics.
- **Disks/size:** execution NVMe + consensus SSD. Docs floor 4 threads/16 GB/NVMe (V); drafts: 8 cores/32 GB, mainnet hint 2048 GiB, full ~1024 (I), archive 4096 (I); chiado 512/256/1024 (I).
- **Snapshots:** never (snap sync + checkpoint sync). No official Nethermind/Gnosis DB snapshot found (searched docs + web). Erigon has its own torrent snapshots (not used).
- **Tips (V, answered):** `https://rpc.gnosischain.com`, `https://gnosis-rpc.publicnode.com` (0x2e7bb83 = 48.74 M), `https://rpc.chiadochain.net`, `https://gnosis-chiado-rpc.publicnode.com`; beacon `https://gnosis-beacon-api.publicnode.com`, `https://gnosis-chiado-beacon-api.publicnode.com`.
- **Status:** READY. Needs `github-asset:` resolver + zip bundle installer + `UnitFlagOverrides` KEY fix for presets.

## joc — READY
- **What it is:** Japan Open Chain, Clique PoA (period 5 s, epoch 30000), chain id 81 mainnet / 10081 testnet (V from `Chains/joc-*.json` and eth_chainId on the public RPCs). **Nethermind ships built-in configs and chainspecs for it** (V: `configs/joc-mainnet.json`, `joc-testnet.json`, `_archive` variants are in the 2.1.0 zip) — no separate client.
- **Artifacts/chain:** same nethermind zip as gnosis; `-c joc-mainnet | joc-mainnet_archive | joc-testnet | joc-testnet_archive`. `Merge.Enabled=false` → no Engine API, no CL, no JWT.
- **Ports (mainnet 41780 / testnet 41790):** +0 http, +1 ws, +2 p2p tcp+udp, +4 metrics.
- **Disks/size:** single NVMe role; sizing INFERRED (no vendor figures): 4 cores/16 GB, mainnet hint 1024 GiB (full ~512, archive ~2048).
- **Snapshots:** never (config has SnapSync + pivot 23.38 M mainnet / 16.98 M testnet).
- **Tips (V):** `https://rpc-1.japanopenchain.org:8545` (0x193bd9d = 26.46 M), `https://rpc-1.testnet.japanopenchain.org:8545` (0x131cfa0 = 19.98 M). `rpc-2` mainnet (0x193bf28) and testnet (0x131d12b) also answer (V).
- **Risk:** the chain is a small consortium PoA; Nethermind config pivot values age (Nethermind's job). READY.

## flare — READY
- **Envs:** `flare` (mainnet, chain 14), `songbird` (canary, 19), `coston2` (testnet, 114) — all V via eth_chainId. Coston (old testnet) is dropped.
- **Client:** flare-foundation/go-flare (avalanchego fork). V: v1.14.0 stable (2026-06-17), v1.14.2-rc.0 prerelease. **Only asset: `go-flare-v1.14.0-linux-amd64.zip`** (44 MB; one file `avalanchego`, 93 MB ELF; + .sig/.pem/.slsa). **No arm64.** The repo `flare-foundation/flare` is a 2024 relic (v0.6.5) — wrong.
- **Artifact URL:** `https://github.com/flare-foundation/go-flare/releases/download/{tag}/go-flare-{tag}-linux-amd64.zip`.
- **Chain selection:** `--network-id=flare|songbird|costwo`; bootstrap `--bootstrap-ips/--bootstrap-ids` from `https://{flare|songbird|coston2}-bootstrap.flare.network/ext/info` (V: all three answer `info.getNodeID`/`getNodeIP`).
- **Ports (41450 / 41460 / 41470):** +0 http (C-Chain `/ext/bc/C/rpc`, ws `/ext/bc/C/ws`, metrics `/ext/metrics` on the same port), +2 staking p2p (tcp). No separate ws/metrics ports.
- **Disks/size (docs, mapping I):** 4 cores/16 GB; flare 1 TB pruned/5.7 TB archive, +30 GB/month; coston2 250 GB/1.2 TB; songbird 2.7 TB/11.2 TB. Disk speed 1200 MB/s read + 600 MB/s write.
- **Snapshots:** none official found → never. Pruning on (full) / off (archive) via C-Chain `config.json` (draft `chain-config.C.json.tmpl`).
- **Tips (V):** `https://flare-api.flare.network/ext/C/rpc` (0x44b34fe = 72.0 M), `https://songbird-api.flare.network/ext/C/rpc` (0x810beda = 135.3 M), `https://coston2-api.flare.network/ext/C/rpc` (0x22e0d0d = 36.6 M).
- **Notes:** hardfork cadence is announced per network with deadlines (v1.14.0 notes: Coston 2026-06-11 … Flare 2026-07-14); the min C-Chain base fee floor is 500 GWei.

## sonic — READY
- **Decision on fantom:** `fantom` is a **separate network**, not an alias: Fantom Opera still runs (V: block 123.68 M on `rpcapi.fantom.network`, head timestamp 10 s behind wall clock; consecutive blocks seen 21 s apart) on the legacy client `Fantom-foundation/Sonic` (the repo README says it "will not work with the new Sonic network"). The Sonic chain is client `0xsoniclabs/sonic`.
- **Envs:** mainnet (chain 146), testnet (14601) — V via eth_chainId. **Client:** 0xsoniclabs/sonic v2.2.5 (V, 2026-10-07, stable release): assets are **raw executables `sonicd` (73 MB) and `sonictool` (72 MB)**, x86_64 ELF (V by ELF header), no arm64, no archive.
- **Artifact URL:** `https://github.com/0xsoniclabs/sonic/releases/download/{tag}/sonicd` (and `/sonictool`).
- **Chain selection:** by genesis file: `https://genesis.soniclabs.com/latest-sonic-{pruned,archive}.g`, `latest-testnet-{pruned,archive}.g` (302 → dated object; V sizes: sonic pruned 6.47 GB, sonic archive 181.6 GB, testnet pruned 368 MB, testnet archive 15.3 GB; `.md5` next to each).
- **Process:** (1) `sonictool --datadir D --cache 12000 genesis FILE` (hours; `GOMEMLIMIT≈50GiB`; log must show "StateDB imported successfully, stateRoot matches"), (2) `sonicd --datadir D --cache 12000 --nat extip:IP --http … --ws …` (rpc mode, default).
- **Ports (42440 / 42450):** +0 http, +1 ws, +2 p2p tcp+udp (upstream default 5050), +4 metrics (`--metrics.addr/--metrics.port`).
- **Disks/size:** docs min 4 vCPU/32 GB, rec. 8/64 GB (128 GB for heavy), archive ≈3 TB local NVMe (V); pruned DB size after import not published (I: ~1 TB hint).
- **Snapshots:** **required** — the genesis IS the bootstrap (resolver sketch FAMILY §5). RPC is not servable until the import ends.
- **Tips (V):** `https://rpc.soniclabs.com` (0x4d34fd3 = 80.96 M), `https://sonic-rpc.publicnode.com`, testnet `https://rpc.testnet.soniclabs.com` (0x12456ee).
- **Hardening to keep (Sonic docs tier 1):** `--http.api=eth,net,web3,sonic` (never debug/trace publicly), `--ipcdisable`, no `--allow-insecure-unlock`, JS tracers off.

## kaia — READY
- **Envs:** mainnet (8217), `kairos` (1001) — both V via eth_chainId; the old Baobab testnet is gone. **Client:** kaiachain/kaia v2.2.2 (V, 2026-03-10; hardfork Osaka @ block 213333000 already passed). GitHub releases carry **no assets**; binaries: `https://packages.kaia.io/kaia/v2.2.2/ken-v2.2.2-0-linux-amd64.tar.gz` (58 MB) and `…-linux-arm64.tar.gz` (54 MB) (V: HTTP 200 + sizes). Layout V: `ken-linux-amd64/{bin/ken,bin/kend,conf/kend.conf}`. Tag → URL needs the `-0` build suffix (I: stays 0).
- **Chain selection:** `--mainnet` / `--kairos` (V in `kend.conf` + `kend` script). Run `ken` directly (kend is a daemonising wrapper).
- **Ports (41810 / 41820):** +0 rpc, +1 ws, +2 p2p (`--port`), +4 prometheus (`--prometheusport`, name I), +8 `--subport` (multichannel; ken's default port+1).
- **Disks/size:** hardware not published beyond "Amazon Linux 2, ~2.5 GB/day" (V); snapshot sizes below drive the disk plan: mainnet 2.8 TB (use 4 TB NVMe), kairos 815 GB.
- **Snapshots:** **required**, official, live-pruned only (V docs, page dated 2026-09-22): `https://snapshots.node.kaia.io/{mainnet|kairos}/pruning-chaindata/latest.txt` → `https://kaia-chaindata-r2-logging.kaia-foundation.workers.dev/mainnet/pruning/kaia-mainnet-pruning-chaindata-20261012010011.tar.zst` (2.29 TB, extracted 2.79 TB, md5 in `manifest.json`); kairos 648 GB / 815 GB. Archive/full snapshots are no longer published (docs). The old `packages.kaia.io/<net>/chaindata` URLs are dead (docs say so).
- **Tips (V):** `https://public-en.node.kaia.io` (0xdb14475 = 229.7 M), `https://public-en-kairos.node.kaia.io` (0xdb9cbcf). `kaia-rpc.publicnode.com` did not answer.
- **I:** `--state.live-pruning` must be on when restoring the live-pruned DB; confirm with `ken --help` (flag exists V in `cmd/utils/flags.go`).

## vana — READY (mainnet x86_64 only)
- **Chain:** PoS EVM L1 (mainnet 1480, Moksha 14800), execution = geth, consensus = Prysm. V from vana-com/vana (`docker-compose.yml`, `.env.*.example`, `networks/<env>/`).
- **Mainnet EL:** the **patched geth** `geth-v1.16.8-vana9228152` (release of vana-com/vana; asset is a raw static linux/amd64 ELF + .sha256; V by ELF header). Stock geth stops at block 9 228 152 (state-root override), so it is mandatory. Docker image alternative not used.
- **Mainnet CL:** vana-com/prysm v7.1.8-vega.1: `beacon-chain-v7.1.8-vega.1-linux-{amd64,arm64}` raw binaries (V, 64/60 MB). Upstream Prysm cannot run the mainnet config (retired deposit contract handling).
- **Moksha:** stock geth v1.16.8 (gethstore resolver) + upstream Prysm v5.1.0 (the testnet `.env` pins it).
- **Files:** `networks/{mainnet,moksha}/{genesis.json,genesis.ssz (2.69 MB),config.yml}`; bootnodes EL_BOOTNODE_1/2 + CL_BOOTNODE_1..20 in `.env.<env>.example` (V). Pin the raw URLs to a vana-com/vana release tag (v2.4.0, 2026-09-24).
- **Init:** `geth --state.scheme=path --datadir D init genesis.json` once. Prysm: `--genesis-state`, `--chain-config-file`, `--chain-id`, checkpoint sync from `https://rpc.vana.org` (I: beacon REST on the same host; compose uses it as TRUSTED_BEACON_NODE_URL).
- **Ports (42650 / 42660):** +0 http, +1 ws, +2 p2p, +3 engine, +4 metrics, +5 prysm REST, +6 prysm tcp, +7 prysm udp, +8 prysm gRPC. Upstream defaults 8545/8546/8551/30303/3500/4000/13000/12000 not used.
- **Disks/size (V docs):** mainnet 8 cores/32 GB/1.2 TB SSD, x86-64; testnet 2 cores/8 GB/100 GB.
- **Snapshots:** never (none found; "Fast Syncing" in the README is checkpoint sync). **Tips (V):** `https://rpc.vana.org` (0xa1015d = 10.55 M), `https://rpc.moksha.vana.org` (0x905cb3).
- **Not supported:** validator keys/deposits (needs whitelist via Discord); no validator role in the toolkit.

## tempo — READY
- **Chain:** Tempo (Stripe/Paradigm payments L1; reth SDK + Commonware Simplex), mainnet chain id 4217, testnet "Moderato" 42431 (V docs).
- **Client:** tempoxyz/tempo v1.16.0 (V, 2026-10-06): `tempo-v1.16.0-x86_64-unknown-linux-gnu.tar.gz` (45.8 MB) and `…-aarch64-unknown-linux-gnu.tar.gz` (43.4 MB) + .sha256/.asc (GPG `EE3C5D41EA963E896F310EC3CBBFA54B20D33446`). Crate tags (`tempo-primitives@…`) share the repo → `tagPrefix: v`.
- **RPC node:** `tempo node --follow --chain mainnet|moderato --datadir D --http …` (V). Trustless follower: verifies finalization certificates against built-in network identity keys, re-executes every block, keeps full state; default pruning = archive. No validator keys, no Engine API, no CL.
- **Ports (42590 / 42600):** +0 http, +1 ws, +2 p2p tcp+udp, +4 metrics (`--metrics <PORT>`); +6 reserved (consensus p2p 8000 is validators only).
- **Disks/size:** RPC node min 16 cores/32 GB/1000 GB NVMe, rec. 32/64/2000 GB (V); **local NVMe only (no EBS/NAS)**, chrony not timesyncd, BBR+fq (V). Mainnet archive snapshot 19.7 GB download / 54 GB extracted; moderato 472 GB / 1.1 TB (V by summing the manifests of 2026-10-13).
- **Snapshots:** required; `tempo download --chain X --archive|--full|--minimal` (V) downloads execution + consensus from `https://tempo-node-snapshots.tempoxyz.dev/tempo-<chain>-<block>-<ts>/manifest.json` (V; viewer `https://snapshots.tempo.xyz`). `--print-plan-json` previews the plan. Keep both parts from the same snapshot.
- **Tips (V):** `https://rpc.tempo.xyz` (0x29f6344 = 44.00 M), `https://rpc.moderato.tempo.xyz`.
- **I:** exact CLI default of `--follow` (no URL → built-in upstream per chain); reth-style `--ws.*`, `--port`, `--rpc.*` flags.

## berachain — READY
- **Chain:** mainnet 80094, Bepolia 80069. Two clients: bera-reth (execution, reth fork) + beacond (BeaconKit, CometBFT).
- **Versions (V, docs.berachain.com/nodes/architecture/evm-execution):** mainnet beacon-kit v1.4.1 + bera-reth v1.4.4; **bepolia beacon-kit v1.4.2-rc.0 (prerelease) + bera-reth v1.4.4**. Newest bera-reth tags (v1.4.3, v1.4.4) are both 2026-07-29.
- **Artifacts (V HTTP 200):** `bera-reth-v1.4.4-x86_64-unknown-linux-gnu.tar.gz` (44.7 MB) / aarch64 (43.3 MB); `beacond-v1.4.1-linux-amd64.tar.gz` (24.3 MB) / arm64 (21.1 MB). Chain files at `beacon-kit/<tag>/testing/networks/{80094,80069}/{genesis.json,kzg-trusted-setup.json,app.toml,config.toml,el-peers.txt}` (V all 200).
- **Chain selection:** `bera-reth node --chain mainnet|bepolia`; `beacond init MONIKER --beacon-kit.chain-spec mainnet|testnet --home H` then copy/patch configs (official `setup-beacond.sh`, V).
- **Ports (41150 / 41160):** +0 http, +1 ws, +2 EL p2p, +3 engine, +4 EL metrics, +5 CL rpc, +6 CL p2p, +7 CL metrics, +8 CL proxy_app.
- **Disks/size:** docs 4 physical cores/32 GB/1 TB local SSD (V); reth pruned snapshot 54 GB, archive 1.2 TB; beacon pruned 30 MB, archive 336 GB.
- **Snapshots:** optional (recommended on): `https://snapshots.berachain.com/index.csv` (V; mirrors `…your-objectstorage.com/mainnet-{reth,beacond}-{pruned,archive}-latest.tar.lz4`); mainnet rows only on 2026-10-13. Restore EL: `lz4 -d | tar -x -C <reth datadir>`; CL: `-C <beacond home>`.
- **Tips (V):** `https://rpc.berachain.com` (0x1a2ec2d = 27.45 M), `https://berachain-rpc.publicnode.com`, `https://bepolia.rpc.berachain.com` (0x194c290).
- **Notes:** official script runs reth `--full` unless archive; keep `priv_validator_key.json` out of snapshot restores.

## data (DATA Network, formerly Story) — READY
- **What "data" is:** the DATA Network = Story Protocol L1 after its rebrand (V: `mainnet.datarpc.io` and `mainnet.storyrpc.io` return the same block; Goldsky/QuickNode/Main.net list "DATA Network, chain id 1514, testnet Aeneid 1315"). Clients and flags are still `piplabs/story` and `piplabs/story-geth` (`--story`, `--aeneid`, `story init --network story|aeneid`).
- **Artifacts (V HTTP 200):** story-geth v1.2.3 `geth-linux-amd64-v1.2.3` (82 MB) / arm64 (79 MB); story v1.8.0 `story-linux-amd64` (126 MB) / arm64 (116 MB) — raw executables.
- **Process:** `story init --network story --home H --moniker M --rpc-laddr tcp://127.0.0.1:P` (then patch `config.toml` p2p `laddr`, `proxy_app`, prometheus), `story-geth --story --datadir … --authrpc.jwtsecret J …`, `story run --home H --engine-endpoint http://127.0.0.1:E --engine-jwt-file J` (flags V in `client/cmd/flags.go`).
- **Ports (41360 / 41370):** +0 http, +1 ws, +2 EL p2p, +3 engine, +4 EL metrics, +5 CL rpc, +6 CL p2p, +7 CL REST api, +8 CL metrics, +9 CL proxy_app.
- **Disks/size:** story README 4 cores/32 GB/200 GB (V, CL host); EL disk INFERRED (hint 1.5 TB, full ~800 GB).
- **Snapshots:** none official found (snapshots.story.foundation / snapshots.storyscan.io / Polkachu hosts did not answer) → never; custom URL allowed.
- **Tips (V):** `https://mainnet.datarpc.io`, `https://mainnet.storyrpc.io`, `https://story-rpc.publicnode.com` (0x163ba1e = 23.31 M), aeneid `https://aeneid.datarpc.io`, `https://aeneid.storyrpc.io` (0x17bf321).

## fluent — READY (x86_64 only)
- **What:** Fluent, blended-execution EVM L2 (rWasm); reth-based single binary. Chain names `fluent-mainnet` (25363) and `fluent-testnet` (20994) (V docs/10-running-node-locally.md).
- **Client:** fluentlabs-xyz/fluentbase v1.5.1 (V, 2026-09-29): `fluent-v1.5.1-x86_64-unknown-linux-gnu.tar.gz` (39 MB, one file `fluent`); **no aarch64 asset (404)**. Also published: `fluent-rwasm-*` build, genesis json.gz per network, SBOM/provenance.
- **Run:** `fluent node --chain=fluent-mainnet --datadir D --http` (V). Testnet: `fluent init` + `fluent download` (official snapshot) before `fluent node` (V). No CL companion/Engine API (I: blocks arrive via devp2p).
- **Ports (41480 / 41490):** +0 http, +1 ws, +2 p2p, +4 metrics.
- **Disks/size:** no vendor sizing (I): 8 cores/32 GB/1 TB hint.
- **Snapshots:** mainnet never; testnet required via `fluent download` (size not published).
- **Tips (V):** `https://rpc.fluent.xyz` (0x115045c = 18.15 M), `https://rpc.testnet.fluent.xyz` (0x2615704).
- **I:** standard reth `--ws/--port/--metrics/--rpc.*` flags present; verify with `fluent node --help`.

## gravity — READY (heaviest)
- **Which Gravity:** *Gravity Mainnet (L1)*, chain 127001, AptosBFT + Gravity Reth (Grevm); hardfork history from 2026-06-29 (Prague) in the docs; Longevity testnet 7771625. The older *Gravity Alpha Mainnet* (chain 1625, Arbitrum Nitro L2) still answers on `rpc.gravity.xyz` (V: web3_clientVersion `nitro/…`) — that would be an `arb`-recipe network, not this one.
- **Client:** Galxe/gravity-sdk (V): stable v1.9.3 (2026-09-18) / v1.9.2 / v1.9.1; **required v1.9.1+ since 2026-08-18** (hardforks compiled in); v1.10.0 + `v1.10.1-testnet` are prereleases (testnet requires v1.10.1-testnet). Assets: raw `gravity_node` (132 MB) + `gravity_cli` (29 MB), linux x86_64 (V HTTP 200), no arm64.
- **Process (V docs run-a-mainnet-pfn + cluster/deploy.sh):** `gravity_cli genesis generate-key --output-file identity.yaml` (once), render `public_full_node.yaml` (seed block = 2 public mainnet seeds `mainnet-rpc-p2p-{1,3}.gravity.xyz:6180` with peer ids + noise keys, or testnet `testnet-rpc-p2p-1.gravity.xyz:6195`), then `gravity_node node --chain genesis.json --full --prune.transactionlookup.distance 10064 --gravity_node_config public_full_node.yaml --http … --datadir …/reth …`. genesis.json + waypoint.txt from `galxe/gravity-sdk` `genesis/{mainnet,testnet}/` (must match each other; pin to tag).
- **Ports (41600 / 41610):** +0 http, +1 ws, +2 reth p2p, +3 authrpc, +4 metrics, +5 inspection, +6 gravity public-network p2p.
- **Disks/size:** no vendor hardware page (I): benchmark nodes 8 vCPU/16 GB; snapshot 112.6 GB mainnet, 95.3 GB testnet → hint 1 TB / 768 GB.
- **Snapshots:** optional, official, stable URL (V HEAD): `https://gravity-snapshots.b-cdn.net/gravity-mainnet-data/latest.tar` (112,566,169,600 B), `…/gravity-testnet-data/latest.tar` (95,268,147,200 B). Extract into storage dir; contains `consensus_db/ quorumstoreDB/ reth/`; never `rand_db`/`secure_storage.json`.
- **Tips (V):** `https://mainnet-rpc.gravity.xyz` (0x348f0be = 55.11 M), `https://testnet-rpc.gravity.xyz` (0x29f9fdf). Blocks ~200 ms → `tipLagBlocks: 30`.

---
## shibarium — NEEDS-DECISION
- **What:** Shiba Inu's Shibarium = Polygon-PoS-v1 style chain (bor + heimdall v1, chain id 109 V via `shibarium.drpc.org`; testnet "Puppynet" id I). Org `shibaone`: repos `bor`, `heimdall`, `launch`, `node-ansible`.
- **Public software (V):** shibaone/bor `v1.3.9-bone` (2025-02-01): `bor-v1.3.9-bone-{amd64,arm64}.deb` (44 MB) + `bor-{shibarium,puppynet}-{sentry,archive,validator,bootnode}-config_<tag>-<arch>.deb`; shibaone/heimdall `v1.0.7-bone` (2024-11-28): `heimdalld-v1.0.7-bone-{amd64,arm64}.deb` + `heimdalld-{shibarium,puppynet}-{sentry,validator}-config_…deb`. Chain selection is **built into the binaries**: `bor --chain shibarium` (VERIFIED in the config deb: `chain = "shibarium"`, p2p 30303, jsonrpc http api `eth,net,web3,txpool,bor`, prometheus 7071), `heimdalld start --home … --chain=shibarium --rest-server` (V service file). node-ansible README: "Shibarium tags 1.x.x-bone, Puppynet 1.x.x-bone-beta-x" (V).
- **Why not READY:** (1) newest Shibarium-specific tag is Feb 2025; the repos' `master` is a Polygon upstream mirror (v2.2.x, chain 137 genesis in the v2.2.9 assets) and the GitHub "latest release" API returns the wrong tag, so which bor/heimdall pair the live network requires in Oct 2026 cannot be confirmed from public sources (news of a Sept 2026 peer rotation implies it is live and maintained). (2) Heimdall v1 needs an Ethereum L1 RPC (`eth_rpc_url`; Sepolia for Puppynet) like Base's l1 options. (3) No snapshot source found. (4) No Puppynet public tip answered.
- **If approved:** reuse the `polygon` recipe (`.deb` extraction, `PolygonConfigPatch`), tag filter `tagSuffix: -bone` (+`-bone-beta` for puppynet), ports from block 42380 (+0 bor http, +1 ws, +2 bor p2p, +4 bor metrics (7071), +5 heimdall REST, +6 heimdall p2p, +7 heimdall rpc, +8 heimdall metrics), tip `https://shibarium.drpc.org` (V, 0x12843b6). Ask the ShibaOne team for the required versions first.

## moca — NEEDS-DECISION
- **What:** Moca Chain (Mocaverse/Animoca), Cosmos-SDK + CometBFT + EVM (a BNB Greenfield-lineage fork). Mainnet cosmos id `moca_2288-1` (EVM 2288, V `eth_chainId 0x8f0`), testnet EVM 222888 (V 0x366a8).
- **Software (V):** mocachain/moca v1.4.1 (2026-08-28): `mocad_linux_x86_64.tar.gz` (37.6 MB) / `mocad_linux_arm64.tar.gz`, `checksums.txt`; v1.4.0 stable older. README: "not ready for production use … under active development". Docs are on a Lark page (`digitalpulse.larksuite.com`), not mirrored.
- **Gaps:** no published mainnet genesis/seeds/peers/state-sync file in the org's repos; genesis is only retrievable from a trusted RPC (`https://tm-rpc.mocachain.org/genesis`, CometBFT 0.38.25, V status `moca_2288-1`, earliest retained block 17,125,001 / latest 31,393,693 → the public node is pruned, so a node needs state sync, and the net_info shows only private peer IPs). Public EVM RPC `https://rpc.mocachain.org` (0x1df079a), testnet `https://testnet-rpc.mocachain.org`.
- **Decision needed:** accept a Cosmos-style "genesis from trusted RPC + state-sync" install (belongs to the cosmos family design), and who provides seeds. Port block 41960 reserved.

## fantom — DEFERRED (decision: separate network from sonic)
- Fantom Opera is alive (V: block 123,678,367 → 123,678,368 within seconds on `https://rpcapi.fantom.network`, head ~10 s behind wall clock). Maintained client: `Fantom-foundation/Sonic` v1.2.1-k (2026-10-12, security release: debug_* disabled by default, JS tracers off) — **releases have no binary assets (V), build from source only** (`make all` → `build/sonicd`, `sonictool`). The only binary is the 2023 `go-opera` v1.1.3-rc.5 `opera` (33 MB) — end-of-life. The genesis host the lachesis_launch docs point to (`files.fantom.network`, `download.fantom.network`) returned 404/301 for the listed `.g` files on 2026-10-13.
- Defer until a Go build step is accepted and a current genesis URL is confirmed with the Sonic Labs team. Reserved block 41420.

## plasma — DEFERRED
- **What:** Plasma (XPL), stablecoin chain, mainnet chain id 9745 (testnet 9746): reth execution + PlasmaBFT consensus. GitHub org `PlasmaLaboratories` (V): `node-templates` v1.1.0 (Docker Compose only).
- **Why deferred:** execution = upstream Reth v1.11.3 (binary exists upstream), but **the consensus client `ghcr.io/plasmalaboratories/plasma-consensus-public:1.1.0` is a container image only — no release binary anywhere (V)**; Docker is not an allowed install path. Snapshots are in requester-pays S3 buckets (AWS account needed, `plasma-mainnet-db-backups`). Ports in the template: 8545/8546/8547 rpc, 8551 auth, 30303-30305 EL p2p, 35070 CL api, 34070-34072 CL p2p, 9001 metrics.
- Revisit if Plasma publishes `plasma-cli` binaries. Tip URLs for later (V): `https://rpc.plasma.to` (0x215cb95), `https://testnet-rpc.plasma.to`. Reserved block 42170.

## monad — DEFERRED
- **Software:** category-labs/monad(-bft) v0.16.x, distributed only as the apt package `monad` (V: `https://pkg.category.xyz/` noble repo, amd64 deb ~80-116 MB; docs full-node-installation: mainnet monad 0.16.4 / testnet 0.16.3). GitHub releases carry no assets.
- **Why deferred:** (1) bare metal only ("cloud not supported"), HyperThreading must be off; (2) needs a **dedicated raw NVMe block device `/dev/triedb`** formatted by `monad-mpt` and a 512-byte LBA, plus a second 500 GB disk; (3) installs services as user `monad` under `/home/monad`, `/opt/monad`, `monad-bft`, `monad-execution`, `monad-rpc`, `monad-mpt`, `monad-cruft`, `otelcol` — incompatible with "everything under node_dir"; (4) a full node needs upstream validators that republish blocks to it (`node.toml` allow-list) and a "hard reset" snapshot import; (5) ports 8000-8002 + RPC 8080. Rec. 16 cores @ 4.5 GHz, 32 GB+, 2 TB TrieDB NVMe.
- Public tip (V): `https://rpc.monad.xyz` (0x6be5e17 = 113.14 M). Reserved block 42020.

## megaeth — DEFERRED
- **No full-node software exists publicly.** megaeth-labs publishes `stateless-validator` v2.0.20 (V: raw `stateless-validator` + `debug-trace-server`, docs say "source only" for the validator but the 2026-10-10 release has binaries) — a stateless re-executor that needs an upstream RPC + SALT witnesses, it does not serve JSON-RPC state. Execution (sequencer) software is not released. Mainnet tip `https://mainnet.megaeth.com/rpc` (0x1bc7228).
- Reserved block 41930. (A "stateless validator" Node type would be a different product; not proposed.)

## ault — DEFERRED
- **What:** Ault Blockchain (aultblockchain.com, Ault Capital Group): Cosmos SDK + CometBFT + EVM; mainnet cosmos `ault_904-1` (EVM 904), testnet `ault_10904-1` (10904). Docs (V, docs.aultblockchain.com) describe `aultd` (`init`, `start`, ports 26656/26657/1317/9090/8545/8546, 16 vCPU/64 GB/1 TiB) but **every artifact URL is a placeholder** (`<BINARY_DOWNLOAD_URL>`, `<GENESIS_FILE_PATH>`, `<PRUNED_SNAPSHOT_URL>`); GitHub org `Ault-Blockchain` only has `ault-mining-node` (the `aultmined` licensed-miner client, v1.1.5), not the node.
- Mainnet EVM RPC works (V `https://main-json-rpc.cloud.aultblockchain.xyz`, 0x1c58d03). Revisit when `aultd` releases are public. Reserved block 41090.

## sahara — DEFERRED
- **What:** Sahara AI chain (SaharaLabsAI): Cosmos SDK + CometBFT fork + EVM (`saharad`). Only a testnet node setup exists (`setup-testnet-node`, **docker compose only**: image `ghcr.io/saharalabsai/sahara/saharad:0.3.1-testnet-beta`; ports 26656/26657/16161/16162; state sync from `testnet-cos-rpc1.saharalabs.ai`). `SaharaLabsAI/sahara` releases (0.4.0-beta.1, 0.3.2-testnet-beta …) have **no assets** (V). No mainnet RPC answered; testnet EVM RPC `https://testnet.saharalabs.ai` (chainId answers, height 0x4c7e1).
- Defer until binary releases and a mainnet exist. Reserved block 42290.
