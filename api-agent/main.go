package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// api-agent — host / node agent (NO ops SPA):
//   Go RPC (RPCNODE_PUBLIC_PORT) — public catch-all → FullNode; sleep/maintenance → 503
//   Agent port (RPCNODE_AGENT_PORT / legacy TRON_PANEL_PORT) — Node Agent API only
// Ops console lives in ../panel (control plane).

type Config struct {
	Env             string
	UpstreamHost    string
	UpstreamPort    int
	ListenHost      string
	RPCPort         int
	PanelPort       int
	P2PPort         int
	PublicBase      string // RPC public URL
	PanelBase       string // panel public URL (optional; derived if empty)
	HtpasswdPath    string
	MaintenanceFile string
	StateFile       string
	InstanceFile    string
	SystemAgentURL  string
	CookieFile      string // bitcoind .cookie — injected toward upstream, not required from clients
}

type Server struct {
	cfg      Config
	metrics  *Metrics
	hostHist *hostMetricsHistory
	proxy    *http.Transport
	auth     *PanelAuth
	sessions *SessionStore
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

// isHostTipStateDir — host Server control-plane state (/var/lib/rpcnode/host),
// not a single-network node state dir.
func isHostTipStateDir(dir string) bool {
	d := strings.TrimRight(strings.TrimSpace(dir), "/")
	return d == "/var/lib/rpcnode/host" || strings.HasSuffix(d, "/rpcnode/host")
}

func loadConfig() Config {
	// Instance env (mainnet/testnet/…). Canonical RPCNODE_ENV; TRON_ENV legacy.
	env := envFirst("mainnet", "RPCNODE_ENV", "TRON_ENV")
	network := strings.ToLower(strings.TrimSpace(envOr("TRON_NETWORK", "")))
	stateDir := strings.TrimSpace(os.Getenv("TRON_STATE_DIR"))
	hostTip := network == "" && isHostTipStateDir(stateDir)
	prof := lookupPortProfile(network, env)
	sysURL := envOr("TRON_SYSTEM_AGENT_URL", "http://127.0.0.1:29090")
	defPub := prof.Public
	if defPub <= 0 {
		defPub = 39090
	}
	defP2P := prof.P2P
	if defP2P <= 0 {
		defP2P = 18888
	}
	defHTTP := prof.NodeHTTP
	if defHTTP <= 0 {
		defHTTP = 18090
	}
	// Host tip is control-plane only — never invent TRON FullNode :18090.
	if hostTip {
		defHTTP = 0
		defP2P = 0
	}
	rpcPort := mustAtoi(envFirst(strconv.Itoa(defPub),
		"RPCNODE_PUBLIC_PORT", "TRON_PUBLIC_PORT", "RPCNODE_GATEWAY_PORT", "TRON_GATEWAY_PORT"), defPub)
	// Agent API port (optional). RPCNODE_AGENT_PORT; legacy TRON_PANEL_PORT still accepted.
	// Set 0 to disable the second listener (RPC port still serves /api/v1).
	agentPort := mustAtoi(envFirst("0",
		"RPCNODE_AGENT_PORT", "TRON_AGENT_PORT", "RPCNODE_PANEL_PORT", "TRON_PANEL_PORT"), 0)
	public := envFirst("", "RPCNODE_PUBLIC_BASE", "TRON_PUBLIC_BASE", "PUBLIC_BASE")
	panelBase := envFirst("", "RPCNODE_PANEL_BASE", "PANEL_INGEST_URL", "PANEL_BASE", "TRON_PANEL_BASE")
	if panelBase == "" && public != "" && agentPort > 0 {
		panelBase = swapURLPort(public, agentPort)
	}
	upHost := envOr("TRON_NODE_HTTP_HOST", "127.0.0.1")
	if upHost == "0.0.0.0" || upHost == "::" || upHost == "[::]" {
		upHost = "127.0.0.1"
	}
	// Non-TRON catalog owns JSON-RPC (stale TRON_NODE_HTTP_PORT=18090 on leaf/host).
	upPort := mustAtoi(envOr("TRON_NODE_HTTP_PORT", strconv.Itoa(defHTTP)), defHTTP)
	if p := prof.CatalogUpstreamHTTP(); p > 0 {
		upPort = p
	}
	stateNet := network
	if stateNet == "" {
		if hostTip {
			stateNet = "host"
		} else {
			stateNet = "tron"
		}
	}
	defaultState := fmt.Sprintf("/var/lib/rpcnode/%s-%s/agent-state.json", stateNet, env)
	defaultInst := fmt.Sprintf("/var/lib/rpcnode/%s-%s/INSTANCE.json", stateNet, env)
	if stateDir != "" {
		defaultState = filepath.Join(stateDir, "agent-state.json")
		defaultInst = filepath.Join(stateDir, "INSTANCE.json")
	}
	cfg := Config{
		Env:             env,
		UpstreamHost:    upHost,
		UpstreamPort:    upPort,
		ListenHost:      envFirst("0.0.0.0", "RPCNODE_GATEWAY_LISTEN", "TRON_GATEWAY_LISTEN"),
		RPCPort:         rpcPort,
		PanelPort:       agentPort,
		P2PPort:         mustAtoi(envOr("TRON_P2P_PORT", strconv.Itoa(defP2P)), defP2P),
		PublicBase:      public,
		PanelBase:       panelBase,
		HtpasswdPath:    envFirst("/etc/nginx/htpasswd/panel.htpasswd", "RPCNODE_PANEL_HTPASSWD", "TRON_PANEL_HTPASSWD"),
		MaintenanceFile: envOr("TRON_MAINTENANCE_FILE", fmt.Sprintf("/run/%s-%s/maintenance.json", stateNet, env)),
		StateFile:       envOr("TRON_AGENT_STATE", defaultState),
		InstanceFile:    envOr("TRON_INSTANCE_FILE", defaultInst),
		SystemAgentURL:  strings.TrimRight(sysURL, "/"),
	}
	if network == "bitcoin" || (network == "" && (upPort == 8332 || upPort == 18332 || upPort == 38332 || upPort == 18443)) {
		cfg.CookieFile = resolveBitcoinCookiePath(cfg)
	}

	return cfg
}

// PublicRPCPort — client-facing FullNode HTTP. RPCNODE_PUBLIC_PORT=0 → UpstreamPort (direct).
func (c Config) PublicRPCPort() int {
	if c.RPCPort > 0 {
		return c.RPCPort
	}
	return c.UpstreamPort
}

func main() {
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "-version", "--version", "version":
			fmt.Println(agentVersion())
			return
		case "--heal-provisioned", "-heal-provisioned":
			os.Exit(runHealProvisionedCLI())
		case "--ensure-watchdog", "-ensure-watchdog":
			os.Exit(runEnsureWatchdogCLI())
		}
	}

	// Host bootstrap units may still hardcode :8091 from pre-0.3.33 installs.
	if migrateHostBootstrapSystemAgent() {
		log.Printf("migrated host system-agent loopback off :809x → :29090")
	}

	cfg := loadConfig()
	sessionPath := envOr("TRON_PANEL_SESSIONS", "/var/lib/rpcnode/panel-sessions.json")
	hostHist := newHostMetricsHistory()
	hostHist.Start(3 * time.Second)
	s := &Server{
		cfg:      cfg,
		metrics:  NewMetrics(),
		hostHist: hostHist,
		auth:     NewPanelAuth(cfg.HtpasswdPath),
		sessions: NewSessionStore(sessionPath),
		// High-load defaults: thousands of concurrent RPC via Go proxy → localhost upstream.
		// MaxConnsPerHost=0 (unlimited); idle pool sized for fan-out, not tiny CI defaults.
		proxy: &http.Transport{
			Proxy: http.ProxyFromEnvironment,
			DialContext: (&net.Dialer{
				Timeout:   10 * time.Second,
				KeepAlive: 30 * time.Second,
			}).DialContext,
			MaxIdleConns:          8192,
			MaxIdleConnsPerHost:   4096,
			MaxConnsPerHost:       0,
			IdleConnTimeout:       90 * time.Second,
			ResponseHeaderTimeout: 120 * time.Second,
			ExpectContinueTimeout: 1 * time.Second,
			DisableCompression:    true,
			ForceAttemptHTTP2:     false,
			WriteBufferSize:       64 << 10,
			ReadBufferSize:        64 << 10,
		},
	}

	if cfg.RPCPort <= 0 && cfg.PanelPort <= 0 {
		log.Fatalf("both RPCNODE_PUBLIC_PORT and RPCNODE_AGENT_PORT are disabled — nothing to listen on")
	}

	log.Printf("rpcnode-api-agent go_rpc=%s:%d agent_port=%d upstream=%s:%d system_agent=%s",
		cfg.ListenHost, cfg.RPCPort, cfg.PanelPort,
		cfg.UpstreamHost, cfg.UpstreamPort, cfg.SystemAgentURL)
	logRegisterHint(cfg)
	// After Servers → Update agent, new tip/leaf processes self-heal stellar
	// (cfg/toml migrate + restart only if unit failed / crash markers).
	scheduleProvisionedHealOnStartup()
	// Finish interrupted delete_files (tip killed mid-wipe left /data/<net>/<env>).
	scheduleRemoveJobResumeOnStartup()
	// Tip: ensure IPAccounting drop-ins on boot (Update runs ensure in *old* binary
	// before restart — first bump to a version that adds ensure would otherwise skip).
	if isHostTipStateDir(cfg.StateFile) || strings.TrimSpace(os.Getenv("TRON_NETWORK")) == "" {
		go func() {
			if steps, err := ensureAllNodeIPAccounting(); err != nil {
				log.Printf("ensure node IPAccounting: %v", err)
			} else if len(steps) > 0 {
				log.Printf("ensure node IPAccounting: %s", strings.Join(steps, "; "))
			}
		}()
	}

	errCh := make(chan error, 2)
	serve := func(name string, srv *http.Server) {
		err := srv.ListenAndServe()
		if err != nil && err != http.ErrServerClosed {
			errCh <- fmt.Errorf("%s: %w", name, err)
			return
		}
		errCh <- err
	}

	var rpcSrv *http.Server
	if cfg.RPCPort > 0 {
		rpcSrv = s.newHTTPServer(cfg.RPCPort, s.rpcHandler())
		go serve("rpc", rpcSrv)
	} else {
		log.Printf("WARN: RPCNODE_PUBLIC_PORT=0 — no Go RPC (cannot sleep RPC on update); upstream=:%d", cfg.UpstreamPort)
	}

	var agentSrv *http.Server
	if cfg.PanelPort > 0 {
		agentSrv = s.newHTTPServer(cfg.PanelPort, s.auth.Middleware(s.sessions, s.agentAPIHandler()))
		go serve("agent", agentSrv)
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	for {
		select {
		case sig := <-sigCh:
			log.Printf("shutdown signal=%v (java-tron untouched)", sig)
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			if rpcSrv != nil {
				_ = rpcSrv.Shutdown(ctx)
			}
			if agentSrv != nil {
				_ = agentSrv.Shutdown(ctx)
			}
			return
		case err := <-errCh:
			if err == nil || err == http.ErrServerClosed {
				continue
			}
			msg := err.Error()
			if strings.HasPrefix(msg, "rpc:") && cfg.PanelPort > 0 {
				// Leaf public may collide with tip listen — keep Agent API up.
				setRPCListenError(msg)
				hostLogf("ERROR", "api-agent", "listen", "%v", err)
				log.Printf("listen failed (rpc kept down, agent API up): %v", err)
				continue
			}
			log.Fatalf("listen failed: %v", err)
		}
	}
}

