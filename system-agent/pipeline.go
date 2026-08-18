package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// lifecyclePaceEpoch — bump when pace semantics change so leaf agents re-walk
// NODE SETUP once after Update (clears instant-Healthy ACK from older builds).
const lifecyclePaceEpoch = 3

// lifecycleProgress — persisted agent-driven pipeline state (survives restarts).
type lifecycleProgress struct {
	Current   string                  `json:"current,omitempty"`
	UpdatedAt string                  `json:"updated_at,omitempty"`
	PaceEpoch int                     `json:"pace_epoch,omitempty"`
	Steps     map[string]stepProgress `json:"steps,omitempty"`
	Auto      autoPipelineState       `json:"auto"`
}

type stepProgress struct {
	Status     string `json:"status,omitempty"`
	Detail     string `json:"detail,omitempty"`
	StartedAt  string `json:"started_at,omitempty"`
	FinishedAt string `json:"finished_at,omitempty"`
}

type autoPipelineState struct {
	SnapshotStartedAt string `json:"snapshot_started_at,omitempty"`
	NodeStartedAt     string `json:"node_started_at,omitempty"`
	LastAction        string `json:"last_action,omitempty"`
	LastError         string `json:"last_error,omitempty"`
	LastAttemptAt     string `json:"last_attempt_at,omitempty"`
	Attempts          int    `json:"attempts,omitempty"`
}

const pipelineBackoff = 45 * time.Second

func lifecycleProgressPath(cfg Config) string {
	return filepath.Join(filepath.Dir(cfg.StateFile), "lifecycle-progress.json")
}

func loadLifecycleProgress(cfg Config) *lifecycleProgress {
	doc := readJSONFile(lifecycleProgressPath(cfg))
	if doc == nil {
		return &lifecycleProgress{Steps: map[string]stepProgress{}}
	}
	p := &lifecycleProgress{Steps: map[string]stepProgress{}}
	p.Current, _ = doc["current"].(string)
	p.UpdatedAt, _ = doc["updated_at"].(string)
	if n, ok := doc["pace_epoch"].(float64); ok {
		p.PaceEpoch = int(n)
	}
	if auto, ok := doc["auto"].(map[string]any); ok {
		p.Auto.SnapshotStartedAt, _ = auto["snapshot_started_at"].(string)
		p.Auto.NodeStartedAt, _ = auto["node_started_at"].(string)
		p.Auto.LastAction, _ = auto["last_action"].(string)
		p.Auto.LastError, _ = auto["last_error"].(string)
		p.Auto.LastAttemptAt, _ = auto["last_attempt_at"].(string)
		if n, ok := auto["attempts"].(float64); ok {
			p.Auto.Attempts = int(n)
		}
	}
	if steps, ok := doc["steps"].(map[string]any); ok {
		for id, raw := range steps {
			m, ok := raw.(map[string]any)
			if !ok {
				continue
			}
			sp := stepProgress{}
			sp.Status, _ = m["status"].(string)
			sp.Detail, _ = m["detail"].(string)
			sp.StartedAt, _ = m["started_at"].(string)
			sp.FinishedAt, _ = m["finished_at"].(string)
			p.Steps[id] = sp
		}
	}
	return p
}

func saveLifecycleProgress(cfg Config, p *lifecycleProgress) {
	if p == nil {
		return
	}
	path := lifecycleProgressPath(cfg)
	_ = ensureDir(filepath.Dir(path))
	steps := map[string]any{}
	for id, sp := range p.Steps {
		steps[id] = map[string]any{
			"status":      sp.Status,
			"detail":      sp.Detail,
			"started_at":  sp.StartedAt,
			"finished_at": sp.FinishedAt,
		}
	}
	doc := map[string]any{
		"current":    p.Current,
		"updated_at": time.Now().UTC().Format(time.RFC3339),
		"pace_epoch": p.PaceEpoch,
		"steps":      steps,
		"auto": map[string]any{
			"snapshot_started_at": p.Auto.SnapshotStartedAt,
			"node_started_at":     p.Auto.NodeStartedAt,
			"last_action":         p.Auto.LastAction,
			"last_error":          p.Auto.LastError,
			"last_attempt_at":     p.Auto.LastAttemptAt,
			"attempts":            p.Auto.Attempts,
		},
	}
	if err := writeJSONFile(path, doc); err != nil {
		log.Printf("lifecycle progress write: %v", err)
	}
}

// LifecyclePipeline — agent-driven auto chain for TRON (and future networks).
type LifecyclePipeline struct {
	cfg  Config
	snap *SnapshotController
	mu   sync.Mutex
}

func newLifecyclePipeline(cfg Config, snap *SnapshotController) *LifecyclePipeline {
	return &LifecyclePipeline{cfg: cfg, snap: snap}
}

func (p *LifecyclePipeline) backoffOK(prog *lifecycleProgress) bool {
	if prog.Auto.LastAttemptAt == "" {
		return true
	}
	t, err := time.Parse(time.RFC3339, prog.Auto.LastAttemptAt)
	if err != nil {
		return true
	}
	return time.Since(t) >= pipelineBackoff
}

func (p *LifecyclePipeline) noteAttempt(prog *lifecycleProgress, action, errMsg string) {
	prog.Auto.LastAction = action
	prog.Auto.LastAttemptAt = time.Now().UTC().Format(time.RFC3339)
	prog.Auto.Attempts++
	prog.Auto.LastError = errMsg
}

