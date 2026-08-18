package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

func tronPaths(env string) (etc, data, opt, conf, jar, unit string) {
	env = normalizeEnv(env)
	etc = filepath.Join("/etc/tron", env)
	data = filepath.Join("/data/tron", env)
	opt = filepath.Join("/opt/tron", env)
	conf = filepath.Join(etc, "main_net_config.conf")
	jar = filepath.Join(opt, "FullNode.jar")
	unit = fmt.Sprintf("tron-%s.service", env)
	return
}

// systemAgentListenPort — loopback control API. Never 8090–8093 (legacy panel / java-tron
// default solidity+PBFT HTTP). TRON uses ports.sh 2909x; bitcoin keeps 819x.
func systemAgentListenPort(network, env string) int {
	env = normalizeEnv(env)
	switch normalizeNetwork(network) {
	case "bitcoin":
		return bitcoinSysListen(env)
	case "solana":
		return solanaSysListen(env)
	case "ethereum":
		return ethereumSysListen(env)
	case "bsc":
		return bscSysListen(env)
	case "hyperliquid":
		return hyperliquidSysListen(env)
	case "arb":
		return arbSysListen(env)
	case "robinhood":
		return robinhoodSysListen(env)
	case "optimism":
		return optimismSysListen(env)
	case "base":
		return baseSysListen(env)
	case "xrpl":
		return xrplSysListen(env)
	case "doge":
		return dogeSysListen(env)
	case "ltc", "dash", "bch":
		return coreLikeSysListen(network, env)
	case "cardano":
		return cardanoSysListen(env)
	case "stellar":
		return stellarSysListen(env)
	case "ton":
		return tonSysListen(env)
	case "etc":
		return etcSysListen(env)
	case "zcash":
		return zcashSysListen(env)
	case "sui":
		return suiSysListen(env)
	case "aptos":
		return aptosSysListen(env)
	case "avalanche":
		return avalancheSysListen(env)
	}
	switch env {
	case "nile":
		return 29091
	case "shasta":
		return 29092
	default:
		return 29090
	}
}

func systemAgentListenAddr(network, env string) string {
	return fmt.Sprintf("127.0.0.1:%d", systemAgentListenPort(network, env))
}

func systemAgentURL(network, env string) string {
	return "http://" + systemAgentListenAddr(network, env)
}

// migrateHostBootstrapSystemAgent — host units must listen on :29090, never :8091–:8093
// (panel / java-tron solidity). Safe on bitcoin-only hosts (no java-tron).
func migrateHostBootstrapSystemAgent() (changed bool) {
	wantListen := "127.0.0.1:29090"
	wantURL := "http://127.0.0.1:29090"

	// Legacy EnvironmentFile often pins LISTEN=:8091 and wins at runtime depending
	// on unit ordering / process restarts — neutralize it.
	for _, envPath := range []string{
		"/etc/rpcnode/host.env",
		"/etc/tron/mainnet/toolkit.env",
	} {
		if !fileExists(envPath) {
			continue
		}
		b, err := os.ReadFile(envPath)
		if err != nil {
			continue
		}
		s := string(b)
		orig := s
		reListen := regexp.MustCompile(`(?m)^TRON_SYSTEM_AGENT_LISTEN=.*$`)
		reURL := regexp.MustCompile(`(?m)^TRON_SYSTEM_AGENT_URL=.*$`)
		if legacySystemAgentPort(s) || !strings.Contains(s, wantListen) {
			if reListen.MatchString(s) {
				s = reListen.ReplaceAllString(s, "TRON_SYSTEM_AGENT_LISTEN="+wantListen)
			} else {
				s = strings.TrimRight(s, "\n") + "\nTRON_SYSTEM_AGENT_LISTEN=" + wantListen + "\n"
			}
			if reURL.MatchString(s) {
				s = reURL.ReplaceAllString(s, "TRON_SYSTEM_AGENT_URL="+wantURL)
			} else {
				s = strings.TrimRight(s, "\n") + "\nTRON_SYSTEM_AGENT_URL=" + wantURL + "\n"
			}
		}
		if s != orig {
			_ = os.WriteFile(envPath, []byte(s), 0o600)
			changed = true
		}
	}

	for _, unit := range []string{
		"/etc/systemd/system/rpcnode-system-agent.service",
		"/etc/systemd/system/rpcnode-api-agent.service",
	} {
		before, _ := os.ReadFile(unit)
		rewriteUnitSystemAgentPort(unit, wantListen, wantURL)
		if b, err := os.ReadFile(unit); err == nil {
			s := string(b)
			orig := s
			// Prefer host.env over leftover /etc/tron/mainnet/toolkit.env.
			s = strings.ReplaceAll(s, "EnvironmentFile=-/etc/tron/mainnet/toolkit.env",
				"EnvironmentFile=-/etc/rpcnode/host.env")
			if s != orig {
				_ = os.WriteFile(unit, []byte(s), 0o644)
			}
		}
		after, _ := os.ReadFile(unit)
		if string(before) != string(after) {
			changed = true
		}
	}
	if !changed {
		return false
	}
	_ = exec.Command("systemctl", "daemon-reload").Run()
	// Restart only if host system-agent is enabled/active — do not wake disabled bootstrap.
	if out, _ := exec.Command("systemctl", "is-active", "rpcnode-system-agent.service").CombinedOutput(); strings.TrimSpace(string(out)) == "active" {
		_ = exec.Command("systemctl", "restart", "rpcnode-system-agent.service").Run()
	}
	return true
}

