#!/usr/bin/env bash
# Build rpcnode-cdn.jar (Snapshot CDN sync). Copy the jar to the CDN host and run it.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR="$ROOT/app"
BUILD_FILE="$APP_DIR/build.gradle.kts"

read_version() {
  sed -n 's/^val cdnVersion = "\([^"]*\)".*/\1/p' "$BUILD_FILE" | head -1
}

bump_patch() {
  local raw major minor patch next
  raw="$(read_version)"
  raw="${raw:-0.1.0}"
  IFS=. read -r major minor patch <<<"$raw"
  major="${major:-0}"
  minor="${minor:-0}"
  patch="${patch:-0}"
  next="${major}.${minor}.$((patch + 1))"
  sed -i -E "s/^(val cdnVersion = \")[^\"]+(\")/\1${next}\2/" "$BUILD_FILE"
}

current="$(read_version)"
current="${current:-0.1.0}"

case "${1:-}" in
  1) ans=1 ;;
  0) ans=0 ;;
  "")
    if [ -t 0 ]; then
      printf 'increase version (%s)? 1 or 0: ' "$current"
      read -r ans
    else
      echo "need 1 (bump) or 0 (keep ${current})" >&2
      exit 1
    fi
    ;;
  *)
    echo "need 1 (bump) or 0 (keep ${current})" >&2
    exit 1
    ;;
esac

case "$ans" in
  1)
    bump_patch
    ;;
  0)
    ;;
  *)
    echo "need 1 or 0, got ${ans}" >&2
    exit 1
    ;;
esac

VERSION="$(read_version)"
if [ -z "$VERSION" ]; then
  echo "could not read val cdnVersion = \"…\" from $BUILD_FILE" >&2
  exit 1
fi

echo "building rpcnode-cdn $VERSION"
(
  cd "$APP_DIR"
  ./gradlew cdnFatJar --console=plain
)

JAR="$APP_DIR/build/libs/rpcnode-cdn.jar"
if [ ! -f "$JAR" ]; then
  echo "missing $JAR" >&2
  exit 1
fi

echo "$JAR ($VERSION)"
echo "on the CDN host:"
echo "  sudo java -jar rpcnode-cdn.jar install"
echo "  sudo java -jar /opt/rpcnode/lib/rpcnode-cdn.jar menu"
