#!/usr/bin/env bash
# RpcNode host agent. Downloads binaries from toolkit.rpcnode.dev and sets up systemd.
#
#   curl -fsSL "https://toolkit.rpcnode.dev/install/agent.sh" | sudo bash
#
set -euo pipefail

# Install CDN is toolkit.rpcnode.dev. Old units/env still have rpcnode.dev (404).
canon_toolkit_cdn() {
  local u="$1"
  case "$u" in
    https://www.rpcnode.dev/*|http://www.rpcnode.dev/*|https://rpcnode.dev/*|http://rpcnode.dev/*)
      echo "https://toolkit.rpcnode.dev/${u#*rpcnode.dev/}"
      ;;
    *) echo "$u" ;;
  esac
}
AGENT_DOWNLOAD_URL="$(canon_toolkit_cdn "${AGENT_DOWNLOAD_URL:-https://toolkit.rpcnode.dev/install/agent.sh}")"
INSTALL_BASE_URL="$(canon_toolkit_cdn "${INSTALL_BASE_URL:-https://toolkit.rpcnode.dev/install}")"
BINARIES_BASE_URL="$(canon_toolkit_cdn "${BINARIES_BASE_URL:-${INSTALL_BASE_URL}/binaries}")"
# Unpacked tarball (agent.sh + binaries/ + watchdog). Skips the download.
LOCAL_ARTIFACT_DIR="${LOCAL_ARTIFACT_DIR:-}"
INSTALL_ROOT="${INSTALL_ROOT:-/opt/rpcnode}"
TOOLKIT_DIR="${TOOLKIT_DIR:-$INSTALL_ROOT}"
BIN_DIR="${BIN_DIR:-$INSTALL_ROOT/bin}"
# Host tip listen. AGENT_RPC_PORT pins it; otherwise we pick a free one.
# Default 38990 — must not equal TRON leaf public 39090.
AGENT_RPC_PORT_PREFERRED="${AGENT_RPC_PORT:-}"
AGENT_RPC_PORT_DEFAULT="${AGENT_RPC_PORT_DEFAULT:-38990}"
AGENT_RPC_PORT=""
AGENT_LISTEN="${AGENT_LISTEN:-0.0.0.0}"
# 1 = try `go build` if the download fails (needs sources on disk).
ALLOW_GO_BUILD="${ALLOW_GO_BUILD:-0}"

HOST_OS=""
HOST_ARCH=""
HOST_OS_PRETTY=""
BINARY_SOURCE=""
PORT_PICK_REASON=""
INSTALL_ACTION="${RPCNODE_INSTALL_MODE:-}"

usage() {
  cat <<'EOF'
RpcNode host agent

  curl -fsSL "https://toolkit.rpcnode.dev/install/agent.sh" | sudo bash

  # offline:
  #   tar -xzf rpcnode-agent-VERSION.tar.gz -C /tmp
  #   sudo LOCAL_ARTIFACT_DIR=/tmp/rpcnode-agent-VERSION bash /tmp/rpcnode-agent-VERSION/agent.sh

Options:
  --help
  --uninstall-agents  drop agents + watchdog, keep fullnodes
  --uninstall         agents + fullnodes + datadirs
  --reinstall         upgrade in place (default on a non-tty re-run)
  --toolkit-dir DIR   install root (default /opt/rpcnode)

Env:
  INSTALL_BASE_URL      https://toolkit.rpcnode.dev/install
  BINARIES_BASE_URL     $INSTALL_BASE_URL/binaries
  LOCAL_ARTIFACT_DIR    unpacked archive
  AGENT_RPC_PORT        preferred port (default 38990; not TRON public 39090)
  RPCNODE_INSTALL_MODE  reinstall | uninstall-agents | uninstall | cancel

Agents only:
  curl -fsSL "https://toolkit.rpcnode.dev/install/uninstall-agents.sh" | sudo bash

Needs curl. After install, paste Agent URL + key into the panel.
EOF
}

hostlog() {
  local level="$1"
  shift
  local ts f
  ts="$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date)"
  f="${RPCNODE_HOST_LOG:-/var/log/rpcnode.log}"
  mkdir -p "$(dirname "$f")" 2>/dev/null || true
  printf '%s %-5s [agent.sh] %s\n' "$ts" "$level" "$*" >>"$f" 2>/dev/null || true
}
log() { printf '+ %s\n' "$*"; hostlog INFO "$*"; }
warn() { printf '! %s\n' "$*" >&2; hostlog WARN "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; hostlog ERROR "$*"; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --toolkit-dir)
      TOOLKIT_DIR="${2:-}"
      [[ -n "$TOOLKIT_DIR" ]] || die "--toolkit-dir requires a path"
      shift 2
      ;;
    --uninstall-agents|--uninstall-agent)
      INSTALL_ACTION=uninstall-agents
      shift
      ;;
    --uninstall)
      INSTALL_ACTION=uninstall
      shift
      ;;
    --reinstall)
      INSTALL_ACTION=reinstall
      shift
      ;;
    --ref)
      warn "--ref is unused, ignored"
      shift 2
      ;;
    *)
      die "unknown argument: $1 (see --help)"
      ;;
  esac
done

if [[ "$(id -u)" -ne 0 ]]; then
  die "run as root (sudo), e.g. curl -fsSL \"$AGENT_DOWNLOAD_URL\" | sudo bash"
fi

export DEBIAN_FRONTEND="${DEBIAN_FRONTEND:-noninteractive}"

detect_platform() {
  local uname_s uname_m
  uname_s="$(uname -s 2>/dev/null || echo unknown)"
  uname_m="$(uname -m 2>/dev/null || echo unknown)"
  HOST_OS_PRETTY="${uname_s} ${uname_m}"

  case "$uname_s" in
    Linux|linux)   HOST_OS=linux ;;
    Darwin|darwin) HOST_OS=darwin ;;
    *) die "unsupported OS: $uname_s (need Linux or Darwin)" ;;
  esac

  case "$uname_m" in
    x86_64|amd64)          HOST_ARCH=amd64 ;;
    aarch64|arm64|armv8*)  HOST_ARCH=arm64 ;;
    *) die "unsupported arch: $uname_m (need amd64 or arm64)" ;;
  esac

  log "detected platform: os=${HOST_OS} arch=${HOST_ARCH} (${HOST_OS_PRETTY})"
}

if command -v apt-get >/dev/null 2>&1; then
  apt-get update -qq 2>/dev/null || true
  apt-get install -y -qq curl ca-certificates 2>/dev/null || true
fi

if [[ -z "${LOCAL_ARTIFACT_DIR}" ]]; then
  command -v curl >/dev/null 2>&1 || die "curl is required (or set LOCAL_ARTIFACT_DIR to an unpacked archive)"
else
  [[ -d "$LOCAL_ARTIFACT_DIR" ]] || die "LOCAL_ARTIFACT_DIR missing: $LOCAL_ARTIFACT_DIR"
  [[ -d "$LOCAL_ARTIFACT_DIR/binaries" ]] \
    || die "LOCAL_ARTIFACT_DIR incomplete (need binaries/): $LOCAL_ARTIFACT_DIR"
  log "using local artifacts: $LOCAL_ARTIFACT_DIR"
fi

ensure_go() {
  if command -v go >/dev/null 2>&1; then
    local ver
    ver="$(go env GOVERSION 2>/dev/null || go version | awk '{print $3}')"
    log "using Go ${ver}"
    return 0
  fi

  [[ "$HOST_OS" == "linux" ]] || die "Go not found; install Go 1.22+ for build fallback on ${HOST_OS}"

  log "installing Go 1.22.10 (linux ${HOST_ARCH})"
  local tarball="go1.22.10.linux-${HOST_ARCH}.tar.gz"
  curl -fsSL "https://go.dev/dl/${tarball}" -o "/tmp/${tarball}"
  rm -rf /usr/local/go
  tar -C /usr/local -xzf "/tmp/${tarball}"
  rm -f "/tmp/${tarball}"
  export PATH="/usr/local/go/bin:${PATH}"
  command -v go >/dev/null 2>&1 || die "Go install failed"
  log "Go $(go env GOVERSION) installed to /usr/local/go"
}

ensure_runtime_layout() {
  mkdir -p "$BIN_DIR" "$INSTALL_ROOT" /etc/rpcnode /var/lib/rpcnode/host
  TOOLKIT_DIR="${TOOLKIT_DIR:-$INSTALL_ROOT}"
  mkdir -p "$TOOLKIT_DIR"
  local ver=""
  if [[ -n "${LOCAL_ARTIFACT_DIR}" && -f "${LOCAL_ARTIFACT_DIR}/TOOLKIT_VERSION" ]]; then
    ver="$(tr -d '[:space:]' <"${LOCAL_ARTIFACT_DIR}/TOOLKIT_VERSION" || true)"
  elif command -v curl >/dev/null 2>&1; then
    ver="$(curl -fsSL --connect-timeout 10 --max-time 20 "${INSTALL_BASE_URL}/TOOLKIT_VERSION" 2>/dev/null | tr -d '[:space:]' || true)"
  fi
  if [[ -n "$ver" ]]; then
    printf '%s\n' "$ver" >"${INSTALL_ROOT}/TOOLKIT_VERSION"
    log "channel ${ver}"
  fi
  if [[ -d "${INSTALL_ROOT}/tron-toolkit" ]]; then
    warn "leftover ${INSTALL_ROOT}/tron-toolkit (unused, ok to delete)"
  fi
}

