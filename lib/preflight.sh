#!/usr/bin/env bash
# Host preflight for rpcnodectl setup — non-blocking suitability report.
# Recommended minimums for mainnet-style FullNode + toolkit agents.

# Defaults (override via env before setup if needed).
PREFLIGHT_MIN_CPUS="${PREFLIGHT_MIN_CPUS:-8}"
PREFLIGHT_WARN_CPUS="${PREFLIGHT_WARN_CPUS:-16}"
PREFLIGHT_MIN_RAM_GB="${PREFLIGHT_MIN_RAM_GB:-32}"
PREFLIGHT_WARN_RAM_GB="${PREFLIGHT_WARN_RAM_GB:-48}"
PREFLIGHT_MIN_DISK_GB="${PREFLIGHT_MIN_DISK_GB:-800}"
PREFLIGHT_WARN_DISK_GB="${PREFLIGHT_WARN_DISK_GB:-1500}"
PREFLIGHT_MIN_ARCH="${PREFLIGHT_MIN_ARCH:-x86_64|amd64|aarch64|arm64}"

# Counters filled by run_preflight
PREFLIGHT_OK=0
PREFLIGHT_WARN=0
PREFLIGHT_FAIL=0
PREFLIGHT_ROWS=() # "LEVEL|name|detail|recommend"

_preflight_add() {
  local level="$1" name="$2" detail="$3" recommend="${4:-}"
  PREFLIGHT_ROWS+=("${level}|${name}|${detail}|${recommend}")
  case "$level" in
    OK) PREFLIGHT_OK=$((PREFLIGHT_OK + 1)) ;;
    WARN) PREFLIGHT_WARN=$((PREFLIGHT_WARN + 1)) ;;
    FAIL) PREFLIGHT_FAIL=$((PREFLIGHT_FAIL + 1)) ;;
  esac
}

_preflight_sysctl() {
  # Prefer absolute path — sudo/minimal PATH often omits /usr/sbin (Mac → 0 cores / 0 GB).
  local key="$1"
  /usr/sbin/sysctl -n "$key" 2>/dev/null || sysctl -n "$key" 2>/dev/null || true
}

_preflight_cpus() {
  local n=0
  if [[ -f /proc/cpuinfo ]]; then
    n="$(grep -c ^processor /proc/cpuinfo 2>/dev/null || echo 0)"
  fi
  if [[ -z "${n//[^0-9]/}" || "$n" -eq 0 ]] && command -v nproc >/dev/null 2>&1; then
    n="$(nproc 2>/dev/null || echo 0)"
  fi
  if [[ -z "${n//[^0-9]/}" || "$n" -eq 0 ]]; then
    n="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 0)"
  fi
  if [[ -z "${n//[^0-9]/}" || "$n" -eq 0 ]]; then
    n="$(_preflight_sysctl hw.logicalcpu)"
  fi
  if [[ -z "${n//[^0-9]/}" || "$n" -eq 0 ]]; then
    n="$(_preflight_sysctl hw.ncpu)"
  fi
  n="${n//[^0-9]/}"
  n="${n:-0}"
  local model=""
  if [[ -f /proc/cpuinfo ]]; then
    model="$(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | sed 's/^ *//' || true)"
  fi
  [[ -z "$model" ]] && model="$(_preflight_sysctl machdep.cpu.brand_string)"
  [[ -z "$model" ]] && model="unknown"
  if (( n < PREFLIGHT_MIN_CPUS )); then
    _preflight_add FAIL "CPU cores" "${n} cores (${model})" \
      "Recommend ≥${PREFLIGHT_WARN_CPUS} (minimum ${PREFLIGHT_MIN_CPUS}) for mainnet FullNode"
  elif (( n < PREFLIGHT_WARN_CPUS )); then
    _preflight_add WARN "CPU cores" "${n} cores (${model})" \
      "For comfortable mainnet prefer ≥${PREFLIGHT_WARN_CPUS} cores"
  else
    _preflight_add OK "CPU cores" "${n} cores (${model})" ""
  fi
}

