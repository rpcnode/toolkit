package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math/rand"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/ali3/tron-toolkit/panel/store"
)

type collectorConfig struct {
	DBPath      string
	Workers     int
	Interval    time.Duration
	HTTPTimeout time.Duration
	JobTimeout  time.Duration
}

func runCollectorMain(_ []string) {
	// Defaults tuned for ~100 nodes: 32 parallel polls, 2s tick, 5s HTTP cap.
	// >100 nodes → natural backlog/delay (ok); raise WORKERS up to 64 if needed.
	cfg := collectorConfig{
		DBPath:      envOr("PANEL_DB", "/var/lib/rpcnode/panel.db"),
		Workers:     mustAtoi(envOr("PANEL_COLLECTOR_WORKERS", "32"), 32),
		Interval:    time.Duration(mustAtoi(envOr("PANEL_COLLECTOR_INTERVAL_MS", "2000"), 2000)) * time.Millisecond,
		HTTPTimeout: time.Duration(mustAtoi(envOr("PANEL_COLLECTOR_TIMEOUT_MS", "5000"), 5000)) * time.Millisecond,
		JobTimeout:  time.Duration(mustAtoi(envOr("PANEL_COLLECTOR_JOB_TIMEOUT_MS", "8000"), 8000)) * time.Millisecond,
	}
	if cfg.Workers < 8 {
		cfg.Workers = 8
	}
	if cfg.Workers > 64 {
		cfg.Workers = 64
	}
	if cfg.Interval < 500*time.Millisecond {
		cfg.Interval = 500 * time.Millisecond
	}
	if cfg.HTTPTimeout < time.Second {
		cfg.HTTPTimeout = time.Second
	}
	if cfg.JobTimeout < cfg.HTTPTimeout {
		cfg.JobTimeout = cfg.HTTPTimeout + time.Second
	}

	db, err := store.Open(cfg.DBPath)
	if err != nil {
		log.Fatalf("collector db: %v", err)
	}
	defer db.Close()
	_ = db.ImportLegacyJSON(envOr("PANEL_LEGACY_DIR", filepathDir(cfg.DBPath)))

	client := &http.Client{
		Timeout: cfg.HTTPTimeout,
		Transport: &http.Transport{
			Proxy: http.ProxyFromEnvironment,
			DialContext: (&net.Dialer{
				Timeout:   3 * time.Second,
				KeepAlive: 30 * time.Second,
			}).DialContext,
			MaxIdleConns:        256,
			MaxIdleConnsPerHost: 16,
			IdleConnTimeout:     90 * time.Second,
			DisableCompression:  true,
			ForceAttemptHTTP2:   false,
		},
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		sig := <-sigCh
		log.Printf("collector shutdown signal=%v", sig)
		cancel()
	}()

	log.Printf("panel-collector db=%s workers=%d interval=%s http_timeout=%s job_timeout=%s",
		cfg.DBPath, cfg.Workers, cfg.Interval, cfg.HTTPTimeout, cfg.JobTimeout)
	c := &collector{
		db: db, client: client,
		workers: cfg.Workers, interval: cfg.Interval, jobTimeout: cfg.JobTimeout,
		heartbeatPath: filepathDir(cfg.DBPath) + "/collector.heartbeat",
	}
	c.run(ctx)
}