// migrateSystemAgentLoopback rewrites toolkit.env + systemd unit drops off legacy
// :8090–:8093 onto canonical 2909x (TRON) so java-tron HTTP can never collide.
func migrateSystemAgentLoopback(envPath, network, env string) {
	wantListen := systemAgentListenAddr(network, env)
	wantURL := systemAgentURL(network, env)

	if fileExists(envPath) {
		if b, err := os.ReadFile(envPath); err == nil {
			lines := strings.Split(string(b), "\n")
			changed := false
			for i, ln := range lines {
				trim := strings.TrimSpace(ln)
				switch {
				case strings.HasPrefix(trim, "TRON_SYSTEM_AGENT_LISTEN="):
					cur := strings.TrimPrefix(trim, "TRON_SYSTEM_AGENT_LISTEN=")
					if cur != wantListen && legacySystemAgentPort(cur) {
						lines[i] = "TRON_SYSTEM_AGENT_LISTEN=" + wantListen
						changed = true
					}
				case strings.HasPrefix(trim, "TRON_SYSTEM_AGENT_URL="):
					cur := strings.TrimPrefix(trim, "TRON_SYSTEM_AGENT_URL=")
					if cur != wantURL && legacySystemAgentPort(cur) {
						lines[i] = "TRON_SYSTEM_AGENT_URL=" + wantURL
						changed = true
					}
				}
			}
			if changed {
				_ = os.WriteFile(envPath, []byte(strings.Join(lines, "\n")), 0o600)
			}
		}
	}

	// Unit files may hardcode Environment=…:8091 (overrides EnvironmentFile).
	envN := normalizeEnv(env)
	for _, unit := range []string{
		"/etc/systemd/system/rpcnode-system-agent.service",
		"/etc/systemd/system/rpcnode-api-agent.service",
		fmt.Sprintf("/etc/systemd/system/rpcnode-system-agent-tron-%s.service", envN),
		fmt.Sprintf("/etc/systemd/system/rpcnode-api-agent-tron-%s.service", envN),
		// Legacy env-only (heal while file still on disk).
		fmt.Sprintf("/etc/systemd/system/rpcnode-system-agent-%s.service", envN),
		fmt.Sprintf("/etc/systemd/system/rpcnode-api-agent-%s.service", envN),
	} {
		rewriteUnitSystemAgentPort(unit, wantListen, wantURL)
	}
}

func legacySystemAgentPort(v string) bool {
	for _, p := range []string{":8090", ":8091", ":8092", ":8093"} {
		if strings.Contains(v, p) {
			return true
		}
	}
	return false
}

func rewriteUnitSystemAgentPort(unitPath, wantListen, wantURL string) {
	b, err := os.ReadFile(unitPath)
	if err != nil {
		return
	}
	s := string(b)
	orig := s
	reListen := regexp.MustCompile(`TRON_SYSTEM_AGENT_LISTEN=127\.0\.0\.1:809[0-3]`)
	reURL := regexp.MustCompile(`TRON_SYSTEM_AGENT_URL=http://127\.0\.0\.1:809[0-3]`)
	s = reListen.ReplaceAllString(s, "TRON_SYSTEM_AGENT_LISTEN="+wantListen)
	s = reURL.ReplaceAllString(s, "TRON_SYSTEM_AGENT_URL="+wantURL)
	if s != orig {
		_ = os.WriteFile(unitPath, []byte(s), 0o644)
		_ = exec.Command("systemctl", "daemon-reload").Run()
	}
}

func isTronNodeUnitStub(unitPath string) bool {
	b, err := os.ReadFile(unitPath)
	if err != nil {
		return true
	}
	s := string(b)
	return strings.Contains(s, "ExecStart=/bin/false") ||
		strings.Contains(s, "provisioned stub")
}

// javaMajorFromVersionOutput parses `java -version` stderr/stdout.
// "1.8.0_442" → 8; "17.0.15" → 17. 0 if unknown.
func javaMajorFromVersionOutput(out string) int {
	re := regexp.MustCompile(`version\s+"([0-9]+)(?:\.([0-9]+))?`)
	m := re.FindStringSubmatch(out)
	if len(m) < 2 {
		return 0
	}
	major, err := strconv.Atoi(m[1])
	if err != nil {
		return 0
	}
	if major == 1 && len(m) > 2 && m[2] != "" {
		minor, err := strconv.Atoi(m[2])
		if err != nil {
			return 0
		}
		return minor
	}
	return major
}

func javaBinMajor(bin string) int {
	bin = strings.TrimSpace(bin)
	if bin == "" || !fileExists(bin) {
		return 0
	}
	out, err := exec.Command(bin, "-version").CombinedOutput()
	if len(out) == 0 && err != nil {
		return 0
	}
	return javaMajorFromVersionOutput(string(out))
}

func isJava8Bin(bin string) bool {
	return javaBinMajor(bin) == 8
}

func java8CandidatePaths() []string {
	known := []string{
		"/usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java",
		"/usr/lib/jvm/java-8-openjdk-amd64/bin/java",
		"/usr/lib/jvm/java-8-openjdk-arm64/jre/bin/java",
		"/usr/lib/jvm/java-8-openjdk-arm64/bin/java",
		"/usr/lib/jvm/java-1.8.0-openjdk-amd64/bin/java",
		"/usr/lib/jvm/temurin-8-jre/bin/java",
		"/usr/lib/jvm/temurin-8-jdk/bin/java",
		"/usr/lib/jvm/zulu8/bin/java",
	}
	globs, _ := filepath.Glob("/usr/lib/jvm/*/bin/java")
	jreGlobs, _ := filepath.Glob("/usr/lib/jvm/*/jre/bin/java")
	return uniqStrings(append(append(known, globs...), jreGlobs...))
}

