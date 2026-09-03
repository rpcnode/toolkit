ALTER TABLE servers ADD COLUMN remove_status TEXT NOT NULL DEFAULT '';
ALTER TABLE servers ADD COLUMN deleted_at TEXT NOT NULL DEFAULT '';
CREATE INDEX idx_servers_deleted_at ON servers(deleted_at);
