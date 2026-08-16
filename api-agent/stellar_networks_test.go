package main

import (
	"math"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestApplyStellarCaptiveCorePorts_RootPeerPort(t *testing.T) {
	in := `NETWORK_PASSPHRASE="Test SDF Network ; September 2015"
UNSAFE_QUORUM=true
HTTP_PORT=11628
PEER_PORT=9999

[[HOME_DOMAINS]]
HOME_DOMAIN="testnet.stellar.org"
QUALITY="HIGH"

[[VALIDATORS]]
NAME="sdf_testnet_2"
HOME_DOMAIN="testnet.stellar.org"
PUBLIC_KEY="GCUCJTIYXSOXKBSNFGNFWW5MUQ54HKRPGJUTQFJ5RQXZXNOLNXYDHRAP"
ADDRESS="core-testnet2.stellar.org"
HTTP_PORT=9999
PEER_PORT=8888
`
	out := applyStellarCaptiveCorePorts(in, stellarNetwork{CoreHTTPPort: 11628, PeerPort: 11627})
	if strings.Contains(out, "HTTP_PORT") {
		t.Fatalf("HTTP_PORT must stay in toml only:\n%s", out)
	}
	if !strings.Contains(out, "PEER_PORT=11627\n") {
		t.Fatalf("want root PEER_PORT=11627 from Confirm:\n%s", out)
	}
	if strings.Contains(out, "PEER_PORT=8888") || strings.Contains(out, "PEER_PORT=9999") {
		t.Fatalf("stale PEER_PORT left behind:\n%s", out)
	}
	// Root PEER_PORT must appear before [[VALIDATORS]].
	if idxPeer, idxVal := strings.Index(out, "PEER_PORT=11627"), strings.Index(out, "[[VALIDATORS]]"); idxPeer < 0 || idxVal < 0 || idxPeer > idxVal {
		t.Fatalf("PEER_PORT must be root-level before validators:\n%s", out)
	}
}

func TestStellarHistoryRetentionFull(t *testing.T) {
	if stellarHistoryRetentionWindow != uint32(math.MaxUint32) {
		t.Fatalf("want MaxUint32 never-prune, got %d", stellarHistoryRetentionWindow)
	}
	dir := t.TempDir()
	path := filepath.Join(dir, "stellar-rpc.toml")
	if err := os.WriteFile(path, []byte("ENDPOINT = \"127.0.0.1:8000\"\nHISTORY_RETENTION_WINDOW = 120960\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	changed, err := ensureStellarFullHistoryToml(dir, 11626)
	if err != nil || !changed {
		t.Fatalf("changed=%v err=%v", changed, err)
	}
	b, _ := os.ReadFile(path)
	if !strings.Contains(string(b), "HISTORY_RETENTION_WINDOW = 4294967295") {
		t.Fatalf("toml not patched: %s", b)
	}
	if !strings.Contains(string(b), "STELLAR_CAPTIVE_CORE_HTTP_QUERY_PORT = 11626") {
		t.Fatalf("HTTP_QUERY not patched: %s", b)
	}
	changed, err = ensureStellarFullHistoryToml(dir, 11626)
	if err != nil || changed {
		t.Fatalf("idempotent: changed=%v err=%v", changed, err)
	}
	if lookupPortProfile("stellar", "mainnet").DiskHintGiB < 512 {
		t.Fatal("mainnet disk plan must cover never-prune growth")
	}
}

func TestResolveStellarRPCImage(t *testing.T) {
	t.Setenv("STELLAR_RPC_VERSION", "27.1.1")
	if got := resolveStellarRPCImage(); got != "stellar/stellar-rpc:27.1.1" {
		t.Fatalf("image=%s", got)
	}
	if got := resolveStellarRPCVersionTag(); got != "v27.1.1" {
		t.Fatalf("tag=%s", got)
	}
	if got := resolveStellarRPCImageForEnv("futurenet"); got != stellarFuturenetRPCImage {
		t.Fatalf("futurenet image=%s want %s", got, stellarFuturenetRPCImage)
	}
	if !stellarNeedsVNext("futurenet") || stellarNeedsVNext("testnet") {
		t.Fatal("only futurenet needs vnext")
	}
}

func TestStellarJournalProtocolMarkers(t *testing.T) {
	if !stellarJournalTextNeedsReset("Catchup material failed verification - unsupported ledger version") {
		t.Fatal("expected unsupported ledger → reset")
	}
}

func TestStellarAptSuitesForHost(t *testing.T) {
	got := stellarAptSuitesForHost("noble")
	if len(got) == 0 || got[0] != "noble" {
		t.Fatalf("noble host should prefer noble suite: %v", got)
	}
	got = stellarAptSuitesForHost("jammy")
	if got[0] != "jammy" {
		t.Fatalf("jammy host should prefer jammy: %v", got)
	}
	got = stellarAptSuitesForHost("focal")
	if got[0] != "focal" {
		t.Fatalf("focal host should prefer focal: %v", got)
	}
	got = stellarAptSuitesForHost("resolute")
	if got[0] == "focal" {
		t.Fatalf("unknown newer must not prefer focal: %v", got)
	}
	joined := strings.Join(stellarAptSuitesForHost(""), ",")
	if !strings.Contains(joined, "noble") || !strings.Contains(joined, "jammy") {
		t.Fatalf("empty host suites: %s", joined)
	}
}

func TestStellarNetworksDayOne(t *testing.T) {
	for _, env := range []string{"mainnet", "testnet", "futurenet"} {
		p := lookupPortProfile("stellar", env)
		if p.Network != "stellar" || p.Env != env {
			t.Fatalf("profile missing for stellar/%s: %#v", env, p)
		}
		if p.Public <= 0 || p.Agent <= 0 || p.NodeHTTP <= 0 || p.SolHTTP <= 0 {
			t.Fatalf("bad ports stellar/%s: %#v", env, p)
		}
		n := lookupStellarNetwork(env)
		if n.Passphrase == "" || n.CaptiveCoreURL == "" {
			t.Fatalf("incomplete stellar meta for %s: %#v", env, n)
		}
		if p.SolHTTP != n.CoreHTTPPort {
			t.Fatalf("SolHTTP (HTTP_QUERY) must match CoreHTTPPort for %s: %d vs %d", env, p.SolHTTP, n.CoreHTTPPort)
		}
		if p.P2P != n.PeerPort {
			t.Fatalf("P2P must match PeerPort for %s: %d vs %d", env, p.P2P, n.PeerPort)
		}
	}
	if !networkSupports("stellar") {
		t.Fatal("stellar not in supportedNetworks")
	}
	caps := lifecycleCapabilities("stellar", "testnet")
	if caps["snapshot"] || !caps["ibd"] {
		t.Fatalf("caps: %#v", caps)
	}
	if stellarSysListen("mainnet") != 8630 || stellarSysListen("testnet") != 8631 || stellarSysListen("futurenet") != 8632 {
		t.Fatal("sys listen ports mismatch")
	}
}