func (s *Server) newHTTPServer(port int, handler http.Handler) *http.Server {
	addr := net.JoinHostPort(s.cfg.ListenHost, strconv.Itoa(port))
	return &http.Server{
		Addr:              addr,
		Handler:           withRecover(handler),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       0, // long-poll / large TRON bodies OK
		WriteTimeout:      0,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    1 << 20,
	}
}

func withRecover(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				log.Printf("panic path=%s err=%v", r.URL.Path, rec)
				writeJSON(w, http.StatusOK, map[string]any{
					"ok": false, "degraded": true, "error": "panic_recovered",
					"message": fmt.Sprint(rec), "gateway": "api-agent",
				})
			}
		}()
		next.ServeHTTP(w, r)
	})
}

// rpcHandler — TRON_PUBLIC_PORT = Go proxy → FullNode/bitcoind (catch-all).
// When TRON_AGENT_PORT>0: agent JSON only on agent port; this port never returns agentIdentity.
// Legacy AGENT_PORT=0: GET `/` identifies agent; /api/* on this port; else proxy.
func (s *Server) rpcHandler() http.Handler {
	agent := s.auth.Middleware(s.sessions, http.HandlerFunc(s.handlePanel))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Path
		if s.cfg.PanelPort > 0 {
			if path == "/healthz" || path == "/gateway/health" {
				s.handleGatewayHealth(w, r)
				return
			}
			s.proxyRequest(w, r)
			return
		}
		if path == "/healthz" || path == "/gateway/health" {
			s.handleHealth(w, r)
			return
		}
		if (path == "/" || path == "") && (r.Method == http.MethodGet || r.Method == http.MethodHead) {
			s.handleAgentRoot(w, r)
			return
		}
		if isAgentAPIPath(path) {
			agent.ServeHTTP(w, r)
			return
		}
		s.proxyRequest(w, r)
	})
}

