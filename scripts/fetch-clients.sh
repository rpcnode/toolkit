#!/usr/bin/env bash
# Fetch official fullnode clients + configs for every shipped network/env.
# First prints local vs catalog vs remote-latest, then waits for y/N.
# Writes into install/clients/<network>/<env>/{dist,conf,VERSION,manifest.json}.
# Does NOT publish, does NOT change a running node, does NOT fetch snapshots.
#
# Nile is a separate channel (tron-nile-testnet PQ jar + config-nile.conf).
#
# Usage (from toolkit root):
#   ./scripts/fetch-clients.sh                 # TUI: ↑↓ space Enter — сейчас / новая
#   ./scripts/fetch-clients.sh --network tron
#   ./scripts/fetch-clients.sh --network tron --env nile
#   ./scripts/fetch-clients.sh --yes           # no prompt
#   ./scripts/fetch-clients.sh --force         # re-download even if versions match
#   ./scripts/fetch-clients.sh --no-remote     # skip GitHub latest probe
#   ./scripts/fetch-clients.sh --configs-only
#   ./scripts/fetch-clients.sh --dry-run       # table only
#   ./scripts/fetch-clients.sh --list
#   ./scripts/fetch-clients.sh --arch aarch64
#
# Exit 0 = ok / nothing to do / declined. 1 = required download failed.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export FETCH_CATALOG="${CATALOG:-$ROOT/install/clients/catalog.json}"
export FETCH_DEST="${DEST:-$ROOT/install/clients}"
export FETCH_NETWORK=""
export FETCH_ENV=""
export FETCH_CONFIGS_ONLY=0
export FETCH_DRY_RUN=0
export FETCH_LIST=0
export FETCH_YES=0
export FETCH_FORCE=0
export FETCH_NO_REMOTE=0
export FETCH_ARCH="x86_64"
export FETCH_CURL_MAX="${CURL_MAX:-900}"

usage() { sed -n '2,26p' "$0"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --network) FETCH_NETWORK="${2:-}"; shift 2 ;;
    --env) FETCH_ENV="${2:-}"; shift 2 ;;
    --configs-only) FETCH_CONFIGS_ONLY=1; shift ;;
    --dry-run) FETCH_DRY_RUN=1; shift ;;
    --list) FETCH_LIST=1; shift ;;
    --yes|-y) FETCH_YES=1; shift ;;
    --force) FETCH_FORCE=1; shift ;;
    --no-remote) FETCH_NO_REMOTE=1; shift ;;
    --arch) FETCH_ARCH="${2:-}"; shift 2 ;;
    --dest) FETCH_DEST="${2:-}"; shift 2 ;;
    --catalog) FETCH_CATALOG="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown arg: $1 (see --help)" ;;
  esac
done

[[ -f "$FETCH_CATALOG" ]] || die "missing catalog: $FETCH_CATALOG"
command -v python3 >/dev/null || die "python3 required"
command -v curl >/dev/null || die "curl required"

case "$FETCH_ARCH" in
  x86_64|amd64|x64) FETCH_ARCH="x86_64" ;;
  aarch64|arm64) FETCH_ARCH="aarch64" ;;
  *) die "unsupported --arch $FETCH_ARCH (x86_64|aarch64)" ;;
esac
export FETCH_ARCH FETCH_NETWORK FETCH_ENV FETCH_CONFIGS_ONLY FETCH_DRY_RUN
export FETCH_LIST FETCH_YES FETCH_FORCE FETCH_NO_REMOTE FETCH_CURL_MAX

python3 - <<'PY'
import hashlib, json, os, re, shutil, subprocess, sys, time, urllib.error, urllib.request
from pathlib import Path

