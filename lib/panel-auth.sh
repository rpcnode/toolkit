#!/usr/bin/env bash
# Panel HTTP basic auth (Go api-agent htpasswd; optional nginx edge uses same file).

panel_htpasswd_path() {
  printf '%s' "${TRON_PANEL_HTPASSWD:-/etc/rpcnode/panel.htpasswd}"
}

panel_auth_dir() {
  dirname "$(panel_htpasswd_path)"
}

# Hash password → apr1 (htpasswd -nbB or openssl passwd -apr1).
_panel_hash_line() {
  local user="$1" pass="$2" line=""
  if command -v htpasswd >/dev/null 2>&1; then
    # -B bcrypt when available; -nb for no file / batch
    line="$(htpasswd -nbB "$user" "$pass" 2>/dev/null || htpasswd -nb "$user" "$pass")"
  elif command -v openssl >/dev/null 2>&1; then
    local hash
    hash="$(openssl passwd -apr1 "$pass")"
    line="${user}:${hash}"
  else
    die "need htpasswd (apache2-utils) or openssl to create panel password hash"
  fi
  printf '%s\n' "$line"
}

write_panel_htpasswd() {
  local user="$1" pass="$2"
  local path dest dir
  path="$(panel_htpasswd_path)"
  dir="$(dirname "$path")"
  require_root
  mkdir -p "$dir"
  chmod 750 "$dir" 2>/dev/null || true
  dest="${path}.tmp.$$"
  _panel_hash_line "$user" "$pass" >"$dest"
  chmod 640 "$dest"
  mv -f "$dest" "$path"
  # Also mirror into toolkit config for local compose bind-mount.
  mkdir -p "$TOOLKIT_DIR/config/nginx/htpasswd"
  cp -a "$path" "$TOOLKIT_DIR/config/nginx/htpasswd/panel.htpasswd"
  chmod 640 "$TOOLKIT_DIR/config/nginx/htpasswd/panel.htpasswd"
  ok "panel htpasswd → $path"
  ok "compose mount → $TOOLKIT_DIR/config/nginx/htpasswd/panel.htpasswd"
}

ensure_panel_htpasswd_for_compose() {
  local path="$TOOLKIT_DIR/config/nginx/htpasswd/panel.htpasswd"
  mkdir -p "$(dirname "$path")"
  if [[ -f "$path" && -s "$path" ]]; then
    return 0
  fi
  # Empty file → panel UI /setup-password creates the first admin (no browser basic dump).
  # Explicit TRON_PANEL_PASSWORD still pre-seeds for non-interactive installs.
  if [[ -n "${TRON_PANEL_PASSWORD:-}" ]]; then
    local user="${TRON_PANEL_USER:-admin}"
    if [[ "$(id -u)" -eq 0 ]]; then
      write_panel_htpasswd "$user" "$TRON_PANEL_PASSWORD"
    else
      _panel_hash_line "$user" "$TRON_PANEL_PASSWORD" >"$path"
      chmod 640 "$path" 2>/dev/null || true
      ok "wrote local $path (user=${user})"
    fi
    return 0
  fi
  : >"$path"
  chmod 640 "$path" 2>/dev/null || true
  info "empty panel htpasswd — open http://<host>:${TRON_PANEL_PORT:-8093}/setup-password"
}

