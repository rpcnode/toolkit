package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

type debugLogSpec struct {
	ID     string
	Label  string
	Path   string // file tail
	Unit   string // journalctl -u
	Note   string
	Signal bool // extract error/fatal lines into a signals tab
}

func debugNetworkFindings(network, env string, units []nodeDebugUnit) []nodeDebugFinding {
	var out []nodeDebugFinding
	switch network {
	case "ton":
		out = append(out, debugTonFindings(env, units)...)
	case "xrpl":
		out = append(out, parseXRPLDebugFindings(debugNetworkText(network, env))...)
	case "tron":
		out = append(out, parseTronDebugFindings(debugNetworkText(network, env))...)
	case "solana":
		out = append(out, parseSolanaDebugFindings(debugNetworkText(network, env))...)
	case "cardano":
		out = append(out, parseCardanoDebugFindings(debugNetworkText(network, env))...)
	case "bitcoin", "doge", "ltc", "dash", "bch", "zcash":
		out = append(out, parseCoreDebugFindings(network, debugNetworkText(network, env))...)
	case "ethereum":
		out = append(out, parseEthereumDebugFindings(debugNetworkText(network, env))...)
	case "bsc", "etc":
		out = append(out, parseEVMDebugFindings(network, debugNetworkText(network, env))...)
	case "arb", "optimism", "base", "robinhood", "hyperliquid":
		out = append(out, parseL2DebugFindings(network, debugNetworkText(network, env))...)
	case "stellar":
		out = append(out, parseStellarDebugFindings(debugNetworkText(network, env))...)
	case "sui", "aptos", "avalanche":
		out = append(out, parseSnapshotClientFindings(network, debugNetworkText(network, env))...)
	}
	out = append(out, parseGenericLogFindings(network, debugNetworkText(network, env))...)
	return dedupeFindings(out)
}

func debugNetworkText(network, env string) string {
	var b strings.Builder
	for _, spec := range debugLogSpecs(network, env) {
		if spec.Path != "" {
			b.WriteString(debugReadTailText(spec.Path, 256*1024))
			b.WriteByte('\n')
		}
	}
	return b.String()
}

