package main

import (
	"net/http"
	"testing"
	"time"
)

func TestSessionTTLIs30Days(t *testing.T) {
	if sessionTTL != 30*24*time.Hour {
		t.Fatalf("sessionTTL=%v want 30d", sessionTTL)
	}
	s := NewSessionStore("")
	if s.ttl != sessionTTL {
		t.Fatalf("store ttl=%v want %v", s.ttl, sessionTTL)
	}
	tok, exp := s.Create("admin")
	if tok == "" {
		t.Fatal("empty token")
	}
	until := time.Until(exp)
	if until < 29*24*time.Hour || until > 31*24*time.Hour {
		t.Fatalf("expires_at delta=%v want ~30d", until)
	}
}

func TestSessionTokenFromRequestBearerAndCookie(t *testing.T) {
	req, _ := http.NewRequest(http.MethodGet, "/api/workloads", nil)
	req.Header.Set("Authorization", "Bearer abc123token")
	if got := sessionTokenFromRequest(req); got != "abc123token" {
		t.Fatalf("bearer got %q", got)
	}

	req2, _ := http.NewRequest(http.MethodGet, "/api/workloads", nil)
	req2.Header.Set("X-Panel-Token", "panel-tok")
	if got := sessionTokenFromRequest(req2); got != "panel-tok" {
		t.Fatalf("x-panel-token got %q", got)
	}

	req3, _ := http.NewRequest(http.MethodGet, "/api/workloads", nil)
	req3.AddCookie(&http.Cookie{Name: sessionCookieName, Value: "cookie-tok"})
	if got := sessionTokenFromRequest(req3); got != "cookie-tok" {
		t.Fatalf("cookie got %q", got)
	}

	// Bearer wins over cookie when both present.
	req4, _ := http.NewRequest(http.MethodGet, "/api/workloads", nil)
	req4.Header.Set("Authorization", "Bearer from-header")
	req4.AddCookie(&http.Cookie{Name: sessionCookieName, Value: "from-cookie"})
	if got := sessionTokenFromRequest(req4); got != "from-header" {
		t.Fatalf("prefer bearer got %q", got)
	}
}
