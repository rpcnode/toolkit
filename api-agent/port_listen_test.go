package main

import (
	"testing"
)

func TestParseSsListenExtractsPortAndPID(t *testing.T) {
	raw := "" +
		"LISTEN 0 4096 0.0.0.0:8899 0.0.0.0:* users:((\"agave-validator\",pid=4321,fd=88))\n" +
		"LISTEN 0 4096 [::]:38990 [::]:* users:((\"rpcnode-api-age\",pid=99,fd=6))\n" +
		"LISTEN 0 4096 127.0.0.1:18090 0.0.0.0:*\n"
	snap := &listenSnap{}
	parseSsListen(raw, snap)
	if !snap.listening[8899] || snap.byPort[8899][0] != "4321" {
		t.Fatalf("8899: listen=%v pids=%v", snap.listening[8899], snap.byPort[8899])
	}
	if !snap.listening[38990] || snap.byPort[38990][0] != "99" {
		t.Fatalf("38990: listen=%v pids=%v", snap.listening[38990], snap.byPort[38990])
	}
	if !snap.listening[18090] {
		t.Fatal("18090 must be listening even without pid")
	}
	if len(snap.byPort[18090]) != 0 {
		t.Fatalf("18090 pids want empty, got %v", snap.byPort[18090])
	}
}

func TestSsLineLooksListeningRejectsNoise(t *testing.T) {
	if ssLineLooksListening("") || ssLineLooksListening("usage: ss") || ssLineLooksListening("Cannot open netlink socket") {
		t.Fatal("noise must not look like LISTEN")
	}
	if !ssLineLooksListening("LISTEN 0 4096 *:18090 *:*") {
		t.Fatal("LISTEN line")
	}
}