func filepathDir(p string) string {
	i := strings.LastIndexAny(p, `/\`)
	if i <= 0 {
		return "."
	}
	return p[:i]
}

type collector struct {
	db            *store.DB
	client        *http.Client
	workers       int
	interval      time.Duration
	jobTimeout    time.Duration
	heartbeatPath string

	inFlight sync.Map // "node:<id>" | "server:<id>" → struct{}
	okN      atomicCounter
	failN    atomicCounter
	skipN    atomicCounter
}

func (c *collector) touchPulse() {
	now := time.Now().UTC().Format(time.RFC3339)
	_ = c.db.SetMeta(store.MetaLastTickAt, now)
	if strings.TrimSpace(c.heartbeatPath) == "" {
		return
	}
	_ = os.WriteFile(c.heartbeatPath, []byte(now+"\n"), 0o644)
}

type atomicCounter struct{ v int64 }

func (c *atomicCounter) Add(n int64) { atomic.AddInt64(&c.v, n) }
func (c *atomicCounter) Swap(n int64) int64 {
	return atomic.SwapInt64(&c.v, n)
}

type collectJob struct {
	kind string // server | node
	id   string
}

func (c *collector) flightKey(job collectJob) string { return job.kind + ":" + job.id }

func (c *collector) run(ctx context.Context) {
	// Queue sized for ~100 nodes + servers; backlog beyond workers is OK (delays).
	queueSize := c.workers * 8
	if queueSize < 128 {
		queueSize = 128
	}
	if queueSize > 512 {
		queueSize = 512
	}
	jobs := make(chan collectJob, queueSize)
	var wg sync.WaitGroup
	for i := 0; i < c.workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-ctx.Done():
					return
				case job, ok := <-jobs:
					if !ok {
						return
					}
					c.handle(ctx, job)
				}
			}
		}()
	}

	ticker := time.NewTicker(c.interval)
	defer ticker.Stop()
	statsTicker := time.NewTicker(10 * time.Second)
	defer statsTicker.Stop()
	c.touchPulse()
	c.enqueue(ctx, jobs)

	for {
		select {
		case <-ctx.Done():
			close(jobs)
			wg.Wait()
			return
		case <-ticker.C:
			c.enqueue(ctx, jobs)
		case <-statsTicker.C:
			ok, fail, skip := c.okN.Swap(0), c.failN.Swap(0), c.skipN.Swap(0)
			inflight := 0
			c.inFlight.Range(func(_, _ any) bool { inflight++; return true })
			line := fmt.Sprintf("ok=%d fail=%d skip=%d inflight=%d q=%d/%d",
				ok, fail, skip, inflight, len(jobs), cap(jobs))
			log.Printf("collector tick %s", line)
			_ = c.db.SetMeta(store.MetaLastStats, line)
			c.touchPulse()
		}
	}
}

func (c *collector) enqueue(ctx context.Context, jobs chan<- collectJob) {
	c.touchPulse()
	forced, _ := c.db.ConsumeForceTick()

	servers, err := c.db.ListServers(false)
	if err != nil {
		log.Printf("collector list servers: %v", err)
		return
	}
	nodes, err := c.db.ListNodes()
	if err != nil {
		log.Printf("collector list nodes: %v", err)
		return
	}

	// Hot (syncing/starting/error) first, then everyone else — ALL nodes every tick.
	var hot, rest []store.Node
	for _, n := range nodes {
		st, ok, _ := c.db.GetNodeStatus(n.ID)
		phase := strings.ToLower(st.Phase)
		wl := strings.ToLower(n.Status)
		if !ok || phase == "installing" || phase == "starting" || phase == "syncing" || phase == "error" ||
			wl == "snapshot_running" || wl == "installing" || wl == "syncing" ||
			wl == "snapshot_error" || wl == "start_error" || wl == "agent_error" ||
			strings.TrimSpace(st.Error) != "" {
			hot = append(hot, n)
		} else {
			rest = append(rest, n)
		}
	}

	push := func(j collectJob) {
		key := c.flightKey(j)
		if _, loaded := c.inFlight.LoadOrStore(key, struct{}{}); loaded {
			c.skipN.Add(1)
			return // already mid-poll — avoid duplicate backlog
		}
		select {
		case <-ctx.Done():
			c.inFlight.Delete(key)
		case jobs <- j:
		default:
			// Queue full (>~100 backlog) — drop this id; next tick retries.
			c.inFlight.Delete(key)
			c.skipN.Add(1)
		}
	}

	for _, s := range servers {
		push(collectJob{kind: "server", id: s.ID})
	}
	for _, n := range hot {
		push(collectJob{kind: "node", id: n.ID})
	}
	for _, n := range rest {
		push(collectJob{kind: "node", id: n.ID})
	}
	if forced {
		log.Printf("collector force-tick enqueued nodes=%d servers=%d hot=%d", len(nodes), len(servers), len(hot))
	}
}

func (c *collector) handle(ctx context.Context, job collectJob) {
	key := c.flightKey(job)
	defer c.inFlight.Delete(key)

	// Tiny jitter to avoid stampedes on shared tip hosts.
	time.Sleep(time.Duration(rand.Intn(80)) * time.Millisecond)

	jobCtx, cancel := context.WithTimeout(ctx, c.jobTimeout)
	defer cancel()

	switch job.kind {
	case "server":
		c.pollServer(jobCtx, job.id)
	case "node":
		c.pollNode(jobCtx, job.id)
	}
}

func (c *collector) pollServer(ctx context.Context, id string) {
	srv, ok, err := c.db.GetServer(id)
	if err != nil || !ok || srv.AgentURL == "" || srv.AgentKey == "" {
		return
	}
	base := strings.TrimRight(srv.AgentURL, "/")

	// Agent version + OS/arch from /healthz (or /api/v1/agent) identity.
	ver, hzOS, hzArch := c.fetchAgentIdentity(ctx, base, srv.AgentKey)
	if ver != "" {
		_ = c.db.SetServerAgentVersion(srv.ID, ver)
		srv.AgentVersion = ver
	}
	if hzOS != "" || hzArch != "" {
		_ = c.db.SetServerPlatform(srv.ID, hzOS, hzArch)
	}

	body, err := c.getJSON(ctx, base+"/api/v1/metrics", srv.AgentKey)
	if err != nil {
		body, err = c.getJSON(ctx, base+"/api/metrics.json", srv.AgentKey)
	}
	if err != nil {
		return
	}
	m := parseHostMetrics(body)
	m.ServerID = srv.ID
	m.AgentURL = srv.AgentURL
	_, _ = c.db.UpsertServerMetrics(m)
	if m.OS != "" || m.Arch != "" {
		_ = c.db.SetServerPlatform(srv.ID, m.OS, m.Arch)
	}
	c.notifyObserveServer(srv, m)
}

func (c *collector) fetchAgentVersion(ctx context.Context, base, key string) string {
	ver, _, _ := c.fetchAgentIdentity(ctx, base, key)
	return ver
}

func (c *collector) fetchAgentIdentity(ctx context.Context, base, key string) (ver, osName, arch string) {
	for _, path := range []string{"/healthz", "/api/v1/agent", "/"} {
		raw, err := c.getJSON(ctx, base+path, key)
		if err != nil {
			continue
		}
		if ver == "" {
			ver = parseAgentVersionJSON(raw)
		}
		if osName == "" && arch == "" {
			osName, arch = parseAgentPlatformJSON(raw)
		}
		if ver != "" && (osName != "" || arch != "") {
			return ver, osName, arch
		}
	}
	return ver, osName, arch
}

func parseAgentVersionJSON(raw []byte) string {
	var doc map[string]any
	if err := json.Unmarshal(raw, &doc); err != nil {
		return ""
	}
	for _, k := range []string{"agent_version", "version", "local_version"} {
		if v, ok := doc[k].(string); ok {
			v = strings.TrimSpace(v)
			if v != "" && !strings.EqualFold(v, "unknown") {
				return v
			}
		}
	}
	return ""
}

func parseAgentPlatformJSON(raw []byte) (osName, arch string) {
	var doc map[string]any
	if err := json.Unmarshal(raw, &doc); err != nil {
		return "", ""
	}
	osName = strFieldMap(doc, "os")
	arch = strFieldMap(doc, "arch")
	if osName == "" && arch == "" {
		if pretty := strFieldMap(doc, "os_pretty"); pretty != "" {
			parts := strings.SplitN(pretty, "/", 2)
			osName = strings.TrimSpace(parts[0])
			if len(parts) > 1 {
				arch = strings.TrimSpace(parts[1])
			}
		}
	}
	return osName, arch
}

// tryHealStuckPreProvision dials the dedicated leaf while SQLite still says
// awaiting_ports/ready_to_install. If leaf is live for this network, advance status.
func (c *collector) tryHealStuckPreProvision(ctx context.Context, node *store.Node) bool {
	if node == nil || node.AgentPort <= 0 {
		return false
	}
	st := strings.ToLower(strings.TrimSpace(node.Status))
	if st != "awaiting_ports" && st != "ready_to_install" {
		return false
	}
	srv, ok, _ := c.db.GetServer(node.ServerID)
	if !ok || strings.TrimSpace(srv.AgentKey) == "" {
		return false
	}
	leaf := agentControlBase(NodeRef(srv), WorkloadRef(*node))
	if leaf == "" {
		return false
	}
	wantNet := strings.ToLower(strings.TrimSpace(node.Network))
	env := strings.TrimSpace(node.Env)
	if env == "" {
		env = "mainnet"
	}
	statusURL := leaf + "/api/status.json?env=" + url.QueryEscape(env)
	if wantNet != "" {
		statusURL += "&network=" + url.QueryEscape(wantNet)
	}
	raw, err := c.getJSON(ctx, statusURL, srv.AgentKey)
	if err != nil {
		return false
	}
	if hz, hzErr := c.getJSON(ctx, leaf+"/healthz", srv.AgentKey); hzErr == nil {
		raw = mergeAgentIdentityFromHealthz(raw, hz)
	}
	doc := decodeStatusDoc(raw)
	if !leafStatusLooksLive(doc, wantNet) {
		return false
	}
	next := healWorkloadStatusFromLeafDoc(doc, node.Status)
	if next == "" || strings.EqualFold(next, node.Status) {
		return false
	}
	node.Status = next
	if _, err := c.db.UpsertNode(*node); err != nil {
		log.Printf("collector heal preprovision %s: %v", node.ID, err)
		return false
	}
	log.Printf("collector healed stuck %s → %s (leaf live)", node.ID, next)
	return true
}

func (c *collector) pollNode(ctx context.Context, id string) {
	node, ok, err := c.db.GetNode(id)
	if err != nil || !ok {
		c.failN.Add(1)
		return
	}
	wlRef := WorkloadRef(node)
	// Add node only — before Confirm ports. Do not dial; leaf does not exist.
	// Exception: stuck awaiting_ports/ready_to_install with catalog ports while leaf
	// is already live — probe leaf and heal SQLite so IBD/ops UI unlocks.
	if workloadPreProvision(wlRef) {
		if healed := c.tryHealStuckPreProvision(ctx, &node); healed {
			wlRef = WorkloadRef(node)
		} else {
			st := strings.ToLower(strings.TrimSpace(node.Status))
			if st == "" || st == "agent_error" || st == "error" {
				node.Status = "awaiting_ports"
				if _, err := c.db.UpsertNode(node); err != nil {
					log.Printf("collector awaiting_ports upsert %s: %v", id, err)
				}
			}
			c.okN.Add(1)
			return
		}
	}
	prevNode := node
	prevStatus, _, _ := c.db.GetNodeStatus(id)
	srv, sok, _ := c.db.GetServer(node.ServerID)
	key := ""
	if sok {
		key = srv.AgentKey
	}
	// Prefer dedicated per-node agent_port (leaf), not host tip Server agent.
	// Until leaf should be up, prefer tip (install/start ACK path).
	base := ""
	if sok {
		tip := strings.TrimRight(strings.TrimSpace(srv.AgentURL), "/")
		leaf := agentControlBase(NodeRef(srv), wlRef)
		if workloadLeafShouldBeUp(wlRef) && leaf != "" {
			base = leaf
		} else if tip != "" {
			base = tip
		} else {
			base = leaf
		}
	}
	if base == "" {
		base = strings.TrimRight(node.AgentURL, "/")
	}
	if base == "" && sok {
		base = strings.TrimRight(srv.AgentURL, "/")
	}
	if base == "" || key == "" {
		// Tip/key missing mid-setup — not the same as leaf down after ACK.
		if !workloadLeafShouldBeUp(wlRef) {
			c.okN.Add(1)
			return
		}
		c.markUnreachablePreserve(id, node, "no_agent")
		c.failN.Add(1)
		if n2, ok2, _ := c.db.GetNode(id); ok2 {
			st2, _, _ := c.db.GetNodeStatus(id)
			c.notifyObserveNode(prevNode, prevStatus, n2, st2, true)
		}
		return
	}
	statusURL := base + "/api/status.json?env=" + url.QueryEscape(node.Env)
	if netName := strings.TrimSpace(node.Network); netName != "" {
		statusURL += "&network=" + url.QueryEscape(netName)
	}
	raw, err := c.getJSON(ctx, statusURL, key)
	if err != nil {
		// Mid-setup / leaf not listening yet — do not flip to agent_error.
		if !workloadLeafShouldBeUp(wlRef) || workloadLeafDialSoftFail(wlRef) {
			c.okN.Add(1)
			return
		}
		// Do NOT clear lifecycle/raw_json/height — UI must show last ACK'd state.
		c.markUnreachablePreserve(id, node, err.Error())
		c.failN.Add(1)
		if n2, ok2, _ := c.db.GetNode(id); ok2 {
			st2, _, _ := c.db.GetNodeStatus(id)
			c.notifyObserveNode(prevNode, prevStatus, n2, st2, true)
		}
		return
	}
	// Agent /healthz is source of truth for network identity. status.json often
	// omits top-level network / supported_networks and leaves stale instance.network=tron
	// in SQLite → permanent Wrong agent. Overlay healthz before sanitize + cache write.
	if hz, hzErr := c.getJSON(ctx, base+"/healthz", key); hzErr == nil {
		raw = mergeAgentIdentityFromHealthz(raw, hz)
	}
	// Leaf Go proxy metrics live on api-agent process — pull metrics.json when
	// status.json omitted them (tip hostView / older agents). Needed for Fullnode Go RPC panel.
	raw = enrichStatusWithProxyMetrics(ctx, c, base, key, raw)
	if strings.TrimSpace(node.Network) != "" {
		raw = sanitizeStatusForWorkload(raw, WorkloadRef(node), base)
	}
	// sanitize may complete stuck Run; advance SQLite syncing→online when tip is honest.
	if healedDoc := decodeStatusDoc(raw); leafHonestlySynced(healedDoc) {
		if next := healWorkloadStatusFromLeafDoc(healedDoc, node.Status); next != "" &&
			!strings.EqualFold(next, node.Status) {
			node.Status = next
			if _, err := c.db.UpsertNode(node); err != nil {
				log.Printf("collector heal stuck-run status %s: %v", id, err)
			} else {
				log.Printf("collector healed stuck run %s → %s (leaf synced)", id, next)
			}
		}
	}
	st := summarizeStatus(id, node.Status, raw)
	st.Error = "" // successful poll clears unreachable annotation
	// Don't wipe cached rpc_proxy when this poll had no metrics sample.
	if strings.TrimSpace(st.RPCProxy) == "" && strings.TrimSpace(prevStatus.RPCProxy) != "" {
		st.RPCProxy = prevStatus.RPCProxy
	}
	var doc map[string]any
	_ = json.Unmarshal(raw, &doc)
	if truthyAny(doc["needs_provision"]) {
		// Prefer lifecycle from agent (via sanitize). SQLite ports_confirmed must not invent install.
		phase := "ports"
		if lc, ok := doc["lifecycle"].(map[string]any); ok {
			if cur, _ := lc["current"].(string); cur != "" {
				phase = cur
			} else if p, _ := lc["phase"].(string); p != "" {
				phase = p
			}
			if d, _ := lc["detail"].(string); d != "" {
				st.Detail = d
			}
		}
		st.Phase = phase
		st.Label = "Setup"
		if st.Detail == "" {
			st.Detail = strFieldMap(doc, "message", "note")
		}
		st.Error = ""
		st.Health = "setup"
		st.SnapshotPct = nil
		// Never regress a provisioned leaf row back to awaiting_ports — that sticks
		// the collector on tip host_tip and hides IBD verification_pct forever.
		if node.AgentPort <= 0 && statusSyncable(node.Status) &&
			node.Status != "awaiting_ports" &&
			node.Status != "ports_confirmed" && node.Status != "ready_to_install" {
			node.Status = "awaiting_ports"
			if _, err := c.db.UpsertNode(node); err != nil {
				log.Printf("collector upsert node %s: %v", id, err)
			}
		}
	} else if truthyAny(doc["network_mismatch"]) || strings.EqualFold(strFieldMap(doc, "health"), "mismatch") {
		st.Phase = "error"
		st.Label = "Wrong agent"
		st.Detail = strFieldMap(doc, "message", "note")
		if st.Detail == "" {
			st.Detail = networkMismatchMessage(node.Network, agentReportedNetwork(doc))
		}
		st.Error = "network_mismatch"
		st.Health = "mismatch"
		st.SnapshotPct = nil
	} else {
		// Live identity matches (or Setup) — never keep stale network_mismatch in SQLite.
		if st.Error == "network_mismatch" || strings.EqualFold(st.Health, "mismatch") ||
			strings.EqualFold(node.Status, "network_mismatch") {
			st.Error = ""
			if strings.EqualFold(st.Health, "mismatch") {
				st.Health = strFieldMap(doc, "health")
			}
		}
	}
	if err := c.db.UpsertNodeStatus(st); err != nil {
		log.Printf("collector upsert status %s: %v", id, err)
		c.failN.Add(1)
		return
	}
	local, latest, updAvail := extractClientUpdate(doc)
	if local != "" || latest != "" || updAvail {
		if err := c.db.SetNodeClientUpdateInfo(id, local, latest, updAvail); err != nil {
			log.Printf("collector set client_update %s: %v", id, err)
		}
		node.ClientVersion = local
		node.ClientLatest = latest
		node.ClientUpdateAvailable = updAvail
	} else if cv := extractClientVersion(doc); cv != "" {
		if err := c.db.SetNodeClientVersion(id, cv); err != nil {
			log.Printf("collector set client_version %s: %v", id, err)
		}
		node.ClientVersion = cv
	}
	c.okN.Add(1)

	// Keep node.status in sync for coarse filters.
	if st.Phase != "" && statusSyncable(node.Status) {
		mapped := mapPhaseToWorkloadStatus(st.Phase, st.Label, node.Status)
		// Explicitly clear stale Wrong agent / agent_error once live poll succeeds.
		if !truthyAny(doc["network_mismatch"]) &&
			!strings.EqualFold(strFieldMap(doc, "health"), "mismatch") &&
			(node.Status == "network_mismatch" || node.Status == "agent_error") {
			if mapped == "" || mapped == "network_mismatch" {
				if truthyAny(doc["needs_provision"]) {
					mapped = "awaiting_ports"
				} else {
					mapped = mapPhaseToWorkloadStatus(st.Phase, st.Label, "")
				}
			}
		}
		if mapped != "" && mapped != node.Status {
			node.Status = mapped
			if _, err := c.db.UpsertNode(node); err != nil {
				log.Printf("collector upsert node %s: %v", id, err)
			}
		}
	}
	c.stampNodeLifecycleDates(prevNode, prevStatus, node, doc)
	c.notifyObserveNode(prevNode, prevStatus, node, st, false)
	if rp := store.ParseRPCProxyJSON(st.RPCProxy); rp != nil {
		c.notifyObserveRPC(node, *rp)
	}
}

func (c *collector) stampNodeLifecycleDates(prev store.Node, prevSt store.NodeStatus, node store.Node, doc map[string]any) {
	if shouldStampInstallStarted(prev.Status, node.Status, node.InstallStartedAt) {
		if err := c.db.StampNodeInstallStarted(node.ID); err != nil {
			log.Printf("collector stamp install_started_at %s: %v", node.ID, err)
		}
	}
	if shouldStampSynced(prev.Status, prevSt.RawJSON, leafHonestlySynced(doc), node.SyncedAt) {
		if err := c.db.StampNodeSynced(node.ID); err != nil {
			log.Printf("collector stamp synced_at %s: %v", node.ID, err)
		}
	} else if shouldClearSynced(leafHonestlySynced(doc), node.SyncedAt, doc) {
		if err := c.db.ClearNodeSynced(node.ID); err != nil {
			log.Printf("collector clear synced_at %s: %v", node.ID, err)
		}
	}
}

// markUnreachablePreserve annotates error without inventing STEP-1 Install or wiping cache.
// Only flips nodes.status → agent_error when there is no useful cached lifecycle phase.
func (c *collector) markUnreachablePreserve(id string, node store.Node, errMsg string) {
	// Async remove owns the row — do not flip to agent_error mid-wipe.
	if strings.EqualFold(node.Status, "removing") || strings.EqualFold(node.Status, "remove_error") {
		return
	}
	if err := c.db.MarkNodeUnreachable(id, errMsg); err != nil {
		log.Printf("collector mark unreachable %s: %v", id, err)
	}
	st, ok, _ := c.db.GetNodeStatus(id)
	phase := strings.ToLower(strings.TrimSpace(st.Phase))
	useful := ok && (phase == "syncing" || phase == "working" || phase == "starting" ||
		phase == "installing" || phase == "run" || phase == "healthy" || phase == "removing")
	if useful {
		return
	}
	if node.Status != "agent_error" {
		node.Status = "agent_error"
		if _, err := c.db.UpsertNode(node); err != nil {
			log.Printf("collector upsert node %s: %v", id, err)
		}
	}
}

func statusSyncable(status string) bool {
	switch strings.ToLower(strings.TrimSpace(status)) {
	case "removing", "remove_error":
		// Freeze — async remove owns the status until tip ACK deletes the row.
		return false
	case "", "awaiting_ports", "ports_confirmed", "ready_to_install", "snapshot_running",
		"syncing", "online", "starting", "snapshot_error", "start_error", "agent_error", "error":
		return true
	default:
		return true
	}
}

func mapPhaseToWorkloadStatus(phase, label, prev string) string {
	switch strings.ToLower(phase) {
	case "installing":
		return "snapshot_running"
	case "starting":
		return "starting"
	case "syncing":
		return "syncing"
	case "working":
		return "online"
	case "error":
		low := strings.ToLower(label + " " + prev)
		if strings.Contains(low, "mismatch") || strings.Contains(low, "wrong agent") {
			return "network_mismatch"
		}
		if strings.Contains(low, "start") {
			return "start_error"
		}
		if strings.Contains(low, "agent") {
			return "agent_error"
		}
		return "snapshot_error"
	case "setup", "ports":
		// Clear stale network_mismatch once sanitize decides Setup / needs_provision.
		if prev == "network_mismatch" || prev == "agent_error" || prev == "snapshot_error" ||
			prev == "start_error" || prev == "" {
			return "awaiting_ports"
		}
		return prev
	default:
		// Non-error phases must not leave forever network_mismatch in nodes.status.
		if prev == "network_mismatch" {
			return "awaiting_ports"
		}
		return ""
	}
}

func (c *collector) getJSON(ctx context.Context, url, key string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+key)
	req.Header.Set("X-Api-Token", key)
	resp, err := c.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode >= 300 {
		return nil, &httpError{code: resp.StatusCode, body: string(raw)}
	}
	return raw, nil
}

type httpError struct {
	code int
	body string
}

func (e *httpError) Error() string {
	msg := strings.TrimSpace(e.body)
	if len(msg) > 120 {
		msg = msg[:120] + "…"
	}
	if msg == "" {
		return "HTTP " + strconv.Itoa(e.code)
	}
	return "HTTP " + strconv.Itoa(e.code) + ": " + msg
}

func parseHostMetrics(raw []byte) store.ServerMetrics {
	var doc map[string]any
	_ = json.Unmarshal(raw, &doc)
	// metrics may be nested under host / current / metrics
	cur := doc
	for _, k := range []string{"host_metrics", "metrics", "current", "host"} {
		if m, ok := cur[k].(map[string]any); ok {
			cur = m
			if k == "host_metrics" {
				if c, ok := m["current"].(map[string]any); ok {
					cur = c
				}
			}
		}
	}
	cpu := floatFieldMap(cur, "cpu_pct", "cpu")
	loadPct := floatFieldMap(cur, "load_pct")
	ncpu := int(floatFieldMap(cur, "ncpu"))
	// cpu_pct stays /proc/stat busy; load_pct is separate (load/ncpu).
	m := store.ServerMetrics{
		CPUPct:      cpu,
		LoadPct:     loadPct,
		NCPU:        ncpu,
		MemPct:      floatFieldMap(cur, "mem_pct", "memory_pct"),
		MemUsedMB:   floatFieldMap(cur, "mem_used_mb", "memory_used_mb"),
		MemTotalMB:  floatFieldMap(cur, "mem_total_mb", "memory_total_mb"),
		DiskUsedPct: floatFieldMap(cur, "disk_used_pct", "disk_pct"),
		DiskUsedGB:  floatFieldMap(cur, "disk_used_gb"),
		DiskTotalGB: floatFieldMap(cur, "disk_total_gb"),
		Load1:       floatFieldMap(cur, "load_1", "load1"),
		OS:          strFieldMap(cur, "os"),
		Arch:        strFieldMap(cur, "arch"),
		HostID:      strFieldMap(cur, "host_id"),
	}
	// Top-level fallback (healthz-shaped or older agents).
	if m.OS == "" {
		m.OS = strFieldMap(doc, "os")
	}
	if m.Arch == "" {
		m.Arch = strFieldMap(doc, "arch")
	}
	return m
}

func summarizeStatus(nodeID, wlStatus string, raw []byte) store.NodeStatus {
	var doc map[string]any
	_ = json.Unmarshal(raw, &doc)
	rpc, _ := doc["rpc"].(map[string]any)
	snap, _ := doc["snapshot"].(map[string]any)
	maint, _ := doc["maintenance"].(map[string]any)
	pause, _ := doc["pause"].(map[string]any)
	lc, _ := doc["lifecycle"].(map[string]any)

	phase := "setup"
	label := "SETUP"
	detail := "Confirm ports / Install"
	busySnap := false
	if snap != nil {
		if truthyAny(snap["wget_running"]) {
			busySnap = true
		}
		ph := strings.ToLower(strFieldMap(snap, "phase"))
		if ph == "download" || ph == "extract" || ph == "extracting" {
			busySnap = true
		}
	}

	var height *int64
	if h := int64Field(rpc, "node_height"); h > 0 {
		height = &h
	}
	var snapPct *float64
	if lc != nil {
		if p := floatFieldMap(lc, "pct"); p > 0 {
			snapPct = &p
		}
	}
	if snapPct == nil && snap != nil {
		if p := floatFieldMap(snap, "progress_pct", "pct", "progress"); p > 0 {
			snapPct = &p
		}
	}
	// Sync/IBD % — verification_pct is always 0–100 (incl. Solana 0.1 lag-closed).
	// Only verificationprogress / verify_pct may be bitcoind 0–1 fractions.
	// TON dump phase may emit dump_pct before lag-closed verification_pct.
	if snapPct == nil {
		if sync, _ := doc["sync"].(map[string]any); sync != nil {
			if p := floatFieldMap(sync, "verification_pct"); p > 0 {
				snapPct = &p
			} else if p := floatFieldMap(sync, "dump_pct"); p > 0 {
				snapPct = &p
			} else if p := floatFieldMap(sync, "verificationprogress", "verify_pct"); p > 0 {
				if p <= 1.5 {
					p *= 100
				}
				snapPct = &p
			}
		}
	}
	if snapPct == nil && rpc != nil {
		if p := floatFieldMap(rpc, "verification_pct"); p > 0 {
			snapPct = &p
		} else if p := floatFieldMap(rpc, "verificationprogress", "verify_pct"); p > 0 {
			if p <= 1.5 {
				p *= 100
			}
			snapPct = &p
		}
	}

	errText := ""
	// Agent lifecycle is source of truth when present.
	if lc != nil {
		lp := strings.ToLower(strFieldMap(lc, "phase"))
		ns := strings.ToLower(strFieldMap(lc, "node_status"))
		label = strFieldMap(lc, "label")
		detail = strFieldMap(lc, "detail")
		// Prefer start-step journal detail when unit crash-loops (never swallow as "starting").
		if startDetail := lifecycleStartErrorDetail(lc); startDetail != "" {
			phase = "error"
			if label == "" || strings.EqualFold(label, "starting") {
				label = "Start error"
			}
			detail = startDetail
			errText = startDetail
		}
		lcComplete := truthyAny(lc["complete"])
		syncStill := false
		if sync, _ := doc["sync"].(map[string]any); sync != nil {
			syncStill = truthyAny(sync["ibd"]) || truthyAny(sync["syncing"])
		}
		if !syncStill && rpc != nil {
			syncStill = truthyAny(rpc["initialblockdownload"]) || truthyAny(rpc["syncing"])
		}
		if !syncStill && snapPct != nil && *snapPct > 0 && *snapPct < 100 &&
			(lp == "run" || ns == "syncing" || ns == "ibd") {
			syncStill = true
		}
		// Chain client update / node restart (Go proxy sleep) — before healthy/sync mapping.
		if cu, _ := doc["client_update"].(map[string]any); cu != nil {
			cup := strings.ToLower(strFieldMap(cu, "phase"))
			if cup == "updating" || cup == "starting" {
				phase = "updating"
				label = strFieldMap(cu, "detail")
				if label == "" {
					if cup == "starting" {
						label = "Starting after client update"
					} else {
						label = "Updating client"
					}
				}
				detail = strFieldMap(cu, "detail")
				if p := floatFieldMap(cu, "pct"); p > 0 {
					snapPct = &p
				}
			}
		}
		if errText == "" {
			snapFailed := truthyAny(snap["failed"]) ||
				strings.EqualFold(strFieldMap(snap, "phase"), "error")
			if snapFailed {
				snapDetail := strFieldMap(snap, "error", "detail", "message")
				if snapDetail == "" {
					snapDetail = detail
				}
				if snapDetail == "" {
					snapDetail = "Snapshot download failed"
				}
				phase = "error"
				label = "snapshot failed"
				detail = snapDetail
				errText = snapDetail
			}
		}
		if nr, _ := doc["node_restart"].(map[string]any); nr != nil && phase != "updating" {
			np := strings.ToLower(strFieldMap(nr, "phase"))
			if np == "restarting" || np == "starting" {
				phase = "restarting"
				label = strFieldMap(nr, "detail")
				if label == "" {
					if np == "starting" {
						label = "Starting after restart"
					} else {
						label = "Restarting node"
					}
				}
				detail = strFieldMap(nr, "detail")
				if p := floatFieldMap(nr, "pct"); p > 0 {
					snapPct = &p
				}
			}
		}

		switch {
		case phase == "updating" || phase == "restarting":
			// keep client_update / node_restart mapping
		case errText != "":
			// already mapped from start-step / journal
		case lp == "error" || ns == "snapshot_error" || ns == "start_error" || ns == "paused":
			phase = "error"
			if label == "" {
				label = "ERROR"
			}
			errText = detail
		// Healthy/complete MUST beat stale wget_running from another env on the host.
		// But never promote to working while sync/IBD is still in progress (HL Full sync).
		case (lp == "healthy" || ns == "running" || lcComplete) && !syncStill:
			phase = "working"
			// Ops-ready cards: Healthy — not "Running" / "Step 4 of 4: Running".
			if label == "" || strings.EqualFold(label, "Running") ||
				strings.HasPrefix(strings.ToLower(label), "step ") {
				label = "Healthy"
			}
		case lp == "start" || ns == "starting":
			phase = "starting"
			if label == "" {
				label = "STARTING"
			}
		case lp == "run" || ns == "syncing" || ns == "ibd" || syncStill:
			// Bitcoin IBD / HL Full sync share the syncing UI phase (no TRON snapshot).
			phase = "syncing"
			if label == "" {
				if ns == "ibd" {
					label = "IBD"
				} else {
					label = "CATCHING UP"
				}
			}
		case lp == "snapshot" || ns == "snapshot_download" || ns == "snapshot_extract" || busySnap:
			phase = "installing"
			if label == "" {
				label = "SNAPSHOT"
			}
		case lp == "install" || ns == "installing" || ns == "needs_snapshot":
			phase = "setup"
			if label == "" {
				label = "SETUP"
			}
		case lp == "ports" || ns == "awaiting_ports":
			phase = "setup"
			if label == "" {
				label = "PORTS"
			}
		}
		// Step headline for in-progress cards only — match detail resolveCurrentStep
		// (step title e.g. "Full sync"). Ops-ready cards keep Healthy/Running, not
		// "Step 4 of 4: Running".
		if phase != "working" && phase != "error" {
			if stepLabel := formatLifecycleStepLabel(lc, label); stepLabel != "" {
				label = stepLabel
			}
		}
	} else if truthyAny(maint["enabled"]) || truthyAny(pause["active"]) {
		phase, label, detail = "error", "paused", strFieldMap(pause, "message")
		if detail == "" {
			detail = strFieldMap(maint, "reason")
		}
		errText = detail
	} else if truthyAny(snap["failed"]) || strings.EqualFold(strFieldMap(snap, "phase"), "error") ||
		strings.EqualFold(wlStatus, "snapshot_error") {
		phase, label = "error", "snapshot failed"
		detail = strFieldMap(snap, "error", "detail", "message")
		if detail == "" {
			detail = "Snapshot download failed"
		}
		errText = detail
	} else if strings.EqualFold(wlStatus, "start_error") {
		phase, label = "error", "start failed"
		detail = strFieldMap(rpc, "error", "message")
		if detail == "" {
			if ag, _ := doc["agent"].(map[string]any); ag != nil {
				detail = strFieldMap(ag, "last_error")
			}
		}
		if detail == "" {
			detail = "Node start failed"
		}
		errText = detail
	} else if busySnap || strings.EqualFold(wlStatus, "snapshot_running") {
		phase, label = "installing", "INSTALLING"
		detail = "Snapshot in progress"
		if snapPct != nil {
			detail = "Snapshot " + strconv.FormatFloat(*snapPct, 'f', 0, 64) + "%"
		}
		if d := strFieldMap(snap, "detail"); d != "" {
			detail = d
		}
	} else if truthyAny(rpc["process_up"]) && !truthyAny(rpc["reachable"]) && !truthyAny(rpc["http_ok"]) {
		phase, label, detail = "starting", "STARTING", "Node starting"
	} else if height != nil && *height > 0 && !truthyAny(rpc["synced"]) {
		// catching up if not at tip — treat syncing when process up
		if truthyAny(rpc["process_up"]) || truthyAny(rpc["reachable"]) {
			phase, label, detail = "syncing", "CATCHING UP", "height "+strconv.FormatInt(*height, 10)
		}
	} else if truthyAny(rpc["reachable"]) || truthyAny(rpc["http_ok"]) || strings.EqualFold(strFieldMap(doc, "health"), "ok") {
		phase, label, detail = "working", "WORKING", "Node healthy"
		if height != nil {
			detail = "height " + strconv.FormatInt(*height, 10)
		}
	} else if strings.EqualFold(wlStatus, "ports_confirmed") || strings.EqualFold(wlStatus, "ready_to_install") {
		phase, label, detail = "install", "SETUP", "Ports confirmed — click Install"
	} else if strings.EqualFold(wlStatus, "awaiting_ports") {
		phase, label, detail = "setup", "SETUP", "Confirm ports in setup wizard, then Install"
	}

	// Cache a slim status.json (lifecycle/rpc/sync) — drop huge logs.lines.
	// Oversized blobs previously corrupted SQLite freelist/overflow pages.
	rawStr := slimStatusCacheJSON(raw)
	rpcProxyJSON := ""
	if rp := extractRPCProxyStats(doc); rp != nil {
		rpcProxyJSON = store.EncodeRPCProxyJSON(rp)
	}

	return store.NodeStatus{
		NodeID:      nodeID,
		Phase:       phase,
		Label:       label,
		Detail:      detail,
		Height:      height,
		SnapshotPct: snapPct,
		Health:      strFieldMap(doc, "health"),
		RawJSON:     rawStr,
		RPCProxy:    rpcProxyJSON,
		Error:       errText,
	}
}

// enrichStatusWithProxyMetrics merges leaf /api/metrics.json gateway snapshot into status
// when status.metrics is missing — so Fullnode Go RPC panel has data while node is up.
func enrichStatusWithProxyMetrics(ctx context.Context, c *collector, base, key string, raw []byte) []byte {
	var doc map[string]any
	if err := json.Unmarshal(raw, &doc); err != nil || doc == nil {
		return raw
	}
	haveRPC := false
	if m, _ := doc["metrics"].(map[string]any); m != nil {
		_, haveRPC = m["rps_1m"]
	}
	haveNodeNet := false
	if _, ok := doc["node_net"].(map[string]any); ok {
		haveNodeNet = true
	}
	if haveRPC && haveNodeNet {
		return raw
	}
	mraw, err := c.getJSON(ctx, base+"/api/metrics.json", key)
	if err != nil {
		mraw, err = c.getJSON(ctx, base+"/api/v1/metrics", key)
	}
	if err != nil {
		return raw
	}
	var mdoc map[string]any
	if json.Unmarshal(mraw, &mdoc) != nil {
		return raw
	}
	changed := false
	if !haveRPC {
		snap, _ := mdoc["gateway"].(map[string]any)
		if snap == nil {
			snap, _ = mdoc["current"].(map[string]any)
		}
		if snap != nil {
			metrics := map[string]any{}
			for _, k := range []string{
				"rps_1m", "rps_5m", "in_flight", "total",
				"latency_p50_ms", "latency_p95_ms",
				"errors_4xx", "errors_5xx", "http_502", "http_503",
				"upstream_errors", "maintenance_hits",
			} {
				if v, ok := snap[k]; ok {
					metrics[k] = v
				}
			}
			if len(metrics) > 0 {
				doc["metrics"] = metrics
				changed = true
			}
		}
	}
	if !haveNodeNet {
		if cur, _ := mdoc["current"].(map[string]any); cur != nil {
			nn := map[string]any{}
		for _, k := range []string{
			"node_net_rx_mbps", "node_net_tx_mbps",
			"node_net_rx_bps", "node_net_tx_bps",
			"node_net_rx_bytes", "node_net_tx_bytes",
			"node_cpu_pct", "node_mem_pct", "node_mem_used_mb",
		} {
			if v, ok := cur[k]; ok && v != nil {
				nn[k] = v
			}
		}
			if len(nn) > 0 {
				doc["node_net"] = nn
				changed = true
			}
		}
	}
	if !changed {
		return raw
	}
	out, err := json.Marshal(doc)
	if err != nil {
		return raw
	}
	return out
}

func extractRPCProxyStats(doc map[string]any) *store.RPCProxyStats {
	if doc == nil {
		return nil
	}
	m, _ := doc["metrics"].(map[string]any)
	if m == nil {
		return nil
	}
	// Always cache when metrics map is present (idle zeros → panel still shows).
	if _, has := m["rps_1m"]; !has {
		if _, has2 := m["latency_p95_ms"]; !has2 {
			if _, has3 := m["total"]; !has3 {
				return nil
			}
		}
	}
	st := &store.RPCProxyStats{
		RPS1m:          floatFieldMap(m, "rps_1m"),
		RPS5m:          floatFieldMap(m, "rps_5m"),
		InFlight:       int64(floatFieldMap(m, "in_flight")),
		Total:          int64(floatFieldMap(m, "total")),
		LatencyP50Ms:   floatFieldMap(m, "latency_p50_ms"),
		LatencyP95Ms:   floatFieldMap(m, "latency_p95_ms"),
		Errors4xx:      int64(floatFieldMap(m, "errors_4xx")),
		Errors5xx:      int64(floatFieldMap(m, "errors_5xx")),
		UpstreamErrors: int64(floatFieldMap(m, "upstream_errors")),
		HTTP502:        int64(floatFieldMap(m, "http_502")),
		HTTP503:        int64(floatFieldMap(m, "http_503")),
	}
	return st
}

// trimAnyStringTail keeps the last n string-ish entries from []any / []string JSON arrays.
func trimAnyStringTail(v any, n int) []string {
	if n <= 0 || v == nil {
		return nil
	}
	var out []string
	switch t := v.(type) {
	case []string:
		out = append(out, t...)
	case []any:
		for _, x := range t {
			s := strings.TrimSpace(fmt.Sprint(x))
			if s == "" || s == "<nil>" {
				continue
			}
			out = append(out, s)
		}
	default:
		return nil
	}
	if len(out) > n {
		out = out[len(out)-n:]
	}
	return out
}

// slimStatusCacheJSON keeps fields needed for unreachable UI fallback and trims
// log tails that balloon node_status.raw_json past hundreds of KiB.
// Keep a short tail so Logs modal still shows something when live agent is down
// (ltc/mainnet: system-agent start-limit-hit → empty modal despite litecoind running).
func slimStatusCacheJSON(raw []byte) string {
	var doc map[string]any
	if err := json.Unmarshal(raw, &doc); err != nil || doc == nil {
		s := string(raw)
		if len(s) > 256<<10 {
			return s[:256<<10]
		}
		return s
	}
	if logs, ok := doc["logs"].(map[string]any); ok {
		logs["lines"] = trimAnyStringTail(logs["lines"], 40)
		logs["truncated"] = true
		doc["logs"] = logs
	}
	if sync, ok := doc["sync"].(map[string]any); ok {
		if _, has := sync["log_tail"]; has {
			sync["log_tail"] = trimAnyStringTail(sync["log_tail"], 40)
			doc["sync"] = sync
		}
	}
	keep := map[string]any{}
	for _, k := range []string{
		"ok", "health", "network", "env", "version", "agent_version", "client_version", "client_update", "node_restart", "supported_networks",
		"lifecycle", "rpc", "sync", "snapshot", "connect", "needs_provision", "network_mismatch",
		"ui_phase", "message", "note", "instance", "capabilities", "logs", "agent", "metrics",
		// Per-node IPAccounting rates for /nodes cards (Host Net stays on live metrics.json).
		"node_net",
	} {
		if v, ok := doc[k]; ok {
			keep[k] = v
		}
	}
	out, err := json.Marshal(keep)
	if err != nil {
		s := string(raw)
		if len(s) > 256<<10 {
			return s[:256<<10]
		}
		return s
	}
	if len(out) > 256<<10 {
		return string(out[:256<<10])
	}
	return string(out)
}

// lifecycleStartErrorDetail returns start-step journal/detail when start failed.
// Prevents node cards from showing "STARTING / warming up" over exit-code crash-loops.
func lifecycleStartErrorDetail(lc map[string]any) string {
	if lc == nil {
		return ""
	}
	ns := strings.ToLower(strFieldMap(lc, "node_status"))
	lp := strings.ToLower(strFieldMap(lc, "phase"))
	if ns == "start_error" || (lp == "error" && strings.Contains(strings.ToLower(strFieldMap(lc, "label")), "start")) {
		if d := meaningfulStartErrorDetail(strFieldMap(lc, "detail")); d != "" {
			return d
		}
	}
	rawSteps, _ := lc["steps"].([]any)
	for _, raw := range rawSteps {
		m, _ := raw.(map[string]any)
		if m == nil {
			continue
		}
		if !strings.EqualFold(strFieldMap(m, "id"), "start") {
			continue
		}
		st := strings.ToLower(strFieldMap(m, "status"))
		if st == "error" || truthyAny(m["error"]) {
			if d := meaningfulStartErrorDetail(strFieldMap(m, "detail")); d != "" {
				return d
			}
			return "Node start failed"
		}
	}
	if prog, _ := lc["progress"].(map[string]any); prog != nil {
		if auto, _ := prog["auto"].(map[string]any); auto != nil {
			if d := meaningfulStartErrorDetail(strFieldMap(auto, "last_error")); d != "" {
				// Only surface as start error when lifecycle is on start / error.
				// ❌ ready_to_start + bare "unit=/path" was a false Start error after Confirm ports.
				if lp == "start" || lp == "error" || ns == "starting" || ns == "start_error" {
					low := strings.ToLower(d)
					if strings.Contains(low, "bitcoin") || strings.Contains(low, "bitcoind") ||
						strings.Contains(low, "conf") || strings.Contains(low, "systemctl") ||
						strings.Contains(low, "failed") || strings.Contains(low, "exit") ||
						strings.Contains(low, "missing") || strings.Contains(low, "stellar") ||
						strings.Contains(low, "error") {
						return d
					}
				}
			}
		}
	}
	return ""
}

// meaningfulStartErrorDetail drops FragmentPath-only noise ("unit=/etc/.../*.service").
func meaningfulStartErrorDetail(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	if strings.HasPrefix(s, "unit=/") && !strings.ContainsAny(strings.TrimPrefix(s, "unit="), " \t") {
		return ""
	}
	for _, sep := range []string{" — unit=/", " · unit=/", " - unit=/"} {
		if i := strings.Index(s, sep); i >= 0 {
			head := strings.TrimSpace(s[:i])
			if head == "" {
				return ""
			}
			s = head
			break
		}
	}
	if strings.HasPrefix(s, "unit=/") && !strings.ContainsAny(strings.TrimPrefix(s, "unit="), " \t") {
		return ""
	}
	return s
}

// formatLifecycleStepLabel builds "Step X of N: Title" from agent lifecycle.steps/current.
func formatLifecycleStepLabel(lc map[string]any, fallbackLabel string) string {
	if lc == nil {
		return ""
	}
	rawSteps, _ := lc["steps"].([]any)
	if len(rawSteps) == 0 {
		return ""
	}
	want := strings.ToLower(strFieldMap(lc, "current_step_id", "current"))
	idx := -1
	title := ""
	for i, raw := range rawSteps {
		m, _ := raw.(map[string]any)
		if m == nil {
			continue
		}
		id := strings.ToLower(strFieldMap(m, "id"))
		active := truthyAny(m["active"]) || strings.EqualFold(strFieldMap(m, "status"), "active") ||
			strings.EqualFold(strFieldMap(m, "status"), "error")
		if (want != "" && id == want) || (want == "" && active) {
			idx = i
			title = strFieldMap(m, "title")
			break
		}
	}
	if idx < 0 {
		for i, raw := range rawSteps {
			m, _ := raw.(map[string]any)
			if m == nil {
				continue
			}
			st := strings.ToLower(strFieldMap(m, "status"))
			if st != "done" && st != "skipped" && !truthyAny(m["done"]) {
				idx = i
				title = strFieldMap(m, "title")
				break
			}
		}
	}
	if idx < 0 {
		idx = len(rawSteps) - 1
		if m, _ := rawSteps[idx].(map[string]any); m != nil {
			title = strFieldMap(m, "title")
		}
	}
	// Prefer step title (agent-owned, e.g. "Full sync") over top-level label
	// ("Running"/"Syncing") so Nodes cards match detail Step headlines.
	base := strings.TrimSpace(title)
	if base == "" {
		base = strings.TrimSpace(fallbackLabel)
	}
	if base == "" {
		base = "step"
	}
	// Avoid double-prefix if agent/UI already formatted.
	if strings.HasPrefix(strings.ToLower(base), "step ") {
		return base
	}
	return fmt.Sprintf("Step %d of %d: %s", idx+1, len(rawSteps), base)
}

// extractClientVersion picks fullnode client version from agent status JSON.
// Order: top-level → rpc → sync.build_version → version.node.
// Always canonicalize (lowercase, no slashes) so SQLite cache matches agent format.
func extractClientVersion(doc map[string]any) string {
	if doc == nil {
		return ""
	}
	raw := ""
	if v := strFieldMap(doc, "client_version"); v != "" {
		raw = v
	} else if rpc, _ := doc["rpc"].(map[string]any); rpc != nil {
		raw = strFieldMap(rpc, "client_version", "version")
	}
	if raw == "" {
		if sync, _ := doc["sync"].(map[string]any); sync != nil {
			raw = strFieldMap(sync, "build_version")
		}
	}
	if raw == "" {
		if ver, _ := doc["version"].(map[string]any); ver != nil {
			raw = strFieldMap(ver, "node")
		}
	}
	return canonicalizeClientVersion(raw)
}

// canonicalizeClientVersion — lowercase, strip wrapping/internal slashes (align with system-agent).
func canonicalizeClientVersion(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	for strings.HasPrefix(s, "/") || strings.HasSuffix(s, "/") {
		s = strings.TrimPrefix(s, "/")
		s = strings.TrimSuffix(s, "/")
		s = strings.TrimSpace(s)
	}
	out := ""
	if i := strings.LastIndex(s, ":"); i > 0 && !strings.Contains(s[:i], "://") {
		name := strings.TrimSpace(s[:i])
		ver := strings.TrimSpace(strings.TrimPrefix(strings.TrimPrefix(s[i+1:], "v"), "V"))
		if name != "" && ver != "" {
			out = name + " " + ver
		}
	}
	if out == "" && strings.Contains(s, "/") {
		parts := strings.SplitN(s, "/", 3)
		head := strings.TrimSpace(parts[0])
		ver := ""
		if len(parts) > 1 {
			ver = strings.TrimSpace(strings.TrimPrefix(strings.TrimPrefix(parts[1], "v"), "V"))
		}
		if head != "" && ver != "" {
			out = head + " " + ver
		}
	}
	if out == "" {
		low := strings.ToLower(s)
		for _, p := range []string{"greatvoyage-", "java-tron-", "tron-"} {
			if strings.HasPrefix(low, p) {
				out = strings.TrimSpace(s[len(p):])
				out = strings.TrimPrefix(strings.TrimPrefix(out, "v"), "V")
				break
			}
		}
	}
	if out == "" {
		out = s
	}
	out = strings.ToLower(out)
	out = strings.ReplaceAll(out, "/", " ")
	return strings.Join(strings.Fields(out), " ")
}

func extractClientUpdate(doc map[string]any) (local, latest string, updateAvailable bool) {
	local = extractClientVersion(doc)
	cu, _ := doc["client_update"].(map[string]any)
	if cu == nil {
		return local, "", false
	}
	if v := strFieldMap(cu, "local"); v != "" {
		local = v
	}
	latest = strFieldMap(cu, "latest")
	updateAvailable = truthyAny(cu["update_available"])
	phase := strings.ToLower(strFieldMap(cu, "phase"))
	if phase == "updating" || phase == "starting" {
		// Surface as busy update even if versions equal mid-apply.
		updateAvailable = true
	}
	return local, latest, updateAvailable
}

func floatFieldMap(m map[string]any, keys ...string) float64 {
	if m == nil {
		return 0
	}
	for _, k := range keys {
		switch v := m[k].(type) {
		case float64:
			return v
		case float32:
			return float64(v)
		case int:
			return float64(v)
		case int64:
			return float64(v)
		case json.Number:
			f, _ := v.Float64()
			return f
		}
	}
	return 0
}

func strFieldMap(m map[string]any, keys ...string) string {
	if m == nil {
		return ""
	}
	for _, k := range keys {
		if v, ok := m[k].(string); ok && strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}

func int64Field(m map[string]any, keys ...string) int64 {
	if m == nil {
		return 0
	}
	for _, k := range keys {
		switch v := m[k].(type) {
		case float64:
			return int64(v)
		case int64:
			return v
		case int:
			return int64(v)
		case json.Number:
			i, _ := v.Int64()
			return i
		}
	}
	return 0
}

func truthyAny(v any) bool {
	switch t := v.(type) {
	case bool:
		return t
	case string:
		return t == "1" || strings.EqualFold(t, "true") || strings.EqualFold(t, "yes")
	case float64:
		return t != 0
	default:
		return false
	}
}
