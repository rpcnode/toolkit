package main

import (
	"os"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

// SampleCount — ~18 min at 3s interval.
const SampleCount = 360

type MetricPoint struct {
	T int64   `json:"t"` // unix seconds
	V float64 `json:"v"`
}

type HostSample struct {
	T        int64   `json:"t"`
	Load1    float64 `json:"load_1"`
	Load5    float64 `json:"load_5"`
	NCPU     int     `json:"ncpu"`
	LoadPct  float64 `json:"load_pct"` // load1/ncpu*100 — run-queue pressure
	CPUPct   float64 `json:"cpu_pct"`  // /proc/stat busy ≈ mpstat 100-%idle (not load avg)
	CPUBusy  float64 `json:"cpu_busy"` // same as cpu_pct (raw busy; iowait counted busy)
	MemUsed  float64 `json:"mem_used_mb"`
	MemTotal float64 `json:"mem_total_mb"`
	MemPct   float64 `json:"mem_pct"`
	// Host NIC throughput (sum of physical/public ifaces; lo/veth/docker skipped).
	NetRxMbps float64 `json:"net_rx_mbps"`
	NetTxMbps float64 `json:"net_tx_mbps"`
	NetRxBps  float64 `json:"net_rx_bps"`
	NetTxBps  float64 `json:"net_tx_bps"`
	// Host disk I/O from /proc/diskstats (whole physical disks; max %util).
	DiskReadIOPS  float64 `json:"disk_read_iops"`
	DiskWriteIOPS float64 `json:"disk_write_iops"`
	DiskReadMBs   float64 `json:"disk_read_mb_s"`
	DiskWriteMBs  float64 `json:"disk_write_mb_s"`
	DiskUtilPct   float64 `json:"disk_util_pct"`
	DiskBusy      string  `json:"disk_busy,omitempty"`
	Disks         []diskDevRate `json:"disks,omitempty"`
}

type MetricsHistory struct {
	mu      sync.Mutex
	samples []HostSample
	pos     int
	count   int
	// CPU delta for /proc/stat
	prevIdle  uint64
	prevTotal uint64
	havePrev  bool
	// Network delta for /proc/net/dev
	prevNetRx   uint64
	prevNetTx   uint64
	prevNetAt   time.Time
	havePrevNet bool
	// Disk delta for /proc/diskstats
	prevDisk     []diskDevSnap
	prevDiskAt   time.Time
	havePrevDisk bool
}

func newMetricsHistory() *MetricsHistory {
	return &MetricsHistory{
		samples: make([]HostSample, SampleCount),
	}
}

func (h *MetricsHistory) Push(s HostSample) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if s.T == 0 {
		s.T = time.Now().Unix()
	}
	h.samples[h.pos] = s
	h.pos = (h.pos + 1) % SampleCount
	if h.count < SampleCount {
		h.count++
	}
}

