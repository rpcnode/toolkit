package main

import (
	"fmt"
	"net"
	"testing"
	"time"
)

func TestDialHostFromAgentURL(t *testing.T) {
	cases := map[string]string{
		"http://203.0.113.10:38990":   "203.0.113.10",
		"https://node.example:38990/": "node.example",
		"http://[2001:db8::1]:38990":  "2001:db8::1",
		"":                            "",
	}
	for raw, want := range cases {
		if got := dialHostFromAgentURL(raw); got != want {
			t.Fatalf("%q: got %q want %q", raw, got, want)
		}
	}
}

func TestDialProbeNonceReachableAndFiltered(t *testing.T) {
	nonce := "abc123"
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		_, _ = fmt.Fprintf(c, "HTTP/1.0 200 OK\r\n\r\n%s%s\n", probeBannerPrefix, nonce)
		_ = c.Close()
	}()
	if got := dialProbeNonce("127.0.0.1", port, nonce, time.Second); got != "reachable" {
		t.Fatalf("reachable=%q", got)
	}
	_ = ln.Close()
	if got := dialProbeNonce("127.0.0.1", port, nonce, 200*time.Millisecond); got != "filtered" {
		t.Fatalf("filtered=%q", got)
	}
}

func TestApplyReachToChecked(t *testing.T) {
	checked := []any{
		map[string]any{"port": 39091, "role": "public_port", "external": true, "bind": "free"},
		map[string]any{"port": 18091, "role": "node_http_port", "external": false, "bind": "free"},
		map[string]any{"port": 18889, "role": "p2p_port", "external": true, "bind": "busy"},
	}
	applyReachToChecked(checked, map[int]string{39091: "filtered", 18889: "skipped"}, map[int]string{18889: "busy"})
	pub := checked[0].(map[string]any)
	if pub["reach"] != "filtered" {
		t.Fatalf("public reach=%v", pub["reach"])
	}
	internal := checked[1].(map[string]any)
	if internal["reach"] != "n/a" {
		t.Fatalf("internal reach=%v", internal["reach"])
	}
	sum := reachSummary("203.0.113.10", checked, true, "")
	if truthy(sum["open_ok"]) {
		t.Fatal("filtered must not be open_ok")
	}
	msg, _ := sum["message"].(string)
	if msg == "" {
		t.Fatal("empty reach message")
	}
}
