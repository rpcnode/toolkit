package main

import "strings"

func cardanoSysListen(env string) int {
	switch normalizeEnv(env) {
	case "preprod":
		return 8621
	case "preview":
		return 8622
	default:
		return 8620
	}
}

func networkIsCardano(network string) bool {
	return strings.EqualFold(strings.TrimSpace(network), "cardano")
}

// cardanoBookEnv — IOG book.world.dev.cardano.org environment slug.
func cardanoBookEnv(env string) string {
	switch normalizeEnv(env) {
	case "preprod":
		return "preprod"
	case "preview":
		return "preview"
	default:
		return "mainnet"
	}
}
