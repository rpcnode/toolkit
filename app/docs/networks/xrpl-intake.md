# Network intake — XRP Ledger (xrpl)

Filled from Go sibling `../toolkit-go/internal/networks/xrpl/` and admin helpers (`xrpl`, `xrpl_history`).

| Field | Answer |
|---|---|
| Network id | `xrpl` |
| Display label | XRP Ledger |
| Author | toolkit (port from Go facts) |
| Date | 2026-09-02 |
| Status | **implemented** (operator request to add xrpl) |

**Go sibling:** `SnapNever`, apt/`xrpld`, history window via Install options, `server_info` height. Kotlin keeps bins + `xrpld.cfg` + validators under **node_dir** only (never `/opt/ripple` or `/etc/opt/ripple`). Do not edit or run Go.

---

## Review table

| Section | Status | Notes |
|---|---|---|
| 1 Product scope | OK | Stock non-validator xrpld RPC |
| 2 Environments | OK | mainnet, testnet |
| 3 Artifacts | OK | Ripple `.deb` pool (version from XRPLF/rippled GitHub) |
| 4 Ports | OK | http + p2p + ws + grpc |
| 5 Disks | OK | ledger (SSD NuDB) |
| 6 Client config | OK | `format: flags` — generated xrpld.cfg |
| 7 Snapshot | OK | `never` — peer IBD / history window |
| 8–9 Start / proc | OK | Extract deb under node_dir → unit → xrpld |
| 10–11 Height / tip | OK | `server_info` validated_ledger.seq |
| 12–13 Lifecycle / options | OK | `xrpl_history` stock/day/weeks/full |
| **Overall** | Ready to implement | |

---

## 1. Product scope

1. **Why:** Operators run stock XRPL full-history / windowed RPC (`xrpld`).
2. **MVP:** Clients download (`.deb`), JBOD ledger disk, ports, history Install option, start unit, push ledger height + sync %, public tip lag.
   **Host layout:** bins, cfg, validators, NuDB, logs under **node_dir** only.
   **Out of scope:** Clio + Scylla, validator / UNL publishing, Docker, Go `/opt`/`/etc` apt provision paths.
3. **Pin-only?** No — public `.deb` at `repos.ripple.com` (amd64).
4. **One env per host?** `false`.

---

## 2. Environments

| Env id | Label | Production? | Notes |
|---|---|---|---|
| `mainnet` | XRP Ledger Mainnet | yes | genesis ledger 32570 |
| `testnet` | XRP Ledger Testnet | test | AltNet `network_id=1` |

Same binary family; network via `[network_id]` / `[ips_fixed]` / validators UNL.

---

## 3. Client binary & artifacts

1. **Upstream:** GitHub `XRPLF/rippled` for version tags; binary from Ripple apt pool
   `https://repos.ripple.com/repos/rippled-deb/pool/stable/xrpld_{version}-1_amd64.deb`.
2. **Latest:** newest non-prerelease GitHub tag (prefer ≥ 3.3.0; avoid 3.2.x first-ledger bug).
3. Stable name: `xrpld-amd64.deb` (amd64 only — no official arm64 `xrpld` package).
4. Config: generated `xrpld.cfg` + `validators.txt` on Start (not downloaded).
5. Program id: `xrpld`.
6. Requirements: `logFile: logs/xrpld.log`.
7. **No Docker.** Extract with `dpkg-deb -x` under node_dir (Polygon pattern).

---

## 4. Fixed ports

| Env | Role | Port |
|---|---|---|
| mainnet | http | 5005 |
| mainnet | p2p | 51235 |
| mainnet | ws | 6005 |
| mainnet | grpc | 51251 |
| testnet | http | 5006 |
| testnet | p2p | 51236 |
| testnet | ws | 6008 |
| testnet | grpc | 51252 |

Height uses **http** (`server_info`). WS admin local stays 6006/6007 (vendor pin in cfg, not catalog).

---

## 5. Disks & sizing

| Role | Media | Leaf |
|---|---|---|
| ledger | ssd | NuDB / `database_path` |

| Env | diskHint / full | CPU | RAM |
|---|---|---|---|
| mainnet | 1024 (weeks window; full ~39 TiB via option) | 8 | 64 |
| testnet | 128 | 4 | 16 |

History window is an Install option, not a second disk role.

---

## 6. Client config

`format: flags` — Start preview bindings; process starter writes `xrpld.cfg`:

| Binding | Source | Notes |
|---|---|---|
| ledger | disk_role_dir | NuDB parent |
| http / p2p / ws / grpc | catalog_port | |
| xrpl_history | install_option | stock/day/weeks/full |
| peers_max | literal `100` | Go stock |
| LimitNOFILE | literal `1048576` | unit |

---

## 7. Snapshot

`snapshot: never` — no toolkit aria2 / CDN mirror. Peer sync fills the chosen `[ledger_history]` window.

---

## 8–9. Start / process

1. Sync `xrpld-amd64.deb` → extract `xrpld` under `{node_dir}/bin/`.
2. Write `validators.txt`, `history.json`, `xrpld.cfg` under node_dir (data under ledger role).
3. systemd unit: `ExecStart=…/xrpld --conf …`, bounded `server_stop` on ExecStop, `LimitNOFILE=1048576`.

---

## 10–11. Height / tip

- **Height:** local POST `server_info` → `validated_ledger.seq`; sync % from complete_ledgers vs history window / genesis.
- **Tip:** same method against `publicTip.urls` (xrplcluster / Ripple public).

---

## 12–13. Lifecycle / options

Install option group `xrpl_history` (admin already ships picker): stock 2k / day 25k / weeks 300k / full.

Lifecycle: syncing until tip live **and** history window OK → active.
