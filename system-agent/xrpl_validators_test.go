package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestXRPLCanonicalValidatorsHasNoThresholdZero(t *testing.T) {
	body := xrplCanonicalValidators("mainnet")
	if strings.Contains(body, "threshold") {
		t.Fatal(body)
	}
	if xrplBareZeroLineRe.MatchString(body) {
		t.Fatal("bare 0")
	}
	if !xrplValidatorsOK(body, "mainnet") {
		t.Fatal("canonical must be ok")
	}
	if !strings.Contains(body, xrplVLKeyRipple) || !strings.Contains(body, "unl.xrplf.org") {
		t.Fatal(body)
	}
}

func TestXRPLValidatorsRejectsStrayZeroInKeys(t *testing.T) {
	raw := `[validator_list_sites]
https://vl.ripple.com

[validator_list_keys]
` + xrplVLKeyRipple + `
` + xrplVLKeyXRPLF + `
# [validator_list_threshold]
0
`
	if xrplValidatorsOK(raw, "mainnet") {
		t.Fatal("stray 0 after commented threshold must be invalid")
	}
}

func TestXRPLCanonicalValidatorsTestnet(t *testing.T) {
	body := xrplCanonicalValidators("testnet")
	if strings.Contains(body, "threshold") || strings.Contains(body, "vl.ripple.com") {
		t.Fatal(body)
	}
	if !xrplValidatorsOK(body, "testnet") {
		t.Fatal("testnet canonical must be ok")
	}
	if !strings.Contains(body, xrplVLKeyAltnet) || !strings.Contains(body, "vl.altnet.rippletest.net") {
		t.Fatal(body)
	}
}

func TestHealXRPLValidatorsFileRewritesBroken(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "validators.txt")
	broken := `[validator_list_keys]
ED2677ABFFD1B33AC6FBC3062B71F1E8397C1505E1C42C64D11AD1B28FF73F4734
# [validator_list_threshold]
0

[validator_list_threshold]
`
	if err := os.WriteFile(path, []byte(broken), 0o644); err != nil {
		t.Fatal(err)
	}
	ok, err := healXRPLValidatorsFile(dir, "mainnet")
	if err != nil || !ok {
		t.Fatalf("heal: ok=%v err=%v", ok, err)
	}
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !xrplValidatorsOK(string(got), "mainnet") {
		t.Fatalf("still broken:\n%s", got)
	}
	ok, err = healXRPLValidatorsFile(dir, "mainnet")
	if err != nil || ok {
		t.Fatalf("second heal no-op: ok=%v err=%v", ok, err)
	}
}

func TestXRPLInfoAcquiringValidated(t *testing.T) {
	if !xrplInfoAcquiringValidated(xrplServerInfo{OK: true, Seq: 0, Uptime: 113, Peers: 21, Proposers: 35}) {
		t.Fatal("peers+proposers while seq=0 is acquiring")
	}
	if xrplInfoAcquiringValidated(xrplServerInfo{OK: true, Seq: 91000000, Uptime: 113, Peers: 21}) {
		t.Fatal("seq>0 is not acquiring")
	}
	if xrplInfoAcquiringValidated(xrplServerInfo{OK: true, Seq: 0, Uptime: 5, Peers: 21}) {
		t.Fatal("fresh process is not acquiring yet")
	}
	if xrplInfoAcquiringValidated(xrplServerInfo{OK: false, Seq: 0, Uptime: 113, Peers: 21}) {
		t.Fatal("RPC down is not acquiring")
	}
}

func TestXRPLInboundStallBlobIgnoresWaitingForValidated(t *testing.T) {
	if xrplInboundStallBlob("LedgerMaster:ERR Need validated ledger") {
		t.Fatal("waiting for first validated ledger is not a stall")
	}
	if xrplInboundStallBlob("accepted_ledger=0 seq=0 proposers=35") {
		t.Fatal("seq=0 with proposers is not a stall")
	}
	if !xrplInboundStallBlob("InboundLedger timeout\nledger data request timeout seq=0") {
		t.Fatal("real inbound timeout must still count")
	}
}
