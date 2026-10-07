#!/usr/bin/env bash
# rpcnode-server installer. Run from this repo on the control host (not curl).
#
#   sudo ./scripts/rpcnode.sh install server
#
# Installs rpcnode-server.jar: $RPCNODE_SERVER_JAR, else app/build/libs/, else the published GitHub
# release (tag of this checkout, then latest). Shares /opt/rpcnode with the agent installer.
set -euo pipefail
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) echo "$(basename "$0") is Linux/systemd only: run it on the target host (or in WSL with systemd)." >&2; exit 1 ;;
esac
export LC_ALL=C LANG=C

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEST_DIR="${RPCNODE_SERVER_HOME:-${RPCNODE_AGENT_HOME:-/opt/rpcnode}}"
LIB_DIR="$DEST_DIR/lib"
BIN_DIR="$DEST_DIR/bin"
DATA_DIR="${RPCNODE_SERVER_DATA:-/var/lib/rpcnode}"
INSTALL_DIR="${PANEL_INSTALL_DIR:-$DEST_DIR/install}"
SERVER_NAME="rpcnode-server"
JAR_FILE="$LIB_DIR/${SERVER_NAME}.jar"
ENV_FILE="${SERVER_ENV_FILE:-/etc/rpcnode/rpcnode-server.env}"
PORT_FILE="${SERVER_PORT_FILE:-/etc/rpcnode/rpcnode-server.port}"
UNIT_NAME="${RPCNODE_SERVER_UNIT:-rpcnode-server.service}"
UNIT_PATH="/etc/systemd/system/${UNIT_NAME}"
# Keep in sync with ServerConfig PANEL_PORT.
PORT="${PANEL_PORT:-8094}"
LISTEN="${PANEL_LISTEN:-0.0.0.0}"
JAVA_MIN="${JAVA_MIN:-25}"
JAVA_HOME_DIR="${JAVA_HOME_DIR:-$DEST_DIR/jdk}"
GUM_VERSION="${GUM_VERSION:-0.16.2}"
GUM=""
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

have_tty() {
  [ -r /dev/tty ] && true >/dev/tty 2>/dev/null
}

usage() {
  cat <<EOF
rpcnode-server

  sudo ./scripts/rpcnode.sh install server

  --install     install / reinstall
  --update      replace the jar, keep database
  --uninstall   stop the unit and remove server files (keeps toolkit.db)
  --purge       remove EVERYTHING of the panel: also database, admin password, sessions,
                settings and the client/agent files it serves. Asks first (-y to skip).
                Does not touch the agent, nodes or chain data on any host.
  --help

Env: RPCNODE_INSTALL_MODE=install|update|uninstall
     RPCNODE_SERVER_JAR  path to rpcnode-server.jar (optional; default: build/libs, else GitHub release)
     RPCNODE_VERSION     release to download when no local jar, e.g. 0.1.8 (default: this checkout)
     PANEL_PORT          listen port (default 8094; admin UI is 8093)
     PANEL_LISTEN        bind address (default 0.0.0.0)
     PANEL_CORS_ORIGINS  browser origins allowed to call the API; empty = any (admin UI on :8093).
                         Kept across --update if already in the env file.
EOF
}

PURGE=0
ASSUME_YES=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --install) INSTALL_ACTION=install; shift ;;
    --update|--reinstall) INSTALL_ACTION=update; shift ;;
    --uninstall) INSTALL_ACTION=uninstall; shift ;;
    --purge|--remove-all) INSTALL_ACTION=uninstall; PURGE=1; shift ;;
    -y|--yes) ASSUME_YES=1; shift ;;
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
  die "run as root: sudo ./scripts/rpcnode.sh install server"
fi

host_ip() {
  if command -v ip >/dev/null 2>&1; then
    ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1
  elif command -v hostname >/dev/null 2>&1; then
    hostname -I 2>/dev/null | awk '{print $1}'
  fi
}

host_arch() {
  case "$(uname -m)" in
    x86_64|amd64) echo amd64 ;;
    aarch64|arm64) echo arm64 ;;
    *) die "unsupported arch: $(uname -m) (need amd64 or arm64)" ;;
  esac
}

detect_pkg_mgr() {
  if command -v apt-get >/dev/null 2>&1; then
    echo apt
  elif command -v dnf >/dev/null 2>&1; then
    echo dnf
  elif command -v yum >/dev/null 2>&1; then
    echo yum
  elif command -v zypper >/dev/null 2>&1; then
    echo zypper
  elif command -v pacman >/dev/null 2>&1; then
    echo pacman
  elif command -v apk >/dev/null 2>&1; then
    echo apk
  else
    echo none
  fi
}

