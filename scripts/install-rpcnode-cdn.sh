#!/usr/bin/env bash
# rpcnode-cdn installer — Snapshot CDN sync daemon (not the panel).
#
# Preferred (from the JAR itself):
#   sudo java -jar rpcnode-cdn.jar install
#
# Or from this repo:
#   sudo ./scripts/install-rpcnode-cdn.sh
#
# Configure targets after install:
#   sudo java -jar /opt/rpcnode/lib/rpcnode-cdn.jar menu
set -euo pipefail
export LC_ALL=C LANG=C

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEST_DIR="${RPCNODE_CDN_HOME:-${RPCNODE_AGENT_HOME:-/opt/rpcnode}}"
LIB_DIR="$DEST_DIR/lib"
CDN_NAME="rpcnode-cdn"
JAR_FILE="$LIB_DIR/${CDN_NAME}.jar"
ENV_FILE="${CDN_ENV_FILE:-/etc/rpcnode/rpcnode-cdn.env}"
TARGETS_FILE="${CDN_TARGETS_FILE:-/etc/rpcnode/rpcnode-cdn.targets.json}"
UNIT_NAME="${RPCNODE_CDN_UNIT:-rpcnode-cdn.service}"
UNIT_PATH="/etc/systemd/system/${UNIT_NAME}"
SNAPSHOT_DIR="${SNAPSHOT_CDN_DIR:-$LIB_DIR}"
POLL_SEC="${CDN_POLL_SEC:-3600}"
JAVA_MIN="${JAVA_MIN:-21}"
JAVA_HOME_DIR="${JAVA_HOME_DIR:-$DEST_DIR/jdk}"
INSTALL_ACTION="${RPCNODE_INSTALL_MODE:-}"
JAVA_BIN=""

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing required command: $1" >&2
    exit 1
  }
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

log() {
  printf '  %s\n' "$*"
}

usage() {
  cat <<EOF
rpcnode-cdn

  Preferred:  sudo java -jar rpcnode-cdn.jar install
  From repo:  sudo ./scripts/install-rpcnode-cdn.sh

  --install     install / reinstall
  --update      replace the jar, keep env + targets
  --uninstall   stop the unit and remove cdn files (keeps snapshot dir + targets)
  --help

Env: RPCNODE_INSTALL_MODE=install|update|uninstall
     RPCNODE_CDN_JAR     path to rpcnode-cdn.jar (optional)
     SNAPSHOT_CDN_DIR    jar directory (archives land in <dir>/snapshots)
     CDN_POLL_SEC        poll interval seconds (default 3600)
     CDN_TARGETS_FILE    JSON list of network/env/type (default /etc/rpcnode/…)

After install, add mirrors then restart:
  sudo java -jar ${JAR_FILE} menu
  sudo systemctl restart ${UNIT_NAME}
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --install) INSTALL_ACTION=install; shift ;;
    --update|--reinstall) INSTALL_ACTION=update; shift ;;
    --uninstall) INSTALL_ACTION=uninstall; shift ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
done

case "$(printf '%s' "$INSTALL_ACTION" | tr '[:upper:]' '[:lower:]')" in
  "" ) ;;
  install|reinstall) INSTALL_ACTION=install ;;
  update|upgrade) INSTALL_ACTION=update ;;
  uninstall|remove) INSTALL_ACTION=uninstall ;;
  *) die "unknown RPCNODE_INSTALL_MODE=$INSTALL_ACTION" ;;
esac

if [ "$(id -u)" -ne 0 ]; then
  die "run as root: sudo $SCRIPT_DIR/install-rpcnode-cdn.sh"
fi

need systemctl

find_java() {
  if [ -x "$JAVA_HOME_DIR/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME_DIR/bin/java"
    return 0
  fi
  if command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(command -v java)"
    return 0
  fi
  return 1
}

local_jar() {
  if [ -n "${RPCNODE_CDN_JAR:-}" ]; then
    printf '%s\n' "$RPCNODE_CDN_JAR"
    return 0
  fi
  if [ -f "$REPO_ROOT/app/build/libs/rpcnode-cdn.jar" ]; then
    printf '%s\n' "$REPO_ROOT/app/build/libs/rpcnode-cdn.jar"
    return 0
  fi
  return 1
}

install_jar() {
  local src
  src="$(local_jar)" || die "missing rpcnode-cdn.jar — build with: (cd \"$REPO_ROOT/app\" && ./gradlew cdnFatJar)"
  [ -f "$src" ] || die "missing $src"
  mkdir -p "$LIB_DIR"
  log "copying $src"
  cp -f "$src" "$JAR_FILE"
}

write_env_and_unit() {
  find_java || die "Java $JAVA_MIN+ required (set JAVA_HOME_DIR or install java)"
  mkdir -p "$(dirname "$ENV_FILE")" "$(dirname "$TARGETS_FILE")" "$SNAPSHOT_DIR"
  if [ ! -f "$TARGETS_FILE" ]; then
    printf '%s\n' '{"targets":[]}' > "$TARGETS_FILE"
    chmod 644 "$TARGETS_FILE"
  fi
  umask 077
  cat > "$ENV_FILE" <<EOF
SNAPSHOT_CDN_DIR=${SNAPSHOT_DIR}
CDN_POLL_SEC=${POLL_SEC}
CDN_TARGETS_FILE=${TARGETS_FILE}
EOF
  chmod 600 "$ENV_FILE"
  cat > "$UNIT_PATH" <<EOF
[Unit]
Description=RpcNode Snapshot CDN sync
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
EnvironmentFile=${ENV_FILE}
WorkingDirectory=${DEST_DIR}
ExecStart=${JAVA_BIN} --enable-native-access=ALL-UNNAMED -jar ${JAR_FILE}
Restart=always
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable "$UNIT_NAME"
  systemctl restart "$UNIT_NAME"
  log "unit ${UNIT_NAME} started"
  log "snapshots → ${SNAPSHOT_DIR}/snapshots"
  log "targets  → ${TARGETS_FILE}"
  log "configure: java -jar ${JAR_FILE} menu"
}

do_uninstall() {
  systemctl stop "$UNIT_NAME" 2>/dev/null || true
  systemctl disable "$UNIT_NAME" 2>/dev/null || true
  rm -f "$UNIT_PATH" "$JAR_FILE" "$ENV_FILE"
  systemctl daemon-reload
  log "removed unit and jar (kept ${SNAPSHOT_DIR} and ${TARGETS_FILE})"
}

already_installed() {
  [ -f "$UNIT_PATH" ] || [ -f "$JAR_FILE" ]
}

if [ -z "$INSTALL_ACTION" ]; then
  if already_installed; then
    INSTALL_ACTION=update
  else
    INSTALL_ACTION=install
  fi
fi

case "$INSTALL_ACTION" in
  install|update)
    install_jar
    write_env_and_unit
    ;;
  uninstall)
    do_uninstall
    ;;
  *)
    die "cancel"
    ;;
esac
