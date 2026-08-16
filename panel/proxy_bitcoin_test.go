package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/ali3/tron-toolkit/panel/store"
)

func TestSanitizeBscStripsForeignTronDiskGate(t *testing.T) {
	// Leaf healthz.network=bsc but status.json loaded stale tron-mainnet disk gate
	// (UI briefly sent ?network=tron&env=mainnet). Must never paint TRON snapshot_error.
	raw, _ := json.Marshal(map[string]any{
		"ok": true, "network": "bsc", "health": "error",
		"supported_networks": []any{"bsc", "tron", "ethereum"},
		"lifecycle": map[string]any{
			"phase": "error", "label": "Snapshot error",
			"detail":      "insufficient disk for snapshot: free≈5481 GiB on /data/tron/mainnet",
			"node_status": "snapshot_error",
			"profile": map[string]any{
				"network": "tron", "env": "mainnet", "include_snapshot": true,
			},
		},
		"snapshot": map[string]any{
			"enabled": true, "failed": true, "phase": "error",
			"error": "insufficient disk for snapshot on /data/tron/mainnet",
		},
		"instance": map[string]any{
			"network": "tron", "env": "mainnet", "id": "tron-mainnet",
			"data_dir": "/data/tron/mainnet",
		},
		"agent": map[string]any{
			"status": "error", "activity": "snapshot_error",
			"last_error": "insufficient disk for snapshot on /data/tron/mainnet",
		},
	})
	wl := WorkloadRef{
		ID: "f5b65a73-1d25-44a4-96b4-eb4880c0bd8a", Network: "bsc", Env: "testnet",
		AgentPort: 39991, ServerID: "ethereum-host",
	}
	out := sanitizeStatusForWorkload(raw, wl, "http://185.44.207.117:39991")
	var doc map[string]any
	if err := json.Unmarshal(out, &doc); err != nil {
		t.Fatal(err)
	}
	if _, has := doc["lifecycle"]; has {
		t.Fatal("foreign TRON lifecycle must be stripped")
	}
	blob := strings.ToLower(string(out))
	if strings.Contains(blob, "/data/tron") || strings.Contains(blob, "insufficient disk") {
		t.Fatalf("must not leak TRON disk gate: %s", string(out)[:400])
	}
	if doc["panel_network"] != "bsc" {
		t.Fatalf("panel_network=%v", doc["panel_network"])
	}
	inst := doc["instance"].(map[string]any)
	if inst["network"] != "bsc" {
		t.Fatalf("instance.network=%v", inst["network"])
	}
}

func TestSanitizeStatusForBitcoinHostTronShowsSetupNotMismatch(t *testing.T) {
	raw, _ := json.Marshal(map[string]any{
		"ok": true,
		"supported_networks": []any{"bitcoin", "tron"},
		"lifecycle": map[string]any{
			"phase":  "error",
			"label":  "Snapshot error",
			"detail": "insufficient disk for snapshot on /data/tron/mainnet",
			"profile": map[string]any{
				"network": "tron", "env": "mainnet", "display_name": "TRON Mainnet",
				"include_snapshot": true, "snapshot_required": true,
			},
		},
		"snapshot": map[string]any{"enabled": true, "phase": "error", "failed": true},
		"instance": map[string]any{"network": "tron", "env": "mainnet", "id": "tron-mainnet"},
	})
	// Unprovisioned / wrong TRON ports on bitcoin card → setup, not mismatch lecture.
	wl := WorkloadRef{
		ID: "bitcoin-mainnet", Network: "bitcoin", Env: "mainnet", ServerID: "bitcoin-1",
		AgentPort: 39190, // TRON port — not a bitcoin per-node agent
	}
	out := sanitizeStatusForWorkload(raw, wl, "http://203.0.113.10:39190")
	var doc map[string]any
	if err := json.Unmarshal(out, &doc); err != nil {
		t.Fatal(err)
	}
	if truthy(doc["network_mismatch"]) {
		t.Fatal("host tron profile must not be network_mismatch for bitcoin")
	}
	if !truthy(doc["needs_provision"]) {
		t.Fatal("expected needs_provision")
	}
	if doc["health"] != "setup" {
		t.Fatalf("health=%v want setup", doc["health"])
	}
	lc := doc["lifecycle"].(map[string]any)
	if lc["label"] != "Setup" {
		t.Fatalf("label=%v", lc["label"])
	}
	detail, _ := lc["detail"].(string)
	low := strings.ToLower(detail)
	if strings.Contains(low, "network mismatch") || strings.Contains(low, "agent profile is") {
		t.Fatalf("must not lecture: %q", detail)
	}
	if strings.Contains(low, "/data/tron") {
		t.Fatalf("must not leak TRON snapshot path: %q", detail)
	}
	if doc["panel_network"] != "bitcoin" {
		t.Fatalf("panel_network=%v", doc["panel_network"])
	}
}

