package store

import (
	"crypto/rand"
	"crypto/subtle"
	"database/sql"
	"encoding/hex"
	"fmt"
	"net/url"
	"strings"
	"time"
	"unicode"
)

func normalizeOS(v string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "linux":
		return "linux"
	case "darwin", "macos", "osx", "mac":
		return "darwin"
	default:
		return strings.ToLower(strings.TrimSpace(v))
	}
}

func normalizeArch(v string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "x86_64", "amd64", "x64":
		return "amd64"
	case "aarch64", "arm64", "armv8", "armv8l":
		return "arm64"
	default:
		return strings.ToLower(strings.TrimSpace(v))
	}
}

func (db *DB) ListServers(stripKeys bool) ([]Server, error) {
	rows, err := db.sql.Query(`
SELECT id, name, env, network, agent_url, agent_key, os, arch, os_pretty, agent_version, created_at, updated_at
FROM servers ORDER BY name COLLATE NOCASE, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Server{}
	for rows.Next() {
		s, err := scanServer(rows)
		if err != nil {
			return nil, err
		}
		if stripKeys {
			s.AgentKey = ""
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

func (db *DB) GetServer(id string) (Server, bool, error) {
	row := db.sql.QueryRow(`
SELECT id, name, env, network, agent_url, agent_key, os, arch, os_pretty, agent_version, created_at, updated_at
FROM servers WHERE id=?`, strings.TrimSpace(id))
	s, err := scanServer(row)
	if err == sql.ErrNoRows {
		return Server{}, false, nil
	}
	if err != nil {
		return Server{}, false, err
	}
	return s, true, nil
}

func (db *DB) UpsertServer(n Server) (Server, error) {
	now := time.Now().UTC()
	n.ID = strings.TrimSpace(n.ID)
	n.AgentURL = strings.TrimRight(strings.TrimSpace(n.AgentURL), "/")
	n.OS = normalizeOS(n.OS)
	n.Arch = normalizeArch(n.Arch)
	n.OSPretty = strings.TrimSpace(n.OSPretty)
	n.AgentVersion = strings.TrimSpace(n.AgentVersion)
	if n.Network == "" {
		n.Network = "tron"
	}
	if n.Env == "" {
		n.Env = "mainnet"
	}

	// Empty ID: update by agent_url if known, otherwise allocate a unique server id.
	// Never default to network-env (e.g. tron-mainnet) — that collapsed every "Add server"
	// into a single row and replaced unrelated hosts.
	if n.ID == "" && n.AgentURL != "" {
		existingID, err := db.FindServerIDByAgentURLOrToken(n.AgentURL, "")
		if err != nil {
			return Server{}, err
		}
		if existingID != "" {
			n.ID = existingID
		}
	}
	if n.ID == "" {
		id, err := db.allocateServerID(n)
		if err != nil {
			return Server{}, err
		}
		n.ID = id
	}
	if n.Name == "" {
		n.Name = n.ID
	}

	prev, ok, err := db.GetServer(n.ID)
	if err != nil {
		return Server{}, err
	}
	if ok {
		n.CreatedAt = prev.CreatedAt
		if n.AgentKey == "" {
			n.AgentKey = prev.AgentKey
		}
		if n.OS == "" {
			n.OS = prev.OS
		}
		if n.Arch == "" {
			n.Arch = prev.Arch
		}
		if n.OSPretty == "" {
			n.OSPretty = prev.OSPretty
		}
		if n.AgentVersion == "" {
			n.AgentVersion = prev.AgentVersion
		}
	} else if n.CreatedAt.IsZero() {
		n.CreatedAt = now
	}
	n.UpdatedAt = now

	_, err = db.sql.Exec(`
INSERT INTO servers(id, name, env, network, agent_url, agent_key, os, arch, os_pretty, agent_version, created_at, updated_at)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
ON CONFLICT(id) DO UPDATE SET
  name=excluded.name, env=excluded.env, network=excluded.network,
  agent_url=excluded.agent_url, agent_key=excluded.agent_key,
  os=excluded.os, arch=excluded.arch, os_pretty=excluded.os_pretty,
  agent_version=excluded.agent_version,
  updated_at=excluded.updated_at`,
		n.ID, n.Name, n.Env, n.Network, n.AgentURL, n.AgentKey,
		n.OS, n.Arch, n.OSPretty, n.AgentVersion, formatTime(n.CreatedAt), formatTime(n.UpdatedAt),
	)
	if err != nil {
		return Server{}, err
	}
	out := n
	out.AgentKey = ""
	return out, nil
}

// allocateServerID picks a unique primary key for a new server row.
func (db *DB) allocateServerID(n Server) (string, error) {
	base := slugServerID(n.Name)
	if base == "" {
		base = slugServerID(hostFromAgentURL(n.AgentURL))
		if base != "" {
			base = "srv-" + base
		}
	}
	if base == "" {
		base = "srv"
	}

	for i := 0; i < 32; i++ {
		id := base
		if i > 0 {
			id = fmt.Sprintf("%s-%d", base, i+1)
		}
		_, ok, err := db.GetServer(id)
		if err != nil {
			return "", err
		}
		if !ok {
			return id, nil
		}
	}

	suf, err := randomHex(4)
	if err != nil {
		return "", err
	}

	return base + "-" + suf, nil
}

func slugServerID(v string) string {
	v = strings.ToLower(strings.TrimSpace(v))
	if v == "" {
		return ""
	}
	var b strings.Builder
	prevHyphen := false
	for _, r := range v {
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r):
			b.WriteRune(r)
			prevHyphen = false
		default:
			if !prevHyphen && b.Len() > 0 {
				b.WriteByte('-')
				prevHyphen = true
			}
		}
	}
	out := strings.Trim(b.String(), "-")
	if len(out) > 48 {
		out = out[:48]
		out = strings.Trim(out, "-")
	}

	return out
}

func hostFromAgentURL(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return ""
	}
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		// bare host:port
		if i := strings.Index(raw, "://"); i >= 0 {
			raw = raw[i+3:]
		}
		raw = strings.Split(raw, "/")[0]
		return strings.TrimSpace(raw)
	}
	return u.Hostname()
}

func randomHex(nBytes int) (string, error) {
	buf := make([]byte, nBytes)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}

	return hex.EncodeToString(buf), nil
}

// SetServerAgentVersion updates only the installed agent version reported by the host.
func (db *DB) SetServerAgentVersion(id, version string) error {
	id = strings.TrimSpace(id)
	version = strings.TrimSpace(version)
	if id == "" || version == "" {
		return nil
	}
	_, err := db.sql.Exec(`
UPDATE servers SET agent_version=?, updated_at=? WHERE id=? AND agent_version != ?`,
		version, formatTime(time.Now().UTC()), id, version,
	)
	return err
}

// SetServerPlatform persists OS/arch from agent metrics/healthz for Servers UI badges.
func (db *DB) SetServerPlatform(id, osName, arch string) error {
	id = strings.TrimSpace(id)
	osName, arch = normalizePlatform(osName, arch)
	if id == "" || (osName == "" && arch == "") {
		return nil
	}
	pretty := strings.TrimSpace(osName + "/" + arch)
	pretty = strings.Trim(pretty, "/")
	_, err := db.sql.Exec(`
UPDATE servers SET os=?, arch=?, os_pretty=?, updated_at=?
WHERE id=? AND (IFNULL(os,'')!=? OR IFNULL(arch,'')!=? OR IFNULL(os_pretty,'')!=?)`,
		osName, arch, pretty, formatTime(time.Now().UTC()),
		id, osName, arch, pretty,
	)
	return err
}

// normalizePlatform maps uname-style values to Go runtime style (linux/amd64).
func normalizePlatform(osName, arch string) (string, string) {
	osName = strings.ToLower(strings.TrimSpace(osName))
	arch = strings.ToLower(strings.TrimSpace(arch))
	switch arch {
	case "x86_64", "x64":
		arch = "amd64"
	case "aarch64":
		arch = "arm64"
	}
	return osName, arch
}

func (db *DB) DeleteServer(id string) (bool, error) {
	res, err := db.sql.Exec(`DELETE FROM servers WHERE id=?`, strings.TrimSpace(id))
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// RenameServerID rewrites servers.id and dependent FK rows (nodes, server_metrics).
// Does not change node primary keys. No-op when oldID == newID or old row missing.
func (db *DB) RenameServerID(oldID, newID string) error {
	oldID = strings.TrimSpace(oldID)
	newID = strings.TrimSpace(newID)
	if oldID == "" || newID == "" {
		return fmt.Errorf("rename server: empty id")
	}
	if oldID == newID {
		return nil
	}

	_, ok, err := db.GetServer(oldID)
	if err != nil {
		return err
	}
	if !ok {
		return nil
	}

	if _, taken, err := db.GetServer(newID); err != nil {
		return err
	} else if taken {
		return fmt.Errorf("rename server: id %q already exists", newID)
	}

	// SQLite rejects PK updates under FK=ON; disable for the rewrite.
	if _, err := db.sql.Exec(`PRAGMA foreign_keys=OFF`); err != nil {
		return err
	}
	defer func() { _, _ = db.sql.Exec(`PRAGMA foreign_keys=ON`) }()

	tx, err := db.sql.Begin()
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	if _, err := tx.Exec(`UPDATE servers SET id=?, updated_at=? WHERE id=?`,
		newID, formatTime(time.Now().UTC()), oldID); err != nil {
		return err
	}
	if _, err := tx.Exec(`UPDATE nodes SET server_id=? WHERE server_id=?`, newID, oldID); err != nil {
		return err
	}
	if _, err := tx.Exec(`UPDATE server_metrics SET server_id=? WHERE server_id=?`, newID, oldID); err != nil {
		return err
	}

	return tx.Commit()
}

// fixLegacyBitcoinServerIDs renames bitcoin hosts that still use tron-* primary keys.
func (db *DB) fixLegacyBitcoinServerIDs() error {
	rows, err := db.sql.Query(`
SELECT id, name FROM servers
WHERE lower(network)='bitcoin' AND id LIKE 'tron-%'
ORDER BY id`)
	if err != nil {
		return err
	}
	defer rows.Close()

	type pair struct{ id, name string }
	var todo []pair
	for rows.Next() {
		var id, name string
		if err := rows.Scan(&id, &name); err != nil {
			return err
		}
		todo = append(todo, pair{id: id, name: name})
	}
	if err := rows.Err(); err != nil {
		return err
	}

	for _, p := range todo {
		target := slugServerID(p.name)
		if target == "" || strings.HasPrefix(target, "tron-") {
			target = "bitcoin-1"
		}
		if _, ok, err := db.GetServer(target); err != nil {
			return err
		} else if ok {
			suf, err := randomHex(3)
			if err != nil {
				return err
			}
			target = target + "-" + suf
		}
		if err := db.RenameServerID(p.id, target); err != nil {
			return err
		}
	}

	return nil
}

func (db *DB) HasAgentKey(token string) (bool, error) {
	token = strings.TrimSpace(token)
	if token == "" {
		return false, nil
	}
	rows, err := db.sql.Query(`SELECT agent_key FROM servers WHERE agent_key != ''`)
	if err != nil {
		return false, err
	}
	defer rows.Close()
	for rows.Next() {
		var k string
		if err := rows.Scan(&k); err != nil {
			return false, err
		}
		if subtleConstantTimeEq(k, token) {
			return true, nil
		}
	}
	return false, rows.Err()
}

func (db *DB) FindServerIDByAgentURLOrToken(agentURL, token string) (string, error) {
	agentURL = strings.TrimRight(strings.TrimSpace(agentURL), "/")
	token = strings.TrimSpace(token)
	servers, err := db.ListServers(false)
	if err != nil {
		return "", err
	}
	for _, n := range servers {
		if agentURL != "" && strings.TrimRight(n.AgentURL, "/") == agentURL {
			return n.ID, nil
		}
		if token != "" && n.AgentKey != "" && subtleConstantTimeEq(n.AgentKey, token) {
			return n.ID, nil
		}
	}
	return "", nil
}

func subtleConstantTimeEq(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

type rowScanner interface {
	Scan(dest ...any) error
}

func scanServer(row rowScanner) (Server, error) {
	var s Server
	var created, updated string
	err := row.Scan(
		&s.ID, &s.Name, &s.Env, &s.Network, &s.AgentURL, &s.AgentKey,
		&s.OS, &s.Arch, &s.OSPretty, &s.AgentVersion, &created, &updated,
	)
	if err != nil {
		return Server{}, err
	}
	s.CreatedAt = parseTime(created)
	s.UpdatedAt = parseTime(updated)
	return s, nil
}