download_one_binary() {
  local name="$1" dest="$2"
  local tmp local_src=""
  tmp="$(mktemp)"
  if [[ -n "${LOCAL_ARTIFACT_DIR}" ]]; then
    for local_src in \
      "${LOCAL_ARTIFACT_DIR}/binaries/${name}" \
      "${LOCAL_ARTIFACT_DIR}/${name}"
    do
      [[ -f "$local_src" ]] || continue
      log "copying local ${name}"
      cp -f "$local_src" "$tmp"
      break
    done
  fi
  if [[ ! -s "$tmp" ]]; then
    local url="${BINARIES_BASE_URL}/${name}"
    log "downloading ${name}"
    if ! curl -fsSL --connect-timeout 15 --max-time 180 "$url" -o "$tmp"; then
      rm -f "$tmp"
      return 1
    fi
  fi
  if [[ ! -s "$tmp" ]]; then
    rm -f "$tmp"
    return 1
  fi
  # html error page instead of a binary
  if head -c 16 "$tmp" | grep -qiE '<!DOCTYPE|<html'; then
    rm -f "$tmp"
    warn "download looked like HTML for ${name}"
    return 1
  fi
  mv -f "$tmp" "$dest"
  chmod 755 "$dest"
  return 0
}

verify_checksums_if_present() {
  local sums_tmp api_name sys_name
  api_name="rpcnode-api-agent-${HOST_OS}-${HOST_ARCH}"
  sys_name="rpcnode-system-agent-${HOST_OS}-${HOST_ARCH}"
  sums_tmp="$(mktemp)"
  if [[ -n "${LOCAL_ARTIFACT_DIR}" && -f "${LOCAL_ARTIFACT_DIR}/binaries/sha256sums.txt" ]]; then
    cp -f "${LOCAL_ARTIFACT_DIR}/binaries/sha256sums.txt" "$sums_tmp"
  elif [[ -n "${LOCAL_ARTIFACT_DIR}" && -f "${LOCAL_ARTIFACT_DIR}/sha256sums.txt" ]]; then
    cp -f "${LOCAL_ARTIFACT_DIR}/sha256sums.txt" "$sums_tmp"
  elif ! curl -fsSL --connect-timeout 10 --max-time 30 "${BINARIES_BASE_URL}/sha256sums.txt" -o "$sums_tmp" 2>/dev/null; then
    rm -f "$sums_tmp"
    warn "sha256sums.txt not available - skipping checksum verify"
    return 0
  fi
  if ! command -v shasum >/dev/null 2>&1 && ! command -v sha256sum >/dev/null 2>&1; then
    rm -f "$sums_tmp"
    warn "no sha256 tool - skipping checksum verify"
    return 0
  fi
  (
    cd "$BIN_DIR"
    if command -v shasum >/dev/null 2>&1; then
      grep -E " (${api_name}|${sys_name})$" "$sums_tmp" | while read -r hash file; do
        [[ -f "$file" ]] || continue
        echo "$hash  $file" | shasum -a 256 -c - || exit 1
      done
    else
      grep -E " (${api_name}|${sys_name})$" "$sums_tmp" | while read -r hash file; do
        [[ -f "$file" ]] || continue
        echo "$hash  $file" | sha256sum -c - || exit 1
      done
    fi
  )
  local rc=$?
  rm -f "$sums_tmp"
  if [[ $rc -ne 0 ]]; then
    die "checksum verification failed"
  fi
  log "checksums OK"
}

# Follow rpcnode-* -> tron-* leftovers so ps/journal show rpcnode-*.
materialize_canonical_agent() {
  local canon="$1"
  local base legacy resolved
  base="$(basename "$canon")"
  legacy="$(dirname "$canon")/${base/rpcnode-/tron-}"
  resolved=""

  if [[ -L "$canon" ]]; then
    resolved="$(readlink -f "$canon" 2>/dev/null || true)"
    if [[ -n "$resolved" && -f "$resolved" ]]; then
      cp -f "$resolved" "${canon}.new"
      mv -f "${canon}.new" "$canon"
      chmod 755 "$canon"
      log "materialized real binary ${base} (was symlink -> ${resolved})"
    fi
  fi

  if [[ ! -e "$canon" && -f "$legacy" ]]; then
    # old tron-* binary still sitting there
    if [[ -L "$legacy" ]]; then
      resolved="$(readlink -f "$legacy" 2>/dev/null || true)"
      [[ -n "$resolved" && -f "$resolved" ]] && cp -f "$resolved" "$canon"
    else
      cp -f "$legacy" "$canon"
    fi
    [[ -f "$canon" ]] && chmod 755 "$canon"
    log "promoted $(basename "$legacy") -> ${base}"
  fi
}

link_agent_binaries() {
  materialize_canonical_agent "$BIN_DIR/rpcnode-system-agent"
  materialize_canonical_agent "$BIN_DIR/rpcnode-api-agent"

  ln -sfn "$BIN_DIR/rpcnode-system-agent" /usr/local/bin/rpcnode-system-agent
  ln -sfn "$BIN_DIR/rpcnode-api-agent" /usr/local/bin/rpcnode-api-agent
  # old names, just aliases
  ln -sfn "$BIN_DIR/rpcnode-system-agent" "$BIN_DIR/tron-system-agent"
  ln -sfn "$BIN_DIR/rpcnode-api-agent" "$BIN_DIR/tron-api-agent"
  ln -sfn "$BIN_DIR/rpcnode-system-agent" /usr/local/bin/tron-system-agent
  ln -sfn "$BIN_DIR/rpcnode-api-agent" /usr/local/bin/tron-api-agent
}

