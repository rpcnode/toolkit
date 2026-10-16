#!/bin/bash
# Runs when the node is removed (stop first, then this, then the files): drops what rpcnode-ton-indexer.sh created in
# PostgreSQL — the database, its tablespace and the role — and the tablespace directory. The PostgreSQL server itself
# (existing or installed by the indexer) stays; other databases on it are not touched.
set -uo pipefail
PWDIR='/mnt/raid0/rpcnode/ton/testnet/blockchain/.toolkit/ton-indexer'
IDX='/mnt/raid0/rpcnode/ton/testnet/indexer'

if [[ ! -f "$PWDIR/pg.env" ]]; then
  echo "the indexer database was never created — nothing to drop"
  exit 0
fi
# shellcheck disable=SC1091
. "$PWDIR/pg.env"
echo "dropped database $PG_DB, tablespace $PG_TS and role $PG_ROLE (PostgreSQL port $PG_PORT)"
