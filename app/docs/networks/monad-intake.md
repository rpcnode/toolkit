# Monad — intake (implemented 2026-10-14)

Filled copy of `docs/adding-a-network-intake.md`. Code: `chains/evmfamily` (`MonadProfile`, `recipe/MonadHost`, `recipe/MonadNodeToml`,
`recipe/MonadCruft`), resources `resources/chains/monad/`. Research history: `research/evml1/networks.md` (monad section, was DEFERRED).

**Status: implemented, verified by unit tests and upstream sources only — never run on a Linux host or on Monad hardware.**
INFERRED items are listed in section 15.

## 0–2. Scope and environments
| Field | Answer |
|---|---|
| Network id / label | `monad` / Monad |
| Envs | `mainnet` (chain id 143, `monad_mainnet`), `testnet` (10143, `monad_testnet`) |
| Product | public **full node** with JSON-RPC (permissionless: follows upstream validators over secondary raptorcast). Not a validator. |
| One env per host | yes (`oneEnvPerHost: true`): one TrieDB device, CPU layout is fixed to 16 cores |
| Platform | Ubuntu 24.04, **amd64 only**, **bare metal** (docs: cloud is not supported), ≥ 16 logical CPUs, Hyper-Threading off, 32 GB RAM, 100 Mbit/s |

Decision of the operator (2026-10-14): install the vendor `.deb` **unpacked under node_dir** (Stellar pattern), not `apt install`;
versions **as the Monad docs require**: mainnet 0.16.4, testnet 0.16.3 (0.16.5 exists, the docs do not name it).

## 3. Client artifacts (`clients.yml`)
- Source: vendor apt repository `https://pkg.category.xyz` (noble, amd64). **Pinned** source (`type: pinned`), bumped by hand; the
  file name is deterministic: `pool/main/monad_<version>_amd64.deb` (736.9 MB for 0.16.4 / 736.8 MB for 0.16.3, sha256 of 0.16.4
  `050e8cae…26347` matches the apt index). The package is a **debug build** (3.1 GB unpacked).
- On disk: `monad-amd64.deb` (versionless). Unpacked by `EvmHostBinaries` (`Kind.DEB_BUNDLE`): one pass
  `dpkg-deb --fsys-tarfile | tar -x` of `monad monad-node monad-rpc monad-mpt monad-cli monad-keystore monad-sign-name-record`
  → `<node_dir>/bin/` (~1.2 GB) and all of `usr/local/lib` → `<node_dir>/lib/` (~285 MB). Everything else in the package
  (indexer, archiver, txgen, … 11 more binaries, the vendor units, `/opt/monad/scripts`) is not used.
- The ELF files carry **no usable RPATH** (the libs are found through ldconfig of `/usr/local/lib`), so every unit and tool call sets
  `LD_LIBRARY_PATH=<node_dir>/lib`.
- System libraries (network.yml `hostPackages`, noble names): libcrypto++8t64 libbrotli1 libgmp10 libboost-context1.83.0
  libboost-stacktrace1.83.0 libtbb12 libzstd1 liburing2 libarchive13t64 libssl3t64 zstd curl.
- Config: the vendor `full-node-node.toml` of the network (`bucket.monadinfra.com/config/<net>/latest/`) is synced as
  `monad-full-node.toml`.
- No Docker anywhere.

## 4. Ports (PORTS.md block 42020, `oneEnvPerHost`)
| Role | mainnet | testnet | Notes |
|---|---|---|---|
| http | 42020 | 42030 | `monad-rpc --rpc-port`, bound 0.0.0.0 (vendor default port 8080) |
| ws | 42021 | 42031 | `monad-rpc --ws-port` |
| p2p | 42022 | 42032 | node.toml bind port, tcp + udp (vendor 8000) |
| auth_udp | 42023 | 42033 | authenticated UDP (vendor 8001) |
| direct_udp | 42024 | 42034 | testnet only (vendor 8002); declared on both envs because the profile has one port set |

The docs also advise dropping short UDP packets on the P2P port: `iptables -I INPUT -p udp --dport <p2p> -m length --length 0:1400 -j DROP`
(network.yml `diskNotes`; the toolkit does not touch the firewall).

