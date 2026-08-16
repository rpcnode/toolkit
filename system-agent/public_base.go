package main

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

type publicBaseRequest struct {
	IP  string `json:"ip"`
	URL string `json:"url"`
}

func (c *ControlState) handlePublicBase(cfg Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			rpc, panel := effectivePublicBases(cfg)
			netInfo := detectHostNetInfo()
			writeJSON(w, http.StatusOK, map[string]any{
				"ok":          true,
				"public_base": rpc,
				"panel_base":  panel,
				"rpc_port":    cfg.PublicRPCPort(),
				"panel_port":  cfg.AgentAPIPort(),
				"rpc_mode":    map[bool]string{true: "go_proxy", false: "fullnode_direct"}[cfg.RPCProxyEnabled()],
				"host":        hostNetForStatus(),
				"primary_ip":  netInfo.PrimaryIP,
				"env_file":    filepath.Join(cfg.EtcDir, "toolkit.env"),
				"override":    publicBaseOverridePath(cfg),
			})
			return
		case http.MethodPost:
			var body publicBaseRequest
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil && err.Error() != "EOF" {
				writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid json: " + err.Error()})
				return
			}
			result, err := applyPublicBase(cfg, body)
			if err != nil {
				writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": err.Error()})
				return
			}
			c.RequestRefresh()
			writeJSON(w, http.StatusOK, result)
			return
		default:
			http.Error(w, "GET or POST", http.StatusMethodNotAllowed)
		}
	}
}

func applyPublicBase(cfg Config, req publicBaseRequest) (map[string]any, error) {
	rpcPort := cfg.PublicRPCPort()
	agentPort := cfg.AgentAPIPort()
	rpcBase, host, err := normalizePublicBaseInput(req, rpcPort)
	if err != nil {
		return nil, err
	}
	panelBase := swapURLPort(rpcBase, agentPort)
	if panelBase == "" {
		panelBase = fmt.Sprintf("http://%s:%d", host, agentPort)
	}

	overridePath := publicBaseOverridePath(cfg)
	_ = ensureDir(filepath.Dir(overridePath))
	ov := map[string]any{
		"public_base": rpcBase,
		"panel_base":  panelBase,
		"ip":          host,
		"rpc_port":    rpcPort,
		"panel_port":  agentPort,
		"updated_at":  time.Now().UTC().Format(time.RFC3339),
		"source":      "ops-console",
	}
	if err := writeJSONFile(overridePath, ov); err != nil {
		return nil, fmt.Errorf("write override: %w", err)
	}

	envPath := filepath.Join(cfg.EtcDir, "toolkit.env")
	envWrote, envErr := upsertToolkitEnvPublicBase(envPath, rpcBase, panelBase, rpcPort, agentPort)

	// Keep INSTANCE / registry in sync for env switcher + status.
	_ = patchInstancePublicBase(cfg, rpcBase, panelBase)

	restartCmd := fmt.Sprintf("TRON_ENV=%s rpcnodectl agents up", cfg.Env)
	note := "TRON_PUBLIC_BASE is Go RPC URL (clients → proxy → FullNode; sleep on update). Node Agent API is a separate port."
	if !cfg.RPCProxyEnabled() {
		note = "TRON_PUBLIC_PORT=0 — public base points at FullNode (no Go sleep). Prefer go_proxy."
	}
	out := map[string]any{
		"ok":           true,
		"public_base":  rpcBase,
		"panel_base":   panelBase,
		"panel_status": panelBase + "/status",
		"ip":           host,
		"rpc_port":     rpcPort,
		"panel_port":   agentPort,
		"override":     overridePath,
		"env_file":     envPath,
		"env_updated":  envWrote,
		"restart_hint": restartCmd,
		"note":         note,
	}
	if envErr != nil {
		out["env_error"] = envErr.Error()
		out["message"] = fmt.Sprintf(
			"Applied override %s. toolkit.env not writable (%v). Persist with: %s",
			overridePath, envErr, restartCmd,
		)
	} else if envWrote {
		out["message"] = fmt.Sprintf(
			"Wrote %s and override. Reload agents so compose picks env: %s",
			envPath, restartCmd,
		)
	} else {
		out["message"] = "Applied public base override (status updates immediately)."
	}

	// Copy-ready snippet for operators who prefer shell.
	out["env_snippet"] = fmt.Sprintf(
		"# in %s\nTRON_PUBLIC_BASE=%s\nTRON_PANEL_BASE=%s\nTRON_NODE_HTTP_PORT=%d\nTRON_AGENT_PORT=%d\n# then: %s",
		envPath, rpcBase, panelBase, rpcPort, agentPort, restartCmd,
	)
	return out, nil
}

