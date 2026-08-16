package main

import (
	"encoding/base64"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestReadBitcoinCookieFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, ".cookie")
	if err := os.WriteFile(path, []byte("__cookie__:deadbeef\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	u, p, ok := readBitcoinCookieFile(path)
	if !ok || u != "__cookie__" || p != "deadbeef" {
		t.Fatalf("got %q %q ok=%v", u, p, ok)
	}
}

func TestUpstreamRPCAuthHeaderFromCookie(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, ".cookie")
	if err := os.WriteFile(path, []byte("__cookie__:s3cret"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := Config{Env: "mainnet", CookieFile: path}
	auth := upstreamRPCAuthHeader(cfg)
	want := "Basic " + base64.StdEncoding.EncodeToString([]byte("__cookie__:s3cret"))
	if auth != want {
		t.Fatalf("auth=%q want %q", auth, want)
	}
}

func TestUpstreamRPCAuthHeaderPrefersEnvUser(t *testing.T) {
	t.Setenv("BITCOIN_RPC_USER", "rpcnode")
	t.Setenv("BITCOIN_RPC_PASSWORD", "pass")
	auth := upstreamRPCAuthHeader(Config{CookieFile: "/no/such/cookie"})
	if !strings.HasPrefix(auth, "Basic ") {
		t.Fatalf("auth=%q", auth)
	}
	raw, _ := base64.StdEncoding.DecodeString(strings.TrimPrefix(auth, "Basic "))
	if string(raw) != "rpcnode:pass" {
		t.Fatalf("decoded=%q", raw)
	}
}
