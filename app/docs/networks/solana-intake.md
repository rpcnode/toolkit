# Network intake — Solana

Filled from Go sibling `../toolkit-go/internal/networks/solana/` and admin helpers.

| Field | Answer |
|---|---|
| Network id | `solana` |
| Display label | Solana |
| Author | toolkit (port from Go facts) |
| Date | 2026-09-02 |
| Status | **approved** (operator request to add solana) |

**Go sibling:** facts / Agave unit + run-validator / getSlot tip / via-node snapshot. Do not edit or run Go.

---

## Review table

| Section | Status | Notes |
|---|---|---|
| 1 Product scope | OK | Non-voting Agave RPC (full / archive ledger) |
| 2 Environments | OK | mainnet, testnet, devnet (localnet later) |
| 3 Artifacts | OK | anza-xyz/agave linux x86_64 tarball |
| 4 Ports | OK | http + p2p (+ dynamic range) |
| 5 Disks | OK | ledger / accounts / snapshots |
| 6 Client config | OK | `format: flags` — run-validator.sh |
| 7 Snapshot | OK | `required` via Agave (not toolkit aria2) |
| 8–9 Start / proc | OK | Templates `resources/chains/solana/*.tmpl` + run-validator |
| 10–11 Height / tip | OK | `getSlot` JSON-RPC |
| 12–13 Lifecycle / options | OK | node=full\|archive |
| **Overall** | Ready to implement | |

---

## 1. Product scope

1. **Why:** Operators run Solana RPC nodes (Agave, non-voting).
2. **MVP:** download Agave, JBOD disks, ports, start unit, push slot height, public tip, full vs archive.
   **Source build:** required when Anza’s `solana-release` tarball lacks `agave-validator`
   (since Agave v3.0). First Start **starts a background** host build (OS packages + rustup + cargo
   into `{node_dir}/bin`); systemd unit is installed only on a later Start once the binary exists
   (avoids panel/proxy HTTP timeouts killing cargo). Log: `{node_dir}/.toolkit/agave-build.log`.
   **Host layout:** binaries, `run-validator.sh`, identity stay under **node_dir** only — never `/opt/solana` or `/etc/solana` (Go sibling differs; Kotlin does not copy that).
   **Out of scope:** localnet test-validator, host apt package install, BigTable archive RPC.
3. **Pin-only?** No — artifacts under `clients/solana.yml`.
4. **One env per host?** `false`.

---

## 2. Environments

| Env id | Label | Production? | Notes |
|---|---|---|---|
| `mainnet` | Solana Mainnet | yes | cluster `mainnet-beta` |
| `testnet` | Solana Testnet | test | |
| `devnet` | Solana Devnet | test | |

Same Agave binary; network via entrypoints / genesis flags. **localnet** deferred.

---

## 3. Client binary & artifacts

1. **Upstream:** GitHub `anza-xyz/agave` release tarball.
2. **Latest:** newest GitHub release.
3. **Artifact:** `solana-release-x86_64-unknown-linux-gnu.tar.bz2` only (no linux aarch64 from Anza).
4. Stable name: same as upstream filename.
5. Program id: `agave-validator`.

---

## 4. Fixed ports

| Env | Role | Port |
|---|---|---|
| mainnet | http | 8899 |
| mainnet | p2p | 8000 |
| testnet | http | 8891 |
| testnet | p2p | 8100 |
| devnet | http | 8893 |
| devnet | p2p | 8200 |

Height uses **http** (`getSlot`). P2P is base of Agave `--dynamic-port-range` (span 26).

---

## 5. Disks & sizing

| Role | Media | Leaf |
|---|---|---|
| ledger | nvme | Agave `--ledger` |
| accounts | nvme | Agave `--accounts` |
| snapshots | ssd | Agave `--snapshots` |

| Env | diskHint / full | archive | CPU | RAM |
|---|---|---|---|---|
| mainnet | 2048 | 409600 | 16 | 128 |
| testnet | 1024 | 102400 | 12 | 64 |
| devnet | 512 | 51200 | 8 | 32 |

---

## 7. Snapshot

`required` for mainnet/testnet/devnet — **via node** (Agave downloads cluster snapshot). No `SnapshotResolver` / aria2 CDN. Admin `snapshotStartsViaNode` treats Solana as ExtraStep = Start unit.

Snapshot type sizes (compressed archive floor for UI): mainnet ~160 GiB, testnet ~60, devnet ~40.

---

## 8–9. Start / proc

- Templates: `resources/chains/solana/node.service.tmpl` + `run-validator.sh.tmpl`
- Unit `rpcnode-solana-<env>` → `{node_dir}/run-validator.sh` (ledger leaf).
- Binary / keygen under `{node_dir}/bin/` (build into that path when Anza tarball has no validator).
- Identity at `{node_dir}/.toolkit/validator-keypair.json` (`solana-keygen`).
- **Never** write `/opt/solana` or `/etc/solana`.
- On Start the agent writes `/etc/sysctl.d/21-solana.conf` (UDP buffers + `fs.nr_open`) so Agave
  does not fail with "OS network limit test failed".
- Full: `--limit-ledger-size`. Archive: omit that flag.

---

## 10–11. Height / tip

- Local: `POST http://127.0.0.1:{http}/` → `getSlot`.
- Tip: `https://api.mainnet-beta.solana.com` / testnet / devnet (same method).

---

## 13. Install options

`node=full|archive` (admin `SOLANA_NODE_OPTIONS`).
