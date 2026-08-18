package main

import "testing"

func TestParseTonVersionRPC(t *testing.T) {
	id, hit := parseTonVersionRPC([]byte(`{"jsonrpc":"2.0","id":7,"method":"getVersion","params":[]}`))
	if !hit {
		t.Fatal("expected hit")
	}
	if n, ok := id.(float64); !ok || n != 7 {
		t.Fatalf("id=%v", id)
	}
	if _, hit := parseTonVersionRPC([]byte(`{"method":"getMasterchainInfo"}`)); hit {
		t.Fatal("getMasterchainInfo is not a version method")
	}
	if _, hit := parseTonVersionRPC([]byte(`not json`)); hit {
		t.Fatal("garbage")
	}
}

func TestRewriteTonUpstreamPath(t *testing.T) {
	if got := rewriteTonUpstreamPath("POST", "/", ""); got != "/api/v2/jsonRPC" {
		t.Fatalf("got %q", got)
	}
	if got := rewriteTonUpstreamPath("POST", "/api/v2/jsonRPC", ""); got != "" {
		t.Fatalf("must not rewrite existing path: %q", got)
	}
	if got := rewriteTonUpstreamPath("GET", "/", ""); got != "" {
		t.Fatalf("GET / stays: %q", got)
	}
}

func TestTonClientVersionFromState(t *testing.T) {
	st := map[string]any{"client_version": "bb935a83e8da"}
	if got := tonClientVersionFromState(st); got != "bb935a83e8da" {
		t.Fatalf("got %q", got)
	}
	st = map[string]any{"rpc": map[string]any{"client_version": "2.17.1"}}
	if got := tonClientVersionFromState(st); got != "2.17.1" {
		t.Fatalf("got %q", got)
	}
}
