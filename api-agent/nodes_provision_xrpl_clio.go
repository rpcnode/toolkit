package main

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

func xrplClioHTTPPort(env string) int {
	if normalizeEnv(env) == "testnet" {
		return 51234
	}

	return 51233
}

func xrplGRPCPort(env string) int {
	if normalizeEnv(env) == "testnet" {
		return 51252
	}

	return 51251
}

func xrplWSPublicPort(env string) int {
	if normalizeEnv(env) == "testnet" {
		return 6008
	}

	return 6005
}

func xrplClioUnitName(env string) string {
	return fmt.Sprintf("xrpl-clio-%s.service", normalizeEnv(env))
}

func xrplClioKeyspace(env string) string {
	if normalizeEnv(env) == "testnet" {
		return "clio_testnet"
	}

	return "clio_mainnet"
}

func provisionXRPLClioStack(env, etc, data string) error {
	env = normalizeEnv(env)
	if err := ensureScyllaInstalled(data); err != nil {
		return err
	}

	if err := ensureClioInstalled(); err != nil {
		return err
	}

	if err := writeXRPLClioConfig(env, etc, data); err != nil {
		return err
	}

	bin := resolveClioBin()
	if bin == "" {
		return fmt.Errorf("clio binary missing after apt install")
	}

	unit := renderXRPLClioUnit(env, bin, filepath.Join(etc, "clio.json"))
	if err := os.WriteFile(filepath.Join("/etc/systemd/system", xrplClioUnitName(env)), []byte(unit), 0o644); err != nil {
		return err
	}

	if _, err := exec.LookPath("systemctl"); err == nil {
		_ = exec.Command("systemctl", "disable", "--now", "clio.service").Run()
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "enable", "scylla-server.service").Run()
		_ = exec.Command("systemctl", "enable", xrplClioUnitName(env)).Run()
	}

	return nil
}

func writeXRPLClioConfig(env, etc, data string) error {
	if err := os.MkdirAll(etc, 0o755); err != nil {
		return err
	}

	logDir := filepath.Join(data, "clio-log")
	_ = os.MkdirAll(logDir, 0o755)

	doc := map[string]any{
		"database": map[string]any{
			"type": "cassandra",
			"cassandra": map[string]any{
				"contact_points":                 "127.0.0.1",
				"port":                           9042,
				"keyspace":                       xrplClioKeyspace(env),
				"replication_factor":             1,
				"table_prefix":                   "",
				"max_write_requests_outstanding": 10000,
				"max_read_requests_outstanding":  10000,
				"threads":                        4,
			},
		},
		"etl_sources": []map[string]any{{
			"ip":        "127.0.0.1",
			"ws_port":   fmt.Sprintf("%d", xrplWSPublicPort(env)),
			"grpc_port": fmt.Sprintf("%d", xrplGRPCPort(env)),
		}},
		"dos_guard": map[string]any{
			"whitelist": []string{"127.0.0.1"},
		},
		"server": map[string]any{
			"ip":   "127.0.0.1",
			"port": xrplClioHTTPPort(env),
		},
		"log_to_console":    true,
		"log_directory":     logDir,
		"log_level":         "warning",
		"extractor_threads": 4,
		"read_only":         false,
	}

	raw, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return err
	}

	return os.WriteFile(filepath.Join(etc, "clio.json"), append(raw, '\n'), 0o644)
}

func renderXRPLClioUnit(env, bin, conf string) string {
	return fmt.Sprintf(`[Unit]
Description=XRPL Clio RPC (%s) — RpcNode
After=network-online.target scylla-server.service xrpl-%s.service
Wants=network-online.target scylla-server.service
StartLimitIntervalSec=0

[Service]
Type=simple
User=nodeop
Group=nodeop
ExecStart=%s %s
Restart=always
RestartSec=5
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
`, env, env, bin, conf)
}

func resolveClioBin() string {
	for _, c := range []string{
		"/opt/clio/bin/clio_server",
		"/usr/bin/clio_server",
		"/usr/bin/clio",
		"/opt/clio/bin/clio",
	} {
		if fileExists(c) {
			return c
		}
	}
	if p, err := exec.LookPath("clio_server"); err == nil {
		return p
	}
	if p, err := exec.LookPath("clio"); err == nil {
		return p
	}

	return ""
}

