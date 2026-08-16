package main

import (
	"bufio"
	"crypto/md5"
	"crypto/sha1"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/bcrypt"
)

// PanelAuth loads nginx-compatible htpasswd (bcrypt / apr1 / {SHA}).
type PanelAuth struct {
	mu       sync.RWMutex
	path     string
	users    map[string]string // user → password hash field
	loadedAt time.Time
}

func NewPanelAuth(path string) *PanelAuth {
	a := &PanelAuth{path: path, users: map[string]string{}}
	_ = a.reload()
	return a
}

func (a *PanelAuth) reload() error {
	if a.path == "" {
		a.mu.Lock()
		a.users = map[string]string{}
		a.mu.Unlock()
		return nil
	}
	f, err := os.Open(a.path)
	if err != nil {
		return err
	}
	defer f.Close()

	next := map[string]string{}
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		i := strings.IndexByte(line, ':')
		if i <= 0 {
			continue
		}
		next[line[:i]] = line[i+1:]
	}
	a.mu.Lock()
	a.users = next
	a.loadedAt = time.Now().UTC()
	a.mu.Unlock()
	return sc.Err()
}

func (a *PanelAuth) maybeReload() {
	a.mu.RLock()
	age := time.Since(a.loadedAt)
	path := a.path
	a.mu.RUnlock()
	if path == "" || age < 5*time.Second {
		return
	}
	_ = a.reload()
}

func (a *PanelAuth) enabled() bool {
	a.maybeReload()
	a.mu.RLock()
	defer a.mu.RUnlock()
	return len(a.users) > 0
}

func bcryptHtpasswdHash(pass string) (string, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(pass), bcrypt.DefaultCost)
	if err != nil {
		return "", err
	}
	// htpasswd-compatible $2y$ prefix
	h := string(hash)
	if strings.HasPrefix(h, "$2a$") {
		h = "$2y$" + h[4:]
	}
	return h, nil
}

func (a *PanelAuth) htpasswdPath() string {
	if a.path != "" {
		return a.path
	}
	return "/etc/rpcnode/panel.htpasswd"
}

// writeUsers writes the full htpasswd map (preserves other users).
func (a *PanelAuth) writeUsers(users map[string]string) error {
	path := a.htpasswdPath()
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return err
	}
	names := make([]string, 0, len(users))
	for u := range users {
		names = append(names, u)
	}
	sort.Strings(names)
	var b strings.Builder
	for _, u := range names {
		b.WriteString(u)
		b.WriteByte(':')
		b.WriteString(users[u])
		b.WriteByte('\n')
	}
	if err := writeHtpasswdFile(path, []byte(b.String())); err != nil {
		return err
	}
	a.path = path
	return a.reload()
}

// writeHtpasswdFile updates htpasswd. Rename is atomic on a normal filesystem;
// a Docker bind-mounted file cannot be renamed (EBUSY) — overwrite in place.
func writeHtpasswdFile(path string, data []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o640); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err == nil {
		return nil
	}
	_ = os.Remove(tmp)
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o640)
	if err != nil {
		return err
	}
	_, werr := f.Write(data)
	cerr := f.Close()
	if werr != nil {
		return werr
	}
	return cerr
}

// SetPassword sets or creates a bcrypt htpasswd entry for login (keeps other users).
// Used by CLI `rpcnode-panel passwd <login>` and first-run setup.
func (a *PanelAuth) SetPassword(user, pass string) error {
	user = strings.TrimSpace(user)
	if user == "" || strings.Contains(user, ":") {
		return fmt.Errorf("invalid username")
	}
	if len(pass) < 8 {
		return fmt.Errorf("password must be at least 8 characters")
	}
	h, err := bcryptHtpasswdHash(pass)
	if err != nil {
		return err
	}
	_ = a.reload()
	a.mu.RLock()
	next := make(map[string]string, len(a.users)+1)
	for u, hash := range a.users {
		next[u] = hash
	}
	a.mu.RUnlock()
	next[user] = h
	return a.writeUsers(next)
}

