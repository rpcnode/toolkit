package main

import (
	"fmt"
	"strconv"
	"strings"
	"time"
)

// Lifecycle step IDs returned in lifecycle.steps[].id
//
// Common (all networks) — always present, order preserved:
//
//	ports   — public_port / agent_port allocated and listening
//	install — agent units answering (healthz / API)
//	start   — chain node process started, RPC warming up
//	run     — syncing with network / healthy
//
// Network-specific extras come from NetworkProfile.ExtraSteps (see network_profiles.go),
// inserted after install. Typical extra: "snapshot".
//
// Status values: pending | active | done | error | skipped
//   - done    = step completed successfully
//   - skipped = optional step intentionally not run (≠ done)
//   - active  = agent is executing this step now
//
// Agent drives the pipeline (see pipeline.go): after a required step finishes,
// the next automatic step is started without waiting for the UI.
// Profile lookup is table-driven — do not add per-network switches here.

type nodeLifecycleInput struct {
	Network string
	Env     string

	PublicPort     int
	AgentPort      int
	UpstreamPort   int // bitcoind RPC / FullNode HTTP (from network profile)
	PublicPortOpen bool
	AgentPortOpen  bool

	InstRegistered bool
	APIUp          bool
	SnapEnabled    bool
	SnapRequired   bool // profile: must complete before start
	Marker         bool
	SnapBusy       bool
	SnapFailed     bool
	SnapPhase      string
	SnapDetail     string
	SnapErr        string
	Pct            string
	NodeActive     bool
	StartError     string // unit failed / conf missing / crash-loop (journal detail)
	WarmupDetail   string // optional: agent-owned start detail while NodeActive && !RPCOK (e.g. Solana snapshot %)
	RPCOK          bool
	Height         any
	Headers        any
	IBD            bool
	VerifyPct      float64 // bitcoin verificationprogress 0..1
	Peers          int     // bitcoin getnetworkinfo.connections (optional)
	SizeOnDisk     int64   // bitcoin getblockchaininfo.size_on_disk bytes (optional)
	Maintenance    bool

	// Persisted progress from previous ticks (optional).
	Progress *lifecycleProgress
}

// networkLifecycleProfile — runtime gating flags for pipeline/lifecycle
// (same shape as orchestration 0.3.12). Static catalog lives in network_profiles.go.
type networkLifecycleProfile struct {
	Network          string
	Env              string
	IncludeSnapshot  bool
	SnapshotRequired bool
	AutoSnapshot     bool // pipeline may start snapshot
	AutoStartNode    bool // pipeline may start node after prior steps
}

// resolveLifecycleProfile maps the static NetworkProfile catalog to runtime
// gating flags (IncludeSnapshot / SnapshotRequired / Auto*). Prefer this over
// ad-hoc env switches in collect/pipeline.
func resolveLifecycleProfile(in nodeLifecycleInput) networkLifecycleProfile {
	np := LookupNetworkProfile(in.Network, in.Env)
	explicitOff := snapshotExplicitlyDisabled()

	p := networkLifecycleProfile{
		Network:       np.Network,
		Env:           np.Env,
		AutoStartNode: np.AutoStartNode,
	}

	wantsSnapshot := np.HasExtra(StepSnapshot) || np.SnapshotPolicy != SnapshotNever
	inFlight := in.SnapBusy || in.SnapFailed || in.Marker

	switch np.SnapshotPolicy {
	case SnapshotRequired:
		// Required policy: include unless explicitly disabled (still show in-flight).
		// Robinhood Orbit MUST keep Snapshot even if an old provision left
		// TRON_SNAPSHOT_ENABLED=0 — otherwise UI loses nitro download % forever.
		robinhoodForce := strings.EqualFold(np.Network, "robinhood")
		off := explicitOff && !robinhoodForce
		p.IncludeSnapshot = wantsSnapshot && (!off || inFlight)
		p.SnapshotRequired = !off
		p.AutoSnapshot = np.AutoSnapshot && !off
	case SnapshotOptional:
		p.IncludeSnapshot = wantsSnapshot && (in.SnapEnabled || inFlight)
		p.SnapshotRequired = in.SnapEnabled && !explicitOff
		p.AutoSnapshot = p.SnapshotRequired && np.AutoSnapshot
	default: // SnapshotNever
		p.IncludeSnapshot = inFlight
		p.SnapshotRequired = false
		p.AutoSnapshot = false
	}

	if in.SnapEnabled && !explicitOff && np.SnapshotPolicy != SnapshotNever {
		p.IncludeSnapshot = true
	}

	return p
}

func lifecycleStep(id, title, status, detail string, pct any) map[string]any {
	m := map[string]any{
		"id":     id,
		"title":  title,
		"status": status, // pending | active | done | error | skipped
		// done flag is ONLY true for successful completion — never for skipped.
		"done":   status == "done",
		"active": status == "active",
		"error":  status == "error",
		"detail": detail,
	}
	if pct != nil {
		m["pct"] = pct
	}
	return m
}

func lifecycleStepOptional(id, title, status, detail string, pct any) map[string]any {
	m := lifecycleStep(id, title, status, detail, pct)
	m["optional"] = true
	return m
}

func heightNum(h any) (int64, bool) {
	switch v := h.(type) {
	case int64:
		return v, true
	case int:
		return int64(v), true
	case float64:
		return int64(v), true
	case string:
		n, err := strconv.ParseInt(strings.TrimSpace(v), 10, 64)
		if err != nil {
			return 0, false
		}
		return n, true
	default:
		return 0, false
	}
}

func parseSnapPct(pct string) any {
	s := strings.TrimSpace(strings.TrimSuffix(pct, "%"))
	if s == "" || s == "…" || strings.EqualFold(s, "n/a") {
		return nil
	}
	f, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return s
	}
	if f < 0 {
		f = 0
	}
	if f > 100 {
		f = 100
	}
	return f
}

func profileNodeHint(network, env string) (hint string, expectRPC int) {
	np := LookupNetworkProfile(network, env)
	hint = strings.TrimSpace(np.NodeBinaryHint)
	if hint == "" {
		hint = "node"
	}
	return hint, np.DefaultNodeHTTP
}

