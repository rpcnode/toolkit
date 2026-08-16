package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

const nodeConfigMaxBytes = 2 << 20 // 2 MiB per document

// NodeConfigController — leaf GET/PUT of chain client config + soft restart.
type NodeConfigController struct {
	cfg     Config
	ctrl    *ControlState
	restart *NodeRestartController
}

func newNodeConfigController(cfg Config, ctrl *ControlState, restart *NodeRestartController) *NodeConfigController {
	return &NodeConfigController{cfg: cfg, ctrl: ctrl, restart: restart}
}

type nodeConfigField struct {
	Key       string   `json:"key"`
	Label     string   `json:"label"`
	Help      string   `json:"help"`
	Type      string   `json:"type"` // string | int | bool | enum
	Group     string   `json:"group,omitempty"`
	Value     string   `json:"value,omitempty"`
	Protected bool     `json:"protected,omitempty"`
	Options   []string `json:"options,omitempty"`
}

type nodeConfigDocument struct {
	ID              string            `json:"id"`
	Path            string            `json:"path"`
	Format          string            `json:"format"` // ini | toml | json | env | hocon | cfg | shell | unit | text
	Title           string            `json:"title"`
	Description     string            `json:"description,omitempty"`
	Content         string            `json:"content"`
	Writable        bool              `json:"writable"`
	RestartRequired bool              `json:"restart_required"`
	DaemonReload    bool              `json:"daemon_reload,omitempty"`
	Missing         bool              `json:"missing,omitempty"`
	Fields          []nodeConfigField `json:"fields,omitempty"`
	ProtectedKeys   []string          `json:"protected_keys,omitempty"`
}

type nodeConfigSpec struct {
	ID              string
	RelPath         string // under EtcDir, or absolute if AbsPath set
	AbsPath         string
	Format          string
	Title           string
	Description     string
	Writable        bool
	RestartRequired bool
	DaemonReload    bool
	ProtectedKeys   []string
	FieldDefs       []nodeConfigFieldDef
}

func (c *NodeConfigController) handleConfig(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		c.handleGet(w, r)
	case http.MethodPut:
		c.handlePut(w, r)
	default:
		http.Error(w, "GET|PUT", http.StatusMethodNotAllowed)
	}
}

func (c *NodeConfigController) handleGet(w http.ResponseWriter, r *http.Request) {
	if c.cfg.HostTip {
		writeJSON(w, http.StatusConflict, map[string]any{
			"ok": false, "error": "host tip has no chain node config — use per-node agent",
		})
		return
	}
	docs, err := c.loadDocuments()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":        true,
		"network":   strings.ToLower(strings.TrimSpace(c.cfg.Network)),
		"env":       strings.ToLower(strings.TrimSpace(c.cfg.Env)),
		"etc_dir":   c.cfg.EtcDir,
		"documents": docs,
		"restart":   "soft_stop_start",
		"note":      "Save applies files then systemctl stop→start (network ExecStop / SIGTERM). confirm=true required on PUT. Ports / listen binds are never editable.",
	})
}

type nodeConfigPutBody struct {
	Confirm   bool  `json:"confirm"`
	Restart   *bool `json:"restart"`
	Documents []struct {
		ID      string            `json:"id"`
		Content string            `json:"content"`
		Fields  map[string]string `json:"fields"`
	} `json:"documents"`
}

