package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// errRemoveSuperseded — in-flight wipe must stop; Confirm ports / provision won.
var errRemoveSuperseded = errors.New("remove superseded by provision")

// agentTeardownDelay — grace only when this process IS the leaf api-agent
// (must flush HTTP ACK before killing self). Tip-driven remove uses delay=0:
// kill → teardown units → wipe (leaf agents already stopped in phase 1).
const agentTeardownDelay = 5 * time.Minute

// removePhaseOrder — product contract exposed in remove ACK JSON.
// 1 kill processes → 2 remove systemd units → 3 wipe dirs when delete_files.
func removePhaseOrder() []string {
	return []string{
		"1_kill_node_and_leaf_agents",
		"2_teardown_systemd_units",
		"3_wipe_files_if_requested",
	}
}

// systemctlStopTimeout — default unit stop budget (non-core networks).
const systemctlStopTimeout = 25 * time.Second

// coreGracefulStopTimeout — brief wait after *-cli stop before escalate on remove.
// Remove must ACK fast (panel stays on "removing" until phase-1 returns).
// Full Core flush is nice-to-have; stuck/broken nodes must not block wipe for minutes.
// Panel remove HTTP timeout must be ≥ this (+ margin).
const coreGracefulStopTimeout = 20 * time.Second

// One pending teardown per network+env — repeated remove must not stack AfterFuncs.
var agentTeardownOnce sync.Map // "network/env" → struct{}

// One in-flight async delete_files job per network+env.
var asyncDeleteOnce sync.Map // "network/env" → struct{}

type nodeRemoveRequest struct {
	Env         string `json:"env"`
	Network     string `json:"network,omitempty"`
	DeleteFiles bool   `json:"delete_files"`
	Force       bool   `json:"force"`
}

func (s *Server) handleNodesRemove(w http.ResponseWriter, r *http.Request) {
	var req nodeRemoveRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
		return
	}
	s.respondNodesRemove(w, req)
}

func (s *Server) respondNodesRemove(w http.ResponseWriter, req nodeRemoveRequest) {
	req.Env = normalizeEnv(req.Env)
	if req.Env == "" {
		req.Env = normalizeEnv(s.cfg.Env)
	}
	if req.Env == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "env_required"})
		return
	}
	req.Network = normalizeNetwork(req.Network)
	if req.Network == "" {
		req.Network = "tron"
	}

	wantDeleteFiles := req.DeleteFiles
	// Large datadirs: ACK after phase-1 kill, then async unit teardown + optional wipe.
	// Never RemoveAll on the request path.
	asyncAfterACK := true

	// Phase 1 (sync, before ACK): kill node + leaf agents for THIS network/env only.
	// Leaf already stopped / missing units is OK — continue. Never tip host Server.
	result, err := stopNodeStackForRemove(req.Network, req.Env)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{
			"ok": false, "error": "remove_failed", "message": err.Error(),
			"version": agentVersion(),
		})
		return
	}

	selfLeaf := removeTargetsSelfAgent(req.Network, req.Env)
	delay := time.Duration(0)
	if selfLeaf && !req.Force {
		// Self leaf must stay alive until ACK flushes; own unit file removed after grace.
		delay = agentTeardownDelay
	}

	teardownSec := int(delay / time.Second)
	result["version"] = agentVersion()
	result["network"] = req.Network
	result["env"] = req.Env
	result["delete_files"] = wantDeleteFiles
	result["agent_teardown_in_sec"] = teardownSec
	result["agent_teardown"] = map[string]any{"scheduled": true, "immediate": delay == 0}
	result["agent_teardown_units"] = perNodeAgentUnits(req.Network, req.Env)
	result["remove_order"] = removePhaseOrder()
	result["delete_files_async"] = asyncAfterACK && wantDeleteFiles
	if wantDeleteFiles {
		result["delete_files_status"] = "started"
		result["message"] = fmt.Sprintf(
			"killed %s/%s; background: teardown units then wipe dirs (tip host stays up)",
			req.Network, req.Env,
		)
	} else {
		result["message"] = fmt.Sprintf(
			"killed %s/%s; background: teardown leaf units (data dirs kept; tip host stays up)",
			req.Network, req.Env,
		)
	}
	// Fresh remove must replace a prior superseded/completed job — otherwise
	// leftovers after Panel-only / re-provision stay forever («Already exists»).
	forceWriteRemoveJobWithWipe(req.Network, req.Env, "started", "", nil, wantDeleteFiles)

	// Flush ACK so the panel sees ok:true before heavy teardown / wipe / self-teardown.
	writeJSON(w, http.StatusOK, result)
	if f, ok := w.(http.Flusher); ok {
		f.Flush()
	}

	go runRemoveAfterACK(req.Network, req.Env, wantDeleteFiles, delay)
}

// removeUsesAsyncFileDelete — chain datadirs can be hundreds of GiB; always ACK first.
func removeUsesAsyncFileDelete(network string) bool {
	_ = normalizeNetwork(network)
	return true
}

// runRemoveAfterACK — phase 2 (teardown systemd units) then phase 3 (wipe dirs if requested).
// Product order: kill (phase 1 sync) → delete units → wipe files when delete_files.
func runRemoveAfterACK(network, env string, deleteFiles bool, teardownDelay time.Duration) {
	key := normalizeNetwork(network) + "/" + normalizeEnv(env)
	if _, loaded := asyncDeleteOnce.LoadOrStore(key, struct{}{}); loaded {
		fmt.Fprintf(os.Stderr, "nodes/remove: async remove already running for %s\n", key)
		return
	}
	defer asyncDeleteOnce.Delete(key)

	// Re-provision marks the job superseded — never wipe/teardown a fresh node.
	if removeJobIsSuperseded(network, env) {
		fmt.Fprintf(os.Stderr, "nodes/remove: abort %s — job superseded by provision\n", key)
		return
	}

	// Quick re-kill — leaf already down is fine; never re-run full graceful budget.
	if nodeStillRunning(network, env) != "" {
		_ = escalateStopNode(network, env)
		time.Sleep(300 * time.Millisecond)
	}
	_ = stopPerNodeAgentsForRemove(network, env)

	if removeJobIsSuperseded(network, env) {
		fmt.Fprintf(os.Stderr, "nodes/remove: abort %s — superseded before unit teardown\n", key)
		return
	}

	// Phase 2: teardown systemd units (node + leaf agents). Tip host untouched.
	writeRemoveJobWithWipe(network, env, "deleting", "", nodeDataPaths(network, env), deleteFiles)
	teardownSteps := teardownLeafSystemUnits(network, env, teardownDelay)
	fmt.Fprintf(os.Stderr, "nodes/remove: phase2 unit teardown %s steps=%v\n", key, teardownSteps)

	if removeJobIsSuperseded(network, env) {
		fmt.Fprintf(os.Stderr, "nodes/remove: abort %s — superseded after unit teardown\n", key)
		return
	}

	wipeOK := true
	var wipePaths []string
	if deleteFiles {
		// Phase 3: wipe datadir/conf/logs after units are gone (no Restart=always recreate).
		paths, steps, err := wipeNodeDataDirsForRemove(network, env)
		wipePaths = paths
		if err != nil && !errors.Is(err, errRemoveSuperseded) {
			for attempt := 0; attempt < 3 && err != nil && !errors.Is(err, errRemoveSuperseded); attempt++ {
				if removeJobIsSuperseded(network, env) {
					err = errRemoveSuperseded
					break
				}
				killNodeProcesses(network, env)
				_ = stopPerNodeAgentsForRemove(network, env)
				time.Sleep(time.Duration(400*(attempt+1)) * time.Millisecond)
				var paths2, steps2 []string
				paths2, steps2, err = wipeNodeDataDirsForRemove(network, env)
				paths = append(paths, paths2...)
				steps = append(steps, steps2...)
				wipePaths = paths
			}
		}
		if errors.Is(err, errRemoveSuperseded) || removeJobIsSuperseded(network, env) {
			fmt.Fprintf(os.Stderr, "nodes/remove: abort %s — superseded during wipe\n", key)
			return
		}
		if err != nil {
			fmt.Fprintf(os.Stderr, "nodes/remove: delete_files %s: %v\n", key, err)
			writeRemoveJobWithWipe(network, env, "error", err.Error(), wipePaths, true)
			wipeOK = false
		} else {
			writeRemoveJobWithWipe(network, env, "wiped", "", wipePaths, true)
			fmt.Fprintf(os.Stderr, "nodes/remove: delete_files completed for %s steps=%v\n", key, steps)
		}
	} else {
		// Units/registry already torn down — keep chain data dirs.
		writeRemoveJobWithWipe(network, env, "wiped", "", nil, false)
	}

	if removeJobIsSuperseded(network, env) {
		fmt.Fprintf(os.Stderr, "nodes/remove: abort %s — superseded before complete\n", key)
		return
	}

	// Self-leaf: agent unit file removal may still be delayed; tip path finishes now.
	if teardownDelay > 0 {
		scheduleAgentTeardownDelayed(network, env, teardownDelay, wipeOK)
		return
	}
	if wipeOK {
		writeRemoveJobWithWipe(network, env, "completed", "", wipePaths, deleteFiles)
	} else {
		writeRemoveJobWithWipe(network, env, "error", "wipe incomplete; per-node units removed", nodeDataPaths(network, env), true)
	}
}