// upstreamMismatchDetail — profile DefaultNodeHTTP vs configured upstream (agent-owned copy).
func upstreamMismatchDetail(in nodeLifecycleInput) string {
	hint, expect := profileNodeHint(in.Network, in.Env)
	if expect <= 0 || in.UpstreamPort <= 0 || in.UpstreamPort == expect {
		return ""
	}
	return fmt.Sprintf("Upstream :%d — point %s at RPC :%d", in.UpstreamPort, hint, expect)
}

func buildPortsStep(in nodeLifecycleInput) map[string]any {
	pub := in.PublicPort
	agent := in.AgentPort
	allocated := pub > 0 && agent > 0
	// Split listen: Go RPC public_port AND Agent API must both be up.
	// APIUp/agent alone must NOT mark ports done (would hide missing :39290).
	listening := false
	switch {
	case pub > 0 && agent > 0 && pub != agent:
		listening = in.PublicPortOpen && in.AgentPortOpen
	case pub > 0 && (agent <= 0 || pub == agent):
		listening = in.PublicPortOpen || in.AgentPortOpen || in.APIUp
	case agent > 0:
		listening = in.AgentPortOpen || in.APIUp
	}

	detail := "Waiting for port assignment"
	status := "pending"
	switch {
	case allocated && listening:
		status = "done"
		if pub == agent {
			detail = "RPC/agent :" + strconv.Itoa(pub) + " listening"
		} else {
			detail = fmt.Sprintf("public :%d · agent :%d listening", pub, agent)
		}
		if up := upstreamMismatchDetail(in); up != "" {
			status = "active"
			detail = up
		}
	case allocated:
		status = "active"
		if pub == agent {
			detail = fmt.Sprintf("Ports assigned (:%d) · waiting for listen", pub)
		} else if in.AgentPortOpen && !in.PublicPortOpen {
			detail = fmt.Sprintf("agent :%d up · waiting Go RPC :%d", agent, pub)
		} else if in.PublicPortOpen && !in.AgentPortOpen {
			detail = fmt.Sprintf("Go RPC :%d up · waiting agent :%d", pub, agent)
		} else {
			detail = fmt.Sprintf("Assigned public :%d · agent :%d · waiting for listen", pub, agent)
		}
	default:
		status = "active"
		parts := []string{}
		if pub > 0 {
			parts = append(parts, fmt.Sprintf("public :%d", pub))
		}
		if agent > 0 {
			parts = append(parts, fmt.Sprintf("agent :%d", agent))
		}
		if len(parts) > 0 {
			detail = strings.Join(parts, " · ") + " · incomplete assignment"
		}
	}

	return lifecycleStep("ports", "Ports", status, detail, nil)
}

func buildInstallStep(in nodeLifecycleInput) map[string]any {
	hint, _ := profileNodeHint(in.Network, in.Env)
	np := LookupNetworkProfile(in.Network, in.Env)
	installDone := in.APIUp
	installStatus := "pending"
	installDetail := "Waiting for agent units"
	switch {
	case installDone && in.InstRegistered:
		installStatus = "done"
		installDetail = "Agent units ready"
		if up := upstreamMismatchDetail(in); up != "" {
			installDetail = up
		}
	case installDone:
		installStatus = "done"
		installDetail = "Agent units ready · instance registry pending"
	default:
		installStatus = "active"
		if np.SnapshotPolicy == SnapshotNever {
			switch {
			case isBitcoinRegtest(in.Env) && (strings.EqualFold(in.Network, "bitcoin") ||
				strings.EqualFold(in.Network, "doge") || networkIsCoreLikeSA(in.Network)):
				installDetail = fmt.Sprintf("Provision %s (regtest)", hint)
			case strings.EqualFold(in.Network, "solana") && isSolanaLocalnet(in.Env):
				installDetail = fmt.Sprintf("Provision %s (localnet)", hint)
			case strings.EqualFold(in.Network, "solana"):
				installDetail = fmt.Sprintf("Provision %s (Agave RPC)", hint)
			case strings.EqualFold(in.Network, "ethereum"):
				installDetail = fmt.Sprintf("Provision %s (Geth + Lighthouse)", hint)
			default:
				installDetail = fmt.Sprintf("Provision %s (IBD)", hint)
			}
		} else {
			installDetail = "API agent not reachable on healthz"
		}
	}
	return lifecycleStep("install", "Install", installStatus, installDetail, nil)
}

func buildSnapshotStep(in nodeLifecycleInput, profile networkLifecycleProfile) map[string]any {
	snapPct := parseSnapPct(in.Pct)
	if in.Marker {
		snapPct = float64(100)
	}

	snapStatus := "pending"
	snapDetail := "Chain data not started"
	switch {
	case in.SnapFailed:
		snapStatus = "error"
		snapDetail = in.SnapErr
		if snapDetail == "" {
			snapDetail = in.SnapDetail
		}
		if snapDetail == "" {
			snapDetail = "Snapshot failed"
		}
	case in.Marker:
		snapStatus = "done"
		snapDetail = "Chain data ready"
	case in.SnapBusy:
		snapStatus = "active"
		ph := strings.ToLower(strings.TrimSpace(in.SnapPhase))
		if ph == "" {
			ph = "download"
		}
		snapDetail = "Snapshot " + ph
		if in.SnapDetail != "" {
			snapDetail = in.SnapDetail
		}
	case !profile.SnapshotRequired && !in.SnapEnabled:
		// Truly optional / disabled — distinct from done.
		snapStatus = "skipped"
		snapDetail = "Snapshot disabled for this env"
		snapPct = nil
	case in.APIUp || profile.SnapshotRequired:
		snapStatus = "pending"
		if in.SnapDetail != "" {
			snapDetail = in.SnapDetail
		} else if !in.SnapEnabled {
			snapDetail = "Waiting for snapshot (URL/config)"
		} else {
			snapDetail = "Waiting to start snapshot download"
		}
	}

	step := lifecycleStepOptional("snapshot", "Snapshot", snapStatus, snapDetail, snapPct)
	if profile.SnapshotRequired {
		step["optional"] = false
		step["required"] = true
	}
	return step
}