catalog_path = Path(os.environ["FETCH_CATALOG"])
dest_root = Path(os.environ["FETCH_DEST"])
net_f = os.environ.get("FETCH_NETWORK", "").strip()
env_f = os.environ.get("FETCH_ENV", "").strip()
arch = os.environ["FETCH_ARCH"]
configs_only = os.environ.get("FETCH_CONFIGS_ONLY") == "1"
dry_run = os.environ.get("FETCH_DRY_RUN") == "1"
list_only = os.environ.get("FETCH_LIST") == "1"
yes = os.environ.get("FETCH_YES") == "1"
force = os.environ.get("FETCH_FORCE") == "1"
no_remote = os.environ.get("FETCH_NO_REMOTE") == "1"
curl_max = os.environ.get("FETCH_CURL_MAX", "900")
gh_token = (os.environ.get("GITHUB_TOKEN") or "").strip()

with catalog_path.open(encoding="utf-8") as f:
    catalog = json.load(f)

entries = catalog.get("entries") or []
if net_f:
    entries = [e for e in entries if e.get("network") == net_f]
if env_f:
    entries = [e for e in entries if e.get("env") == env_f]
if not entries:
    print("ERROR: нет записей в каталоге под этот фильтр", file=sys.stderr)
    sys.exit(1)

def pick_url(item):
    if arch == "aarch64" and item.get("url_aarch64"):
        return item["url_aarch64"]
    return (item.get("url") or "").strip()

def read_local_version(net, env):
    d = dest_root / net / env
    for name in ("VERSION", "manifest.json"):
        p = d / name
        if not p.is_file():
            continue
        if name == "VERSION":
            v = p.read_text(encoding="utf-8").strip()
            if v and v != "unknown":
                return v
        else:
            try:
                man = json.loads(p.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                continue
            v = (man.get("version") or "").strip()
            if v:
                return v
    return ""

def local_has_artifacts(e):
    d = dest_root / e.get("network") / e.get("env")
    arts = e.get("artifacts") or []
    downloadable = [a for a in arts if (a.get("kind") or "") != "apt" and not (a.get("url") or "").startswith("apt://")]
    if not downloadable:
        return bool((e.get("configs") or []) and (d / "conf").is_dir() and any((d / "conf").iterdir()))
    for a in downloadable:
        name = a.get("name") or ""
        if name and (d / "dist" / name).is_file():
            return True
    return False

GH_RE = re.compile(
    r"^https://github\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/"
)

def github_from_entry(e):
    repo = (e.get("github_repo") or "").strip()
    tag = (e.get("tag") or "").strip()
    prefix = (e.get("tag_prefix") or "").strip()
    for a in e.get("artifacts") or []:
        m = GH_RE.match(pick_url(a))
        if not m:
            continue
        if not repo:
            repo = f"{m.group(1)}/{m.group(2)}"
        if not tag:
            tag = m.group(3)
        break
    if not prefix and tag:
        prefix = re.split(r"(?=\d)", tag, maxsplit=1)[0]
    return repo, tag, prefix

_release_cache = {}

def github_latest(repo, prefix):
    if not repo or no_remote:
        return "", ""
    key = (repo, prefix)
    if key in _release_cache:
        return _release_cache[key]
    url = f"https://api.github.com/repos/{repo}/releases?per_page=20"
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "rpcnode-fetch-clients",
    })
    if gh_token:
        req.add_header("Authorization", f"Bearer {gh_token}")
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ValueError) as err:
        _release_cache[key] = ("", f"github: {err}")
        return _release_cache[key]
    if not isinstance(data, list):
        _release_cache[key] = ("", "github: bad payload")
        return _release_cache[key]
    for rel in data:
        if rel.get("draft"):
            continue
        t = (rel.get("tag_name") or "").strip()
        if prefix and not t.startswith(prefix):
            continue
        ver = t
        for p in ("GreatVoyage-Nile-", "GreatVoyage-", "v"):
            if ver.startswith(p):
                ver = ver[len(p):]
                break
        if ver.lower().startswith("v"):
            ver = ver[1:]
        _release_cache[key] = (ver or t, "")
        return _release_cache[key]
    _release_cache[key] = ("", "")
    return _release_cache[key]