func removeJobPath(network, env string) string {
	return filepath.Join(removeJobsDir, normalizeNetwork(network)+"-"+normalizeEnv(env)+".json")
}

func writeRemoveJob(network, env, status, errMsg string, paths []string) {
	writeRemoveJobWithWipe(network, env, status, errMsg, paths, removeJobDeleteFiles(network, env))
}

func writeRemoveJobWithWipe(network, env, status, errMsg string, paths []string, deleteFiles bool) {
	writeRemoveJobWithWipeOpts(network, env, status, errMsg, paths, deleteFiles, false)
}

// forceWriteRemoveJobWithWipe — explicit tip/panel remove start. May replace
// superseded/completed so orphan dirs can be wiped after a prior re-provision abort.
func forceWriteRemoveJobWithWipe(network, env, status, errMsg string, paths []string, deleteFiles bool) {
	writeRemoveJobWithWipeOpts(network, env, status, errMsg, paths, deleteFiles, true)
}

func writeRemoveJobWithWipeOpts(network, env, status, errMsg string, paths []string, deleteFiles bool, force bool) {
	dir := removeJobsDir
	_ = os.MkdirAll(dir, 0o755)
	status = strings.ToLower(strings.TrimSpace(status))
	// Never clobber superseded from an in-flight OLD wipe — that would delete a
	// just-provisioned node. Explicit remove (force) starts a new cycle.
	if !force {
		if cur := removeJobStatus(network, env); cur == "superseded" && status != "superseded" {
			return
		}
	}
	body := map[string]any{
		"ok":           status == "completed" || status == "started" || status == "deleting",
		"network":      normalizeNetwork(network),
		"env":          normalizeEnv(env),
		"status":       status,
		"delete_files": deleteFiles,
		"updated":      time.Now().UTC().Format(time.RFC3339),
		"version":      agentVersion(),
		"paths":        paths,
		"error":        errMsg,
	}
	raw, _ := json.MarshalIndent(body, "", "  ")
	_ = os.WriteFile(removeJobPath(network, env), raw, 0o644)
}

func removeJobDeleteFiles(network, env string) bool {
	b, err := os.ReadFile(removeJobPath(network, env))
	if err != nil {
		// Missing job on resume of orphan dirs — prefer wipe so re-add works.
		return true
	}
	var doc map[string]any
	if json.Unmarshal(b, &doc) != nil {
		return true
	}
	if v, ok := doc["delete_files"].(bool); ok {
		return v
	}
	return true
}

func removeJobStatus(network, env string) string {
	b, err := os.ReadFile(removeJobPath(network, env))
	if err != nil {
		return ""
	}
	var doc map[string]any
	if json.Unmarshal(b, &doc) != nil {
		return ""
	}
	st, _ := doc["status"].(string)
	return strings.ToLower(strings.TrimSpace(st))
}

func removeJobIsSuperseded(network, env string) bool {
	return removeJobStatus(network, env) == "superseded"
}

// isHostTipProcess — host Server control plane (:38990). Never a leaf remove target.
func isHostTipProcess() bool {
	if strings.EqualFold(strings.TrimSpace(os.Getenv("RPCNODE_HOST_TIP")), "1") {
		return true
	}
	network := strings.ToLower(strings.TrimSpace(os.Getenv("TRON_NETWORK")))
	stateDir := strings.TrimSpace(os.Getenv("TRON_STATE_DIR"))
	if stateDir == "" {
		// Some units only set agent-state path under host/.
		stateDir = filepath.Dir(strings.TrimSpace(os.Getenv("TRON_AGENT_STATE")))
	}
	return network == "" && isHostTipStateDir(stateDir)
}

// removeTargetsSelfAgent — true when this process IS the per-node agent for network/env
// (stopping our own unit would kill the HTTP handler mid-response).
// Host tip never counts as a leaf self-target (empty TRON_NETWORK must not default to tron).
func removeTargetsSelfAgent(network, env string) bool {
	if isHostTipProcess() {
		return false
	}
	myNet := normalizeNetwork(os.Getenv("TRON_NETWORK"))
	if myNet == "" {
		// Non-tip with empty network: legacy tron leaf.
		myNet = "tron"
	}
	myEnv := normalizeEnv(envFirst("", "RPCNODE_ENV", "TRON_ENV"))
	return normalizeNetwork(network) == myNet && normalizeEnv(env) == myEnv
}

// stopNodeStackForRemove — phase 1: node systemd + processes + per-node Go RPC proxy
// (api-agent) + system-agent for this env. Tip host units are never touched.
//
// Stop order (MUST):
//  1. disable units (block Restart=on-failure respawn)
//  2. network-native graceful stop (bitcoin-cli / dogecoin-cli / …)
//  3. wait for daemon exit (core flush may take minutes)
//  4. escalate SIGTERM → SIGKILL only if still alive
//  5. stop leaf agents
//
// Returns error if the fullnode is still running after escalate — panel must NOT
// drop the row / claim remove ACK when the daemon outlives agents.
func stopNodeStackForRemove(network, env string) (map[string]any, error) {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	if network == "" {
		network = "tron"
	}
	steps := []string{}

	// Pin first: disable+mask so Restart=always / watchdog / oneshot wrappers
	// cannot respawn while we SIGTERM. Never mask tip host units.
	self := removeTargetsSelfAgent(network, env)
	if _, err := exec.LookPath("systemctl"); err == nil {
		for _, u := range unitsToPinForRemove(network, env) {
			if self && unitBelongsToThisProcess(u) {
				steps = append(steps, "kept self unmasked for ACK: "+u)
				continue
			}
			steps = append(steps, pinUnitDown(u)...)
		}
	}
	hostLogf("info", "api-agent", "remove", "pin+kill %s/%s units=%d", network, env, len(unitsToPinForRemove(network, env)))

	stopBudget := stopTimeoutForNetwork(network)
	// Never `systemctl stop` — that runs ExecStop and hangs / Job canceled
	// (xrpld server_stop, bitcoin-cli flush, TON child units).
	// Order: native CLI if any → SIGTERM main → wait → SIGKILL → reset-failed.
	if networkUsesCLIStop(network) {
		steps = append(steps, gracefulStopNode(network, env)...)
	} else {
		steps = append(steps, sigtermNodeUnits(network, env)...)
	}
	if waitNodeExit(network, env, stopBudget) {
		steps = append(steps, "graceful stop completed for "+network+"/"+env)
	} else {
		steps = append(steps, "graceful stop timed out — escalating "+network+"/"+env)
		steps = append(steps, escalateStopNode(network, env)...)
	}
	steps = append(steps, resetNodeUnitsAfterStop(network, env)...)

	if alive := nodeStillRunning(network, env); alive != "" {
		// Last ditch — kill + reset; ACK if only systemd leftover state remains.
		steps = append(steps, "node still reported alive: "+alive+" — force kill")
		steps = append(steps, escalateStopNode(network, env)...)
		steps = append(steps, resetNodeUnitsAfterStop(network, env)...)
		time.Sleep(400 * time.Millisecond)
		if alive = nodeStillRunning(network, env); alive != "" {
			steps = append(steps, "node still alive after escalate: "+alive)
			return map[string]any{
				"ok": false, "env": env, "network": network, "steps": steps,
				"phase": "1_stop_node_and_go_rpc_proxy",
			}, fmt.Errorf("node still running after remove stop: %s", alive)
		}
	}
	steps = append(steps, "verified node daemon stopped")

	// Stop Go RPC proxy + system-agent for THIS leaf (not tip).
	// Must run before wipe so system-agent cannot recreate datadir/conf.
	steps = append(steps, stopPerNodeAgentsForRemove(network, env)...)

	return map[string]any{
		"ok":      true,
		"env":     env,
		"network": network,
		"steps":   steps,
		"phase":   "1_stop_node_and_go_rpc_proxy",
	}, nil
}