func buildStartStep(in nodeLifecycleInput, profile networkLifecycleProfile, snapStatus string) map[string]any {
	startStatus := "pending"
	startDetail := "Node process not started"
	hint, _ := profileNodeHint(in.Network, in.Env)

	// Required snapshot not finished → start MUST stay pending (never "starting").
	snapBlocks := profile.IncludeSnapshot && profile.SnapshotRequired &&
		snapStatus != "done" && snapStatus != "skipped"

	pipeErr := ""
	if in.Progress != nil {
		// Bare "unit=/etc/.../*.service" is FragmentPath noise, not a real start failure.
		pipeErr = stripUnitPathNoise(in.Progress.Auto.LastError)
	}
	startErr := stripUnitPathNoise(in.StartError)

	switch {
	case profile.IncludeSnapshot && in.SnapFailed && profile.SnapshotRequired:
		startStatus = "pending"
		startDetail = "Blocked by snapshot error"
	case snapBlocks:
		startStatus = "pending"
		startDetail = "Waiting for snapshot"
	case startErr != "":
		// Crash-loop / missing conf / unit exit-code — never "warming up".
		startStatus = "error"
		startDetail = startErr
	case in.RPCOK:
		startStatus = "done"
		startDetail = "RPC responding"
	case in.NodeActive:
		startStatus = "active"
		if wd := strings.TrimSpace(in.WarmupDetail); wd != "" {
			startDetail = wd
		} else {
			startDetail = hint + " warming up · waiting for RPC"
		}
	case pipeErr != "" && (in.Progress == nil || in.Progress.Auto.NodeStartedAt == ""):
		startStatus = "error"
		startDetail = pipeErr
	case in.Marker || (!profile.SnapshotRequired && !snapBlocks):
		// ACK: "active/starting" only when the process/unit is really up.
		// Stale NodeStartedAt without NodeActive must not pretend warming.
		if in.NodeActive {
			startStatus = "active"
			startDetail = "Starting " + hint
		} else if pipeErr != "" {
			startStatus = "error"
			startDetail = pipeErr
		} else {
			startStatus = "pending"
			startDetail = "Ready to start " + hint
		}
	}
	return lifecycleStep("start", "Start", startStatus, startDetail, nil)
}

