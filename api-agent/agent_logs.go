package main

import (
	"bufio"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

type agentLogStream struct {
	ID     string   `json:"id"`
	Unit   string   `json:"unit"`
	Label  string   `json:"label"`
	Path   string   `json:"path,omitempty"`
	Source string   `json:"source"` // file | journal | empty
	Lines  []string `json:"lines"`
}

// agentLogStreamLabel — human label for tip/leaf/watchdog unit basenames.
func agentLogStreamLabel(unitBasename string) string {
	base := strings.TrimSuffix(strings.TrimSpace(unitBasename), ".service")
	switch base {
	case "rpcnode-api-agent":
		return "Tip api-agent"
	case "rpcnode-system-agent":
		return "Tip system-agent"
	case "rpcnode-agent-watchdog":
		return "Watchdog"
	}
	if strings.HasPrefix(base, "rpcnode-api-agent-") {
		slug := strings.TrimPrefix(base, "rpcnode-api-agent-")
		return "Leaf api-agent · " + strings.ReplaceAll(slug, "-", "/")
	}
	if strings.HasPrefix(base, "rpcnode-system-agent-") {
		slug := strings.TrimPrefix(base, "rpcnode-system-agent-")
		return "Leaf system-agent · " + strings.ReplaceAll(slug, "-", "/")
	}
	return base
}

func clampAgentLogLines(n int) int {
	if n <= 0 {
		return 200
	}
	if n < 50 {
		return 50
	}
	if n > 500 {
		return 500
	}
	return n
}

func tailFileLines(path string, n int) ([]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if st.Size() == 0 {
		return nil, fmt.Errorf("empty")
	}
	// Read whole file when small; otherwise scan all lines (agent logs are rotated ≤100M).
	var lines []string
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for sc.Scan() {
		lines = append(lines, sc.Text())
		if len(lines) > n*4 && len(lines) > n {
			// Keep memory bounded on huge files — drop from front in chunks.
			lines = append([]string(nil), lines[len(lines)-n:]...)
		}
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	if len(lines) == 0 {
		return nil, fmt.Errorf("empty")
	}
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return lines, nil
}

func journalUnitLines(unit string, n int) ([]string, error) {
	unit = strings.TrimSpace(unit)
	if unit == "" {
		return nil, fmt.Errorf("empty unit")
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	out, err := exec.Command(
		"journalctl", "-u", unit, "-n", strconv.Itoa(n),
		"--no-pager", "-o", "short-iso",
	).CombinedOutput()
	if err != nil && len(out) == 0 {
		return nil, err
	}
	raw := strings.TrimSpace(string(out))
	if raw == "" {
		return nil, fmt.Errorf("empty journal")
	}
	lines := strings.Split(raw, "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return lines, nil
}

func collectAgentLogStream(unitBasename string, n int) agentLogStream {
	base := strings.TrimSuffix(strings.TrimSpace(unitBasename), ".service")
	unit := base + ".service"
	st := agentLogStream{
		ID:    base,
		Unit:  unit,
		Label: agentLogStreamLabel(base),
		Lines: []string{},
	}
	path := agentLogPathForUnit(base)
	st.Path = path
	if lines, err := tailFileLines(path, n); err == nil && len(lines) > 0 {
		st.Source = "file"
		st.Lines = lines
		return st
	}
	if lines, err := journalUnitLines(unit, n); err == nil && len(lines) > 0 {
		st.Source = "journal"
		st.Lines = lines
		return st
	}
	st.Source = "empty"
	st.Lines = []string{"(no log lines yet — unit may be idle or file-log drop-in not active until restart)"}
	return st
}

func collectHostAuditLogStream(n int) agentLogStream {
	path := hostLogFilePath()
	st := agentLogStream{
		ID:     "host",
		Label:  "Host · rpcnode.log",
		Path:   path,
		Source: "empty",
		Lines:  []string{"(no /var/log/rpcnode.log yet — Update agent or wait for first install/start event)"},
	}
	if lines, err := tailFileLines(path, n); err == nil && len(lines) > 0 {
		st.Source = "file"
		st.Lines = lines
	}
	return st
}

func collectAgentLogStreams(filterUnit string, n int) []agentLogStream {
	n = clampAgentLogLines(n)
	filterUnit = strings.TrimSuffix(strings.TrimSpace(filterUnit), ".service")
	units := listAgentUnitsForFileLogging()
	out := make([]agentLogStream, 0, len(units)+1)
	if filterUnit == "" || filterUnit == "host" {
		out = append(out, collectHostAuditLogStream(n))
		if filterUnit == "host" {
			return out
		}
	}
	for _, u := range units {
		base := strings.TrimSuffix(u, ".service")
		if filterUnit != "" && base != filterUnit && u != filterUnit+".service" {
			continue
		}
		out = append(out, collectAgentLogStream(base, n))
	}
	for _, st := range collectTronNodeLogStreams(n) {
		if filterUnit != "" && st.ID != filterUnit && st.ID+".service" != filterUnit {
			continue
		}
		out = append(out, st)
	}
	return out
}

func collectFileLogStream(id, label, path string, n int) agentLogStream {
	st := agentLogStream{
		ID:     id,
		Label:  label,
		Path:   path,
		Source: "empty",
		Lines:  []string{"(no " + path + " yet)"},
	}
	if lines, err := tailFileLinesBounded(path, n, 256*1024); err == nil && len(lines) > 0 {
		st.Source = "file"
		st.Lines = lines
	}
	return st
}

func tailFileLinesBounded(path string, n int, maxBytes int64) ([]string, error) {
	if n <= 0 {
		n = 200
	}
	if maxBytes <= 0 {
		maxBytes = 256 * 1024
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if st.Size() == 0 {
		return nil, fmt.Errorf("empty")
	}
	start := int64(0)
	if st.Size() > maxBytes {
		start = st.Size() - maxBytes
	}
	if _, err := f.Seek(start, io.SeekStart); err != nil {
		return nil, err
	}
	b, err := io.ReadAll(io.LimitReader(f, maxBytes+1))
	if err != nil {
		return nil, err
	}
	if start > 0 {
		if i := strings.IndexByte(string(b), '\n'); i >= 0 && i+1 < len(b) {
			b = b[i+1:]
		}
	}
	raw := strings.Split(string(b), "\n")
	var lines []string
	for _, ln := range raw {
		ln = strings.TrimSpace(ln)
		if ln != "" {
			lines = append(lines, ln)
		}
	}
	if len(lines) == 0 {
		return nil, fmt.Errorf("empty")
	}
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return lines, nil
}

func collectTronNodeLogStreams(n int) []agentLogStream {
	envs := listProvisionedTronEnvsFrom("/etc/systemd/system", "/etc/rpcnode/nodes", "/opt/tron")
	out := make([]agentLogStream, 0, len(envs))
	for _, env := range envs {
		path := filepath.Join("/opt/tron", env, "logs", "tron.log")
		out = append(out, collectFileLogStream("tron-"+env, "TRON · "+env, path, n))
	}
	return out
}

func listProvisionedTronEnvsFrom(systemdDir, nodesDir, optRoot string) []string {
	seen := map[string]bool{}
	add := func(env string) {
		env = strings.ToLower(strings.TrimSpace(env))
		if env == "" || env == "snapshot" || seen[env] {
			return
		}
		if strings.Contains(env, "/") || strings.Contains(env, "..") {
			return
		}
		seen[env] = true
	}
	if found, err := filepath.Glob(filepath.Join(systemdDir, "tron-*.service")); err == nil {
		for _, p := range found {
			base := strings.TrimSuffix(filepath.Base(p), ".service")
			if strings.HasSuffix(base, "-snapshot") {
				continue
			}
			add(strings.TrimPrefix(base, "tron-"))
		}
	}
	if found, err := filepath.Glob(filepath.Join(nodesDir, "tron-*.json")); err == nil {
		for _, p := range found {
			add(strings.TrimSuffix(strings.TrimPrefix(filepath.Base(p), "tron-"), ".json"))
		}
	}
	if found, err := filepath.Glob(filepath.Join(optRoot, "*", "logs", "tron.log")); err == nil {
		for _, p := range found {
			// /opt/tron/<env>/logs/tron.log
			add(filepath.Base(filepath.Dir(filepath.Dir(p))))
		}
	}
	out := make([]string, 0, len(seen))
	for env := range seen {
		out = append(out, env)
	}
	sort.Strings(out)
	return out
}

func (s *Server) handleAgentLogs(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "error": "method_not_allowed"})
		return
	}
	q := r.URL.Query()
	n, _ := strconv.Atoi(strings.TrimSpace(q.Get("lines")))
	n = clampAgentLogLines(n)
	unit := strings.TrimSpace(q.Get("unit"))
	streams := collectAgentLogStreams(unit, n)
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":      true,
		"version": agentVersion(),
		"lines":   n,
		"streams": streams,
		"count":   len(streams),
	})
}