func (h *MetricsHistory) Snapshot() map[string]any {
	h.mu.Lock()
	defer h.mu.Unlock()

	ordered := h.orderedLocked()
	load := make([]MetricPoint, 0, len(ordered))
	cpu := make([]MetricPoint, 0, len(ordered))
	mem := make([]MetricPoint, 0, len(ordered))
	netRx := make([]MetricPoint, 0, len(ordered))
	netTx := make([]MetricPoint, 0, len(ordered))
	diskR := make([]MetricPoint, 0, len(ordered))
	diskW := make([]MetricPoint, 0, len(ordered))
	diskUtil := make([]MetricPoint, 0, len(ordered))
	var cur HostSample
	if len(ordered) > 0 {
		cur = ordered[len(ordered)-1]
	}
	for _, s := range ordered {
		load = append(load, MetricPoint{T: s.T, V: round2(s.Load1)})
		cpu = append(cpu, MetricPoint{T: s.T, V: round2(s.CPUPct)})
		mem = append(mem, MetricPoint{T: s.T, V: round2(s.MemPct)})
		netRx = append(netRx, MetricPoint{T: s.T, V: round2(s.NetRxMbps)})
		netTx = append(netTx, MetricPoint{T: s.T, V: round2(s.NetTxMbps)})
		diskR = append(diskR, MetricPoint{T: s.T, V: round1(s.DiskReadIOPS)})
		diskW = append(diskW, MetricPoint{T: s.T, V: round1(s.DiskWriteIOPS)})
		diskUtil = append(diskUtil, MetricPoint{T: s.T, V: round2(s.DiskUtilPct)})
	}
	osName, arch, _ := liveUname()
	return map[string]any{
		"sample_count": len(ordered),
		"current": map[string]any{
			"load_1":       round2(cur.Load1),
			"load_5":       round2(cur.Load5),
			"ncpu":         cur.NCPU,
			"load_pct":     round2(cur.LoadPct),
			"cpu_pct":      round2(cur.CPUPct),
			"cpu_busy":     round2(cur.CPUBusy),
			"mem_used_mb":  round1(cur.MemUsed),
			"mem_total_mb": round1(cur.MemTotal),
			"mem_pct":      round2(cur.MemPct),
			"net_rx_mbps":      round2(cur.NetRxMbps),
			"net_tx_mbps":      round2(cur.NetTxMbps),
			"net_rx_bps":       round1(cur.NetRxBps),
			"net_tx_bps":       round1(cur.NetTxBps),
			"disk_read_iops":   round1(cur.DiskReadIOPS),
			"disk_write_iops":  round1(cur.DiskWriteIOPS),
			"disk_read_mb_s":   round2(cur.DiskReadMBs),
			"disk_write_mb_s":  round2(cur.DiskWriteMBs),
			"disk_util_pct":    round2(cur.DiskUtilPct),
			"disk_busy":        cur.DiskBusy,
			"disks":            diskRatesJSON(cur.Disks),
			"os":               osName,
			"arch":             arch,
		},
		"history": map[string]any{
			"load":            load,
			"cpu":             cpu,
			"memory":          mem,
			"net_rx":          netRx,
			"net_tx":          netTx,
			"disk_read_iops":  diskR,
			"disk_write_iops": diskW,
			"disk_util":       diskUtil,
			"disks":           buildDiskHistory(ordered),
		},
		"samples": ordered,
	}
}

func (h *MetricsHistory) orderedLocked() []HostSample {
	if h.count == 0 {
		return nil
	}
	out := make([]HostSample, 0, h.count)
	start := 0
	if h.count == SampleCount {
		start = h.pos
	}
	for i := 0; i < h.count; i++ {
		out = append(out, h.samples[(start+i)%SampleCount])
	}
	return out
}

func (h *MetricsHistory) Collect() HostSample {
	now := time.Now().Unix()
	load1, load5 := readLoadAvg()
	memUsed, memTotal, memPct := readMem()
	busy := h.readCPUBusyPct()
	rxBps, txBps, rxMbps, txMbps := h.readNetRates()
	disk := h.readDiskRates()
	ncpu := readNCPU()
	loadPct := 0.0
	if ncpu > 0 {
		loadPct = load1 / float64(ncpu) * 100
		if loadPct > 100 {
			loadPct = 100
		}
	}
	// CPU % must match host tools (mpstat 100-%idle). Run-queue pressure stays
	// on load_pct / load_1 — do not inflate cpu_pct with load/ncpu.
	return HostSample{
		T: now, Load1: load1, Load5: load5, NCPU: ncpu, LoadPct: loadPct,
		CPUPct: busy, CPUBusy: busy,
		MemUsed: memUsed, MemTotal: memTotal, MemPct: memPct,
		NetRxMbps: rxMbps, NetTxMbps: txMbps,
		NetRxBps: rxBps, NetTxBps: txBps,
		DiskReadIOPS: disk.ReadIOPS, DiskWriteIOPS: disk.WriteIOPS,
		DiskReadMBs: disk.ReadMBs, DiskWriteMBs: disk.WriteMBs,
		DiskUtilPct: disk.UtilPct, DiskBusy: disk.BusyName,
		Disks: disk.Devices,
	}
}

