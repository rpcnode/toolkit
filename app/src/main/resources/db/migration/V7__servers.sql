CREATE TABLE servers (
  id            TEXT PRIMARY KEY,
  name          TEXT NOT NULL DEFAULT '',
  env           TEXT NOT NULL DEFAULT '',
  network       TEXT NOT NULL DEFAULT '',
  agent_url     TEXT NOT NULL DEFAULT '',
  agent_key     TEXT NOT NULL DEFAULT '',
  os            TEXT NOT NULL DEFAULT '',
  arch          TEXT NOT NULL DEFAULT '',
  os_pretty     TEXT NOT NULL DEFAULT '',
  agent_version  TEXT NOT NULL DEFAULT '',
  agent_build   TEXT NOT NULL DEFAULT '',
  created_at    TEXT NOT NULL,
  updated_at    TEXT NOT NULL
);
CREATE INDEX idx_servers_agent_url ON servers(agent_url);