pkg_install() {
  local mgr="$1"
  shift
  case "$mgr" in
    apt)
      export DEBIAN_FRONTEND="${DEBIAN_FRONTEND:-noninteractive}"
      apt-get update -qq
      apt-get install -y -qq "$@"
      ;;
    dnf) dnf install -y -q "$@" ;;
    yum) yum install -y -q "$@" ;;
    zypper) zypper --non-interactive install -y "$@" ;;
    pacman) pacman -Sy --noconfirm "$@" ;;
    apk) apk add --no-cache "$@" ;;
    *) return 1 ;;
  esac
}

java_major() {
  local bin="$1"
  [ -x "$bin" ] || return 1
  "$bin" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1
}

java_ok() {
  local bin="$1"
  local maj
  maj="$(java_major "$bin" || true)"
  [ -n "$maj" ] && [ "$maj" -ge "$JAVA_MIN" ]
}

find_java() {
  local c
  for c in \
    "${JAVA_HOME_DIR}/bin/java" \
    "${JAVA_HOME:-}/bin/java" \
    "$(command -v java 2>/dev/null || true)" \
    /usr/lib/jvm/java-${JAVA_MIN}-openjdk/bin/java \
    /usr/lib/jvm/jre-${JAVA_MIN}-openjdk/bin/java \
    /usr/lib/jvm/java-${JAVA_MIN}-openjdk-amd64/bin/java \
    /usr/lib/jvm/java-${JAVA_MIN}-openjdk-arm64/bin/java \
    /usr/lib/jvm/temurin-${JAVA_MIN}-jre/bin/java
  do
    if [ -n "$c" ] && java_ok "$c"; then
      printf '%s\n' "$c"
      return 0
    fi
  done
  return 1
}

install_java_pkg() {
  local mgr="$1"
  log "installing Java ${JAVA_MIN}+ via $mgr"
  case "$mgr" in
    apt)
      pkg_install apt "openjdk-${JAVA_MIN}-jre-headless" \
        || pkg_install apt "openjdk-${JAVA_MIN}-jdk-headless"
      ;;
    dnf)
      pkg_install dnf "java-${JAVA_MIN}-openjdk-headless" \
        || pkg_install dnf "java-${JAVA_MIN}-openjdk"
      ;;
    yum)
      pkg_install yum "java-${JAVA_MIN}-openjdk-headless" \
        || pkg_install yum "java-${JAVA_MIN}-openjdk"
      ;;
    zypper)
      pkg_install zypper "java-${JAVA_MIN}-openjdk"
      ;;
    pacman)
      pkg_install pacman jre-openjdk-headless || pkg_install pacman jdk-openjdk
      ;;
    apk)
      pkg_install apk "openjdk${JAVA_MIN}-jre" \
        || pkg_install apk "openjdk${JAVA_MIN}-jre-headless"
      ;;
    *)
      return 1
      ;;
  esac
}

install_java_temurin() {
  local arch tarch url tarball
  # Adoptium "linux" tarball is glibc Linux only. Windows/macOS run the JAR from a JDK
  # (IDEA JBR); this installer is the Linux host path (pkg manager first).
  [ "$(uname -s)" = Linux ] || die "Temurin fallback is Linux-only; install Java ${JAVA_MIN}+ and retry"
  arch="$(host_arch)"
  case "$arch" in
    amd64) tarch=x64 ;;
    arm64) tarch=aarch64 ;;
  esac
  url="https://api.adoptium.net/v3/binary/latest/${JAVA_MIN}/ga/linux/${tarch}/jre/hotspot/normal/eclipse?project=jdk"
  log "installing Temurin JRE ${JAVA_MIN} (${arch}) -> ${JAVA_HOME_DIR}"
  mkdir -p "$DEST_DIR"
  tarball="$(mktemp /tmp/rpcnode-server-jre.XXXXXX.tar.gz)"
  curl -fsSL "$url" -o "$tarball"
  rm -rf "$JAVA_HOME_DIR"
  mkdir -p "$JAVA_HOME_DIR"
  tar -xzf "$tarball" -C "$JAVA_HOME_DIR" --strip-components=1
  rm -f "$tarball"
  [ -x "$JAVA_HOME_DIR/bin/java" ]
}