// Tick advances the agent-driven pipeline after collect builds lifecycle.
// Backoff between attempts is pipelineBackoff; progress is flushed to disk each tick.
func (p *LifecyclePipeline) Tick(st map[string]any) {
	p.mu.Lock()
	defer p.mu.Unlock()

	lc, _ := st["lifecycle"].(map[string]any)
	if lc == nil {
		return
	}
	prof, _ := lc["profile"].(map[string]any)
	if prof == nil {
		return
	}
	if maint, _ := st["maintenance"].(map[string]any); truthy(maint["enabled"]) {
		return
	}

	prog := loadLifecycleProgress(p.cfg)
	// Sync step timestamps from emitted lifecycle.
	if steps, ok := lc["steps"].([]map[string]any); ok {
		mergeProgressTimestamps(steps, prog)
	} else if raw, ok := lc["steps"].([]any); ok {
		converted := make([]map[string]any, 0, len(raw))
		for _, item := range raw {
			if m, ok := item.(map[string]any); ok {
				converted = append(converted, m)
			}
		}
		mergeProgressTimestamps(converted, prog)
	}
	if cur, _ := lc["current"].(string); cur != "" {
		prog.Current = cur
	}

	ns, _ := lc["node_status"].(string)
	phase, _ := lc["phase"].(string)
	snap, _ := st["snapshot"].(map[string]any)
	marker := truthy(snap["ready"])
	snapBusy := truthy(snap["wget_running"]) ||
		strings.EqualFold(fmt.Sprint(snap["phase"]), "download") ||
		strings.EqualFold(fmt.Sprint(snap["phase"]), "extract") ||
		strings.EqualFold(fmt.Sprint(snap["phase"]), "extracting")
	snapFailed := truthy(snap["failed"])
	nodeActive := false
	if svc, _ := st["services"].(map[string]any); svc != nil {
		nodeActive = strings.EqualFold(fmt.Sprint(svc["node"]), "active") ||
			strings.EqualFold(fmt.Sprint(svc["node"]), "activating")
	}
	if checks, _ := st["checks"].(map[string]any); checks != nil {
		if truthy(checks["java_tron_process"]) || truthy(checks["bitcoind_process"]) ||
			truthy(checks["geth_process"]) || truthy(checks["node_process_up"]) {
			nodeActive = true
		}
	}

	autoSnap, _ := prof["auto_snapshot"].(bool)
	autoNode, _ := prof["auto_start_node"].(bool)
	snapRequired, _ := prof["snapshot_required"].(bool)
	isBitcoin := strings.EqualFold(p.cfg.Network, "bitcoin") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "bitcoin")
	isSolana := strings.EqualFold(p.cfg.Network, "solana") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "solana")
	isEthereum := strings.EqualFold(p.cfg.Network, "ethereum") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "ethereum")
	isBSC := strings.EqualFold(p.cfg.Network, "bsc") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "bsc")
	isL2 := strings.EqualFold(p.cfg.Network, "hyperliquid") ||
		strings.EqualFold(p.cfg.Network, "arb") ||
		strings.EqualFold(p.cfg.Network, "robinhood") ||
		strings.EqualFold(p.cfg.Network, "optimism") ||
		strings.EqualFold(p.cfg.Network, "base") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "hyperliquid") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "arb") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "robinhood") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "optimism") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "base")
	isXRPL := strings.EqualFold(p.cfg.Network, "xrpl") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "xrpl")
	isDoge := strings.EqualFold(p.cfg.Network, "doge") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "doge")
	isCoreLike := networkIsCoreLikeSA(p.cfg.Network) ||
		networkIsCoreLikeSA(fmt.Sprint(prof["network"]))
	isCardano := strings.EqualFold(p.cfg.Network, "cardano") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "cardano")
	isStellar := strings.EqualFold(p.cfg.Network, "stellar") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "stellar")
	isTon := strings.EqualFold(p.cfg.Network, "ton") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "ton")
	isETC := strings.EqualFold(p.cfg.Network, "etc") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "etc")
	isZcash := strings.EqualFold(p.cfg.Network, "zcash") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "zcash")
	isSui := strings.EqualFold(p.cfg.Network, "sui") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "sui")
	isAptos := strings.EqualFold(p.cfg.Network, "aptos") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "aptos")
	isAvalanche := strings.EqualFold(p.cfg.Network, "avalanche") ||
		strings.EqualFold(fmt.Sprint(prof["network"]), "avalanche")
	diskOK := true
	if dg, _ := st["disk_gate"].(map[string]any); dg != nil {
		diskOK = truthy(dg["ok"])
	}
	// Drop FragmentPath-only last_error left by older agents / empty journal.
	if stripUnitPathNoise(prog.Auto.LastError) == "" {
		prog.Auto.LastError = ""
	}

	removing := removeJobPending(p.cfg.Network, p.cfg.Env)
	provisioning := provisionLockPending(p.cfg.Network, p.cfg.Env)

	// TON: dump apply OOMs if celldb cache / preload-all is huge. Cap + start.
	if isTon && !removing && !provisioning && tonBootstrapDone(p.cfg) {
		changed, err := healTonValidatorMemory()
		if err != nil {
			log.Printf("pipeline: ton celldb heal: %v", err)
		}
		if changed {
			if err := recycleTonValidator(); err != nil {
				log.Printf("pipeline: ton validator recycle after celldb heal: %v", err)
			} else {
				hostLogf("INFO", "system-agent", "start", "capped validator celldb cache + recycle")
			}
		} else if tonValidatorDown() {
			if err := nudgeTonValidatorStack(); err != nil {
				log.Printf("pipeline: ton validator start: %v", err)
			} else {
				hostLogf("INFO", "system-agent", "start", "validator down after dump — start")
			}
		}
	}

	// XRPL: hardcoded node_size=huge stalls LoadManager (seq=0 → FTL 90s) on <32 GiB RAM.
	// Never recycle the unit while tip remove/provision is in flight (enable+restart vs kill).
	if isXRPL && !removing && !provisioning {
		if healXRPLUnitGracefulStop(p.cfg) {
			hostLogf("INFO", "system-agent", "start", "healed %s ExecStop=-timeout server_stop", p.cfg.NodeService)
		}
		hasLedger := xrplStatusHasLedger(st) || xrplDatadirHasLedger(p.cfg.DataDir)
		xrplNoteFirstLedgerWait(p.cfg.DataDir, hasLedger)
		if !hasLedger {
			if pinned, err := healXRPLFirstLedgerBinary(p.cfg); err != nil {
				log.Printf("pipeline: xrpl catalog client: %v", err)
			} else if pinned {
				unit := p.cfg.NodeService
				if unit != "" && !strings.HasSuffix(unit, ".service") {
					unit += ".service"
				}
				if unit != "" {
					if err := recycleXRPLUnit(unit, p.cfg); err != nil {
						log.Printf("pipeline: xrpl recycle after catalog client: %v", err)
					} else {
						_, ver := xrplDebFromCatalog(p.cfg.Env)
						hostLogf("INFO", "system-agent", "start", "applied catalog xrpld %s", ver)
					}
				}
			}
		}
		rotated := false
		if !hasLedger || xrplShouldHealStateDB(p.cfg.DataDir, p.cfg.NodeService) {
			xrplPrepareDatadirHeal(p.cfg)
		}
		if !hasLedger {
			var err error
			rotated, err = xrplReinitStaleNuDB(p.cfg.DataDir)
			if err != nil {
				log.Printf("pipeline: xrpl nudb reinit: %v", err)
			} else if rotated {
				hostLogf("INFO", "system-agent", "start", "reinit empty NuDB after failed first acquire")
				log.Printf("pipeline: reinit stale NuDB under %s", p.cfg.DataDir)
			}
		}
		if !rotated && xrplShouldHealStateDB(p.cfg.DataDir, p.cfg.NodeService) {
			ok, err := xrplHealCorruptStateDB(p.cfg.DataDir, p.cfg.NodeService)
			if err != nil {
				log.Printf("pipeline: xrpl state-db reinit: %v", err)
			} else if ok {
				rotated = true
				hostLogf("INFO", "system-agent", "start", "reinit NuDB after SHAMapStore state db error")
				log.Printf("pipeline: reinit corrupt SHAMapStore NuDB under %s", p.cfg.DataDir)
			}
		}
		conf := filepath.Join(p.cfg.EtcDir, "xrpld.cfg")
		changed, err := healXRPLCfgFile(conf, p.cfg.Env, hasLedger)
		if err != nil {
			log.Printf("pipeline: xrpl cfg heal: %v", err)
		}
		unit := p.cfg.NodeService
		if unit != "" && !strings.HasSuffix(unit, ".service") {
			unit += ".service"
		}
		if changed || rotated {
			if unit != "" {
				if err := recycleXRPLUnit(unit, p.cfg); err != nil {
					log.Printf("pipeline: xrpl recycle after cfg heal: %v", err)
				}
			}
			hostLogf("INFO", "system-agent", "start", "healed xrpld.cfg + recycle %s", unit)
			log.Printf("pipeline: healed %s changed=%v rotated=%v recycled %s", conf, changed, rotated, unit)
		} else if unit != "" && systemdUnitInstalled(unit) && fileExists(conf) && xrplUnitDown(p.cfg) {
			if err := recycleXRPLUnit(unit, p.cfg); err != nil {
				log.Printf("pipeline: xrpl start after dead server_stop: %v", err)
			} else {
				hostLogf("INFO", "system-agent", "start", "xrpld down (stale server_stop) — start")
			}
		} else if !hasLedger && unit != "" && systemdUnitInstalled(unit) &&
			xrplUnitHasLoadStall(unit) && xrplCooldownReady(p.cfg.DataDir, ".load-stall-recycle", 12*time.Minute) {
			xrplMarkCooldown(p.cfg.DataDir, ".load-stall-recycle", "LoadManager stall while seq=0\n")
			if err := recycleXRPLUnit(unit, p.cfg); err != nil {
				log.Printf("pipeline: xrpl recycle after LoadManager stall: %v", err)
			} else {
				hostLogf("INFO", "system-agent", "start", "LoadManager stall · seq=0 — recycle medium")
			}
		}
	}

	// Self-heal: confirmed public_port (Go RPC) must listen — agent API alone is not enough.
	if !removing && (isBitcoin || isSolana || isEthereum || isBSC || isL2 || isXRPL || isDoge || isCoreLike || isCardano || isStellar || isTon || isETC || isZcash || isSui || isAptos || isAvalanche) &&
		p.cfg.PublicRPCPort() > 0 && p.cfg.AgentAPIPort() > 0 &&
		p.cfg.PublicRPCPort() != p.cfg.AgentAPIPort() &&
		!portOpen("127.0.0.1", p.cfg.PublicRPCPort()) && p.backoffOK(prog) {
		if err := ensurePerNodeAPIAgent(p.cfg); err != nil {
			p.noteAttempt(prog, "go_rpc", err.Error())
			log.Printf("pipeline: ensure Go RPC :%d: %v", p.cfg.PublicRPCPort(), err)
		} else {
			p.noteAttempt(prog, "go_rpc", "")
			log.Printf("pipeline: restarted per-node api-agent for Go RPC :%d", p.cfg.PublicRPCPort())
		}
	}

	// Disk recovered after a prior insufficient-disk failure → allow auto retry.
	if snapFailed && p.snap != nil && p.snap.ClearDiskErrorIfRecovered() {
		snapFailed = false
		if m, ok := st["snapshot"].(map[string]any); ok {
			m["failed"] = false
			m["error"] = ""
			m["phase"] = "idle"
			m["detail"] = "disk space recovered — ready to retry snapshot"
			st["snapshot"] = m
		}
	}

	// 1) After install: auto-start snapshot when required and not ready.
	if autoSnap && !removing && snapRequired && !marker && !snapBusy && !snapFailed &&
		(phase == "snapshot" || ns == "needs_snapshot") {
		if p.backoffOK(prog) {
			var err error
			if strings.EqualFold(p.cfg.Network, "robinhood") {
				// Must rewrite unit (--init.url + TRON_SNAPSHOT_ENABLED=1) via api-agent;
				// oneshot alone starts a stale unit and Sync % never appears.
				err = startRobinhoodViaAPIAgent(p.cfg)
			} else {
				err = p.snap.Start()
			}
			if err != nil {
				msg := err.Error()
				// "already running/ready" is fine — not an error for pipeline.
				if strings.Contains(msg, "already") {
					p.noteAttempt(prog, "snapshot_start", "")
					if prog.Auto.SnapshotStartedAt == "" {
						prog.Auto.SnapshotStartedAt = time.Now().UTC().Format(time.RFC3339)
					}
				} else {
					p.noteAttempt(prog, "snapshot_start", msg)
					hostLogf("ERROR", "system-agent", "snapshot", "%s/%s: %v", p.cfg.Network, p.cfg.Env, err)
					log.Printf("pipeline: auto snapshot start: %v", err)
				}
			} else {
				p.noteAttempt(prog, "snapshot_start", "")
				prog.Auto.SnapshotStartedAt = time.Now().UTC().Format(time.RFC3339)
				hostLogf("INFO", "system-agent", "snapshot", "start %s/%s", p.cfg.Network, p.cfg.Env)
				log.Printf("pipeline: auto-started snapshot for env=%s", p.cfg.Env)
			}
		}
	}

	// Snapshot in flight + java-tron already up = genesis IBD into the same datadir.
	// Stop the node; wget|tar cannot share output-directory with FullNode.
	if snapRequired && !marker && nodeActive &&
		(pipelineMayUseTronctl(p.cfg.Network) || strings.EqualFold(p.cfg.Network, "cardano")) {
		unit := p.cfg.NodeService
		if unit != "" && !strings.HasSuffix(unit, ".service") {
			unit += ".service"
		}
		if unit != "" {
			_ = exec.Command("systemctl", "stop", unit).Run()
			hostLogf("INFO", "system-agent", "start", "stop %s — snapshot not ready", unit)
			log.Printf("pipeline: stopped %s until snapshot marker exists", unit)
			nodeActive = false
		}
	}

	// 2) After snapshot done (or not required): auto-start chain node.
	// Bitcoin: also require disk_gate before IBD auto-start.
	// Retry on start_error so missing conf / crash-loop can self-heal (agent writes conf).
	wantStart := phase == "start" || phase == "error" ||
		ns == "ready_to_start" || ns == "starting" || ns == "start_error"
	if autoNode && !nodeActive && !snapBusy && !snapFailed && wantStart &&
		(!snapRequired || marker) && !removing {
		if (isBitcoin || isSolana || isEthereum || isBSC || isL2 || isXRPL || isDoge || isCoreLike || isCardano || isStellar || isTon || isETC || isZcash || isSui || isAptos || isAvalanche) && !diskOK {
			p.noteAttempt(prog, "disk_gate", "insufficient free disk for node start")
			prog.Auto.NodeStartedAt = ""
		} else if p.backoffOK(prog) {
			if err := p.startNode(); err != nil {
				p.noteAttempt(prog, "node_start", err.Error())
				prog.Auto.NodeStartedAt = ""
				hostLogf("ERROR", "system-agent", "start", "%s/%s: %v", p.cfg.Network, p.cfg.Env, err)
				log.Printf("pipeline: auto node start: %v", err)
			} else {
				p.noteAttempt(prog, "node_start", "")
				prog.Auto.NodeStartedAt = time.Now().UTC().Format(time.RFC3339)
				hostLogf("INFO", "system-agent", "start", "ok %s/%s unit=%s", p.cfg.Network, p.cfg.Env, p.cfg.NodeService)
				log.Printf("pipeline: auto-started node network=%s env=%s", p.cfg.Network, p.cfg.Env)
			}
		}
	}

	// Clear stale "node started" if marker missing and we somehow had it.
	if snapRequired && !marker {
		// Keep SnapshotStartedAt; do not claim node start.
		if ns == "needs_snapshot" || ns == "snapshot_download" || ns == "snapshot_extract" {
			// ok
		}
	}

	saveLifecycleProgress(p.cfg, prog)

	// Attach progress summary into lifecycle for API consumers.
	lc["progress"] = map[string]any{
		"current":    prog.Current,
		"updated_at": prog.UpdatedAt,
		"auto": map[string]any{
			"snapshot_started_at": prog.Auto.SnapshotStartedAt,
			"node_started_at":     prog.Auto.NodeStartedAt,
			"last_action":         prog.Auto.LastAction,
			"last_error":          prog.Auto.LastError,
			"last_attempt_at":     prog.Auto.LastAttemptAt,
			"attempts":            prog.Auto.Attempts,
		},
	}
	st["lifecycle"] = lc
}

