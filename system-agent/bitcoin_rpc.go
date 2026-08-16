package main

import (
	"bytes"
	"context"
	"encoding/base64"
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

type bitcoinChainInfo struct {
	OK            bool
	Blocks        int64
	Headers       int64
	IBD           bool
	Verify        float64
	Chain         string
	SizeOnDisk    int64
	Peers         int64
	ClientVersion string // getnetworkinfo.subversion (or version)
	Pruned        bool
	Error         string
}

func readBitcoinCookie(path string) (user, pass string, ok bool) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", "", false
	}
	s := strings.TrimSpace(string(b))
	if s == "" {
		return "", "", false
	}
	parts := strings.SplitN(s, ":", 2)
	if len(parts) != 2 {
		return "", "", false
	}
	return parts[0], parts[1], true
}

func bitcoinRPCAuthHeader(cfg Config) string {
	if u := envOr("BITCOIN_RPC_USER", ""); u != "" {
		p := envOr("BITCOIN_RPC_PASSWORD", "")
		raw := u + ":" + p
		return "Basic " + base64.StdEncoding.EncodeToString([]byte(raw))
	}
	cookie := envOr("TRON_COOKIE", "")
	if cookie == "" {
		data := strings.TrimSpace(cfg.DataDir)
		if data == "" {
			data = LookupNetworkProfile(cfg.Network, cfg.Env).DataPath
		}
		// DataDir is the chain directory (/data/bitcoin/regtest) — cookie at <chain>/.cookie.
		cookie = filepath.Join(data, ".cookie")
	}
	user, pass, ok := readBitcoinCookie(cookie)
	if !ok {
		return ""
	}
	return "Basic " + base64.StdEncoding.EncodeToString([]byte(user+":"+pass))
}

// bitcoinRPCRequestBody — JSON-RPC payload. Nil params → [] (Zebra rejects null → -32600).
func bitcoinRPCRequestBody(method string, params []any) ([]byte, error) {
	if params == nil {
		params = []any{}
	}
	return json.Marshal(map[string]any{
		"jsonrpc": "1.0",
		"id":      "rpcnode-system-agent",
		"method":  method,
		"params":  params,
	})
}

