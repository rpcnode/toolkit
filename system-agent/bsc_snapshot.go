package main

import (
	"crypto/sha256"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	bscSnapshotsRepo    = "https://github.com/bnb-chain/bsc-snapshots"
	bscSnapshotsREADME  = "https://raw.githubusercontent.com/bnb-chain/bsc-snapshots/main/README.md"
	bscFetchSnapshotURL = "https://raw.githubusercontent.com/bnb-chain/bsc-snapshots/main/dist/fetch-snapshot.sh"
)

var (
	reBSCAria2Pct  = regexp.MustCompile(`\[#[0-9a-fA-F]+[^\n]*?\(([0-9]+)%\)`)
	reBSCAria2Size = regexp.MustCompile(`\[#[0-9a-fA-F]+\s+([0-9][0-9,.]*)\s*(GiB|MiB|TiB)/([0-9][0-9,.]*)\s*(GiB|MiB|TiB)\(([0-9]+)%\)`)
	reBSCSnapName  = regexp.MustCompile(`(?i)(?:mainnet|testnet)-geth-pbss-[0-9]{8}`)
	reBSCPartDone  = regexp.MustCompile(`(?i)(?:Download complete:|Skipping \S+ - already downloaded|Extraction complete(?: and removed)?:)\s*(\S+)`)
	reBSCPartStart = regexp.MustCompile(`(?i)Downloading (\S+) from`)
	reBSCExtract   = regexp.MustCompile(`(?i)Extracting \S+`)
	reBSCCSVRows   = regexp.MustCompile(`(?m)^[^,\n]+\.tar\.lz4,`)
)

func bscSnapshotFlavor(cfg Config) string {
	path := filepath.Join("/etc/bsc", normalizeEnvName(cfg.Env), "install-options.json")
	doc := readJSONFile(path)
	if s, _ := doc["snapshot"].(string); strings.EqualFold(strings.TrimSpace(s), "full") {
		return "full"
	}
	return "pruned"
}

func bscSnapshotNamePrefix(env string) string {
	if normalizeEnvName(env) == "testnet" {
		return "testnet-geth-pbss"
	}
	return "mainnet-geth-pbss"
}

func bscOfficialSnapshotRunning(cfg Config) bool {
	// Do not match this pgrep line (pgrep -f matches its own argv).
	out, err := runCmd(2*time.Second, "bash", "-lc",
		`pgrep -af '[a]ria2c .*/bsc/|[f]etch-snapshot.sh|[b]sc-official-snapshot.sh' | head -1`)
	return err == nil && strings.TrimSpace(out) != ""
}

func bscOfficialSnapshotPct(cfg Config) (float64, bool) {
	texts := bscSnapshotProgressTexts(cfg)
	csvText := bscSnapshotCSVText(cfg.DataDir, cfg.Env)
	var logPct float64
	var logOK bool
	for _, t := range texts {
		if p, hit := parseBSCSnapshotProgress(t, csvText); hit && (!logOK || p >= logPct) {
			logPct, logOK = p, true
		}
	}
	statePct, stateOK := bscSnapshotStatePct(cfg)
	if logOK && logPct > 0 {
		return logPct, true
	}
	if stateOK && statePct > 0 {
		return statePct, true
	}
	if logOK {
		return logPct, true
	}
	return 0, false
}

func bscSnapshotStatePct(cfg Config) (float64, bool) {
	st := readJSONFile(cfg.SnapshotState)
	if st == nil {
		return 0, false
	}
	switch v := st["pct"].(type) {
	case float64:
		if v >= 0 && v <= 100 {
			return v, true
		}
	case string:
		if p, err := strconv.ParseFloat(strings.TrimSpace(v), 64); err == nil && p >= 0 && p <= 100 {
			return p, true
		}
	}
	return 0, false
}

func bscSnapshotProgressTexts(cfg Config) []string {
	texts := []string{}
	if p := strings.TrimSpace(cfg.SnapshotLog); p != "" {
		if b, err := os.ReadFile(p); err == nil && len(b) > 0 {
			texts = append(texts, strings.ReplaceAll(string(b), "\r", "\n"))
		}
	}
	if snip := journalUnitSnippet(cfg.SnapshotService, 120); snip != "" {
		texts = append(texts, strings.ReplaceAll(snip, "\r", "\n"))
	}
	if snip := journalUnitGrepSnippet(cfg.SnapshotService, 80, `\[#|Download |Extract|DONE |parts `); snip != "" {
		texts = append(texts, strings.ReplaceAll(snip, "\r", "\n"))
	}
	return texts
}

