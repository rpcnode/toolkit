package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

type nodePlanRequest struct {
	Network string `json:"network"`
	Env     string `json:"env"`
}

type nodePlanResponse struct {
	OK           bool   `json:"ok"`
	Network      string `json:"network"`
	Env          string `json:"env"`
	PublicPort   int    `json:"public_port"`    // Go RPC proxy (clients; sleep/maintenance)
	AgentPort    int    `json:"agent_port"`     // Node Agent API (panel / control)
	NodeHTTPPort int    `json:"node_http_port"` // java-tron HTTP (internal, via Go)
	P2PPort      int    `json:"p2p_port,omitempty"`
	Message      string `json:"message,omitempty"`
}

type nodeProvisionRequest struct {
	Network      string `json:"network"`
	Env          string `json:"env"`
	PublicPort   int    `json:"public_port,omitempty"`
	AgentPort    int    `json:"agent_port,omitempty"`
	NodeHTTPPort int    `json:"node_http_port,omitempty"`
	P2PPort      int    `json:"p2p_port,omitempty"`
	Name         string `json:"name,omitempty"`
	// Solana JBOD layout (optional). Absolute dirs for Agave --ledger / --accounts / --snapshots.
	LedgerDir    string              `json:"ledger_dir,omitempty"`
	AccountsDir  string              `json:"accounts_dir,omitempty"`
	SnapshotsDir string              `json:"snapshots_dir,omitempty"`
	DiskLayout   *solanaDiskLayoutIn `json:"disk_layout,omitempty"`
	// XRPL: stock | day | weeks | full (default weeks ≈ 2 weeks / 300k ledgers).
	XRPLHistory string `json:"xrpl_history,omitempty"`
	// InstallOptions — wizard choices (e.g. snapshot=internal_tx). Persisted on the host.
	InstallOptions map[string]string `json:"install_options,omitempty"`
}

// solanaDiskLayoutIn — panel/wizard confirmed mounts or absolute dirs.
// Optional Roles map is shared with Aptos (state/index) without breaking Solana.
type solanaDiskLayoutIn struct {
	LedgerMount    string                `json:"ledger_mount,omitempty"`
	AccountsMount  string                `json:"accounts_mount,omitempty"`
	SnapshotsMount string                `json:"snapshots_mount,omitempty"`
	LedgerDir      string                `json:"ledger_dir,omitempty"`
	AccountsDir    string                `json:"accounts_dir,omitempty"`
	SnapshotsDir   string                `json:"snapshots_dir,omitempty"`
	Strategy       string                `json:"strategy,omitempty"`
	StateDir       string                `json:"state_dir,omitempty"`
	IndexDir       string                `json:"index_dir,omitempty"`
	StateMount     string                `json:"state_mount,omitempty"`
	IndexMount     string                `json:"index_mount,omitempty"`
	Roles          map[string]diskRoleIn `json:"roles,omitempty"`
}

// diskRoleIn — generic multi-disk role (aptos state/index, future networks).
type diskRoleIn struct {
	Dir   string `json:"dir,omitempty"`
	Mount string `json:"mount,omitempty"`
}

func (s *Server) handleNodesV1(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSuffix(r.URL.Path, "/")

	// Network-scoped aliases:
	//   /api/v1/networks/{network}/envs/{env}/remove|start|provision
	//   /api/v1/networks/{network}/envs
	if strings.HasPrefix(path, "/api/v1/networks/") {
		s.handleNetworkScopedNodes(w, r, path)
		return
	}

	switch {
	case path == "/api/v1/nodes" && r.Method == http.MethodGet:
		s.handleNodesListLocal(w, r)
	case path == "/api/v1/nodes/plan" && r.Method == http.MethodPost:
		s.handleNodesPlan(w, r)
	case path == "/api/v1/nodes/check-ports" && r.Method == http.MethodPost:
		s.handleNodesCheckPorts(w, r)
	case path == "/api/v1/nodes/port-holder" && r.Method == http.MethodPost:
		s.handleNodesPortHolder(w, r)
	case path == "/api/v1/nodes/port-holder/kill" && r.Method == http.MethodPost:
		s.handleNodesPortHolderKill(w, r)
	case path == "/api/v1/nodes/probe-listen" && r.Method == http.MethodPost:
		s.handleNodesProbeListen(w, r)
	case path == "/api/v1/nodes/probe-stop" && r.Method == http.MethodPost:
		s.handleNodesProbeStop(w, r)
	case path == "/api/v1/nodes/provision" && r.Method == http.MethodPost:
		s.handleNodesProvision(w, r)
	case path == "/api/v1/nodes/start" && r.Method == http.MethodPost:
		s.handleNodesStart(w, r)
	case path == "/api/v1/nodes/remove" && r.Method == http.MethodPost:
		s.handleNodesRemove(w, r)
	default:
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
	}
}

func (s *Server) handleNetworkScopedNodes(w http.ResponseWriter, r *http.Request, path string) {
	// /api/v1/networks/{network}/envs[/{env}[/{action}]]
	rest := strings.TrimPrefix(path, "/api/v1/networks/")
	parts := strings.Split(rest, "/")
	if len(parts) < 2 || parts[1] != "envs" {
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
		return
	}
	network := normalizeNetwork(parts[0])

	if len(parts) == 2 && r.Method == http.MethodGet {
		s.handleNodesListLocal(w, r)
		return
	}
	if len(parts) < 3 {
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
		return
	}
	env := normalizeEnv(parts[2])
	action := ""
	if len(parts) >= 4 {
		action = parts[3]
	}

	switch {
	case action == "remove" && r.Method == http.MethodPost:
		var req nodeRemoveRequest
		_ = json.NewDecoder(r.Body).Decode(&req)
		req.Env = env
		req.Network = network
		s.respondNodesRemove(w, req)
	case action == "start" && r.Method == http.MethodPost:
		s.handleNodesStartWithEnv(w, env, network)
	case action == "provision" && r.Method == http.MethodPost:
		var req nodeProvisionRequest
		_ = json.NewDecoder(r.Body).Decode(&req)
		req.Env = env
		req.Network = network
		s.handleNodesProvisionBody(w, req)
	case action == "" && r.Method == http.MethodGet:
		// Single env tip from local list.
		s.handleNodesListLocal(w, r)
	default:
		writeJSON(w, http.StatusNotFound, map[string]any{
			"ok": false, "error": "not_found",
			"message": "use POST .../remove|start|provision",
		})
	}
}

