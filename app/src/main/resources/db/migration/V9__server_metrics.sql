CREATE TABLE server_metrics (
  server_id      TEXT PRIMARY KEY,
  cpu_pct        REAL NOT NULL DEFAULT 0,
  load_pct       REAL NOT NULL DEFAULT 0,
  ncpu           INTEGER NOT NULL DEFAULT 0,
  mem_pct        REAL NOT NULL DEFAULT 0,
  mem_used_mb    REAL NOT NULL DEFAULT 0,
  mem_total_mb   REAL NOT NULL DEFAULT 0,
  disk_used_pct  REAL NOT NULL DEFAULT 0,
  disk_used_gb   REAL NOT NULL DEFAULT 0,
  disk_total_gb  REAL NOT NULL DEFAULT 0,
  load_1         REAL NOT NULL DEFAULT 0,
  disks_json     TEXT NOT NULL DEFAULT '[]',
  os             TEXT NOT NULL DEFAULT '',
  arch           TEXT NOT NULL DEFAULT '',
  collected_at   TEXT NOT NULL,
  last_seen_at   TEXT NOT NULL,
  FOREIGN KEY(server_id) REFERENCES servers(id)
);
