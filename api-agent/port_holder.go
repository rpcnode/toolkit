package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"strings"
	"syscall"
	"time"
)

type portHolderInfo struct {
	Port        int    `json:"port"`
	Role        string `json:"role,omitempty"`
	Label       string `json:"label,omitempty"`
	Listening   bool   `json:"listening"`
	Holder      string `json:"holder,omitempty"`
	PID         string `json:"pid,omitempty"`
	Comm        string `json:"comm,omitempty"`
	Cmdline     string `json:"cmdline,omitempty"`
	Unit        string `json:"unit,omitempty"`
	Killable    bool   `json:"killable"`
	KillBlocked string `json:"kill_blocked,omitempty"`
}

func catalogRoleForPort(network, env string, port int) (catalogPortRole, bool) {
	if port <= 0 {
		return catalogPortRole{}, false
	}
	for _, r := range catalogPortRoles(lookupPortProfile(network, env)) {
		if r.Port == port {
			return r, true
		}
	}
	return catalogPortRole{}, false
}

func inspectPortHolder(port int, network, env string) portHolderInfo {
	info := portHolderInfo{Port: port}
	if role, ok := catalogRoleForPort(network, env, port); ok {
		info.Role = role.Role
		info.Label = role.Label
	}
	if port <= 0 || !portIsListening(port) {
		return info
	}
	info.Listening = true
	info.Holder = portBusyHolder(port, network, env)
	pids := portListenerPIDs(port)
	if len(pids) > 0 {
		info.PID = pids[0]
		info.Comm = pidComm(info.PID)
		info.Cmdline = pidCmdline(info.PID)
		info.Unit = pidSystemdUnit(info.PID)
	}
	info.KillBlocked = portHolderKillBlocked(info, network, env)
	info.Killable = info.KillBlocked == ""
	return info
}

func portHolderKillBlocked(info portHolderInfo, network, env string) string {
	if !info.Listening {
		return "port is not listening"
	}
	if _, ok := catalogRoleForPort(network, env, info.Port); !ok {
		return "not a catalog port for " + network + "/" + env
	}
	if info.PID == "" {
		return "no pid (ss/lsof miss)"
	}
	if info.PID == "1" {
		return "pid 1"
	}
	if info.PID == strconv.Itoa(os.Getpid()) {
		return "self"
	}
	if isHostTipListenerPID(info.PID) || info.Holder == "host_tip" {
		return "host tip agent"
	}
	if portOwnedByEnv(info.Port, network, env) {
		return "this node unit — use Remove"
	}
	if reason := portHolderProtectedName(info.Comm, info.Cmdline, info.Unit, network, env); reason != "" {
		return reason
	}
	return ""
}

func portHolderProtectedName(comm, cmdline, unit, network, env string) string {
	u := strings.ToLower(strings.TrimSpace(unit))
	switch u {
	case "rpcnode-api-agent.service", "rpcnode-system-agent.service",
		"rpcnode-watchdog.service", "ssh.service", "sshd.service",
		"systemd.service", "systemd-journald.service":
		return "protected unit " + u
	}
	for _, own := range envReclaimUnits(network, env) {
		if strings.EqualFold(u, own) {
			return "this node unit (" + own + ") — use Remove"
		}
	}
	blob := strings.ToLower(strings.TrimSpace(comm) + " " + strings.TrimSpace(cmdline))
	for _, name := range []string{
		"systemd", "sshd", "rpcnode-api-agent", "rpcnode-system-agent", "rpcnode-watchdog",
	} {
		if strings.EqualFold(strings.TrimSpace(comm), name) || strings.Contains(blob, name) {
			return "protected process " + name
		}
	}
	return ""
}

func pidCmdline(pid string) string {
	pid = strings.TrimSpace(pid)
	if pid == "" || pid == "0" {
		return ""
	}
	b, err := os.ReadFile("/proc/" + pid + "/cmdline")
	if err != nil {
		return ""
	}
	return strings.Join(strings.Fields(strings.ReplaceAll(string(b), "\x00", " ")), " ")
}

func pidSystemdUnit(pid string) string {
	pid = strings.TrimSpace(pid)
	if pid == "" || pid == "0" {
		return ""
	}
	b, err := os.ReadFile("/proc/" + pid + "/cgroup")
	if err != nil {
		return ""
	}
	best := ""
	for _, line := range strings.Split(string(b), "\n") {
		line = strings.TrimSpace(line)
		if i := strings.LastIndex(line, "/"); i >= 0 {
			name := line[i+1:]
			if strings.HasSuffix(name, ".service") {
				best = name
			}
		}
	}
	return best
}