func TestSanitizeStatusMatchedBitcoinPassthrough(t *testing.T) {
	// Panel must pass through agent lifecycle copy unchanged (no java-tron / rewrite).
	const agentDetail = "Agent units ready · upstream :8332"
	raw, _ := json.Marshal(map[string]any{
		"ok": true, "health": "setup",
		"network": "bitcoin",
		"lifecycle": map[string]any{
			"phase": "install", "label": "Install",
			"detail": agentDetail,
			"profile": map[string]any{
				"network": "bitcoin", "env": "mainnet", "include_snapshot": false,
			},
			"steps": []any{
				map[string]any{"id": "install", "title": "Install", "status": "active",
					"detail": agentDetail},
			},
		},
		"instance": map[string]any{"network": "bitcoin", "env": "mainnet", "id": "bitcoin-mainnet"},
	})
	wl := WorkloadRef{
		ID: "bitcoin-mainnet", Network: "bitcoin", Env: "mainnet", AgentPort: 39390,
	}
	out := sanitizeStatusForWorkload(raw, wl, "http://203.0.113.10:39390")
	var doc map[string]any
	if err := json.Unmarshal(out, &doc); err != nil {
		t.Fatal(err)
	}
	if truthy(doc["network_mismatch"]) {
		t.Fatal("matched agent must not be mismatch")
	}
	if truthy(doc["needs_provision"]) {
		t.Fatal("matched bitcoin agent must not need_provision")
	}
	if doc["health"] == "mismatch" {
		t.Fatal("health must not be mismatch")
	}
	lc := doc["lifecycle"].(map[string]any)
	if lc["detail"] != agentDetail {
		t.Fatalf("lifecycle detail=%v want agent copy", lc["detail"])
	}
	steps := lc["steps"].([]any)
	step := steps[0].(map[string]any)
	if step["detail"] != agentDetail {
		t.Fatalf("install detail=%v want agent copy", step["detail"])
	}
	if strings.Contains(strings.ToLower(step["detail"].(string)), "java-tron") {
		t.Fatal("must not inject java-tron")
	}
}

func TestSanitizeStatusWrongPerNodeAgentShortMismatch(t *testing.T) {
	// Truly incompatible: supported_networks present and excludes bitcoin.
	raw, _ := json.Marshal(map[string]any{
		"ok":                 true,
		"supported_networks": []any{"tron"},
		"upstream":           "127.0.0.1:18332",
		"lifecycle": map[string]any{
			"profile": map[string]any{"network": "tron", "include_snapshot": false},
		},
		"instance": map[string]any{"network": "tron"},
	})
	wl := WorkloadRef{
		ID: "bitcoin-mainnet", Network: "bitcoin", Env: "mainnet", AgentPort: 39390,
	}
	out := sanitizeStatusForWorkload(raw, wl, "http://203.0.113.10:39390")
	var doc map[string]any
	if err := json.Unmarshal(out, &doc); err != nil {
		t.Fatal(err)
	}
	if !truthy(doc["network_mismatch"]) {
		t.Fatal("dedicated wrong agent must mismatch")
	}
	detail, _ := doc["message"].(string)
	if strings.Contains(strings.ToLower(detail), "provision bitcoin on this server") {
		t.Fatalf("too verbose: %q", detail)
	}
	if !strings.Contains(detail, "bitcoin") {
		t.Fatalf("detail=%q", detail)
	}
}

