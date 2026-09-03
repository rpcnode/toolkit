-- Host-reported IBD / snap sync progress (0..100). -1 = unknown.
ALTER TABLE nodes ADD COLUMN sync_pct REAL NOT NULL DEFAULT -1;
