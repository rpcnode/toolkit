# Adding a network — intake questionnaire

**Audience:** developers adding a chain to toolkit-kotlin.  
**Gate:** fill this document **before** writing YAML / Kotlin. Review and approve the answers, then implement.

Companion checklist (files to touch after approval): section **14. Wiring checklist** below.  
Chain layout: one package per network under `chains/<id>/` — never a shared `when (network)` monolith (see [`ARCHITECTURE.md`](../ARCHITECTURE.md)).

Worked example: [`networks/bitcoin-intake.md`](networks/bitcoin-intake.md).

---

## How to use

1. Copy this file to `app/docs/networks/<id>-intake.md` (or fill a PR description from the sections below).
2. Answer every question. Use `TBD` only when research is blocked; list what is missing.
3. Mark each section **Approved** / **Needs change** in the review table at the top of the filled copy.
4. Only after approval: implement `resources/chains/<id>/` (YAML + tmpls) + Kotlin `chains/<id>/…`, wiring, tests.

Do **not** invent chain rules in shared “planner” classes. Every network-specific answer maps to types under `chains/<id>/` or declarative YAML.

---

## 0. Meta

| Field | Answer |
|---|---|
| Network id (filename / `NetworkId`) | |
| Display label | |
| Author | |
| Date | |
| Reviewers | |
| Status | draft / approved / implemented |

**Go sibling (read-only):** does `../toolkit/internal/networks/<id>/` exist? What can we port (facts only)?

---

## 1. Product scope

1. **Why this network?** (operator need / product priority)
2. **MVP vs later:** what must work in v1 (install, start, height, snapshot, …)? What is explicitly out of scope?
3. **Pin-only?** Only if there is **no** public tarball/deb/binary URL. Prefer GitHub/CDN
   artifacts in `clients.yml` even when upstream also ships a container image.
   Do **not** use Docker (`docker pull` / `docker cp` / ghcr) as the install path.
   → `NetworkPinOnly` only when truly no downloadable artifact exists.
4. **One env per host?** (`oneEnvPerHost` in networks YAML)

---

## 2. Environments

For **each** env:

| Env id | Label | Production? | Notes |
|---|---|---|---|
| | | | |

Questions:

1. Env ids (lowercase, stable forever — used in URLs, SQLite, disk paths).
2. Which envs share the **same binary** vs need separate artifacts?
3. Any env that must **not** expose a public tip (local-only / regtest)?

---

## 3. Client binary & artifacts (`chains/<id>/clients.yml`)

1. **Upstream source:** GitHub repo? Official CDN? Other? (Prefer release assets over container images.)
2. **How we pick “latest”:** GitHub latest release? Tag prefix? Channel (stable/rc)?
3. **Artifacts per arch:** x86_64 / aarch64 filenames and URL templates (`{version}`, `{tag}`).
4. **On-disk stable names** (`name` / `nameAarch64`) — versionless names for sync/install-plan.
5. **Shipped config template(s):** file name(s), URL or vendored under `public/install/clients/`?
6. **Program id** used in install plan / `clientConfig.program`.
7. **Runtime requirements** (`programs[].requirements`): e.g. `javaMajor: 8`, `logFile: logs/tron.log`.
8. **No Docker:** confirm install does not require `docker pull` / extract from an image.

---

## 4. Fixed ports (`chains/<id>/clients.yml` → `programs[].ports`)

| Env | Role | Port | Label | Bound by product? |
|---|---|---|---|---|
| | | | | |

Questions:

1. Which ports are **catalog-fixed** vs allocated dynamically?
2. Which roles matter for height / RPC / P2P / ZMQ / HTTP?
3. Conflicts with other networks on the same host? (`oneEnvPerHost`, port ranges)

---

## 5. Disks & sizing (`chains/<id>/network.yml`)

