#!/bin/bash
# Move the WAL (pg_wal) of a Debian/Ubuntu PostgreSQL cluster from the system disk to a fast disk and leave a symlink.
#
# Why: a tablespace moves tables and indexes but NOT the WAL; the WAL of a shared server stays in its data directory on the
# system disk, where every commit's fsync lands. On a host with a consumer SSD for / this is the write bottleneck.
# (New TON indexer installs get a PostgreSQL cluster of their own on the indexer disk and need none of this.)
#
# Usage (as root, on the host):
#   move-postgres-wal.sh --check  <version> <cluster> <dest-dir>            only report what would happen
#   move-postgres-wal.sh          <version> <cluster> <dest-dir> [unit...]  do it; the units are stopped first, started last
# Example (the shared server of the TON testnet indexer):
#   move-postgres-wal.sh 16 main /mnt/raid0/pg_wal-16-main rpcnode-ton-testnet-indexer.service
#
# Safety: stops the dependent units, then the cluster (fast shutdown) and refuses to go on unless it is "shut down" cleanly;
# copies with rsync and compares file count and bytes BEFORE touching the original; the original is renamed, never deleted
# (pg_wal.moved-<timestamp>: remove it yourself once the server has run fine); any failure before the swap restarts everything,
# a failed start after the swap rolls the symlink back. A systemd drop-in makes the cluster wait for the destination mount.
# The destination must not be inside a directory that the toolkit removes with a node (use a neutral path, not a node dir).
set -uo pipefail

CHECK=0
if [[ "${1:-}" == "--check" ]]; then CHECK=1; shift; fi
VER="${1:-}"; CLUSTER="${2:-}"; DST="${3:-}"
if [[ -z "$VER" || -z "$CLUSTER" || -z "$DST" ]]; then
  sed -n '2,/^set -uo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
  exit 2
fi
shift 3
UNITS=("$@")

[[ "$(id -u)" -eq 0 ]] || { echo "run as root"; exit 2; }
PGDATA="$(pg_lsclusters -h 2>/dev/null | awk -v v="$VER" -v n="$CLUSTER" '$1 == v && $2 == n { print $6 }')"
[[ -n "$PGDATA" && -d "$PGDATA" ]] || { echo "cluster $VER/$CLUSTER not found (pg_lsclusters)"; exit 2; }
SRC="$PGDATA/pg_wal"
[[ -d "$SRC" && ! -L "$SRC" ]] || { echo "$SRC is not a plain directory (already moved?)"; exit 2; }
case "$DST" in /*) ;; *) echo "destination must be an absolute path"; exit 2 ;; esac
[[ "$DST" != "$SRC"* ]] || { echo "destination must not be inside the data directory"; exit 2; }
PGU="postgresql@${VER}-${CLUSTER}.service"
MOUNT="$(df --output=target "$(dirname "$DST")" | tail -1)"
need=$(du -sb "$SRC" | cut -f1)
avail=$(df -B1 --output=avail "$(dirname "$DST")" | tail -1 | tr -d ' ')

echo "cluster       : $VER/$CLUSTER ($PGDATA)"
echo "pg_wal        : $SRC ($(numfmt --to=iec "$need" 2>/dev/null || echo "$need bytes"))"
echo "destination   : $DST (mount $MOUNT, $(numfmt --to=iec "$avail" 2>/dev/null || echo "$avail bytes") free)"
echo "stop first    : ${UNITS[*]:-(nothing)} then $PGU"
(( avail > need * 2 )) || { echo "not enough free space at the destination (need 2x the WAL size)"; exit 2; }
if (( CHECK == 1 )); then echo "check only: nothing changed"; exit 0; fi

LOG="/root/rpcnode-pgwal-move.log"
exec > >(tee -a "$LOG") 2>&1
echo "=== start $(date -Is)"

restart_all() {
  echo "restoring services"
  systemctl start "$PGU"
  local i; for i in $(seq 1 60); do pg_isready -q && break; sleep 2; done
  local u; for u in "${UNITS[@]}"; do systemctl start "$u"; done
}
fail() { echo "FAILED: $1"; restart_all; echo "=== aborted $(date -Is)"; exit 1; }

for u in "${UNITS[@]}"; do systemctl stop "$u" || fail "cannot stop $u"; done
systemctl stop "$PGU" || fail "cannot stop $PGU"
state="$("/usr/lib/postgresql/$VER/bin/pg_controldata" "$PGDATA" | awk -F: '/Database cluster state/ { gsub(/^ +/, "", $2); print $2 }')"
echo "cluster state: $state"
[[ "$state" == "shut down" ]] || fail "PostgreSQL did not shut down cleanly ($state)"

mkdir -p "$DST" && chown postgres:postgres "$DST" && chmod 700 "$DST" || fail "cannot create $DST"
echo "rsync start $(date -Is)"
rsync -aH "$SRC"/ "$DST"/ || fail "rsync failed"
echo "rsync done  $(date -Is)"
a=$(find "$SRC" -type f | wc -l); b=$(find "$DST" -type f | wc -l)
sa=$(du -sb "$SRC" | cut -f1); sb=$(du -sb "$DST" | cut -f1)
echo "files $a -> $b, bytes $sa -> $sb"
[[ "$a" == "$b" && "$sa" == "$sb" ]] || fail "copy verification mismatch"

ts=$(date +%s)
mv "$SRC" "$SRC.moved-$ts" || fail "cannot rename the old pg_wal"
if ! ln -s "$DST" "$SRC"; then mv "$SRC.moved-$ts" "$SRC"; fail "cannot create the symlink"; fi
chown -h postgres:postgres "$SRC"

mkdir -p "/etc/systemd/system/$PGU.d"
printf '[Unit]\nRequiresMountsFor=%s\n' "$MOUNT" >"/etc/systemd/system/$PGU.d/rpcnode-wal-mount.conf"
systemctl daemon-reload
rollback() { systemctl stop "$PGU" 2>/dev/null; rm -f "$SRC"; mv "$SRC.moved-$ts" "$SRC"; }
systemctl start "$PGU" || { rollback; fail "PostgreSQL did not start with the symlinked pg_wal (rolled back)"; }
for i in $(seq 1 90); do pg_isready -q && break; sleep 2; done
pg_isready -q || { rollback; fail "PostgreSQL is not ready (rolled back)"; }
for u in "${UNITS[@]}"; do systemctl start "$u" || echo "WARNING: $u did not start"; done

echo "old copy kept: $SRC.moved-$ts   (remove it when the server has run fine for a while)"
echo "=== done $(date -Is)"