def norm(s):
    s = (s or "").strip().lower()
    s = s[1:] if s.startswith("v") and len(s) > 1 and s[1].isdigit() else s
    return s

def clip(s, n):
    s = s or "—"
    return s if len(s) <= n else s[: n - 1] + "…"

def plan_entry(e):
    net, env = e.get("network") or "", e.get("env") or ""
    catalog_ver = (e.get("version") or "").strip()
    local = read_local_version(net, env)
    skip_reason = e.get("skip_reason") or ""
    arts = e.get("artifacts") or []
    apt_only = bool(arts) and all(
        (a.get("kind") == "apt") or (a.get("url") or "").startswith("apt://") for a in arts
    )
    repo, _tag, prefix = github_from_entry(e)
    latest, latest_err = github_latest(repo, prefix) if repo else ("", "")
    if not latest:
        latest = catalog_ver

    action = "ок"
    will_fetch = False
    if skip_reason and not (e.get("configs") or []):
        action = "пропуск"
    elif apt_only and not (e.get("configs") or []):
        action = "apt"
    elif latest_err and not no_remote:
        action = "latest?"
        will_fetch = (not local) or (norm(local) != norm(catalog_ver)) or force
    elif catalog_ver and latest and norm(latest) != norm(catalog_ver):
        action = "каталог старше"
        will_fetch = (not local) or (norm(local) != norm(catalog_ver)) or force
    elif not local or not local_has_artifacts(e):
        action = "скачать"
        will_fetch = True
    elif catalog_ver and norm(local) != norm(catalog_ver):
        action = "обновить"
        will_fetch = True
    elif force:
        action = "force"
        will_fetch = True

    if configs_only and (e.get("configs") or []) and action in ("ок", "apt", "пропуск"):
        if force or not any((dest_root / net / env / "conf").glob("*")):
            action = "конфиг"
            will_fetch = True

    return {
        "entry": e,
        "network": net,
        "env": env,
        "local": local,
        "catalog": catalog_ver,
        "latest": latest,
        "latest_err": latest_err,
        "action": action,
        "will_fetch": will_fetch,
        "skip_reason": skip_reason,
        "new": latest or catalog_ver,
        "profile": f"{net}/{env}",
    }

print("Проверяю версии (локальный кэш / каталог / GitHub latest)…")
if no_remote:
    print("(без GitHub: --no-remote)")
rows = [plan_entry(e) for e in entries]
for i, r in enumerate(rows, 1):
    r["idx"] = i

behind_n = sum(1 for r in rows if r["action"] == "каталог старше")
fetchable = [r for r in rows if r["will_fetch"]]

def print_env_table(subset, selected=None):
    selected = selected or set()
    hdr = f"{'#':>3}  {'':2} {'СЕТЬ':<12} {'ENV':<12} {'СЕЙЧАС':<20} {'КАТАЛОГ':<20} {'LATEST':<20} ДЕЙСТВИЕ"
    print()
    print(hdr)
    print("-" * len(hdr))
    for r in subset:
        star = "*" if r["idx"] in selected else " "
        mark = " ←" if r["will_fetch"] else ""
        if r["action"] == "каталог старше":
            mark = " ← latest"
        print(
            f"{r['idx']:>3}  {star}  {r['network']:<12} {r['env']:<12} "
            f"{clip(r['local'], 20):<20} {clip(r['catalog'], 20):<20} "
            f"{clip(r['latest'], 20):<20} {r['action']}{mark}"
        )
        if r["latest_err"]:
            print(f"{'':>7}  ! {r['latest_err'][:80]}")
    print()

def group_networks(subset):
    order = []
    by_net = {}
    for r in subset:
        if r["network"] not in by_net:
            by_net[r["network"]] = []
            order.append(r["network"])
        by_net[r["network"]].append(r)
    return order, by_net

