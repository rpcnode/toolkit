# Shared shell functions: a separate role + database + tablespace for one node in the host's PostgreSQL.
# Used by chains/cardano/dbsync-prepare.sh.tmpl (the TON indexer has the same logic inlined in chains/ton/indexer.sh.tmpl).
# Three role settings (pg_*) are template placeholders filled by the caller: they are clientConfig.bindings literals of the network.
#
# The caller sets before calling anything: PWDIR (0700 state dir), TS_DIR (tablespace directory, on the indexer disk),
# PG_ROLE, PG_DB, PG_TS, PG_CONF_TAG (name of the conf.d file), PG_MAINT_MIN_MB (floor of maintenance_work_mem),
# and defines log(). Nothing here puts the database password into argv: SQL goes in through stdin, the password lives
# in $PWDIR/postgres_password and in the 0600 pgpass file ($PGPASSFILE) that libpq and the application read.
PG_PORT=""; PG_BY_US=0; PGURL=""
if [[ -f "$PWDIR/pg.env" ]] && grep -q '^PG_INSTALLED_BY_US=1' "$PWDIR/pg.env"; then PG_BY_US=1; fi

# psql as the postgres OS user on the local socket (superuser on a stock install). Arguments are passed on, SQL comes on stdin.
psql_su() { ( cd / && setpriv --reuid=postgres --regid=postgres --init-groups psql -p "$PG_PORT" -X -q -At -v ON_ERROR_STOP=1 "$@" ); }

# port of the PostgreSQL server that is already there (Debian clusters, else the default port); empty = none
detect_postgres() {
  PG_PORT=""
  if command -v pg_lsclusters >/dev/null 2>&1; then
    PG_PORT="$(pg_lsclusters -h 2>/dev/null | awk '$4 == "online" { print $3; exit }')"
  fi
  if [[ -z "$PG_PORT" ]] && command -v pg_isready >/dev/null 2>&1 && pg_isready -q -p 5432; then PG_PORT=5432; fi
  [[ -n "$PG_PORT" ]]
}

ensure_server() {
  if detect_postgres; then
    log "using the PostgreSQL server that is already on this host (port $PG_PORT)"
    return 0
  fi
  command -v apt-get >/dev/null 2>&1 || { log "no PostgreSQL server found and apt-get is not available — install PostgreSQL first"; return 1; }
  log "no PostgreSQL server on this host — installing it from apt"
  DEBIAN_FRONTEND=noninteractive apt-get update -y && DEBIAN_FRONTEND=noninteractive apt-get install -y postgresql util-linux || return 1
  systemctl enable --now postgresql >/dev/null 2>&1 || true
  local i
  for i in $(seq 1 60); do detect_postgres && break; sleep 2; done
  [[ -n "$PG_PORT" ]] || { log "PostgreSQL did not start"; return 1; }
  PG_BY_US=1
}

# recommended server settings for a heavy read/write load, from the size of this machine
pg_server_settings() {
  local ram_mb cores shared cache workers
  ram_mb="$(awk '/MemTotal/ { printf "%d", $2 / 1024 }' /proc/meminfo)"; cores="$(nproc)"
  shared=$(( ram_mb / 4 )); (( shared > 65536 )) && shared=65536; (( shared < 512 )) && shared=512
  cache=$(( ram_mb * 7 / 10 )); workers=$cores; (( workers < 4 )) && workers=4
  cat <<CONF
# RpcNode ($PG_CONF_TAG) — recommended for a large indexing + query load (written by the node's prepare script)
max_connections = 200
shared_buffers = ${shared}MB
effective_cache_size = ${cache}MB
wal_compression = on
max_wal_size = 16GB
min_wal_size = 2GB
checkpoint_timeout = 15min
checkpoint_completion_target = 0.9
max_worker_processes = $workers
max_parallel_workers = $workers
max_parallel_maintenance_workers = 4
autovacuum_max_workers = 6
autovacuum_vacuum_cost_limit = 2000
autovacuum_naptime = 15s
tcp_keepalives_idle = 60
tcp_keepalives_interval = 60
tcp_keepalives_count = 60
CONF
}

# a server that this script installed is ours to tune; one that was already there is left as it is
tune_server() {
  if (( PG_BY_US == 1 )); then
    [[ -f "$PWDIR/pg-tuned" ]] && return 0
    local conf_dir
    conf_dir="$(dirname "$(psql_su -d postgres -c 'show config_file')")/conf.d"
    if [[ -d "$conf_dir" ]]; then
      pg_server_settings >"$conf_dir/90-rpcnode-$PG_CONF_TAG.conf"
      log "wrote $conf_dir/90-rpcnode-$PG_CONF_TAG.conf — restarting PostgreSQL once to apply it"
      systemctl restart postgresql >/dev/null 2>&1 || true
      local i; for i in $(seq 1 60); do pg_isready -q -p "$PG_PORT" && break; sleep 2; done
      touch "$PWDIR/pg-tuned"
    else
      pg_server_settings >"$PWDIR/postgresql-recommended.conf"
      log "no conf.d in the PostgreSQL config — tune the server yourself, see $PWDIR/postgresql-recommended.conf"
    fi
  else
    pg_server_settings >"$PWDIR/postgresql-recommended.conf"
    log "existing PostgreSQL server left untouched; for a heavy load apply $PWDIR/postgresql-recommended.conf (shared_buffers etc.)"
  fi
}

