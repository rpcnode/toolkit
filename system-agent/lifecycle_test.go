package main

import (
	"fmt"
	"strings"
	"testing"
	"time"
)

func stepByID(steps []map[string]any, id string) map[string]any {
	for _, s := range steps {
		if s["id"] == id {
			return s
		}
	}
	return nil
}

func TestBuildNodeLifecycleSnapshotBusy(t *testing.T) {
	lc := buildNodeLifecycle(nodeLifecycleInput{
		Network:        "tron",
		Env:            "mainnet",
		PublicPort:     39090,
		AgentPort:      39090,
		PublicPortOpen: true,
		AgentPortOpen:  true,
		InstRegistered: true,
		APIUp:          true,
		SnapEnabled:    true,
		Marker:         false,
		SnapBusy:       true,
		SnapPhase:      "download",
		Pct:            "42",
	})
	if lc["phase"] != "snapshot" {
		t.Fatalf("phase=%v want snapshot", lc["phase"])
	}
	if lc["node_status"] != "snapshot_download" {
		t.Fatalf("node_status=%v", lc["node_status"])
	}
	steps, _ := lc["steps"].([]map[string]any)
	if len(steps) != 5 {
		t.Fatalf("steps=%d want 5 (ports+install+snapshot+start+run)", len(steps))
	}
	snap := stepByID(steps, "snapshot")
	if snap == nil || snap["active"] != true {
		t.Fatalf("snapshot step not active: %+v", snap)
	}
	start := stepByID(steps, "start")
	if start == nil || start["status"] != "pending" {
		t.Fatalf("start must stay pending while snapshot active: %+v", start)
	}
	if lc["label"] == "Starting" {
		t.Fatalf("label must not be Starting during snapshot: %v", lc["label"])
	}
}

func TestBuildNodeLifecycleMainnetNeedsSnapshotNotStarting(t *testing.T) {
	// Root-cause repro: mainnet, agents up, no marker, no wget — must NOT claim Starting.
	lc := buildNodeLifecycle(nodeLifecycleInput{
		Network:        "tron",
		Env:            "mainnet",
		PublicPort:     39090,
		AgentPort:      39190,
		PublicPortOpen: true,
		AgentPortOpen:  true,
		InstRegistered: true,
		APIUp:          true,
		SnapEnabled:    true,
		Marker:         false,
		SnapBusy:       false,
		NodeActive:     false,
		RPCOK:          false,
	})
	if lc["phase"] != "snapshot" {
		t.Fatalf("phase=%v want snapshot", lc["phase"])
	}
	if lc["node_status"] != "needs_snapshot" {
		t.Fatalf("node_status=%v want needs_snapshot", lc["node_status"])
	}
	steps, _ := lc["steps"].([]map[string]any)
	snap := stepByID(steps, "snapshot")
	if snap == nil {
		t.Fatal("snapshot step required on mainnet")
	}
	if snap["status"] == "done" || snap["status"] == "skipped" {
		t.Fatalf("snapshot must not be done/skipped without marker: %+v", snap)
	}
	if snap["done"] == true {
		t.Fatalf("done flag must be false for unfinished snapshot: %+v", snap)
	}
	start := stepByID(steps, "start")
	if start["status"] != "pending" || start["active"] == true {
		t.Fatalf("start must be pending: %+v", start)
	}
	if lc["detail"] == "Starting java-tron" || lc["label"] == "Starting" {
		t.Fatalf("must not claim Starting: label=%v detail=%v", lc["label"], lc["detail"])
	}
}

func TestBuildNodeLifecycleFalseSnapEnabledStillRequiresMainnet(t *testing.T) {
	// Empty URL used to set SnapEnabled=false and lie "skipped" — profile still requires snapshot.
	lc := buildNodeLifecycle(nodeLifecycleInput{
		Network:        "tron",
		Env:            "mainnet",
		PublicPort:     39090,
		AgentPort:      39190,
		PublicPortOpen: true,
		AgentPortOpen:  true,
		APIUp:          true,
		InstRegistered: true,
		SnapEnabled:    false,
		Marker:         false,
		NodeActive:     false,
	})
	if lc["node_status"] == "starting" {
		t.Fatal("must not report starting when snapshot incomplete")
	}
	steps, _ := lc["steps"].([]map[string]any)
	snap := stepByID(steps, "snapshot")
	if snap == nil {
		t.Fatal("mainnet must include snapshot step")
	}
	if snap["status"] == "skipped" || snap["status"] == "done" {
		t.Fatalf("must not skip/done unfinished required snapshot: %+v", snap)
	}
	start := stepByID(steps, "start")
	if start["status"] != "pending" {
		t.Fatalf("start=%+v", start)
	}
}

