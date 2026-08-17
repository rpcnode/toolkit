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

func TestWriteXRPLConfigDefaultWeeksHistory(t *testing.T) {
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
	if !strings.Contains(body, "online_delete=300000") {
		t.Fatalf("default weeks must set online_delete:\n%s", body)
	}
	if !strings.Contains(body, "[ledger_history]\n300000\n") {
		t.Fatalf("default install is 2 weeks, not full:\n%s", body)
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
	if !strings.Contains(body, "[port_grpc]\nport = 51251\n") {
		t.Fatalf("want localhost gRPC for Clio:\n%s", body)
	}
	if !strings.Contains(body, "[port_ws_public]\nport = 6005\n") {
		t.Fatalf("want public WS for Clio ETL:\n%s", body)
	}
}

func TestScyllaIOPropertiesYAML(t *testing.T) {
	body := scyllaIOPropertiesYAML("/data")
	if !strings.Contains(body, "mountpoint: /data") {
		t.Fatalf("mount: %s", body)
	}
	if !strings.Contains(body, "read_iops:") {
		t.Fatalf("iops: %s", body)
	}
	if got := scyllaIOPropertiesYAML(""); !strings.Contains(got, "mountpoint: /") {
		t.Fatalf("empty mount: %s", got)
	}
}

func TestScyllaAptListURL(t *testing.T) {
	if got := scyllaAptListURL("ubuntu"); !strings.Contains(got, "/deb/ubuntu/scylla-") {
		t.Fatalf("ubuntu list: %s", got)
	}
	if got := scyllaAptListURL("debian"); !strings.Contains(got, "/deb/debian/scylla-") {
		t.Fatalf("debian list: %s", got)
	}
	if got := scyllaAptListURL(""); !strings.Contains(got, "/deb/debian/scylla-") {
		t.Fatalf("unknown os → debian list: %s", got)
	}
	if strings.Contains(scyllaWebInstallerURL, "/deb/install") {
		t.Fatal("dead downloads.scylladb.com/deb/install must not be used")
	}
}

func TestScyllaMemoryGiB(t *testing.T) {
	t.Setenv("SCYLLA_MEMORY_GIB", "")
	if got := scyllaMemoryGiB(12); got != 4 {
		t.Fatalf("small host=%d", got)
	}
	if got := scyllaMemoryGiB(96); got != 32 {
		t.Fatalf("96 GiB host → 1/3: %d", got)
	}
	if got := scyllaMemoryGiB(384); got != 96 {
		t.Fatalf("cap 96, got %d", got)
	}
	t.Setenv("SCYLLA_MEMORY_GIB", "48")
	if got := scyllaMemoryGiB(96); got != 48 {
		t.Fatalf("override=%d", got)
	}
}

func TestWriteXRPLClioConfig(t *testing.T) {
	dir := t.TempDir()
	if err := writeXRPLClioConfig("mainnet", dir, filepath.Join(dir, "data")); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(filepath.Join(dir, "clio.json"))
	if err != nil {
		t.Fatal(err)
	}
	body := string(raw)
	if !strings.Contains(body, `"port": 51233`) {
		t.Fatalf("clio listen:\n%s", body)
	}
	if !strings.Contains(body, `"grpc_port": "51251"`) {
		t.Fatalf("clio grpc:\n%s", body)
	}
	if !strings.Contains(body, `"keyspace": "clio_mainnet"`) {
		t.Fatalf("clio keyspace:\n%s", body)
	}
}

func TestWriteXRPLConfigFullHistory(t *testing.T) {
	dir := t.TempDir()
	etc := filepath.Join(dir, "etc")
	data := filepath.Join(dir, "data")
	if err := os.MkdirAll(etc, 0o755); err != nil {
		t.Fatal(err)
	}
	path, err := writeXRPLConfig(etc, data, nodeProvisionRequest{
		NodeHTTPPort: 5005, P2PPort: 51235, XRPLHistory: "full",
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
}

func TestWriteXRPLConfigStockHistory(t *testing.T) {
	dir := t.TempDir()
	etc := filepath.Join(dir, "etc")
	data := filepath.Join(dir, "data")
	path, err := writeXRPLConfig(etc, data, nodeProvisionRequest{
		NodeHTTPPort: 5005, XRPLHistory: "stock",
	}, lookupXRPLNetwork("mainnet"))
	if err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	body := string(b)
	if !strings.Contains(body, "[ledger_history]\n2000\n") {
		t.Fatalf("stock window:\n%s", body)
	}
	if !strings.Contains(body, "online_delete=2000") {
		t.Fatalf("stock online_delete:\n%s", body)
	}
}

func TestRenderXRPLUnitBoundedServerStop(t *testing.T) {
	u := renderXRPLUnit("mainnet", "/usr/bin/xrpld", "/etc/xrpl/mainnet/xrpld.cfg")
	if !strings.Contains(u, "ExecStop=/usr/bin/timeout 15 /usr/bin/xrpld --conf /etc/xrpl/mainnet/xrpld.cfg server_stop") {
		t.Fatalf("want bounded server_stop:\n%s", u)
	}
	if !strings.Contains(u, "TimeoutStopSec=45") {
		t.Fatal("want TimeoutStopSec=45")
	}
	if !strings.Contains(u, "KillMode=mixed") {
		t.Fatal("want KillMode=mixed")
	}
}