func bscSnapshotCSVText(dataDir, env string) string {
	prefix := bscSnapshotNamePrefix(env)
	dataDir = strings.TrimSpace(dataDir)
	dirs := []string{}
	if dataDir != "" {
		dirs = append(dirs,
			filepath.Join(dataDir, "snapshots"),
			filepath.Join(dataDir, "geth", "snapshots"),
		)
	}
	for _, dir := range dirs {
		matches, _ := filepath.Glob(filepath.Join(dir, prefix+"-*.csv"))
		if len(matches) == 0 {
			continue
		}
		b, err := os.ReadFile(matches[0])
		if err != nil {
			continue
		}
		return string(b)
	}
	return ""
}

func aria2LastTotalGiB(text string) float64 {
	ms := reBSCAria2Size.FindAllStringSubmatch(text, -1)
	if len(ms) == 0 {
		return 0
	}
	m := ms[len(ms)-1]
	n, err := strconv.ParseFloat(strings.ReplaceAll(m[3], ",", ""), 64)
	if err != nil {
		return 0
	}
	switch strings.ToUpper(m[4]) {
	case "TIB":
		return n * 1024
	case "MIB":
		return n / 1024
	default:
		return n
	}
}

func bscAria2ProgressDetail(text string) string {
	text = strings.ReplaceAll(text, "\r", "\n")
	ms := reBSCAria2Size.FindAllStringSubmatch(text, -1)
	if len(ms) == 0 {
		return ""
	}
	m := ms[len(ms)-1]
	return fmt.Sprintf("%s%s / %s%s (%s%%)",
		strings.ReplaceAll(m[1], ",", ""), m[2],
		strings.ReplaceAll(m[3], ",", ""), m[4], m[5])
}

// parseBSCSnapshotProgress — aria2 current-file % plus completed CSV parts.
func parseBSCSnapshotProgress(text, csvText string) (float64, bool) {
	text = strings.ReplaceAll(text, "\r", "\n")
	if strings.Contains(text, "\nDONE ") || strings.Contains(text, " DONE ") {
		return 99.9, true
	}
	total := len(reBSCCSVRows.FindAllString(csvText, -1))
	done := map[string]struct{}{}
	for _, m := range reBSCPartDone.FindAllStringSubmatch(text, -1) {
		if len(m) >= 2 && strings.TrimSpace(m[1]) != "" {
			done[m[1]] = struct{}{}
		}
	}
	curPct := 0
	if ms := reBSCAria2Pct.FindAllStringSubmatch(text, -1); len(ms) > 0 {
		n, _ := strconv.Atoi(ms[len(ms)-1][1])
		if n > 0 && n <= 100 {
			curPct = n
		}
	}
	// One huge base archive (1.7 TB) is the ExtraStep — do not dilute 77% across CSV rows.
	if curPct > 0 && aria2LastTotalGiB(text) >= 100 {
		if curPct >= 100 {
			return 99.9, true
		}
		return float64(curPct), true
	}
	if total > 0 {
		p := float64(len(done)) * 100 / float64(total)
		if curPct > 0 && len(done) < total {
			p += float64(curPct) / float64(total)
		}
		if p > 99.9 {
			p = 99.9
		}
		if p < 0 {
			return 0, false
		}
		return p, true
	}
	if curPct > 0 {
		if curPct >= 100 {
			return 99.9, true
		}
		return float64(curPct), true
	}
	if reBSCExtract.MatchString(text) || reBSCPartStart.MatchString(text) || reBSCSnapName.MatchString(text) {
		return 0, true
	}
	return 0, false
}

func parseBSCLatestSnapshotName(readme, env string) string {
	prefix := bscSnapshotNamePrefix(env)
	re := regexp.MustCompile(regexp.QuoteMeta(prefix) + `-[0-9]{8}`)
	m := re.FindString(readme)
	return m
}

