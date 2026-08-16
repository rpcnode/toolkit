package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

// TRON catch-up: local getnowblock vs public tip. % = lag-closed vs peak
// behind (Solana lesson). ❌ Not local/tip — 188k behind still reads ~99.7%.
const tronSyncedBehindMax int64 = 40 // ~2 min at 3s/block

var tronBlockNumRe = regexp.MustCompile(`(?i)Num:(\d+)`)

type tronNodeInfoResult struct {
	OK       bool
	Peers    int64
	BlockNum int64
}

func parseTronBlockNumber(data map[string]any) int64 {
	raw := tronBlockRaw(data)
	if raw == nil {
		return 0
	}
	return tronJSONInt64(raw["number"])
}

func parseTronBlockTimestamp(data map[string]any) time.Time {
	raw := tronBlockRaw(data)
	if raw == nil {
		return time.Time{}
	}
	ms := tronJSONInt64(raw["timestamp"])
	if ms <= 0 {
		return time.Time{}
	}
	return time.UnixMilli(ms).UTC()
}

func tronBlockRaw(data map[string]any) map[string]any {
	if data == nil {
		return nil
	}
	bh, _ := data["block_header"].(map[string]any)
	if bh == nil {
		return nil
	}
	raw, _ := bh["raw_data"].(map[string]any)
	return raw
}

// tronBlockTimeStale — genesis/IBD headers are years old. Tip==local (proxy
// loop) must not paint Synced while java-tron is still on 2018 blocks.
func tronBlockTimeStale(ts time.Time) bool {
	if ts.IsZero() {
		return false
	}
	return time.Since(ts) > 3*time.Minute
}

func tronJSONInt64(v any) int64 {
	switch n := v.(type) {
	case float64:
		return int64(n)
	case json.Number:
		i, _ := n.Int64()
		return i
	case int64:
		return n
	case int:
		return int64(n)
	case string:
		n = strings.TrimSpace(n)
		if n == "" {
			return 0
		}
		var i int64
		if _, err := fmt.Sscan(n, &i); err == nil {
			return i
		}
	}
	return 0
}

func parseTronNodeInfo(data map[string]any) tronNodeInfoResult {
	out := tronNodeInfoResult{Peers: -1}
	if data == nil {
		return out
	}
	out.OK = true
	if n := tronJSONInt64(data["currentConnectCount"]); n >= 0 && data["currentConnectCount"] != nil {
		out.Peers = n
	} else {
		active := tronJSONInt64(data["activeConnectCount"])
		passive := tronJSONInt64(data["passiveConnectCount"])
		if data["activeConnectCount"] != nil || data["passiveConnectCount"] != nil {
			out.Peers = active + passive
		}
	}
	if s, ok := data["block"].(string); ok {
		if m := tronBlockNumRe.FindStringSubmatch(s); len(m) == 2 {
			out.BlockNum = tronJSONInt64(m[1])
		}
	}
	return out
}

func tronNodeInfo(host string, port int) tronNodeInfoResult {
	if host == "" || port <= 0 {
		return tronNodeInfoResult{Peers: -1}
	}
	client := &http.Client{Timeout: 1200 * time.Millisecond}
	resp, err := client.Get(fmt.Sprintf("http://%s:%d/wallet/getnodeinfo", host, port))
	if err != nil {
		return tronNodeInfoResult{Peers: -1}
	}
	defer resp.Body.Close()
	var data map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&data); err != nil {
		return tronNodeInfoResult{Peers: -1}
	}
	return parseTronNodeInfo(data)
}

func tronPublicTipURLs(cfg Config) []string {
	seen := map[string]bool{}
	var out []string
	add := func(u string) {
		u = strings.TrimSpace(u)
		if u == "" || seen[u] {
			return
		}
		seen[u] = true
		out = append(out, u)
	}
	add(envOr("TRON_PUBLIC_TIP_URL", ""))
	if b, err := os.ReadFile(filepath.Join(cfg.EtcDir, "public_tip.url")); err == nil {
		add(string(b))
	}
	switch strings.ToLower(strings.TrimSpace(cfg.Env)) {
	case "nile":
		add("https://nile.trongrid.io/wallet/getnowblock")
	case "shasta":
		add("https://api.shasta.trongrid.io/wallet/getnowblock")
	default:
		add("https://api.trongrid.io/wallet/getnowblock")
	}
	return out
}

