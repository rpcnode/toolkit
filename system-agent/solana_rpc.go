package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

type solanaRPCResult struct {
	OK          bool
	Healthy     bool
	Slot        int64
	BlockHeight int64 // getBlockHeight — confirmed blocks (≠ slot; skips omitted)
	Version     string
	Behind      string // getHealth error message when behind
	RawError    string
	Peers       int // getClusterNodes length; -1 if unknown
}

func solanaRPCURL(cfg Config) string {
	host := cfg.UpstreamHost
	if host == "" {
		host = "127.0.0.1"
	}
	port := cfg.UpstreamPort
	if port <= 0 {
		port = LookupNetworkProfile(cfg.Network, cfg.Env).DefaultNodeHTTP
	}
	if port <= 0 {
		port = 8899
	}

	return fmt.Sprintf("http://%s:%d", host, port)
}

func solanaJSONRPC(cfg Config, method string, params any) (json.RawMessage, error) {
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
	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Post(solanaRPCURL(cfg), "application/json", bytes.NewReader(raw))
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
			Code    int    `json:"code"`
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

func probeSolanaRPC(cfg Config) solanaRPCResult {
	out := solanaRPCResult{Peers: -1}
	slotRaw, err := solanaJSONRPC(cfg, "getSlot", nil)
	if err != nil {
		out.RawError = err.Error()
		return out
	}
	out.OK = true
	_ = json.Unmarshal(slotRaw, &out.Slot)

	if bhRaw, err := solanaJSONRPC(cfg, "getBlockHeight", nil); err == nil {
		_ = json.Unmarshal(bhRaw, &out.BlockHeight)
	}

	healthRaw, err := solanaJSONRPC(cfg, "getHealth", nil)
	if err != nil {
		out.Healthy = false
		out.Behind = err.Error()
	} else {
		var health string
		_ = json.Unmarshal(healthRaw, &health)
		out.Healthy = health == "ok"
		if !out.Healthy && health != "" {
			out.Behind = health
		}
	}

	if verRaw, err := solanaJSONRPC(cfg, "getVersion", nil); err == nil {
		var ver map[string]any
		if json.Unmarshal(verRaw, &ver) == nil {
			if v, ok := ver["solana-core"].(string); ok {
				out.Version = v
			}
		}
	}

	if peers, ok := solanaClusterPeerCount(cfg); ok {
		out.Peers = peers
	}

	return out
}

// solanaClusterPeerCount — best-effort gossip peer count for panel Sync card.
// Mainnet getClusterNodes ≈ 1–2 MiB / ~1s; keep headroom so collect does not drop peers.
func solanaClusterPeerCount(cfg Config) (int, bool) {
	body, _ := json.Marshal(map[string]any{
		"jsonrpc": "2.0", "id": 1, "method": "getClusterNodes", "params": []any{},
	})
	client := &http.Client{Timeout: 4 * time.Second}
	resp, err := client.Post(solanaRPCURL(cfg), "application/json", bytes.NewReader(body))
	if err != nil {
		return 0, false
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil {
		return 0, false
	}
	var envelope struct {
		Result []json.RawMessage `json:"result"`
		Error  *struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(b, &envelope); err != nil || envelope.Error != nil {
		return 0, false
	}

	return len(envelope.Result), true
}
