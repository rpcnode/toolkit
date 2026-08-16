package main

import (
	"fmt"
	"io"
	"net"
	"os/exec"
	"testing"
	"time"
)

func TestReservedPortSetSkipsOtherEnvCanonical(t *testing.T) {
	used := reservedPortSet("tron", "nile")
	// Shasta FullNode HTTP must stay reserved so Nile cannot steal it.
	if !used[18092] {
		t.Fatal("expected shasta NodeHTTP 18092 reserved against nile plan")
	}
	if !used[18090] {
		t.Fatal("expected mainnet NodeHTTP 18090 reserved")
	}
	// Nile's own canonical must NOT be in the exclude set.
	if used[18091] {
		t.Fatal("nile NodeHTTP 18091 must not be excluded from its own plan")
	}
	if used[50161] {
		t.Fatal("nile grpc-sol 50161 must not be excluded from its own plan")
	}
}

func TestPlanEnvPortsAlwaysCanonical(t *testing.T) {
	pub, agent, httpPort, p2p, _, err := planEnvPorts("tron", "nile")
	if err != nil {
		t.Fatal(err)
	}
	c := canonicalPorts("tron", "nile")
	if pub != c.Public || agent != c.Agent || httpPort != c.NodeHTTP || p2p != c.P2P {
		t.Fatalf("want canonical %+v got pub=%d agent=%d http=%d p2p=%d", c, pub, agent, httpPort, p2p)
	}
}

