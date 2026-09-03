#!/usr/bin/env bash
# Thin wrapper around rpcnode-agent.jar self-install (same as CDN).
#
# Prefer downloading the jar from the panel, then:
#   sudo java -jar rpcnode-agent.jar install
#
# From a repo checkout (local jar):
#   sudo ./scripts/install-rpcnode-agent.sh
#   sudo ./scripts/install-rpcnode-agent.sh update
#   sudo ./scripts/install-rpcnode-agent.sh uninstall
set -euo pipefail
export LC_ALL=C LANG=C

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

usage() {
  cat <<EOF
rpcnode-agent (wrapper → java -jar … install)

  sudo ./scripts/install-rpcnode-agent.sh [install|update|uninstall]

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
  die "run as root: sudo $SCRIPT_DIR/install-rpcnode-agent.sh $CMD"
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

JAR="$(find_jar)" || die "missing rpcnode-agent.jar — build with: (cd \"$REPO_ROOT/app\" && ./gradlew agentFatJar)"
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
