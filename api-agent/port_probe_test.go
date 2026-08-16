package main

import (
	"bytes"
	"io"
	"net"
	"strconv"
	"testing"
	"time"
)

func TestPortProbeListenAndDial(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	_ = ln.Close()

	roles := []catalogPortRole{{Port: port, Role: "public_port", Label: "Go RPC (proxy)", External: true}}
	nonce, ports := startPortProbe(roles, 0)
	defer stopPortProbe()
	if nonce == "" {
		t.Fatal("empty nonce")
	}
	if len(ports) != 1 || ports[0]["listen"] != "ok" {
		t.Fatalf("ports=%v", ports)
	}

	if got := readProbe(t, port, nonce, 2*time.Second); got != "reachable" {
		t.Fatalf("dial=%q", got)
	}

	stopPortProbe()
	if got := readProbe(t, port, nonce, 200*time.Millisecond); got != "filtered" {
		t.Fatalf("after stop dial=%q", got)
	}
}

func TestPortProbeSkipBusyAndInternal(t *testing.T) {
	ln, err := net.Listen("tcp", ":0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	port := ln.Addr().(*net.TCPAddr).Port

	roles := []catalogPortRole{
		{Port: port, Role: "public_port", Label: "Go RPC (proxy)", External: true},
		{Port: port + 1, Role: "node_http_port", Label: "Upstream", External: false},
	}
	_, ports := startPortProbe(roles, 0)
	defer stopPortProbe()
	if len(ports) != 1 {
		t.Fatalf("internal port must not be probed: %v", ports)
	}
	if ports[0]["listen"] != "skip" {
		t.Fatalf("busy port listen=%v", ports[0])
	}
}

func TestPortProbeSkipTipListen(t *testing.T) {
	roles := []catalogPortRole{{Port: 38990, Role: "public_port", Label: "Go RPC", External: true}}
	_, ports := startPortProbe(roles, 38990)
	defer stopPortProbe()
	if len(ports) != 1 || ports[0]["listen"] != "skip" || ports[0]["reason"] != "tip_listen" {
		t.Fatalf("ports=%v", ports)
	}
}

func TestProbeResponseBody(t *testing.T) {
	if got := probeResponseBody("deadbeef"); got != "rpcnode-probe deadbeef\n" {
		t.Fatalf("body=%q", got)
	}
}

func readProbe(t *testing.T, port int, nonce string, timeout time.Duration) string {
	t.Helper()
	conn, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), timeout)
	if err != nil {
		return "filtered"
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(timeout))
	buf, _ := io.ReadAll(io.LimitReader(conn, 512))
	if bytes.Contains(buf, []byte(probeBannerPrefix+nonce)) {
		return "reachable"
	}
	return "filtered"
}