func stopTimeoutForNetwork(network string) time.Duration {
	// Keep ≤ panel remove HTTP timeout (180s) with margin. Prefer fast ACK + escalate.
	switch normalizeNetwork(network) {
	case "bitcoin", "doge", "ltc", "dash", "bch":
		return coreGracefulStopTimeout // 20s — then SIGKILL (remove must not stall minutes)
	case "zcash":
		return 30 * time.Second // zebrad SIGTERM budget
	case "tron", "ethereum", "bsc", "cardano", "arb", "robinhood", "hyperliquid", "ton", "etc", "base", "avalanche":
		return 45 * time.Second // SIGTERM budget then escalate
	case "xrpl", "solana", "optimism", "stellar", "sui", "aptos":
		return 30 * time.Second
	default:
		return systemctlStopTimeout
	}
}

// resetNodeUnitsAfterStop — kill leftover unit cgroup + reset-failed (no long systemctl stop).
func resetNodeUnitsAfterStop(network, env string) []string {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return nil
	}
	steps := []string{}
	for _, u := range nodeUnitsForRemove(network, env) {
		if isHostBootstrapUnit(u) {
			continue
		}
		_ = exec.Command("systemctl", "kill", "-s", "SIGKILL", "--kill-who=all", u).Run()
		_ = exec.Command("systemctl", "reset-failed", u).Run()
		steps = append(steps, "reset unit "+u)
	}
	return steps
}

// networkUsesCLIStop — true when graceful stop is a client RPC/CLI (not bare SIGTERM).
// Day-one for new Core-like nets: add case here + gracefulStopNode branch.
func networkUsesCLIStop(network string) bool {
	switch normalizeNetwork(network) {
	case "bitcoin", "doge", "ltc", "dash", "bch", "xrpl":
		return true
	default:
		return false
	}
}

func sigtermNodeUnits(network, env string) []string {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return []string{"systemctl missing — skip SIGTERM units"}
	}
	steps := []string{}
	for _, u := range nodeUnitsForRemove(network, env) {
		if isHostBootstrapUnit(u) {
			continue
		}
		_ = exec.Command("systemctl", "kill", "-s", "SIGTERM", "--kill-who=all", u).Run()
		steps = append(steps, "sigterm "+u)
	}
	if len(steps) == 0 {
		steps = append(steps, "no node units to SIGTERM for "+network+"/"+env)
	}
	return steps
}

// gracefulStopNode — per-network CLI/RPC stop. No SIGKILL here.
// Call only when networkUsesCLIStop; other nets use sigtermNodeUnits (no ExecStop).
func gracefulStopNode(network, env string) []string {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	prof := lookupPortProfile(network, env)
	etc := prof.EtcPath
	if etc == "" {
		etc = filepath.Join("/etc", network, env)
	}
	steps := []string{}

	switch network {
	case "bitcoin":
		conf := filepath.Join(etc, "bitcoin.conf")
		cli := resolveBitcoinCLI(prof.OptPath, resolveBitcoindBinary(prof.OptPath))
		steps = append(steps, runCLIStop(cli, conf, "bitcoin-cli stop")...)
	case "doge":
		conf := filepath.Join(etc, "dogecoin.conf")
		cli := resolveDogecoinCLI(prof.OptPath, resolveDogecoindBinary(prof.OptPath))
		steps = append(steps, runCLIStop(cli, conf, "dogecoin-cli stop")...)
	case "ltc", "dash", "bch":
		if client, ok := lookupCoreLike(network); ok {
			conf := filepath.Join(etc, client.ConfName)
			cli := resolveCoreLikeCLI(client, prof.OptPath, resolveCoreLikeBinary(client, prof.OptPath))
			steps = append(steps, runCLIStop(cli, conf, client.CLI+" stop")...)
		}
	case "xrpl":
		conf := filepath.Join(etc, "xrpld.cfg")
		if !fileExists(conf) {
			conf = filepath.Join(etc, "rippled.cfg")
		}
		bin := ""
		for _, name := range []string{"xrpld", "rippled"} {
			if p := findBinaryInOpt(prof.OptPath, name); p != "" {
				bin = p
				break
			}
			if p, err := exec.LookPath(name); err == nil {
				bin = p
				break
			}
		}
		if bin == "" {
			return []string{"xrpl server_stop: binary missing — will rely on systemctl"}
		}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		var cmd *exec.Cmd
		if conf != "" && fileExists(conf) {
			cmd = exec.CommandContext(ctx, bin, "--conf", conf, "server_stop")
		} else {
			cmd = exec.CommandContext(ctx, bin, "server_stop")
		}
		out, err := cmd.CombinedOutput()
		msg := strings.TrimSpace(string(out))
		if err != nil {
			steps = append(steps, fmt.Sprintf("%s server_stop: %v (%s)", filepath.Base(bin), err, msg))
		} else {
			steps = append(steps, filepath.Base(bin)+" server_stop ok")
		}
	default:
		steps = append(steps, "graceful stop via systemctl for "+network)
	}
	return steps
}

func findBinaryInOpt(optPath, name string) string {
	for _, c := range []string{
		filepath.Join(optPath, "bin", name),
		filepath.Join(optPath, name),
		filepath.Join("/usr/bin", name),
		filepath.Join("/usr/local/bin", name),
	} {
		if fileExists(c) {
			return c
		}
	}
	return ""
}

func runCLIStop(cli, conf, label string) []string {
	cli = strings.TrimSpace(cli)
	conf = strings.TrimSpace(conf)
	if cli == "" || !fileExists(cli) {
		return []string{label + ": cli binary missing — will rely on systemctl"}
	}
	if conf == "" || !fileExists(conf) {
		return []string{label + ": conf missing (" + conf + ") — will rely on systemctl"}
	}
	// *-cli stop returns once shutdown is requested; flush continues in daemon.
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, cli, "-conf="+conf, "stop")
	out, err := cmd.CombinedOutput()
	msg := strings.TrimSpace(string(out))
	if err != nil {
		// Already stopped is fine.
		low := strings.ToLower(msg + " " + err.Error())
		if strings.Contains(low, "could not connect") || strings.Contains(low, "connection refused") ||
			strings.Contains(low, "not found") || strings.Contains(low, "stopped") {
			return []string{label + ": already stopped (" + msg + ")"}
		}
		return []string{fmt.Sprintf("%s: %v (%s)", label, err, msg)}
	}
	if msg == "" {
		msg = "ok"
	}
	return []string{label + ": " + msg}
}

