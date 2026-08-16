package main

import (
	"embed"
	"io"
	"io/fs"
	"net/http"
	"os"
	"path"
	"strings"
)

//go:embed all:ui
var embeddedUI embed.FS

func (s *Server) uiFS() http.FileSystem {
	if dir := os.Getenv("STATUS_UI_DIR"); dir != "" {
		if st, err := os.Stat(dir); err == nil && st.IsDir() {
			return http.Dir(dir)
		}
	}
	sub, err := fs.Sub(embeddedUI, "ui")
	if err != nil {
		return http.Dir(".")
	}
	return http.FS(sub)
}

func isSPAPath(p string) bool {
	switch {
	case p == "/", p == "/index.html", p == "/dashboard", p == "/home":
		return true
	case p == "/login", p == "/setup-password", p == "/install", p == "/setup":
		return true
	case p == "/servers", p == "/agents", p == "/nodes", p == "/settings", p == "/notifications":
		return true
	case strings.HasPrefix(p, "/nodes/"):
		return true
	case p == "/status", p == "/status/", strings.HasPrefix(p, "/status/"):
		return true
	case strings.HasPrefix(p, "/assets/"):
		return true
	case p == "/favicon.svg", p == "/logo.svg", p == "/icons.svg", p == "/favicon.ico":
		return true
	case strings.HasPrefix(p, "/docs/"):
		return true
	default:
		return false
	}
}

// serveStatusUI serves the React SPA at panel root (and legacy /status/*).
func (s *Server) serveStatusUI(w http.ResponseWriter, r *http.Request) {
	fsys := s.uiFS()
	p := r.URL.Path

	// Legacy bookmark: /status → /
	if p == "/status" || p == "/status/" {
		http.Redirect(w, r, "/", http.StatusFound)
		return
	}
	if strings.HasPrefix(p, "/status/") {
		rel := strings.TrimPrefix(p, "/status/")
		// Map old /status/setup → /install, /status/env/x → /nodes/x
		switch {
		case rel == "setup" || rel == "install":
			http.Redirect(w, r, "/install", http.StatusFound)
			return
		case strings.HasPrefix(rel, "env/"):
			http.Redirect(w, r, "/nodes/"+strings.TrimPrefix(rel, "env/"), http.StatusFound)
			return
		case strings.HasPrefix(rel, "assets/") || rel == "favicon.svg" || rel == "logo.svg" || rel == "icons.svg" || strings.HasPrefix(rel, "docs/"):
			s.serveUIRel(w, r, fsys, rel)
			return
		default:
			http.Redirect(w, r, "/", http.StatusFound)
			return
		}
	}

	if p == "/" || p == "/dashboard" || p == "/home" ||
		p == "/login" || p == "/setup-password" || p == "/install" || p == "/setup" ||
		p == "/servers" || p == "/agents" || p == "/nodes" || p == "/settings" ||
		strings.HasPrefix(p, "/nodes/") {
		s.writeUIFile(w, fsys, "index.html", "text/html; charset=utf-8")
		return
	}

	rel := strings.TrimPrefix(p, "/")
	if rel == "" {
		s.writeUIFile(w, fsys, "index.html", "text/html; charset=utf-8")
		return
	}
	s.serveUIRel(w, r, fsys, rel)
}

func (s *Server) serveUIRel(w http.ResponseWriter, r *http.Request, fsys http.FileSystem, rel string) {
	rel = path.Clean("/" + rel)
	rel = strings.TrimPrefix(rel, "/")
	if rel == "" || rel == "." {
		s.writeUIFile(w, fsys, "index.html", "text/html; charset=utf-8")
		return
	}
	f, err := fsys.Open(rel)
	if err != nil {
		s.writeUIFile(w, fsys, "index.html", "text/html; charset=utf-8")
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil || st.IsDir() {
		s.writeUIFile(w, fsys, "index.html", "text/html; charset=utf-8")
		return
	}
	ctype := contentType(rel)
	w.Header().Set("Content-Type", ctype)
	if strings.HasPrefix(rel, "assets/") {
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	} else {
		w.Header().Set("Cache-Control", "no-store")
	}
	http.ServeContent(w, r, rel, st.ModTime(), f.(io.ReadSeeker))
}

func (s *Server) writeUIFile(w http.ResponseWriter, fsys http.FileSystem, name, ctype string) {
	f, err := fsys.Open(name)
	if err != nil {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`<!doctype html><meta charset="utf-8"><title>RpcNode panel</title>
<body style="font-family:system-ui;background:#0b1016;color:#e8eef7;padding:2rem">
<h1>Panel UI not built</h1>
<p>Rebuild the panel image so Docker compiles <code>status-ui</code> into the binary:</p>
<p><code>docker compose -f docker-compose.panel.yml up -d --build --pull never</code></p>
<p><a href="/login" style="color:#5eb1ff">/login</a> · <a href="/api/auth/status" style="color:#5eb1ff">/api/auth/status</a></p>
</body>`))
		return
	}
	defer f.Close()
	st, _ := f.Stat()
	w.Header().Set("Content-Type", ctype)
	w.Header().Set("Cache-Control", "no-store")
	if rs, ok := f.(io.ReadSeeker); ok && st != nil {
		http.ServeContent(w, &http.Request{}, name, st.ModTime(), rs)
		return
	}
	b, _ := io.ReadAll(f)
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(b)
}

func contentType(name string) string {
	switch {
	case strings.HasSuffix(name, ".html"):
		return "text/html; charset=utf-8"
	case strings.HasSuffix(name, ".js"):
		return "application/javascript; charset=utf-8"
	case strings.HasSuffix(name, ".css"):
		return "text/css; charset=utf-8"
	case strings.HasSuffix(name, ".svg"):
		return "image/svg+xml"
	case strings.HasSuffix(name, ".json"):
		return "application/json"
	case strings.HasSuffix(name, ".md"):
		return "text/markdown; charset=utf-8"
	case strings.HasSuffix(name, ".woff2"):
		return "font/woff2"
	case strings.HasSuffix(name, ".png"):
		return "image/png"
	default:
		return "application/octet-stream"
	}
}