// CreateUser writes bcrypt htpasswd (first admin / panel-auth set from API).
func (a *PanelAuth) CreateUser(user, pass string) error {
	return a.SetPassword(user, pass)
}

func (a *PanelAuth) verify(user, pass string) bool {
	a.maybeReload()
	a.mu.RLock()
	hash, ok := a.users[user]
	a.mu.RUnlock()
	if !ok || hash == "" {
		return false
	}
	return checkPassword(pass, hash)
}

func checkPassword(pass, hash string) bool {
	switch {
	case strings.HasPrefix(hash, "$2y$"), strings.HasPrefix(hash, "$2a$"), strings.HasPrefix(hash, "$2b$"):
		h := hash
		if strings.HasPrefix(h, "$2y$") {
			h = "$2a$" + h[4:]
		}
		return bcrypt.CompareHashAndPassword([]byte(h), []byte(pass)) == nil
	case strings.HasPrefix(hash, "$apr1$"):
		return subtle.ConstantTimeCompare([]byte(apr1Crypt(pass, hash)), []byte(hash)) == 1
	case strings.HasPrefix(hash, "{SHA}"):
		sum := sha1.Sum([]byte(pass))
		want, err := base64.StdEncoding.DecodeString(hash[len("{SHA}"):])
		if err != nil {
			return false
		}
		return subtle.ConstantTimeCompare(sum[:], want) == 1
	default:
		return false
	}
}

// isPublicPanelPath — no session required (SPA + auth bootstrap + health).
func isPublicPanelPath(path string) bool {
	switch {
	case path == "/healthz", path == "/gateway/health":
		return true
	case path == "/api/auth/status", path == "/api/auth/login", path == "/api/auth/setup", path == "/api/auth/logout":
		return true
	case strings.HasPrefix(path, "/assets/"):
		return true
	case path == "/favicon.svg", path == "/logo.svg", path == "/icons.svg", path == "/favicon.ico":
		return true
	case strings.HasPrefix(path, "/docs/"):
		return true
	case path == "/", path == "/dashboard", path == "/home",
		path == "/login", path == "/setup-password", path == "/install", path == "/setup",
		path == "/servers", path == "/agents", path == "/nodes", path == "/settings",
		path == "/notifications":
		return true
	case strings.HasPrefix(path, "/nodes/"):
		return true
	case path == "/status", path == "/status/", strings.HasPrefix(path, "/status/"):
		return true
	case path == "/index.html":
		return true
	default:
		return false
	}
}

func isAPIPath(path string) bool {
	return strings.HasPrefix(path, "/api/") ||
		path == "/status.json" ||
		path == "/instances.json" ||
		path == "/instance.json"
}

// isBrowserUIRequest — Chromium/Firefox/Safari send Sec-Fetch-* on navigations and
// same-origin SPA fetches. Curl/scripts typically do not. Used so cached HTTP Basic
// in the browser cannot keep the ops SPA "logged in" after session logout.
func isBrowserUIRequest(r *http.Request) bool {
	if r.Header.Get("Sec-Fetch-Site") != "" {
		return true
	}
	if dest := r.Header.Get("Sec-Fetch-Dest"); dest == "document" || dest == "iframe" {
		return true
	}
	if mode := r.Header.Get("Sec-Fetch-Mode"); mode == "navigate" {
		return true
	}
	accept := r.Header.Get("Accept")
	if strings.Contains(accept, "text/html") && !strings.Contains(accept, "application/json") {
		return true
	}
	return false
}