// resolveJava8Bin returns a java binary that actually reports major 8.
// Never returns /usr/bin/java when that is 11/17/21 — java-tron amd64 dies on those.
func resolveJava8Bin() string {
	for _, c := range java8CandidatePaths() {
		if isJava8Bin(c) {
			return c
		}
	}
	if p, err := exec.LookPath("java"); err == nil && isJava8Bin(p) {
		return p
	}
	return ""
}

func debianCodename() string {
	out, err := exec.Command("lsb_release", "-cs").Output()
	if err == nil {
		if c := strings.TrimSpace(string(out)); c != "" && c != "n/a" {
			return c
		}
	}
	b, err := os.ReadFile("/etc/os-release")
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(b), "\n") {
		if strings.HasPrefix(line, "VERSION_CODENAME=") {
			return strings.Trim(strings.TrimPrefix(line, "VERSION_CODENAME="), `"'`)
		}
	}
	return ""
}

func ensureTemurin8() error {
	if _, err := exec.LookPath("apt-get"); err != nil {
		return fmt.Errorf("apt-get not found — cannot install Temurin 8")
	}
	if err := ensureAptPackages([]string{"ca-certificates", "curl", "gnupg"}); err != nil {
		return err
	}
	codename := debianCodename()
	if codename == "" {
		codename = "jammy"
	}
	if err := os.MkdirAll("/etc/apt/keyrings", 0o755); err != nil {
		return err
	}
	keyPath := "/etc/apt/keyrings/adoptium.gpg"
	if !fileExists(keyPath) {
		cmd := exec.Command("bash", "-lc",
			"curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o "+keyPath)
		if out, err := cmd.CombinedOutput(); err != nil {
			return fmt.Errorf("adoptium gpg key: %v (%s)", err, strings.TrimSpace(string(out)))
		}
	}
	list := fmt.Sprintf("deb [signed-by=%s] https://packages.adoptium.net/artifactory/deb %s main\n", keyPath, codename)
	if err := os.WriteFile("/etc/apt/sources.list.d/adoptium.list", []byte(list), 0o644); err != nil {
		return err
	}
	upd := exec.Command("apt-get", "update", "-qq")
	upd.Env = append(os.Environ(), "DEBIAN_FRONTEND=noninteractive")
	if out, err := upd.CombinedOutput(); err != nil {
		return fmt.Errorf("apt-get update (adoptium): %v (%s)", err, strings.TrimSpace(string(out)))
	}
	return aptInstall([]string{"temurin-8-jre"})
}

// ensureJava8 installs a real Java 8 runtime if missing. Java 17 on PATH is not enough —
// java-tron GreatVoyage (amd64) exits: "Java 1.8 is required … Detected version 17".
// Does not change the system default java (other software may need 17).
func ensureJava8() error {
	if bin := resolveJava8Bin(); bin != "" {
		return nil
	}
	hostLog("INFO", "api-agent", "java8", "missing — apt openjdk-8")
	_ = ensureAptPackages([]string{"openjdk-8-jre-headless", "openjdk-8-jre"})
	if bin := resolveJava8Bin(); bin != "" {
		hostLogf("INFO", "api-agent", "java8", "installed %s", bin)
		return nil
	}
	hostLog("INFO", "api-agent", "java8", "openjdk-8 missing — Temurin 8")
	if err := ensureTemurin8(); err != nil {
		hostLogf("ERROR", "api-agent", "java8", "temurin failed: %v", err)
		return fmt.Errorf("Java 8 required for java-tron (a newer JDK on PATH is ignored): %w", err)
	}
	if bin := resolveJava8Bin(); bin == "" {
		hostLog("ERROR", "api-agent", "java8", "still missing after openjdk-8 / temurin-8")
		return fmt.Errorf("Java 8 still missing after openjdk-8 / temurin-8-jre — java-tron cannot use Java 11+")
	} else {
		hostLogf("INFO", "api-agent", "java8", "temurin %s", bin)
	}
	return nil
}

func tronUnitExecJava(unitBody string) string {
	for _, line := range strings.Split(unitBody, "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, "ExecStart=") {
			continue
		}
		rest := strings.TrimSpace(strings.TrimPrefix(line, "ExecStart="))
		rest = strings.TrimSpace(strings.TrimSuffix(rest, "\\"))
		fields := strings.Fields(rest)
		if len(fields) == 0 {
			return ""
		}
		return fields[0]
	}
	return ""
}

func javaHomeFromBin(javaBin string) string {
	javaBin = strings.TrimSpace(javaBin)
	if javaBin == "" {
		return ""
	}
	// …/bin/java or …/jre/bin/java → JAVA_HOME
	dir := filepath.Dir(javaBin) // bin
	home := filepath.Dir(dir)    // jre or java-8-…
	if strings.HasSuffix(home, "/jre") {
		return filepath.Dir(home)
	}
	return home
}

