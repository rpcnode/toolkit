package main

import (
	"strings"
	"testing"
)

func TestHostLogNoisy(t *testing.T) {
	if !hostLogNoisy("INFO", "rpc-proxy", "listen :39090 → 127.0.0.1:18090 network=tron env=mainnet") {
		t.Fatal("listen must be dropped")
	}
	if hostLogNoisy("ERROR", "rpc-proxy", "upstream timeout") {
		t.Fatal("proxy errors stay")
	}
	if !hostLogNoisy("ERROR", "rpc-proxy", "upstream_unavailable 127.0.0.1:0 dial tcp 127.0.0.1:0: connect: connection refused") {
		t.Fatal("tip upstream :0 must be dropped")
	}
	if !hostLogNoisy("INFO", "start", "version=0.4.117 log=/var/log/rpcnode.log") {
		t.Fatal("process boot must be dropped")
	}
	if hostLogNoisy("INFO", "start", "begin tron/mainnet") {
		t.Fatal("node start stays")
	}
	if !hostLogNoisy("INFO", "java8", "using /usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java") {
		t.Fatal("java8 using must be dropped")
	}
}

func TestRedactHostLog(t *testing.T) {
	in := "update token=abc123 Bearer xyz agent_key=secret password: hunter2 ok"
	got := redactHostLog(in)
	for _, bad := range []string{"abc123", "xyz", "secret", "hunter2"} {
		if strings.Contains(got, bad) {
			t.Fatalf("leaked %q in %q", bad, got)
		}
	}
	if !strings.Contains(got, "ok") {
		t.Fatalf("lost trailing text: %q", got)
	}
}