func bscSnapshotArchiveGiB(cfg Config) int64 {
	flavor := bscSnapshotFlavor(cfg)
	if normalizeEnvName(cfg.Env) == "testnet" {
		if flavor == "full" {
			return 440
		}
		return 180
	}
	if flavor == "full" {
		return 6600
	}
	return 1700
}

func bscFileFingerprint(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return "missing"
	}
	sum := sha256.Sum256(b)
	st, _ := os.Stat(path)
	mt := "?"
	if st != nil {
		mt = st.ModTime().UTC().Format(time.RFC3339)
	}
	n := 0
	for _, c := range b {
		if c == '\n' {
			n++
		}
	}
	return fmt.Sprintf("bytes=%d lines=%d sha256=%x mtime=%s", len(b), n, sum[:8], mt)
}

var (
	bscEnsureSkipMu   sync.Mutex
	bscEnsureSkipLast time.Time
)

func bscSnapDiag(cfg Config, msg string) {
	msg = strings.TrimSpace(msg)
	if msg == "" {
		return
	}
	log.Printf("bsc-snapshot: %s", msg)
	if p := strings.TrimSpace(cfg.SnapshotLog); p != "" {
		_ = os.MkdirAll(filepath.Dir(p), 0o755)
		if f, err := os.OpenFile(p, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644); err == nil {
			_, _ = fmt.Fprintf(f, "%s SNAPSHOT_DIAG %s\n", time.Now().UTC().Format(time.RFC3339), msg)
			_ = f.Close()
		}
	}
}

func bscSnapDiagThrottle(cfg Config, msg string) {
	bscEnsureSkipMu.Lock()
	defer bscEnsureSkipMu.Unlock()
	if time.Since(bscEnsureSkipLast) < time.Minute {
		return
	}
	bscEnsureSkipLast = time.Now()
	bscSnapDiag(cfg, msg)
}

func bscSnapshotKeepPath(dataDir string) string {
	return filepath.Join(strings.TrimSpace(dataDir), ".snapshot-keep")
}

func bscGethChaindataPresent(dataDir string) bool {
	p := filepath.Join(strings.TrimSpace(dataDir), "geth", "chaindata")
	st, err := os.Stat(p)
	return err == nil && st.IsDir()
}

func bscDirNonEmpty(path string) bool {
	st, err := os.Stat(path)
	if err != nil || !st.IsDir() {
		return false
	}
	ents, err := os.ReadDir(path)
	return err == nil && len(ents) > 0
}

func bscOfficialExtractPresent(dataDir string) bool {
	data := strings.TrimSpace(dataDir)
	if data == "" {
		return false
	}
	if bscDirNonEmpty(filepath.Join(data, "geth", "chaindata", "ancient", "chain")) {
		return true
	}
	return bscDirNonEmpty(filepath.Join(data, "geth", "chaindata", "ancient"))
}

func bscSawOfficialExtract(cfg Config) bool {
	if fileExists(bscSnapshotKeepPath(cfg.DataDir)) {
		return true
	}
	for _, t := range bscSnapshotProgressTexts(cfg) {
		if strings.Contains(strings.ToLower(t), "extraction complete") {
			return true
		}
	}
	return false
}

func bscOfficialExtractPinned(cfg Config) bool {
	if !bscGethChaindataPresent(cfg.DataDir) {
		return false
	}
	// Genesis IBD also has chaindata/ancient — only pin official extract
	// (keep file or "Extraction complete" in snapshot log/journal).
	return bscSawOfficialExtract(cfg)
}

func prepareBSCSnapshotDatadir(cfg Config) {
	if recoverBSCSnapshotMarker(cfg) {
		bscSnapDiag(cfg, "prepare skip wipe: recovered marker (official extract on disk)")
		return
	}
	unit := cfg.NodeService
	if unit != "" && !strings.HasSuffix(unit, ".service") {
		unit += ".service"
	}
	if unit != "" {
		_ = exec.Command("systemctl", "stop", unit).Run()
	}
	if fileExists(cfg.SnapshotMarker) {
		bscSnapDiag(cfg, "prepare skip wipe: marker exists")
		return
	}
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		return
	}
	if bscOfficialExtractPinned(cfg) {
		bscSnapDiag(cfg, "prepare skip wipe: official extract pinned (keep/log/ancient)")
		return
	}
	geth := filepath.Join(data, "geth")
	if st, err := os.Stat(geth); err == nil && st.IsDir() {
		_ = os.RemoveAll(geth)
		hostLogf("INFO", "system-agent", "snapshot", "wiped %s (genesis IBD, no official extract)", geth)
		bscSnapDiag(cfg, "prepare WIPED "+geth+" (no marker, no official extract)")
	}
}

