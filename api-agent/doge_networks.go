package main

import "strings"

func dogeSysListen(env string) int {
	switch normalizeEnv(env) {
	case "testnet":
		return 8611
	default:
		return 8610
	}
}

func networkIsDoge(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "doge")
}