# Rewrite ExecStart to $BIN_DIR/rpcnode-*-agent (not /usr/local/bin, not tron-*).
rewrite_unit_execstarts() {
  local f
  for f in /etc/systemd/system/*.service; do
    [[ -f "$f" ]] || continue
    grep -qE '(/usr/local/bin/(rpcnode|tron)-(api|system)-agent|/tron-(api|system)-agent)' "$f" 2>/dev/null \
      || continue
    sed -i.bak \
      -e "s|/usr/local/bin/tron-api-agent|${BIN_DIR}/rpcnode-api-agent|g" \
      -e "s|/usr/local/bin/tron-system-agent|${BIN_DIR}/rpcnode-system-agent|g" \
      -e "s|/usr/local/bin/rpcnode-api-agent|${BIN_DIR}/rpcnode-api-agent|g" \
      -e "s|/usr/local/bin/rpcnode-system-agent|${BIN_DIR}/rpcnode-system-agent|g" \
      -e "s|${BIN_DIR}/tron-api-agent|${BIN_DIR}/rpcnode-api-agent|g" \
      -e "s|${BIN_DIR}/tron-system-agent|${BIN_DIR}/rpcnode-system-agent|g" \
      -e 's|/tron-api-agent|/rpcnode-api-agent|g' \
      -e 's|/tron-system-agent|/rpcnode-system-agent|g' \
      "$f" 2>/dev/null \
      || sed -i '' \
        -e "s|/usr/local/bin/tron-api-agent|${BIN_DIR}/rpcnode-api-agent|g" \
        -e "s|/usr/local/bin/tron-system-agent|${BIN_DIR}/rpcnode-system-agent|g" \
        -e "s|/usr/local/bin/rpcnode-api-agent|${BIN_DIR}/rpcnode-api-agent|g" \
        -e "s|/usr/local/bin/rpcnode-system-agent|${BIN_DIR}/rpcnode-system-agent|g" \
        -e "s|${BIN_DIR}/tron-api-agent|${BIN_DIR}/rpcnode-api-agent|g" \
        -e "s|${BIN_DIR}/tron-system-agent|${BIN_DIR}/rpcnode-system-agent|g" \
        -e 's|/tron-api-agent|/rpcnode-api-agent|g' \
        -e 's|/tron-system-agent|/rpcnode-system-agent|g' \
        "$f"
    rm -f "${f}.bak" 2>/dev/null || true
    log "ExecStart -> ${BIN_DIR}/rpcnode-*-agent in $(basename "$f")"
  done
}

# Old agent unit names only. Leaves tron-<env>.service (java-tron) alone.
retire_legacy_tron_agent_units() {
  local f base
  for f in /etc/systemd/system/tron-api-agent.service \
           /etc/systemd/system/tron-system-agent.service \
           /etc/systemd/system/tron-api-agent-*.service \
           /etc/systemd/system/tron-system-agent-*.service; do
    [[ -e "$f" ]] || continue
    base="$(basename "$f")"
    systemctl disable --now "$base" 2>/dev/null || true
    rm -f "$f"
    log "removed legacy unit $base (use rpcnode-*-agent)"
  done
}

download_agents() {
  mkdir -p "$BIN_DIR"
  local api_name sys_name
  api_name="rpcnode-api-agent-${HOST_OS}-${HOST_ARCH}"
  sys_name="rpcnode-system-agent-${HOST_OS}-${HOST_ARCH}"

  if [[ -n "${LOCAL_ARTIFACT_DIR}" ]]; then
    log "installing prebuilt agents from LOCAL_ARTIFACT_DIR (${HOST_OS}/${HOST_ARCH})"
  else
    log "fetching prebuilt agents from ${BINARIES_BASE_URL} (${HOST_OS}/${HOST_ARCH})"
  fi
  download_one_binary "$api_name" "$BIN_DIR/rpcnode-api-agent" \
    || return 1
  download_one_binary "$sys_name" "$BIN_DIR/rpcnode-system-agent" \
    || return 1

  # checksums.txt uses the CDN filenames
  cp -f "$BIN_DIR/rpcnode-api-agent" "$BIN_DIR/$api_name"
  cp -f "$BIN_DIR/rpcnode-system-agent" "$BIN_DIR/$sys_name"
  verify_checksums_if_present
  rm -f "$BIN_DIR/$api_name" "$BIN_DIR/$sys_name"

  link_agent_binaries
  if [[ -n "${LOCAL_ARTIFACT_DIR}" ]]; then
    BINARY_SOURCE="local:${LOCAL_ARTIFACT_DIR}"
  else
    BINARY_SOURCE="download:${BINARIES_BASE_URL}"
  fi
  sync_toolkit_version_file
  return 0
}

log_agent_binary_version() {
  local ver=""
  if [[ -x "$BIN_DIR/rpcnode-api-agent" ]]; then
    ver="$("$BIN_DIR/rpcnode-api-agent" -version 2>/dev/null | tr -d '[:space:]' || true)"
  fi
  if [[ -z "$ver" && -x /usr/local/bin/rpcnode-api-agent ]]; then
    ver="$(/usr/local/bin/rpcnode-api-agent -version 2>/dev/null | tr -d '[:space:]' || true)"
  fi
  if [[ -z "$ver" && -x "$BIN_DIR/tron-api-agent" ]]; then
    ver="$("$BIN_DIR/tron-api-agent" -version 2>/dev/null | tr -d '[:space:]' || true)"
  fi
  if [[ -n "$ver" ]]; then
    log "version $ver"
  else
    log "version unknown"
  fi
}

sync_toolkit_version_file() {
  log_agent_binary_version
}

build_agents() {
  [[ -d "$TOOLKIT_DIR/api-agent" && -d "$TOOLKIT_DIR/system-agent" ]] \
    || die "ALLOW_GO_BUILD=1 but no sources under $TOOLKIT_DIR"
  ensure_go
  export PATH="/usr/local/go/bin:${PATH:-/usr/bin}"
  mkdir -p "$BIN_DIR"
  local ver=""
  ver="$(curl -fsSL --max-time 15 "${INSTALL_BASE_URL}/TOOLKIT_VERSION" 2>/dev/null | tr -d '[:space:]' || true)"
  [[ -z "$ver" && -f "${INSTALL_ROOT}/TOOLKIT_VERSION" ]] \
    && ver="$(tr -d '[:space:]' <"${INSTALL_ROOT}/TOOLKIT_VERSION" || true)"
  local ldflags="-s -w"
  if [[ -n "$ver" ]]; then
    ldflags="-s -w -X main.toolkitVersion=${ver}"
  fi
  log "building Go agents -> $BIN_DIR (dev fallback) embed=${ver:-version.go}"
  (
    cd "$TOOLKIT_DIR/system-agent"
    CGO_ENABLED=0 go build -trimpath -ldflags="$ldflags" -o "$BIN_DIR/rpcnode-system-agent" .
  )
  (
    cd "$TOOLKIT_DIR/api-agent"
    CGO_ENABLED=0 go build -trimpath -ldflags="$ldflags" -o "$BIN_DIR/rpcnode-api-agent" .
  )
  chmod 755 "$BIN_DIR/rpcnode-system-agent" "$BIN_DIR/rpcnode-api-agent"
  link_agent_binaries
  BINARY_SOURCE="go-build:local"
  sync_toolkit_version_file
}

install_agents() {
  if download_agents; then
    log "installed prebuilt binaries"
    return 0
  fi
  warn "prebuilt artifacts missing for ${HOST_OS}/${HOST_ARCH}"
  if [[ "$ALLOW_GO_BUILD" != "1" ]]; then
    die "download failed (set LOCAL_ARTIFACT_DIR or ALLOW_GO_BUILD=1)"
  fi
  warn "ALLOW_GO_BUILD=1, building from local sources"
  build_agents
}

gen_token() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  else
    head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'
  fi
}

detect_agent_url() {
  local ip=""
  ip="$(curl -4 -fsS --max-time 2 https://ifconfig.me 2>/dev/null || true)"
  [[ -z "$ip" ]] && ip="$(curl -4 -fsS --max-time 2 https://api.ipify.org 2>/dev/null || true)"
  if [[ -z "$ip" ]] && command -v hostname >/dev/null 2>&1; then
    ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  fi
  if [[ -n "$ip" ]]; then
    AGENT_URL="http://${ip}:${AGENT_RPC_PORT}"
  else
    AGENT_URL="http://<this-host>:${AGENT_RPC_PORT}"
  fi
}

# skip well-known ports when auto-picking
is_popular_port() {
  case "$1" in
    22|80|443|3000|3306|5432|6379|8000|8080|8081|8443|8888|9000|9090|\
    8090|8091|8092|8093|5173|27017|18090|18091|18092|18888|18889|18890|29090)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

port_in_use() {
  local p="$1"
  [[ "$p" =~ ^[0-9]+$ ]] || return 0
  if command -v ss >/dev/null 2>&1; then
    ss -H -lnt "( sport = :$p )" 2>/dev/null | grep -q . && return 0
    return 1
  fi
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1 && return 0
    return 1
  fi
  if command -v fuser >/dev/null 2>&1; then
    fuser "${p}/tcp" >/dev/null 2>&1 && return 0
    return 1
  fi
  # Last resort: try bind via python
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$p" <<'PY' 2>/dev/null
import socket, sys
p = int(sys.argv[1])
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    s.bind(("0.0.0.0", p))
except OSError:
    sys.exit(0)  # in use
finally:
    s.close()
sys.exit(1)  # free
PY
    return $?
  fi
  return 1
}

# Copy ports from /etc/rpcnode/nodes/*.json into toolkit.env.
sync_toolkit_env_from_nodes_json() {
  local env_file="$1" nodesf pub agent http p2p net nenv
  for nodesf in /etc/rpcnode/nodes/*.json; do
    [[ -f "$nodesf" ]] || continue
    pub="$(sed -n 's/.*"public_port"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$nodesf" | head -1)"
    agent="$(sed -n 's/.*"agent_port"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$nodesf" | head -1)"
    http="$(sed -n 's/.*"node_http_port"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$nodesf" | head -1)"
    p2p="$(sed -n 's/.*"p2p_port"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$nodesf" | head -1)"
    net="$(sed -n 's/.*"network"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$nodesf" | head -1 | tr '[:upper:]' '[:lower:]')"
    nenv="$(sed -n 's/.*"env"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$nodesf" | head -1 | tr '[:upper:]' '[:lower:]')"
    [[ "$pub" =~ ^[0-9]+$ && "$agent" =~ ^[0-9]+$ && "$http" =~ ^[0-9]+$ ]] || continue
    # bitcoind rpc port by env, ignore whatever was cached
    if [[ "$net" == "bitcoin" ]]; then
      case "${nenv:-mainnet}" in
        testnet4) http=18332 ;;
        signet)   http=38332 ;;
        regtest)  http=18443 ;;
        *)        http=8332 ;;
      esac
      env_upsert "$env_file" TRON_NETWORK bitcoin
    elif [[ -n "$net" ]]; then
      env_upsert "$env_file" TRON_NETWORK "$net"
    fi
    env_upsert "$env_file" RPCNODE_PUBLIC_PORT "$pub"
    env_upsert "$env_file" TRON_PUBLIC_PORT "$pub"
    env_upsert "$env_file" RPCNODE_GATEWAY_PORT "$pub"
    env_upsert "$env_file" TRON_GATEWAY_PORT "$pub"
    env_upsert "$env_file" RPCNODE_AGENT_PORT "$agent"
    env_upsert "$env_file" TRON_AGENT_PORT "$agent"
    env_upsert "$env_file" RPCNODE_PANEL_PORT "$agent"
    env_upsert "$env_file" TRON_PANEL_PORT "$agent"
    env_upsert "$env_file" TRON_NODE_HTTP_PORT "$http"
    if [[ "$p2p" =~ ^[0-9]+$ ]]; then
      env_upsert "$env_file" TRON_P2P_PORT "$p2p"
    fi
    log "synced toolkit.env ports from $(basename "$nodesf"): network=${net:-?} rpc=${pub} agent=${agent} upstream=${http}"
    return 0
  done
  return 1
}

has_provisioned_env() {
  local f has_unit=0 has_node=0
  for f in /etc/systemd/system/rpcnode-api-agent-*.service; do
    [[ -e "$f" ]] || continue
    has_unit=1
    break
  done
  for f in /etc/rpcnode/nodes/*.json; do
    [[ -e "$f" ]] || continue
    has_node=1
    break
  done
  [[ "$has_unit" -eq 1 && "$has_node" -eq 1 ]]
}

# Skip restart while a remove-job is still wiping this leaf.
leaf_unit_remove_pending() {
  local unit="${1%.service}"
  local slug="" job="" st=""
  case "$unit" in
    rpcnode-api-agent-*|rpcnode-system-agent-*)
      slug="${unit#rpcnode-api-agent-}"
      slug="${slug#rpcnode-system-agent-}"
      ;;
    *)
      return 1
      ;;
  esac
  [[ -n "$slug" ]] || return 1
  for job in \
    "/var/lib/rpcnode/remove-jobs/${slug}.json" \
    "/var/lib/rpcnode/remove-jobs/tron-${slug}.json"; do
    [[ -f "$job" ]] || continue
    st="$(sed -n 's/.*"status"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$job" | head -1)"
    case "$st" in
      deleting|started|wiped|error)
        return 0
        ;;
    esac
  done
  return 1
}

# Per-node agent API ports. Tip must not steal these.
# Keep in sync with api-agent/network_ports.go (Agent, not Public).
is_per_node_agent_port() {
  case "$1" in
    # tron
    39190|39191|39192)
      return 0 ;;
    # bitcoin (3929x is public/tip, not listed)
    39390|39391|39392|39393)
      return 0 ;;
    # solana
    39590|39591|39592|39593)
      return 0 ;;
    # ethereum
    39790|39791|39792|39793)
      return 0 ;;
    # bsc
    39890|39891|39990|39991|39992|39993)
      return 0 ;;
    # hyperliquid / arb / optimism
    40190|40191|40192|40193|40194|40195)
      return 0 ;;
    # robinhood
    42190|42191)
      return 0 ;;
    # base
    42390|42391)
      return 0 ;;
    # zcash
    42590|42591)
      return 0 ;;
    # sui
    42790|42791)
      return 0 ;;
    # aptos
    42990|42991)
      return 0 ;;
    # avalanche
    43190|43191)
      return 0 ;;
    # xrpl
    40390|40391)
      return 0 ;;
    # doge
    40590|40591)
      return 0 ;;
    # cardano
    40790|40791|40792)
      return 0 ;;
    # stellar
    40990|40991|40992)
      return 0 ;;
    # litecoin
    41190|41191|41192)
      return 0 ;;
    # dash agent API
    41390|41391|41392)
      return 0 ;;
    # bitcoin cash agent API
    41590|41591|41592)
      return 0 ;;
    # toncoin agent API
    41790|41791)
      return 0 ;;
    # ethereum classic agent API
    41990|41991)
      return 0 ;;
    *) return 1 ;;
  esac
}

matches_provisioned_node_agent_port() {
  local want="$1" f p
  [[ "$want" =~ ^[0-9]+$ ]] || return 1
  shopt -s nullglob
  for f in /etc/rpcnode/nodes/*.json; do
    p="$(sed -n 's/.*"agent_port"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$f" | head -1)"
    if [[ "$p" == "$want" ]]; then
      shopt -u nullglob
      return 0
    fi
  done
  shopt -u nullglob
  return 1
}

# Leaf Go RPC / public catalog. Tip must not use these (same as Agent ports).
# Keep in sync with api-agent/network_ports.go Public.
is_network_public_port() {
  case "$1" in
    39090|39091|39092) return 0 ;; # tron
    39290|39291|39292|39293) return 0 ;; # bitcoin
    39490|39491|39492|39493) return 0 ;; # solana
    39690|39691|39692) return 0 ;; # ethereum
    39890|39891) return 0 ;; # bsc
    40090|40091|40092|40093|40094|40095) return 0 ;; # hl/arb/op
    40290|40291) return 0 ;; # xrpl
    40490|40491) return 0 ;; # doge
    40690|40691|40692) return 0 ;; # cardano
    40890|40891|40892) return 0 ;; # stellar
    41090|41091|41092) return 0 ;; # ltc
    41290|41291|41292) return 0 ;; # dash
    41490|41491|41492) return 0 ;; # bch
    41690|41691) return 0 ;; # ton
    41890|41891) return 0 ;; # etc
    42090|42091) return 0 ;; # robinhood
    42290|42291) return 0 ;; # base
    42490|42491) return 0 ;; # zcash
    42690|42691) return 0 ;; # sui
    42890|42891) return 0 ;; # aptos
    43090|43091) return 0 ;; # avalanche
  esac
  return 1
}

matches_provisioned_leaf_port() {
  local want="$1" f p
  [[ "$want" =~ ^[0-9]+$ ]] || return 1
  if is_per_node_agent_port "$want" || is_network_public_port "$want" || matches_provisioned_node_agent_port "$want"; then
    return 0
  fi
  shopt -s nullglob
  for f in /etc/rpcnode/nodes/*.json; do
    for key in agent_port public_port; do
      p="$(sed -n "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\\([0-9][0-9]*\\).*/\\1/p" "$f" | head -1)"
      if [[ "$p" == "$want" ]]; then
        shopt -u nullglob
        return 0
      fi
    done
  done
  shopt -u nullglob
  return 1
}

