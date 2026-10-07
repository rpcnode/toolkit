#!/usr/bin/env bash
# Bump toolkit version, build JARs, tag, push, create a published GitHub Release.
#
# Usage:
#   ./scripts/release.sh -m "Fix agent install"
#   ./scripts/release.sh 0.2.0 -m "Fix agent install"
#   ./scripts/release.sh -m "notes" --dry-run
#   ./scripts/release.sh 0.2.0 -m "notes" --no-push
#
# Comment (-m) is required: used for the Release commit, annotated tag, and GitHub notes.
# On failure before the release commit, version files are restored to HEAD.
# If the tag already exists but the GitHub Release is missing/incomplete, re-run with
# the same version and -m to rebuild jars and finish publishing (no second tag).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/java-env.sh
. "$(dirname "$0")/lib/java-env.sh"
APP_DIR="$ROOT/app"
BUILD_FILE="$APP_DIR/build.gradle.kts"
PANEL_VERSION_FILE="$ROOT/admin/PANEL_VERSION"
DIST_DIR="$ROOT/dist/release"
BUILD_REL="app/build.gradle.kts"
PANEL_REL="admin/PANEL_VERSION"

DRY_RUN=0
NO_PUSH=0
EXPLICIT_VERSION=""
COMMENT=""
VERSION_TOUCHED=0
RELEASE_COMMITTED=0
RESTORE_VER=""
UPLOAD_ONLY=0