func TestSkippedNotDoneFlag(t *testing.T) {
	m := lifecycleStep("snapshot", "Snapshot", "skipped", "Snapshot disabled for this env", nil)
	if m["done"] == true {
		t.Fatal("skipped must not set done=true")
	}
	if m["status"] != "skipped" {
		t.Fatalf("status=%v", m["status"])
	}
}

func TestBuildNodeLifecycleSnapBusyWinsOverDisabled(t *testing.T) {
	lc := buildNodeLifecycle(nodeLifecycleInput{
		Network:        "tron",
		Env:            "mainnet",
		PublicPort:     39090,
		AgentPort:      39090,
		PublicPortOpen: true,
		AgentPortOpen:  true,
		APIUp:          true,
		SnapEnabled:    false,
		SnapBusy:       true,
		SnapPhase:      "download",
		Pct:            "15",
	})
	if lc["phase"] != "snapshot" {
		t.Fatalf("phase=%v want snapshot", lc["phase"])
	}
	if lc["node_status"] != "snapshot_download" {
		t.Fatalf("node_status=%v", lc["node_status"])
	}
}

func TestBuildNodeLifecycleOmitSnapshotWhenDisabledShasta(t *testing.T) {
	t.Setenv("TRON_SNAPSHOT_ENABLED", "0")
	lc := buildNodeLifecycle(nodeLifecycleInput{
		Network:        "tron",
		Env:            "shasta",
		PublicPort:     39092,
		AgentPort:      39092,
		PublicPortOpen: true,
		AgentPortOpen:  true,
		APIUp:          true,
		InstRegistered: true,
		SnapEnabled:    false,
		Marker:         false,
		SnapBusy:       false,
		NodeActive:     true,
		RPCOK:          true,
		Height:         int64(5000),
		Progress:       &lifecycleProgress{Auto: autoPipelineState{NodeStartedAt: "2026-01-01T00:00:00Z"}},
	})
	steps, _ := lc["steps"].([]map[string]any)
	if stepByID(steps, "snapshot") != nil {
		t.Fatalf("snapshot must be omitted when disabled and idle: %+v", steps)
	}
}

func TestBuildNodeLifecycleStartAfterMarker(t *testing.T) {
	// Stale NodeStartedAt without a live process must NOT claim warming/active.
	lc := buildNodeLifecycle(nodeLifecycleInput{
		Network:        "tron",
		Env:            "mainnet",
		PublicPort:     39090,
		AgentPort:      39190,
		PublicPortOpen: true,
		AgentPortOpen:  true,
		APIUp:          true,
		InstRegistered: true,
		SnapEnabled:    true,
		Marker:         true,
		NodeActive:     false,
		Progress:       &lifecycleProgress{Auto: autoPipelineState{NodeStartedAt: "2026-08-09T12:00:00Z"}},
	})
	if lc["phase"] != "start" {
		t.Fatalf("phase=%v want start", lc["phase"])
	}
	if lc["node_status"] != "ready_to_start" {
		t.Fatalf("node_status=%v want ready_to_start (stale ACK)", lc["node_status"])
	}
	steps, _ := lc["steps"].([]map[string]any)
	snap := stepByID(steps, "snapshot")
	if snap["status"] != "done" {
		t.Fatalf("snapshot=%+v", snap)
	}
	start := stepByID(steps, "start")
	if start["status"] != "pending" {
		t.Fatalf("start=%+v want pending without live process", start)
	}
}

