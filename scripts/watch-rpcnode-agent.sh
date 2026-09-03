#!/usr/bin/env bash
# Watch agent-related sources; on change rebuild rpcnode-agent.jar and restart the unit.
#
#   ./scripts/watch-rpcnode-agent.sh
#   # or: sudo ./scripts/watch-rpcnode-agent.sh
#
# Build runs as the invoking user (not root) so Gradle sees your JDK 26 toolchain.
# Copy + systemctl restart escalate via sudo when needed.
set -euo pipefail
export LC_ALL=C LANG=C

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_DIR="$REPO_ROOT/app"
UNIT_NAME="${RPCNODE_AGENT_UNIT:-rpcnode-agent.service}"
JAR_SRC="$APP_DIR/build/libs/rpcnode-agent.jar"
JAR_DEST="${RPCNODE_AGENT_JAR:-/opt/rpcnode/lib/rpcnode-agent.jar}"
DEBOUNCE_SEC="${WATCH_DEBOUNCE_SEC:-2}"
# Project kotlin.jvmToolchain(26)
JAVA_MAJOR="${RPCNODE_BUILD_JAVA_MAJOR:-26}"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

log() {
  printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"
}

# Who owns the repo / should run Gradle (never root if we can avoid it).
if [ "$(id -u)" -eq 0 ] && [ -n "${SUDO_USER:-}" ] && [ "$SUDO_USER" != "root" ]; then
  BUILD_USER="$SUDO_USER"
else
  BUILD_USER="$(id -un)"
fi
BUILD_HOME="$(getent passwd "$BUILD_USER" | cut -d: -f6)"
[ -n "$BUILD_HOME" ] || BUILD_HOME="${HOME:-/root}"

java_major() {
  local bin="$1"
  [ -x "$bin" ] || return 1
  # openjdk version "26.0.2" …  → 26
  "$bin" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\)\..*/\1/p' | head -1
}

# Prefer an explicit JDK matching the Gradle toolchain (26).
resolve_java_home() {
  local major home
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    major="$(java_major "$JAVA_HOME/bin/java" || true)"
    if [ "$major" = "$JAVA_MAJOR" ]; then
      printf '%s\n' "$JAVA_HOME"
      return 0
    fi
  fi
  shopt -s nullglob
  for home in \
    "$BUILD_HOME/.jdks"/openjdk-"${JAVA_MAJOR}"* \
    "$BUILD_HOME/.jdks"/jdk-"${JAVA_MAJOR}"* \
    /opt/rpcnode/jdk \
    /usr/lib/jvm/java-"${JAVA_MAJOR}"-openjdk-amd64 \
    /usr/lib/jvm/java-"${JAVA_MAJOR}"-openjdk \
    /usr/lib/jvm/jdk-"${JAVA_MAJOR}"* \
    /usr/lib/jvm/temurin-"${JAVA_MAJOR}"*
  do
    if [ -x "$home/bin/java" ]; then
      major="$(java_major "$home/bin/java" || true)"
      if [ "$major" = "$JAVA_MAJOR" ]; then
        printf '%s\n' "$home"
        shopt -u nullglob
        return 0
      fi
    fi
  done
  shopt -u nullglob
  return 1
}

JAVA_HOME_RESOLVED="$(resolve_java_home || true)"
if [ -z "$JAVA_HOME_RESOLVED" ]; then
  die "JDK ${JAVA_MAJOR} not found (needed by app/build.gradle.kts jvmToolchain). Install it or set JAVA_HOME. Tried: \$JAVA_HOME, ${BUILD_HOME}/.jdks/openjdk-${JAVA_MAJOR}*"
fi
export JAVA_HOME="$JAVA_HOME_RESOLVED"
export PATH="$JAVA_HOME/bin:$PATH"
log "JAVA_HOME=$JAVA_HOME (build user: $BUILD_USER)"

run_as_builder() {
  if [ "$(id -u)" -eq 0 ] && [ "$BUILD_USER" != "root" ]; then
    # Preserve JAVA_HOME; use builder's HOME for Gradle caches.
    sudo -u "$BUILD_USER" -H -- \
      env JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:$PATH" HOME="$BUILD_HOME" \
      "$@"
  else
    env JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:$PATH" "$@"
  fi
}

escalate() {
  if [ "$(id -u)" -eq 0 ]; then
    "$@"
  elif command -v sudo >/dev/null 2>&1; then
    sudo -- "$@"
  else
    die "need root for: $*"
  fi
}

WATCH_PATHS=(
  "$APP_DIR/src/agent"
  "$APP_DIR/src/main/kotlin/rpcnode/toolkit/chains"
  "$APP_DIR/src/main/kotlin/rpcnode/toolkit/nodes/application/start"
  "$APP_DIR/src/main/kotlin/rpcnode/toolkit/nodes/infrastructure/host"
  "$APP_DIR/src/main/kotlin/rpcnode/toolkit/shared/infrastructure"
  "$APP_DIR/build.gradle.kts"
  "$APP_DIR/gradle/libs.versions.toml"
)

rebuild_and_restart() {
  log "building agentFatJar…"
  if ! run_as_builder bash -c "cd \"$APP_DIR\" && ./gradlew agentFatJar --console=plain -q"; then
    log "build FAILED — agent not restarted"
    return 1
  fi
  if [ ! -f "$JAR_SRC" ]; then
    log "missing $JAR_SRC"
    return 1
  fi
  escalate mkdir -p "$(dirname "$JAR_DEST")"
  escalate cp -f "$JAR_SRC" "$JAR_DEST"
  log "installed $JAR_DEST"
  if escalate systemctl cat "$UNIT_NAME" >/dev/null 2>&1; then
    escalate systemctl restart "$UNIT_NAME"
    if escalate systemctl is-active --quiet "$UNIT_NAME"; then
      log "restarted $UNIT_NAME (active)"
    else
      log "WARNING: $UNIT_NAME not active after restart"
      escalate systemctl --no-pager -l status "$UNIT_NAME" | head -40 || true
    fi
  else
    log "WARNING: unit $UNIT_NAME not installed — jar updated only"
    log "         install with: sudo java -jar rpcnode-agent.jar install"
  fi
}

fingerprint() {
  find "${WATCH_PATHS[@]}" \
    \( -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.toml' -o -name '*.xml' \) \) \
    -printf '%p %T@ %s\n' 2>/dev/null | sort | sha256sum | awk '{print $1}'
}

log "watching agent sources → rebuild → $JAR_DEST → $UNIT_NAME"
log "paths:"
for p in "${WATCH_PATHS[@]}"; do
  log "  $p"
done

LAST="$(fingerprint || true)"
rebuild_and_restart || true

while true; do
  sleep "$DEBOUNCE_SEC"
  NOW="$(fingerprint || true)"
  if [ -n "$NOW" ] && [ "$NOW" != "$LAST" ]; then
    LAST="$NOW"
    sleep "$DEBOUNCE_SEC"
    NOW2="$(fingerprint || true)"
    [ -n "$NOW2" ] && LAST="$NOW2"
    log "change detected"
    rebuild_and_restart || true
  fi
done
