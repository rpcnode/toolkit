#!/usr/bin/env bash
# Instance registry — durable “where is this toolkit running” records.
# Default: /etc/rpcnode/instances.d/tron-<env>.json
# Also writes INSTANCE.json under /var/lib/rpcnode/… for the status UI.

RPCNODE_REGISTRY_DIR="${RPCNODE_REGISTRY_DIR:-/etc/rpcnode/instances.d}"
RPCNODE_TOOLKIT_NAME="${RPCNODE_TOOLKIT_NAME:-RpcNode toolkit}"
RPCNODE_TOOLKIT_VERSION="${RPCNODE_TOOLKIT_VERSION:-0.3.3}"

instance_id() {
  printf 'tron-%s' "${TRON_ENV:-mainnet}"
}

instance_registry_path() {
  printf '%s/%s.json' "$RPCNODE_REGISTRY_DIR" "$(instance_id)"
}

instance_sidecar_path() {
  printf '%s' "${TRON_INSTANCE_FILE:-/var/lib/rpcnode/tron-${TRON_ENV:-mainnet}/INSTANCE.json}"
}

hostname_fqdn() {
  hostname -f 2>/dev/null || hostname 2>/dev/null || echo "unknown"
}

detect_public_base() {
  if [[ -n "${RPCNODE_PUBLIC_BASE:-${TRON_PUBLIC_BASE:-}}" ]]; then
    printf '%s' "${RPCNODE_PUBLIC_BASE:-$TRON_PUBLIC_BASE}"
    return 0
  fi
  local ip
  ip="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')"
  if [[ -n "$ip" ]]; then
    printf 'http://%s:%s' "$ip" "${RPCNODE_PUBLIC_PORT:-${TRON_PUBLIC_PORT:-${RPCNODE_GATEWAY_PORT:-${TRON_GATEWAY_PORT:-8090}}}}"
  else
    printf 'http://127.0.0.1:%s' "${RPCNODE_PUBLIC_PORT:-${TRON_PUBLIC_PORT:-${RPCNODE_GATEWAY_PORT:-${TRON_GATEWAY_PORT:-8090}}}}"
  fi
}

_svc_name() {
  local n="$1"
  [[ -z "$n" ]] && return 0
  [[ "$n" == *.service ]] && printf '%s' "$n" || printf '%s.service' "$n"
}

# Args (optional): mode=fresh|existing|gateway-only
write_instance_registry() {
  local mode="${1:-existing}"
  local id path sidecar base host now
  local s1 s2 s3 s4 s5
  require_jq
  id="$(instance_id)"
  path="$(instance_registry_path)"
  sidecar="$(instance_sidecar_path)"
  base="$(detect_public_base)"
  host="$(hostname_fqdn)"
  now="$(ts)"

  s1="$(_svc_name "${TRON_SYSTEM_SERVICE:-tron-${TRON_ENV}-system}")"
  s2="$(_svc_name "${TRON_API_SERVICE:-tron-${TRON_ENV}-api}")"
  s3="$(_svc_name "${TRON_SERVICE:-}")"
  s4="$(_svc_name "${TRON_SNAPSHOT_SERVICE:-}")"
  s5="$(_svc_name "${TRON_UPDATER_SERVICE:-}")"

  mkdir -p "$RPCNODE_REGISTRY_DIR" "$(dirname "$sidecar")"

  local panel_port panel_base
  panel_port="${RPCNODE_PANEL_PORT:-${TRON_PANEL_PORT:-8093}}"
  panel_base="${RPCNODE_PANEL_BASE:-${TRON_PANEL_BASE:-}}"
  if [[ -z "$panel_base" ]]; then
    panel_base="$(rebuild_public_base_port "$base" "$panel_port" 2>/dev/null || printf 'http://127.0.0.1:%s' "$panel_port")"
  fi

  local doc
  doc="$(jq -n \
    --arg id "$id" \
    --arg env "${RPCNODE_ENV:-$TRON_ENV}" \
    --arg host "$host" \
    --arg base "$base" \
    --arg panel_base "$panel_base" \
    --arg gw "${RPCNODE_GATEWAY_LISTEN:-$TRON_GATEWAY_LISTEN}:${RPCNODE_PUBLIC_PORT:-${TRON_PUBLIC_PORT:-$TRON_GATEWAY_PORT}}" \
    --arg node "${TRON_NODE_HTTP_HOST}:${TRON_NODE_HTTP_PORT}" \
    --argjson gw_port "${RPCNODE_PUBLIC_PORT:-${TRON_PUBLIC_PORT:-${RPCNODE_GATEWAY_PORT:-${TRON_GATEWAY_PORT:-8090}}}}" \
    --argjson public_port "${RPCNODE_PUBLIC_PORT:-${TRON_PUBLIC_PORT:-${RPCNODE_GATEWAY_PORT:-${TRON_GATEWAY_PORT:-8090}}}}" \
    --argjson panel_port "$panel_port" \
    --argjson node_port "${TRON_NODE_HTTP_PORT:-18090}" \
    --argjson p2p "${TRON_P2P_PORT:-18888}" \
    --argjson system_agent_port "${TRON_SYSTEM_AGENT_PORT:-0}" \
    --arg data "$TRON_DATA" \
    --arg output "$TRON_OUTPUT" \
    --arg opt "$TRON_OPT" \
    --arg etc "$TRON_ETC" \
    --arg toolkit "$TOOLKIT_DIR" \
    --arg state "${TRON_AGENT_STATE:-}" \
    --arg mode "$mode" \
    --arg managed "$RPCNODE_TOOLKIT_NAME" \
    --arg ver "$RPCNODE_TOOLKIT_VERSION" \
    --arg now "$now" \
    --arg s1 "$s1" --arg s2 "$s2" --arg s3 "$s3" --arg s4 "$s4" --arg s5 "$s5" \
    '{
      id: $id,
      network: "tron",
      env: $env,
      hostname: $host,
      public_base_url: $base,
      public_base: $base,
      panel_base_url: $panel_base,
      gateway_listen: $gw,
      gateway_port: $gw_port,
      public_port: $public_port,
      panel_port: $panel_port,
      node_http: $node,
      node_http_port: $node_port,
      p2p_port: $p2p,
      system_agent_port: (if $system_agent_port > 0 then $system_agent_port else null end),
      data_dir: $data,
      output_dir: $output,
      opt_dir: $opt,
      etc_dir: $etc,
      toolkit_root: $toolkit,
      services: [$s1,$s2,$s3,$s4,$s5] | map(select(length > 0)),
      mode: $mode,
      runtime: "docker",
      agents: ["system-agent","api-agent"],
      edge: "optional-nginx-profile-edge",
      state_file: $state,
      managed_by: $managed,
      version: $ver,
      installed_at: $now,
      status_url: ($panel_base + "/status"),
      status_json_url: ($panel_base + "/status.json")
    }')"

  printf '%s\n' "$doc" >"$path"
  printf '%s\n' "$doc" >"$sidecar"
  ok "instance registry: $path"
  ok "instance sidecar:  $sidecar"
}

