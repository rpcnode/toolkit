package main

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// nodeResourceTracker — systemd cgroup accounting for the chain node unit:
// IPAccounting → net Mbps; CPUAccounting → cpu % of host; MemoryAccounting → mem %.
type nodeResourceTracker struct {
	mu sync.Mutex

	ok bool

	rxBytes, txBytes uint64
	rxBps, txBps     float64
	rxMbps, txMbps   float64

	cpuPct    float64
	memPct    float64
	memUsedMB float64

	diskReadIOPS  float64
	diskWriteIOPS float64
	diskReadMBs   float64
	diskWriteMBs  float64

	prevRx, prevTx   uint64
	prevCPUNsec      uint64
	prevAt           time.Time
	havePrev         bool
	havePrevCPU      bool

	prevDiskR, prevDiskW     uint64
	prevDiskRI, prevDiskWI   uint64
	havePrevDisk             bool

	// Ring for charts (~same window as host metrics).
	samples []nodeResourceSample
	pos     int
	count   int
}

type nodeResourceSample struct {
	T         int64
	NetRxMbps float64
	NetTxMbps float64
	CPUPct    float64
	MemPct    float64
	MemUsedMB float64
	DiskReadIOPS  float64
	DiskWriteIOPS float64
}

func newNodeNetTracker() *nodeResourceTracker {
	return &nodeResourceTracker{
		samples: make([]nodeResourceSample, SampleCount),
	}
}

