package main

import (
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	cmdOutTimeout   = 2 * time.Second
	listenSnapFresh = 800 * time.Millisecond
)

var (
	ssListenPortRe = regexp.MustCompile(`:(\d+)\b`)
	ssListenPIDRe  = regexp.MustCompile(`pid=(\d+)`)
)

type listenSnap struct {
	at        time.Time
	ok        bool
	byPort    map[int][]string
	listening map[int]bool
}

var (
	listenSnapMu  sync.Mutex
	listenSnapCur *listenSnap
)

func refreshListenSnap() *listenSnap {
	snap := captureListenSnap()
	listenSnapMu.Lock()
	listenSnapCur = snap
	listenSnapMu.Unlock()
	if snap == nil || !snap.ok {
		return nil
	}
	return snap
}

func currentListenSnap() *listenSnap {
	listenSnapMu.Lock()
	cur := listenSnapCur
	listenSnapMu.Unlock()
	if cur != nil && time.Since(cur.at) < listenSnapFresh {
		if !cur.ok {
			return nil
		}
		return cur
	}
	return refreshListenSnap()
}

func captureListenSnap() *listenSnap {
	snap := &listenSnap{
		at:        time.Now(),
		byPort:    map[int][]string{},
		listening: map[int]bool{},
	}
	out, err := cmdOut("ss", "-H", "-lntp")
	if len(out) == 0 && err != nil {
		return snap
	}
	snap.ok = true
	parseSsListen(string(out), snap)
	return snap
}

func parseSsListen(raw string, snap *listenSnap) {
	if snap.byPort == nil {
		snap.byPort = map[int][]string{}
	}
	if snap.listening == nil {
		snap.listening = map[int]bool{}
	}
	for _, line := range strings.Split(raw, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || !ssLineLooksListening(line) {
			continue
		}
		port := firstSsLocalPort(line)
		if port <= 0 {
			continue
		}
		snap.listening[port] = true
		for _, m := range ssListenPIDRe.FindAllStringSubmatch(line, -1) {
			if len(m) < 2 {
				continue
			}
			pid := strings.TrimSpace(m[1])
			if pid == "" || pid == "0" {
				continue
			}
			seen := false
			for _, have := range snap.byPort[port] {
				if have == pid {
					seen = true
					break
				}
			}
			if !seen {
				snap.byPort[port] = append(snap.byPort[port], pid)
			}
		}
	}
}

func firstSsLocalPort(line string) int {
	m := ssListenPortRe.FindStringSubmatch(line)
	if len(m) < 2 {
		return 0
	}
	port, err := strconv.Atoi(m[1])
	if err != nil || port <= 0 || port > 65535 {
		return 0
	}
	return port
}

func ssLineLooksListening(raw string) bool {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return false
	}
	low := strings.ToLower(raw)
	if strings.Contains(low, "not found") || strings.Contains(low, "usage:") {
		return false
	}
	if strings.Contains(low, "cannot open netlink") {
		return false
	}
	return strings.Contains(low, "listen") || ssListenPortRe.MatchString(raw)
}

func pidsFromSs(raw string) []string {
	out := []string{}
	seen := map[string]bool{}
	for _, m := range ssListenPIDRe.FindAllStringSubmatch(raw, -1) {
		if len(m) < 2 {
			continue
		}
		pid := strings.TrimSpace(m[1])
		if pid == "" || pid == "0" || seen[pid] {
			continue
		}
		seen[pid] = true
		out = append(out, pid)
	}
	return out
}

func portListenerPIDs(port int) []string {
	if port <= 0 {
		return nil
	}
	if snap := currentListenSnap(); snap != nil {
		if pids := snap.byPort[port]; len(pids) > 0 {
			return append([]string(nil), pids...)
		}
		if snap.listening[port] {
			return nil
		}
		return nil
	}
	out, err := cmdOut("ss", "-H", "-lntp", "sport = :"+strconv.Itoa(port))
	if err == nil {
		return pidsFromSs(string(out))
	}
	return nil
}

// portIsListening — a process is bound LISTEN on port.
// Do not use net.Listen: catalog public/agent ports overlap the kernel ephemeral
// range, so an outbound TCP (healthz, overlay, panel) can make Listen fail while
// ss -lntp is empty → false check-ports port_busy (arb sepolia :40094).
// Do not fall back to unbounded lsof: on a busy fullnode (Solana) that scans every
// FD and blows the panel check-ports deadline. Empty ss = free.
func portIsListening(port int) bool {
	if port <= 0 {
		return false
	}
	if snap := currentListenSnap(); snap != nil {
		return snap.listening[port] || len(snap.byPort[port]) > 0
	}
	out, err := cmdOut("ss", "-H", "-lntp", "sport = :"+strconv.Itoa(port))
	if err == nil {
		return ssLineLooksListening(string(out))
	}
	out, err = cmdOut("ss", "-H", "-ltn", "sport = :"+strconv.Itoa(port))
	if err == nil {
		return ssLineLooksListening(string(out))
	}
	return false
}