func TestPlanEnvPortsNoRemapWhenCanonicalBusy(t *testing.T) {
	c := canonicalPorts("tron", "nile")
	stop := holdPortInChild(t, c.NodeHTTP)
	defer stop()

	pub, _, httpPort, _, _, err := planEnvPorts("tron", "nile")
	if err != nil {
		t.Fatal(err)
	}
	if pub != c.Public || httpPort != c.NodeHTTP {
		t.Fatalf("plan must still return catalog ports, got pub=%d http=%d", pub, httpPort)
	}
	busy := checkEnvPortsBusy("tron", "nile")
	if len(busy) == 0 {
		t.Fatal("expected busy_ports when canonical NodeHTTP held by foreign process")
	}
	found := false
	for _, b := range busy {
		if intFromAny(b["port"]) == c.NodeHTTP {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("busy_ports missing NodeHTTP %d: %+v", c.NodeHTTP, busy)
	}
}

func TestResolveLivePortProfileNoRemap(t *testing.T) {
	c := lookupPortProfile("tron", "nile")
	stop := holdPortInChild(t, c.GRPCSol)
	defer stop()

	live := resolveLivePortProfile("tron", "nile")
	if live.GRPCSol != c.GRPCSol {
		t.Fatalf("must keep catalog grpc-sol %d, got %d", c.GRPCSol, live.GRPCSol)
	}
}

func TestCheckEnvPortsBusyFreeWhenIdle(t *testing.T) {
	// dash regtest catalog ports are unlikely held in unit tests.
	busy := checkEnvPortsBusy("dash", "regtest")
	if busy == nil {
		t.Fatal("busy slice must be non-nil empty")
	}
}

func TestETCCanonicalPorts(t *testing.T) {
	main := canonicalPorts("etc", "mainnet")
	if main.Public != 41890 || main.Agent != 41990 || main.NodeHTTP != 8555 || main.P2P != 30323 {
		t.Fatalf("etc mainnet catalog: %+v", main)
	}
	mordor := canonicalPorts("etc", "mordor")
	if mordor.Public != 41891 || mordor.Agent != 41991 {
		t.Fatalf("etc mordor catalog: %+v", mordor)
	}
	if !networkSupports("etc") {
		t.Fatal("etc must be supported")
	}
	caps := lifecycleCapabilities("etc", "mainnet")
	if !caps["ibd"] || caps["snapshot"] {
		t.Fatalf("etc caps: %+v", caps)
	}
}

func TestZcashCanonicalPorts(t *testing.T) {
	main := canonicalPorts("zcash", "mainnet")
	if main.Public != 42490 || main.Agent != 42590 || main.NodeHTTP != 8232 || main.P2P != 8233 {
		t.Fatalf("zcash mainnet catalog: %+v", main)
	}
	test := canonicalPorts("zcash", "testnet")
	if test.Public != 42491 || test.Agent != 42591 || test.NodeHTTP != 18232 || test.P2P != 18233 {
		t.Fatalf("zcash testnet catalog: %+v", test)
	}
	if !networkSupports("zcash") {
		t.Fatal("zcash must be supported")
	}
	caps := lifecycleCapabilities("zcash", "mainnet")
	if !caps["ibd"] || caps["snapshot"] {
		t.Fatalf("zcash caps: %+v", caps)
	}
}

func TestSuiCanonicalPorts(t *testing.T) {
	main := canonicalPorts("sui", "mainnet")
	if main.Public != 42690 || main.Agent != 42790 || main.NodeHTTP != 9000 || main.P2P != 8084 {
		t.Fatalf("sui mainnet catalog: %+v", main)
	}
	mainProf := lookupPortProfile("sui", "mainnet")
	if mainProf.Metrics != 9184 {
		t.Fatalf("sui mainnet metrics want 9184 got %d", mainProf.Metrics)
	}
	test := canonicalPorts("sui", "testnet")
	if test.Public != 42691 || test.Agent != 42791 || test.NodeHTTP != 9001 || test.P2P != 8085 {
		t.Fatalf("sui testnet catalog: %+v", test)
	}
	testProf := lookupPortProfile("sui", "testnet")
	if testProf.Metrics != 9185 {
		t.Fatalf("sui testnet metrics want 9185 got %d", testProf.Metrics)
	}
	if !networkSupports("sui") {
		t.Fatal("sui must be supported")
	}
	caps := lifecycleCapabilities("sui", "mainnet")
	if !caps["ibd"] || caps["snapshot"] {
		t.Fatalf("sui caps: %+v", caps)
	}
}

func TestAvalancheCanonicalPorts(t *testing.T) {
	main := canonicalPorts("avalanche", "mainnet")
	if main.Public != 43090 || main.Agent != 43190 || main.NodeHTTP != 9650 || main.P2P != 9651 {
		t.Fatalf("avalanche mainnet catalog: %+v", main)
	}
	mainProf := lookupPortProfile("avalanche", "mainnet")
	if mainProf.Metrics != 9690 {
		t.Fatalf("avalanche mainnet metrics want 9690 got %d", mainProf.Metrics)
	}
	fuji := canonicalPorts("avalanche", "fuji")
	if fuji.Public != 43091 || fuji.Agent != 43191 || fuji.NodeHTTP != 9660 || fuji.P2P != 9661 {
		t.Fatalf("avalanche fuji catalog: %+v", fuji)
	}
	alias := lookupPortProfile("avalanche", "testnet")
	if alias.Env != "fuji" || alias.Public != 43091 {
		t.Fatalf("avalanche testnet alias → fuji: %+v", alias)
	}
	if !networkSupports("avalanche") {
		t.Fatal("avalanche must be supported")
	}
	caps := lifecycleCapabilities("avalanche", "mainnet")
	if !caps["ibd"] || caps["snapshot"] {
		t.Fatalf("avalanche caps: %+v", caps)
	}
}

func TestAptosCanonicalPorts(t *testing.T) {
	main := canonicalPorts("aptos", "mainnet")
	if main.Public != 42890 || main.Agent != 42990 || main.NodeHTTP != 8080 || main.P2P != 6180 {
		t.Fatalf("aptos mainnet catalog: %+v", main)
	}
	mainProf := lookupPortProfile("aptos", "mainnet")
	if mainProf.Metrics != 9101 {
		t.Fatalf("aptos mainnet metrics want 9101 got %d", mainProf.Metrics)
	}
	test := canonicalPorts("aptos", "testnet")
	if test.Public != 42891 || test.Agent != 42991 || test.NodeHTTP != 8081 || test.P2P != 6182 {
		t.Fatalf("aptos testnet catalog: %+v", test)
	}
	testProf := lookupPortProfile("aptos", "testnet")
	if testProf.Metrics != 9102 {
		t.Fatalf("aptos testnet metrics want 9102 got %d", testProf.Metrics)
	}
	if !networkSupports("aptos") {
		t.Fatal("aptos must be supported")
	}
	caps := lifecycleCapabilities("aptos", "mainnet")
	if !caps["ibd"] || caps["snapshot"] {
		t.Fatalf("aptos caps: %+v", caps)
	}
}

func TestTonCanonicalPorts(t *testing.T) {
	main := canonicalPorts("ton", "mainnet")
	if main.Public != 41690 || main.Agent != 41790 || main.NodeHTTP != 8081 || main.P2P != 30310 {
		t.Fatalf("ton mainnet catalog: %+v", main)
	}
	test := canonicalPorts("ton", "testnet")
	if test.Public != 41691 || test.Agent != 41791 || test.NodeHTTP != 8082 || test.P2P != 30311 {
		t.Fatalf("ton testnet catalog: %+v", test)
	}
	if !networkSupports("ton") {
		t.Fatal("ton must be in supportedNetworks")
	}
	caps := lifecycleCapabilities("ton", "mainnet")
	if !caps["ibd"] || caps["snapshot"] {
		t.Fatalf("ton caps: %+v", caps)
	}
	if !networkOneEnvPerHost("ton") {
		t.Fatal("ton must be one_env_per_host")
	}
}

// holdPortInChild binds port in a subprocess so portOwnedByEnv does not treat
// the test PID as "ours" (self-PID reclaim short-circuit).
func holdPortInChild(t *testing.T, port int) func() {
	t.Helper()
	cmd := exec.Command("python3", "-c", fmt.Sprintf(
		"import socket,time;s=socket.socket();s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);"+
			"s.bind(('0.0.0.0',%d));s.listen(1);time.sleep(30)", port))
	if err := cmd.Start(); err != nil {
		t.Skipf("cannot start port holder: %v", err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if portInUse(port) {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if !portInUse(port) {
		_ = cmd.Process.Kill()
		t.Skipf("port %d not held by child", port)
	}
	return func() {
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
			_, _ = cmd.Process.Wait()
		}
	}
}

func TestPortBusyForeignIgnoresEphemeralSource(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_, _ = io.Copy(io.Discard, c)
			}(c)
		}
	}()

	localPort := 40094
	if portIsListening(localPort) {
		localPort = 40194
	}
	if portIsListening(localPort) {
		t.Skip("arb sepolia catalog ports already listening")
	}

	d := net.Dialer{
		Timeout:   2 * time.Second,
		LocalAddr: &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: localPort},
	}
	conn, err := d.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Skipf("cannot bind local :%d as ephemeral source: %v", localPort, err)
	}
	defer conn.Close()

	if portBusyForeign(localPort, "arb", "sepolia") {
		t.Fatalf("ephemeral source :%d must not be check-ports foreign", localPort)
	}
	if portIsListening(localPort) {
		t.Fatalf(":%d is ESTABLISHED, not LISTEN", localPort)
	}
}

