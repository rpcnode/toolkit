package main

import (
	"fmt"
	"math"
)

// formatSyncPct — 3 decimals below 1% so XRPL/Stellar backfill (0.016%) is visible.
func formatSyncPct(pct float64) string {
	if pct > 0 && pct < 1 {
		return fmt.Sprintf("%.3f", pct)
	}

	return fmt.Sprintf("%.1f", pct)
}

// historyWindowCaughtUp — full-history proof for windowed ledgers (XRPL complete_ledgers,
// Stellar oldest/latest). Live tip alone is never enough.
func historyWindowCaughtUp(lo, hi, seq, genesis, tipSlack int64) bool {
	if lo <= 0 || hi <= 0 || seq <= 0 {
		return false
	}
	if genesis <= 0 {
		genesis = 1
	}
	if lo > genesis {
		return false
	}
	if tipSlack < 0 {
		tipSlack = 0
	}
	if hi+tipSlack < seq {
		return false
	}
	return true
}

// historyWindowPct — tip catch-up, then backfill toward genesis.
// 100 only when live AND the window reaches genesis. Never treat tip-health as Synced.
func historyWindowPct(live, historyOK bool, lo, hi, seq, genesis int64) float64 {
	if live && historyOK {
		return 100
	}
	if !live {
		tip := seq
		if hi > tip {
			tip = hi
		}
		if tip <= 0 || hi <= 0 {
			return 0
		}
		return ethSyncVerificationPct(hi, tip, true)
	}
	if genesis <= 0 {
		genesis = 1
	}
	if seq <= genesis || lo <= 0 {
		return 0
	}

	span := seq - genesis
	if span <= 0 {
		return 0
	}

	pct := float64(seq-lo) / float64(span) * 100
	out := math.Round(pct*1000) / 1000
	if out < 0.001 {
		return 0.001
	}

	if out >= 100 {
		return 99.9
	}

	return out
}

// coreHistoryMissing — bitcoind-family at tip with prune>0 is not a fullnode.
func coreHistoryMissing(chain bitcoinChainInfo, regtest bool) bool {
	return chain.OK && chain.Pruned && !regtest
}
