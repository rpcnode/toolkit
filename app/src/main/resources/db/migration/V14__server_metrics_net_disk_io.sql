ALTER TABLE server_metrics ADD COLUMN net_rx_mbps REAL NOT NULL DEFAULT 0;
ALTER TABLE server_metrics ADD COLUMN net_tx_mbps REAL NOT NULL DEFAULT 0;
ALTER TABLE server_metrics ADD COLUMN disk_read_iops REAL NOT NULL DEFAULT 0;
ALTER TABLE server_metrics ADD COLUMN disk_write_iops REAL NOT NULL DEFAULT 0;
ALTER TABLE server_metrics ADD COLUMN disk_read_mb_s REAL NOT NULL DEFAULT 0;
ALTER TABLE server_metrics ADD COLUMN disk_write_mb_s REAL NOT NULL DEFAULT 0;
ALTER TABLE server_metrics ADD COLUMN disk_util_pct REAL NOT NULL DEFAULT 0;
ALTER TABLE server_metrics ADD COLUMN disk_busy TEXT NOT NULL DEFAULT '';
