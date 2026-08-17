package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// systemdUnitProbe — live unit health for crash-loop / exit-code detection.
type systemdUnitProbe struct {
	ActiveState string
	Result      string
	NRestarts   int
	Failed      bool
}

func bitcoinConfPath(cfg Config) string {
	etc := strings.TrimSpace(cfg.EtcDir)
	if etc == "" {
		etc = LookupNetworkProfile(cfg.Network, cfg.Env).EtcPath
	}
	if etc == "" {
		etc = fmt.Sprintf("/etc/bitcoin/%s", normalizeEnvName(cfg.Env))
	}
	return filepath.Join(etc, "bitcoin.conf")
}

func normalizeEnvName(env string) string {
	e := strings.ToLower(strings.TrimSpace(env))
	if e == "" {
		return "mainnet"
	}
	return e
}

// isBitcoinRegtest — local chain; bitcoind may set initialblockdownload at height 0,
// but that is not public-network IBD/sync progress for the panel.
func isBitcoinRegtest(env string) bool {
	return normalizeEnvName(env) == "regtest"
}

func probeSystemdUnit(unit string) systemdUnitProbe {
	unit = strings.TrimSuffix(strings.TrimSpace(unit), ".service")
	if unit == "" {
		return systemdUnitProbe{}
	}
	name := unit + ".service"
	out, _ := runCmd(3*time.Second, "systemctl", "show", name,
		"-p", "ActiveState", "-p", "Result", "-p", "NRestarts", "--no-pager")
	p := systemdUnitProbe{Failed: systemctlFailed(unit)}
	for _, ln := range strings.Split(out, "\n") {
		ln = strings.TrimSpace(ln)
		key, val, ok := strings.Cut(ln, "=")
		if !ok {
			continue
		}
		switch key {
		case "ActiveState":
			p.ActiveState = val
		case "Result":
			p.Result = val
		case "NRestarts":
			p.NRestarts, _ = strconv.Atoi(val)
		}
	}
	if p.ActiveState == "" {
		p.ActiveState = systemctlActive(unit)
	}
	return p
}