def print_network_menu(subset, selected):
    order, by_net = group_networks(subset)
    print()
    print(f"{'#':>3}  СЕТЬ            ПРОФИЛИ   НОВЫЕ   КРАТКО")
    print("-" * 72)
    for i, net in enumerate(order, 1):
        rs = by_net[net]
        news = [r for r in rs if r["will_fetch"]]
        bits = []
        for r in rs:
            if r["will_fetch"] or r["action"] == "каталог старше":
                bits.append(f"{r['env']}:{r['action']}")
        brief = ", ".join(bits) if bits else "ок"
        mark = "*" if any(r["idx"] in selected for r in rs) else " "
        print(f"{i:>3}  {mark} {net:<14} {len(rs):>3}       {len(news):>3}     {clip(brief, 36)}")
    print()
    if selected:
        names = [f"{r['network']}/{r['env']}" for r in subset if r["idx"] in selected]
        print("Очередь: " + ", ".join(names))
        print()
    print("  номер      открыть сеть          2 5 / 2-4  выбрать профили")
    print("  имя        вся сеть (tron)       new        все, где есть что качать")
    print("  all        все профили           go         качать очередь")
    print("  clear      очистить очередь      q          выход")
    print()

def open_tty():
    try:
        return open("/dev/tty", "r")
    except OSError:
        return None

def read_cmd(prompt):
    if yes:
        return "go"
    tty = open_tty()
    if tty is None:
        print("ERROR: нет TTY — интерактив нужен. Для авто: --yes", file=sys.stderr)
        sys.exit(1)
    sys.stdout.write(prompt)
    sys.stdout.flush()
    line = tty.readline()
    tty.close()
    if line == "":
        return "q"
    return line.strip()

def parse_selection(text, subset, mode="rows"):
    """mode=networks: one digit opens that network. mode=rows: digits are table #."""
    raw = (text or "").strip().lower()
    if not raw:
        return "empty", set()
    if raw in ("q", "quit", "exit", "й"):
        return "quit", set()
    if raw in ("go", "g", "ок", "качать", "download"):
        return "go", set()
    if raw in ("new", "n", "новые"):
        return "set", {r["idx"] for r in subset if r["will_fetch"]}
    if raw in ("all", "a", "*"):
        return "set", {r["idx"] for r in subset}
    if raw in ("clear", "c", "сброс"):
        return "set", set()
    if raw in ("?", "h", "help"):
        return "help", set()
    order, by_net = group_networks(subset)
    if raw in by_net:
        return "set", {r["idx"] for r in by_net[raw]}
    if raw.isdigit():
        n = int(raw)
        if mode == "networks" and 1 <= n <= len(order):
            return "open", {n}
        hit = next((r for r in subset if r["idx"] == n), None)
        if hit:
            return "set", {hit["idx"]}
        return "bad", set()
    picked = set()
    unknown = []
    for part in re.split(r"[\s,]+", raw):
        if not part:
            continue
        m = re.fullmatch(r"(\d+)-(\d+)", part)
        if m:
            a, b = int(m.group(1)), int(m.group(2))
            if a > b:
                a, b = b, a
            for i in range(a, b + 1):
                if mode == "networks" and 1 <= i <= len(order):
                    for r in by_net[order[i - 1]]:
                        picked.add(r["idx"])
                else:
                    hit = next((r for r in subset if r["idx"] == i), None)
                    if hit:
                        picked.add(hit["idx"])
                    else:
                        unknown.append(part)
            continue
        if part.isdigit():
            i = int(part)
            if mode == "networks" and 1 <= i <= len(order):
                for r in by_net[order[i - 1]]:
                    picked.add(r["idx"])
            else:
                hit = next((r for r in subset if r["idx"] == i), None)
                if hit:
                    picked.add(hit["idx"])
                else:
                    unknown.append(part)
            continue
        if part in by_net:
            for r in by_net[part]:
                picked.add(r["idx"])
            continue
        unknown.append(part)
    if unknown and not picked:
        return "bad", set()
    return "set", picked

if list_only or dry_run:
    print_env_table(rows)
    if behind_n:
        print(f"Каталог старше GitHub у {behind_n} профилей (пин catalog.json, не latest).")
    print(f"К загрузке: {len(fetchable)}. " + ("dry-run." if dry_run else "list."))
    sys.exit(0)