func debugLogSpecs(network, env string) []debugLogSpec {
	prof := lookupPortProfile(network, env)
	etc := strings.TrimSpace(prof.EtcPath)
	if etc == "" {
		etc = filepath.Join("/etc", network, env)
	}
	data := strings.TrimSpace(prof.DataPath)
	if data == "" {
		data = filepath.Join("/data", network, env)
	}
	opt := strings.TrimSpace(prof.OptPath)
	if opt == "" {
		opt = filepath.Join("/opt", network, env)
	}

	var specs []debugLogSpec
	addFile := func(id, label, path, note string, signal bool) {
		if strings.TrimSpace(path) == "" {
			return
		}
		specs = append(specs, debugLogSpec{ID: id, Label: label, Path: path, Note: note, Signal: signal})
	}
	addUnit := func(id, label, unit, note string) {
		unit = strings.TrimSpace(unit)
		if unit == "" {
			return
		}
		if !strings.HasSuffix(unit, ".service") {
			unit += ".service"
		}
		specs = append(specs, debugLogSpec{ID: id, Label: label, Unit: unit, Note: note})
	}

	addFile("host", "Host audit", "/var/log/rpcnode.log", "discrete agent ops only", false)
	addFile("leaf-agent", "Leaf agent",
		filepath.Join("/var/log/rpcnode", fmt.Sprintf("rpcnode-system-agent-%s-%s.log", network, env)),
		"", true)

	switch network {
	case "ton":
		boot := filepath.Join("/var/log/ton", env, "bootstrap.log")
		addFile("ton-bootstrap", "TON bootstrap", boot, "", true)
		addFile("ton-sync", "TON sync", filepath.Join(etc, "sync-progress.log"), "", false)
		addUnit("validator", "validator", "validator.service", "")
		addUnit("tha", "TON HTTP API", "ton-http-api.service", "")
	case "tron":
		addFile("tron", "tron.log", filepath.Join(opt, "logs", "tron.log"), "", true)
		addFile("snapshot", "Snapshot", fmt.Sprintf("/var/log/tron/%s-snapshot.log", env), "", true)
		addUnit("node", "FullNode", prof.ServiceUnit, "")
		addUnit("snap-unit", "Snapshot unit", fmt.Sprintf("tron-%s-snapshot.service", env), "")
	case "solana":
		addFile("solana", "Agave log", filepath.Join(data, "solana-"+env+".log"), "", true)
		addUnit("node", "Validator", prof.ServiceUnit, "")
	case "bitcoin", "doge", "ltc", "dash", "bch", "zcash":
		addFile("debug", "debug.log", filepath.Join(data, "debug.log"), "", true)
		addUnit("node", "Node", prof.ServiceUnit, "")
	case "ethereum":
		addUnit("geth", "Geth", prof.ServiceUnit, "")
		addUnit("lighthouse", "Lighthouse", fmt.Sprintf("ethereum-lighthouse-%s.service", env), "")
	case "bsc", "etc":
		addUnit("node", "Node", prof.ServiceUnit, "")
	case "arb", "robinhood":
		addFile("snapshot", "Snapshot", fmt.Sprintf("/var/log/%s/%s-snapshot.log", network, env), "", true)
		addUnit("node", "Nitro", prof.ServiceUnit, "")
		if network == "robinhood" {
			addUnit("snap-unit", "Snapshot unit", fmt.Sprintf("robinhood-%s-snapshot.service", env), "")
		}
	case "optimism":
		addUnit("op-geth", "op-geth", prof.ServiceUnit, "")
		addUnit("op-node", "op-node", fmt.Sprintf("optimism-op-node-%s.service", env), "")
	case "base":
		addUnit("reth", "base-reth", prof.ServiceUnit, "")
		addUnit("consensus", "base-consensus", fmt.Sprintf("base-consensus-%s.service", env), "")
	case "hyperliquid":
		addUnit("node", "Hyperliquid", prof.ServiceUnit, "")
	case "xrpl":
		addFile("debug", "xrpld debug.log", filepath.Join(data, "debug.log"), "", true)
		addUnit("xrpld", "xrpld", prof.ServiceUnit, "")
		addUnit("clio", "Clio", fmt.Sprintf("xrpl-clio-%s.service", env), "")
		addUnit("scylla", "Scylla", "scylla-server.service", "")
	case "stellar":
		addUnit("node", "stellar-core", prof.ServiceUnit, "")
	case "cardano":
		addFile("snapshot", "Mithril snapshot", fmt.Sprintf("/var/log/cardano/%s-snapshot.log", env), "", true)
		addUnit("node", "cardano-node", prof.ServiceUnit, "")
		addUnit("ogmios", "Ogmios", fmt.Sprintf("cardano-ogmios-%s.service", env), "")
		addUnit("snap-unit", "Snapshot unit", fmt.Sprintf("cardano-%s-snapshot.service", env), "")
	case "sui":
		addFile("snapshot", "Snapshot", fmt.Sprintf("/var/log/sui/%s-snapshot.log", env), "", true)
		addFile("sync", "Sync progress", filepath.Join(etc, "sync-progress.log"), "", false)
		addUnit("node", "sui-node", prof.ServiceUnit, "")
		addUnit("snap-unit", "Snapshot unit", fmt.Sprintf("sui-%s-snapshot.service", env), "")
	case "aptos":
		addFile("sync", "Sync progress", filepath.Join(etc, "sync-progress.log"), "", true)
		addUnit("node", "aptos-node", prof.ServiceUnit, "")
	case "avalanche":
		if latest := debugLatestFileInDir(filepath.Join("/var/log/avalanche", env)); latest != "" {
			addFile("avalanche", "avalanchego log", latest, "", true)
		}
		addUnit("node", "avalanchego", prof.ServiceUnit, "")
	default:
		addFile("snapshot", "Snapshot", fmt.Sprintf("/var/log/%s/%s-snapshot.log", network, env), "", true)
		addUnit("node", "Node", prof.ServiceUnit, "")
	}
	return specs
}

