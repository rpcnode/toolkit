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

// CreateUser writes bcrypt htpasswd (first admin / panel-auth set from API).
func (a *PanelAuth) CreateUser(user, pass string) error {
	user = strings.TrimSpace(user)
	if user == "" || strings.Contains(user, ":") {
		return fmt.Errorf("invalid username")
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(pass), bcrypt.DefaultCost)
	if err != nil {
		return err
	}
	// htpasswd-compatible $2y$ prefix
	h := string(hash)
	if strings.HasPrefix(h, "$2a$") {
		h = "$2y$" + h[4:]
	}
	line := user + ":" + h + "\n"
	path := a.path
	if path == "" {
		path = "/etc/rpcnode/panel.htpasswd"
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(line), 0o640); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		f, openErr := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o640)
		if openErr != nil {
			return err
		}
		_, werr := f.Write([]byte(line))
		cerr := f.Close()
		if werr != nil {
			return werr
		}
		if cerr != nil {
			return cerr
		}
	}
	a.path = path
	return a.reload()
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

// isPublicPanelPath — health / root identity on the node agent (SPA lives on standalone panel).
func isPublicPanelPath(path string) bool {
	switch {
	case path == "/", path == "":
		return true
	case path == "/healthz", path == "/gateway/health":
		return true
	default:
		return false
	}
}

func isAPIPath(path string) bool {
	return strings.HasPrefix(path, "/api/") ||
		path == "/status.json" ||
		path == "/instances.json" ||
		path == "/instance.json" ||
		path == "/internal/auth-token"
}

// Middleware: AGENT_API_TOKEN (preferred) and/or legacy htpasswd basic for agent JSON APIs.
// Ops SPA auth lives on the standalone panel — not here.
func (a *PanelAuth) Middleware(sessions *SessionStore, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Path
		if isPublicPanelPath(path) {
			next.ServeHTTP(w, r)
			return
		}
		// Agent API key (control plane / machines).
		if a.tokenOK(r) {
			next.ServeHTTP(w, r)
			return
		}
		// If no agent token is configured, allow (dev / open lab). Production should set AGENT_API_TOKEN.
		if agentAPIToken() == "" && !agentAPITokenRequired() {
			next.ServeHTTP(w, r)
			return
		}
		// Legacy curl: HTTP basic against htpasswd (optional).
		if user, pass, ok := r.BasicAuth(); ok && a.enabled() && a.verify(user, pass) {
			next.ServeHTTP(w, r)
			return
		}
		if isAPIPath(path) {
			writeJSON(w, http.StatusUnauthorized, map[string]any{
				"ok":      false,
				"error":   "unauthorized",
				"message": "Use AGENT_API_TOKEN (Bearer / X-Api-Token) against the node agent.",
			})
			return
		}
		writeJSON(w, http.StatusNotFound, map[string]any{
			"ok": false, "error": "not_found",
			"message": "node agent — no ops SPA; use standalone panel",
		})
	})
}

func (a *PanelAuth) tokenOK(r *http.Request) bool {
	want := agentAPIToken()
	if want == "" {
		return false
	}
	return tokenMatch(extractAPIToken(r), want)
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