if behind_n:
    print(f"Каталог отстаёт от GitHub у {behind_n} профилей — качается пин из catalog.json.")
    print("Latest сам не возьму: сначала поправь install/clients/catalog.json.")

selected = set()
if yes:
    selected = {r["idx"] for r in fetchable} or {r["idx"] for r in rows}
    print("Автовыбор (--yes): " + ", ".join(f"{r['network']}/{r['env']}" for r in rows if r["idx"] in selected))
else:
    view = rows
    while True:
        print_network_menu(view, selected)
        cmd = read_cmd("> ")
        kind, picked = parse_selection(cmd, view, mode="networks")
        if kind == "quit":
            print("Отменено.")
            sys.exit(0)
        if kind == "empty":
            continue
        if kind == "help":
            continue
        if kind == "bad":
            print(f"Не понял: {cmd!r}. Номер сети, имя (tron), 1 3, new, all, go, q.")
            continue
        if kind == "open":
            order, by_net = group_networks(view)
            net = order[next(iter(picked)) - 1]
            subset = by_net[net]
            print_env_table(subset, selected)
            print(f"  {net}: номер строки, 2 3, new, all, b=назад")
            cmd2 = read_cmd(f"{net}> ")
            if cmd2.strip().lower() in ("b", "back", "назад", ""):
                continue
            if cmd2.strip().lower() in ("q", "quit", "й"):
                print("Отменено.")
                sys.exit(0)
            if cmd2.strip().lower() in ("go", "g"):
                break
            k2, p2 = parse_selection(cmd2, subset, mode="rows")
            if k2 == "set":
                keep = {r["idx"] for r in subset}
                selected = (selected - keep) | p2
            elif k2 == "bad":
                print(f"Не понял: {cmd2!r}")
            continue
        if kind == "set":
            selected = picked
            if selected:
                print_env_table([r for r in rows if r["idx"] in selected], selected)
            continue
        if kind == "go":
            break

todo = [r for r in rows if r["idx"] in selected]
if not todo:
    print("Очередь пустая — нечего качать.")
    sys.exit(0)

print_env_table(todo, selected)
if not yes:
    tty = open_tty()
    if tty is None:
        print("ERROR: нет TTY — подтверди --yes", file=sys.stderr)
        sys.exit(1)
    sys.stdout.write(f"Скачать {len(todo)} профил.? [y/N] ")
    sys.stdout.flush()
    ans = tty.readline().strip().lower()
    tty.close()
    if ans not in ("y", "yes", "д", "да"):
        print("Отменено.")
        sys.exit(0)
else:
    print(f"Скачать {len(todo)} профил.? да (--yes)")

def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

url_cache = {}
ok = fail = skip = optional_miss = 0
report = []

def fetch(url: str, dest: Path, optional: bool) -> str:
    dest.parent.mkdir(parents=True, exist_ok=True)
    cached = url_cache.get(url)
    if cached and Path(cached).is_file():
        if Path(cached).resolve() != dest.resolve():
            shutil.copy2(cached, dest)
        return "ok"
    tmp = dest.with_name(dest.name + ".tmp")
    if tmp.exists():
        tmp.unlink()
    cmd = [
        "curl", "-fL", "--retry", "5", "--retry-delay", "2",
        "--connect-timeout", "30", "--max-time", str(curl_max),
        "-A", "rpcnode-fetch-clients", "-o", str(tmp), url,
    ]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        if tmp.exists():
            tmp.unlink()
        return "optional-miss" if optional else "fail"
    tmp.replace(dest)
    url_cache[url] = str(dest)
    return "ok"

print(f"+ dest={dest_root} arch={arch} configs_only={int(configs_only)}")

