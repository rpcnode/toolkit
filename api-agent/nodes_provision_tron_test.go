package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSystemAgentListenPort_NeverJavaTronDefaults(t *testing.T) {
	for _, env := range []string{"mainnet", "nile", "shasta"} {
		p := systemAgentListenPort("tron", env)
		if p == 8090 || p == 8091 || p == 8092 || p == 8093 {
			t.Fatalf("tron/%s system-agent port %d collides with java-tron/panel defaults", env, p)
		}
		if p < 29090 || p > 29099 {
			t.Fatalf("tron/%s expected 2909x, got %d", env, p)
		}
	}
	if systemAgentListenPort("bitcoin", "mainnet") != 8191 {
		t.Fatalf("bitcoin mainnet sys listen")
	}
	if systemAgentListenPort("ethereum", "mainnet") != 8390 {
		t.Fatalf("ethereum mainnet sys listen")
	}
	if systemAgentListenPort("ethereum", "sepolia") != 8391 {
		t.Fatalf("ethereum sepolia sys listen")
	}
	if systemAgentListenPort("bsc", "mainnet") != 8490 {
		t.Fatalf("bsc mainnet sys listen")
	}
	if systemAgentListenPort("bsc", "testnet") != 8491 {
		t.Fatalf("bsc testnet sys listen")
	}
	if systemAgentListenPort("hyperliquid", "mainnet") != 8590 {
		t.Fatalf("hyperliquid mainnet sys listen")
	}
	if systemAgentListenPort("arb", "mainnet") != 8591 {
		t.Fatalf("arb mainnet sys listen")
	}
	if systemAgentListenPort("robinhood", "mainnet") != 8670 {
		t.Fatalf("robinhood mainnet sys listen")
	}
	if systemAgentListenPort("robinhood", "testnet") != 8671 {
		t.Fatalf("robinhood testnet sys listen")
	}
	if systemAgentListenPort("optimism", "mainnet") != 8592 {
		t.Fatalf("optimism mainnet sys listen")
	}
	if systemAgentListenPort("base", "mainnet") != 8680 {
		t.Fatalf("base mainnet sys listen")
	}
	if systemAgentListenPort("base", "sepolia") != 8681 {
		t.Fatalf("base sepolia sys listen")
	}
	if systemAgentListenPort("xrpl", "mainnet") != 8600 {
		t.Fatalf("xrpl mainnet sys listen")
	}
	if systemAgentListenPort("xrpl", "testnet") != 8601 {
		t.Fatalf("xrpl testnet sys listen")
	}
	if systemAgentListenPort("doge", "mainnet") != 8610 {
		t.Fatalf("doge mainnet sys listen")
	}
	if systemAgentListenPort("cardano", "mainnet") != 8620 {
		t.Fatalf("cardano mainnet sys listen")
	}
	if systemAgentListenPort("stellar", "mainnet") != 8630 {
		t.Fatalf("stellar mainnet sys listen")
	}
	if systemAgentListenPort("stellar", "testnet") != 8631 {
		t.Fatalf("stellar testnet sys listen")
	}
	if systemAgentListenPort("ltc", "mainnet") != 8640 {
		t.Fatalf("ltc main sys listen")
	}
	if systemAgentListenPort("dash", "mainnet") != 8642 {
		t.Fatalf("dash main sys listen")
	}
	if systemAgentListenPort("bch", "mainnet") != 8644 {
		t.Fatalf("bch main sys listen")
	}
	if systemAgentListenPort("stellar", "futurenet") != 8632 {
		t.Fatalf("stellar futurenet sys listen")
	}
	if systemAgentListenPort("ltc", "mainnet") != 8640 {
		t.Fatalf("ltc mainnet sys listen")
	}
	if systemAgentListenPort("dash", "mainnet") != 8642 {
		t.Fatalf("dash mainnet sys listen")
	}
	if systemAgentListenPort("bch", "mainnet") != 8644 {
		t.Fatalf("bch mainnet sys listen")
	}
	if systemAgentListenPort("ltc", "regtest") != 8646 {
		t.Fatalf("ltc regtest sys listen")
	}
	if systemAgentListenPort("dash", "regtest") != 8647 {
		t.Fatalf("dash regtest sys listen")
	}
	if systemAgentListenPort("ton", "mainnet") != 8650 {
		t.Fatalf("ton mainnet sys-listen want 8650 got %d", systemAgentListenPort("ton", "mainnet"))
	}
	if systemAgentListenPort("ton", "testnet") != 8651 {
		t.Fatalf("ton testnet sys-listen want 8651 got %d", systemAgentListenPort("ton", "testnet"))
	}
	if systemAgentListenPort("etc", "mainnet") != 8660 {
		t.Fatalf("etc mainnet sys-listen want 8660 got %d", systemAgentListenPort("etc", "mainnet"))
	}
	if systemAgentListenPort("etc", "mordor") != 8661 {
		t.Fatalf("etc mordor sys-listen want 8661 got %d", systemAgentListenPort("etc", "mordor"))
	}
	if systemAgentListenPort("zcash", "mainnet") != 8690 {
		t.Fatalf("zcash mainnet sys-listen want 8690 got %d", systemAgentListenPort("zcash", "mainnet"))
	}
	if systemAgentListenPort("zcash", "testnet") != 8691 {
		t.Fatalf("zcash testnet sys-listen want 8691 got %d", systemAgentListenPort("zcash", "testnet"))
	}
	if systemAgentListenPort("sui", "mainnet") != 8700 {
		t.Fatalf("sui mainnet sys-listen want 8700 got %d", systemAgentListenPort("sui", "mainnet"))
	}
	if systemAgentListenPort("sui", "testnet") != 8701 {
		t.Fatalf("sui testnet sys-listen want 8701 got %d", systemAgentListenPort("sui", "testnet"))
	}
	if systemAgentListenPort("aptos", "mainnet") != 8710 {
		t.Fatalf("aptos mainnet sys-listen want 8710 got %d", systemAgentListenPort("aptos", "mainnet"))
	}
	if systemAgentListenPort("aptos", "testnet") != 8711 {
		t.Fatalf("aptos testnet sys-listen want 8711 got %d", systemAgentListenPort("aptos", "testnet"))
	}
	if systemAgentListenPort("avalanche", "mainnet") != 8720 {
		t.Fatalf("avalanche mainnet sys-listen want 8720 got %d", systemAgentListenPort("avalanche", "mainnet"))
	}
	if systemAgentListenPort("avalanche", "fuji") != 8721 {
		t.Fatalf("avalanche fuji sys-listen want 8721 got %d", systemAgentListenPort("avalanche", "fuji"))
	}
	if systemAgentListenPort("avalanche", "testnet") != 8721 {
		t.Fatalf("avalanche testnet alias sys-listen want 8721 got %d", systemAgentListenPort("avalanche", "testnet"))
	}
	if systemAgentListenPort("bch", "regtest") != 8648 {
		t.Fatalf("bch regtest sys listen")
	}
}