// handleGatewayHealth — tiny liveness on public Go RPC (not agent lifecycle).
func (s *Server) handleGatewayHealth(w http.ResponseWriter, r *http.Request) {
	_ = r
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":       true,
		"alive":    true,
		"role":     "go_rpc",
		"version":  agentVersion(),
		"rpc_port": s.cfg.RPCPort,
		"upstream": fmt.Sprintf("%s:%d", s.cfg.UpstreamHost, s.cfg.UpstreamPort),
		"network":  strings.ToLower(strings.TrimSpace(envOr("TRON_NETWORK", ""))),
		"env":      s.cfg.Env,
	})
}

// agentAPIHandler — optional second listen (legacy panel port): JSON APIs only.
func (s *Server) agentAPIHandler() http.Handler {
	return http.HandlerFunc(s.handlePanel)
}

func isAgentAPIPath(path string) bool {
	switch {
	case strings.HasPrefix(path, "/api/"):
		return true
	case path == "/status.json", path == "/instances.json", path == "/instance.json":
		return true
	case path == "/internal/auth-token":
		return true
	default:
		return false
	}
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	_ = r
	writeJSON(w, http.StatusOK, s.agentIdentity(true))
}

// handleAgentRoot — GET http://host:39091/ must identify the agent (version), not proxy FullNode.
func (s *Server) handleAgentRoot(w http.ResponseWriter, r *http.Request) {
	_ = r
	writeJSON(w, http.StatusOK, s.agentIdentity(true))
}

func (s *Server) agentIdentity(includeNode bool) map[string]any {
	network := strings.ToLower(strings.TrimSpace(envOr("TRON_NETWORK", "")))
	stateDir := strings.TrimSpace(os.Getenv("TRON_STATE_DIR"))
	hostTip := network == "" && (isHostTipStateDir(stateDir) || isHostTipStateDir(filepath.Dir(s.cfg.StateFile)))
	out := map[string]any{
		"ok":            true,
		"alive":         true,
		"role":          "api-agent",
		"version":       agentVersion(),
		"agent_version": agentVersion(),
		"rpc_port":      s.cfg.RPCPort,
		// Combined mode (TRON_AGENT_PORT=0): agent API shares the RPC listen port.
		"panel_port": map[bool]int{true: s.cfg.PanelPort, false: s.cfg.RPCPort}[s.cfg.PanelPort > 0],
		"agent_port": map[bool]int{true: s.cfg.PanelPort, false: s.cfg.RPCPort}[s.cfg.PanelPort > 0],
		"env":        s.cfg.Env,
		"os":         runtime.GOOS,
		"arch":       runtime.GOARCH,
		// Unified Server / per-node agent — one binary, many networks.
		"supported_networks":  supportedNetworks(),
		"network_constraints": networkHostConstraints(),
	}
	if hostTip {
		// Multi-chain Server tip: control plane only — never a failed single-network node.
		out["host_tip"] = true
		out["node_status"] = "host"
		out["ui_phase"] = "host"
		out["node_up"] = false
		out["node_known"] = false
		return out
	}
	if s.cfg.UpstreamPort > 0 {
		out["upstream"] = fmt.Sprintf("%s:%d", s.cfg.UpstreamHost, s.cfg.UpstreamPort)
	}
	if network != "" {
		out["network"] = network
		// Profile-driven lifecycle plan for this per-node agent (UI filters Snapshot etc.).
		out["supported_steps"] = supportedLifecycleSteps(network, s.cfg.Env)
		out["capabilities"] = lifecycleCapabilities(network, s.cfg.Env)
	}
	if !includeNode {
		return out
	}
	st := readJSONFile(s.cfg.StateFile)
	up, known := upstreamNodeKnownUpFromState(st)
	out["node_up"] = up
	out["node_known"] = known

	// Prefer system-agent lifecycle (install → snapshot → start → run) over bare process probe.
	if lc, ok := st["lifecycle"].(map[string]any); ok && len(lc) > 0 {
		out["lifecycle"] = lc
		if ns, _ := lc["node_status"].(string); strings.TrimSpace(ns) != "" {
			out["node_status"] = ns
		}
		if ph, _ := lc["phase"].(string); strings.TrimSpace(ph) != "" {
			out["ui_phase"] = ph
		}
		if lab, _ := lc["label"].(string); lab != "" {
			out["lifecycle_label"] = lab
		}
		if det, _ := lc["detail"].(string); det != "" {
			out["lifecycle_detail"] = det
		}
		if pct, ok := lc["pct"]; ok {
			out["snapshot_pct"] = pct
		}
	}
	if _, has := out["node_status"]; !has {
		if act := digString(st, "agent", "activity"); act == "snapshot_download" {
			out["node_status"] = "snapshot_download"
			out["ui_phase"] = "snapshot"
		} else if act == "node_starting" {
			out["node_status"] = "starting"
			out["ui_phase"] = "start"
		} else if act == "syncing" {
			out["node_status"] = "syncing"
			out["ui_phase"] = "run"
		} else if !known {
			out["node_status"] = "unknown"
		} else if up {
			out["node_status"] = "running"
		} else {
			out["node_status"] = "not_started"
		}
	}
	if ph, _ := st["ui_phase"].(string); ph != "" {
		if _, has := out["ui_phase"]; !has {
			out["ui_phase"] = ph
		}
	}
	// Fullnode client version from system-agent collect (not toolkit agent_version).
	cv := strings.TrimSpace(digString(st, "client_version"))
	if cv == "" {
		cv = strings.TrimSpace(digString(st, "rpc", "client_version"))
	}
	if cv == "" {
		cv = strings.TrimSpace(digString(st, "rpc", "version"))
	}
	if cv != "" {
		out["client_version"] = cv
	}
	return out
}

