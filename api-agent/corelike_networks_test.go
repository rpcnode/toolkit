package main

import (
	"strings"
	"testing"
)

func TestRenderCoreLikeConfTestnetSection(t *testing.T) {
	client, ok := lookupCoreLike("dash")
	if !ok {
		t.Fatal("dash missing")
	}
	prof := lookupPortProfile("dash", "testnet")
	body := renderCoreLikeConf(client, prof, 19999, 19998, 512, "rpcnode", "secret")
	for _, want := range []string{
		"testnet=1", "[test]", "port=19999", "rpcport=19998",
		"datadir=/data/dash", // parent — Core nests → /data/dash/testnet3 or testnet
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("missing %q in:\n%s", want, body)
		}
	}
	if strings.Index(body, "port=19999") < strings.Index(body, "[test]") {
		t.Fatalf("port must be under [test]:\n%s", body)
	}
	reg := lookupPortProfile("ltc", "regtest")
	ltc, _ := lookupCoreLike("ltc")
	regBody := renderCoreLikeConf(ltc, reg, 19444, 19443, 256, "rpcnode", "secret")
	if !strings.Contains(regBody, "[regtest]") ||
		strings.Index(regBody, "port=19444") < strings.Index(regBody, "[regtest]") {
		t.Fatalf("ltc regtest section:\n%s", regBody)
	}

	ltcTN := lookupPortProfile("ltc", "testnet")
	if ltcTN.DataPath != "/data/ltc/testnet4" {
		t.Fatalf("ltc testnet DataPath=%q want /data/ltc/testnet4 (litecoind nests testnet4)", ltcTN.DataPath)
	}
	ltcTNBody := renderCoreLikeConf(ltc, ltcTN, 19333, 19332, 512, "rpcnode", "secret")
	if !strings.Contains(ltcTNBody, "datadir=/data/ltc\n") || !strings.Contains(ltcTNBody, "testnet=1") {
		t.Fatalf("ltc testnet conf want parent datadir + testnet=1:\n%s", ltcTNBody)
	}
	if nest := coreLikeProvisionNestDir("ltc", "testnet", "/data/ltc/testnet"); nest != "/data/ltc/testnet4" {
		t.Fatalf("nest=%q want /data/ltc/testnet4", nest)
	}
}

func TestLookupCoreLikeClients(t *testing.T) {
	for _, net := range []string{"ltc", "dash", "bch"} {
		c, ok := lookupCoreLike(net)
		if !ok {
			t.Fatalf("missing corelike %s", net)
		}
		if c.Daemon == "" || c.CLI == "" || c.ConfName == "" {
			t.Fatalf("%s incomplete: %+v", net, c)
		}
		url := c.DownloadURL(c.DefaultVersion, "x86_64-linux-gnu")
		if url == "" || !containsHTTP(url) {
			t.Fatalf("%s bad download url %q", net, url)
		}
	}
	if networkIsCoreLike("doge") || networkIsCoreLike("bitcoin") {
		t.Fatal("doge/bitcoin are not corelike helpers (own provisioners)")
	}
	if !networkUsesRPCUserAuth("doge") || !networkUsesRPCUserAuth("ltc") {
		t.Fatal("rpcuser auth expected for doge/ltc")
	}
}

func containsHTTP(s string) bool {
	return len(s) > 8 && (s[:8] == "https://" || s[:7] == "http://")
}

func TestResolveCoreLikeBinaryBCHNeverUsrLocal(t *testing.T) {
	// On bitcoin hosts /usr/local/bin/bitcoind → Bitcoin Core. BCHN must only
	// resolve under /opt/bch so ensureCoreLikeInstalled downloads the right client.
	c, ok := lookupCoreLike("bch")
	if !ok {
		t.Fatal("bch missing")
	}
	for _, cand := range coreLikeBinaryCandidates(c, "/opt/bch/mainnet") {
		if strings.Contains(cand, "/usr/local") {
			t.Fatalf("BCH candidates must not include %q: %v", cand, coreLikeBinaryCandidates(c, "/opt/bch/mainnet"))
		}
	}
	got := resolveCoreLikeBinary(c, "/opt/bch/mainnet")
	want := "/opt/bch/mainnet/bin/bitcoind"
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
	cli := resolveCoreLikeCLI(c, "/opt/bch/mainnet", got)
	if cli != "/opt/bch/mainnet/bin/bitcoin-cli" {
		t.Fatalf("cli=%q want /opt/bch/mainnet/bin/bitcoin-cli", cli)
	}
}