func (s *Server) handleNodesStart(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Env     string `json:"env"`
		Network string `json:"network"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	env := normalizeEnv(req.Env)
	if env == "" {
		env = normalizeEnv(s.cfg.Env)
	}
	network := normalizeNetwork(req.Network)
	if network == "" {
		network = normalizeNetwork(envOr("TRON_NETWORK", "tron"))
	}
	s.handleNodesStartWithEnv(w, env, network)
}

func (s *Server) handleNodesStartWithEnv(w http.ResponseWriter, env, network string) {
	env = normalizeEnv(env)
	network = normalizeNetwork(network)
	if env == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "env_required"})
		return
	}
	hostLogf("INFO", "api-agent", "start", "begin %s/%s", network, env)
	if block, msg := snapshotBlocksNodeStart(network, env); block {
		hostLogf("ERROR", "api-agent", "start", "snapshot_required %s/%s: %s", network, env, msg)
		writeJSON(w, http.StatusConflict, map[string]any{
			"ok": false, "error": "snapshot_required", "message": msg,
			"network": network, "env": env, "version": agentVersion(),
		})
		return
	}
	if err := ensureNetworkHostDeps(network); err != nil {
		hostLogf("ERROR", "api-agent", "start", "host_deps_failed %s/%s: %v", network, env, err)
		writeJSON(w, http.StatusConflict, map[string]any{
			"ok": false, "error": "host_deps_failed", "message": err.Error(),
			"network": network, "env": env, "version": agentVersion(),
		})
		return
	}

	// Bitcoin: identity=network → ensure binary + conf + unit, then enable --now.
	if network == "bitcoin" {
		prof := lookupPortProfile(network, env)
		bin, err := ensureBitcoindInstalled(prof.OptPath)
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "bitcoind_missing", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		_ = ensureNodeopUser()
		req := nodeProvisionRequest{
			Network: "bitcoin", Env: env,
			NodeHTTPPort: prof.NodeHTTP, P2PPort: prof.P2P,
		}
		confPath, err := ensureBitcoinConf(prof, req)
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "bitcoin_conf_failed", "message": err.Error(),
				"conf": confPath, "version": agentVersion(),
			})
			return
		}
		if err := rewriteBitcoinUnitBinary(prof, bin); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "unit_rewrite_failed", "message": err.Error(),
				"conf": confPath, "version": agentVersion(),
			})
			return
		}
		unit := fmt.Sprintf("bitcoin-%s.service", env)
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		// restart (not start): ensureBitcoinConf may have rewritten prod RPC knobs — running
		// bitcoind ignores on-disk conf until the process is recycled.
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out))),
				"conf":    confPath,
				"hint":    "bitcoind unit failed — see journalctl -u " + unit,
				"version": agentVersion(),
			})
			return
		}
		// Give bitcoind time to die on bad conf / OOM (900ms often still "activating").
		time.Sleep(2500 * time.Millisecond)
		failedOut, _ := exec.Command("systemctl", "is-failed", unit).CombinedOutput()
		activeOut, _ := exec.Command("systemctl", "is-active", unit).CombinedOutput()
		state := strings.TrimSpace(string(activeOut))
		failed := strings.TrimSpace(string(failedOut)) == "failed" || state == "failed"
		rpcUp := prof.NodeHTTP > 0 && portOpenLocal(prof.NodeHTTP)
		if failed || (!rpcUp && state != "active" && state != "activating") {
			jOut, _ := exec.Command("journalctl", "-u", unit, "-n", "24", "--no-pager", "-o", "cat").CombinedOutput()
			show, _ := exec.Command("systemctl", "show", unit, "-p", "ExecMainStatus", "-p", "ExecMainCode", "-p", "StatusText", "--no-pager").CombinedOutput()
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": "unit failed after start",
				"conf":    confPath,
				"state":   state,
				"journal": strings.TrimSpace(string(jOut)),
				"systemd": strings.TrimSpace(string(show)),
				"version": agentVersion(),
			})
			return
		}
		// Confirmed public_port must listen (Go RPC → bitcoind). Agent :39390 alone is not enough.
		pub := prof.Public
		if doc := readJSONFile(filepath.Join("/etc/rpcnode/nodes", "bitcoin-"+env+".json")); doc != nil {
			if v := intFromJSON(doc["public_port"]); v > 0 {
				pub = v
			}
		}
		if pub <= 0 {
			pub = 39290
		}
		if err := ensureBitcoinGoRPC(env, pub); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "go_rpc_down",
				"message":     err.Error(),
				"public_port": pub,
				"hint":        "per-node api-agent must bind TRON_PUBLIC_PORT (Go RPC)",
				"version":     agentVersion(),
			})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "env": env, "network": network, "unit": unit,
			"via": "systemctl", "bitcoind": bin, "conf": confPath,
			"upstream_port": prof.NodeHTTP, "public_port": pub,
			"go_rpc": true, "started": true,
			"version": agentVersion(),
		})
		return
	}

	// Solana: ensure Agave binary + run script + unit, then enable --now.
	if network == "solana" {
		prof := lookupPortProfile(network, env)
		cluster := lookupSolanaCluster(env)
		bin, err := ensureSolanaBinaryInstalled(prof.OptPath, env, cluster.Localnet)
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "solana_binary_missing", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		_ = ensureNodeopUser()
		_ = ensureSolanaSysctl()
		req := nodeProvisionRequest{
			Network: "solana", Env: env,
			NodeHTTPPort: prof.NodeHTTP, P2PPort: prof.P2P,
		}
		if err := rewriteSolanaUnit(prof, req); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "unit_rewrite_failed", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		unit := fmt.Sprintf("solana-%s.service", env)
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out))),
				"hint":    "solana unit failed — see journalctl -u " + unit,
				"version": agentVersion(),
			})
			return
		}
		time.Sleep(2500 * time.Millisecond)
		failedOut, _ := exec.Command("systemctl", "is-failed", unit).CombinedOutput()
		activeOut, _ := exec.Command("systemctl", "is-active", unit).CombinedOutput()
		state := strings.TrimSpace(string(activeOut))
		failed := strings.TrimSpace(string(failedOut)) == "failed" || state == "failed"
		if failed {
			jOut, _ := exec.Command("journalctl", "-u", unit, "-n", "24", "--no-pager", "-o", "cat").CombinedOutput()
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": "unit failed after start",
				"state":   state,
				"journal": strings.TrimSpace(string(jOut)),
				"version": agentVersion(),
			})
			return
		}
		pub := prof.Public
		if doc := readJSONFile(filepath.Join("/etc/rpcnode/nodes", "solana-"+env+".json")); doc != nil {
			if v := intFromJSON(doc["public_port"]); v > 0 {
				pub = v
			}
		}
		if pub <= 0 {
			pub = 39490
		}
		if err := ensureSolanaGoRPC(env, pub); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "go_rpc_down",
				"message":     err.Error(),
				"public_port": pub,
				"hint":        "per-node api-agent must bind TRON_PUBLIC_PORT (Go RPC)",
				"version":     agentVersion(),
			})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "env": env, "network": network, "unit": unit,
			"via": "systemctl", "binary": bin,
			"upstream_port": prof.NodeHTTP, "public_port": pub,
			"go_rpc": true, "started": true,
			"version": agentVersion(),
		})
		return
	}

	// Hyperliquid: ensure hl-visor + unit, then enable --now.
	if network == "hyperliquid" {
		prof := lookupPortProfile(network, env)
		cluster := lookupHyperliquidNetwork(env)
		bin, err := ensureHLVisorInstalled(prof.OptPath, cluster)
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "hl_visor_missing", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		_ = ensureNodeopUser()
		req := nodeProvisionRequest{
			Network: "hyperliquid", Env: env,
			NodeHTTPPort: prof.NodeHTTP, P2PPort: prof.P2P,
		}
		if err := rewriteHyperliquidUnit(prof, req); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "unit_rewrite_failed", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		unit := fmt.Sprintf("hyperliquid-%s.service", env)
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out))),
				"hint":    "hyperliquid unit failed — see journalctl -u " + unit,
				"version": agentVersion(),
			})
			return
		}
		time.Sleep(2500 * time.Millisecond)
		failedOut, _ := exec.Command("systemctl", "is-failed", unit).CombinedOutput()
		activeOut, _ := exec.Command("systemctl", "is-active", unit).CombinedOutput()
		state := strings.TrimSpace(string(activeOut))
		failed := strings.TrimSpace(string(failedOut)) == "failed" || state == "failed"
		if failed {
			jOut, _ := exec.Command("journalctl", "-u", unit, "-n", "24", "--no-pager", "-o", "cat").CombinedOutput()
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": "unit failed after start",
				"state":   state,
				"journal": strings.TrimSpace(string(jOut)),
				"version": agentVersion(),
			})
			return
		}
		pub := prof.Public
		if doc := readJSONFile(filepath.Join("/etc/rpcnode/nodes", "hyperliquid-"+env+".json")); doc != nil {
			if v := intFromJSON(doc["public_port"]); v > 0 {
				pub = v
			}
		}
		if pub <= 0 {
			pub = 40090
		}
		if err := ensureHyperliquidGoRPC(env, pub); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "go_rpc_down",
				"message":     err.Error(),
				"public_port": pub,
				"version":     agentVersion(),
			})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "env": env, "network": network, "unit": unit,
			"via": "systemctl", "binary": bin,
			"upstream_port": prof.NodeHTTP, "public_port": pub,
			"go_rpc": true, "started": true,
			"version": agentVersion(),
		})
		return
	}

	// Arbitrum: ensure nitro + unit, then enable --now.
	if network == "arb" {
		prof := lookupPortProfile(network, env)
		bin, err := ensureNitroInstalled(prof.OptPath)
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "nitro_missing", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		_ = ensureNodeopUser()
		req := nodeProvisionRequest{
			Network: "arb", Env: env,
			NodeHTTPPort: prof.NodeHTTP, P2PPort: prof.P2P,
		}
		if err := rewriteArbUnit(prof, req); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "unit_rewrite_failed", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		unit := fmt.Sprintf("arb-%s.service", env)
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out))),
				"hint":    "arb unit failed — see journalctl -u " + unit,
				"version": agentVersion(),
			})
			return
		}
		time.Sleep(2500 * time.Millisecond)
		failedOut, _ := exec.Command("systemctl", "is-failed", unit).CombinedOutput()
		activeOut, _ := exec.Command("systemctl", "is-active", unit).CombinedOutput()
		state := strings.TrimSpace(string(activeOut))
		failed := strings.TrimSpace(string(failedOut)) == "failed" || state == "failed"
		rpcUp := prof.NodeHTTP > 0 && portOpenLocal(prof.NodeHTTP)
		if failed || (!rpcUp && state != "active" && state != "activating") {
			jOut, _ := exec.Command("journalctl", "-u", unit, "-n", "24", "--no-pager", "-o", "cat").CombinedOutput()
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": "unit failed after start",
				"state":   state,
				"journal": strings.TrimSpace(string(jOut)),
				"version": agentVersion(),
			})
			return
		}
		pub := prof.Public
		if doc := readJSONFile(filepath.Join("/etc/rpcnode/nodes", "arb-"+env+".json")); doc != nil {
			if v := intFromJSON(doc["public_port"]); v > 0 {
				pub = v
			}
		}
		if pub <= 0 {
			pub = prof.Public
		}
		if err := ensureArbGoRPC(env, pub); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "go_rpc_down",
				"message":     err.Error(),
				"public_port": pub,
				"version":     agentVersion(),
			})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "env": env, "network": network, "unit": unit,
			"via": "systemctl", "binary": bin,
			"upstream_port": prof.NodeHTTP, "public_port": pub,
			"go_rpc": true, "started": true,
			"version": agentVersion(),
		})
		return
	}

	// Robinhood: ensure nitro + --init.url unit + snapshot oneshot, then enable --now.
	if network == "robinhood" {
		prof := lookupPortProfile(network, env)
		bin, err := ensureNitroInstalled(prof.OptPath)
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "nitro_missing", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		_ = ensureNodeopUser()
		req := nodeProvisionRequest{
			Network: "robinhood", Env: env,
			NodeHTTPPort: prof.NodeHTTP, P2PPort: prof.P2P,
		}
		if err := rewriteRobinhoodUnit(prof, req); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "unit_rewrite_failed", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		unit := fmt.Sprintf("robinhood-%s.service", env)
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out))),
				"hint":    "robinhood unit failed — see journalctl -u " + unit,
				"version": agentVersion(),
			})
			return
		}
		time.Sleep(2500 * time.Millisecond)
		failedOut, _ := exec.Command("systemctl", "is-failed", unit).CombinedOutput()
		activeOut, _ := exec.Command("systemctl", "is-active", unit).CombinedOutput()
		state := strings.TrimSpace(string(activeOut))
		failed := strings.TrimSpace(string(failedOut)) == "failed" || state == "failed"
		rpcUp := prof.NodeHTTP > 0 && portOpenLocal(prof.NodeHTTP)
		if failed || (!rpcUp && state != "active" && state != "activating") {
			jOut, _ := exec.Command("journalctl", "-u", unit, "-n", "24", "--no-pager", "-o", "cat").CombinedOutput()
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": "unit failed after start",
				"state":   state,
				"journal": strings.TrimSpace(string(jOut)),
				"version": agentVersion(),
			})
			return
		}
		pub := prof.Public
		if doc := readJSONFile(filepath.Join("/etc/rpcnode/nodes", "robinhood-"+env+".json")); doc != nil {
			if v := intFromJSON(doc["public_port"]); v > 0 {
				pub = v
			}
		}
		if pub <= 0 {
			pub = 42090
		}
		if err := ensureRobinhoodGoRPC(env, pub); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "go_rpc_down",
				"message":     err.Error(),
				"public_port": pub,
				"version":     agentVersion(),
			})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "env": env, "network": network, "unit": unit,
			"via": "systemctl", "binary": bin,
			"upstream_port": prof.NodeHTTP, "public_port": pub,
			"go_rpc": true, "started": true, "snapshot": true,
			"version": agentVersion(),
		})
		return
	}

	// Optimism: ensure op-geth + op-node + units, then enable --now.
	if network == "optimism" {
		prof := lookupPortProfile(network, env)
		gethBin, err := ensureOpGethInstalled(prof.OptPath)
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "op_geth_missing", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		opNodeBin, err := ensureOpNodeInstalled(prof.OptPath)
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "op_node_missing", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		_ = ensureNodeopUser()
		req := nodeProvisionRequest{
			Network: "optimism", Env: env,
			NodeHTTPPort: prof.NodeHTTP, P2PPort: prof.P2P,
		}
		if err := rewriteOptimismUnits(prof, req); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "unit_rewrite_failed", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		gethUnit := fmt.Sprintf("optimism-%s.service", env)
		opNodeUnit := fmt.Sprintf("optimism-op-node-%s.service", env)
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", gethUnit, opNodeUnit).Run()
		_ = exec.Command("systemctl", "enable", gethUnit, opNodeUnit).Run()
		out, err := exec.Command("systemctl", "restart", gethUnit).CombinedOutput()
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": fmt.Sprintf("systemctl restart %s: %v (%s)", gethUnit, err, strings.TrimSpace(string(out))),
				"version": agentVersion(),
			})
			return
		}
		out, err = exec.Command("systemctl", "restart", opNodeUnit).CombinedOutput()
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": fmt.Sprintf("systemctl restart %s: %v (%s)", opNodeUnit, err, strings.TrimSpace(string(out))),
				"version": agentVersion(),
			})
			return
		}
		time.Sleep(2500 * time.Millisecond)
		gethActive, _ := exec.Command("systemctl", "is-active", gethUnit).CombinedOutput()
		opActive, _ := exec.Command("systemctl", "is-active", opNodeUnit).CombinedOutput()
		gethState := strings.TrimSpace(string(gethActive))
		opState := strings.TrimSpace(string(opActive))
		rpcUp := prof.NodeHTTP > 0 && portOpenLocal(prof.NodeHTTP)
		if (gethState == "failed" || opState == "failed") || (!rpcUp && gethState != "active" && gethState != "activating") {
			jOut, _ := exec.Command("journalctl", "-u", gethUnit, "-n", "16", "--no-pager", "-o", "cat").CombinedOutput()
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message":    "optimism units failed after start",
				"geth_state": gethState, "op_node_state": opState,
				"journal": strings.TrimSpace(string(jOut)),
				"version": agentVersion(),
			})
			return
		}
		pub := prof.Public
		if doc := readJSONFile(filepath.Join("/etc/rpcnode/nodes", "optimism-"+env+".json")); doc != nil {
			if v := intFromJSON(doc["public_port"]); v > 0 {
				pub = v
			}
		}
		if pub <= 0 {
			pub = 40092
		}
		if err := ensureOptimismGoRPC(env, pub); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "go_rpc_down",
				"message":     err.Error(),
				"public_port": pub,
				"version":     agentVersion(),
			})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "env": env, "network": network,
			"units": []string{gethUnit, opNodeUnit},
			"via":   "systemctl", "op_geth": gethBin, "op_node": opNodeBin,
			"upstream_port": prof.NodeHTTP, "public_port": pub,
			"go_rpc": true, "started": true,
			"version": agentVersion(),
		})
		return
	}

	// Base: ensure base-reth-node + base-consensus + units, then enable --now.
	if network == "base" {
		prof := lookupPortProfile(network, env)
		rethBin, err := ensureBaseRethInstalled(prof.OptPath)
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "base_reth_missing", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		consensusBin, err := ensureBaseConsensusInstalled(prof.OptPath)
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "base_consensus_missing", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		_ = ensureNodeopUser()
		req := nodeProvisionRequest{
			Network: "base", Env: env,
			NodeHTTPPort: prof.NodeHTTP, P2PPort: prof.P2P,
		}
		if err := rewriteBaseUnits(prof, req); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "unit_rewrite_failed", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		rethUnit := fmt.Sprintf("base-%s.service", env)
		consensusUnit := fmt.Sprintf("base-consensus-%s.service", env)
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", rethUnit, consensusUnit).Run()
		_ = exec.Command("systemctl", "enable", rethUnit, consensusUnit).Run()
		out, err := exec.Command("systemctl", "restart", rethUnit).CombinedOutput()
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": fmt.Sprintf("systemctl restart %s: %v (%s)", rethUnit, err, strings.TrimSpace(string(out))),
				"version": agentVersion(),
			})
			return
		}
		out, err = exec.Command("systemctl", "restart", consensusUnit).CombinedOutput()
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": fmt.Sprintf("systemctl restart %s: %v (%s)", consensusUnit, err, strings.TrimSpace(string(out))),
				"version": agentVersion(),
			})
			return
		}
		time.Sleep(2500 * time.Millisecond)
		rethActive, _ := exec.Command("systemctl", "is-active", rethUnit).CombinedOutput()
		consActive, _ := exec.Command("systemctl", "is-active", consensusUnit).CombinedOutput()
		rethState := strings.TrimSpace(string(rethActive))
		consState := strings.TrimSpace(string(consActive))
		rpcUp := prof.NodeHTTP > 0 && portOpenLocal(prof.NodeHTTP)
		if (rethState == "failed" || consState == "failed") || (!rpcUp && rethState != "active" && rethState != "activating") {
			jOut, _ := exec.Command("journalctl", "-u", rethUnit, "-n", "16", "--no-pager", "-o", "cat").CombinedOutput()
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message":         "base units failed after start",
				"reth_state":      rethState,
				"consensus_state": consState,
				"journal":         strings.TrimSpace(string(jOut)),
				"version":         agentVersion(),
			})
			return
		}
		pub := prof.Public
		if doc := readJSONFile(filepath.Join("/etc/rpcnode/nodes", "base-"+env+".json")); doc != nil {
			if v := intFromJSON(doc["public_port"]); v > 0 {
				pub = v
			}
		}
		if pub <= 0 {
			pub = 42290
		}
		if err := ensureBaseGoRPC(env, pub); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "go_rpc_down",
				"message":     err.Error(),
				"public_port": pub,
				"version":     agentVersion(),
			})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "env": env, "network": network,
			"units": []string{rethUnit, consensusUnit},
			"via":   "systemctl", "base_reth": rethBin, "base_consensus": consensusBin,
			"upstream_port": prof.NodeHTTP, "public_port": pub,
			"go_rpc": true, "started": true,
			"version": agentVersion(),
		})
		return
	}

	// BSC: ensure bsc-geth + genesis + unit, then enable --now.
	if network == "bsc" {
		prof := lookupPortProfile(network, env)
		bin, err := ensureBSCGethInstalled(prof.OptPath)
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "bsc_geth_missing", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		_ = ensureNodeopUser()
		req := nodeProvisionRequest{
			Network: "bsc", Env: env,
			NodeHTTPPort: prof.NodeHTTP, P2PPort: prof.P2P,
		}
		if err := rewriteBSCUnit(prof, req); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "unit_rewrite_failed", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		unit := fmt.Sprintf("bsc-%s.service", env)
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out))),
				"hint":    "bsc unit failed — see journalctl -u " + unit,
				"version": agentVersion(),
			})
			return
		}
		time.Sleep(2500 * time.Millisecond)
		failedOut, _ := exec.Command("systemctl", "is-failed", unit).CombinedOutput()
		activeOut, _ := exec.Command("systemctl", "is-active", unit).CombinedOutput()
		state := strings.TrimSpace(string(activeOut))
		failed := strings.TrimSpace(string(failedOut)) == "failed" || state == "failed"
		rpcUp := prof.NodeHTTP > 0 && portOpenLocal(prof.NodeHTTP)
		if failed || (!rpcUp && state != "active" && state != "activating") {
			jOut, _ := exec.Command("journalctl", "-u", unit, "-n", "24", "--no-pager", "-o", "cat").CombinedOutput()
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": "unit failed after start",
				"state":   state,
				"journal": strings.TrimSpace(string(jOut)),
				"version": agentVersion(),
			})
			return
		}
		pub := prof.Public
		if doc := readJSONFile(filepath.Join("/etc/rpcnode/nodes", "bsc-"+env+".json")); doc != nil {
			if v := intFromJSON(doc["public_port"]); v > 0 {
				pub = v
			}
		}
		if pub <= 0 {
			pub = 39890
		}
		if err := ensureBSCGoRPC(env, pub); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "go_rpc_down",
				"message":     err.Error(),
				"public_port": pub,
				"hint":        "per-node api-agent must bind TRON_PUBLIC_PORT (Go RPC)",
				"version":     agentVersion(),
			})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "env": env, "network": network, "unit": unit,
			"via": "systemctl", "binary": bin,
			"upstream_port": prof.NodeHTTP, "public_port": pub,
			"go_rpc": true, "started": true,
			"version": agentVersion(),
		})
		return
	}

	// Ethereum: ensure geth + lighthouse binaries + JWT + units, then enable --now.
	if network == "ethereum" {
		prof := lookupPortProfile(network, env)
		gethBin, err := ensureGethInstalled()
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "geth_missing", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		lhBin, err := ensureLighthouseInstalled()
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "lighthouse_missing", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		_ = ensureNodeopUser()
		jwtPath := filepath.Join(prof.EtcPath, "jwt.hex")
		if err := ensureJWT(jwtPath); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "jwt_failed", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		req := nodeProvisionRequest{
			Network: "ethereum", Env: env,
			NodeHTTPPort: prof.NodeHTTP, P2PPort: prof.P2P,
		}
		cluster := lookupEthereumNetwork(env)
		gethUnitName := fmt.Sprintf("ethereum-geth-%s.service", env)
		lhUnitName := fmt.Sprintf("ethereum-lighthouse-%s.service", env)
		gethData := filepath.Join(prof.DataPath, "geth")
		lhData := filepath.Join(prof.DataPath, "lighthouse")
		gethUnit := renderGethUnit(env, gethBin, gethData, jwtPath, req, prof, cluster)
		lhUnit := renderLighthouseUnit(env, lhBin, lhData, jwtPath, prof.SolHTTP, prof.PBFTHTTP, prof.Metrics, cluster)
		if err := os.WriteFile(filepath.Join("/etc/systemd/system", gethUnitName), []byte(gethUnit), 0o644); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "unit_rewrite_failed", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		if err := os.WriteFile(filepath.Join("/etc/systemd/system", lhUnitName), []byte(lhUnit), 0o644); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "unit_rewrite_failed", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", gethUnitName, lhUnitName).Run()
		_ = exec.Command("systemctl", "enable", gethUnitName, lhUnitName).Run()
		out, err := exec.Command("systemctl", "restart", gethUnitName).CombinedOutput()
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": fmt.Sprintf("systemctl restart %s: %v (%s)", gethUnitName, err, strings.TrimSpace(string(out))),
				"hint":    "geth unit failed — see journalctl -u " + gethUnitName,
				"version": agentVersion(),
			})
			return
		}
		out, err = exec.Command("systemctl", "restart", lhUnitName).CombinedOutput()
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": fmt.Sprintf("systemctl restart %s: %v (%s)", lhUnitName, err, strings.TrimSpace(string(out))),
				"hint":    "lighthouse unit failed — see journalctl -u " + lhUnitName,
				"version": agentVersion(),
			})
			return
		}
		time.Sleep(2500 * time.Millisecond)
		gethActive, _ := exec.Command("systemctl", "is-active", gethUnitName).CombinedOutput()
		lhActive, _ := exec.Command("systemctl", "is-active", lhUnitName).CombinedOutput()
		gethState := strings.TrimSpace(string(gethActive))
		lhState := strings.TrimSpace(string(lhActive))
		rpcUp := prof.NodeHTTP > 0 && portOpenLocal(prof.NodeHTTP)
		if (gethState == "failed" || lhState == "failed") || (!rpcUp && gethState != "active" && gethState != "activating") {
			jOut, _ := exec.Command("journalctl", "-u", gethUnitName, "-n", "16", "--no-pager", "-o", "cat").CombinedOutput()
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message":    "ethereum units failed after start",
				"geth_state": gethState, "lighthouse_state": lhState,
				"journal": strings.TrimSpace(string(jOut)),
				"version": agentVersion(),
			})
			return
		}
		pub := prof.Public
		if doc := readJSONFile(filepath.Join("/etc/rpcnode/nodes", "ethereum-"+env+".json")); doc != nil {
			if v := intFromJSON(doc["public_port"]); v > 0 {
				pub = v
			}
		}
		if pub <= 0 {
			pub = 39690
		}
		if err := ensureEthereumGoRPC(env, pub); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "go_rpc_down",
				"message":     err.Error(),
				"public_port": pub,
				"hint":        "per-node api-agent must bind TRON_PUBLIC_PORT (Go RPC)",
				"version":     agentVersion(),
			})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "env": env, "network": network,
			"units": []string{gethUnitName, lhUnitName},
			"via":   "systemctl", "geth": gethBin, "lighthouse": lhBin,
			"upstream_port": prof.NodeHTTP, "public_port": pub,
			"go_rpc": true, "started": true,
			"version": agentVersion(),
		})
		return
	}

	unit := fmt.Sprintf("%s-%s.service", network, env)

	// Stellar: heal captive-core.cfg, kill orphan cores (bind-in-use), full-history toml.
	if network == "stellar" {
		prof := lookupPortProfile(network, env)
		cluster := lookupStellarNetwork(env)
		if _, err := patchStellarCaptiveCorePorts(prof.EtcPath, cluster); err != nil {
			// Missing cfg → try full download (same as provision).
			if _, err2 := ensureStellarCaptiveCoreCfg(prof.EtcPath, cluster); err2 != nil {
				writeJSON(w, http.StatusConflict, map[string]any{
					"ok": false, "error": "stellar_core_cfg_failed",
					"message": err2.Error(), "version": agentVersion(),
				})
				return
			}
		}
		live := resolveLivePortProfile(network, env)
		if changed, err := ensureStellarFullHistoryToml(prof.EtcPath, live.SolHTTP); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "stellar_toml_failed", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		} else if changed {
			// Process must recycle to pick up HISTORY_RETENTION_WINDOW / HTTP_QUERY.
		}
		resetStellarCaptiveCoreRuntime(env, prof.EtcPath, prof.DataPath, cluster)
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out))),
				"hint":    "stellar-rpc unit failed — see journalctl -u " + unit,
				"version": agentVersion(),
			})
			return
		}
		time.Sleep(2500 * time.Millisecond)
		activeOut, _ := exec.Command("systemctl", "is-active", unit).CombinedOutput()
		state := strings.TrimSpace(string(activeOut))
		failedOut, _ := exec.Command("systemctl", "is-failed", unit).CombinedOutput()
		failed := strings.TrimSpace(string(failedOut)) == "failed" || state == "failed"
		rpcUp := prof.NodeHTTP > 0 && portOpenLocal(prof.NodeHTTP)
		if failed || (!rpcUp && state != "active" && state != "activating") {
			jOut, _ := exec.Command("journalctl", "-u", unit, "-n", "24", "--no-pager", "-o", "cat").CombinedOutput()
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": "unit failed after start",
				"state":   state,
				"journal": strings.TrimSpace(string(jOut)),
				"version": agentVersion(),
			})
			return
		}
		pub := prof.Public
		if doc := readJSONFile(filepath.Join("/etc/rpcnode/nodes", "stellar-"+env+".json")); doc != nil {
			if v := intFromJSON(doc["public_port"]); v > 0 {
				pub = v
			}
		}
		if pub <= 0 {
			pub = 40890
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "env": env, "network": network, "unit": unit,
			"via": "systemctl", "history_retention_window": stellarHistoryRetentionWindow,
			"upstream_port": prof.NodeHTTP, "public_port": pub,
			"go_rpc": true, "started": true,
			"version": agentVersion(),
		})
		return
	}

	if network == "xrpl" {
		switch removeJobStatus(network, env) {
		case "deleting", "started", "wiped", "error":
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "remove_pending",
				"message": "remove job in flight — not starting xrpld",
				"version": agentVersion(),
			})
			return
		}
		prof := lookupPortProfile(network, env)
		confPath, err := healXRPLConfig(env)
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "xrpl_conf_failed", "message": err.Error(),
				"version": agentVersion(),
			})
			return
		}
		if bin, berr := ensureXRPLDInstalled(prof.OptPath, env); berr == nil && bin != "" {
			unitPath := filepath.Join("/etc/systemd/system", unit)
			_ = os.WriteFile(unitPath, []byte(renderXRPLUnit(env, bin, confPath)), 0o644)
		}
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		if err := recycleXRPLUnit(unit); err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": err.Error(),
				"conf":    confPath,
				"hint":    "xrpld unit failed — see journalctl -u " + unit,
				"version": agentVersion(),
			})
			return
		}
		time.Sleep(2500 * time.Millisecond)
		activeOut, _ := exec.Command("systemctl", "is-active", unit).CombinedOutput()
		state := strings.TrimSpace(string(activeOut))
		failedOut, _ := exec.Command("systemctl", "is-failed", unit).CombinedOutput()
		failed := strings.TrimSpace(string(failedOut)) == "failed" || state == "failed"
		rpcUp := prof.NodeHTTP > 0 && portOpenLocal(prof.NodeHTTP)
		if failed || (!rpcUp && state != "active" && state != "activating") {
			jOut, _ := exec.Command("journalctl", "-u", unit, "-n", "24", "--no-pager", "-o", "cat").CombinedOutput()
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": "unit failed after start",
				"conf":    confPath,
				"state":   state,
				"journal": strings.TrimSpace(string(jOut)),
				"version": agentVersion(),
			})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "env": env, "network": network, "unit": unit,
			"via": "systemctl", "conf": confPath, "started": true,
			"node_size": xrplNodeSize(hostMemTotalGiB(), xrplDatadirHasLedger(prof.DataPath)),
			"version":   agentVersion(),
		})
		return
	}

	if network == "tron" {
		prof := lookupPortProfile(network, env)
		nodeHTTP := prof.NodeHTTP
		p2p := prof.P2P
		if doc := readJSONFile(filepath.Join("/etc/rpcnode/nodes", "tron-"+env+".json")); doc != nil {
			if v := intFromJSON(doc["node_http_port"]); v > 0 {
				nodeHTTP = v
			}
			if v := intFromJSON(doc["p2p_port"]); v > 0 {
				p2p = v
			}
		}
		if _, err := ensureTronNodeUnit(env, nodeHTTP, p2p); err != nil {
			hostLogf("ERROR", "api-agent", "start", "tron_unit_failed %s: %v", env, err)
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "tron_unit_failed", "message": err.Error(),
				"hint":    "Need FullNode.jar + main_net_config.conf + Java 8; stub ExecStart=/bin/false cannot start",
				"version": agentVersion(),
			})
			return
		}
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "reset-failed", unit).Run()
		_ = exec.Command("systemctl", "enable", unit).Run()
		out, err := exec.Command("systemctl", "restart", unit).CombinedOutput()
		if err != nil {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": fmt.Sprintf("systemctl restart %s: %v (%s)", unit, err, strings.TrimSpace(string(out))),
				"log":     tronStartLogSnippet(env, 20),
				"version": agentVersion(),
			})
			return
		}
		time.Sleep(2 * time.Second)
		active := strings.TrimSpace(string(mustCombined("systemctl", "is-active", unit)))
		if active != "active" && active != "activating" {
			writeJSON(w, http.StatusConflict, map[string]any{
				"ok": false, "error": "start_failed",
				"message": "tron unit not active after restart (state=" + active + ")",
				"log":     tronStartLogSnippet(env, 20),
				"hint":    "See /opt/tron/" + env + "/logs/tron.log (checkpoint.version / Java 8)",
				"version": agentVersion(),
			})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "env": env, "network": network, "unit": unit,
			"via": "systemctl", "started": true, "state": active, "version": agentVersion(),
		})
		return
	}

	out, err := exec.Command("systemctl", "start", unit).CombinedOutput()
	if err != nil {
		writeJSON(w, http.StatusConflict, map[string]any{
			"ok": false, "error": "start_failed",
			"message": fmt.Sprintf("systemctl start %s: %v (%s)", unit, err, strings.TrimSpace(string(out))),
			"hint":    "Complete node unit setup if ExecStart is still a stub",
			"version": agentVersion(),
		})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "env": env, "network": network, "unit": unit, "via": "systemctl", "version": agentVersion(),
	})
}

func mustCombined(name string, args ...string) []byte {
	out, _ := exec.Command(name, args...).CombinedOutput()
	return out
}

func (s *Server) handleNodesListLocal(w http.ResponseWriter, r *http.Request) {
	items := listLocalNodeEnvs()
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "items": items, "count": len(items)})
}

func (s *Server) handleNodesPlan(w http.ResponseWriter, r *http.Request) {
	var req nodePlanRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
		return
	}
	req.Network = normalizeNetwork(req.Network)
	req.Env = normalizeEnv(req.Env)
	if req.Network == "avalanche" {
		req.Env = normalizeAvalancheEnv(req.Env)
	}
	if !networkEnvSupported(req.Network, req.Env) {
		writeJSON(w, http.StatusBadRequest, unsupportedNetworkEnvPayload(req.Network, req.Env))
		return
	}
	if err := checkOneEnvPerHost(req.Network, req.Env); err != nil {
		c := networkConstraint(req.Network)
		writeJSON(w, http.StatusConflict, map[string]any{
			"ok": false, "error": constraintOneEnvPerHost,
			"message":             err.Error(),
			"network":             req.Network,
			"env":                 req.Env,
			"occupied_envs":       occupiedEnvsForNetwork(req.Network),
			"network_constraint":  c,
			"network_constraints": networkHostConstraints(),
		})
		return
	}
	// Plan always returns tip catalog ports (no remap). Busy check is Install/provision.
	pub, agentPort, nodeHTTP, p2p, reused, err := planEnvPorts(req.Network, req.Env)
	if err != nil {
		writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "error": "no_canonical_ports", "message": err.Error()})
		return
	}
	canon := canonicalPorts(req.Network, req.Env)
	prof := lookupPortProfile(req.Network, req.Env)
	msg := "fixed ports from tip agent catalog (network/env); Install checks they are free"
	if reused {
		msg = "catalog ports match prior provision for network/env; " + msg
	}
	checked, busy := buildCheckedPorts(req.Network, req.Env)
	out := map[string]any{
		"ok": true, "network": req.Network, "env": req.Env,
		"public_port": pub, "agent_port": agentPort, "node_http_port": nodeHTTP, "p2p_port": p2p,
		"checked_ports": checked,
		"busy_ports":    busy,
		"ports_free":    len(busy) == 0,
		"canonical": map[string]int{
			"public_port": canon.Public, "agent_port": canon.Agent,
			"node_http_port": canon.NodeHTTP, "p2p_port": canon.P2P,
			"captive_core_http_query_port": prof.SolHTTP, "admin_port": prof.Metrics,
		},
		"ports": map[string]int{
			"public_port": pub, "agent_port": agentPort, "node_http_port": nodeHTTP, "p2p_port": p2p,
			"sol_http_port": prof.SolHTTP, "pbft_http_port": prof.PBFTHTTP,
			"grpc_port": prof.GRPC, "grpc_sol_port": prof.GRPCSol, "grpc_pbft_port": prof.GRPCPbft,
			"metrics_port":                 prof.Metrics,
			"captive_core_http_query_port": prof.SolHTTP, "admin_port": prof.Metrics,
		},
		"captive_core_http_query_port": prof.SolHTTP,
		"admin_port":                   prof.Metrics,
		"drifted":                      false,
		"message":                      msg,
		"snapshot": req.Network != "bitcoin" && req.Network != "solana" &&
			req.Network != "ethereum" && req.Network != "bsc" &&
			req.Network != "hyperliquid" && req.Network != "arb" && req.Network != "robinhood" && req.Network != "optimism" &&
			req.Network != "base" &&
			req.Network != "xrpl" && req.Network != "doge" && req.Network != "ltc" &&
			req.Network != "dash" && req.Network != "bch" && req.Network != "cardano" &&
			req.Network != "stellar" && req.Network != "ton" && req.Network != "etc",
		"supported_networks":  supportedNetworks(),
		"network_constraints": networkHostConstraints(),
		"capabilities":        lifecycleCapabilities(req.Network, req.Env),
		"install_options":     installOptionGroups(req.Network, req.Env),
	}
	if c := networkConstraint(req.Network); c != nil {
		out["network_constraint"] = c
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleNodesProvision(w http.ResponseWriter, r *http.Request) {
	var req nodeProvisionRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
		return
	}
	s.handleNodesProvisionBody(w, req)
}

func (s *Server) handleNodesProvisionBody(w http.ResponseWriter, req nodeProvisionRequest) {
	req.Network = normalizeNetwork(req.Network)
	req.Env = normalizeEnv(req.Env)
	if req.Network == "avalanche" {
		req.Env = normalizeAvalancheEnv(req.Env)
	}
	// Re-provision supersedes an interrupted wipe — do not resume-delete a fresh node.
	hostLogf("INFO", "api-agent", "provision", "begin %s/%s", req.Network, req.Env)
	logProvisionClientCatalog(req.Network, req.Env)
	clearRemoveJobOnProvision(req.Network, req.Env)
	if !networkEnvSupported(req.Network, req.Env) {
		writeJSON(w, http.StatusBadRequest, unsupportedNetworkEnvPayload(req.Network, req.Env))
		return
	}
	req.InstallOptions = mergeInstallOptions(req.Network, req.Env, req.InstallOptions)
	_ = writeInstallOptions(req.Network, req.Env, req.InstallOptions)
	if err := checkOneEnvPerHost(req.Network, req.Env); err != nil {
		c := networkConstraint(req.Network)
		writeJSON(w, http.StatusConflict, map[string]any{
			"ok": false, "error": constraintOneEnvPerHost,
			"message":             err.Error(),
			"network":             req.Network,
			"env":                 req.Env,
			"occupied_envs":       occupiedEnvsForNetwork(req.Network),
			"network_constraint":  c,
			"network_constraints": networkHostConstraints(),
		})
		return
	}

	// Fixed catalog ports only — never scan / +1. Fill omissions from tip canon.
	canon := canonicalPorts(req.Network, req.Env)
	if canon.Public <= 0 {
		writeJSON(w, http.StatusConflict, map[string]any{
			"ok": false, "error": "no_canonical_ports",
			"message": fmt.Sprintf("no tip catalog ports for %s/%s", req.Network, req.Env),
		})
		return
	}
	if (req.PublicPort > 0 && req.PublicPort != canon.Public) ||
		(req.AgentPort > 0 && req.AgentPort != canon.Agent) ||
		(req.NodeHTTPPort > 0 && req.NodeHTTPPort != canon.NodeHTTP) ||
		(canon.P2P > 0 && req.P2PPort > 0 && req.P2PPort != canon.P2P) {
		writeJSON(w, http.StatusConflict, map[string]any{
			"ok": false, "error": "ports_mismatch",
			"message": fmt.Sprintf(
				"provision ports must match tip catalog for %s/%s (public=%d agent=%d node_http=%d p2p=%d)",
				req.Network, req.Env, canon.Public, canon.Agent, canon.NodeHTTP, canon.P2P,
			),
			"canonical": map[string]int{
				"public_port": canon.Public, "agent_port": canon.Agent,
				"node_http_port": canon.NodeHTTP, "p2p_port": canon.P2P,
			},
		})
		return
	}
	req.PublicPort = canon.Public
	req.AgentPort = canon.Agent
	req.NodeHTTPPort = canon.NodeHTTP
	req.P2PPort = canon.P2P
	if req.PublicPort == req.AgentPort || req.PublicPort == req.NodeHTTPPort || req.AgentPort == req.NodeHTTPPort {
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"ok": false, "error": "ports_must_differ",
			"message": "public_port (Go RPC), agent_port (per-node Agent API) and node_http_port (upstream) must differ",
		})
		return
	}

	if busy := checkEnvPortsBusy(req.Network, req.Env); len(busy) > 0 {
		writeJSON(w, http.StatusConflict, map[string]any{
			"ok": false, "error": "port_busy",
			"message":    portBusyMessage(req.Network, req.Env, busy),
			"busy_ports": busy,
			"canonical": map[string]int{
				"public_port": canon.Public, "agent_port": canon.Agent,
				"node_http_port": canon.NodeHTTP, "p2p_port": canon.P2P,
			},
		})
		return
	}

	if err := ensureNetworkHostDeps(req.Network); err != nil {
		hostLogf("ERROR", "api-agent", "provision", "host_deps_failed %s/%s: %v", req.Network, req.Env, err)
		writeJSON(w, http.StatusInternalServerError, map[string]any{
			"ok": false, "error": "host_deps_failed", "message": err.Error(),
			"network": req.Network, "env": req.Env, "version": agentVersion(),
		})
		return
	}

	// Write dirs/units only. Activation is delayed so the HTTP response can flush first.
	var result map[string]any
	var err error
	switch req.Network {
	case "bitcoin":
		result, err = provisionBitcoinNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "ethereum":
		result, err = provisionEthereumNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "bsc":
		result, err = provisionBSCNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "solana":
		result, err = provisionSolanaNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "hyperliquid":
		result, err = provisionHyperliquidNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "arb":
		result, err = provisionArbNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "robinhood":
		result, err = provisionRobinhoodNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "optimism":
		result, err = provisionOptimismNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "base":
		result, err = provisionBaseNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "xrpl":
		result, err = provisionXRPLNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "doge":
		result, err = provisionDogeNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "ltc", "dash", "bch":
		result, err = provisionCoreLikeNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "cardano":
		result, err = provisionCardanoNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "stellar":
		result, err = provisionStellarNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "ton":
		result, err = provisionTonNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "etc":
		result, err = provisionETCNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "zcash":
		result, err = provisionZcashNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "sui":
		result, err = provisionSuiNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "aptos":
		result, err = provisionAptosNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "avalanche":
		result, err = provisionAvalancheNodeEnv(req, lookupPortProfile(req.Network, req.Env))
	case "tron":
		result, err = provisionNodeEnv(req)
	default:
		writeJSON(w, http.StatusBadRequest, unsupportedNetworkEnvPayload(req.Network, req.Env))
		return
	}
	if err != nil {
		hostLogf("ERROR", "api-agent", "provision", "fail %s/%s: %v", req.Network, req.Env, err)
		writeJSON(w, http.StatusInternalServerError, map[string]any{
			"ok": false, "error": "provision_failed", "message": err.Error(),
		})
		return
	}
	hostLogf("INFO", "api-agent", "provision", "ok %s/%s", req.Network, req.Env)
	result["version"] = agentVersion()
	result["units_start"] = "scheduled"
	result["units_start_delay_ms"] = 600
	result["ports_stable"] = true
	result["supported_networks"] = supportedNetworks()
	writeJSON(w, http.StatusOK, result)
	if f, ok := w.(http.Flusher); ok {
		f.Flush()
	}

	go func(r nodeProvisionRequest) {
		time.Sleep(600 * time.Millisecond)
		if steps, err := ensureAgentFileLogging(); err != nil {
			log.Printf("agent file logging: %v (%v)", err, steps)
		} else {
			_ = exec.Command("systemctl", "daemon-reload").Run()
		}
		if actErr := activateProvisionedNetwork(r); actErr != nil {
			fmt.Fprintf(os.Stderr, "provision activate %s/%s: %v\n", r.Network, r.Env, actErr)
		}
	}(req)
}

func activateProvisionedNetwork(r nodeProvisionRequest) error {
	switch r.Network {
	case "bitcoin":
		return activateBitcoinUnits(r.Env)
	case "ethereum":
		return activateEthereumUnits(r.Env)
	case "bsc":
		return activateBSCUnits(r.Env)
	case "solana":
		return activateSolanaUnits(r.Env)
	case "hyperliquid":
		return activateHyperliquidUnits(r.Env)
	case "arb":
		return activateArbUnits(r.Env)
	case "robinhood":
		return activateRobinhoodUnits(r.Env)
	case "optimism":
		return activateOptimismUnits(r.Env)
	case "base":
		return activateBaseUnits(r.Env)
	case "xrpl":
		return activateXRPLUnits(r.Env)
	case "doge":
		return activateDogeUnits(r.Env)
	case "ltc", "dash", "bch":
		return activateCoreLikeUnits(r.Network, r.Env)
	case "cardano":
		return activateCardanoUnits(r.Env)
	case "stellar":
		return activateStellarUnits(r.Env)
	case "ton":
		return activateTonUnits(r.Env)
	case "etc":
		return activateETCUnits(r.Env)
	case "zcash":
		return activateZcashUnits(r.Env)
	case "sui":
		return activateSuiUnits(r.Env)
	case "aptos":
		return activateAptosUnits(r.Env)
	case "avalanche":
		return activateAvalancheUnits(r.Env)
	case "tron":
		return activateProvisionedUnits(r)
	default:
		return fmt.Errorf("no activate for network %q", r.Network)
	}
}

func normalizeNetwork(v string) string {
	v = strings.ToLower(strings.TrimSpace(v))
	if v == "" {
		return "tron"
	}
	return v
}

func normalizeEnv(v string) string {
	v = strings.ToLower(strings.TrimSpace(v))
	if v == "" {
		return "mainnet"
	}
	return v
}

// snapshotBlocksNodeStart — wget|tar / sui-tool must finish before the node
// unit starts. Robinhood start IS the snapshot (--init.url) — never block.
func snapshotBlocksNodeStart(network, env string) (bool, string) {
	network = strings.ToLower(strings.TrimSpace(network))
	env = normalizeEnv(env)
	if network == "robinhood" {
		return false, ""
	}
	prof := lookupPortProfile(network, env)
	need := false
	for _, s := range prof.ExtraSteps {
		if s == "snapshot" {
			need = true
			break
		}
	}
	if !need {
		return false, ""
	}
	data := strings.TrimSpace(prof.DataPath)
	if data == "" {
		data = filepath.Join("/data", network, env)
	}
	marker := filepath.Join(data, ".snapshot-ready")
	if fileExists(marker) {
		return false, ""
	}
	return true, fmt.Sprintf("%s/%s snapshot is required before start (missing %s)", network, env, marker)
}

type envPorts struct {
	Public   int
	Agent    int
	NodeHTTP int
	P2P      int
}

// canonicalPorts — keep in sync with system-agent NetworkProfile defaults
// (network_profiles.go / network_ports.go). Drift breaks healthz probes.
func canonicalPorts(network, env string) envPorts {
	p := lookupPortProfile(network, env)
	return envPorts{Public: p.Public, Agent: p.Agent, NodeHTTP: p.NodeHTTP, P2P: p.P2P}
}

// planEnvPorts returns tip catalog ports for network/env (fixed — no scan/remap).
// Busy check is checkEnvPortsBusy / provision Install path.
func planEnvPorts(network, env string) (publicPort, agentPort, nodeHTTP, p2p int, reused bool, err error) {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	canon := canonicalPorts(network, env)
	if canon.Public <= 0 {
		return 0, 0, 0, 0, false, fmt.Errorf("no canonical ports for %s/%s", network, env)
	}
	existing := loadExistingPorts(network, env)
	reused = existing.Public == canon.Public && existing.Agent == canon.Agent &&
		existing.NodeHTTP == canon.NodeHTTP &&
		(canon.P2P <= 0 || existing.P2P == canon.P2P || existing.P2P <= 0)
	return canon.Public, canon.Agent, canon.NodeHTTP, canon.P2P, reused, nil
}

// checkEnvPortsBusy — foreign listeners on every catalog port the network binds.
func checkEnvPortsBusy(network, env string) []map[string]any {
	_, busy := buildCheckedPorts(network, env)
	return busy
}

func (s *Server) handleNodesCheckPorts(w http.ResponseWriter, r *http.Request) {
	var req nodePlanRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
		return
	}
	req.Network = normalizeNetwork(req.Network)
	req.Env = normalizeEnv(req.Env)
	if req.Network == "avalanche" {
		req.Env = normalizeAvalancheEnv(req.Env)
	}
	if !networkEnvSupported(req.Network, req.Env) {
		writeJSON(w, http.StatusBadRequest, unsupportedNetworkEnvPayload(req.Network, req.Env))
		return
	}
	canon := canonicalPorts(req.Network, req.Env)
	stopPortProbe()
	checked, busy := buildCheckedPorts(req.Network, req.Env)
	out := map[string]any{
		"ok": true, "network": req.Network, "env": req.Env,
		"ports_free":    len(busy) == 0,
		"busy_ports":    busy,
		"checked_ports": checked,
		"canonical": map[string]int{
			"public_port": canon.Public, "agent_port": canon.Agent,
			"node_http_port": canon.NodeHTTP, "p2p_port": canon.P2P,
		},
		"public_port": canon.Public, "agent_port": canon.Agent,
		"node_http_port": canon.NodeHTTP, "p2p_port": canon.P2P,
	}
	if len(busy) > 0 {
		out["ok"] = false
		out["error"] = "port_busy"
		out["message"] = portBusyMessage(req.Network, req.Env, busy)
		writeJSON(w, http.StatusConflict, out)
		return
	}
	out["message"] = "catalog ports are free (or reclaimable by this network/env leaf units)"
	writeJSON(w, http.StatusOK, out)
}

// profileAllPorts — every port a profile claims (primary + TRON aux + bitcoin ZMQ).
func profileAllPorts(p networkPortProfile) []int {
	return []int{
		p.Public, p.Agent, p.NodeHTTP, p.P2P,
		p.SolHTTP, p.PBFTHTTP, p.GRPC, p.GRPCSol, p.GRPCPbft, p.Metrics,
		p.ZMQRawBlock, p.ZMQRawTx,
	}
}

// reservedPortSet — ports owned by OTHER profiles (not exceptNetwork/exceptEnv).
// Used so findFreePort never assigns Shasta's canonical HTTP to a drifting Nile.
func reservedPortSet(exceptNetwork, exceptEnv string) map[int]bool {
	exceptNetwork = normalizeNetwork(exceptNetwork)
	exceptEnv = normalizeEnv(exceptEnv)
	used := map[int]bool{}
	for _, p := range builtinPortProfiles() {
		if normalizeNetwork(p.Network) == exceptNetwork && normalizeEnv(p.Env) == exceptEnv {
			continue
		}
		for _, port := range profileAllPorts(p) {
			if port > 0 {
				used[port] = true
			}
		}
	}
	return used
}

// resolveLivePortProfile — tip catalog only (no aux remap / +1).
func resolveLivePortProfile(network, env string) networkPortProfile {
	return lookupPortProfile(network, env)
}

// planFreePorts kept for callers/tests — wraps planEnvPorts (tron default).
func planFreePorts(env string) (publicPort, agentPort, nodeHTTP, p2p int, err error) {
	publicPort, agentPort, nodeHTTP, p2p, _, err = planEnvPorts("tron", env)
	return
}

func portsReclaimable(p envPorts, network, env string) bool {
	if p.Public <= 0 || p.Agent <= 0 || p.NodeHTTP <= 0 {
		return false
	}
	if p.Public == p.Agent || p.Public == p.NodeHTTP || p.Agent == p.NodeHTTP {
		return false
	}
	for _, port := range []int{p.Public, p.Agent, p.NodeHTTP} {
		if portBusyForeign(port, network, env) {
			return false
		}
	}
	if p.P2P > 0 && portBusyForeign(p.P2P, network, env) {
		return false
	}
	return true
}

// profileAuxBusyForeign — captive-core HTTP_QUERY (SolHTTP), admin/metrics, TRON aux, ZMQ.
func profileAuxBusyForeign(p networkPortProfile, network, env string) bool {
	for _, port := range []int{
		p.SolHTTP, p.PBFTHTTP, p.GRPC, p.GRPCSol, p.GRPCPbft, p.Metrics,
		p.ZMQRawBlock, p.ZMQRawTx,
	} {
		if port > 0 && portBusyForeign(port, network, env) {
			return true
		}
	}
	return false
}

// portBusyForeign reports a LISTEN socket that is NOT ours to replace on re-provision.
// ESTABLISHED / ephemeral source ports do not count — catalog 39xxx–42xxx sits inside
// Linux ip_local_port_range (32768–60999); net.Listen would false-positive as port_busy.
func portBusyForeign(port int, network, env string) bool {
	return portBusyHolder(port, network, env) != ""
}

func portBusyHolder(port int, network, env string) string {
	if port <= 0 || !portIsListening(port) {
		return ""
	}
	if portOwnedByEnv(port, network, env) {
		return ""
	}
	pids := portListenerPIDs(port)
	if len(pids) == 0 {
		// ss miss: check-ports runs on tip; a LISTEN here is the tip collision case.
		if isHostTipProcess() {
			return "host_tip"
		}
		return "foreign"
	}
	for _, pid := range pids {
		if !isHostTipListenerPID(pid) {
			return "foreign"
		}
	}
	return "host_tip"
}

func portBusyMessage(network, env string, busy []map[string]any) string {
	if busyOnlyHostTipCollision(busy, 0, 0) {
		return fmt.Sprintf(
			"host tip is listening on a catalog port for %s/%s — tip must not use leaf public/agent ports",
			network, env,
		)
	}
	return fmt.Sprintf("catalog ports for %s/%s are in use by a foreign process", network, env)
}

// busyOnlyHostTipCollision — every busy row is the host tip on leaf public/agent.
// public/agent 0 = do not constrain ports (message helper).
func busyOnlyHostTipCollision(busy []map[string]any, public, agent int) bool {
	if len(busy) == 0 {
		return false
	}
	for _, b := range busy {
		if h, _ := b["holder"].(string); h != "host_tip" {
			return false
		}
		role, _ := b["role"].(string)
		if role != "public_port" && role != "agent_port" {
			return false
		}
		if public > 0 || agent > 0 {
			port := intFromAny(b["port"])
			if port != public && port != agent {
				return false
			}
		}
	}
	return true
}

func isHostTipListenerPID(pid string) bool {
	pid = strings.TrimSpace(pid)
	if pid == "" || pid == "0" {
		return false
	}
	if isHostTipProcess() && pid == strconv.Itoa(os.Getpid()) {
		return true
	}
	tip := strings.TrimSpace(string(mustCmdOut("systemctl", "show", "-p", "MainPID", "--value", "rpcnode-api-agent.service")))
	return tip != "" && tip != "0" && tip == pid
}

func envReclaimUnits(network, env string) []string {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	return []string{
		fmt.Sprintf("rpcnode-api-agent-%s.service", env),
		fmt.Sprintf("rpcnode-system-agent-%s.service", env),
		fmt.Sprintf("rpcnode-api-agent-%s-%s.service", network, env),
		fmt.Sprintf("rpcnode-system-agent-%s-%s.service", network, env),
		fmt.Sprintf("%s-%s.service", network, env),
		fmt.Sprintf("%s-clio-%s.service", network, env),
	}
}

func portOwnedByEnv(port int, network, env string) bool {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	self := strconv.Itoa(os.Getpid())

	for _, pid := range portListenerPIDs(port) {
		if pid == "0" || isHostTipListenerPID(pid) {
			continue
		}
		if pid == self {
			return true
		}
	}

	units := envReclaimUnits(network, env)
	keep := map[string]bool{}
	for _, u := range units {
		pid := strings.TrimSpace(string(mustCmdOut("systemctl", "show", "-p", "MainPID", "--value", u)))
		if pid != "" && pid != "0" {
			keep[pid] = true
		}
	}
	for _, pid := range portListenerPIDs(port) {
		if keep[pid] {
			return true
		}
	}

	for _, pid := range portListenerPIDs(port) {
		if pidBelongsToEnv(pid, network, env) {
			return true
		}
	}
	return false
}

func portListenerPIDs(port int) []string {
	if port <= 0 {
		return nil
	}
	out := []string{}
	seen := map[string]bool{}
	add := func(pid string) {
		pid = strings.TrimSpace(pid)
		if pid == "" || pid == "0" || seen[pid] {
			return
		}
		seen[pid] = true
		out = append(out, pid)
	}

	// ss -lntp → users:(("bin",pid=123,fd=8))
	ssOut := string(mustCmdOut("ss", "-H", "-lntp", fmt.Sprintf("sport = :%d", port)))
	for _, m := range regexp.MustCompile(`pid=(\d+)`).FindAllStringSubmatch(ssOut, -1) {
		if len(m) > 1 {
			add(m[1])
		}
	}
	if len(out) > 0 {
		return out
	}

	lsofOut := string(mustCmdOut("lsof", "-nP", "-t", "-iTCP:"+strconv.Itoa(port), "-sTCP:LISTEN"))
	for _, line := range strings.Split(lsofOut, "\n") {
		add(line)
	}
	return out
}

func pidBelongsToEnv(pid, network, env string) bool {
	blob := pidIdentityBlob(pid)
	if blob == "" {
		return false
	}
	network = normalizeNetwork(network)
	env = normalizeEnv(env)

	// Another network/env must not be treated as reclaimable.
	for _, p := range builtinPortProfiles() {
		if p.Network == network && p.Env == env {
			continue
		}
		if strings.Contains(blob, "/etc/"+p.Network+"/"+p.Env) ||
			strings.Contains(blob, "/var/lib/rpcnode/"+p.Network+"-"+p.Env) ||
			strings.Contains(blob, p.Network+"-"+p.Env+".service") ||
			strings.Contains(blob, "rpcnode-api-agent-"+p.Network+"-"+p.Env+".service") ||
			(p.Network == "tron" && strings.Contains(blob, "rpcnode-api-agent-"+p.Env+".service") && network != "tron") {
			return false
		}
	}

	markers := []string{
		"/etc/" + network + "/" + env,
		"TRON_ENV=" + env,
		"TRON_NETWORK=" + network,
		network + "-" + env + ".service",
		"rpcnode-api-agent-" + network + "-" + env + ".service",
		"rpcnode-system-agent-" + network + "-" + env + ".service",
		"/var/lib/rpcnode/" + network + "-" + env,
	}
	if network == "tron" {
		markers = append(markers,
			"rpcnode-api-agent-"+env+".service",
			"rpcnode-system-agent-"+env+".service",
		)
	}
	for _, m := range markers {
		if strings.Contains(blob, m) {
			return true
		}
	}

	// Host Server agent (no env suffix) — reclaimable for first provision.
	if strings.Contains(blob, "rpcnode-api-agent.service") ||
		strings.Contains(blob, "rpcnode-system-agent.service") {
		if !strings.Contains(blob, "rpcnode-api-agent-") &&
			!strings.Contains(blob, "rpcnode-system-agent-") {
			return true
		}
	}
	return false
}

func pidIdentityBlob(pid string) string {
	parts := []string{}
	for _, name := range []string{"cmdline", "environ", "cgroup"} {
		b, err := os.ReadFile(filepath.Join("/proc", pid, name))
		if err != nil {
			continue
		}
		parts = append(parts, strings.ReplaceAll(string(b), "\x00", " "))
	}
	return strings.Join(parts, "\n")
}

func loadExistingPorts(network, env string) envPorts {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	out := envPorts{}

	// 1) /etc/rpcnode/nodes/{network}-{env}.json (and legacy tron {env}.json)
	candidates := []string{
		filepath.Join("/etc/rpcnode/nodes", network+"-"+env+".json"),
	}
	if network == "tron" {
		candidates = append(candidates, filepath.Join("/etc/rpcnode/nodes", env+".json"))
	}
	for _, path := range candidates {
		if b, err := os.ReadFile(path); err == nil {
			var doc map[string]any
			if json.Unmarshal(b, &doc) == nil {
				out.Public = intFromJSON(doc["public_port"])
				out.Agent = intFromJSON(doc["agent_port"])
				out.NodeHTTP = intFromJSON(doc["node_http_port"])
				if out.NodeHTTP <= 0 {
					out.NodeHTTP = intFromJSON(doc["node_rpc_port"])
				}
				out.P2P = intFromJSON(doc["p2p_port"])
				break
			}
		}
	}

	// 2) toolkit.env
	if b, err := os.ReadFile(filepath.Join("/etc", network, env, "toolkit.env")); err == nil {
		for _, line := range strings.Split(string(b), "\n") {
			line = strings.TrimSpace(line)
			switch {
			case out.Public <= 0 && (strings.HasPrefix(line, "RPCNODE_PUBLIC_PORT=") || strings.HasPrefix(line, "TRON_PUBLIC_PORT=") ||
				strings.HasPrefix(line, "RPCNODE_GATEWAY_PORT=") || strings.HasPrefix(line, "TRON_GATEWAY_PORT=")):
				if n, ok := toolkitEnvLineInt(line, "RPCNODE_PUBLIC_PORT", "TRON_PUBLIC_PORT", "RPCNODE_GATEWAY_PORT", "TRON_GATEWAY_PORT"); ok {
					out.Public = n
				}
			case out.Agent <= 0 && (strings.HasPrefix(line, "RPCNODE_AGENT_PORT=") || strings.HasPrefix(line, "TRON_AGENT_PORT=") ||
				strings.HasPrefix(line, "RPCNODE_PANEL_PORT=") || strings.HasPrefix(line, "TRON_PANEL_PORT=")):
				if n, ok := toolkitEnvLineInt(line, "RPCNODE_AGENT_PORT", "TRON_AGENT_PORT", "RPCNODE_PANEL_PORT", "TRON_PANEL_PORT"); ok {
					out.Agent = n
				}
			case strings.HasPrefix(line, "TRON_NODE_HTTP_PORT=") && out.NodeHTTP <= 0:
				out.NodeHTTP, _ = strconv.Atoi(strings.TrimPrefix(line, "TRON_NODE_HTTP_PORT="))
			case strings.HasPrefix(line, "TRON_P2P_PORT=") && out.P2P <= 0:
				out.P2P, _ = strconv.Atoi(strings.TrimPrefix(line, "TRON_P2P_PORT="))
			}
		}
	}

	// 3) INSTANCE.json
	if b, err := os.ReadFile(filepath.Join("/var/lib/rpcnode", network+"-"+env, "INSTANCE.json")); err == nil {
		var doc map[string]any
		if json.Unmarshal(b, &doc) == nil {
			if out.Public <= 0 {
				out.Public = intFromJSON(doc["public_port"])
			}
			if out.Agent <= 0 {
				out.Agent = intFromJSON(doc["agent_port"])
			}
			if out.NodeHTTP <= 0 {
				out.NodeHTTP = intFromJSON(doc["node_http_port"])
			}
			if out.NodeHTTP <= 0 {
				out.NodeHTTP = intFromJSON(doc["node_rpc_port"])
			}
			if out.P2P <= 0 {
				out.P2P = intFromJSON(doc["p2p_port"])
			}
		}
	}
	return out
}

func pidComm(pid string) string {
	pid = strings.TrimSpace(pid)
	if pid == "" || pid == "0" {
		return ""
	}
	b, err := os.ReadFile("/proc/" + pid + "/comm")
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func intFromJSON(v any) int {
	switch t := v.(type) {
	case float64:
		return int(t)
	case int:
		return t
	case json.Number:
		i, _ := t.Int64()
		return int(i)
	case string:
		n, _ := strconv.Atoi(strings.TrimSpace(t))
		return n
	default:
		return 0
	}
}

func findFreePort(start, span int, exclude map[int]bool) (int, error) {
	if start < 1024 {
		start = 1024
	}
	end := start + span
	if end > 65535 {
		end = 65535
	}
	for p := start; p <= end; p++ {
		if isPopularPort(p) {
			continue
		}
		if exclude != nil && exclude[p] {
			continue
		}
		if !portInUse(p) {
			return p, nil
		}
	}
	return 0, fmt.Errorf("no free port in %d-%d", start, end)
}

func isPopularPort(p int) bool {
	switch p {
	case 22, 80, 443, 3306, 5432, 6379, 8080, 8443, 27017:
		return true
	default:
		return false
	}
}

// portIsListening — a process is bound LISTEN on port.
// Do not use net.Listen: catalog public/agent ports overlap the kernel ephemeral
// range, so an outbound TCP (healthz, overlay, panel) can make Listen fail while
// ss -lntp is empty → false check-ports port_busy (arb sepolia :40094).
func portIsListening(port int) bool {
	if port <= 0 {
		return false
	}
	if len(portListenerPIDs(port)) > 0 {
		return true
	}
	ssOut := strings.TrimSpace(string(mustCmdOut("ss", "-H", "-ltn", fmt.Sprintf("sport = :%d", port))))
	if ssOut == "" {
		return false
	}
	low := strings.ToLower(ssOut)
	if strings.Contains(low, "not found") || strings.Contains(low, "usage:") {
		return false
	}
	return true
}

func portInUse(port int) bool {
	ln, err := net.Listen("tcp", ":"+strconv.Itoa(port))
	if err != nil {
		return true
	}
	_ = ln.Close()
	return false
}

func listLocalNodeEnvs() []map[string]any {
	return listLocalNodeEnvsFrom("/etc/rpcnode/nodes", "/etc")
}

// listLocalNodeEnvsFrom — multi-network inventory for tip GET /api/v1/nodes.
// Prefer /etc/rpcnode/nodes/{network}-{env}.json; fall back to /etc/{network}/{env}/toolkit.env.
func listLocalNodeEnvsFrom(nodesDir, etcRoot string) []map[string]any {
	out := []map[string]any{}
	seen := map[string]bool{}

	appendItem := func(item map[string]any) {
		network, _ := item["network"].(string)
		env, _ := item["env"].(string)
		network = strings.ToLower(strings.TrimSpace(network))
		env = strings.ToLower(strings.TrimSpace(env))
		if network == "" || env == "" {
			return
		}
		key := network + "/" + env
		if seen[key] {
			return
		}
		seen[key] = true
		item["network"] = network
		item["env"] = env
		if _, ok := item["status"]; !ok {
			item["status"] = "present"
		}
		out = append(out, item)
	}

	entries, err := os.ReadDir(nodesDir)
	if err == nil {
		for _, e := range entries {
			if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
				continue
			}
			path := filepath.Join(nodesDir, e.Name())
			b, err := os.ReadFile(path)
			if err != nil {
				continue
			}
			var doc map[string]any
			if json.Unmarshal(b, &doc) != nil || doc == nil {
				continue
			}
			network, _ := doc["network"].(string)
			env, _ := doc["env"].(string)
			if network == "" || env == "" {
				network, env = splitNodesFileName(e.Name())
			}
			if network == "" || env == "" {
				continue
			}
			item := map[string]any{
				"network": network,
				"env":     env,
				"status":  "present",
				"source":  path,
			}
			if etc := filepath.Join(etcRoot, network, env); dirExists(etc) {
				item["etc_dir"] = etc
			}
			copyPortField(item, doc, "public_port")
			copyPortField(item, doc, "agent_port")
			copyPortField(item, doc, "node_http_port")
			copyPortField(item, doc, "p2p_port")
			if mode, ok := doc["rpc_mode"].(string); ok && mode != "" {
				item["rpc_mode"] = mode
			} else if intFromAny(doc["public_port"]) > 0 {
				item["rpc_mode"] = "go_proxy"
			}
			if url, ok := doc["agent_url"].(string); ok && strings.TrimSpace(url) != "" {
				item["agent_url"] = strings.TrimSpace(url)
			}
			appendItem(item)
		}
	}

	for _, network := range supportedNetworks() {
		root := filepath.Join(etcRoot, network)
		ents, err := os.ReadDir(root)
		if err != nil {
			continue
		}
		for _, e := range ents {
			if !e.IsDir() {
				continue
			}
			env := e.Name()
			envFile := filepath.Join(root, env, "toolkit.env")
			if _, err := os.Stat(envFile); err != nil {
				continue
			}
			item := map[string]any{
				"env": env, "network": network,
				"etc_dir": filepath.Join(root, env),
				"status":  "present",
				"source":  envFile,
			}
			if b, err := os.ReadFile(envFile); err == nil {
				pubProxy, nodeHTTP, agentPort, p2p := 0, 0, 0, 0
				netFromEnv := ""
				for _, line := range strings.Split(string(b), "\n") {
					switch {
					case pubProxy <= 0 && (strings.HasPrefix(line, "RPCNODE_PUBLIC_PORT=") || strings.HasPrefix(line, "TRON_PUBLIC_PORT=") ||
						strings.HasPrefix(line, "RPCNODE_GATEWAY_PORT=") || strings.HasPrefix(line, "TRON_GATEWAY_PORT=")):
						if n, ok := toolkitEnvLineInt(line, "RPCNODE_PUBLIC_PORT", "TRON_PUBLIC_PORT", "RPCNODE_GATEWAY_PORT", "TRON_GATEWAY_PORT"); ok {
							pubProxy = n
						}
					case strings.HasPrefix(line, "TRON_NODE_HTTP_PORT="):
						nodeHTTP, _ = strconv.Atoi(strings.TrimPrefix(line, "TRON_NODE_HTTP_PORT="))
					case agentPort <= 0 && (strings.HasPrefix(line, "RPCNODE_AGENT_PORT=") || strings.HasPrefix(line, "TRON_AGENT_PORT=") ||
						strings.HasPrefix(line, "RPCNODE_PANEL_PORT=") || strings.HasPrefix(line, "TRON_PANEL_PORT=")):
						if n, ok := toolkitEnvLineInt(line, "RPCNODE_AGENT_PORT", "TRON_AGENT_PORT", "RPCNODE_PANEL_PORT", "TRON_PANEL_PORT"); ok {
							agentPort = n
						}
					case strings.HasPrefix(line, "TRON_P2P_PORT="):
						p2p, _ = strconv.Atoi(strings.TrimPrefix(line, "TRON_P2P_PORT="))
					case strings.HasPrefix(line, "TRON_NETWORK="):
						netFromEnv = strings.TrimSpace(strings.TrimPrefix(line, "TRON_NETWORK="))
					}
				}
				if netFromEnv != "" {
					item["network"] = normalizeNetwork(netFromEnv)
				}
				item["node_http_port"] = nodeHTTP
				if agentPort > 0 {
					item["agent_port"] = agentPort
				}
				if p2p > 0 {
					item["p2p_port"] = p2p
				}
				if pubProxy > 0 {
					item["public_port"] = pubProxy
					item["rpc_mode"] = "go_proxy"
				} else if nodeHTTP > 0 {
					// Legacy misconfig: no Go RPC — treat FullNode port as public (not preferred).
					item["public_port"] = nodeHTTP
					item["rpc_mode"] = "fullnode_direct"
				}
			}
			appendItem(item)
		}
	}

	return out
}

func splitNodesFileName(name string) (network, env string) {
	base := strings.TrimSuffix(strings.TrimSpace(name), ".json")
	if base == "" {
		return "", ""
	}
	// Prefer {network}-{env}.json for known networks.
	for _, net := range supportedNetworks() {
		prefix := net + "-"
		if strings.HasPrefix(base, prefix) {
			env = strings.TrimPrefix(base, prefix)
			if env != "" {
				return net, env
			}
		}
	}
	// Legacy tron: mainnet.json / nile.json
	return "tron", base
}

func copyPortField(dst, src map[string]any, key string) {
	if n := intFromAny(src[key]); n > 0 {
		dst[key] = n
	}
}

func intFromAny(v any) int {
	switch t := v.(type) {
	case float64:
		return int(t)
	case float32:
		return int(t)
	case int:
		return t
	case int64:
		return int(t)
	case json.Number:
		i, _ := t.Int64()
		return int(i)
	case string:
		n, _ := strconv.Atoi(strings.TrimSpace(t))
		return n
	default:
		return 0
	}
}

func provisionNodeEnv(req nodeProvisionRequest) (map[string]any, error) {
	// TRON-only legacy provisioner. Other networks must use provision*NodeEnv.
	if normalizeNetwork(req.Network) != "tron" {
		return nil, fmt.Errorf("provisionNodeEnv is tron-only (got %s/%s)", req.Network, req.Env)
	}
	etc := filepath.Join("/etc/tron", req.Env)
	dataRoot := filepath.Join("/data/tron", req.Env)
	opt := filepath.Join("/opt/tron", req.Env)
	state := filepath.Join("/var/lib/rpcnode", "tron-"+req.Env)
	outputDir := resolveNetworkRoleDir(req, "tron", req.Env, "fullnode", filepath.Join(dataRoot, "output-directory"))
	solidityDir := resolveNetworkRoleDir(req, "tron", req.Env, "solidity", filepath.Join(dataRoot, "solidity"))
	data := dataRoot
	for _, d := range []string{etc, data, opt, state, outputDir, solidityDir} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return nil, fmt.Errorf("mkdir %s: %w", d, err)
		}
	}
	// Persist layout for ensureTronNodeUnit / INSTANCE (custom FullNode DB path).
	_ = writeJSONFile(filepath.Join(etc, "disk_layout.json"), map[string]any{
		"fullnode_dir": outputDir, "solidity_dir": solidityDir,
	})

	binDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	toolkitDir := envOr("TOOLKIT_DIR", "/opt/rpcnode/toolkit")
	token := envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", ""))

	// Loopback only — must NOT use 8091/8092 (java-tron default solidity/PBFT HTTP).
	// Canonical: ports.sh 29090/29091/29092.
	sysListen := systemAgentListenPort("tron", req.Env)

	opts := mergeInstallOptions(req.Network, req.Env, req.InstallOptions)
	_ = writeInstallOptions(req.Network, req.Env, opts)
	snapURL := resolveSnapshotURLForOptions(req.Network, req.Env, opts)
	if snapURL != "" {
		logDownload("snapshot", snapURL, req.Network+"/"+req.Env+" toolkit.env")
	}
	// Clients → Go RPC :public_port (sleep/maintenance) → FullNode :node_http (loopback).
	// Node Agent API on :agent_port for panel/control.
	envBody := fmt.Sprintf(`# managed by rpcnode provision %s
# RPC: Go proxy on :%d → FullNode :%d (sleep/maintenance on Go)
# Control: Node Agent API on :%d
%sTRON_NETWORK=%s
TRON_NODE_HTTP_HOST=127.0.0.1
TRON_NODE_HTTP_PORT=%d
TRON_P2P_PORT=%d
TRON_SYSTEM_AGENT_LISTEN=127.0.0.1:%d
TRON_SYSTEM_AGENT_URL=http://127.0.0.1:%d
TRON_SNAPSHOT_URL=%s
TRON_STATE_DIR=%s
TOOLKIT_DIR=%s
AGENT_API_TOKEN=%s
`,
		time.Now().UTC().Format(time.RFC3339),
		req.PublicPort, req.NodeHTTPPort, req.AgentPort,
		productEnvVars(req.Env, req.PublicPort, req.AgentPort), req.Network,
		req.NodeHTTPPort, req.P2PPort,
		sysListen, sysListen, snapURL, state, toolkitDir, token,
	)

	envPath := filepath.Join(etc, "toolkit.env")
	if err := os.WriteFile(envPath, []byte(envBody), 0o600); err != nil {
		return nil, err
	}

	apiUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node api-agent (tron/%s) — Go RPC :%d + Node Agent API :%d → FullNode :%d
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=tron
Environment=TRON_NODE_HTTP_HOST=127.0.0.1
Environment=TRON_NODE_HTTP_PORT=%d
Environment=TRON_SYSTEM_AGENT_URL=http://127.0.0.1:%d
Environment=TRON_STATE_DIR=%s
Environment=TOOLKIT_DIR=%s
ExecStart=%s/rpcnode-api-agent
Restart=always
RestartSec=2
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
`, req.Env, req.PublicPort, req.AgentPort, req.NodeHTTPPort, envPath,
		productSystemdAPIListenEnv(req.Env, req.PublicPort, req.AgentPort),
		req.NodeHTTPPort, sysListen, state, toolkitDir, binDir)

	sysUnit := fmt.Sprintf(`[Unit]
Description=RpcNode per-node system-agent (tron/%s)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
KillMode=control-group
EnvironmentFile=-%s
%sEnvironment=TRON_NETWORK=tron
Environment=TRON_SYSTEM_AGENT_LISTEN=127.0.0.1:%d
Environment=TRON_NODE_HTTP_HOST=127.0.0.1
Environment=TRON_NODE_HTTP_PORT=%d
Environment=TRON_STATE_DIR=%s
Environment=TOOLKIT_DIR=%s
ExecStart=%s/rpcnode-system-agent
Restart=always
RestartSec=2
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
`, req.Env, envPath, productSystemdSysListenEnv(req.Env, req.PublicPort, req.AgentPort), sysListen,
		req.NodeHTTPPort,
		state, toolkitDir, binDir)

	// Canonical: rpcnode-*-agent-tron-<env> (same pattern as bitcoin/solana).
	// ❌ Do not create legacy env-only rpcnode-api-agent-<env>.
	apiUnitPath := fmt.Sprintf("/etc/systemd/system/rpcnode-api-agent-tron-%s.service", req.Env)
	sysUnitPath := fmt.Sprintf("/etc/systemd/system/rpcnode-system-agent-tron-%s.service", req.Env)
	if err := os.WriteFile(apiUnitPath, []byte(apiUnit), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(sysUnitPath, []byte(sysUnit), 0o644); err != nil {
		return nil, err
	}

	// java-tron unit: real ExecStart when jar+config present; otherwise explicit stub (never silent ok).
	nodeUnitPath := fmt.Sprintf("/etc/systemd/system/tron-%s.service", req.Env)
	if _, err := ensureTronNodeUnit(req.Env, req.NodeHTTPPort, req.P2PPort); err != nil {
		if _, st := os.Stat(nodeUnitPath); os.IsNotExist(st) {
			nodeStub := fmt.Sprintf(`[Unit]
Description=TRON FullNode (%s) — stub (jar/config/Java8 missing: %s)
After=network-online.target

[Service]
Type=simple
EnvironmentFile=-%s
WorkingDirectory=%s
ExecStart=/bin/false
Restart=no

[Install]
WantedBy=multi-user.target
`, req.Env, err.Error(), envPath, opt)
			_ = os.WriteFile(nodeUnitPath, []byte(nodeStub), 0o644)
		}
	}

	// Control URL is the dedicated agent API port (not the public RPC port).
	agentURL := resolvePublicAgentURL(req.AgentPort)

	// Persist agreed ports for reinstall / register.txt / panel Server URL.
	persistProvisionedPorts(req, agentURL)

	return map[string]any{
		"ok":             true,
		"network":        req.Network,
		"env":            req.Env,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"rpc_mode":       "go_proxy",
		"agent_url":      agentURL,
		"etc_dir":        etc,
		"data_dir":       data,
		"units_started":  false,
		"status":         "provisioned",
		"message":        "env dirs + api-agent-tron-" + req.Env + " written; unit activation scheduled after response",
		"register_file":  "/etc/rpcnode/register.txt",
		"ports_file":     filepath.Join("/etc/rpcnode/nodes", "tron-"+req.Env+".json"),
	}, nil
}

