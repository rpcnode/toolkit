#!/usr/bin/env bash
# Per-env default ports — one stack per env, dual public listeners on api-agent.
#
# Canonical Go RPC / agent (preferred; see deploy/nodes/tron/DESIGN.md):
# | env     | Go RPC (PUBLIC) | Agent API | java-tron HTTP | P2P   | system-agent |
# |---------|-----------------|-----------|----------------|-------|--------------|
# | mainnet | 39090           | 39190     | 18090          | 18888 | 29090        |
# | nile    | 39091           | 39191     | 18091          | 18889 | 29091        |
# | shasta  | 39092           | 39192     | 18092          | 18890 | 29092        |
#
# Legacy setup.sh defaults below still use 8090/8091/8092 as PUBLIC for older
# single-stack installs. Provisioned multi-env hosts MUST use 3909x/3919x.
# Aux java-tron ports (sol/PBFT HTTP disabled, gRPC, metrics): DESIGN.md.
#
# Panel stays :8093 for every env. Setup auto-bumps RPCNODE_PANEL_PORT (legacy
# TRON_PANEL_PORT) only when 8093 is already bound (second stack on the same host).
# Prefer one primary panel on :8093 and use the env switcher for other envs' state.
#
# Host-network extras (bridge mode keeps these internal to compose):
#   system-agent listen  TRON_SYSTEM_AGENT_PORT  → 29090 / 29091 / 29092
#   NEVER put solidity/PBFT HTTP on 18091/18092 — those are Nile/Shasta FullNode HTTP.
#
# Canonical product listen vars: RPCNODE_PUBLIC_PORT / RPCNODE_GATEWAY_PORT (alias)
# / RPCNODE_PANEL_PORT. Legacy TRON_* names still accepted (one release).
# nginx is optional (compose profile "edge") — default is Go-only dual listen.

# Returns: rpc panel node_http p2p system_agent
default_ports_for_env() {
  local env="${1:-mainnet}"
  case "$env" in
    nile)   echo "8091 8093 18091 18889 29091" ;;
    shasta) echo "8092 8093 18092 18890 29092" ;;
    *)      echo "8090 8093 18090 18888 29090" ;; # mainnet
  esac
}

# Apply defaults only when vars are unset/empty (after load_env_file).
apply_env_port_defaults() {
  # Prefer RPCNODE_*; fall back to deprecated TRON_*.
  : "${RPCNODE_ENV:=${TRON_ENV:-mainnet}}"
  : "${TRON_ENV:=$RPCNODE_ENV}"
  local env="${RPCNODE_ENV}"
  local rpc panel node p2p sys
  read -r rpc panel node p2p sys <<<"$(default_ports_for_env "$env")"

  : "${RPCNODE_PUBLIC_PORT:=${TRON_PUBLIC_PORT:-}}"
  : "${RPCNODE_GATEWAY_PORT:=${TRON_GATEWAY_PORT:-}}"
  : "${RPCNODE_PANEL_PORT:=${TRON_PANEL_PORT:-}}"
  : "${RPCNODE_PUBLIC_BASE:=${TRON_PUBLIC_BASE:-}}"
  : "${RPCNODE_PANEL_BASE:=${TRON_PANEL_BASE:-}}"
  : "${RPCNODE_GATEWAY_LISTEN:=${TRON_GATEWAY_LISTEN:-}}"

  # Aliases: GATEWAY = PUBLIC = RPC port
  if [[ -n "${RPCNODE_PUBLIC_PORT:-}" && -z "${RPCNODE_GATEWAY_PORT:-}" ]]; then
    RPCNODE_GATEWAY_PORT="$RPCNODE_PUBLIC_PORT"
  fi
  if [[ -n "${RPCNODE_GATEWAY_PORT:-}" && -z "${RPCNODE_PUBLIC_PORT:-}" ]]; then
    RPCNODE_PUBLIC_PORT="$RPCNODE_GATEWAY_PORT"
  fi

  : "${RPCNODE_PUBLIC_PORT:=$rpc}"
  : "${RPCNODE_GATEWAY_PORT:=$RPCNODE_PUBLIC_PORT}"
  : "${RPCNODE_PANEL_PORT:=$panel}"
  : "${TRON_NODE_HTTP_PORT:=$node}"
  : "${TRON_P2P_PORT:=$p2p}"
  : "${TRON_SYSTEM_AGENT_PORT:=$sys}"

  # Keep legacy names in sync for older scripts (tronctl / setup.sh).
  TRON_PUBLIC_PORT="$RPCNODE_PUBLIC_PORT"
  TRON_GATEWAY_PORT="$RPCNODE_GATEWAY_PORT"
  TRON_PANEL_PORT="$RPCNODE_PANEL_PORT"
  TRON_PUBLIC_BASE="${RPCNODE_PUBLIC_BASE:-${TRON_PUBLIC_BASE:-}}"
  TRON_PANEL_BASE="${RPCNODE_PANEL_BASE:-${TRON_PANEL_BASE:-}}"
  TRON_GATEWAY_LISTEN="${RPCNODE_GATEWAY_LISTEN:-${TRON_GATEWAY_LISTEN:-0.0.0.0}}"
  RPCNODE_GATEWAY_LISTEN="$TRON_GATEWAY_LISTEN"
  # Legacy name — in Go-only mode api-agent listens on PUBLIC+PANEL directly.
  : "${TRON_API_AGENT_PORT:=$RPCNODE_PUBLIC_PORT}"
}