// waitNodeExit — poll until daemon gone or timeout.
func waitNodeExit(network, env string, budget time.Duration) bool {
	if budget <= 0 {
		budget = systemctlStopTimeout
	}
	deadline := time.Now().Add(budget)
	for time.Now().Before(deadline) {
		if nodeStillRunning(network, env) == "" {
			return true
		}
		time.Sleep(1 * time.Second)
	}
	return nodeStillRunning(network, env) == ""
}

// escalateStopNode — SIGTERM then SIGKILL only after graceful path failed.
func escalateStopNode(network, env string) []string {
	steps := []string{}
	if _, err := exec.LookPath("systemctl"); err == nil {
		for _, u := range nodeUnitsForRemove(network, env) {
			if isHostBootstrapUnit(u) {
				continue
			}
			_ = exec.Command("systemctl", "kill", "-s", "SIGTERM", "--kill-who=all", u).Run()
			steps = append(steps, "sigterm "+u)
		}
	}
	time.Sleep(5 * time.Second)
	if nodeStillRunning(network, env) == "" {
		return steps
	}
	if _, err := exec.LookPath("systemctl"); err == nil {
		for _, u := range nodeUnitsForRemove(network, env) {
			if isHostBootstrapUnit(u) {
				continue
			}
			steps = append(steps, systemctlForceKillDisable(u)...)
		}
	}
	killNodeProcesses(network, env)
	steps = append(steps, "sigkill leftover processes for "+network+"/"+env)
	return steps
}

// systemctlForceKillDisable — last resort after graceful + SIGTERM failed.
func systemctlForceKillDisable(unit string) []string {
	unit = strings.TrimSpace(unit)
	if unit == "" || isHostBootstrapUnit(unit) {
		return nil
	}
	steps := []string{}
	_ = exec.Command("systemctl", "disable", unit).Run()
	_ = exec.Command("systemctl", "mask", "--runtime", unit).Run()
	_ = exec.Command("systemctl", "kill", "-s", "SIGKILL", "--kill-who=all", unit).Run()
	steps = append(steps, "kill-forced "+unit)
	_ = exec.Command("systemctl", "reset-failed", unit).Run()
	steps = append(steps, "disabled "+unit)
	return steps
}

// stopPerNodeAgentsForRemove stops leaf api-agent (Go RPC proxy) + system-agent.
// Never stops the host tip. When we ARE the leaf api-agent, keep self alive for ACK.
func stopPerNodeAgentsForRemove(network, env string) []string {
	steps := []string{}
	self := removeTargetsSelfAgent(network, env)
	for _, u := range filterTeardownUnits(perNodeAgentUnits(network, env)) {
		if isHostBootstrapUnit(u) {
			continue
		}
		if self && unitBelongsToThisProcess(u) {
			steps = append(steps, "kept self alive for ACK: "+u)
			continue
		}
		steps = append(steps, systemctlStopDisableTimed(u)...)
	}
	if !self {
		killEnvAgentProcesses(network, env)
		steps = append(steps, "stopped per-node Go RPC proxy + system-agent for "+network+"/"+env)
	} else {
		killSiblingEnvAgentProcesses(network, env)
		steps = append(steps, "stopped sibling per-node agents (self api-agent kept for ACK)")
	}
	return steps
}

func systemctlStopDisableTimed(unit string) []string {
	unit = strings.TrimSpace(unit)
	if unit == "" || isHostBootstrapUnit(unit) {
		return nil
	}
	steps := []string{}
	// Disable first — Restart=on-failure must not win a race after timed SIGKILL.
	_ = exec.Command("systemctl", "disable", unit).Run()
	ctx, cancel := context.WithTimeout(context.Background(), systemctlStopTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, "systemctl", "stop", unit)
	_ = cmd.Run()
	if ctx.Err() != nil {
		_ = exec.Command("systemctl", "kill", "-s", "SIGKILL", "--kill-who=all", unit).Run()
		steps = append(steps, "kill-forced "+unit+" after stop timeout")
	} else {
		steps = append(steps, "stopped "+unit)
	}
	_ = exec.Command("systemctl", "reset-failed", unit).Run()
	steps = append(steps, "disabled "+unit)
	return steps
}

// unitBelongsToThisProcess — exact systemd unit match via cgroup (not "any api-agent").
func unitBelongsToThisProcess(unit string) bool {
	want := strings.TrimSuffix(strings.TrimSpace(unit), ".service")
	if want == "" || isHostBootstrapUnit(want+".service") {
		return false
	}
	if isHostTipProcess() {
		return false
	}
	if data, err := os.ReadFile("/proc/self/cgroup"); err == nil {
		if strings.Contains(string(data), want+".service") {
			return true
		}
	}
	// Fallback: unit name encodes network/env matching our env.
	myNet := normalizeNetwork(os.Getenv("TRON_NETWORK"))
	myEnv := normalizeEnv(envFirst("", "RPCNODE_ENV", "TRON_ENV"))
	if myEnv == "" {
		return false
	}
	candidates := perNodeAgentUnits(myNet, myEnv)
	if myNet == "" {
		candidates = append(candidates, perNodeAgentUnits("tron", myEnv)...)
	}
	for _, c := range candidates {
		if strings.TrimSuffix(c, ".service") == want {
			exe, _ := os.Executable()
			exe = strings.ToLower(exe)
			if strings.Contains(want, "api-agent") && strings.Contains(exe, "api-agent") {
				return true
			}
			if strings.Contains(want, "system-agent") && strings.Contains(exe, "system-agent") {
				return true
			}
		}
	}
	return false
}

func killSiblingEnvAgentProcesses(network, env string) {
	for _, u := range filterTeardownUnits(perNodeAgentUnits(network, env)) {
		if isHostBootstrapUnit(u) || unitBelongsToThisProcess(u) {
			continue
		}
		base := strings.TrimSuffix(u, ".service")
		_ = exec.Command("systemctl", "kill", "--kill-who=all", u).Run()
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`pgrep -af '(rpcnode|tron)-(api|system)-agent' | grep -F '%s' | grep -vE 'rpcnode-api-agent\.service|rpcnode-system-agent\.service' | awk '{print $1}' | while read p; do kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null; done`,
			base,
		)).Run()
	}
}

// teardownLeafSystemUnits — phase 2: remove node + leaf agent systemd units + registry.
// When teardownDelay>0 (self-leaf), only node units/registry go now; agent self-unit
// is scheduled separately. Tip host bootstrap units are never touched.
func teardownLeafSystemUnits(network, env string, teardownDelay time.Duration) (steps []string) {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)

	_, metaSteps, _ := wipeNodeMetadataForRemove(network, env)
	steps = append(steps, metaSteps...)

	if teardownDelay > 0 {
		// Own api-agent unit stays until delayed teardown; siblings already stopped.
		steps = append(steps, "agent unit teardown delayed")
		return steps
	}

	stopUnits := filterTeardownUnits(perNodeAgentUnits(network, env))
	if _, err := exec.LookPath("systemctl"); err == nil {
		for _, u := range stopUnits {
			if isHostBootstrapUnit(u) {
				continue
			}
			steps = append(steps, systemctlStopDisableTimed(u)...)
		}
	}
	for _, u := range stopUnits {
		if isHostBootstrapUnit(u) {
			continue
		}
		p := filepath.Join("/etc/systemd/system", u)
		if rmErr := os.Remove(p); rmErr == nil {
			steps = append(steps, "removed unit "+filepath.Base(p))
		}
		_ = os.RemoveAll(p + ".d")
	}
	killEnvAgentProcesses(network, env)
	if _, err := exec.LookPath("systemctl"); err == nil {
		_ = exec.Command("systemctl", "daemon-reload").Run()
		steps = append(steps, "daemon-reload")
	}
	return steps
}