func debugCollectLogs(network, env string) []nodeDebugLog {
	var out []nodeDebugLog
	seen := map[string]bool{}
	add := func(l nodeDebugLog) {
		id := l.ID
		if id == "" {
			id = l.Label
		}
		if seen[id] || len(l.Lines) == 0 {
			return
		}
		seen[id] = true
		out = append(out, l)
	}

	for _, spec := range debugLogSpecs(network, env) {
		if spec.Path != "" {
			if spec.Signal {
				if lines := parseGenericSignalLines(debugReadTailText(spec.Path, 256*1024), 30); len(lines) > 0 {
					add(nodeDebugLog{
						ID: spec.ID + "-signals", Label: spec.Label + " signals",
						Path: spec.Path, Lines: lines, Note: spec.Note,
					})
				}
			}
			if lines, err := tailFileLines(spec.Path, 50); err == nil && len(lines) > 0 {
				add(nodeDebugLog{ID: spec.ID, Label: spec.Label, Path: spec.Path, Lines: lines, Note: spec.Note})
			}
		}
		if spec.Unit != "" {
			if raw, err := cmdOut("journalctl", "-u", spec.Unit, "-n", "25", "--no-pager", "-o", "cat"); err == nil {
				lines := nonEmptyTailLines(string(raw), 25)
				if len(lines) > 0 {
					add(nodeDebugLog{
						ID: spec.ID + "-journal", Label: spec.Label,
						Path: "journalctl -u " + spec.Unit, Lines: lines, Note: spec.Note,
					})
				}
			}
		}
	}
	return out
}

func debugProcPattern(network string) string {
	base := `apt-get|dpkg |unattended-upgr|aria2c|wget `
	switch network {
	case "ton":
		return base + `|install\.sh|mytoninstaller|mytonctrl|rpcnode-ton-bootstrap`
	case "solana":
		return base + `|agave-validator|solana-validator`
	case "tron":
		return base + `|java.*FullNode|FullNode\.jar`
	case "ethereum":
		return base + `|geth|lighthouse`
	case "bsc":
		return base + `|geth|bnbchain`
	case "etc":
		return base + `|core-geth|geth`
	case "arb", "robinhood":
		return base + `|nitro`
	case "optimism":
		return base + `|op-geth|op-node`
	case "base":
		return base + `|base-reth|base-consensus`
	case "hyperliquid":
		return base + `|hyperliquid|hl-visor|hl-node`
	case "xrpl":
		return base + `|xrpld|rippled|clio|scylla`
	case "stellar":
		return base + `|stellar-core|stellar-horizon`
	case "cardano":
		return base + `|cardano-node|ogmios|mithril-client`
	case "sui":
		return base + `|sui-node|sui-tool`
	case "aptos":
		return base + `|aptos-node`
	case "avalanche":
		return base + `|avalanchego`
	case "zcash":
		return base + `|zebrad|zcashd`
	case "bitcoin", "doge", "ltc", "dash", "bch":
		return base + `|bitcoind|dogecoind|litecoind|dashd|bitcoind`
	default:
		return base
	}
}

func debugMissingConfFindings(network, env string) []nodeDebugFinding {
	prof := lookupPortProfile(network, env)
	etc := strings.TrimSpace(prof.EtcPath)
	if etc == "" {
		etc = filepath.Join("/etc", network, env)
	}
	unit := strings.TrimSpace(prof.ServiceUnit)
	if unit != "" && !fileExists("/etc/systemd/system/"+unit) {
		return nil
	}
	name := ""
	switch network {
	case "bitcoin":
		name = "bitcoin.conf"
	case "doge":
		name = "dogecoin.conf"
	case "ltc":
		name = "litecoin.conf"
	case "dash":
		name = "dash.conf"
	case "bch":
		name = "bitcoin.conf"
	case "zcash":
		name = "zebrad.toml"
	case "xrpl":
		name = "rippled.cfg"
	case "stellar":
		name = "stellar-core.cfg"
	default:
		return nil
	}
	path := filepath.Join(etc, name)
	if fileExists(path) {
		return nil
	}
	return []nodeDebugFinding{{
		Severity: "error",
		Scope:    "network",
		Code:     "conf_missing",
		Title:    name + " missing",
		Detail:   path,
		Hint:     "Unit exists but the conf file is gone. Re-provision from the panel — Debug does not rewrite conf.",
	}}
}

func debugLatestFileInDir(dir string) string {
	ents, err := os.ReadDir(dir)
	if err != nil {
		return ""
	}
	var newest string
	var newestMod int64
	for _, e := range ents {
		if e.IsDir() {
			continue
		}
		info, err := e.Info()
		if err != nil || info.Size() == 0 {
			continue
		}
		if info.ModTime().Unix() >= newestMod {
			newestMod = info.ModTime().Unix()
			newest = filepath.Join(dir, e.Name())
		}
	}
	return newest
}