// journalUnitGrepSnippet — last matching journal lines (regex). Use when spam
// (peer version, gossip) pushes progress lines out of a plain -n tail.
func journalUnitGrepSnippet(unit string, n int, grepRe string) string {
	unit = strings.TrimSpace(unit)
	grepRe = strings.TrimSpace(grepRe)
	if unit == "" || grepRe == "" {
		return ""
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	if n <= 0 {
		n = 40
	}
	out, err := runCmd(6*time.Second, "journalctl", "-u", unit, "--grep", grepRe, "-n", strconv.Itoa(n),
		"--no-pager", "-o", "cat")
	if err != nil || strings.TrimSpace(out) == "" {
		return ""
	}
	lines := []string{}
	for _, ln := range strings.Split(out, "\n") {
		ln = strings.TrimSpace(ln)
		if ln == "" {
			continue
		}
		lines = append(lines, ln)
	}
	if len(lines) == 0 {
		return ""
	}

	return strings.Join(lines, "\n")
}

func journalUnitSnippet(unit string, n int) string {
	unit = strings.TrimSpace(unit)
	if unit == "" {
		return ""
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	if n <= 0 {
		n = 12
	}
	// Prefer bitcoind stderr over bare systemd "Failed with result" lines.
	out, _ := runCmd(4*time.Second, "journalctl", "-u", unit, "-n", strconv.Itoa(n),
		"--no-pager", "-o", "cat")
	return journalUnitSnippetFrom(out, unit)
}

func journalUnitSnippetSince(unit string, since time.Time, n int) string {
	unit = strings.TrimSpace(unit)
	if unit == "" || since.IsZero() {
		return journalUnitSnippet(unit, n)
	}
	if !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	if n <= 0 {
		n = 12
	}

	out, _ := runCmd(4*time.Second, "journalctl", "-u", unit,
		"--since", since.UTC().Format("2006-01-02 15:04:05"),
		"-n", strconv.Itoa(n), "--no-pager", "-o", "cat")

	return journalUnitSnippetFrom(out, unit)
}

func journalUnitSnippetFrom(out, unit string) string {
	lines := []string{}
	for _, ln := range strings.Split(out, "\n") {
		ln = strings.TrimSpace(ln)
		if ln == "" {
			continue
		}
		lines = append(lines, ln)
	}
	show, _ := runCmd(3*time.Second, "systemctl", "show", unit,
		"-p", "ExecMainStatus", "-p", "ExecMainCode", "-p", "StatusText",
		"-p", "FragmentPath", "--no-pager")
	execHint := ""
	for _, ln := range strings.Split(show, "\n") {
		ln = strings.TrimSpace(ln)
		if k, v, ok := strings.Cut(ln, "="); ok && v != "" && v != "0" {
			switch k {
			case "ExecMainStatus", "ExecMainCode", "StatusText":
				if execHint != "" {
					execHint += " "
				}
				execHint += k + "=" + v
			case "FragmentPath":
				if execHint != "" {
					execHint += " "
				}
				execHint += "unit=" + v
			}
		}
	}
	if len(lines) == 0 {
		// Never-started units only expose FragmentPath → "unit=/etc/.../*.service".
		// That is NOT a start failure — returning it caused Start error flashes after Confirm ports.
		if journalHintIsUnitPathOnly(execHint) {
			return ""
		}
		return execHint
	}
	// Prefer the last Error:/Failed line that is not generic systemd Result noise.
	for i := len(lines) - 1; i >= 0; i-- {
		low := strings.ToLower(lines[i])
		if journalLineIsSystemdNoise(low) {
			continue
		}
		if strings.Contains(low, "error") || strings.Contains(low, "failed") ||
			strings.Contains(low, "could not") || strings.Contains(low, "couldn't") ||
			strings.Contains(low, "fatal") ||
			strings.Contains(low, "oom") || strings.Contains(low, "cannot") ||
			strings.Contains(low, "deprecat") || strings.Contains(low, "end-of-support") ||
			strings.Contains(low, "end of support") || strings.Contains(low, "refuse") ||
			strings.Contains(low, "i-am-aware") {
			s := lines[i]
			if execHint != "" && !journalHintIsUnitPathOnly(execHint) {
				s = s + " · " + execHint
			}
			if len(s) > 320 {
				s = s[:320] + "…"
			}
			return s
		}
	}
	useful := make([]string, 0, 3)
	for i := len(lines) - 1; i >= 0 && len(useful) < 3; i-- {
		if journalLineIsSystemdNoise(strings.ToLower(lines[i])) {
			continue
		}
		useful = append([]string{lines[i]}, useful...)
	}
	if len(useful) == 0 {
		if journalHintIsUnitPathOnly(execHint) {
			return ""
		}
		return execHint
	}
	s := strings.Join(useful, " · ")
	if execHint != "" && !journalHintIsUnitPathOnly(execHint) {
		s = s + " · " + execHint
	}
	if len(s) > 320 {
		s = s[:320] + "…"
	}
	return s
}

// journalLineIsSystemdNoise — bare unit exit/result lines without the app reason.
func journalLineIsSystemdNoise(low string) bool {
	low = strings.TrimSpace(low)
	if low == "" {
		return true
	}
	if strings.Contains(low, "failed with result") {
		return true
	}
	if strings.Contains(low, "main process exited") {
		return true
	}
	if strings.Contains(low, "control process exited") {
		return true
	}
	if strings.Contains(low, "consumed") && strings.Contains(low, "cpu time") {
		return true
	}
	if strings.HasPrefix(low, "started ") || strings.HasPrefix(low, "stopped ") {
		return true
	}
	if xrplServerStopNoise(low) {
		return true
	}
	return false
}

// journalHintIsUnitPathOnly — true for bare FragmentPath ("unit=/path/foo.service").
func journalHintIsUnitPathOnly(hint string) bool {
	h := strings.TrimSpace(hint)
	if h == "" {
		return true
	}
	if strings.HasPrefix(h, "unit=/") && !strings.ContainsAny(strings.TrimPrefix(h, "unit="), " \t") {
		return true
	}
	// "unit=/a unit=/b" still noise
	for _, p := range strings.Fields(h) {
		if !strings.HasPrefix(p, "unit=") {
			return false
		}
	}
	return true
}

// stripUnitPathNoise removes FragmentPath-only tails from pipeline/journal errors.
func stripUnitPathNoise(s string) string {
	s = strings.TrimSpace(s)
	if s == "" || journalHintIsUnitPathOnly(s) {
		return ""
	}
	for _, sep := range []string{" — unit=/", " · unit=/", " - unit=/"} {
		if i := strings.Index(s, sep); i >= 0 {
			s = strings.TrimSpace(s[:i])
			break
		}
	}
	if journalHintIsUnitPathOnly(s) {
		return ""
	}
	return s
}

// memTotalMB reads MemTotal from /proc/meminfo (0 if unavailable — e.g. non-Linux).
func memTotalMB() int {
	b, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return 0
	}

	for _, ln := range strings.Split(string(b), "\n") {
		if !strings.HasPrefix(ln, "MemTotal:") {
			continue
		}

		fields := strings.Fields(ln)
		if len(fields) < 2 {
			return 0
		}

		kb, err := strconv.Atoi(fields[1])
		if err != nil {
			return 0
		}

		return kb / 1024
	}

	return 0
}

// bitcoinDBCacheMB — ~25% RAM, floor 2048 MiB, cap 8192; default 4096 when RAM unknown.
// Tradeoff: higher speeds IBD/RPC reads; too high starves OS / bitcoind other arenas.
func bitcoinDBCacheMB() int {
	mem := memTotalMB()
	if mem <= 0 {
		return 4096
	}

	c := mem / 4
	if c < 2048 {
		c = 2048
	}
	if c > 8192 {
		c = 8192
	}

	return c
}

// bitcoinCoreDatadirSetting — bitcoin.conf datadir=. Core nests regtest/signet/testnet4
// under datadir; profile DataPath is the final chain dir (/data/bitcoin/regtest).
func bitcoinCoreDatadirSetting(dataPath, env string) string {
	dataPath = strings.TrimRight(strings.TrimSpace(dataPath), "/")
	if dataPath == "" {
		return dataPath
	}
	switch normalizeEnvName(env) {
	case "regtest", "signet", "testnet4", "testnet", "testnet3":
		parent := filepath.Dir(dataPath)
		if parent != "" && parent != "." && parent != "/" {
			return parent
		}
	}
	return dataPath
}

// bitcoinDBCacheMBForEnv — non-mainnet stays small when sharing a host with mainnet IBD.
func bitcoinDBCacheMBForEnv(env string) int {
	switch normalizeEnvName(env) {
	case "regtest":
		return 256
	case "signet", "testnet4", "testnet":
		return 512
	default:
		return bitcoinDBCacheMB()
	}
}

func isAgentGeneratedBitcoinConf(body []byte) bool {
	s := string(body)

	return strings.Contains(s, "Generated by rpcnode") ||
		strings.Contains(s, "Generated by bitcoin-api-agent")
}

func preserveBitcoinRPCAuthLines(old []byte) []string {
	var out []string
	for _, ln := range strings.Split(string(old), "\n") {
		t := strings.TrimSpace(ln)
		if strings.HasPrefix(t, "rpcauth=") {
			out = append(out, t)
		}
	}

	return out
}

// renderBitcoinConfBody — full node + txindex + prod RPC queue defaults for RpcNode.
func renderBitcoinConfBody(cfg Config) string {
	return renderBitcoinConfBodyWithCache(cfg, bitcoinDBCacheMBForEnv(cfg.Env))
}

func renderBitcoinConfBodyWithCache(cfg Config, dbcacheMB int) string {
	prof := LookupNetworkProfile(cfg.Network, cfg.Env)
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		data = prof.DataPath
	}
	rpcPort := cfg.UpstreamPort
	if rpcPort <= 0 {
		rpcPort = prof.DefaultNodeHTTP
	}
	p2p := cfg.P2PPort
	if p2p <= 0 {
		p2p = prof.DefaultP2PPort
	}
	if dbcacheMB <= 0 {
		dbcacheMB = 4096
	}

	var b strings.Builder
	b.WriteString("# Generated by rpcnode system-agent — do not edit by hand\n")
	b.WriteString("# Profile: full node + txindex (prune forbidden). txindex ≈ extra disk vs chain-only.\n")
	b.WriteString("server=1\n")
	b.WriteString("txindex=1\n")
	b.WriteString("prune=0\n")
	b.WriteString("disablewallet=1\n")
	b.WriteString("daemon=0\n")
	fmt.Fprintf(&b, "datadir=%s\n", bitcoinCoreDatadirSetting(data, cfg.Env))
	fmt.Fprintf(&b, "dbcache=%d\n", dbcacheMB)
	// High-load from day one: thousands concurrent via Go proxy (Core defaults 4/16 are unusable).
	b.WriteString("rpcthreads=64\n")
	b.WriteString("rpcworkqueue=1024\n")
	b.WriteString("maxconnections=125\n")
	b.WriteString("rest=1\n")
	if prof.ChainFlag != "" {
		b.WriteString(prof.ChainFlag + "\n")
	}
	// Core: port/rpcport only apply on regtest/signet/… inside their INI section.
	if sec := bitcoinConfNetworkSection(cfg.Env); sec != "" {
		b.WriteString("\n[" + sec + "]\n")
	}
	fmt.Fprintf(&b, "port=%d\n", p2p)
	fmt.Fprintf(&b, "rpcport=%d\n", rpcPort)
	b.WriteString("rpcbind=127.0.0.1\n")
	b.WriteString("rpcallowip=127.0.0.1\n")
	if prof.ZMQRawBlock > 0 {
		fmt.Fprintf(&b, "zmqpubrawblock=tcp://127.0.0.1:%d\n", prof.ZMQRawBlock)
	}
	if prof.ZMQRawTx > 0 {
		fmt.Fprintf(&b, "zmqpubrawtx=tcp://127.0.0.1:%d\n", prof.ZMQRawTx)
	}

	return b.String()
}

