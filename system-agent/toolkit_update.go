package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ToolkitUpdateController — self-update for nginx/agents/UI (not java-tron).
type ToolkitUpdateController struct {
	cfg      Config
	mu       sync.Mutex
	running  bool
	lastAuto string // YYYY-MM-DD UTC when auto last fired
}

func newToolkitUpdateController(cfg Config) *ToolkitUpdateController {
	return &ToolkitUpdateController{cfg: cfg}
}

func (t *ToolkitUpdateController) statePath() string {
	if v := os.Getenv("TRON_TOOLKIT_UPDATE_STATE"); v != "" {
		return v
	}
	return filepath.Join(filepath.Dir(t.cfg.StateFile), "toolkit-update.json")
}

func (t *ToolkitUpdateController) requestPath() string {
	if v := os.Getenv("TRON_TOOLKIT_UPDATE_REQUEST"); v != "" {
		return v
	}
	return filepath.Join(filepath.Dir(t.cfg.StateFile), "update-requested.json")
}

func (t *ToolkitUpdateController) toolkitRoot() string {
	if t.cfg.ToolkitDir != "" {
		return t.cfg.ToolkitDir
	}
	return "/opt/rpcnode/deploy/nodes/tron/toolkit"
}

func (t *ToolkitUpdateController) localVersion() string {
	// Always from the binary (ldflags / version.go) — never TOOLKIT_VERSION on disk.
	return agentVersion()
}

func (t *ToolkitUpdateController) applyCapability() (mode string, ready bool, reason string) {
	root := t.toolkitRoot()
	if root == "" || !fileExists(root) {
		return "unavailable", false, "TOOLKIT_DIR missing"
	}
	test := filepath.Join(root, ".write-test-toolkit")
	if err := os.WriteFile(test, []byte("ok\n"), 0644); err != nil {
		return "host-queue", false, "toolkit dir not writable — mount TOOLKIT_DIR :rw"
	}
	_ = os.Remove(test)

	if _, err := exec.LookPath("docker"); err != nil {
		return "host-queue", false, "docker CLI missing in agent image"
	}
	if err := exec.Command("docker", "compose", "version").Run(); err != nil {
		return "host-queue", false, "docker compose plugin missing"
	}
	sock := "/var/run/docker.sock"
	if _, err := os.Stat(sock); err != nil && os.Getenv("DOCKER_HOST") == "" {
		return "host-queue", false, "docker.sock not mounted (and DOCKER_HOST unset)"
	}
	ctl := toolkitCtlPath(root)
	if !fileExists(ctl) {
		return "host-queue", false, "rpcnodectl not found under TOOLKIT_DIR"
	}
	return "docker-sock", true, ""
}

func (t *ToolkitUpdateController) load() map[string]any {
	st := readJSONFile(t.statePath())
	if len(st) == 0 {
		st = map[string]any{
			"auto": false, "hour_utc": 4, "minute_utc": 15,
			"status": "idle", "local_version": t.localVersion(),
			"remote_version": "", "update_available": false,
			"message": "no check yet", "progress": "",
		}
	}
	st["local_version"] = t.localVersion()
	if st["channel"] == nil {
		st["channel"] = os.Getenv("TOOLKIT_VERSION_URL")
	}
	if st["update_url"] == nil {
		st["update_url"] = os.Getenv("TOOLKIT_UPDATE_URL")
	}
	mode, ready, reason := t.applyCapability()
	st["apply_mode"] = mode
	st["apply_ready"] = ready
	if reason != "" {
		st["apply_blocker"] = reason
	} else {
		delete(st, "apply_blocker")
	}
	st["metrics_scope"] = metricsScope()
	return st
}

func metricsScope() string {
	// pid:host → /proc reflects the host; otherwise container cgroup view.
	if b, err := os.ReadFile("/proc/1/cgroup"); err == nil {
		s := string(b)
		if strings.Contains(s, "/docker") || strings.Contains(s, "containerd") {
			// Still may be pid:host; loadavg from host pid ns is enough signal for docs.
		}
	}
	if os.Getenv("TRON_COMPOSE_HOST") == "1" {
		return "host"
	}
	// Default compose sets pid: host on system-agent.
	return "host"
}

