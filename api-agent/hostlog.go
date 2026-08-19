package main

import (
	"fmt"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"
)

const defaultHostLogPath = "/var/log/rpcnode.log"

var (
	hostLogMu      sync.Mutex
	proxyErrMu     sync.Mutex
	proxyErrLastAt time.Time
	secretKV       = regexp.MustCompile(`(?i)(token|password|secret|bearer|agent_key|api_key|htpasswd)[=:\s]+\S+`)
)

func hostLogFilePath() string {
	if p := strings.TrimSpace(os.Getenv("RPCNODE_HOST_LOG")); p != "" {
		return p
	}
	return defaultHostLogPath
}

func redactHostLog(s string) string {
	return secretKV.ReplaceAllString(s, "${1}=***")
}

// hostLogNoisy — Restart=always / listen chatter must not hit the shared audit file.
func hostLogNoisy(level, action, msg string) bool {
	a := strings.ToLower(strings.TrimSpace(action))
	m := strings.ToLower(strings.TrimSpace(msg))
	if a == "rpc-proxy" {
		if !strings.EqualFold(strings.TrimSpace(level), "ERROR") {
			return true
		}
		// Tip has no FullNode (upstream :0) — probes are not an audit event.
		if strings.Contains(m, "127.0.0.1:0") || strings.Contains(m, ":0 ") {
			return true
		}
	}
	if a == "start" && strings.Contains(m, "version=") && strings.Contains(m, "log=") {
		return true
	}
	if a == "java8" && strings.HasPrefix(m, "using ") {
		return true
	}
	return false
}

func appendHostLog(line string) {
	path := hostLogFilePath()
	hostLogMu.Lock()
	defer hostLogMu.Unlock()
	if err := os.MkdirAll("/var/log", 0o755); err != nil && !os.IsExist(err) {
		return
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o640)
	if err != nil {
		return
	}
	_, _ = f.WriteString(line)
	_ = f.Close()
}

// hostLog writes one audit line to /var/log/rpcnode.log.
// Only discrete ops: update / provision / node start / snapshot / Java install / download URL / errors.
// ❌ Not process boot, listen, or other Restart=always chatter (that stays in <unit>.log).
func hostLog(level, component, action, msg string) {
	if hostLogNoisy(level, action, msg) {
		return
	}
	msg = redactHostLog(strings.TrimSpace(msg))
	line := fmt.Sprintf("%s %-5s [%s] %s %s\n",
		time.Now().UTC().Format(time.RFC3339),
		strings.ToUpper(strings.TrimSpace(level)),
		strings.TrimSpace(component),
		strings.TrimSpace(action),
		msg,
	)
	appendHostLog(line)
}

func hostLogf(level, component, action, format string, args ...any) {
	hostLog(level, component, action, fmt.Sprintf(format, args...))
}

// hostLogProxyErr logs Go RPC → upstream failures at most once per 30s (not every RPC).
func hostLogProxyErr(msg string) {
	proxyErrMu.Lock()
	defer proxyErrMu.Unlock()
	if time.Since(proxyErrLastAt) < 30*time.Second {
		return
	}
	proxyErrLastAt = time.Now()
	hostLog("ERROR", "api-agent", "rpc-proxy", msg)
}
