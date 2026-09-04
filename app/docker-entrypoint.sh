#!/bin/sh
set -eu

install_dir="${PANEL_INSTALL_DIR:-/data/install}"
agent_dir="$install_dir/binaries"
mkdir -p "$agent_dir" "${CLIENT_SYNC_DEST:-$install_dir/clients}" /data/database /data/logs

# Always refresh from the image. /data is a bind mount, so a one-time copy
# would keep serving an old rpcnode-agent.jar across rebuilds forever.
image_agent="/opt/rpcnode/install/binaries/rpcnode-agent.jar"
if [ -f "$image_agent" ]; then
    cp -f "$image_agent" "$agent_dir/rpcnode-agent.jar"
fi

if [ "$(id -u)" -eq 0 ]; then
    chown -R rpcnode:rpcnode /data
    exec runuser -u rpcnode -- java --enable-native-access=ALL-UNNAMED -jar /opt/rpcnode/lib/rpcnode-server.jar
fi

exec java --enable-native-access=ALL-UNNAMED -jar /opt/rpcnode/lib/rpcnode-server.jar