func bitcoinConfNetworkSection(env string) string {
	switch normalizeEnvName(env) {
	case "regtest":
		return "regtest"
	case "signet":
		return "signet"
	case "testnet4":
		return "testnet4"
	case "testnet", "testnet3":
		return "test"
	default:
		return ""
	}
}

// ensureBitcoinConf creates etc/datadir + bitcoin.conf (0644, nodeop-owned).
// Missing or agent-generated confs are (re)written so RPC tuning upgrades apply; hand-edited left alone.
func ensureBitcoinConf(cfg Config) (string, error) {
	prof := LookupNetworkProfile(cfg.Network, cfg.Env)
	etc := strings.TrimSpace(cfg.EtcDir)
	if etc == "" {
		etc = prof.EtcPath
	}
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		data = prof.DataPath
	}
	coreData := bitcoinCoreDatadirSetting(data, cfg.Env)
	for _, d := range []string{etc, data, coreData} {
		if d == "" {
			continue
		}
		if err := os.MkdirAll(d, 0o755); err != nil {
			return "", fmt.Errorf("mkdir %s: %w", d, err)
		}
	}

	confPath := filepath.Join(etc, "bitcoin.conf")
	body := renderBitcoinConfBody(cfg)
	write := !fileExists(confPath)

	if fileExists(confPath) {
		old, err := os.ReadFile(confPath)
		if err == nil && isAgentGeneratedBitcoinConf(old) {
			write = true
			for _, auth := range preserveBitcoinRPCAuthLines(old) {
				if !strings.Contains(body, auth) {
					body = strings.TrimRight(body, "\n") + "\n" + auth + "\n"
				}
			}
		}
	}

	if write {
		if err := os.WriteFile(confPath, []byte(body), 0o644); err != nil {
			return "", fmt.Errorf("write %s: %w", confPath, err)
		}
	}

	_ = exec.Command("chown", "-R", "nodeop:nodeop", etc, data).Run()
	_ = os.Chmod(confPath, 0o644)

	return confPath, nil
}

