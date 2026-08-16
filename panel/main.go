package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/ali3/tron-toolkit/panel/store"
)

// panel — standalone control plane (SPA + human auth + SQLite registry).
// Collector mode: rpcnode-panel collector — polls host agents into panel.db.

type Config struct {
	ListenHost        string
	ListenPort        int
	HtpasswdPath      string
	SessionPath       string
	DBPath            string
	LegacyDataDir     string
	DefaultAgentURL   string
	DefaultAgentToken string
}

type Server struct {
	cfg       Config
	db        *store.DB
	auth      *PanelAuth
	sessions  *SessionStore
	registry  *NodeRegistry
	metrics   *MetricsCache
	workloads *WorkloadRegistry
	client    *http.Client
	// statusClient — short timeout for /status.json proxy. Collector is the live poller;
	// a 45s tip dial per UI/smoke refresh wedges the panel under parallel Install.
	statusClient *http.Client
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
	port := mustAtoi(envOr("PANEL_PORT", envOr("RPCNODE_PANEL_PORT", envOr("TRON_PANEL_PORT", "8093"))), 8093)
	dbPath := envOr("PANEL_DB", "/var/lib/rpcnode/panel.db")
	legacy := envOr("PANEL_LEGACY_DIR", filepath.Dir(dbPath))
	return Config{
		ListenHost:        envOr("PANEL_LISTEN", "0.0.0.0"),
		ListenPort:        port,
		HtpasswdPath:      envOr("PANEL_HTPASSWD", envOr("TRON_PANEL_HTPASSWD", "/etc/rpcnode/panel.htpasswd")),
		SessionPath:       envOr("PANEL_SESSIONS", envOr("TRON_PANEL_SESSIONS", "/var/lib/rpcnode/panel-sessions.json")),
		DBPath:            dbPath,
		LegacyDataDir:     legacy,
		DefaultAgentURL:   strings.TrimRight(envOr("PANEL_DEFAULT_AGENT_URL", ""), "/"),
		DefaultAgentToken: envOr("PANEL_DEFAULT_AGENT_TOKEN", envOr("AGENT_API_TOKEN", "")),
	}
}

func main() {
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "collector":
			runCollectorMain(os.Args[2:])
			return
		case "passwd":
			runPasswdMain(os.Args[2:])
			return
		}
	}

	cfg := loadConfig()
	db, err := store.Open(cfg.DBPath)
	if err != nil {
		log.Fatalf("open db: %v", err)
	}
	defer db.Close()
	if err := db.ImportLegacyJSON(cfg.LegacyDataDir); err != nil {
		log.Printf("legacy import: %v", err)
	}

	s := &Server{
		cfg:       cfg,
		db:        db,
		auth:      NewPanelAuth(cfg.HtpasswdPath),
		sessions:  NewSessionStore(cfg.SessionPath),
		registry:  NewNodeRegistry(db),
		metrics:   NewMetricsCache(db),
		workloads: NewWorkloadRegistry(db),
		client: &http.Client{
			Timeout: 45 * time.Second,
			Transport: &http.Transport{
				Proxy: http.ProxyFromEnvironment,
				DialContext: (&net.Dialer{
					Timeout:   10 * time.Second,
					KeepAlive: 30 * time.Second,
				}).DialContext,
				MaxIdleConns:        128,
				MaxIdleConnsPerHost: 16,
				IdleConnTimeout:     90 * time.Second,
				DisableCompression:  true,
				ForceAttemptHTTP2:   false,
			},
		},
		statusClient: &http.Client{
			Timeout: 8 * time.Second,
			Transport: &http.Transport{
				Proxy: http.ProxyFromEnvironment,
				DialContext: (&net.Dialer{
					Timeout:   3 * time.Second,
					KeepAlive: 30 * time.Second,
				}).DialContext,
				MaxIdleConns:        64,
				MaxIdleConnsPerHost: 8,
				IdleConnTimeout:     90 * time.Second,
				DisableCompression:  true,
				ForceAttemptHTTP2:   false,
			},
		},
	}

	addr := net.JoinHostPort(cfg.ListenHost, strconv.Itoa(cfg.ListenPort))
	srv := &http.Server{
		Addr:              addr,
		Handler:           withRecover(s.auth.Middleware(s.sessions, s.handler())),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    1 << 20,
	}

	log.Printf("rpcnode-panel listen=%s db=%s htpasswd=%s default_agent=%s",
		addr, cfg.DBPath, cfg.HtpasswdPath, cfg.DefaultAgentURL)

	errCh := make(chan error, 1)
	go func() { errCh <- srv.ListenAndServe() }()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	select {
	case sig := <-sigCh:
		log.Printf("shutdown signal=%v", sig)
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = srv.Shutdown(ctx)
	case err := <-errCh:
		if err != nil && err != http.ErrServerClosed {
			log.Fatalf("listen failed: %v", err)
		}
	}
}

