package main

import "strings"

// toolkitVersion is embedded in the binary. Runtime must never read TOOLKIT_VERSION from disk.
// Release builds override via: -ldflags "-X main.toolkitVersion=…"
// (scripts/build-agent-binaries.sh reads the repo TOOLKIT_VERSION only at compile time).
var toolkitVersion = "0.4.3"

func agentVersion() string {
	v := strings.Trim(strings.TrimSpace(toolkitVersion), "/")
	if v == "" {
		return "0.0.0"
	}
	if rs := []rune(v); len(rs) > 30 {
		v = string(rs[:30])
	}
	return v
}