list_instance_files() {
  if [[ -d "$RPCNODE_REGISTRY_DIR" ]]; then
    find "$RPCNODE_REGISTRY_DIR" -maxdepth 1 -type f -name 'tron-*.json' 2>/dev/null | sort
  fi
}

cmd_instances() {
  local files f
  require_jq
  files="$(list_instance_files || true)"
  if [[ -z "$files" ]]; then
    warn "no instances in $RPCNODE_REGISTRY_DIR"
    info "run: sudo rpcnodectl setup   # or install"
    return 0
  fi
  echo "=== RpcNode toolkit instances ($RPCNODE_REGISTRY_DIR) ==="
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    jq -r --arg f "$f" '
      "\n• \(.id // "?")  (\(.env // "?"))",
      "  hostname:     \(.hostname // "")",
      "  public:       \(.public_base_url // "")",
      "  status:       \(.status_url // "")",
      "  gateway:      \(.gateway_listen // "")  (port=\(.gateway_port // .public_port // "?"))",
      "  node_http:    \(.node_http // "")  (port=\(.node_http_port // "?"))",
      "  data_dir:     \(.data_dir // "")",
      "  opt_dir:      \(.opt_dir // "")",
      "  etc_dir:      \(.etc_dir // "")",
      "  toolkit_root: \(.toolkit_root // "")",
      "  mode:         \(.mode // "")",
      "  runtime:      \(.runtime // "")",
      "  managed_by:   \(.managed_by // "")",
      "  version:      \(.version // "")",
      "  installed_at: \(.installed_at // "")",
      "  file:         \($f)"
    ' "$f"
  done <<<"$files"
}

cmd_where() {
  local id path sidecar
  id="$(instance_id)"
  path="$(instance_registry_path)"
  sidecar="$(instance_sidecar_path)"
  echo "=== this host · $(hostname_fqdn) · env=${TRON_ENV} ==="
  if [[ -f "$path" ]]; then
    cat "$path"
  elif [[ -f "$sidecar" ]]; then
    warn "registry missing, showing sidecar $sidecar"
    cat "$sidecar"
  else
    warn "no registry for $id"
    info "expected: $path"
    info "run: sudo TRON_ENV=${TRON_ENV} rpcnodectl setup --write-registry"
    echo
    echo "resolved paths (not yet registered):"
    cmd_paths
    return 1
  fi
  echo
  cmd_instances
}