func (c *NodeConfigController) handlePut(w http.ResponseWriter, r *http.Request) {
	if c.cfg.HostTip {
		writeJSON(w, http.StatusConflict, map[string]any{
			"ok": false, "error": "host tip has no chain node config — use per-node agent",
		})
		return
	}
	raw, err := io.ReadAll(io.LimitReader(r.Body, nodeConfigMaxBytes*4))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	var body nodeConfigPutBody
	if err := json.Unmarshal(raw, &body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid json: " + err.Error()})
		return
	}
	if !body.Confirm {
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"ok": false, "error": "confirm_required",
			"message": "Set confirm=true after UI confirmation. Soft stop+start will follow when restart=true.",
		})
		return
	}
	doRestart := true
	if body.Restart != nil {
		doRestart = *body.Restart
	}
	if len(body.Documents) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "documents required"})
		return
	}

	specs := c.catalog()
	byID := map[string]nodeConfigSpec{}
	for _, s := range specs {
		byID[s.ID] = s
	}

	needDaemonReload := false
	written := []string{}
	for _, d := range body.Documents {
		id := strings.TrimSpace(d.ID)
		spec, ok := byID[id]
		if !ok {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "unknown document id: " + id})
			return
		}
		if !spec.Writable {
			writeJSON(w, http.StatusForbidden, map[string]any{"ok": false, "error": "document not writable: " + id})
			return
		}
		path := spec.resolvePath(c.cfg)
		oldContent := ""
		if b, err := os.ReadFile(path); err == nil {
			oldContent = string(b)
		}
		content := d.Content
		prot := mergeProtectedKeys(spec.ProtectedKeys...)
		if len(d.Fields) > 0 {
			merged, err := applyConfigFields(spec.Format, oldContent, content, d.Fields, prot)
			if err != nil {
				writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": id + ": " + err.Error()})
				return
			}
			content = merged
		}
		if content == "" && oldContent == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": id + ": empty content"})
			return
		}
		if int64(len(content)) > nodeConfigMaxBytes {
			writeJSON(w, http.StatusRequestEntityTooLarge, map[string]any{"ok": false, "error": id + ": content too large"})
			return
		}
		if err := assertProtectedUnchanged(spec.Format, oldContent, content, prot); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{
				"ok": false, "error": "protected_key", "message": id + ": " + err.Error(),
			})
			return
		}
		content = healConfigContent(c.cfg.Network, spec.ID, content)

		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		mode := os.FileMode(0o644)
		if fi, err := os.Stat(path); err == nil {
			mode = fi.Mode().Perm()
		} else if strings.HasSuffix(path, ".env") || strings.Contains(path, "toolkit.env") || strings.HasSuffix(path, "jwt.hex") {
			mode = 0o640
		}
		tmp := path + ".rpcnode-tmp"
		if err := os.WriteFile(tmp, []byte(content), mode); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		if err := os.Rename(tmp, path); err != nil {
			_ = os.Remove(tmp)
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		written = append(written, path)
		if spec.DaemonReload {
			needDaemonReload = true
		}
	}

	if needDaemonReload {
		if out, err := exec.Command("systemctl", "daemon-reload").CombinedOutput(); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{
				"ok": false, "error": fmt.Sprintf("daemon-reload: %v (%s)", err, strings.TrimSpace(string(out))),
				"written": written,
			})
			return
		}
	}

	resp := map[string]any{
		"ok":      true,
		"written": written,
		"restart": doRestart,
	}
	if doRestart {
		if c.restart == nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": "restart controller missing", "written": written})
			return
		}
		st, err := c.restart.Restart()
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": err.Error(), "written": written, "node_restart": st,
			})
			return
		}
		resp["accepted"] = true
		resp["node_restart"] = st
		resp["message"] = "Config saved. Soft stop→start scheduled (RPC sleep while unit recycles)."
	} else {
		resp["message"] = "Config saved without restart. Restart the node to apply."
	}
	if c.ctrl != nil {
		c.ctrl.RequestRefresh()
	}
	writeJSON(w, http.StatusOK, resp)
}

func (c *NodeConfigController) loadDocuments() ([]nodeConfigDocument, error) {
	specs := c.catalog()
	out := make([]nodeConfigDocument, 0, len(specs))
	for _, spec := range specs {
		path := spec.resolvePath(c.cfg)
		prot := mergeProtectedKeys(spec.ProtectedKeys...)
		doc := nodeConfigDocument{
			ID:              spec.ID,
			Path:            path,
			Format:          spec.Format,
			Title:           spec.Title,
			Description:     spec.Description,
			Writable:        spec.Writable,
			RestartRequired: spec.RestartRequired,
			DaemonReload:    spec.DaemonReload,
			ProtectedKeys:   prot,
		}
		b, err := os.ReadFile(path)
		if err != nil {
			doc.Missing = true
			doc.Content = ""
			if !os.IsNotExist(err) {
				return nil, fmt.Errorf("%s: %w", path, err)
			}
		} else {
			doc.Content = string(b)
		}
		doc.Fields = materializeFields(spec.Format, doc.Content, spec.FieldDefs, prot)
		out = append(out, doc)
	}
	return out, nil
}

func (s nodeConfigSpec) resolvePath(cfg Config) string {
	if strings.TrimSpace(s.AbsPath) != "" {
		return s.AbsPath
	}
	etc := strings.TrimSpace(cfg.EtcDir)
	if etc == "" {
		etc = filepath.Join("/etc", strings.ToLower(cfg.Network), strings.ToLower(cfg.Env))
	}
	rel := strings.TrimPrefix(s.RelPath, "/")
	return filepath.Join(etc, rel)
}