func TestBuildNodeLifecycleBitcoinStartErrorFromJournal(t *testing.T) {
	lc := buildNodeLifecycle(nodeLifecycleInput{
		Network:        "bitcoin",
		Env:            "mainnet",
		PublicPort:     39290,
		AgentPort:      39390,
		PublicPortOpen: true,
		AgentPortOpen:  true,
		APIUp:          true,
		InstRegistered: true,
		NodeActive:     false,
		StartError:     `Error: specified config file "/etc/bitcoin/mainnet/bitcoin.conf" could not be opened.`,
	})
	if lc["phase"] != "error" {
		t.Fatalf("phase=%v want error", lc["phase"])
	}
	if lc["node_status"] != "start_error" {
		t.Fatalf("node_status=%v want start_error", lc["node_status"])
	}
	steps, _ := lc["steps"].([]map[string]any)
	start := stepByID(steps, "start")
	if start["status"] != "error" {
		t.Fatalf("start=%+v", start)
	}
	if !strings.Contains(fmt.Sprint(start["detail"]), "could not be opened") {
		t.Fatalf("detail=%v", start["detail"])
	}
	if strings.Contains(strings.ToLower(fmt.Sprint(lc["detail"])), "warming") {
		t.Fatalf("must not claim warming: %v", lc["detail"])
	}
}

func TestPaceLifecycleCompletionsInstantHealthy(t *testing.T) {
	// Regtest collapse: every step done in one tick must walk active→done.
	prevDwell := lifecyclePaceMinDwell
	lifecyclePaceMinDwell = 0
	t.Cleanup(func() { lifecyclePaceMinDwell = prevDwell })

	in := nodeLifecycleInput{
		Network:        "dash",
		Env:            "regtest",
		PublicPort:     41292,
		AgentPort:      41392,
		PublicPortOpen: true,
		AgentPortOpen:  true,
		APIUp:          true,
		InstRegistered: true,
		NodeActive:     true,
		RPCOK:          true,
		Height:         int64(0),
		Peers:          0,
		Progress:       &lifecycleProgress{Steps: map[string]stepProgress{}},
	}
	lc1 := buildNodeLifecycle(in)
	if lc1["phase"] == "healthy" || lc1["complete"] == true {
		t.Fatalf("tick1 must not be healthy yet: phase=%v complete=%v", lc1["phase"], lc1["complete"])
	}
	steps1, _ := lc1["steps"].([]map[string]any)
	ports := stepByID(steps1, "ports")
	if ports["status"] != "active" {
		t.Fatalf("tick1 ports want active: %+v", ports)
	}
	if stepByID(steps1, "install")["status"] == "done" {
		t.Fatalf("tick1 install must stay pending while ports active")
	}

	lc2 := buildNodeLifecycle(in)
	steps2, _ := lc2["steps"].([]map[string]any)
	if stepByID(steps2, "ports")["status"] != "done" {
		t.Fatalf("tick2 ports want done: %+v", stepByID(steps2, "ports"))
	}
	if stepByID(steps2, "install")["status"] != "active" {
		t.Fatalf("tick2 install want active: %+v", stepByID(steps2, "install"))
	}

	// Drain remaining paced ticks until healthy.
	var phase any
	for i := 0; i < 12; i++ {
		lc := buildNodeLifecycle(in)
		phase = lc["phase"]
		if phase == "healthy" {
			if lc["complete"] != true {
				t.Fatalf("healthy must set complete")
			}
			return
		}
	}
	t.Fatalf("never reached healthy; last phase=%v", phase)
}

func TestPaceLifecycleResetsPrematureACK(t *testing.T) {
	// ports/install already stamped done (same-second) before RPC made run done —
	// must re-walk from ports, not jump to Healthy on the last step only.
	prevDwell := lifecyclePaceMinDwell
	lifecyclePaceMinDwell = 5 * time.Second
	t.Cleanup(func() { lifecyclePaceMinDwell = prevDwell })

	same := time.Now().UTC().Format(time.RFC3339)
	in := nodeLifecycleInput{
		Network:        "ltc",
		Env:            "regtest",
		PublicPort:     41092,
		AgentPort:      41192,
		PublicPortOpen: true,
		AgentPortOpen:  true,
		APIUp:          true,
		InstRegistered: true,
		NodeActive:     true,
		RPCOK:          true,
		Height:         int64(0),
		Peers:          0,
		Progress: &lifecycleProgress{Steps: map[string]stepProgress{
			"ports":   {Status: "done", StartedAt: same, FinishedAt: same},
			"install": {Status: "done", StartedAt: same, FinishedAt: same},
			"start":   {Status: "done", StartedAt: same, FinishedAt: same},
		}},
	}
	lc := buildNodeLifecycle(in)
	if lc["phase"] == "healthy" || lc["complete"] == true {
		t.Fatalf("must not skip to healthy: phase=%v", lc["phase"])
	}
	steps, _ := lc["steps"].([]map[string]any)
	if stepByID(steps, "ports")["status"] != "active" {
		t.Fatalf("want ports active after premature-ACK reset: %+v", stepByID(steps, "ports"))
	}
	if stepByID(steps, "run")["status"] == "done" {
		t.Fatalf("run must stay pending while ports paced: %+v", stepByID(steps, "run"))
	}
}