// wipeNodeDataDirsForRemove — phase 3: datadir/etc/opt/logs only (units already gone).
// Also prunes empty /data/<network> (etc/opt/log) parents after env wipe.
func wipeNodeDataDirsForRemove(network, env string) (paths []string, steps []string, err error) {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	if network == "" {
		network = "tron"
	}
	if removeJobIsSuperseded(network, env) {
		return nil, steps, errRemoveSuperseded
	}

	// Belt-and-suspenders: leaf must stay down mid-wipe.
	steps = append(steps, stopPerNodeAgentsForRemove(network, env)...)
	killNodeProcesses(network, env)

	for _, p := range nodeDataPaths(network, env) {
		if removeJobIsSuperseded(network, env) {
			return paths, steps, errRemoveSuperseded
		}
		if _, stErr := os.Stat(p); stErr != nil {
			continue
		}
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`fuser -k %q >/dev/null 2>&1 || true`, p,
		)).Run()
		if rmErr := os.RemoveAll(p); rmErr != nil {
			return paths, steps, fmt.Errorf("remove %s: %w", p, rmErr)
		}
		paths = append(paths, p)
		steps = append(steps, "deleted "+p)
	}
	for _, p := range nodeDataPaths(network, env) {
		if st, stErr := os.Stat(p); stErr == nil && st.IsDir() {
			return paths, steps, fmt.Errorf("delete_files incomplete: %s still exists", p)
		}
	}
	if removeJobIsSuperseded(network, env) {
		return paths, steps, errRemoveSuperseded
	}

	for _, s := range pruneEmptyNetworkParents(network, env) {
		steps = append(steps, s)
		if strings.HasPrefix(s, "removed empty ") {
			paths = append(paths, strings.TrimPrefix(s, "removed empty "))
		}
	}
	return paths, steps, nil
}

// wipeNodeFilesForRemove — sync helper (tests / removeNodeEnv): units then data dirs.
func wipeNodeFilesForRemove(network, env string) (paths []string, steps []string, err error) {
	steps = append(steps, teardownLeafSystemUnits(network, env, 0)...)
	dataPaths, dataSteps, err := wipeNodeDataDirsForRemove(network, env)
	paths = append(paths, dataPaths...)
	steps = append(steps, dataSteps...)
	return paths, steps, err
}

func wipeNodeMetadataForRemove(network, env string) (paths []string, steps []string, err error) {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)

	for _, u := range nodeUnitsForRemove(network, env) {
		if isHostBootstrapUnit(u) || isStockSharedNodeUnit(network, u) {
			continue
		}
		p := filepath.Join("/etc/systemd/system", u)
		if rmErr := os.Remove(p); rmErr == nil {
			paths = append(paths, p)
			steps = append(steps, "removed unit "+filepath.Base(p))
		}
		_ = os.RemoveAll(p + ".d")
	}

	for _, p := range nodeRegistryPaths(network, env) {
		if rmErr := os.Remove(p); rmErr == nil {
			paths = append(paths, p)
			steps = append(steps, "removed "+p)
		}
	}

	if _, lookErr := exec.LookPath("systemctl"); lookErr == nil {
		_ = exec.Command("systemctl", "daemon-reload").Run()
		steps = append(steps, "daemon-reload")
	}

	clearRegisterIfNoNodes()
	return paths, steps, nil
}

// removeNodeEnv — kept for tests / callers that want a single-shot sync remove.
// Order: kill → teardown units → wipe files (if requested).
func removeNodeEnv(req nodeRemoveRequest) (map[string]any, error) {
	result, err := stopNodeStackForRemove(req.Network, req.Env)
	if err != nil {
		return nil, err
	}
	steps, _ := result["steps"].([]string)
	removedPaths := []string{}

	teardownSteps := teardownLeafSystemUnits(req.Network, req.Env, 0)
	steps = append(steps, teardownSteps...)

	if req.DeleteFiles {
		paths, wipeSteps, wipeErr := wipeNodeDataDirsForRemove(req.Network, req.Env)
		if wipeErr != nil {
			return nil, wipeErr
		}
		removedPaths = append(removedPaths, paths...)
		steps = append(steps, wipeSteps...)
	}

	return map[string]any{
		"ok":            true,
		"env":           normalizeEnv(req.Env),
		"network":       normalizeNetwork(req.Network),
		"delete_files":  req.DeleteFiles,
		"removed_paths": removedPaths,
		"steps":         steps,
		"remove_order":  removePhaseOrder(),
	}, nil
}

// scheduleAgentTeardown stops/removes per-node api/system agent units after a delay.
// Never touches host bootstrap units (rpcnode-api-agent.service / rpcnode-system-agent.service).
func scheduleAgentTeardown(network, env string) {
	scheduleAgentTeardownDelayed(network, env, agentTeardownDelay, true)
}

func scheduleAgentTeardownDelayed(network, env string, delay time.Duration, wipeOK bool) {
	network = normalizeNetwork(network)
	if network == "" {
		network = "tron"
	}
	env = normalizeEnv(env)
	key := network + "/" + env

	if _, loaded := agentTeardownOnce.LoadOrStore(key, struct{}{}); loaded {
		fmt.Fprintf(os.Stderr, "nodes/remove: teardown already scheduled for %s\n", key)
		return
	}

	stopUnits := filterTeardownUnits(perNodeAgentUnits(network, env))
	run := func() {
		defer agentTeardownOnce.Delete(key)
		if removeJobIsSuperseded(network, env) {
			fmt.Fprintf(os.Stderr, "nodes/remove: phase3 skip %s — superseded by provision\n", key)
			return
		}
		fmt.Fprintf(os.Stderr, "nodes/remove: phase3 removing per-node agent units for %s after %s (units=%v; host tip untouched; wipe_ok=%v)\n",
			key, delay, stopUnits, wipeOK)

		// Last chance: fullnode must not outlive agent teardown.
		if nodeStillRunning(network, env) != "" {
			_ = escalateStopNode(network, env)
		}

		if _, err := exec.LookPath("systemctl"); err == nil {
			for _, u := range stopUnits {
				if isHostBootstrapUnit(u) {
					continue
				}
				_ = systemctlStopDisableTimed(u)
			}
		}

		for _, u := range stopUnits {
			if isHostBootstrapUnit(u) {
				continue
			}
			_ = os.Remove(filepath.Join("/etc/systemd/system", u))
			_ = os.RemoveAll(filepath.Join("/etc/systemd/system", u+".d"))
		}

		killEnvAgentProcesses(network, env)

		if _, err := exec.LookPath("systemctl"); err == nil {
			_ = exec.Command("systemctl", "daemon-reload").Run()
		}
		if wipeOK {
			writeRemoveJob(network, env, "completed", "", nil)
		} else {
			// Keep error status from wipe — agents are still torn down so tip stays healthy.
			writeRemoveJob(network, env, "error", "wipe incomplete; per-node agents removed", nodeDataPaths(network, env))
		}
	}

	if delay <= 0 {
		run()
		return
	}
	time.AfterFunc(delay, run)
}