func (c *NodeConfigController) catalog() []nodeConfigSpec {
	net := strings.ToLower(strings.TrimSpace(c.cfg.Network))
	env := strings.ToLower(strings.TrimSpace(c.cfg.Env))
	etc := strings.TrimSpace(c.cfg.EtcDir)
	opt := strings.TrimSpace(c.cfg.OptDir)
	if opt == "" {
		opt = filepath.Join("/opt", net, env)
	}
	unit := strings.TrimSuffix(strings.TrimSpace(c.cfg.NodeService), ".service")
	if unit == "" {
		unit = strings.TrimSuffix(LookupNetworkProfile(net, env).ServiceUnit(), ".service")
	}
	unitPath := filepath.Join("/etc/systemd/system", unit+".service")

	coreFields := coreLikeConfigFields()
	portsNote := " Ports are locked (catalog / Confirm ports) — not editable."

	switch net {
	case "bitcoin":
		return []nodeConfigSpec{{
			ID: "main", RelPath: "bitcoin.conf", Format: "ini",
			Title: "Bitcoin Core", Description: "bitcoind bitcoin.conf (full history: prune=0, txindex=1)." + portsNote,
			Writable: true, RestartRequired: true, FieldDefs: coreFields,
		}}
	case "doge":
		return []nodeConfigSpec{{
			ID: "main", RelPath: "dogecoin.conf", Format: "ini",
			Title: "Dogecoin Core", Description: "dogecoind conf." + portsNote,
			Writable: true, RestartRequired: true, FieldDefs: coreFields,
		}}
	case "zcash":
		return []nodeConfigSpec{{
			ID: "main", RelPath: "zebrad.toml", Format: "toml",
			Title: "Zcash Zebra (zebrad)", Description: "zebrad.toml — full state sync (zcashd EOL 2026-07-18)." + portsNote,
			Writable: true, RestartRequired: true,
		}}
	case "sui":
		return []nodeConfigSpec{{
			ID: "main", RelPath: "fullnode.yaml", Format: "yaml",
			Title:       "sui-node fullnode.yaml",
			Description: "JSON-RPC / metrics / genesis / archival fallback." + portsNote,
			Writable:    true, RestartRequired: true,
			FieldDefs: suiConfigFields(),
		}}
	case "aptos":
		return []nodeConfigSpec{{
			ID: "main", RelPath: "fullnode.yaml", Format: "yaml",
			Title:       "aptos-node fullnode.yaml",
			Description: "REST API / inspection metrics / genesis / full-history pruners off." + portsNote,
			Writable:    true, RestartRequired: true,
			FieldDefs: aptosConfigFields(),
		}}
	case "avalanche":
		return []nodeConfigSpec{
			{
				ID: "main", RelPath: "config.json", Format: "json",
				Title:       "AvalancheGo config.json",
				Description: "Node HTTP/P2P / data-dir / chain-config-dir." + portsNote,
				Writable:    true, RestartRequired: true,
				FieldDefs: avalancheNodeConfigFields(),
			},
			{
				ID: "cchain", RelPath: "configs/chains/C/config.json", Format: "json",
				Title:       "C-Chain config.json",
				Description: "Full history: pruning-enabled=false, state-sync-enabled=false.",
				Writable:    true, RestartRequired: true,
				FieldDefs: avalancheCChainConfigFields(),
			},
		}
	case "ltc":
		return []nodeConfigSpec{{
			ID: "main", RelPath: "litecoin.conf", Format: "ini",
			Title: "Litecoin Core", Description: "litecoin.conf." + portsNote,
			Writable: true, RestartRequired: true, FieldDefs: coreFields,
		}}
	case "dash":
		return []nodeConfigSpec{{
			ID: "main", RelPath: "dash.conf", Format: "ini",
			Title: "Dash Core", Description: "dash.conf." + portsNote,
			Writable: true, RestartRequired: true, FieldDefs: coreFields,
		}}
	case "bch":
		return []nodeConfigSpec{{
			ID: "main", RelPath: "bitcoin.conf", Format: "ini",
			Title: "Bitcoin Cash Node", Description: "BCHN bitcoin.conf." + portsNote,
			Writable: true, RestartRequired: true, FieldDefs: coreFields,
		}}
	case "tron":
		return []nodeConfigSpec{{
			ID: "main", RelPath: "main_net_config.conf", Format: "hocon",
			Title: "java-tron config", Description: "HOCON." + portsNote,
			Writable: true, RestartRequired: true,
			FieldDefs: tronConfigFields(),
		}}
	case "bsc":
		return []nodeConfigSpec{{
			ID: "main", RelPath: "config.toml", Format: "toml",
			Title: "BSC geth config.toml", Description: "config.toml." + portsNote,
			Writable: true, RestartRequired: true,
			FieldDefs: bscConfigFields(),
		}}
	case "xrpl":
		mainRel := "xrpld.cfg"
		if etc != "" && !fileExists(filepath.Join(etc, mainRel)) && fileExists(filepath.Join(etc, "rippled.cfg")) {
			mainRel = "rippled.cfg"
		}
		return []nodeConfigSpec{
			{
				ID: "main", RelPath: mainRel, Format: "cfg",
				Title: mainRel, Description: mainRel + "." + portsNote,
				Writable: true, RestartRequired: true,
				FieldDefs: xrplConfigFields(),
			},
			{
				ID: "validators", RelPath: "validators.txt", Format: "text",
				Title: "validators.txt", Description: "UNL / validator list.",
				Writable: true, RestartRequired: true,
			},
		}
	case "cardano":
		return []nodeConfigSpec{
			{
				ID: "main", RelPath: "config.json", Format: "json",
				Title: "cardano-node config.json", Writable: true, RestartRequired: true,
			},
			{
				ID: "topology", RelPath: "topology.json", Format: "json",
				Title: "topology.json", Writable: true, RestartRequired: true,
			},
		}
	case "stellar":
		return []nodeConfigSpec{
			{
				ID: "main", RelPath: "stellar-rpc.toml", Format: "toml",
				Title:       "stellar-rpc.toml",
				Description: "HISTORY_RETENTION_WINDOW forced to never-prune on save." + portsNote,
				Writable:    true, RestartRequired: true,
				FieldDefs: stellarConfigFields(),
			},
			{
				ID: "core", RelPath: "stellar-core.cfg", Format: "cfg",
				Title: "stellar-core.cfg (captive)", Description: "Captive core." + portsNote,
				Writable: true, RestartRequired: true,
			},
		}
	case "solana":
		return []nodeConfigSpec{{
			ID: "main", AbsPath: filepath.Join(opt, "run-validator.sh"), Format: "shell",
			Title: "run-validator.sh", Description: "Agave/validator launcher." + portsNote,
			Writable: true, RestartRequired: true,
			FieldDefs: solanaConfigFields(),
		}}
	case "ethereum":
		lh := fmt.Sprintf("ethereum-lighthouse-%s", env)
		return []nodeConfigSpec{
			{
				ID: "execution", AbsPath: unitPath, Format: "unit",
				Title: "geth systemd unit", Description: "ExecStart flags." + portsNote,
				Writable: true, RestartRequired: true, DaemonReload: true,
			},
			{
				ID: "consensus", AbsPath: filepath.Join("/etc/systemd/system", lh+".service"), Format: "unit",
				Title: "lighthouse systemd unit", Description: "Beacon unit." + portsNote,
				Writable: true, RestartRequired: true, DaemonReload: true,
			},
		}
	case "optimism":
		op := fmt.Sprintf("optimism-op-node-%s", env)
		return []nodeConfigSpec{
			{
				ID: "env", RelPath: "env", Format: "env",
				Title: "L1 / beacon env", Description: "L1 URLs." + portsNote,
				Writable: true, RestartRequired: true,
				FieldDefs: optimismEnvFields(),
			},
			{
				ID: "execution", AbsPath: unitPath, Format: "unit",
				Title: "op-geth systemd unit", Description: "op-geth unit." + portsNote,
				Writable: true, RestartRequired: true, DaemonReload: true,
			},
			{
				ID: "op_node", AbsPath: filepath.Join("/etc/systemd/system", op+".service"), Format: "unit",
				Title: "op-node systemd unit", Description: "op-node unit." + portsNote,
				Writable: true, RestartRequired: true, DaemonReload: true,
			},
		}
	case "base":
		cons := fmt.Sprintf("base-consensus-%s", env)
		return []nodeConfigSpec{
			{
				ID: "consensus_env", RelPath: "consensus.env", Format: "env",
				Title: "base-consensus env", Description: "L1 + engine env." + portsNote,
				Writable: true, RestartRequired: true,
				ProtectedKeys: []string{"BASE_NODE_L2_ENGINE_AUTH_RAW"},
				FieldDefs:     baseConsensusEnvFields(),
			},
			{
				ID: "execution", AbsPath: unitPath, Format: "unit",
				Title: "base-reth systemd unit", Description: "reth unit." + portsNote,
				Writable: true, RestartRequired: true, DaemonReload: true,
			},
			{
				ID: "consensus", AbsPath: filepath.Join("/etc/systemd/system", cons+".service"), Format: "unit",
				Title: "base-consensus systemd unit", Description: "consensus unit." + portsNote,
				Writable: true, RestartRequired: true, DaemonReload: true,
			},
		}
	case "arbitrum":
		return []nodeConfigSpec{{
			ID: "main", RelPath: "nitro.env", Format: "env",
			Title: "nitro.env", Writable: true, RestartRequired: true,
			FieldDefs: arbEnvFields(),
		}}
	case "robinhood":
		return []nodeConfigSpec{
			{
				ID: "main", RelPath: "chain-info.json", Format: "json",
				Title: "chain-info.json", Writable: true, RestartRequired: true,
			},
			{
				ID: "unit", AbsPath: unitPath, Format: "unit",
				Title: "nitro systemd unit", Writable: true, RestartRequired: true, DaemonReload: true,
			},
		}
	case "hyperliquid":
		return []nodeConfigSpec{
			{
				ID: "main", RelPath: "visor.json", Format: "json",
				Title: "visor.json", Writable: true, RestartRequired: true,
			},
			{
				ID: "gossip", RelPath: "override_gossip_config.json", Format: "json",
				Title: "override_gossip_config.json", Writable: true, RestartRequired: true,
			},
		}
	case "ton":
		return []nodeConfigSpec{{
			ID: "main", RelPath: "rpcnode-ton.json", Format: "json",
			Title: "RpcNode Ton meta", Description: "MyTonCtrl/validator live under /var/ton-work; this file is RpcNode metadata.",
			Writable: true, RestartRequired: true,
		}}
	case "etc":
		return []nodeConfigSpec{{
			ID: "unit", AbsPath: unitPath, Format: "unit",
			Title: "Core-Geth systemd unit", Description: "ETC archive flags live in ExecStart (--gcmode archive).",
			Writable: true, RestartRequired: true, DaemonReload: true,
		}}
	default:
		// Fallback: toolkit-known etc files + unit.
		specs := []nodeConfigSpec{}
		if etc != "" {
			for _, name := range []string{"config.toml", "bitcoin.conf", "main_net_config.conf", "xrpld.cfg"} {
				p := filepath.Join(etc, name)
				if fileExists(p) {
					specs = append(specs, nodeConfigSpec{
						ID: "main", RelPath: name, Format: guessFormat(name),
						Title: name, Writable: true, RestartRequired: true,
					})
					break
				}
			}
		}
		if unit != "" {
			specs = append(specs, nodeConfigSpec{
				ID: "unit", AbsPath: unitPath, Format: "unit",
				Title: unit + ".service", Writable: true, RestartRequired: true, DaemonReload: true,
			})
		}
		return specs
	}
}