# the database password: generated once, kept 0600; PGPASSFILE is what the application (and psql below) reads
ensure_password() {
  PW_FILE="$PWDIR/postgres_password"
  if [[ ! -s "$PW_FILE" ]]; then
    ( umask 077; od -An -tx1 -N24 /dev/urandom | tr -d ' \n' >"$PW_FILE" )
  fi
  chmod 600 "$PW_FILE"
  PGPASS="$PWDIR/pgpass"
  export PGPASSFILE="$PGPASS"
}

# a separate role, tablespace (the data goes to the indexer disk) and database for this node; idempotent
provision_database() {
  ensure_server || return 1
  PGURL="postgresql://$PG_ROLE@127.0.0.1:$PG_PORT/$PG_DB"
  mkdir -p "$TS_DIR"; chown postgres:postgres "$TS_DIR"; chmod 700 "$TS_DIR"
  local pw ram_mb workmem maint
  pw="$(cat "$PW_FILE")"
  if psql_su -d postgres -c "select 1 from pg_roles where rolname = '$PG_ROLE'" | grep -q 1; then
    psql_su -d postgres -f - <<SQL || return 1
alter role $PG_ROLE login password '$pw';
SQL
  else
    psql_su -d postgres -f - <<SQL || return 1
create role $PG_ROLE login password '$pw';
SQL
    log "created role $PG_ROLE"
  fi
  if ! psql_su -d postgres -c "select 1 from pg_tablespace where spcname = '$PG_TS'" | grep -q 1; then
    psql_su -d postgres -c "create tablespace $PG_TS owner $PG_ROLE location '$TS_DIR'" || return 1
    log "created tablespace $PG_TS in $TS_DIR"
  fi
  if ! psql_su -d postgres -c "select 1 from pg_database where datname = '$PG_DB'" | grep -q 1; then
    psql_su -d postgres -c "create database $PG_DB owner $PG_ROLE template template0 encoding 'UTF8' tablespace $PG_TS" || return 1
    log "created database $PG_DB"
  fi
  # settings that apply to this role only (the rest of the server is not affected)
  ram_mb="$(awk '/MemTotal/ { printf "%d", $2 / 1024 }' /proc/meminfo)"
  workmem=$(( ram_mb / 512 )); (( workmem < 16 )) && workmem=16; (( workmem > 128 )) && workmem=128
  maint=$(( ram_mb / 16 )); (( maint > 4096 )) && maint=4096; (( maint < PG_MAINT_MIN_MB )) && maint=$PG_MAINT_MIN_MB
  psql_su -d postgres \
    -c "alter role $PG_ROLE set work_mem = '${workmem}MB'" \
    -c "alter role $PG_ROLE set maintenance_work_mem = '${maint}MB'" \
    -c "alter role $PG_ROLE set max_parallel_maintenance_workers = '{{pg_max_parallel_maintenance_workers}}'" \
    -c "alter role $PG_ROLE set random_page_cost = '{{pg_random_page_cost}}'" \
    -c "alter role $PG_ROLE set effective_io_concurrency = '{{pg_effective_io_concurrency}}'" || return 1
  # what the removal hook needs to undo all of it
  cat >"$PWDIR/pg.env" <<ENVFILE
PG_PORT=$PG_PORT
PG_ROLE=$PG_ROLE
PG_DB=$PG_DB
PG_TS=$PG_TS
PG_TS_DIR=$TS_DIR
PG_INSTALLED_BY_US=$PG_BY_US
ENVFILE
  # libpq and the application read host:port:database:user:password from here (5 fields, the password has no ':')
  ( umask 077; printf '127.0.0.1:%s:%s:%s:%s\n' "$PG_PORT" "$PG_DB" "$PG_ROLE" "$pw" >"$PGPASS" )
  chmod 600 "$PGPASS"
  tune_server
  if ! psql "$PGURL" -Atc 'select 1' >/dev/null 2>&1; then
    log "cannot log in as $PG_ROLE over 127.0.0.1:$PG_PORT — check listen_addresses and a scram-sha-256/md5 rule for 127.0.0.1 in pg_hba.conf"
    return 1
  fi
}
