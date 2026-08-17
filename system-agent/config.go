package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Network         string // "tron" (TRON_NETWORK); looked up in network_profiles.go
	Env             string
	Interval        time.Duration
	StateFile       string
	InstanceFile    string
	RegistryFile    string
	InternalListen  string // e.g. 127.0.0.1:29090 — empty disables; never 8091 (java-tron solidity HTTP)
	UpstreamHost    string
	UpstreamPort    int
	APIListenHost   string
	APIListenPort   int // RPC proxy port; 0 = FullNode direct (no proxy)
	PanelPort       int // Node Agent API
	PanelBase       string
	P2PPort         int
	PublicBase      string
	MaintenanceFile string
	Output          string
	OptDir          string
	EtcDir          string
	DataDir         string
	ToolkitDir      string
	SnapshotLog     string
	SnapshotMarker  string
	SnapshotState   string
	SnapshotURL     string
	UpdaterState    string
	VersionFile     string
	NodeService     string
	SnapshotService string
	APIService      string
	SystemService   string
	GatewayService  string // legacy alias checked too
	// HostTip — multi-chain Server control plane (no single-network lifecycle).
	HostTip bool
}

// isHostTipStateDir — host Server control-plane state (/var/lib/rpcnode/host).
func isHostTipStateDir(dir string) bool {
	d := strings.TrimRight(strings.TrimSpace(dir), "/")
	return d == "/var/lib/rpcnode/host" || strings.HasSuffix(d, "/rpcnode/host")
}

// PublicRPCPort — client-facing FullNode HTTP. When TRON_PUBLIC_PORT=0 (no proxy),
// clients hit java-tron on TRON_NODE_HTTP_PORT directly.
func (c Config) PublicRPCPort() int {
	if c.APIListenPort > 0 {
		return c.APIListenPort
	}
	return c.UpstreamPort
}

// AgentAPIPort — Node Agent API listen port (control / panel).
func (c Config) AgentAPIPort() int {
	if c.PanelPort > 0 {
		return c.PanelPort
	}
	return c.APIListenPort
}