func parseGenericSignalLines(text string, n int) []string {
	if n <= 0 {
		n = 20
	}
	var pick []string
	for _, ln := range strings.Split(text, "\n") {
		ln = strings.TrimSpace(ln)
		if ln == "" {
			continue
		}
		low := strings.ToLower(ln)
		if debugLineLooksSignal(low) {
			pick = append(pick, ln)
		}
	}
	if len(pick) > n {
		pick = pick[len(pick)-n:]
	}
	return pick
}

func debugLineLooksSignal(low string) bool {
	keys := []string{
		"error", "fatal", "panic", "oom", "killed", "permission denied",
		"no space", "failed", "exception", "crash", "could not", "unable to",
		"does not have a release file", "install.sh attempt", "install.sh exit",
		"waiting for apt", "home not set", "bootstrap marker", "gib/",
		"state db error", "publisher key", "lock:", "corrupted",
		"invalid", "refused", "timeout",
	}
	for _, k := range keys {
		if strings.Contains(low, k) {
			return true
		}
	}
	return false
}

func parseGenericLogFindings(network, text string) []nodeDebugFinding {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	low := strings.ToLower(text)
	var out []nodeDebugFinding
	add := func(code, title, hint string, keys []string) {
		if hasFindingCode(out, code) {
			return
		}
		line := lastLineContaining(text, keys)
		if line == "" {
			line = lastSignalLine(text)
		}
		out = append(out, nodeDebugFinding{
			Severity: "error", Scope: "network", Code: code, Title: title,
			Detail: truncateRunesAPI(line, 240), Hint: hint,
		})
	}
	switch {
	case strings.Contains(low, "no space left") || strings.Contains(low, "enospc"):
		add("enospc", "Disk full (ENOSPC) in node logs", "Free space on the data mount. Debug does not wipe.",
			[]string{"no space", "ENOSPC"})
	case strings.Contains(low, "oom-kill") || strings.Contains(low, "out of memory") ||
		strings.Contains(low, "killed process"):
		add("oom", "OOM killer in node logs", "Host RAM / cgroup MemoryMax. See that network’s DESIGN/OPS.",
			[]string{"oom", "out of memory", "Killed process"})
	case strings.Contains(low, "permission denied") && strings.Contains(low, "lock"):
		add("datadir_lock_perm", "Datadir LOCK permission denied", "Node user cannot write the datadir. Re-provision/chown is an agent start heal — not Debug.",
			[]string{"LOCK", "Permission denied"})
	}
	_ = network
	return out
}

func parseXRPLDebugFindings(text string) []nodeDebugFinding {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	low := strings.ToLower(text)
	var out []nodeDebugFinding
	if strings.Contains(low, "invalid validator list publisher key") {
		out = append(out, nodeDebugFinding{
			Severity: "error", Scope: "network", Code: "xrpl_unl_key0",
			Title:  "XRPL UNL publisher key invalid (often 0)",
			Detail: lastLineContaining(text, []string{"publisher key"}),
			Hint:   "Stock validators-example.txt. Agent heal writes canonical UNL. Do not wipe NuDB while waiting for first validated ledger.",
		})
	}
	if strings.Contains(low, "state db error") ||
		(strings.Contains(low, "shamapstore") && strings.Contains(low, "error")) {
		out = append(out, nodeDebugFinding{
			Severity: "error", Scope: "network", Code: "xrpl_state_db",
			Title:  "XRPL SHAMapStore / state db error",
			Detail: lastLineContaining(text, []string{"state db error", "SHAMapStore"}),
			Hint:   "Often after kill during NuDB rotate. Agent can rotate db/nudb — Debug only reports.",
		})
	}
	if strings.Contains(low, "nubd close") || strings.Contains(low, "nudb close") {
		out = append(out, nodeDebugFinding{
			Severity: "warn", Scope: "network", Code: "xrpl_nudb_close",
			Title:  "NuDB closed while files moved",
			Detail: lastLineContaining(text, []string{"NuBD", "NuDB"}),
			Hint:   "Do not rotate db/nudb under a live xrpld.",
		})
	}
	if strings.Contains(text, "LoadManager") && strings.Contains(low, "stalled") {
		out = append(out, nodeDebugFinding{
			Severity: "warn", Scope: "network", Code: "xrpl_load_stall",
			Title:  "LoadManager stalled",
			Detail: lastLineContaining(text, []string{"LoadManager"}),
		})
	}
	return out
}

