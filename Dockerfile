# Multi-stage: RpcNode ops console (panel) + Go system-agent / api-agent binaries.
# Panel image/compose: rpcnode-panel (multi-network control plane).
FROM node:22-alpine AS ui
WORKDIR /ui
COPY status-ui/package.json status-ui/package-lock.json* ./
RUN npm ci || npm install
COPY status-ui/ ./
COPY PANEL_VERSION /PANEL_VERSION
COPY PANEL_VERSION ./PANEL_VERSION
COPY docs/developer-api.md ./public/docs/developer-api.md
RUN npm run build

FROM golang:1.22-alpine AS build
WORKDIR /src
RUN apk add --no-cache git ca-certificates
COPY system-agent/ system-agent/
COPY api-agent/ api-agent/
COPY panel/ panel/
# Embed built React SPA into standalone panel (NOT into node api-agent).
# panel/ui is gitignored — always take dist from the ui stage.
RUN rm -rf panel/ui && mkdir -p panel/ui
COPY --from=ui /ui/dist/ panel/ui/
COPY TOOLKIT_VERSION TOOLKIT_VERSION
RUN VERSION="$(tr -d '[:space:]' <TOOLKIT_VERSION 2>/dev/null || echo 0.0.0)" \
 && LDFLAGS="-s -w -X main.toolkitVersion=${VERSION}" \
 && cd system-agent && CGO_ENABLED=0 go build -trimpath -ldflags="$LDFLAGS" -o /out/rpcnode-system-agent . \
 && cd ../api-agent && CGO_ENABLED=0 go build -trimpath -ldflags="$LDFLAGS" -o /out/rpcnode-api-agent . \
 && cd ../panel && CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /out/rpcnode-panel .

FROM alpine:3.20 AS runtime
RUN apk add --no-cache ca-certificates curl bash procps iproute2 coreutils sqlite \
    docker-cli docker-cli-compose git rsync jq \
 && adduser -D -H -u 10001 toolkit
WORKDIR /opt/toolkit
COPY --from=build /out/rpcnode-system-agent /usr/local/bin/rpcnode-system-agent
COPY --from=build /out/rpcnode-api-agent /usr/local/bin/rpcnode-api-agent
# Compat names for older scripts / unit templates.
RUN ln -sfn /usr/local/bin/rpcnode-system-agent /usr/local/bin/tron-system-agent \
 && ln -sfn /usr/local/bin/rpcnode-api-agent /usr/local/bin/tron-api-agent
COPY --from=build /out/rpcnode-panel /usr/local/bin/rpcnode-panel
COPY install/panel-watchdog.sh /usr/local/bin/rpcnode-panel-watchdog
RUN chmod +x /usr/local/bin/rpcnode-panel-watchdog
USER root
# Image ships panel + agent binaries; panel compose runs rpcnode-panel only.
EXPOSE 8093
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -fsS -o /dev/null http://127.0.0.1:${PANEL_PORT:-8093}/healthz || exit 1
ENTRYPOINT []
CMD ["/usr/local/bin/rpcnode-panel"]
