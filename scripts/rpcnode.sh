#!/usr/bin/env bash
# rpcnode.sh — the one script for RpcNode: build, release, install, update, remove.
#
#   ./scripts/rpcnode.sh                         interactive menu
#
#   ./scripts/rpcnode.sh build   [server|agent|cdn|all] [--bump-agent] [--bump-server]
#   ./scripts/rpcnode.sh release -m "notes" [VERSION] [--build-only|--tag-only|--publish] [--dry-run]
#   ./scripts/rpcnode.sh install server|agent|cdn      (on the server; asks for sudo itself)
#   ./scripts/rpcnode.sh update  server|agent|cdn
#   ./scripts/rpcnode.sh remove  server|agent|cdn|nodes|all [--purge] [-y]
#   ./scripts/rpcnode.sh status                        services + which agent version is where
#   install/update/remove/status take --server local|server|solana (asked in a terminal when omitted)
#   ./scripts/rpcnode.sh stop    server|agent          stop a dev JVM (IntelliJ run / local jar)
#   ./scripts/rpcnode.sh watch                         rebuild + restart the agent on source changes
#
# build    jars land in app/build/libs/. "all" builds agent -> server -> cdn, in that order on
#          purpose: the server jar embeds the agent version, so it must be built after the bump.
# release  bump versions (agent too) and build; you choose how far it goes (asked in a terminal):
#            --build-only  jars in dist/release, nothing committed or tagged
#            --tag-only    + version commit and tag vX.Y.Z on this machine, nothing pushed
#            --publish     + push the tag and create the GitHub Release (default)
# install  server/agent/cdn on the chosen machine. For a remote one the scripts and the jars you built
#          (app/build/libs) are copied over ssh and the command runs there (ssh -t, so sudo can ask for a
#          password). Without a built jar the machine downloads the jar of this checkout's version from
#          GitHub Releases.
# remove   nodes = stop+delete all node services (chain data stays on disk);
#          all = nodes, agent, server, cdn. --purge also deletes the panel database/accounts.
# servers  local   this machine (systemd: a Linux host or WSL)
#          server  nekf@server
#          solana  root@45.152.160.19
#          --server NAME, or RPCNODE_SERVER=NAME; --local = --server local; RPCNODE_HOST=user@host = any other host.
#          RPCNODE_REMOTE_DIR (default rpcnode-toolkit, in the login home), RPCNODE_SSH_OPTS
#          (e.g. "-i ~/.ssh/key -p 2222"). build, release, stop, watch always run here.
#
# Windows: run it through Git Bash or scripts\rpcnode.cmd; install/update/remove/status work there for a remote server.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$HERE/lib"
ROOT="$(cd "$HERE/.." && pwd)"

die() { echo "ERROR: $*" >&2; exit 1; }
log() { printf '  %s\n' "$*"; }

SERVERS=(local server solana)
DEFAULT_SERVER="server"

# server name -> user@host ("local" = this machine)
server_host() {
  case "$1" in
    server) echo "nekf@server" ;;
    solana) echo "root@45.152.160.19" ;;
    *) return 1 ;;
  esac
}

REMOTE_DIR="${RPCNODE_REMOTE_DIR:-rpcnode-toolkit}"
SSH_OPTS=(${RPCNODE_SSH_OPTS:-})
SERVER="${RPCNODE_SERVER:-}"          # chosen with --server / the prompt
ON_HOST="${RPCNODE_ON_HOST:-0}"       # 1 = this is the copy already running on the target

is_windows_shell() {
  case "$(uname -s 2>/dev/null)" in MINGW*|MSYS*|CYGWIN*) return 0 ;; *) return 1 ;; esac
}

