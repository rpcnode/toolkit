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
	"runtime"
	"strings"
	"sync"
	"time"
)

// ClientUpdateController — chain client (java-tron / geth / Agave / …) updates.
// Separate from toolkit agent update. Apply MUST enable Go-proxy maintenance sleep.
type ClientUpdateController struct {
	cfg  Config
	ctrl *ControlState
	mu   sync.Mutex
	busy bool
}

type clientManifest struct {
	Version            string `json:"version"`
	ArtifactURL        string `json:"artifact_url"`
	ArtifactURLAarch64 string `json:"artifact_url_aarch64"`
	SHA256             string `json:"sha256"`
	SHA256Aarch64      string `json:"sha256_aarch64"`
	NeedsConfPatch     bool   `json:"needs_conf_patch"`
	ArtifactKind       string `json:"artifact_kind"` // jar | bin | tarball | zip | apt
	Notes              string `json:"notes"`
}

func (m clientManifest) urlForHost() string {
	if runtime.GOARCH == "arm64" || runtime.GOARCH == "aarch64" {
		if u := strings.TrimSpace(m.ArtifactURLAarch64); u != "" {
			return u
		}
	}
	return strings.TrimSpace(m.ArtifactURL)
}

func (m clientManifest) shaForHost() string {
	if runtime.GOARCH == "arm64" || runtime.GOARCH == "aarch64" {
		if s := strings.TrimSpace(m.SHA256Aarch64); s != "" {
			return s
		}
	}
	return strings.TrimSpace(m.SHA256)
}

func newClientUpdateController(cfg Config, ctrl *ControlState) *ClientUpdateController {
	return &ClientUpdateController{cfg: cfg, ctrl: ctrl}
}

func (c *ClientUpdateController) statePath() string {
	if v := strings.TrimSpace(os.Getenv("RPCNODE_CLIENT_UPDATE_STATE")); v != "" {
		return v
	}
	return filepath.Join(filepath.Dir(c.cfg.StateFile), "client-update.json")
}

func clientInstallBaseURL() string {
	if u := strings.TrimSpace(os.Getenv("CLIENTS_BASE_URL")); u != "" {
		return strings.TrimRight(canonToolkitCDNHost(u), "/")
	}
	return "https://toolkit.rpcnode.dev"
}

func (c *ClientUpdateController) channelBase() string {
	net := strings.ToLower(strings.TrimSpace(c.cfg.Network))
	env := strings.ToLower(strings.TrimSpace(c.cfg.Env))
	if net == "" {
		net = "unknown"
	}
	if env == "" {
		env = "mainnet"
	}
	if u := strings.TrimSpace(os.Getenv("RPCNODE_CLIENT_CHANNEL_URL")); u != "" {
		return strings.TrimRight(u, "/")
	}
	return fmt.Sprintf("%s/clients/%s/%s", clientInstallBaseURL(), net, env)
}

func (c *ClientUpdateController) load() map[string]any {
	st := readJSONFile(c.statePath())
	if len(st) == 0 {
		st = map[string]any{
			"phase": "idle", "local": "", "latest": "",
			"update_available": false, "detail": "no check yet", "pct": 0,
		}
	}
	st["channel"] = c.channelBase()
	st["network"] = c.cfg.Network
	st["env"] = c.cfg.Env
	return st
}

func (c *ClientUpdateController) save(st map[string]any) {
	st["updated_at"] = time.Now().UTC().Format(time.RFC3339)
	b, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return
	}
	b = append(b, '\n')
	path := c.statePath()
	_ = ensureDir(filepath.Dir(path))
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0644); err != nil {
		return
	}
	_ = os.Rename(tmp, path)
}

func (c *ClientUpdateController) Snapshot() map[string]any {
	c.mu.Lock()
	defer c.mu.Unlock()
	st := c.load()
	if local := strings.TrimSpace(fmt.Sprint(st["local"])); local == "" {
		st["local"] = c.localClientVersion()
	}
	return st
}