1. **`diskRoles`:** id, label, media (`nvme` / `ssd` / `hdd`). What data lives where?
2. Per env: `diskHintGiB`, `fullNodeGiB`, `archiveGiB` (if any), `cpuCores`, `memoryGiB`.
3. **`diskNotes`** for the operator UI?
4. Source of numbers (Go facts, vendor docs, ops experience)?

---

## 6. Client config shape (`chains/<id>/network.yml` → `clientConfig`)

1. **Format:** `ini` / `hocon` / `flags` / other?
2. **Template mapping:** one file for all envs, or per-env templates / INI sections?
3. **Bindings:** list keys the panel must inject (disk roles, catalog ports, install options, literals).
4. What stays **operator-editable** later vs read-only at Start?
5. **Capacity / production knobs** (required — do not skip):
   - max connections, peer slots, RPC / PubSub thread pools, subscription caps
   - request body / message size limits
   - systemd `LimitNOFILE` (or similar) if the unit raises them
   - any Go-facts / vendor “production RPC” flags beyond paths and ports
   - Each must appear in `clientConfig.bindings` so Start **Config preview** shows it
     on day one, and the same value must be written into the conf / run script / unit
     (not only hardcoded in Kotlin). For `format: flags`, keep YAML literals identical
     to the numbers in the chain’s unit/script renderer.

Example binding inventory:

| Config key | Source (`disk_role_dir` / `catalog_port` / `install_option` / `literal`) | Role / option / value | Applied where (conf / flags / unit)? |
|---|---|---|---|
| | | | |

---

## 7. Snapshot policy

1. Per env: `snapshot: required | optional | never`?
2. If snapshots exist: resolver class under `chains/<id>/…`, types (`full` / `lite` / `archive`), size hints, CDN mirror?
3. Destination directory rules (role leaf, `destLeaf`)?
4. Can the node serve RPC while snapshot extract is still running? (affects wizard “ready”)

---

## 8. Start recipe (`chains/<id>/infrastructure/start`)

1. **Process model:** binary / JVM jar under **node_dir** (not Docker). Entry path relative to node dir?
2. **Args** (foreground vs daemon — agent must own the PID).
3. **Archive extract / normalize** (`extractArchiveGlob`, `normalizeDir`)?
4. **`NodeLaunchSpec.kind`** and height kind string sent to the agent.
5. Class name: `<Id>NodeStart` implementing `ChainNodeStart`.

---

## 9. Host process start (`chains/<id>/infrastructure/proc`)

1. Anything beyond shared `HostNodeLaunchSupport` (env vars, working directory, ulimit, Java opts)?
2. Class name: `<Id>NodeProcessStarter`.
3. **Layout:** binaries, scripts, identity, logs stay under **node_dir** only.
   Never write `/opt/<chain>` or `/etc/<chain>` (Go sibling may; Kotlin must not).
   Systemd unit file in `/etc/systemd/system` via `HostNodeLaunchSupport` is fine.

---

## 10. Local height (`chains/<id>/infrastructure/http` — host)

1. How do we read **this node’s** height? HTTP? CLI? IPC?
2. Endpoint / command, auth, bind address assumptions (`127.0.0.1` only?).
3. Class: `<Id>NodeHeightProbe` implementing `HostNodeHeightProbe` (`suspend`).
4. Failure mode: return `null` (agent skips sample) — never crash the push loop.

---

## 11. Public network tip (panel)

**Default:** tip uses the **same protocol as local height**. In YAML you only list public
endpoints that speak that protocol — no separate tip format.

```yaml
publicTip:
  urls:
    - https://api.shasta.trongrid.io/wallet/getnowblock
```

1. Do we show “behind tip” in admin? If yes: `publicTip.urls` per env (skip local-only envs).
2. Tip probe class `<Id>NetworkTipProbe` must **reuse** the height reader (same HTTP/CLI
   helper). Example: TRON local `127.0.0.1:{port}/wallet/getnowblock` and Trongrid URL both
   go through one `getnowblock` fetch+parse.
