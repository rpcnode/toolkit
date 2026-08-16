package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	hlAppliedBlockRe  = regexp.MustCompile(`(?i)applied\s+block\s+(\d+)`)
	hlHeightContextRe = regexp.MustCompile(`(?i)\bheight:\s*(\d{6,})`)
	hlPeerConnRe = regexp.MustCompile(`(?i)(?:connection established|connecting to peer).*?Ip\((\d+\.\d+\.\d+\.\d+)\)`)
)

// hlJournalProgress parses hl-visor / hl-node journal for bootstrap / applied block.
type hlJournalProgress struct {
	AppliedBlock      int64
	FinishedBootstrap bool
	Peers             int
	Detail            string
}

func parseHLJournalProgress(lines []string) hlJournalProgress {
	out := hlJournalProgress{Peers: -1}
	peers := map[string]struct{}{}
	for i := len(lines) - 1; i >= 0; i-- {
		ln := strings.TrimSpace(lines[i])
		if ln == "" {
			continue
		}
		l := strings.ToLower(ln)
		if strings.Contains(l, "finished bootstrap") {
			out.FinishedBootstrap = true
			if out.Detail == "" {
				out.Detail = "finished bootstrap"
			}
		}
		if m := hlAppliedBlockRe.FindStringSubmatch(ln); len(m) == 2 {
			if n, err := strconv.ParseInt(m[1], 10, 64); err == nil && n > out.AppliedBlock {
				out.AppliedBlock = n
			}
		}
		if out.AppliedBlock <= 0 {
			if m := hlHeightContextRe.FindStringSubmatch(ln); len(m) == 2 {
				if n, err := strconv.ParseInt(m[1], 10, 64); err == nil && n > out.AppliedBlock {
					out.AppliedBlock = n
				}
			}
		}
		if strings.Contains(l, "connection established") || strings.Contains(l, "connecting to peer") {
			if m := hlPeerConnRe.FindStringSubmatch(ln); len(m) == 2 {
				peers[m[1]] = struct{}{}
			}
		}
	}
	if len(peers) > 0 {
		out.Peers = len(peers)
	}
	if out.AppliedBlock > 0 {
		out.Detail = fmt.Sprintf("applied block %d", out.AppliedBlock)
		if out.FinishedBootstrap {
			out.Detail += " · finished bootstrap"
		}
	} else if out.FinishedBootstrap {
		out.Detail = "finished bootstrap · waiting for HyperEVM RPC"
	}

	return out
}

func hlJournalSnapshot(cfg Config, network string) hlJournalProgress {
	rawOut, _ := runCmd(4*time.Second, "journalctl", "-u", l2NodeUnit(cfg, network),
		"-n", "120", "--no-pager", "-o", "cat")
	raw := expandCarriageProgress(rawOut)

	return parseHLJournalProgress(raw)
}

// hlVerificationPct: explorer tip is HyperCore L1 height (~647M on testnet), NOT HyperEVM
// eth_blockNumber (~61M). Prefer L1 applied/tip; never claim 100% when L1 tip unknown
// or L1 still lagging.
func hlVerificationPct(
	rpc ethereumRPCResult,
	rpcOK bool,
	journal hlJournalProgress,
	evmTip int64,
	l1Tip int64,
) float64 {
	l1Local := journal.AppliedBlock

	if l1Local > 0 && l1Tip > 0 {
		lagging := l1Local+512 < l1Tip
		pct := ethSyncVerificationPct(l1Local, l1Tip, lagging)
		if lagging {
			return pct
		}
		// L1 near tip — require HyperEVM RPC healthy before 100%.
		if !rpcOK {
			if pct > 98 {
				return 98
			}

			return pct
		}
		if rpc.Syncing {
			if rpc.HighestBlock > 0 {
				return ethSyncVerificationPct(rpc.CurrentBlock, rpc.HighestBlock, true)
			}
			if evmTip > 0 && rpc.Block > 0 {
				return ethSyncVerificationPct(rpc.Block, evmTip, true)
			}

			return 99
		}
		if evmTip > 0 && rpc.Block > 0 && rpc.Block+128 < evmTip {
			return ethSyncVerificationPct(rpc.Block, evmTip, true)
		}

		return 100
	}

	if rpcOK {
		if rpc.Syncing {
			if rpc.HighestBlock > 0 {
				return ethSyncVerificationPct(rpc.CurrentBlock, rpc.HighestBlock, true)
			}
			if evmTip > 0 && rpc.Block > 0 {
				return ethSyncVerificationPct(rpc.Block, evmTip, true)
			}

			return 0
		}
		if evmTip > 0 && rpc.Block > 0 {
			if rpc.Block+128 < evmTip {
				return ethSyncVerificationPct(rpc.Block, evmTip, true)
			}
			// EVM looks caught up but L1 tip unknown — do not fake full sync.
			return 97
		}
		if journal.FinishedBootstrap {
			return 92
		}

		return 85
	}

	if journal.FinishedBootstrap {
		return 92
	}
	if journal.AppliedBlock > 0 {
		return 35
	}

	return 0
}

