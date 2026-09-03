#!/usr/bin/env bash
# Bump toolkit version, build JARs, tag, push, create GitHub Release with jars.
#
# Usage:
#   ./scripts/release.sh              # bump patch (0.1.1 -> 0.1.2)
#   ./scripts/release.sh 0.2.0        # set explicit version
#   ./scripts/release.sh --dry-run    # print plan only
#   ./scripts/release.sh 0.2.0 --no-push
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR="$ROOT/app"
BUILD_FILE="$APP_DIR/build.gradle.kts"
PANEL_VERSION_FILE="$ROOT/admin/PANEL_VERSION"
DIST_DIR="$ROOT/dist/release"

DRY_RUN=0
NO_PUSH=0
EXPLICIT_VERSION=""

usage() {
  sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
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

CURRENT="$(read_server_version)"
CURRENT="${CURRENT:-0.0.0}"
if [[ -n "$EXPLICIT_VERSION" ]]; then
  VERSION="$EXPLICIT_VERSION"
else
  VERSION="$(bump_patch "$CURRENT")"
fi
require_semver "$VERSION"
TAG="v${VERSION}"

if [[ "$VERSION" == "$CURRENT" ]]; then
  echo "version is already $CURRENT; pass a newer version" >&2
  exit 1
fi

cd "$ROOT"

echo "release $CURRENT -> $VERSION (tag $TAG)"

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "dry-run: would bump version, build jars, commit, tag, push, gh release create"
  exit 0
fi

if [[ -n "$(git status --porcelain)" ]]; then
  echo "working tree is dirty; commit or stash first" >&2
  git status --short >&2
  exit 1
fi

if git rev-parse "$TAG" >/dev/null 2>&1; then
  echo "tag already exists: $TAG" >&2
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "gh CLI is required to create the GitHub Release" >&2
  exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
  echo "gh is not logged in. Run: gh auth login" >&2
  echo "(without auth, GitHub only shows Source code zip — not the JARs)" >&2
  exit 1
fi

set_server_version "$VERSION"

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

git add "$BUILD_FILE" "$PANEL_VERSION_FILE"
git commit -m "Release ${TAG}"
git tag -a "$TAG" -m "RpcNode ${TAG}"

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
