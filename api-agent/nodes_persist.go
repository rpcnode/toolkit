package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// After Confirm ports / provision — persist agreed ports so reinstall and
// register.txt point at the live Node Agent API, not the bootstrap listen.
func persistProvisionedPorts(req nodeProvisionRequest, agentURL string) {
	_ = os.MkdirAll("/etc/rpcnode/nodes", 0o755)

	doc := map[string]any{
		"network":        req.Network,
		"env":            req.Env,
		"public_port":    req.PublicPort,
		"agent_port":     req.AgentPort,
		"node_http_port": req.NodeHTTPPort,
		"p2p_port":       req.P2PPort,
		"agent_url":      agentURL,
		"rpc_mode":       "go_proxy",
		"updated_at":     time.Now().UTC().Format(time.RFC3339),
	}
	if raw, err := json.MarshalIndent(doc, "", "  "); err == nil {
		net := normalizeNetwork(req.Network)
		if net == "" {
			net = "tron"
		}
		name := net + "-" + req.Env + ".json"
		path := filepath.Join("/etc/rpcnode/nodes", name)
		_ = os.WriteFile(path, append(raw, '\n'), 0o644)
		// Legacy tron env-only path (mainnet.json) for older scripts — same content.
		if net == "tron" {
			_ = os.WriteFile(filepath.Join("/etc/rpcnode/nodes", req.Env+".json"), append(raw, '\n'), 0o644)
		}
	}

	// Tip control-plane files (/etc/rpcnode/agent.port, register.txt Agent URL) are
	// intentionally NOT updated here. Writing leaf agent_port stole the tip bind
	// (e.g. :40990 stellar / :41390 dash) and left panel Servers offline after Update.
}

// shouldUpdateHostRegister — deprecated: leaf provision must not touch tip register.
func shouldUpdateHostRegister(req nodeProvisionRequest) bool {
	return hostRegisterDecision(req.Env, req.AgentPort, readStoredAgentPort(), 0)
}

// hostRegisterDecision — always false. Tip agent.port / Servers URL are control-plane only.
func hostRegisterDecision(env string, agentPort, storedPort, mainnetNodePort int) bool {
	_, _, _, _ = env, agentPort, storedPort, mainnetNodePort
	return false
}

func readStoredAgentPort() int {
	b, err := os.ReadFile("/etc/rpcnode/agent.port")
	if err != nil {
		return 0
	}
	n, _ := strconv.Atoi(strings.TrimSpace(string(b)))
	if n < 1024 || n > 65535 {
		return 0
	}
	return n
}

// Dedicated host tip listen — must not equal any leaf public/agent catalog port.
// Historical default 39090 is TRON mainnet Go RPC and collides on Install.
const defaultHostTipPort = 38990

// healHostTipAgentPortFile — if Confirm ports previously stole tip agent.port onto a
// leaf Agent API port, restore the control-plane tip port and rewrite register.txt.
func healHostTipAgentPortFile() string {
	cur := readStoredAgentPort()
	if cur <= 0 || !isCanonicalPerNodeAgentPort(cur) {
		return ""
	}
	tip := tipPortFromUnitFile()
	if tip <= 0 || isCanonicalPerNodeAgentPort(tip) {
		tip = defaultHostTipPort
	}
	_ = os.MkdirAll("/etc/rpcnode", 0o755)
	if err := os.WriteFile("/etc/rpcnode/agent.port", []byte(strconv.Itoa(tip)+"\n"), 0o644); err != nil {
		return fmt.Sprintf("tip agent.port heal failed: %v", err)
	}
	rewriteTipRegisterTxt(tip)
	return fmt.Sprintf("healed tip agent.port %d → %d (was leaf Agent API)", cur, tip)
}

func tipPortFromUnitFile() int {
	b, err := os.ReadFile("/etc/systemd/system/rpcnode-api-agent.service")
	if err != nil {
		return 0
	}
	for _, key := range []string{
		"Environment=RPCNODE_PUBLIC_PORT=",
		"Environment=TRON_PUBLIC_PORT=",
	} {
		for _, line := range strings.Split(string(b), "\n") {
			line = strings.TrimSpace(line)
			if !strings.HasPrefix(line, key) {
				continue
			}
			n, _ := strconv.Atoi(strings.TrimSpace(strings.TrimPrefix(line, key)))
			if n >= 1024 && n <= 65535 && !isCanonicalPerNodeAgentPort(n) {
				return n
			}
		}
	}
	return 0
}

func rewriteTipRegisterTxt(tipPort int) {
	if tipPort <= 0 {
		return
	}
	token := ""
	if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
		token = strings.TrimSpace(string(b))
	}
	if token == "" {
		token = strings.TrimSpace(envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", "")))
	}
	host := hostFromRegisterTxt()
	if host == "" {
		host = detectPublicIPv4()
	}
	if host == "" {
		host = "<this-host-ip>"
	}
	body := fmt.Sprintf(`RpcNode host agent — paste into panel (Servers → Add server)

  Agent URL : http://%s:%d
  Agent key : %s
  Agent port: %d (host tip · control plane)
  Go RPC    : tip has no chain upstream — per-node leaves own public ports

Server agent (host): rpcnode-api-agent / rpcnode-system-agent
Token file: /etc/rpcnode/agent.token
Port file:  /etc/rpcnode/agent.port  (host tip — never a leaf Agent API)
Nodes:      /etc/rpcnode/nodes/*.json
`, host, tipPort, token, tipPort)
	_ = os.WriteFile("/etc/rpcnode/register.txt", []byte(body), 0o600)
}