port_is_busy() {
  local port="$1"
  [[ -z "$port" ]] && return 1
  if command -v ss >/dev/null 2>&1; then
    ss -lnt 2>/dev/null | grep -qE ":${port}\\b" && return 0
    return 1
  fi
  if command -v lsof >/dev/null 2>&1; then
    lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 && return 0
    return 1
  fi
  if command -v netstat >/dev/null 2>&1; then
    netstat -an 2>/dev/null | grep -qE "(\\.|:)(${port})\\s.*LISTEN" && return 0
    return 1
  fi
  return 1
}

# Find next free port starting at $1 (inclusive), scan up to +50.
suggest_free_port() {
  local start="${1:?}"
  local i=0 p
  p="$start"
  while (( i < 50 )); do
    if ! port_is_busy "$p"; then
      printf '%s' "$p"
      return 0
    fi
    p=$((p + 1))
    i=$((i + 1))
  done
  printf '%s' "$start"
  return 1
}

# Rebuild PUBLIC_BASE host:port from current RPC port (keep scheme+host).
rebuild_public_base_port() {
  local base="${1:-}"
  local port="${2:-${RPCNODE_PUBLIC_PORT:-${TRON_PUBLIC_PORT:-${RPCNODE_GATEWAY_PORT:-${TRON_GATEWAY_PORT:-8090}}}}}"
  [[ -z "$base" ]] && return 0
  base="${base%/}"
  if [[ "$base" =~ ^(https?://[^/:]+)(:[0-9]+)?$ ]]; then
    printf '%s:%s' "${BASH_REMATCH[1]}" "$port"
  else
    printf '%s' "$base"
  fi
}

# Derive panel URL from RPC public base + RPCNODE_PANEL_PORT (legacy TRON_PANEL_*).
derive_panel_base() {
  local base="${1:-${RPCNODE_PUBLIC_BASE:-${TRON_PUBLIC_BASE:-}}}"
  local port="${2:-${RPCNODE_PANEL_PORT:-${TRON_PANEL_PORT:-8093}}}"
  if [[ -n "${RPCNODE_PANEL_BASE:-${TRON_PANEL_BASE:-}}" ]]; then
    printf '%s' "${RPCNODE_PANEL_BASE:-$TRON_PANEL_BASE}"
    return 0
  fi
  [[ -z "$base" ]] && return 0
  rebuild_public_base_port "$base" "$port"
}

print_port_scheme() {
  cat <<'EOF'
Per-env host ports (Go api-agent dual listen — NOT path-routing on one port):

  env       RPC (PUBLIC)  Panel         java-tron HTTP   P2P
  --------  ------------  ------------  ---------------  -----
  mainnet   8090          8093          18090            18888
  nile      8091          8093          18091            18889
  shasta    8092          8093          18092            18890

  RPC port   = catch-all FullNode HTTP proxy (no auth); differs per env
  Panel port = /status + /api* (htpasswd); default :8093 for all envs
               (setup bumps only if 8093 is already busy)
  nginx optional: docker compose --profile edge up -d
EOF
}