func parseTronDebugFindings(text string) []nodeDebugFinding {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	low := strings.ToLower(text)
	var out []nodeDebugFinding
	if strings.Contains(low, "lock: permission denied") ||
		(strings.Contains(low, "permission denied") && strings.Contains(low, "lock")) {
		out = append(out, nodeDebugFinding{
			Severity: "error", Scope: "network", Code: "tron_leveldb_lock",
			Title:  "TRON LevelDB LOCK permission denied",
			Detail: lastLineContaining(text, []string{"LOCK", "Permission"}),
			Hint:   "Snapshot extract as root then FullNode as nodeop. Agent chowns on start/collect.",
		})
	}
	if strings.Contains(low, "download snapshot fail") || strings.Contains(low, "snapshot fail") {
		out = append(out, nodeDebugFinding{
			Severity: "error", Scope: "network", Code: "tron_snapshot_fail",
			Title:  "TRON snapshot download failed",
			Detail: lastLineContaining(text, []string{"snapshot"}),
		})
	}
	return out
}

func parseSolanaDebugFindings(text string) []nodeDebugFinding {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	low := strings.ToLower(text)
	var out []nodeDebugFinding
	if strings.Contains(low, "failed to start validator") || strings.Contains(low, "unable to start") {
		out = append(out, nodeDebugFinding{
			Severity: "error", Scope: "network", Code: "solana_start_failed",
			Title:  "Agave failed to start",
			Detail: lastLineContaining(text, []string{"failed to start", "unable to start"}),
		})
	}
	if strings.Contains(low, "ledger does not exist") ||
		(strings.Contains(low, "no such file") && strings.Contains(low, "ledger")) {
		out = append(out, nodeDebugFinding{
			Severity: "error", Scope: "network", Code: "solana_ledger_missing",
			Title:  "Solana ledger path missing",
			Detail: lastLineContaining(text, []string{"ledger"}),
		})
	}
	return out
}

func parseCardanoDebugFindings(text string) []nodeDebugFinding {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	low := strings.ToLower(text)
	var out []nodeDebugFinding
	if strings.Contains(low, "mithril") && (strings.Contains(low, "error") || strings.Contains(low, "fail")) {
		out = append(out, nodeDebugFinding{
			Severity: "error", Scope: "network", Code: "cardano_mithril",
			Title:  "Mithril snapshot error",
			Detail: lastLineContaining(text, []string{"mithril", "Mithril"}),
		})
	}
	if strings.Contains(low, "ogmios") && strings.Contains(low, "error") {
		out = append(out, nodeDebugFinding{
			Severity: "warn", Scope: "network", Code: "cardano_ogmios",
			Title:  "Ogmios error",
			Detail: lastLineContaining(text, []string{"ogmios", "Ogmios"}),
		})
	}
	return out
}

func parseCoreDebugFindings(network, text string) []nodeDebugFinding {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	low := strings.ToLower(text)
	var out []nodeDebugFinding
	if (strings.Contains(low, "could not be opened") && strings.Contains(low, "config")) ||
		(strings.Contains(low, "missing") && strings.Contains(low, ".conf")) {
		out = append(out, nodeDebugFinding{
			Severity: "error", Scope: "network", Code: "core_conf_open",
			Title:  network + " config could not be opened",
			Detail: lastLineContaining(text, []string{"conf", "config"}),
			Hint:   "Start writes conf before the daemon. Re-provision if the file was wiped.",
		})
	}
	if strings.Contains(low, "corrupted") || strings.Contains(low, "rewind") && strings.Contains(low, "error") {
		out = append(out, nodeDebugFinding{
			Severity: "error", Scope: "network", Code: "core_db_corrupt",
			Title:  network + " chainstate / block index error",
			Detail: lastLineContaining(text, []string{"Corrupt", "corrupt", "rewind"}),
		})
	}
	if strings.Contains(low, "potential stale tip") || strings.Contains(low, "not downloading blocks") {
		out = append(out, nodeDebugFinding{
			Severity: "warn", Scope: "network", Code: "core_stale_tip",
			Title:  network + " not downloading blocks",
			Detail: lastLineContaining(text, []string{"stale", "downloading"}),
		})
	}
	return out
}

