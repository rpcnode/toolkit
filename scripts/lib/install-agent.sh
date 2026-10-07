#!/usr/bin/env bash
# Thin wrapper around rpcnode-agent.jar self-install (same as CDN).
#
# Prefer downloading the jar from the panel, then:
#   sudo java -jar rpcnode-agent.jar install
#
# From a repo checkout (local jar):
#   sudo ./scripts/rpcnode.sh install agent
#   sudo ./scripts/rpcnode.sh update agent
#   sudo ./scripts/rpcnode.sh remove agent
set -euo pipefail
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) echo "$(basename "$0") is Linux/systemd only: run it on the target host (or in WSL with systemd)." >&2; exit 1 ;;
esac
export LC_ALL=C LANG=C

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

usage() {
  cat <<EOF
rpcnode-agent (wrapper → java -jar … install)

  sudo ./scripts/rpcnode.sh install|update|remove agent

  Or download from the panel and install without this repo:

    curl -fsSL -o rpcnode-agent.jar "\$ORIGIN/install/binaries/rpcnode-agent.jar"
    sudo java -jar rpcnode-agent.jar install

Env: RPCNODE_AGENT_JAR  path to rpcnode-agent.jar (optional)
EOF
}

CMD=install
case "${1:-}" in
  ""|install|--install) CMD=install ;;
  update|upgrade|reinstall|--update|--reinstall) CMD=update ;;
  uninstall|remove|--uninstall) CMD=uninstall ;;
  -h|--help|help) usage; exit 0 ;;
  *) die "unknown argument: $1 (see --help)" ;;
esac

if [ "$(id -u)" -ne 0 ]; then
  die "run as root: sudo ./scripts/rpcnode.sh install agent"
fi

find_jar() {
  if [ -n "${RPCNODE_AGENT_JAR:-}" ]; then
    printf '%s\n' "$RPCNODE_AGENT_JAR"
    return 0
  fi
  if [ -f "$REPO_ROOT/app/build/libs/rpcnode-agent.jar" ]; then
    printf '%s\n' "$REPO_ROOT/app/build/libs/rpcnode-agent.jar"
    return 0
  fi
  if [ -f "$REPO_ROOT/app/public/install/binaries/rpcnode-agent.jar" ]; then
    printf '%s\n' "$REPO_ROOT/app/public/install/binaries/rpcnode-agent.jar"
    return 0
  fi
  if [ -f /opt/rpcnode/lib/rpcnode-agent.jar ]; then
    printf '%s\n' /opt/rpcnode/lib/rpcnode-agent.jar
    return 0
  fi
  return 1
}

# No local jar (fresh checkout on the host): take the published release of this checkout's version,
# else the latest.
download_release_jar() {
  local ver url out
  command -v curl >/dev/null 2>&1 || return 1
  ver="${RPCNODE_VERSION:-}"
  if [ -z "$ver" ] && [ -f "$REPO_ROOT/app/build.gradle.kts" ]; then
    ver="$(sed -n 's/^version = "\([^"]*\)".*/\1/p' "$REPO_ROOT/app/build.gradle.kts" | head -1)"
  fi
  ver="${ver#v}"
  out="$(mktemp -d)/rpcnode-agent.jar"
  for url in \
    "${ver:+https://github.com/rpcnode/toolkit/releases/download/v${ver}/rpcnode-agent.jar}" \
    "https://github.com/rpcnode/toolkit/releases/latest/download/rpcnode-agent.jar"
  do
    [ -n "$url" ] || continue
    echo "  downloading $url" >&2
    if curl -fSL --retry 3 --connect-timeout 20 -o "$out" "$url" 2>/dev/null && [ -s "$out" ]; then
      printf '%s\n' "$out"
      return 0
    fi
  done
  return 1
}

JAR="$(find_jar)" || JAR="$(download_release_jar)" \
  || die "missing rpcnode-agent.jar — build with: ./scripts/rpcnode.sh build agent  (or set RPCNODE_AGENT_JAR)"
[ -f "$JAR" ] || die "missing $JAR"

JAVA_BIN="${JAVA_BIN:-}"
if [ -z "$JAVA_BIN" ]; then
  if [ -x /opt/rpcnode/jdk/bin/java ]; then
    JAVA_BIN=/opt/rpcnode/jdk/bin/java
  elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
  else
    JAVA_BIN="$(command -v java 2>/dev/null || true)"
  fi
fi
[ -n "$JAVA_BIN" ] || die "java not found — install Java 25+ then retry"

echo "  using $JAR → $CMD"
exec "$JAVA_BIN" --enable-native-access=ALL-UNNAMED -jar "$JAR" "$CMD"