func TestPaceLifecycleEpochClearsInstantHealthy(t *testing.T) {
	prevDwell := lifecyclePaceMinDwell
	lifecyclePaceMinDwell = 0
	t.Cleanup(func() { lifecyclePaceMinDwell = prevDwell })

	same := time.Now().UTC().Format(time.RFC3339)
	in := nodeLifecycleInput{
		Network:        "ltc",
		Env:            "regtest",
		PublicPort:     41092,
		AgentPort:      41192,
		PublicPortOpen: true,
		AgentPortOpen:  true,
		APIUp:          true,
		InstRegistered: true,
		NodeActive:     true,
		RPCOK:          true,
		Height:         int64(0),
		Progress: &lifecycleProgress{
			PaceEpoch: 0, // pre-0.4.42 progress
			Steps: map[string]stepProgress{
				"ports":   {Status: "done", StartedAt: same, FinishedAt: same},
				"install": {Status: "done", StartedAt: same, FinishedAt: same},
				"start":   {Status: "done", StartedAt: same, FinishedAt: same},
				"run":     {Status: "done", StartedAt: same, FinishedAt: same},
			},
		},
	}
	lc := buildNodeLifecycle(in)
	if lc["phase"] == "healthy" {
		t.Fatalf("epoch bump must re-walk, not stay healthy")
	}
	if in.Progress.PaceEpoch != lifecyclePaceEpoch {
		t.Fatalf("pace_epoch=%d want %d", in.Progress.PaceEpoch, lifecyclePaceEpoch)
	}
	steps, _ := lc["steps"].([]map[string]any)
	if stepByID(steps, "ports")["status"] != "active" {
		t.Fatalf("want ports active: %+v", stepByID(steps, "ports"))
	}
}

func TestPaceLifecycleRepairsInstantCollapseACK(t *testing.T) {
	prevDwell := lifecyclePaceMinDwell
	lifecyclePaceMinDwell = 5 * time.Second
	t.Cleanup(func() { lifecyclePaceMinDwell = prevDwell })

	t0 := time.Now().UTC()
	in := nodeLifecycleInput{
		Network:        "ltc",
		Env:            "regtest",
		PublicPort:     41092,
		AgentPort:      41192,
		PublicPortOpen: true,
		AgentPortOpen:  true,
		APIUp:          true,
		InstRegistered: true,
		NodeActive:     true,
		RPCOK:          true,
		Height:         int64(0),
		Progress: &lifecycleProgress{Steps: map[string]stepProgress{
			"ports":   {Status: "done", StartedAt: t0.Format(time.RFC3339), FinishedAt: t0.Format(time.RFC3339)},
			"install": {Status: "done", StartedAt: t0.Format(time.RFC3339), FinishedAt: t0.Add(2 * time.Second).Format(time.RFC3339)},
			"start":   {Status: "done", StartedAt: t0.Add(2 * time.Second).Format(time.RFC3339), FinishedAt: t0.Add(4 * time.Second).Format(time.RFC3339)},
			"run":     {Status: "done", StartedAt: t0.Add(4 * time.Second).Format(time.RFC3339), FinishedAt: t0.Add(6 * time.Second).Format(time.RFC3339)},
		}},
	}
	lc := buildNodeLifecycle(in)
	if lc["phase"] == "healthy" {
		t.Fatalf("instant-collapse ACK must be repaired, not stay healthy")
	}
	steps, _ := lc["steps"].([]map[string]any)
	if stepByID(steps, "ports")["status"] != "active" {
		t.Fatalf("repair must restart walk at ports: %+v", stepByID(steps, "ports"))
	}
}