func buildDiskHistory(ordered []HostSample) []map[string]any {
	lists := make([][]diskDevRate, 0, len(ordered))
	for _, s := range ordered {
		lists = append(lists, s.Disks)
	}
	names := diskNameOrder(lists)
	out := make([]map[string]any, 0, len(names))
	for _, name := range names {
		r := make([]MetricPoint, 0, len(ordered))
		w := make([]MetricPoint, 0, len(ordered))
		u := make([]MetricPoint, 0, len(ordered))
		for _, s := range ordered {
			d := findDiskRate(s.Disks, name)
			r = append(r, MetricPoint{T: s.T, V: round1(d.ReadIOPS)})
			w = append(w, MetricPoint{T: s.T, V: round1(d.WriteIOPS)})
			u = append(u, MetricPoint{T: s.T, V: round2(d.UtilPct)})
		}
		out = append(out, map[string]any{
			"name":       name,
			"read_iops":  r,
			"write_iops": w,
			"util":       u,
		})
	}
	return out
}

func (h *MetricsHistory) readDiskRates() diskRates {
	devs, ok := readDiskstats()
	if !ok {
		return diskRates{}
	}
	now := time.Now()
	h.mu.Lock()
	defer h.mu.Unlock()
	if !h.havePrevDisk || h.prevDiskAt.IsZero() {
		h.prevDisk = devs
		h.prevDiskAt = now
		h.havePrevDisk = true
		rates := diskRates{Devices: diskDevicesPlaceholder(devs)}
		attachDiskSpace(rates.Devices)
		return rates
	}
	dt := now.Sub(h.prevDiskAt).Seconds()
	rates := diskRatesFromDelta(h.prevDisk, devs, dt)
	h.prevDisk = devs
	h.prevDiskAt = now
	attachDiskSpace(rates.Devices)
	return rates
}

func readNCPU() int {
	b, err := readProcPath("cpuinfo")
	if err == nil {
		n := 0
		for _, line := range strings.Split(string(b), "\n") {
			if strings.HasPrefix(line, "processor") {
				n++
			}
		}
		if n > 0 {
			return n
		}
	}
	if n := runtime.NumCPU(); n > 0 {
		return n
	}
	return 1
}

func readProcPath(name string) ([]byte, error) {
	for _, root := range []string{"/host/proc", "/proc"} {
		b, err := os.ReadFile(root + "/" + name)
		if err == nil {
			return b, nil
		}
	}
	return nil, os.ErrNotExist
}

func readLoadAvg() (float64, float64) {
	b, err := readProcPath("loadavg")
	if err != nil {
		n := float64(runtime.NumCPU())
		return n * 0.15, n * 0.2
	}
	parts := strings.Fields(string(b))
	if len(parts) < 2 {
		return 0, 0
	}
	l1, _ := strconv.ParseFloat(parts[0], 64)
	l5, _ := strconv.ParseFloat(parts[1], 64)
	return l1, l5
}

func readMem() (usedMB, totalMB, pct float64) {
	b, err := readProcPath("meminfo")
	if err != nil {
		return 0, 0, 0
	}
	var total, avail uint64
	for _, line := range strings.Split(string(b), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		v, _ := strconv.ParseUint(fields[1], 10, 64) // kB
		switch fields[0] {
		case "MemTotal:":
			total = v
		case "MemAvailable:":
			avail = v
		}
	}
	if total == 0 {
		return 0, 0, 0
	}
	used := total - avail
	totalMB = float64(total) / 1024
	usedMB = float64(used) / 1024
	pct = float64(used) * 100 / float64(total)
	return usedMB, totalMB, pct
}

