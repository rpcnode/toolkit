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
	bscSnapshotsRepo     = "https://github.com/bnb-chain/bsc-snapshots"
	bscSnapshotsREADME   = "https://raw.githubusercontent.com/bnb-chain/bsc-snapshots/main/README.md"
	bscFetchSnapshotURL  = "https://raw.githubusercontent.com/bnb-chain/bsc-snapshots/main/dist/fetch-snapshot.sh"
	bscSnapshotPrefixMN  = "mainnet-geth-pbss"
	bscSnapshotPrefixTN  = "testnet-geth-pbss"
)

func bscSnapshotFlavor(env string) string {
	opts := loadInstallOptions("bsc", normalizeEnv(env))
	if strings.EqualFold(strings.TrimSpace(opts["snapshot"]), "full") {
		return "full"
	}
	return "pruned"
}

func bscSnapshotNamePrefix(env string) string {
	if normalizeEnv(env) == "testnet" {
		return bscSnapshotPrefixTN
	}
	return bscSnapshotPrefixMN
}

func bscOfficialSnapshotProcRunning() bool {
	out, err := exec.Command("bash", "-lc",
		`pgrep -af '[a]ria2c .*/bsc/|[f]etch-snapshot.sh|[b]sc-official-snapshot.sh' | head -1`).Output()
	return err == nil && strings.TrimSpace(string(out)) != ""
}

func startBSCOfficialSnapshot(env string) error {
	env = normalizeEnv(env)
	prof := lookupPortProfile("bsc", env)
	if _, _, err := ensureBSCSnapshotUnit(prof, ""); err != nil {
		return err
	}
	data := prof.DataPath
	if data == "" {
		data = fmt.Sprintf("/data/bsc/%s", env)
	}
	marker := filepath.Join(data, ".snapshot-ready")
	if recoverBSCSnapshotMarkerOnHost(env, data, marker) {
		return nil
	}
	unit := fmt.Sprintf("bsc-%s-snapshot.service", env)
	_ = exec.Command("systemctl", "reset-failed", unit).Run()
	out, err := exec.Command("systemctl", "start", "--no-block", unit).CombinedOutput()
	if err != nil {
		if bscOfficialSnapshotProcRunning() {
			return nil
		}
		st := strings.TrimSpace(string(mustCmdOut("systemctl", "is-active", unit)))
		if st == "active" || st == "activating" {
			return nil
		}
		return fmt.Errorf("systemctl start %s: %v (%s)", unit, err, strings.TrimSpace(string(out)))
	}
	hostLogf("INFO", "api-agent", "snapshot", "start %s (start deferred until marker)", unit)
	return nil
}

func recoverBSCSnapshotMarkerOnHost(env, data, marker string) bool {
	if fileExists(marker) {
		return true
	}
	if bscOfficialSnapshotProcRunning() {
		return false
	}
	chain := filepath.Join(data, "geth", "chaindata")
	st, err := os.Stat(chain)
	if err != nil || !st.IsDir() {
		return false
	}
	keep := fileExists(filepath.Join(data, ".snapshot-keep"))
	logHas := false
	if b, errRead := os.ReadFile(fmt.Sprintf("/var/log/bsc/%s-snapshot.log", normalizeEnv(env))); errRead == nil {
		logHas = strings.Contains(strings.ToLower(string(b)), "extraction complete")
	}
	if !keep && !logHas {
		return false
	}
	_ = os.WriteFile(marker, []byte(time.Now().UTC().Format(time.RFC3339Nano)+"\n"), 0o644)
	_ = os.WriteFile(filepath.Join(data, ".snapshot-keep"), []byte("keep\n"), 0o644)
	hostLogf("INFO", "api-agent", "snapshot", "recovered marker %s (official extract on disk)", marker)
	appendBSCSnapDiag(fmt.Sprintf("/var/log/bsc/%s-snapshot.log", normalizeEnv(env)), "recovered marker "+marker)
	return fileExists(marker)
}