## 5. Disks
- Role `state` (nvme): BFT ledger, WAL, keystores, node.toml, sockets, snapshot staging (docs: 500 GB shared with the OS).
- **TrieDB is a raw block device, not a directory**, so it is the install option `triedb_device` (2 TB NVMe partition, 512-byte LBA).
  The toolkit **never partitions, formats or wipes a drive by itself**. `MonadHost.checkDevice` refuses a device that is not a block
  device, is mounted (or has a mounted partition), has a sector size ≠ 512, is smaller than 100 GiB, or carries any filesystem / RAID /
  LVM / partition-table signature (`blkid -p`) unless **this node** initialised it earlier (marker `<node_dir>/.toolkit/monad-triedb.device`
  holds the resolved path). Only then does the Snapshot step run `monad-mpt --create / --truncate / --create-empty` on it.

## 6. Units and config (what the vendor package does, moved under node_dir)
The three units of the 0.16.3 and 0.16.4 packages are **identical** (checked in the .deb and in the git tags) and are reproduced with
`User=monad`, `/home/monad`, `/opt/monad`, `/dev/triedb` and the OTEL collector dropped (the toolkit runs units as root like every other
chain; the device is opened by path). Flags that exist only in repo master (`--triedb-stats-path`, `--db-stats-file`) are **not** used.

| Unit | Command (flags = packaged unit) | Restart | CPUs |
|---|---|---|---|
| `rpcnode-monad-<env>` (primary) | `monad --chain monad_<net> --db <dev> --block_db <state>/ledger --statesync <state>/statesync.sock --sq_thread_cpu 1 --log_level INFO` | no | 1-7 |
| `…-bft-<env>` | `monad-node --secp-identity … --bls-identity … --node-config <state>/config/node.toml --forkpoint-config … --wal-path <state>/wal --mempool-ipc-path … --control-panel-ipc-path … --statesync-ipc-path … --ledger-path … --triedb-path <dev> --statesync-sq-thread-cpu 8 --record-metrics-interval-seconds 1 --validators-path … --persisted-peers-path … --keystore-password "${KEYSTORE_PASSWORD}"` | no | 8-11 |
| `…-rpc-<env>` | `monad-rpc --ipc-path <state>/mempool.sock --triedb-path <dev> --allow-unprotected-txs --node-config … --rpc-addr 0.0.0.0 --rpc-port <http> --ws-port <ws>` | always | 12-15 |
| `…-hugepages-<env>` (oneshot) | 4 × 1 GiB + 2048 × 2 MiB huge pages (vendor `set-hugepages.service`) | no | – |
| `…-cruft-<env>` + `.timer` | hourly ledger clean-up (vendor `monad-cruft`): ledger 10 h, forkpoint 5 h, validators 30 d, only while the ledger is growing | no | – |

All: `LimitNOFILE=1048576`, `LimitMEMLOCK=infinity`, `AllowedCPUs`/`CPUAffinity` as above (**needs ≥ 16 logical CPUs, checked at Start**),
`Requires=` the hugepages unit, companions start first (hugepages, bft, rpc, cruft) and then the primary. Execution and consensus
never restart by themselves (vendor `Restart=no`: a crashed node is looked at by the operator).
`REMOTE_VALIDATORS_URL` / `REMOTE_FORKPOINT_URL` are set in the consensus unit (Monad Foundation URLs, v0.12.1+ feature).

Generated on Start (`MonadProfile.prepare`, in this order; any failure stops the Start before a unit is written):
1. ≥ 16 CPUs; device check (above); `monad-rpc --help` under `LD_LIBRARY_PATH` (library hint + the `--rpc-addr/--rpc-port/--ws-port` flags exist).
2. `<state>/config/{forkpoint,validators}`, `ledger`.
3. Password: 64 hex chars in `<node_dir>/.toolkit/monad.env` (0600), created once. Keys: `monad-keystore create` for `config/id-secp` and `config/id-bls`
   (never overwritten; if only one exists the Start stops). The tool's output (private keys) goes to `.toolkit/backup/{secp,bls}-backup` (0600)
   — **the operator must copy password + backups off the host; they are the node identity**.
4. node.toml = synced vendor template with `node_name`, `[network]` bind ports and `[peer_discovery]` from `monad-sign-name-record`
   (public IPv4 from the optional `public_ip` option, else api.ipify.org / ifconfig.me / icanhazip.com). An up-to-date record is kept; a changed
   IP / port is re-signed with `seq + 1`. Vendor peer / bootstrap / statesync lists are kept untouched (replaced on re-sign).