func digString(m map[string]any, keys ...string) string {
	cur := any(m)
	for _, k := range keys {
		obj, ok := cur.(map[string]any)
		if !ok {
			return ""
		}
		cur = obj[k]
	}
	s, _ := cur.(string)
	return s
}

// upstreamNodeKnownUp reads system-agent state — no dial to FullNode.
func (s *Server) upstreamNodeKnownUp() (up bool, known bool) {
	return upstreamNodeKnownUpFromState(readJSONFile(s.cfg.StateFile))
}

func upstreamNodeKnownUpFromState(st map[string]any) (up bool, known bool) {
	if checks, ok := st["checks"].(map[string]any); ok {
		if v, ok := checks["node_process_up"].(bool); ok {
			return v, true
		}
		if v, ok := checks["java_tron_process"].(bool); ok {
			return v, true
		}
	}
	if rpc, ok := st["rpc"].(map[string]any); ok {
		if v, ok := rpc["process_up"].(bool); ok {
			return v, true
		}
	}
	if svc, ok := st["services"].(map[string]any); ok {
		if n, ok := svc["node"].(string); ok && n != "" {
			n = strings.ToLower(strings.TrimSpace(n))
			return n == "active" || n == "activating", true
		}
	}
	return false, false
}

func (s *Server) handlePanel(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path
	switch {
	case path == "/" || path == "" || path == "/healthz" || path == "/gateway/health":
		s.handleAgentRoot(w, r)
	case path == "/status.json" || path == "/api/status.json":
		env := r.URL.Query().Get("env")
		network := r.URL.Query().Get("network")
		writeJSON(w, http.StatusOK, s.buildStatusForNetworkEnv(
			publicBaseFromRequest(r, s.cfg.PublicBase), network, env,
		))
	case path == "/api/instances.json" || path == "/instances.json":
		st := s.buildStatus(publicBaseFromRequest(r, s.cfg.PublicBase))
		items, _ := st["instances"].([]any)
		if items == nil {
			if typed, ok := st["instances"].([]map[string]any); ok {
				writeJSON(w, http.StatusOK, map[string]any{"ok": true, "agent_env": s.cfg.Env, "items": typed})
				return
			}
			items = []any{}
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "agent_env": s.cfg.Env, "items": items})
	case path == "/instance.json":
		env := r.URL.Query().Get("env")
		network := r.URL.Query().Get("network")
		st := s.buildStatusForNetworkEnv(publicBaseFromRequest(r, s.cfg.PublicBase), network, env)
		inst, _ := st["instance"].(map[string]any)
		if inst == nil {
			inst = map[string]any{}
		}
		writeJSON(w, http.StatusOK, inst)
	case path == "/api/v1" || strings.HasPrefix(path, "/api/v1/"):
		s.handleDevAPI(w, r)
	case path == "/api/agent" || strings.HasPrefix(path, "/api/agent/"):
		s.handleAgentV1(w, r)
	case path == "/api/metrics.json":
		s.handleMetrics(w, r)
	case strings.HasPrefix(path, "/api/snapshot/"):
		s.handleSnapshotAPI(w, r)
	case strings.HasPrefix(path, "/api/maintenance"), path == "/api/refresh", path == "/api/preflight",
		strings.HasPrefix(path, "/api/toolkit/"):
		s.handleControlAPI(w, r)
	case path == "/api/public-base":
		s.handlePublicBaseAPI(w, r)
	case path == "/api/host":
		s.handleHostAPI(w, r)
	default:
		writeJSON(w, http.StatusNotFound, map[string]any{
			"ok":         false,
			"error":      "not_found",
			"message":    "node agent has no ops SPA — use standalone panel; this port serves RPC proxy + /api/v1",
			"rpc_port":   s.cfg.RPCPort,
			"agent_port": s.cfg.PanelPort,
			"panel_hint": "docker compose -f docker-compose.panel.yml up -d --build",
		})
	}
}

