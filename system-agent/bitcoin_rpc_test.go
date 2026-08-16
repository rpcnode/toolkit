package main

import (
	"strings"
	"testing"
)

func TestBitcoinRPCRequestBodyNilParamsEmptyArray(t *testing.T) {
	raw, err := bitcoinRPCRequestBody("getblockchaininfo", nil)
	if err != nil {
		t.Fatal(err)
	}
	s := string(raw)
	if strings.Contains(s, `"params":null`) {
		t.Fatalf("Zebra rejects params:null (-32600): %s", s)
	}
	if !strings.Contains(s, `"params":[]`) {
		t.Fatalf("want empty params array: %s", s)
	}
}

func TestBitcoinRPCRequestBodyKeepsArgs(t *testing.T) {
	raw, err := bitcoinRPCRequestBody("getblockhash", []any{float64(1)})
	if err != nil {
		t.Fatal(err)
	}
	s := string(raw)
	if !strings.Contains(s, `"params":[1]`) {
		t.Fatalf("want params [1]: %s", s)
	}
}
