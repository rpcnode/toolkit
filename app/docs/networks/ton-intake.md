# Network intake — Toncoin (TON)

Filled from Go sibling `../toolkit-go/internal/networks/ton/` and admin helpers.

| Field | Answer |
|---|---|
| Network id | `ton` |
| Display label | Toncoin |
| Author | toolkit (port from Go facts) |
| Date | 2026-09-02 |
| Status | **implemented** (operator request — full MyTonCtrl port) |

**Go sibling:** MyTonCtrl liteserver via `install.sh`, host-global `/var/ton-work`, stock units `validator` / `mytoncore` / `ton_http_api`. Kotlin keeps rpcnode scripts, markers, and primary units under **node_dir**; bootstrap creates `/var/ton-work` → blockchain disk (documented exception — MyTonCtrl cannot run node_dir-only). Do not edit or run Go.

---

## Review table

| Section | Status | Notes |
|---|---|---|
| 1 Product scope | OK | Liteserver + TON HTTP API (not validator staking) |
| 2 Environments | OK | mainnet, testnet; oneEnvPerHost |
| 3 Artifacts | OK | Pin-only — MyTonCtrl install.sh (no CDN tarball) |
| 4 Ports | OK | http (THA) + p2p |
| 5 Disks | OK | blockchain (NVMe) + archive (SSD) |
| 6 Client config | OK | `format: flags` — bindings for Start preview |
| 7 Snapshot | OK | `never` — dump/archive via install options |
| 8–9 Start / proc | OK | bootstrap oneshot → stock units |
| 10–11 Height / tip | OK | getMasterchainInfo seqno; tip via toncenter |
| 12–13 Lifecycle / options | OK | sync→active; history dump\|archive |
| **Overall** | Ready / implemented | |

---

## 1. Product scope

1. **Why:** Operators run Toncoin liteserver RPC (MyTonCtrl + TON HTTP API).
2. **MVP:** pin-only enable, JBOD disks, ports, history option, MyTonCtrl bootstrap, start units, push seqno height, public tip lag via toncenter. **Host layout:** rpcnode scripts/markers under **node_dir**; `/var/ton-work` symlink required by MyTonCtrl.
   **Out of scope:** validator staking duties, Docker, SelfHeal OOM tick, public JSON-RPC rewrite proxy.
3. **Pin-only?** Yes — no public toolkit tarball; host `install.sh` from `ton-blockchain/mytonctrl`. Listed in `NetworkPinOnly`.
4. **One env per host?** `true` (`mytonctrl_global_workdir`).

---

## 2. Environments

| Env id | Label | Production? | Notes |
|---|---|---|---|
| `mainnet` | Toncoin Mainnet | yes | `-n mainnet` |
| `testnet` | Toncoin Testnet | test | `-n testnet` |

Same install path; chain flag selects network.

---

## 3. Client binary & artifacts

1. **Upstream:** MyTonCtrl `https://raw.githubusercontent.com/ton-blockchain/mytonctrl/master/scripts/install.sh` (`-m liteserver`).
2. **Latest:** host installer owns versions (pin reference `v2.18.0` in clients.yml for UI).
3. Artifacts: **none** (pin-only).
4. Program id: `validator-engine`.
5. Requirements: `logFile: logs/ton.log`.
6. **No Docker.**

---

## 4. Fixed ports

| Env | Role | Port |
|---|---|---|
| mainnet | http | 8081 |
| mainnet | p2p | 30310 |
| testnet | http | 8082 |
| testnet | p2p | 30311 |

Height/tip use **http** (TON HTTP API). P2P = validator listen.

---

## 5. Disks & sizing

| Role | Media | Leaf |
|---|---|---|
| blockchain | nvme | `/var/ton-work` target (db) |
| archive | ssd | archive / aux |

| Env | diskHint / full | archiveGiB | CPU | RAM |
|---|---|---|---|---|
| mainnet | 1024 | 12288 | 8 | 32 |
| testnet | 256 | 4096 | 4 | 16 |

---

## 6. Client config

`format: flags` — Start preview bindings; bootstrap/unit apply the same literals:

| Key | Source | Applied |
|---|---|---|
| blockchain | disk_role_dir blockchain | workdir symlink |
| archive | disk_role_dir archive | aux |
| http | catalog_port http | THA port |
| p2p | catalog_port p2p | VALIDATOR_PORT |
| history | install_option history | `-d` / `--archive` |
| LimitNOFILE | literal 4194304 | validator drop-in + unit |
| archive_ttl | literal 2592000 | env / meta |
| state_ttl | literal 86400 | env / meta |
| fs.nr_open | literal 8388608 | sysctl |

---

## 7. Snapshot

`never` for both envs. Dump (~30d) or archive is MyTonCtrl install flavor, not toolkit aria2.

---

## 8–9. Start / proc

- Write `{node_dir}/bin/rpcnode-ton-{bootstrap,node-start}.sh`
- Units: `rpcnode-ton-{env}-bootstrap.service` (oneshot) + `rpcnode-ton-{env}.service`
- Node-start: if no `.toolkit/bootstrap.done` → start bootstrap; else start `validator` / `mytoncore` / THA
- Symlink `/var/ton-work` → blockchain disk role

---

## 10–11. Height / tip

- Local: `GET http://127.0.0.1:{http}/getMasterchainInfo` (fallback `/api/v2/…`) → `result.last.seqno`
- Tip: same parse against YAML `publicTip.urls` (toncenter)
- Optional: local oos may set `syncing` on height reading

---

## 12–13. Lifecycle / options

sync → active on tip lag ≤ 3. Install option group `history` default `dump` (`archive` → `--archive`).