// RPCProxyEnabled — true when api-agent still terminates public RPC.
func (c Config) RPCProxyEnabled() bool {
	return c.APIListenPort > 0
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func mustAtoi(s string, def int) int {
	n, err := strconv.Atoi(s)
	if err != nil {
		return def
	}
	return n
}

func loadConfig() Config {
	rawNetwork := strings.ToLower(strings.TrimSpace(os.Getenv("TRON_NETWORK")))
	stateDir := strings.TrimSpace(os.Getenv("TRON_STATE_DIR"))
	hostTip := rawNetwork == "" && isHostTipStateDir(stateDir)

	network := rawNetwork
	if network == "" && !hostTip {
		network = DefaultNetwork
	}
	if network == "" {
		network = DefaultNetwork // profile lookup needs a catalog id; HostTip gates lifecycle
	}
	env := envFirst(DefaultEnv, "RPCNODE_ENV", "TRON_ENV")
	prof := LookupNetworkProfile(network, env)
	defOpt := fmt.Sprintf("/opt/%s/%s", network, env)
	defData := fmt.Sprintf("/data/%s/%s", network, env)
	if prof.OptPath != "" {
		defOpt = prof.OptPath
	}
	if prof.DataPath != "" {
		defData = prof.DataPath
	}
	opt := envOr("TRON_OPT", defOpt)
	data := envOr("TRON_DATA", defData)
	etcDefault := fmt.Sprintf("/etc/%s/%s", network, env)
	if prof.EtcPath != "" {
		etcDefault = prof.EtcPath
	}
	if hostTip {
		// Tip is multi-chain control plane — never inherit tron/mainnet paths.
		// Empty TRON_NETWORK + TRON_ENV=mainnet used to resolve DataDir=/data/tron/mainnet
		// and disk/preflight then created that tree on every tick.
		if strings.TrimSpace(os.Getenv("TRON_DATA")) == "" {
			data = "/var/lib/rpcnode/host"
		}
		if strings.TrimSpace(os.Getenv("TRON_OPT")) == "" {
			opt = "/var/lib/rpcnode/host"
		}
		if strings.TrimSpace(os.Getenv("TRON_ETC")) == "" {
			etcDefault = "/etc/rpcnode"
		}
	}
	// Default 2s so Node online / getnowblock flips quickly in lifecycle UI after start.
	intervalSec := mustAtoi(envOr("TRON_SYSTEM_AGENT_INTERVAL_SEC", "2"), 2)
	if intervalSec < 1 {
		intervalSec = 1
	}
	defaultState := fmt.Sprintf("/var/lib/rpcnode/%s-%s/agent-state.json", network, env)
	defaultInst := fmt.Sprintf("/var/lib/rpcnode/%s-%s/INSTANCE.json", network, env)
	if hostTip {
		defaultState = "/var/lib/rpcnode/host/agent-state.json"
		defaultInst = "/var/lib/rpcnode/host/INSTANCE.json"
	}
	if stateDir != "" {
		defaultState = filepath.Join(stateDir, "agent-state.json")
		defaultInst = filepath.Join(stateDir, "INSTANCE.json")
	}
	state := envOr("TRON_AGENT_STATE", defaultState)
	if !hostTip && isHostTipStateDir(filepath.Dir(state)) && rawNetwork == "" {
		hostTip = true
	}
	upHost := envOr("TRON_NODE_HTTP_HOST", "127.0.0.1")
	// Bind address 0.0.0.0 is for java-tron listen; agents probe loopback.
	if upHost == "0.0.0.0" || upHost == "::" || upHost == "[::]" {
		upHost = "127.0.0.1"
	}
	defPub := prof.DefaultPublicPort
	if defPub <= 0 {
		defPub = 39090
	}
	defAgent := prof.DefaultAgentPort
	if defAgent <= 0 {
		// Last-resort only — prefer NetworkProfile.DefaultAgentPort (3919x), never legacy 8093.
		defAgent = 39190
	}
	defP2P := prof.DefaultP2PPort
	if defP2P <= 0 {
		defP2P = 18888
	}
	defHTTP := prof.DefaultNodeHTTP
	if defHTTP <= 0 {
		defHTTP = 18090
	}
	if hostTip {
		defHTTP = 0
		defP2P = 0
		defAgent = 0
	}
	// Bitcoin/Solana profile: upstream always from catalog (ignore stale env).
	upPort := mustAtoi(envOr("TRON_NODE_HTTP_PORT", strconv.Itoa(defHTTP)), defHTTP)
	if !hostTip && strings.EqualFold(network, "xrpl") {
		if n := mustAtoi(envOr("TRON_XRPLD_HTTP_PORT", "0"), 0); n > 0 {
			upPort = n
		} else if prof.DefaultNodeHTTP > 0 {
			upPort = prof.DefaultNodeHTTP
		}
	}
	if !hostTip && (strings.EqualFold(network, "bitcoin") || strings.EqualFold(network, "solana") ||
		strings.EqualFold(network, "ethereum") || strings.EqualFold(network, "bsc") ||
		strings.EqualFold(network, "hyperliquid") || strings.EqualFold(network, "arb") ||
		strings.EqualFold(network, "robinhood") ||
		strings.EqualFold(network, "optimism") ||
		strings.EqualFold(network, "base") ||
		strings.EqualFold(network, "etc") ||
		strings.EqualFold(network, "ton")) && prof.DefaultNodeHTTP > 0 {
		upPort = prof.DefaultNodeHTTP
	}
	svcPrefix := prof.ServicePrefix
	if svcPrefix == "" {
		svcPrefix = network
	}
	return Config{
		Network:        network,
		Env:            env,
		Interval:       time.Duration(intervalSec) * time.Second,
		StateFile:      state,
		InstanceFile:   envOr("TRON_INSTANCE_FILE", defaultInst),
		RegistryFile:   envOr("TRON_REGISTRY_FILE", fmt.Sprintf("/etc/rpcnode/instances.d/%s-%s.json", map[bool]string{true: "host", false: network}[hostTip], env)),
		InternalListen: envOr("TRON_SYSTEM_AGENT_LISTEN", "127.0.0.1:29090"),
		UpstreamHost:   upHost,
		UpstreamPort:   upPort,
		APIListenHost:  envFirst("0.0.0.0", "RPCNODE_GATEWAY_LISTEN", "TRON_GATEWAY_LISTEN"),
		APIListenPort: mustAtoi(envFirst(strconv.Itoa(defPub),
			"RPCNODE_PUBLIC_PORT", "TRON_PUBLIC_PORT", "RPCNODE_GATEWAY_PORT", "TRON_GATEWAY_PORT"), defPub),
		PanelPort: mustAtoi(envFirst(strconv.Itoa(defAgent),
			"RPCNODE_AGENT_PORT", "TRON_AGENT_PORT", "RPCNODE_PANEL_PORT", "TRON_PANEL_PORT"), defAgent),
		PanelBase:       envFirst("", "RPCNODE_PANEL_BASE", "PANEL_INGEST_URL", "PANEL_BASE", "TRON_PANEL_BASE"),
		P2PPort:         mustAtoi(envOr("TRON_P2P_PORT", strconv.Itoa(defP2P)), defP2P),
		PublicBase:      envFirst("", "RPCNODE_PUBLIC_BASE", "TRON_PUBLIC_BASE", "PUBLIC_BASE"),
		MaintenanceFile: envOr("TRON_MAINTENANCE_FILE", fmt.Sprintf("/run/%s-%s/maintenance.json", map[bool]string{true: "host", false: network}[hostTip], env)),
		Output:          envOr("TRON_OUTPUT", fmt.Sprintf("%s/output-directory", data)),
		OptDir:          opt,
		EtcDir:          envOr("TRON_ETC", etcDefault),
		DataDir:         data,
		ToolkitDir:      envOr("TOOLKIT_DIR", ""),
		SnapshotLog:     envOr("TRON_SNAPSHOT_LOG", fmt.Sprintf("/var/log/%s/%s-snapshot.log", network, env)),
		SnapshotMarker:  envOr("TRON_SNAPSHOT_MARKER", fmt.Sprintf("%s/.snapshot-ready", data)),
		SnapshotState:   envOr("TRON_SNAPSHOT_STATE", fmt.Sprintf("%s/.snapshot-state.json", data)),
		SnapshotURL:     map[bool]string{true: "", false: resolveSnapshotURL(network, env)}[hostTip],
		UpdaterState:    envOr("TRON_UPDATER_STATE", fmt.Sprintf("%s/.updater-state.json", data)),
		VersionFile:     envOr("TRON_VERSION_FILE", fmt.Sprintf("%s/VERSION", opt)),
		NodeService:     envOr("TRON_SERVICE", fmt.Sprintf("%s-%s", svcPrefix, env)),
		SnapshotService: envOr("TRON_SNAPSHOT_SERVICE", fmt.Sprintf("%s-%s-snapshot", svcPrefix, env)),
		APIService:      envOr("TRON_API_SERVICE", fmt.Sprintf("%s-%s-api", svcPrefix, env)),
		SystemService:   envOr("TRON_SYSTEM_SERVICE", fmt.Sprintf("%s-%s-system", svcPrefix, env)),
		GatewayService:  envOr("TRON_GATEWAY_SERVICE", fmt.Sprintf("%s-%s-gateway", svcPrefix, env)),
		HostTip:         hostTip,
	}
}

// defaultSnapshotURL — from NetworkProfile so agent stays truthful when toolkit.env
// omitted TRON_SNAPSHOT_URL (root cause of false "Snapshot skipped for this env").
func defaultSnapshotURL(network, env string) string {
	return LookupNetworkProfile(network, env).DefaultSnapshotURL
}

func resolveSnapshotURL(network, env string) string {
	if v := strings.TrimSpace(os.Getenv("TRON_SNAPSHOT_URL")); v != "" {
		return v
	}
	return defaultSnapshotURL(network, env)
}

func snapshotExplicitlyDisabled() bool {
	flag := strings.ToLower(strings.TrimSpace(envOr("TRON_SNAPSHOT_ENABLED", "")))
	switch flag {
	case "0", "false", "no", "off":
		return true
	default:
		return false
	}
}

// snapshotFeatureEnabled — false only when TRON_SNAPSHOT_ENABLED=0 or URL still empty.
func snapshotFeatureEnabled(cfg Config) bool {
	if snapshotExplicitlyDisabled() {
		return false
	}
	return strings.TrimSpace(cfg.SnapshotURL) != ""
}