_preflight_ram() {
  local kb=0 gb=0
  if [[ -f /proc/meminfo ]]; then
    kb="$(awk '/MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)"
  else
    local bytes
    bytes="$(_preflight_sysctl hw.memsize)"
    bytes="${bytes//[^0-9]/}"
    bytes="${bytes:-0}"
    kb=$((bytes / 1024))
  fi
  kb="${kb//[^0-9]/}"
  kb="${kb:-0}"
  gb=$((kb / 1024 / 1024))
  if (( gb < PREFLIGHT_MIN_RAM_GB )); then
    _preflight_add FAIL "RAM" "${gb} GB" \
      "Recommend ≥${PREFLIGHT_WARN_RAM_GB} GB (minimum ${PREFLIGHT_MIN_RAM_GB} GB); java-tron often uses Xmx≈48g"
  elif (( gb < PREFLIGHT_WARN_RAM_GB )); then
    _preflight_add WARN "RAM" "${gb} GB" \
      "For mainnet with Xmx 48g prefer ≥${PREFLIGHT_WARN_RAM_GB} GB"
  else
    _preflight_add OK "RAM" "${gb} GB" ""
  fi
}

_preflight_disk() {
  local path="${1:-${TRON_DATA:-/data/tron/${TRON_ENV:-mainnet}}}"
  local probe="$path"
  while [[ ! -d "$probe" && "$probe" != "/" ]]; do
    probe="$(dirname "$probe")"
  done
  mkdir -p "$path" 2>/dev/null || true
  [[ -d "$path" ]] && probe="$path"
  local avail_kb=0 avail_gb=0 fstype="" src=""
  if df -Pk "$probe" >/dev/null 2>&1; then
    avail_kb="$(df -Pk "$probe" 2>/dev/null | awk 'NR==2 {print $4}')"
    src="$(df -Pk "$probe" 2>/dev/null | awk 'NR==2 {print $1}')"
  elif df -Pk / >/dev/null 2>&1; then
    avail_kb="$(df -Pk / 2>/dev/null | awk 'NR==2 {print $4}')"
    src="$(df -Pk / 2>/dev/null | awk 'NR==2 {print $1}')"
    probe="/"
  fi
  avail_kb="${avail_kb//[^0-9]/}"
  avail_kb="${avail_kb:-0}"
  avail_gb=$((avail_kb / 1024 / 1024))
  if command -v findmnt >/dev/null 2>&1; then
    fstype="$(findmnt -n -o FSTYPE --target "$probe" 2>/dev/null || true)"
  elif [[ "$(uname -s)" == "Darwin" ]]; then
    fstype="apfs"
  fi
  local rotational=""
  if [[ -n "$src" && -b "$src" ]]; then
    local base
    base="$(basename "$src")"
    base="${base%%[0-9]*}"
    if [[ -f "/sys/block/${base}/queue/rotational" ]]; then
      rotational="$(cat "/sys/block/${base}/queue/rotational" 2>/dev/null || true)"
    fi
  fi
  local disk_note="free≈${avail_gb} GB on ${path}"
  [[ "$probe" != "$path" ]] && disk_note+=" (probed ${probe})"
  [[ -n "$fstype" ]] && disk_note+=" fstype=${fstype}"
  [[ -n "$src" ]] && disk_note+=" device=${src}"
  if [[ "$rotational" == "1" ]]; then
    disk_note+=" (HDD — slow for FullNode)"
  elif [[ "$rotational" == "0" ]]; then
    disk_note+=" (SSD/NVMe)"
  fi

  if (( avail_gb < PREFLIGHT_MIN_DISK_GB )); then
    _preflight_add FAIL "Disk free" "$disk_note" \
      "Need ≥${PREFLIGHT_MIN_DISK_GB} GB free for data (prefer ≥${PREFLIGHT_WARN_DISK_GB} GB); LevelDB snapshot ~1TB+"
  elif (( avail_gb < PREFLIGHT_WARN_DISK_GB )); then
    _preflight_add WARN "Disk free" "$disk_note" \
      "For mainnet snapshot prefer ≥${PREFLIGHT_WARN_DISK_GB} GB free"
  else
    _preflight_add OK "Disk free" "$disk_note" ""
  fi
  if [[ "$rotational" == "1" ]]; then
    _preflight_add WARN "Disk type" "rotational HDD detected" \
      "FullNode benefits a lot from NVMe/SSD"
  fi
}