prompt_panel_credentials() {
  # Sets PANEL_USER / PANEL_PASSWORD in caller via nameref-style globals.
  local def_user="${TRON_PANEL_USER:-admin}"
  local ans_user ans_pass ans_pass2
  if [[ -n "${TRON_PANEL_USER:-}" && -n "${TRON_PANEL_PASSWORD:-}" ]]; then
    PANEL_USER="$TRON_PANEL_USER"
    PANEL_PASSWORD="$TRON_PANEL_PASSWORD"
    return 0
  fi
  if [[ "${SETUP_NONINTERACTIVE:-0}" == "1" ]]; then
    PANEL_USER="${TRON_PANEL_USER:-admin}"
    if [[ -z "${TRON_PANEL_PASSWORD:-}" ]]; then
      PANEL_PASSWORD="$(openssl rand -base64 18 2>/dev/null | tr -d '/+=' | head -c 16 || echo changeme)"
      warn "non-interactive: generated panel password for user=${PANEL_USER}: ${PANEL_PASSWORD}"
    else
      PANEL_PASSWORD="$TRON_PANEL_PASSWORD"
    fi
    return 0
  fi
  _setup_ask "Ops panel username" "$def_user" ans_user
  if [[ ! -t 0 ]]; then
    PANEL_USER="$ans_user"
    PANEL_PASSWORD="$(openssl rand -base64 18 2>/dev/null | tr -d '/+=' | head -c 16 || echo changeme)"
    warn "no TTY — generated panel password: ${PANEL_PASSWORD}"
    return 0
  fi
  while true; do
    read -r -s -p "Ops panel password: " ans_pass
    echo
    [[ -n "$ans_pass" ]] || { warn "password required"; continue; }
    read -r -s -p "Confirm password: " ans_pass2
    echo
    [[ "$ans_pass" == "$ans_pass2" ]] && break
    warn "passwords do not match"
  done
  PANEL_USER="$ans_user"
  PANEL_PASSWORD="$ans_pass"
}

cmd_panel_auth() {
  local sub="${1:-}"
  shift || true
  case "$sub" in
    set)
      local user="" pass=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --user|-u) user="$2"; shift 2 ;;
          --password|-p) pass="$2"; shift 2 ;;
          --help|-h)
            cat <<EOF
rpcnodectl panel-auth set — set HTTP basic-auth credentials for ops panel (Go api-agent)

  sudo rpcnodectl panel-auth set --user admin --password 'new-secret'
  sudo rpcnodectl panel-auth set          # interactive

htpasswd: $(panel_htpasswd_path)
compose:  \$TOOLKIT_DIR/config/nginx/htpasswd/panel.htpasswd

Go dual-port default: api-agent reloads htpasswd ~every 5s (no restart).
If needed: rpcnodectl agents restart   (or docker compose restart api-agent)
Optional edge nginx: docker compose --profile edge up -d --force-recreate nginx
Test panel (not RPC): curl -u USER:PASS http://127.0.0.1:\${TRON_PANEL_PORT:-8093}/status
EOF
            return 0
            ;;
          *) warn "unknown flag: $1"; shift ;;
        esac
      done
      require_root
      if [[ -z "$user" || -z "$pass" ]]; then
        TRON_PANEL_USER="${user:-${TRON_PANEL_USER:-admin}}"
        TRON_PANEL_PASSWORD="${pass:-}"
        prompt_panel_credentials
        user="$PANEL_USER"
        pass="$PANEL_PASSWORD"
      fi
      [[ -n "$user" && -n "$pass" ]] || die "user and password required"
      # Ensure hash tools
      if ! command -v htpasswd >/dev/null 2>&1 && ! command -v openssl >/dev/null 2>&1; then
        apt-get install -y -qq apache2-utils 2>/dev/null || true
      fi
      write_panel_htpasswd "$user" "$pass"
      ok "panel auth updated for user=${user}"
      info "test: curl -u ${user}:… http://127.0.0.1:${TRON_PANEL_PORT:-8093}/status"
      info "panel (Docker): docker compose -f docker-compose.panel.yml restart"
      info "host agents (if any): rpcnodectl agents restart"
      ;;
    show|path)
      echo "htpasswd=$(panel_htpasswd_path)"
      echo "compose=$TOOLKIT_DIR/config/nginx/htpasswd/panel.htpasswd"
      if [[ -f "$(panel_htpasswd_path)" ]]; then
        echo "users:"
        cut -d: -f1 "$(panel_htpasswd_path)" 2>/dev/null || true
      elif [[ -f "$TOOLKIT_DIR/config/nginx/htpasswd/panel.htpasswd" ]]; then
        echo "users (compose copy):"
        cut -d: -f1 "$TOOLKIT_DIR/config/nginx/htpasswd/panel.htpasswd" 2>/dev/null || true
      else
        warn "no htpasswd yet — run: rpcnodectl panel-auth set"
      fi
      ;;
    *)
      die "panel-auth: set|show"
      ;;
  esac
}
