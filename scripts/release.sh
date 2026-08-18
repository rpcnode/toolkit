#!/usr/bin/env bash
# Agent channel release helper: TOOLKIT_VERSION ↔ git tag vX.Y.Z ↔ CDN.
#
#   ./scripts/release.sh status
#   ./scripts/release.sh bump patch|minor|major|X.Y.Z
#   ./scripts/release.sh tag [--push]
#   ./scripts/release.sh publish           # build + connect commit + git push
#   ./scripts/release.sh publish --dry-run
#
# See docs/releasing.md. Does not rsync to a host. Does not force-move tags.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

log() { printf '+ %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

version_file() {
  tr -d '[:space:]' <"$ROOT/TOOLKIT_VERSION"
}

valid_version() {
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
}

bump_semver() {
  local cur="$1" part="$2"
  local major minor patch
  IFS=. read -r major minor patch <<<"$cur"
  case "$part" in
    major) echo "$((major + 1)).0.0" ;;
    minor) echo "${major}.$((minor + 1)).0" ;;
    patch) echo "${major}.${minor}.$((patch + 1))" ;;
    *) die "bump part must be patch|minor|major (got $part)" ;;
  esac
}

cmd_status() {
  local ver tag remote
  ver="$(version_file)"
  tag="v${ver}"
  printf 'TOOLKIT_VERSION  %s\n' "$ver"
  printf 'git tag          %s\n' "$tag"
  if git rev-parse -q --verify "refs/tags/${tag}" >/dev/null; then
    printf 'local tag        %s  %s\n' "$tag" "$(git rev-list -n1 "$tag")"
  else
    printf 'local tag        (missing)\n'
  fi
  remote="$(git rev-parse --abbrev-ref --symbolic-full-name @{u} 2>/dev/null || true)"
  if [[ -n "$remote" ]]; then
    printf 'upstream         %s\n' "$remote"
  if git ls-remote --exit-code --tags origin "refs/tags/${tag}" >/dev/null 2>&1; then
      printf 'origin tag       present\n'
    else
      printf 'origin tag       (missing or unreachable)\n'
    fi
  else
    printf 'upstream         (none — git push -u origin HEAD)\n'
  fi
  printf 'CDN              https://toolkit.rpcnode.dev/install/TOOLKIT_VERSION\n'
}

cmd_bump() {
  local cur next
  [[ -n "${1:-}" ]] || die "usage: ./scripts/release.sh bump patch|minor|major|X.Y.Z"
  cur="$(version_file)"
  valid_version "$cur" || die "TOOLKIT_VERSION is not X.Y.Z: ${cur}"
  case "$1" in
    patch|minor|major) next="$(bump_semver "$cur" "$1")" ;;
    *)
      next="$1"
      valid_version "$next" || die "version must be X.Y.Z (got $next)"
      ;;
  esac
  printf '%s\n' "$next" >"$ROOT/TOOLKIT_VERSION"
  log "TOOLKIT_VERSION ${cur} → ${next}"
  printf 'Next:\n  git add TOOLKIT_VERSION install && git commit -m "Release %s"\n  ./scripts/release.sh tag --push\n  ./scripts/release.sh publish\n' "$next"
}

cmd_tag() {
  local ver tag push=0
  ver="$(version_file)"
  valid_version "$ver" || die "TOOLKIT_VERSION is not X.Y.Z: ${ver}"
  tag="v${ver}"
  for arg in "$@"; do
    case "$arg" in
      --push) push=1 ;;
      *) die "unknown arg: $arg" ;;
    esac
  done
  if git rev-parse -q --verify "refs/tags/${tag}" >/dev/null; then
    die "tag ${tag} already exists locally — never move it; bump TOOLKIT_VERSION"
  fi
  git tag -a "$tag" -m "RpcNode ${ver}"
  log "created annotated tag ${tag} → $(git rev-parse --short HEAD)"
  if [[ "$push" -eq 1 ]]; then
    cmd_push "$tag"
  else
    printf 'Push with:\n  ./scripts/release.sh push\n'
  fi
}

cmd_push() {
  local tag="${1:-v$(version_file)}"
  git rev-parse -q --verify "refs/tags/${tag}" >/dev/null || die "no local tag ${tag} — run ./scripts/release.sh tag first"
  log "git push origin HEAD"
  git push -u origin HEAD
  log "git push origin ${tag}"
  git push origin "$tag"
}

cmd_publish() {
  local dry=()
  for arg in "$@"; do
    case "$arg" in
      --dry-run) dry=(--dry-run) ;;
      *) die "unknown arg: $arg" ;;
    esac
  done
  "$ROOT/scripts/build-agent-binaries.sh"
  "$ROOT/scripts/publish-install.sh" "${dry[@]}"
}

usage() {
  cat <<'EOF'
Usage:
  ./scripts/release.sh status
  ./scripts/release.sh bump patch|minor|major|X.Y.Z
  ./scripts/release.sh tag [--push]
  ./scripts/release.sh push
  ./scripts/release.sh publish [--dry-run]

See docs/releasing.md. Does not force-move tags.
EOF
}

cmd="${1:-status}"
shift || true
case "$cmd" in
  status) cmd_status ;;
  bump) cmd_bump "${1:-}" ;;
  tag) cmd_tag "$@" ;;
  push) cmd_push "${1:-}" ;;
  publish) cmd_publish "$@" ;;
  -h|--help|help) usage ;;
  *) die "unknown command: $cmd (try status|bump|tag|publish)" ;;
esac
