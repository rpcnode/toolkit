# Network intake — Bitcoin (worked example)

Filled from the template [`../adding-a-network-intake.md`](../adding-a-network-intake.md).  
Use this as the review format when onboarding another chain.

| Field | Answer |
|---|---|
| Network id | `bitcoin` |
| Display label | Bitcoin |
| Author | toolkit (port from Go facts + current Kotlin impl) |
| Date | 2026-09-01 |
| Reviewers | _(fill)_ |
| Status | **implemented** (questionnaire filled retrospectively to validate the template) |

**Go sibling:** `../toolkit/internal/networks/bitcoin/` — port **facts / ports / sizing** only; do not edit or run Go.

---

## Review table

| Section | Status | Notes |
|---|---|---|
| 1 Product scope | OK | Full node RPC product path |
| 2 Environments | OK | mainnet, testnet4, signet, regtest |
| 3 Artifacts | OK | One Core tarball for all envs |
| 4 Ports | OK | Catalog fixed; testnet4 reuses 18332/18333 |
| 5 Disks | OK | blockchain + index roles |
| 6 Client config | OK | INI + env sections |
| 7 Snapshot | OK | `never` all envs |
| 8–9 Start / proc | OK | bitcoind foreground |
| 10–11 Height / tip | OK | cli + mempool.space |
| 12–13 Lifecycle / options | OK | sync → active; txindex / max_connections |
| 15 Risks | See below | |
| **Overall** | Ready as template reference | |

---

## 1. Product scope

1. **Why:** Operators run Bitcoin Core full nodes (RPC) managed by the panel/agent.
2. **MVP:** download Core release, disks layout, ports check, render `bitcoin.conf`, start `bitcoind`, push height, show tip lag in admin. **Out of scope:** Electrs, wallet UI, pruned-as-default product mode (txindex is an option).
3. **Pin-only?** No — artifacts under `clients/bitcoin.yml`.
4. **One env per host?** `false` — multiple envs can share a host if ports do not collide.

---

## 2. Environments

| Env id | Label | Production? | Notes |
|---|---|---|---|
| `mainnet` | Bitcoin Mainnet | yes | |
| `testnet4` | Bitcoin Testnet4 | test | Catalog ports intentionally legacy 18332/18333 (not Core’s newer 48332/48333) |
| `signet` | Bitcoin Signet | test | |
| `regtest` | Bitcoin Regtest | local | No `publicTip` |

1. Env ids: as above (stable).
2. **Same binary** for all envs (`bitcoind`); network selected via conf section / flags.
3. **regtest** has no public tip URLs.

---

## 3. Client binary & artifacts

1. **Upstream:** GitHub `bitcoin/bitcoin` for release discovery; binaries from `bitcoincore.org/bin/…`.
2. **Latest:** newest non-draft GitHub release (`BitcoinClientReleaseResolver`).
3. **Artifacts:**  
   - `bitcoin-{version}-x86_64-linux-gnu.tar.gz`  
   - `bitcoin-{version}-aarch64-linux-gnu.tar.gz`
4. **Stable names:** `bitcoin-x86_64-linux-gnu.tar.gz` / `bitcoin-aarch64-linux-gnu.tar.gz`.
5. **Config:** `bitcoin.conf` from `raw.githubusercontent.com/bitcoin/bitcoin/{tag}/share/examples/bitcoin.conf`.
6. **Program id:** `bitcoin`.

---

## 4. Fixed ports

| Env | Role | Port | Label |
|---|---|---|---|
| mainnet | p2p | 8333 | P2P |
| mainnet | rpc | 8332 | RPC |
| mainnet | zmq_rawblock | 28332 | ZMQ rawblock |
| mainnet | zmq_rawtx | 28333 | ZMQ rawtx |
| testnet4 | p2p | 18333 | P2P |
| testnet4 | rpc | 18332 | RPC |
| testnet4 | zmq_rawblock | 28342 | ZMQ rawblock |
| testnet4 | zmq_rawtx | 28343 | ZMQ rawtx |
| signet | p2p | 38333 | P2P |
| signet | rpc | 38332 | RPC |
| signet | zmq_rawblock | 28352 | ZMQ rawblock |
| signet | zmq_rawtx | 28353 | ZMQ rawtx |
| regtest | p2p | 18444 | P2P |
| regtest | rpc | 18443 | RPC |
| regtest | zmq_rawblock | 28362 | ZMQ rawblock |
| regtest | zmq_rawtx | 28363 | ZMQ rawtx |

1. All of the above are **catalog-fixed**.
2. Height uses **RPC** role (via `bitcoin-cli`, not raw HTTP).
3. ZMQ reserved for future / aux; not required for height MVP.

---

## 5. Disks & sizing

**Roles:**

| id | label | media | Data |
|---|---|---|---|
| `blockchain` | Blockchain data | ssd | blocks / chainstate (`datadir`) |
| `index` | Index / auxiliary | ssd | optional `blocksdir`; Electrs not in product |

| Env | diskHintGiB | fullNodeGiB | cpuCores | memoryGiB |
|---|---|---|---|---|
| mainnet | 1024 | 1024 | 4 | 16 |
| testnet4 | 128 | 128 | 2 | 8 |
| signet | 64 | 64 | 2 | 4 |
| regtest | 8 | 8 | 1 | 2 |

**diskNotes:** Electrs not in product — second role is reserved index/aux path only.  
**Source:** Go sibling facts + current `networks/bitcoin.yml`.

---

## 6. Client config shape

