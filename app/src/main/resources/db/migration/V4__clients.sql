CREATE TABLE client_versions (
  network         TEXT NOT NULL,
  env             TEXT NOT NULL,
  program         TEXT NOT NULL,
  current_version TEXT NOT NULL DEFAULT '',
  current_tag     TEXT NOT NULL DEFAULT '',
  latest_version  TEXT NOT NULL DEFAULT '',
  latest_tag      TEXT NOT NULL DEFAULT '',
  status          TEXT NOT NULL DEFAULT 'wait',
  source          TEXT NOT NULL DEFAULT '',
  url             TEXT NOT NULL DEFAULT '',
  notes           TEXT NOT NULL DEFAULT '',
  skip_reason     TEXT NOT NULL DEFAULT '',
  probe_error     TEXT NOT NULL DEFAULT '',
  probed_at       TEXT NOT NULL DEFAULT '',
  updated_at      TEXT NOT NULL DEFAULT '',
  PRIMARY KEY (network, env, program)
);
CREATE INDEX idx_client_versions_status ON client_versions(status);

CREATE TABLE client_purged (
  network   TEXT PRIMARY KEY,
  purged_at TEXT NOT NULL DEFAULT ''
);
