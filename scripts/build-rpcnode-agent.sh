#!/usr/bin/env bash
# Build rpcnode-agent.jar, optionally bump chainAgentVersion, and stage it for /install/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR="$ROOT/app"
INSTALL_DIR="${PANEL_INSTALL_DIR:-$APP_DIR/public/install}"
BUILD_FILE="$APP_DIR/build.gradle.kts"

read_version() {
  sed -n 's/^val chainAgentVersion = "\([^"]*\)".*/\1/p' "$BUILD_FILE" | head -1
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
  sed -i -E "s/^(val chainAgentVersion = \")[^\"]+(\")/\1${next}\2/" "$BUILD_FILE"
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
  echo "could not read val chainAgentVersion = \"…\" from $BUILD_FILE" >&2
  exit 1
fi

echo "building rpcnode-agent $VERSION"
(
  cd "$APP_DIR"
  ./gradlew agentFatJar --console=plain
)

JAR="$APP_DIR/build/libs/rpcnode-agent.jar"
if [ ! -f "$JAR" ]; then
  echo "missing $JAR" >&2
  exit 1
fi

mkdir -p "$INSTALL_DIR/binaries"
cp -f "$JAR" "$INSTALL_DIR/binaries/rpcnode-agent.jar"
rm -f "$INSTALL_DIR/binaries/chain-agent.jar"
rm -f "$INSTALL_DIR/version"
rm -f "$INSTALL_DIR/agent.sh"
(
  cd "$INSTALL_DIR/binaries"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum rpcnode-agent.jar > sha256sums.txt
  fi
)

echo "staged $INSTALL_DIR/binaries/rpcnode-agent.jar ($VERSION)"
echo "       install: curl -fsSL -o rpcnode-agent.jar <panel>/install/binaries/rpcnode-agent.jar && sudo java -jar rpcnode-agent.jar install"
echo "       or local: sudo ./scripts/install-rpcnode-agent.sh"
