package main

import (
	"strings"
	"testing"
)

func TestNormalizeClientVersion(t *testing.T) {
	cases := map[string]string{
		"GreatVoyage-v4.8.2.1": "4.8.2.1",
		"greatvoyage-v4.7.7":   "4.7.7",
		"v4.8.2":               "4.8.2",
		"4.8.2.1":              "4.8.2.1",
		"/Dash Core:23.1.8/":   "23.1.8",
		"/Satoshi:27.0.0/":     "27.0.0",
		"Geth/v1.14.0/linux":   "1.14.0",
		"":                     "",
	}
	for in, want := range cases {
		if got := normalizeClientVersion(in); got != want {
			t.Fatalf("normalizeClientVersion(%q)=%q want %q", in, got, want)
		}
	}
}

func TestFormatClientVersion(t *testing.T) {
	cases := map[string]string{
		"/Dash Core:23.1.8/":                     "dash core 23.1.8",
		"/Satoshi:27.0.0/":                       "satoshi 27.0.0",
		"/Litecoin Core:0.21.5.6/":               "0.21.5.6", // network name stripped
		"/Bitcoin Cash Node:29.1.0(EB32.0)/":     "29.1.0(eb32.0)",
		"Bitcoin Cash Node:29.1.0(eb32.0)":       "29.1.0(eb32.0)",
		"/Dogecoin Core:1.14.9/":                 "1.14.9",
		"Geth/v1.14.12/linux-amd64/go":           "geth 1.14.12",
		"geth 1.17.4 · lighthouse 8.2.1":         "geth 1.17.4 · lighthouse 8.2.1",
		"Geth/v1.17.4-stable/linux · Lighthouse v8.2.1": "geth 1.17.4-stable · lighthouse 8.2.1",
		"GreatVoyage-v4.8.2.1":                   "4.8.2.1",
		"4.8.2.1":                                "4.8.2.1",
		"DASH CORE 23.1.8":                       "dash core 23.1.8",
		"bash: line 1: validator-engine: command not found": "",
		"": "",
	}
	for in, want := range cases {
		if got := formatClientVersion(in); got != want {
			t.Fatalf("formatClientVersion(%q)=%q want %q", in, got, want)
		}
	}
}

func TestTronReleaseFromTag(t *testing.T) {
	rel := tronReleaseFromTag(tronClientPinTag, "mainnet", "pin")
	if rel.Version != "4.8.2.1" {
		t.Fatalf("version=%q", rel.Version)
	}
	if !strings.Contains(rel.ArtifactURL, "FullNode.jar") {
		t.Fatalf("artifact=%q", rel.ArtifactURL)
	}
	if rel.ArtifactURL != "https://github.com/tronprotocol/java-tron/releases/download/GreatVoyage-v4.8.2.1/FullNode.jar" {
		t.Fatalf("unexpected jar url: %s", rel.ArtifactURL)
	}
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

func TestResolveTronPinned(t *testing.T) {
	t.Setenv("RPCNODE_CLIENT_RELEASE_PIN", "1")
	t.Setenv("TRON_TAG", "")
	t.Setenv("TRON_JAR_URL", "")
	t.Setenv("TRON_CONFIG_URL", "")
	t.Setenv("CLIENTS_BASE_URL", "http://127.0.0.1:1")
	t.Setenv("AGENT_DOWNLOAD_URL", "")
	rel, err := ResolveClientRelease("tron", "mainnet")
	if err != nil {
		t.Fatal(err)
	}
	if rel.Source != "pin" || rel.Version != "4.8.2.1" {
		t.Fatalf("got %+v", rel)
	}
	if rel.ArtifactURL == "" {
		t.Fatal("empty artifact_url")
	}
}

func TestEthereumClientVersionDisplay(t *testing.T) {
	got := formatEthereumClientVersion("Geth/v1.17.4-stable-36a7dc72/linux-amd64/go1.25.7", "Lighthouse v8.2.1")
	want := "geth 1.17.4-stable-36a7dc72 · lighthouse 8.2.1"
	if got != want {
		t.Fatalf("formatEthereumClientVersion=%q want %q", got, want)
	}
	if parseLighthouseVersion("Lighthouse v8.2.1\nBLS library: blst") != "8.2.1" {
		t.Fatalf("parseLighthouseVersion line: %q", parseLighthouseVersion("Lighthouse v8.2.1\nBLS library: blst"))
	}
	if parseLighthouseVersion("Lighthouse/v8.2.1-abc/x86_64-linux") != "8.2.1-abc" {
		t.Fatalf("parseLighthouseVersion ua: %q", parseLighthouseVersion("Lighthouse/v8.2.1-abc/x86_64-linux"))
	}
}

func TestEthereumUpdateComparesLighthouseNotGeth(t *testing.T) {
	localN, latestN, latestDisplay := clientVersionsForUpdate(
		"ethereum",
		"geth 1.17.4-stable-36a7dc72",
		"8.2.1",
	)
	if localN != "" {
		t.Fatalf("geth-only local must not compare to lighthouse pin, got localN=%q", localN)
	}
	if latestN != "8.2.1" || latestDisplay != "lighthouse 8.2.1" {
		t.Fatalf("latestN=%q display=%q", latestN, latestDisplay)
	}
	if localN != "" && latestN != "" && versionCompareLoose(localN, latestN) < 0 {
		t.Fatal("must not flag geth 1.17.4 → 8.2.1 as an update")
	}

	localN, latestN, latestDisplay = clientVersionsForUpdate(
		"ethereum",
		"geth 1.17.4 · lighthouse 8.2.1",
		"8.2.1",
	)
	if localN != "8.2.1" || latestN != "8.2.1" || latestDisplay != "lighthouse 8.2.1" {
		t.Fatalf("current CL: localN=%q latestN=%q display=%q", localN, latestN, latestDisplay)
	}
	if versionCompareLoose(localN, latestN) < 0 {
		t.Fatal("lighthouse 8.2.1 vs pin 8.2.1 must be up to date")
	}

	localN, latestN, _ = clientVersionsForUpdate(
		"ethereum",
		"geth 1.17.4 · lighthouse 8.1.0",
		"v8.2.1",
	)
	if localN != "8.1.0" || latestN != "8.2.1" {
		t.Fatalf("behind CL: localN=%q latestN=%q", localN, latestN)
	}
	if versionCompareLoose(localN, latestN) >= 0 {
		t.Fatal("lighthouse 8.1.0 should be older than 8.2.1")
	}
}

func TestVersionCompareNormalized(t *testing.T) {
	local := normalizeClientVersion("4.7.7")
	latest := normalizeClientVersion("GreatVoyage-v4.8.2.1")
	if versionCompareLoose(local, latest) >= 0 {
		t.Fatalf("%s should be older than %s", local, latest)
	}
}
