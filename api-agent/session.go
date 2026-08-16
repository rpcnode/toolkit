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

const sessionCookieName = "rpcnode_session"

type sessionRec struct {
	User      string    `json:"user"`
	ExpiresAt time.Time `json:"expires_at"`
}

// SessionStore — HttpOnly cookie sessions for the main panel (humans).
// Agent API keys stay separate (Bearer / X-Api-Token).
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
		ttl:      7 * 24 * time.Hour,
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

func sessionTokenFromRequest(r *http.Request) string {
	c, err := r.Cookie(sessionCookieName)
	if err != nil || c == nil {
		return ""
	}
	return strings.TrimSpace(c.Value)
}

func defaultAgentDownloadURL() string {
	if v := strings.TrimSpace(os.Getenv("AGENT_DOWNLOAD_URL")); v != "" {
		return v
	}
	// Placeholder until rpcnode hosts the install script / binary.
	return "https://rpcnode.dev/install/agent.sh"
}

func agentAPIToken() string {
	if v := strings.TrimSpace(os.Getenv("AGENT_API_TOKEN")); v != "" {
		return v
	}
	return strings.TrimSpace(os.Getenv("TRON_API_TOKEN"))
}

func agentAPITokenRequired() bool {
	return os.Getenv("AGENT_API_TOKEN_REQUIRED") == "1" || os.Getenv("TRON_API_TOKEN_REQUIRED") == "1"
}
