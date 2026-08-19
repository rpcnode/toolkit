package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"
)

const (
	probeTTL          = 25 * time.Second
	probeBannerPrefix = "rpcnode-probe "
)

type probeSession struct {
	nonce     string
	listeners []net.Listener
}

var (
	probeMu      sync.Mutex
	probeCurrent *probeSession
)

func newProbeNonce() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 16)
	}
	return hex.EncodeToString(b[:])
}

func probeResponseBody(nonce string) string {
	return probeBannerPrefix + nonce + "\n"
}

func (s *Server) tipListenPort() int {
	if s != nil && s.cfg.PanelPort > 0 {
		return s.cfg.PanelPort
	}
	if s != nil && s.cfg.RPCPort > 0 {
		return s.cfg.RPCPort
	}
	return defaultHostTipPort
}

func (s *Server) handleNodesProbeListen(w http.ResponseWriter, r *http.Request) {
	var req nodePlanRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
		return
	}
	req.Network = normalizeNetwork(req.Network)
	req.Env = normalizeEnv(req.Env)
	if req.Network == "avalanche" {
		req.Env = normalizeAvalancheEnv(req.Env)
	}
	if !networkEnvSupported(req.Network, req.Env) {
		writeJSON(w, http.StatusBadRequest, unsupportedNetworkEnvPayload(req.Network, req.Env))
		return
	}
	prof := lookupPortProfile(req.Network, req.Env)
	nonce, ports := startPortProbe(catalogPortRoles(prof), s.tipListenPort())
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":      true,
		"network": req.Network,
		"env":     req.Env,
		"nonce":   nonce,
		"ports":   ports,
		"ttl_sec": int(probeTTL / time.Second),
	})
}

func (s *Server) handleNodesProbeStop(w http.ResponseWriter, r *http.Request) {
	_ = r
	stopPortProbe()
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func stopPortProbe() {
	probeMu.Lock()
	defer probeMu.Unlock()
	stopProbeLocked()
}

func stopProbeLocked() {
	if probeCurrent == nil {
		return
	}
	for _, ln := range probeCurrent.listeners {
		_ = ln.Close()
	}
	probeCurrent = nil
}

func startPortProbe(roles []catalogPortRole, tipPort int) (nonce string, ports []map[string]any) {
	refreshListenSnap()
	probeMu.Lock()
	defer probeMu.Unlock()
	stopProbeLocked()

	nonce = newProbeNonce()
	sess := &probeSession{nonce: nonce}
	if ports == nil {
		ports = []map[string]any{}
	}

	for _, r := range roles {
		if r.Port <= 0 || !r.External {
			continue
		}
		row := map[string]any{
			"port":   r.Port,
			"role":   r.Role,
			"label":  r.Label,
			"listen": "skip",
		}
		switch {
		case tipPort > 0 && r.Port == tipPort:
			row["reason"] = "tip_listen"
		case portIsListening(r.Port):
			row["reason"] = "busy"
		default:
			ln, err := net.Listen("tcp", ":"+strconv.Itoa(r.Port))
			if err != nil {
				row["reason"] = "listen_fail"
			} else {
				row["listen"] = "ok"
				sess.listeners = append(sess.listeners, ln)
				go serveProbe(ln, nonce)
			}
		}
		ports = append(ports, row)
	}

	probeCurrent = sess
	go autoStopProbe(nonce)
	return nonce, ports
}

func autoStopProbe(nonce string) {
	time.Sleep(probeTTL)
	probeMu.Lock()
	defer probeMu.Unlock()
	if probeCurrent != nil && probeCurrent.nonce == nonce {
		stopProbeLocked()
	}
}

func serveProbe(ln net.Listener, nonce string) {
	body := probeResponseBody(nonce)
	hdr := fmt.Sprintf(
		"HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s",
		len(body),
		body,
	)
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go func(conn net.Conn) {
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
			_, _ = conn.Write([]byte(hdr))
		}(c)
	}
}