func ensureTronConfig(confPath, env string, nodeHTTP, p2p int) error {
	if !fileExists(confPath) {
		return fmt.Errorf("config missing: %s", confPath)
	}
	b, err := os.ReadFile(confPath)
	if err != nil {
		return err
	}
	t := string(b)
	orig := t
	// resolveLivePortProfile: canonical aux first; if foreign-busy (e.g. gRPC sol
	// 50061 held by mainnet), scan free — never leave commented stock defaults.
	prof := resolveLivePortProfile("tron", env)
	if nodeHTTP <= 0 {
		nodeHTTP = prof.NodeHTTP
	}
	if p2p <= 0 {
		p2p = prof.P2P
	}
	prof.NodeHTTP = nodeHTTP
	prof.P2P = p2p

	if nodeHTTP > 0 {
		re := regexp.MustCompile(`fullNodePort\s*=\s*\d+`)
		t = re.ReplaceAllString(t, fmt.Sprintf("fullNodePort = %d", nodeHTTP))
	}
	if p2p > 0 {
		re := regexp.MustCompile(`listen\.port\s*=\s*\d+`)
		t = re.ReplaceAllString(t, fmt.Sprintf("listen.port = %d", p2p))
	}

	// Snapshot DBs from GreatVoyage require checkpoint.version = 2.
	if !regexp.MustCompile(`(?m)^\s*checkpoint\.version\s*=`).MatchString(t) {
		t = strings.Replace(t, "storage {\n", "storage {\n  checkpoint.version = 2\n", 1)
	} else {
		re := regexp.MustCompile(`(?m)^\s*checkpoint\.version\s*=\s*\d+`)
		t = re.ReplaceAllString(t, "  checkpoint.version = 2")
	}

	// RpcNode exposes fullNode HTTP via Go proxy only. Aux HTTP/gRPC/metrics
	// come from the canonical profile so envs never collide (DESIGN.md).
	t = patchTronHTTPBlock(t, prof)
	t = patchTronRPCBlock(t, prof)
	t = patchTronMetricsPort(t, prof.Metrics)
	// High-load day one: thousands concurrent via Go proxy → loopback FullNode.
	t = patchTronHighLoadLimits(t)
	if normalizeEnv(env) == "nile" {
		t = patchTronNileP2P(t)
	}
	t = patchTronInstallOptions(t, env)

	if t == orig {
		return nil
	}
	return os.WriteFile(confPath, []byte(t), 0o640)
}

func patchTronInstallOptions(t, env string) string {
	opts := loadInstallOptions("tron", env)
	ch := findInstallChoice("tron", env, "snapshot", opts["snapshot"])
	if ch == nil {
		return t
	}
	if ch.SaveInternalTx != nil {
		t = patchTronHOCONBool(t, "saveInternalTx", *ch.SaveInternalTx)
	}
	if ch.SaveFeaturedInternalTx != nil {
		t = patchTronHOCONBool(t, "saveFeaturedInternalTx", *ch.SaveFeaturedInternalTx)
	}
	if ch.BalanceHistoryLookup != nil {
		t = patchTronHOCONBool(t, "balance.history.lookup", *ch.BalanceHistoryLookup)
	}
	return t
}

func patchTronHOCONBool(t, key string, val bool) string {
	lit := "false"
	if val {
		lit = "true"
	}
	re := regexp.MustCompile(`(?m)^(\s*)` + regexp.QuoteMeta(key) + `\s*=\s*(true|false)\s*$`)
	if re.MatchString(t) {
		return re.ReplaceAllString(t, "${1}"+key+" = "+lit)
	}
	if key == "saveFeaturedInternalTx" {
		reIns := regexp.MustCompile(`(?m)^(\s*)saveInternalTx\s*=\s*(true|false)\s*$`)
		if reIns.MatchString(t) {
			return reIns.ReplaceAllString(t, "${1}saveInternalTx = ${2}\n${1}saveFeaturedInternalTx = "+lit)
		}
	}
	return t
}

// nileex / nile-testnet config-nile.conf (2026). tron-docker 47.252.* seeds
// no longer accept TCP — Nile stays active=0 and catch-up stalls on inbound laggards.
var nileLiveSeeds = []string{
	"44.236.192.97:18888",
	"44.236.125.107:18888",
	"44.232.119.174:18888",
	"52.39.105.180:18888",
	"54.70.52.47:18888",
}

func hoconQuotedIPList(items []string) string {
	var b strings.Builder
	for _, it := range items {
		b.WriteString("    \"")
		b.WriteString(it)
		b.WriteString("\",\n")
	}
	return b.String()
}

func indexUncommented(t, marker string) int {
	for start := 0; start < len(t); {
		rel := strings.Index(t[start:], marker)
		if rel < 0 {
			return -1
		}
		i := start + rel
		line := t[:i]
		if nl := strings.LastIndex(line, "\n"); nl >= 0 {
			line = line[nl+1:]
		}
		trim := strings.TrimSpace(line)
		if strings.HasPrefix(trim, "#") || strings.HasPrefix(trim, "//") {
			start = i + len(marker)
			continue
		}
		return i
	}
	return -1
}

func matchingDelim(t string, open int, openCh, closeCh byte) int {
	depth := 0
	for i := open; i < len(t); i++ {
		switch t[i] {
		case openCh:
			depth++
		case closeCh:
			depth--
			if depth == 0 {
				return i
			}
		}
	}
	return -1
}

func nileSeedNodeBlock() string {
	return "seed.node = {\n  ip.list = [\n" + hoconQuotedIPList(nileLiveSeeds) + "  ]\n}\n"
}

func nileActiveBlock() string {
	return "  active = [\n" + hoconQuotedIPList(nileLiveSeeds) + "  ]\n"
}

