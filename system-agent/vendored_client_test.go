package main

import (
	"strings"
	"testing"
)

func TestParseVendoredManifest(t *testing.T) {
	raw := []byte(`{
		"version": "4.8.2.1-PQ1-build1",
		"tag": "GreatVoyage-Nile-v4.8.2.1-PQ1-build1",
		"artifact_url": "https://cdn.example/clients/tron/nile/dist/x86_64/FullNode.jar",
		"artifact_url_aarch64": "https://cdn.example/clients/tron/nile/dist/aarch64/FullNode.jar",
		"artifact_kind": "jar",
		"needs_conf_patch": true,
		"notes": "vendored nile",
		"files": [
			{"role": "config", "name": "config-nile.conf", "status": "ok"}
		]
	}`)
	rel, err := parseVendoredManifest("tron", "nile", "https://cdn.example/install", raw)
	if err != nil {
		t.Fatal(err)
	}
	if rel.Source != "cdn" {
		t.Fatalf("source=%q", rel.Source)
	}
	if rel.Version != "4.8.2.1-pq1-build1" && rel.Version != "4.8.2.1-PQ1-build1" {
		if !strings.Contains(rel.Version, "4.8.2.1") {
			t.Fatalf("version=%q", rel.Version)
		}
	}
	if rel.ConfURL != "https://cdn.example/install/clients/tron/nile/conf/config-nile.conf" {
		t.Fatalf("conf=%q", rel.ConfURL)
	}
	wantX86 := "https://cdn.example/clients/tron/nile/dist/x86_64/FullNode.jar"
	wantARM := "https://cdn.example/clients/tron/nile/dist/aarch64/FullNode.jar"
	if hostIsARM() {
		if rel.ArtifactURL != wantARM {
			t.Fatalf("arm jar=%q", rel.ArtifactURL)
		}
	} else if rel.ArtifactURL != wantX86 {
		t.Fatalf("x86 jar=%q", rel.ArtifactURL)
	}
}

func TestParseVendoredManifestExplicitConfURL(t *testing.T) {
	raw := []byte(`{
		"artifact_url": "https://cdn.example/jar.jar",
		"conf_url": "https://cdn.example/clients/tron/mainnet/conf/main_net_config.conf"
	}`)
	rel, err := parseVendoredManifest("tron", "mainnet", "https://cdn.example/install", raw)
	if err != nil {
		t.Fatal(err)
	}
	if rel.ConfURL != "https://cdn.example/clients/tron/mainnet/conf/main_net_config.conf" {
		t.Fatalf("conf=%q", rel.ConfURL)
	}
}

func TestPickVendoredJar(t *testing.T) {
	man := vendoredManifest{
		ArtifactURL:        "https://x86.example/FullNode.jar",
		ArtifactURLAarch64: "https://arm.example/FullNode.jar",
	}
	if got := pickVendoredJar(man, false); got != man.ArtifactURL {
		t.Fatalf("x86=%q", got)
	}
	if got := pickVendoredJar(man, true); got != man.ArtifactURLAarch64 {
		t.Fatalf("arm=%q", got)
	}
	man.ArtifactURLAarch64 = ""
	if got := pickVendoredJar(man, true); got != man.ArtifactURL {
		t.Fatalf("arm fallback=%q", got)
	}
}
