package main

import (
	"fmt"
	"sort"
	"strings"
)

type nodeConfigFieldDef struct {
	Key       string
	Label     string
	Help      string
	Type      string
	Group     string
	Protected bool
	Options   []string
}

func materializeFields(format, content string, defs []nodeConfigFieldDef, protected []string) []nodeConfigField {
	prot := map[string]bool{}
	for _, k := range protected {
		prot[strings.ToLower(k)] = true
	}
	curated := map[string]nodeConfigFieldDef{}
	for _, d := range defs {
		curated[strings.ToLower(d.Key)] = d
	}

	kv := extractConfigKV(format, content)
	// Prefer keys as they appear in file (first-seen casing from parseLooseKV dual write).
	seen := map[string]bool{}
	keys := []string{}
	for _, d := range defs {
		lk := strings.ToLower(d.Key)
		if seen[lk] {
			continue
		}
		seen[lk] = true
		keys = append(keys, d.Key)
	}
	// Auto-discover every key present in the file (ini/env/toml/cfg/hocon).
	if formatSupportsKVFields(format) {
		for k := range kv {
			if strings.ToLower(k) != k {
				continue // skip camel duplicates from parseLooseKV
			}
			if seen[k] {
				continue
			}
			// Recover original casing if curated has it; else use file key.
			display := k
			if d, ok := curated[k]; ok {
				display = d.Key
			} else if v := firstKVKeyCasing(content, k); v != "" {
				display = v
			}
			seen[k] = true
			keys = append(keys, display)
		}
		sort.SliceStable(keys, func(i, j int) bool {
			di, okI := curated[strings.ToLower(keys[i])]
			dj, okJ := curated[strings.ToLower(keys[j])]
			gi, gj := "zz", "zz"
			if okI && di.Group != "" {
				gi = di.Group
			}
			if okJ && dj.Group != "" {
				gj = dj.Group
			}
			if gi != gj {
				return gi < gj
			}
			return strings.ToLower(keys[i]) < strings.ToLower(keys[j])
		})
	}

	out := make([]nodeConfigField, 0, len(keys))
	for _, key := range keys {
		lk := strings.ToLower(key)
		d, has := curated[lk]
		f := nodeConfigField{
			Key:   key,
			Label: key,
			Type:  "string",
			Group: "config",
		}
		if has {
			if d.Label != "" {
				f.Label = d.Label
			}
			f.Help = d.Help
			if d.Type != "" {
				f.Type = d.Type
			}
			f.Group = d.Group
			f.Options = d.Options
			f.Protected = d.Protected
		}
		if isLockedConfigKey(key, protected) || prot[lk] {
			f.Protected = true
			if f.Help == "" {
				f.Help = "Locked — ports / datadir / agent wiring are fixed by RpcNode catalog."
			} else if (isPortLikeKey(key) || isDataDirLikeKey(key)) && !strings.Contains(strings.ToLower(f.Help), "protect") && !strings.Contains(strings.ToLower(f.Help), "lock") {
				if isDataDirLikeKey(key) {
					f.Help = f.Help + " (locked — data directory not editable)"
				} else {
					f.Help = f.Help + " (locked — ports not editable)"
				}
			}
		}
		if v, ok := kv[lk]; ok {
			f.Value = v
		} else if v, ok := kv[key]; ok {
			f.Value = v
		}
		out = append(out, f)
	}
	return out
}

func formatSupportsKVFields(format string) bool {
	switch strings.ToLower(format) {
	case "ini", "cfg", "env", "toml", "hocon", "yaml", "yml":
		return true
	default:
		return false
	}
}

func firstKVKeyCasing(content, lowerKey string) string {
	for _, line := range strings.Split(content, "\n") {
		trim := strings.TrimSpace(line)
		if trim == "" || strings.HasPrefix(trim, "#") || strings.HasPrefix(trim, ";") || strings.HasPrefix(trim, "[") {
			continue
		}
		if !strings.Contains(trim, "=") {
			continue
		}
		k := strings.TrimSpace(strings.SplitN(trim, "=", 2)[0])
		if strings.EqualFold(k, lowerKey) {
			return k
		}
	}
	return ""
}

func extractConfigKV(format, content string) map[string]string {
	switch strings.ToLower(format) {
	case "ini", "cfg", "env", "toml", "hocon", "yaml", "yml":
		return parseLooseKV(content)
	default:
		return parseLooseKV(content)
	}
}