type tronTipCacheEntry struct {
	tip int64
	at  time.Time
}

var (
	tronTipCacheMu sync.Mutex
	tronTipCache   = map[string]tronTipCacheEntry{}
)

func tronPublicTip(cfg Config) int64 {
	key := strings.ToLower(cfg.Network + "/" + cfg.Env)
	tronTipCacheMu.Lock()
	if e, ok := tronTipCache[key]; ok && time.Since(e.at) < 20*time.Second && e.tip > 0 {
		tip := e.tip
		tronTipCacheMu.Unlock()
		return tip
	}
	tronTipCacheMu.Unlock()

	client := &http.Client{Timeout: 1500 * time.Millisecond}
	var tip int64
	for _, u := range tronPublicTipURLs(cfg) {
		resp, err := client.Get(u)
		if err != nil {
			continue
		}
		var data map[string]any
		err = json.NewDecoder(resp.Body).Decode(&data)
		resp.Body.Close()
		if err != nil {
			continue
		}
		if n := parseTronBlockNumber(data); n > 0 {
			tip = n
			break
		}
	}
	if tip > 0 {
		tronTipCacheMu.Lock()
		tronTipCache[key] = tronTipCacheEntry{tip: tip, at: time.Now()}
		tronTipCacheMu.Unlock()
	}
	return tip
}

func tronLagClosedPct(cfg Config, behind int64) (float64, bool) {
	if behind < 0 {
		return 0, false
	}
	if behind <= tronSyncedBehindMax {
		return 100, true
	}
	maxBehind := loadTronCatchupMaxBehind(cfg)
	if behind > maxBehind {
		maxBehind = behind
		saveTronCatchupMaxBehind(cfg, maxBehind)
	}
	if maxBehind <= 0 {
		return 0, false
	}
	pct := float64(maxBehind-behind) / float64(maxBehind) * 100
	if pct > 99.9 {
		pct = 99.9
	}
	if pct < 0.1 {
		pct = 0.1
	}
	return float64(int(pct*10+0.5)) / 10, true
}

func tronCatchupStatePath(cfg Config) string {
	base := filepath.Dir(cfg.StateFile)
	if strings.TrimSpace(base) == "" || base == "." {
		base = filepath.Join("/var/lib/rpcnode", "tron-"+normalizeEnvName(cfg.Env))
	}
	return filepath.Join(base, "tron-catchup.json")
}

func loadTronCatchupMaxBehind(cfg Config) int64 {
	doc := readJSONFile(tronCatchupStatePath(cfg))
	switch v := doc["max_behind"].(type) {
	case float64:
		return int64(v)
	case json.Number:
		n, _ := v.Int64()
		return n
	default:
		return 0
	}
}

func saveTronCatchupMaxBehind(cfg Config, n int64) {
	path := tronCatchupStatePath(cfg)
	_ = os.MkdirAll(filepath.Dir(path), 0o755)
	b, _ := json.Marshal(map[string]any{"max_behind": n, "updated_at": time.Now().UTC().Format(time.RFC3339)})
	_ = os.WriteFile(path, b, 0o644)
}

func clearTronCatchupMaxBehind(cfg Config) {
	_ = os.Remove(tronCatchupStatePath(cfg))
}

var (
	tronDiskMu   sync.Mutex
	tronDiskAt   time.Time
	tronDiskN    int64
	tronDiskPath string
)

func tronDataSizeBytes(path string) int64 {
	path = strings.TrimSpace(path)
	if path == "" {
		return 0
	}
	tronDiskMu.Lock()
	defer tronDiskMu.Unlock()
	if path == tronDiskPath && time.Since(tronDiskAt) < 90*time.Second && tronDiskN > 0 {
		return tronDiskN
	}
	n := duBytes(path)
	if n > 0 {
		tronDiskN = n
		tronDiskAt = time.Now()
		tronDiskPath = path
	}
	return n
}
