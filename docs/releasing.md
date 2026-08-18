# Releases — git tags and the agent CDN

`TOOLKIT_VERSION` (repo root, one line, no `v`) is the **agent install channel**. Panel **Servers** compares each host’s `agent_version` to `https://toolkit.rpcnode.dev/install/TOOLKIT_VERSION`. A git tag is the matching git object for that ship.

| What | Where |
|---|---|
| Channel file | `TOOLKIT_VERSION` → `0.4.115` |
| Panel footer | `PANEL_VERSION` → `0.1.0` (bump on every `status-ui` / `panel/` change) |
| Git tag | `v0.4.115` (always the `v` prefix) |
| Embedded in binaries | `-ldflags -X main.toolkitVersion=…` at compile time |
| CDN | `https://toolkit.rpcnode.dev/install/TOOLKIT_VERSION` + binaries / archives |
| Public notes | [`docs/versions.json`](versions.json) → [`/versions/`](https://toolkit.rpcnode.dev/versions/) + `/versions/<X.Y.Z>/` (sitemap) + `/install/VERSIONS.json` |

Do **not** move a tag after it is on CDN. Ship the next patch instead.

## When to bump

| Change | Bump `TOOLKIT_VERSION`? | Git tag? |
|---|---|---|
| `api-agent` / `system-agent` / `install/agent.sh` / watchdog | **Yes** (patch, or minor if the contract changes) | **Yes** `vX.Y.Z` |
| Panel UI / `panel/` Go only | No `TOOLKIT_VERSION` — bump **`PANEL_VERSION`** (footer) | Optional (notes only) |
| Docs / README | No | No |

Patch `0.4.113` → `0.4.114` for normal agent ships. Minor `0.5.0` if you break agent API or install layout. Major `1.0.0` when you call the product stable.

## Ship an agent version

From a clean-enough tree on `master` (or the branch you push):

```bash
# 1) Bump the channel file (writes TOOLKIT_VERSION only)
./scripts/release.sh bump patch        # or: minor | major | 0.4.114

# 2) Add a release note at the top of docs/versions.json (same version)
#    publish-install refuses if latest != TOOLKIT_VERSION

# 3) Commit the bump with the rest of the change
git add TOOLKIT_VERSION docs/versions.json
git commit -m "Release 0.4.114"

# 4) Annotated tag on that commit, then push commit + tag
./scripts/release.sh tag --push

# 5) Build agents, commit frontend/toolkit public/install (+ versions.json), git push
./scripts/release.sh publish
```

After CDN is live, panel **Servers** shows **Update** for hosts still on the old binary. Existing servers: **Update all agents** (or per-server Update). New hosts: `curl -fsSL https://toolkit.rpcnode.dev/install/agent.sh | sudo bash` already gets the new channel.

Helper: `./scripts/release.sh status` prints file vs tags vs `origin`.

## Tag rules

- Annotated only: `git tag -a v0.4.114 -m "RpcNode 0.4.114"`.
- Tag name **must** equal `v` + `TOOLKIT_VERSION` on that commit.
- Never `git tag -f` / never force-push a published tag.
- Push the branch **and** the tag: `git push origin HEAD` then `git push origin v0.4.114` (or `./scripts/release.sh tag --push`).
- `git push --tags` is fine for missing tags; it still must not overwrite remotes.

Checkout a past ship:

```bash
git checkout v0.4.113
```

Hosts install from CDN only (`docs/agent-install.md`) — not from a source checkout.

## Do not

- Publish CDN without a matching git tag (you cannot rebuild that exact ship later).
- Tag without bumping `TOOLKIT_VERSION` (panel/CDN would disagree with git).
- Reuse `0.4.113` after it was already on CDN — bump to `0.4.114`.
- Publish without a new first entry in `docs/versions.json` (site `/versions/` and CDN feed would lie).
- Commit `.cursor/secrets`, `update-remote-*.sh`, host IPs, or `panel.db`.
