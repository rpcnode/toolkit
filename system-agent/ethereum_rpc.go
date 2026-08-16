package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type ethereumRPCResult struct {
	OK            bool
	Syncing       bool
	Block         int64
	CurrentBlock  int64   // eth_syncing.currentBlock (0 when synced / unknown)
	HighestBlock  int64   // eth_syncing.highestBlock (0 when synced / unknown)
	SyncProgress  float64 // 0..1 from current/highest; 1 when eth_syncing=false
	Peers         int64
	ChainID       string
	ClientVersion string // web3_clientVersion
	SyncDetail    string
	Error         string
	// Stages — reth/base-reth eth_syncing.stages when current/highest stay 0x0.
	Stages []ethSyncStage
}

// ethSyncStage is one pipeline checkpoint from eth_syncing.stages (reth).
type ethSyncStage struct {
	Name  string
	Block int64
}

// ethSyncVerificationPct — currentBlock/highestBlock*100 while syncing; 100 when synced.
func ethSyncVerificationPct(current, highest int64, syncing bool) float64 {
	if !syncing {
		return 100
	}
	if highest <= 0 {
		return 0
	}
	pct := float64(current) / float64(highest) * 100
	if pct < 0 {
		return 0
	}
	out := float64(int(pct*10+0.5)) / 10
	// Never report 100% while still syncing (near-tip rounding).
	if out >= 100 {
		return 99.9
	}

	return out
}

func ethereumRPCURL(cfg Config) string {
	host := cfg.UpstreamHost
	if host == "" {
		host = "127.0.0.1"
	}
	port := cfg.UpstreamPort
	if port <= 0 {
		port = LookupNetworkProfile(cfg.Network, cfg.Env).DefaultNodeHTTP
	}
	if port <= 0 {
		port = 8545
	}

	return fmt.Sprintf("http://%s:%d", host, port)
}