3. Exception only when local height is not a public HTTP API (e.g. Bitcoin `bitcoin-cli`) —
   then tip is a thin adapter over public HTTP that still returns a block height integer.
4. When is status promoted **`sync` → `active`** (default tip lag ≤ 3)?

---

## 12. Lifecycle & panel status

1. After successful start: status **`sync`** (catching tip) then **`active`** (caught up). Confirm this fits the chain.
2. Any extra statuses needed (IBD-specific, snapshot phases already covered by snapshot jobs)?
3. Admin: Sync step + header height poll only while `sync` / `active`.

---

## 13. Install options (wizard)

1. Options shown on Install / Start (txindex, prune, snapshot type, …)?
2. Defaults and validation.
3. How they map into client config bindings.

---

## 14. Wiring checklist (implementation — after approval)

Do not start this until section 0 status is **approved**.

**Reference implementation:** TRON (`chains/tron`, `resources/chains/tron/node.service.tmpl`).
Cursor rule: `.cursor/rules/adding-a-network.mdc`.

### Resources (do these first — all under `app/src/main/resources/chains/<id>/`)

- [ ] `network.yml` — catalog + disk/CPU/RAM + `clientConfig` + snapshot policy
- [ ] `clientConfig.bindings` include capacity knobs (connections / RPC pools / body size /
      `LimitNOFILE`, …) visible on Start; values also applied in conf / flags / unit
- [ ] `clients.yml` — programs, ports, **GitHub/CDN** artifact URLs (no Docker install path;
      + `snapshots:` mirrors if toolkit CDN)
- [ ] `node.service.tmpl` — **required** systemd unit via `HostSystemdUnitTemplate.load/render`
- [ ] Extra tmpls if needed (`lighthouse.service.tmpl`, `run-validator.sh.tmpl`, …)
### Catalog / policy

- [ ] `NetworkId` companion val
- [ ] `EnvId` companion vals for **new** env ids only
- [ ] `clients.yml` GitHub/CDN artifacts (not Docker); `NetworkPinOnly` only if no public binary URL

### Chain package (`app/src/main/kotlin/.../chains/<id>/` — never under `src/agent`)

- [ ] `infrastructure/start/<Id>NodeStart`
- [ ] `infrastructure/proc/<Id>NodeProcessStarter` (uses tmpl via `HostNodeLaunchSupport` or `installCustomUnits`)
- [ ] `infrastructure/http/<Id>NodeHeightProbe`
- [ ] `infrastructure/http/<Id>NetworkTipProbe` (if `publicTip.urls`)
- [ ] `infrastructure/http/<Id>ClientReleaseResolver` (when latest-release exists)
- [ ] Snapshot resolver + `clients.yml` `snapshots:` **only** if toolkit downloads archives
      (`snapshot: required` + official tarball). Via-node bootstrap → no resolver.

### Wiring

- [ ] `Toolkit.production()` maps: release, tip, start (+ snapshot / artifact URL if needed)
- [ ] Agent `AgentMain` `ChainNodeRuntime` map (proc + height)

### Tests / admin

- [ ] `YamlNetworkFactsRepositoryTest` + `NetworksRoutesTest` id lists
- [ ] Start plan + height/tip parse tests
- [ ] Admin: YAML-driven; special-case helpers only when unavoidable

---

## 15. Open risks

List unknowns, vendor quirks, port catalog debt, snapshot URL instability, auth, prune vs full, etc.

| Risk | Impact | Mitigation |
|---|---|---|
| | | |

---

## Review sign-off

| Section | Approved by | Date | Notes |
|---|---|---|---|
| 1 Product scope | | | |
| 2 Environments | | | |
| 3 Artifacts | | | |
| 4 Ports | | | |
| 5 Disks | | | |
| 6 Client config | | | |
| 7 Snapshot | | | |
| 8–9 Start / proc | | | |
| 10–11 Height / tip | | | |
| 12–13 Lifecycle / options | | | |
| 15 Risks | | | |
| **Overall** | | | |