func TestEnvReclaimUnitsOmitHostTip(t *testing.T) {
	for _, u := range envReclaimUnits("tron", "mainnet") {
		if u == "rpcnode-api-agent.service" || u == "rpcnode-system-agent.service" {
			t.Fatalf("host tip unit %s must not be reclaimable as leaf", u)
		}
	}
}

func TestBusyOnlyHostTipCollision(t *testing.T) {
	busy := []map[string]any{
		{"port": 39090, "role": "public_port", "holder": "host_tip"},
	}
	if !busyOnlyHostTipCollision(busy, 39090, 39190) {
		t.Fatal("tip on TRON public must be tip-collision")
	}
	mixed := []map[string]any{
		{"port": 39090, "role": "public_port", "holder": "host_tip"},
		{"port": 18090, "role": "node_http_port", "holder": "foreign"},
	}
	if busyOnlyHostTipCollision(mixed, 39090, 39190) {
		t.Fatal("foreign upstream must not look like tip-only")
	}
}

func TestTipSelfIsNotLeafOwner(t *testing.T) {
	t.Setenv("RPCNODE_HOST_TIP", "1")
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	port := ln.Addr().(*net.TCPAddr).Port
	if portOwnedByEnv(port, "tron", "mainnet") {
		t.Fatal("tip process must not own a leaf catalog listen")
	}
	if !portBusyForeign(port, "tron", "mainnet") {
		t.Fatal("tip listen must be busy/foreign to the leaf")
	}
	if got := portBusyHolder(port, "tron", "mainnet"); got != "host_tip" {
		t.Fatalf("holder=%q want host_tip", got)
	}
}