func (t *ToolkitUpdateController) save(st map[string]any) error {
	path := t.statePath()
	_ = ensureDir(filepath.Dir(path))
	b, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	b = append(b, '\n')
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func (t *ToolkitUpdateController) Snapshot() map[string]any {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.load()
}

func toolkitVersionURL() string {
	if u := strings.TrimSpace(os.Getenv("TOOLKIT_VERSION_URL")); u != "" {
		return u
	}
	return "https://rpcnode.dev/install/TOOLKIT_VERSION"
}

func (t *ToolkitUpdateController) fetchRemote() string {
	url := toolkitVersionURL()
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func (t *ToolkitUpdateController) Check() map[string]any {
	t.mu.Lock()
	defer t.mu.Unlock()
	st := t.load()
	local := t.localVersion()
	remote := t.fetchRemote()
	st["local_version"] = local
	st["remote_version"] = remote
	st["channel"] = toolkitVersionURL()
	st["update_url"] = os.Getenv("TOOLKIT_UPDATE_URL")
	st["last_check_at"] = time.Now().UTC().Format(time.RFC3339)
	switch {
	case remote == "":
		st["status"] = "error"
		st["update_available"] = false
		st["message"] = "cannot fetch remote version from " + toolkitVersionURL()
	case remote != local:
		st["status"] = "available"
		st["update_available"] = true
		st["message"] = fmt.Sprintf("update available: %s → %s", local, remote)
	default:
		st["status"] = "ok"
		st["update_available"] = false
		st["message"] = "up to date (" + local + ")"
	}
	if blocker, _ := st["apply_blocker"].(string); blocker != "" && truthy(st["update_available"]) {
		st["message"] = fmt.Sprintf("%v; apply needs: %s", st["message"], blocker)
	}
	_ = t.save(st)
	return st
}

func (t *ToolkitUpdateController) SetSchedule(auto bool, hour, minute int) map[string]any {
	t.mu.Lock()
	defer t.mu.Unlock()
	if hour < 0 || hour > 23 {
		hour = 4
	}
	if minute < 0 || minute > 59 {
		minute = 15
	}
	st := t.load()
	st["auto"] = auto
	st["hour_utc"] = hour
	st["minute_utc"] = minute
	st["message"] = fmt.Sprintf("schedule saved (daily %02d:%02d UTC)", hour, minute)
	_ = t.save(st)
	return st
}

func (t *ToolkitUpdateController) busyReason() string {
	maint := readJSONFile(t.cfg.MaintenanceFile)
	if truthy(maint["enabled"]) {
		return "maintenance active"
	}
	if wgetRunning(t.cfg) {
		return "snapshot download running"
	}
	return ""
}

func (t *ToolkitUpdateController) queueHost(reason string) map[string]any {
	req := map[string]any{
		"requested_at": time.Now().UTC().Format(time.RFC3339),
		"reason":       reason,
		"source":       "system-agent",
		"env":          t.cfg.Env,
	}
	path := t.requestPath()
	_ = ensureDir(filepath.Dir(path))
	b, _ := json.MarshalIndent(req, "", "  ")
	b = append(b, '\n')
	_ = os.WriteFile(path, b, 0644)

	st := t.load()
	st["status"] = "queued"
	st["progress"] = ""
	st["apply_mode"] = "host-queue"
	st["apply_ready"] = false
	st["message"] = "queued for host apply (" + reason + ") — run: rpcnodectl toolkit-update watch"
	_ = t.save(st)
	return st
}

// Apply starts toolkit update asynchronously when docker-sock is available,
// or queues a host-side request. HTTP returns immediately with status updating|queued.
func (t *ToolkitUpdateController) Apply() (map[string]any, error) {
	t.mu.Lock()
	if t.running {
		st := t.load()
		st["message"] = "apply already in progress"
		t.mu.Unlock()
		return st, nil
	}
	mode, ready, reason := t.applyCapability()
	st := t.load()
	if !ready {
		t.mu.Unlock()
		return t.queueHost(reason), nil
	}

	t.running = true
	st["status"] = "updating"
	st["progress"] = "starting rpcnodectl toolkit-update apply"
	st["message"] = "Updating nginx + agents + UI. java-tron on host is NOT touched."
	st["apply_mode"] = mode
	st["apply_ready"] = true
	_ = t.save(st)
	t.mu.Unlock()

	go t.runApply()
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.load(), nil
}

func (t *ToolkitUpdateController) runApply() {
	defer func() {
		t.mu.Lock()
		t.running = false
		t.mu.Unlock()
	}()

	ctl := toolkitCtlPath(t.toolkitRoot())
	cmd := exec.Command(ctl, "toolkit-update", "apply", "--yes")
	cmd.Env = append(os.Environ(), "TRON_ENV="+t.cfg.Env, "SETUP_NONINTERACTIVE=1")
	cmd.Dir = t.toolkitRoot()
	out, err := cmd.CombinedOutput()

	t.mu.Lock()
	defer t.mu.Unlock()
	st := t.load()
	st["progress"] = ""
	st["last_apply_at"] = time.Now().UTC().Format(time.RFC3339)
	st["local_version"] = t.localVersion()
	if err != nil {
		// Recreate often kills this process mid-apply; if state was already ok, keep it.
		if cur, _ := st["status"].(string); cur == "ok" && !truthy(st["update_available"]) {
			log.Printf("toolkit apply process ended after recreate: %v (%s)", err, trimCmd(string(out)))
			_ = t.save(st)
			return
		}
		st["status"] = "error"
		st["message"] = fmt.Sprintf("apply failed: %v (%s)", err, trimCmd(string(out)))
		_ = t.save(st)
		log.Printf("toolkit apply failed: %v (%s)", err, trimCmd(string(out)))
		return
	}
	st["status"] = "ok"
	st["update_available"] = false
	st["message"] = "toolkit updated to " + t.localVersion() + " (java-tron untouched)"
	_ = t.save(st)
	log.Printf("toolkit apply ok → %s", t.localVersion())
}

func (t *ToolkitUpdateController) TickAuto() {
	st := t.Check()
	auto, _ := st["auto"].(bool)
	if !auto {
		return
	}
	hour := asInt(st["hour_utc"], 4)
	minute := asInt(st["minute_utc"], 15)
	now := time.Now().UTC()
	if now.Hour() != hour || now.Minute() != minute {
		return
	}
	day := now.Format("2006-01-02")
	t.mu.Lock()
	if t.lastAuto == day {
		t.mu.Unlock()
		return
	}
	if s, _ := st["last_auto_day"].(string); s == day {
		t.lastAuto = day
		t.mu.Unlock()
		return
	}
	t.lastAuto = day
	t.mu.Unlock()

	if reason := t.busyReason(); reason != "" {
		t.mu.Lock()
		st = t.load()
		st["last_auto_day"] = day
		st["message"] = "auto-update skipped: " + reason
		_ = t.save(st)
		t.mu.Unlock()
		return
	}
	avail, _ := st["update_available"].(bool)
	t.mu.Lock()
	st = t.load()
	st["last_auto_day"] = day
	_ = t.save(st)
	t.mu.Unlock()
	if !avail {
		return
	}
	log.Printf("AUTO toolkit update %v → %v (java-tron untouched)", st["local_version"], st["remote_version"])
	_, _ = t.Apply()
}

func asInt(v any, def int) int {
	switch n := v.(type) {
	case float64:
		return int(n)
	case int:
		return n
	case json.Number:
		i, err := n.Int64()
		if err == nil {
			return int(i)
		}
	case string:
		i, err := strconv.Atoi(n)
		if err == nil {
			return i
		}
	}
	return def
}

func (t *ToolkitUpdateController) handleCheck(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodGet {
		http.Error(w, "GET|POST", http.StatusMethodNotAllowed)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "toolkit_update": t.Check()})
}

func (t *ToolkitUpdateController) handleApply(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	st, err := t.Apply()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error(), "toolkit_update": st})
		return
	}
	// Accepted: work continues in background (or queued for host).
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "accepted": true, "toolkit_update": st})
}

func (t *ToolkitUpdateController) handleSchedule(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	var body struct {
		Auto   *bool `json:"auto"`
		Hour   *int  `json:"hour_utc"`
		Minute *int  `json:"minute_utc"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	cur := t.Snapshot()
	auto, _ := cur["auto"].(bool)
	hour := asInt(cur["hour_utc"], 4)
	minute := asInt(cur["minute_utc"], 15)
	if body.Auto != nil {
		auto = *body.Auto
	}
	if body.Hour != nil {
		hour = *body.Hour
	}
	if body.Minute != nil {
		minute = *body.Minute
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "toolkit_update": t.SetSchedule(auto, hour, minute)})
}