func normalizePublicBaseInput(req publicBaseRequest, rpcPort int) (rpcBase, host string, err error) {
	raw := strings.TrimSpace(req.URL)
	if raw == "" {
		raw = strings.TrimSpace(req.IP)
	}
	if raw == "" {
		return "", "", fmt.Errorf("ip or url required")
	}

	// Bare IP or hostname
	if !strings.Contains(raw, "://") {
		host = strings.TrimSpace(raw)
		if strings.Contains(host, "/") {
			return "", "", fmt.Errorf("invalid host")
		}
		// host:port → strip port; RPC port always from config
		if h, _, e := net.SplitHostPort(host); e == nil {
			host = h
		} else if strings.Count(host, ":") == 1 && !strings.HasPrefix(host, "[") {
			// hostname:port without brackets
			parts := strings.SplitN(host, ":", 2)
			host = parts[0]
		}
		if err := validatePublicHost(host); err != nil {
			return "", "", err
		}
		return fmt.Sprintf("http://%s:%d", host, rpcPort), host, nil
	}

	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return "", "", fmt.Errorf("invalid url")
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return "", "", fmt.Errorf("url scheme must be http or https")
	}
	host = u.Hostname()
	if err := validatePublicHost(host); err != nil {
		return "", "", err
	}
	// Force RPC port from env config (do not accept panel port by mistake).
	return fmt.Sprintf("%s://%s:%d", u.Scheme, host, rpcPort), host, nil
}

func validatePublicHost(host string) error {
	host = strings.TrimSpace(host)
	if host == "" {
		return fmt.Errorf("empty host")
	}
	if host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0.0.0.0" {
		return fmt.Errorf("loopback host is not allowed for public base")
	}
	if ip := net.ParseIP(host); ip != nil {
		if ip.IsLoopback() || ip.IsUnspecified() || ip.IsMulticast() || ip.IsLinkLocalUnicast() {
			return fmt.Errorf("unusable IP address")
		}
		return nil
	}
	// Hostname: basic sanity
	if strings.ContainsAny(host, " \t\n\r/") {
		return fmt.Errorf("invalid hostname")
	}
	return nil
}

func upsertToolkitEnvPublicBase(path, rpcBase, panelBase string, rpcPort, panelPort int) (bool, error) {
	if path == "" {
		return false, fmt.Errorf("empty toolkit.env path")
	}
	_ = ensureDir(filepath.Dir(path))

	keys := map[string]string{
		"TRON_PUBLIC_BASE":  rpcBase,
		"PUBLIC_BASE":       rpcBase,
		"TRON_PANEL_BASE":   panelBase,
		"TRON_PUBLIC_PORT":  fmt.Sprintf("%d", rpcPort),
		"TRON_GATEWAY_PORT": fmt.Sprintf("%d", rpcPort),
		"TRON_PANEL_PORT":   fmt.Sprintf("%d", panelPort),
	}

	var content string
	if b, err := os.ReadFile(path); err == nil {
		content = string(b)
	} else if !os.IsNotExist(err) {
		return false, err
	} else {
		content = "# Created by ops console public-base apply\n"
	}

	updated := content
	for k, v := range keys {
		updated = upsertEnvLine(updated, k, v)
	}
	if updated == content {
		// Still rewrite to ensure file exists / permissions ok
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(updated), 0640); err != nil {
		return false, err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return false, err
	}
	return true, nil
}

func upsertEnvLine(content, key, value string) string {
	re := regexp.MustCompile(`(?m)^` + regexp.QuoteMeta(key) + `=.*$`)
	line := key + "=" + value
	if re.MatchString(content) {
		return re.ReplaceAllString(content, line)
	}
	if content != "" && !strings.HasSuffix(content, "\n") {
		content += "\n"
	}
	return content + line + "\n"
}

func writeJSONFile(path string, v any) error {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	b = append(b, '\n')
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func patchInstancePublicBase(cfg Config, rpcBase, panelBase string) error {
	for _, path := range []string{cfg.InstanceFile, cfg.RegistryFile} {
		if path == "" {
			continue
		}
		doc := readJSONFile(path)
		if doc == nil {
			doc = map[string]any{}
		}
		doc["public_base_url"] = rpcBase
		doc["public_base"] = rpcBase
		doc["panel_base_url"] = panelBase
		doc["status_url"] = strings.TrimRight(panelBase, "/") + "/status"
		doc["gateway_port"] = cfg.PublicRPCPort()
		doc["public_port"] = cfg.PublicRPCPort()
		doc["panel_port"] = cfg.AgentAPIPort()
		doc["updated_at"] = time.Now().UTC().Format(time.RFC3339)
		_ = ensureDir(filepath.Dir(path))
		if err := writeJSONFile(path, doc); err != nil {
			return err
		}
	}
	return nil
}