usage() {
  sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --no-push) NO_PUSH=1; shift ;;
    -m|--message|--notes|--comment)
      if [[ $# -lt 2 || -z "${2:-}" ]]; then
        echo "missing value for $1" >&2
        usage 1
      fi
      COMMENT="$2"
      shift 2
      ;;
    -*)
      echo "unknown flag: $1" >&2
      usage 1
      ;;
    *)
      if [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
        if [[ -n "$EXPLICIT_VERSION" ]]; then
          echo "unexpected argument: $1" >&2
          usage 1
        fi
        EXPLICIT_VERSION="$1"
      elif [[ -z "$COMMENT" ]]; then
        COMMENT="$1"
      else
        echo "unexpected argument: $1" >&2
        usage 1
      fi
      shift
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

require_comment() {
  if [[ -n "$COMMENT" ]]; then
    return 0
  fi
  if [[ ! -t 0 ]]; then
    echo "release comment required: ./scripts/release.sh -m \"your notes\"" >&2
    exit 1
  fi
  printf 'Release comment: '
  IFS= read -r COMMENT
  if [[ -z "$COMMENT" ]]; then
    echo "empty comment — abort" >&2
    exit 1
  fi
}

# Only version files may be dirty (left over from a failed release attempt).
assert_tree_ok_for_release() {
  local bad=0
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    local path="${line:3}"
    path="${path#\"}"
    path="${path%\"}"
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

release_notes_body() {
  printf '%s\n\n**Full changelog:** compare previous tag → %s\n' "$COMMENT" "$TAG"
}

# Create/publish GitHub Release, then upload assets one-by-one (avoids multi-file hang/404).
publish_release() {
  local notes_file
  notes_file="$(mktemp)"
  release_notes_body > "$notes_file"

  if ! gh release view "$TAG" >/dev/null 2>&1; then
    echo "creating GitHub Release $TAG…"
    gh release create "$TAG" \
      --title "RpcNode ${TAG}" \
      --notes-file "$notes_file" \
      --latest
  else
    echo "updating GitHub Release $TAG notes…"
    gh release edit "$TAG" \
      --title "RpcNode ${TAG}" \
      --notes-file "$notes_file" \
      --draft=false \
      --latest
  fi
  rm -f "$notes_file"

  local asset
  for asset in \
    "$DIST_DIR/rpcnode-server.jar" \
    "$DIST_DIR/rpcnode-agent.jar" \
    "$DIST_DIR/rpcnode-cdn.jar" \
    "$DIST_DIR/rpcnode-${TAG}.sha256"
  do
    echo "uploading $(basename "$asset")…"
    local attempt=1
    while true; do
      if gh release upload "$TAG" "$asset" --clobber; then
        break
      fi
      if [[ "$attempt" -ge 3 ]]; then
        echo "failed to upload $asset after $attempt attempts" >&2
        return 1
      fi
      echo "upload failed, retry $((attempt + 1))/3…" >&2
      sleep $((attempt * 2))
      attempt=$((attempt + 1))
    done
  done

  gh release edit "$TAG" --draft=false --latest >/dev/null
}

trap restore_version_if_needed EXIT

cd "$ROOT"
require_comment

FILE_VER="$(read_server_version)"
FILE_VER="${FILE_VER:-0.0.0}"
GIT_VER="$(read_git_version)"
GIT_VER="${GIT_VER:-0.0.0}"
RESTORE_VER="$GIT_VER"

if [[ -n "$EXPLICIT_VERSION" ]]; then
  VERSION="$EXPLICIT_VERSION"
else
  if [[ -n "$GIT_VER" ]] && ! git rev-parse "v${GIT_VER}" >/dev/null 2>&1; then
    VERSION="$GIT_VER"
  else
    VERSION="$(bump_patch "$GIT_VER")"
  fi
fi
require_semver "$VERSION"
TAG="v${VERSION}"

if git rev-parse "$TAG" >/dev/null 2>&1; then
  # Tag exists — allow finishing a broken GitHub Release (upload-only path).
  UPLOAD_ONLY=1
  echo "tag $TAG already exists — will rebuild jars and publish/repair GitHub Release"
fi

NEED_VERSION_COMMIT=0
if [[ "$UPLOAD_ONLY" -eq 1 ]]; then
  echo "publish $TAG — $COMMENT"
elif [[ "$VERSION" != "$GIT_VER" ]]; then
  NEED_VERSION_COMMIT=1
  echo "release $GIT_VER -> $VERSION (tag $TAG) — $COMMENT"
elif [[ "$FILE_VER" != "$VERSION" ]]; then
  NEED_VERSION_COMMIT=1
  echo "release $VERSION (tag $TAG) — sync version files — $COMMENT"
else
  echo "release $VERSION (tag $TAG) — version already on HEAD — $COMMENT"
fi
if [[ "$FILE_VER" == "$VERSION" && "$FILE_VER" != "$GIT_VER" && "$UPLOAD_ONLY" -eq 0 ]]; then
  echo "retry: working tree already at $VERSION (previous attempt left version files bumped)"
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "dry-run: comment=$(printf %q "$COMMENT")"
  if [[ "$UPLOAD_ONLY" -eq 1 ]]; then
    echo "dry-run: would build jars, push (if needed), publish GitHub Release for existing $TAG"
  elif [[ "$NEED_VERSION_COMMIT" -eq 1 ]]; then
    echo "dry-run: would set version, build jars, commit, tag, push, publish GitHub Release"
  else
    echo "dry-run: would build jars, tag HEAD, push, publish GitHub Release"
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

if [[ "$UPLOAD_ONLY" -eq 0 && "$NEED_VERSION_COMMIT" -eq 1 ]]; then
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

if [[ "$UPLOAD_ONLY" -eq 0 ]]; then
  if [[ "$NEED_VERSION_COMMIT" -eq 1 ]]; then
    git add "$BUILD_FILE" "$PANEL_VERSION_FILE"
    if ! git diff --cached --quiet; then
      git commit -m "$(printf 'Release %s\n\n%s\n' "$TAG" "$COMMENT")"
    fi
  fi
  git tag -a "$TAG" -m "$(printf 'RpcNode %s\n\n%s\n' "$TAG" "$COMMENT")"
  RELEASE_COMMITTED=1
else
  # Tag already committed earlier — do not restore version on later failure.
  RELEASE_COMMITTED=1
fi

if [[ "$NO_PUSH" -eq 1 ]]; then
  echo "skipped push (--no-push). Publish later with:"
  echo "  git push origin HEAD \"$TAG\""
  echo "  # then re-run: ./scripts/release.sh $VERSION -m $(printf %q "$COMMENT")"
  exit 0
fi

if [[ "$UPLOAD_ONLY" -eq 0 ]]; then
  git push origin HEAD "$TAG"
else
  # Ensure tag is on origin (no-op if already there).
  git push origin "$TAG" 2>/dev/null || git push origin "refs/tags/$TAG"
fi

if ! publish_release; then
  echo "tag $TAG is on origin, but publishing JARs failed." >&2
  echo "JARs are ready in $DIST_DIR — retry with:" >&2
  echo "  ./scripts/release.sh $VERSION -m $(printf %q "$COMMENT")" >&2
  exit 1
fi

echo "released $TAG"
gh release view "$TAG" --json url,isDraft,isLatest -q '"\(.url)  draft=\(.isDraft)  latest=\(.isLatest)"'