func buildRunStep(in nodeLifecycleInput) map[string]any {
	hn, hasH := heightNum(in.Height)
	headers, hasHeaders := heightNum(in.Headers)
	np := LookupNetworkProfile(in.Network, in.Env)
	regtest := isBitcoinRegtest(in.Env) && (strings.EqualFold(in.Network, "bitcoin") ||
		strings.EqualFold(in.Network, "doge") || networkIsCoreLikeSA(in.Network))
	solanaNet := strings.EqualFold(in.Network, "solana")
	solanaLocal := solanaNet && isSolanaLocalnet(in.Env)
	ethereumNet := strings.EqualFold(in.Network, "ethereum")
	bscNet := strings.EqualFold(in.Network, "bsc")
	hlNet := strings.EqualFold(in.Network, "hyperliquid")
	tonNet := strings.EqualFold(in.Network, "ton")
	tronNet := strings.EqualFold(in.Network, "tron")
	ibdProfile := np.SnapshotPolicy == SnapshotNever || in.IBD || hasHeaders
	runTitle := "Run"
	if tonNet || tronNet {
		runTitle = "Catch-up / sync"
	} else if !regtest && !solanaNet && !ethereumNet && !bscNet && !hlNet && ibdProfile && (strings.EqualFold(in.Network, "bitcoin") || in.IBD || hasHeaders) {
		runTitle = "IBD / sync"
	}
	if solanaNet && !solanaLocal {
		runTitle = "Catch-up / sync"
	}
	if ethereumNet {
		runTitle = "EL/CL sync"
	}
	if bscNet || hlNet {
		runTitle = "Full sync"
	}

	// Hyperliquid — HyperEVM eth_syncing + hl-node applied-block bootstrap.
	if hlNet {
		runStatus := "pending"
		runDetail := "Node not online yet"
		var pct any
		if in.VerifyPct > 0 {
			p := in.VerifyPct * 100
			if p > 100 {
				p = 100
			}
			pct = float64(int(p*10+0.5)) / 10
		}
		switch {
		case in.StartError != "":
			runStatus = "error"
			runDetail = in.StartError
			pct = nil
		case !in.NodeActive:
			runStatus = "pending"
			runDetail = "Waiting for hl-visor"
			pct = nil
		case !in.RPCOK && in.IBD:
			runStatus = "active"
			if in.WarmupDetail != "" {
				runDetail = in.WarmupDetail
			} else if hasH {
				runDetail = fmt.Sprintf("Bootstrap · applied block %d", hn)
			} else {
				runDetail = "Bootstrap · waiting for HyperEVM RPC"
			}
			if pct != nil {
				runDetail = fmt.Sprintf("%s · %s%%", runDetail, formatSyncPct(pct.(float64)))
			}
		case !in.RPCOK:
			runStatus = "pending"
			runDetail = "Waiting for HyperEVM RPC"
			pct = nil
		case in.Maintenance:
			runStatus = "active"
			runDetail = "RPC paused (maintenance)"
			pct = nil
		case in.IBD:
			runStatus = "active"
			if hasH && hasHeaders {
				runDetail = fmt.Sprintf("Syncing · blocks %d / %d", hn, headers)
			} else if hasH {
				runDetail = fmt.Sprintf("Syncing · block %d", hn)
			} else {
				runDetail = "Full syncing"
			}
			if pct != nil {
				runDetail = fmt.Sprintf("%s · %s%%", runDetail, formatSyncPct(pct.(float64)))
			}
			if in.Peers >= 0 {
				runDetail = fmt.Sprintf("%s · peers %d", runDetail, in.Peers)
			}
		default:
			runStatus = "done"
			pct = float64(100)
			if hasH {
				runDetail = "Synced · block " + strconv.FormatInt(hn, 10)
			} else {
				runDetail = "Synced · RPC healthy"
			}
			if in.Peers >= 0 {
				runDetail = fmt.Sprintf("%s · peers %d", runDetail, in.Peers)
			}
		}
		return lifecycleStep("run", runTitle, runStatus, runDetail, pct)
	}

	// TON — MyTonCtrl dump/bootstrap before THA, then lag-closed catch-up.
	// Mirror HL: keep VerifyPct / WarmupDetail while !RPCOK && IBD (dump %).
	if tonNet {
		runStatus := "pending"
		runDetail := "Node not online yet"
		var pct any
		if in.VerifyPct > 0 {
			p := in.VerifyPct * 100
			if p > 100 {
				p = 100
			}
			pct = float64(int(p*10+0.5)) / 10
		}
		switch {
		case in.StartError != "":
			runStatus = "error"
			runDetail = in.StartError
			pct = nil
		case !in.NodeActive:
			runStatus = "pending"
			runDetail = "Waiting for MyTonCtrl / validator"
			pct = nil
		case !in.RPCOK && in.IBD:
			runStatus = "active"
			if in.WarmupDetail != "" {
				runDetail = in.WarmupDetail
			} else if hasH {
				runDetail = fmt.Sprintf("Catch-up · seqno %d", hn)
			} else {
				runDetail = "MyTonCtrl bootstrap / catch-up"
			}
			if pct != nil {
				runDetail = fmt.Sprintf("%s · %s%%", runDetail, formatSyncPct(pct.(float64)))
			}
		case !in.RPCOK:
			runStatus = "pending"
			runDetail = "Waiting for TON HTTP API"
			// Keep dump/lag % on the run step so lifecycle.pct survives start gate.
		case in.Maintenance:
			runStatus = "active"
			runDetail = "RPC paused (maintenance)"
			pct = nil
		case in.IBD:
			runStatus = "active"
			if hasH {
				runDetail = fmt.Sprintf("Catch-up · seqno %d", hn)
			} else {
				runDetail = "Catch-up / sync"
			}
			if pct != nil {
				runDetail = fmt.Sprintf("%s · %s%%", runDetail, formatSyncPct(pct.(float64)))
			}
		default:
			runStatus = "done"
			pct = float64(100)
			if hasH {
				runDetail = "Synced · seqno " + strconv.FormatInt(hn, 10)
			} else {
				runDetail = "Synced · RPC healthy"
			}
		}
		return lifecycleStep("run", runTitle, runStatus, runDetail, pct)
	}

	// BSC full sync (bnb-chain/bsc geth fork, Parlia — no separate CL).
	if bscNet {
		runStatus := "pending"
		runDetail := "Node not online yet"
		var pct any
		if in.VerifyPct > 0 {
			p := in.VerifyPct * 100
			if p > 100 {
				p = 100
			}
			pct = float64(int(p*10+0.5)) / 10
		}
		switch {
		case !in.RPCOK:
			runStatus = "pending"
			runDetail = "Waiting for bsc-geth RPC"
			pct = nil
		case in.Maintenance:
			runStatus = "active"
			runDetail = "RPC paused (maintenance)"
			pct = nil
		case in.IBD:
			runStatus = "active"
			if hasH && hasHeaders {
				runDetail = fmt.Sprintf("Syncing · blocks %d / %d", hn, headers)
			} else if hasH {
				runDetail = fmt.Sprintf("Syncing · block %d", hn)
			} else {
				runDetail = "Full syncing"
			}
			if pct != nil {
				runDetail = fmt.Sprintf("%s · %s%%", runDetail, formatSyncPct(pct.(float64)))
			}
			if in.Peers >= 0 {
				runDetail = fmt.Sprintf("%s · peers %d", runDetail, in.Peers)
			}
		default:
			runStatus = "done"
			pct = float64(100)
			if hasH {
				runDetail = "Synced · block " + strconv.FormatInt(hn, 10)
			} else {
				runDetail = "Synced · RPC healthy"
			}
			if in.Peers >= 0 {
				runDetail = fmt.Sprintf("%s · peers %d", runDetail, in.Peers)
			}
		}
		return lifecycleStep("run", runTitle, runStatus, runDetail, pct)
	}

	// Ethereum EL/CL sync (Geth + Lighthouse).
	if ethereumNet {
		runStatus := "pending"
		runDetail := "Node not online yet"
		var pct any
		switch {
		case !in.RPCOK:
			runStatus = "pending"
			runDetail = "Waiting for geth RPC"
		case in.Maintenance:
			runStatus = "active"
			runDetail = "RPC paused (maintenance)"
		case in.IBD:
			runStatus = "active"
			if hasH {
				runDetail = fmt.Sprintf("Syncing · block %d", hn)
			} else {
				runDetail = "EL/CL syncing"
			}
			if in.Peers >= 0 {
				runDetail = fmt.Sprintf("%s · peers %d", runDetail, in.Peers)
			}
		default:
			runStatus = "done"
			pct = float64(100)
			if hasH {
				runDetail = "Synced · block " + strconv.FormatInt(hn, 10)
			} else {
				runDetail = "Synced · RPC healthy"
			}
			if in.Peers >= 0 {
				runDetail = fmt.Sprintf("%s · peers %d", runDetail, in.Peers)
			}
		}
		return lifecycleStep("run", runTitle, runStatus, runDetail, pct)
	}

	// Solana localnet / public cluster catch-up (not Bitcoin IBD copy).
	// Catch-up has no honest 0–100% (unlike bitcoind verificationprogress) —
	// only attach pct when collect gave a real signal (snapshot download / healthy).
	if solanaNet {
		runStatus := "pending"
		runDetail := "Node not online yet"
		var pct any
		if in.VerifyPct > 0 {
			p := in.VerifyPct * 100
			if p > 100 {
				p = 100
			}
			pct = float64(int(p*10+0.5)) / 10
		}
		switch {
		case !in.RPCOK:
			runStatus = "pending"
			runDetail = "Waiting for RPC"
			pct = nil
		case in.Maintenance:
			runStatus = "active"
			runDetail = "RPC paused (maintenance)"
			pct = nil
		case solanaLocal:
			runStatus = "done"
			pct = float64(100)
			if hasH {
				runDetail = fmt.Sprintf("Localnet · slot %d", hn)
			} else {
				runDetail = "Localnet · Ready"
			}
		case in.IBD: // catch-up flag from collectSolana
			runStatus = "active"
			// Show node slot + cluster tip + behind. % = lag closed (not me/tip ratio).
			if hasH && hasHeaders && headers > hn {
				runDetail = fmt.Sprintf("node %d · tip %d · %d behind", hn, headers, headers-hn)
			} else if hasH && hasHeaders {
				runDetail = fmt.Sprintf("node %d · tip %d", hn, headers)
			} else if hasH {
				runDetail = fmt.Sprintf("Catching up · slot %d", hn)
			} else {
				runDetail = "Catching up with cluster"
			}
			if pct != nil {
				runDetail = fmt.Sprintf("%s · %s%% lag closed", runDetail, formatSyncPct(pct.(float64)))
			}
		default:
			runStatus = "done"
			pct = float64(100)
			if hasH {
				runDetail = "Synced · slot " + strconv.FormatInt(hn, 10)
			} else {
				runDetail = "Synced · RPC healthy"
			}
		}
		return lifecycleStep("run", runTitle, runStatus, runDetail, pct)
	}

	// TRON — local getnowblock vs public tip. % = lag-closed (not me/tip).
	if tronNet {
		runStatus := "pending"
		runDetail := "Node not online yet"
		var pct any
		if in.VerifyPct > 0 {
			p := in.VerifyPct * 100
			if p > 100 {
				p = 100
			}
			pct = float64(int(p*10+0.5)) / 10
		}
		switch {
		case in.StartError != "":
			runStatus = "error"
			runDetail = in.StartError
			pct = nil
		case !in.NodeActive:
			runStatus = "pending"
			runDetail = "Waiting for java-tron"
			pct = nil
		case !in.RPCOK:
			runStatus = "pending"
			runDetail = "Waiting for RPC"
			pct = nil
		case in.Maintenance:
			runStatus = "active"
			runDetail = "RPC paused (maintenance)"
			pct = nil
		case in.IBD:
			runStatus = "active"
			if hasH && hasHeaders && headers > hn {
				runDetail = fmt.Sprintf("node %d · tip %d · %d behind", hn, headers, headers-hn)
			} else if hasH && hasHeaders {
				runDetail = fmt.Sprintf("node %d · tip %d", hn, headers)
			} else if hasH {
				runDetail = fmt.Sprintf("height %d · waiting for public tip", hn)
			} else {
				runDetail = "Catching up"
			}
			if pct != nil {
				runDetail = fmt.Sprintf("%s · %s%% lag closed", runDetail, formatSyncPct(pct.(float64)))
			}
			if in.Peers >= 0 {
				runDetail = fmt.Sprintf("%s · peers %d", runDetail, in.Peers)
			}
		default:
			runStatus = "done"
			pct = float64(100)
			if hasH {
				runDetail = "Synced · block " + strconv.FormatInt(hn, 10)
			} else {
				runDetail = "Synced · RPC healthy"
			}
			if in.Peers >= 0 {
				runDetail = fmt.Sprintf("%s · peers %d", runDetail, in.Peers)
			}
		}
		return lifecycleStep("run", runTitle, runStatus, runDetail, pct)
	}

	// Regtest: local chain — RPC up means ready (ignore bitcoind initialblockdownload).
	if regtest {
		runStatus := "pending"
		runDetail := "Node not online yet"
		var pct any
		switch {
		case !in.RPCOK:
			runStatus = "pending"
			runDetail = "Waiting for RPC"
		case in.Maintenance:
			runStatus = "active"
			runDetail = "RPC paused (maintenance)"
		default:
			runStatus = "done"
			pct = float64(100)
			if hasH {
				runDetail = fmt.Sprintf("Regtest · blocks %d", hn)
			} else {
				runDetail = "Regtest · Ready"
			}
			if in.Peers >= 0 {
				runDetail = fmt.Sprintf("%s · peers %d", runDetail, in.Peers)
			}
		}
		return lifecycleStep("run", runTitle, runStatus, runDetail, pct)
	}

	// Bitcoin / IBD profiles: never mark run=done while initialblockdownload or headers ahead.
	if strings.EqualFold(in.Network, "bitcoin") || in.IBD || hasHeaders {
		var pct any
		if in.VerifyPct > 0 {
			p := in.VerifyPct * 100
			if p > 100 {
				p = 100
			}
			pct = float64(int(p*10+0.5)) / 10
		}
		synced := in.RPCOK && !in.IBD
		if synced && hasH && hasHeaders && hn+1 < headers {
			synced = false
		}
		runStatus := "pending"
		runDetail := "Node not online yet"
		switch {
		case !in.RPCOK:
			runStatus = "pending"
			runDetail = "Waiting for RPC"
		case in.Maintenance:
			runStatus = "active"
			runDetail = "RPC paused (maintenance)"
		case in.IBD || !synced:
			runStatus = "active"
			if hasH && hasHeaders {
				runDetail = fmt.Sprintf("IBD · blocks %d / headers %d", hn, headers)
			} else if hasH {
				runDetail = "IBD · height " + strconv.FormatInt(hn, 10)
			} else {
				runDetail = "Initial block download"
			}
			if pct != nil {
				runDetail = fmt.Sprintf("%s · %s%%", runDetail, formatSyncPct(pct.(float64)))
			}
			if in.Peers > 0 {
				runDetail = fmt.Sprintf("%s · peers %d", runDetail, in.Peers)
			}
			if in.SizeOnDisk > 0 {
				runDetail = fmt.Sprintf("%s · %.1f GiB", runDetail, float64(in.SizeOnDisk)/(1024*1024*1024))
			}
		default:
			runStatus = "done"
			if hasH {
				runDetail = "Synced · height " + strconv.FormatInt(hn, 10)
			} else {
				runDetail = "Synced · RPC online · IBD complete"
			}
			if in.Peers > 0 {
				runDetail = fmt.Sprintf("%s · peers %d", runDetail, in.Peers)
			}
			pct = float64(100)
		}
		return lifecycleStep("run", runTitle, runStatus, runDetail, pct)
	}

	// Generic path (etc/cardano/… after eth_syncing=false): trust explicit IBD.
	// ❌ Never use hn<1000 — synced ETC with Height=0 (eth_syncing clears current)
	// stayed "Syncing · height 0" forever while verification_pct=100 / SYNCED UI.
	runStatus := "pending"
	runDetail := "Node not online yet"
	switch {
	case !in.RPCOK:
		runStatus = "pending"
		runDetail = "Waiting for RPC"
	case in.Maintenance:
		runStatus = "active"
		runDetail = "RPC paused (maintenance)"
	case in.IBD:
		runStatus = "active"
		if hasH {
			runDetail = "Syncing · height " + strconv.FormatInt(hn, 10)
		} else {
			runDetail = "RPC up · syncing with network"
		}
		if in.VerifyPct > 0 {
			p := in.VerifyPct * 100
			if p > 100 {
				p = 100
			}
			pct := float64(int(p*10+0.5)) / 10
			runDetail = fmt.Sprintf("%s · %s%%", runDetail, formatSyncPct(pct))
			return lifecycleStep("run", "Run", runStatus, runDetail, pct)
		}
	default:
		runStatus = "done"
		if hasH && hn > 0 {
			runDetail = "Healthy · height " + strconv.FormatInt(hn, 10)
		} else {
			runDetail = "Healthy · RPC online"
		}
	}
	return lifecycleStep("run", "Run", runStatus, runDetail, nil)
}