func replaceUncommentedBlock(t, marker string, openCh, closeCh byte, repl string) string {
	idx := indexUncommented(t, marker)
	if idx < 0 {
		return t
	}
	open := strings.Index(t[idx:], string(openCh))
	if open < 0 {
		return t
	}
	open += idx
	close := matchingDelim(t, open, openCh, closeCh)
	if close < 0 {
		return t
	}
	lineStart := 0
	if nl := strings.LastIndex(t[:idx], "\n"); nl >= 0 {
		lineStart = nl + 1
	}
	return t[:lineStart] + repl + t[close+1:]
}

var leakedHoconIPKeyRe = regexp.MustCompile(`^"\d+\.\d+\.\d+\.\d+:\d+",?$`)

func stripLeakedHoconIPKeys(t string) string {
	var b strings.Builder
	depth := 0
	for _, line := range strings.SplitAfter(t, "\n") {
		trim := strings.TrimSpace(strings.TrimSuffix(line, "\n"))
		if depth == 0 && leakedHoconIPKeyRe.MatchString(trim) {
			continue
		}
		b.WriteString(line)
		for i := 0; i < len(line); i++ {
			switch line[i] {
			case '[':
				depth++
			case ']':
				depth--
			}
		}
	}
	return b.String()
}

func patchTronNileP2P(t string) string {
	// Replace the whole uncommented seed.node { } — never the `# ip.list = [` example.
	if indexUncommented(t, "seed.node") >= 0 {
		t = replaceUncommentedBlock(t, "seed.node", '{', '}', nileSeedNodeBlock())
	} else {
		t = strings.TrimRight(t, "\n") + "\n" + nileSeedNodeBlock()
	}
	t = replaceUncommentedBlock(t, "active =", '[', ']', nileActiveBlock())
	return stripLeakedHoconIPKeys(t)
}

// High-load java-tron HTTP / rate-limiter defaults (private RPC behind Go proxy).
// All public clients share one upstream IP (127.0.0.1), so global.ip.qps ≈ global.qps.
const (
	tronMaxHTTPConnect = 4000
	tronGlobalQPS      = 200000
	tronGlobalIPQPS    = 200000
)

func patchTronHighLoadLimits(t string) string {
	maxHTTP := tronMaxHTTPConnect
	if v := strings.TrimSpace(os.Getenv("TRON_MAX_HTTP_CONNECT")); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			maxHTTP = n
		}
	}
	gqps := tronGlobalQPS
	if v := strings.TrimSpace(os.Getenv("TRON_GLOBAL_QPS")); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			gqps = n
		}
	}
	ipqps := tronGlobalIPQPS
	if v := strings.TrimSpace(os.Getenv("TRON_GLOBAL_IP_QPS")); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			ipqps = n
		}
	}

	if regexp.MustCompile(`maxHttpConnectNumber\s*=`).MatchString(t) {
		t = regexp.MustCompile(`maxHttpConnectNumber\s*=\s*\d+`).
			ReplaceAllString(t, fmt.Sprintf("maxHttpConnectNumber = %d", maxHTTP))
	} else if start, end, ok := braceBlock(t, "node {"); ok {
		block := t[start : end+1]
		block = strings.Replace(block, "node {",
			fmt.Sprintf("node {\n  maxHttpConnectNumber = %d", maxHTTP), 1)
		t = t[:start] + block + t[end+1:]
	}

	// Also uncomment stock `# global.qps = …` lines (common in Nile configs).
	if regexp.MustCompile(`(?m)^\s*#?\s*global\.qps\s*=`).MatchString(t) {
		t = regexp.MustCompile(`(?m)^\s*#?\s*global\.qps\s*=\s*\d+`).
			ReplaceAllString(t, fmt.Sprintf("  global.qps = %d", gqps))
	}
	if regexp.MustCompile(`(?m)^\s*#?\s*global\.ip\.qps\s*=`).MatchString(t) {
		t = regexp.MustCompile(`(?m)^\s*#?\s*global\.ip\.qps\s*=\s*\d+`).
			ReplaceAllString(t, fmt.Sprintf("  global.ip.qps = %d", ipqps))
	}
	if regexp.MustCompile(`(?m)^\s*#?\s*apiNonBlocking\s*=`).MatchString(t) {
		t = regexp.MustCompile(`(?m)^\s*#?\s*apiNonBlocking\s*=\s*(true|false)`).
			ReplaceAllString(t, "  apiNonBlocking = true")
	}

	// Inject rate.limiter block when stock conf omits it entirely.
	if !regexp.MustCompile(`rate\.limiter\s*=`).MatchString(t) {
		block := fmt.Sprintf(`
rate.limiter = {
  global.qps = %d
  global.ip.qps = %d
  apiNonBlocking = true
}
`, gqps, ipqps)
		t = t + block
	}

	return t
}

func braceBlock(t, marker string) (start, end int, ok bool) {
	start = strings.Index(t, marker)
	if start < 0 {
		return 0, 0, false
	}
	i := start + len(marker)
	depth := 1
	for i < len(t) {
		switch t[i] {
		case '{':
			depth++
		case '}':
			depth--
			if depth == 0 {
				return start, i, true
			}
		}
		i++
	}
	return 0, 0, false
}

