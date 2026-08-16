package main

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"time"
)

type authBody struct {
	Username string `json:"username"`
	Email    string `json:"email"`
	Password string `json:"password"`
}

func (b authBody) user() string {
	u := strings.TrimSpace(b.Username)
	if u == "" {
		u = strings.TrimSpace(b.Email)
	}
	return u
}

func (s *Server) handleAuthAPI(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSuffix(r.URL.Path, "/")
	switch {
	case path == "/api/auth/status" && r.Method == http.MethodGet:
		s.handleAuthStatus(w, r)
	case path == "/api/auth/login" && r.Method == http.MethodPost:
		s.handleAuthLogin(w, r)
	case path == "/api/auth/setup" && r.Method == http.MethodPost:
		s.handleAuthSetup(w, r)
	case path == "/api/auth/logout" && (r.Method == http.MethodPost || r.Method == http.MethodGet):
		s.handleAuthLogout(w, r)
	default:
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
	}
}

func (s *Server) handleAuthStatus(w http.ResponseWriter, r *http.Request) {
	needsSetup := !s.auth.enabled()
	user := ""
	authed := false
	// Session-cookie only for UI "logged in". Basic must not re-auth SPA after logout.
	if tok := sessionTokenFromRequest(r); tok != "" {
		if u, ok := s.sessions.Get(tok); ok {
			user = u
			authed = true
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":                 true,
		"needs_setup":        needsSetup,
		"authenticated":      authed,
		"user":               user,
		"agent_download_url": defaultAgentDownloadURL(),
		"links": map[string]string{
			"rpcnode": "https://rpcnode.dev",
		},
		"auth_modes": []string{"session_cookie", "agent_api_token"},
		"note":       "Ops SPA lives on standalone panel (session cookie). This agent uses AGENT_API_TOKEN.",
	})
}

func (s *Server) handleAuthLogin(w http.ResponseWriter, r *http.Request) {
	if !s.auth.enabled() {
		writeJSON(w, http.StatusConflict, map[string]any{
			"ok": false, "error": "needs_setup",
			"message": "No panel user yet — open /setup-password",
		})
		return
	}
	var body authBody
	if err := readAuthBody(r, &body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
		return
	}
	user := body.user()
	if user == "" || body.Password == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "username_and_password_required"})
		return
	}
	if !s.auth.verify(user, body.Password) {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"ok": false, "error": "invalid_credentials"})
		return
	}
	tok, exp := s.sessions.Create(user)
	setSessionCookie(w, tok, exp)
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "authenticated": true, "user": user,
		"expires_at": exp.UTC().Format(time.RFC3339),
	})
}

func (s *Server) handleAuthSetup(w http.ResponseWriter, r *http.Request) {
	if s.auth.enabled() {
		writeJSON(w, http.StatusConflict, map[string]any{
			"ok": false, "error": "already_configured",
			"message": "Panel user already exists — use /login",
		})
		return
	}
	var body authBody
	if err := readAuthBody(r, &body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
		return
	}
	user := body.user()
	if user == "" {
		user = "admin"
	}
	if len(body.Password) < 8 {
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"ok": false, "error": "password_too_short",
			"message": "Password must be at least 8 characters",
		})
		return
	}
	if err := s.auth.CreateUser(user, body.Password); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{
			"ok": false, "error": "write_failed", "message": err.Error(),
		})
		return
	}
	tok, exp := s.sessions.Create(user)
	setSessionCookie(w, tok, exp)
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "authenticated": true, "user": user, "created": true,
		"expires_at": exp.UTC().Format(time.RFC3339),
		"message":    "Admin created. You are signed in.",
	})
}

func (s *Server) handleAuthLogout(w http.ResponseWriter, r *http.Request) {
	if tok := sessionTokenFromRequest(r); tok != "" {
		s.sessions.Revoke(tok)
	}
	clearSessionCookie(w)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "authenticated": false})
}

func readAuthBody(r *http.Request, body *authBody) error {
	defer r.Body.Close()
	b, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		return err
	}
	if len(b) == 0 {
		return nil
	}
	return json.Unmarshal(b, body)
}
