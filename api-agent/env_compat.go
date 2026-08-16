package main

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

// envFirst returns the first non-empty environment value among keys, else def.
// Used for RPCNODE_* canonical names with legacy TRON_* fallback (one release).
func envFirst(def string, keys ...string) string {
	for _, k := range keys {
		if v := os.Getenv(k); v != "" {
			return v
		}
	}
	return def
}

// productEnvVars — toolkit.env fragment for multi-chain listen / instance env.
// Writes RPCNODE_* (canonical) and TRON_* (deprecated aliases for old scripts/units).
func productEnvVars(env string, publicPort, agentPort int) string {
	return fmt.Sprintf(`RPCNODE_ENV=%s
TRON_ENV=%s
RPCNODE_PUBLIC_PORT=%d
TRON_PUBLIC_PORT=%d
RPCNODE_GATEWAY_PORT=%d
TRON_GATEWAY_PORT=%d
RPCNODE_AGENT_PORT=%d
TRON_AGENT_PORT=%d
RPCNODE_PANEL_PORT=%d
TRON_PANEL_PORT=%d
`, env, env, publicPort, publicPort, publicPort, publicPort, agentPort, agentPort, agentPort, agentPort)
}

// productSystemdAPIListenEnv — Environment= lines for per-node / host api-agent units.
func productSystemdAPIListenEnv(env string, publicPort, agentPort int) string {
	var b strings.Builder
	fmt.Fprintf(&b, "Environment=RPCNODE_ENV=%s\n", env)
	fmt.Fprintf(&b, "Environment=TRON_ENV=%s\n", env)
	b.WriteString("Environment=RPCNODE_GATEWAY_LISTEN=0.0.0.0\n")
	b.WriteString("Environment=TRON_GATEWAY_LISTEN=0.0.0.0\n")
	b.WriteString("Environment=TRON_LISTEN=0.0.0.0\n")
	fmt.Fprintf(&b, "Environment=RPCNODE_PUBLIC_PORT=%d\n", publicPort)
	fmt.Fprintf(&b, "Environment=TRON_PUBLIC_PORT=%d\n", publicPort)
	fmt.Fprintf(&b, "Environment=RPCNODE_GATEWAY_PORT=%d\n", publicPort)
	fmt.Fprintf(&b, "Environment=TRON_GATEWAY_PORT=%d\n", publicPort)
	fmt.Fprintf(&b, "Environment=RPCNODE_AGENT_PORT=%d\n", agentPort)
	fmt.Fprintf(&b, "Environment=TRON_AGENT_PORT=%d\n", agentPort)
	fmt.Fprintf(&b, "Environment=RPCNODE_PANEL_PORT=%d\n", agentPort)
	fmt.Fprintf(&b, "Environment=TRON_PANEL_PORT=%d\n", agentPort)
	return b.String()
}

// productSystemdSysListenEnv — Environment= lines for per-node system-agent units.
func productSystemdSysListenEnv(env string, publicPort, agentPort int) string {
	var b strings.Builder
	fmt.Fprintf(&b, "Environment=RPCNODE_ENV=%s\n", env)
	fmt.Fprintf(&b, "Environment=TRON_ENV=%s\n", env)
	fmt.Fprintf(&b, "Environment=RPCNODE_PUBLIC_PORT=%d\n", publicPort)
	fmt.Fprintf(&b, "Environment=TRON_PUBLIC_PORT=%d\n", publicPort)
	fmt.Fprintf(&b, "Environment=RPCNODE_AGENT_PORT=%d\n", agentPort)
	fmt.Fprintf(&b, "Environment=TRON_AGENT_PORT=%d\n", agentPort)
	fmt.Fprintf(&b, "Environment=RPCNODE_PANEL_PORT=%d\n", agentPort)
	fmt.Fprintf(&b, "Environment=TRON_PANEL_PORT=%d\n", agentPort)
	return b.String()
}

// toolkitEnvLineInt parses KEY=int from a toolkit.env line (first matching key).
func toolkitEnvLineInt(line string, keys ...string) (int, bool) {
	line = strings.TrimSpace(line)
	for _, key := range keys {
		prefix := key + "="
		if strings.HasPrefix(line, prefix) {
			n, err := strconv.Atoi(strings.TrimSpace(strings.TrimPrefix(line, prefix)))
			return n, err == nil
		}
	}
	return 0, false
}
