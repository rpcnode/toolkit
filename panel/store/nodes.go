package store

import (
	"database/sql"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
)

// LegacyNodeSlug is the pre-UUID id shape (bitcoin-mainnet). Kept for one-release resolve.
func LegacyNodeSlug(network, env string) string {
	network = strings.TrimSpace(strings.ToLower(network))
	env = strings.TrimSpace(env)
	if network == "" {
		network = "tron"
	}
	if env == "" {
		env = "mainnet"
	}
	return network + "-" + env
}

// CanonicalNodeID is deprecated — node primary keys are UUIDs. Alias of LegacyNodeSlug.
func CanonicalNodeID(network, env string) string {
	return LegacyNodeSlug(network, env)
}

func IsNodeUUID(id string) bool {
	id = strings.TrimSpace(id)
	if id == "" {
		return false
	}
	_, err := uuid.Parse(id)
	return err == nil
}

func NewNodeID() string {
	return uuid.NewString()
}

// nodeSelectCols — keep scanNode / ListNodeViews in lockstep.
const nodeSelectCols = `id, server_id, name, network, env, public_port, agent_port, node_http_port, p2p_port,
       agent_url, status, client_version, client_latest, client_update_available,
       COALESCE(disk_layout_json,''), COALESCE(install_started_at,''), COALESCE(synced_at,''), created_at, updated_at`

const nodeViewSelectCols = `n.id, n.server_id, n.name, n.network, n.env, n.public_port, n.agent_port, n.node_http_port, n.p2p_port,
       n.agent_url, n.status, n.client_version, n.client_latest, n.client_update_available,
       COALESCE(n.disk_layout_json,''), COALESCE(n.install_started_at,''), COALESCE(n.synced_at,''), n.created_at, n.updated_at,
       COALESCE(s.phase,''), COALESCE(s.label,''), COALESCE(s.detail,''), s.height, s.snapshot_pct,
       COALESCE(s.error,''), COALESCE(s.collected_at,''), COALESCE(s.last_seen_at,''), COALESCE(s.rpc_proxy,''),
       COALESCE(s.raw_json,'')`

func defaultNodeName(network, env string) string {
	network = strings.TrimSpace(network)
	env = strings.TrimSpace(env)
	if network == "" {
		network = "tron"
	}
	if env == "" {
		env = "mainnet"
	}
	runes := []rune(network)
	if len(runes) > 0 {
		runes[0] = []rune(strings.ToUpper(string(runes[0])))[0]
		network = string(runes)
	}
	return network + " " + env
}

