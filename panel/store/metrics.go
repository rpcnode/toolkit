package store

import (
	"database/sql"
	"strings"
	"time"
)

const MetricsStaleAfter = 3 * time.Minute

func (db *DB) UpsertServerMetrics(m ServerMetrics) (ServerMetrics, error) {
	now := time.Now().UTC()
	if m.CollectedAt.IsZero() {
		m.CollectedAt = now
	}
	m.LastSeenAt = now
	if strings.TrimSpace(m.ServerID) == "" {
		id, err := db.FindServerIDByAgentURLOrToken(m.AgentURL, "")
		if err != nil {
			return m, err
		}
		m.ServerID = id
	}
	if m.ServerID == "" {
		return m, nil
	}

	_, err := db.sql.Exec(`
INSERT INTO server_metrics(
  server_id, host_id, agent_url, cpu_pct, load_pct, ncpu, mem_pct, mem_used_mb, mem_total_mb,
  disk_used_pct, disk_used_gb, disk_total_gb, load_1, os, arch, collected_at, last_seen_at)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
ON CONFLICT(server_id) DO UPDATE SET
  host_id=excluded.host_id, agent_url=excluded.agent_url,
  cpu_pct=excluded.cpu_pct, load_pct=excluded.load_pct, ncpu=excluded.ncpu,
  mem_pct=excluded.mem_pct,
  mem_used_mb=excluded.mem_used_mb, mem_total_mb=excluded.mem_total_mb,
  disk_used_pct=excluded.disk_used_pct, disk_used_gb=excluded.disk_used_gb,
  disk_total_gb=excluded.disk_total_gb, load_1=excluded.load_1,
  os=excluded.os, arch=excluded.arch,
  collected_at=excluded.collected_at, last_seen_at=excluded.last_seen_at`,
		m.ServerID, m.HostID, m.AgentURL, m.CPUPct, m.LoadPct, m.NCPU, m.MemPct, m.MemUsedMB, m.MemTotalMB,
		m.DiskUsedPct, m.DiskUsedGB, m.DiskTotalGB, m.Load1, m.OS, m.Arch,
		formatTime(m.CollectedAt), formatTime(m.LastSeenAt),
	)
	return m, err
}

func (db *DB) GetServerMetrics(serverID, agentURL string) (ServerMetrics, bool, error) {
	serverID = strings.TrimSpace(serverID)
	agentURL = strings.TrimRight(strings.TrimSpace(agentURL), "/")
	if serverID != "" {
		m, ok, err := db.getMetricsByServer(serverID)
		if err != nil || ok {
			return m, ok, err
		}
	}
	if agentURL != "" {
		row := db.sql.QueryRow(`
SELECT server_id, host_id, agent_url, cpu_pct, load_pct, ncpu, mem_pct, mem_used_mb, mem_total_mb,
       disk_used_pct, disk_used_gb, disk_total_gb, load_1, os, arch, collected_at, last_seen_at
FROM server_metrics WHERE agent_url=?`, agentURL)
		return scanMetrics(row)
	}
	return ServerMetrics{}, false, nil
}

func (db *DB) getMetricsByServer(serverID string) (ServerMetrics, bool, error) {
	row := db.sql.QueryRow(`
SELECT server_id, host_id, agent_url, cpu_pct, load_pct, ncpu, mem_pct, mem_used_mb, mem_total_mb,
       disk_used_pct, disk_used_gb, disk_total_gb, load_1, os, arch, collected_at, last_seen_at
FROM server_metrics WHERE server_id=?`, serverID)
	return scanMetrics(row)
}

func scanMetrics(row rowScanner) (ServerMetrics, bool, error) {
	var m ServerMetrics
	var collected, lastSeen string
	err := row.Scan(
		&m.ServerID, &m.HostID, &m.AgentURL, &m.CPUPct, &m.LoadPct, &m.NCPU, &m.MemPct, &m.MemUsedMB, &m.MemTotalMB,
		&m.DiskUsedPct, &m.DiskUsedGB, &m.DiskTotalGB, &m.Load1, &m.OS, &m.Arch,
		&collected, &lastSeen,
	)
	if err == sql.ErrNoRows {
		return ServerMetrics{}, false, nil
	}
	if err != nil {
		return ServerMetrics{}, false, err
	}
	m.CollectedAt = parseTime(collected)
	m.LastSeenAt = parseTime(lastSeen)
	return m, true, nil
}

