package main

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// healProvisionedNodesIfNeeded — after agent Update / process start, migrate
// on-disk stellar conf and bounce failed units. Healthy syncing nodes are left alone
// unless forceMigrateRestart and files actually changed.
//
// If a registered stellar leaf lost /etc (e.g. wipe raced with Update), recreate
// conf + unit from canonical/registry ports and start the node.
func healProvisionedNodesIfNeeded(forceMigrateRestart bool) []string {
	var steps []string
	seen := map[string]bool{}

	add := func(net, env string) {
		net = normalizeNetwork(net)
		env = normalizeEnv(env)
		if net != "stellar" || env == "" {
			return
		}
		key := net + "/" + env
		if seen[key] {
			return
		}
		seen[key] = true
		steps = append(steps, healStellarNodeIfNeeded(env, forceMigrateRestart)...)
	}

	for _, item := range listLocalNodeEnvs() {
		net, _ := item["network"].(string)
		env, _ := item["env"].(string)
		add(net, env)
	}
	if networkIsStellar(os.Getenv("TRON_NETWORK")) {
		add("stellar", envFirst("", "RPCNODE_ENV", "TRON_ENV"))
	}
	return steps
}

// healStellarNodeIfNeeded patches cfg/toml (strip HTTP_PORT/PEER_PORT, captive HTTP=0,
// full history). Restarts only when files changed (and forced) or unit is unhealthy.
// Wipes captive-core storage only on hard failure markers.
func healStellarNodeIfNeeded(env string, forceMigrateRestart bool) []string {
	env = normalizeEnv(env)
	if env == "" {
		return nil
	}
	cluster := lookupStellarNetwork(env)
	prof := lookupPortProfile("stellar", env)
	unit := fmt.Sprintf("stellar-%s.service", env)
	steps := []string{}

	tomlPath := filepath.Join(prof.EtcPath, "stellar-rpc.toml")
	if _, err := os.Stat(tomlPath); err != nil {
		// Registry / unit present but etc wiped — Update must self-heal, not ask for SSH.
		restored, err := restoreStellarConfFromRegistry(env)
		if err != nil {
			return []string{fmt.Sprintf("stellar/%s restore conf failed: %v", env, err)}
		}
		steps = append(steps, restored...)
		clearStaleStellarRemoveJob(env)
		if _, err := exec.LookPath("systemctl"); err == nil {
			_ = exec.Command("systemctl", "daemon-reload").Run()
			_ = exec.Command("systemctl", "reset-failed", unit).Run()
			_ = exec.Command("systemctl", "enable", unit).Run()
			out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
			if err != nil {
				steps = append(steps, fmt.Sprintf("stellar/%s restart failed: %v (%s)", env, err, strings.TrimSpace(string(out))))
				return steps
			}
			steps = append(steps, "restarted "+unit)
		}
		return steps
	}

	cfgPath := filepath.Join(prof.EtcPath, "stellar-core.cfg")
	cfgBefore, _ := os.ReadFile(cfgPath)
	if _, err := patchStellarCaptiveCorePorts(prof.EtcPath, cluster); err != nil {
		if _, err2 := ensureStellarCaptiveCoreCfg(prof.EtcPath, cluster); err2 != nil {
			return []string{fmt.Sprintf("stellar/%s cfg heal failed: %v", env, err2)}
		}
		steps = append(steps, fmt.Sprintf("stellar/%s captive-core.cfg downloaded+healed", env))
	} else if cfgAfter, _ := os.ReadFile(cfgPath); string(cfgBefore) != string(cfgAfter) {
		steps = append(steps, fmt.Sprintf("stellar/%s captive-core.cfg stripped HTTP_PORT/PEER_PORT", env))
	}

	live := resolveLivePortProfile("stellar", env)
	tomlChanged, err := ensureStellarFullHistoryToml(prof.EtcPath, live.SolHTTP)
	if err != nil {
		steps = append(steps, fmt.Sprintf("stellar/%s toml heal failed: %v", env, err))
	} else if tomlChanged {
		steps = append(steps, fmt.Sprintf("stellar/%s stellar-rpc.toml migrated (history + HTTP=0 + HTTP_QUERY=:%d)", env, live.SolHTTP))
	}

	// Futurenet: stable /usr stellar-core (protocol 27) cannot catchup protocol 28.
	protocolBad := stellarNeedsVNext(env) && (stellarJournalProtocolTooOld(unit) ||
		stellarCoreMaxProtocol(stellarCoreBinFromToml(prof.EtcPath)) < stellarFuturenetMinProtocol)
	if protocolBad {
		rpcBin, coreBin, berr := ensureStellarVNextBinaries(env)
		if berr != nil {
			steps = append(steps, fmt.Sprintf("stellar/%s vnext binaries: %v", env, berr))
		} else if patched, perr := patchStellarBinaryPaths(env, prof, rpcBin, coreBin); perr != nil {
			steps = append(steps, fmt.Sprintf("stellar/%s binary path patch: %v", env, perr))
		} else if patched {
			steps = append(steps, fmt.Sprintf("stellar/%s switched to vnext binaries (protocol≥%d)", env, stellarFuturenetMinProtocol))
		} else {
			steps = append(steps, fmt.Sprintf("stellar/%s vnext binaries already in place", env))
		}
	}

	_ = exec.Command("chown", "-R", "nodeop:nodeop", prof.DataPath).Run()

	migrated := len(steps) > 0
	failed := stellarUnitFailed(unit)
	journalBad := stellarJournalNeedsReset(unit)
	unhealthy := failed || journalBad || stellarUnitUnhealthy(unit) || protocolBad
	_ = forceMigrateRestart // CLI/update still pass true; restart follows migrate/unhealthy.

	// Toml/cfg port patches require process recycle (PEER_PORT / HTTP_QUERY).
	needRestart := unhealthy || migrated
	if !needRestart {
		return []string{fmt.Sprintf("stellar/%s healthy — no heal restart", env)}
	}

	if unhealthy || migrated {
		resetStellarCaptiveCoreRuntime(env, prof.EtcPath, prof.DataPath, cluster)
		steps = append(steps, fmt.Sprintf("stellar/%s captive-core runtime reset", env))
	}

	if _, err := exec.LookPath("systemctl"); err != nil {
		return append(steps, "systemctl missing — cannot restart "+unit)
	}
	_ = exec.Command("systemctl", "daemon-reload").Run()
	_ = exec.Command("systemctl", "reset-failed", unit).Run()
	_ = exec.Command("systemctl", "enable", unit).Run()
	out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
	if err != nil {
		steps = append(steps, fmt.Sprintf("stellar/%s restart failed: %v (%s)", env, err, strings.TrimSpace(string(out))))
		return steps
	}
	steps = append(steps, "restarted "+unit)
	return steps
}