usage() { sed -n '2,35p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

need_linux() {
  if is_windows_shell; then
    die "'$1' manages systemd services: run it on the Linux host (or in WSL with systemd). On Windows use: build, release, stop."
  fi
}

# install / update / remove need root: ask for it once instead of making the operator type sudo.
need_root() {
  need_linux "$1"
  if [ "$(id -u)" -ne 0 ]; then
    command -v sudo >/dev/null 2>&1 || die "run as root (sudo is not installed)"
    exec sudo -E bash "${BASH_SOURCE[0]}" "${ORIG_ARGS[@]}"
  fi
}

# install / update / remove / status act on the server, not on this machine: copy what the installers read from a
# checkout (scripts, the version, the jars built here) and run the same command there. Returns 1 when the command
# must run here (--local, or this is the copy already running on the server).
choose_server() {
  [ -n "${RPCNODE_HOST:-}" ] && { SERVER="custom"; return 0; }
  [ -n "$SERVER" ] && return 0
  if { [ -r /dev/tty ] && : >/dev/tty; } 2>/dev/null; then
    local i=1 name pick
    echo >/dev/tty
    echo "  Where to run it?" >/dev/tty
    for name in "${SERVERS[@]}"; do
      printf '    %d) %-8s %s\n' "$i" "$name" "$([ "$name" = local ] && echo 'this machine' || server_host "$name")" >/dev/tty
      i=$((i + 1))
    done
    printf '  choose [%s]: ' "$DEFAULT_SERVER" >/dev/tty
    read -r pick </dev/tty || pick=""
    pick="${pick:-$DEFAULT_SERVER}"
    case "$pick" in
      [0-9]*) SERVER="${SERVERS[$((pick - 1))]:-}" ;;
      *) SERVER="$pick" ;;
    esac
  else
    SERVER="$DEFAULT_SERVER"
  fi
  [ -n "$SERVER" ] || die "no such server"
}

run_on_server() {
  [ "$ON_HOST" = 1 ] && return 1
  choose_server
  local host
  if [ "$SERVER" = custom ]; then
    host="$RPCNODE_HOST"
  elif [ "$SERVER" = local ]; then
    return 1
  else
    host="$(server_host "$SERVER")" || die "unknown server '$SERVER' (known: ${SERVERS[*]})"
  fi
  command -v ssh >/dev/null 2>&1 || die "ssh is not installed (use --server local to run on this machine)"
  local files=(scripts app/build.gradle.kts) name uploaded=""
  for name in rpcnode-agent rpcnode-server rpcnode-cdn; do
    [ -f "$ROOT/app/build/libs/$name.jar" ] || continue
    files+=("app/build/libs/$name.jar")
    uploaded="$uploaded $name.jar"
  done
  log "$SERVER: $host:~/$REMOTE_DIR"
  log "uploading scripts${uploaded:+ and$uploaded}"
  # macOS tar adds com.apple.* xattr headers that GNU tar on the server complains about: leave them out.
  local tar_opts=(--exclude=scripts/deploy-local.sh)
  tar --no-xattrs -cf /dev/null /dev/null >/dev/null 2>&1 && tar_opts+=(--no-xattrs)
  (cd "$ROOT" && COPYFILE_DISABLE=1 tar "${tar_opts[@]}" -cf - "${files[@]}") \
    | ssh ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "$host" \
      "mkdir -p '$REMOTE_DIR' && cd '$REMOTE_DIR' && rm -rf scripts app/build/libs && tar -xf -" \
    || die "could not copy to $host"
  local args
  printf -v args '%q ' "$@"
  exec ssh -t ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "$host" \
    "cd '$REMOTE_DIR' && RPCNODE_ON_HOST=1 bash scripts/rpcnode.sh $args"
}

# `agent/version` inside a jar (unzip, else python3).
jar_version() {
  local jar="$1"
  [ -f "$jar" ] || return 0
  if command -v unzip >/dev/null 2>&1; then
    unzip -p "$jar" agent/version 2>/dev/null | tr -d '[:space:]' || true
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import sys,zipfile; print(zipfile.ZipFile(sys.argv[1]).read("agent/version").decode().strip())' "$jar" 2>/dev/null || true
  fi
}

# ---------------------------------------------------------------- build