func MetricsStatus(m ServerMetrics) string {
	if m.LastSeenAt.IsZero() {
		return "unknown"
	}
	age := time.Since(m.LastSeenAt)
	if age <= MetricsStaleAfter {
		return "online"
	}
	if age <= 15*time.Minute {
		return "stale"
	}
	return "offline"
}

func (db *DB) UpsertNodeStatus(st NodeStatus) error {
	now := time.Now().UTC()
	if st.CollectedAt.IsZero() {
		st.CollectedAt = now
	}
	st.LastSeenAt = now
	_, err := db.sql.Exec(`
INSERT INTO node_status(node_id, phase, label, detail, height, snapshot_pct, health, raw_json, rpc_proxy, error, collected_at, last_seen_at)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
ON CONFLICT(node_id) DO UPDATE SET
  phase=excluded.phase, label=excluded.label, detail=excluded.detail,
  height=excluded.height, snapshot_pct=excluded.snapshot_pct, health=excluded.health,
  raw_json=excluded.raw_json, rpc_proxy=excluded.rpc_proxy, error=excluded.error,
  collected_at=excluded.collected_at, last_seen_at=excluded.last_seen_at`,
		st.NodeID, st.Phase, st.Label, st.Detail,
		nullInt64(st.Height), nullFloat64(st.SnapshotPct),
		st.Health, st.RawJSON, st.RPCProxy, st.Error,
		formatTime(st.CollectedAt), formatTime(st.LastSeenAt),
	)
	return err
}

func (db *DB) GetNodeStatus(nodeID string) (NodeStatus, bool, error) {
	row := db.sql.QueryRow(`
SELECT node_id, phase, label, detail, height, snapshot_pct, health, raw_json, COALESCE(rpc_proxy,''), error, collected_at, last_seen_at
FROM node_status WHERE node_id=?`, strings.TrimSpace(nodeID))
	var st NodeStatus
	var height sql.NullInt64
	var snap sql.NullFloat64
	var collected, lastSeen string
	err := row.Scan(
		&st.NodeID, &st.Phase, &st.Label, &st.Detail, &height, &snap,
		&st.Health, &st.RawJSON, &st.RPCProxy, &st.Error, &collected, &lastSeen,
	)
	if err == sql.ErrNoRows {
		return NodeStatus{}, false, nil
	}
	if err != nil {
		return NodeStatus{}, false, err
	}
	st.Height = scanNullInt64(height)
	st.SnapshotPct = scanNullFloat64(snap)
	st.CollectedAt = parseTime(collected)
	st.LastSeenAt = parseTime(lastSeen)
	return st, true, nil
}

// MarkNodeUnreachable records a poll/proxy failure without wiping the last good
// lifecycle snapshot (phase/label/detail/height/raw_json). Panel cache stays
// renderable while the agent is down.
func (db *DB) MarkNodeUnreachable(nodeID, errMsg string) error {
	nodeID = strings.TrimSpace(nodeID)
	if nodeID == "" {
		return nil
	}
	errMsg = strings.TrimSpace(errMsg)
	if errMsg == "" {
		errMsg = "agent_unreachable"
	}
	now := time.Now().UTC()
	st, ok, err := db.GetNodeStatus(nodeID)
	if err != nil {
		return err
	}
	if ok && (strings.TrimSpace(st.RawJSON) != "" || strings.TrimSpace(st.Phase) != "") {
		_, err = db.sql.Exec(`
UPDATE node_status SET error=?, last_seen_at=? WHERE node_id=?`,
			errMsg, formatTime(now), nodeID)
		return err
	}
	return db.UpsertNodeStatus(NodeStatus{
		NodeID:      nodeID,
		Phase:       "error",
		Label:       "agent error",
		Detail:      errMsg,
		Error:       errMsg,
		Health:      "agent_unreachable",
		CollectedAt: now,
		LastSeenAt:  now,
	})
}
