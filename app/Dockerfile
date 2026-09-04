# syntax=docker/dockerfile:1

FROM eclipse-temurin:26-jdk AS builder
WORKDIR /workspace/app
COPY app/ ./
RUN ./gradlew --no-daemon buildFatJar agentFatJar cdnFatJar

FROM eclipse-temurin:26-jre AS base
RUN useradd --system --create-home --home-dir /data rpcnode \
    && mkdir -p /opt/rpcnode/lib /opt/rpcnode/install/binaries /data \
    && chown -R rpcnode:rpcnode /opt/rpcnode /data

FROM base AS server
COPY --from=builder --chown=rpcnode:rpcnode /workspace/app/build/libs/rpcnode-server.jar /opt/rpcnode/lib/rpcnode-server.jar
COPY --from=builder --chown=rpcnode:rpcnode /workspace/app/build/libs/rpcnode-agent.jar /opt/rpcnode/install/binaries/rpcnode-agent.jar
COPY --chmod=755 --chown=rpcnode:rpcnode app/docker-entrypoint.sh /usr/local/bin/rpcnode-server

# Entrypoint starts as root so a host bind-mount of ./data can be chowned, then drops to rpcnode.
USER root
WORKDIR /data
ENV PANEL_LISTEN=0.0.0.0 \
    PANEL_PORT=8094 \
    TOOLKIT_DB=/data/database/toolkit.db \
    PANEL_HTPASSWD=/data/database/panel.htpasswd \
    PANEL_SESSIONS=/data/database/panel-sessions.json \
    PANEL_INSTALL_DIR=/data/install \
    CLIENT_SYNC_DEST=/data/install/clients \
    SERVER_LOG_FILE=/data/logs/server.log \
    XDG_CACHE_HOME=/data/cache \
    XDG_CONFIG_HOME=/data/config \
    XDG_DATA_HOME=/data/share \
    XDG_STATE_HOME=/data/state

EXPOSE 8094
ENTRYPOINT ["/usr/local/bin/rpcnode-server"]

FROM base AS cdn
COPY --from=builder --chown=rpcnode:rpcnode /workspace/app/build/libs/rpcnode-cdn.jar /opt/rpcnode/lib/rpcnode-cdn.jar

USER rpcnode
WORKDIR /data
ENV SNAPSHOT_CDN_DIR=/data \
    CDN_TARGETS_FILE=/data/rpcnode-cdn.targets.json \
    CDN_POLL_SEC=3600 \
    XDG_CACHE_HOME=/data/cache \
    XDG_CONFIG_HOME=/data/config \
    XDG_DATA_HOME=/data/share \
    XDG_STATE_HOME=/data/state

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/opt/rpcnode/lib/rpcnode-cdn.jar"]
