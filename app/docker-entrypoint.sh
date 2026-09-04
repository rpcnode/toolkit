#!/bin/sh
set -eu

install_dir="${PANEL_INSTALL_DIR:-/data/install}"
agent_dir="$install_dir/binaries"
mkdir -p "$agent_dir" "${CLIENT_SYNC_DEST:-$install_dir/clients}" /data/database /data/logs

if [ ! -f "$agent_dir/rpcnode-agent.jar" ]; then
    cp /opt/rpcnode/install/binaries/rpcnode-agent.jar "$agent_dir/rpcnode-agent.jar"
fi

if [ "$(id -u)" -eq 0 ]; then
    chown -R rpcnode:rpcnode /data
    exec runuser -u rpcnode -- java --enable-native-access=ALL-UNNAMED -jar /opt/rpcnode/lib/rpcnode-server.jar
fi

exec java --enable-native-access=ALL-UNNAMED -jar /opt/rpcnode/lib/rpcnode-server.jar
