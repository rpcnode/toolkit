package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	probeBannerPrefix = "rpcnode-probe "
	probeDialTimeout  = 3 * time.Second
)

var workloadCheckPortsMu sync.Map // serverID → *sync.Mutex

func lockWorkloadCheckPorts(serverID string) func() {
	v, _ := workloadCheckPortsMu.LoadOrStore(serverID, &sync.Mutex{})
	mu := v.(*sync.Mutex)
	mu.Lock()
	return mu.Unlock
}

func dialHostFromAgentURL(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return ""
	}
	u, err := url.Parse(raw)
	if err != nil || u.Hostname() == "" {
		if i := strings.Index(raw, "://"); i >= 0 {
			raw = raw[i+3:]
		}
		host := strings.Split(raw, "/")[0]
		h, _, splitErr := net.SplitHostPort(host)
		if splitErr == nil {
			return h
		}
		return strings.TrimSpace(host)
	}
	return u.Hostname()
}

func (s *Server) postTipJSON(ctx context.Context, srv NodeRef, path string, payload any) (int, map[string]any, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return 0, nil, err
	}
	url := strings.TrimRight(srv.AgentURL, "/") + path
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	if srv.AgentKey != "" {
		req.Header.Set("Authorization", "Bearer "+srv.AgentKey)
		req.Header.Set("X-Api-Token", srv.AgentKey)
	}
	client := s.client
	if client == nil {
		client = &http.Client{Timeout: 15 * time.Second}
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	var doc map[string]any
	_ = json.Unmarshal(raw, &doc)
	if doc == nil {
		doc = map[string]any{}
	}
	return resp.StatusCode, doc, nil
}

func dialProbeNonce(host string, port int, nonce string, timeout time.Duration) string {
	if host == "" || port <= 0 || nonce == "" {
		return "skipped"
	}
	if timeout <= 0 {
		timeout = probeDialTimeout
	}
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(port)), timeout)
	if err != nil {
		return "filtered"
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(timeout))
	buf, _ := io.ReadAll(io.LimitReader(conn, 512))
	if bytes.Contains(buf, []byte(probeBannerPrefix+nonce)) {
		return "reachable"
	}
	return "filtered"
}

func markCheckedReach(checked []any, status, reason string) {
	for _, raw := range checked {
		m, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		if ext, _ := m["external"].(bool); !ext {
			m["reach"] = "n/a"
			continue
		}
		m["reach"] = status
		if reason != "" {
			m["reach_reason"] = reason
		}
	}
}

func applyReachToChecked(checked []any, byPort map[int]string, reasons map[int]string) {
	for _, raw := range checked {
		m, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		port := intFromAny(m["port"])
		if ext, _ := m["external"].(bool); !ext {
			m["reach"] = "n/a"
			continue
		}
		if st, ok := byPort[port]; ok && st != "" {
			m["reach"] = st
		} else {
			m["reach"] = "skipped"
		}
		if reason := reasons[port]; reason != "" {
			m["reach_reason"] = reason
		}
	}
}

func reachSummary(host string, checked []any, probed bool, fallback string) map[string]any {
	var filtered, reachable, skipped []map[string]any
	for _, raw := range checked {
		m, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		if ext, _ := m["external"].(bool); !ext {
			continue
		}
		row := map[string]any{"port": m["port"], "role": m["role"], "label": m["label"]}
		switch fmt.Sprint(m["reach"]) {
		case "reachable":
			reachable = append(reachable, row)
		case "filtered":
			filtered = append(filtered, row)
		default:
			skipped = append(skipped, row)
		}
	}
	if filtered == nil {
		filtered = []map[string]any{}
	}
	if reachable == nil {
		reachable = []map[string]any{}
	}
	if skipped == nil {
		skipped = []map[string]any{}
	}
	msg := fallback
	if msg == "" {
		if !probed {
			msg = "tip agent is too old for an outbound reach probe — Update the Server agent"
		} else if len(filtered) == 0 {
			msg = fmt.Sprintf("panel reached public/agent/p2p on %s", host)
		} else {
			parts := make([]string, 0, len(filtered))
			for _, b := range filtered {
				parts = append(parts, fmt.Sprintf("%v :%v", b["role"], b["port"]))
			}
			msg = fmt.Sprintf(
				"panel cannot reach %s from outside (%s) — open the cloud security group / host firewall",
				host,
				strings.Join(parts, ", "),
			)
		}
	}
	return map[string]any{
		"probed":    probed,
		"host":      host,
		"open_ok":   len(filtered) == 0,
		"filtered":  filtered,
		"reachable": reachable,
		"skipped":   skipped,
		"message":   msg,
	}
}

func (s *Server) attachOutboundReach(ctx context.Context, srv NodeRef, network, env string, agent map[string]any) {
	if agent == nil {
		return
	}
	checked, _ := agent["checked_ports"].([]any)
	host := dialHostFromAgentURL(srv.AgentURL)
	if host == "" {
		markCheckedReach(checked, "skipped", "no_host")
		agent["reach"] = reachSummary(host, checked, false, "no public host on Server agent_url")
		return
	}

	status, probe, err := s.postTipJSON(ctx, srv, "/api/v1/nodes/probe-listen", map[string]any{
		"network": network, "env": env,
	})
	if err != nil || status == http.StatusNotFound || !truthy(probe["ok"]) {
		markCheckedReach(checked, "skipped", "unsupported")
		agent["reach"] = reachSummary(host, checked, false, "")
		return
	}
	defer func() {
		stopCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _, _ = s.postTipJSON(stopCtx, srv, "/api/v1/nodes/probe-stop", map[string]any{})
	}()

	nonce, _ := probe["nonce"].(string)
	listenByPort := map[int]map[string]any{}
	if raw, ok := probe["ports"].([]any); ok {
		for _, p := range raw {
			m, ok := p.(map[string]any)
			if !ok {
				continue
			}
			if port := intFromAny(m["port"]); port > 0 {
				listenByPort[port] = m
			}
		}
	}

	byPort := map[int]string{}
	reasons := map[int]string{}
	var (
		wg sync.WaitGroup
		mu sync.Mutex
	)
	for _, raw := range checked {
		m, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		if ext, _ := m["external"].(bool); !ext {
			continue
		}
		port := intFromAny(m["port"])
		if port <= 0 {
			continue
		}
		if bind, _ := m["bind"].(string); bind == "busy" {
			byPort[port] = "skipped"
			reasons[port] = "busy"
			continue
		}
		info := listenByPort[port]
		listen, _ := info["listen"].(string)
		if listen != "ok" {
			byPort[port] = "skipped"
			if info != nil {
				if reason, _ := info["reason"].(string); reason != "" {
					reasons[port] = reason
				}
			} else {
				reasons[port] = "not_probed"
			}
			continue
		}
		wg.Add(1)
		go func(port int) {
			defer wg.Done()
			st := dialProbeNonce(host, port, nonce, probeDialTimeout)
			mu.Lock()
			byPort[port] = st
			mu.Unlock()
		}(port)
	}
	wg.Wait()
	applyReachToChecked(checked, byPort, reasons)
	agent["reach"] = reachSummary(host, checked, true, "")
}