func (n *nodeResourceTracker) Sample(unit string) {
	unit = strings.TrimSpace(unit)
	if unit == "" {
		return
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	rx, tx, cpuNsec, memCurrent, mainPID, ok := readSystemdUnitResources(unit)
	if !ok {
		return
	}
	diskR, diskW, diskRI, diskWI, haveDisk := readUnitIOStat(unit)
	// MemoryCurrent includes page cache (bitcoind file≈tens of GiB) and is NOT
	// comparable to host MemAvailable-based %. Prefer cgroup anon (+RSS fallback).
	memBytes := readUnitAnonMemoryBytes(unit, mainPID)
	if memBytes == 0 {
		memBytes = memCurrent // last resort
	}
	now := time.Now()
	ncpu := readNCPU()
	if ncpu < 1 {
		ncpu = 1
	}

	n.mu.Lock()
	defer n.mu.Unlock()
	n.rxBytes = rx
	n.txBytes = tx
	n.ok = true
	if memBytes > 0 {
		n.memUsedMB = float64(memBytes) / (1024 * 1024)
		_, totalMB, _ := readMem()
		if totalMB > 0 {
			n.memPct = n.memUsedMB / totalMB * 100
			if n.memPct > 100 {
				n.memPct = 100
			}
		}
	}

	if !n.havePrev || n.prevAt.IsZero() {
		n.prevRx = rx
		n.prevTx = tx
		n.prevCPUNsec = cpuNsec
		n.prevAt = now
		n.havePrev = true
		n.havePrevCPU = cpuNsec > 0 || mainPID > 0
		if haveDisk {
			n.prevDiskR, n.prevDiskW = diskR, diskW
			n.prevDiskRI, n.prevDiskWI = diskRI, diskWI
			n.havePrevDisk = true
		}
		n.pushLocked(now.Unix())
		return
	}
	dt := now.Sub(n.prevAt).Seconds()
	if dt < 0.2 {
		return
	}
	var dRx, dTx float64
	if rx >= n.prevRx {
		dRx = float64(rx - n.prevRx)
	}
	if tx >= n.prevTx {
		dTx = float64(tx - n.prevTx)
	}
	n.prevRx = rx
	n.prevTx = tx
	n.rxBps = dRx / dt
	n.txBps = dTx / dt
	n.rxMbps = n.rxBps * 8 / 1_000_000
	n.txMbps = n.txBps * 8 / 1_000_000

	if cpuNsec > 0 && n.havePrevCPU && cpuNsec >= n.prevCPUNsec {
		dCPU := float64(cpuNsec - n.prevCPUNsec)
		// Fraction of all host CPUs (0–100), comparable to host cpu_pct.
		n.cpuPct = dCPU / (dt * 1e9 * float64(ncpu)) * 100
		if n.cpuPct < 0 {
			n.cpuPct = 0
		}
		if n.cpuPct > 100 {
			n.cpuPct = 100
		}
	}
	n.prevCPUNsec = cpuNsec
	n.havePrevCPU = true
	if haveDisk {
		if n.havePrevDisk {
			n.diskReadIOPS, n.diskWriteIOPS, n.diskReadMBs, n.diskWriteMBs =
				nodeDiskRates(n.prevDiskR, n.prevDiskW, n.prevDiskRI, n.prevDiskWI, diskR, diskW, diskRI, diskWI, dt)
		}
		n.prevDiskR, n.prevDiskW = diskR, diskW
		n.prevDiskRI, n.prevDiskWI = diskRI, diskWI
		n.havePrevDisk = true
	}
	n.prevAt = now
	n.pushLocked(now.Unix())
}

func (n *nodeResourceTracker) pushLocked(t int64) {
	s := nodeResourceSample{
		T: t, NetRxMbps: n.rxMbps, NetTxMbps: n.txMbps,
		CPUPct: n.cpuPct, MemPct: n.memPct, MemUsedMB: n.memUsedMB,
		DiskReadIOPS: n.diskReadIOPS, DiskWriteIOPS: n.diskWriteIOPS,
	}
	n.samples[n.pos] = s
	n.pos = (n.pos + 1) % SampleCount
	if n.count < SampleCount {
		n.count++
	}
}

func (n *nodeResourceTracker) Snapshot() map[string]any {
	n.mu.Lock()
	defer n.mu.Unlock()
	if !n.ok {
		return nil
	}
	ordered := n.orderedLocked()
	netRx := make([]MetricPoint, 0, len(ordered))
	netTx := make([]MetricPoint, 0, len(ordered))
	cpu := make([]MetricPoint, 0, len(ordered))
	mem := make([]MetricPoint, 0, len(ordered))
	diskR := make([]MetricPoint, 0, len(ordered))
	diskW := make([]MetricPoint, 0, len(ordered))
	for _, s := range ordered {
		netRx = append(netRx, MetricPoint{T: s.T, V: round2(s.NetRxMbps)})
		netTx = append(netTx, MetricPoint{T: s.T, V: round2(s.NetTxMbps)})
		cpu = append(cpu, MetricPoint{T: s.T, V: round2(s.CPUPct)})
		mem = append(mem, MetricPoint{T: s.T, V: round2(s.MemPct)})
		diskR = append(diskR, MetricPoint{T: s.T, V: round1(s.DiskReadIOPS)})
		diskW = append(diskW, MetricPoint{T: s.T, V: round1(s.DiskWriteIOPS)})
	}
	return map[string]any{
		"node_net_rx_mbps":     round2(n.rxMbps),
		"node_net_tx_mbps":     round2(n.txMbps),
		"node_net_rx_bps":      round1(n.rxBps),
		"node_net_tx_bps":      round1(n.txBps),
		"node_net_rx_bytes":    n.rxBytes,
		"node_net_tx_bytes":    n.txBytes,
		"node_cpu_pct":         round2(n.cpuPct),
		"node_mem_pct":         round2(n.memPct),
		"node_mem_used_mb":     round1(n.memUsedMB),
		"node_disk_read_iops":  round1(n.diskReadIOPS),
		"node_disk_write_iops": round1(n.diskWriteIOPS),
		"node_disk_read_mb_s":  round2(n.diskReadMBs),
		"node_disk_write_mb_s": round2(n.diskWriteMBs),
		"history": map[string]any{
			"node_net_rx":         netRx,
			"node_net_tx":         netTx,
			"node_cpu":            cpu,
			"node_memory":         mem,
			"node_disk_read_iops": diskR,
			"node_disk_write_iops": diskW,
		},
	}
}

func (n *nodeResourceTracker) orderedLocked() []nodeResourceSample {
	if n.count == 0 {
		return nil
	}
	out := make([]nodeResourceSample, 0, n.count)
	start := 0
	if n.count == SampleCount {
		start = n.pos
	}
	for i := 0; i < n.count; i++ {
		out = append(out, n.samples[(start+i)%SampleCount])
	}
	return out
}

func mergeNodeNetIntoCurrent(cur map[string]any, snap map[string]any) {
	if cur == nil || snap == nil {
		return
	}
	for _, k := range []string{
		"node_net_rx_mbps", "node_net_tx_mbps",
		"node_net_rx_bps", "node_net_tx_bps",
		"node_net_rx_bytes", "node_net_tx_bytes",
		"node_cpu_pct", "node_mem_pct", "node_mem_used_mb",
		"node_disk_read_iops", "node_disk_write_iops",
		"node_disk_read_mb_s", "node_disk_write_mb_s",
	} {
		if v, ok := snap[k]; ok {
			cur[k] = v
		}
	}
}

func mergeNodeHistoryInto(hostHist map[string]any, snap map[string]any) {
	if hostHist == nil || snap == nil {
		return
	}
	nh, _ := snap["history"].(map[string]any)
	if nh == nil {
		return
	}
	for _, k := range []string{
		"node_net_rx", "node_net_tx", "node_cpu", "node_memory",
		"node_disk_read_iops", "node_disk_write_iops",
	} {
		if v, ok := nh[k]; ok {
			hostHist[k] = v
		}
	}
}

// readUnitAnonMemoryBytes — cgroup memory.stat `anon` (comparable to host Mem %).
// Falls back to /proc/<pid>/status VmRSS. ❌ Do not use MemoryCurrent (includes file cache).
func readUnitAnonMemoryBytes(unit string, mainPID int64) uint64 {
	cg := strings.TrimSpace(systemdControlGroup(unit))
	if cg != "" {
		if !strings.HasPrefix(cg, "/") {
			cg = "/" + cg
		}
		statPath := "/sys/fs/cgroup" + cg + "/memory.stat"
		if b, err := os.ReadFile(statPath); err == nil {
			if n, ok := parseCgroupMemoryStatAnon(string(b)); ok && n > 0 {
				return n
			}
		}
	}
	if mainPID > 0 {
		if n, ok := readProcRSSBytes(mainPID); ok && n > 0 {
			return n
		}
	}
	return 0
}

func readUnitIOStat(unit string) (rbytes, wbytes, rios, wios uint64, ok bool) {
	cg := strings.TrimSpace(systemdControlGroup(unit))
	if cg == "" {
		return 0, 0, 0, 0, false
	}
	if !strings.HasPrefix(cg, "/") {
		cg = "/" + cg
	}
	b, err := os.ReadFile("/sys/fs/cgroup" + cg + "/io.stat")
	if err != nil || len(b) == 0 {
		return 0, 0, 0, 0, false
	}
	rbytes, wbytes, rios, wios = parseCgroupIOStat(string(b))
	return rbytes, wbytes, rios, wios, true
}

func systemdControlGroup(unit string) string {
	out, err := exec.Command("systemctl", "show", unit, "-p", "ControlGroup", "--value").CombinedOutput()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

func parseCgroupMemoryStatAnon(s string) (uint64, bool) {
	for _, line := range strings.Split(s, "\n") {
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		if fields[0] == "anon" {
			n, err := strconv.ParseUint(fields[1], 10, 64)
			return n, err == nil
		}
	}
	return 0, false
}

func readProcRSSBytes(pid int64) (uint64, bool) {
	b, err := os.ReadFile(fmt.Sprintf("/proc/%d/status", pid))
	if err != nil {
		return 0, false
	}
	for _, line := range strings.Split(string(b), "\n") {
		if !strings.HasPrefix(line, "VmRSS:") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			return 0, false
		}
		kb, err := strconv.ParseUint(fields[1], 10, 64)
		if err != nil {
			return 0, false
		}
		return kb * 1024, true
	}
	return 0, false
}

func readSystemdUnitResources(unit string) (rx, tx, cpuNsec, memBytes uint64, mainPID int64, ok bool) {
	out, err := exec.Command("systemctl", "show", unit,
		"-p", "IPIngressBytes", "-p", "IPEgressBytes",
		"-p", "CPUUsageNSec", "-p", "MemoryCurrent",
		"-p", "MainPID").CombinedOutput()
	if err != nil {
		return 0, 0, 0, 0, 0, false
	}
	return parseSystemdUnitResources(string(out))
}

func parseSystemdUnitResources(s string) (rx, tx, cpuNsec, memBytes uint64, mainPID int64, ok bool) {
	var haveRx, haveTx bool
	for _, line := range strings.Split(s, "\n") {
		line = strings.TrimSpace(line)
		k, v, cut := strings.Cut(line, "=")
		if !cut {
			continue
		}
		v = strings.TrimSpace(v)
		if v == "" || strings.EqualFold(v, "[no data]") || strings.EqualFold(v, "[not set]") {
			continue
		}
		switch k {
		case "IPIngressBytes":
			if n, err := strconv.ParseUint(v, 10, 64); err == nil {
				rx = n
				haveRx = true
			}
		case "IPEgressBytes":
			if n, err := strconv.ParseUint(v, 10, 64); err == nil {
				tx = n
				haveTx = true
			}
		case "CPUUsageNSec":
			if n, err := strconv.ParseUint(v, 10, 64); err == nil {
				cpuNsec = n
			}
		case "MemoryCurrent":
			if n, err := strconv.ParseUint(v, 10, 64); err == nil {
				memBytes = n
			}
		case "MainPID":
			if n, err := strconv.ParseInt(v, 10, 64); err == nil {
				mainPID = n
			}
		}
	}
	if mainPID <= 0 && !haveRx && !haveTx && cpuNsec == 0 && memBytes == 0 {
		return 0, 0, 0, 0, 0, false
	}
	// Prefer any signal: net accounting, cpu, or memory.
	if !haveRx && !haveTx && cpuNsec == 0 && memBytes == 0 {
		return 0, 0, 0, 0, mainPID, false
	}
	return rx, tx, cpuNsec, memBytes, mainPID, true
}

// parseSystemdIPAccounting kept for tests (net-only subset).
func parseSystemdIPAccounting(s string) (rx, tx uint64, ok bool) {
	rx, tx, _, _, mainPID, ok := parseSystemdUnitResources(s)
	if !ok {
		return 0, 0, false
	}
	if mainPID <= 0 && rx == 0 && tx == 0 {
		return 0, 0, false
	}
	return rx, tx, true
}

// ensureLocalNodeIPAccounting — leaf boot heal: drop-in + set-property without
// restarting the chain unit (Update may have swapped binaries before tip ensure ran).
func ensureLocalNodeIPAccounting(unit string) {
	unit = strings.TrimSpace(unit)
	if unit == "" {
		return
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	unitPath := filepath.Join("/etc/systemd/system", unit)
	if _, err := os.Stat(unitPath); err != nil {
		return
	}
	dropDir := filepath.Join("/etc/systemd/system", unit+".d")
	_ = os.MkdirAll(dropDir, 0o755)
	body := `[Service]
# RpcNode: per-node NIC / CPU / Memory / IO (leaf ensure).
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
IOAccounting=yes
`
	_ = os.WriteFile(filepath.Join(dropDir, "ip-accounting.conf"), []byte(body), 0o644)
	_ = exec.Command("systemctl", "daemon-reload").Run()
	_ = exec.Command("systemctl", "set-property", unit,
		"IPAccounting=yes", "CPUAccounting=yes", "MemoryAccounting=yes", "IOAccounting=yes").Run()
	log.Printf("node resource accounting enabled on %s", unit)
}