func resolveBitcoindBin(cfg Config) string {
	opt := strings.TrimSpace(cfg.OptDir)
	for _, cand := range []string{
		filepath.Join(opt, "bin", "bitcoind"),
		"/opt/bitcoin/bin/bitcoind",
		"/usr/local/bin/bitcoind",
	} {
		if fileExists(cand) {
			return cand
		}
	}
	if p, err := exec.LookPath("bitcoind"); err == nil && p != "" {
		return p
	}
	return ""
}

// bitcoinStartFailureDetail — human detail for lifecycle start=error (never "warming up").
func bitcoinStartFailureDetail(cfg Config, procOK bool) (detail string, bad bool) {
	confPath := bitcoinConfPath(cfg)
	unit := cfg.NodeService
	probe := probeSystemdUnit(unit)
	snippet := journalUnitSnippet(unit, 16)

	confMissing := !fileExists(confPath)
	resultBad := probe.Result == "exit-code" || probe.Result == "signal" ||
		probe.Result == "resources" || probe.Result == "timeout"
	// Any exit-code + restart without a live process is a crash-loop (not "warming").
	crashLoop := !procOK && resultBad && (probe.NRestarts >= 1 || probe.ActiveState == "activating")
	failed := probe.Failed || probe.ActiveState == "failed"

	switch {
	case confMissing:
		bad = true
		detail = fmt.Sprintf("bitcoin.conf missing: %s", confPath)
		if snippet != "" {
			detail = detail + " — " + snippet
		}
	case failed || crashLoop:
		bad = true
		if snippet != "" {
			detail = fmt.Sprintf("bitcoin-%s unit failed (Result=%s, restarts=%d): %s",
				normalizeEnvName(cfg.Env), probe.Result, probe.NRestarts, snippet)
		} else {
			detail = fmt.Sprintf("bitcoin-%s unit failed (Result=%s, restarts=%d, state=%s)",
				normalizeEnvName(cfg.Env), probe.Result, probe.NRestarts, probe.ActiveState)
		}
	case !procOK && probe.ActiveState == "activating" && resultBad:
		bad = true
		detail = fmt.Sprintf("bitcoind crash-loop (Result=%s, restarts=%d)", probe.Result, probe.NRestarts)
		if snippet != "" {
			detail = detail + ": " + snippet
		}
	}
	return detail, bad
}

// bitcoinNodeReallyUp — true only when process is up or unit is stably active (not crash-loop).
func bitcoinNodeReallyUp(cfg Config, nodeState string, procOK bool) bool {
	if procOK {
		return true
	}
	if _, bad := bitcoinStartFailureDetail(cfg, procOK); bad {
		return false
	}
	return nodeState == "active" || nodeState == "activating"
}