func parseEthereumDebugFindings(text string) []nodeDebugFinding {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	low := strings.ToLower(text)
	var out []nodeDebugFinding
	if strings.Contains(low, "jwt") && (strings.Contains(low, "invalid") || strings.Contains(low, "missing")) {
		out = append(out, nodeDebugFinding{
			Severity: "error", Scope: "network", Code: "eth_jwt",
			Title:  "Geth/Lighthouse JWT mismatch",
			Detail: lastLineContaining(text, []string{"jwt", "JWT"}),
		})
	}
	if strings.Contains(low, "beacon") && strings.Contains(low, "offline") {
		out = append(out, nodeDebugFinding{
			Severity: "warn", Scope: "network", Code: "eth_beacon_offline",
			Title:  "Beacon node offline",
			Detail: lastLineContaining(text, []string{"beacon", "Beacon"}),
		})
	}
	return out
}

func parseEVMDebugFindings(network, text string) []nodeDebugFinding {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	low := strings.ToLower(text)
	if (strings.Contains(low, "ancient") && strings.Contains(low, "error")) ||
		strings.Contains(low, "datadir already used") {
		return []nodeDebugFinding{{
			Severity: "error", Scope: "network", Code: "evm_datadir",
			Title:  network + " datadir error",
			Detail: lastLineContaining(text, []string{"ancient", "datadir"}),
		}}
	}
	return nil
}

func parseL2DebugFindings(network, text string) []nodeDebugFinding {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	low := strings.ToLower(text)
	var out []nodeDebugFinding
	if (strings.Contains(low, "init.url") && strings.Contains(low, "error")) ||
		(strings.Contains(low, "init download") && strings.Contains(low, "fail")) {
		out = append(out, nodeDebugFinding{
			Severity: "error", Scope: "network", Code: "l2_init_url",
			Title:  network + " snapshot / --init.url failed",
			Detail: lastLineContaining(text, []string{"init", "snapshot"}),
		})
	}
	if (strings.Contains(low, "batcher") && strings.Contains(low, "error")) ||
		(strings.Contains(low, "derivation") && strings.Contains(low, "reset")) {
		out = append(out, nodeDebugFinding{
			Severity: "warn", Scope: "network", Code: "l2_derivation",
			Title:  network + " derivation / batch error",
			Detail: lastLineContaining(text, []string{"derivation", "batcher"}),
		})
	}
	return out
}

func parseStellarDebugFindings(text string) []nodeDebugFinding {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	low := strings.ToLower(text)
	var out []nodeDebugFinding
	if strings.Contains(low, "history") && strings.Contains(low, "error") {
		out = append(out, nodeDebugFinding{
			Severity: "warn", Scope: "network", Code: "stellar_history",
			Title:  "Stellar history archive error",
			Detail: lastLineContaining(text, []string{"history", "HISTORY"}),
		})
	}
	if strings.Contains(low, "unable to connect") || strings.Contains(low, "lost connection") {
		out = append(out, nodeDebugFinding{
			Severity: "warn", Scope: "network", Code: "stellar_peers",
			Title:  "Stellar peer connection issue",
			Detail: lastLineContaining(text, []string{"connect", "connection"}),
		})
	}
	return out
}

func parseSnapshotClientFindings(network, text string) []nodeDebugFinding {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	low := strings.ToLower(text)
	if strings.Contains(low, "snapshot") && (strings.Contains(low, "error") || strings.Contains(low, "fail")) {
		return []nodeDebugFinding{{
			Severity: "error", Scope: "network", Code: network + "_snapshot",
			Title:  network + " snapshot / restore error",
			Detail: lastLineContaining(text, []string{"snapshot", "restore"}),
		}}
	}
	return nil
}

func lastLineContaining(text string, keys []string) string {
	var last string
	for _, ln := range strings.Split(text, "\n") {
		ln = strings.TrimSpace(ln)
		if ln == "" {
			continue
		}
		low := strings.ToLower(ln)
		for _, k := range keys {
			if k != "" && strings.Contains(low, strings.ToLower(k)) {
				last = ln
				break
			}
		}
	}
	return last
}

func lastSignalLine(text string) string {
	var last string
	for _, ln := range strings.Split(text, "\n") {
		ln = strings.TrimSpace(ln)
		if ln != "" && debugLineLooksSignal(strings.ToLower(ln)) {
			last = ln
		}
	}
	return last
}
