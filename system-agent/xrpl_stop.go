package main

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// xrplGracefulStop — close NuDB before anyone renames db/nudb.
// Ctrl+C / SIGKILL while files move → "NuBD close() failed: No such file or directory"
// and the next start is SHAMapStore state db error.
func xrplServerStopNoise(s string) bool {
	low := strings.ToLower(s)
	return strings.Contains(low, "no response from server") &&
		(strings.Contains(low, "another process") || strings.Contains(low, "xrpld server is running"))
}

func xrplUnitDown(cfg Config) bool {
	p := probeSystemdUnit(cfg.NodeService)
	return p.ActiveState != "active" && p.ActiveState != "activating"
}

func xrplGracefulStop(cfg Config) {
	if running, _ := xrplProcessRunning(cfg); !running {
		return
	}

	bin := resolveXRPLDBin(cfg)
	conf := filepath.Join(strings.TrimSpace(cfg.EtcDir), "xrpld.cfg")
	if bin != "" && fileExists(conf) {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		cmd := exec.CommandContext(ctx, bin, "--conf", conf, "server_stop")
		_ = cmd.Run()
		cancel()
	}

	unit := strings.TrimSpace(cfg.NodeService)
	if unit != "" {
		if !strings.HasSuffix(unit, ".service") {
			unit += ".service"
		}
		_ = exec.Command("systemctl", "kill", "-s", "SIGTERM", "--kill-who=main", unit).Run()
	}

	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		if running, _ := xrplProcessRunning(cfg); !running && !xrpldHoldsNuDB(cfg.DataDir) {
			return
		}
		time.Sleep(300 * time.Millisecond)
	}

	if unit != "" {
		_ = exec.Command("systemctl", "kill", "-s", "SIGKILL", "--kill-who=main", unit).Run()
	}
	time.Sleep(400 * time.Millisecond)
}

func xrpldHoldsNuDB(data string) bool {
	nudb := filepath.Join(strings.TrimSpace(data), "db", "nudb")
	if strings.TrimSpace(data) == "" || !fileExists(nudb) {
		return false
	}

	fds, err := filepath.Glob("/proc/[0-9]*/fd/*")
	if err != nil {
		return false
	}

	for _, fd := range fds {
		target, err := os.Readlink(fd)
		if err != nil {
			continue
		}
		if strings.Contains(target, nudb) {
			return true
		}
	}

	return false
}

func healXRPLUnitGracefulStop(cfg Config) bool {
	unit := strings.TrimSpace(cfg.NodeService)
	if unit == "" {
		return false
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}

	path := "/etc/systemd/system/" + unit
	raw, err := os.ReadFile(path)
	if err != nil {
		return false
	}

	bin := resolveXRPLDBin(cfg)
	conf := filepath.Join(strings.TrimSpace(cfg.EtcDir), "xrpld.cfg")
	if bin == "" || !fileExists(conf) {
		return false
	}

	s, ok := patchXRPLUnitGracefulStop(string(raw), bin, conf)
	if !ok {
		return false
	}

	if err := os.WriteFile(path, []byte(s), 0o644); err != nil {
		return false
	}

	_ = exec.Command("systemctl", "daemon-reload").Run()

	return true
}

func patchXRPLUnitGracefulStop(s, bin, conf string) (string, bool) {
	if strings.Contains(s, "ExecStop=-/usr/bin/timeout") && strings.Contains(s, "server_stop") {
		return s, false
	}

	line := fmt.Sprintf("ExecStop=-/usr/bin/timeout 15 %s --conf %s server_stop\n", bin, conf)
	if strings.Contains(s, "\nExecStop=") {
		out := make([]string, 0, 32)
		for _, ln := range strings.Split(s, "\n") {
			if strings.HasPrefix(strings.TrimSpace(ln), "ExecStop=") {
				out = append(out, strings.TrimRight(line, "\n"))
				continue
			}
			out = append(out, ln)
		}
		s = strings.Join(out, "\n")
	} else if i := strings.Index(s, "\nExecStart="); i >= 0 {
		rest := s[i+1:]
		nl := strings.Index(rest, "\n")
		if nl < 0 {
			return s, false
		}
		insertAt := i + 1 + nl + 1
		s = s[:insertAt] + line + s[insertAt:]
	} else {
		return s, false
	}

	if !strings.Contains(s, "TimeoutStopSec=") {
		s = strings.Replace(s, "RestartSec=10\n", "RestartSec=10\nTimeoutStopSec=45\n", 1)
	}

	return s, true
}

func xrplPrepareDatadirHeal(cfg Config) {
	if running, _ := xrplProcessRunning(cfg); !running && !xrpldHoldsNuDB(cfg.DataDir) {
		return
	}

	hostLogf("INFO", "system-agent", "start", "stop xrpld before NuDB heal (do not rotate under a live process)")
	xrplGracefulStop(cfg)
}
