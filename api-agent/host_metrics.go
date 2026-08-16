package main

import (
	"os"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Host metrics for /api/metrics.json — collected in api-agent so Network charts
// work even when leaf system-agent is still an older binary (in-memory) after tip update.

const hostSampleCount = 360

type hostMetricPoint struct {
	T int64   `json:"t"`
	V float64 `json:"v"`
}

type hostSample struct {
	T         int64
	Load1     float64
	CPUPct    float64
	MemPct    float64
	NetRxMbps float64
	NetTxMbps float64
	NetRxBps  float64
	NetTxBps  float64
}

type hostMetricsHistory struct {
	mu          sync.Mutex
	samples     []hostSample
	pos         int
	count       int
	prevNetRx   uint64
	prevNetTx   uint64
	prevNetAt   time.Time
	havePrevNet bool
	prevIdle    uint64
	prevTotal   uint64
	havePrevCPU bool
	lastPush    time.Time
}

func newHostMetricsHistory() *hostMetricsHistory {
	return &hostMetricsHistory{samples: make([]hostSample, hostSampleCount)}
}

func (h *hostMetricsHistory) Start(interval time.Duration) {
	if interval < time.Second {
		interval = 3 * time.Second
	}
	h.Push()
	go func() {
		t := time.NewTicker(interval)
		defer t.Stop()
		for range t.C {
			h.Push()
		}
	}()
}

func (h *hostMetricsHistory) Push() {
	s := h.collect()
	h.mu.Lock()
	defer h.mu.Unlock()
	h.samples[h.pos] = s
	h.pos = (h.pos + 1) % hostSampleCount
	if h.count < hostSampleCount {
		h.count++
	}
	h.lastPush = time.Now()
}

func (h *hostMetricsHistory) Snapshot() (current map[string]any, history map[string]any) {
	h.mu.Lock()
	defer h.mu.Unlock()
	ordered := h.orderedLocked()
	load := make([]hostMetricPoint, 0, len(ordered))
	cpu := make([]hostMetricPoint, 0, len(ordered))
	mem := make([]hostMetricPoint, 0, len(ordered))
	netRx := make([]hostMetricPoint, 0, len(ordered))
	netTx := make([]hostMetricPoint, 0, len(ordered))
	var cur hostSample
	if len(ordered) > 0 {
		cur = ordered[len(ordered)-1]
	}
	for _, s := range ordered {
		load = append(load, hostMetricPoint{T: s.T, V: round2(s.Load1)})
		cpu = append(cpu, hostMetricPoint{T: s.T, V: round2(s.CPUPct)})
		mem = append(mem, hostMetricPoint{T: s.T, V: round2(s.MemPct)})
		netRx = append(netRx, hostMetricPoint{T: s.T, V: round2(s.NetRxMbps)})
		netTx = append(netTx, hostMetricPoint{T: s.T, V: round2(s.NetTxMbps)})
	}
	current = map[string]any{
		"load_1":      round2(cur.Load1),
		"cpu_pct":     round2(cur.CPUPct),
		"mem_pct":     round2(cur.MemPct),
		"net_rx_mbps": round2(cur.NetRxMbps),
		"net_tx_mbps": round2(cur.NetTxMbps),
		"net_rx_bps":  round1(cur.NetRxBps),
		"net_tx_bps":  round1(cur.NetTxBps),
	}
	history = map[string]any{
		"load":   load,
		"cpu":    cpu,
		"memory": mem,
		"net_rx": netRx,
		"net_tx": netTx,
	}
	return current, history
}

func (h *hostMetricsHistory) orderedLocked() []hostSample {
	if h.count == 0 {
		return nil
	}
	out := make([]hostSample, 0, h.count)
	start := 0
	if h.count == hostSampleCount {
		start = h.pos
	}
	for i := 0; i < h.count; i++ {
		out = append(out, h.samples[(start+i)%hostSampleCount])
	}
	return out
}

func (h *hostMetricsHistory) collect() hostSample {
	now := time.Now().Unix()
	load1, _ := readLoadAvgHost()
	_, _, memPct := readMemHost()
	busy := h.readCPUBusyPct()
	rxBps, txBps, rxMbps, txMbps := h.readNetRates()
	return hostSample{
		T: now, Load1: load1, CPUPct: busy, MemPct: memPct,
		NetRxMbps: rxMbps, NetTxMbps: txMbps,
		NetRxBps: rxBps, NetTxBps: txBps,
	}
}

func (h *hostMetricsHistory) readNetRates() (rxBps, txBps, rxMbps, txMbps float64) {
	rx, tx, ok := readNetDevTotalsHost()
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

func (h *hostMetricsHistory) readCPUBusyPct() float64 {
	b, err := readProcPathHost("stat")
	if err != nil {
		return 0
	}
	var line string
	for _, l := range strings.Split(string(b), "\n") {
		if strings.HasPrefix(l, "cpu ") {
			line = l
			break
		}
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
	idle := vals[3]
	h.mu.Lock()
	defer h.mu.Unlock()
	if !h.havePrevCPU {
		h.prevIdle = idle
		h.prevTotal = total
		h.havePrevCPU = true
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

func readProcPathHost(name string) ([]byte, error) {
	for _, root := range []string{"/host/proc", "/proc"} {
		b, err := os.ReadFile(root + "/" + name)
		if err == nil {
			return b, nil
		}
	}
	return nil, os.ErrNotExist
}

func readLoadAvgHost() (float64, float64) {
	b, err := readProcPathHost("loadavg")
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

func readMemHost() (usedMB, totalMB, pct float64) {
	b, err := readProcPathHost("meminfo")
	if err != nil {
		return 0, 0, 0
	}
	var total, avail uint64
	for _, line := range strings.Split(string(b), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		v, _ := strconv.ParseUint(fields[1], 10, 64)
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
	pct = float64(used) / float64(total) * 100
	return usedMB, totalMB, pct
}

// readNetDevTotalsHost — sum RX/TX bytes; prefer physical NICs, fallback to all except lo.
func readNetDevTotalsHost() (rx, tx uint64, ok bool) {
	b, err := readProcPathHost("net/dev")
	if err != nil {
		return 0, 0, false
	}
	rx, tx, ok = sumNetDev(b, true)
	if ok {
		return rx, tx, true
	}
	return sumNetDev(b, false)
}

func sumNetDev(b []byte, skipVirtual bool) (rx, tx uint64, ok bool) {
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
			if skipNetIfaceHost(iface) {
				continue
			}
		} else if strings.EqualFold(iface, "lo") || iface == "" {
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

func skipNetIfaceHost(name string) bool {
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

func round1(v float64) float64 {
	return float64(int(v*10+0.5)) / 10
}

func histLen(v any) int {
	switch t := v.(type) {
	case []any:
		return len(t)
	case []hostMetricPoint:
		return len(t)
	default:
		return 0
	}
}

func firstMetrics(vals ...any) any {
	for _, v := range vals {
		if v != nil {
			return v
		}
	}
	return nil
}
