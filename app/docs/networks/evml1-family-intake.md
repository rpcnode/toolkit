# EVM family 1 (gnosis, joc, flare, sonic, kaia, vana, tempo, berachain, fluent, gravity) — implementation notes

Status: implemented in `chains/evmfamily` (one shared package, one profile object per network), verified against upstream source on
2026-10-14 (fact checks of every flag below were done by reading the pinned tags; nothing could be RUN: there is no Linux host here).
Research: `research/evml1/{FAMILY,networks,risks}.md`. Not implemented: `data` (DATA Network / Story) was dropped by the user;
fantom, shibarium, moca, plasma, megaeth, ault, sahara stay DEFERRED (notes in the research folder). `monad` was added later (2026-10-14) as an
EVM-family profile with its own recipe: see `monad-intake.md`.

## Shape
`EvmProfile` (network, envs, port offsets, disk roles) + one `EvmRecipe` per network that renders its units from an `EvmStartContext`
(pure, tested) and does the host preparation in `prepare` (init, config patch, bootstrap lookup). Shared: `EvmNodeStart` (ChainNodeStart),
`EvmNodeProcessStarter` (install binaries → JWT → prepare → `installCustomUnits`), `EvmHostBinaries` (RAW / TAR_MEMBER / ZIP_MEMBER / ZIP_BUNDLE into
`<node_dir>/bin`, Java unzip, path traversal skipped), `EvmJwt` (`<node_dir>/.toolkit/jwt.hex`, 0600), `EvmUnitBodies` + per-network
`node.service.tmpl` / `companion.service.tmpl`, `EvmNodeHeightProbe` (eth_blockNumber + eth_syncing), `EvmNetworkTipProbe`,
snapshot resolvers, and `EvmSnapshotRunner` (agent: runs `evm-snapshot://` scripts). Flag fragments: `NethermindFlags`, `LighthouseFlags`, `GethFlags`, `RethFlags`,
`CometbftCompanion` (init + `CometbftTomlPatch` of the CometBFT family).

## Per network
| id | client | units | snapshot | notes |
|---|---|---|---|---|
| gnosis | Nethermind 2.1.0 (zip bundle, `github-asset:` glob) + Lighthouse | 2 (EL, CL `--network gnosis|chiado`) | never (snap sync + checkpoint sync) | `node` full/archive → `-c gnosis[_archive]` |
| joc | Nethermind built-in `joc-*` configs (Clique) | 1, no Engine API | never | |
| flare | go-flare zip (single `avalanchego`) | 1 | never | bootstrap ip/id looked up on every Start (cached), C-Chain `config.json`, x86_64 only |
| sonic | `sonicd` + `sonictool` raw | 1 | `evm-snapshot://sonic/<env>/<pruned|archive>`: genesis download + md5 + `sonictool genesis` | x86_64 only, `--cache 12000`, GOMEMLIMIT 90 % |
| kaia | `ken` from packages.kaia.io (probe) | 1 | live-pruned tar.zst, streamed in one pass | `--state.live-pruning`, firewall `--prometheusport` |
| vana | patched geth (mainnet, raw) / stock geth (moksha, gethstore) + vana-com/prysm | 2 | never (official GCS tarballs exist, network unconfirmed) | mainnet x86_64 only |
| tempo | `tempo node --follow` | 1 | `tempo download` archive/full/minimal | follower ports pinned (authrpc, consensus metrics), IPC off |
| berachain | bera-reth + beacond | 2 | index.csv pair (EL+CL of one block), mainnet only | one disk role `data`: `reth/` + `beacond/` |
| fluent | `fluent node` | 1 | `fluent init` + `fluent download` (testnet required, mainnet optional) | follows wss://rpc.fluent.xyz as well as devp2p; x86_64 only |
| gravity | `gravity_node` PFN (+ `gravity_cli` for the identity) | 1 | official `latest.tar`, streamed | x86_64 only, identity 0600 under `.toolkit/config` |

## INFERRED / not verified (needs a real host before release)
- Nothing was executed. Unit behaviour under systemd is unproven (Nethermind needs `DOTNET_BUNDLE_EXTRACT_BASE_DIR` because `$HOME` is unset: reasoned from Microsoft's single-file docs).
- Berachain: `beacond init` over a snapshot-restored `data/` (restore before the first Start) is assumed to be accepted; `--bootnodes` instead of the official script's `--trusted-peers` follows the docs' production checklist.
- Tempo: whether reth's authrpc binds at all in follow mode (pinned anyway); `tempo download` re-run without `--force` on a complete datadir; per-phase percentages.
- Fluent: `fluent download` output format (progress is shown as bytes on disk); whether `fluent init` is needed/harmless for mainnet.
- Flare: `--public-ip` behind NAT; whether peers serve C-Chain state-sync summaries (archive sets `state-sync-enabled: false`, full lets the client decide); disk table mapping (positional in the docs).
- Sonic: `--nat` left out (the default is the client's own choice); `sonictool` log lines `Successfully imported genesis file` / `stateRoot matches` are from source at v2.2.5.
- Kaia: `-0` build number is probed (0, 1, 2); `--state.live-pruning` on a restored live-pruned DB is harmless per source, not run.
- Vana: patched geth requirement rests on its release note (the official compose still pins stock v1.16.8); `--monitoring-host/port` exist as strings in the Prysm fork binary (flag semantics not run).
- Gravity: `identity.public.yaml` is produced by the cluster tooling, not by `generate-key`: not needed here. Hardfork times are compiled in: "latest stable" can be older than required.
- Sizing numbers (cores / RAM / disks) are the research's hints, mostly INFERRED where no vendor figure exists.
