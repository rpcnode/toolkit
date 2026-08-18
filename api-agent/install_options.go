package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

// InstallOptionGroup — wizard choice for a network/env (snapshot flavor, …).
type InstallOptionGroup struct {
	ID      string               `json:"id"`
	Label   string               `json:"label"`
	Hint    string               `json:"hint,omitempty"`
	Default string               `json:"default"`
	Choices []InstallOptionChoice `json:"choices"`
}

type InstallOptionChoice struct {
	ID          string `json:"id"`
	Title       string `json:"title"`
	Hint        string `json:"hint,omitempty"`
	SnapshotURL string `json:"snapshot_url,omitempty"`
	// Conf flags applied to java-tron (mainnet snapshot variants).
	SaveInternalTx         *bool `json:"save_internal_tx,omitempty"`
	SaveFeaturedInternalTx *bool `json:"save_featured_internal_tx,omitempty"`
	BalanceHistoryLookup   *bool `json:"balance_history_lookup,omitempty"`
}

func boolPtr(v bool) *bool { return &v }

func installOptionGroups(network, env string) []InstallOptionGroup {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	if network == "xrpl" && (env == "mainnet" || env == "testnet") {
		return []InstallOptionGroup{{
			ID:      "xrpl_history",
			Label:   "History to install",
			Hint:    "How much ledger history xrpld will keep. Full history has no public snapshot (~39 TiB).",
			Default: "weeks",
			Choices: []InstallOptionChoice{
				{ID: "stock", Title: "Stock · ~2 hours", Hint: "2 000 ledgers — default xrpld window. Smallest disk."},
				{ID: "day", Title: "1 day", Hint: "25 000 ledgers. Typical public RPC day window."},
				{ID: "weeks", Title: "2 weeks", Hint: "300 000 ledgers. Default for a new install."},
				{ID: "full", Title: "Full history", Hint: "Genesis → tip (ledger 32 570 on mainnet). No snapshot. Official archive ~39 TiB."},
			},
		}}
	}
	if network == "tron" && env == "mainnet" {
		return []InstallOptionGroup{{
			ID:      "snapshot",
			Label:   "Snapshot",
			Hint:    "Official TRON FullNode LevelDB mirrors. Internal txs and historical balances are different snapshots — pick the one your RPC/AML needs.",
			Default: "standard",
			Choices: []InstallOptionChoice{
				{
					ID:                     "standard",
					Title:                  "Standard · no internal txs",
					Hint:                   "US Virginia (34.86.86.229). ~2.9 TB. gettransactioninfobyid has no historical internal_transactions. Default.",
					SnapshotURL:            "http://34.86.86.229/",
					SaveInternalTx:         boolPtr(false),
					SaveFeaturedInternalTx: boolPtr(false),
					BalanceHistoryLookup:   boolPtr(false),
				},
				{
					ID:                     "internal_tx",
					Title:                  "Internal transactions",
					Hint:                   "Singapore (35.247.128.170). ~3.1 TB. Needed if AML must see contract internal calls (gettransactioninfobyid). Enables vm.saveInternalTx.",
					SnapshotURL:            "http://35.247.128.170/",
					SaveInternalTx:         boolPtr(true),
					SaveFeaturedInternalTx: boolPtr(true),
					BalanceHistoryLookup:   boolPtr(false),
				},
				{
					ID:                     "balance_history",
					Title:                  "Historical TRX balances",
					Hint:                   "US (34.48.6.163). ~3.6 TB. getaccountbalance for any past block. No historical internal txs. Enables storage.balance.history.lookup.",
					SnapshotURL:            "http://34.48.6.163/",
					SaveInternalTx:         boolPtr(false),
					SaveFeaturedInternalTx: boolPtr(false),
					BalanceHistoryLookup:   boolPtr(true),
				},
			},
		}}
	}
	return nil
}

func parseInstallOptionsMap(raw map[string]string) map[string]string {
	if raw == nil {
		return map[string]string{}
	}
	out := map[string]string{}
	for k, v := range raw {
		k = strings.ToLower(strings.TrimSpace(k))
		v = strings.ToLower(strings.TrimSpace(v))
		if k != "" && v != "" {
			out[k] = v
		}
	}
	return out
}

func installOptionsFromAny(v any) map[string]string {
	out := map[string]string{}
	switch t := v.(type) {
	case map[string]string:
		return parseInstallOptionsMap(t)
	case map[string]any:
		for k, raw := range t {
			s, ok := raw.(string)
			if !ok {
				continue
			}
			k = strings.ToLower(strings.TrimSpace(k))
			s = strings.ToLower(strings.TrimSpace(s))
			if k != "" && s != "" {
				out[k] = s
			}
		}
	}
	return out
}

func defaultInstallOptions(network, env string) map[string]string {
	out := map[string]string{}
	for _, g := range installOptionGroups(network, env) {
		if g.Default != "" {
			out[g.ID] = g.Default
		}
	}
	return out
}

func mergeInstallOptions(network, env string, requested map[string]string) map[string]string {
	out := defaultInstallOptions(network, env)
	for k, v := range parseInstallOptionsMap(requested) {
		if findInstallChoice(network, env, k, v) != nil {
			out[k] = v
		}
	}
	return out
}

func findInstallGroup(network, env, groupID string) *InstallOptionGroup {
	groupID = strings.ToLower(strings.TrimSpace(groupID))
	groups := installOptionGroups(network, env)
	for i := range groups {
		if groups[i].ID == groupID {
			return &groups[i]
		}
	}
	return nil
}

func findInstallChoice(network, env, groupID, choiceID string) *InstallOptionChoice {
	g := findInstallGroup(network, env, groupID)
	if g == nil {
		return nil
	}
	choiceID = strings.ToLower(strings.TrimSpace(choiceID))
	for i := range g.Choices {
		if g.Choices[i].ID == choiceID {
			return &g.Choices[i]
		}
	}
	return nil
}

func installOptionsPath(network, env string) string {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	switch network {
	case "tron":
		return filepath.Join("/etc/tron", env, "install-options.json")
	default:
		return filepath.Join("/etc", network, env, "install-options.json")
	}
}

func writeInstallOptions(network, env string, opts map[string]string) error {
	path := installOptionsPath(network, env)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	raw, err := json.MarshalIndent(opts, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(raw, '\n'), 0o644)
}

func loadInstallOptions(network, env string) map[string]string {
	raw, err := os.ReadFile(installOptionsPath(network, env))
	if err != nil {
		return defaultInstallOptions(network, env)
	}
	var doc map[string]any
	if json.Unmarshal(raw, &doc) != nil {
		return defaultInstallOptions(network, env)
	}
	return mergeInstallOptions(network, env, installOptionsFromAny(doc))
}

func resolveSnapshotURLForOptions(network, env string, opts map[string]string) string {
	opts = mergeInstallOptions(network, env, opts)
	if ch := findInstallChoice(network, env, "snapshot", opts["snapshot"]); ch != nil && strings.TrimSpace(ch.SnapshotURL) != "" {
		return strings.TrimSpace(ch.SnapshotURL)
	}
	return strings.TrimSpace(lookupPortProfile(network, env).SnapshotURL)
}