func stepStatus(m map[string]any) string {
	s, _ := m["status"].(string)
	return s
}

// stepComplete — prior step clears the gate for the next one.
// skipped counts only for optional steps (explicitly not required).
func stepComplete(m map[string]any) bool {
	st := stepStatus(m)
	if st == "done" {
		return true
	}
	if st == "skipped" {
		opt, _ := m["optional"].(bool)
		req, _ := m["required"].(bool)
		return opt && !req
	}
	return false
}

// applySequentialGate — step N may be active only if all previous required steps are complete.
// Otherwise force pending (never lie that Start is active while Snapshot is unfinished).
func applySequentialGate(steps []map[string]any) {
	blocked := false
	for _, s := range steps {
		st := stepStatus(s)
		if blocked {
			if st == "active" || st == "done" {
				// Don't rewrite a later done if somehow true — but start/run done while
				// snapshot incomplete is dishonest; force pending.
				id, _ := s["id"].(string)
				if id == "start" || id == "run" || id == "snapshot" {
					s["status"] = "pending"
					s["active"] = false
					s["done"] = false
					s["error"] = false
					if id == "start" && (s["detail"] == "" || strings.Contains(strings.ToLower(fmt.Sprint(s["detail"])), "start")) {
						s["detail"] = "Waiting for previous step"
					}
					if id == "run" {
						s["detail"] = "Waiting for previous step"
					}
				}
			} else if st != "error" {
				s["status"] = "pending"
				s["active"] = false
				s["done"] = false
			}
			continue
		}
		if st == "error" {
			blocked = true
			continue
		}
		if !stepComplete(s) {
			// Current incomplete step is the head; later ones stay pending.
			blocked = true
		}
	}
}