func ensurePerNodeAPIAgent(cfg Config) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}
	env := cfg.Env
	if env == "" {
		env = "mainnet"
	}
	network := strings.ToLower(cfg.Network)
	if network == "" {
		network = "bitcoin"
	}
	apiUnit := fmt.Sprintf("rpcnode-api-agent-%s-%s.service", network, env)
	pub := cfg.PublicRPCPort()
	// Already listening — do not restart the unit that may be serving this HTTP call.
	if pub > 0 && portOpen("127.0.0.1", pub) {
		return nil
	}
	// NEVER stop/disable host tip (rpcnode-api-agent.service). Leaf ports are
	// distinct (doge/cardano/HL/…); killing tip caused panel remove connection refused.
	keepHostTipUnits(apiUnit)
	_ = exec.Command("systemctl", "daemon-reload").Run()
	_ = exec.Command("systemctl", "reset-failed", apiUnit).Run()
	// Only manage the leaf api-agent (Go RPC proxy). NEVER systemctl start/restart our
	// own rpcnode-system-agent-*-*.service from inside this process: during stop/update
	// is-active is not "active", start races systemd → start-limit-hit, collect dies,
	// panel Logs stay empty (seen on ltc/mainnet).
	_ = exec.Command("systemctl", "enable", apiUnit).Run()
	st := systemctlActive(strings.TrimSuffix(apiUnit, ".service"))
	if st == "active" || st == "activating" {
		// api-agent up but public port not open yet — soft restart only the proxy.
		if out, err := exec.Command("systemctl", "restart", apiUnit).CombinedOutput(); err != nil {
			return fmt.Errorf("restart %s: %v (%s)", apiUnit, err, strings.TrimSpace(string(out)))
		}
	} else if out, err := exec.Command("systemctl", "start", apiUnit).CombinedOutput(); err != nil {
		return fmt.Errorf("start %s: %v (%s)", apiUnit, err, strings.TrimSpace(string(out)))
	}
	deadline := time.Now().Add(10 * time.Second)
	for pub > 0 && time.Now().Before(deadline) {
		if portOpen("127.0.0.1", pub) {
			return nil
		}
		time.Sleep(400 * time.Millisecond)
	}
	if pub > 0 && !portOpen("127.0.0.1", pub) {
		return fmt.Errorf("Go RPC :%d still closed after %s", pub, apiUnit)
	}

	return nil
}

