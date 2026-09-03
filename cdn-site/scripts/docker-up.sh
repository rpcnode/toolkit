#!/usr/bin/env bash
# Build and run the public CDN site (+ nginx) with plain Docker (no compose plugin).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SNAPSHOT_CDN_DIR="${SNAPSHOT_CDN_DIR:-$ROOT/data}"
CDN_SITE_PORT="${CDN_SITE_PORT:-7090}"
CDN_HTTP_PORT="${CDN_HTTP_PORT:-8095}"
IMAGE="${CDN_SITE_IMAGE:-rpcnode-cdn-site:local}"
NET="${CDN_DOCKER_NET:-rpcnode-cdn}"
SITE_NAME="${CDN_SITE_CONTAINER:-rpcnode-cdn-site}"
NGINX_NAME="${CDN_NGINX_CONTAINER:-rpcnode-cdn-nginx}"

mkdir -p "$SNAPSHOT_CDN_DIR/snapshots"

echo "building $IMAGE …"
docker build -t "$IMAGE" .

docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET"

docker rm -f "$SITE_NAME" "$NGINX_NAME" >/dev/null 2>&1 || true

echo "starting $SITE_NAME (Next :$CDN_SITE_PORT) …"
docker run -d --name "$SITE_NAME" --restart unless-stopped \
  --network "$NET" \
  -e SNAPSHOT_CDN_DIR=/data \
  -p "${CDN_SITE_PORT}:7090" \
  -v "${SNAPSHOT_CDN_DIR}:/data" \
  "$IMAGE"

echo "starting $NGINX_NAME (HTTP :$CDN_HTTP_PORT) …"
docker run -d --name "$NGINX_NAME" --restart unless-stopped \
  --network "$NET" \
  -p "${CDN_HTTP_PORT}:8095" \
  -v "$ROOT/deploy/nginx.docker.conf:/etc/nginx/conf.d/default.conf:ro" \
  -v "${SNAPSHOT_CDN_DIR}/snapshots:/data/snapshots:ro" \
  nginx:1.27-alpine

echo "CDN site up:"
echo "  public  http://127.0.0.1:${CDN_HTTP_PORT}/"
echo "  next    http://127.0.0.1:${CDN_SITE_PORT}/"
echo "  data    $SNAPSHOT_CDN_DIR"
echo "stop: docker rm -f $SITE_NAME $NGINX_NAME"
