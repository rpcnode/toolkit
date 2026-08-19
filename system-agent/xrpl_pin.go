package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

const (
	xrplFirstLedgerWaitFile = ".first-ledger-wait"
	xrplCatalogPinMarker    = ".xrpl-catalog-pin"
	xrplFirstLedgerStuck    = 12 * time.Minute
	xrplCatalogFallbackVer  = "3.3.0"
)

// xrplBuildIsBroken32 — XRPLF#7572 (3.2.0 never finalizes first ledger).
func xrplBuildIsBroken32(ver string) bool {
	v := strings.ToLower(strings.TrimSpace(ver))
	return strings.Contains(v, "3.2.0") || strings.Contains(v, "3.2.1")
}

func xrplVersionMatchesCatalog(local, catalog string) bool {
	l := normalizeClientVersion(local)
	c := normalizeClientVersion(catalog)
	return l != "" && c != "" && (l == c || strings.HasPrefix(l, c) || strings.Contains(local, c))
}

func xrplNoteFirstLedgerWait(data string, hasLedger bool) {
	data = strings.TrimSpace(data)
	if data == "" {
		return
	}
	p := filepath.Join(data, xrplFirstLedgerWaitFile)
	if hasLedger {
		_ = os.Remove(p)
		return
	}
	if fileExists(p) {
		return
	}
	_ = os.MkdirAll(data, 0o755)
	_ = os.WriteFile(p, []byte(time.Now().UTC().Format(time.RFC3339)+"\n"), 0o644)
}

func xrplFirstLedgerStuckFor(data string) bool {
	p := filepath.Join(strings.TrimSpace(data), xrplFirstLedgerWaitFile)
	raw, err := os.ReadFile(p)
	if err != nil {
		return false
	}
	ts, err := time.Parse(time.RFC3339, strings.TrimSpace(string(raw)))
	if err != nil {
		if info, err2 := os.Stat(p); err2 == nil {
			return time.Since(info.ModTime()) >= xrplFirstLedgerStuck
		}
		return false
	}
	return time.Since(ts) >= xrplFirstLedgerStuck
}

func xrplDebugLogHasInboundStall(data string) bool {
	lines := textLogTail(filepath.Join(strings.TrimSpace(data), "debug.log"), 80)
	return xrplInboundStallBlob(strings.Join(lines, "\n"))
}

func xrplInboundStallBlob(blob string) bool {
	blob = strings.ToLower(blob)
	if strings.TrimSpace(blob) == "" {
		return false
	}
	if strings.Contains(blob, "ledger data request timeout seq=0") {
		return true
	}
	if strings.Contains(blob, "inboundledger") && strings.Contains(blob, "timeout") {
		return true
	}

	return false
}

func xrplLiveBuildVersion(cfg Config) string {
	info := probeXRPLServerInfo(cfg)
	if strings.TrimSpace(info.BuildVer) != "" {
		return info.BuildVer
	}
	bin := resolveXRPLDBin(cfg)
	if bin == "" {
		return ""
	}
	out, _ := runCmd(4*time.Second, bin, "--version")
	return strings.TrimSpace(out)
}

func xrplAcquiringValidated(cfg Config) bool {
	return xrplInfoAcquiringValidated(probeXRPLServerInfo(cfg))
}

// xrplSkipFirstLedgerDisruptive — seq=0: do not stop/wipe/recycle until the
// first-ledger window expires. Pipeline interval is 2s; latch acquiring
// needs uptime≥20 + peers. A recycle resets both → never latches.
// Process down still skips wipe (unit-down path will start xrpld).
func xrplSkipFirstLedgerDisruptive(waitFileStuck, acquiring bool) bool {
	if acquiring {
		return true
	}

	return !waitFileStuck
}

func xrplShouldSkipFirstLedgerDisruptive(cfg Config) bool {
	return xrplSkipFirstLedgerDisruptive(
		xrplFirstLedgerStuckFor(cfg.DataDir),
		xrplAcquiringValidated(cfg),
	)
}

func xrplInfoAcquiringValidated(info xrplServerInfo) bool {
	if !info.OK || info.Seq > 0 {
		return false
	}
	if info.Uptime < 20 {
		return false
	}

	return info.Peers > 0 || info.Proposers > 0
}