func (s *Server) handleMetrics(w http.ResponseWriter, r *http.Request) {
	st := readJSONFile(s.cfg.StateFile)
	hostMetrics, _ := st["host_metrics"].(map[string]any)
	if hostMetrics == nil {
		hostMetrics = map[string]any{}
	}
	hist, _ := hostMetrics["history"].(map[string]any)
	if hist == nil {
		hist = map[string]any{}
	}
	curHost, _ := hostMetrics["current"].(map[string]any)
	if curHost == nil {
		curHost = map[string]any{}
	}
	// Live host samples from this process — fills Network when system-agent
	// state predates net_* history (tip updated, leaf unit still old in RAM).
	var liveCur, liveHist map[string]any
	if s.hostHist != nil {
		liveCur, liveHist = s.hostHist.Snapshot()
	}
	if liveHist == nil {
		liveHist = map[string]any{}
	}
	if liveCur == nil {
		liveCur = map[string]any{}
	}
	// Network always from api-agent live ring (system-agent may omit net_* on old binary).
	netRx := liveHist["net_rx"]
	netTx := liveHist["net_tx"]
	if histLen(netRx) == 0 {
		netRx = hist["net_rx"]
	}
	if histLen(netTx) == 0 {
		netTx = hist["net_tx"]
	}
	loadHist := hist["load"]
	cpuHist := hist["cpu"]
	memHist := hist["memory"]
	if histLen(loadHist) == 0 {
		loadHist = liveHist["load"]
	}
	if histLen(cpuHist) == 0 {
		cpuHist = liveHist["cpu"]
	}
	if histLen(memHist) == 0 {
		memHist = liveHist["memory"]
	}
	diskRead := liveHist["disk_read_iops"]
	diskWrite := liveHist["disk_write_iops"]
	diskUtil := liveHist["disk_util"]
	diskDevs := liveHist["disks"]
	if histLen(diskRead) == 0 {
		diskRead = hist["disk_read_iops"]
	}
	if histLen(diskWrite) == 0 {
		diskWrite = hist["disk_write_iops"]
	}
	if histLen(diskUtil) == 0 {
		diskUtil = hist["disk_util"]
	}
	if histLen(diskDevs) == 0 {
		diskDevs = hist["disks"]
	}
	pickCur := func(key string) any {
		if v, ok := curHost[key]; ok && v != nil {
			return v
		}
		return liveCur[key]
	}

	gw := s.metrics.Snapshot()
	rpsHist := s.metrics.RPSHistory()
	disk, _ := st["disk"].(map[string]any)
	if disk == nil || asFloatAny(disk["total_gb"]) <= 0 {
		// Live fallback when state is missing disk (or old collect).
		if live := diskRootLive(); asFloatAny(live["total_gb"]) > 0 {
			disk = live
		}
	}
	if disk == nil {
		disk = map[string]any{}
	}
	osName, _ := curHost["os"].(string)
	arch, _ := curHost["arch"].(string)
	osName = strings.TrimSpace(osName)
	arch = strings.TrimSpace(arch)
	if osName == "" {
		osName = runtime.GOOS
	}
	if arch == "" {
		arch = runtime.GOARCH
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":         true,
		"updated_at": time.Now().UTC().Format(time.RFC3339),
		"current": map[string]any{
			"rps_1m":         gw["rps_1m"],
			"rps_5m":         gw["rps_5m"],
			"in_flight":      gw["in_flight"],
			"latency_p50_ms": gw["latency_p50_ms"],
			"latency_p95_ms": gw["latency_p95_ms"],
			"load_1":         pickCur("load_1"),
			"load_5":         pickCur("load_5"),
			"ncpu":           pickCur("ncpu"),
			"load_pct":       pickCur("load_pct"),
			"cpu_pct":        pickCur("cpu_pct"),
			"cpu_busy":       pickCur("cpu_busy"),
			"mem_pct":        pickCur("mem_pct"),
			"mem_used_mb":    pickCur("mem_used_mb"),
			"mem_total_mb":   pickCur("mem_total_mb"),
			"net_rx_mbps":    firstMetrics(liveCur["net_rx_mbps"], curHost["net_rx_mbps"]),
			"net_tx_mbps":    firstMetrics(liveCur["net_tx_mbps"], curHost["net_tx_mbps"]),
			"net_rx_bps":     firstMetrics(liveCur["net_rx_bps"], curHost["net_rx_bps"]),
			"net_tx_bps":     firstMetrics(liveCur["net_tx_bps"], curHost["net_tx_bps"]),
			"disk_read_iops":  firstMetrics(liveCur["disk_read_iops"], curHost["disk_read_iops"]),
			"disk_write_iops": firstMetrics(liveCur["disk_write_iops"], curHost["disk_write_iops"]),
			"disk_read_mb_s":  firstMetrics(liveCur["disk_read_mb_s"], curHost["disk_read_mb_s"]),
			"disk_write_mb_s": firstMetrics(liveCur["disk_write_mb_s"], curHost["disk_write_mb_s"]),
			"disk_util_pct":   firstMetrics(liveCur["disk_util_pct"], curHost["disk_util_pct"]),
			"disk_busy":       firstMetrics(liveCur["disk_busy"], curHost["disk_busy"]),
			"disks":           firstHist(liveCur["disks"], curHost["disks"]),
			// Per-node unit accounting (system-agent); nil when unset.
			"node_net_rx_mbps":  pickNodeNetField(curHost, st, "node_net_rx_mbps"),
			"node_net_tx_mbps":  pickNodeNetField(curHost, st, "node_net_tx_mbps"),
			"node_net_rx_bps":   pickNodeNetField(curHost, st, "node_net_rx_bps"),
			"node_net_tx_bps":   pickNodeNetField(curHost, st, "node_net_tx_bps"),
			"node_net_rx_bytes": pickNodeNetField(curHost, st, "node_net_rx_bytes"),
			"node_net_tx_bytes": pickNodeNetField(curHost, st, "node_net_tx_bytes"),
			"node_cpu_pct":         pickNodeNetField(curHost, st, "node_cpu_pct"),
			"node_mem_pct":         pickNodeNetField(curHost, st, "node_mem_pct"),
			"node_mem_used_mb":     pickNodeNetField(curHost, st, "node_mem_used_mb"),
			"node_disk_read_iops":  pickNodeNetField(curHost, st, "node_disk_read_iops"),
			"node_disk_write_iops": pickNodeNetField(curHost, st, "node_disk_write_iops"),
			"node_disk_read_mb_s":  pickNodeNetField(curHost, st, "node_disk_read_mb_s"),
			"node_disk_write_mb_s": pickNodeNetField(curHost, st, "node_disk_write_mb_s"),
			"disk_used_pct":     disk["used_pct"],
			"disk_used_gb":      disk["used_gb"],
			"disk_total_gb":     disk["total_gb"],
			"os":                osName,
			"arch":              arch,
		},
		"disk": disk,
		"history": map[string]any{
			"rps":         rpsHist,
			"load":        loadHist,
			"cpu":         cpuHist,
			"memory":      memHist,
			"net_rx":      netRx,
			"net_tx":      netTx,
			"node_net_rx":          firstHist(hist["node_net_rx"], nodeNetHist(st, "node_net_rx")),
			"node_net_tx":          firstHist(hist["node_net_tx"], nodeNetHist(st, "node_net_tx")),
			"node_cpu":             firstHist(hist["node_cpu"], nodeNetHist(st, "node_cpu")),
			"node_memory":          firstHist(hist["node_memory"], nodeNetHist(st, "node_memory")),
			"disk_read_iops":       diskRead,
			"disk_write_iops":      diskWrite,
			"disk_util":            diskUtil,
			"disks":                diskDevs,
			"node_disk_read_iops":  firstHist(hist["node_disk_read_iops"], nodeNetHist(st, "node_disk_read_iops")),
			"node_disk_write_iops": firstHist(hist["node_disk_write_iops"], nodeNetHist(st, "node_disk_write_iops")),
		},
		"gateway": gw,
	})
}

