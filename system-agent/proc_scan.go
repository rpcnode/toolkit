package main

import (
	"os"
	"strconv"
	"strings"
)

// procCmdlineHas — scan /proc without shell. Critical for Hyperliquid: any
// `bash -lc '…hl-node…'` / `pgrep … hl-node` appears in /proc and trips the
// client's process-name singleton panic (net_utils child/system.rs).
func procCmdlineHas(substrings ...string) (cmdline string, ok bool) {
	self := os.Getpid()
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return "", false
	}
	for _, e := range entries {
		pid, err := strconv.Atoi(e.Name())
		if err != nil || pid <= 1 || pid == self {
			continue
		}
		raw, err := os.ReadFile("/proc/" + e.Name() + "/cmdline")
		if err != nil || len(raw) == 0 {
			continue
		}
		cmd := strings.TrimSpace(strings.ReplaceAll(string(raw), "\x00", " "))
		if cmd == "" {
			continue
		}
		all := true
		for _, s := range substrings {
			s = strings.TrimSpace(s)
			if s == "" {
				continue
			}
			if !strings.Contains(cmd, s) {
				all = false
				break
			}
		}
		if all {
			return cmd, true
		}
	}

	return "", false
}

func procCmdlineHasAny(anyOf []string, also ...string) (cmdline string, ok bool) {
	for _, one := range anyOf {
		args := append([]string{one}, also...)
		if cmd, hit := procCmdlineHas(args...); hit {
			return cmd, true
		}
	}

	return "", false
}
