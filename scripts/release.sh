#!/usr/bin/env bash
# Bump toolkit version, build JARs, tag, push, create GitHub Release with jars.
#
# Usage:
#   ./scripts/release.sh              # bump patch, or finish HEAD version if its tag is missing
#   ./scripts/release.sh 0.2.0        # explicit version (retry OK if tag missing; no bump if already on HEAD)
#   ./scripts/release.sh --dry-run    # print plan only
#   ./scripts/release.sh 0.2.0 --no-push
#
# On failure before the release commit, version files are restored to HEAD.
# Re-run the same version to retry a failed attempt (no need to bump).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR="$ROOT/app"
BUILD_FILE="$APP_DIR/build.gradle.kts"
PANEL_VERSION_FILE="$ROOT/admin/PANEL_VERSION"
DIST_DIR="$ROOT/dist/release"
BUILD_REL="app/build.gradle.kts"
PANEL_REL="admin/PANEL_VERSION"

DRY_RUN=0
NO_PUSH=0
EXPLICIT_VERSION=""
VERSION_TOUCHED=0
RELEASE_COMMITTED=0
RESTORE_VER=""

usage() {
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

for arg in "$@"; do
  case "$arg" in
    -h|--help) usage 0 ;;
    --dry-run) DRY_RUN=1 ;;
    --no-push) NO_PUSH=1 ;;
    -*)
      echo "unknown flag: $arg" >&2
      usage 1
      ;;
    *)
      if [[ -n "$EXPLICIT_VERSION" ]]; then
        echo "unexpected argument: $arg" >&2
        usage 1
      fi
      EXPLICIT_VERSION="$arg"
      ;;
  esac
done

read_server_version() {
  sed -n 's/^version = "\([^"]*\)".*/\1/p' "$BUILD_FILE" | head -1
}

read_git_version() {
  git -C "$ROOT" show HEAD:"$BUILD_REL" 2>/dev/null \
    | sed -n 's/^version = "\([^"]*\)".*/\1/p' \
    | head -1
}

bump_patch() {
  local raw="$1" major minor patch
  IFS=. read -r major minor patch <<<"$raw"
  major="${major:-0}"
  minor="${minor:-0}"
  patch="${patch:-0}"
  printf '%s.%s.%s\n' "$major" "$minor" "$((patch + 1))"
}

set_server_version() {
  local ver="$1"
  sed -i -E "s/^(version = \")[^\"]+(\")/\1${ver}\2/" "$BUILD_FILE"
  printf '%s\n' "$ver" > "$PANEL_VERSION_FILE"
}

require_semver() {
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] || {
    echo "version must look like X.Y.Z, got: $1" >&2
    exit 1
  }
}

