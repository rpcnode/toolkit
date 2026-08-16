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
	hostLogMu sync.Mutex
	secretKV  = regexp.MustCompile(`(?i)(token|password|secret|bearer|agent_key|api_key|htpasswd)[=:\s]+\S+`)
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

func hostLogNoisy(level, action, msg string) bool {
	_ = level
	a := strings.ToLower(strings.TrimSpace(action))
	m := strings.ToLower(strings.TrimSpace(msg))
	return a == "start" && strings.Contains(m, "version=") && strings.Contains(m, "log=")
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

// hostLog writes one audit line. Discrete ops only — not process boot (Restart=always).
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