_preflight_os() {
  local os arch kernel
  os="$(uname -s 2>/dev/null || echo unknown)"
  arch="$(uname -m 2>/dev/null || echo unknown)"
  kernel="$(uname -r 2>/dev/null || echo unknown)"
  local detail="${os} ${arch} kernel=${kernel}"
  local kl
  kl="$(printf '%s' "$kernel" | tr '[:upper:]' '[:lower:]')"
  if [[ "$os" != "Linux" && "$os" != "Darwin" ]]; then
    _preflight_add FAIL "OS/arch" "$detail" "Expected Linux x86_64/aarch64 (prod) or Darwin for local toolkit"
    return
  fi
  if [[ "$os" == "Darwin" ]]; then
    _preflight_add WARN "OS/arch" "${detail} [local-mac-toolkit]" \
      "local Mac toolkit — open http://<server-ip>:8093/status on the Linux TRON host (not Mac localhost)"
  elif [[ "$kl" == *linuxkit* || "$kl" == *docker-desktop* ]]; then
    _preflight_add WARN "OS/arch" "${detail} [docker-desktop-vm]" \
      "Docker Desktop VM (local Mac), not the remote server — use http://<server-ip>:8093/status"
  elif [[ "$arch" =~ ^(x86_64|amd64|aarch64|arm64)$ ]]; then
    _preflight_add OK "OS/arch" "$detail" ""
  else
    _preflight_add WARN "OS/arch" "$detail" "Non-standard arch — verify java-tron compatibility"
  fi
}

_preflight_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    _preflight_add FAIL "Docker" "docker not found" "Install Docker Engine (apt install docker.io) — toolkit agents run in Docker"
    return
  fi
  local ver
  ver="$(docker --version 2>/dev/null || echo docker)"
  if ! docker compose version >/dev/null 2>&1; then
    _preflight_add FAIL "Docker Compose" "${ver}; compose plugin missing" \
      "Need docker compose plugin (docker-compose-plugin / docker-compose-v2)"
  else
    local cv
    cv="$(docker compose version 2>/dev/null | head -1)"
    _preflight_add OK "Docker" "${ver}; ${cv}" ""
  fi
  if ! docker info >/dev/null 2>&1; then
    _preflight_add WARN "Docker daemon" "cannot talk to docker (permission or not running)" \
      "systemctl start docker and/or add user to the docker group"
  fi
}

_preflight_port() {
  local port="$1" name="$2" role="${3:-gateway}"
  local busy=0 alt=""
  if command -v port_is_busy >/dev/null 2>&1; then
    port_is_busy "$port" && busy=1
  elif command -v ss >/dev/null 2>&1; then
    ss -lnt 2>/dev/null | grep -qE ":${port}\\b" && busy=1
  elif command -v lsof >/dev/null 2>&1; then
    lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 && busy=1
  elif command -v netstat >/dev/null 2>&1; then
    netstat -an 2>/dev/null | grep -qE "(\\.|:)(${port})\\s.*LISTEN" && busy=1
  fi
  if (( busy )); then
    alt="$(suggest_free_port "$((port + 1))" 2>/dev/null || echo "$((port + 1))")"
    local own_stack=0
    if docker ps --format '{{.Names}}' 2>/dev/null | grep -qE "tron-${TRON_ENV:-mainnet}-nginx|tron-${TRON_ENV:-mainnet}-api-agent"; then
      own_stack=1
    fi
    if (( own_stack )); then
      _preflight_add WARN "Port ${port}" "${name}: in use by this env's stack (tron-${TRON_ENV:-mainnet}-*)" \
        "OK if re-running setup; else: docker compose -p tron-toolkit-${TRON_ENV:-mainnet} down"
    elif [[ "$role" == "node" ]]; then
      _preflight_add WARN "Port ${port}" "${name}: already listening" \
        "Likely existing java-tron — keep for gateway-only, or use --node-http-port ${alt}"
    else
      _preflight_add FAIL "Port ${port}" "${name}: already listening" \
        "Another service/env occupies :${port}. Use --gateway-port / --panel-port (see rpcnodectl ports) or free it"
    fi
  else
    _preflight_add OK "Port ${port}" "${name}: free" ""
  fi
}