func nodeNetHist(st map[string]any, key string) any {
	if st == nil {
		return nil
	}
	nn, _ := st["node_net"].(map[string]any)
	if nn == nil {
		return nil
	}
	h, _ := nn["history"].(map[string]any)
	if h == nil {
		return nil
	}
	return h[key]
}

func firstHist(a, b any) any {
	if histLen(a) > 0 {
		return a
	}
	if histLen(b) > 0 {
		return b
	}
	return a
}

func asFloatAny(v any) float64 {
	switch t := v.(type) {
	case float64:
		return t
	case float32:
		return float64(t)
	case int:
		return float64(t)
	case int64:
		return float64(t)
	case json.Number:
		f, _ := t.Float64()
		return f
	case string:
		f, _ := strconv.ParseFloat(strings.TrimSpace(t), 64)
		return f
	default:
		return 0
	}
}

// pickNodeNetField — prefer host_metrics.current, else status.node_net map.
func pickNodeNetField(curHost, st map[string]any, key string) any {
	if curHost != nil {
		if v, ok := curHost[key]; ok && v != nil {
			return v
		}
	}
	if st != nil {
		if nn, _ := st["node_net"].(map[string]any); nn != nil {
			if v, ok := nn[key]; ok && v != nil {
				return v
			}
		}
	}
	return nil
}

// diskRootLive — root filesystem usage via df (same shape as system-agent diskRoot).
func diskRootLive() map[string]any {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, "df", "-B1", "/").CombinedOutput()
	if err != nil {
		return map[string]any{"total_gb": 0.0, "used_gb": 0.0, "free_gb": 0.0, "used_pct": 0.0}
	}
	lines := strings.Split(strings.TrimSpace(string(out)), "\n")
	if len(lines) < 2 {
		return map[string]any{"total_gb": 0.0, "used_gb": 0.0, "free_gb": 0.0, "used_pct": 0.0}
	}
	fields := strings.Fields(lines[1])
	if len(fields) < 4 {
		return map[string]any{"total_gb": 0.0, "used_gb": 0.0, "free_gb": 0.0, "used_pct": 0.0}
	}
	total, _ := strconv.ParseFloat(fields[1], 64)
	used, _ := strconv.ParseFloat(fields[2], 64)
	free, _ := strconv.ParseFloat(fields[3], 64)
	pct := 0.0
	if total > 0 {
		pct = used * 100 / total
	}
	round1 := func(v float64) float64 { return float64(int(v*10+0.5)) / 10 }
	return map[string]any{
		"total_gb": round1(total / (1024 * 1024 * 1024)),
		"used_gb":  round1(used / (1024 * 1024 * 1024)),
		"free_gb":  round1(free / (1024 * 1024 * 1024)),
		"used_pct": round1(pct),
	}
}

func (s *Server) handleSnapshotAPI(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	action := "start"
	if strings.HasSuffix(r.URL.Path, "/stop") {
		action = "stop"
	}
	s.proxySystemAgent(w, r, "/v1/snapshot/"+action, nil, 15*time.Second)
}

func (s *Server) handleControlAPI(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && !(r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/check")) {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	path := r.URL.Path
	switch {
	case path == "/api/refresh":
		s.proxySystemAgent(w, r, "/v1/refresh", nil, 15*time.Second)
	case path == "/api/preflight":
		s.proxySystemAgent(w, r, "/v1/preflight", nil, 45*time.Second)
	case path == "/api/maintenance/disable":
		s.proxySystemAgent(w, r, "/v1/maintenance/disable", nil, 15*time.Second)
	case path == "/api/maintenance/enable":
		s.proxySystemAgent(w, r, "/v1/maintenance/enable", r.Body, 15*time.Second)
	case path == "/api/maintenance":
		s.proxySystemAgent(w, r, "/v1/maintenance", r.Body, 15*time.Second)
	case path == "/api/toolkit/check":
		s.proxySystemAgent(w, r, "/v1/toolkit/check", nil, 30*time.Second)
	case path == "/api/toolkit/apply":
		s.proxySystemAgent(w, r, "/v1/toolkit/apply", nil, 30*time.Second)
	case path == "/api/toolkit/schedule":
		s.proxySystemAgent(w, r, "/v1/toolkit/schedule", r.Body, 15*time.Second)
	default:
		http.NotFound(w, r)
	}
}

func (s *Server) handlePublicBaseAPI(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.proxySystemAgentMethod(w, r, "/v1/public-base", http.MethodGet, nil, 10*time.Second)
	case http.MethodPost:
		s.proxySystemAgent(w, r, "/v1/public-base", r.Body, 15*time.Second)
	default:
		http.Error(w, "GET or POST", http.StatusMethodNotAllowed)
	}
}