func withRecover(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				log.Printf("panic path=%s err=%v", r.URL.Path, rec)
				writeJSON(w, http.StatusOK, map[string]any{
					"ok": false, "degraded": true, "error": "panic_recovered",
					"message": fmt.Sprint(rec), "service": "panel",
				})
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func (s *Server) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", s.handleHealth)
	mux.HandleFunc("/api/auth/", s.handleAuthAPI)
	mux.HandleFunc("/api/auth/status", s.handleAuthAPI)
	mux.HandleFunc("/api/auth/login", s.handleAuthAPI)
	mux.HandleFunc("/api/auth/setup", s.handleAuthAPI)
	mux.HandleFunc("/api/auth/logout", s.handleAuthAPI)
	mux.HandleFunc("/api/nodes", s.handleNodesAPI)
	mux.HandleFunc("/api/nodes/", s.handleNodesAPI)
	mux.HandleFunc("/api/servers/", s.handleServersAPI)
	mux.HandleFunc("/api/workloads", s.handleWorkloadsAPI)
	mux.HandleFunc("/api/workloads/", s.handleWorkloadsAPI)
	mux.HandleFunc("/api/collector/", s.handleCollectorAPI)
	mux.HandleFunc("/api/ingest/server-metrics", s.handleIngestMetrics)
	mux.HandleFunc("/api/agent/channel", s.handleAgentChannel)
	mux.HandleFunc("/api/install/channel", s.handleAgentChannel)
	mux.HandleFunc("/api/donate", s.handleDonate)
	mux.HandleFunc("/api/install/donate", s.handleDonate)
	mux.HandleFunc("/api/notifications/", s.handleNotificationsAPI)
	mux.HandleFunc("/", s.handleRoot)
	return mux
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "alive": true, "role": "panel", "port": s.cfg.ListenPort,
		"db": s.cfg.DBPath,
	})
}

func (s *Server) handleRoot(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path
	switch {
	case path == "/api/auth" || strings.HasPrefix(path, "/api/auth/"):
		s.handleAuthAPI(w, r)
	case path == "/api/nodes" || strings.HasPrefix(path, "/api/nodes/"):
		s.handleNodesAPI(w, r)
	case path == "/api/workloads" || strings.HasPrefix(path, "/api/workloads/"):
		s.handleWorkloadsAPI(w, r)
	case strings.HasPrefix(path, "/api/collector/"):
		s.handleCollectorAPI(w, r)
	case path == "/api/ingest/server-metrics":
		s.handleIngestMetrics(w, r)
	// Panel CDN channel (install TOOLKIT_VERSION) — must NOT proxy to host agent.
	case path == "/api/agent/channel" || path == "/api/install/channel":
		s.handleAgentChannel(w, r)
	case path == "/api/donate" || path == "/api/install/donate":
		s.handleDonate(w, r)
	case strings.HasPrefix(path, "/api/notifications/"):
		s.handleNotificationsAPI(w, r)
	case isAgentProxyPath(path):
		s.proxyToAgent(w, r)
	case isSPAPath(path):
		s.serveStatusUI(w, r)
	default:
		s.serveStatusUI(w, r)
	}
}

func isAgentProxyPath(path string) bool {
	switch {
	case path == "/api/status.json", path == "/status.json":
		return true
	case path == "/api/metrics.json":
		return true
	case path == "/api/instances.json", path == "/instances.json", path == "/instance.json":
		return true
	case path == "/api/host", path == "/api/public-base":
		return true
	case path == "/api/refresh", path == "/api/preflight":
		return true
	case strings.HasPrefix(path, "/api/snapshot/"):
		return true
	case strings.HasPrefix(path, "/api/maintenance"):
		return true
	case strings.HasPrefix(path, "/api/toolkit/"):
		return true
	case path == "/api/agent/channel", path == "/api/install/channel":
		return false // panel-owned CDN channel
	case path == "/api/donate", path == "/api/install/donate":
		return false // panel-owned install/donate.json
	case path == "/api/agent", strings.HasPrefix(path, "/api/agent/"):
		return true
	case path == "/api/v1", strings.HasPrefix(path, "/api/v1/"):
		return true
	default:
		return false
	}
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
