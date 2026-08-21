package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

var (
	tonCelldbCacheRe    = regexp.MustCompile(`\s+--celldb-cache-size(?:=|\s+)\S+`)
	tonCelldbPreloadRe  = regexp.MustCompile(`\s+--celldb-preload-all\b`)
	tonCelldbInMemRe    = regexp.MustCompile(`\s+--celldb-in-memory\b`)
	tonCelldbDirectIORe = regexp.MustCompile(`\s+--celldb-direct-io\b`)
	tonFastStateSerRe   = regexp.MustCompile(`\s+--fast-state-serializer\b`)
	tonExecStartRe      = regexp.MustCompile(`(?m)^ExecStart\s*=\s*(.+)$`)
)

const tonValidatorUnitFile = "/etc/systemd/system/validator.service"

// Engine asks 1.5M; hard + kernel ceiling with headroom (4M / 8M).
const (
	tonValidatorNofile = 4194304
	tonNrOpen          = 8388608
)

// tonCelldbCacheBytes — liteserver dump-apply RAM cap.
// Default MyTonCtrl 1G is fine on small hosts; huge cache / preload-all OOMs
// validator-engine right after dump (seqno stays 0).
func tonCelldbCacheBytes(ramGiB float64) int64 {
	switch {
	case ramGiB < 16:
		return 1 << 30
	case ramGiB < 32:
		return 2 << 30
	case ramGiB < 64:
		return 4 << 30
	default:
		return 8 << 30
	}
}

func healTonValidatorExecStart(body string, cacheBytes int64) (string, bool) {
	if cacheBytes <= 0 {
		cacheBytes = 1 << 30
	}
	m := tonExecStartRe.FindStringSubmatch(body)
	if len(m) < 2 || !strings.Contains(m[1], "validator-engine") {
		return body, false
	}
	line := strings.TrimSpace(m[1])
	orig := line
	line = tonCelldbCacheRe.ReplaceAllString(line, "")
	line = tonCelldbPreloadRe.ReplaceAllString(line, "")
	line = tonCelldbInMemRe.ReplaceAllString(line, "")
	line = tonCelldbDirectIORe.ReplaceAllString(line, "")
	line = tonFastStateSerRe.ReplaceAllString(line, "")
	line = strings.TrimSpace(line)
	flag := fmt.Sprintf("--celldb-cache-size=%d", cacheBytes)
	if !strings.Contains(line, flag) {
		line = line + " " + flag
	}
	if line == orig {
		return body, false
	}
	return tonExecStartRe.ReplaceAllString(body, "ExecStart="+line), true
}

func healTonValidatorMemory() (bool, error) {
	return healTonValidatorMemoryCache(tonCelldbCacheBytes(float64(ramGB())))
}

func tonOOMCapPath(cfg Config) string {
	dir := filepath.Dir(strings.TrimSpace(cfg.StateFile))
	if dir == "" || dir == "." {
		dir = filepath.Join("/var/lib/rpcnode", "ton-"+strings.ToLower(strings.TrimSpace(cfg.Env)))
	}
	return filepath.Join(dir, "ton-oom-cap.json")
}

func tonOOMCapSticky(cfg Config) bool {
	doc := readJSONFile(tonOOMCapPath(cfg))
	return truthy(doc["force_1g"])
}

func markTonOOMCap(cfg Config) {
	path := tonOOMCapPath(cfg)
	_ = ensureDir(filepath.Dir(path))
	_ = writeJSONFile(path, map[string]any{
		"force_1g":   true,
		"updated_at": time.Now().UTC().Format(time.RFC3339),
	})
}

func tonCelldbHealCache(cfg Config) int64 {
	if tonValidatorOOM() || tonValidatorApplyCrashLoop() {
		markTonOOMCap(cfg)
		return 1 << 30
	}
	if tonOOMCapSticky(cfg) {
		return 1 << 30
	}
	return tonCelldbCacheBytes(float64(ramGB()))
}

func healTonValidatorMemoryCache(cache int64) (bool, error) {
	if cache <= 0 {
		cache = 1 << 30
	}
	// MemoryMax is a kill ceiling, not "RAM the node needs". 16G would OOM a
	// live mainnet validator (working set ≫ celldb cache). 85% of the host
	// is the product cap; the OOM fix is celldb 1G + no preload-all.
	_ = writeTonValidatorMemoryDropin("85%")
	_ = ensureTonValidatorNofile()
	anyChanged := false
	for _, path := range tonValidatorUnitPaths() {
		raw, err := os.ReadFile(path)
		if err != nil {
			if os.IsNotExist(err) {
				continue
			}
			return anyChanged, err
		}
		next, changed := healTonValidatorExecStart(string(raw), cache)
		if !changed {
			continue
		}
		if err := os.WriteFile(path, []byte(next), 0644); err != nil {
			return anyChanged, err
		}
		anyChanged = true
	}
	if anyChanged {
		_ = exec.Command("systemctl", "daemon-reload").Run()
	}
	return anyChanged, nil
}