func TestSanitizeBitcoinTronLifecycleOnDedicatedPortNoInventedSetup(t *testing.T) {
	// Dedicated agent_port matched — never invent needs_provision (Confirm ports ACK freeze).
	// Stale TRON lifecycle without supported_networks is not Wrong agent either.
	raw, _ := json.Marshal(map[string]any{
		"ok":       true,
		"upstream": "127.0.0.1:18090",
		"lifecycle": map[string]any{
			"phase": "error", "label": "Snapshot error",
			"profile": map[string]any{
				"network": "tron", "env": "mainnet", "include_snapshot": true,
			},
		},
		"instance": map[string]any{"network": "tron", "env": "mainnet"},
	})
	wl := WorkloadRef{
		ID: "bitcoin-mainnet", Network: "bitcoin", Env: "mainnet", AgentPort: 39390,
	}
	out := sanitizeStatusForWorkload(raw, wl, "http://203.0.113.10:39390")
	var doc map[string]any
	if err := json.Unmarshal(out, &doc); err != nil {
		t.Fatal(err)
	}
	if truthy(doc["network_mismatch"]) {
		t.Fatal("tron lifecycle without supported_networks must not be Wrong agent")
	}
	if truthy(doc["needs_provision"]) {
		t.Fatal("dedicated port must not invent needs_provision")
	}
}

func TestSanitizeHealthzBitcoinStaleInstanceNotWrongAgent(t *testing.T) {
	// healthz.network=bitcoin wins; upstream port must NOT imply tron / force Setup lecture.
	raw, _ := json.Marshal(map[string]any{
		"ok":                 true,
		"network":            "bitcoin",
		"supported_networks": []any{"bitcoin", "tron"},
		"capabilities":       map[string]any{"ibd": true, "snapshot": false},
		"agent_version":      "0.3.19",
		"upstream":           "127.0.0.1:18090",
		"instance":           map[string]any{"network": "tron", "env": "mainnet", "id": "tron-mainnet"},
		"lifecycle": map[string]any{
			"phase": "install", "label": "Install",
			"detail": "Agent units ready · upstream :8332",
			"profile": map[string]any{"network": "tron", "include_snapshot": true},
			"steps": []any{
				map[string]any{"id": "install", "title": "Install", "status": "done",
					"detail": "Agent units ready · upstream :8332"},
			},
		},
	})
	wl := WorkloadRef{
		ID: "bitcoin-mainnet", Network: "bitcoin", Env: "mainnet", AgentPort: 39390,
	}
	out := sanitizeStatusForWorkload(raw, wl, "http://203.0.113.10:39390")
	var doc map[string]any
	if err := json.Unmarshal(out, &doc); err != nil {
		t.Fatal(err)
	}
	if truthy(doc["network_mismatch"]) || doc["health"] == "mismatch" {
		t.Fatal("healthz.network=bitcoin must not be Wrong agent")
	}
	if doc["agent_network"] != "bitcoin" {
		t.Fatalf("agent_network=%v want bitcoin (not stale instance tron)", doc["agent_network"])
	}
	if truthy(doc["needs_provision"]) {
		t.Fatal("must not force Setup from upstream port when identity is bitcoin")
	}
	lc := doc["lifecycle"].(map[string]any)
	if lc["label"] == "Wrong agent" {
		t.Fatal("label must not be Wrong agent")
	}
	detail, _ := lc["detail"].(string)
	low := strings.ToLower(detail)
	if strings.Contains(low, "got tron") || strings.Contains(low, "java-tron") {
		t.Fatalf("must not lecture tron/java-tron: %q", detail)
	}
	if detail != "Agent units ready · upstream :8332" {
		t.Fatalf("must keep agent lifecycle copy, got %q", detail)
	}
}