5. First `forkpoint.toml` / `validators.toml` if missing; kernel buffers `/etc/sysctl.d/90-monad-network-buffer.conf` (system file, like the vendor package);
   the clean-up script `bin/rpcnode-monad-cruft.sh`.

## 7. Snapshot (required, scripted — `evm-snapshot://monad/<env>/full?triedb_device=…`)
Re-implementation of the docs' "hard reset" (`restore-from-snapshot.sh` is **not** downloaded or executed; it hardcodes `/home/monad` and `/dev/triedb`):
latest.txt → `<name>.tar.zst` + `.checksum` (8.66 GB mainnet / 3.08 GB testnet on 2026-10-14; sha256 verified) → unpack → `monad-mpt` create / truncate /
create-empty → `monad-cli --load_binary_snapshot … --version <block id> --sq_thread_cpu 15` → ledger / WAL / sockets reset → fresh forkpoint + validators.
The device path travels from the wizard's install options into the snapshot URL (`StartNodeSnapshotUseCase` + `EvmProfile.optionKeys`).
The RPC is not available until the import finished and statesync caught up (RPC listens only after statesync, docs).

## 10–11. Height and tip
`eth_blockNumber` on the `http` port (shared `EvmNodeHeightProbe`); public tips `https://rpc.monad.xyz` (chain id 0x8f) and
`https://testnet-rpc.monad.xyz` (0x279f), answered live 2026-10-14. `tipLagBlocks: 20` (400 ms blocks).

## 13. Install options (admin)
`snapshot` (single choice `full`) with the text field **TrieDB block device** (`triedb_device`, required) and `ip` / **Public IPv4** (`public_ip`, optional).

## 14. Wiring done
`NetworkId.MONAD`, `EvmProfiles.ALL` (→ `EvmWiring` tip / start / snapshot maps, `AgentMain` runtime), `ShippedNetworkIds`, admin (`NetworkIcon`,
`DiskLayoutPanel`, wizard list, `networkPaths`, `networkDocs`, `InstallOptionsPicker`). Shared changes: `EvmBinary.Kind.DEB_BUNDLE`, `EvmUnit.restart/serviceExtra`,
`EvmProfile.optionKeys`, `EvmLaunchOptions.extra`, `EvmSnapshotSentinel.withParams`. Tests: `MonadProfileTest`, `MonadNodeTomlTest` (+ `MonadCruftTest`),
`EvmHostBinariesDebTest` (needs dpkg-deb: skipped on macOS), `EvmCatalogTest`.

## 15. Open risks
| Risk | Impact | Mitigation / how to check |
|---|---|---|
| Never run on Linux / Monad hardware | any of the below | first install on a lab host, mainnet or testnet |
| `monad-rpc` flags `--rpc-addr/--rpc-port/--ws-port` are INFERRED (names found in the binary's strings, not its `--help`) | RPC on a wrong / default port (8080 clash) | Start runs `monad-rpc --help` and refuses when a flag is missing; check `--rpc-addr` is a host, not `host:port` |
| `--otel-endpoint` omitted (not in the binaries' required-argument lists) | node refuses to start | add `--otel-endpoint http://127.0.0.1:4317` to the unit if it does |
| `monad-sign-name-record` output: INFERRED to print `self_*` as `key = value` lines (strings found in the binary); values without quotes are quoted by the toolkit | broken node.toml | `config/node.toml` `[peer_discovery]` after the first Start |
| `monad-mpt --create` on a device that already holds a TrieDB (e.g. made by the vendor package) | semantics unverified | the Snapshot step reformats it anyway (`--truncate`) |
| Only a 1-stream `curl` download (vendor uses aria2c × 8) | slower snapshot | acceptable for 3–9 GB |
| Units run as root; keystore password is in the process arguments (same as vendor) | local users can read it from /proc | dedicated host; password file is 0600 |
| `AllowedCPUs` needs cgroup v2 cpuset delegation (vendor does the same) | unit fails on odd kernels | Ubuntu 24.04 default is fine |
| No `otelcol`, no `monad-status`, no `monad-ledger-tail`, no UFW rules, no udev rule | no metrics on :8889 | not part of the product; ports must be opened by the operator |
| Ledger growth if the cruft timer is stopped | state disk fills | `rpcnode-monad-cruft-<env>.timer` is a companion of the node |
| Version bump | 0.16.5+ may change flags | edit the pin in `clients.yml`, compare the packaged units (`/usr/lib/systemd/system/monad-*.service` in the .deb) |