func ensureClioInstalled() error {
	if resolveClioBin() != "" {
		return nil
	}

	if err := ensureRippleAptRepo(); err != nil {
		return err
	}

	if out, err := exec.Command("apt-get", "-y", "install", "clio").CombinedOutput(); err != nil {
		return fmt.Errorf("apt-get install clio: %v (%s)", err, strings.TrimSpace(string(out)))
	}

	return nil
}

const scyllaWebInstallerURL = "https://get.scylladb.com/server"
const scyllaAptRelease = "2026.2"

func scyllaAptListURL(osID string) string {
	osID = strings.ToLower(strings.TrimSpace(osID))
	if osID == "ubuntu" {
		return "https://downloads.scylladb.com/deb/ubuntu/scylla-" + scyllaAptRelease + ".list"
	}

	return "https://downloads.scylladb.com/deb/debian/scylla-" + scyllaAptRelease + ".list"
}

func ensureScyllaInstalled(data string) error {
	if scyllaBinReady() {
		return finalizeScyllaInstall(data)
	}

	if _, err := exec.LookPath("apt-get"); err != nil {
		return fmt.Errorf("apt-get required to install scylla: %w", err)
	}

	_ = exec.Command("apt-get", "-y", "install", "curl", "ca-certificates", "gnupg").Run()
	_ = writeScyllaIOSkip(data)

	aptErr := installScyllaViaAptRepo()
	if !scyllaBinReady() {
		if webErr := installScyllaViaWebInstaller(); webErr != nil {
			if aptErr != nil {
				return fmt.Errorf("scylla install: apt repo: %v; web installer: %w", aptErr, webErr)
			}

			return webErr
		}
	}

	if !scyllaBinReady() {
		return fmt.Errorf("scylla binary missing after install")
	}

	return finalizeScyllaInstall(data)
}

func finalizeScyllaInstall(data string) error {
	if _, err := exec.LookPath("systemctl"); err == nil {
		_ = exec.Command("systemctl", "stop", "scylla-server.service").Run()
	}

	return configureScyllaProd(data)
}

func installScyllaViaWebInstaller() error {
	script := "/tmp/scylla-web-install.sh"
	if err := downloadFile(scyllaWebInstallerURL, script); err != nil {
		return fmt.Errorf("download %s: %w", scyllaWebInstallerURL, err)
	}

	_ = os.Chmod(script, 0o755)
	cmd := exec.Command("bash", script)
	cmd.Env = append(os.Environ(), "DEBIAN_FRONTEND=noninteractive")
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("%v (%s)", err, strings.TrimSpace(string(out)))
	}

	return nil
}

func installScyllaViaAptRepo() error {
	if err := ensureScyllaAptRepo(); err != nil {
		return err
	}

	cmd := exec.Command("apt-get", "-y", "install", "scylla")
	cmd.Env = append(os.Environ(), "DEBIAN_FRONTEND=noninteractive")
	if out, err := cmd.CombinedOutput(); err != nil {
		cmd2 := exec.Command("apt-get", "-y", "install", "scylla-server")
		cmd2.Env = append(os.Environ(), "DEBIAN_FRONTEND=noninteractive")
		if out2, err2 := cmd2.CombinedOutput(); err2 != nil {
			return fmt.Errorf("apt-get install scylla: %v (%s | %s)",
				err, strings.TrimSpace(string(out)), strings.TrimSpace(string(out2)))
		}
	}

	return nil
}

func hostOSReleaseID() string {
	out, err := exec.Command("bash", "-lc", `. /etc/os-release 2>/dev/null; echo "${ID:-}"`).Output()
	if err != nil {
		return ""
	}

	return strings.ToLower(strings.TrimSpace(string(out)))
}

func ensureScyllaAptRepo() error {
	_ = os.MkdirAll("/etc/apt/keyrings", 0o755)
	if err := ensureScyllaGPGKey(); err != nil {
		return err
	}

	listPath := "/etc/apt/sources.list.d/scylla.list"
	if err := downloadFile(scyllaAptListURL(hostOSReleaseID()), listPath); err != nil {
		return fmt.Errorf("scylla apt list: %w", err)
	}

	if out, err := exec.Command("apt-get", "-y", "update").CombinedOutput(); err != nil {
		return fmt.Errorf("apt-get update (scylla): %v (%s)", err, strings.TrimSpace(string(out)))
	}

	return nil
}