func hlPublicEVMTip(env string) int64 {
	env = strings.ToLower(strings.TrimSpace(env))
	url := "https://rpc.hyperliquid.xyz/evm"
	if env == "testnet" {
		url = "https://rpc.hyperliquid-testnet.xyz/evm"
	}
	raw, err := jsonRPCPost(url, "eth_blockNumber", nil)
	if err != nil {
		return 0
	}
	var hex string
	if json.Unmarshal(raw, &hex) != nil {
		return 0
	}
	n, err := parseHexInt64(hex)
	if err != nil {
		return 0
	}

	return n
}

type hlL1TipCacheEntry struct {
	tip int64
	at  time.Time
}

var (
	hlL1TipMu    sync.Mutex
	hlL1TipCache = map[string]hlL1TipCacheEntry{}
)

func hlExplorerBase(env string) string {
	if strings.EqualFold(strings.TrimSpace(env), "testnet") {
		return "https://rpc.hyperliquid-testnet.xyz/explorer"
	}

	return "https://rpc.hyperliquid.xyz/explorer"
}

func hlExplorerBlockExists(base string, height int64) bool {
	if height <= 0 {
		return false
	}
	body, _ := json.Marshal(map[string]any{"type": "blockDetails", "height": height})
	client := &http.Client{Timeout: 4 * time.Second}
	req, err := http.NewRequest(http.MethodPost, base, strings.NewReader(string(body)))
	if err != nil {
		return false
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	var doc map[string]any
	if json.NewDecoder(resp.Body).Decode(&doc) != nil {
		return false
	}
	_, ok := doc["blockDetails"]

	return ok
}

// hlPublicL1Tip returns HyperCore consensus height (app.hyperliquid*.xyz/explorer),
// using a short-lived cache + exponential probe from hint (local applied block).
func hlPublicL1Tip(env string, hint int64) int64 {
	env = strings.ToLower(strings.TrimSpace(env))
	base := hlExplorerBase(env)

	hlL1TipMu.Lock()
	cached := hlL1TipCache[env]
	hlL1TipMu.Unlock()
	if cached.tip > 0 && time.Since(cached.at) < 30*time.Second {
		return cached.tip
	}

	seed := hint
	if cached.tip > seed {
		seed = cached.tip
	}
	if seed <= 0 {
		// Cold start: try a few recent explorer heights via coarse probe from a
		// high floor (testnet was ~6.47e8 in Aug 2026; mainnet is higher).
		seed = 600_000_000
		if env != "testnet" {
			seed = 500_000_000
		}
	}

	if !hlExplorerBlockExists(base, seed) {
		// Hint ahead of tip — walk down.
		for step := int64(1); step <= 1_000_000; step *= 2 {
			try := seed - step
			if try <= 0 {
				break
			}
			if hlExplorerBlockExists(base, try) {
				seed = try
				break
			}
		}
	}

	// Grow upward until miss, then binary search.
	lo := seed
	step := int64(1)
	for step < 20_000_000 {
		hi := seed + step
		if hlExplorerBlockExists(base, hi) {
			lo = hi
			step *= 2
			continue
		}
		// binary search (lo, hi)
		left, right := lo, hi
		for left+1 < right {
			mid := (left + right) / 2
			if hlExplorerBlockExists(base, mid) {
				left = mid
			} else {
				right = mid
			}
		}
		lo = left
		break
	}

	if lo > 0 {
		hlL1TipMu.Lock()
		hlL1TipCache[env] = hlL1TipCacheEntry{tip: lo, at: time.Now()}
		hlL1TipMu.Unlock()
	}

	return lo
}

func hlDataDirBytes(cfg Config) int64 {
	prof := LookupNetworkProfile("hyperliquid", cfg.Env)
	path := prof.DataPath
	if path == "" {
		return 0
	}
	out, err := runCmd(6*time.Second, "du", "-sb", path)
	if err != nil {
		return 0
	}
	fields := strings.Fields(strings.TrimSpace(out))
	if len(fields) == 0 {
		return 0
	}
	n, err := strconv.ParseInt(fields[0], 10, 64)
	if err != nil || n <= 0 {
		return 0
	}

	return n
}
