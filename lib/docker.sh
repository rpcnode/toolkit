#!/usr/bin/env bash
# Agent runtime helpers. Host agents = Go binaries + systemd (not Docker).
# Docker remains only for the standalone panel: docker-compose.panel.yml

ensure_host_runtime_dirs() {
  mkdir -p \
    "/var/lib/rpcnode/tron-${TRON_ENV}" \
    "/etc/rpcnode/instances.d" \
    "/run/tron-${TRON_ENV}" \
    "/var/log/tron" \
    "$TRON_ETC" \
    "$TRON_OPT" \
    "$TRON_DATA" \
    "$(dirname "$(panel_htpasswd_path 2>/dev/null || echo /etc/rpcnode/panel.htpasswd)")"
  chmod 755 "/var/lib/rpcnode" "/var/lib/rpcnode/tron-${TRON_ENV}" 2>/dev/null || true
}

agent_units() {
  printf '%s\n' rpcnode-system-agent.service rpcnode-api-agent.service
}

require_agent_units() {
  command -v systemctl >/dev/null 2>&1 || die "systemctl not found — install host agent via https://rpcnode.dev/install/agent.sh"
  local u
  for u in $(agent_units); do
    [[ -f "/etc/systemd/system/${u}" ]] || die "missing ${u} — run: curl -fsSL https://rpcnode.dev/install/agent.sh | sudo bash"
  done
}

agent_port() {
  if [[ -f /etc/rpcnode/agent.port ]]; then
    tr -d '[:space:]' </etc/rpcnode/agent.port
    return 0
  fi
  printf '%s' "${TRON_PUBLIC_PORT:-${TRON_GATEWAY_PORT:-39090}}"
}

cmd_agents() {
  local sub="${1:-status}"
  shift || true
  case "$sub" in
    up|start)
      require_root
      require_agent_units
      ensure_host_runtime_dirs
      systemctl daemon-reload
      systemctl enable rpcnode-system-agent.service rpcnode-api-agent.service
      systemctl restart rpcnode-system-agent.service
      sleep 1
      systemctl restart rpcnode-api-agent.service
      systemctl --no-pager --full status rpcnode-system-agent.service rpcnode-api-agent.service || true
      local port
      port="$(agent_port)"
      curl -sS -o /dev/null -w "agent /healthz=%{http_code}\n" --max-time 3 \
        "http://127.0.0.1:${port}/healthz" || true
      ok "host agents up (systemd) — RPC/agent :${port}"
      info "panel is separate: docker compose -f docker-compose.panel.yml up -d --build"
      ;;
    down|stop)
      require_root
      systemctl stop rpcnode-api-agent.service rpcnode-system-agent.service 2>/dev/null || true
      ok "host agents stopped"
      ;;
    restart)
      cmd_agents up
      ;;
    logs)
      journalctl -u rpcnode-api-agent.service -u rpcnode-system-agent.service -f --no-pager -n 100 "$@"
      ;;
    ps|status)
      if command -v systemctl >/dev/null 2>&1; then
        systemctl --no-pager --full status rpcnode-system-agent.service rpcnode-api-agent.service 2>/dev/null \
          || warn "agent units not installed — https://rpcnode.dev/install/agent.sh"
      fi
      local port
      port="$(agent_port)"
      echo
      curl -sS --max-time 3 "http://127.0.0.1:${port}/healthz" 2>/dev/null | head -c 200 || warn "agent :${port} not reachable"
      echo
      ;;
    build)
      die "agents build removed — use ./scripts/build-agent-binaries.sh + publish, or reinstall via agent.sh"
      ;;
    wrapper-enable)
      die "compose wrapper removed — agents use rpcnode-*-agent.service (WantedBy=multi-user.target)"
      ;;
    *)
      die "agents: up|down|restart|status|logs"
      ;;
  esac
}