func ensureScyllaGPGKey() error {
	keyPath := "/etc/apt/keyrings/scylladb.gpg"
	if fileExists(keyPath) {
		return nil
	}

	tmp := "/tmp/scylladb-apt-key.asc"
	if err := downloadFile(
		"https://keyserver.ubuntu.com/pks/lookup?op=get&search=0xc503c686b007f39e",
		tmp,
	); err != nil {
		return fmt.Errorf("scylla gpg key: %w", err)
	}

	if out, err := exec.Command("bash", "-lc",
		fmt.Sprintf(`gpg --dearmor < %q > %q && chmod 644 %q`, tmp, keyPath, keyPath),
	).CombinedOutput(); err != nil {
		return fmt.Errorf("scylla gpg --dearmor: %v (%s)", err, strings.TrimSpace(string(out)))
	}

	return nil
}

func scyllaBinReady() bool {
	if fileExists("/usr/bin/scylla") || fileExists("/opt/scylladb/bin/scylla") {
		return true
	}
	_, err := exec.LookPath("scylla")

	return err == nil
}

func scyllaMemoryGiB(hostGiB int) int {
	if v := strings.TrimSpace(os.Getenv("SCYLLA_MEMORY_GIB")); v != "" {
		n := 0
		fmt.Sscanf(v, "%d", &n)
		if n >= 4 {
			return n
		}
	}

	if hostGiB < 16 {
		return 4
	}

	// Leave the rest for xrpld (huge) + Clio + OS. Cap so one process cannot take the box.
	n := hostGiB / 3
	if n < 8 {
		n = 8
	}
	if n > 96 {
		n = 96
	}

	return n
}

func configureScyllaProd(data string) error {
	_ = os.MkdirAll("/etc/scylla.d", 0o755)
	if err := os.WriteFile("/etc/scylla.d/dev-mode.conf", []byte("DEV_MODE=0\n"), 0o644); err != nil {
		return err
	}

	mem := scyllaMemoryGiB(memTotalMB() / 1024)
	memBody := fmt.Sprintf("# managed by rpcnode — share RAM with xrpld\nSCYLLA_ARGS=\"--memory %dG\"\n", mem)
	if err := os.WriteFile("/etc/scylla.d/memory.conf", []byte(memBody), 0o644); err != nil {
		return err
	}

	dataRoot := filepath.Join(strings.TrimSpace(data), "scylla")
	if strings.TrimSpace(data) == "" {
		dataRoot = "/var/lib/scylla"
	}
	dataDir := filepath.Join(dataRoot, "data")
	clogDir := filepath.Join(dataRoot, "commitlog")
	for _, d := range []string{dataDir, clogDir} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return err
		}
	}
	_ = exec.Command("chown", "-R", "scylla:scylla", dataRoot).Run()

	yamlPath := "/etc/scylla/scylla.yaml"
	if !fileExists(yamlPath) {
		return nil
	}

	raw, err := os.ReadFile(yamlPath)
	if err != nil {
		return err
	}

	s := string(raw)
	s = replaceOrAppendYAML(s, "developer_mode", "false")
	s = replaceOrAppendYAML(s, "listen_address", "127.0.0.1")
	s = replaceOrAppendYAML(s, "rpc_address", "127.0.0.1")
	s = strings.ReplaceAll(s, "/var/lib/scylla/data", dataDir)
	s = strings.ReplaceAll(s, "/var/lib/scylla/commitlog", clogDir)
	if !strings.Contains(s, dataDir) {
		s = strings.TrimRight(s, "\n") + fmt.Sprintf("\ndata_file_directories:\n  - %s\ncommitlog_directory: %s\n", dataDir, clogDir)
	}
	if !strings.Contains(s, "seeds:") {
		s = strings.TrimRight(s, "\n") + "\nseed_provider:\n  - class_name: org.apache.cassandra.locator.SimpleSeedProvider\n    parameters:\n      - seeds: \"127.0.0.1\"\n"
	}
	if err := os.WriteFile(yamlPath, []byte(s), 0o644); err != nil {
		return err
	}

	return writeScyllaIOSkip(data)
}