// perNodeAgentUnits — systemd units belonging to one provisioned node (network+env).
// Never includes host bootstrap control-plane units.
func perNodeAgentUnits(network, env string) []string {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	if env == "" {
		return nil
	}
	switch network {
	case "bitcoin":
		return []string{
			fmt.Sprintf("rpcnode-api-agent-bitcoin-%s.service", env),
			fmt.Sprintf("rpcnode-system-agent-bitcoin-%s.service", env),
		}
	case "solana":
		return []string{
			fmt.Sprintf("rpcnode-api-agent-solana-%s.service", env),
			fmt.Sprintf("rpcnode-system-agent-solana-%s.service", env),
		}
	case "tron", "":
		// Canon first; legacy env-only still torn down on old hosts.
		return []string{
			fmt.Sprintf("rpcnode-api-agent-tron-%s.service", env),
			fmt.Sprintf("rpcnode-system-agent-tron-%s.service", env),
			fmt.Sprintf("rpcnode-api-agent-%s.service", env),
			fmt.Sprintf("rpcnode-system-agent-%s.service", env),
		}
	default:
		return []string{
			fmt.Sprintf("rpcnode-api-agent-%s-%s.service", network, env),
			fmt.Sprintf("rpcnode-system-agent-%s-%s.service", network, env),
		}
	}
}

func nodeUnitsForRemove(network, env string) []string {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	switch network {
	case "bitcoin":
		return []string{fmt.Sprintf("bitcoin-%s.service", env)}
	case "doge":
		return []string{fmt.Sprintf("doge-%s.service", env)}
	case "zcash":
		return []string{fmt.Sprintf("zcash-%s.service", env)}
	case "sui":
		return []string{
			fmt.Sprintf("sui-%s.service", env),
			fmt.Sprintf("sui-%s-snapshot.service", env),
		}
	case "aptos":
		return []string{fmt.Sprintf("aptos-%s.service", env)}
	case "avalanche":
		return []string{fmt.Sprintf("avalanche-%s.service", env)}
	case "ltc", "dash", "bch":
		return []string{fmt.Sprintf("%s-%s.service", network, env)}
	case "solana":
		return []string{fmt.Sprintf("solana-%s.service", env)}
	case "ethereum":
		return []string{
			fmt.Sprintf("ethereum-geth-%s.service", env),
			fmt.Sprintf("ethereum-lighthouse-%s.service", env),
		}
	case "bsc":
		return []string{
			fmt.Sprintf("bsc-%s.service", env),
			fmt.Sprintf("bsc-%s-snapshot.service", env),
		}
	case "hyperliquid":
		return []string{fmt.Sprintf("hyperliquid-%s.service", env)}
	case "arb":
		return []string{fmt.Sprintf("arb-%s.service", env)}
	case "robinhood":
		return []string{
			fmt.Sprintf("robinhood-%s.service", env),
			fmt.Sprintf("robinhood-%s-snapshot.service", env),
		}
	case "optimism":
		return []string{
			fmt.Sprintf("optimism-%s.service", env),
			fmt.Sprintf("optimism-op-node-%s.service", env),
		}
	case "base":
		return []string{
			fmt.Sprintf("base-%s.service", env),
			fmt.Sprintf("base-consensus-%s.service", env),
		}
	case "xrpl":
		return []string{
			fmt.Sprintf("xrpl-%s.service", env),
			fmt.Sprintf("xrpl-clio-%s.service", env),
		}
	case "stellar":
		return []string{fmt.Sprintf("stellar-%s.service", env)}
	case "ton":
		// Stock MyTonCtrl units are host-global (one_env_per_host) — tear down with leaf.
		return []string{
			fmt.Sprintf("ton-%s.service", env),
			fmt.Sprintf("ton-%s-bootstrap.service", env),
			"ton-http-api.service",
			"ton_http_api.service",
			"mytoncore.service",
			"validator.service",
		}
	case "etc":
		return []string{fmt.Sprintf("etc-%s.service", env)}
	case "cardano":
		return []string{
			fmt.Sprintf("cardano-%s.service", env),
			fmt.Sprintf("cardano-ogmios-%s.service", env),
			fmt.Sprintf("cardano-%s-snapshot.service", env),
		}
	case "tron", "":
		return []string{
			fmt.Sprintf("tron-%s.service", env),
			fmt.Sprintf("tron-%s-snapshot.service", env),
		}
	default:
		out := []string{fmt.Sprintf("%s-%s.service", network, env)}
		if prof := lookupPortProfile(network, env); prof.ServiceUnit != "" &&
			prof.ServiceUnit != out[0] {
			out = append(out, prof.ServiceUnit)
		}
		return out
	}
}

func filterTeardownUnits(units []string) []string {
	out := make([]string, 0, len(units))
	seen := map[string]bool{}
	for _, u := range units {
		u = strings.TrimSpace(u)
		if u == "" || isHostBootstrapUnit(u) || seen[u] {
			continue
		}
		seen[u] = true
		out = append(out, u)
	}
	return out
}

func isHostBootstrapUnit(unit string) bool {
	base := strings.TrimSuffix(strings.TrimSpace(unit), ".service")
	switch base {
	case "rpcnode-api-agent", "rpcnode-system-agent",
		"tron-api-agent", "tron-system-agent":
		return true
	default:
		return false
	}
}

// stockSharedNodeUnits — host-global units we stop/mask but do not delete
// (MyTonCtrl validator.service lives outside RpcNode unit files).
func stockSharedNodeUnits(network string) []string {
	switch normalizeNetwork(network) {
	case "ton":
		return []string{
			"validator.service",
			"mytoncore.service",
			"ton-http-api.service",
			"ton_http_api.service",
		}
	default:
		return nil
	}
}

func isStockSharedNodeUnit(network, unit string) bool {
	want := strings.TrimSpace(unit)
	for _, u := range stockSharedNodeUnits(network) {
		if u == want {
			return true
		}
	}
	return false
}

func unitsToPinForRemove(network, env string) []string {
	out := append([]string{}, nodeUnitsForRemove(network, env)...)
	out = append(out, perNodeAgentUnits(network, env)...)
	return filterTeardownUnits(out)
}

// pinUnitDown — disable + mask + SIGTERM the cgroup. Restart=always cannot
// respawn a masked unit; watchdog skips masked/disabled.
func pinUnitDown(unit string) []string {
	unit = strings.TrimSpace(unit)
	if unit == "" || isHostBootstrapUnit(unit) {
		return nil
	}
	_ = exec.Command("systemctl", "disable", unit).Run()
	// --runtime: do not replace /etc unit files with /dev/null (stock TON validator).
	_ = exec.Command("systemctl", "mask", "--runtime", unit).Run()
	_ = exec.Command("systemctl", "kill", "-s", "SIGTERM", "--kill-who=all", unit).Run()
	_ = exec.Command("systemctl", "mask", "--runtime", unit).Run()
	return []string{"pinned " + unit}
}

func unpinUnit(unit string) {
	unit = strings.TrimSpace(unit)
	if unit == "" || isHostBootstrapUnit(unit) {
		return
	}
	_ = exec.Command("systemctl", "unmask", "--runtime", unit).Run()
	_ = exec.Command("systemctl", "unmask", unit).Run()
}

func unpinAllRemovePins(network, env string) []string {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return nil
	}
	var steps []string
	for _, u := range unitsToPinForRemove(network, env) {
		unpinUnit(u)
		steps = append(steps, "unmasked "+u)
	}
	return steps
}

// systemdUnitBlocksRemove — oneshot RemainAfterExit + SubState=exited is a
// wrapper linger (TON ton-<env>.service), not a live daemon.
func systemdUnitBlocksRemove(activeState, subState, typ, remainAfterExit string) bool {
	activeState = strings.ToLower(strings.TrimSpace(activeState))
	subState = strings.ToLower(strings.TrimSpace(subState))
	typ = strings.ToLower(strings.TrimSpace(typ))
	remain := strings.ToLower(strings.TrimSpace(remainAfterExit))
	linger := typ == "oneshot" && (remain == "yes" || remain == "1" || remain == "true")
	if linger && (subState == "exited" || subState == "dead") {
		return false
	}
	switch activeState {
	case "active", "activating", "reloading":
		return true
	}
	switch subState {
	case "activating", "deactivating", "running", "start", "start-pre", "start-post":
		return true
	}
	return false
}