cmd_build() {
  local target="all" bump_agent=0 bump_server=0
  while [ "$#" -gt 0 ]; do
    case "$1" in
      server|agent|cdn|all) target="$1" ;;
      --bump-agent) bump_agent=1 ;;
      --bump-server) bump_server=1 ;;
      *) die "build: unknown argument '$1'" ;;
    esac
    shift
  done
  case "$target" in
    agent|all) bash "$LIB/build-agent.sh" "$bump_agent" ;;
  esac
  case "$target" in
    server|all) bash "$LIB/build-server.sh" "$bump_server" ;;
  esac
  case "$target" in
    cdn|all) bash "$LIB/build-cdn.sh" 0 ;;
  esac
  echo
  log "built (app/build/libs):"
  local j
  for j in rpcnode-agent rpcnode-server rpcnode-cdn; do
    [ -f "$ROOT/app/build/libs/$j.jar" ] || continue
    printf '    %-20s agent %s\n' "$j.jar" "$(jar_version "$ROOT/app/build/libs/$j.jar")"
  done
}

# ---------------------------------------------------------------- install / update

cmd_install() {
  local what="${1:-}"
  shift || true
  case "$what" in
    server) need_root install; bash "$LIB/install-server.sh" --install "$@" ;;
    agent) need_root install; bash "$LIB/install-agent.sh" install "$@" ;;
    cdn) need_root install; bash "$LIB/install-cdn.sh" --install "$@" ;;
    *) die "install: say what — server | agent | cdn" ;;
  esac
}

cmd_update() {
  local what="${1:-}"
  shift || true
  case "$what" in
    server) need_root update; bash "$LIB/install-server.sh" --update "$@" ;;
    agent) need_root update; bash "$LIB/install-agent.sh" update "$@" ;;
    cdn) need_root update; bash "$LIB/install-cdn.sh" --update "$@" ;;
    *) die "update: say what — server | agent | cdn" ;;
  esac
}

# ---------------------------------------------------------------- remove

cmd_remove() {
  local what="" purge=0 yes=0
  while [ "$#" -gt 0 ]; do
    case "$1" in
      server|agent|cdn|nodes|all) what="$1" ;;
      --purge) purge=1 ;;
      -y|--yes) yes=1 ;;
      *) die "remove: unknown argument '$1'" ;;
    esac
    shift
  done
  [ -n "$what" ] || die "remove: say what — server | agent | cdn | nodes | all"
  need_root remove

  local y=()
  [ "$yes" = 1 ] && y=(-y)

  case "$what" in
    nodes) bash "$LIB/remove-services.sh" --nodes "${y[@]}" ;;
    agent) bash "$LIB/install-agent.sh" uninstall ;;
    cdn) bash "$LIB/install-cdn.sh" --uninstall ;;
    server)
      if [ "$purge" = 1 ]; then bash "$LIB/install-server.sh" --purge "${y[@]}"
      else bash "$LIB/install-server.sh" --uninstall
      fi
      ;;
    all)
      echo
      echo "  This removes from THIS host: all node services (chain data stays), the agent, the"
      echo "  panel$([ "$purge" = 1 ] && echo ' INCLUDING its database and admin account') and the CDN service."
      echo
      if [ "$yes" != 1 ]; then
        { [ -r /dev/tty ] && : >/dev/tty; } 2>/dev/null || die "needs confirmation: run it in a terminal or add -y"
        printf '  Type "remove-all" to continue: ' >/dev/tty
        local answer=""
        read -r answer </dev/tty || answer=""
        [ "$answer" = "remove-all" ] || { log "cancelled — nothing was removed"; return 0; }
      fi
      bash "$LIB/remove-services.sh" --nodes -y
      [ -f /opt/rpcnode/lib/rpcnode-agent.jar ] || [ -f /etc/systemd/system/rpcnode-agent.service ] \
        && bash "$LIB/install-agent.sh" uninstall || log "agent not installed"
      if [ -f /etc/systemd/system/rpcnode-server.service ] || [ -f /opt/rpcnode/lib/rpcnode-server.jar ]; then
        if [ "$purge" = 1 ]; then bash "$LIB/install-server.sh" --purge -y
        else bash "$LIB/install-server.sh" --uninstall
        fi
      else
        log "panel not installed"
      fi
      if [ -f /etc/systemd/system/rpcnode-cdn.service ]; then
        bash "$LIB/install-cdn.sh" --uninstall
      fi
      echo
      log "done. Node data directories were not touched. Check:  ./scripts/rpcnode.sh status"
      ;;
  esac
}

