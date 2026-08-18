package store

import (
	"database/sql"
	"embed"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

//go:embed schema.sql
var schemaFS embed.FS

const schemaVersion = 12

type DB struct {
	sql  *sql.DB
	path string
}

func Open(path string) (*DB, error) {
	path = strings.TrimSpace(path)
	if path == "" {
		path = "/var/lib/rpcnode/panel.db"
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, err
	}

	if err := recoverMalformedDB(path); err != nil {
		return nil, fmt.Errorf("panel.db recover: %w", err)
	}

	dsn := path + "?_pragma=busy_timeout(8000)&_pragma=foreign_keys(1)&_pragma=synchronous(NORMAL)"
	sqlDB, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	sqlDB.SetMaxOpenConns(1) // SQLite writer serialization (panel + collector = 2 processes)
	sqlDB.SetMaxIdleConns(1)
	sqlDB.SetConnMaxLifetime(0)

	db := &DB{sql: sqlDB, path: path}
	if err := db.migrate(); err != nil {
		_ = sqlDB.Close()
		return nil, err
	}
	if _, err := sqlDB.Exec(`PRAGMA journal_mode=WAL`); err != nil {
		_ = sqlDB.Close()
		return nil, err
	}
	_, _ = sqlDB.Exec(`PRAGMA wal_autocheckpoint=1000`)
	_ = os.Chmod(path, 0o600)

	if ok, detail := sqliteIntegrityOK(path); !ok {
		log.Printf("panel.db integrity still bad after open: %s", detail)
	}

	return db, nil
}

func (db *DB) Close() error {
	if db == nil || db.sql == nil {
		return nil
	}
	return db.sql.Close()
}

func (db *DB) Path() string { return db.path }

func (db *DB) migrate() error {
	raw, err := schemaFS.ReadFile("schema.sql")
	if err != nil {
		return err
	}
	if _, err := db.sql.Exec(string(raw)); err != nil {
		return fmt.Errorf("schema: %w", err)
	}
	var ver int
	err = db.sql.QueryRow(`SELECT version FROM schema_version LIMIT 1`).Scan(&ver)
	if err == sql.ErrNoRows {
		_, err = db.sql.Exec(`INSERT INTO schema_version(version) VALUES(?)`, schemaVersion)
		if err != nil {
			return err
		}
		ver = schemaVersion
	} else if err != nil {
		// table empty after CREATE
		_, err = db.sql.Exec(`INSERT INTO schema_version(version) VALUES(?)`, schemaVersion)
		if err != nil {
			return err
		}
		ver = schemaVersion
	}

	if ver < 2 {
		if err := db.ensureColumn("servers", "agent_version", "TEXT NOT NULL DEFAULT ''"); err != nil {
			return err
		}
	}

	if ver < 3 {
		if err := db.fixLegacyBitcoinServerIDs(); err != nil {
			return fmt.Errorf("migrate v3 bitcoin server ids: %w", err)
		}
	}

	if ver < 4 {
		if err := db.migrateNodesToUUID(); err != nil {
			return fmt.Errorf("migrate v4 node uuids: %w", err)
		}
	}

	// v5: rebuild UNIQUE(server_id, env) → UNIQUE(server_id, network, env).
	// (v4 detector false-positive skipped rebuild because CREATE TABLE lists a network column.)
	if ver < 5 {
		if err := db.ensureNodesUniqueServerNetworkEnv(); err != nil {
			return fmt.Errorf("migrate v5 nodes unique: %w", err)
		}
	}

	if ver < 6 {
		if err := db.ensureColumn("server_metrics", "load_pct", "REAL NOT NULL DEFAULT 0"); err != nil {
			return err
		}
		if err := db.ensureColumn("server_metrics", "ncpu", "INTEGER NOT NULL DEFAULT 0"); err != nil {
			return err
		}
	}

	if ver < 7 {
		if err := db.ensureColumn("nodes", "client_version", "TEXT NOT NULL DEFAULT ''"); err != nil {
			return err
		}
	}

	if ver < 8 {
		if err := db.ensureColumn("nodes", "client_latest", "TEXT NOT NULL DEFAULT ''"); err != nil {
			return err
		}
		if err := db.ensureColumn("nodes", "client_update_available", "INTEGER NOT NULL DEFAULT 0"); err != nil {
			return err
		}
	}

	if ver < 9 {
		if err := db.ensureColumn("node_status", "rpc_proxy", "TEXT NOT NULL DEFAULT ''"); err != nil {
			return err
		}
	}

	if ver < 10 {
		if err := db.ensureColumn("nodes", "disk_layout_json", "TEXT NOT NULL DEFAULT ''"); err != nil {
			return err
		}
	}

	if ver < 11 {
		if err := db.ensureColumn("nodes", "install_started_at", "TEXT NOT NULL DEFAULT ''"); err != nil {
			return err
		}
		if err := db.ensureColumn("nodes", "synced_at", "TEXT NOT NULL DEFAULT ''"); err != nil {
			return err
		}
	}

	if ver < 12 {
		if err := db.ensureColumn("nodes", "install_options_json", "TEXT NOT NULL DEFAULT ''"); err != nil {
			return err
		}
	}

	if ver < schemaVersion {
		_, err = db.sql.Exec(`UPDATE schema_version SET version=?`, schemaVersion)
		return err
	}
	return nil
}

// migrateNodesToUUID assigns UUID v4 primary keys to slug ids (bitcoin-mainnet, …).
func (db *DB) migrateNodesToUUID() error {
	rows, err := db.sql.Query(`SELECT id FROM nodes ORDER BY id`)
	if err != nil {
		return err
	}
	defer rows.Close()

	var todo []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return err
		}
		if !IsNodeUUID(id) {
			todo = append(todo, id)
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}

	for _, oldID := range todo {
		newID := NewNodeID()
		if err := db.RenameNodeID(oldID, newID); err != nil {
			return fmt.Errorf("rename node %q → %q: %w", oldID, newID, err)
		}
	}
	return nil
}

