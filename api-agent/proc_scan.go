package main

import (
	"os"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// killProcsMatching — /proc scan + kill. Avoids shell `pgrep … hl-node` which
// Hyperliquid treats as a second instance (process-name singleton panic).
func killProcsMatching(anyOf []string, also ...string) {
	self := os.Getpid()
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return
	}
	var pids []int
	for _, e := range entries {
		pid, err := strconv.Atoi(e.Name())
		if err != nil || pid <= 1 || pid == self {
			continue
		}
		raw, err := os.ReadFile("/proc/" + e.Name() + "/cmdline")
		if err != nil || len(raw) == 0 {
			continue
		}
		cmd := strings.ReplaceAll(string(raw), "\x00", " ")
		hitAny := false
		for _, a := range anyOf {
			if a != "" && strings.Contains(cmd, a) {
				hitAny = true
				break
			}
		}
		if !hitAny {
			continue
		}
		okAlso := true
		for _, a := range also {
			if a == "" {
				continue
			}
			if !strings.Contains(cmd, a) {
				okAlso = false
				break
			}
		}
		if okAlso {
			pids = append(pids, pid)
		}
	}
	for _, pid := range pids {
		_ = syscall.Kill(pid, syscall.SIGTERM)
	}
	time.Sleep(200 * time.Millisecond)
	for _, pid := range pids {
		_ = syscall.Kill(pid, syscall.SIGKILL)
	}
}