// parseLooseKV — key=value / key = value across ini/env/toml-ish lines (ignores sections).
func parseLooseKV(content string) map[string]string {
	out := map[string]string{}
	for _, line := range strings.Split(content, "\n") {
		trim := strings.TrimSpace(line)
		if trim == "" || strings.HasPrefix(trim, "#") || strings.HasPrefix(trim, ";") || strings.HasPrefix(trim, "//") {
			continue
		}
		if strings.HasPrefix(trim, "[") && strings.HasSuffix(trim, "]") {
			continue
		}
		sep := "="
		if i := strings.Index(trim, "="); i < 0 {
			if j := strings.Index(trim, ":"); j > 0 && formatLooksLikeYAMLKV(trim) {
				sep = ":"
			} else {
				continue
			}
		}
		parts := strings.SplitN(trim, sep, 2)
		if len(parts) != 2 {
			continue
		}
		k := strings.TrimSpace(parts[0])
		v := strings.TrimSpace(parts[1])
		v = strings.Trim(v, `"'`)
		if k == "" {
			continue
		}
		out[strings.ToLower(k)] = v
		out[k] = v
	}
	return out
}

func formatLooksLikeYAMLKV(trim string) bool {
	return strings.Contains(trim, ": ")
}

func applyConfigFields(format, oldContent, newContent string, fields map[string]string, protected []string) (string, error) {
	base := newContent
	if strings.TrimSpace(base) == "" {
		base = oldContent
	}
	switch strings.ToLower(format) {
	case "ini", "cfg", "env", "toml", "hocon", "yaml", "yml":
		for k := range fields {
			if isLockedConfigKey(k, protected) {
				return "", fmt.Errorf("cannot change locked key %q (ports / datadir / agent wiring)", k)
			}
		}
		return upsertLooseKV(base, fields), nil
	default:
		return "", fmt.Errorf("fields merge not supported for format %s — send full content", format)
	}
}

func assertProtectedUnchanged(format, oldContent, newContent string, protected []string) error {
	if strings.TrimSpace(oldContent) == "" {
		return nil
	}
	// Always block port / datadir drift (raw editor included).
	if err := assertLockedBindingsUnchanged(oldContent, newContent); err != nil {
		return err
	}
	if len(protected) == 0 {
		return nil
	}
	oldKV := parseLooseKV(oldContent)
	newKV := parseLooseKV(newContent)
	for _, k := range protected {
		ok := oldKV[strings.ToLower(k)]
		nk := newKV[strings.ToLower(k)]
		if ok == "" {
			continue
		}
		if nk != ok {
			return fmt.Errorf("protected key %q must not change (was %q)", k, ok)
		}
	}
	_ = format
	return nil
}

func isProtectedKey(key string, protected []string) bool {
	k := strings.ToLower(strings.TrimSpace(key))
	for _, p := range protected {
		if strings.ToLower(p) == k {
			return true
		}
	}
	return false
}

func upsertLooseKV(content string, updates map[string]string) string {
	if len(updates) == 0 {
		return content
	}
	want := map[string]string{}
	order := []string{}
	for k, v := range updates {
		lk := strings.ToLower(strings.TrimSpace(k))
		if lk == "" {
			continue
		}
		if _, seen := want[lk]; !seen {
			order = append(order, strings.TrimSpace(k))
		}
		want[lk] = v
	}
	seen := map[string]bool{}
	lines := strings.Split(content, "\n")
	for i, line := range lines {
		trim := strings.TrimSpace(line)
		if trim == "" || strings.HasPrefix(trim, "#") || strings.HasPrefix(trim, ";") {
			continue
		}
		if strings.HasPrefix(trim, "[") {
			continue
		}
		if !strings.Contains(trim, "=") {
			continue
		}
		parts := strings.SplitN(trim, "=", 2)
		k := strings.TrimSpace(parts[0])
		lk := strings.ToLower(k)
		if v, ok := want[lk]; ok {
			indent := line[:len(line)-len(strings.TrimLeft(line, " \t"))]
			lines[i] = indent + k + "=" + v
			seen[lk] = true
		}
	}
	out := strings.Join(lines, "\n")
	for _, k := range order {
		lk := strings.ToLower(k)
		if seen[lk] {
			continue
		}
		if !strings.HasSuffix(out, "\n") && out != "" {
			out += "\n"
		}
		out += k + "=" + want[lk] + "\n"
	}
	return out
}

