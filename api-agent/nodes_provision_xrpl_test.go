package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestXRPLNodeSizeForRAM(t *testing.T) {
	cases := []struct {
		ram  float64
		want string
	}{
		{0, "medium"},
		{4, "tiny"},
		{7.9, "tiny"},
		{8, "small"},
		{15.9, "small"},
		{16, "medium"},
		{31, "medium"},
		{32, "huge"},
		{128, "huge"},
		{390, "huge"},
	}
	for _, tc := range cases {
		if got := xrplNodeSizeForRAMGiB(tc.ram); got != tc.want {
			t.Fatalf("ram=%.1f: got %s want %s", tc.ram, got, tc.want)
		}
	}
	if got := xrplNodeSize(390, false); got != "medium" {
		t.Fatalf("empty NuDB on 390 GiB must bootstrap medium, got %s", got)
	}
	if got := xrplNodeSize(390, true); got != "huge" {
		t.Fatalf("after first ledger 390 GiB must be huge, got %s", got)
	}
}

func TestWriteXRPLConfigFullHistoryNotHugeDefault(t *testing.T) {
	dir := t.TempDir()
	etc := filepath.Join(dir, "etc")
	data := filepath.Join(dir, "data")
	if err := os.MkdirAll(etc, 0o755); err != nil {
		t.Fatal(err)
	}
	path, err := writeXRPLConfig(etc, data, nodeProvisionRequest{
		NodeHTTPPort: 5005, P2PPort: 51235,
	}, lookupXRPLNetwork("mainnet"))
	if err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	body := string(b)
	if strings.Contains(body, "online_delete") {
		t.Fatalf("full history must omit online_delete:\n%s", body)
	}
	if !strings.Contains(body, "[ledger_history]\nfull\n") {
		t.Fatalf("want ledger_history=full:\n%s", body)
	}
	if !strings.Contains(body, "r.ripple.com 51235") {
		t.Fatalf("want mainnet [ips]:\n%s", body)
	}
	if !strings.Contains(body, "s2.ripple.com 51235") {
		t.Fatalf("want s2 history peer in ips_fixed:\n%s", body)
	}
	if !strings.Contains(body, "[peers_max]\n100\n") {
		t.Fatalf("want peers_max=100 for outgoing history fetch:\n%s", body)
	}
	if !strings.Contains(body, "hub.xrpl-commons.org 51235") {
		t.Fatalf("want official hub list:\n%s", body)
	}
	if !strings.Contains(body, "[node_size]\nmedium\n") {
		t.Fatalf("empty datadir must bootstrap medium, got:\n%s", body)
	}
}

func TestRenderXRPLUnitHasNoExecStop(t *testing.T) {
	u := renderXRPLUnit("mainnet", "/usr/bin/xrpld", "/etc/xrpl/mainnet/xrpld.cfg")
	if strings.Contains(u, "\nExecStop=") {
		t.Fatal("ExecStop=server_stop races SIGKILL on a stalled xrpld")
	}
	if !strings.Contains(u, "KillMode=mixed") {
		t.Fatal("want KillMode=mixed")
	}
}
