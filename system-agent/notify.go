package main

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	eventRingSize     = 200
	diskWarnPct       = 90.0
	webhookTimeout    = 8 * time.Second
	defaultWebhookCfg = "webhooks.json"
	defaultEventsFile = "events.jsonl"
)

// Event — developer notification payload (webhook + polling feed).
type Event struct {
	ID         string         `json:"id"`
	TS         string         `json:"ts"`
	Type       string         `json:"type"`
	Severity   string         `json:"severity"` // info|warning|critical
	Env        string         `json:"env"`
	InstanceID string         `json:"instance_id"`
	Message    string         `json:"message"`
	Data       map[string]any `json:"data,omitempty"`
}

type Notifier struct {
	cfg      Config
	mu       sync.Mutex
	events   []Event
	prev     map[string]string // edge keys → last value
	webhooks []string
	client   *http.Client
}

func newNotifier(cfg Config) *Notifier {
	n := &Notifier{
		cfg:    cfg,
		events: make([]Event, 0, eventRingSize),
		prev:   map[string]string{},
		client: &http.Client{Timeout: webhookTimeout},
	}
	n.webhooks = n.loadWebhookURLs()
	n.loadEventsFromDisk()
	return n
}

func (n *Notifier) webhooksPath() string {
	if v := os.Getenv("TRON_WEBHOOKS_FILE"); v != "" {
		return v
	}
	return filepath.Join(filepath.Dir(n.cfg.StateFile), defaultWebhookCfg)
}

func (n *Notifier) eventsPath() string {
	if v := os.Getenv("TRON_EVENTS_FILE"); v != "" {
		return v
	}
	return filepath.Join(filepath.Dir(n.cfg.StateFile), defaultEventsFile)
}

func (n *Notifier) loadWebhookURLs() []string {
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
	for _, u := range strings.Split(os.Getenv("TRON_WEBHOOK_URLS"), ",") {
		add(u)
	}
	st := readJSONFile(n.webhooksPath())
	if arr, ok := st["urls"].([]any); ok {
		for _, v := range arr {
			if s, ok := v.(string); ok {
				add(s)
			}
		}
	}
	return out
}

func (n *Notifier) saveWebhookURLs(urls []string) error {
	path := n.webhooksPath()
	_ = ensureDir(filepath.Dir(path))
	clean := make([]string, 0, len(urls))
	seen := map[string]bool{}
	for _, u := range urls {
		u = strings.TrimSpace(u)
		if u == "" || seen[u] {
			continue
		}
		seen[u] = true
		clean = append(clean, u)
	}
	b, err := json.MarshalIndent(map[string]any{
		"urls":       clean,
		"updated_at": time.Now().UTC().Format(time.RFC3339),
	}, "", "  ")
	if err != nil {
		return err
	}
	b = append(b, '\n')
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0644); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		return err
	}
	n.mu.Lock()
	n.webhooks = clean
	n.mu.Unlock()
	return nil
}

func (n *Notifier) Webhooks() []string {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.webhooks = n.loadWebhookURLs()
	out := make([]string, len(n.webhooks))
	copy(out, n.webhooks)
	return out
}

func (n *Notifier) SetWebhooks(urls []string) error {
	return n.saveWebhookURLs(urls)
}

func (n *Notifier) loadEventsFromDisk() {
	b, err := os.ReadFile(n.eventsPath())
	if err != nil {
		return
	}
	lines := strings.Split(string(b), "\n")
	var evs []Event
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		var e Event
		if json.Unmarshal([]byte(line), &e) == nil && e.ID != "" {
			evs = append(evs, e)
		}
	}
	if len(evs) > eventRingSize {
		evs = evs[len(evs)-eventRingSize:]
	}
	n.events = evs
}

func newEventID() string {
	var b [8]byte
	_, _ = rand.Read(b[:])
	return "evt_" + hex.EncodeToString(b[:])
}

func (n *Notifier) emit(typ, severity, message string, data map[string]any) {
	if data == nil {
		data = map[string]any{}
	}
	netName := n.cfg.Network
	if netName == "" {
		netName = "tron"
	}
	e := Event{
		ID:         newEventID(),
		TS:         time.Now().UTC().Format(time.RFC3339),
		Type:       typ,
		Severity:   severity,
		Env:        n.cfg.Env,
		InstanceID: fmt.Sprintf("%s-%s", netName, n.cfg.Env),
		Message:    message,
		Data:       data,
	}
	n.mu.Lock()
	n.events = append(n.events, e)
	if len(n.events) > eventRingSize {
		n.events = n.events[len(n.events)-eventRingSize:]
	}
	urls := append([]string{}, n.webhooks...)
	n.mu.Unlock()

	n.appendEventDisk(e)
	for _, u := range urls {
		go n.postWebhook(u, e)
	}
}

