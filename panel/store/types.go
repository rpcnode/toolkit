package store

import (
	"encoding/json"
	"strings"
	"time"
)

// Server — registered host agent (panel Servers page).
type Server struct {
	ID           string    `json:"id"`
	Name         string    `json:"name"`
	Env          string    `json:"env"`
	Network      string    `json:"network"`
	AgentURL     string    `json:"agent_url"`
	AgentKey     string    `json:"agent_key,omitempty"`
	OS           string    `json:"os,omitempty"`
	Arch         string    `json:"arch,omitempty"`
	OSPretty     string    `json:"os_pretty,omitempty"`
	AgentVersion string    `json:"agent_version,omitempty"`
	CreatedAt    time.Time `json:"created_at"`
	UpdatedAt    time.Time `json:"updated_at"`
}

// Node — chain env attached to a server (panel Nodes page / workload).
type Node struct {
	ID                    string         `json:"id"`
	ServerID              string         `json:"server_id"`
	Name                  string         `json:"name,omitempty"`
	Network               string         `json:"network"`
	Env                   string         `json:"env"`
	PublicPort            int            `json:"public_port,omitempty"`
	AgentPort             int            `json:"agent_port,omitempty"`
	NodeHTTPPort          int            `json:"node_http_port,omitempty"`
	P2PPort               int            `json:"p2p_port,omitempty"`
	AgentURL              string         `json:"agent_url,omitempty"`
	Status                string         `json:"status,omitempty"`
	ClientVersion         string         `json:"client_version,omitempty"`
	ClientLatest          string         `json:"client_latest,omitempty"`
	ClientUpdateAvailable bool           `json:"client_update_available,omitempty"`
	// DiskLayout — confirmed multi-disk roles→paths (wizard / provision). Nil when unset.
	DiskLayout map[string]any `json:"disk_layout,omitempty"`
	// InstallStartedAt — first Install/provision (empty until then). RFC3339.
	InstallStartedAt string `json:"install_started_at,omitempty"`
	// SyncedAt — first honestly-synced / working (empty until then). RFC3339.
	SyncedAt  string    `json:"synced_at,omitempty"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

// MarshalDiskLayoutJSON stores a layout map as TEXT for SQLite.
func MarshalDiskLayoutJSON(layout map[string]any) (string, error) {
	if layout == nil {
		return "", nil
	}
	b, err := json.Marshal(layout)
	if err != nil {
		return "", err
	}
	s := string(b)
	if s == "" || s == "null" || s == "{}" {
		return "", nil
	}
	return s, nil
}

// ParseDiskLayoutJSON unmarshals nodes.disk_layout_json.
func ParseDiskLayoutJSON(raw string) map[string]any {
	raw = strings.TrimSpace(raw)
	if raw == "" || raw == "null" || raw == "{}" {
		return nil
	}
	var out map[string]any
	if err := json.Unmarshal([]byte(raw), &out); err != nil || len(out) == 0 {
		return nil
	}
	return out
}

// ServerMetrics — last host snapshot.
type ServerMetrics struct {
	ServerID    string    `json:"server_id,omitempty"`
	HostID      string    `json:"host_id,omitempty"`
	AgentURL    string    `json:"agent_url,omitempty"`
	CPUPct      float64   `json:"cpu_pct"`
	LoadPct     float64   `json:"load_pct,omitempty"`
	NCPU        int       `json:"ncpu,omitempty"`
	MemPct      float64   `json:"mem_pct"`
	MemUsedMB   float64   `json:"mem_used_mb"`
	MemTotalMB  float64   `json:"mem_total_mb"`
	DiskUsedPct float64   `json:"disk_used_pct"`
	DiskUsedGB  float64   `json:"disk_used_gb"`
	DiskTotalGB float64   `json:"disk_total_gb"`
	Load1       float64   `json:"load_1"`
	OS          string    `json:"os,omitempty"`
	Arch        string    `json:"arch,omitempty"`
	CollectedAt time.Time `json:"collected_at"`
	LastSeenAt  time.Time `json:"last_seen_at"`
}

// RPCProxyStats — Go fullnode proxy traffic (from leaf api-agent metrics).
// Keep zeros in JSON (no omitempty on gauges) so UI/collector always see rps/latency keys.
type RPCProxyStats struct {
	RPS1m          float64 `json:"rps_1m"`
	RPS5m          float64 `json:"rps_5m"`
	InFlight       int64   `json:"in_flight"`
	Total          int64   `json:"total"`
	LatencyP50Ms   float64 `json:"latency_p50_ms"`
	LatencyP95Ms   float64 `json:"latency_p95_ms"`
	Errors4xx      int64   `json:"errors_4xx"`
	Errors5xx      int64   `json:"errors_5xx"`
	UpstreamErrors int64   `json:"upstream_errors"`
	HTTP502        int64   `json:"http_502"`
	HTTP503        int64   `json:"http_503"`
}

// NodeNetStats — per-node systemd IPAccounting rates (from leaf status / metrics).
type NodeNetStats struct {
	RxMbps  float64 `json:"node_net_rx_mbps"`
	TxMbps  float64 `json:"node_net_tx_mbps"`
	RxBytes uint64  `json:"node_net_rx_bytes,omitempty"`
	TxBytes uint64  `json:"node_net_tx_bytes,omitempty"`
}

// NodeStatus — last polled agent status summary for list UI.
type NodeStatus struct {
	NodeID      string    `json:"node_id"`
	Phase       string    `json:"phase,omitempty"`
	Label       string    `json:"label,omitempty"`
	Detail      string    `json:"detail,omitempty"`
	Height      *int64    `json:"height,omitempty"`
	SnapshotPct *float64  `json:"snapshot_pct,omitempty"`
	Health      string    `json:"health,omitempty"`
	RawJSON     string    `json:"raw_json,omitempty"`
	RPCProxy    string    `json:"rpc_proxy,omitempty"` // JSON RPCProxyStats
	Error       string    `json:"error,omitempty"`
	CollectedAt time.Time `json:"collected_at"`
	LastSeenAt  time.Time `json:"last_seen_at"`
}

// NodeView — node + cached status for API list.
type NodeView struct {
	Node
	LifecyclePhase   string         `json:"lifecycle_phase,omitempty"`
	LifecycleLabel   string         `json:"lifecycle_label,omitempty"`
	LifecycleDetail  string         `json:"lifecycle_detail,omitempty"`
	LifecycleBusy    bool           `json:"lifecycle_busy,omitempty"`
	Height           *int64         `json:"height,omitempty"`
	SnapshotProgress *float64       `json:"snapshot_progress,omitempty"`
	StatusError      string         `json:"status_error,omitempty"`
	StatusAt         string         `json:"status_at,omitempty"`
	AgentReachable   *bool          `json:"agent_reachable,omitempty"`
	RPCProxy         *RPCProxyStats `json:"rpc_proxy,omitempty"`
	NodeNet          *NodeNetStats  `json:"node_net,omitempty"`
}