func coreLikeConfigFields() []nodeConfigFieldDef {
	return []nodeConfigFieldDef{
		{Key: "dbcache", Label: "DB cache (MiB)", Help: "UTXO / leveldb cache. Higher = faster IBD/RPC, more RAM.", Type: "int", Group: "performance"},
		{Key: "txindex", Label: "txindex", Help: "Full transaction index. RpcNode full history keeps this at 1.", Type: "bool", Group: "history"},
		{Key: "prune", Label: "prune", Help: "0 = full history (required). Non-zero truncates old blocks.", Type: "int", Group: "history"},
		{Key: "rpcthreads", Label: "RPC threads", Help: "Parallel RPC worker threads for high concurrency.", Type: "int", Group: "rpc"},
		{Key: "rpcworkqueue", Label: "RPC work queue", Help: "Queued RPC jobs before overload.", Type: "int", Group: "rpc"},
		{Key: "maxconnections", Label: "Max peers", Help: "Outbound+inbound P2P connection cap.", Type: "int", Group: "p2p"},
		{Key: "maxmempool", Label: "maxmempool (MiB)", Help: "Mempool size cap.", Type: "int", Group: "performance"},
		{Key: "rpcuser", Label: "RPC user", Help: "Locked — agent / Go proxy auth.", Type: "string", Group: "auth", Protected: true},
		{Key: "rpcpassword", Label: "RPC password", Help: "Locked — agent / Go proxy auth.", Type: "string", Group: "auth", Protected: true},
		{Key: "rpcport", Label: "RPC port", Help: "Locked — catalog NodeHTTP.", Type: "int", Group: "ports", Protected: true},
		{Key: "port", Label: "P2P port", Help: "Locked — catalog P2P.", Type: "int", Group: "ports", Protected: true},
	}
}

func tronConfigFields() []nodeConfigFieldDef {
	return []nodeConfigFieldDef{
		{Key: "maxHttpConnectNumber", Label: "maxHttpConnectNumber", Help: "HTTP connection backlog for FullNode HTTP API.", Type: "int", Group: "rpc"},
		{Key: "openTransactionScanServerSide", Label: "openTransactionScanServerSide", Help: "Server-side tx scan (CPU).", Type: "bool", Group: "rpc"},
		{Key: "maxConnections", Label: "maxConnections", Help: "P2P connection cap.", Type: "int", Group: "p2p"},
	}
}

func bscConfigFields() []nodeConfigFieldDef {
	return []nodeConfigFieldDef{
		{Key: "HTTPPort", Label: "HTTPPort", Help: "Locked — catalog NodeHTTP.", Type: "int", Group: "ports", Protected: true},
		{Key: "WSPort", Label: "WSPort", Help: "Locked — WebSocket port.", Type: "int", Group: "ports", Protected: true},
		{Key: "MaxPeers", Label: "MaxPeers", Help: "P2P peer cap.", Type: "int", Group: "p2p"},
	}
}

func xrplConfigFields() []nodeConfigFieldDef {
	return []nodeConfigFieldDef{
		{Key: "port", Label: "port", Help: "Locked — catalog listen port.", Type: "string", Group: "ports", Protected: true},
		{Key: "online_delete", Label: "online_delete", Help: "Matches ledger_history when a window is chosen (stock/day/weeks). Unset only for full (genesis) history.", Type: "int", Group: "history"},
		{Key: "node_size", Label: "node_size", Help: "tiny/small/medium/huge from host RAM. huge needs ≥32–64 GiB. Hardcoding huge stalls LoadManager on a VPS.", Type: "string", Group: "performance"},
	}
}

func stellarConfigFields() []nodeConfigFieldDef {
	return []nodeConfigFieldDef{
		{Key: "HISTORY_RETENTION_WINDOW", Label: "HISTORY_RETENTION_WINDOW", Help: "Forced to MaxUint32 (never prune) on save.", Type: "int", Group: "history"},
		{Key: "ENDPOINT", Label: "ENDPOINT", Help: "Locked — host:port listen.", Type: "string", Group: "ports", Protected: true},
	}
}

func solanaConfigFields() []nodeConfigFieldDef {
	return []nodeConfigFieldDef{
		{Key: "--rpc-threads", Label: "rpc-threads", Help: "High-load RPC threads (in run-validator.sh).", Type: "string", Group: "rpc"},
		{Key: "--limit-ledger-size", Label: "limit-ledger-size", Help: "Do not enable aggressive prune — RpcNode is full history.", Type: "string", Group: "history"},
	}
}