func TestSanitizeHealthzBitcoinMatchingPassthrough(t *testing.T) {
	// healthz.network=bitcoin + proper bitcoind upstream + stale instance.tron →
	// trust identity, clear mismatch, do not force Setup.
	raw, _ := json.Marshal(map[string]any{
		"ok":                 true,
		"health":             "ok",
		"network":            "bitcoin",
		"supported_networks": []any{"bitcoin", "tron"},
		"capabilities":       map[string]any{"ibd": true, "snapshot": false},
		"upstream":           "127.0.0.1:8332",
		"instance":           map[string]any{"network": "tron", "env": "mainnet"},
		"lifecycle": map[string]any{
			"phase": "run", "label": "Running",
			"profile": map[string]any{"network": "tron", "include_snapshot": false},
		},
	})
	wl := WorkloadRef{
		ID: "bitcoin-mainnet", Network: "bitcoin", Env: "mainnet", AgentPort: 39390,
	}
	out := sanitizeStatusForWorkload(raw, wl, "http://203.0.113.10:39390")
	var doc map[string]any
	if err := json.Unmarshal(out, &doc); err != nil {
		t.Fatal(err)
	}
	if truthy(doc["network_mismatch"]) {
		t.Fatal("must not mismatch when healthz.network matches")
	}
	if truthy(doc["needs_provision"]) {
		t.Fatal("must not force Setup when identity matches and upstream is bitcoind")
	}
	if doc["agent_network"] != "bitcoin" {
		t.Fatalf("agent_network=%v", doc["agent_network"])
	}
	inst := doc["instance"].(map[string]any)
	if inst["network"] != "bitcoin" {
		t.Fatalf("instance.network overlay=%v want bitcoin", inst["network"])
	}
}

func TestAgentReportedNetworkPrefersHealthzOverInstance(t *testing.T) {
	doc := map[string]any{
		"network":            "bitcoin",
		"supported_networks": []any{"bitcoin", "tron"},
		"capabilities":       map[string]any{"ibd": true, "snapshot": false},
		"instance":           map[string]any{"network": "tron"},
		"lifecycle": map[string]any{
			"profile": map[string]any{"network": "tron"},
		},
	}
	if got := agentReportedNetwork(doc); got != "bitcoin" {
		t.Fatalf("got %q want bitcoin", got)
	}
}

func TestSanitizeBitcoinMissingSupportedNetworksIsSetup(t *testing.T) {
	// Host Server agent (wrong port) + omitted supported_networks → Setup shell.
	raw, _ := json.Marshal(map[string]any{
		"ok":       true,
		"instance": map[string]any{"network": "tron"},
	})
	wl := WorkloadRef{
		ID: "btc", Network: "bitcoin", Env: "mainnet", AgentPort: 39390,
	}
	out := sanitizeStatusForWorkload(raw, wl, "http://10.0.0.1:39190")
	var doc map[string]any
	if err := json.Unmarshal(out, &doc); err != nil {
		t.Fatal(err)
	}
	if truthy(doc["network_mismatch"]) {
		t.Fatal("missing supported_networks must not force Wrong agent")
	}
	if !truthy(doc["needs_provision"]) {
		t.Fatal("expected needs_provision soften on host port")
	}
}

func TestSanitizeDedicatedAgentPassthroughEvenIfStaleInstance(t *testing.T) {
	// Regtest stuck bug: dedicated :39393 must not get needs_provision shell —
	// Confirm ports waits for ACK that the shell can never give.
	raw, _ := json.Marshal(map[string]any{
		"ok": true, "network": "bitcoin",
		"lifecycle": map[string]any{
			"phase": "start", "current": "start", "current_step_id": "start",
			"detail": "warming up",
			"profile": map[string]any{"network": "bitcoin", "env": "regtest"},
			"steps": []any{
				map[string]any{"id": "ports", "status": "done", "done": true},
				map[string]any{"id": "install", "status": "done", "done": true},
				map[string]any{"id": "start", "status": "active"},
			},
		},
		"instance": map[string]any{"network": "tron", "env": "regtest"},
	})
	wl := WorkloadRef{
		ID: "regtest", Network: "bitcoin", Env: "regtest", AgentPort: 39393,
	}
	out := sanitizeStatusForWorkload(raw, wl, "http://10.0.0.1:39393")
	var doc map[string]any
	if err := json.Unmarshal(out, &doc); err != nil {
		t.Fatal(err)
	}
	if truthy(doc["needs_provision"]) {
		t.Fatal("dedicated bitcoin agent must not invent needs_provision")
	}
	lc := doc["lifecycle"].(map[string]any)
	if lc["current"] != "start" && lc["current_step_id"] != "start" {
		t.Fatalf("lifecycle must pass through start, got %#v", lc)
	}
}