func unitStillBlockingRemove(unit string) string {
	unit = strings.TrimSpace(unit)
	if unit == "" {
		return ""
	}
	out, err := exec.Command("systemctl", "show",
		"-p", "ActiveState", "-p", "SubState", "-p", "Type", "-p", "RemainAfterExit",
		unit).CombinedOutput()
	if err != nil {
		return ""
	}
	active, sub, typ, remain := "", "", "", ""
	for _, line := range strings.Split(string(out), "\n") {
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		switch k {
		case "ActiveState":
			active = v
		case "SubState":
			sub = v
		case "Type":
			typ = v
		case "RemainAfterExit":
			remain = v
		}
	}
	if systemdUnitBlocksRemove(active, sub, typ, remain) {
		return "unit-" + active + "/" + sub + ":" + unit
	}
	return ""
}

func killNodeProcesses(network, env string) {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	prof := lookupPortProfile(network, env)
	etc, data, opt := prof.EtcPath, prof.DataPath, prof.OptPath
	if etc == "" {
		etc = filepath.Join("/etc", network, env)
	}
	if data == "" {
		data = filepath.Join("/data", network, env)
	}
	if opt == "" {
		opt = filepath.Join("/opt", network, env)
	}
	pathGrep := fmt.Sprintf("%s|%s|%s|%s-%s", etc, data, opt, network, env)

	switch network {
	case "bitcoin":
		// /proc scan — more reliable than pgrep|grep (cli stop orphans, short argv).
		killCoreDaemonProcs("bitcoind", etc, data, opt, "bitcoin-"+env)
	case "doge":
		killCoreDaemonProcs("dogecoind", etc, data, opt, "doge-"+env)
	case "zcash":
		killCoreDaemonProcs("zebrad", etc, data, opt, "zcash-"+env)
		killCoreDaemonProcs("zcashd", etc, data, opt, "zcash-"+env) // legacy EOL client
	case "sui":
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`pgrep -af 'sui-node' | grep -E %q | awk '{print $1}' | while read p; do kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null; done`,
			pathGrep,
		)).Run()
	case "aptos":
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`pgrep -af 'aptos-node' | grep -E %q | awk '{print $1}' | while read p; do kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null; done`,
			pathGrep,
		)).Run()
	case "avalanche":
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`pgrep -af 'avalanchego' | grep -E %q | awk '{print $1}' | while read p; do kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null; done`,
			pathGrep,
		)).Run()
	case "ltc", "dash", "bch":
		if client, ok := lookupCoreLike(network); ok {
			killCoreDaemonProcs(client.Daemon, etc, data, opt, network+"-"+env)
		}
	case "solana":
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`pgrep -af 'agave-validator|solana-test-validator' | grep -E %q | awk '{print $1}' | while read p; do kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null; done`,
			pathGrep,
		)).Run()
	case "ethereum":
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`pgrep -af 'geth|lighthouse' | grep -E %q | awk '{print $1}' | while read p; do kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null; done`,
			pathGrep,
		)).Run()
	case "bsc":
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`pgrep -af 'geth|aria2c|fetch-snapshot' | grep -E %q | awk '{print $1}' | while read p; do kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null; done`,
			pathGrep,
		)).Run()
	case "hyperliquid":
		// Pure /proc kill — never `pgrep … hl-node` (HL singleton panics on that argv).
		hint := data
		if hint == "" {
			hint = "hyperliquid"
		}
		killProcsMatching([]string{"hl-node", "hl-visor"}, hint)
		killProcsMatching([]string{"hl-visor-" + env})
	case "arb", "robinhood":
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`pgrep -af 'nitro' | grep -E %q | awk '{print $1}' | while read p; do kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null; done`,
			pathGrep,
		)).Run()
	case "optimism":
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`pgrep -af 'op-geth|op-node' | grep -E %q | awk '{print $1}' | while read p; do kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null; done`,
			pathGrep,
		)).Run()
	case "base":
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`pgrep -af 'base-reth-node|base-consensus' | grep -E %q | awk '{print $1}' | while read p; do kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null; done`,
			pathGrep,
		)).Run()
	case "xrpl":
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`pgrep -af 'xrpld|rippled|clio_server|clio' | grep -E %q | awk '{print $1}' | while read p; do kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null; done`,
			pathGrep,
		)).Run()
	case "stellar":
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`pgrep -af 'stellar-rpc|stellar-core' | grep -E %q | awk '{print $1}' | while read p; do kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null; done`,
			pathGrep,
		)).Run()
		killProcsMatching([]string{"stellar-rpc"}, "stellar/"+env)
		killProcsMatching([]string{"stellar-core"}, "stellar/"+env)
	case "ton":
		_ = exec.Command("bash", "-lc",
			`pgrep -af 'validator-engine|mytoncore|ton-http-api|ton_http_api' | grep -vE 'rpcnode-(api|system)-agent|pgrep|grep' | awk '{print $1}' | while read p; do kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null; done`,
		).Run()
		killProcsMatching([]string{"validator-engine"})
		killProcsMatching([]string{"mytoncore"})
		killProcsMatching([]string{"ton-http-api"})
	case "etc":
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`pgrep -af 'geth' | grep -E %q | awk '{print $1}' | while read p; do kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null; done`,
			pathGrep,
		)).Run()
		killCoreDaemonProcs("geth", etc, data, opt, "etc-"+env)
	case "cardano":
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`pgrep -af 'cardano-node|ogmios|mithril-client' | grep -E %q | awk '{print $1}' | while read p; do kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null; done`,
			pathGrep,
		)).Run()
	case "tron", "":
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`pgrep -af 'java.*(FullNode\.jar|java-tron)' | grep -E %q | awk '{print $1}' | while read p; do kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null; done; pkill -f 'FullNode_output-directory.*/(data/tron/%s|opt/tron/%s)' 2>/dev/null || true`,
			pathGrep, env, env,
		)).Run()
	default:
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`pgrep -af '.' | grep -E %q | grep -vE 'rpcnode-(api|system)-agent|pgrep|grep' | awk '{print $1}' | while read p; do kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null; done`,
			pathGrep,
		)).Run()
	}
}

// killCoreDaemonProcs — SIGTERM then SIGKILL any matching Core daemon for this env.
// Matches cmdline against etc/data/opt OR unit slug (covers -conf= and datadir= forms).
func killCoreDaemonProcs(binName string, markers ...string) {
	binName = strings.TrimSpace(binName)
	if binName == "" {
		return
	}
	for _, m := range markers {
		m = strings.TrimSpace(m)
		if m == "" {
			continue
		}
		killProcsMatching([]string{binName}, m)
	}
}

// nodeStillRunning — non-empty when fullnode/runtime for network/env is still up.
func nodeStillRunning(network, env string) string {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	switch network {
	case "stellar":
		if s := stellarDaemonStillRunning(env); s != "" {
			return s
		}
	case "bitcoin", "doge", "ltc", "dash", "bch", "zcash":
		// Daemon process is truth. Ignore systemd activating/deactivating after
		// kill — systemd leftovers must not block remove ACK.
		// zcash: zebrad (current) or leftover zcashd.
		return nodeDaemonStillRunning(network, env)
	case "ton":
		if s := tonDaemonStillRunning(); s != "" {
			return s
		}
	}
	// Any node unit still active/activating = not removed.
	// Oneshot RemainAfterExit (TON ton-<env>.service) stays "active" after
	// ExecStart exits — that is not the daemon. Ignore linger; trust processes.
	if _, err := exec.LookPath("systemctl"); err == nil {
		for _, u := range nodeUnitsForRemove(network, env) {
			if isHostBootstrapUnit(u) {
				continue
			}
			if reason := unitStillBlockingRemove(u); reason != "" {
				return reason
			}
		}
	}
	return ""
}