func guessFormat(name string) string {
	switch {
	case strings.HasSuffix(name, ".yaml") || strings.HasSuffix(name, ".yml"):
		return "yaml"
	case strings.HasSuffix(name, ".toml"):
		return "toml"
	case strings.HasSuffix(name, ".json"):
		return "json"
	case strings.HasSuffix(name, ".conf") && strings.Contains(name, "main_net"):
		return "hocon"
	case strings.HasSuffix(name, ".cfg"):
		return "cfg"
	case strings.HasSuffix(name, ".env"):
		return "env"
	default:
		return "ini"
	}
}

func healConfigContent(network, docID, content string) string {
	if strings.EqualFold(network, "stellar") && docID == "main" {
		// Never allow short retention — product is full history.
		changed, out, err := patchStellarRetentionWindow(content)
		if err == nil && changed {
			return out
		}
	}
	return content
}

// patchStellarRetentionWindow — test helper surface; real heal uses ensure path when possible.
func patchStellarRetentionWindow(content string) (bool, string, error) {
	const key = "HISTORY_RETENTION_WINDOW"
	const want = "4294967295" // MaxUint32
	lines := strings.Split(content, "\n")
	changed := false
	found := false
	for i, line := range lines {
		trim := strings.TrimSpace(line)
		if trim == "" || strings.HasPrefix(trim, "#") {
			continue
		}
		if !strings.HasPrefix(strings.ToUpper(trim), key) {
			continue
		}
		found = true
		parts := strings.SplitN(trim, "=", 2)
		if len(parts) != 2 {
			continue
		}
		cur := strings.TrimSpace(parts[1])
		cur = strings.Trim(cur, `"'`)
		if cur != want {
			indent := line[:len(line)-len(strings.TrimLeft(line, " \t"))]
			lines[i] = indent + key + " = " + want
			changed = true
		}
	}
	if !found {
		if !strings.HasSuffix(content, "\n") && content != "" {
			content += "\n"
			lines = strings.Split(content, "\n")
		}
		lines = append(lines, key+" = "+want)
		changed = true
	}
	return changed, strings.Join(lines, "\n"), nil
}