func TestResolveAgent_NodeBitcoinUsesBitcoinServer(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/panel.db")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })

	btc, err := db.UpsertServer(store.Server{
		ID: "bitcoin-1", Name: "bitcoin-1", Network: "bitcoin", Env: "mainnet",
		AgentURL: "http://203.0.113.10:39190", AgentKey: "btc-key",
	})
	if err != nil {
		t.Fatal(err)
	}
	tron, err := db.UpsertServer(store.Server{
		ID: "tron-1", Name: "tron 1", Network: "tron", Env: "mainnet",
		AgentURL: "http://198.51.100.20:39190", AgentKey: "tron-key",
	})
	if err != nil {
		t.Fatal(err)
	}
	btcNode, err := db.UpsertNode(store.Node{
		ServerID: btc.ID, Network: "bitcoin", Env: "mainnet",
		AgentURL: btc.AgentURL, AgentPort: 39390,
	})
	if err != nil {
		t.Fatal(err)
	}
	if !store.IsNodeUUID(btcNode.ID) {
		t.Fatalf("btc node id=%q want uuid", btcNode.ID)
	}
	if _, err := db.UpsertNode(store.Node{
		ServerID: tron.ID, Network: "tron", Env: "mainnet", AgentURL: tron.AgentURL,
	}); err != nil {
		t.Fatal(err)
	}

	s := &Server{
		db:        db,
		registry:  NewNodeRegistry(db),
		workloads: NewWorkloadRegistry(db),
	}

	req := httptest.NewRequest(http.MethodGet,
		"/api/status.json?node="+btcNode.ID+"&network=bitcoin&env=mainnet", nil)
	base, token := s.resolveAgent(req)
	if base != "http://203.0.113.10:39390" {
		t.Fatalf("base=%q want bitcoin per-node agent :39390", base)
	}
	if token != "btc-key" {
		t.Fatalf("token mismatch")
	}

	// Legacy slug still resolves when unique.
	reqSlug := httptest.NewRequest(http.MethodGet,
		"/api/status.json?node=bitcoin-mainnet&network=bitcoin&env=mainnet", nil)
	baseSlug, _ := s.resolveAgent(reqSlug)
	if baseSlug != "http://203.0.113.10:39390" {
		t.Fatalf("legacy slug base=%q want :39390", baseSlug)
	}

	// UI sends server=bitcoin-1 (host :39190) together with node= — must still use :39390.
	reqBoth := httptest.NewRequest(http.MethodGet,
		"/api/status.json?node="+btcNode.ID+"&server=bitcoin-1&network=bitcoin&env=mainnet", nil)
	baseBoth, tokenBoth := s.resolveAgent(reqBoth)
	if baseBoth != "http://203.0.113.10:39390" {
		t.Fatalf("node+server base=%q want :39390 (not host :39190)", baseBoth)
	}
	if tokenBoth != "btc-key" {
		t.Fatalf("token mismatch")
	}

	// server alone → host control-plane agent.
	reqSrv := httptest.NewRequest(http.MethodGet, "/api/v1/agent/check?server=bitcoin-1", nil)
	baseSrv, _ := s.resolveAgent(reqSrv)
	if baseSrv != "http://203.0.113.10:39190" {
		t.Fatalf("server-only base=%q want host :39190", baseSrv)
	}

	if _, ok := s.workloadByEnv("mainnet"); ok {
		t.Fatal("env=mainnet alone must be ambiguous with tron+bitcoin")
	}
}