_preflight_java() {
  local need="${1:-0}"
  if [[ "$need" != "1" ]]; then
    if command -v java >/dev/null 2>&1; then
      local jv
      jv="$(java -version 2>&1 | head -1 || true)"
      _preflight_add OK "Java" "optional; found: ${jv}" ""
    else
      _preflight_add OK "Java" "not required (gateway-only / node already managed)" ""
    fi
    return
  fi
  if command -v java >/dev/null 2>&1; then
    local jv
    jv="$(java -version 2>&1 | head -1 || true)"
    _preflight_add OK "Java" "${jv}" ""
  else
    _preflight_add WARN "Java" "java not in PATH" \
      "For --with-node install JDK 8+ (temurin/openjdk) before starting java-tron"
  fi
}

print_preflight_report() {
  echo
  printf '%s╔══════════════════════════════════════════════════════════════╗%s\n' "$C_BLD" "$C_OFF"
  printf '%s║  Host preflight — is this host fit for TRON + toolkit?      ║%s\n' "$C_BLD" "$C_OFF"
  printf '%s╚══════════════════════════════════════════════════════════════╝%s\n' "$C_BLD" "$C_OFF"
  echo
  printf '  %-4s  %-14s  %s\n' "STAT" "CHECK" "DETAIL"
  printf '  ----  --------------  ------\n'
  local row level name detail recommend
  for row in "${PREFLIGHT_ROWS[@]}"; do
    IFS='|' read -r level name detail recommend <<<"$row"
    local tag
    case "$level" in
      OK) tag="${C_GRN}OK  ${C_OFF}" ;;
      WARN) tag="${C_YEL}WARN${C_OFF}" ;;
      FAIL) tag="${C_RED}FAIL${C_OFF}" ;;
      *) tag="$level" ;;
    esac
    printf '  %b  %-14s  %s\n' "$tag" "$name" "$detail"
    if [[ -n "$recommend" && "$level" != "OK" ]]; then
      printf '        %s→ %s%s\n' "$C_YEL" "$recommend" "$C_OFF"
    fi
  done
  echo
  printf '  Summary: %s%d OK%s · %s%d WARN%s · %s%d FAIL%s\n' \
    "$C_GRN" "$PREFLIGHT_OK" "$C_OFF" \
    "$C_YEL" "$PREFLIGHT_WARN" "$C_OFF" \
    "$C_RED" "$PREFLIGHT_FAIL" "$C_OFF"
  if (( PREFLIGHT_FAIL > 0 )); then
    warn "Host is NOT fully suitable — see FAIL above. Install can still continue."
  elif (( PREFLIGHT_WARN > 0 )); then
    warn "Warnings present — for prod mainnet, resolve WARN items if possible."
  else
    ok "Preflight: host looks suitable"
  fi
  echo
}

