package main

import "testing"

func TestParseSystemdIPAccounting(t *testing.T) {
	rx, tx, ok := parseSystemdIPAccounting(`IPIngressBytes=1000
IPEgressBytes=2000
MainPID=42
`)
	if !ok || rx != 1000 || tx != 2000 {
		t.Fatalf("got rx=%d tx=%d ok=%v", rx, tx, ok)
	}
	_, _, ok = parseSystemdIPAccounting(`IPIngressBytes=0
IPEgressBytes=0
MainPID=0
`)
	if ok {
		t.Fatal("idle dead unit should be omitted")
	}
}

func TestParseSystemdUnitResources(t *testing.T) {
	rx, tx, cpu, mem, pid, ok := parseSystemdUnitResources(`IPIngressBytes=10
IPEgressBytes=20
CPUUsageNSec=5000000000
MemoryCurrent=104857600
MainPID=9
`)
	if !ok || rx != 10 || tx != 20 || cpu != 5000000000 || mem != 104857600 || pid != 9 {
		t.Fatalf("rx=%d tx=%d cpu=%d mem=%d pid=%d ok=%v", rx, tx, cpu, mem, pid, ok)
	}
}

func TestParseCgroupMemoryStatAnon(t *testing.T) {
	n, ok := parseCgroupMemoryStatAnon("anon 3182268416\nfile 36867461120\n")
	if !ok || n != 3182268416 {
		t.Fatalf("n=%d ok=%v", n, ok)
	}
}

func TestNodeNetTrackerSnapshot(t *testing.T) {
	n := newNodeNetTracker()
	n.mu.Lock()
	n.ok = true
	n.rxMbps = 1.5
	n.txMbps = 0.5
	n.rxBytes = 999
	n.txBytes = 888
	n.cpuPct = 12.5
	n.memPct = 33.3
	n.mu.Unlock()
	snap := n.Snapshot()
	if snap["node_net_rx_mbps"] != 1.5 || snap["node_net_tx_bytes"] != uint64(888) {
		t.Fatalf("%v", snap)
	}
	if snap["node_cpu_pct"] != 12.5 || snap["node_mem_pct"] != 33.3 {
		t.Fatalf("cpu/mem %v", snap)
	}
}