// keepHostTipUnits — leaf activate must never stop/disable tip bootstrap units.
func keepHostTipUnits(forLeafUnit string) {
	for _, u := range []string{"rpcnode-api-agent.service", "tron-api-agent.service", "rpcnode-system-agent.service", "tron-system-agent.service"} {
		if u == forLeafUnit {
			continue
		}
		active, _ := exec.Command("systemctl", "is-active", u).CombinedOutput()
		if strings.TrimSpace(string(active)) == "active" {
			log.Printf("pipeline: keeping host tip %s while activating %s", u, forLeafUnit)
		}
	}
}

func (p *LifecyclePipeline) startNode() error {
	unit := p.cfg.NodeService + ".service"
	isBitcoin := strings.EqualFold(p.cfg.Network, "bitcoin")
	isSolana := strings.EqualFold(p.cfg.Network, "solana")
	isEthereum := strings.EqualFold(p.cfg.Network, "ethereum")
	isBSC := strings.EqualFold(p.cfg.Network, "bsc")
	isHL := strings.EqualFold(p.cfg.Network, "hyperliquid")
	isArb := strings.EqualFold(p.cfg.Network, "arb")
	isRobinhood := strings.EqualFold(p.cfg.Network, "robinhood")
	isOP := strings.EqualFold(p.cfg.Network, "optimism")
	isBase := strings.EqualFold(p.cfg.Network, "base")
	isXRPL := strings.EqualFold(p.cfg.Network, "xrpl")
	isDoge := strings.EqualFold(p.cfg.Network, "doge")
	isCoreLike := networkIsCoreLikeSA(p.cfg.Network)
	isCardano := strings.EqualFold(p.cfg.Network, "cardano")
	isStellar := strings.EqualFold(p.cfg.Network, "stellar")
	isTon := strings.EqualFold(p.cfg.Network, "ton")
	isETC := strings.EqualFold(p.cfg.Network, "etc")
	isZcash := strings.EqualFold(p.cfg.Network, "zcash")
	isSui := strings.EqualFold(p.cfg.Network, "sui")
	isAptos := strings.EqualFold(p.cfg.Network, "aptos")
	isAvalanche := strings.EqualFold(p.cfg.Network, "avalanche")

	if isETC {
		bin := filepath.Join(p.cfg.OptDir, "bin", "geth")
		if !fileExists(bin) {
			return fmt.Errorf("core-geth binary missing under %s/bin — re-provision etc/%s", p.cfg.OptDir, p.cfg.Env)
		}
		if err := ensurePerNodeAPIAgent(p.cfg); err != nil {
			return fmt.Errorf("ensure Go RPC: %w", err)
		}
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			snippet := stripUnitPathNoise(journalUnitSnippet(unit, 12))
			msg := fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out)))
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		time.Sleep(900 * time.Millisecond)
		if etcProcessRunning(p.cfg) {
			log.Printf("pipeline: etc Core-Geth START via %s", unit)
			return nil
		}
		state := systemctlActive(p.cfg.NodeService)
		if systemctlFailed(p.cfg.NodeService) || state == "failed" {
			snippet := stripUnitPathNoise(journalUnitSnippet(unit, 12))
			msg := fmt.Sprintf("core-geth not running after start (state=%s)", state)
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		log.Printf("pipeline: etc START via %s state=%s", unit, state)
		return nil
	}

	if isTon {
		etc := p.cfg.EtcDir
		if etc == "" || !fileExists(filepath.Join(etc, "toolkit.env")) {
			return fmt.Errorf("ton toolkit.env missing — re-provision ton/%s", p.cfg.Env)
		}
		if err := ensurePerNodeAPIAgent(p.cfg); err != nil {
			return fmt.Errorf("ensure Go RPC: %w", err)
		}
		bootUnit := fmt.Sprintf("ton-%s-bootstrap.service", p.cfg.Env)
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "enable", bootUnit).Run()
		_ = exec.Command("systemctl", "reset-failed", bootUnit).Run()
		// Bootstrap is long (apt + MyTonCtrl dump) — never block the pipeline on it.
		if out, err := exec.Command("systemctl", "start", "--no-block", bootUnit).CombinedOutput(); err != nil {
			log.Printf("pipeline: ton bootstrap start: %v (%s)", err, strings.TrimSpace(string(out)))
		}
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		// ton-*.service is Type=oneshot RemainAfterExit — use start --no-block, not restart.
		// restart races nested systemctl (bootstrap) and agent Update → "signal: terminated".
		out, err := exec.Command("systemctl", "start", "--no-block", unit).CombinedOutput()
		if err != nil {
			msg := fmt.Sprintf("systemctl start %s: %v (%s)", unit, err, strings.TrimSpace(string(out)))
			// Transient: Update/SIGTERM during oneshot start — bootstrap may still be running.
			if strings.Contains(strings.ToLower(msg), "signal: terminated") ||
				strings.Contains(strings.ToLower(msg), "signal: killed") {
				if tonBootstrapActive(p.cfg) || tonBootstrapDone(p.cfg) {
					log.Printf("pipeline: ton start interrupted (%s) — bootstrap already underway", msg)
					return nil
				}
			}
			snippet := stripUnitPathNoise(journalUnitSnippet(unit, 12))
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		// Also nudge stock MyTonCtrl units when bootstrap already finished.
		// ❌ /usr/bin/ton/validator-engine is a build dir mid-dump — only marker or real binary.
		if fileExists(filepath.Join(etc, "bootstrap.done")) || tonValidatorEngineBin() != "" {
			if changed, err := healTonValidatorMemory(); err != nil {
				log.Printf("pipeline: ton celldb heal: %v", err)
			} else if changed {
				hostLogf("INFO", "system-agent", "start", "capped validator celldb cache + recycle")
				if err := recycleTonValidator(); err != nil {
					log.Printf("pipeline: ton validator recycle: %v", err)
				}
			}
			_ = exec.Command("systemctl", "start", "--no-block", "validator.service").Run()
			_ = exec.Command("systemctl", "start", "--no-block", "mytoncore.service").Run()
			for _, u := range []string{"ton-http-api.service", "ton_http_api.service"} {
				_ = exec.Command("systemctl", "start", "--no-block", u).Run()
			}
		}
		log.Printf("pipeline: ton START via %s (+ bootstrap/validator)", unit)
		return nil
	}

	if isStellar {
		rpcBin := findStellarRPCBin(p.cfg)
		if rpcBin == "" {
			return fmt.Errorf("stellar-rpc binary missing — re-provision stellar/%s", p.cfg.Env)
		}
		toml := filepath.Join(p.cfg.EtcDir, "stellar-rpc.toml")
		if !fileExists(toml) {
			return fmt.Errorf("stellar-rpc.toml missing at %s — re-provision", toml)
		}
		if _, err := ensureStellarFullHistoryToml(p.cfg.EtcDir); err != nil {
			log.Printf("pipeline: stellar full-history toml: %v", err)
		}
		if err := ensurePerNodeAPIAgent(p.cfg); err != nil {
			return fmt.Errorf("ensure Go RPC: %w", err)
		}
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			snippet := stripUnitPathNoise(journalUnitSnippet(unit, 12))
			msg := fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out)))
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		time.Sleep(1200 * time.Millisecond)
		if stellarProcessRunning(p.cfg) {
			log.Printf("pipeline: stellar-rpc START via %s", unit)
			return nil
		}
		state := systemctlActive(p.cfg.NodeService)
		if state == "activating" || state == "active" {
			log.Printf("pipeline: stellar-rpc START via %s state=%s", unit, state)
			return nil
		}
		if systemctlFailed(p.cfg.NodeService) || state == "failed" {
			snippet := stripUnitPathNoise(journalUnitSnippet(unit, 12))
			msg := fmt.Sprintf("stellar-rpc not running after start (state=%s)", state)
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		// inactive right after restart can be slow spawn — not a hard failure yet.
		log.Printf("pipeline: stellar-rpc START via %s state=%s (waiting for process)", unit, state)
		return nil
	}

	if isDoge {
		bin := filepath.Join(p.cfg.OptDir, "bin", "dogecoind")
		if !fileExists(bin) {
			if path, err := exec.LookPath("dogecoind"); err != nil || path == "" {
				return fmt.Errorf("dogecoind binary missing under %s/bin", p.cfg.OptDir)
			}
		}
		conf := filepath.Join(p.cfg.EtcDir, "dogecoin.conf")
		if !fileExists(conf) {
			return fmt.Errorf("dogecoin.conf missing at %s — re-provision", conf)
		}
		if err := ensurePerNodeAPIAgent(p.cfg); err != nil {
			return fmt.Errorf("ensure Go RPC: %w", err)
		}
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			snippet := journalUnitSnippet(unit, 12)
			msg := fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out)))
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s (conf=%s)", msg, conf)
		}
		time.Sleep(900 * time.Millisecond)
		procOK, _ := dogecoindRunningFor(p.cfg)
		if procOK {
			log.Printf("pipeline: dogecoind START via %s conf=%s", unit, conf)
			return nil
		}
		state := systemctlActive(p.cfg.NodeService)
		if systemctlFailed(p.cfg.NodeService) || state == "failed" || state == "inactive" || state == "dead" {
			snippet := journalUnitSnippet(unit, 12)
			msg := fmt.Sprintf("dogecoind not running after start (state=%s)", state)
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s (conf=%s)", msg, conf)
		}
		log.Printf("pipeline: dogecoind START via %s conf=%s state=%s", unit, conf, state)
		return nil
	}

	if isZcash {
		bin := filepath.Join(p.cfg.OptDir, "bin", "zebrad")
		if !fileExists(bin) {
			if path, err := exec.LookPath("zebrad"); err != nil || path == "" {
				return fmt.Errorf("zebrad binary missing under %s/bin — re-provision zcash/%s (zcashd is EOL)", p.cfg.OptDir, p.cfg.Env)
			}
		}
		conf := filepath.Join(p.cfg.EtcDir, "zebrad.toml")
		if !fileExists(conf) {
			return fmt.Errorf("zebrad.toml missing at %s — re-provision (zcashd EOL; do not use zcash.conf)", conf)
		}
		if err := ensurePerNodeAPIAgent(p.cfg); err != nil {
			return fmt.Errorf("ensure Go RPC: %w", err)
		}
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			snippet := stripUnitPathNoise(journalUnitSnippet(unit, 24))
			msg := fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out)))
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s (conf=%s)", msg, conf)
		}
		time.Sleep(1200 * time.Millisecond)
		procOK, _ := zcashdRunningFor(p.cfg)
		if procOK {
			log.Printf("pipeline: zebrad START via %s conf=%s", unit, conf)
			return nil
		}
		state := systemctlActive(p.cfg.NodeService)
		if state == "activating" || state == "active" {
			log.Printf("pipeline: zebrad START via %s conf=%s state=%s", unit, conf, state)
			return nil
		}
		if systemctlFailed(p.cfg.NodeService) || state == "failed" || state == "inactive" || state == "dead" {
			snippet := stripUnitPathNoise(journalUnitSnippet(unit, 24))
			msg := fmt.Sprintf("zebrad not running after start (state=%s)", state)
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s (conf=%s)", msg, conf)
		}
		log.Printf("pipeline: zebrad START via %s conf=%s state=%s", unit, conf, state)
		return nil
	}

	if isSui {
		bin := filepath.Join(p.cfg.OptDir, "bin", "sui-node")
		if !fileExists(bin) {
			if path, err := exec.LookPath("sui-node"); err != nil || path == "" {
				return fmt.Errorf("sui-node binary missing under %s/bin — re-provision sui/%s", p.cfg.OptDir, p.cfg.Env)
			}
		}
		yaml := filepath.Join(p.cfg.EtcDir, "fullnode.yaml")
		if !fileExists(yaml) {
			return fmt.Errorf("fullnode.yaml missing at %s — re-provision", yaml)
		}
		if err := ensurePerNodeAPIAgent(p.cfg); err != nil {
			return fmt.Errorf("ensure Go RPC: %w", err)
		}
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			snippet := stripUnitPathNoise(journalUnitSnippet(unit, 12))
			msg := fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out)))
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		time.Sleep(1200 * time.Millisecond)
		procOK, _ := suiNodeRunningFor(p.cfg)
		if procOK {
			log.Printf("pipeline: sui-node START via %s", unit)
			return nil
		}
		state := systemctlActive(p.cfg.NodeService)
		if state == "activating" || state == "active" {
			log.Printf("pipeline: sui-node START via %s state=%s", unit, state)
			return nil
		}
		if systemctlFailed(p.cfg.NodeService) || state == "failed" {
			snippet := stripUnitPathNoise(journalUnitSnippet(unit, 12))
			msg := fmt.Sprintf("sui-node not running after start (state=%s)", state)
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		log.Printf("pipeline: sui-node START via %s state=%s (waiting for process)", unit, state)
		return nil
	}

	if isAptos {
		bin := filepath.Join(p.cfg.OptDir, "bin", "aptos-node")
		if !fileExists(bin) {
			if path, err := exec.LookPath("aptos-node"); err != nil || path == "" {
				return fmt.Errorf("aptos-node binary missing under %s/bin — re-provision aptos/%s", p.cfg.OptDir, p.cfg.Env)
			}
		}
		yaml := filepath.Join(p.cfg.EtcDir, "fullnode.yaml")
		if !fileExists(yaml) {
			return fmt.Errorf("fullnode.yaml missing at %s — re-provision", yaml)
		}
		if err := ensurePerNodeAPIAgent(p.cfg); err != nil {
			return fmt.Errorf("ensure Go RPC: %w", err)
		}
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			snippet := stripUnitPathNoise(journalUnitSnippet(unit, 12))
			msg := fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out)))
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		time.Sleep(1200 * time.Millisecond)
		procOK, _ := aptosNodeRunningFor(p.cfg)
		if procOK {
			log.Printf("pipeline: aptos-node START via %s", unit)
			return nil
		}
		state := systemctlActive(p.cfg.NodeService)
		if state == "activating" || state == "active" {
			log.Printf("pipeline: aptos-node START via %s state=%s", unit, state)
			return nil
		}
		if systemctlFailed(p.cfg.NodeService) || state == "failed" {
			snippet := stripUnitPathNoise(journalUnitSnippet(unit, 12))
			msg := fmt.Sprintf("aptos-node not running after start (state=%s)", state)
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		log.Printf("pipeline: aptos-node START via %s state=%s (waiting for process)", unit, state)
		return nil
	}

	if isAvalanche {
		bin := filepath.Join(p.cfg.OptDir, "bin", "avalanchego")
		if !fileExists(bin) {
			if path, err := exec.LookPath("avalanchego"); err != nil || path == "" {
				return fmt.Errorf("avalanchego binary missing under %s/bin — re-provision avalanche/%s", p.cfg.OptDir, p.cfg.Env)
			}
		}
		cfgPath := filepath.Join(p.cfg.EtcDir, "config.json")
		if !fileExists(cfgPath) {
			return fmt.Errorf("config.json missing at %s — re-provision", cfgPath)
		}
		cChain := filepath.Join(p.cfg.EtcDir, "configs", "chains", "C", "config.json")
		if !fileExists(cChain) {
			return fmt.Errorf("C-Chain config missing at %s — re-provision", cChain)
		}
		if err := ensurePerNodeAPIAgent(p.cfg); err != nil {
			return fmt.Errorf("ensure Go RPC: %w", err)
		}
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			snippet := stripUnitPathNoise(journalUnitSnippet(unit, 12))
			msg := fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out)))
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		time.Sleep(1200 * time.Millisecond)
		procOK, _ := avalancheNodeRunningFor(p.cfg)
		if procOK {
			log.Printf("pipeline: avalanchego START via %s", unit)
			return nil
		}
		state := systemctlActive(p.cfg.NodeService)
		if state == "activating" || state == "active" {
			log.Printf("pipeline: avalanchego START via %s state=%s", unit, state)
			return nil
		}
		if systemctlFailed(p.cfg.NodeService) || state == "failed" {
			snippet := stripUnitPathNoise(journalUnitSnippet(unit, 12))
			msg := fmt.Sprintf("avalanchego not running after start (state=%s)", state)
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		log.Printf("pipeline: avalanchego START via %s state=%s (waiting for process)", unit, state)
		return nil
	}

	if isCoreLike {
		client, ok := lookupCoreLikeSA(p.cfg.Network)
		if !ok {
			return fmt.Errorf("unknown core-like network %q", p.cfg.Network)
		}
		bin := filepath.Join(p.cfg.OptDir, "bin", client.Daemon)
		if !fileExists(bin) {
			// BCH: never accept PATH bitcoind (Bitcoin Core).
			if p.cfg.Network != "bch" {
				if path, err := exec.LookPath(client.Daemon); err != nil || path == "" {
					return fmt.Errorf("%s binary missing under %s/bin", client.Daemon, p.cfg.OptDir)
				}
			} else {
				return fmt.Errorf("%s binary missing under %s/bin (BCHN)", client.Daemon, p.cfg.OptDir)
			}
		}
		conf := filepath.Join(p.cfg.EtcDir, client.ConfName)
		if !fileExists(conf) {
			return fmt.Errorf("%s missing at %s — re-provision", client.ConfName, conf)
		}
		// Self-heal nest dirs (e.g. litecoind → /data/ltc/testnet4) + nodeop ownership
		// before start — no SSH; Update alone must recover Permission denied crash-loops.
		if err := ensureCoreLikeDataDirs(p.cfg); err != nil {
			return fmt.Errorf("ensure corelike datadir: %w", err)
		}
		if err := ensurePerNodeAPIAgent(p.cfg); err != nil {
			return fmt.Errorf("ensure Go RPC: %w", err)
		}
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			snippet := journalUnitSnippet(unit, 12)
			msg := fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out)))
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s (conf=%s)", msg, conf)
		}
		time.Sleep(900 * time.Millisecond)
		procOK, _ := coreLikeDaemonRunningFor(p.cfg, client.Daemon)
		if procOK {
			log.Printf("pipeline: %s START via %s conf=%s", client.Daemon, unit, conf)
			return nil
		}
		state := systemctlActive(p.cfg.NodeService)
		if systemctlFailed(p.cfg.NodeService) || state == "failed" || state == "inactive" || state == "dead" {
			snippet := journalUnitSnippet(unit, 12)
			msg := fmt.Sprintf("%s not running after start (state=%s)", client.Daemon, state)
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s (conf=%s)", msg, conf)
		}
		log.Printf("pipeline: %s START via %s conf=%s state=%s", client.Daemon, unit, conf, state)
		return nil
	}

	if isCardano {
		bin := filepath.Join(p.cfg.OptDir, "bin", "cardano-node")
		if !fileExists(bin) {
			return fmt.Errorf("cardano-node binary missing under %s/bin", p.cfg.OptDir)
		}
		conf := filepath.Join(p.cfg.EtcDir, "config.json")
		if !fileExists(conf) {
			return fmt.Errorf("cardano config missing at %s — re-provision", conf)
		}
		if err := ensurePerNodeAPIAgent(p.cfg); err != nil {
			return fmt.Errorf("ensure Go RPC: %w", err)
		}
		ogmiosUnit := fmt.Sprintf("cardano-ogmios-%s.service", p.cfg.Env)
		_ = exec.Command("systemctl", "daemon-reload").Run()
		for _, u := range []string{unit, ogmiosUnit} {
			_ = exec.Command("systemctl", "reset-failed", u).Run()
			_ = exec.Command("systemctl", "enable", u).Run()
			out, err := exec.Command("systemctl", "restart", u).CombinedOutput()
			if err != nil {
				snippet := journalUnitSnippet(u, 12)
				msg := fmt.Sprintf("systemctl restart %s: %v (%s)", u, err, strings.TrimSpace(string(out)))
				if snippet != "" {
					msg += " — " + snippet
				}
				return fmt.Errorf("%s", msg)
			}
			time.Sleep(900 * time.Millisecond)
		}
		procOK, _ := cardanoProcessRunning(p.cfg)
		if procOK {
			log.Printf("pipeline: cardano-node START via %s + %s", unit, ogmiosUnit)
			return nil
		}
		state := systemctlActive(p.cfg.NodeService)
		if systemctlFailed(p.cfg.NodeService) || state == "failed" {
			snippet := journalUnitSnippet(unit, 12)
			msg := fmt.Sprintf("cardano-node not running after start (state=%s)", state)
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		log.Printf("pipeline: cardano-node START via %s state=%s", unit, state)
		return nil
	}

	if isXRPL {
		if removeJobPending(p.cfg.Network, p.cfg.Env) {
			log.Printf("pipeline: skip xrpld start — remove job pending")
			return nil
		}
		if provisionLockPending(p.cfg.Network, p.cfg.Env) {
			log.Printf("pipeline: skip xrpld start — provision in flight")
			return nil
		}
		if resolveXRPLDBin(p.cfg) == "" {
			return fmt.Errorf("xrpld binary missing under %s/bin or /usr/bin/xrpld", p.cfg.OptDir)
		}
		conf := filepath.Join(p.cfg.EtcDir, "xrpld.cfg")
		if !fileExists(conf) {
			log.Printf("pipeline: skip xrpld start — cfg not ready at %s", conf)
			return nil
		}
		if !systemdUnitInstalled(unit) {
			log.Printf("pipeline: skip xrpld start — %s not installed yet", unit)
			return nil
		}
		hasLedger := xrplDatadirHasLedger(p.cfg.DataDir)
		if _, err := healXRPLCfgFile(conf, p.cfg.Env, hasLedger); err != nil {
			return fmt.Errorf("heal xrpld.cfg: %w", err)
		}
		if xrplShouldHealStateDB(p.cfg.DataDir, unit) {
			xrplPrepareDatadirHeal(p.cfg)
			if ok, err := xrplHealCorruptStateDB(p.cfg.DataDir, unit); err != nil {
				return fmt.Errorf("reinit corrupt NuDB: %w", err)
			} else if ok {
				hostLogf("INFO", "system-agent", "start", "reinit NuDB after SHAMapStore state db error")
				log.Printf("pipeline: start reinit corrupt SHAMapStore NuDB under %s", p.cfg.DataDir)
			}
		}
		if err := ensurePerNodeAPIAgent(p.cfg); err != nil {
			return fmt.Errorf("ensure Go RPC: %w", err)
		}
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		if err := recycleXRPLUnit(unit, p.cfg); err != nil {
			snippet := journalUnitSnippet(unit, 12)
			msg := err.Error()
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s (conf=%s)", msg, conf)
		}
		time.Sleep(900 * time.Millisecond)
		procOK, _ := xrplProcessRunning(p.cfg)
		if procOK {
			if err := startXRPLClioStack(p.cfg); err != nil {
				log.Printf("pipeline: xrpl clio start: %v", err)
			}
			log.Printf("pipeline: xrpld START via %s conf=%s", unit, conf)
			return nil
		}
		state := systemctlActive(p.cfg.NodeService)
		if systemctlFailed(p.cfg.NodeService) || state == "failed" || state == "inactive" || state == "dead" {
			snippet := journalUnitSnippet(unit, 12)
			msg := fmt.Sprintf("xrpld not running after start (state=%s)", state)
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s (conf=%s)", msg, conf)
		}
		if err := startXRPLClioStack(p.cfg); err != nil {
			log.Printf("pipeline: xrpl clio start: %v", err)
		}
		log.Printf("pipeline: xrpld START via %s conf=%s state=%s", unit, conf, state)
		return nil
	}

	if isHL || isArb || isRobinhood || isOP || isBase {
		net := strings.ToLower(p.cfg.Network)
		if resolveL2NodeBin(p.cfg, net) == "" {
			return fmt.Errorf("%s binary missing under %s/bin", net, p.cfg.OptDir)
		}
		if err := ensureL2Dirs(p.cfg); err != nil {
			return fmt.Errorf("ensure %s dirs: %w", net, err)
		}
		if err := ensurePerNodeAPIAgent(p.cfg); err != nil {
			return fmt.Errorf("ensure Go RPC: %w", err)
		}
		if isRobinhood {
			if err := startRobinhoodViaAPIAgent(p.cfg); err != nil {
				return err
			}
			log.Printf("pipeline: robinhood START via api-agent rewrite+start")
			return nil
		}
		units := []string{unit}
		if isOP {
			units = append(units, fmt.Sprintf("optimism-op-node-%s.service", p.cfg.Env))
		}
		if isBase {
			units = append(units, fmt.Sprintf("base-consensus-%s.service", p.cfg.Env))
		}
		_ = exec.Command("systemctl", "daemon-reload").Run()
		for _, u := range units {
			_ = exec.Command("systemctl", "reset-failed", u).Run()
			_ = exec.Command("systemctl", "enable", u).Run()
			out, err := exec.Command("systemctl", "restart", u).CombinedOutput()
			if err != nil {
				snippet := journalUnitSnippet(u, 12)
				msg := fmt.Sprintf("systemctl restart %s: %v (%s)", u, err, strings.TrimSpace(string(out)))
				if snippet != "" {
					msg += " — " + snippet
				}
				return fmt.Errorf("%s", msg)
			}
			time.Sleep(900 * time.Millisecond)
		}
		procOK, _ := l2ProcessRunning(p.cfg, net)
		if procOK {
			log.Printf("pipeline: %s START via %v", net, units)
			return nil
		}
		state := systemctlActive(p.cfg.NodeService)
		if systemctlFailed(p.cfg.NodeService) || state == "failed" {
			snippet := journalUnitSnippet(unit, 12)
			msg := fmt.Sprintf("%s not running after start (state=%s)", net, state)
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		log.Printf("pipeline: %s START via %v state=%s", net, units, state)
		return nil
	}

	if isBSC {
		if bin := resolveBSCGethBin(p.cfg); bin == "" {
			return fmt.Errorf("bsc-geth binary missing under %s/bin or /usr/local/bin/bsc-geth", p.cfg.OptDir)
		}
		if err := ensureBSCDirs(p.cfg); err != nil {
			return fmt.Errorf("ensure bsc dirs: %w", err)
		}
		genesis := filepath.Join(p.cfg.EtcDir, "genesis.json")
		if !fileExists(genesis) {
			return fmt.Errorf("genesis missing at %s — re-provision", genesis)
		}
		if err := ensurePerNodeAPIAgent(p.cfg); err != nil {
			return fmt.Errorf("ensure Go RPC: %w", err)
		}
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			snippet := journalUnitSnippet(unit, 12)
			msg := fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out)))
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		time.Sleep(1200 * time.Millisecond)
		procOK, _ := bscProcessRunning(p.cfg)
		if procOK {
			log.Printf("pipeline: bsc START via %s", unit)
			return nil
		}
		state := systemctlActive(p.cfg.NodeService)
		if systemctlFailed(p.cfg.NodeService) || state == "failed" || state == "inactive" || state == "dead" {
			snippet := journalUnitSnippet(unit, 12)
			msg := fmt.Sprintf("bsc-geth not running after start (state=%s)", state)
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		log.Printf("pipeline: bsc START via %s state=%s", unit, state)
		return nil
	}

	if isEthereum {
		if bin := resolveGethBin(p.cfg); bin == "" {
			return fmt.Errorf("geth binary missing under %s/bin or /usr/bin/geth", p.cfg.OptDir)
		}
		if bin := resolveLighthouseBin(p.cfg); bin == "" {
			return fmt.Errorf("lighthouse binary missing at /usr/local/bin/lighthouse or PATH")
		}
		if err := ensureEthereumDirs(p.cfg); err != nil {
			return fmt.Errorf("ensure ethereum dirs: %w", err)
		}
		if err := ensureEthereumJWT(p.cfg); err != nil {
			return fmt.Errorf("ensure JWT: %w", err)
		}
		if err := ensurePerNodeAPIAgent(p.cfg); err != nil {
			return fmt.Errorf("ensure Go RPC: %w", err)
		}
		gethUnit := unit
		lhUnit := envOr("TRON_LIGHTHOUSE_SERVICE", ethereumLighthouseUnit(p.cfg.Env)) + ".service"
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", gethUnit, lhUnit).Run()
		_ = exec.Command("systemctl", "enable", gethUnit, lhUnit).Run()
		out, err := exec.Command("systemctl", "restart", gethUnit).CombinedOutput()
		if err != nil {
			snippet := journalUnitSnippet(gethUnit, 12)
			msg := fmt.Sprintf("systemctl restart %s: %v (%s)", gethUnit, err, strings.TrimSpace(string(out)))
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		time.Sleep(1200 * time.Millisecond)
		gethOK, _ := gethProcessRunning(p.cfg)
		if !gethOK {
			state := systemctlActive(p.cfg.NodeService)
			if systemctlFailed(p.cfg.NodeService) || state == "failed" || state == "inactive" || state == "dead" {
				snippet := journalUnitSnippet(gethUnit, 12)
				msg := fmt.Sprintf("geth not running after start (state=%s)", state)
				if snippet != "" {
					msg += " — " + snippet
				}
				return fmt.Errorf("%s", msg)
			}
		}
		out, err = exec.Command("systemctl", "restart", lhUnit).CombinedOutput()
		if err != nil {
			snippet := journalUnitSnippet(lhUnit, 12)
			msg := fmt.Sprintf("systemctl restart %s: %v (%s)", lhUnit, err, strings.TrimSpace(string(out)))
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		time.Sleep(900 * time.Millisecond)
		lhOK, _ := lighthouseProcessRunning(p.cfg)
		if gethOK && lhOK {
			log.Printf("pipeline: ethereum START via %s + %s", gethUnit, lhUnit)
			return nil
		}
		state := systemctlActive(envOr("TRON_LIGHTHOUSE_SERVICE", ethereumLighthouseUnit(p.cfg.Env)))
		if systemctlFailed(envOr("TRON_LIGHTHOUSE_SERVICE", ethereumLighthouseUnit(p.cfg.Env))) ||
			state == "failed" || state == "inactive" || state == "dead" {
			snippet := journalUnitSnippet(lhUnit, 12)
			msg := fmt.Sprintf("lighthouse not running after start (state=%s)", state)
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		log.Printf("pipeline: ethereum START via %s + %s state=%s", gethUnit, lhUnit, state)
		return nil
	}

	if isBitcoin {
		if bin := resolveBitcoindBin(p.cfg); bin == "" {
			return fmt.Errorf("bitcoind binary missing under %s/bin or /opt/bitcoin/bin", p.cfg.OptDir)
		}
		confPath, err := ensureBitcoinConf(p.cfg)
		if err != nil {
			return fmt.Errorf("ensure bitcoin.conf: %w", err)
		}
		// Go RPC on confirmed public_port must be up before/with bitcoind start.
		if err := ensurePerNodeAPIAgent(p.cfg); err != nil {
			return fmt.Errorf("ensure Go RPC: %w", err)
		}
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		// restart so rewritten bitcoin.conf (dbcache/rpcthreads/…) is actually loaded.
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			snippet := journalUnitSnippet(unit, 12)
			msg := fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out)))
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s (conf=%s)", msg, confPath)
		}
		// Type=simple returns before bitcoind validates -conf; detect instant death.
		time.Sleep(900 * time.Millisecond)
		procOK, _ := bitcoindRunningFor(p.cfg)
		if procOK {
			log.Printf("pipeline: bitcoind START via %s conf=%s", unit, confPath)
			return nil
		}
		state := systemctlActive(p.cfg.NodeService)
		if systemctlFailed(p.cfg.NodeService) || state == "failed" || state == "inactive" || state == "dead" {
			snippet := journalUnitSnippet(unit, 12)
			msg := fmt.Sprintf("bitcoind not running after start (state=%s)", state)
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s (conf=%s)", msg, confPath)
		}
		// activating/active — ACK start attempt; collect surfaces journal if it crash-loops.
		log.Printf("pipeline: bitcoind START via %s conf=%s state=%s", unit, confPath, state)
		return nil
	}

	if isSolana {
		if bin := resolveAgaveBin(p.cfg); bin == "" {
			return fmt.Errorf("agave-validator / solana-test-validator missing under %s/bin or nodeop install", p.cfg.OptDir)
		}
		if err := ensureSolanaDirs(p.cfg); err != nil {
			return fmt.Errorf("ensure solana dirs: %w", err)
		}
		if !fileExists(solanaRunScriptPath(p.cfg)) {
			return fmt.Errorf("run-validator.sh missing at %s — re-provision", solanaRunScriptPath(p.cfg))
		}
		if err := ensurePerNodeAPIAgent(p.cfg); err != nil {
			return fmt.Errorf("ensure Go RPC: %w", err)
		}
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			snippet := journalUnitSnippet(unit, 12)
			msg := fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out)))
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		time.Sleep(1200 * time.Millisecond)
		procOK, _ := solanaProcessRunning(p.cfg)
		if procOK {
			log.Printf("pipeline: solana START via %s", unit)
			return nil
		}
		state := systemctlActive(p.cfg.NodeService)
		if systemctlFailed(p.cfg.NodeService) || state == "failed" || state == "inactive" || state == "dead" {
			snippet := journalUnitSnippet(unit, 12)
			msg := fmt.Sprintf("solana not running after start (state=%s)", state)
			if snippet != "" {
				msg += " — " + snippet
			}
			return fmt.Errorf("%s", msg)
		}
		log.Printf("pipeline: solana START via %s state=%s", unit, state)
		return nil
	}

	// TRON snapshot extracts as root; java-tron is User=nodeop.
	if pipelineMayUseTronctl(p.cfg.Network) {
		ensureNodeopOwned(p.cfg.DataDir, p.cfg.Output, p.cfg.OptDir)
	}

	// Refuse silent success on stub units (ExecStart=/bin/false).
	unitPath := "/etc/systemd/system/" + unit
	if b, err := os.ReadFile(unitPath); err == nil {
		body := string(b)
		if strings.Contains(body, "ExecStart=/bin/false") || strings.Contains(body, "provisioned stub") {
			return fmt.Errorf("%s is a stub (ExecStart=/bin/false) — need FullNode.jar + Java 8 + real unit", unit)
		}
	}

	cmd := exec.Command("systemctl", "restart", unit)
	if out, err := cmd.CombinedOutput(); err != nil {
		snippet := journalUnitSnippet(unit, 12)
		msg := fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out)))
		if snippet != "" {
			msg += " — " + snippet
		}
		return fmt.Errorf("%s", msg)
	}
	log.Printf("pipeline: node START via %s", unit)
	return nil
}