ensure_curl() {
  command -v curl >/dev/null 2>&1 && return 0
  local mgr
  mgr="$(detect_pkg_mgr)"
  [ "$mgr" != none ] || die "need curl"
  log "installing curl ($mgr)"
  pkg_install "$mgr" curl || die "could not install curl"
}

ensure_java() {
  local found mgr
  found="$(find_java || true)"
  if [ -n "$found" ]; then
    JAVA_BIN="$found"
    log "java $JAVA_BIN"
    return 0
  fi
  log "Java ${JAVA_MIN}+ not found — installing"
  ensure_curl
  mgr="$(detect_pkg_mgr)"
  if [ "$mgr" != none ]; then
    install_java_pkg "$mgr" || true
    found="$(find_java || true)"
    if [ -n "$found" ]; then
      JAVA_BIN="$found"
      log "java $JAVA_BIN"
      return 0
    fi
  fi
  install_java_temurin || die "could not install Java ${JAVA_MIN}+ (pkg=$mgr, then Temurin linux/$(host_arch))"
  JAVA_BIN="$JAVA_HOME_DIR/bin/java"
  java_ok "$JAVA_BIN" || die "installed java is older than ${JAVA_MIN}"
  log "java $JAVA_BIN"
}

ensure_gum() {
  GUM=""
  if command -v gum >/dev/null 2>&1; then
    GUM="$(command -v gum)"
    return 0
  fi
  if [ -x "$BIN_DIR/gum" ]; then
    GUM="$BIN_DIR/gum"
    return 0
  fi
  have_tty || return 0
  local arch garch url tarball tmp
  arch="$(host_arch)"
  case "$arch" in
    amd64) garch=x86_64 ;;
    arm64) garch=arm64 ;;
  esac
  url="https://github.com/charmbracelet/gum/releases/download/v${GUM_VERSION}/gum_${GUM_VERSION}_Linux_${garch}.tar.gz"
  log "installing gum ${GUM_VERSION} (${arch})"
  mkdir -p "$BIN_DIR"
  tarball="$(mktemp /tmp/rpcnode-server-gum.XXXXXX.tar.gz)"
  tmp="$(mktemp -d /tmp/rpcnode-server-gum.XXXXXX)"
  if curl -fsSL "$url" -o "$tarball" && tar -xzf "$tarball" -C "$tmp"; then
    g="$(find "$tmp" -type f -name gum | head -1)"
    if [ -n "$g" ]; then
      cp -f "$g" "$BIN_DIR/gum"
      chmod 755 "$BIN_DIR/gum"
    fi
  fi
  rm -rf "$tarball" "$tmp"
  if [ -x "$BIN_DIR/gum" ]; then
    GUM="$BIN_DIR/gum"
  fi
}

already_installed() {
  [ -f "$UNIT_PATH" ] || [ -f "$JAR_FILE" ] || [ -f "$ENV_FILE" ]
}

print_banner() {
  if [ -n "$GUM" ] && have_tty; then
    "$GUM" style --border rounded --padding "1 3" --border-foreground 212 --foreground 15 --bold \
      "rpcnode-server" "RpcNode control server" >/dev/tty
    return 0
  fi
  {
    printf '\n'
    printf '  rpcnode-server\n'
    printf '  RpcNode control server\n'
    printf '\n'
  } >/dev/tty 2>/dev/null || printf '\nrpcnode-server\n'
}

choose_action() {
  local choice default="Install"
  if [ -n "$INSTALL_ACTION" ]; then
    return 0
  fi
  if already_installed; then
    default="Update"
  fi
  if ! have_tty; then
    if already_installed; then
      INSTALL_ACTION=update
      log "no TTY — updating existing install"
    else
      INSTALL_ACTION=install
      log "no TTY — installing"
    fi
    return 0
  fi
  if [ -n "$GUM" ]; then
    choice="$("$GUM" choose --header "What do you want to do?" --selected "$default" \
      "Install" "Update" "Uninstall" "Cancel" </dev/tty)" || choice="Cancel"
  else
    {
      printf '  [1] Install\n'
      printf '  [2] Update\n'
      printf '  [3] Uninstall\n'
      printf '  [4] Cancel\n'
      printf '\n'
    } >/dev/tty
    printf 'Choose [1/2/3/4]: ' >/dev/tty
    read -r choice </dev/tty || choice="4"
  fi
  case "$(printf '%s' "$choice" | tr '[:upper:]' '[:lower:]')" in
    1|i|install) INSTALL_ACTION=install ;;
    2|u|update|upgrade|reinstall) INSTALL_ACTION=update ;;
    3|uninstall|remove|d|delete) INSTALL_ACTION=uninstall ;;
    4|c|cancel|n|no|q|"") INSTALL_ACTION=cancel ;;
    *) INSTALL_ACTION=cancel ;;
  esac
}