// patchTronHTTPBlock rewrites the first `http { … }` block only.
func patchTronHTTPBlock(t string, prof networkPortProfile) string {
	start, end, ok := braceBlock(t, "http {")
	if !ok {
		return t
	}
	block := t[start : end+1]
	solPort := prof.SolHTTP
	pbftPort := prof.PBFTHTTP
	if solPort <= 0 {
		solPort = 18190
	}
	if pbftPort <= 0 {
		pbftPort = 18191
	}
	repl := []struct {
		re *regexp.Regexp
		to string
	}{
		{regexp.MustCompile(`fullNodeEnable\s*=\s*(true|false)`), "fullNodeEnable = true"},
		{regexp.MustCompile(`solidityEnable\s*=\s*(true|false)`), "solidityEnable = false"},
		{regexp.MustCompile(`PBFTEnable\s*=\s*(true|false)`), "PBFTEnable = false"},
		{regexp.MustCompile(`fullNodePort\s*=\s*\d+`), fmt.Sprintf("fullNodePort = %d", prof.NodeHTTP)},
		{regexp.MustCompile(`solidityPort\s*=\s*\d+`), fmt.Sprintf("solidityPort = %d", solPort)},
		{regexp.MustCompile(`PBFTPort\s*=\s*\d+`), fmt.Sprintf("PBFTPort = %d", pbftPort)},
	}
	for _, r := range repl {
		block = r.re.ReplaceAllString(block, r.to)
	}
	// Nile/stock configs often omit Enable flags — inject after opening brace.
	if !regexp.MustCompile(`solidityEnable\s*=`).MatchString(block) {
		block = strings.Replace(block, "http {", "http {\n    fullNodeEnable = true\n    solidityEnable = false\n    PBFTEnable = false", 1)
	}
	if prof.NodeHTTP > 0 && !regexp.MustCompile(`fullNodePort\s*=`).MatchString(block) {
		block = strings.Replace(block, "http {", fmt.Sprintf("http {\n    fullNodePort = %d", prof.NodeHTTP), 1)
	}
	if !regexp.MustCompile(`solidityPort\s*=`).MatchString(block) {
		block = strings.Replace(block, "http {", fmt.Sprintf("http {\n    solidityPort = %d", solPort), 1)
	}
	if !regexp.MustCompile(`PBFTPort\s*=`).MatchString(block) {
		// Insert after solidityPort line when stock Nile configs omit PBFT HTTP.
		block = regexp.MustCompile(`solidityPort\s*=\s*\d+`).ReplaceAllString(block,
			fmt.Sprintf("solidityPort = %d\n    PBFTPort = %d", solPort, pbftPort))
	}
	return t[:start] + block + t[end+1:]
}

// patchTronRPCBlock rewrites the first `rpc { … }` under node (gRPC ports).
// Stock Nile configs often comment `#solidityPort = 50061` — java-tron then
// defaults to mainnet's 50061 and crash-loops with BindException on multi-env hosts.
func patchTronRPCBlock(t string, prof networkPortProfile) string {
	if prof.GRPC <= 0 {
		return t
	}
	// Prefer `rpc {` after node { — first rpc block is FullNode gRPC.
	start, end, ok := braceBlock(t, "rpc {")
	if !ok {
		return t
	}
	block := t[start : end+1]
	sol := prof.GRPCSol
	pbft := prof.GRPCPbft
	if sol <= 0 {
		sol = prof.GRPC + 10
	}
	if pbft <= 0 {
		pbft = prof.GRPC + 20
	}
	// Uncomment + rewrite (handles "#solidityPort = 50061").
	repl := []struct {
		re *regexp.Regexp
		to string
	}{
		{regexp.MustCompile(`(?m)^\s*port\s*=\s*\d+`), fmt.Sprintf("    port = %d", prof.GRPC)},
		{regexp.MustCompile(`(?m)^\s*#?\s*solidityPort\s*=\s*\d+`), fmt.Sprintf("    solidityPort = %d", sol)},
		{regexp.MustCompile(`(?m)^\s*#?\s*PBFTPort\s*=\s*\d+`), fmt.Sprintf("    PBFTPort = %d", pbft)},
	}
	for _, r := range repl {
		block = r.re.ReplaceAllString(block, r.to)
	}
	if !regexp.MustCompile(`(?m)^\s*solidityPort\s*=`).MatchString(block) {
		block = strings.Replace(block, "rpc {", fmt.Sprintf("rpc {\n    solidityPort = %d", sol), 1)
	}
	if !regexp.MustCompile(`(?m)^\s*PBFTPort\s*=`).MatchString(block) {
		block = regexp.MustCompile(`(?m)^\s*solidityPort\s*=\s*\d+`).ReplaceAllString(block,
			fmt.Sprintf("    solidityPort = %d\n    PBFTPort = %d", sol, pbft))
	}
	return t[:start] + block + t[end+1:]
}

func patchTronMetricsPort(t string, metrics int) string {
	if metrics <= 0 {
		return t
	}
	// Common forms: port = "9527" | port="9527" | port = 9527 inside prometheus { }.
	reQ := regexp.MustCompile(`(?i)(prometheus\s*\{[\s\S]{0,200}?port\s*=\s*")\d+(")`)
	if reQ.MatchString(t) {
		return reQ.ReplaceAllString(t, fmt.Sprintf(`${1}%d${2}`, metrics))
	}
	reN := regexp.MustCompile(`(?i)(prometheus\s*\{[\s\S]{0,200}?port\s*=\s*)\d+`)
	if reN.MatchString(t) {
		return reN.ReplaceAllString(t, fmt.Sprintf(`${1}%d`, metrics))
	}
	return t
}