func scyllaIOPropertiesYAML(mount string) string {
	mount = strings.TrimSpace(mount)
	if mount == "" {
		mount = "/"
	}

	return fmt.Sprintf("disks:\n  - mountpoint: %s\n    read_iops: 100000\n    read_bandwidth: 2000000000\n    write_iops: 50000\n    write_bandwidth: 1000000000\n", mount)
}

func diskMountpoint(path string) string {
	path = strings.TrimSpace(path)
	if path == "" {
		return "/"
	}

	out, err := exec.Command("df", "--output=target", path).Output()
	if err != nil {
		return "/"
	}

	lines := strings.Split(strings.TrimSpace(string(out)), "\n")
	if len(lines) == 0 {
		return "/"
	}

	mp := strings.TrimSpace(lines[len(lines)-1])
	if mp == "" || mp == "Mounted" || mp == "Mounted on" {
		return "/"
	}

	return mp
}

func killScyllaIotune() {
	_ = exec.Command("pkill", "-9", "-x", "iotune").Run()
	_ = exec.Command("pkill", "-9", "-f", "/usr/bin/iotune").Run()
}

// writeScyllaIOSkip — static io.conf so scylla-server never launches iotune
// (iotune saturates every CPU and races xrpld start / systemd).
func writeScyllaIOSkip(data string) error {
	killScyllaIotune()
	_ = os.MkdirAll("/etc/scylla.d", 0o755)

	dataRoot := filepath.Join(strings.TrimSpace(data), "scylla")
	if strings.TrimSpace(data) == "" {
		dataRoot = "/var/lib/scylla"
	}
	_ = os.MkdirAll(dataRoot, 0o755)

	props := "/etc/scylla.d/io_properties.yaml"
	if err := os.WriteFile(props, []byte(scyllaIOPropertiesYAML(diskMountpoint(dataRoot))), 0o644); err != nil {
		return err
	}

	conf := "# managed by rpcnode — skip iotune (provision must not benchmark the disk)\n" +
		"SEASTAR_IO=\"--io-properties-file=/etc/scylla.d/io_properties.yaml\"\n"
	if err := os.WriteFile("/etc/scylla.d/io.conf", []byte(conf), 0o644); err != nil {
		return err
	}

	if _, err := exec.LookPath("systemctl"); err == nil {
		_ = exec.Command("systemctl", "mask", "--now", "scylla-image-setup.service").Run()
	}

	return nil
}

func replaceOrAppendYAML(s, key, value string) string {
	prefix := key + ":"
	lines := strings.Split(s, "\n")
	for i, ln := range lines {
		trim := strings.TrimSpace(ln)
		if strings.HasPrefix(trim, prefix) && !strings.HasPrefix(trim, "#") {
			lines[i] = key + ": " + value

			return strings.Join(lines, "\n")
		}
	}

	return strings.TrimRight(s, "\n") + "\n" + key + ": " + value + "\n"
}

func startXRPLClioUnits(env string) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return err
	}

	env = normalizeEnv(env)
	_ = writeScyllaIOSkip(filepath.Join("/data/xrpl", env))
	_ = exec.Command("systemctl", "daemon-reload").Run()
	_ = exec.Command("systemctl", "reset-failed", "scylla-server.service").Run()
	_ = exec.Command("systemctl", "enable", "scylla-server.service").Run()
	if out, err := exec.Command("systemctl", "start", "scylla-server.service").CombinedOutput(); err != nil {
		return fmt.Errorf("start scylla-server: %v (%s)", err, strings.TrimSpace(string(out)))
	}

	deadline := time.Now().Add(90 * time.Second)
	for time.Now().Before(deadline) {
		if portOpenLocal(9042) {
			break
		}
		time.Sleep(2 * time.Second)
	}

	clio := xrplClioUnitName(env)
	_ = exec.Command("systemctl", "reset-failed", clio).Run()
	_ = exec.Command("systemctl", "enable", clio).Run()
	if out, err := exec.Command("systemctl", "start", clio).CombinedOutput(); err != nil {
		return fmt.Errorf("start %s: %v (%s)", clio, err, strings.TrimSpace(string(out)))
	}

	return nil
}
