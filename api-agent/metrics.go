package main

import (
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

// Metrics tracks gateway proxy traffic (excludes /status* and /health).
type Metrics struct {
	inFlight atomic.Int64

	total       atomic.Int64
	err502      atomic.Int64
	err503      atomic.Int64
	err4xx      atomic.Int64
	err5xx      atomic.Int64
	upstreamErr atomic.Int64
	maintHits   atomic.Int64

	mu       sync.Mutex
	latUs    []int64 // ring of recent latencies (µs)
	latPos   int
	latCount int

	secMu     sync.Mutex
	secBucket [300]int64 // last 300 unix-second slots

	histMu   sync.Mutex
	rpsHist  []metricPoint
	rpsPos   int
	rpsCount int
	stopHist chan struct{}
}

type metricPoint struct {
	T int64   `json:"t"`
	V float64 `json:"v"`
}

const latencyRingSize = 8192
const rpsHistSize = 360

func NewMetrics() *Metrics {
	m := &Metrics{
		latUs:    make([]int64, latencyRingSize),
		rpsHist:  make([]metricPoint, rpsHistSize),
		stopHist: make(chan struct{}),
	}
	go m.sampleLoop()
	return m
}

func (m *Metrics) sampleLoop() {
	t := time.NewTicker(2 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-m.stopHist:
			return
		case <-t.C:
			m.pushRPSSample()
		}
	}
}

func (m *Metrics) pushRPSSample() {
	now := time.Now().Unix()
	v := m.rpsWindow(5)
	m.histMu.Lock()
	m.rpsHist[m.rpsPos] = metricPoint{T: now, V: round2(v)}
	m.rpsPos = (m.rpsPos + 1) % rpsHistSize
	if m.rpsCount < rpsHistSize {
		m.rpsCount++
	}
	m.histMu.Unlock()
}

func (m *Metrics) RPSHistory() []metricPoint {
	m.histMu.Lock()
	defer m.histMu.Unlock()
	if m.rpsCount == 0 {
		return nil
	}
	out := make([]metricPoint, 0, m.rpsCount)
	start := 0
	if m.rpsCount == rpsHistSize {
		start = m.rpsPos
	}
	for i := 0; i < m.rpsCount; i++ {
		out = append(out, m.rpsHist[(start+i)%rpsHistSize])
	}
	return out
}

func (m *Metrics) Begin() { m.inFlight.Add(1) }
func (m *Metrics) End()   { m.inFlight.Add(-1) }

func (m *Metrics) Observe(status int, latency time.Duration, upstreamFail bool) {
	m.total.Add(1)
	m.bumpSecond()

	us := latency.Microseconds()
	if us < 0 {
		us = 0
	}

	m.mu.Lock()
	m.latUs[m.latPos] = us
	m.latPos = (m.latPos + 1) % latencyRingSize
	if m.latCount < latencyRingSize {
		m.latCount++
	}
	m.mu.Unlock()

	if upstreamFail {
		m.upstreamErr.Add(1)
	}
	switch {
	case status == 502:
		m.err502.Add(1)
		m.err5xx.Add(1)
	case status == 503:
		m.err503.Add(1)
		m.err5xx.Add(1)
	case status >= 500:
		m.err5xx.Add(1)
	case status >= 400:
		m.err4xx.Add(1)
	}
}

func (m *Metrics) ObserveMaintenance() {
	m.maintHits.Add(1)
	m.Observe(503, 0, false)
}

func (m *Metrics) bumpSecond() {
	now := time.Now().Unix()
	m.secMu.Lock()
	m.secBucket[int(now%300)]++
	m.secMu.Unlock()
}

func (m *Metrics) rpsWindow(seconds int) float64 {
	now := time.Now().Unix()
	m.secMu.Lock()
	defer m.secMu.Unlock()

	var sum int64
	for i := 0; i < seconds; i++ {
		sum += m.secBucket[int((now-int64(i))%300)]
	}
	if seconds <= 0 {
		return 0
	}
	return float64(sum) / float64(seconds)
}

func (m *Metrics) percentile(p float64) float64 {
	m.mu.Lock()
	n := m.latCount
	if n == 0 {
		m.mu.Unlock()
		return 0
	}
	cp := make([]int64, n)
	if n < latencyRingSize {
		copy(cp, m.latUs[:n])
	} else {
		copy(cp, m.latUs)
	}
	m.mu.Unlock()

	sort.Slice(cp, func(i, j int) bool { return cp[i] < cp[j] })
	idx := int(float64(n-1) * p)
	if idx < 0 {
		idx = 0
	}
	if idx >= n {
		idx = n - 1
	}
	return float64(cp[idx]) / 1000.0 // ms
}

func (m *Metrics) Snapshot() map[string]any {
	return map[string]any{
		"rps_1m":           round2(m.rpsWindow(60)),
		"rps_5m":           round2(m.rpsWindow(300)),
		"in_flight":        m.inFlight.Load(),
		"total":            m.total.Load(),
		"latency_p50_ms":   round2(m.percentile(0.50)),
		"latency_p95_ms":   round2(m.percentile(0.95)),
		"errors_4xx":       m.err4xx.Load(),
		"errors_5xx":       m.err5xx.Load(),
		"http_502":         m.err502.Load(),
		"http_503":         m.err503.Load(),
		"upstream_errors":  m.upstreamErr.Load(),
		"maintenance_hits": m.maintHits.Load(),
	}
}

func round2(v float64) float64 {
	return float64(int(v*100+0.5)) / 100
}