func (c *ClientUpdateController) localClientVersion() string {
	// Prefer last collect state written by network collectors.
	st := readJSONFile(c.cfg.StateFile)
	if v, _ := st["client_version"].(string); strings.TrimSpace(v) != "" {
		return strings.TrimSpace(v)
	}
	if rpc, _ := st["rpc"].(map[string]any); rpc != nil {
		if v, _ := rpc["client_version"].(string); strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
		if v, _ := rpc["version"].(string); strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	net := strings.ToLower(strings.TrimSpace(c.cfg.Network))
	switch net {
	case "tron", "":
		return tronClientVersion(c.cfg, readVersionFile(c.cfg.VersionFile))
	default:
		return ""
	}
}

func (c *ClientUpdateController) fetchChannel() (ver string, man clientManifest, rel ClientRelease, err error) {
	rel, e := ResolveClientRelease(c.cfg.Network, c.cfg.Env)
	if e != nil || strings.TrimSpace(rel.ArtifactURL) == "" {
		return "", man, rel, e
	}
	man = clientManifest{
		Version:        rel.Version,
		ArtifactURL:    rel.ArtifactURL,
		SHA256:         rel.SHA256,
		NeedsConfPatch: rel.NeedsConfPatch,
		ArtifactKind:   rel.ArtifactKind,
		Notes:          rel.Notes,
	}
	return rel.Version, man, rel, nil
}

func (c *ClientUpdateController) fetchCDNChannel() (ver string, man clientManifest, err error) {
	base := c.channelBase()
	client := &http.Client{Timeout: 20 * time.Second}
	manURL := base + "/manifest.json"
	if resp, e := client.Get(manURL); e == nil {
		defer resp.Body.Close()
		if resp.StatusCode >= 200 && resp.StatusCode < 300 {
			b, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
			if json.Unmarshal(b, &man) == nil && strings.TrimSpace(man.Version) != "" {
				return strings.TrimSpace(man.Version), man, nil
			}
		}
	}
	verURL := base + "/VERSION"
	resp, e := client.Get(verURL)
	if e != nil {
		return "", man, e
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return "", man, fmt.Errorf("HTTP %d fetching %s", resp.StatusCode, verURL)
	}
	b, e := io.ReadAll(io.LimitReader(resp.Body, 256))
	if e != nil {
		return "", man, e
	}
	ver = strings.TrimSpace(string(b))
	if ver == "" {
		return "", man, fmt.Errorf("empty client VERSION at %s", verURL)
	}
	man.Version = ver
	return ver, man, nil
}

func (c *ClientUpdateController) Check() map[string]any {
	c.mu.Lock()
	defer c.mu.Unlock()
	st := c.load()
	local := c.localClientVersion()
	st["local"] = local
	st["phase"] = firstNonEmptyStr(fmt.Sprint(st["phase"]), "idle")
	if ph := strings.ToLower(fmt.Sprint(st["phase"])); ph == "updating" || ph == "starting" {
		c.save(st)
		return st
	}
	latest, man, rel, err := c.fetchChannel()
	if err != nil {
		st["detail"] = "channel check failed: " + err.Error()
		st["check_error"] = err.Error()
		c.save(st)
		return st
	}
	localN, latestN, latestDisplay := clientVersionsForUpdate(c.cfg.Network, local, latest)
	st["latest"] = latestDisplay
	st["release"] = rel
	st["channel"] = rel.Source
	st["manifest"] = map[string]any{
		"version": man.Version, "artifact_url": man.ArtifactURL,
		"sha256": man.SHA256, "needs_conf_patch": man.NeedsConfPatch,
		"artifact_kind": man.ArtifactKind, "notes": man.Notes,
		"tag": rel.Tag, "source": rel.Source, "conf_url": rel.ConfURL,
	}
	avail := localN != "" && latestN != "" && versionCompareLoose(localN, latestN) < 0
	if localN == "" && latestN != "" {
		// Unknown local — still surface latest for ops.
		avail = false
		st["detail"] = "latest " + latestN + " (local client version unknown)"
	} else if avail {
		st["detail"] = "update available: " + localN + " → " + latestN
	} else if latestN != "" {
		st["detail"] = "up to date (" + localN + ")"
	}
	st["update_available"] = avail
	st["check_error"] = ""
	st["phase"] = "idle"
	c.save(st)
	return st
}

func versionCompareLoose(a, b string) int {
	// Reuse panel-compatible numeric dotted compare (local copy).
	as := strings.Split(strings.TrimPrefix(strings.TrimSpace(a), "v"), ".")
	bs := strings.Split(strings.TrimPrefix(strings.TrimSpace(b), "v"), ".")
	n := len(as)
	if len(bs) > n {
		n = len(bs)
	}
	for i := 0; i < n; i++ {
		var ai, bi string
		if i < len(as) {
			ai = as[i]
		}
		if i < len(bs) {
			bi = bs[i]
		}
		an := atoiDigitsPrefix(ai)
		bn := atoiDigitsPrefix(bi)
		if an < bn {
			return -1
		}
		if an > bn {
			return 1
		}
	}
	if a == b {
		return 0
	}
	if a < b {
		return -1
	}
	if a > b {
		return 1
	}
	return 0
}

func atoiDigitsPrefix(s string) int {
	n := 0
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			break
		}
		n = n*10 + int(s[i]-'0')
	}
	return n
}