func TestEnsureTronConfig_DisablesHTTPSolidityPBFT(t *testing.T) {
	dir := t.TempDir()
	conf := filepath.Join(dir, "main_net_config.conf")
	body := `storage {
  db.engine = "LEVELDB",
  db.directory = "database",
}

node {
  listen.port = 18888
  maxHttpConnectNumber = 50
  http {
    fullNodeEnable = true
    fullNodePort = 8090
    solidityEnable = true
    solidityPort = 8091
    PBFTEnable = true
    PBFTPort = 8092
  }
  rpc {
    enable = true
    port = 50051
    solidityPort = 50061
    PBFTPort = 50071
  }
}
rate.limiter = {
  global.qps = 1000
  global.ip.qps = 1000
  apiNonBlocking = false
}
`
	if err := os.WriteFile(conf, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := ensureTronConfig(conf, "mainnet", 18090, 18888); err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(conf)
	s := string(got)
	for _, want := range []string{
		"checkpoint.version = 2",
		"fullNodePort = 18090",
		"listen.port = 18888",
		"solidityEnable = false",
		"PBFTEnable = false",
		"solidityPort = 18190",
		"PBFTPort = 18191",
		"port = 50051",
		"solidityPort = 50061",
		"PBFTPort = 50071",
		"maxHttpConnectNumber = 4000",
		"global.qps = 200000",
		"global.ip.qps = 200000",
		"apiNonBlocking = true",
	} {
		if !strings.Contains(s, want) {
			t.Fatalf("missing %q in:\n%s", want, s)
		}
	}
	if strings.Contains(s, "solidityPort = 8091") || strings.Contains(s, "PBFTPort = 8092") {
		t.Fatalf("stock colliding ports still present:\n%s", s)
	}
}

func TestEnsureTronConfig_NileUncommentsGRPCSolidityPort(t *testing.T) {
	dir := t.TempDir()
	conf := filepath.Join(dir, "main_net_config.conf")
	// Stock nile_net_config leaves solidity gRPC commented → defaults to 50061.
	body := `node {
  listen.port = 18888
  http {
    fullNodePort = 8090
    solidityPort = 8091
  }
  rpc {
    port = 50051
    #solidityPort = 50061
  }
}
`
	if err := os.WriteFile(conf, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := ensureTronConfig(conf, "nile", 18091, 18889); err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(conf)
	s := string(got)
	if !strings.Contains(s, "solidityPort = 50161") {
		t.Fatalf("want active solidityPort 50161, got:\n%s", s)
	}
	if strings.Contains(s, "#solidityPort") {
		t.Fatalf("solidityPort must be uncommented:\n%s", s)
	}
	if strings.Contains(s, "solidityPort = 50061") {
		t.Fatalf("must not keep mainnet default 50061:\n%s", s)
	}
}

func TestEnsureTronConfig_NileAuxPortsDoNotStealMainnetHTTP(t *testing.T) {
	dir := t.TempDir()
	conf := filepath.Join(dir, "main_net_config.conf")
	body := `node {
  listen.port = 18888
  http {
    fullNodePort = 8090
    solidityPort = 8091
  }
  rpc {
    port = 50051
    solidityPort = 50061
    PBFTPort = 50071
  }
}
node.metrics = {
  prometheus {
    enable = true
    port = "9527"
  }
}
`
	if err := os.WriteFile(conf, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := ensureTronConfig(conf, "nile", 18091, 18889); err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(conf)
	s := string(got)
	for _, want := range []string{
		"fullNodePort = 18091",
		"listen.port = 18889",
		"solidityEnable = false",
		"PBFTEnable = false",
		"solidityPort = 18290",
		"PBFTPort = 18291",
		"port = 50151",
		`port = "9528"`,
	} {
		if !strings.Contains(s, want) {
			t.Fatalf("missing %q in:\n%s", want, s)
		}
	}
	// Must not park Nile aux on mainnet FullNode HTTP or mainnet gRPC.
	for _, bad := range []string{"fullNodePort = 18090", "solidityPort = 18091", "port = 50051"} {
		if strings.Contains(s, bad) {
			t.Fatalf("nile conf must not use %q:\n%s", bad, s)
		}
	}
}

func TestTronPortProfiles_NoHTTPOverlap(t *testing.T) {
	envs := []string{"mainnet", "nile", "shasta"}
	used := map[int]string{}
	claim := func(port int, who string) {
		if port <= 0 {
			return
		}
		if prev, ok := used[port]; ok {
			t.Fatalf("port %d claimed by %s and %s", port, prev, who)
		}
		used[port] = who
	}
	for _, env := range envs {
		p := lookupPortProfile("tron", env)
		claim(p.Public, env+"/public")
		claim(p.Agent, env+"/agent")
		claim(p.NodeHTTP, env+"/http")
		claim(p.P2P, env+"/p2p")
		claim(p.SolHTTP, env+"/sol-http")
		claim(p.PBFTHTTP, env+"/pbft-http")
		claim(p.GRPC, env+"/grpc")
		claim(p.GRPCSol, env+"/grpc-sol")
		claim(p.GRPCPbft, env+"/grpc-pbft")
		claim(p.Metrics, env+"/metrics")
	}
}

func TestMigrateSystemAgentLoopback(t *testing.T) {
	dir := t.TempDir()
	envPath := filepath.Join(dir, "toolkit.env")
	_ = os.WriteFile(envPath, []byte("TRON_SYSTEM_AGENT_LISTEN=127.0.0.1:8091\nTRON_SYSTEM_AGENT_URL=http://127.0.0.1:8091\n"), 0o600)
	migrateSystemAgentLoopback(envPath, "tron", "mainnet")
	got, _ := os.ReadFile(envPath)
	s := string(got)
	if !strings.Contains(s, "TRON_SYSTEM_AGENT_LISTEN=127.0.0.1:29090") {
		t.Fatalf("listen not migrated: %s", s)
	}
	if !strings.Contains(s, "TRON_SYSTEM_AGENT_URL=http://127.0.0.1:29090") {
		t.Fatalf("url not migrated: %s", s)
	}
}

func TestIsTronNodeUnitStub(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "tron-mainnet.service")
	_ = os.WriteFile(p, []byte("ExecStart=/bin/false\n"), 0o644)
	if !isTronNodeUnitStub(p) {
		t.Fatal("expected stub")
	}
	_ = os.WriteFile(p, []byte("ExecStart=/usr/bin/java -jar FullNode.jar\n"), 0o644)
	if isTronNodeUnitStub(p) {
		t.Fatal("real unit must not be stub")
	}
}

func TestJavaMajorFromVersionOutput(t *testing.T) {
	cases := []struct {
		out  string
		want int
	}{
		{`openjdk version "1.8.0_442"`, 8},
		{`java version "1.8.0_411"`, 8},
		{`openjdk version "17.0.15" 2025-04-15`, 17},
		{`openjdk version "11.0.24"`, 11},
		{`openjdk version "21.0.6"`, 21},
		{`garbage`, 0},
	}
	for _, tc := range cases {
		if got := javaMajorFromVersionOutput(tc.out); got != tc.want {
			t.Fatalf("%q → %d, want %d", tc.out, got, tc.want)
		}
	}
}

func TestTronUnitExecJava(t *testing.T) {
	body := `[Service]
ExecStart=/usr/bin/java \
  -Xmx48g \
  -jar /opt/tron/mainnet/FullNode.jar
`
	if got := tronUnitExecJava(body); got != "/usr/bin/java" {
		t.Fatalf("got %q", got)
	}
	body8 := `ExecStart=/usr/lib/jvm/java-8-openjdk-amd64/bin/java -jar FullNode.jar`
	if got := tronUnitExecJava(body8); got != "/usr/lib/jvm/java-8-openjdk-amd64/bin/java" {
		t.Fatalf("got %q", got)
	}
}

func TestEnsureTronConfig_NileRefreshesLiveSeeds(t *testing.T) {
	dir := t.TempDir()
	conf := filepath.Join(dir, "main_net_config.conf")
	body := `node {
  listen.port = 18888
  http { fullNodePort = 8090 }
  active = [
    # Active establish connection
    # "ip:port",
  ]
}
seed.node = {
  # example:
  # ip.list = [
  #   "ip:port",
  #   "ip:port"
  # ]
  ip.list = [
    "47.252.19.181:18888"
  ]
}
`
	if err := os.WriteFile(conf, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := ensureTronConfig(conf, "nile", 18091, 18889); err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(conf)
	s := string(got)
	if !strings.Contains(s, "44.236.192.97:18888") {
		t.Fatalf("want nileex seed, got:\n%s", s)
	}
	if strings.Contains(s, "47.252.19.181:18888") {
		t.Fatalf("stale tron-docker seed must be replaced:\n%s", s)
	}
	if i := strings.Index(s, "# example:"); i >= 0 {
		j := strings.Index(s[i:], "\n  ip.list")
		if j > 0 && strings.Contains(s[i:i+j], "44.236") {
			t.Fatalf("must not write seeds into commented ip.list example:\n%s", s)
		}
	}
	active := s[strings.Index(s, "active ="):]
	if !strings.Contains(active, "44.236.192.97:18888") {
		t.Fatalf("node.active must dial live seeds:\n%s", s)
	}
}

func TestSnapshotBlocksNodeStart(t *testing.T) {
	if block, _ := snapshotBlocksNodeStart("bitcoin", "mainnet"); block {
		t.Fatal("bitcoin has no ExtraSteps snapshot")
	}
	if block, _ := snapshotBlocksNodeStart("robinhood", "mainnet"); block {
		t.Fatal("robinhood start is the snapshot (--init.url)")
	}
	marker := filepath.Join(lookupPortProfile("tron", "mainnet").DataPath, ".snapshot-ready")
	if fileExists(marker) {
		t.Skip("local /data/tron/mainnet/.snapshot-ready present")
	}
	block, msg := snapshotBlocksNodeStart("tron", "mainnet")
	if !block {
		t.Fatalf("tron without marker must block start, msg=%s", msg)
	}
	if !strings.Contains(msg, ".snapshot-ready") {
		t.Fatalf("message should name marker: %s", msg)
	}
}