// lifecyclePaceMinDwell — each paced step stays active at least this long before
// advancing. Collect interval is often 2s; without dwell, regtest reaches Healthy
// in ~4s and NODE SETUP never reads as a real walk.
var lifecyclePaceMinDwell = 5 * time.Second

// ensureLifecyclePaceEpoch — one-time after Update. Bump epoch and always clear
// step ACK (keep Auto pipeline fields). Old StartedAt+done stamps otherwise skip
// dwell and collapse to Healthy again.
func ensureLifecyclePaceEpoch(prog *lifecycleProgress) {
	if prog == nil {
		return
	}
	if prog.PaceEpoch >= lifecyclePaceEpoch {
		return
	}
	prog.PaceEpoch = lifecyclePaceEpoch
	prog.Steps = map[string]stepProgress{}
}

// paceLifecycleCompletions — when reality already marks every step done in one
// collect tick (typical regtest after Confirm ports), walk pending→active→done
// with a minimum dwell so NODE SETUP is visible. Steady healthy (all ACK'd in
// progress) is left alone. Errors / in-progress IBD are not paced.
func paceLifecycleCompletions(steps []map[string]any, prog *lifecycleProgress) {
	if prog == nil || len(steps) == 0 {
		return
	}
	ensureLifecyclePaceEpoch(prog)
	for _, s := range steps {
		st := stepStatus(s)
		if st == "error" {
			return
		}
		if st != "done" && st != "skipped" {
			return
		}
	}
	allACK := true
	for _, s := range steps {
		id, _ := s["id"].(string)
		prev := prog.Steps[id]
		if prev.Status != "done" && prev.Status != "skipped" {
			allACK = false
			break
		}
	}
	if allACK {
		// Older agents stamped every step done in ≤2s → permanent Healthy with no
		// visible NODE SETUP. One-time repair: clear and re-walk.
		if !lifecyclePaceLooksInstantCollapse(prog, steps) {
			return
		}
		for _, s := range steps {
			id, _ := s["id"].(string)
			if id != "" {
				delete(prog.Steps, id)
			}
		}
	} else if !lifecyclePaceWalkStarted(prog, steps) {
		// ports/install often ACK as done *before* run becomes reality-done (RPC
		// lag). Without a reset, pace only touches the last step → instant Healthy.
		for _, s := range steps {
			id, _ := s["id"].(string)
			if id == "" {
				continue
			}
			delete(prog.Steps, id)
		}
	}
	now := time.Now().UTC()
	forcePendingFrom := func(idx int) {
		for _, s := range steps[idx:] {
			s["status"] = "pending"
			s["active"] = false
			s["done"] = false
			s["error"] = false
		}
	}
	for i, s := range steps {
		id, _ := s["id"].(string)
		if id == "" {
			continue
		}
		prev := prog.Steps[id]
		if prev.Status == "done" || prev.Status == "skipped" {
			continue
		}
		if prev.Status == "active" || prev.StartedAt != "" {
			if prev.StartedAt != "" {
				if t, err := time.Parse(time.RFC3339, prev.StartedAt); err == nil && now.Sub(t) < lifecyclePaceMinDwell {
					s["status"] = "active"
					s["active"] = true
					s["done"] = false
					s["error"] = false
					forcePendingFrom(i + 1)
					return
				}
			}
			// Dwell satisfied → allow done this tick; activate the next.
			if i+1 < len(steps) {
				n := steps[i+1]
				n["status"] = "active"
				n["active"] = true
				n["done"] = false
				n["error"] = false
				forcePendingFrom(i + 2)
			}
			return
		}
		// First unacked step — show active for one dwell window.
		s["status"] = "active"
		s["active"] = true
		s["done"] = false
		s["error"] = false
		forcePendingFrom(i + 1)
		return
	}
}

