#!/usr/bin/env bash
# List / remove the systemd services RpcNode created on THIS host.
#
#   sudo ./scripts/remove-rpcnode-services.sh                # list only (nothing is changed)
#   sudo ./scripts/remove-rpcnode-services.sh --nodes        # node services (geth, tron, ... each node)
#   sudo ./scripts/remove-rpcnode-services.sh --own          # rpcnode-agent / -server / -cdn (+ legacy chain-agent)
#   sudo ./scripts/remove-rpcnode-services.sh --all          # both
#   ...add -y to skip the confirmation question
#
# Only the unit files are removed: stop + disable + delete. Chain data in the node directories
# (databases, snapshots, configs, jars) is NOT touched, and neither are the agent/server files
# in /opt/rpcnode and /etc/rpcnode. For a clean agent removal use:
#   sudo java -jar /opt/rpcnode/lib/rpcnode-agent.jar uninstall
# and for the panel:  sudo ./scripts/install-rpcnode-server.sh --purge
set -euo pipefail
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) echo "$(basename "$0") is Linux/systemd only: run it on the target host (or in WSL with systemd)." >&2; exit 1 ;;
esac
export LC_ALL=C LANG=C

die() { echo "ERROR: $*" >&2; exit 1; }
log() { printf '  %s\n' "$*"; }

SCOPE=""
ASSUME_YES=0
UNIT_DIR="${SYSTEMD_UNIT_DIR:-/etc/systemd/system}"

usage() { sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    --nodes) SCOPE=nodes; shift ;;
    --own) SCOPE=own; shift ;;
    --all) SCOPE=all; shift ;;
    -y|--yes) ASSUME_YES=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
done

command -v systemctl >/dev/null 2>&1 || die "systemctl not found"

# Names RpcNode itself installs; everything else called rpcnode-*.service is a node service.
is_own() {
  case "$1" in
    rpcnode-agent.service|rpcnode-server.service|rpcnode-cdn.service|chain-agent.service) return 0 ;;
    *) return 1 ;;
  esac
}

discover() {
  {
    systemctl list-unit-files 'rpcnode-*.service' --no-legend --no-pager 2>/dev/null | awk '{print $1}'
    systemctl list-units 'rpcnode-*.service' --all --no-legend --no-pager 2>/dev/null | awk '{gsub(/^[^a-z]+/, ""); print $1}'
    [ -f "$UNIT_DIR/chain-agent.service" ] && echo chain-agent.service
    for f in "$UNIT_DIR"/rpcnode-*.service; do
      [ -e "$f" ] && basename "$f"
    done
  } | grep -E '^(rpcnode-[A-Za-z0-9._@-]+|chain-agent)\.service$' | sort -u || true
}

NODE_UNITS=()
OWN_UNITS=()
while IFS= read -r u; do
  [ -n "$u" ] || continue
  if is_own "$u"; then OWN_UNITS+=("$u"); else NODE_UNITS+=("$u"); fi
done < <(discover)

show() {
  local title="$1"; shift
  echo
  echo "  $title"
  if [ "$#" -eq 0 ]; then
    echo "    (none)"
    return
  fi
  local u state
  for u in "$@"; do
    state="$(systemctl is-active "$u" 2>/dev/null || true)"
    printf '    %-52s %s\n' "$u" "${state:-unknown}"
  done
}

show "Node services:" "${NODE_UNITS[@]}"
show "RpcNode own services:" "${OWN_UNITS[@]}"
echo

if [ -z "$SCOPE" ]; then
  log "Nothing changed. Choose what to remove: --nodes | --own | --all   (see --help)"
  exit 0
fi

[ "$(id -u)" -eq 0 ] || die "run as root: sudo $0 --$SCOPE"

TARGETS=()
case "$SCOPE" in
  nodes) TARGETS=("${NODE_UNITS[@]}") ;;
  own) TARGETS=("${OWN_UNITS[@]}") ;;
  all) TARGETS=("${NODE_UNITS[@]}" "${OWN_UNITS[@]}") ;;
esac
if [ "${#TARGETS[@]}" -eq 0 ]; then
  log "no matching services — nothing to do"
  exit 0
fi

echo "  Will stop, disable and delete ${#TARGETS[@]} service(s) (chain data and files stay on disk)."
case "$SCOPE" in
  nodes|all) echo "  Running nodes will be STOPPED (systemd waits for a clean shutdown; big nodes can take minutes)." ;;
esac
if [ "$ASSUME_YES" != 1 ]; then
  { [ -r /dev/tty ] && : >/dev/tty; } 2>/dev/null || die "needs confirmation: run it in a terminal or add -y"
  printf '  Type "remove" to continue: ' >/dev/tty
  read -r answer </dev/tty || answer=""
  [ "$answer" = "remove" ] || { log "cancelled — nothing was removed"; exit 0; }
fi

# Node services first, the agent that manages them last.
for u in "${TARGETS[@]}"; do
  log "removing $u"
  systemctl stop "$u" 2>/dev/null || true
  systemctl disable "$u" 2>/dev/null || true
  systemctl unmask "$u" 2>/dev/null || true
  rm -f "$UNIT_DIR/$u"
  rm -rf "$UNIT_DIR/$u.d"
done
systemctl daemon-reload
systemctl reset-failed 2>/dev/null || true

echo
log "done. Check what is left:  systemctl list-units 'rpcnode-*' --all"