// Merged host CP: only :39390 listens (no separate :39190). servers.agent_url == node URL.
func TestResolveAgentMergedHostControlPlane(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/panel.db")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })

	btc, err := db.UpsertServer(store.Server{
		ID: "bitcoin-1", Name: "bitcoin-1", Network: "bitcoin", Env: "mainnet",
		AgentURL: "http://203.0.113.10:39390", AgentKey: "btc-key",
	})
	if err != nil {
		t.Fatal(err)
	}
	btcNode, err := db.UpsertNode(store.Node{
		ServerID: btc.ID, Network: "bitcoin", Env: "mainnet",
		AgentURL: btc.AgentURL, AgentPort: 39390, Status: "ports_confirmed",
	})
	if err != nil {
		t.Fatal(err)
	}

	s := &Server{
		db:        db,
		registry:  NewNodeRegistry(db),
		workloads: NewWorkloadRegistry(db),
	}

	reqSrv := httptest.NewRequest(http.MethodGet, "/api/v1/agent/check?server=bitcoin-1", nil)
	baseSrv, _ := s.resolveAgent(reqSrv)
	if baseSrv != "http://203.0.113.10:39390" {
		t.Fatalf("merged server-only base=%q want :39390", baseSrv)
	}

	reqNode := httptest.NewRequest(http.MethodGet,
		"/api/status.json?node="+btcNode.ID+"&network=bitcoin&env=mainnet", nil)
	baseNode, _ := s.resolveAgent(reqNode)
	if baseNode != "http://203.0.113.10:39390" {
		t.Fatalf("merged node base=%q want :39390", baseNode)
	}

	if agentControlBase(NodeRef(btc), WorkloadRef(btcNode)) != "http://203.0.113.10:39390" {
		t.Fatal("agentControlBase must stay on live :39390")
	}
}

func TestSanitizeHostPortResponseNotMismatch(t *testing.T) {
	// Panel knows agent_port=39390 but status was fetched from host :39190 (tron instance).
	raw, _ := json.Marshal(map[string]any{
		"ok": true,
		"instance": map[string]any{"network": "tron", "env": "mainnet", "id": "tron-mainnet"},
	})
	wl := WorkloadRef{
		ID: "bitcoin-mainnet", Network: "bitcoin", Env: "mainnet", AgentPort: 39390,
	}
	out := sanitizeStatusForWorkload(raw, wl, "http://203.0.113.10:39190")
	var doc map[string]any
	if err := json.Unmarshal(out, &doc); err != nil {
		t.Fatal(err)
	}
	if truthy(doc["network_mismatch"]) {
		t.Fatal("host :39190 response must not be Wrong agent when node port is :39390")
	}
	if !truthy(doc["needs_provision"]) {
		t.Fatal("expected needs_provision safety net")
	}
}

func TestWorkloadLooksProvisionedBitcoin(t *testing.T) {
	if workloadLooksProvisioned(WorkloadRef{Network: "bitcoin", AgentPort: 0}) {
		t.Fatal("port 0 not provisioned")
	}
	if workloadLooksProvisioned(WorkloadRef{Network: "bitcoin", AgentPort: 39190}) {
		t.Fatal("tron agent port must not count as bitcoin provisioned")
	}
	if !workloadLooksProvisioned(WorkloadRef{Network: "bitcoin", AgentPort: 39390}) {
		t.Fatal("39390 is bitcoin agent")
	}
}