// activateProvisionedUnits enables/restarts per-env TRON agents.
// Must run only AFTER the provision HTTP response is flushed — otherwise
// restarting the env unit that is serving this request closes the client with EOF.
//
// Multi-network: leave host Server agent units (rpcnode-*-agent.service without
// env suffix) running — same as Bitcoin provision. Orphan cleanup must keep
// both the new env MainPID and the host Server agent PIDs.
func activateProvisionedUnits(req nodeProvisionRequest) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}

	env := normalizeEnv(req.Env)
	sysUnitName := fmt.Sprintf("rpcnode-system-agent-tron-%s.service", env)
	apiUnitName := fmt.Sprintf("rpcnode-api-agent-tron-%s.service", env)

	_ = exec.Command("systemctl", "daemon-reload").Run()
	// Drop legacy env-only units if present (rpcnode-api-agent-mainnet → tron-mainnet).
	retireLegacyTronEnvOnlyAgentUnits(env)
	_ = exec.Command("systemctl", "enable", sysUnitName).Run()
	_ = exec.Command("systemctl", "enable", apiUnitName).Run()

	_ = exec.Command("systemctl", "restart", sysUnitName).Run()
	apiErr := exec.Command("systemctl", "restart", apiUnitName).Run()

	keepSys := waitUnitMainPID(sysUnitName, 5*time.Second)
	keepAPI := waitUnitMainPID(apiUnitName, 5*time.Second)
	hostSys := waitUnitMainPID("rpcnode-system-agent.service", 500*time.Millisecond)
	hostAPI := waitUnitMainPID("rpcnode-api-agent.service", 500*time.Millisecond)
	killAgentOrphansExcept("rpcnode-system-agent", keepSys, hostSys)
	killAgentOrphansExcept("rpcnode-api-agent", keepAPI, hostAPI)
	// Legacy binary names (pre-rename installs / compat symlinks).
	killAgentOrphansExcept("tron-system-agent", keepSys, hostSys)
	killAgentOrphansExcept("tron-api-agent", keepAPI, hostAPI)

	return apiErr
}