func (h portHolderInfo) asMap() map[string]any {
	b, err := json.Marshal(h)
	if err != nil {
		return map[string]any{"port": h.Port, "ok": false}
	}
	var out map[string]any
	if json.Unmarshal(b, &out) != nil {
		return map[string]any{"port": h.Port}
	}
	return out
}

func (s *Server) handleNodesPortHolder(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Network string `json:"network"`
		Env     string `json:"env"`
		Port    int    `json:"port"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
		return
	}
	req.Network = normalizeNetwork(req.Network)
	req.Env = normalizeEnv(req.Env)
	if req.Network == "" || req.Env == "" || req.Port <= 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "network_env_port_required"})
		return
	}
	if !networkEnvSupported(req.Network, req.Env) {
		writeJSON(w, http.StatusBadRequest, unsupportedNetworkEnvPayload(req.Network, req.Env))
		return
	}
	info := inspectPortHolder(req.Port, req.Network, req.Env)
	out := info.asMap()
	out["ok"] = true
	out["network"] = req.Network
	out["env"] = req.Env
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleNodesPortHolderKill(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Network string `json:"network"`
		Env     string `json:"env"`
		Port    int    `json:"port"`
		PID     string `json:"pid"`
		Confirm bool   `json:"confirm"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
		return
	}
	req.Network = normalizeNetwork(req.Network)
	req.Env = normalizeEnv(req.Env)
	req.PID = strings.TrimSpace(req.PID)
	if !req.Confirm {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "confirm_required"})
		return
	}
	if req.Network == "" || req.Env == "" || req.Port <= 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "network_env_port_required"})
		return
	}
	if !networkEnvSupported(req.Network, req.Env) {
		writeJSON(w, http.StatusBadRequest, unsupportedNetworkEnvPayload(req.Network, req.Env))
		return
	}
	info := inspectPortHolder(req.Port, req.Network, req.Env)
	if !info.Killable {
		writeJSON(w, http.StatusConflict, map[string]any{
			"ok": false, "error": "kill_blocked", "message": info.KillBlocked,
			"holder": info.asMap(),
		})
		return
	}
	if req.PID != "" && req.PID != info.PID {
		writeJSON(w, http.StatusConflict, map[string]any{
			"ok": false, "error": "pid_mismatch",
			"message": fmt.Sprintf("listener is pid %s, not %s — re-check ports", info.PID, req.PID),
			"holder":  info.asMap(),
		})
		return
	}
	pidNum, err := strconv.Atoi(info.PID)
	if err != nil || pidNum <= 1 {
		writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "error": "bad_pid", "pid": info.PID})
		return
	}
	hostLogf("info", "api-agent", "port_kill", "SIGTERM pid=%s comm=%s port=%d %s/%s",
		info.PID, info.Comm, req.Port, req.Network, req.Env)
	_ = syscall.Kill(pidNum, syscall.SIGTERM)
	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		if !portIsListening(req.Port) || !pidAlive(info.PID) {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}
	if portIsListening(req.Port) && pidAlive(info.PID) {
		hostLogf("warn", "api-agent", "port_kill", "SIGKILL pid=%s port=%d", info.PID, req.Port)
		_ = syscall.Kill(pidNum, syscall.SIGKILL)
		time.Sleep(300 * time.Millisecond)
	}
	after := inspectPortHolder(req.Port, req.Network, req.Env)
	freed := !after.Listening
	out := map[string]any{
		"ok":      freed,
		"freed":   freed,
		"killed":  info.asMap(),
		"holder":  after.asMap(),
		"network": req.Network,
		"env":     req.Env,
	}
	if !freed {
		out["error"] = "still_listening"
		out["message"] = "process still listening — systemd may have restarted it"
		if after.Unit != "" {
			out["message"] = fmt.Sprintf("still listening (unit %s may Restart=always)", after.Unit)
		}
		writeJSON(w, http.StatusConflict, out)
		return
	}
	out["message"] = fmt.Sprintf("killed %s pid %s on :%d", info.Comm, info.PID, req.Port)
	writeJSON(w, http.StatusOK, out)
}

func pidAlive(pid string) bool {
	pid = strings.TrimSpace(pid)
	if pid == "" || pid == "0" {
		return false
	}
	_, err := os.Stat("/proc/" + pid)
	return err == nil
}