func suiConfigFields() []nodeConfigFieldDef {
	return []nodeConfigFieldDef{
		{Key: "json-rpc-address", Label: "json-rpc-address", Help: "Locked — catalog NodeHTTP.", Type: "string", Group: "ports", Protected: true},
		{Key: "metrics-address", Label: "metrics-address", Help: "Locked — loopback metrics.", Type: "string", Group: "ports", Protected: true},
		{Key: "db-path", Label: "db-path", Help: "Locked — catalog data dir.", Type: "string", Group: "paths", Protected: true},
		{Key: "num-epochs-to-retain", Label: "num-epochs-to-retain", Help: "Keep max local history; archival fallback covers older checkpoints.", Type: "int", Group: "history"},
	}
}

func avalancheNodeConfigFields() []nodeConfigFieldDef {
	return []nodeConfigFieldDef{
		{Key: "http-port", Label: "http-port", Help: "Locked — catalog NodeHTTP.", Type: "int", Group: "ports", Protected: true},
		{Key: "staking-port", Label: "staking-port", Help: "Locked — catalog P2P.", Type: "int", Group: "ports", Protected: true},
		{Key: "http-host", Label: "http-host", Help: "Locked — loopback for Go proxy.", Type: "string", Group: "ports", Protected: true},
		{Key: "data-dir", Label: "data-dir", Help: "Locked — catalog / multi-disk chain role.", Type: "string", Group: "paths", Protected: true},
		{Key: "db-dir", Label: "db-dir", Help: "Locked — under data-dir.", Type: "string", Group: "paths", Protected: true},
		{Key: "network-id", Label: "network-id", Help: "mainnet or fuji.", Type: "string", Group: "network", Protected: true},
	}
}

func avalancheCChainConfigFields() []nodeConfigFieldDef {
	return []nodeConfigFieldDef{
		{Key: "pruning-enabled", Label: "pruning-enabled", Help: "MUST stay false — RpcNode full-history archive.", Type: "bool", Group: "history", Protected: true},
		{Key: "state-sync-enabled", Label: "state-sync-enabled", Help: "MUST stay false for archive product.", Type: "bool", Group: "history", Protected: true},
	}
}

func aptosConfigFields() []nodeConfigFieldDef {
	return []nodeConfigFieldDef{
		{Key: "api.address", Label: "api.address", Help: "Locked — catalog NodeHTTP (loopback REST).", Type: "string", Group: "ports", Protected: true},
		{Key: "inspection_service.port", Label: "inspection_service.port", Help: "Locked — loopback metrics.", Type: "int", Group: "ports", Protected: true},
		{Key: "base.data_dir", Label: "base.data_dir", Help: "Locked — state DB path.", Type: "string", Group: "paths", Protected: true},
		{Key: "bootstrapping_mode", Label: "bootstrapping_mode", Help: "Full history: ExecuteOrApplyFromGenesis (not fast sync).", Type: "string", Group: "history"},
	}
}

func optimismEnvFields() []nodeConfigFieldDef {
	return []nodeConfigFieldDef{
		{Key: "OP_NODE_L1_ETH_RPC", Label: "L1 ETH RPC", Help: "L1 execution RPC URL for op-node.", Type: "string", Group: "l1"},
		{Key: "OP_NODE_L1_BEACON", Label: "L1 Beacon", Help: "L1 consensus beacon URL.", Type: "string", Group: "l1"},
	}
}

func baseConsensusEnvFields() []nodeConfigFieldDef {
	return []nodeConfigFieldDef{
		{Key: "BASE_NODE_L1_ETH_RPC", Label: "L1 ETH RPC", Help: "L1 execution endpoint for base-consensus.", Type: "string", Group: "l1"},
		{Key: "BASE_NODE_L1_BEACON", Label: "L1 Beacon", Help: "L1 beacon endpoint.", Type: "string", Group: "l1"},
		{Key: "BASE_NODE_L2_ENGINE_AUTH_RAW", Label: "Engine JWT", Help: "Locked JWT hex shared with reth.", Type: "string", Group: "auth", Protected: true},
	}
}

func arbEnvFields() []nodeConfigFieldDef {
	return []nodeConfigFieldDef{
		{Key: "L1_RPC_URL", Label: "L1 RPC URL", Help: "Ethereum L1 RPC for nitro.", Type: "string", Group: "l1"},
		{Key: "L1_BEACON_URL", Label: "L1 Beacon URL", Help: "Beacon API for blobs / consensus.", Type: "string", Group: "l1"},
	}
}