func (s *Server) handleHostAPI(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	// Prefer system-agent (richer IPs). Fall back locally so panel "Check connection"
	// never fails with HTTP 502 when only the internal system-agent API is down.
	if payload, ok := s.fetchSystemAgentJSON("/v1/host", http.MethodGet, 3*time.Second); ok {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(payload)
		return
	}
	hn, _ := os.Hostname()
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":     true,
		"source": "api-agent-local",
		"host": map[string]any{
			"hostname":    hn,
			"os":          runtime.GOOS,
			"arch":        runtime.GOARCH,
			"detected_at": time.Now().UTC().Format(time.RFC3339),
		},
	})
}

func (s *Server) fetchSystemAgentJSON(path, method string, timeout time.Duration) ([]byte, bool) {
	url := s.cfg.SystemAgentURL + path
	client := &http.Client{Timeout: timeout}
	req, err := http.NewRequest(method, url, nil)
	if err != nil {
		return nil, false
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, false
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, false
	}
	payload, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil || len(payload) == 0 {
		return nil, false
	}
	return payload, true
}

func logRegisterHint(cfg Config) {
	tokenHint := "(see /etc/rpcnode/agent.token)"
	if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
		t := strings.TrimSpace(string(b))
		if t != "" {
			tokenHint = t
		}
	}
	log.Printf("────────────────────────────────────────────────────────")
	log.Printf("Register this host in the RpcNode panel (Servers → Add server):")
	agentURLPort := cfg.PanelPort
	if agentURLPort <= 0 {
		agentURLPort = cfg.RPCPort
	}
	log.Printf("  Agent URL : http://<this-host-ip>:%d", agentURLPort)
	if cfg.RPCPort > 0 {
		log.Printf("  Go RPC    : http://<this-host-ip>:%d  (→ FullNode :%d; sleep on update)", cfg.RPCPort, cfg.UpstreamPort)
	}
	log.Printf("  Agent key : %s", tokenHint)
	log.Printf("  Also saved: /etc/rpcnode/register.txt")
	log.Printf("────────────────────────────────────────────────────────")
}

func (s *Server) proxySystemAgent(w http.ResponseWriter, r *http.Request, path string, body io.Reader, timeout time.Duration) {
	url := s.cfg.SystemAgentURL + path
	if timeout <= 0 {
		timeout = 15 * time.Second
	}
	ctx, cancel := context.WithTimeout(r.Context(), timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, body)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"ok": false, "error": "system-agent unreachable: " + err.Error(),
		})
		return
	}
	defer resp.Body.Close()
	payload, _ := io.ReadAll(resp.Body)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(resp.StatusCode)
	_, _ = w.Write(payload)
}

func (s *Server) proxyRequest(w http.ResponseWriter, r *http.Request) {
	maint := readJSONFile(s.cfg.MaintenanceFile)
	if truthy(maint["enabled"]) {
		s.metrics.ObserveMaintenance()
		retry := 30
		if v, ok := maint["retry_after_sec"].(float64); ok {
			retry = int(v)
		}
		reason, _ := maint["reason"].(string)
		if reason == "" {
			reason = "node maintenance"
		}
		phase, _ := maint["phase"].(string)
		w.Header().Set("Retry-After", strconv.Itoa(retry))
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{
			"error": "maintenance", "message": reason, "retry_after_sec": retry, "phase": phase,
			"version": agentVersion(),
		})
		return
	}

	// Agent already knows java-tron is down — do not dial :18090.
	if up, known := s.upstreamNodeKnownUp(); known && !up {
		s.metrics.Observe(http.StatusServiceUnavailable, 0, true)
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{
			"ok":          false,
			"error":       "node_not_started",
			"message":     "FullNode is not running yet — agent is up; start/provision the node first",
			"version":     agentVersion(),
			"upstream":    fmt.Sprintf("%s:%d", s.cfg.UpstreamHost, s.cfg.UpstreamPort),
			"node_status": "not_started",
		})
		return
	}

	s.metrics.Begin()
	defer s.metrics.End()
	start := time.Now()

	body, err := io.ReadAll(io.LimitReader(r.Body, 64<<20))
	if err != nil {
		s.metrics.Observe(http.StatusBadRequest, time.Since(start), false)
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "bad_request", "message": err.Error(), "version": agentVersion()})
		return
	}
	_ = r.Body.Close()

	if networkIsTonCfg() {
		if s.serveTonVersionRPC(w, body, start) {
			return
		}
	}

	upPath := r.URL.RequestURI()
	// Hyperliquid EVM JSON-RPC lives at /evm; rewrite bare / so eth_chainId clients work.
	if networkIsHyperliquid(s.cfg) {
		p := r.URL.Path
		if p == "" || p == "/" {
			upPath = "/evm"
			if q := r.URL.RawQuery; q != "" {
				upPath += "?" + q
			}
		}
	}
	// TON THA lives at /api/v2/jsonRPC — rewrite bare POST / so admin GetVersion
	// and getMasterchainInfo work on the public Go RPC base URL.
	if networkIsTonCfg() {
		if next := rewriteTonUpstreamPath(r.Method, r.URL.Path, r.URL.RawQuery); next != "" {
			upPath = next
		}
	}
	// Avalanche product RPC = C-Chain eth JSON-RPC at /ext/bc/C/rpc.
	if networkIsAvalancheCfg(s.cfg) {
		p := r.URL.Path
		if p == "" || p == "/" || !strings.HasPrefix(p, "/ext/") {
			upPath = "/ext/bc/C/rpc"
			if q := r.URL.RawQuery; q != "" {
				upPath += "?" + q
			}
		}
	}
	url := fmt.Sprintf("http://%s:%d%s", s.cfg.UpstreamHost, s.cfg.UpstreamPort, upPath)
	req, err := http.NewRequestWithContext(r.Context(), r.Method, url, bytes.NewReader(body))
	if err != nil {
		s.metrics.Observe(http.StatusBadGateway, time.Since(start), true)
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"error": "upstream_unavailable", "message": err.Error(), "version": agentVersion(),
		})
		return
	}
	for k, vv := range r.Header {
		if hopByHop[strings.ToLower(k)] {
			continue
		}
		for _, v := range vv {
			req.Header.Add(k, v)
		}
	}
	// Bitcoin/Doge/LTC/Dash/BCH: clients hit public Go RPC without auth; inject cookie/rpcuser toward localhost.
	if (networkIsBitcoin(s.cfg) || networkUsesRPCUserAuth(envOr("TRON_NETWORK", ""))) &&
		req.Header.Get("Authorization") == "" {
		if auth := upstreamRPCAuthHeader(s.cfg); auth != "" {
			req.Header.Set("Authorization", auth)
		}
	}
	req.Host = fmt.Sprintf("%s:%d", s.cfg.UpstreamHost, s.cfg.UpstreamPort)
	req.ContentLength = int64(len(body))

	resp, err := s.proxy.RoundTrip(req)
	if err != nil {
		if s.cfg.UpstreamPort > 0 {
			hostLogProxyErr(fmt.Sprintf("upstream_unavailable %s:%d %v", s.cfg.UpstreamHost, s.cfg.UpstreamPort, err))
		}
		s.metrics.Observe(http.StatusBadGateway, time.Since(start), true)
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"error": "upstream_unavailable", "message": err.Error(),
			"version": agentVersion(), "node_status": "unreachable",
		})
		return
	}
	defer resp.Body.Close()
	payload, err := io.ReadAll(resp.Body)
	if err != nil {
		s.metrics.Observe(http.StatusBadGateway, time.Since(start), true)
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"error": "upstream_unavailable", "message": err.Error(), "version": agentVersion(),
		})
		return
	}
	s.metrics.Observe(resp.StatusCode, time.Since(start), false)
	for k, vv := range resp.Header {
		if hopByHop[strings.ToLower(k)] {
			continue
		}
		for _, v := range vv {
			w.Header().Add(k, v)
		}
	}
	w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
	w.WriteHeader(resp.StatusCode)
	if r.Method != http.MethodHead {
		_, _ = w.Write(payload)
	}
}