func tonDaemonStillRunning() string {
	needles := []string{"validator-engine", "mytoncore", "ton-http-api", "ton_http_api"}
	if hit := procCmdlineContains(needles); hit != "" {
		return hit
	}
	return ""
}

func stellarDaemonStillRunning(env string) string {
	env = normalizeEnv(env)
	markers := []string{
		"/etc/stellar/" + env,
		"/data/stellar/" + env,
		"stellar-" + env,
	}
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return ""
	}
	for _, e := range entries {
		raw, err := os.ReadFile("/proc/" + e.Name() + "/cmdline")
		if err != nil || len(raw) == 0 {
			continue
		}
		cmd := strings.ReplaceAll(string(raw), "\x00", " ")
		low := strings.ToLower(cmd)
		if !strings.Contains(low, "stellar-rpc") && !strings.Contains(low, "stellar-core") {
			continue
		}
		for _, m := range markers {
			if strings.Contains(cmd, m) {
				return strings.TrimSpace(cmd)
			}
		}
	}
	return ""
}

func nodeDaemonStillRunning(network, env string) string {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	prof := lookupPortProfile(network, env)
	etc, data, opt := prof.EtcPath, prof.DataPath, prof.OptPath
	if etc == "" {
		etc = filepath.Join("/etc", network, env)
	}
	if data == "" {
		data = filepath.Join("/data", network, env)
	}
	if opt == "" {
		opt = filepath.Join("/opt", network, env)
	}
	bins := []string{"bitcoind"}
	switch network {
	case "doge":
		bins = []string{"dogecoind"}
	case "zcash":
		bins = []string{"zebrad", "zcashd"}
	case "ltc":
		bins = []string{"litecoind"}
	case "dash":
		bins = []string{"dashd"}
	case "bch":
		bins = []string{"bitcoind"}
	}
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return ""
	}
	for _, e := range entries {
		raw, err := os.ReadFile("/proc/" + e.Name() + "/cmdline")
		if err != nil || len(raw) == 0 {
			continue
		}
		cmd := strings.ReplaceAll(string(raw), "\x00", " ")
		matched := false
		for _, bin := range bins {
			if strings.Contains(cmd, bin) {
				matched = true
				break
			}
		}
		if !matched {
			continue
		}
		if strings.Contains(cmd, etc) || strings.Contains(cmd, data) || strings.Contains(cmd, opt) ||
			strings.Contains(cmd, network+"-"+env) {
			return strings.TrimSpace(cmd)
		}
	}
	return ""
}

func killEnvAgentProcesses(network, env string) {
	// Only touch processes tied to per-node unit names — never host bootstrap tip.
	for _, u := range filterTeardownUnits(perNodeAgentUnits(network, env)) {
		if isHostBootstrapUnit(u) {
			continue
		}
		base := strings.TrimSuffix(u, ".service")
		_ = exec.Command("systemctl", "kill", "--kill-who=all", u).Run()
		_ = exec.Command("bash", "-lc", fmt.Sprintf(
			`pgrep -af '(rpcnode|tron)-(api|system)-agent' | grep -F '%s' | grep -v '/rpcnode-api-agent$' | grep -v '/rpcnode-system-agent$' | awk '{print $1}' | while read p; do
  # Never kill host tip PIDs (unit rpcnode-api-agent.service / rpcnode-system-agent.service).
  unit=$(ps -o unit= -p "$p" 2>/dev/null | tr -d ' ')
  case "$unit" in
    rpcnode-api-agent.service|rpcnode-system-agent.service|tron-api-agent.service|tron-system-agent.service) continue ;;
  esac
  kill "$p" 2>/dev/null; sleep 0.2; kill -9 "$p" 2>/dev/null
done`,
			base,
		)).Run()
	}
}

func nodeDataPaths(network, env string) []string {
	network = normalizeNetwork(network)
	if network == "" {
		network = "tron"
	}
	env = normalizeEnv(env)
	prof := lookupPortProfile(network, env)
	etc, data, opt := prof.EtcPath, prof.DataPath, prof.OptPath
	if etc == "" {
		etc = filepath.Join("/etc", network, env)
	}
	if data == "" {
		data = filepath.Join("/data", network, env)
	}
	if opt == "" {
		opt = filepath.Join("/opt", network, env)
	}
	paths := []string{
		etc,
		data,
		opt,
		filepath.Join("/var/lib/rpcnode", network+"-"+env),
		filepath.Join("/var/log", network, env),
		filepath.Join("/var/log", network, env+"-snapshot.log"),
		filepath.Join("/run", network+"-"+env),
	}
	if network == "tron" && env == "mainnet" {
		paths = append(paths, "/var/log/tron/mainnet-snapshot.log")
	}
	if network == "arb" {
		paths = append(paths,
			filepath.Join("/etc", "arb", env),
			filepath.Join("/data", "arb", env),
			filepath.Join("/opt", "arb", env),
		)
	}
	if network == "ton" {
		// MyTonCtrl global workdir (symlink or dir) — wipe with leaf (one_env_per_host).
		paths = append(paths, "/var/ton-work", filepath.Join("/var/log/ton", env))
	}
	if network == "solana" {
		// JBOD layout may place ledger/accounts/snapshots outside canonical DataPath.
		paths = append(paths, solanaExtraDataPaths(env)...)
	}
	return paths
}

func solanaExtraDataPaths(env string) []string {
	env = normalizeEnv(env)
	cands := []string{
		filepath.Join("/var/lib/rpcnode", "solana-"+env, "INSTANCE.json"),
		filepath.Join("/etc/rpcnode/nodes", "solana-"+env+".json"),
		filepath.Join("/etc/rpcnode/instances.d", "solana-"+env+".json"),
	}
	var out []string
	seen := map[string]bool{}
	add := func(p string) {
		p = filepath.Clean(strings.TrimSpace(p))
		if p == "" || p == "." || p == "/" || seen[p] {
			return
		}
		seen[p] = true
		out = append(out, p)
	}
	for _, f := range cands {
		b, err := os.ReadFile(f)
		if err != nil {
			continue
		}
		var doc map[string]any
		if json.Unmarshal(b, &doc) != nil {
			continue
		}
		for _, k := range []string{"ledger_dir", "accounts_dir", "snapshots_dir", "data_dir"} {
			if s, ok := doc[k].(string); ok {
				add(s)
			}
		}
	}
	return out
}

func nodeRegistryPaths(network, env string) []string {
	network = normalizeNetwork(network)
	paths := []string{
		filepath.Join("/etc/rpcnode/nodes", network+"-"+env+".json"),
		filepath.Join("/etc/rpcnode/instances.d", network+"-"+env+".json"),
		filepath.Join("/var/lib/rpcnode", network+"-"+env, "INSTANCE.json"),
		filepath.Join("/var/lib/rpcnode", network+"-"+env, "agent-state.json"),
	}
	if network == "tron" {
		paths = append(paths,
			filepath.Join("/etc/rpcnode/nodes", env+".json"),
			filepath.Join("/etc/rpcnode/instances.d", "tron-"+env+".json"),
		)
	}
	return paths
}

func clearRegisterIfNoNodes() {
	entries, err := os.ReadDir("/etc/rpcnode/nodes")
	if err != nil {
		return
	}
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".json") {
			return
		}
	}
	token := ""
	if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
		token = strings.TrimSpace(string(b))
	}
	body := fmt.Sprintf(`RpcNode host agent — no provisioned nodes

  Agent key : %s
  Status    : all nodes removed; re-Add node from panel to provision ports

Token file: /etc/rpcnode/agent.token
`, token)
	_ = os.WriteFile("/etc/rpcnode/register.txt", []byte(body), 0o600)
}