// ensureTronAssets downloads jar + config when missing (Nile uses Nile config / snapshot).
func ensureTronAssets(env string) error {
	env = normalizeEnv(env)
	etc, _, opt, conf, jar, _ := tronPaths(env)
	for _, d := range []string{etc, opt} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return err
		}
	}
	jarURL, confURL := tronAssetURLs(env)
	if !fileExists(jar) {
		if err := downloadFile(jarURL, jar); err != nil {
			return fmt.Errorf("download FullNode.jar: %w", err)
		}
		_ = exec.Command("chown", "nodeop:nodeop", jar).Run()
	}
	if !fileExists(conf) {
		if err := downloadFile(confURL, conf); err != nil {
			return fmt.Errorf("download config: %w", err)
		}
		_ = os.Chmod(conf, 0o640)
		_ = exec.Command("chown", "root:nodeop", conf).Run()
	}
	return nil
}

func tronAssetURLs(env string) (jarURL, confURL string) {
	// Vendored CDN catalog first (same as system-agent). GitHub only if CDN miss.
	rel := resolveTronClientRelease(env)
	return rel.ArtifactURL, rel.ConfURL
}

func downloadFile(url, dest string) error {
	url = strings.TrimSpace(url)
	if url == "" {
		return fmt.Errorf("empty url")
	}
	tmp := dest + ".tmp"
	_ = os.Remove(tmp)
	cmd := exec.Command("curl", "-fL", "--retry", "5", "--retry-delay", "2", "-o", tmp, url)
	out, err := cmd.CombinedOutput()
	if err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("%v: %s", err, strings.TrimSpace(string(out)))
	}
	return os.Rename(tmp, dest)
}

func tronOutputDir(env, data string) string {
	etc := filepath.Join("/etc/tron", env)
	if doc := readJSONFile(filepath.Join(etc, "disk_layout.json")); doc != nil {
		if v, ok := doc["fullnode_dir"].(string); ok && strings.TrimSpace(v) != "" {
			return filepath.Clean(strings.TrimSpace(v))
		}
	}
	return filepath.Join(data, "output-directory")
}

func renderTronNodeUnit(env, javaBin, javaHome, opt, conf, data string) string {
	xmx := strings.TrimSpace(os.Getenv("TRON_JAVA_XMX"))
	if xmx == "" {
		xmx = "48g"
	}
	xms := strings.TrimSpace(os.Getenv("TRON_JAVA_XMS"))
	if xms == "" {
		xms = xmx
	}
	logs := filepath.Join(opt, "logs")
	output := tronOutputDir(env, data)
	envFile := filepath.Join("/etc/tron", env, "toolkit.env")

	return fmt.Sprintf(`[Unit]
Description=TRON java-tron FullNode (%s)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nodeop
Group=nodeop
WorkingDirectory=%s
Environment=JAVA_HOME=%s
EnvironmentFile=-%s
ExecStart=%s \
  -Xmx%s -Xms%s \
  -XX:+UseG1GC \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=%s \
  -jar %s/FullNode.jar \
  -c %s \
  -d %s
SuccessExitStatus=143
TimeoutStopSec=600
Restart=on-failure
RestartSec=30
IPAccounting=yes
CPUAccounting=yes
MemoryAccounting=yes
LimitNOFILE=1048576
KillSignal=SIGTERM
KillMode=mixed
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
`, env, opt, javaHome, envFile, javaBin, xmx, xms, logs, opt, conf, output)
}

func renderTronSnapshotScript(url, data, marker, logPath string) string {
	var b strings.Builder
	b.WriteString("#!/bin/bash\nset -euo pipefail\n")
	fmt.Fprintf(&b, "URL=%s\n", strconv.Quote(url))
	fmt.Fprintf(&b, "DATA=%s\n", strconv.Quote(data))
	fmt.Fprintf(&b, "MARKER=%s\n", strconv.Quote(marker))
	fmt.Fprintf(&b, "LOG=%s\n", strconv.Quote(logPath))
	b.WriteString(`if [[ -f "$MARKER" ]]; then exit 0; fi
mkdir -p "$DATA" "$(dirname "$LOG")"
if [[ -z "$URL" ]]; then
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) no snapshot URL — skip" >>"$LOG"
  exit 0
fi
# Mirror root (http://IP/) → latest official backupYYYYMMDD/FullNode_output-directory.tgz
if [[ "$URL" != *.tgz && "$URL" != *.tar.gz ]]; then
  BASE="${URL%/}"
  HTML=$(wget -qO- --timeout=45 "$BASE/" || true)
  LATEST=$(printf '%s\n' "$HTML" | grep -oE 'backup[0-9]{8}' | sort | tail -1 || true)
  if [[ -n "$LATEST" ]]; then
    URL="$BASE/$LATEST/FullNode_output-directory.tgz"
  else
    URL="$BASE/FullNode_output-directory.tgz"
  fi
fi
cd "$DATA"
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) START $URL" >>"$LOG"
wget -O - "$URL" 2>>"$LOG" | tar -xzf - >>"$LOG" 2>&1
# Snapshot unit is root; java-tron is User=nodeop — chown BEFORE the ready marker
# so pipeline start cannot race a root-owned LevelDB.
if id -u nodeop >/dev/null 2>&1; then
  chown -R nodeop:nodeop "$DATA" >>"$LOG" 2>&1 || true
fi
date -u +"%Y-%m-%dT%H:%M:%SZ" >"$MARKER"
if id -u nodeop >/dev/null 2>&1; then
  chown nodeop:nodeop "$MARKER" >>"$LOG" 2>&1 || true
fi
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) DONE" >>"$LOG"
`)
	return b.String()
}