func (db *DB) ListNodes() ([]Node, error) {
	rows, err := db.sql.Query(`
SELECT `+nodeSelectCols+`
FROM nodes ORDER BY network, env, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Node{}
	for rows.Next() {
		n, err := scanNode(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, rows.Err()
}

func (db *DB) ListNodeViews() ([]NodeView, error) {
	rows, err := db.sql.Query(`
SELECT ` + nodeViewSelectCols + `
FROM nodes n
LEFT JOIN node_status s ON s.node_id = n.id
ORDER BY n.network, n.env, n.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []NodeView{}
	for rows.Next() {
		var v NodeView
		var created, updated, collected, lastSeen, rpcProxy, diskLayoutJSON, rawJSON, installStarted, syncedAt string
		var height sql.NullInt64
		var snapPct sql.NullFloat64
		var updAvail int
		err := rows.Scan(
			&v.ID, &v.ServerID, &v.Name, &v.Network, &v.Env,
			&v.PublicPort, &v.AgentPort, &v.NodeHTTPPort, &v.P2PPort,
			&v.AgentURL, &v.Status, &v.ClientVersion, &v.ClientLatest, &updAvail,
			&diskLayoutJSON, &installStarted, &syncedAt, &created, &updated,
			&v.LifecyclePhase, &v.LifecycleLabel, &v.LifecycleDetail,
			&height, &snapPct, &v.StatusError, &collected, &lastSeen, &rpcProxy, &rawJSON,
		)
		if err != nil {
			return nil, err
		}
		v.ClientUpdateAvailable = updAvail != 0
		v.DiskLayout = ParseDiskLayoutJSON(diskLayoutJSON)
		v.InstallStartedAt = strings.TrimSpace(installStarted)
		v.SyncedAt = strings.TrimSpace(syncedAt)
		v.CreatedAt = parseTime(created)
		v.UpdatedAt = parseTime(updated)
		v.Height = scanNullInt64(height)
		v.SnapshotProgress = scanNullFloat64(snapPct)
		if collected != "" {
			v.StatusAt = collected
		} else {
			v.StatusAt = lastSeen
		}
		busy := false
		switch strings.ToLower(v.LifecyclePhase) {
		case "installing", "starting", "syncing", "updating", "restarting", "removing":
			busy = true
		}
		v.LifecycleBusy = busy
		// Successful collector poll clears status_error; non-empty → last poll failed.
		reachable := strings.TrimSpace(v.StatusError) == "" && strings.TrimSpace(v.StatusAt) != ""
		v.AgentReachable = &reachable
		if rp := ParseRPCProxyJSON(rpcProxy); rp != nil {
			v.RPCProxy = rp
		}
		if nn := ParseNodeNetFromStatusJSON(rawJSON); nn != nil {
			v.NodeNet = nn
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

func (db *DB) GetNode(id string) (Node, bool, error) {
	id = strings.TrimSpace(id)
	if id == "" {
		return Node{}, false, nil
	}
	row := db.sql.QueryRow(`
SELECT `+nodeSelectCols+`
FROM nodes WHERE id=?`, id)
	n, err := scanNode(row)
	if err == sql.ErrNoRows {
		// One-release: old bookmarks / ?node=bitcoin-mainnet when unique.
		return db.FindNodeByLegacySlug(id)
	}
	if err != nil {
		return Node{}, false, err
	}
	return n, true, nil
}

// FindNodeByLegacySlug resolves network-env slug when exactly one node matches.
func (db *DB) FindNodeByLegacySlug(slug string) (Node, bool, error) {
	slug = strings.TrimSpace(strings.ToLower(slug))
	if slug == "" || IsNodeUUID(slug) {
		return Node{}, false, nil
	}
	network, env, ok := splitLegacyNodeSlug(slug)
	if !ok {
		return Node{}, false, nil
	}
	rows, err := db.sql.Query(`
SELECT `+nodeSelectCols+`
FROM nodes WHERE lower(network)=? AND env=?`, network, env)
	if err != nil {
		return Node{}, false, err
	}
	defer rows.Close()
	var matches []Node
	for rows.Next() {
		n, err := scanNode(rows)
		if err != nil {
			return Node{}, false, err
		}
		matches = append(matches, n)
	}
	if err := rows.Err(); err != nil {
		return Node{}, false, err
	}
	if len(matches) != 1 {
		return Node{}, false, nil
	}
	return matches[0], true, nil
}

func splitLegacyNodeSlug(slug string) (network, env string, ok bool) {
	slug = strings.TrimSpace(strings.ToLower(slug))
	parts := strings.SplitN(slug, "-", 2)
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		return "", "", false
	}
	network = parts[0]
	if network == "btc" {
		network = "bitcoin"
	}
	switch network {
	case "tron", "bitcoin":
		return network, parts[1], true
	default:
		return "", "", false
	}
}

func (db *DB) FindNodeByServerEnv(serverID, env string) (Node, bool, error) {
	serverID = strings.TrimSpace(serverID)
	env = strings.TrimSpace(env)
	row := db.sql.QueryRow(`
SELECT `+nodeSelectCols+`
FROM nodes WHERE server_id=? AND env=?`, serverID, env)
	n, err := scanNode(row)
	if err == sql.ErrNoRows {
		return Node{}, false, nil
	}
	if err != nil {
		return Node{}, false, err
	}
	return n, true, nil
}

func (db *DB) FindNodeByServerNetworkEnv(serverID, network, env string) (Node, bool, error) {
	serverID = strings.TrimSpace(serverID)
	network = strings.TrimSpace(strings.ToLower(network))
	env = strings.TrimSpace(env)
	if network == "" {
		return db.FindNodeByServerEnv(serverID, env)
	}
	row := db.sql.QueryRow(`
SELECT `+nodeSelectCols+`
FROM nodes WHERE server_id=? AND lower(network)=? AND env=?`, serverID, network, env)
	n, err := scanNode(row)
	if err == sql.ErrNoRows {
		return Node{}, false, nil
	}
	if err != nil {
		return Node{}, false, err
	}
	return n, true, nil
}

func (db *DB) UpsertNode(w Node) (Node, error) {
	now := time.Now().UTC()
	w.Network = strings.TrimSpace(strings.ToLower(w.Network))
	w.Env = strings.TrimSpace(w.Env)
	w.ServerID = strings.TrimSpace(w.ServerID)
	w.AgentURL = strings.TrimRight(strings.TrimSpace(w.AgentURL), "/")
	w.ID = strings.TrimSpace(w.ID)
	if w.Network == "" {
		w.Network = "tron"
	}
	if w.Env == "" {
		w.Env = "mainnet"
	}
	if w.Name == "" {
		w.Name = defaultNodeName(w.Network, w.Env)
	}

	var prev Node
	found := false

	// Prefer explicit UUID id.
	if w.ID != "" && IsNodeUUID(w.ID) {
		if p, ok, err := db.getNodeExact(w.ID); err != nil {
			return Node{}, err
		} else if ok {
			prev, found = p, true
		}
	}

	// Legacy slug id → resolve existing row (do not keep slug as PK).
	if !found && w.ID != "" && !IsNodeUUID(w.ID) {
		if p, ok, err := db.FindNodeByLegacySlug(w.ID); err != nil {
			return Node{}, err
		} else if ok {
			prev, found = p, true
		}
	}

	if !found && w.ServerID != "" {
		if p, ok, err := db.FindNodeByServerNetworkEnv(w.ServerID, w.Network, w.Env); err != nil {
			return Node{}, err
		} else if ok {
			prev, found = p, true
		}
	}

	if found {
		w.ID = prev.ID
		w.CreatedAt = prev.CreatedAt
		if strings.TrimSpace(w.Name) == "" {
			w.Name = prev.Name
		}
		if w.Status == "" {
			w.Status = prev.Status
		}
		// Fresh Add / reset to awaiting_ports with no ports in the request must NOT
		// inherit leftover agent_port from a prior Confirm — that made the panel talk
		// to a leftover leaf and skip Confirm ports.
		resetAwaitingPorts := strings.EqualFold(strings.TrimSpace(w.Status), "awaiting_ports") &&
			w.PublicPort == 0 && w.AgentPort == 0
		if resetAwaitingPorts {
			w.PublicPort = 0
			w.AgentPort = 0
			w.NodeHTTPPort = 0
			w.P2PPort = 0
			w.AgentURL = ""
			w.DiskLayout = nil // fresh Add — drop prior JBOD confirmation
			w.InstallStartedAt = ""
			w.SyncedAt = ""
		} else {
			if w.PublicPort == 0 {
				w.PublicPort = prev.PublicPort
			}
			if w.AgentPort == 0 {
				w.AgentPort = prev.AgentPort
			}
			if w.NodeHTTPPort == 0 {
				w.NodeHTTPPort = prev.NodeHTTPPort
			}
			if w.P2PPort == 0 {
				w.P2PPort = prev.P2PPort
			}
			if w.AgentURL == "" {
				w.AgentURL = prev.AgentURL
			}
			if w.DiskLayout == nil {
				w.DiskLayout = prev.DiskLayout
			}
			if strings.TrimSpace(w.InstallStartedAt) == "" {
				w.InstallStartedAt = prev.InstallStartedAt
			}
			if strings.TrimSpace(w.SyncedAt) == "" {
				w.SyncedAt = prev.SyncedAt
			}
		}
		if w.ServerID == "" {
			w.ServerID = prev.ServerID
		}
		if strings.TrimSpace(w.ClientVersion) == "" {
			w.ClientVersion = prev.ClientVersion
		}
		if strings.TrimSpace(w.ClientLatest) == "" {
			w.ClientLatest = prev.ClientLatest
		}
		if !w.ClientUpdateAvailable {
			w.ClientUpdateAvailable = prev.ClientUpdateAvailable
		}
	} else {
		if w.ID == "" || !IsNodeUUID(w.ID) {
			w.ID = NewNodeID()
		}
		if w.CreatedAt.IsZero() {
			w.CreatedAt = now
		}
	}

	w.UpdatedAt = now

	updAvail := 0
	if w.ClientUpdateAvailable {
		updAvail = 1
	}
	diskJSON, err := MarshalDiskLayoutJSON(w.DiskLayout)
	if err != nil {
		return Node{}, fmt.Errorf("disk_layout: %w", err)
	}
	_, err = db.sql.Exec(`
INSERT INTO nodes(id, server_id, name, network, env, public_port, agent_port, node_http_port, p2p_port,
                  agent_url, status, client_version, client_latest, client_update_available,
                  disk_layout_json, install_started_at, synced_at, created_at, updated_at)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
ON CONFLICT(id) DO UPDATE SET
  server_id=excluded.server_id, name=excluded.name, network=excluded.network, env=excluded.env,
  public_port=excluded.public_port, agent_port=excluded.agent_port,
  node_http_port=excluded.node_http_port, p2p_port=excluded.p2p_port,
  agent_url=excluded.agent_url, status=excluded.status, updated_at=excluded.updated_at,
  disk_layout_json=excluded.disk_layout_json,
  install_started_at=excluded.install_started_at,
  synced_at=excluded.synced_at,
  client_version=CASE
    WHEN excluded.client_version != '' THEN excluded.client_version
    ELSE nodes.client_version
  END,
  client_latest=CASE
    WHEN excluded.client_latest != '' THEN excluded.client_latest
    ELSE nodes.client_latest
  END,
  client_update_available=excluded.client_update_available`,
		w.ID, w.ServerID, w.Name, w.Network, w.Env,
		w.PublicPort, w.AgentPort, w.NodeHTTPPort, w.P2PPort,
		w.AgentURL, w.Status, w.ClientVersion, w.ClientLatest, updAvail,
		diskJSON, strings.TrimSpace(w.InstallStartedAt), strings.TrimSpace(w.SyncedAt),
		formatTime(w.CreatedAt), formatTime(w.UpdatedAt),
	)
	if err != nil {
		return Node{}, err
	}
	return w, nil
}

// SetNodeDiskLayout persists confirmed multi-disk layout for a node UUID.
func (db *DB) SetNodeDiskLayout(id string, layout map[string]any) error {
	id = strings.TrimSpace(id)
	if id == "" {
		return fmt.Errorf("node id required")
	}
	diskJSON, err := MarshalDiskLayoutJSON(layout)
	if err != nil {
		return err
	}
	res, err := db.sql.Exec(`
UPDATE nodes SET disk_layout_json=?, updated_at=? WHERE id=?`,
		diskJSON, formatTime(time.Now().UTC()), id,
	)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return fmt.Errorf("node not found")
	}
	return nil
}

// SetNodeClientVersion updates fullnode client version reported by the agent.
func (db *DB) SetNodeClientVersion(id, version string) error {
	return db.SetNodeClientUpdateInfo(id, version, "", false)
}

// SetNodeClientUpdateInfo caches local/latest client versions from agent status.
func (db *DB) SetNodeClientUpdateInfo(id, local, latest string, updateAvailable bool) error {
	id = strings.TrimSpace(id)
	local = strings.TrimSpace(local)
	latest = strings.TrimSpace(latest)
	if id == "" {
		return nil
	}
	flag := 0
	if updateAvailable {
		flag = 1
	}
	_, err := db.sql.Exec(`
UPDATE nodes SET
  client_version=CASE WHEN ? != '' THEN ? ELSE client_version END,
  client_latest=CASE WHEN ? != '' THEN ? ELSE client_latest END,
  client_update_available=?,
  updated_at=?
WHERE id=?`,
		local, local, latest, latest, flag, formatTime(time.Now().UTC()), id,
	)
	return err
}

func (db *DB) getNodeExact(id string) (Node, bool, error) {
	row := db.sql.QueryRow(`
SELECT `+nodeSelectCols+`
FROM nodes WHERE id=?`, strings.TrimSpace(id))
	n, err := scanNode(row)
	if err == sql.ErrNoRows {
		return Node{}, false, nil
	}
	if err != nil {
		return Node{}, false, err
	}
	return n, true, nil
}

// RenameNodeID rewrites nodes.id and node_status.node_id.
func (db *DB) RenameNodeID(oldID, newID string) error {
	oldID = strings.TrimSpace(oldID)
	newID = strings.TrimSpace(newID)
	if oldID == "" || newID == "" {
		return fmt.Errorf("rename node: empty id")
	}
	if oldID == newID {
		return nil
	}
	if _, ok, err := db.getNodeExact(oldID); err != nil {
		return err
	} else if !ok {
		return nil
	}
	if _, taken, err := db.getNodeExact(newID); err != nil {
		return err
	} else if taken {
		return fmt.Errorf("rename node: id %q already exists", newID)
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

	if _, err := tx.Exec(`UPDATE nodes SET id=?, updated_at=? WHERE id=?`,
		newID, formatTime(time.Now().UTC()), oldID); err != nil {
		return err
	}
	if _, err := tx.Exec(`UPDATE node_status SET node_id=? WHERE node_id=?`, newID, oldID); err != nil {
		return err
	}

	return tx.Commit()
}

func (db *DB) DeleteNode(id string) (bool, error) {
	res, err := db.sql.Exec(`DELETE FROM nodes WHERE id=?`, strings.TrimSpace(id))
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

func (db *DB) CountNodesByServer(serverID string) (int, error) {
	var n int
	err := db.sql.QueryRow(`SELECT COUNT(*) FROM nodes WHERE server_id=?`, strings.TrimSpace(serverID)).Scan(&n)
	return n, err
}

func (db *DB) NodeIDsByServer(serverID string) ([]string, error) {
	rows, err := db.sql.Query(`SELECT id FROM nodes WHERE server_id=?`, strings.TrimSpace(serverID))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

func scanNode(row rowScanner) (Node, error) {
	var n Node
	var created, updated, diskLayoutJSON, installStarted, syncedAt string
	var updAvail int
	err := row.Scan(
		&n.ID, &n.ServerID, &n.Name, &n.Network, &n.Env,
		&n.PublicPort, &n.AgentPort, &n.NodeHTTPPort, &n.P2PPort,
		&n.AgentURL, &n.Status, &n.ClientVersion, &n.ClientLatest, &updAvail,
		&diskLayoutJSON, &installStarted, &syncedAt, &created, &updated,
	)
	if err != nil {
		return Node{}, err
	}
	n.ClientUpdateAvailable = updAvail != 0
	n.DiskLayout = ParseDiskLayoutJSON(diskLayoutJSON)
	n.InstallStartedAt = strings.TrimSpace(installStarted)
	n.SyncedAt = strings.TrimSpace(syncedAt)
	n.CreatedAt = parseTime(created)
	n.UpdatedAt = parseTime(updated)
	return n, nil
}

// NodeStatusPreInstall — Add/Confirm ports; install has not started.
func NodeStatusPreInstall(status string) bool {
	switch strings.ToLower(strings.TrimSpace(status)) {
	case "", "awaiting_ports", "ready_to_install", "ports_confirmed":
		return true
	default:
		return false
	}
}

// NodeStatusAlreadyWorking — ops-ready helper (existing nodes after upgrade).
func NodeStatusAlreadyWorking(status string) bool {
	switch strings.ToLower(strings.TrimSpace(status)) {
	case "online", "running", "healthy", "working":
		return true
	default:
		return false
	}
}

// StampNodeInstallStarted sets install_started_at once (first Install/provision).
func (db *DB) StampNodeInstallStarted(id string) error {
	return db.stampNodeTimeOnce(id, "install_started_at")
}

// StampNodeSynced sets synced_at once (first honestly-synced).
func (db *DB) StampNodeSynced(id string) error {
	return db.stampNodeTimeOnce(id, "synced_at")
}

// ClearNodeSynced drops a false first-synced stamp (leaf is catching up).
func (db *DB) ClearNodeSynced(id string) error {
	id = strings.TrimSpace(id)
	if id == "" {
		return nil
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	_, err := db.sql.Exec(`UPDATE nodes SET synced_at='', updated_at=? WHERE id=? AND TRIM(COALESCE(synced_at,''))!=''`,
		now, id)
	return err
}

func (db *DB) stampNodeTimeOnce(id, column string) error {
	id = strings.TrimSpace(id)
	if id == "" {
		return nil
	}
	switch column {
	case "install_started_at", "synced_at":
	default:
		return fmt.Errorf("unknown stamp column %q", column)
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	_, err := db.sql.Exec(`UPDATE nodes SET `+column+`=?, updated_at=? WHERE id=? AND TRIM(COALESCE(`+column+`,''))=''`,
		now, now, id)
	return err
}
