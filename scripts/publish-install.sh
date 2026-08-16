#!/usr/bin/env bash
# Stage agent installer + binaries into frontend/connect and push that repo.
# Does NOT rsync to a host. CDN is whatever connect deploys from git.
#
#   https://rpcnode.dev/install/agent.sh
#   https://rpcnode.dev/install/TOOLKIT_VERSION
#   https://rpcnode.dev/install/binaries/...
#
# Usage:
#   ./scripts/build-agent-binaries.sh
#   ./scripts/publish-install.sh              # stage → commit connect → git push
#   ./scripts/publish-install.sh --local-only # stage only (no git)
#   ./scripts/publish-install.sh --no-push    # commit, do not push
#   ./scripts/publish-install.sh --dry-run
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MONOREPO_ROOT="$(cd "$ROOT/../../../.." && pwd 2>/dev/null || true)"
BIN_DIR="${BIN_DIR:-$ROOT/dist/binaries}"
ARCHIVE_DIR="${ARCHIVE_DIR:-$ROOT/dist/archives}"
DEFAULT_LOCAL_INSTALL="${MONOREPO_ROOT}/frontend/connect/public/install"
LOCAL_INSTALL="${CONNECT_PUBLIC_INSTALL:-$DEFAULT_LOCAL_INSTALL}"
DRY_RUN=0
GIT_MODE=push # push | commit | none

log() { printf '+ %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --local-only) GIT_MODE=none ;;
    --no-push) GIT_MODE=commit ;;
    --remote)
      die "rsync-to-server is removed — publish commits frontend/connect and git push"
      ;;
    -h|--help)
      sed -n '2,20p' "$0"
      exit 0
      ;;
    *) die "unknown arg: $arg (try --help)" ;;
  esac
done

[[ -f "$ROOT/install/agent.sh" ]] || die "missing install/agent.sh"
[[ -d "$BIN_DIR" ]] || die "missing $BIN_DIR — run ./scripts/build-agent-binaries.sh first"
ls "$BIN_DIR"/rpcnode-*-* >/dev/null 2>&1 || die "no binaries in $BIN_DIR"

VERSION="$(tr -d '[:space:]' <"$ROOT/TOOLKIT_VERSION" 2>/dev/null || echo unknown)"
[[ -n "$VERSION" && "$VERSION" != "unknown" ]] || die "TOOLKIT_VERSION missing/empty"

pack_archive() {
  local stage name tgz
  mkdir -p "$ARCHIVE_DIR"
  name="rpcnode-agent-${VERSION}"
  stage="$(mktemp -d)"
  mkdir -p "${stage}/${name}/binaries"
  cp -f "$ROOT/install/agent.sh" "${stage}/${name}/agent.sh"
  chmod 755 "${stage}/${name}/agent.sh"
  if [[ -f "$ROOT/install/uninstall-agents.sh" ]]; then
    cp -f "$ROOT/install/uninstall-agents.sh" "${stage}/${name}/uninstall-agents.sh"
    chmod 755 "${stage}/${name}/uninstall-agents.sh"
  fi
  if [[ -f "$ROOT/install/rpcnode-agent-watchdog.sh" ]]; then
    cp -f "$ROOT/install/rpcnode-agent-watchdog.sh" "${stage}/${name}/rpcnode-agent-watchdog.sh"
    chmod 755 "${stage}/${name}/rpcnode-agent-watchdog.sh"
  fi
  printf '%s\n' "$VERSION" >"${stage}/${name}/TOOLKIT_VERSION"
  cp -f "$BIN_DIR"/rpcnode-api-agent-* "$BIN_DIR"/rpcnode-system-agent-* "${stage}/${name}/binaries/" 2>/dev/null || true
  [[ -f "$BIN_DIR/sha256sums.txt" ]] && cp -f "$BIN_DIR/sha256sums.txt" "${stage}/${name}/binaries/"
  if find "${stage}/${name}" -name '*.go' -print -quit | grep -q .; then
    rm -rf "$stage"
    die "refusing to publish archive that contains .go sources"
  fi
  tgz="${ARCHIVE_DIR}/${name}.tar.gz"
  tar -C "$stage" -czf "$tgz" "$name"
  rm -rf "$stage"
  cp -f "$tgz" "${ARCHIVE_DIR}/rpcnode-agent-latest.tar.gz"
  log "packed archive ${tgz}"
}