var hopByHop = map[string]bool{
	"connection": true, "keep-alive": true, "proxy-authenticate": true,
	"proxy-authorization": true, "te": true, "trailers": true,
	"transfer-encoding": true, "upgrade": true, "content-length": true, "host": true,
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		http.Error(w, `{"error":"encode"}`, http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Length", strconv.Itoa(len(b)))
	w.WriteHeader(code)
	_, _ = w.Write(b)
}

func publicBaseFromRequest(r *http.Request, fallback string) string {
	host := strings.TrimSpace(r.Host)
	if xfh := strings.TrimSpace(r.Header.Get("X-Forwarded-Host")); xfh != "" {
		host = strings.TrimSpace(strings.Split(xfh, ",")[0])
	}
	if host == "" {
		return fallback
	}
	proto := "http"
	if xf := r.Header.Get("X-Forwarded-Proto"); xf != "" {
		proto = strings.TrimSpace(strings.Split(xf, ",")[0])
	}
	if proto != "http" && proto != "https" {
		proto = "http"
	}
	return proto + "://" + host
}

// resolveConnectBase — Connect/curl examples must be reachable from outside.
// Prefer RPCNODE_PUBLIC_BASE; never let a browser Host of localhost replace a configured public URL.
func resolveConnectBase(cfgPublic string, requestBase string, listenPort int) string {
	cfg := strings.TrimRight(strings.TrimSpace(cfgPublic), "/")
	req := strings.TrimRight(strings.TrimSpace(requestBase), "/")
	req = ensureURLPort(req, listenPort)

	if cfg != "" && !isLoopbackBase(cfg) {
		return ensureURLPort(cfg, listenPort)
	}
	if req != "" && !isLoopbackBase(req) {
		return req
	}
	if cfg != "" {
		return ensureURLPort(cfg, listenPort)
	}
	if req != "" {
		return req
	}
	return fmt.Sprintf("http://127.0.0.1:%d", listenPort)
}

func isLoopbackBase(base string) bool {
	u := strings.TrimPrefix(strings.TrimPrefix(strings.ToLower(base), "https://"), "http://")
	host := u
	if i := strings.IndexByte(u, '/'); i >= 0 {
		host = u[:i]
	}
	if i := strings.IndexByte(host, ':'); i >= 0 {
		host = host[:i]
	}
	return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0.0.0.0"
}

// ensureURLPort forces listenPort onto base. Must replace an existing port —
// panel often dials the leaf Agent API (:agent) while Connect RPC must be
// :public (Go proxy). Old behavior kept the Agent port when already present.
func ensureURLPort(base string, port int) string {
	return swapURLPort(base, port)
}

func swapURLPort(base string, port int) string {
	base = strings.TrimRight(strings.TrimSpace(base), "/")
	if base == "" || port <= 0 {
		return base
	}
	proto := "http"
	rest := base
	if strings.HasPrefix(base, "https://") {
		proto = "https"
		rest = base[len("https://"):]
	} else if strings.HasPrefix(base, "http://") {
		rest = base[len("http://"):]
	}
	host := rest
	path := ""
	if i := strings.IndexByte(rest, '/'); i >= 0 {
		host = rest[:i]
		path = rest[i:]
	}
	if strings.HasPrefix(host, "[") {
		if i := strings.Index(host, "]:"); i >= 0 {
			host = host[:i+1]
		}
	} else if i := strings.LastIndexByte(host, ':'); i >= 0 {
		host = host[:i]
	}
	return fmt.Sprintf("%s://%s:%d%s", proto, host, port, path)
}