// restoreStellarConfFromRegistry re-runs stellar provision using ports from
// /etc/rpcnode/nodes/<net>-<env>.json (fallback: canonical profile).
func restoreStellarConfFromRegistry(env string) ([]string, error) {
	env = normalizeEnv(env)
	prof := lookupPortProfile("stellar", env)
	req := nodeProvisionRequest{
		Network:      "stellar",
		Env:          env,
		PublicPort:   prof.Public,
		AgentPort:    prof.Agent,
		NodeHTTPPort: prof.NodeHTTP,
		P2PPort:      prof.P2P,
		Name:         "stellar-" + env,
	}
	if b, err := os.ReadFile(filepath.Join("/etc/rpcnode/nodes", "stellar-"+env+".json")); err == nil {
		var doc map[string]any
		if json.Unmarshal(b, &doc) == nil {
			if n := intFromAny(doc["public_port"]); n > 0 {
				req.PublicPort = n
			}
			if n := intFromAny(doc["agent_port"]); n > 0 {
				req.AgentPort = n
			}
			if n := intFromAny(doc["node_http_port"]); n > 0 {
				req.NodeHTTPPort = n
			}
			if n := intFromAny(doc["p2p_port"]); n > 0 {
				req.P2PPort = n
			}
			if name, ok := doc["name"].(string); ok && strings.TrimSpace(name) != "" {
				req.Name = strings.TrimSpace(name)
			}
		}
	}
	out, err := provisionStellarNodeEnv(req, prof)
	if err != nil {
		return nil, err
	}
	steps := []string{fmt.Sprintf("stellar/%s re-provisioned missing conf (etc/data)", env)}
	if raw, ok := out["steps"].([]string); ok {
		steps = append(steps, raw...)
	}
	return steps, nil
}

