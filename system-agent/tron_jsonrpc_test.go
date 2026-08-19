package main

import (
	"strings"
	"testing"
)

func TestPatchTronJSONRPCEnablesDisabledBlock(t *testing.T) {
	in := `node {
  jsonrpc {
    httpFullNodeEnable = false
    httpFullNodePort = 8545
  }
}
`
	got := patchTronJSONRPC(in, 18545)
	if !strings.Contains(got, "httpFullNodeEnable = true") || !strings.Contains(got, "httpFullNodePort = 18545") {
		t.Fatalf("got:\n%s", got)
	}
	if strings.Contains(got, "httpFullNodeEnable = false") {
		t.Fatalf("still disabled:\n%s", got)
	}
}

func TestPatchTronJSONRPCInjectsMissingBlock(t *testing.T) {
	in := `node {
  listen.port = 18888
}
`
	got := patchTronJSONRPC(in, 18545)
	if !strings.Contains(got, "httpFullNodeEnable = true") || !strings.Contains(got, "httpFullNodePort = 18545") {
		t.Fatalf("got:\n%s", got)
	}
}