# Write JSON for ops console / registry (best-effort).
write_preflight_json() {
  local dest="${1:-/var/lib/rpcnode/tron-${TRON_ENV:-mainnet}/preflight.json}"
  mkdir -p "$(dirname "$dest")" 2>/dev/null || return 0
  local suitable="true"
  (( PREFLIGHT_FAIL > 0 )) && suitable="false"
  local os arch kernel platform context hint
  os="$(uname -s 2>/dev/null || echo unknown)"
  arch="$(uname -m 2>/dev/null || echo unknown)"
  kernel="$(uname -r 2>/dev/null || echo unknown)"
  platform="$os"
  context="linux"
  hint="Open the server panel at http://<server-ip>:8093/status (RPC :8090 · panel :8093)."
  local kl
  kl="$(printf '%s' "$kernel" | tr '[:upper:]' '[:lower:]')"
  if [[ "$os" == "Darwin" ]]; then
    context="local-mac-toolkit"
    hint="local Mac toolkit — for the TRON server open http://<server-ip>:8093/status (not Mac localhost)."
  elif [[ "$kl" == *linuxkit* || "$kl" == *docker-desktop* ]]; then
    context="docker-desktop-vm"
    hint="Docker Desktop VM (local Mac) — server facts live on http://<server-ip>:8093/status."
  elif [[ "${TRON_COMPOSE_HOST:-0}" == "1" ]]; then
    context="linux-server-host"
  fi
  if command -v jq >/dev/null 2>&1; then
    local items="[]"
    local row level name detail recommend
    items="$(
      {
        echo '['
        local first=1
        for row in "${PREFLIGHT_ROWS[@]}"; do
          IFS='|' read -r level name detail recommend <<<"$row"
          (( first )) || echo ','
          first=0
          jq -n \
            --arg level "$level" --arg name "$name" \
            --arg detail "$detail" --arg recommend "$recommend" \
            '{level:$level,name:$name,detail:$detail,recommend:$recommend}'
        done
        echo ']'
      } 2>/dev/null || echo '[]'
    )"
    local hn
    hn="$(hostname -f 2>/dev/null || hostname 2>/dev/null || echo unknown)"
    jq -n \
      --argjson ok "$PREFLIGHT_OK" \
      --argjson warn "$PREFLIGHT_WARN" \
      --argjson fail "$PREFLIGHT_FAIL" \
      --argjson suitable "$suitable" \
      --argjson checks "$items" \
      --arg checked_at "$(ts 2>/dev/null || date -u +%Y-%m-%dT%H:%M:%SZ)" \
      --arg env "${TRON_ENV:-mainnet}" \
      --arg source "rpcnodectl" \
      --arg platform "$platform" \
      --arg hostname "$hn" \
      --arg context "$context" \
      --arg hint "$hint" \
      '{ok:$ok,warn:$warn,fail:$fail,suitable:($suitable=="true"),checked_at:$checked_at,env:$env,checks:$checks,blocking:false,source:$source,platform:$platform,hostname:$hostname,context:$context,hint:$hint}' \
      >"${dest}.tmp" 2>/dev/null && mv -f "${dest}.tmp" "$dest"
  else
    local hn
    hn="$(hostname -f 2>/dev/null || hostname 2>/dev/null || echo unknown)"
    cat >"$dest" <<EOF
{"ok":${PREFLIGHT_OK},"warn":${PREFLIGHT_WARN},"fail":${PREFLIGHT_FAIL},"suitable":${suitable},"blocking":false,"env":"${TRON_ENV:-mainnet}","source":"rpcnodectl","platform":"${platform}","hostname":"${hn}","context":"${context}","hint":"${hint}"}
EOF
  fi
}