// validator-engine change_maximize_rlimit(nofile, 1572864).
// Stock Ubuntu fs.nr_open=1048576 + LimitNOFILE=1048576 → PosixError EPERM setrlimit.
// Headroom: 4M NOFILE / 8M nr_open. Drop-in + sysctl only — ❌ do not recycle mid-apply.
func ensureTonValidatorNofile() error {
	dir := "/etc/systemd/system/validator.service.d"
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	path := filepath.Join(dir, "rpcnode-nofile.conf")
	body := fmt.Sprintf("[Service]\nLimitNOFILE=%d\n", tonValidatorNofile)
	prev, _ := os.ReadFile(path)
	needReload := string(prev) != body
	if needReload {
		if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
			return err
		}
	}
	_ = os.MkdirAll("/etc/sysctl.d", 0o755)
	sysPath := "/etc/sysctl.d/99-rpcnode-ton.conf"
	sysBody := fmt.Sprintf("fs.nr_open = %d\n", tonNrOpen)
	if prev, _ := os.ReadFile(sysPath); string(prev) != sysBody {
		_ = os.WriteFile(sysPath, []byte(sysBody), 0o644)
	}
	_ = exec.Command("sysctl", "-w", fmt.Sprintf("fs.nr_open=%d", tonNrOpen)).Run()
	if needReload {
		_ = exec.Command("systemctl", "daemon-reload").Run()
	}
	return nil
}

func writeTonValidatorMemoryDropin(memMax string) error {
	dir := "/etc/systemd/system/validator.service.d"
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	if strings.TrimSpace(memMax) == "" {
		memMax = "85%"
	}
	path := filepath.Join(dir, "rpcnode-memory.conf")
	body := `[Service]
MemoryAccounting=yes
MemoryMax=` + memMax + `
`
	prev, _ := os.ReadFile(path)
	if string(prev) == body {
		return nil
	}
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		return err
	}
	_ = exec.Command("systemctl", "daemon-reload").Run()
	return nil
}

// tonApplyCrashLoop — dump-end `systemctl restart validator` (bootstrap) sets
// NRestarts=1. That is not a crash. Sticky 1G cache only after OOM or a
// real restart storm (otherwise fat hosts apply at 1G for no reason).
func tonApplyCrashLoop(nRestarts int, result, active string, oom bool) bool {
	if oom {
		return true
	}
	if nRestarts >= 3 {
		return true
	}
	res := strings.ToLower(strings.TrimSpace(result))
	if strings.TrimSpace(active) == "activating" &&
		(strings.Contains(res, "oom") || res == "signal" || res == "core-dump") {
		return true
	}

	return false
}

func tonValidatorApplyCrashLoop() bool {
	p := probeSystemdUnit("validator")

	return tonApplyCrashLoop(p.NRestarts, p.Result, p.ActiveState, tonValidatorOOM())
}

func tonValidatorUnitPaths() []string {
	paths := []string{tonValidatorUnitFile}
	dir := "/etc/systemd/system/validator.service.d"
	ents, err := os.ReadDir(dir)
	if err != nil {
		return paths
	}
	for _, e := range ents {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".conf") {
			continue
		}
		paths = append(paths, filepath.Join(dir, e.Name()))
	}
	return paths
}

func tonValidatorDown() bool {
	p := probeSystemdUnit("validator")
	return p.ActiveState != "active" && p.ActiveState != "activating"
}

func recycleTonValidator() error {
	_ = exec.Command("systemctl", "kill", "-s", "SIGKILL", "--kill-who=main", "validator.service").Run()
	return nudgeTonValidatorStack()
}

func nudgeTonValidatorStack() error {
	_ = exec.Command("systemctl", "reset-failed", "validator.service").Run()
	out, err := exec.Command("systemctl", "start", "--no-block", "validator.service").CombinedOutput()
	_ = exec.Command("systemctl", "start", "--no-block", "mytoncore.service").Run()
	for _, u := range []string{"ton-http-api.service", "ton_http_api.service"} {
		_ = exec.Command("systemctl", "start", "--no-block", u).Run()
	}
	if err != nil {
		return fmt.Errorf("start validator: %v (%s)", err, strings.TrimSpace(string(out)))
	}
	return nil
}

// tonCatchupHonest — Synced / 99.9% only with an applied masterchain seqno.
// oos≈0 + seqno=0 is dump/start/OOM, not tip (log: oos=1 seqno=0 pct=99.9).
func tonCatchupHonest(oos float64, seqno int64, oom bool) bool {
	if oom || seqno <= 0 {
		return false
	}
	return oos >= 0 && oos <= tonOutOfSyncHealthySec
}

func tonValidatorOOM() bool {
	p := probeSystemdUnit("validator")
	if strings.Contains(strings.ToLower(p.Result), "oom") {
		return true
	}
	j := strings.ToLower(journalUnitSnippet("validator.service", 40))
	oom := strings.Contains(j, "oom killer") || strings.Contains(j, "oom-kill")
	if !oom {
		return false
	}
	// Journal line survives a healthy recycle — ignore only when stable at tip apply.
	if p.ActiveState == "active" && p.NRestarts == 0 {
		return false
	}
	return true
}
