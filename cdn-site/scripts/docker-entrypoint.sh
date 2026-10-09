#!/bin/sh
set -eu

if [ -z "${SNAPSHOT_CDN_DIR:-}" ]; then
  echo "ERROR: SNAPSHOT_CDN_DIR is required (mount the jar snapshot root at /data)" >&2
  exit 1
fi

mkdir -p "${SNAPSHOT_CDN_DIR}/snapshots"

exec "$@"
