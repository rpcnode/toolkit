#!/usr/bin/env bash
# Back-compat wrapper. Prefer ./scripts/open-rpcnode.sh
exec "$(cd "$(dirname "$0")" && pwd)/open-rpcnode.sh" "$@"
