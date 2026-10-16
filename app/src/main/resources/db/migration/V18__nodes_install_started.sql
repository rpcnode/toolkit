-- The node page shows "Install" and "Synced" dates; nothing wrote them until now (always blank, shown as a dash).
-- A node that already is past the ports step gets TODAY as its install date (the real date is unknown); from now on the date is
-- written when the install actually starts. A node that is already active gets today as its synced date the same way.
UPDATE nodes SET install_started_at = strftime('%Y-%m-%dT%H:%M:%SZ', 'now') WHERE install_started_at = '' AND status <> 'awaiting_ports';
UPDATE nodes SET synced_at = strftime('%Y-%m-%dT%H:%M:%SZ', 'now') WHERE synced_at = '' AND status = 'active';