1. **Format:** `ini`.
2. **Templates:** single `bitcoin.conf` with **env sections** (`main` / `testnet4` / `signet` / `regtest`).
3. **Bindings (current):**

| Config key | Source | Detail |
|---|---|---|
| `datadir` | `disk_role_dir` | role `blockchain` |
| `blocksdir` | `disk_role_dir` | role `index`, optional |
| `port` | `catalog_port` | role `p2p` |
| `rpcport` | `catalog_port` | role `rpc` |
| `rpcbind` | `literal` | `127.0.0.1` |
| `maxconnections` | `install_option` | `max_connections`, default `125` |
| `txindex` | `install_option` | `txindex`, default `0` |
| `server` | `literal` | `1` |

4. Start step shows read-only preview from bindings; deeper edits later via node config if exposed.

---

## 7. Snapshot policy

- All envs: **`snapshot: never`**.
- No snapshot resolver / CDN path for Bitcoin in this product.
- Sync is IBD / tip catch-up from the network (or empty regtest).

---

## 8. Start recipe

1. **Binary:** `bitcoin/bin/bitcoind` after tarball extract/normalize to `bitcoin/`.
2. **Args:** `-conf=<ini> -daemon=0` (foreground — agent owns PID).
3. **Extract:** `*.tar.gz` → normalize dir `bitcoin`.
4. **Launch kind:** `binary`; height kind: `bitcoin_cli`, port role `rpc`.
5. **Class:** `BitcoinNodeStart`.

---

## 9. Host process start

1. Shared `HostNodeLaunchSupport.startProcess` only — no Java opts / special env.
2. **Class:** `BitcoinNodeProcessStarter`.

---

## 10. Local height

1. **`bitcoin-cli -conf=… getblockcount`** from node dir (`bitcoin/bin/bitcoin-cli`).
2. Uses the same conf as bitcoind (RPC auth from conf); no HTTP to rpcport for height MVP.
3. **Class:** `BitcoinNodeHeightProbe` (`suspend`, IO dispatcher).
4. Failures → `null` sample skipped.

---

## 11. Public network tip

1. Yes for mainnet / testnet4 / signet — show behind in header + Sync step.
2. **Exception:** local height is `bitcoin-cli getblockcount`, not HTTP — tip cannot hit the
   same endpoint. Public tip is plain-text height URLs (same integer meaning):
   - mainnet: `https://mempool.space/api/blocks/tip/height`
   - testnet4: `https://mempool.space/testnet4/api/blocks/tip/height`
   - signet: `https://mempool.space/signet/api/blocks/tip/height`
3. **Class:** `BitcoinNetworkTipProbe` (GET text via `SimpleHttp`). Prefer same-as-height when
   the chain exposes HTTP height (see TRON + `publicTip.urls` only).
4. Promote **`sync` → `active`** when behind ≤ 3 (panel `GetNodeHeightUseCase` default).
5. **regtest:** no tip.

---

## 12. Lifecycle & panel status

1. Start success → **`sync`**; tip lag within threshold → **`active`**. Fits Bitcoin IBD/catch-up.
2. No extra snapshot statuses.
3. Admin polls `GET /api/nodes/{id}/height` only while `sync` / `active`.

---

## 13. Install options

| Option | Default | Maps to |
|---|---|---|
| `max_connections` | `125` | conf `maxconnections` |
| `txindex` | `0` | conf `txindex` |

(Exact wizard group wiring follows existing Install options YAML / UI for Bitcoin.)

---

## 14. Wiring checklist (done in tree)

- [x] `networks/bitcoin.yml`
- [x] `clients/bitcoin.yml`
- [x] `NetworkId.BITCOIN` / env ids
- [x] `BitcoinNodeStart` / `BitcoinNodeProcessStarter`
- [x] `BitcoinNodeHeightProbe` / `BitcoinNetworkTipProbe`
- [x] `BitcoinClientReleaseResolver`
- [x] `Toolkit.production()` + agent `ChainNodeRuntime`
- [x] Tests under `chains/bitcoin/…`

---

## 15. Open risks

| Risk | Impact | Mitigation |
|---|---|---|
| testnet4 catalog ports ≠ upstream Core defaults | Confusion vs docs / other stacks | Document in `clients/bitcoin.yml`; keep stable for existing deploys |
| mempool.space tip outage | `network_height` null; no `active` promote | Retry URLs list; tolerate null tip |
| IBD on mainnet takes long | Sync step looks “stuck” | Show height + behind; do not fake 100% |
| `txindex=1` disk growth | Operator surprise | Default `0`; document in option description |
| Electrs / index role unused | Empty second disk | Notes in YAML; optional `blocksdir` |

---

## Mapping → code (quick index)

| Concern | Location |
|---|---|
| Facts / tip / clientConfig | `app/src/main/resources/chains/bitcoin/network.yml` |
| Ports / artifacts | `app/src/main/resources/chains/bitcoin/clients.yml` |
| Start plan | `…/chains/bitcoin/infrastructure/start/BitcoinNodeStart.kt` |
| Host start | `…/chains/bitcoin/infrastructure/proc/BitcoinNodeProcessStarter.kt` |
| Host height | `…/chains/bitcoin/infrastructure/http/BitcoinNodeHeightProbe.kt` |
| Public tip | `…/chains/bitcoin/infrastructure/http/BitcoinNetworkTipProbe.kt` |
| Latest release | `…/chains/bitcoin/infrastructure/http/BitcoinClientReleaseResolver.kt` |