for r in todo:
    e = r["entry"]
    net, env = r["network"], r["env"]
    key = f"{net}/{env}"
    d = dest_root / net / env
    print(f"+ {key}  {r['local'] or '—'} → {r['catalog'] or r['latest'] or '—'}")
    files = []
    skip_reason = e.get("skip_reason") or ""
    if skip_reason and not (e.get("configs") or []):
        report.append(f"| `{key}` | skip | {e.get('version') or '-'} | {e.get('source') or ''} | {skip_reason} |")
        skip += 1
        continue

    jobs = []
    if not configs_only:
        for a in e.get("artifacts") or []:
            jobs.append(("artifact", a))
    for c in e.get("configs") or []:
        jobs.append(("config", c))

    d.mkdir(parents=True, exist_ok=True)
    (d / "dist").mkdir(exist_ok=True)
    (d / "conf").mkdir(exist_ok=True)

    for role, item in jobs:
        name = item.get("name") or ""
        kind = item.get("kind") or ""
        url = pick_url(item)
        optional = bool(item.get("optional"))
        rec = {"role": role, "kind": kind, "name": name, "url": url, "optional": optional}
        if kind == "apt" or url.startswith("apt://"):
            print(f"  apt      {name}  {url}")
            rec["status"] = "apt"
            files.append(rec)
            report.append(f"| `{key}` | apt | {name} | {url} | host package, not downloaded |")
            skip += 1
            continue
        if not url or not name:
            continue
        dest = (d / "conf" / name) if role == "config" else (d / "dist" / name)
        print(f"  {role:8} {name}")
        status = fetch(url, dest, optional)
        rec["status"] = status
        rec["path"] = str(dest.relative_to(dest_root))
        if status == "ok":
            rec["bytes"] = dest.stat().st_size
            rec["sha256"] = sha256_file(dest)
            report.append(f"| `{key}` | ok | {name} | {rec['bytes']} B | `{rec['sha256'][:16]}…` |")
            ok += 1
        elif status == "optional-miss":
            report.append(f"| `{key}` | optional-miss | {name} | {url} | |")
            optional_miss += 1
        else:
            report.append(f"| `{key}` | FAIL | {name} | {url} | |")
            fail += 1
        files.append(rec)

    artifact_url = ""
    artifact_kind = ""
    sha = ""
    for it in files:
        if it.get("role") == "artifact" and it.get("status") in ("ok", "apt"):
            artifact_url = it.get("url") or artifact_url
            artifact_kind = it.get("kind") or artifact_kind
            sha = it.get("sha256") or sha
            if it.get("kind") != "apt" and it.get("status") == "ok":
                break
    man = {
        "network": net,
        "env": env,
        "version": e.get("version") or "",
        "tag": e.get("tag") or "",
        "source": e.get("source") or "",
        "artifact_url": artifact_url,
        "sha256": sha,
        "needs_conf_patch": bool(e.get("needs_conf_patch")),
        "artifact_kind": artifact_kind,
        "notes": e.get("notes") or skip_reason,
        "files": files,
        "fetched_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    (d / "manifest.json").write_text(json.dumps(man, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (d / "VERSION").write_text((e.get("version") or "unknown") + "\n", encoding="utf-8")

report_path = dest_root / "FETCH_REPORT.md"
body = [
    "# Client fetch report",
    "",
    f"Generated by `scripts/fetch-clients.sh`. Dest: `{dest_root}`. Arch: `{arch}`.",
    "",
    "Nile uses `tron-nile-testnet` PQ jar (`4.8.2.1.PQ1_build1`) + `config-nile.conf`, not GreatVoyage / tron-docker.",
    "",
    "| profile | status | name / version | extra | note |",
    "|---|---|---|---|---|",
    *report,
    "",
    f"Counts: ok={ok} fail={fail} skip/apt={skip} optional-miss={optional_miss}",
    "",
]
report_path.write_text("\n".join(body), encoding="utf-8")
print(f"+ report {report_path}")
print(f"+ ok={ok} fail={fail} skip/apt={skip} optional-miss={optional_miss} dest={dest_root}")
sys.exit(0 if fail == 0 else 1)
PY
