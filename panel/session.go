package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	sessionCookieName = "rpcnode_session"
	// Panel human session TTL (cookie + Bearer token from POST /api/auth/login).
	sessionTTL = 30 * 24 * time.Hour
)

type sessionRec struct {
	User      string    `json:"user"`
	ExpiresAt time.Time `json:"expires_at"`
}

// SessionStore — HttpOnly cookie + Bearer sessions for the main panel (humans).
// Agent API keys stay separate (registry AGENT_API_TOKEN on panel→agent calls).
type SessionStore struct {
	mu       sync.RWMutex
	path     string
	sessions map[string]sessionRec
	ttl      time.Duration
}

func NewSessionStore(path string) *SessionStore {
	s := &SessionStore{
		path:     path,
		sessions: map[string]sessionRec{},
		ttl:      sessionTTL,
	}
	_ = s.load()
	return s
}

func (s *SessionStore) load() error {
	if s.path == "" {
		return nil
	}
	b, err := os.ReadFile(s.path)
	if err != nil {
		return err
	}
	var doc struct {
		Sessions map[string]sessionRec `json:"sessions"`
	}
	if err := json.Unmarshal(b, &doc); err != nil {
		return err
	}
	now := time.Now().UTC()
	next := map[string]sessionRec{}
	for k, v := range doc.Sessions {
		if v.ExpiresAt.After(now) && v.User != "" {
			next[k] = v
		}
	}
	s.mu.Lock()
	s.sessions = next
	s.mu.Unlock()
	return nil
}

func (s *SessionStore) persist() {
	if s.path == "" {
		return
	}
	s.mu.RLock()
	doc := map[string]any{"sessions": s.sessions}
	s.mu.RUnlock()
	b, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return
	}
	_ = os.MkdirAll(filepath.Dir(s.path), 0o755)
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return
	}
	_ = os.Rename(tmp, s.path)
}

func (s *SessionStore) Create(user string) (token string, exp time.Time) {
	buf := make([]byte, 32)
	_, _ = rand.Read(buf)
	token = hex.EncodeToString(buf)
	exp = time.Now().UTC().Add(s.ttl)
	s.mu.Lock()
	s.sessions[token] = sessionRec{User: user, ExpiresAt: exp}
	s.mu.Unlock()
	s.persist()
	return token, exp
}

func (s *SessionStore) Get(token string) (user string, ok bool) {
	if token == "" {
		return "", false
	}
	s.mu.RLock()
	rec, found := s.sessions[token]
	s.mu.RUnlock()
	if !found || rec.ExpiresAt.Before(time.Now().UTC()) {
		if found {
			s.Revoke(token)
		}
		return "", false
	}
	return rec.User, true
}

func (s *SessionStore) Revoke(token string) {
	s.mu.Lock()
	delete(s.sessions, token)
	s.mu.Unlock()
	s.persist()
}

// RevokeUser drops persisted sessions for login (disk). Running panel may keep
// in-memory tokens until restart — passwd CLI documents docker restart.
func (s *SessionStore) RevokeUser(user string) int {
	user = strings.TrimSpace(user)
	if user == "" {
		return 0
	}
	n := 0
	s.mu.Lock()
	for k, v := range s.sessions {
		if v.User == user {
			delete(s.sessions, k)
			n++
		}
	}
	s.mu.Unlock()
	if n > 0 {
		s.persist()
	}
	return n
}

func setSessionCookie(w http.ResponseWriter, token string, exp time.Time) {
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookieName,
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Expires:  exp,
		MaxAge:   int(time.Until(exp).Seconds()),
	})
}

func clearSessionCookie(w http.ResponseWriter) {
	// MaxAge < 0 → Set-Cookie Max-Age=0 (delete). Path/SameSite must match login cookie.
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookieName,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   -1,
		Expires:  time.Unix(0, 0),
	})
}

// sessionTokenFromRequest — panel human session from Bearer / X-Panel-Token / cookie.
// Prefer Authorization Bearer so curl/scripts match the SPA without cookie jars.
// Agent keys are NOT accepted here; middleware falls through to HTTP Basic if Get fails.
func sessionTokenFromRequest(r *http.Request) string {
	if h := strings.TrimSpace(r.Header.Get("Authorization")); len(h) >= 7 &&
		strings.EqualFold(h[:7], "bearer ") {
		if tok := strings.TrimSpace(h[7:]); tok != "" {
			return tok
		}
	}
	if tok := strings.TrimSpace(r.Header.Get("X-Panel-Token")); tok != "" {
		return tok
	}
	c, err := r.Cookie(sessionCookieName)
	if err != nil || c == nil {
		return ""
	}
	return strings.TrimSpace(c.Value)
}

func defaultAgentDownloadURL() string {
	if v := strings.TrimSpace(os.Getenv("AGENT_DOWNLOAD_URL")); v != "" {
		return canonToolkitCDNHost(v)
	}
	return "https://toolkit.rpcnode.dev/install/agent.sh"
}
