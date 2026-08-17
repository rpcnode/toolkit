package main

import "testing"

func TestXRPLBuildIsBroken32(t *testing.T) {
	if !xrplBuildIsBroken32("3.2.0-1") || !xrplBuildIsBroken32("1.12.0 / 3.2.0") {
		t.Fatal("3.2.0 is the known first-ledger bug")
	}
	if xrplBuildIsBroken32("3.3.0") || xrplBuildIsBroken32("3.1.3-1") || xrplBuildIsBroken32("") {
		t.Fatal("3.3.0 / 3.1.3 / empty are not the 3.2.x bug class")
	}
}

func TestXRPLVersionMatchesCatalog(t *testing.T) {
	if !xrplVersionMatchesCatalog("3.3.0-1", "3.3.0") {
		t.Fatal("deb suffix matches catalog pin")
	}
	if xrplVersionMatchesCatalog("3.2.0-1", "3.3.0") {
		t.Fatal("3.2.0 is not catalog 3.3.0")
	}
}
