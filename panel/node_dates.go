package main

import (
	"strings"

	"github.com/ali3/tron-toolkit/panel/store"
)

func shouldStampInstallStarted(prevStatus, nextStatus, already string) bool {
	if strings.TrimSpace(already) != "" {
		return false
	}
	if store.NodeStatusPreInstall(nextStatus) {
		return false
	}
	return store.NodeStatusPreInstall(prevStatus)
}

func shouldStampSynced(prevStatus, prevRaw string, nowSynced bool, already string) bool {
	if strings.TrimSpace(already) != "" || !nowSynced {
		return false
	}
	if leafHonestlySynced(decodeStatusDoc([]byte(prevRaw))) {
		return false
	}
	// Existing online node after upgrade: empty prev raw + already working → do not invent now.
	if strings.TrimSpace(prevRaw) == "" && store.NodeStatusAlreadyWorking(prevStatus) {
		return false
	}
	return true
}

// shouldClearSynced — drop a false first-synced stamp once the leaf is
// explicitly catching up (TRON genesis IBD after a tip==local lie).
func shouldClearSynced(nowSynced bool, already string, doc map[string]any) bool {
	if strings.TrimSpace(already) == "" || nowSynced {
		return false
	}
	if doc == nil {
		return false
	}
	sync, _ := doc["sync"].(map[string]any)
	if sync == nil {
		return false
	}
	return truthyAny(sync["ibd"]) || truthyAny(sync["syncing"])
}
