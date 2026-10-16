-- Outbound HTTP proxy for the node (id from the global proxy list in settings); '' = direct.
ALTER TABLE nodes ADD COLUMN proxy_id TEXT NOT NULL DEFAULT '';