func (n *Notifier) appendEventDisk(e Event) {
	path := n.eventsPath()
	_ = ensureDir(filepath.Dir(path))
	b, err := json.Marshal(e)
	if err != nil {
		return
	}
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err != nil {
		return
	}
	defer f.Close()
	_, _ = f.Write(append(b, '\n'))
}

func (n *Notifier) postWebhook(url string, e Event) {
	body, err := json.Marshal(e)
	if err != nil {
		return
	}
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "tron-toolkit-system-agent/notify")
	req.Header.Set("X-RpcNode-Event", e.Type)
	resp, err := n.client.Do(req)
	if err != nil {
		log.Printf("webhook %s: %v", url, err)
		return
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	if resp.StatusCode >= 300 {
		log.Printf("webhook %s → HTTP %d", url, resp.StatusCode)
	}
}

func (n *Notifier) Recent(limit int) []Event {
	if limit <= 0 || limit > eventRingSize {
		limit = 50
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if len(n.events) == 0 {
		return []Event{}
	}
	start := 0
	if len(n.events) > limit {
		start = len(n.events) - limit
	}
	out := make([]Event, len(n.events)-start)
	copy(out, n.events[start:])
	// newest first
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return out
}

// ObserveState derives notification edges from a collected agent-state map.
func (n *Notifier) ObserveState(st map[string]any) {
	health, _ := st["health"].(string)
	network, _ := st["network"].(string)
	if network == "" {
		if inst, ok := st["instance"].(map[string]any); ok {
			network, _ = inst["network"].(string)
		}
	}
	isBitcoin := strings.EqualFold(network, "bitcoin")
	isEthereum := strings.EqualFold(network, "ethereum")

	checks, _ := st["checks"].(map[string]any)
	rpc := st["rpc"]
	var height any
	rpcOK := false
	ibd := false
	syncing := false
	if rm, ok := rpc.(map[string]any); ok {
		height = rm["node_height"]
		if height == nil {
			height = rm["blocks"]
		}
		if height == nil {
			height = rm["height"]
		}
		rpcOK = truthy(rm["ok"]) || truthy(rm["reachable"]) || truthy(rm["http_ok"])
		ibd = truthy(rm["initialblockdownload"])
		syncing = truthy(rm["syncing"]) || ibd
	}
	if sync, ok := st["sync"].(map[string]any); ok && truthy(sync["syncing"]) {
		syncing = true
	}

	nodeProcOK := truthy(checks["java_tron_process"]) || truthy(checks["bitcoind_process"]) ||
		truthy(checks["agave_process"]) || truthy(checks["geth_process"]) ||
		truthy(checks["lighthouse_process"]) || truthy(checks["node_process_up"])
	nodeDown := health == "error" || (!rpcOK && (health == "degraded" || health == "setup"))
	if !nodeProcOK || height == nil {
		// During bitcoin/ethereum sync height may be 0 but RPC ok — not "down".
		if !((isBitcoin || isEthereum) && rpcOK) {
			nodeDown = true
		}
	}
	if (isBitcoin && rpcOK && ibd) || (isEthereum && rpcOK && syncing) {
		nodeDown = false // syncing is expected, not critical down
	}

	if nodeDown {
		msg := "node / RPC not healthy"
		if isBitcoin {
			msg = "bitcoind / RPC not healthy"
		} else if isEthereum {
			msg = "geth / RPC not healthy"
		} else {
			msg = "java-tron / RPC not healthy"
		}
		n.edge("node", "down", "node.down", "critical", msg, map[string]any{
			"health": health, "height": height, "network": network,
		})
	} else {
		n.edge("node", "up", "node.up", "info", "node RPC healthy again", map[string]any{
			"health": health, "height": height, "network": network,
		})
	}

	// Bitcoin IBD progress — edge on block buckets so /api/v1/events stays useful without spam.
	// Regtest: never emit IBD progress (local chain, not network sync).
	envName, _ := st["env"].(string)
	if envName == "" {
		if inst, ok := st["instance"].(map[string]any); ok {
			envName, _ = inst["env"].(string)
		}
	}
	if isBitcoin && rpcOK && !isBitcoinRegtest(envName) {
		if rm, ok := rpc.(map[string]any); ok {
			blocks := int64(asFloat(rm["blocks"]))
			headers := int64(asFloat(rm["headers"]))
			verify := asFloat(rm["verificationprogress"])
			if ibd {
				bucket := fmt.Sprintf("%d", blocks/500)
				n.edge("ibd", bucket, "ibd.progress", "info",
					fmt.Sprintf("IBD · blocks %d / headers %d · %.1f%%", blocks, headers, verify*100),
					map[string]any{
						"blocks": blocks, "headers": headers,
						"verificationprogress": verify, "peers": rm["peers"],
					})
			} else {
				n.edge("ibd", "synced", "ibd.synced", "info",
					fmt.Sprintf("synced · height %d", blocks),
					map[string]any{"blocks": blocks})
			}
		}
	}

	disk, _ := st["disk"].(map[string]any)
	used := asFloat(disk["used_pct"])
	if used >= diskWarnPct {
		n.edge("disk", "low", "disk.low", "warning",
			fmt.Sprintf("disk used %.0f%% (threshold %.0f%%)", used, diskWarnPct),
			map[string]any{"used_pct": used, "free_gb": disk["free_gb"]})
	} else {
		n.edge("disk", "ok", "disk.ok", "info", "disk usage back below threshold", map[string]any{"used_pct": used})
	}

	maint, _ := st["maintenance"].(map[string]any)
	if truthy(maint["enabled"]) {
		n.edge("maint", "on", "maintenance.on", "warning",
			fmt.Sprint(maint["reason"]), map[string]any{"phase": maint["phase"]})
	} else {
		n.edge("maint", "off", "maintenance.off", "info", "maintenance cleared", nil)
	}

	snap, _ := st["snapshot"].(map[string]any)
	phase, _ := snap["phase"].(string)
	detail, _ := snap["detail"].(string)
	if strings.Contains(strings.ToLower(phase+" "+detail), "fail") || phase == "error" {
		n.edge("snapshot", "failed", "snapshot.failed", "critical",
			"snapshot failed: "+detail, map[string]any{"phase": phase, "detail": detail})
	} else if truthy(snap["wget_running"]) {
		n.edge("snapshot", "running", "snapshot.running", "info", "snapshot download running", map[string]any{
			"pct": snap["pct"], "eta": snap["eta"],
		})
	} else {
		n.edge("snapshot", "idle", "", "", "", nil) // track only
	}

	tu, _ := st["toolkit_update"].(map[string]any)
	if truthy(tu["update_available"]) {
		n.edge("toolkit_upd", fmt.Sprint(tu["remote_version"]),
			"toolkit.update_available", "warning",
			fmt.Sprintf("toolkit update available: %v → %v", tu["local_version"], tu["remote_version"]),
			map[string]any{
				"local_version":  tu["local_version"],
				"remote_version": tu["remote_version"],
			})
	} else {
		n.edge("toolkit_upd", "current", "", "", "", nil)
	}

	upd, _ := st["updater"].(map[string]any)
	if truthy(upd["update_available"]) || truthy(upd["available"]) {
		n.edge("jar_upd", fmt.Sprint(upd["remote_version"], upd["latest"]),
			"node.update_available", "warning",
			"java-tron jar update available", map[string]any{
				"local":  upd["local_version"],
				"remote": firstNonEmpty(upd["remote_version"], upd["latest"]),
			})
	} else {
		n.edge("jar_upd", "current", "", "", "", nil)
	}
}

func firstNonEmpty(vals ...any) any {
	for _, v := range vals {
		if v == nil {
			continue
		}
		if s, ok := v.(string); ok && s == "" {
			continue
		}
		return v
	}
	return nil
}

func asFloat(v any) float64 {
	switch n := v.(type) {
	case float64:
		return n
	case int:
		return float64(n)
	case json.Number:
		f, _ := n.Float64()
		return f
	case string:
		var f float64
		fmt.Sscanf(n, "%f", &f)
		return f
	}
	return 0
}

func (n *Notifier) handleEvents(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	limit := asInt(r.URL.Query().Get("limit"), 50)
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":     true,
		"count":  len(n.Recent(limit)),
		"events": n.Recent(limit),
	})
}