# Args: data_path, gateway_port, need_java(0|1), node_http_port?
run_preflight() {
  local data_path="${1:-}"
  local gw_port="${2:-${TRON_GATEWAY_PORT:-${TRON_PUBLIC_PORT:-8090}}}"
  local need_java="${3:-0}"
  local node_port="${4:-${TRON_NODE_HTTP_PORT:-18090}}"
  PREFLIGHT_OK=0
  PREFLIGHT_WARN=0
  PREFLIGHT_FAIL=0
  PREFLIGHT_ROWS=()

  _preflight_os
  _preflight_cpus
  _preflight_ram
  _preflight_disk "${data_path:-${TRON_DATA:-/data/tron/${TRON_ENV:-mainnet}}}"
  _preflight_docker
  local panel_port="${TRON_PANEL_PORT:-8093}"
  _preflight_port "$gw_port" "RPC public port (api-agent catch-all)" "gateway"
  _preflight_port "$panel_port" "Panel ops port (UI + /api)" "gateway"
  _preflight_port "$node_port" "java-tron HTTP (internal)" "node"
  _preflight_java "$need_java"

  print_preflight_report
  # Live snapshot only in state dir — never overwrite git placeholder in config/preflight.json.
  write_preflight_json "/var/lib/rpcnode/tron-${TRON_ENV:-mainnet}/preflight.json" 2>/dev/null || true
  # Drop foreign/Mac leftovers that may have been copied into the toolkit tree.
  if [[ -n "${TOOLKIT_DIR:-}" && -f "$TOOLKIT_DIR/config/preflight.json" ]]; then
    if grep -qiE 'darwin|apfs|local-mac' "$TOOLKIT_DIR/config/preflight.json" 2>/dev/null \
      || ! grep -q '"source": "placeholder"' "$TOOLKIT_DIR/config/preflight.json" 2>/dev/null; then
      if grep -q '"checks"' "$TOOLKIT_DIR/config/preflight.json" 2>/dev/null \
        && ! grep -q '"source": "placeholder"' "$TOOLKIT_DIR/config/preflight.json" 2>/dev/null; then
        warn "wiping non-placeholder config/preflight.json (live facts belong in state dir)"
        cat >"$TOOLKIT_DIR/config/preflight.json" <<'EOF'
{
  "ok": 0,
  "warn": 0,
  "fail": 0,
  "suitable": false,
  "blocking": false,
  "env": "mainnet",
  "source": "placeholder",
  "platform": "",
  "context": "pending",
  "hint": "Placeholder — system-agent writes live facts to state dir on container start. Do not commit real host snapshots here.",
  "checks": []
}
EOF
      fi
    fi
  fi
}

# After report: wizard prompt / non-interactive policy.
# Returns 0 to continue, 1 to abort.
preflight_confirm_continue() {
  local strict="${SETUP_STRICT_PREFLIGHT:-0}"
  if (( PREFLIGHT_FAIL == 0 && PREFLIGHT_WARN == 0 )); then
    return 0
  fi
  if [[ "$strict" == "1" ]] && (( PREFLIGHT_FAIL > 0 )); then
    err "strict-preflight: FAIL present — install stopped (omit --strict-preflight to continue)"
    return 1
  fi
  if [[ "${SETUP_NONINTERACTIVE:-0}" == "1" ]]; then
    warn "non-interactive: continuing despite WARN/FAIL (use --strict-preflight to fail)"
    return 0
  fi
  local go="y"
  _setup_yn "Host is not ideal. Continue install?" "y" go
  [[ "$go" == "y" ]] || return 1
  return 0
}

cmd_preflight() {
  # shellcheck disable=SC1091
  source "$TOOLKIT_DIR/lib/paths.sh"
  local data="${TRON_DATA:-/data/tron/${TRON_ENV}}"
  local port="${TRON_GATEWAY_PORT:-8090}"
  local need_java=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --data-dir) data="$2"; shift 2 ;;
      --gateway-port) port="$2"; shift 2 ;;
      --with-node) need_java=1; shift ;;
      --strict) SETUP_STRICT_PREFLIGHT=1; shift ;;
      *) shift ;;
    esac
  done
  run_preflight "$data" "$port" "$need_java"
  if [[ "${SETUP_STRICT_PREFLIGHT:-0}" == "1" ]] && (( PREFLIGHT_FAIL > 0 )); then
    exit 1
  fi
}