// pipelineMayUseTronctl — TRON-only CLI fallback when the node unit is missing.
// Bitcoin must use bitcoind systemd units exclusively.
func ensureNodeopOwned(paths ...string) {
	args := []string{"-R", "nodeop:nodeop"}
	for _, p := range paths {
		p = strings.TrimSpace(p)
		if p != "" {
			args = append(args, p)
		}
	}
	if len(args) <= 2 {
		return
	}
	_ = exec.Command("chown", args...).Run()
}

func pipelineMayUseTronctl(network string) bool {
	n := strings.ToLower(strings.TrimSpace(network))
	return n == "" || n == "tron"
	// bitcoin / solana / ethereum must use their own systemd units exclusively.
}

// startRobinhoodViaAPIAgent — tip/leaf api-agent rewrites nitro unit (--init.url,
// snapshot oneshot, TRON_SNAPSHOT_ENABLED=1) then starts. Plain systemctl would
// keep a stale provision without download % on Sync status.
func startRobinhoodViaAPIAgent(cfg Config) error {
	env := normalizeEnvName(cfg.Env)
	payload, _ := json.Marshal(map[string]string{
		"network": "robinhood",
		"env":     env,
	})
	token := strings.TrimSpace(envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", "")))

	ports := make([]int, 0, 4)
	if p := cfg.AgentAPIPort(); p > 0 {
		ports = append(ports, p)
	}
	if p := cfg.PublicRPCPort(); p > 0 {
		ports = append(ports, p)
	}
	// Host tip control plane (canonical when leaf API is still warming).
	if b, err := os.ReadFile("/etc/rpcnode/agent.port"); err == nil {
		if tip, err := strconv.Atoi(strings.TrimSpace(string(b))); err == nil && tip > 0 {
			ports = append(ports, tip)
		}
	}
	ports = append(ports, 39090)

	seen := map[int]bool{}
	var last string
	client := &http.Client{Timeout: 90 * time.Second}
	for _, port := range ports {
		if port <= 0 || seen[port] {
			continue
		}
		seen[port] = true
		if !portOpen("127.0.0.1", port) {
			continue
		}
		url := fmt.Sprintf("http://127.0.0.1:%d/api/v1/nodes/start", port)
		req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(payload))
		if err != nil {
			last = err.Error()
			continue
		}
		req.Header.Set("Content-Type", "application/json")
		if token != "" {
			req.Header.Set("X-Api-Token", token)
			req.Header.Set("Authorization", "Bearer "+token)
		}
		resp, err := client.Do(req)
		if err != nil {
			last = err.Error()
			continue
		}
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		_ = resp.Body.Close()
		if resp.StatusCode >= 200 && resp.StatusCode < 300 {
			return nil
		}
		msg := strings.TrimSpace(string(body))
		if msg == "" {
			msg = resp.Status
		}
		last = fmt.Sprintf("%s → %s", url, msg)
		// "already" / active unit — treat as success for pipeline pace.
		if strings.Contains(strings.ToLower(msg), "already") {
			return nil
		}
	}
	if last == "" {
		last = "no local api-agent listening for robinhood start"
	}
	return fmt.Errorf("%s", last)
}
