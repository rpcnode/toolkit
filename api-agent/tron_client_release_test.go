package main

import (
	"strings"
	"testing"
)

func TestTronReleaseFromTagNilePQ(t *testing.T) {
	nile := tronReleaseFromTag(tronClientPinTag, "nile", "pin")
	if !strings.Contains(nile.ConfURL, "config-nile.conf") {
		t.Fatalf("nile conf=%q", nile.ConfURL)
	}
	if strings.Contains(nile.ConfURL, "nile_net_config") || strings.Contains(nile.ArtifactURL, "tronprotocol/java-tron") {
		t.Fatalf("nile fallback must be PQ channel, got jar=%s conf=%s", nile.ArtifactURL, nile.ConfURL)
	}
	if !strings.Contains(nile.ArtifactURL, "nile-testnet") {
		t.Fatalf("nile jar=%q", nile.ArtifactURL)
	}
}

func TestParseVendoredManifestAPI(t *testing.T) {
	raw := []byte(`{
		"version": "4.8.2.1",
		"tag": "GreatVoyage-v4.8.2.1",
		"artifact_url": "https://cdn.example/clients/tron/mainnet/dist/x86_64/FullNode.jar",
		"artifact_url_aarch64": "https://cdn.example/clients/tron/mainnet/dist/aarch64/FullNode.jar",
		"files": [{"role": "config", "name": "main_net_config.conf", "status": "ok"}]
	}`)
	rel, err := parseVendoredManifest("tron", "mainnet", "https://cdn.example/install", raw)
	if err != nil {
		t.Fatal(err)
	}
	if rel.Source != "cdn" {
		t.Fatalf("source=%q", rel.Source)
	}
	if rel.ConfURL != "https://cdn.example/install/clients/tron/mainnet/conf/main_net_config.conf" {
		t.Fatalf("conf=%q", rel.ConfURL)
	}
	if hostIsARM() {
		if !strings.Contains(rel.ArtifactURL, "aarch64") {
			t.Fatalf("arm jar=%q", rel.ArtifactURL)
		}
	} else if !strings.Contains(rel.ArtifactURL, "x86_64") {
		t.Fatalf("x86 jar=%q", rel.ArtifactURL)
	}
}

func TestResolveTronPinnedSkipsDeadCDN(t *testing.T) {
	t.Setenv("RPCNODE_CLIENT_RELEASE_PIN", "1")
	t.Setenv("TRON_TAG", "")
	t.Setenv("TRON_JAR_URL", "")
	t.Setenv("TRON_CONFIG_URL", "")
	t.Setenv("INSTALL_BASE_URL", "http://127.0.0.1:1")
	t.Setenv("AGENT_DOWNLOAD_URL", "")
	rel := resolveTronClientRelease("mainnet")
	if rel.Source != "pin" {
		t.Fatalf("source=%q want pin after CDN miss", rel.Source)
	}
	if rel.ArtifactURL == "" {
		t.Fatal("empty artifact_url")
	}
}

func TestPreferVendoredFallsBackWhenCDNDown(t *testing.T) {
	t.Setenv("INSTALL_BASE_URL", "http://127.0.0.1:1")
	t.Setenv("AGENT_DOWNLOAD_URL", "")
	got := preferVendoredArtifact("ltc", "mainnet", "https://official.example/ltc.tgz")
	if got != "https://official.example/ltc.tgz" {
		t.Fatalf("got %q", got)
	}
}

func TestVendoredNamedConfURL(t *testing.T) {
	t.Setenv("INSTALL_BASE_URL", "https://rpcnode.dev/install")
	t.Setenv("AGENT_DOWNLOAD_URL", "")
	got := vendoredNamedConfURL("aptos", "mainnet", "genesis.blob")
	if got != "https://rpcnode.dev/install/clients/aptos/mainnet/conf/genesis.blob" {
		t.Fatalf("got %q", got)
	}
}