func bitcoinRPC(cfg Config, method string, params []any) (map[string]any, error) {
	url := fmt.Sprintf("http://%s:%d/", cfg.UpstreamHost, cfg.UpstreamPort)
	raw, err := bitcoinRPCRequestBody(method, params)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	if auth := bitcoinRPCAuthHeader(cfg); auth != "" {
		req.Header.Set("Authorization", auth)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode == http.StatusUnauthorized {
		return nil, fmt.Errorf("rpc unauthorized (cookie/rpcauth)")
	}
	var envelope struct {
		Result map[string]any `json:"result"`
		Error  any            `json:"error"`
	}
	if err := json.Unmarshal(b, &envelope); err != nil {
		return nil, fmt.Errorf("rpc decode: %w", err)
	}
	if envelope.Error != nil && fmt.Sprint(envelope.Error) != "<nil>" && fmt.Sprint(envelope.Error) != "null" {
		return nil, fmt.Errorf("rpc error: %v", envelope.Error)
	}
	if envelope.Result == nil {
		return map[string]any{}, nil
	}
	return envelope.Result, nil
}

func getBlockchainInfo(cfg Config) bitcoinChainInfo {
	res, err := bitcoinRPC(cfg, "getblockchaininfo", nil)
	if err != nil {
		return bitcoinChainInfo{OK: false, Error: err.Error()}
	}
	info := bitcoinChainInfo{OK: true}
	if v, ok := res["blocks"].(float64); ok {
		info.Blocks = int64(v)
	}
	if v, ok := res["headers"].(float64); ok {
		info.Headers = int64(v)
	}
	if v, ok := res["initialblockdownload"].(bool); ok {
		info.IBD = v
	}
	if v, ok := res["verificationprogress"].(float64); ok {
		info.Verify = v
	}
	if v, ok := res["chain"].(string); ok {
		info.Chain = v
	}
	if v, ok := res["size_on_disk"].(float64); ok {
		info.SizeOnDisk = int64(v)
	}
	if v, ok := res["pruned"].(bool); ok {
		info.Pruned = v
	}
	if !info.IBD && info.Headers > 0 && info.Blocks+1 < info.Headers {
		info.IBD = true
	}
	info.Peers = getConnectionCount(cfg)
	info.ClientVersion = bitcoinClientVersion(cfg)
	return info
}

func bitcoinClientVersion(cfg Config) string {
	res, err := bitcoinRPC(cfg, "getnetworkinfo", nil)
	if err != nil || res == nil {
		return ""
	}
	if s, ok := res["subversion"].(string); ok {
		if t := formatClientVersion(s); t != "" {
			return t
		}
	}
	switch v := res["version"].(type) {
	case float64:
		return formatClientVersion(strconv.FormatInt(int64(v), 10))
	case string:
		return formatClientVersion(v)
	default:
		return ""
	}
}

func getConnectionCount(cfg Config) int64 {
	n, err := bitcoinRPCNumber(cfg, "getconnectioncount")
	if err != nil {
		return -1
	}
	return n
}

// bitcoinRPCNumber calls a JSON-RPC method that returns a numeric result.
func bitcoinRPCNumber(cfg Config, method string) (int64, error) {
	url := fmt.Sprintf("http://%s:%d/", cfg.UpstreamHost, cfg.UpstreamPort)
	body := map[string]any{
		"jsonrpc": "1.0",
		"id":      "rpcnode-system-agent",
		"method":  method,
		"params":  []any{},
	}
	raw, err := json.Marshal(body)
	if err != nil {
		return 0, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(raw))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	if auth := bitcoinRPCAuthHeader(cfg); auth != "" {
		req.Header.Set("Authorization", auth)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return 0, err
	}
	var envelope struct {
		Result any `json:"result"`
		Error  any `json:"error"`
	}
	if err := json.Unmarshal(b, &envelope); err != nil {
		return 0, err
	}
	if envelope.Error != nil && fmt.Sprint(envelope.Error) != "<nil>" && fmt.Sprint(envelope.Error) != "null" {
		return 0, fmt.Errorf("rpc error: %v", envelope.Error)
	}
	switch v := envelope.Result.(type) {
	case float64:
		return int64(v), nil
	case json.Number:
		n, err := v.Int64()
		return n, err
	default:
		return 0, fmt.Errorf("unexpected numeric rpc result %T", envelope.Result)
	}
}

func bitcoindRunning() (bool, string) {
	// Legacy helper — prefer bitcoindRunningFor(cfg) when env/datadir known.
	out, err := runCmd(2*time.Second, "bash", "-lc", `ps -eo pid=,args= | grep -E '[b]itcoind' | head -1`)
	if err != nil || strings.TrimSpace(out) == "" {
		return false, ""
	}
	return true, strings.TrimSpace(out)
}

// bitcoindRunningFor — true only for THIS env's bitcoind (never mainnet PID on regtest).
func bitcoindRunningFor(cfg Config) (bool, string) {
	unit := strings.TrimSpace(cfg.NodeService)
	if unit != "" {
		name := unit
		if !strings.HasSuffix(name, ".service") {
			name += ".service"
		}
		out, _ := runCmd(3*time.Second, "systemctl", "show", name,
			"-p", "ActiveState", "-p", "MainPID", "--no-pager")
		state, pid := "", 0
		for _, ln := range strings.Split(out, "\n") {
			ln = strings.TrimSpace(ln)
			if k, v, ok := strings.Cut(ln, "="); ok {
				switch k {
				case "ActiveState":
					state = v
				case "MainPID":
					pid, _ = strconv.Atoi(v)
				}
			}
		}
		if (state == "active" || state == "activating") && pid > 0 {
			cmdOut, _ := runCmd(2*time.Second, "ps", "-p", strconv.Itoa(pid), "-o", "args=")
			cmd := strings.TrimSpace(cmdOut)
			if cmd != "" && strings.Contains(cmd, "bitcoind") {
				return true, cmd
			}
		}
	}

	conf := bitcoinConfPath(cfg)
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		data = LookupNetworkProfile(cfg.Network, cfg.Env).DataPath
	}
	// Match conf and/or datadir so multiple bitcoind on one host do not alias.
	var needles []string
	if conf != "" {
		needles = append(needles, conf)
	}
	if data != "" {
		needles = append(needles, data)
	}
	env := normalizeEnvName(cfg.Env)
	if env != "" && env != "mainnet" {
		needles = append(needles, env+"=.cookie", "chain="+env, env+"=1")
	}
	if len(needles) == 0 {
		return bitcoindRunning()
	}
	out, err := runCmd(2*time.Second, "bash", "-lc", `ps -eo pid=,args= | grep -E '[b]itcoind' || true`)
	if err != nil || strings.TrimSpace(out) == "" {
		return false, ""
	}
	for _, ln := range strings.Split(out, "\n") {
		ln = strings.TrimSpace(ln)
		if ln == "" {
			continue
		}
		for _, n := range needles {
			if n != "" && strings.Contains(ln, n) {
				return true, ln
			}
		}
	}
	return false, ""
}

func bitcoinDiskGateOK(cfg Config) (ok bool, freeGiB, needGiB float64, detail string) {
	prof := LookupNetworkProfile(cfg.Network, cfg.Env)
	needGiB = prof.DiskHintGiB
	if needGiB <= 0 {
		needGiB = 64
	}
	floor := needGiB * 0.2
	if floor < 5 {
		floor = 5
	}
	d := diskUsageGiB(cfg.DataDir)
	freeGiB = d
	if freeGiB >= floor {
		return true, freeGiB, needGiB, fmt.Sprintf("free %.0f GiB ≥ floor %.0f GiB (plan %.0f GiB)", freeGiB, floor, needGiB)
	}
	return false, freeGiB, needGiB, fmt.Sprintf("free %.0f GiB < floor %.0f GiB before IBD (plan %.0f GiB for %s)", freeGiB, floor, needGiB, cfg.Env)
}

func diskUsageGiB(path string) float64 {
	if path == "" {
		path = "/"
	}
	out, err := runCmd(2*time.Second, "df", "-B1", path)
	if err != nil {
		out, err = runCmd(2*time.Second, "df", "-B1", "/")
		if err != nil {
			return 0
		}
	}
	lines := strings.Split(strings.TrimSpace(out), "\n")
	if len(lines) < 2 {
		return 0
	}
	fields := strings.Fields(lines[1])
	if len(fields) < 4 {
		return 0
	}
	free, _ := strconv.ParseFloat(fields[3], 64)
	return free / (1024 * 1024 * 1024)
}