func recoverBSCSnapshotMarker(cfg Config) bool {
	marker := strings.TrimSpace(cfg.SnapshotMarker)
	if marker == "" || fileExists(marker) {
		return fileExists(marker)
	}
	if !bscOfficialExtractPinned(cfg) {
		return false
	}
	if bscOfficialSnapshotRunning(cfg) {
		return false
	}
	if st := systemctlActive(cfg.SnapshotService); st == "active" || st == "activating" {
		return false
	}
	_ = os.WriteFile(marker, []byte(time.Now().UTC().Format(time.RFC3339Nano)+"\n"), 0o644)
	if data := strings.TrimSpace(cfg.DataDir); data != "" {
		_ = os.WriteFile(bscSnapshotKeepPath(data), []byte("keep\n"), 0o644)
	}
	if svc := strings.TrimSpace(cfg.SnapshotService); svc != "" {
		_ = exec.Command("systemctl", "reset-failed", svc).Run()
	}
	hostLogf("INFO", "system-agent", "snapshot", "recovered marker %s (official extract on disk)", marker)
	bscSnapDiag(cfg, "recovered marker "+marker)
	return true
}

func ensureBSCSnapshotUnit(cfg Config) error {
	env := normalizeEnvName(cfg.Env)
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		data = fmt.Sprintf("/data/bsc/%s", env)
	}
	opt := strings.TrimSpace(cfg.OptDir)
	if opt == "" {
		opt = fmt.Sprintf("/opt/bsc/%s", env)
	}
	snapDir := filepath.Join(data, "snapshots")
	marker := cfg.SnapshotMarker
	if marker == "" {
		marker = filepath.Join(data, ".snapshot-ready")
	}
	stateJSON := cfg.SnapshotState
	if stateJSON == "" {
		stateJSON = filepath.Join(data, ".snapshot-state.json")
	}
	logPath := cfg.SnapshotLog
	if logPath == "" {
		logPath = fmt.Sprintf("/var/log/bsc/%s-snapshot.log", env)
	}
	_ = os.MkdirAll(filepath.Dir(logPath), 0o755)
	_ = os.MkdirAll(filepath.Join(opt, "bin"), 0o755)
	_ = os.MkdirAll(snapDir, 0o755)

	flavor := bscSnapshotFlavor(cfg)
	prefix := bscSnapshotNamePrefix(env)
	unitName := fmt.Sprintf("bsc-%s-snapshot.service", env)
	scriptPath := filepath.Join(opt, "bin", "bsc-official-snapshot.sh")
	st := systemctlActive(unitName)
	run := bscOfficialSnapshotRunning(cfg)
	if st == "active" || st == "activating" || run {
		bscSnapDiagThrottle(cfg, fmt.Sprintf("ensure skip rewrite unit=%s state=%s aria2/script_running=%v fp=%s",
			unitName, st, run, bscFileFingerprint(scriptPath)))
		return nil
	}
	oldFP := bscFileFingerprint(scriptPath)
	if old, err := os.ReadFile(scriptPath); err == nil && len(old) > 0 {
		prev := filepath.Join(filepath.Dir(logPath), env+"-official-snapshot.sh.prev")
		_ = os.WriteFile(prev, old, 0o644)
	}
	body := renderBSCSnapshotHealScript(env, data, snapDir, opt, marker, stateJSON, logPath, flavor, prefix)
	tmp := scriptPath + ".tmp"
	if err := os.WriteFile(tmp, []byte(body), 0o755); err != nil {
		return err
	}
	if err := os.Rename(tmp, scriptPath); err != nil {
		return err
	}
	hostLogf("INFO", "system-agent", "snapshot", "wrote %s", scriptPath)
	bscSnapDiag(cfg, fmt.Sprintf("ensure wrote %s old=%s new=%s", scriptPath, oldFP, bscFileFingerprint(scriptPath)))
	unitPath := filepath.Join("/etc/systemd/system", unitName)
	unitBody := fmt.Sprintf(`[Unit]
Description=BSC %s official snapshot (bnb-chain/bsc-snapshots %s)
After=network-online.target
Wants=network-online.target
ConditionPathExists=!%s

[Service]
Type=oneshot
User=root
Nice=10
TimeoutStartSec=0
ExecStart=%s
RemainAfterExit=yes
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
`, env, flavor, marker, scriptPath)
	if err := os.WriteFile(unitPath, []byte(unitBody), 0o644); err != nil {
		return err
	}
	_ = exec.Command("systemctl", "daemon-reload").Run()
	return nil
}

