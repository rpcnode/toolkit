#!/usr/bin/env bash
# Install rpcnode-client-watch as a systemd service (API :8094 + hourly check).
# Run on the CDN/watch host as root. Does not open ufw.
set -euo pipefail

if [[ ${EUID:-0} -ne 0 ]]; then
  echo "нужен root: sudo $0" >&2
  exit 1
fi

here=$(cd "$(dirname "$0")" && pwd)
toolkit=$(cd "$here/../.." && pwd)
bin_src="$here/rpcnode-client-watch"
env_src="$toolkit/client-watch.env"
unit_src="$here/rpcnode-client-watch.service"

if [[ ! -x $bin_src ]]; then
  echo "нет бинарника $bin_src" >&2
  echo "собери: cd $here && go build -o rpcnode-client-watch ." >&2
  exit 1
fi
if [[ ! -f $env_src ]]; then
  echo "нет $env_src — скопируй из client-watch.env.example и впиши GITHUB_TOKEN + TELEGRAM_*" >&2
  exit 1
fi

install -d -m 0755 /etc/rpcnode /var/lib/rpcnode/client-watch /opt/rpcnode
install -m 0755 "$bin_src" /usr/local/bin/rpcnode-client-watch
install -m 0600 "$env_src" /etc/rpcnode/client-watch.env
install -m 0644 "$unit_src" /etc/systemd/system/rpcnode-client-watch.service

drop=/etc/systemd/system/rpcnode-client-watch.service.d
install -d -m 0755 "$drop"
{
  echo "[Service]"
  echo "EnvironmentFile=-$env_src"
  if [[ $toolkit != /opt/rpcnode/toolkit ]]; then
    echo "Environment=CLIENT_WATCH_CATALOG=$toolkit/install/clients/catalog.json"
    echo "Environment=CLIENT_WATCH_CLIENTS=$toolkit/install/clients"
  fi
} >"$drop/paths.conf"
chmod 0644 "$drop/paths.conf"

systemctl daemon-reload
systemctl enable --now rpcnode-client-watch.service
echo "ok  $(systemctl is-active rpcnode-client-watch)"
echo "API  http://<этот-хост>:8094"
echo "лог  journalctl -u rpcnode-client-watch -f"
echo "healthz: curl -fsS http://127.0.0.1:8094/healthz"
echo "cron -check сними — демон сам проверяет раз в час"