stage_local() {
  local dest="$1"
  [[ -n "$dest" ]] || die "local install dest is empty"
  mkdir -p "${dest}/binaries" "${dest}/archives"
  cp -f "$ROOT/install/agent.sh" "${dest}/agent.sh"
  chmod 755 "${dest}/agent.sh"
  if [[ -f "$ROOT/install/uninstall-agents.sh" ]]; then
    cp -f "$ROOT/install/uninstall-agents.sh" "${dest}/uninstall-agents.sh"
    chmod 755 "${dest}/uninstall-agents.sh"
  fi
  if [[ -f "$ROOT/install/rpcnode-agent-watchdog.sh" ]]; then
    cp -f "$ROOT/install/rpcnode-agent-watchdog.sh" "${dest}/rpcnode-agent-watchdog.sh"
    chmod 755 "${dest}/rpcnode-agent-watchdog.sh"
  fi
  printf '%s\n' "$VERSION" >"${dest}/TOOLKIT_VERSION"
  if [[ -f "$ROOT/install/donate.json" ]]; then
    cp -f "$ROOT/install/donate.json" "${dest}/donate.json"
  fi
  cp -f "$BIN_DIR"/rpcnode-api-agent-* "$BIN_DIR"/rpcnode-system-agent-* "${dest}/binaries/" 2>/dev/null || true
  [[ -f "$BIN_DIR/sha256sums.txt" ]] && cp -f "$BIN_DIR/sha256sums.txt" "${dest}/binaries/"
  chmod 755 "${dest}/binaries"/rpcnode-* 2>/dev/null || true
  cp -f "${ARCHIVE_DIR}/rpcnode-agent-${VERSION}.tar.gz" \
    "${ARCHIVE_DIR}/rpcnode-agent-latest.tar.gz" \
    "${dest}/archives/"
  if [[ -d "$ROOT/install/clients" ]]; then
    mkdir -p "${dest}/clients"
    # Catalog + manifests + conf from toolkit. Do not overwrite connect dist/
    # (RpcNode.app already fetched jars/tarballs there — that is the CDN payload).
    tar -C "$ROOT/install/clients" \
      --exclude dist --exclude FETCH_REPORT.md --exclude FETCH_LOG.txt \
      -cf - . | tar -C "${dest}/clients" -xf -
  fi
  if find "${dest}" -name '*.go' -print -quit | grep -q .; then
    die "refusing to stage .go sources into ${dest}"
  fi
  log "staged ${dest} (version ${VERSION})"
}

# Commit the install CDN directory (connect public/install, or dest itself), then push.
commit_connect() {
  local dest="$1"
  local repo rel
  dest="$(cd "$dest" && pwd)"
  repo="$(git -C "$dest" rev-parse --show-toplevel 2>/dev/null || true)"
  [[ -n "$repo" ]] || die "not a git repo: $dest (expected frontend/connect public/install)"
  repo="$(cd "$repo" && pwd)"
  rel="${dest#"$repo"/}"
  [[ "$rel" != "$dest" && -n "$rel" ]] || die "dest $dest is not inside git repo $repo"
  [[ -d "$dest" ]] || die "missing install CDN dir: $dest"

  log "git -C ${repo} add ${rel}"
  git -C "$repo" add -- "$rel"

  if git -C "$repo" diff --cached --quiet; then
    log "install CDN: nothing to commit (already ${VERSION})"
    return 0
  fi

  git -C "$repo" commit -m "$(cat <<EOF
Release agent ${VERSION}

Publish rpcnode.dev/install from toolkit TOOLKIT_VERSION.
EOF
)"
  log "install CDN commit $(git -C "$repo" rev-parse --short HEAD) agent ${VERSION}"

  if [[ "$GIT_MODE" != "push" ]]; then
    log "skip git push (--no-push)"
    return 0
  fi

  if git -C "$repo" rev-parse --abbrev-ref --symbolic-full-name '@{u}' >/dev/null 2>&1; then
    log "git -C ${repo} push"
    git -C "$repo" push
  else
    log "git -C ${repo} push -u origin HEAD"
    git -C "$repo" push -u origin HEAD
  fi
}

if [[ "$DRY_RUN" -eq 1 ]]; then
  log "[dry-run] would pack archive + stage → ${LOCAL_INSTALL}/"
  log "[dry-run] would git commit frontend/connect public/install (${VERSION})"
  if [[ "$GIT_MODE" == "push" ]]; then
    log "[dry-run] would git push origin"
  fi
  exit 0
fi

[[ -n "$LOCAL_INSTALL" ]] || die "CONNECT_PUBLIC_INSTALL / local dest unresolved"
parent="$(dirname "$LOCAL_INSTALL")"
if [[ ! -d "$parent" ]]; then
  die "connect public dir missing: $parent (set CONNECT_PUBLIC_INSTALL)"
fi

pack_archive
stage_local "$LOCAL_INSTALL"

if [[ "$GIT_MODE" == "none" ]]; then
  log "skip install CDN git (--local-only)"
else
  dest_repo="$(git -C "$LOCAL_INSTALL" rev-parse --show-toplevel 2>/dev/null || true)"
  if [[ -n "$dest_repo" && "$(cd "$dest_repo" && pwd)" == "$ROOT" ]]; then
    log "dest is toolkit install/ — already in toolkit commit, skip second git"
  else
    commit_connect "$LOCAL_INSTALL"
  fi
fi

log "published ${VERSION}"
log "  https://rpcnode.dev/install/agent.sh"
log "  https://rpcnode.dev/install/TOOLKIT_VERSION"
log "  https://rpcnode.dev/install/binaries/"
log "  https://rpcnode.dev/install/archives/rpcnode-agent-${VERSION}.tar.gz"