write_port() {
  mkdir -p /etc/rpcnode
  printf '%s\n' "$PORT" > "$PORT_FILE"
  chmod 644 "$PORT_FILE"
  log "API port ${PORT}"
}

local_jar() {
  if [ -n "${RPCNODE_SERVER_JAR:-}" ]; then
    printf '%s\n' "$RPCNODE_SERVER_JAR"
    return 0
  fi
  if [ -f "$REPO_ROOT/app/build/libs/rpcnode-server.jar" ]; then
    printf '%s\n' "$REPO_ROOT/app/build/libs/rpcnode-server.jar"
    return 0
  fi
  return 1
}

RELEASE_REPO="${RPCNODE_RELEASE_REPO:-rpcnode/toolkit}"
FETCHED_DIR=""

# Version of this checkout (app/build.gradle.kts), or RPCNODE_VERSION (e.g. 0.1.8 or v0.1.8).
checkout_version() {
  local v="${RPCNODE_VERSION:-}"
  if [ -z "$v" ] && [ -f "$REPO_ROOT/app/build.gradle.kts" ]; then
    v="$(sed -n 's/^version = "\([^"]*\)".*/\1/p' "$REPO_ROOT/app/build.gradle.kts" | head -1)"
  fi
  printf '%s\n' "${v#v}"
}

# Download a release asset (rpcnode-server.jar / rpcnode-agent.jar) to $FETCHED_DIR and print its path.
# Tries the tag matching this checkout first, then the latest release.
fetch_release_asset() {
  local name="$1" ver url out
  ensure_curl
  [ -d "$FETCHED_DIR" ] || die "internal: FETCHED_DIR not prepared"
  out="$FETCHED_DIR/$name"
  [ -s "$out" ] && { printf '%s\n' "$out"; return 0; }
  ver="$(checkout_version)"
  for url in \
    "${ver:+https://github.com/${RELEASE_REPO}/releases/download/v${ver}/${name}}" \
    "https://github.com/${RELEASE_REPO}/releases/latest/download/${name}"
  do
    [ -n "$url" ] || continue
    log "downloading $url" >&2
    if curl -fSL --retry 3 --connect-timeout 20 -o "$out" "$url" 2>/dev/null && [ -s "$out" ]; then
      printf '%s\n' "$out"
      return 0
    fi
    rm -f "$out"
  done
  return 1
}

install_jar() {
  local src
  if ! src="$(local_jar)"; then
    log "no local rpcnode-server.jar — using the published release"
    FETCHED_DIR="$(mktemp -d)"
    src="$(fetch_release_asset rpcnode-server.jar)" || die "no local jar and could not download rpcnode-server.jar from github.com/${RELEASE_REPO} releases.
  Put the jar next to this script's repo and set RPCNODE_SERVER_JAR=/path/rpcnode-server.jar,
  or build it: (cd \"$REPO_ROOT/app\" && ./gradlew buildFatJar)"
  fi
  [ -f "$src" ] || die "missing $src"
  mkdir -p "$LIB_DIR"
  log "copying $src"
  cp -f "$src" "$JAR_FILE"
}

# `agent/version` inside a jar (unzip, else python3). Empty when it cannot be read.
jar_agent_version() {
  local jar="$1" v=""
  [ -f "$jar" ] || return 0
  if command -v unzip >/dev/null 2>&1; then
    v="$(unzip -p "$jar" agent/version 2>/dev/null | tr -d '[:space:]' || true)"
  elif command -v python3 >/dev/null 2>&1; then
    v="$(python3 -c 'import sys,zipfile; print(zipfile.ZipFile(sys.argv[1]).read("agent/version").decode().strip())' "$jar" 2>/dev/null || true)"
  fi
  printf '%s\n' "$v"
}

# The panel hands this jar to every new host (and to agents that self-update), and announces the
# version found inside it. A stale copy committed in the checkout (app/public/install/binaries)
# used to win over the release, so new hosts got an old agent that then "updated" to the same jar
# forever. Pick the candidate whose version matches the server jar; download the release otherwise.
stage_install_dir() {
  mkdir -p "$INSTALL_DIR/binaries"
  local staged="$INSTALL_DIR/binaries/rpcnode-agent.jar" agent="" want cand v
  want="$(jar_agent_version "$JAR_FILE")"
  for cand in \
    "$REPO_ROOT/app/build/libs/rpcnode-agent.jar" \
    "$REPO_ROOT/app/public/install/binaries/rpcnode-agent.jar" \
    "$staged"
  do
    [ -s "$cand" ] || continue
    if [ -z "$want" ]; then
      agent="$cand"   # cannot compare versions: first existing wins, as before
      break
    fi
    v="$(jar_agent_version "$cand")"
    if [ "$v" = "$want" ]; then
      agent="$cand"
      break
    fi
    log "skipping $cand (agent ${v:-?}, the server expects $want)"
  done
  if [ -z "$agent" ]; then
    [ -d "$FETCHED_DIR" ] || FETCHED_DIR="$(mktemp -d)"
    agent="$(fetch_release_asset rpcnode-agent.jar || true)"
    if [ -z "$agent" ]; then
      log "WARNING: no matching rpcnode-agent.jar — hosts cannot be installed from the panel until it is in $INSTALL_DIR/binaries"
    elif [ -n "$want" ] && [ "$(jar_agent_version "$agent")" != "$want" ]; then
      log "WARNING: the downloaded agent is $(jar_agent_version "$agent"), the server expects $want — pass RPCNODE_VERSION=<release> to pick a matching one"
    fi
  fi
  if [ -n "$agent" ] && [ "$agent" != "$staged" ]; then
    log "staging agent jar $(jar_agent_version "$agent") into $INSTALL_DIR/binaries"
    cp -f "$agent" "$staged"
  fi
  [ -z "$FETCHED_DIR" ] || rm -rf "$FETCHED_DIR"
}

write_env_and_unit() {
  mkdir -p "$(dirname "$ENV_FILE")" "$DATA_DIR"
  umask 077
  # PANEL_CORS_ORIGINS (blank = any origin, needed when the admin UI runs on another port/host)
  # is an operator setting: take it from this run's environment, else keep what the old file had,
  # so --update does not silently drop it.
  local cors_line=""
  if [ "${PANEL_CORS_ORIGINS+set}" = set ]; then
    cors_line="PANEL_CORS_ORIGINS=${PANEL_CORS_ORIGINS}"
  elif [ -f "$ENV_FILE" ]; then
    cors_line="$(grep -E '^PANEL_CORS_ORIGINS=' "$ENV_FILE" | tail -1 || true)"
  fi
  cat > "$ENV_FILE" <<EOF
PANEL_LISTEN=${LISTEN}
PANEL_PORT=${PORT}
TOOLKIT_DB=${DATA_DIR}/toolkit.db
PANEL_HTPASSWD=/etc/rpcnode/panel.htpasswd
PANEL_SESSIONS=${DATA_DIR}/panel-sessions.json
PANEL_INSTALL_DIR=${INSTALL_DIR}
EOF
  [ -z "$cors_line" ] || printf '%s\n' "$cors_line" >> "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  cat > "$UNIT_PATH" <<EOF
[Unit]
Description=RpcNode control server
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
EnvironmentFile=${ENV_FILE}
WorkingDirectory=${DEST_DIR}
ExecStart=${JAVA_BIN} --enable-native-access=ALL-UNNAMED -jar ${JAR_FILE}
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF
}

start_and_wait() {
  systemctl daemon-reload
  systemctl enable "$UNIT_NAME"
  if ! systemctl restart "$UNIT_NAME"; then
    journalctl -u "$UNIT_NAME" -n 40 --no-pager || true
    die "failed to start $UNIT_NAME"
  fi
  systemctl is-active --quiet "$UNIT_NAME" || die "$UNIT_NAME is not active"
  local health_url="http://127.0.0.1:${PORT}/healthz" body="" ok=0 i=0
  while [ "$i" -lt 20 ]; do
    i=$((i + 1))
    body="$(curl -fsS "$health_url" 2>/dev/null || true)"
    if printf '%s' "$body" | grep -q '"alive":true'; then
      ok=1
      break
    fi
    sleep 0.5
  done
  if [ "$ok" -ne 1 ]; then
    journalctl -u "$UNIT_NAME" -n 40 --no-pager || true
    die "rpcnode-server health check failed: $health_url"
  fi
}

print_done() {
  local ip url
  IP="$(host_ip)"
  IP="${IP:-127.0.0.1}"
  url="http://${IP}:${PORT}"
  cat <<EOF

  unit     ${UNIT_NAME} ($(systemctl is-active "$UNIT_NAME"))
  jar      ${JAR_FILE}
  listen   ${LISTEN}:${PORT}
  db       ${DATA_DIR}/toolkit.db

  API URL   :  ${url}
  Setup     :  ${url}/setup

Admin UI (rpcnode-admin) is :8093. This server is :8094.
Vite dev: :5173 with VITE_API_URL=${url}.
First visit /setup if no admin user exists yet.

EOF
}

do_install() {
  need systemctl
  ensure_java
  install_jar
  stage_install_dir
  write_port
  write_env_and_unit
  start_and_wait
  print_done
}

do_update() {
  need systemctl
  already_installed || die "rpcnode-server is not installed — choose Install"
  ensure_java
  install_jar
  stage_install_dir
  write_port
  write_env_and_unit
  start_and_wait
  print_done
}

do_uninstall() {
  if [ "$PURGE" = 1 ]; then
    do_purge
    return
  fi
  log "removing rpcnode-server"
  if command -v systemctl >/dev/null 2>&1; then
    systemctl stop "$UNIT_NAME" 2>/dev/null || true
    systemctl disable "$UNIT_NAME" 2>/dev/null || true
    systemctl daemon-reload 2>/dev/null || true
  fi
  rm -f "$UNIT_PATH"
  rm -f "$JAR_FILE"
  rm -f "$ENV_FILE" "$PORT_FILE"
  cat <<EOF

  rpcnode-server removed (database kept in ${DATA_DIR}).
  Re-install:  sudo ./scripts/rpcnode.sh install server
  Remove everything including the database:  sudo ./scripts/rpcnode.sh remove server --purge

EOF
}

# Refuse system directories: a wrong env override must never turn --purge into rm -rf /etc.
safe_purge_dir() {
  case "$1" in
    ""|/|/bin|/boot|/dev|/etc|/home|/lib|/lib64|/opt|/proc|/root|/run|/sbin|/srv|/sys|/tmp|/usr|/var|/var/lib|/var/www)
      return 1 ;;
  esac
  case "$1" in /*) return 0 ;; *) return 1 ;; esac
}

do_purge() {
  local htpasswd="/etc/rpcnode/panel.htpasswd" d
  for d in "$DATA_DIR" "$INSTALL_DIR"; do
    safe_purge_dir "$d" || die "refusing to purge '$d' (not a dedicated directory)"
  done
  cat <<EOF

  This permanently deletes rpcnode-server and ALL of its state:

    service        $UNIT_PATH
    program        $JAR_FILE
    config         $ENV_FILE, $PORT_FILE
    admin account  $htpasswd
    database etc.  $DATA_DIR   (toolkit.db: servers, nodes, settings, sessions, tokens)
    served files   $INSTALL_DIR   (client files and the agent jar)

  Not touched: agents, nodes and chain data on managed hosts, the shared Java in $DEST_DIR.

EOF
  if [ "$ASSUME_YES" != 1 ]; then
    have_tty || die "--purge needs confirmation: run it in a terminal or add -y"
    local answer=""
    printf '  Type "purge" to continue: ' >/dev/tty
    read -r answer </dev/tty || answer=""
    if [ "$answer" != "purge" ]; then
      log "cancelled — nothing was removed"
      return 0
    fi
  fi
  log "purging rpcnode-server"
  if command -v systemctl >/dev/null 2>&1; then
    systemctl stop "$UNIT_NAME" 2>/dev/null || true
    systemctl disable "$UNIT_NAME" 2>/dev/null || true
  fi
  rm -f "$UNIT_PATH" "$JAR_FILE" "$ENV_FILE" "$PORT_FILE" "$htpasswd"
  rm -rf -- "$DATA_DIR" "$INSTALL_DIR"
  if command -v systemctl >/dev/null 2>&1; then
    systemctl daemon-reload 2>/dev/null || true
  fi
  rmdir "$LIB_DIR" "$DEST_DIR" /etc/rpcnode 2>/dev/null || true
  cat <<EOF

  rpcnode-server and its data are gone.
  Fresh install:  sudo ./scripts/rpcnode.sh install server

EOF
}

need systemctl
ensure_curl
ensure_gum
print_banner
choose_action

case "$INSTALL_ACTION" in
  install) do_install ;;
  update) do_update ;;
  uninstall) do_uninstall ;;
  cancel)
    log "cancelled"
    exit 0
    ;;
  *)
    die "nothing to do"
    ;;
esac