func clearStaleStellarRemoveJob(env string) {
	path := removeJobPath("stellar", env)
	b, err := os.ReadFile(path)
	if err != nil {
		return
	}
	var doc map[string]any
	if json.Unmarshal(b, &doc) != nil {
		return
	}
	st, _ := doc["status"].(string)
	switch strings.ToLower(strings.TrimSpace(st)) {
	case "deleting", "started", "error":
		writeRemoveJob("stellar", env, "aborted_heal", "aborted: leaf still registered; heal restored conf", nil)
	}
}

func stellarUnitFailed(unit string) bool {
	out, _ := exec.Command("systemctl", "is-failed", unit).CombinedOutput()
	return strings.TrimSpace(string(out)) == "failed"
}

func stellarUnitUnhealthy(unit string) bool {
	out, _ := exec.Command("systemctl", "is-active", unit).CombinedOutput()
	switch strings.TrimSpace(string(out)) {
	case "active":
		return false
	case "activating":
		// Fresh start is OK; only treat as unhealthy with failure journal.
		return stellarJournalNeedsReset(unit)
	default:
		// inactive / failed / unknown while unit file exists → heal.
		if err := exec.Command("systemctl", "cat", unit).Run(); err != nil {
			return false
		}
		return true
	}
}

func stellarJournalNeedsReset(unit string) bool {
	out, err := exec.Command("journalctl", "-u", unit, "-n", "40", "--no-pager", "-o", "cat").CombinedOutput()
	if err != nil {
		return false
	}
	return stellarJournalTextNeedsReset(string(out))
}

func stellarJournalTextNeedsReset(journal string) bool {
	low := strings.ToLower(journal)
	for _, m := range []string{
		"invalid captive core toml",
		"validators.",
		"address already in use",
		"permission denied",
		"failed to create storage directory",
		"unsupported ledger version",
		"upgrade this stellar-core",
		"error running stellar-core catchup",
	} {
		if strings.Contains(low, m) {
			return true
		}
	}
	return false
}

func stellarJournalProtocolTooOld(unit string) bool {
	out, err := exec.Command("journalctl", "-u", unit, "-n", "80", "--no-pager", "-o", "cat").CombinedOutput()
	if err != nil {
		return false
	}
	low := strings.ToLower(string(out))
	return strings.Contains(low, "unsupported ledger version") ||
		strings.Contains(low, "upgrade this stellar-core")
}

func stellarCoreBinFromToml(etc string) string {
	b, err := os.ReadFile(filepath.Join(etc, "stellar-rpc.toml"))
	if err != nil {
		return ""
	}
	for _, ln := range strings.Split(string(b), "\n") {
		t := strings.TrimSpace(ln)
		if strings.HasPrefix(t, "STELLAR_CORE_BINARY_PATH") {
			if i := strings.Index(t, "="); i >= 0 {
				return strings.Trim(strings.TrimSpace(t[i+1:]), `"'`)
			}
		}
	}
	return ""
}