func agentPortFromNodeFile(name string) int {
	b, err := os.ReadFile(filepath.Join("/etc/rpcnode/nodes", name))
	if err != nil {
		return 0
	}
	var doc map[string]any
	if json.Unmarshal(b, &doc) != nil {
		return 0
	}
	switch v := doc["agent_port"].(type) {
	case float64:
		return int(v)
	case int:
		return v
	case json.Number:
		n, _ := v.Int64()
		return int(n)
	case string:
		n, _ := strconv.Atoi(strings.TrimSpace(v))
		return n
	default:
		return 0
	}
}

func resolvePublicAgentURL(agentPort int) string {
	if agentPort <= 0 {
		return ""
	}

	if pb := strings.TrimRight(envFirst("", "RPCNODE_PUBLIC_BASE", "TRON_PUBLIC_BASE", "PUBLIC_BASE"), "/"); pb != "" {
		if u := rewritePort(pb, agentPort); u != "" {
			return u
		}
	}

	if host := hostFromRegisterTxt(); host != "" {
		return fmt.Sprintf("http://%s:%d", host, agentPort)
	}

	if ip := detectPublicIPv4(); ip != "" {
		return fmt.Sprintf("http://%s:%d", ip, agentPort)
	}

	return fmt.Sprintf("http://127.0.0.1:%d", agentPort)
}

func rewriteRegisterTxt(agentURL string, req nodeProvisionRequest) {
	token := ""
	if b, err := os.ReadFile("/etc/rpcnode/agent.token"); err == nil {
		token = strings.TrimSpace(string(b))
	}
	if token == "" {
		token = strings.TrimSpace(envOr("AGENT_API_TOKEN", envOr("TRON_API_TOKEN", "")))
	}

	net := normalizeNetwork(req.Network)
	if net == "" {
		net = "tron"
	}
	unitSuffix := net + "-" + req.Env
	nodeFile := unitSuffix + ".json"
	body := fmt.Sprintf(`RpcNode per-node agent (not Servers tip URL)

  Leaf Agent URL : %s
  Agent key      : %s
  Agent port     : %d (per-node · %s/%s)
  Go RPC         : :%d  (clients → proxy → upstream)
  Upstream       : 127.0.0.1:%d  (internal)
  P2P            : :%d

Per-node units: rpcnode-api-agent-%s / rpcnode-system-agent-%s
Servers tip:    rpcnode-api-agent / rpcnode-system-agent  (/etc/rpcnode/agent.port)
Token file:     /etc/rpcnode/agent.token
Node file:      /etc/rpcnode/nodes/%s
`,
		agentURL, token, req.AgentPort, net, req.Env,
		req.PublicPort, req.NodeHTTPPort, req.P2PPort,
		unitSuffix, unitSuffix, nodeFile,
	)

	_ = os.WriteFile("/etc/rpcnode/register.txt", []byte(body), 0o600)
}

var registerURLRe = regexp.MustCompile(`(?m)^\s*Agent URL\s*:\s*(https?://\S+)`)

func hostFromRegisterTxt() string {
	b, err := os.ReadFile("/etc/rpcnode/register.txt")
	if err != nil {
		return ""
	}
	m := registerURLRe.FindSubmatch(b)
	if len(m) < 2 {
		return ""
	}
	u := strings.TrimSpace(string(m[1]))
	u = strings.TrimPrefix(u, "http://")
	u = strings.TrimPrefix(u, "https://")
	if i := strings.IndexByte(u, '/'); i >= 0 {
		u = u[:i]
	}
	if i := strings.LastIndex(u, ":"); i > 0 {
		u = u[:i]
	}
	if u == "" || u == "127.0.0.1" || u == "localhost" || strings.Contains(u, "<") {
		return ""
	}

	return u
}

func detectPublicIPv4() string {
	client := &http.Client{Timeout: 2 * time.Second}
	for _, url := range []string{
		"https://ifconfig.me",
		"https://api.ipify.org",
	} {
		resp, err := client.Get(url)
		if err != nil {
			continue
		}
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 64))
		_ = resp.Body.Close()
		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			continue
		}
		ip := strings.TrimSpace(string(raw))
		if net.ParseIP(ip) != nil && strings.Contains(ip, ".") {
			return ip
		}
	}

	// Fallback: UDP dial trick → egress interface IP (often private LAN).
	c, err := net.Dial("udp", "8.8.8.8:80")
	if err != nil {
		return ""
	}
	defer c.Close()
	if addr, ok := c.LocalAddr().(*net.UDPAddr); ok && addr.IP != nil {
		ip := addr.IP.To4()
		if ip != nil && !ip.IsLoopback() {
			return ip.String()
		}
	}

	return ""
}