# ---------------------------------------------------------------- status

cmd_status() {
  echo
  echo "  Agent versions (the panel offers an update when the served one differs from the installed one):"
  local f
  for f in /opt/rpcnode/lib/rpcnode-agent.jar:installed /opt/rpcnode/install/binaries/rpcnode-agent.jar:"served by this panel" \
           "$ROOT/app/build/libs/rpcnode-agent.jar":"local build"; do
    local path="${f%%:*}" label="${f#*:}"
    [ -f "$path" ] || continue
    printf '    %-24s %-8s %s\n' "$label" "$(jar_version "$path")" "$path"
  done
  if [ -f /opt/rpcnode/lib/rpcnode-server.jar ]; then
    printf '    %-24s %-8s %s\n' "panel jar expects" "$(jar_version /opt/rpcnode/lib/rpcnode-server.jar)" /opt/rpcnode/lib/rpcnode-server.jar
  fi
  if is_windows_shell; then
    return 0
  fi
  bash "$LIB/remove-services.sh" --list
}

# ---------------------------------------------------------------- menu

menu() {
  local pick sub
  cat <<'EOF'

  RpcNode
    1) build            server + agent (+ cdn)   (here)
    2) release          bump, build, tag, push
    3) install          server | agent | cdn      (on the server)
    4) update           server | agent | cdn
    5) remove           nodes | agent | server | cdn | everything
    6) status
    q) quit

EOF
  printf '  choose: '
  read -r pick || exit 0
  case "$pick" in
    1) cmd_build all ;;
    2)
      local msg=""
      printf '  release notes: '; read -r msg
      [ -n "$msg" ] || die "notes are required"
      bash "$LIB/release.sh" -m "$msg"
      ;;
    3) printf '  install what (server/agent/cdn): '; read -r sub; run_on_server install "$sub" || { ORIG_ARGS=(install "$sub"); cmd_install "$sub"; } ;;
    4) printf '  update what (server/agent/cdn): '; read -r sub; run_on_server update "$sub" || { ORIG_ARGS=(update "$sub"); cmd_update "$sub"; } ;;
    5) printf '  remove what (nodes/agent/server/cdn/all): '; read -r sub; run_on_server remove "$sub" || { ORIG_ARGS=(remove "$sub"); cmd_remove "$sub"; } ;;
    6) run_on_server status || cmd_status ;;
    *) exit 0 ;;
  esac
}

# ---------------------------------------------------------------- dispatch

ARGS=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --local) SERVER="local" ;;
    --server) [ "$#" -ge 2 ] || die "--server needs a name (${SERVERS[*]})"; SERVER="$2"; shift ;;
    --server=*) SERVER="${1#--server=}" ;;
    *) ARGS+=("$1") ;;
  esac
  shift
done
set -- ${ARGS[@]+"${ARGS[@]}"}

ORIG_ARGS=("$@")
cmd="${1:-menu}"
shift || true
case "$cmd" in
  build) cmd_build "$@" ;;
  release) bash "$LIB/release.sh" "$@" ;;
  install) run_on_server install "$@" || cmd_install "$@" ;;
  update|upgrade) run_on_server "$cmd" "$@" || cmd_update "$@" ;;
  remove|uninstall|delete) run_on_server "$cmd" "$@" || cmd_remove "$@" ;;
  status) run_on_server status "$@" || cmd_status ;;
  stop)
    case "${1:-}" in
      server) bash "$LIB/stop-server.sh" ;;
      agent) bash "$LIB/stop-agent.sh" ;;
      *) die "stop: server | agent" ;;
    esac
    ;;
  watch) bash "$LIB/watch-agent.sh" ;;
  menu) menu ;;
  -h|--help|help) usage ;;
  *) usage; echo; die "unknown command: $cmd" ;;
esac
