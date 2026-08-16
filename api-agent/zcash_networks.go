package main

import "strings"

func zcashSysListen(env string) int {
	switch normalizeEnv(env) {
	case "testnet":
		return 8691
	default:
		return 8690
	}
}

func networkIsZcash(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "zcash")
}