// retireLegacyTronEnvOnlyAgentUnits stops/disables/removes pre-network-slug units
// (rpcnode-*-agent-<env>.service). Canonical is rpcnode-*-agent-tron-<env>.
func retireLegacyTronEnvOnlyAgentUnits(env string) {
	env = normalizeEnv(env)
	if env == "" {
		return
	}
	for _, u := range []string{
		fmt.Sprintf("rpcnode-api-agent-%s.service", env),
		fmt.Sprintf("rpcnode-system-agent-%s.service", env),
	} {
		_ = exec.Command("systemctl", "disable", "--now", u).Run()
		_ = exec.Command("systemctl", "stop", u).Run()
		_ = os.Remove("/etc/systemd/system/" + u)
		_ = os.RemoveAll("/etc/systemd/system/" + u + ".d")
	}
	_ = exec.Command("systemctl", "daemon-reload").Run()
}

// healRetireOrphanLegacyTronEnvOnlyUnits — Update/heal: drop env-only tron leaf
// units when canon exists or the node is already gone (no instances.d).
func healRetireOrphanLegacyTronEnvOnlyUnits() string {
	retired := []string{}
	for _, env := range []string{"mainnet", "nile", "shasta"} {
		legacyAPI := fmt.Sprintf("/etc/systemd/system/rpcnode-api-agent-%s.service", env)
		if _, err := os.Stat(legacyAPI); err != nil {
			continue
		}
		canonAPI := fmt.Sprintf("/etc/systemd/system/rpcnode-api-agent-tron-%s.service", env)
		inst := filepath.Join("/etc/rpcnode/instances.d", "tron-"+env+".json")
		_, canonOK := os.Stat(canonAPI)
		_, instOK := os.Stat(inst)
		if canonOK == nil || instOK != nil {
			retireLegacyTronEnvOnlyAgentUnits(env)
			retired = append(retired, env)
		}
	}
	if len(retired) == 0 {
		return ""
	}
	return "retired legacy tron env-only agent units: " + strings.Join(retired, ",")
}