func xrplFirstLedgerIsStuck(cfg Config) bool {
	if xrplAcquiringValidated(cfg) {
		return false
	}
	if xrplFirstLedgerStuckFor(cfg.DataDir) || xrplDebugLogHasInboundStall(cfg.DataDir) {
		return true
	}
	info := probeXRPLServerInfo(cfg)
	if info.OK && info.Seq <= 0 && info.Uptime >= int64(xrplFirstLedgerStuck.Seconds()) {
		return true
	}

	return false
}

func xrplDebFromCatalog(env string) (pkg, ver string) {
	pkg, ver = "xrpld", xrplCatalogFallbackVer
	rel, err := fetchVendoredClientRelease("xrpl", env)
	if err != nil || strings.TrimSpace(rel.Version) == "" {
		return pkg, ver
	}
	ver = strings.TrimSpace(rel.Version)
	u := strings.ToLower(rel.ArtifactURL)
	if strings.Contains(u, "rippled") && !strings.Contains(u, "xrpld") {
		pkg = "rippled"
	}
	return pkg, ver
}

func healXRPLFirstLedgerBinary(cfg Config) (bool, error) {
	pkg, catalogVer := xrplDebFromCatalog(cfg.Env)
	local := xrplLiveBuildVersion(cfg)
	if xrplVersionMatchesCatalog(local, catalogVer) {
		return false, nil
	}
	if !xrplBuildIsBroken32(local) && !xrplFirstLedgerIsStuck(cfg) {
		return false, nil
	}
	marker := filepath.Join(cfg.DataDir, xrplCatalogPinMarker)
	if raw, err := os.ReadFile(marker); err == nil && strings.Contains(string(raw), catalogVer) &&
		xrplVersionMatchesCatalog(local, catalogVer) {
		return false, nil
	}

	if err := installXRPLCatalogDeb(pkg, catalogVer); err != nil {
		return false, err
	}
	_ = os.WriteFile(marker, []byte(pkg+" "+catalogVer+" from install/clients catalog\n"), 0o644)
	if healXRPLUnitExecStart(cfg) {
		_ = exec.Command("systemctl", "daemon-reload").Run()
	}
	return true, nil
}

func installXRPLCatalogDeb(pkg, ver string) error {
	if _, err := exec.LookPath("apt-get"); err != nil {
		return fmt.Errorf("apt-get required for catalog %s %s: %w", pkg, ver, err)
	}
	pref := fmt.Sprintf("Package: %s\nPin: version %s*\nPin-Priority: 1001\n", pkg, ver)
	if err := os.WriteFile("/etc/apt/preferences.d/rpcnode-xrpl", []byte(pref), 0o644); err != nil {
		return err
	}
	cands := []string{pkg + "=" + ver + "-1", pkg + "=" + ver, pkg}
	var lastOut []byte
	var lastErr error
	for _, spec := range cands {
		cmd := exec.Command("apt-get", "-y", "-o", "Dpkg::Options::=--force-confold", "install", spec)
		cmd.Env = append(os.Environ(), "DEBIAN_FRONTEND=noninteractive")
		out, err := cmd.CombinedOutput()
		if err == nil {
			_ = exec.Command("apt-mark", "hold", pkg).Run()
			return nil
		}
		lastOut, lastErr = out, err
	}
	return fmt.Errorf("apt-get install catalog %s %s: %v (%s)",
		pkg, ver, lastErr, strings.TrimSpace(string(lastOut)))
}

func healXRPLUnitExecStart(cfg Config) bool {
	unit := strings.TrimSpace(cfg.NodeService)
	if unit == "" {
		return false
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	bin := resolveXRPLDBin(cfg)
	conf := filepath.Join(strings.TrimSpace(cfg.EtcDir), "xrpld.cfg")
	if bin == "" || !fileExists(conf) {
		return false
	}
	path := "/etc/systemd/system/" + unit
	raw, err := os.ReadFile(path)
	if err != nil {
		return false
	}
	want := "ExecStart=" + bin + " --conf " + conf
	s := string(raw)
	if strings.Contains(s, want) {
		return false
	}
	out := make([]string, 0, 32)
	changed := false
	for _, ln := range strings.Split(s, "\n") {
		if strings.HasPrefix(strings.TrimSpace(ln), "ExecStart=") {
			out = append(out, want)
			changed = true
			continue
		}
		out = append(out, ln)
	}
	if !changed {
		return false
	}
	if err := os.WriteFile(path, []byte(strings.Join(out, "\n")), 0o644); err != nil {
		return false
	}
	return true
}