func TestSanitizePortsConfirmedDoesNotInventInstall(t *testing.T) {
	// ports_confirmed in SQLite is a panel UX helper — must NOT invent lifecycle.current=install.
	// Agent lifecycle (started_at/finished_at) is the only source of step progress.
	raw, _ := json.Marshal(map[string]any{
		"ok": true,
		"supported_networks": []any{"bitcoin", "tron"},
		"instance":           map[string]any{"network": "tron", "env": "mainnet"},
	})
	wl := WorkloadRef{
		ID: "d976bd5b-7d43-4425-b946-7d8d894b767f",
		Network: "bitcoin", Env: "mainnet", ServerID: "srv-1",
		AgentPort: 39190, Status: "ports_confirmed",
	}
	out := sanitizeStatusForWorkload(raw, wl, "http://203.0.113.10:39190")
	var doc map[string]any
	if err := json.Unmarshal(out, &doc); err != nil {
		t.Fatal(err)
	}
	if !truthy(doc["needs_provision"]) {
		t.Fatal("still needs per-node agent")
	}
	lc := doc["lifecycle"].(map[string]any)
	if lc["current"] != "ports" || lc["current_step_id"] != "ports" {
		t.Fatalf("current=%v want ports (ACK rule: no invent from SQLite)", lc["current"])
	}
	if lc["node_status"] != "ports_confirmed" {
		t.Fatalf("node_status=%v (helper may stay ports_confirmed)", lc["node_status"])
	}
	steps, _ := lc["steps"].([]any)
	if len(steps) < 1 {
		t.Fatal("expected ports step")
	}
	ports := steps[0].(map[string]any)
	if ports["status"] == "done" || truthy(ports["done"]) {
		t.Fatal("ports step must not be marked done from SQLite alone")
	}
}

func TestWorkloadPreProvision(t *testing.T) {
	if !workloadPreProvision(WorkloadRef{Status: "awaiting_ports", AgentPort: 0}) {
		t.Fatal("awaiting_ports must be pre-provision")
	}
	if !workloadPreProvision(WorkloadRef{Status: "awaiting_ports", AgentPort: 40992, Network: "stellar"}) {
		t.Fatal("awaiting_ports with leftover port still pre-provision")
	}
	if !workloadPreProvision(WorkloadRef{Status: "ready_to_install", AgentPort: 40992, Network: "dash"}) {
		t.Fatal("ready_to_install has tip catalog ports but leaf not installed yet")
	}
	if !workloadPreProvision(WorkloadRef{Status: "", AgentPort: 0, Network: "stellar"}) {
		t.Fatal("no agent_port must be pre-provision")
	}
	if workloadPreProvision(WorkloadRef{Status: "ports_confirmed", AgentPort: 40992, Network: "stellar"}) {
		t.Fatal("ports_confirmed with agent_port should poll tip (not pre-provision skip)")
	}
	if workloadPreProvision(WorkloadRef{Status: "installing", AgentPort: 40992, Network: "dash"}) {
		t.Fatal("installing must poll leaf/tip (not pre-provision skip)")
	}
	if workloadPreProvision(WorkloadRef{Status: "syncing", AgentPort: 40992, Network: "stellar"}) {
		t.Fatal("syncing leaf must not be pre-provision")
	}
}

func TestWorkloadLeafShouldBeUp(t *testing.T) {
	if workloadLeafShouldBeUp(WorkloadRef{Status: "awaiting_ports", AgentPort: 0}) {
		t.Fatal("awaiting_ports leaf not up")
	}
	if workloadLeafShouldBeUp(WorkloadRef{Status: "ready_to_install", AgentPort: 40992, Network: "dash"}) {
		t.Fatal("ready_to_install — leaf not up until Install")
	}
	if workloadLeafShouldBeUp(WorkloadRef{Status: "awaiting_ports", AgentPort: 40992, Network: "stellar"}) {
		t.Fatal("awaiting_ports with port still skips leaf")
	}
	if !workloadLeafShouldBeUp(WorkloadRef{Status: "ports_confirmed", AgentPort: 40992, Network: "stellar"}) {
		t.Fatal("ports_confirmed — dial leaf for IBD % (soft-fail if down)")
	}
	if !workloadLeafDialSoftFail(WorkloadRef{Status: "ports_confirmed", AgentPort: 40992, Network: "stellar"}) {
		t.Fatal("ports_confirmed leaf dial must be soft-fail")
	}
	if !workloadLeafDialSoftFail(WorkloadRef{Status: "installing", AgentPort: 40992, Network: "dash"}) {
		t.Fatal("installing leaf dial must be soft-fail")
	}
	if workloadLeafDialSoftFail(WorkloadRef{Status: "syncing", AgentPort: 40992, Network: "stellar"}) {
		t.Fatal("syncing leaf down is a real fault")
	}
	if !workloadLeafShouldBeUp(WorkloadRef{Status: "syncing", AgentPort: 40992, Network: "stellar"}) {
		t.Fatal("syncing — leaf should be up")
	}
	if !workloadLeafShouldBeUp(WorkloadRef{Status: "online", AgentPort: 40992, Network: "stellar"}) {
		t.Fatal("online — leaf should be up")
	}
}