func renderBSCSnapshotHealScript(env, data, snapDir, opt, marker, stateJSON, logPath, flavor, prefix string) string {
	pruneFlag := ""
	if flavor != "full" {
		pruneFlag = " -p"
	}
	return fmt.Sprintf(`#!/bin/bash
set -euo pipefail
ENV=%q
NODE_UNIT="bsc-${ENV}.service"
DATA=%q
SNAP=%q
OPT=%q
MARKER=%q
STATE=%q
LOG=%q
FLAVOR=%q
PREFIX=%q
README=%q
FETCH=%q
mkdir -p "$(dirname "$LOG")" "$DATA" "$SNAP" "$OPT/bin"
if ! command -v aria2c >/dev/null || ! command -v lz4 >/dev/null; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq aria2 lz4
fi
	echo "{\"phase\":\"download\",\"pct\":0,\"detail\":\"BSC official snapshot · resolving latest\",\"flavor\":\"$FLAVOR\",\"updated_at\":\"$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)\"}" >"$STATE"
SELF="${BASH_SOURCE[0]:-$0}"
snapdiag() { echo "SNAPSHOT_DIAG $*" | tee -a "$LOG"; }
pin_keep() {
  if [ -d "$DATA/geth/chaindata" ]; then
    echo keep >"$DATA/.snapshot-keep"
    snapdiag "pin_keep chaindata"
  fi
}
snapdiag "begin pid=$$ ppid=$PPID toolkit=%s self=$SELF inode=$(stat -c %%i "$SELF" 2>/dev/null || echo ?) size=$(stat -c %%s "$SELF" 2>/dev/null || echo ?) lines=$(wc -l <"$SELF" 2>/dev/null || echo ?) sha=$(sha256sum "$SELF" 2>/dev/null | awk '{print $1}')"
snapdiag "env=$ENV data=$DATA snap=$SNAP marker=$MARKER ancient=$([ -d "$DATA/geth/chaindata/ancient/chain" ] && echo yes || echo no) unit=$(systemctl is-active bsc-${ENV}-snapshot.service 2>/dev/null || true)"
cp -p "$SELF" "${LOG}.running.$$" 2>/dev/null || true
trap 'snapdiag "ERR line=$LINENO cmd=$BASH_COMMAND rc=$?"' ERR
trap 'snapdiag "EXIT rc=$? line=$LINENO self_sha=$(sha256sum "$SELF" 2>/dev/null | cut -d\" \" -f1) self_lines=$(wc -l <"$SELF" 2>/dev/null || echo ?)"' EXIT
(
  while kill -0 $$ 2>/dev/null; do
    sleep 15
    if grep -qE 'Extraction complete' "$LOG" 2>/dev/null && [ -d "$DATA/geth/chaindata" ]; then
      pin_keep
    fi
    [ -f "$MARKER" ] && break
  done
) &
systemctl stop "$NODE_UNIT" 2>/dev/null || true
# Extract already on disk (oneshot died after tar, or script rewritten mid-run). Do not wipe.
if [ -f "$DATA/.snapshot-keep" ] && [ -d "$DATA/geth/chaindata" ]; then
  snapdiag "recover_extract writing marker"
  echo "$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ) INFO extract already on disk — writing marker" | tee -a "$LOG"
  date -u +"%%Y-%%m-%%dT%%H:%%M:%%SZ" >"$MARKER"
  echo "{\"phase\":\"done\",\"pct\":100,\"detail\":\"BSC official snapshot ready (recovered extract)\",\"flavor\":\"$FLAVOR\",\"updated_at\":\"$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)\"}" >"$STATE"
  chown -R nodeop:nodeop "$DATA" 2>/dev/null || true
  exit 0
fi
if [ ! -f "$MARKER" ] && [ ! -f "$DATA/.snapshot-keep" ]; then
  snapdiag "wipe_geth no_marker_no_keep"
  rm -rf "$DATA/geth"
fi
chown -R nodeop:nodeop "$DATA" "$SNAP" 2>/dev/null || true
echo "$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ) INFO  [system-agent] download snapshot %s  bsc/$ENV $FLAVOR" >>/var/log/rpcnode.log
NAME=""
for i in 1 2 3; do
  MD="$(curl -fsSL --connect-timeout 20 --max-time 60 "$README" || true)"
  NAME="$(printf '%%s\n' "$MD" | grep -oE "${PREFIX}-[0-9]{8}" | head -1 || true)"
  if [ -n "$NAME" ]; then break; fi
  sleep 8
done
if [ -z "$NAME" ]; then
  echo "$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ) ERROR cannot resolve latest $PREFIX from README" | tee -a "$LOG"
  echo "{\"phase\":\"error\",\"pct\":0,\"error\":\"cannot resolve latest $PREFIX from bsc-snapshots README\",\"updated_at\":\"$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)\"}" >"$STATE"
  exit 1
fi
echo "$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ) START official $NAME flavor=$FLAVOR" | tee -a "$LOG"
curl -fsSL --connect-timeout 20 --max-time 60 "$FETCH" -o "$OPT/bin/fetch-snapshot.sh"
chmod 755 "$OPT/bin/fetch-snapshot.sh"
set -o pipefail
cd "$SNAP"
bash "$OPT/bin/fetch-snapshot.sh" -d -e -c%s --auto-delete -D "$SNAP" -E "$DATA" "$NAME" 2>&1 | tee -a "$LOG"
snapdiag "fetch_done rc=${PIPESTATUS[0]:-?} ancient=$([ -d "$DATA/geth/chaindata/ancient/chain" ] && echo yes || echo no) self_sha=$(sha256sum "$SELF" 2>/dev/null | awk '{print $1}') self_lines=$(wc -l <"$SELF" 2>/dev/null || echo ?)"
pin_keep
if [ -d "$DATA/geth/chaindata" ]; then
  date -u +"%%Y-%%m-%%dT%%H:%%M:%%SZ" >"$MARKER"
  snapdiag "marker_after_fetch"
fi
if [ ! -d "$DATA/geth/chaindata" ]; then
  echo "$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ) ERROR extract missing $DATA/geth/chaindata" | tee -a "$LOG"
  echo "{\"phase\":\"error\",\"pct\":0,\"error\":\"extract missing geth/chaindata\",\"updated_at\":\"$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)\"}" >"$STATE"
  exit 1
fi
date -u +"%%Y-%%m-%%dT%%H:%%M:%%SZ" >"$MARKER"
echo "{\"phase\":\"done\",\"pct\":100,\"detail\":\"BSC official snapshot ready · $NAME\",\"flavor\":\"$FLAVOR\",\"name\":\"$NAME\",\"updated_at\":\"$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)\"}" >"$STATE"
chown -R nodeop:nodeop "$DATA" 2>/dev/null || true
echo "$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ) DONE $NAME" | tee -a "$LOG"
`, env, data, snapDir, opt, marker, stateJSON, logPath, flavor, prefix, bscSnapshotsREADME, bscFetchSnapshotURL, agentVersion(), bscSnapshotsRepo, pruneFlag)
}

func stopBSCSnapshotTools(cfg Config) {
	needle := strings.TrimSpace(cfg.DataDir)
	if needle == "" {
		needle = "bsc-" + normalizeEnvName(cfg.Env)
	}
	_ = exec.Command("pkill", "-f", "aria2c.*"+needle).Run()
	_ = exec.Command("pkill", "-f", "fetch-snapshot.*"+needle).Run()
	_ = exec.Command("pkill", "-f", "bsc-official-snapshot").Run()
}