// ensureNodesUniqueServerNetworkEnv rebuilds nodes when legacy UNIQUE(server_id, env) is present.
func (db *DB) ensureNodesUniqueServerNetworkEnv() error {
	hasLegacy, err := db.nodesHasLegacyServerEnvUnique()
	if err != nil {
		return err
	}
	if !hasLegacy {
		return nil
	}

	if _, err := db.sql.Exec(`PRAGMA foreign_keys=OFF`); err != nil {
		return err
	}
	defer func() { _, _ = db.sql.Exec(`PRAGMA foreign_keys=ON`) }()

	tx, err := db.sql.Begin()
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	if _, err := tx.Exec(`
CREATE TABLE nodes_v4 (
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
  created_at      TEXT NOT NULL,
  updated_at      TEXT NOT NULL,
  UNIQUE (server_id, network, env)
)`); err != nil {
		return err
	}
	if _, err := tx.Exec(`
INSERT INTO nodes_v4(id, server_id, name, network, env, public_port, agent_port, node_http_port, p2p_port,
                     agent_url, status, created_at, updated_at)
SELECT id, server_id, name, network, env, public_port, agent_port, node_http_port, p2p_port,
       agent_url, status, created_at, updated_at FROM nodes`); err != nil {
		return err
	}
	if _, err := tx.Exec(`DROP TABLE nodes`); err != nil {
		return err
	}
	if _, err := tx.Exec(`ALTER TABLE nodes_v4 RENAME TO nodes`); err != nil {
		return err
	}
	if _, err := tx.Exec(`CREATE INDEX IF NOT EXISTS idx_nodes_server ON nodes(server_id)`); err != nil {
		return err
	}

	return tx.Commit()
}

func (db *DB) nodesHasLegacyServerEnvUnique() (bool, error) {
	var tableSQL string
	err := db.sql.QueryRow(`SELECT sql FROM sqlite_master WHERE type='table' AND name='nodes'`).Scan(&tableSQL)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	low := strings.ToLower(tableSQL)
	// Target constraint text only — ignore the "network" column definition.
	if strings.Contains(low, "unique (server_id, network, env)") ||
		strings.Contains(low, "unique(server_id, network, env)") {
		return false, nil
	}
	if strings.Contains(low, "unique (server_id, env)") ||
		strings.Contains(low, "unique(server_id, env)") {
		return true, nil
	}
	return false, nil
}

func (db *DB) ensureColumn(table, column, decl string) error {
	rows, err := db.sql.Query(fmt.Sprintf(`PRAGMA table_info(%s)`, table))
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var cid int
		var name, ctype string
		var notnull, pk int
		var dflt sql.NullString
		if err := rows.Scan(&cid, &name, &ctype, &notnull, &dflt, &pk); err != nil {
			return err
		}
		if name == column {
			return nil
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}
	_, err = db.sql.Exec(fmt.Sprintf(`ALTER TABLE %s ADD COLUMN %s %s`, table, column, decl))
	return err
}

func (db *DB) ServerCount() (int, error) {
	var n int
	err := db.sql.QueryRow(`SELECT COUNT(*) FROM servers`).Scan(&n)
	return n, err
}

func formatTime(t time.Time) string {
	if t.IsZero() {
		return time.Now().UTC().Format(time.RFC3339Nano)
	}
	return t.UTC().Format(time.RFC3339Nano)
}

func parseTime(s string) time.Time {
	s = strings.TrimSpace(s)
	if s == "" {
		return time.Time{}
	}
	for _, layout := range []string{time.RFC3339Nano, time.RFC3339} {
		if t, err := time.Parse(layout, s); err == nil {
			return t
		}
	}
	return time.Time{}
}

func nullInt64(p *int64) sql.NullInt64 {
	if p == nil {
		return sql.NullInt64{}
	}
	return sql.NullInt64{Int64: *p, Valid: true}
}

func nullFloat64(p *float64) sql.NullFloat64 {
	if p == nil {
		return sql.NullFloat64{}
	}
	return sql.NullFloat64{Float64: *p, Valid: true}
}

func scanNullInt64(n sql.NullInt64) *int64 {
	if !n.Valid {
		return nil
	}
	v := n.Int64
	return &v
}

func scanNullFloat64(n sql.NullFloat64) *float64 {
	if !n.Valid {
		return nil
	}
	v := n.Float64
	return &v
}