// Middleware: session cookie / Bearer for humans; optional Basic for API tools (curl).
// SPA routes are always public so React can show /login and /setup-password.
// Host agents authenticate to *agents*, not to this panel (panel stores agent keys in registry).
// Ingest endpoints validate Bearer against registry agent keys inside the handler.
func (a *PanelAuth) Middleware(sessions *SessionStore, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Path
		if isPublicPanelPath(path) {
			next.ServeHTTP(w, r)
			return
		}
		if path == "/api/ingest/server-metrics" {
			next.ServeHTTP(w, r)
			return
		}
		// Session from cookie (SPA) or Authorization: Bearer / X-Panel-Token (curl).
		if sessions != nil {
			if tok := sessionTokenFromRequest(r); tok != "" {
				if _, ok := sessions.Get(tok); ok {
					next.ServeHTTP(w, r)
					return
				}
			}
		}
		// Legacy curl / scripts: HTTP basic against htpasswd — never for browser SPA.
		// Cached Basic after logout would otherwise keep the UI authorized.
		if !isBrowserUIRequest(r) {
			if user, pass, ok := r.BasicAuth(); ok && a.enabled() && a.verify(user, pass) {
				next.ServeHTTP(w, r)
				return
			}
		}
		// Unauthenticated API → JSON (no WWW-Authenticate — avoid browser basic dump).
		if isAPIPath(path) {
			writeJSON(w, http.StatusUnauthorized, map[string]any{
				"ok":          false,
				"error":       "unauthorized",
				"needs_setup": !a.enabled(),
				"needs_login": a.enabled(),
				"login_path":  "/login",
				"setup_path":  "/setup-password",
				"message":     "Sign in at /login (or create admin at /setup-password).",
			})
			return
		}
		// Unknown non-API path — still serve SPA shell (client router).
		next.ServeHTTP(w, r)
	})
}

// apr1Crypt — Apache MD5 ($apr1$salt$...) compatible with openssl passwd -apr1 / htpasswd -nbm.
func apr1Crypt(password, setting string) string {
	// setting: $apr1$salt$checksum  (or just use salt from setting)
	parts := strings.Split(setting, "$")
	if len(parts) < 4 || parts[1] != "apr1" {
		return ""
	}
	salt := parts[2]
	if len(salt) > 8 {
		salt = salt[:8]
	}

	passwordBytes := []byte(password)
	saltBytes := []byte(salt)

	h := md5.New()
	h.Write(passwordBytes)
	h.Write([]byte("$apr1$"))
	h.Write(saltBytes)

	h2 := md5.New()
	h2.Write(passwordBytes)
	h2.Write(saltBytes)
	h2.Write(passwordBytes)
	final := h2.Sum(nil)

	for i := len(passwordBytes); i > 0; i -= 16 {
		if i > 16 {
			h.Write(final)
		} else {
			h.Write(final[:i])
		}
	}

	for i := len(passwordBytes); i > 0; i >>= 1 {
		if i&1 == 1 {
			h.Write([]byte{0})
		} else {
			h.Write(passwordBytes[:1])
		}
	}
	final = h.Sum(nil)

	for i := 0; i < 1000; i++ {
		h = md5.New()
		if i&1 == 1 {
			h.Write(passwordBytes)
		} else {
			h.Write(final)
		}
		if i%3 != 0 {
			h.Write(saltBytes)
		}
		if i%7 != 0 {
			h.Write(passwordBytes)
		}
		if i&1 == 1 {
			h.Write(final)
		} else {
			h.Write(passwordBytes)
		}
		final = h.Sum(nil)
	}

	return "$apr1$" + salt + "$" + apr1Encode(final)
}

func apr1Encode(final []byte) string {
	const itoa64 = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
	out := make([]byte, 0, 22)
	pack := func(a, b, c byte, n int) {
		v := uint32(a)<<16 | uint32(b)<<8 | uint32(c)
		for i := 0; i < n; i++ {
			out = append(out, itoa64[v&0x3f])
			v >>= 6
		}
	}
	pack(final[0], final[6], final[12], 4)
	pack(final[1], final[7], final[13], 4)
	pack(final[2], final[8], final[14], 4)
	pack(final[3], final[9], final[15], 4)
	pack(final[4], final[10], final[5], 4)
	pack(0, 0, final[11], 2)
	return string(out)
}
