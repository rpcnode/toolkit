package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
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
	cache := tonCelldbCacheBytes(float64(ramGB()))
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
	// Historical journal "OOM killer" stays after a successful recycle — ignore while up.
	if p.ActiveState == "active" || p.ActiveState == "activating" {
		return false
	}
	if strings.Contains(strings.ToLower(p.Result), "oom") {
		return true
	}
	j := strings.ToLower(journalUnitSnippet("validator.service", 40))
	return strings.Contains(j, "oom killer") || strings.Contains(j, "oom-kill")
}