func appendBSCSnapDiag(logPath, msg string) {
	msg = strings.TrimSpace(msg)
	if msg == "" || strings.TrimSpace(logPath) == "" {
		return
	}
	_ = os.MkdirAll(filepath.Dir(logPath), 0o755)
	f, err := os.OpenFile(logPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	_, _ = fmt.Fprintf(f, "%s SNAPSHOT_DIAG %s\n", time.Now().UTC().Format(time.RFC3339), msg)
	_ = f.Close()
}

// ensureBSCSnapshotUnit — oneshot official fetch-snapshot.sh (aria2 + lz4).
// Marker: /data/bsc/<env>/.snapshot-ready. Node must be stopped; genesis IBD is wiped.
func ensureBSCSnapshotUnit(prof networkPortProfile, snapDir string) (unitPath, scriptPath string, err error) {
	env := normalizeEnv(prof.Env)
	data := prof.DataPath
	if data == "" {
		data = fmt.Sprintf("/data/bsc/%s", env)
	}
	opt := prof.OptPath
	if opt == "" {
		opt = fmt.Sprintf("/opt/bsc/%s", env)
	}
	if strings.TrimSpace(snapDir) == "" {
		snapDir = filepath.Join(data, "snapshots")
	}
	marker := filepath.Join(data, ".snapshot-ready")
	stateJSON := filepath.Join(data, ".snapshot-state.json")
	logPath := fmt.Sprintf("/var/log/bsc/%s-snapshot.log", env)
	_ = os.MkdirAll(filepath.Dir(logPath), 0o755)
	_ = os.MkdirAll(filepath.Join(opt, "bin"), 0o755)
	_ = os.MkdirAll(snapDir, 0o755)

	flavor := bscSnapshotFlavor(env)
	prefix := bscSnapshotNamePrefix(env)
	scriptPath = filepath.Join(opt, "bin", "bsc-official-snapshot.sh")
	unitName := fmt.Sprintf("bsc-%s-snapshot.service", env)
	unitState := strings.TrimSpace(string(mustCmdOut("systemctl", "is-active", unitName)))
	run := bscOfficialSnapshotProcRunning()
	if unitState == "active" || unitState == "activating" || run {
		msg := fmt.Sprintf("api-agent ensure skip rewrite unit=%s state=%s script_running=%v path=%s",
			unitName, unitState, run, scriptPath)
		hostLogf("INFO", "api-agent", "snapshot", "%s", msg)
		appendBSCSnapDiag(logPath, msg)
		unitPath = filepath.Join("/etc/systemd/system", unitName)
		return unitPath, scriptPath, nil
	}
	if old, errRead := os.ReadFile(scriptPath); errRead == nil && len(old) > 0 {
		_ = os.WriteFile(filepath.Join(filepath.Dir(logPath), env+"-official-snapshot.sh.prev"), old, 0o644)
	}
	script := renderBSCSnapshotScript(env, data, snapDir, opt, marker, stateJSON, logPath, flavor, prefix)
	if err = os.WriteFile(scriptPath, []byte(script), 0o755); err != nil {
		return "", "", err
	}
	hostLogf("INFO", "api-agent", "snapshot", "ensure wrote %s bytes=%d", scriptPath, len(script))
	appendBSCSnapDiag(logPath, fmt.Sprintf("api-agent ensure wrote %s bytes=%d", scriptPath, len(script)))

	unitPath = filepath.Join("/etc/systemd/system", unitName)
	body := fmt.Sprintf(`[Unit]
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
	if err = os.WriteFile(unitPath, []byte(body), 0o644); err != nil {
		return "", "", err
	}
	_ = exec.Command("systemctl", "daemon-reload").Run()
	return unitPath, scriptPath, nil
}

func renderBSCSnapshotScript(env, data, snapDir, opt, marker, stateJSON, logPath, flavor, prefix string) string {
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
echo "$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ) INFO  [api-agent] download snapshot %s  bsc/$ENV $FLAVOR" >>/var/log/rpcnode.log
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
(
  while kill -0 $$ 2>/dev/null; do
    sleep 8
    [ -f "$MARKER" ] && break
    PCT=0
    DETAIL="BSC official snapshot · $NAME"
    ARIA="$(tr '\r' '\n' <"$LOG" 2>/dev/null | grep -E '\[#[0-9a-fA-F]+' | tail -1 || true)"
    if [ -n "$ARIA" ]; then
      CUR="$(printf '%%s' "$ARIA" | grep -oE '\([0-9]+%%\)' | tail -1 | tr -dc '0-9' || true)"
      [ -n "$CUR" ] && PCT="$CUR"
      DETAIL="aria2 · $NAME · ${PCT}%%"
    fi
    CSV="$(ls -1 "$SNAP"/${PREFIX}-*.csv 2>/dev/null | head -1 || true)"
    if [ -n "$CSV" ]; then
      TOTAL="$(grep -cve '^filename' -e '^$' "$CSV" || echo 0)"
      DONE="$(tr '\r' '\n' <"$LOG" 2>/dev/null | grep -cE 'Download complete:|Skipping .+ already downloaded|Extraction complete' || true)"
      if [ "${TOTAL:-0}" -gt 0 ]; then
        if [ "${DONE:-0}" -gt "$TOTAL" ]; then DONE="$TOTAL"; fi
        BASE=$((DONE * 100 / TOTAL))
        if [ "$BASE" -gt 99 ]; then BASE=99; fi
        if [ "${PCT:-0}" -gt 0 ] && [ "$DONE" -lt "$TOTAL" ]; then
          PCT=$((BASE + PCT / TOTAL))
        else
          PCT="$BASE"
        fi
        if [ "$PCT" -gt 99 ]; then PCT=99; fi
        DETAIL="parts $DONE/$TOTAL · $NAME · ${PCT}%%"
      fi
    fi
    if grep -qE 'Extracting ' "$LOG" 2>/dev/null && ! grep -qE '\[#[0-9a-fA-F]+' <(tr '\r' '\n' <"$LOG" | tail -20); then
      DETAIL="extracting · $NAME · ${PCT}%%"
    fi
    if grep -qE 'Extraction complete' "$LOG" 2>/dev/null && [ -d "$DATA/geth/chaindata" ]; then
      pin_keep
    fi
    echo "{\"phase\":\"download\",\"pct\":$PCT,\"detail\":\"$DETAIL\",\"flavor\":\"$FLAVOR\",\"name\":\"$NAME\",\"updated_at\":\"$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)\"}" >"$STATE"
  done
) &
WATCH=$!
trap 'kill $WATCH 2>/dev/null || true; snapdiag "EXIT rc=$? line=$LINENO self_sha=$(sha256sum "$SELF" 2>/dev/null | cut -d\" \" -f1) self_lines=$(wc -l <"$SELF" 2>/dev/null || echo ?)"' EXIT
set -o pipefail
cd "$SNAP"
# pruneancient flag is appended by fetch-snapshot when flavor is pruned.
# Do not pass a snapshot name that already has that suffix.
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
kill $WATCH 2>/dev/null || true
date -u +"%%Y-%%m-%%dT%%H:%%M:%%SZ" >"$MARKER"
echo "{\"phase\":\"done\",\"pct\":100,\"detail\":\"BSC official snapshot ready · $NAME\",\"flavor\":\"$FLAVOR\",\"name\":\"$NAME\",\"updated_at\":\"$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)\"}" >"$STATE"
chown -R nodeop:nodeop "$DATA" 2>/dev/null || true
echo "$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ) DONE $NAME" | tee -a "$LOG"
`, env, data, snapDir, opt, marker, stateJSON, logPath, flavor, prefix, bscSnapshotsREADME, bscFetchSnapshotURL, agentVersion(), bscSnapshotsRepo, pruneFlag)
}