# Only version files may be dirty (left over from a failed release attempt).
assert_tree_ok_for_release() {
  local bad=0
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    local path="${line:3}"
    path="${path#\"}"
    path="${path%\"}"
    # handle "R  old -> new" / rename — take last field
    if [[ "$line" =~ -\>\ (.+)$ ]]; then
      path="${BASH_REMATCH[1]}"
      path="${path#\"}"
      path="${path%\"}"
    fi
    case "$path" in
      "$BUILD_REL"|"$PANEL_REL") ;;
      dist|dist/*) ;;
      *)
        echo "  $line" >&2
        bad=1
        ;;
    esac
  done < <(git -C "$ROOT" status --porcelain)
  if [[ "$bad" -eq 1 ]]; then
    echo "working tree has non-version changes; commit or stash them first" >&2
    exit 1
  fi
}

restore_version_if_needed() {
  if [[ "$RELEASE_COMMITTED" -eq 1 ]]; then
    return 0
  fi
  if [[ "$VERSION_TOUCHED" -ne 1 ]]; then
    return 0
  fi
  if [[ -z "$RESTORE_VER" ]]; then
    return 0
  fi
  set_server_version "$RESTORE_VER"
  echo "release failed — restored version to $RESTORE_VER" >&2
}

trap restore_version_if_needed EXIT

cd "$ROOT"

FILE_VER="$(read_server_version)"
FILE_VER="${FILE_VER:-0.0.0}"
GIT_VER="$(read_git_version)"
GIT_VER="${GIT_VER:-0.0.0}"
RESTORE_VER="$GIT_VER"

if [[ -n "$EXPLICIT_VERSION" ]]; then
  VERSION="$EXPLICIT_VERSION"
else
  # HEAD already bumped but tag never created (failed release) → finish that version.
  if [[ -n "$GIT_VER" ]] && ! git rev-parse "v${GIT_VER}" >/dev/null 2>&1; then
    VERSION="$GIT_VER"
  else
    VERSION="$(bump_patch "$GIT_VER")"
  fi
fi
require_semver "$VERSION"
TAG="v${VERSION}"

if git rev-parse "$TAG" >/dev/null 2>&1; then
  echo "tag already exists: $TAG" >&2
  echo "if only the GitHub Release upload failed, run:" >&2
  echo "  gh release upload \"$TAG\" dist/release/rpcnode-*.jar dist/release/rpcnode-${TAG}.sha256 --clobber" >&2
  exit 1
fi

NEED_VERSION_COMMIT=0
if [[ "$VERSION" != "$GIT_VER" ]]; then
  NEED_VERSION_COMMIT=1
  echo "release $GIT_VER -> $VERSION (tag $TAG)"
elif [[ "$FILE_VER" != "$VERSION" ]]; then
  NEED_VERSION_COMMIT=1
  echo "release $VERSION (tag $TAG) — sync version files then tag HEAD"
else
  echo "release $VERSION (tag $TAG) — version already on HEAD, tagging retry"
fi
if [[ "$FILE_VER" == "$VERSION" && "$FILE_VER" != "$GIT_VER" ]]; then
  echo "retry: working tree already at $VERSION (previous attempt left version files bumped)"
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
  if [[ "$NEED_VERSION_COMMIT" -eq 1 ]]; then
    echo "dry-run: would set version, build jars, commit, tag, push, gh release create"
  else
    echo "dry-run: would build jars, tag HEAD, push, gh release create (no version bump)"
  fi
  exit 0
fi

assert_tree_ok_for_release

if ! command -v gh >/dev/null 2>&1; then
  echo "gh CLI is required to create the GitHub Release" >&2
  exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
  echo "gh is not logged in. Run: gh auth login" >&2
  echo "(without auth, GitHub only shows Source code zip — not the JARs)" >&2
  exit 1
fi

if [[ "$NEED_VERSION_COMMIT" -eq 1 ]]; then
  set_server_version "$VERSION"
  VERSION_TOUCHED=1
fi

echo "building jars…"
(
  cd "$APP_DIR"
  ./gradlew --no-daemon buildFatJar agentFatJar cdnFatJar --console=plain
)

SERVER_JAR="$APP_DIR/build/libs/rpcnode-server.jar"
AGENT_JAR="$APP_DIR/build/libs/rpcnode-agent.jar"
CDN_JAR="$APP_DIR/build/libs/rpcnode-cdn.jar"
for jar in "$SERVER_JAR" "$AGENT_JAR" "$CDN_JAR"; do
  if [[ ! -f "$jar" ]]; then
    echo "missing $jar" >&2
    exit 1
  fi
done

mkdir -p "$DIST_DIR"
cp -f "$SERVER_JAR" "$AGENT_JAR" "$CDN_JAR" "$DIST_DIR/"
(
  cd "$DIST_DIR"
  sha256sum rpcnode-server.jar rpcnode-agent.jar rpcnode-cdn.jar > "rpcnode-${TAG}.sha256"
)

if [[ "$NEED_VERSION_COMMIT" -eq 1 ]]; then
  git add "$BUILD_FILE" "$PANEL_VERSION_FILE"
  # Empty commit only if version files actually differ from HEAD index after add
  if ! git diff --cached --quiet; then
    git commit -m "Release ${TAG}"
  fi
fi
git tag -a "$TAG" -m "RpcNode ${TAG}"
RELEASE_COMMITTED=1

if [[ "$NO_PUSH" -eq 1 ]]; then
  echo "skipped push (--no-push). Create the release later with:"
  echo "  git push origin HEAD \"$TAG\""
  echo "  gh release create \"$TAG\" \"$DIST_DIR\"/rpcnode-*.jar \"$DIST_DIR/rpcnode-${TAG}.sha256\" --title \"RpcNode ${TAG}\" --generate-notes"
  exit 0
fi

git push origin HEAD "$TAG"

publish_release() {
  if gh release view "$TAG" >/dev/null 2>&1; then
    gh release upload "$TAG" \
      "$DIST_DIR/rpcnode-server.jar" \
      "$DIST_DIR/rpcnode-agent.jar" \
      "$DIST_DIR/rpcnode-cdn.jar" \
      "$DIST_DIR/rpcnode-${TAG}.sha256" \
      --clobber
  else
    gh release create "$TAG" \
      "$DIST_DIR/rpcnode-server.jar" \
      "$DIST_DIR/rpcnode-agent.jar" \
      "$DIST_DIR/rpcnode-cdn.jar" \
      "$DIST_DIR/rpcnode-${TAG}.sha256" \
      --title "RpcNode ${TAG}" \
      --generate-notes
  fi
}

if ! publish_release; then
  echo "tag $TAG is on origin, but uploading JARs failed (gh auth?)." >&2
  echo "JARs are ready in $DIST_DIR — upload with:" >&2
  echo "  gh auth login" >&2
  echo "  gh release create \"$TAG\" \"$DIST_DIR\"/rpcnode-server.jar \"$DIST_DIR\"/rpcnode-agent.jar \"$DIST_DIR\"/rpcnode-cdn.jar \"$DIST_DIR/rpcnode-${TAG}.sha256\" --title \"RpcNode ${TAG}\" --generate-notes" >&2
  echo "  # or if the release already exists:" >&2
  echo "  gh release upload \"$TAG\" \"$DIST_DIR\"/rpcnode-server.jar \"$DIST_DIR\"/rpcnode-agent.jar \"$DIST_DIR\"/rpcnode-cdn.jar \"$DIST_DIR/rpcnode-${TAG}.sha256\" --clobber" >&2
  exit 1
fi

echo "released $TAG"
gh release view "$TAG" --json url -q .url
