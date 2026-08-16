PRAGMA journal_mode=WAL;
PRAGMA busy_timeout=5000;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS schema_version (
  version INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS servers (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL DEFAULT '',
  env         TEXT NOT NULL DEFAULT 'mainnet',
  network     TEXT NOT NULL DEFAULT 'tron',
  agent_url   TEXT NOT NULL DEFAULT '',
  agent_key   TEXT NOT NULL DEFAULT '',
  os            TEXT NOT NULL DEFAULT '',
  arch          TEXT NOT NULL DEFAULT '',
  os_pretty     TEXT NOT NULL DEFAULT '',
  agent_version TEXT NOT NULL DEFAULT '',
  created_at    TEXT NOT NULL,
  updated_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS nodes (
  id              TEXT PRIMARY KEY,
  server_id       TEXT NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  name            TEXT NOT NULL DEFAULT '',
  network         TEXT NOT NULL DEFAULT 'tron',
  env             TEXT NOT NULL DEFAULT 'mainnet',
  public_port     INTEGER NOT NULL DEFAULT 0,
  agent_port      INTEGER NOT NULL DEFAULT 0,
  node_http_port  INTEGER NOT NULL DEFAULT 0,
  p2p_port        INTEGER NOT NULL DEFAULT 0,
  agent_url       TEXT NOT NULL DEFAULT '',
  status          TEXT NOT NULL DEFAULT '',
  client_version          TEXT NOT NULL DEFAULT '',
  client_latest           TEXT NOT NULL DEFAULT '',
  client_update_available INTEGER NOT NULL DEFAULT 0,
  disk_layout_json        TEXT NOT NULL DEFAULT '',
  install_started_at      TEXT NOT NULL DEFAULT '',
  synced_at               TEXT NOT NULL DEFAULT '',
  created_at      TEXT NOT NULL,
  updated_at      TEXT NOT NULL,
  UNIQUE (server_id, network, env)
);

CREATE INDEX IF NOT EXISTS idx_nodes_server ON nodes(server_id);

CREATE TABLE IF NOT EXISTS server_metrics (
  server_id     TEXT PRIMARY KEY REFERENCES servers(id) ON DELETE CASCADE,
  host_id       TEXT NOT NULL DEFAULT '',
  agent_url     TEXT NOT NULL DEFAULT '',
  cpu_pct       REAL NOT NULL DEFAULT 0,
  load_pct      REAL NOT NULL DEFAULT 0,
  ncpu          INTEGER NOT NULL DEFAULT 0,
  mem_pct       REAL NOT NULL DEFAULT 0,
  mem_used_mb   REAL NOT NULL DEFAULT 0,
  mem_total_mb  REAL NOT NULL DEFAULT 0,
  disk_used_pct REAL NOT NULL DEFAULT 0,
  disk_used_gb  REAL NOT NULL DEFAULT 0,
  disk_total_gb REAL NOT NULL DEFAULT 0,
  load_1        REAL NOT NULL DEFAULT 0,
  os            TEXT NOT NULL DEFAULT '',
  arch          TEXT NOT NULL DEFAULT '',
  collected_at  TEXT NOT NULL DEFAULT '',
  last_seen_at  TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS node_status (
  node_id       TEXT PRIMARY KEY REFERENCES nodes(id) ON DELETE CASCADE,
  phase         TEXT NOT NULL DEFAULT '',
  label         TEXT NOT NULL DEFAULT '',
  detail        TEXT NOT NULL DEFAULT '',
  height        INTEGER,
  snapshot_pct  REAL,
  health        TEXT NOT NULL DEFAULT '',
  raw_json      TEXT NOT NULL DEFAULT '',
  rpc_proxy     TEXT NOT NULL DEFAULT '',
  error         TEXT NOT NULL DEFAULT '',
  collected_at  TEXT NOT NULL DEFAULT '',
  last_seen_at  TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS collector_meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL DEFAULT ''
);