func waitUnitMainPID(unit string, timeout time.Duration) string {
	deadline := time.Now().Add(timeout)
	for {
		pid := strings.TrimSpace(string(mustCmdOut("systemctl", "show", "-p", "MainPID", "--value", unit)))
		if pid != "" && pid != "0" {
			return pid
		}
		if time.Now().After(deadline) {
			return pid
		}
		time.Sleep(100 * time.Millisecond)
	}
}

func rewritePort(base string, port int) string {
	base = strings.TrimRight(base, "/")
	// strip existing :port
	if i := strings.LastIndex(base, ":"); i > 7 {
		base = base[:i]
	}
	return fmt.Sprintf("%s:%d", base, port)
}

func mustCmdOut(name string, args ...string) []byte {
	out, _ := exec.Command(name, args...).CombinedOutput()
	return out
}

// killAgentOrphans terminates processes matching bin path that are not keepPID (systemd MainPID).
// Uses pgrep -f because Linux truncates /proc comm to 15 chars (rpcnode-system-).
// Never kills the current process — provision/update may still be finishing.
func killAgentOrphans(bin, keepPID string) {
	killAgentOrphansExcept(bin, keepPID)
}

// killAgentOrphansExcept is like killAgentOrphans but keeps every non-empty PID in keep.
func killAgentOrphansExcept(bin string, keep ...string) {
	self := strconv.Itoa(os.Getpid())
	keepSet := map[string]struct{}{self: {}}
	for _, p := range keep {
		p = strings.TrimSpace(p)
		if p != "" && p != "0" {
			keepSet[p] = struct{}{}
		}
	}
	pat := "/" + bin + "( |$)"
	out, err := exec.Command("pgrep", "-f", pat).CombinedOutput()
	if err != nil {
		return
	}
	for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") {
		pid := strings.TrimSpace(line)
		if pid == "" || pid == "0" {
			continue
		}
		if _, ok := keepSet[pid]; ok {
			continue
		}
		_ = exec.Command("kill", pid).Run()
		time.Sleep(200 * time.Millisecond)
		_ = exec.Command("kill", "-9", pid).Run()
	}
}