read_host_tip_port_from_unit() {
  local p=""
  if [[ -f /etc/systemd/system/rpcnode-api-agent.service ]]; then
    p="$(grep -E '^Environment=RPCNODE_PUBLIC_PORT=' /etc/systemd/system/rpcnode-api-agent.service 2>/dev/null | head -1 | cut -d= -f3- | tr -d '[:space:]\r' || true)"
    if [[ -z "$p" ]]; then
      p="$(grep -E '^Environment=TRON_PUBLIC_PORT=' /etc/systemd/system/rpcnode-api-agent.service 2>/dev/null | head -1 | cut -d= -f3- | tr -d '[:space:]\r' || true)"
    fi
  fi
  if [[ "$p" =~ ^[0-9]+$ ]] && (( p >= 1024 && p <= 65535 )); then
    if matches_provisioned_leaf_port "$p"; then
      return 1
    fi
    printf '%s' "$p"
    return 0
  fi
  return 1
}

count_provisioned_networks() {
  local f net
  local -A seen=()
  shopt -s nullglob
  for f in /etc/rpcnode/nodes/*.json; do
    net="$(sed -n 's/.*"network"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$f" | head -1 | tr '[:upper:]' '[:lower:]')"
    [[ -n "$net" ]] || continue
    seen["$net"]=1
  done
  shopt -u nullglob
  printf '%s' "${#seen[@]}"
}

read_previous_agent_port() {
  local p="" tip
  # don't reuse a leaf port as the tip listen
  if [[ -f /etc/rpcnode/agent.port ]]; then
    p="$(tr -d '[:space:]' </etc/rpcnode/agent.port || true)"
    if [[ -n "$p" ]] && matches_provisioned_leaf_port "$p"; then
      warn "ignoring leaf port ${p} in agent.port for host tip"
      p=""
    fi
  fi
  if [[ -z "$p" ]]; then
    tip="$(read_host_tip_port_from_unit || true)"
    if [[ -n "$tip" ]]; then
      p="$tip"
    fi
  fi
  if [[ -n "$p" ]] && matches_provisioned_leaf_port "$p"; then
    warn "tip candidate :${p} collides with provisioned leaf - will re-pick"
    p=""
  fi
  if [[ -z "$p" ]] && has_provisioned_env; then
    # default tip port if it's free of leaf reservations
    if ! matches_provisioned_leaf_port "$AGENT_RPC_PORT_DEFAULT"; then
      p="$AGENT_RPC_PORT_DEFAULT"
    fi
  fi
  if [[ "$p" =~ ^[0-9]+$ ]] && (( p >= 1024 && p <= 65535 )); then
    printf '%s' "$p"
  fi
}

pick_agent_port() {
  local c prev try
  local -a candidates=()

  prev="$(read_previous_agent_port || true)"

  # keep the existing tip port across a binary swap (units were just stopped)
  if has_provisioned_env && [[ -n "$prev" ]] && ! matches_provisioned_leaf_port "$prev"; then
    AGENT_RPC_PORT="$prev"
    PORT_PICK_REASON="provisioned-stable"
    log "external agent port: ${AGENT_RPC_PORT} (${PORT_PICK_REASON})"
    mkdir -p /etc/rpcnode
    printf '%s\n' "$AGENT_RPC_PORT" >/etc/rpcnode/agent.port
    chmod 0644 /etc/rpcnode/agent.port
    return 0
  fi

  if [[ -n "$AGENT_RPC_PORT_PREFERRED" ]]; then
    candidates+=("$AGENT_RPC_PORT_PREFERRED")
  fi

  if [[ -n "$prev" ]]; then
    candidates+=("$prev")
  fi

  # shortlist; leaf ports are filtered below
  candidates+=(
    "$AGENT_RPC_PORT_DEFAULT"
    47890 48443 48765 38990 38890
  )

  # unique, keep order
  local -a uniq=()
  local seen="|"
  for c in "${candidates[@]}"; do
    [[ "$c" =~ ^[0-9]+$ ]] || continue
    [[ "$seen" == *"|$c|"* ]] && continue
    seen+="${c}|"
    uniq+=("$c")
  done

  for try in "${uniq[@]}"; do
    if is_popular_port "$try"; then
      warn "skip popular port ${try}"
      continue
    fi
    if matches_provisioned_leaf_port "$try"; then
      warn "skip leaf-reserved port ${try} for host tip"
      continue
    fi
    if port_in_use "$try"; then
      log "port ${try} busy - scanning further"
      continue
    fi
    AGENT_RPC_PORT="$try"
    if [[ -n "$AGENT_RPC_PORT_PREFERRED" && "$try" == "$AGENT_RPC_PORT_PREFERRED" ]]; then
      PORT_PICK_REASON="preferred"
    elif [[ -n "$prev" && "$try" == "$prev" ]]; then
      PORT_PICK_REASON="previous-install"
    else
      PORT_PICK_REASON="auto-free"
    fi
    log "external agent port: ${AGENT_RPC_PORT} (${PORT_PICK_REASON})"
    mkdir -p /etc/rpcnode
    printf '%s\n' "$AGENT_RPC_PORT" >/etc/rpcnode/agent.port
    chmod 0644 /etc/rpcnode/agent.port
    return 0
  done

  # scan upward from the default
  local start="${AGENT_RPC_PORT_DEFAULT}"
  local end=$((start + 200))
  (( end > 65535 )) && end=65535
  log "scanning free ports ${start}-${end} (skipping popular)"
  for ((try = start; try <= end; try++)); do
    is_popular_port "$try" && continue
    matches_provisioned_leaf_port "$try" && continue
    port_in_use "$try" && continue
    AGENT_RPC_PORT="$try"
    PORT_PICK_REASON="scan"
    log "external agent port: ${AGENT_RPC_PORT} (${PORT_PICK_REASON})"
    mkdir -p /etc/rpcnode
    printf '%s\n' "$AGENT_RPC_PORT" >/etc/rpcnode/agent.port
    chmod 0644 /etc/rpcnode/agent.port
    return 0
  done

  die "no free external agent port found in ${start}-${end}"
}

write_platform_file() {
  mkdir -p /etc/rpcnode
  cat >/etc/rpcnode/host.platform <<EOF
os=${HOST_OS}
arch=${HOST_ARCH}
os_pretty=${HOST_OS_PRETTY}
binary_source=${BINARY_SOURCE}
detected_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
  chmod 0644 /etc/rpcnode/host.platform
}

# Chain clients run as nodeop; add journal groups so they can read logs.
ensure_nodeop_runtime_user() {
  if [[ "$HOST_OS" != "linux" ]]; then
    return 0
  fi
  if ! command -v useradd >/dev/null 2>&1 && ! id nodeop >/dev/null 2>&1; then
    warn "useradd missing - skip nodeop user (create manually before provision)"
    return 0
  fi
  if ! id nodeop >/dev/null 2>&1; then
    log "creating system user nodeop (chain clients)"
    useradd --system --home /var/lib/nodeop --shell /usr/sbin/nologin nodeop 2>/dev/null \
      || useradd --system --home-dir /var/lib/nodeop --shell /usr/sbin/nologin nodeop 2>/dev/null \
      || warn "useradd nodeop failed"
  fi
  id nodeop >/dev/null 2>&1 || return 0
  mkdir -p /var/lib/nodeop
  chown nodeop:nodeop /var/lib/nodeop 2>/dev/null || true
  local g
  for g in systemd-journal adm; do
    getent group "$g" >/dev/null 2>&1 || continue
    if id -nG nodeop 2>/dev/null | tr ' ' '\n' | grep -qx "$g"; then
      continue
    fi
    log "usermod -aG $g nodeop (journal read for bootstrap %)"
    usermod -aG "$g" nodeop 2>/dev/null || warn "usermod -aG $g nodeop failed"
  done
  # debian/ubuntu journal is group-readable
  if [[ -d /var/log/journal ]]; then
    chgrp -R systemd-journal /var/log/journal 2>/dev/null || true
    chmod -R g+rX /var/log/journal 2>/dev/null || true
  fi
  log "nodeop groups: $(id -nG nodeop 2>/dev/null || echo '?')"
}

env_upsert() {
  local file="$1" key="$2" value="$3"
  mkdir -p "$(dirname "$file")"
  touch "$file"
  if grep -qE "^${key}=" "$file" 2>/dev/null; then
    sed -i.bak "s|^${key}=.*|${key}=${value}|" "$file" 2>/dev/null \
      || sed -i '' "s|^${key}=.*|${key}=${value}|" "$file"
  else
    printf '%s=%s\n' "$key" "$value" >>"$file"
  fi
  rm -f "${file}.bak" 2>/dev/null || true
}

stop_existing_agents() {
  if [[ "$HOST_OS" != "linux" ]] || ! command -v systemctl >/dev/null 2>&1; then
    # no systemd; just kill leftovers
    pkill -f '/rpcnode-system-agent( |$)' 2>/dev/null || true
    pkill -f '/rpcnode-api-agent( |$)' 2>/dev/null || true
    pkill -f '/tron-system-agent( |$)' 2>/dev/null || true
    pkill -f '/tron-api-agent( |$)' 2>/dev/null || true
    return 0
  fi
  log "stopping existing agent units"

  # leftover docker agents from older installs
  if command -v docker >/dev/null 2>&1; then
    for c in $(docker ps -aq --filter name=tron --filter name=rpcnode 2>/dev/null); do
      name="$(docker inspect -f '{{.Name}}' "$c" 2>/dev/null || true)"
      case "$name" in
        *system-agent*|*api-agent*|*gateway*)
          log "removing docker agent container ${name}"
          docker update --restart=no "$c" 2>/dev/null || true
          docker rm -f "$c" 2>/dev/null || true
          ;;
      esac
    done
    # old compose project names
    for proj in tron-toolkit-mainnet tron-mainnet rpcnode-toolkit; do
      docker compose -p "$proj" down --remove-orphans 2>/dev/null || true
    done
    if [[ -f "${TOOLKIT_DIR}/docker-compose.yml" ]]; then
      (cd "${TOOLKIT_DIR}" && docker compose --env-file /etc/tron/mainnet/toolkit.env down --remove-orphans 2>/dev/null) || true
    fi
  fi

  # tip + per-env + old names
  local -a units=(
    rpcnode-api-agent.service
    rpcnode-system-agent.service
    tron-api-agent.service
    tron-system-agent.service
  )
  local u
  # per-env units: rpcnode-api-agent-<net>-<env>.service
  for u in /etc/systemd/system/rpcnode-api-agent-*.service \
           /etc/systemd/system/rpcnode-system-agent-*.service \
           /etc/systemd/system/tron-*-api*.service \
           /etc/systemd/system/tron-*-system*.service; do
    [[ -e "$u" ]] || continue
    units+=("$(basename "$u")")
  done

  # Unique list
  local -A seen=()
  local -a uniq=()
  for u in "${units[@]}"; do
    [[ -n "${seen[$u]:-}" ]] && continue
    seen[$u]=1
    uniq+=("$u")
  done

  for u in "${uniq[@]}"; do
    # tip stays up; we restart it after the binary swap
    case "$u" in
      rpcnode-api-agent.service|rpcnode-system-agent.service|tron-api-agent.service|tron-system-agent.service)
        continue
        ;;
    esac
    systemctl stop "$u" 2>/dev/null || true
    # drop old tron-*-agent unit names
    case "$u" in
      tron-api-agent-*.service|tron-system-agent-*.service)
        systemctl disable "$u" 2>/dev/null || true
        ;;
    esac
  done

  if has_provisioned_env; then
    # keep tip + leaves enabled
    log "keeping provisioned leaf units and tip"
    for u in /etc/systemd/system/rpcnode-api-agent-*.service \
             /etc/systemd/system/rpcnode-system-agent-*.service; do
      [[ -e "$u" ]] || continue
      systemctl enable "$(basename "$u")" 2>/dev/null || true
    done
    systemctl enable rpcnode-api-agent.service rpcnode-system-agent.service 2>/dev/null || true
  else
    # first install: no leftover per-env units on the tip port
    for u in /etc/systemd/system/rpcnode-api-agent-*.service \
             /etc/systemd/system/rpcnode-system-agent-*.service; do
      [[ -e "$u" ]] || continue
      systemctl disable --now "$(basename "$u")" 2>/dev/null || true
    done
  fi

  # pkill -x is useless here: comm is truncated to 15 chars
  if has_provisioned_env; then
    # leaves only; tip is restarted via systemctl later
    pkill -f 'rpcnode-api-agent-[a-z0-9]+-' 2>/dev/null || true
    pkill -f 'rpcnode-system-agent-[a-z0-9]+-' 2>/dev/null || true
    pkill -f 'rpcnode-api-agent-(mainnet|nile|shasta)\.service' 2>/dev/null || true
  else
    pkill -f '/rpcnode-system-agent( |$)' 2>/dev/null || true
    pkill -f '/rpcnode-api-agent( |$)' 2>/dev/null || true
  fi
  pkill -f '/tron-system-agent( |$)' 2>/dev/null || true
  pkill -f '/tron-api-agent( |$)' 2>/dev/null || true
  sleep 1
  if ! has_provisioned_env; then
    if pgrep -f '/rpcnode-system-agent( |$)|/rpcnode-api-agent( |$)|/tron-system-agent( |$)|/tron-api-agent( |$)' >/dev/null 2>&1; then
      warn "force-killing leftover agent processes (fresh bootstrap)"
      pkill -9 -f '/rpcnode-system-agent( |$)' 2>/dev/null || true
      pkill -9 -f '/rpcnode-api-agent( |$)' 2>/dev/null || true
      pkill -9 -f '/tron-system-agent( |$)' 2>/dev/null || true
      pkill -9 -f '/tron-api-agent( |$)' 2>/dev/null || true
      sleep 1
    fi
  fi

  # don't fuser leaf ports; that lets tip steal them
  if ! has_provisioned_env; then
    local old_port
    old_port="$(read_previous_agent_port || true)"
    local -a free_ports=()
    [[ -n "$old_port" ]] && free_ports+=("$old_port")
    free_ports+=("$AGENT_RPC_PORT_DEFAULT" 8090)
    local p
    for p in "${free_ports[@]}"; do
      matches_provisioned_leaf_port "$p" && continue
      if command -v fuser >/dev/null 2>&1; then
        fuser -k "${p}/tcp" 2>/dev/null || true
      elif command -v lsof >/dev/null 2>&1; then
        lsof -ti "tcp:${p}" | xargs -r kill -9 2>/dev/null || true
      fi
    done
    sleep 1
  fi
}

write_host_id() {
  mkdir -p /etc/rpcnode /var/lib/rpcnode/host
  local host_id_file=/etc/rpcnode/host-agent.id
  local token_file=/etc/rpcnode/agent.token
  # old token location; read only, don't create /etc/tron
  local legacy_env=/etc/tron/mainnet/toolkit.env

  if [[ ! -f "$host_id_file" ]]; then
    if command -v openssl >/dev/null 2>&1; then
      openssl rand -hex 8 >"$host_id_file"
    else
      head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n' >"$host_id_file"
    fi
    chmod 0644 "$host_id_file"
  fi
  HOST_ID="$(tr -d '[:space:]' <"$host_id_file")"

  # reuse token if we already have one
  AGENT_API_TOKEN=""
  if [[ -f "$token_file" ]]; then
    AGENT_API_TOKEN="$(tr -d '[:space:]' <"$token_file")"
  fi
  if [[ -z "$AGENT_API_TOKEN" && -f "$legacy_env" ]]; then
    AGENT_API_TOKEN="$(grep -E '^AGENT_API_TOKEN=' "$legacy_env" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\r' || true)"
  fi
  if [[ -z "$AGENT_API_TOKEN" ]]; then
    AGENT_API_TOKEN="$(gen_token)"
    log "generated AGENT_API_TOKEN (save it for the panel)"
  else
    log "reusing existing AGENT_API_TOKEN"
  fi
  printf '%s\n' "$AGENT_API_TOKEN" >"$token_file"
  chmod 0600 "$token_file"

  # optional panel ingest url for host metrics
  if [[ -n "${PANEL_INGEST_URL:-${PANEL_BASE:-${RPCNODE_PANEL_BASE:-${TRON_PANEL_BASE:-}}}}" ]]; then
    _panel_base="${PANEL_INGEST_URL:-${PANEL_BASE:-${RPCNODE_PANEL_BASE:-${TRON_PANEL_BASE}}}}"
    export PANEL_INGEST_URL="${_panel_base}"
  fi

  printf '%s\n' "${AGENT_RPC_PORT}" >/etc/rpcnode/agent.port
  detect_agent_url
  write_platform_file
  write_register_file
}

write_register_file() {
  cat >/etc/rpcnode/register.txt <<EOF
RpcNode host agent

  Agent URL : ${AGENT_URL}
  Agent key : ${AGENT_API_TOKEN}
  Agent port: ${AGENT_RPC_PORT} (${PORT_PICK_REASON:-chosen})
  Host OS   : ${HOST_OS}/${HOST_ARCH}

Token: /etc/rpcnode/agent.token
Port:  /etc/rpcnode/agent.port
EOF
  chmod 0600 /etc/rpcnode/register.txt
}

install_systemd() {
  if [[ "$HOST_OS" != "linux" ]]; then
    warn "systemd units skipped on ${HOST_OS} - start agents manually:"
    warn "  ${BIN_DIR}/rpcnode-system-agent &"
    warn "  ${BIN_DIR}/rpcnode-api-agent &"
    return 0
  fi
  command -v systemctl >/dev/null 2>&1 || {
    warn "systemctl not found - binaries installed, units skipped"
    return 0
  }

  log "writing systemd units"
  link_agent_binaries
  rewrite_unit_execstarts
  retire_legacy_tron_agent_units

  mkdir -p /etc/rpcnode /var/lib/rpcnode/host
  cat >/etc/rpcnode/host.env <<EOF
# written by install/agent.sh (host tip)
RPCNODE_ENV=mainnet
TRON_ENV=mainnet
TRON_SYSTEM_AGENT_LISTEN=127.0.0.1:29090
TRON_SYSTEM_AGENT_URL=http://127.0.0.1:29090
TRON_STATE_DIR=/var/lib/rpcnode/host
TRON_AGENT_STATE=/var/lib/rpcnode/host/agent-state.json
TRON_INSTANCE_FILE=/var/lib/rpcnode/host/INSTANCE.json
TRON_SNAPSHOT_ENABLED=0
TOOLKIT_DIR=${TOOLKIT_DIR}
AGENT_API_TOKEN=${AGENT_API_TOKEN}
EOF
  chmod 0600 /etc/rpcnode/host.env
  # old toolkit.env may still pin a listen addr
  if [[ -f /etc/tron/mainnet/toolkit.env ]]; then
    env_upsert /etc/tron/mainnet/toolkit.env TRON_SYSTEM_AGENT_LISTEN "127.0.0.1:29090"
    env_upsert /etc/tron/mainnet/toolkit.env TRON_SYSTEM_AGENT_URL "http://127.0.0.1:29090"
  fi

  cat >/etc/systemd/system/rpcnode-system-agent.service <<EOF
[Unit]
Description=RpcNode system-agent (host checks / control API)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-/etc/rpcnode/host.env
Environment=RPCNODE_ENV=mainnet
Environment=TRON_ENV=mainnet
Environment=TRON_SYSTEM_AGENT_LISTEN=127.0.0.1:29090
Environment=TRON_STATE_DIR=/var/lib/rpcnode/host
Environment=TOOLKIT_DIR=${TOOLKIT_DIR}
ExecStart=${BIN_DIR}/rpcnode-system-agent
ExecStartPost=/bin/sh -c 'sleep 1; logger -t rpcnode-agent "system-agent up - see /etc/rpcnode/register.txt"'
Restart=always
RestartSec=2
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
EOF

  cat >/etc/systemd/system/rpcnode-api-agent.service <<EOF
[Unit]
Description=RpcNode api-agent (RPC proxy + agent API)
After=network-online.target rpcnode-system-agent.service
Wants=network-online.target rpcnode-system-agent.service
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-/etc/rpcnode/host.env
Environment=RPCNODE_ENV=mainnet
Environment=TRON_ENV=mainnet
Environment=RPCNODE_GATEWAY_LISTEN=${AGENT_LISTEN}
Environment=TRON_GATEWAY_LISTEN=${AGENT_LISTEN}
Environment=TRON_LISTEN=${AGENT_LISTEN}
Environment=RPCNODE_PUBLIC_PORT=${AGENT_RPC_PORT}
Environment=TRON_PUBLIC_PORT=${AGENT_RPC_PORT}
Environment=RPCNODE_GATEWAY_PORT=${AGENT_RPC_PORT}
Environment=TRON_GATEWAY_PORT=${AGENT_RPC_PORT}
Environment=TRON_SYSTEM_AGENT_URL=http://127.0.0.1:29090
# kill leftover listen from an old toolkit.env
Environment=RPCNODE_AGENT_PORT=0
Environment=TRON_AGENT_PORT=0
Environment=RPCNODE_PANEL_PORT=0
Environment=TRON_PANEL_PORT=0
Environment=TRON_STATE_DIR=/var/lib/rpcnode/host
Environment=TOOLKIT_DIR=${TOOLKIT_DIR}
ExecStart=${BIN_DIR}/rpcnode-api-agent
Restart=always
RestartSec=2
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
EOF

  # old drop-in that pinned tip to a leaf public port
  if [[ -d /etc/systemd/system/rpcnode-api-agent.service.d ]]; then
    rm -f /etc/systemd/system/rpcnode-api-agent.service.d/tip-port.conf 2>/dev/null || true
  fi

  harden_agent_unit_start_limits
  ensure_nodeop_runtime_user
  install_agent_watchdog
  install_agent_file_logging

  systemctl daemon-reload

  if has_provisioned_env; then
    log "restarting tip + leaf agents"
    # tip first, then leaves
    systemctl enable rpcnode-system-agent.service rpcnode-api-agent.service 2>/dev/null || true
    systemctl restart rpcnode-system-agent.service 2>/dev/null || true
    sleep 1
    if ! systemctl restart rpcnode-api-agent.service 2>/dev/null; then
      warn "host tip api-agent failed - journalctl -u rpcnode-api-agent -n 30"
    fi
    local u restarted=0
    for u in /etc/systemd/system/rpcnode-system-agent-*.service; do
      [[ -e "$u" ]] || continue
      if leaf_unit_remove_pending "$(basename "$u")"; then
        warn "skip restart $(basename "$u") - remove-job still wiping datadir"
        systemctl disable --now "$(basename "$u")" 2>/dev/null || true
        continue
      fi
      systemctl enable "$(basename "$u")" 2>/dev/null || true
      systemctl restart "$(basename "$u")" 2>/dev/null || true
    done
    for u in /etc/systemd/system/rpcnode-api-agent-*.service; do
      [[ -e "$u" ]] || continue
      if leaf_unit_remove_pending "$(basename "$u")"; then
        warn "skip restart $(basename "$u") - remove-job still wiping datadir"
        systemctl disable --now "$(basename "$u")" 2>/dev/null || true
        continue
      fi
      systemctl enable "$(basename "$u")" 2>/dev/null || true
      if systemctl restart "$(basename "$u")"; then
        restarted=1
      else
        warn "failed to restart $(basename "$u") - journalctl -u $(basename "$u") -n 30"
      fi
    done
    # tip is enough; leaves are best-effort
    if [[ "$restarted" -ne 1 ]] && ! systemctl is-active --quiet rpcnode-api-agent.service; then
      die "Failed to restart host tip or provisioned rpcnode-api-agent-<env>.service"
    fi
    # agent.port is the tip port
    if [[ -n "${AGENT_RPC_PORT:-}" ]]; then
      printf '%s\n' "$AGENT_RPC_PORT" >/etc/rpcnode/agent.port
    fi
    if curl -fsS -H "Authorization: Bearer ${AGENT_API_TOKEN}" \
        "http://127.0.0.1:${AGENT_RPC_PORT}/healthz" >/tmp/rpcnode-host-probe.json 2>/dev/null \
      || curl -fsS -H "Authorization: Bearer ${AGENT_API_TOKEN}" \
        "http://127.0.0.1:${AGENT_RPC_PORT}/api/host" >/tmp/rpcnode-host-probe.json 2>/dev/null; then
      log "local probe OK: tip :${AGENT_RPC_PORT}"
    else
      warn "local tip probe failed on :${AGENT_RPC_PORT} - check rpcnode-api-agent journal"
    fi
    systemctl --no-pager --full status rpcnode-api-agent.service rpcnode-system-agent.service \
      rpcnode-api-agent-*.service rpcnode-system-agent-*.service 2>/dev/null || true
    systemctl restart rpcnode-agent-watchdog.service 2>/dev/null || true
    return 0
  fi

  # first install: one tip pair
  systemctl disable rpcnode-api-agent-*.service rpcnode-system-agent-*.service 2>/dev/null || true
  systemctl enable rpcnode-system-agent.service rpcnode-api-agent.service
  log "starting agent units (enabled for reboot autostart)"
  if ! systemctl restart rpcnode-system-agent.service; then
    warn "system-agent failed to start - see journalctl -u rpcnode-system-agent"
  fi
  sleep 1
  if ! systemctl restart rpcnode-api-agent.service; then
    warn "api-agent failed - recent journal:"
    journalctl -u rpcnode-api-agent.service -n 30 --no-pager 2>/dev/null || true
    die "Failed to start rpcnode-api-agent.service (often port still busy or bad TRON_PANEL_PORT in toolkit.env)"
  fi
  systemctl restart rpcnode-agent-watchdog.service 2>/dev/null || true
  # extra system-agent from an old ExecStart path
  assert_single_system_agent
  local en_api en_sys
  en_api="$(systemctl is-enabled rpcnode-api-agent.service 2>/dev/null || echo unknown)"
  en_sys="$(systemctl is-enabled rpcnode-system-agent.service 2>/dev/null || echo unknown)"
  log "autostart: api-agent=${en_api} system-agent=${en_sys}"
  if [[ "$en_api" != "enabled" || "$en_sys" != "enabled" ]]; then
    warn "units not enabled for reboot - run: systemctl enable rpcnode-api-agent rpcnode-system-agent"
  fi
  # same path the panel hits
  if curl -fsS -H "Authorization: Bearer ${AGENT_API_TOKEN}" \
      "http://127.0.0.1:${AGENT_RPC_PORT}/api/host" >/tmp/rpcnode-host-probe.json 2>/dev/null; then
    log "local probe OK: GET /api/host"
  else
    warn "local probe failed - check: journalctl -u rpcnode-api-agent -n 50"
  fi
  systemctl --no-pager --full status rpcnode-system-agent.service rpcnode-api-agent.service || true
}

# systemd default StartLimitBurst + RestartSec=2 ends in start-limit-hit.
harden_agent_unit_start_limits() {
  local f changed=0
  for f in \
    /etc/systemd/system/rpcnode-api-agent.service \
    /etc/systemd/system/rpcnode-system-agent.service \
    /etc/systemd/system/rpcnode-api-agent-*.service \
    /etc/systemd/system/rpcnode-system-agent-*.service
  do
    [[ -f "$f" ]] || continue
    if grep -qE '^StartLimitIntervalSec=0' "$f" 2>/dev/null; then
      continue
    fi
    if grep -qE '^\[Unit\]' "$f" 2>/dev/null; then
      # after [Unit]; awk so it works without GNU sed
      awk '
        BEGIN { done=0 }
        /^\[Unit\]/ && !done { print; print "StartLimitIntervalSec=0"; done=1; next }
        { print }
      ' "$f" >"${f}.rpcnode.tmp" && mv "${f}.rpcnode.tmp" "$f"
      changed=1
      log "StartLimitIntervalSec=0 -> $(basename "$f")"
    fi
  done
  [[ "$changed" -eq 1 ]] || true
}

install_agent_watchdog() {
  local src="" dest="${BIN_DIR}/rpcnode-agent-watchdog"
  mkdir -p "$BIN_DIR" /var/lib/rpcnode/watchdog
  # next to agent.sh, or download from the same prefix
  if [[ -n "${LOCAL_ARTIFACT_DIR}" && -f "${LOCAL_ARTIFACT_DIR}/rpcnode-agent-watchdog.sh" ]]; then
    cp -f "${LOCAL_ARTIFACT_DIR}/rpcnode-agent-watchdog.sh" "$dest"
  elif [[ -f "$(dirname "$0")/rpcnode-agent-watchdog.sh" ]]; then
    cp -f "$(dirname "$0")/rpcnode-agent-watchdog.sh" "$dest"
  elif command -v curl >/dev/null 2>&1 \
    && curl -fsSL "${INSTALL_BASE_URL}/rpcnode-agent-watchdog.sh" -o "$dest" 2>/dev/null; then
    :
  else
    for src in \
      "${TOOLKIT_DIR}/install/rpcnode-agent-watchdog.sh" \
      "${INSTALL_ROOT}/install/rpcnode-agent-watchdog.sh"
    do
      [[ -f "$src" ]] || continue
      cp -f "$src" "$dest"
      break
    done
  fi
  if [[ ! -f "$dest" ]]; then
    warn "watchdog script missing - skip rpcnode-agent-watchdog unit"
    return 0
  fi
  chmod 755 "$dest"

  cat >/etc/systemd/system/rpcnode-agent-watchdog.service <<EOF
[Unit]
Description=RpcNode agent watchdog (tip + leaf restart)
After=network-online.target rpcnode-api-agent.service
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
ExecStart=${dest}
Restart=always
RestartSec=5
Nice=10

[Install]
WantedBy=multi-user.target
EOF
  systemctl enable rpcnode-agent-watchdog.service 2>/dev/null || true
  systemctl restart rpcnode-agent-watchdog.service 2>/dev/null || \
    warn "rpcnode-agent-watchdog failed to start - journalctl -u rpcnode-agent-watchdog"
  log "watchdog: rpcnode-agent-watchdog.service enabled"
}

install_agent_file_logging() {
  local logdir=/var/log/rpcnode
  local conf=/etc/logrotate.d/rpcnode-agents
  local f base dropdir n=0
  mkdir -p "$logdir"
  touch /var/log/rpcnode.log 2>/dev/null || true
  chmod 0640 /var/log/rpcnode.log 2>/dev/null || true
  cat >"$conf" <<'EOF'
# /etc/logrotate.d/rpcnode-agents
/var/log/rpcnode.log
/var/log/rpcnode/*.log {
	size 100M
	rotate 7
	compress
	delaycompress
	missingok
	notifempty
	copytruncate
	create 0640 root root
}
EOF
  log "logrotate -> $conf (size 100M, rotate 7, copytruncate)"

  for f in \
    /etc/systemd/system/rpcnode-api-agent.service \
    /etc/systemd/system/rpcnode-system-agent.service \
    /etc/systemd/system/rpcnode-agent-watchdog.service \
    /etc/systemd/system/rpcnode-api-agent-*.service \
    /etc/systemd/system/rpcnode-system-agent-*.service
  do
    [[ -f "$f" ]] || continue
    base="$(basename "$f" .service)"
    dropdir="/etc/systemd/system/$(basename "$f").d"
    mkdir -p "$dropdir"
    touch "${logdir}/${base}.log" 2>/dev/null || true
    chmod 0640 "${logdir}/${base}.log" 2>/dev/null || true
    cat >"${dropdir}/file-log.conf" <<EOF
[Service]
# stdout/stderr -> file; logrotate uses copytruncate
StandardOutput=append:${logdir}/${base}.log
StandardError=append:${logdir}/${base}.log
EOF
    n=$((n + 1))
  done
  log "agent file logs: drop-ins for ${n} unit(s) under ${logdir}/"
}

assert_single_system_agent() {
  local -a pids=()
  local pid unit_main
  unit_main="$(systemctl show -p MainPID --value rpcnode-system-agent.service 2>/dev/null || echo 0)"
  while read -r pid; do
    [[ -n "$pid" ]] && pids+=("$pid")
  done < <(pgrep -f '/(rpcnode|tron)-system-agent( |$)' 2>/dev/null || true)
  [[ "${#pids[@]}" -le 1 ]] && return 0
  warn "multiple system-agent PIDs: ${pids[*]} - keeping MainPID=${unit_main}, killing extras"
  for pid in "${pids[@]}"; do
    [[ "$pid" == "$unit_main" ]] && continue
    kill "$pid" 2>/dev/null || true
  done
  sleep 1
  for pid in "${pids[@]}"; do
    [[ "$pid" == "$unit_main" ]] && continue
    kill -9 "$pid" 2>/dev/null || true
  done
}

print_install_summary() {
  local en_api en_sys act_api act_sys
  en_api="$(systemctl is-enabled rpcnode-api-agent.service 2>/dev/null || echo n/a)"
  en_sys="$(systemctl is-enabled rpcnode-system-agent.service 2>/dev/null || echo n/a)"
  act_api="$(systemctl is-active rpcnode-api-agent.service 2>/dev/null || echo n/a)"
  act_sys="$(systemctl is-active rpcnode-system-agent.service 2>/dev/null || echo n/a)"

  cat <<EOF

installed.
  host:    ${HOST_ID}  ${HOST_OS}/${HOST_ARCH}
  source:  ${BINARY_SOURCE}
  port:    ${AGENT_RPC_PORT} (${PORT_PICK_REASON:-chosen})

  Agent URL :  ${AGENT_URL}
  Agent key :  ${AGENT_API_TOKEN}

paste those into the panel (Servers -> Add server).
  rpcnode-api-agent:    ${en_api} / ${act_api}
  rpcnode-system-agent: ${en_sys} / ${act_sys}

EOF
}

print_rpcnode_banner() {
  cat <<'EOF'

  ____  ____   ____ _   _  ___  ____  _____
 |  _ \|  _ \ / ___| \ | |/ _ \|  _ \| ____|
 | |_) | |_) | |   |  \| | | | | | | |  _|
 |  _ <|  __/| |___| |\  | |_| | |_| | |___
 |_| \_\_|    \____|_| \_|\___/|____/|_____|
              host agent installer

EOF
}

agents_already_installed() {
  [[ -x "${BIN_DIR}/rpcnode-api-agent" ]] && return 0
  [[ -f /etc/systemd/system/rpcnode-api-agent.service ]] && return 0
  [[ -f /etc/rpcnode/agent.port ]] && return 0
  [[ -d /etc/rpcnode/instances.d ]] && compgen -G '/etc/rpcnode/instances.d/*.json' >/dev/null && return 0
  if command -v systemctl >/dev/null 2>&1; then
    systemctl is-active --quiet rpcnode-api-agent.service 2>/dev/null && return 0
  fi
  # any RpcNode unit left on disk
  if compgen -G '/etc/systemd/system/*.service' >/dev/null; then
    if grep -l 'RpcNode' /etc/systemd/system/*.service >/dev/null 2>&1; then
      return 0
    fi
  fi
  return 1
}

purge_unit() {
  local u="${1%.service}.service"
  [[ -n "$u" && "$u" != ".service" ]] || return 0
  systemctl disable --now "$u" 2>/dev/null || true
  systemctl stop "$u" 2>/dev/null || true
  systemctl kill -s SIGKILL --kill-who=all "$u" 2>/dev/null || true
  rm -f "/etc/systemd/system/${u}" "/lib/systemd/system/${u}"
  rm -rf "/etc/systemd/system/${u}.d"
}

run_uninstall_agents_only() {
  local sibling src
  sibling="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/uninstall-agents.sh"
  if [[ -f "$sibling" ]]; then
    log "uninstall-agents: running $sibling"
    bash "$sibling"
    return 0
  fi
  src="${INSTALL_BASE_URL}/uninstall-agents.sh"
  log "uninstall-agents: fetching $src"
  curl -fsSL "$src" | bash
}

uninstall_agents() {
  log "full uninstall: agents + fullnode units + datadirs"
  local u f net env

  if [[ "$HOST_OS" == "linux" ]] && command -v systemctl >/dev/null 2>&1; then
    # watchdog first so it doesn't bounce units we just stopped
    purge_unit rpcnode-agent-watchdog.service
    shopt -s nullglob
    for u in /etc/systemd/system/rpcnode-*.service; do
      purge_unit "$(basename "$u")"
    done
    shopt -u nullglob

    # fullnode units from inventory + anything tagged RpcNode
    shopt -s nullglob
    for f in /etc/rpcnode/instances.d/*.json /etc/rpcnode/nodes/*.json; do
      [[ -f "$f" ]] || continue
      net="$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print((d.get("network") or "").strip().lower())' "$f" 2>/dev/null || true)"
      env="$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print((d.get("env") or "").strip().lower())' "$f" 2>/dev/null || true)"
      if [[ -n "$net" && -n "$env" ]]; then
        for u in \
          "${net}-${env}.service" \
          "tron-${env}.service" \
          "tron-${env}-snapshot.service" \
          "tron-${env}-updater.service" \
          "tron-${env}-gateway.service" \
          "ethereum-geth-${env}.service" \
          "ethereum-lighthouse-${env}.service" \
          "base-reth-node.service" \
          "base-consensus.service" \
          "optimism-${env}.service" \
          "optimism-op-node-${env}.service" \
          "base-${env}.service" \
          "base-consensus-${env}.service" \
          "cardano-${env}.service" \
          "cardano-ogmios-${env}.service" \
          "sui-${env}.service" \
          "sui-${env}-snapshot.service" \
          "hl-visor-${env}.service" \
          "validator.service" \
          "mytoncore.service" \
          "ton_http_api.service"
        do
          purge_unit "$u"
        done
      fi
      # unit names from INSTANCE.json if present
      python3 -c '
import json,sys
d=json.load(open(sys.argv[1]))
for k in ("node_unit","service","systemd_unit"):
  v=d.get(k)
  if isinstance(v,str) and v.strip():
    print(v.strip())
for x in d.get("units") or []:
  if isinstance(x,str) and x.strip():
    print(x.strip())
' "$f" 2>/dev/null | while read -r uu; do
        purge_unit "$uu"
      done
    done
    # leftover units with Description=RpcNode
    for u in /etc/systemd/system/*.service; do
      grep -q 'RpcNode' "$u" 2>/dev/null || continue
      purge_unit "$(basename "$u")"
    done
    shopt -u nullglob

    if command -v pgrep >/dev/null 2>&1; then
      local pid
      for pid in $(pgrep -f '/opt/rpcnode/bin/rpcnode-(api|system)-agent' 2>/dev/null || true); do
        kill -9 "$pid" 2>/dev/null || true
      done
      for pid in $(pgrep -f '/opt/rpcnode/bin/rpcnode-agent-watchdog' 2>/dev/null || true); do
        kill -9 "$pid" 2>/dev/null || true
      done
    fi
    systemctl daemon-reload 2>/dev/null || true
    systemctl reset-failed 2>/dev/null || true
  fi

  # datadirs for each inventoried network/env
  shopt -s nullglob
  for f in /etc/rpcnode/instances.d/*.json /etc/rpcnode/nodes/*.json; do
    [[ -f "$f" ]] || continue
    net="$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print((d.get("network") or "").strip().lower())' "$f" 2>/dev/null || true)"
    env="$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print((d.get("env") or "").strip().lower())' "$f" 2>/dev/null || true)"
    [[ -n "$net" && -n "$env" ]] || continue
    rm -rf \
      "/data/${net}/${env}" "/etc/${net}/${env}" "/opt/${net}/${env}" \
      "/var/lib/rpcnode/${net}-${env}" "/var/log/${net}/${env}" \
      "/run/${net}-${env}" "/var/log/${net}/${env}-snapshot.log" \
      "/var/log/${net}/${env}-bootstrap.log" 2>/dev/null || true
    # empty parents
    rmdir "/data/${net}" "/etc/${net}" "/opt/${net}" "/var/log/${net}" 2>/dev/null || true
  done
  shopt -u nullglob

  # leave a co-hosted panel.db alone
  rm -f "${BIN_DIR}/rpcnode-api-agent" "${BIN_DIR}/rpcnode-system-agent" "${BIN_DIR}/rpcnode-agent-watchdog"
  rm -f "${BIN_DIR}/tron-api-agent" "${BIN_DIR}/tron-system-agent"
  rm -f "${BIN_DIR}"/rpcnode-system-agent.bak.* 2>/dev/null || true
  rm -rf /etc/rpcnode
  rm -rf /var/log/rpcnode
  rm -f /etc/logrotate.d/rpcnode-agents
  if [[ -d /var/lib/rpcnode ]]; then
    find /var/lib/rpcnode -mindepth 1 -maxdepth 1 \
      ! -name 'panel' ! -name 'panel.db' 2>/dev/null | while read -r d; do
      rm -rf "$d"
    done
  fi

  cat <<'EOF'

OK  RpcNode FULL uninstall complete.
    Removed: all rpcnode* agents, RpcNode fullnode units, matching /data|/etc|/opt dirs.
    Re-install:  curl -fsSL "https://toolkit.rpcnode.dev/install/agent.sh" | sudo bash

EOF
}

prompt_existing_install() {
  # curl|bash: stdin is the script, so read from the tty
  local mode="${INSTALL_ACTION:-}"
  mode="$(printf '%s' "$mode" | tr '[:upper:]' '[:lower:]')"
  case "$mode" in
    uninstall-agents|uninstall_agents|agents-only|agents)
      INSTALL_ACTION=uninstall-agents
      return 0
      ;;
    uninstall|reinstall|cancel|install) INSTALL_ACTION="$mode"; return 0 ;;
  esac
  if [[ ! -r /dev/tty ]] || ! : >/dev/tty 2>/dev/null; then
    log "RpcNode already present - non-interactive -> reinstall (use --uninstall-agents to drop agents only)"
    INSTALL_ACTION=reinstall
    return 0
  fi
  cat <<'EOF' >/dev/tty

RpcNode is already installed on this host.

  [1] Install / reinstall / upgrade agents   (keep fullnodes + datadirs)
  [2] Remove agents only           (tip + leaves + watchdog; keep fullnodes)
  [3] FULL uninstall               (agents + fullnode units + /data|/etc|/opt)
  [4] Cancel

EOF
  local choice=""
  printf 'Choose [1/2/3/4]: ' >/dev/tty
  read -r choice </dev/tty || true
  case "$(printf '%s' "$choice" | tr '[:upper:]' '[:lower:]')" in
    1|i|install|r|reinstall|upgrade|"") INSTALL_ACTION=reinstall ;;
    2|a|agents|uninstall-agents|agents-only) INSTALL_ACTION=uninstall-agents ;;
    3|uninstall|remove|d|delete|full) INSTALL_ACTION=uninstall ;;
    4|c|cancel|n|no|q) INSTALL_ACTION=cancel ;;
    *)
      warn "unknown choice - cancelling"
      INSTALL_ACTION=cancel
      ;;
  esac
}

print_rpcnode_banner
detect_platform

if agents_already_installed; then
  prompt_existing_install
  case "${INSTALL_ACTION}" in
    uninstall-agents)
      run_uninstall_agents_only
      exit 0
      ;;
    uninstall)
      uninstall_agents
      exit 0
      ;;
    cancel)
      log "cancelled - no changes"
      exit 0
      ;;
    reinstall|install|"")
      log "install / reinstall / upgrade existing agents"
      ;;
  esac
elif [[ "${INSTALL_ACTION}" == "uninstall" || "${INSTALL_ACTION}" == "uninstall-agents" ]]; then
  # wipe even if we didn't detect a full install (partial leftover)
  if [[ "${INSTALL_ACTION}" == "uninstall-agents" ]]; then
    run_uninstall_agents_only
  else
    uninstall_agents
  fi
  exit 0
fi

stop_existing_agents
pick_agent_port
ensure_runtime_layout
install_agents
write_host_id
install_systemd
print_install_summary