func TestSanitizeTipHostTipOnTipURLDoesNotWipeSetup(t *testing.T) {
	// Collector used to dial tip while status=ports_confirmed → host_tip → fake
	// Install SETUP that wiped snapshot_pct. Tip URL + host_tip must pass through.
	raw, _ := json.Marshal(map[string]any{
		"ok": true, "host_tip": true, "node_status": "host",
		"supported_networks": []any{"dash", "bitcoin", "tron"},
		"lifecycle":          map[string]any{"phase": "host", "label": "Host Server"},
	})
	wl := WorkloadRef{
		ID: "dash-main", Network: "dash", Env: "mainnet", AgentPort: 41390,
		Status: "ports_confirmed",
	}
	out := sanitizeStatusForWorkload(raw, wl, "http://185.44.207.104:39090")
	var doc map[string]any
	if err := json.Unmarshal(out, &doc); err != nil {
		t.Fatal(err)
	}
	if truthy(doc["needs_provision"]) {
		t.Fatal("tip host_tip on tip URL must not invent needs_provision (hides IBD %)")
	}
}

func TestSanitizeTronAwaitingPortsStaysOnPorts(t *testing.T) {
	raw, _ := json.Marshal(map[string]any{
		"ok": true,
		"instance": map[string]any{"network": "tron", "env": "mainnet"},
	})
	wl := WorkloadRef{
		ID: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
		Network: "tron", Env: "mainnet", AgentPort: 0, Status: "awaiting_ports",
	}
	out := sanitizeStatusForWorkload(raw, wl, "http://203.0.113.10:39190")
	var doc map[string]any
	if err := json.Unmarshal(out, &doc); err != nil {
		t.Fatal(err)
	}
	lc := doc["lifecycle"].(map[string]any)
	if lc["current"] != "ports" {
		t.Fatalf("current=%v want ports", lc["current"])
	}
}

func TestSanitizeAwaitingPortsIgnoresLeftoverLeafLifecycle(t *testing.T) {
	// Re-add / incomplete remove: SQLite still awaiting_ports but leftover leaf
	// answers with install/start — must stay on Confirm ports.
	raw, _ := json.Marshal(map[string]any{
		"ok": true,
		"instance": map[string]any{"network": "stellar", "env": "testnet"},
		"lifecycle": map[string]any{
			"phase": "start", "current": "start", "current_step_id": "start",
			"label": "Starting", "node_status": "starting",
		},
	})
	wl := WorkloadRef{
		ID: "bbbbbbbb-cccc-dddd-eeee-ffffffffffff",
		Network: "stellar", Env: "testnet",
		AgentPort: 40991, Status: "awaiting_ports",
	}
	out := sanitizeStatusForWorkload(raw, wl, "http://203.0.113.10:40991")
	var doc map[string]any
	if err := json.Unmarshal(out, &doc); err != nil {
		t.Fatal(err)
	}
	if !truthy(doc["needs_provision"]) {
		t.Fatal("awaiting_ports must force needs_provision over leftover leaf")
	}
	lc := doc["lifecycle"].(map[string]any)
	if lc["current"] != "ports" || lc["current_step_id"] != "ports" {
		t.Fatalf("current=%v want ports", lc["current"])
	}
}