func (n *Notifier) handleWebhooks(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "urls": n.Webhooks()})
	case http.MethodPut, http.MethodPost:
		var body struct {
			URLs []string `json:"urls"`
			URL  string   `json:"url"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid json"})
			return
		}
		urls := body.URLs
		if body.URL != "" {
			urls = append(urls, body.URL)
		}
		if r.Method == http.MethodPost && len(body.URLs) == 0 && body.URL != "" {
			urls = append(n.Webhooks(), body.URL)
		}
		if err := n.SetWebhooks(urls); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "urls": n.Webhooks()})
	default:
		http.Error(w, "GET|PUT|POST", http.StatusMethodNotAllowed)
	}
}

// handle edge with empty typ = silent state track only
func (n *Notifier) edge(key, value, typ, severity, message string, data map[string]any) {
	n.mu.Lock()
	prev, ok := n.prev[key]
	n.prev[key] = value
	n.mu.Unlock()
	if typ == "" {
		return
	}
	if ok && prev == value {
		return
	}
	if !ok {
		if value == "ok" || value == "false" || value == "up" || value == "off" || value == "idle" || value == "current" {
			return
		}
	}
	// recovery transitions
	if ok && (value == "up" || value == "ok" || value == "off" || value == "idle" || value == "current") {
		if prev == "down" || prev == "low" || prev == "on" || prev == "failed" || prev == "running" {
			n.emit(typ, severity, message, data)
		}
		return
	}
	n.emit(typ, severity, message, data)
}
