package store

import (
	"database/sql"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// recoverMalformedDB rebuilds path when PRAGMA integrity_check fails.
// Prefer sqlite3 CLI (.recover); fall back to best-effort table copy.
func recoverMalformedDB(path string) error {
	if _, err := os.Stat(path); err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	ok, detail := sqliteIntegrityOK(path)
	if ok {
		return nil
	}

	corrupt := path + ".corrupt-" + time.Now().UTC().Format("20060102T150405Z")
	if err := copyFile(path, corrupt); err != nil {
		return fmt.Errorf("backup corrupt db: %w", err)
	}
	for _, suf := range []string{"-wal", "-shm"} {
		src := path + suf
		if _, err := os.Stat(src); err == nil {
			_ = copyFile(src, corrupt+suf)
		}
	}

	recovered := path + ".recovered"
	_ = os.Remove(recovered)

	var recoverErr error
	if err := execSQLiteRecover(path, recovered); err != nil {
		recoverErr = err
		_ = os.Remove(recovered)
		if err2 := rebuildDBBestEffort(path, recovered); err2 != nil {
			return fmt.Errorf("integrity_check failed (%s); sqlite3 recover: %v; rebuild: %w (backup=%s)",
				detail, recoverErr, err2, corrupt)
		}
	}

	ok2, detail2 := sqliteIntegrityOK(recovered)
	if !ok2 {
		return fmt.Errorf("recover produced bad db (%s); backup=%s", detail2, corrupt)
	}
	for _, suf := range []string{"-wal", "-shm"} {
		_ = os.Remove(path + suf)
	}
	if err := os.Rename(recovered, path); err != nil {
		return err
	}
	return nil
}

func sqliteIntegrityOK(path string) (bool, string) {
	dsn := path + "?_pragma=busy_timeout(2000)&mode=ro"
	sqlDB, err := sql.Open("sqlite", dsn)
	if err != nil {
		return false, err.Error()
	}
	defer sqlDB.Close()
	rows, err := sqlDB.Query(`PRAGMA integrity_check`)
	if err != nil {
		return false, err.Error()
	}
	defer rows.Close()
	var lines []string
	for rows.Next() {
		var line string
		if err := rows.Scan(&line); err != nil {
			return false, err.Error()
		}
		lines = append(lines, line)
	}
	if err := rows.Err(); err != nil {
		return false, err.Error()
	}
	if len(lines) == 1 && strings.EqualFold(strings.TrimSpace(lines[0]), "ok") {
		return true, "ok"
	}
	joined := strings.Join(lines, "; ")
	if len(joined) > 200 {
		joined = joined[:200] + "…"
	}
	return false, joined
}

func execSQLiteRecover(src, dst string) error {
	sqlite3, err := exec.LookPath("sqlite3")
	if err != nil {
		return fmt.Errorf("sqlite3 not in PATH")
	}
	cmd := exec.Command(sqlite3, src, ".recover")
	out, err := cmd.Output()
	if err != nil {
		return fmt.Errorf("sqlite3 .recover: %w", err)
	}
	load := exec.Command(sqlite3, dst)
	stdin, err := load.StdinPipe()
	if err != nil {
		return err
	}
	if err := load.Start(); err != nil {
		_ = stdin.Close()
		return err
	}
	_, werr := stdin.Write(out)
	_ = stdin.Close()
	if err := load.Wait(); err != nil {
		return fmt.Errorf("sqlite3 load recover: %w", err)
	}
	if werr != nil {
		return werr
	}
	return nil
}

// rebuildDBBestEffort copies servers/nodes/(per-id) node_status into a fresh file.
func rebuildDBBestEffort(src, dst string) error {
	srcDB, err := sql.Open("sqlite", src+"?mode=ro&_pragma=busy_timeout(2000)")
	if err != nil {
		return err
	}
	defer srcDB.Close()

	_ = os.Remove(dst)
	dstDB, err := sql.Open("sqlite", dst+"?_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)")
	if err != nil {
		return err
	}
	defer dstDB.Close()

	raw, err := schemaFS.ReadFile("schema.sql")
	if err != nil {
		return err
	}
	if _, err := dstDB.Exec(string(raw)); err != nil {
		return fmt.Errorf("schema: %w", err)
	}
	if _, err := dstDB.Exec(`INSERT INTO schema_version(version) VALUES(?)`, schemaVersion); err != nil {
		return err
	}

	if err := copySQLTable(srcDB, dstDB, `SELECT id, name, env, network, agent_url, agent_key, os, arch, os_pretty, agent_version, created_at, updated_at FROM servers`,
		`INSERT OR REPLACE INTO servers(id, name, env, network, agent_url, agent_key, os, arch, os_pretty, agent_version, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)`,
		12); err != nil {
		return fmt.Errorf("servers: %w", err)
	}
	if err := copySQLTable(srcDB, dstDB, `SELECT id, server_id, name, network, env, public_port, agent_port, node_http_port, p2p_port, agent_url, status, created_at, updated_at FROM nodes`,
		`INSERT OR REPLACE INTO nodes(id, server_id, name, network, env, public_port, agent_port, node_http_port, p2p_port, agent_url, status, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		13); err != nil {
		return fmt.Errorf("nodes: %w", err)
	}

	// node_status often holds the corrupt overflow pages — copy per node_id.
	ids, _ := listNodeIDs(srcDB)
	for _, id := range ids {
		row := srcDB.QueryRow(`SELECT node_id, phase, label, detail, height, snapshot_pct, health, raw_json, error, collected_at, last_seen_at FROM node_status WHERE node_id=?`, id)
		var nodeID, phase, label, detail, health, rawJSON, errMsg, collected, lastSeen string
		var height sql.NullInt64
		var snap sql.NullFloat64
		if err := row.Scan(&nodeID, &phase, &label, &detail, &height, &snap, &health, &rawJSON, &errMsg, &collected, &lastSeen); err != nil {
			continue
		}
		// Cap huge blobs that contributed to freelist corruption.
		if len(rawJSON) > 256<<10 {
			rawJSON = slimRawJSONString(rawJSON)
		}
		_, _ = dstDB.Exec(`INSERT OR REPLACE INTO node_status(node_id, phase, label, detail, height, snapshot_pct, health, raw_json, error, collected_at, last_seen_at)
VALUES(?,?,?,?,?,?,?,?,?,?,?)`,
			nodeID, phase, label, detail, nullInt64Ptr(height), nullFloat64Ptr(snap), health, rawJSON, errMsg, collected, lastSeen)
	}

	_ = copySQLTable(srcDB, dstDB, `SELECT server_id, host_id, agent_url, cpu_pct, load_pct, ncpu, mem_pct, mem_used_mb, mem_total_mb, disk_used_pct, disk_used_gb, disk_total_gb, load_1, os, arch, collected_at, last_seen_at FROM server_metrics`,
		`INSERT OR REPLACE INTO server_metrics(server_id, host_id, agent_url, cpu_pct, load_pct, ncpu, mem_pct, mem_used_mb, mem_total_mb, disk_used_pct, disk_used_gb, disk_total_gb, load_1, os, arch, collected_at, last_seen_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		17)

	_, _ = dstDB.Exec(`PRAGMA journal_mode=WAL`)
	_, _ = dstDB.Exec(`PRAGMA wal_checkpoint(TRUNCATE)`)
	return nil
}

func listNodeIDs(db *sql.DB) ([]string, error) {
	rows, err := db.Query(`SELECT id FROM nodes`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return ids, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

func copySQLTable(src, dst *sql.DB, selectSQL, insertSQL string, cols int) error {
	rows, err := src.Query(selectSQL)
	if err != nil {
		return err
	}
	defer rows.Close()
	args := make([]any, cols)
	ptrs := make([]any, cols)
	for i := range args {
		ptrs[i] = &args[i]
	}
	for rows.Next() {
		if err := rows.Scan(ptrs...); err != nil {
			continue
		}
		if _, err := dst.Exec(insertSQL, args...); err != nil {
			return err
		}
	}
	return rows.Err()
}

func nullInt64Ptr(v sql.NullInt64) any {
	if !v.Valid {
		return nil
	}
	return v.Int64
}

func nullFloat64Ptr(v sql.NullFloat64) any {
	if !v.Valid {
		return nil
	}
	return v.Float64
}

func copyFile(src, dst string) error {
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, in)
	return err
}

func slimRawJSONString(raw string) string {
	if len(raw) <= 64<<10 {
		return raw
	}
	// Best-effort: drop logs.lines / huge tails without full parse failure.
	if i := strings.Index(raw, `"logs"`); i > 0 {
		return raw[:i] + `"logs":{"truncated":true}}`
	}
	if len(raw) > 256<<10 {
		return raw[:256<<10]
	}
	return raw
}
