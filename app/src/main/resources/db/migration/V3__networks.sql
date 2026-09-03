CREATE TABLE networks (
  network   TEXT PRIMARY KEY,
  status    TEXT NOT NULL DEFAULT 'pending',
  added_at  TEXT NOT NULL DEFAULT '',
  notes     TEXT NOT NULL DEFAULT ''
);