func firstNonEmptyStr(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" && v != "<nil>" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}

func (c *ClientUpdateController) Apply() (map[string]any, error) {
	c.mu.Lock()
	if c.busy {
		st := c.load()
		c.mu.Unlock()
		return st, fmt.Errorf("client update already running")
	}
	if c.cfg.HostTip {
		c.mu.Unlock()
		return nil, fmt.Errorf("host tip has no chain client — use per-node agent")
	}
	if len(cfgNodeUnits(c.cfg)) == 0 {
		c.mu.Unlock()
		return nil, fmt.Errorf("node unit unknown")
	}
	c.busy = true
	st := c.load()
	st["phase"] = "updating"
	st["detail"] = "starting client update"
	st["pct"] = 5
	st["last_error"] = ""
	c.save(st)
	c.mu.Unlock()

	go c.runApply()
	return c.Snapshot(), nil
}

func (c *ClientUpdateController) runApply() {
	defer func() {
		c.mu.Lock()
		c.busy = false
		c.mu.Unlock()
		if c.ctrl != nil {
			c.ctrl.RequestRefresh()
		}
	}()

	set := func(phase, detail string, pct float64, errMsg string) {
		c.mu.Lock()
		defer c.mu.Unlock()
		st := c.load()
		st["phase"] = phase
		st["detail"] = detail
		st["pct"] = pct
		if errMsg != "" {
			st["last_error"] = errMsg
		}
		c.save(st)
	}

	latest, man, _, err := c.fetchChannel()
	if err != nil {
		set("error", "channel fetch failed", 0, err.Error())
		return
	}
	kind := guessArtifactKind(man.ArtifactKind, man.urlForHost())
	if strings.TrimSpace(man.urlForHost()) == "" || kind == "apt" || kind == "docker_extract" {
		set("error", "no downloadable client artifact for "+c.cfg.Network+"/"+c.cfg.Env+" (catalog is "+kind+" / empty url)", 0, "no artifact_url")
		return
	}
	latest = normalizeClientVersion(latest)

	units := cfgNodeUnits(c.cfg)
	label := strings.Join(units, ", ")

	// Order MUST be: sleep RPC → graceful stop → wait dead → replace client → start → wake.
	if c.ctrl != nil {
		_ = c.ctrl.SetMaintenanceEx(c.cfg, true, "client update "+latest+" — RPC paused", "client_update")
	}
	set("updating", "RPC sleep (maintenance)", 10, "")

	set("updating", "soft-stopping "+label, 20, "")
	if err := stopNodeUnits(c.cfg, cfgStopBudget(c.cfg.Network)); err != nil {
		set("error", "fullnode did not stop: "+err.Error(), 25, err.Error())
		if c.ctrl != nil {
			_ = c.ctrl.SetMaintenanceEx(c.cfg, false, "", "")
		}
		return
	}
	set("updating", "fullnode stopped — installing "+latest, 40, "")

	if err := c.installArtifact(man); err != nil {
		set("error", "install failed: "+err.Error(), 45, err.Error())
		_ = startNodeUnits(c.cfg)
		if c.ctrl != nil {
			_ = c.ctrl.SetMaintenanceEx(c.cfg, false, "", "")
		}
		return
	}
	if man.NeedsConfPatch {
		set("updating", "patching node conf for new client", 60, "")
		if err := c.patchTronConfIfNeeded(); err != nil {
			log.Printf("client_update conf patch: %v", err)
		}
	}

	set("starting", "starting "+label, 75, "")
	if err := startNodeUnits(c.cfg); err != nil {
		set("error", "start failed: "+err.Error(), 75, err.Error())
		if c.ctrl != nil {
			_ = c.ctrl.SetMaintenanceEx(c.cfg, false, "", "")
		}
		return
	}

	// 5) Brief wait then wake proxy.
	time.Sleep(3 * time.Second)
	if c.ctrl != nil {
		_ = c.ctrl.SetMaintenanceEx(c.cfg, false, "", "")
	}
	set("idle", "updated to "+latest+" — node starting", 100, "")
	c.mu.Lock()
	st := c.load()
	st["local"] = latest
	st["latest"] = latest
	st["update_available"] = false
	st["phase"] = "idle"
	st["detail"] = "updated to " + latest
	st["pct"] = 100
	c.save(st)
	c.mu.Unlock()
	log.Printf("client_update: %s/%s → %s", c.cfg.Network, c.cfg.Env, latest)
}

// waitUnitStopped polls systemctl until the unit is inactive/failed/dead (or timeout).
func waitUnitStopped(unit string, timeout time.Duration) error {
	unit = strings.TrimSuffix(strings.TrimSpace(unit), ".service")
	if unit == "" {
		return fmt.Errorf("empty unit")
	}
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		out, _ := exec.Command("systemctl", "is-active", unit).CombinedOutput()
		state := strings.TrimSpace(string(out))
		switch state {
		case "inactive", "failed", "dead", "unknown":
			return nil
		}
		// Also accept empty / not-found as stopped.
		if state == "" || strings.Contains(state, "could not be found") {
			return nil
		}
		time.Sleep(500 * time.Millisecond)
	}
	out, _ := exec.Command("systemctl", "is-active", unit).CombinedOutput()
	return fmt.Errorf("still %q after %s", strings.TrimSpace(string(out)), timeout)
}

