package main

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
)

// litecoind/bitcoind: "Synchronizing blockheaders, height: 2607999 (~82.62%)"
var coreHeaderSyncPctRe = regexp.MustCompile(`(?i)Synchronizing blockheaders.*?~\s*([0-9]+(?:\.[0-9]+)?)\s*%`)

// parseCoreHeaderSyncPct — last header-sync % from daemon debug/journal lines.
func parseCoreHeaderSyncPct(lines []string) float64 {
	for i := len(lines) - 1; i >= 0; i-- {
		m := coreHeaderSyncPctRe.FindStringSubmatch(lines[i])
		if m == nil {
			continue
		}
		p, err := strconv.ParseFloat(m[1], 64)
		if err != nil {
			continue
		}
		if p < 0 {
			p = 0
		}
		if p > 100 {
			p = 100
		}
		return p
	}
	return 0
}

// coreHonestIBDPct — verificationprogress stays ~0 during hours of header sync
// (looks "dead" in UI). Prefer daemon header-sync %, then blocks/headers floor.
func coreHonestIBDPct(blocks, headers int64, verify, headerSyncPct float64) float64 {
	vp := verify * 100
	if vp < 0 {
		vp = 0
	}
	if vp > 100 {
		vp = 100
	}

	// Native progress once it moves off noise floor with real blocks.
	if vp >= 0.05 && blocks > 0 {
		return round1(vp)
	}

	if headerSyncPct > 0 && (blocks == 0 || vp < 0.05) {
		if headerSyncPct > 99.9 {
			headerSyncPct = 99.9
		}
		return round1(headerSyncPct)
	}

	if headers > 0 && blocks > 0 {
		bh := float64(blocks) / float64(headers) * 100
		if bh > 99.9 {
			bh = 99.9
		}
		if bh > vp {
			return round1(bh)
		}
	}

	return round1(vp)
}

func formatCoreSyncPct(p float64) string {
	if p > 0 && p < 1 {
		return fmt.Sprintf("%.2f%%", p)
	}
	return fmt.Sprintf("%.1f%%", p)
}

// coreLikeDebugLogPath — datadir/debug.log (Core nest may differ from profile DataPath).
func coreLikeDebugLogPath(cfg Config) string {
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		data = LookupNetworkProfile(cfg.Network, cfg.Env).DataPath
	}
	// Also try nest Core actually uses (ltc testnet → testnet4).
	if nest := coreLikeChainDataDir(cfg.Network, cfg.Env, data); nest != "" && nest != data {
		cand := nest + "/debug.log"
		if fileExists(cand) {
			return cand
		}
	}
	if data == "" {
		return ""
	}
	return data + "/debug.log"
}