// patchStellarBinaryPaths — point unit + toml at per-env vnext binaries.
func patchStellarBinaryPaths(env string, prof networkPortProfile, rpcBin, coreBin string) (bool, error) {
	changed := false
	tomlPath := filepath.Join(prof.EtcPath, "stellar-rpc.toml")
	if b, err := os.ReadFile(tomlPath); err == nil {
		reCore := regexp.MustCompile(`(?m)^(\s*STELLAR_CORE_BINARY_PATH\s*=\s*).*$`)
		want := fmt.Sprintf(`${1}%q`, coreBin)
		// regexp ReplaceAllString doesn't support %q in replacement that way — build manually.
		_ = want
		newB := reCore.ReplaceAllStringFunc(string(b), func(line string) string {
			return "STELLAR_CORE_BINARY_PATH = " + strconvQuote(coreBin)
		})
		if newB != string(b) {
			if err := os.WriteFile(tomlPath, []byte(newB), 0o644); err != nil {
				return false, err
			}
			changed = true
		}
	}
	unitPath := filepath.Join("/etc/systemd/system", fmt.Sprintf("stellar-%s.service", env))
	rpcToml := filepath.Join(prof.EtcPath, "stellar-rpc.toml")
	wantUnit := renderStellarUnit(env, prof.EtcPath, prof.DataPath, rpcBin, rpcToml)
	prev, _ := os.ReadFile(unitPath)
	if string(prev) != wantUnit {
		if err := os.WriteFile(unitPath, []byte(wantUnit), 0o644); err != nil {
			return false, err
		}
		changed = true
	}
	envPath := filepath.Join(prof.EtcPath, "toolkit.env")
	if b, err := os.ReadFile(envPath); err == nil {
		s := string(b)
		s2 := replaceEnvLine(s, "STELLAR_RPC_BIN", rpcBin)
		s2 = replaceEnvLine(s2, "STELLAR_CORE_BIN", coreBin)
		if s2 != s {
			if err := os.WriteFile(envPath, []byte(s2), 0o600); err != nil {
				return false, err
			}
			changed = true
		}
	}
	return changed, nil
}

func strconvQuote(s string) string {
	return `"` + strings.ReplaceAll(s, `"`, `\"`) + `"`
}

func replaceEnvLine(body, key, val string) string {
	re := regexp.MustCompile(`(?m)^` + regexp.QuoteMeta(key) + `=.*$`)
	line := key + "=" + val
	if re.MatchString(body) {
		return re.ReplaceAllString(body, line)
	}
	return strings.TrimRight(body, "\n") + "\n" + line + "\n"
}

func runHealProvisionedCLI() int {
	steps := []string{}
	if msg := healHostTipAgentPortFile(); msg != "" {
		steps = append(steps, msg)
	}
	if msg := healRetireOrphanLegacyTronEnvOnlyUnits(); msg != "" {
		steps = append(steps, msg)
	}
	steps = append(steps, healProvisionedNodesIfNeeded(true)...)
	// Panel Update (any prior binary) already execs this NEW binary with
	// --heal-provisioned after CDN swap — install watchdog here so first Update
	// after 0.4.89 ships the unit without a second click / full agent.sh.
	if wdSteps, err := ensureAgentWatchdog(); err != nil {
		fmt.Fprintf(os.Stderr, "ensure-watchdog: %v\n", err)
		for _, s := range append(steps, wdSteps...) {
			fmt.Println(s)
		}
		return 1
	} else {
		steps = append(steps, wdSteps...)
	}
	if logSteps, err := ensureAgentFileLogging(); err != nil {
		fmt.Fprintf(os.Stderr, "ensure-file-logging: %v\n", err)
		for _, s := range append(steps, logSteps...) {
			fmt.Println(s)
		}
		return 1
	} else {
		steps = append(steps, logSteps...)
	}
	if len(steps) == 0 {
		fmt.Println("heal-provisioned: nothing to do")
		return 0
	}
	for _, s := range steps {
		fmt.Println(s)
	}
	return 0
}

func scheduleProvisionedHealOnStartup() {
	go func() {
		time.Sleep(2 * time.Second)
		if !isHostTipProcess() && !networkIsStellar(os.Getenv("TRON_NETWORK")) {
			return
		}
		if isHostTipProcess() {
			if msg := healHostTipAgentPortFile(); msg != "" {
				log.Printf("auto-heal: %s", msg)
			}
		}
		for _, s := range healProvisionedNodesIfNeeded(false) {
			if strings.Contains(s, "healthy — no heal") || strings.Contains(s, "left running") {
				continue
			}
			log.Printf("auto-heal: %s", s)
		}
	}()
}