func TestPaceLifecycleHoldsDwell(t *testing.T) {
	prevDwell := lifecyclePaceMinDwell
	lifecyclePaceMinDwell = 5 * time.Second
	t.Cleanup(func() { lifecyclePaceMinDwell = prevDwell })

	started := time.Now().UTC().Add(-1 * time.Second).Format(time.RFC3339)
	in := nodeLifecycleInput{
		Network:        "ltc",
		Env:            "regtest",
		PublicPort:     41092,
		AgentPort:      41192,
		PublicPortOpen: true,
		AgentPortOpen:  true,
		APIUp:          true,
		InstRegistered: true,
		NodeActive:     true,
		RPCOK:          true,
		Height:         int64(0),
		Progress: &lifecycleProgress{Steps: map[string]stepProgress{
			"ports": {Status: "active", StartedAt: started},
		}},
	}
	lc := buildNodeLifecycle(in)
	steps, _ := lc["steps"].([]map[string]any)
	if stepByID(steps, "ports")["status"] != "active" {
		t.Fatalf("ports must stay active during dwell: %+v", stepByID(steps, "ports"))
	}
	if lc["phase"] == "healthy" {
		t.Fatalf("must not be healthy during ports dwell")
	}
}

func TestBuildNodeLifecyclePortsPhase(t *testing.T) {
	lc := buildNodeLifecycle(nodeLifecycleInput{
		Network:        "tron",
		Env:            "mainnet",
		PublicPort:     39090,
		AgentPort:      39190,
		PublicPortOpen: false,
		AgentPortOpen:  false,
		APIUp:          false,
		SnapEnabled:    true,
	})
	if lc["phase"] != "ports" {
		t.Fatalf("phase=%v want ports", lc["phase"])
	}
}

func TestBuildPortsStepRequiresGoRPCWhenSplit(t *testing.T) {
	// Agent API up, Go public_port down → ports not done.
	step := buildPortsStep(nodeLifecycleInput{
		PublicPort: 39290, AgentPort: 39390,
		PublicPortOpen: false, AgentPortOpen: true, APIUp: true,
	})
	if step["status"] == "done" {
		t.Fatalf("status=%v want active (Go RPC missing)", step["status"])
	}
	detail, _ := step["detail"].(string)
	if !strings.Contains(detail, "39290") {
		t.Fatalf("detail=%q want mention of Go RPC :39290", detail)
	}
	stepOK := buildPortsStep(nodeLifecycleInput{
		PublicPort: 39290, AgentPort: 39390,
		PublicPortOpen: true, AgentPortOpen: true, APIUp: true,
	})
	if stepOK["status"] != "done" {
		t.Fatalf("status=%v want done when both listen", stepOK["status"])
	}
}

func TestResolveLifecycleProfileFutureNetwork(t *testing.T) {
	p := resolveLifecycleProfile(nodeLifecycleInput{
		Network:     "solana",
		Env:         "mainnet",
		SnapEnabled: true,
	})
	if p.IncludeSnapshot {
		t.Fatal("solana must not inherit tron snapshot by default")
	}
}

func TestBuildNodeLifecycleRPCDoesNotSkipSnapshotGate(t *testing.T) {
	// Lying path: RPC somehow OK while mainnet snapshot unfinished.
	lc := buildNodeLifecycle(nodeLifecycleInput{
		Network:        "tron",
		Env:            "mainnet",
		PublicPort:     39090,
		AgentPort:      39190,
		PublicPortOpen: true,
		AgentPortOpen:  true,
		APIUp:          true,
		InstRegistered: true,
		SnapEnabled:    true,
		Marker:         false,
		NodeActive:     true,
		RPCOK:          true,
		Height:         int64(5000),
	})
	if lc["phase"] != "snapshot" {
		t.Fatalf("phase=%v want snapshot", lc["phase"])
	}
	steps, _ := lc["steps"].([]map[string]any)
	start := stepByID(steps, "start")
	run := stepByID(steps, "run")
	if start["status"] != "pending" || start["done"] == true {
		t.Fatalf("start must stay pending: %+v", start)
	}
	if run["status"] != "pending" || run["done"] == true {
		t.Fatalf("run must stay pending: %+v", run)
	}
}

func TestStepCompleteSkippedOptionalOnly(t *testing.T) {
	req := lifecycleStepOptional("snapshot", "Snapshot", "skipped", "disabled", nil)
	req["required"] = true
	req["optional"] = false
	if stepComplete(req) {
		t.Fatal("required+skipped must not complete the gate")
	}
	opt := lifecycleStepOptional("snapshot", "Snapshot", "skipped", "disabled", nil)
	if !stepComplete(opt) {
		t.Fatal("optional skipped should complete the gate")
	}
}
