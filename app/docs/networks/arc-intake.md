# Arc (Circle) — intake

Status: **implemented**, not yet run on a live host. Envs `mainnet`, `testnet`.

## 1. Scope
Reth-based execution layer + Malachite consensus layer (`circlefin/arc-node`). A node cannot sync from genesis: it restores a snapshot with the
bundled `arc-snapshots` tool. No peers (`--disable-discovery`): the CL follows relay endpoints. Out of scope: validator mode.

## 3. Client
`circlefin/arc-node` GitHub release `arc-node-{tag}-{x86_64,aarch64}-unknown-linux-gnu.tar.gz` → `arc-node-execution`, `arc-node-consensus`,
`arc-snapshots`. One tag serves both envs (`ArcClientReleaseResolver`). No Docker. Tarball layout is searched, not assumed.

## 4. Ports (local only — no P2P)
mainnet: http 8591, el_metrics 9291, cl_rpc 31091, cl_metrics 29091. testnet: 8592 / 9292 / 31092 / 29092. IPC sockets live in `/run/rpcnode-arc-<env>/`.

## 5. Disks
One role `execution` (nvme); under it `bin/`, `logs/`, `.toolkit/` and the two databases in `execution/` (Reth) and `consensus/` (Malachite) — `arc-snapshots` refuses a directory with data it did not restore, so they cannot share the role dir itself. Hints are estimates: docs say 1 TB+ NVMe, 64 GB RAM; testnet pruned snapshot ~103 GB + 36 GB extracted.

## 6. Run script (`bin/rpcnode-arc-run.sh`)
1. Snapshot: normally already restored by the wizard's **Snapshot step** (`snapshot: required`, types full / archive; the resolver gives `arc-snapshots://<env>/<type>`, the agent runs `arc-snapshots download …` through `ArcSnapshotRunner` with progress from the tool's log or the size on disk and writes `.toolkit/arc-snapshot-<type>.done`). If the step was skipped, the script runs the same command at the first start.
2. `arc-node-consensus init --home …` once, after the snapshot (the key lives in the consensus dir).
3. EL then CL, supervised; the CL is started after the EL's IPC sockets appear.
Node types (the wizard's snapshot type, saved as install option `snapshot`): **full** (default) = the Arc tutorial path: default snapshot preset + `--full` on both layers;
**archive** = `--el-profile archive`, no `--full`. Capacity knobs in bindings: `rpc.max-connections 250`, `rpc.max-subscriptions-per-connection 32`,
backpressure threshold 16, `LimitNOFILE 1048576`, `http.api eth,net,web3,txpool,trace,debug`.

## 7. Snapshots
Official, via `arc-snapshots` (`https://snapshots.arc.network`): presets minimal / full / archive. Open: the exact EL pruning flag that
matches the tool's default preset on the first start (the tutorial uses `--full`); the archive preset's size.

## 8. Height / tip
`eth_blockNumber` locally and on `rpc.mainnet.arc.io` / `rpc.testnet.arc.io`.
