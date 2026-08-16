package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestBaseL1URLs(t *testing.T) {
	t.Setenv("RPCNODE_L1_RPC_URL", "")
	t.Setenv("RPCNODE_L1_BEACON_URL", "")
	if got := defaultL1RPCURLFor(baseL1Env("sepolia")); got != publicSepoliaL1RPCURL {
		t.Fatalf("sepolia L1 RPC=%q want publicnode", got)
	}
	if got := defaultL1BeaconURLFor(baseL1Env("sepolia")); got != "https://ethereum-sepolia-beacon-api.publicnode.com" {
		t.Fatalf("sepolia L1 beacon=%q want publicnode sepolia", got)
	}
	if got := defaultL1RPCURLFor(baseL1Env("mainnet")); got != "http://185.44.207.117:39690" {
		t.Fatalf("mainnet L1 RPC=%q want :39690", got)
	}
	if got := defaultL1BeaconURLFor(baseL1Env("mainnet")); got != "http://185.44.207.117:15052" {
		t.Fatalf("mainnet L1 beacon=%q want ethereum-host :15052", got)
	}
}

func TestRenderBaseConsensusWrapperWritesJWTBeforeWait(t *testing.T) {
	body := renderBaseConsensusWrapper("/opt/base/sepolia/bin/base-consensus")
	jwtIdx := strings.Index(body, `printf '%s\n' "$BASE_NODE_L2_ENGINE_AUTH_RAW"`)
	waitIdx := strings.Index(body, `/dev/tcp/`)
	if jwtIdx < 0 {
		t.Fatal("wrapper must write JWT file from AUTH_RAW")
	}
	if waitIdx < 0 {
		t.Fatal("wrapper must wait on engine TCP, not unauthenticated HTTP")
	}
	if jwtIdx > waitIdx {
		t.Fatal("JWT must be written before the engine wait loop")
	}
	if strings.Contains(body, `= "401"`) {
		t.Fatal("must not wait for HTTP 401 on authrpc (reth often returns 200 JSON error)")
	}
	if strings.Contains(body, `%{http_code}`) {
		t.Fatal("must not poll authrpc HTTP without JWT")
	}
	if !strings.Contains(body, "exec /opt/base/sepolia/bin/base-consensus node") {
		t.Fatalf("exec path missing: %s", body)
	}
}

func TestWriteBaseConsensusEnvQuotesAuthRaw(t *testing.T) {
	dir := t.TempDir()
	cluster := lookupBaseNetwork("sepolia")
	raw := "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
	if err := writeBaseConsensusEnv(dir, cluster, "http://127.0.0.1:39691", "http://beacon",
		"/etc/base/sepolia/jwt.hex", raw, 8574, 9033); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(filepath.Join(dir, "consensus.env"))
	if err != nil {
		t.Fatal(err)
	}
	body := string(b)
	if !strings.Contains(body, `BASE_NODE_L2_ENGINE_AUTH_RAW="`+raw+`"`) {
		t.Fatalf("AUTH_RAW must be systemd-quoted, got:\n%s", body)
	}
	if !strings.Contains(body, "BASE_NODE_L1_ETH_RPC=http://127.0.0.1:39691") {
		t.Fatalf("L1 RPC missing:\n%s", body)
	}
	if !strings.Contains(body, "BASE_NODE_NETWORK=base-sepolia") {
		t.Fatalf("network flag:\n%s", body)
	}
}