// lifecyclePaceWalkStarted — true once a paced step was held active for the
// minimum dwell (or is currently active). Same-second done stamps do not count.
func lifecyclePaceWalkStarted(prog *lifecycleProgress, steps []map[string]any) bool {
	if prog == nil {
		return false
	}
	for _, s := range steps {
		id, _ := s["id"].(string)
		if id == "" {
			continue
		}
		prev := prog.Steps[id]
		if prev.Status == "active" {
			return true
		}
		if prev.StartedAt == "" {
			continue
		}
		if prev.Status != "done" && prev.Status != "skipped" {
			return true
		}
		st, e1 := time.Parse(time.RFC3339, prev.StartedAt)
		if e1 != nil {
			continue
		}
		if prev.FinishedAt == "" {
			return true
		}
		ft, e2 := time.Parse(time.RFC3339, prev.FinishedAt)
		if e2 != nil {
			continue
		}
		if ft.Sub(st) >= lifecyclePaceMinDwell {
			return true
		}
	}
	return false
}

// lifecyclePaceLooksInstantCollapse — every required step ACK'd done with dwell
// shorter than MinDwell (typical regtest bug: Healthy in ~8s).
func lifecyclePaceLooksInstantCollapse(prog *lifecycleProgress, steps []map[string]any) bool {
	if prog == nil || len(steps) == 0 {
		return false
	}
	sawDone := false
	for _, s := range steps {
		id, _ := s["id"].(string)
		if id == "" {
			continue
		}
		prev := prog.Steps[id]
		if prev.Status == "skipped" {
			continue
		}
		if prev.Status != "done" {
			return false
		}
		sawDone = true
		if prev.StartedAt == "" || prev.FinishedAt == "" {
			return true
		}
		st, e1 := time.Parse(time.RFC3339, prev.StartedAt)
		ft, e2 := time.Parse(time.RFC3339, prev.FinishedAt)
		if e1 != nil || e2 != nil {
			return true
		}
		if ft.Sub(st) >= lifecyclePaceMinDwell {
			return false
		}
	}
	return sawDone
}

func mergeProgressTimestamps(steps []map[string]any, prog *lifecycleProgress) {
	if prog == nil {
		return
	}
	now := time.Now().UTC().Format(time.RFC3339)
	for _, s := range steps {
		id, _ := s["id"].(string)
		if id == "" {
			continue
		}
		prev := prog.Steps[id]
		st := stepStatus(s)
		switch st {
		case "active":
			if prev.StartedAt == "" {
				prev.StartedAt = now
			}
			s["started_at"] = prev.StartedAt
		case "done":
			if prev.StartedAt == "" {
				prev.StartedAt = now
			}
			s["started_at"] = prev.StartedAt
			if prev.FinishedAt == "" || prev.Status != "done" {
				prev.FinishedAt = now
			}
			s["finished_at"] = prev.FinishedAt
		case "skipped":
			if prev.FinishedAt == "" {
				prev.FinishedAt = now
			}
			s["finished_at"] = prev.FinishedAt
		}
		if prev.StartedAt != "" {
			if _, ok := s["started_at"]; !ok {
				s["started_at"] = prev.StartedAt
			}
		}
		if prev.FinishedAt != "" && (st == "done" || st == "skipped") {
			s["finished_at"] = prev.FinishedAt
		}
		prev.Status = st
		if d, ok := s["detail"].(string); ok {
			prev.Detail = d
		}
		if prog.Steps == nil {
			prog.Steps = map[string]stepProgress{}
		}
		prog.Steps[id] = prev
	}
}