func (c *ClientUpdateController) installArtifact(man clientManifest) error {
	url := man.urlForHost()
	kind := guessArtifactKind(man.ArtifactKind, url)
	switch kind {
	case "apt", "docker_extract":
		return fmt.Errorf("client is %s-managed — no artifact to replace", kind)
	case "jar":
		return c.downloadVerified(url, c.clientJarPath(), 0644, man.shaForHost())
	case "tarball", "zip":
		return c.installFromArchive(man)
	default:
		dest := c.clientBinPath()
		if err := c.downloadVerified(url, dest, 0755, man.shaForHost()); err != nil {
			return err
		}
		c.refreshClientLinks(dest)
		return nil
	}
}

func (c *ClientUpdateController) tronJarPath() string {
	opt := c.optDir()
	candidates := []string{
		filepath.Join(opt, "FullNode.jar"),
		filepath.Join(opt, "java-tron.jar"),
		filepath.Join(opt, "FullNode", "FullNode.jar"),
	}
	for _, p := range candidates {
		if fileExists(p) {
			return p
		}
	}
	// Default install location even if missing (first write).
	return filepath.Join(opt, "FullNode.jar")
}

func (c *ClientUpdateController) patchTronConfIfNeeded() error {
	conf := filepath.Join(c.cfg.EtcDir, "config.conf")
	if !fileExists(conf) {
		conf = filepath.Join(c.cfg.EtcDir, "main_net_config.conf")
	}
	if !fileExists(conf) {
		return fmt.Errorf("tron conf not found under %s", c.cfg.EtcDir)
	}

	changed, err := ensureTronJSONRPCConfFile(conf, c.cfg.Env)
	if err != nil {
		return err
	}
	if changed {
		log.Printf("client_update: enabled jsonrpc in %s (restart java-tron to listen)", conf)
	}

	return nil
}

func (c *ClientUpdateController) handleGet(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "client_update": c.Snapshot()})
}

func (c *ClientUpdateController) handleRelease(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	rel, err := ResolveClientRelease(c.cfg.Network, c.cfg.Env)
	if err != nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "release": rel,
		"local": c.localClientVersion(),
	})
}

func (c *ClientUpdateController) handleCheck(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodGet {
		http.Error(w, "GET|POST", http.StatusMethodNotAllowed)
		return
	}
	st := c.Check()
	if c.ctrl != nil {
		c.ctrl.RequestRefresh()
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "client_update": st})
}

func (c *ClientUpdateController) handleApply(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	st, err := c.Apply()
	if err != nil {
		writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "error": err.Error(), "client_update": st})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "accepted": true, "client_update": st})
}

// applyClientUpdateToStatus merges client_update into collect status and
// overrides lifecycle/ui when an update is in progress.
func applyClientUpdateToStatus(st map[string]any, snap map[string]any) {
	if st == nil || snap == nil {
		return
	}
	st["client_update"] = snap
	if local, _ := snap["local"].(string); strings.TrimSpace(local) != "" {
		if _, has := st["client_version"]; !has || strings.TrimSpace(fmt.Sprint(st["client_version"])) == "" {
			st["client_version"] = local
		}
	}
	phase := strings.ToLower(strings.TrimSpace(fmt.Sprint(snap["phase"])))
	switch phase {
	case "updating", "starting":
		st["ui_phase"] = phase
		st["health"] = "maintenance"
		st["degraded"] = true
		detail := strings.TrimSpace(fmt.Sprint(snap["detail"]))
		label := "Updating client"
		if phase == "starting" {
			label = "Starting after client update"
		}
		if lc, ok := st["lifecycle"].(map[string]any); ok && lc != nil {
			lc["phase"] = phase
			lc["label"] = label
			lc["detail"] = detail
			lc["busy"] = true
			if pct, ok := snap["pct"]; ok {
				lc["pct"] = pct
			}
			st["lifecycle"] = lc
		}
	}
}