func ethereumJSONRPC(cfg Config, method string, params []any) (json.RawMessage, error) {
	body := map[string]any{
		"jsonrpc": "2.0",
		"id":      1,
		"method":  method,
	}
	if params != nil {
		body["params"] = params
	} else {
		body["params"] = []any{}
	}
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, ethereumRPCURL(cfg), bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	var envelope struct {
		Result json.RawMessage `json:"result"`
		Error  *struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(b, &envelope); err != nil {
		return nil, err
	}
	if envelope.Error != nil {
		return nil, fmt.Errorf("%s", envelope.Error.Message)
	}

	return envelope.Result, nil
}

func parseEthSyncStages(raw any) []ethSyncStage {
	arr, ok := raw.([]any)
	if !ok || len(arr) == 0 {
		return nil
	}
	out := make([]ethSyncStage, 0, len(arr))
	for _, item := range arr {
		m, ok := item.(map[string]any)
		if !ok {
			continue
		}
		name, _ := m["name"].(string)
		name = strings.TrimSpace(name)
		if name == "" {
			continue
		}
		var block int64
		for _, key := range []string{"block", "checkpoint"} {
			switch b := m[key].(type) {
			case string:
				if h, err := parseHexInt64(b); err == nil {
					block = h
				}
			case float64:
				block = int64(b)
			case json.Number:
				if n, err := b.Int64(); err == nil {
					block = n
				}
			}
			if block > 0 {
				break
			}
		}
		out = append(out, ethSyncStage{Name: name, Block: block})
	}
	if len(out) == 0 {
		return nil
	}

	return out
}

func probeEthereumRPC(cfg Config) ethereumRPCResult {
	out := ethereumRPCResult{Peers: -1}

	syncRaw, err := ethereumJSONRPC(cfg, "eth_syncing", nil)
	if err != nil {
		out.Error = err.Error()
		return out
	}

	var syncing any
	if err := json.Unmarshal(syncRaw, &syncing); err != nil {
		out.Error = err.Error()
		return out
	}
	out.OK = true
	switch v := syncing.(type) {
	case bool:
		out.Syncing = v
		if !v {
			out.SyncProgress = 1
		}
	case map[string]any:
		out.Syncing = true
		if cur, ok := v["currentBlock"].(string); ok {
			if h, err := parseHexInt64(cur); err == nil {
				out.Block = h
				out.CurrentBlock = h
			}
		}
		if hi, ok := v["highestBlock"].(string); ok {
			if h, err := parseHexInt64(hi); err == nil {
				out.HighestBlock = h
			}
		}
		out.Stages = parseEthSyncStages(v["stages"])
		// Nitro Orbit (arb/robinhood): eth_syncing is a custom object with blockNum /
		// executionSyncTarget / batchProcessed — not geth currentBlock/highestBlock.
		if out.HighestBlock <= 0 {
			applyNitroEthSyncing(&out, v)
		}
		if out.HighestBlock > 0 {
			out.SyncProgress = float64(out.CurrentBlock) / float64(out.HighestBlock)
			if out.SyncProgress > 1 {
				out.SyncProgress = 1
			}
			pct := ethSyncVerificationPct(out.CurrentBlock, out.HighestBlock, true)
			if out.SyncDetail == "" {
				out.SyncDetail = fmt.Sprintf("blocks %d / %d · %.1f%%", out.CurrentBlock, out.HighestBlock, pct)
			}
		} else if hi, ok := v["highestBlock"].(string); ok {
			out.SyncDetail = fmt.Sprintf("blocks %v / %s", v["currentBlock"], hi)
		}
	default:
		out.Syncing = false
		out.SyncProgress = 1
	}

	blockRaw, err := ethereumJSONRPC(cfg, "eth_blockNumber", nil)
	if err == nil {
		var hex string
		if json.Unmarshal(blockRaw, &hex) == nil {
			if h, err := parseHexInt64(hex); err == nil && h > out.Block {
				out.Block = h
			}
		}
	}

	// When nitro reports near-local sync target but is still behind the public tip,
	// prefer the tip for honest % (local executionSyncTarget can stall on blob inbox).
	if out.Syncing && isNitroOrbitNetwork(cfg.Network) {
		if tip := nitroPublicTipBlock(cfg.Network, cfg.Env); tip > out.HighestBlock {
			out.HighestBlock = tip
			if out.CurrentBlock <= 0 && out.Block > 0 {
				out.CurrentBlock = out.Block
			}
			if out.HighestBlock > 0 && out.CurrentBlock > 0 {
				pct := ethSyncVerificationPct(out.CurrentBlock, out.HighestBlock, true)
				out.SyncProgress = float64(out.CurrentBlock) / float64(out.HighestBlock)
				if out.SyncProgress > 1 {
					out.SyncProgress = 1
				}
				out.SyncDetail = fmt.Sprintf("blocks %d / %d · %.1f%%", out.CurrentBlock, out.HighestBlock, pct)
			}
		}
	}

	chainRaw, err := ethereumJSONRPC(cfg, "eth_chainId", nil)
	if err == nil {
		var hex string
		if json.Unmarshal(chainRaw, &hex) == nil {
			if id, err := parseHexInt64(hex); err == nil {
				out.ChainID = strconv.FormatInt(id, 10)
			}
		}
	}

	// Nitro often exposes `net` in --http.api but not net_peerCount (Orbit feed sync).
	// Probing it spam-logs "method does not exist" and fakes peers=0 — skip.
	if !isNitroOrbitNetwork(cfg.Network) {
		peerRaw, err := ethereumJSONRPC(cfg, "net_peerCount", nil)
		if err == nil {
			var hex string
			if json.Unmarshal(peerRaw, &hex) == nil {
				if n, err := parseHexInt64(hex); err == nil {
					out.Peers = n
				}
			}
		}
	}

	if verRaw, err := ethereumJSONRPC(cfg, "web3_clientVersion", nil); err == nil {
		var ver string
		if json.Unmarshal(verRaw, &ver) == nil {
			out.ClientVersion = formatClientVersion(ver)
		}
	}

	if out.SyncDetail == "" && out.Syncing {
		if out.Peers >= 0 {
			out.SyncDetail = fmt.Sprintf("syncing · block %d · peers %d", out.Block, out.Peers)
		} else {
			out.SyncDetail = fmt.Sprintf("syncing · block %d", out.Block)
		}
	}

	return out
}

func isNitroOrbitNetwork(network string) bool {
	n := strings.ToLower(strings.TrimSpace(network))
	return n == "arb" || n == "robinhood"
}

// applyNitroEthSyncing maps nitro's eth_syncing object into current/highest + detail.
func applyNitroEthSyncing(out *ethereumRPCResult, v map[string]any) {
	blockNum := jsonAnyInt64(v["blockNum"])
	target := jsonAnyInt64(v["executionSyncTarget"])
	if target <= 0 {
		target = jsonAnyInt64(v["maxMessageCount"])
	}
	if target <= 0 {
		target = jsonAnyInt64(v["consensusSyncTargetMsgCount"])
	}
	batchProc := jsonAnyInt64(v["batchProcessed"])
	batchSeen := jsonAnyInt64(v["batchSeen"])
	if blockNum > 0 {
		out.Block = blockNum
		out.CurrentBlock = blockNum
	}
	if target > 0 {
		out.HighestBlock = target
	}
	switch {
	case batchSeen > 0 && batchProc < batchSeen:
		// Inbox/batch catch-up stuck or in progress (often L1 blob dependent).
		pct := ethSyncVerificationPct(batchProc, batchSeen, true)
		out.SyncDetail = fmt.Sprintf("batches %d / %d · block %d · %.1f%%", batchProc, batchSeen, blockNum, pct)
		if out.HighestBlock <= out.CurrentBlock && batchSeen > batchProc {
			// Keep syncing=true; inflate highest so UI doesn't show ~100% on stalled tip.
			out.HighestBlock = out.CurrentBlock + (batchSeen - batchProc)
		}
	case out.HighestBlock > 0 && out.CurrentBlock > 0:
		pct := ethSyncVerificationPct(out.CurrentBlock, out.HighestBlock, true)
		out.SyncDetail = fmt.Sprintf("blocks %d / %d · %.1f%%", out.CurrentBlock, out.HighestBlock, pct)
	case blockNum > 0:
		out.SyncDetail = fmt.Sprintf("syncing · block %d", blockNum)
	}
}

func jsonAnyInt64(v any) int64 {
	switch t := v.(type) {
	case float64:
		return int64(t)
	case json.Number:
		n, _ := t.Int64()
		return n
	case int64:
		return t
	case int:
		return int64(t)
	case string:
		if h, err := parseHexInt64(t); err == nil {
			return h
		}
		if n, err := strconv.ParseInt(strings.TrimSpace(t), 10, 64); err == nil {
			return n
		}
	}
	return 0
}

func nitroPublicTipBlock(network, env string) int64 {
	url := nitroPublicTipRPC(network, env)
	if url == "" {
		return 0
	}
	raw, err := jsonRPCPost(url, "eth_blockNumber", nil)
	if err != nil {
		return 0
	}
	var hex string
	if json.Unmarshal(raw, &hex) != nil {
		return 0
	}
	h, err := parseHexInt64(hex)
	if err != nil {
		return 0
	}
	return h
}

func ethereumPublicTipRPC(cfg Config) string {
	if v := strings.TrimSpace(os.Getenv("ETHEREUM_PUBLIC_TIP_RPC")); v != "" {
		return v
	}

	if cfg.EtcDir != "" {
		if b, err := os.ReadFile(filepath.Join(cfg.EtcDir, "public_tip.url")); err == nil {
			if u := strings.TrimSpace(string(b)); u != "" {
				return u
			}
		}
	}

	switch normalizeEnvName(cfg.Env) {
	case "sepolia", "testnet":
		return "https://ethereum-sepolia-rpc.publicnode.com"
	default:
		return "https://ethereum-rpc.publicnode.com"
	}
}

func ethereumPublicTipBlock(cfg Config) int64 {
	url := ethereumPublicTipRPC(cfg)
	if url == "" {
		return 0
	}

	raw, err := jsonRPCPost(url, "eth_blockNumber", nil)
	if err != nil {
		return 0
	}

	var hex string
	if json.Unmarshal(raw, &hex) != nil {
		return 0
	}

	h, err := parseHexInt64(hex)
	if err != nil || h <= 0 {
		return 0
	}

	return h
}

// ethereumDisplayHeights — local block + network tip for Sync "blocks / headers".
// eth_syncing=false leaves highestBlock=0; then use public tip, else local.
func ethereumDisplayHeights(rpc ethereumRPCResult, publicTip int64) (blocks, headers int64) {
	blocks = rpc.Block
	if rpc.Syncing && rpc.CurrentBlock > 0 {
		blocks = rpc.CurrentBlock
	}

	headers = rpc.HighestBlock
	if publicTip > headers {
		headers = publicTip
	}

	if headers <= 0 {
		headers = blocks
	}

	return blocks, headers
}

func nitroPublicTipRPC(network, env string) string {
	n := strings.ToLower(strings.TrimSpace(network))
	e := strings.ToLower(strings.TrimSpace(env))
	switch n {
	case "robinhood":
		if e == "testnet" {
			if v := strings.TrimSpace(os.Getenv("RPCNODE_ROBINHOOD_TIP_RPC")); v != "" {
				return v
			}
			return "https://rpc.testnet.chain.robinhood.com"
		}
		if v := strings.TrimSpace(os.Getenv("RPCNODE_ROBINHOOD_TIP_RPC")); v != "" {
			return v
		}
		return "https://rpc.mainnet.chain.robinhood.com"
	case "arb":
		if e == "sepolia" || e == "testnet" {
			return "https://sepolia-rollup.arbitrum.io/rpc"
		}
		return "https://arb1.arbitrum.io/rpc"
	}
	return ""
}

func probeLighthouseSync(beaconPort int) (syncing bool, detail string) {
	if beaconPort <= 0 {
		return false, ""
	}
	url := fmt.Sprintf("http://127.0.0.1:%d/eth/v1/node/syncing", beaconPort)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return false, err.Error()
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return false, err.Error()
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return false, err.Error()
	}
	if resp.StatusCode != http.StatusOK {
		return false, fmt.Sprintf("beacon HTTP %d", resp.StatusCode)
	}
	var envelope struct {
		Data struct {
			IsSyncing    bool   `json:"is_syncing"`
			IsOptimistic bool   `json:"is_optimistic"`
			HeadSlot     string `json:"head_slot"`
			SyncDistance string `json:"sync_distance"`
		} `json:"data"`
	}
	if err := json.Unmarshal(b, &envelope); err != nil {
		return false, err.Error()
	}
	syncing = envelope.Data.IsSyncing || envelope.Data.IsOptimistic
	if syncing {
		detail = fmt.Sprintf("CL syncing · head %s · distance %s",
			envelope.Data.HeadSlot, envelope.Data.SyncDistance)
	}

	return syncing, detail
}

func probeLighthouseVersion(beaconPort int) string {
	if beaconPort <= 0 {
		return ""
	}
	url := fmt.Sprintf("http://127.0.0.1:%d/eth/v1/node/version", beaconPort)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return ""
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1<<16))
	if err != nil || resp.StatusCode != http.StatusOK {
		return ""
	}
	var envelope struct {
		Data struct {
			Version string `json:"version"`
		} `json:"data"`
	}
	if err := json.Unmarshal(b, &envelope); err != nil {
		return ""
	}
	return strings.TrimSpace(envelope.Data.Version)
}

func parseHexInt64(hex string) (int64, error) {
	hex = strings.TrimSpace(hex)
	hex = strings.TrimPrefix(hex, "0x")
	if hex == "" {
		return 0, nil
	}

	return strconv.ParseInt(hex, 16, 64)
}