// buildNodeLifecycle is the system-agent source of truth for UI/steps/gating:
// ports → install → [profile ExtraSteps…] → start → run.
// Sequential gate ensures skipped ≠ done and start/run never look active while
// a required prior step (e.g. mainnet snapshot) is unfinished.
func buildNodeLifecycle(in nodeLifecycleInput) map[string]any {
	profile := resolveLifecycleProfile(in)
	// SnapRequired from profile wins for gating (collect may not set it).
	in.SnapRequired = profile.SnapshotRequired

	np := LookupNetworkProfile(profile.Network, profile.Env)

	portsStep := buildPortsStep(in)
	installStep := buildInstallStep(in)

	var snapStep map[string]any
	if profile.IncludeSnapshot {
		snapStep = buildSnapshotStep(in, profile)
	}

	snapStatus := ""
	if snapStep != nil {
		snapStatus = stepStatus(snapStep)
	}
	startStep := buildStartStep(in, profile, snapStatus)
	runStep := buildRunStep(in)

	// Common base + catalog ExtraSteps + start/run (no env-name switches).
	steps := []map[string]any{portsStep, installStep}
	extras := np.ExtraSteps
	if len(extras) == 0 && profile.IncludeSnapshot {
		extras = []string{StepSnapshot}
	}
	for _, id := range extras {
		if id == StepSnapshot && snapStep != nil {
			steps = append(steps, snapStep)
		}
	}
	steps = append(steps, startStep, runStep)

	applySequentialGate(steps)
	// Fast networks (regtest) can make every step done in one collect tick —
	// pace so panel paints ports→install→start→run instead of instant Healthy.
	paceLifecycleCompletions(steps, in.Progress)
	mergeProgressTimestamps(steps, in.Progress)

	// Re-read after gate (start/run may have been forced pending).
	if snapStep != nil {
		snapStatus = stepStatus(snapStep)
	}
	startStatus := stepStatus(startStep)
	runStatus := stepStatus(runStep)

	snapPct := any(nil)
	if snapStep != nil {
		snapPct = snapStep["pct"]
	}

	phase := "ports"
	label := "Ports"
	detail, _ := portsStep["detail"].(string)
	busy := true
	nodeStatus := "not_started"
	var overallPct any

	hn, hasH := heightNum(in.Height)

	portsDone := stepComplete(portsStep)
	installDone := stepComplete(installStep)

	switch {
	case in.Maintenance:
		phase = "error"
		label = "Paused"
		detail = "RPC paused"
		busy = false
		nodeStatus = "paused"
	case profile.IncludeSnapshot && in.SnapFailed && profile.SnapshotRequired:
		phase = "error"
		label = "Snapshot error"
		if snapStep != nil {
			detail, _ = snapStep["detail"].(string)
		}
		busy = false
		nodeStatus = "snapshot_error"
		overallPct = snapPct
	case !portsDone:
		phase = "ports"
		label = "Ports"
		detail, _ = portsStep["detail"].(string)
		busy = true
		nodeStatus = "awaiting_ports"
	case !installDone:
		phase = "install"
		label = "Install"
		detail, _ = installStep["detail"].(string)
		busy = true
		nodeStatus = "installing"
	case profile.IncludeSnapshot && snapStatus != "done" && snapStatus != "skipped":
		phase = "snapshot"
		overallPct = snapPct
		if snapStatus == "error" {
			label = "Snapshot error"
			detail, _ = snapStep["detail"].(string)
			busy = false
			nodeStatus = "snapshot_error"
		} else if in.SnapBusy || snapStatus == "active" {
			label = "Snapshot"
			if p, ok := snapPct.(float64); ok {
				label = "Snapshot " + strconv.FormatFloat(p, 'f', 0, 64) + "%"
			}
			detail, _ = snapStep["detail"].(string)
			busy = true
			nodeStatus = "snapshot_download"
			if strings.EqualFold(in.SnapPhase, "extract") || strings.EqualFold(in.SnapPhase, "extracting") {
				nodeStatus = "snapshot_extract"
			}
		} else {
			label = "Awaiting snapshot"
			detail, _ = snapStep["detail"].(string)
			busy = false
			nodeStatus = "needs_snapshot"
		}
	case startStatus == "error":
		phase = "error"
		label = "Start error"
		detail, _ = startStep["detail"].(string)
		busy = false
		nodeStatus = "start_error"
	case startStatus != "done":
		phase = "start"
		label = "Starting"
		detail, _ = startStep["detail"].(string)
		busy = startStatus == "active"
		nodeStatus = "starting"
		if startStatus == "pending" {
			label = "Start"
			nodeStatus = "ready_to_start"
			busy = false
		}
		// Bootstrap / warmup may already expose sync pct (HL applied block, nitro download).
		if p := runStep["pct"]; p != nil {
			overallPct = p
		}
	case runStatus != "done":
		phase = "run"
		label = "Syncing"
		if title, _ := runStep["title"].(string); title != "" {
			label = title
		}
		detail, _ = runStep["detail"].(string)
		busy = true
		nodeStatus = "syncing"
		if p := runStep["pct"]; p != nil {
			overallPct = p
		}
	default:
		phase = "healthy"
		label = "Running"
		detail, _ = runStep["detail"].(string)
		busy = false
		nodeStatus = "running"
		if p := runStep["pct"]; p != nil {
			overallPct = p
		}
	}

	// Current step = first non-complete.
	current := ""
	for _, s := range steps {
		if !stepComplete(s) {
			current, _ = s["id"].(string)
			break
		}
	}
	if current == "" && len(steps) > 0 {
		current, _ = steps[len(steps)-1]["id"].(string)
	}

	if in.Progress != nil {
		in.Progress.Current = current
		in.Progress.UpdatedAt = time.Now().UTC().Format(time.RFC3339)
	}

	stepIDs := make([]string, 0, len(steps))
	for _, s := range steps {
		if id, ok := s["id"].(string); ok {
			stepIDs = append(stepIDs, id)
		}
	}

	supportedSteps := np.SupportedLifecycleSteps()
	capabilities := np.LifecycleCapabilities()

	out := map[string]any{
		"phase":           phase,
		"label":           label,
		"detail":          detail,
		"busy":            busy,
		"node_status":     nodeStatus,
		"current":         current,
		"steps":           steps,
		"complete":        phase == "healthy",
		"supported_steps": supportedSteps,
		"capabilities":    capabilities,
		"profile": map[string]any{
			"id":                np.ID,
			"network":           profile.Network,
			"env":               profile.Env,
			"display_name":      np.DisplayName,
			"include_snapshot":  profile.IncludeSnapshot,
			"snapshot_required": profile.SnapshotRequired,
			"auto_snapshot":     profile.AutoSnapshot,
			"auto_start_node":   profile.AutoStartNode,
			"extra_steps":       np.ExtraSteps,
			"service_prefix":    np.ServicePrefix,
			"node_binary":       np.NodeBinaryHint,
			"step_ids":          stepIDs,
			"supported_steps":   supportedSteps,
			"capabilities":      capabilities,
		},
	}
	if overallPct != nil {
		out["pct"] = overallPct
	}
	if hasH {
		out["height"] = hn
	}
	return out
}

func lifecycleDone(lifecycle map[string]any) bool {
	phase, _ := lifecycle["phase"].(string)
	if strings.EqualFold(phase, "healthy") {
		return true
	}
	if c, ok := lifecycle["complete"].(bool); ok {
		return c
	}
	return false
}