func writeTronSnapshotScript(env string) (string, error) {
	env = normalizeEnv(env)
	url := resolveSnapshotURLForOptions("tron", env, loadInstallOptions("tron", env))
	if url == "" {
		url = strings.TrimSpace(os.Getenv("TRON_SNAPSHOT_URL"))
	}
	data := filepath.Join("/data/tron", env)
	marker := filepath.Join(data, ".snapshot-ready")
	logPath := fmt.Sprintf("/var/log/tron/%s-snapshot.log", env)
	binDir := envOr("RPCNODE_BIN_DIR", "/opt/rpcnode/bin")
	path := filepath.Join(binDir, "tron-snapshot-"+env+".sh")
	if err := os.MkdirAll(binDir, 0o755); err != nil {
		return "", err
	}
	if err := os.WriteFile(path, []byte(renderTronSnapshotScript(url, data, marker, logPath)), 0o755); err != nil {
		return "", err
	}
	return path, nil
}

// ensureTronSnapshotUnit writes tron-<env>-snapshot.service.
// ExecStart is an agent-written script (CDN hosts have no rpcnodectl).
func ensureTronSnapshotUnit(env string) error {
	env = normalizeEnv(env)
	script, err := writeTronSnapshotScript(env)
	if err != nil {
		return err
	}
	unitPath := fmt.Sprintf("/etc/systemd/system/tron-%s-snapshot.service", env)
	marker := filepath.Join("/data/tron", env, ".snapshot-ready")
	body := fmt.Sprintf(`[Unit]
Description=TRON %s FullNode snapshot download+extract
After=network-online.target
Wants=network-online.target
ConditionPathExists=!%s

[Service]
Type=simple
User=root
Environment=TRON_ENV=%s
EnvironmentFile=-/etc/tron/%s/toolkit.env
ExecStart=%s
Restart=on-failure
RestartSec=60
Nice=10
`, env, marker, env, env, script)
	if err := os.WriteFile(unitPath, []byte(body), 0o644); err != nil {
		return err
	}
	_ = exec.Command("systemctl", "daemon-reload").Run()
	return nil
}

// ensureTronNodeUnit writes a real java-tron systemd unit when jar+config exist.
// Replaces provision stubs (ExecStart=/bin/false) so nodes/start can succeed.
func ensureTronNodeUnit(env string, nodeHTTP, p2p int) (unitPath string, err error) {
	etc, data, opt, conf, jar, unitName := tronPaths(env)
	unitPath = filepath.Join("/etc/systemd/system", unitName)

	output := tronOutputDir(env, data)
	for _, d := range []string{etc, data, opt, filepath.Join(opt, "logs"), output} {
		if mkErr := os.MkdirAll(d, 0o755); mkErr != nil {
			return unitPath, fmt.Errorf("mkdir %s: %w", d, mkErr)
		}
	}

	if err := ensureTronAssets(env); err != nil {
		return unitPath, err
	}
	_ = ensureTronSnapshotUnit(env)
	if !fileExists(jar) {
		return unitPath, fmt.Errorf("FullNode.jar missing: %s", jar)
	}
	if !fileExists(conf) {
		return unitPath, fmt.Errorf("config missing: %s", conf)
	}

	migrateSystemAgentLoopback(filepath.Join(etc, "toolkit.env"), "tron", env)

	if err := ensureNetworkHostDeps("tron"); err != nil {
		return unitPath, err
	}
	if err := ensureJava8(); err != nil {
		return unitPath, err
	}
	javaBin := resolveJava8Bin()
	if javaBin == "" {
		return unitPath, fmt.Errorf("Java 8 required for amd64 java-tron — agent failed to install openjdk-8 / temurin-8")
	}
	if err := ensureTronConfig(conf, env, nodeHTTP, p2p); err != nil {
		return unitPath, err
	}

	body := renderTronNodeUnit(env, javaBin, javaHomeFromBin(javaBin), opt, conf, data)
	needWrite := !fileExists(unitPath) || isTronNodeUnitStub(unitPath)
	if fileExists(unitPath) && !needWrite {
		old, _ := os.ReadFile(unitPath)
		oldS := string(old)
		execJava := tronUnitExecJava(oldS)
		// Pin ExecStart to Java 8 even when /usr/bin/java is 17.
		if !strings.Contains(oldS, javaBin) || !strings.Contains(oldS, jar) ||
			(execJava != "" && execJava != javaBin) ||
			(execJava != "" && !isJava8Bin(execJava)) {
			needWrite = true
		}
	}
	if needWrite {
		if err := os.WriteFile(unitPath, []byte(body), 0o644); err != nil {
			return unitPath, err
		}
		_ = exec.Command("systemctl", "daemon-reload").Run()
	}
	_ = exec.Command("chown", "-R", "nodeop:nodeop", opt, data, output).Run()
	return unitPath, nil
}

func tronStartLogSnippet(env string, n int) string {
	if n <= 0 {
		n = 24
	}
	_, _, opt, _, _, _ := tronPaths(env)
	path := filepath.Join(opt, "logs", "tron.log")
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	lines := strings.Split(string(b), "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	// Prefer ExitManager / ERROR lines.
	var picked []string
	for _, ln := range lines {
		if strings.Contains(ln, "[Exit]") || strings.Contains(ln, "ERROR") || strings.Contains(ln, "Java 1.8") {
			picked = append(picked, ln)
		}
	}
	if len(picked) > 0 {
		if len(picked) > 6 {
			picked = picked[len(picked)-6:]
		}
		return strings.TrimSpace(strings.Join(picked, " | "))
	}
	return strings.TrimSpace(strings.Join(lines, "\n"))
}