func (h *MetricsHistory) readCPUBusyPct() float64 {
	b, err := readProcPath("stat")
	if err != nil {
		return 0
	}
	line := strings.SplitN(string(b), "\n", 2)[0]
	if !strings.HasPrefix(line, "cpu ") {
		return 0
	}
	fields := strings.Fields(line)
	if len(fields) < 5 {
		return 0
	}
	var vals []uint64
	for _, f := range fields[1:] {
		v, _ := strconv.ParseUint(f, 10, 64)
		vals = append(vals, v)
	}
	var total uint64
	for _, v := range vals {
		total += v
	}
	// Idle only — iowait counts as busy (disk-bound validators look "loaded").
	idle := vals[3]
	h.mu.Lock()
	defer h.mu.Unlock()
	if !h.havePrev {
		h.prevIdle = idle
		h.prevTotal = total
		h.havePrev = true
		return 0
	}
	dIdle := idle - h.prevIdle
	dTotal := total - h.prevTotal
	h.prevIdle = idle
	h.prevTotal = total
	if dTotal == 0 {
		return 0
	}
	busy := 100 * (1 - float64(dIdle)/float64(dTotal))
	if busy < 0 {
		busy = 0
	}
	if busy > 100 {
		busy = 100
	}
	return busy
}

func (h *MetricsHistory) readNetRates() (rxBps, txBps, rxMbps, txMbps float64) {
	rx, tx, ok := readNetDevTotals()
	if !ok {
		return 0, 0, 0, 0
	}
	now := time.Now()
	h.mu.Lock()
	defer h.mu.Unlock()
	if !h.havePrevNet || h.prevNetAt.IsZero() {
		h.prevNetRx = rx
		h.prevNetTx = tx
		h.prevNetAt = now
		h.havePrevNet = true
		return 0, 0, 0, 0
	}
	dt := now.Sub(h.prevNetAt).Seconds()
	if dt < 0.2 {
		return 0, 0, 0, 0
	}
	var dRx, dTx float64
	if rx >= h.prevNetRx {
		dRx = float64(rx - h.prevNetRx)
	}
	if tx >= h.prevNetTx {
		dTx = float64(tx - h.prevNetTx)
	}
	h.prevNetRx = rx
	h.prevNetTx = tx
	h.prevNetAt = now
	rxBps = dRx / dt
	txBps = dTx / dt
	rxMbps = rxBps * 8 / 1_000_000
	txMbps = txBps * 8 / 1_000_000
	return rxBps, txBps, rxMbps, txMbps
}

// readNetDevTotals — sum RX/TX bytes across host NICs (skip lo / veth / docker bridges).
// If every iface is filtered, fall back to all except lo (exotic NIC names).
func readNetDevTotals() (rx, tx uint64, ok bool) {
	b, err := readProcPath("net/dev")
	if err != nil {
		return 0, 0, false
	}
	rx, tx, ok = sumNetDevBytes(b, true)
	if ok {
		return rx, tx, true
	}
	return sumNetDevBytes(b, false)
}

func sumNetDevBytes(b []byte, skipVirtual bool) (rx, tx uint64, ok bool) {
	for _, line := range strings.Split(string(b), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || !strings.Contains(line, ":") {
			continue
		}
		parts := strings.SplitN(line, ":", 2)
		if len(parts) != 2 {
			continue
		}
		iface := strings.TrimSpace(parts[0])
		if skipVirtual {
			if skipNetIface(iface) {
				continue
			}
		} else if iface == "" || strings.EqualFold(iface, "lo") {
			continue
		}
		fields := strings.Fields(parts[1])
		if len(fields) < 9 {
			continue
		}
		r, errR := strconv.ParseUint(fields[0], 10, 64)
		t, errT := strconv.ParseUint(fields[8], 10, 64)
		if errR != nil || errT != nil {
			continue
		}
		rx += r
		tx += t
		ok = true
	}
	return rx, tx, ok
}

func skipNetIface(name string) bool {
	n := strings.ToLower(strings.TrimSpace(name))
	if n == "" || n == "lo" {
		return true
	}
	for _, p := range []string{
		"veth", "docker", "br-", "virbr", "cni", "flannel", "cali",
		"tun", "tap", "wg", "dummy", "nodelocaldns",
	} {
		if strings.HasPrefix(n, p) {
			return true
		}
	}
	return false
}

func round2(v float64) float64 {
	return float64(int(v*100+0.5)) / 100
}
