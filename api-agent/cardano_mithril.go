package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

const mithrilClientVersion = "2630.0"

type cardanoMithrilNet struct {
	Network     string
	Aggregator  string
	GenesisKey  string
	AncillaryKey string
}

func cardanoMithrilParams(env string) cardanoMithrilNet {
	switch normalizeEnv(env) {
	case "preprod":
		return cardanoMithrilNet{
			Network:      "preprod",
			Aggregator:   "https://aggregator.release-preprod.api.mithril.network/aggregator",
			GenesisKey:   "https://raw.githubusercontent.com/IntersectMBO/mithril/main/mithril-infra/configuration/release-preprod/genesis.vkey",
			AncillaryKey: "https://raw.githubusercontent.com/IntersectMBO/mithril/main/mithril-infra/configuration/release-preprod/ancillary.vkey",
		}
	case "preview":
		return cardanoMithrilNet{
			Network:      "preview",
			Aggregator:   "https://aggregator.pre-release-preview.api.mithril.network/aggregator",
			GenesisKey:   "https://raw.githubusercontent.com/IntersectMBO/mithril/main/mithril-infra/configuration/pre-release-preview/genesis.vkey",
			AncillaryKey: "https://raw.githubusercontent.com/IntersectMBO/mithril/main/mithril-infra/configuration/pre-release-preview/ancillary.vkey",
		}
	default:
		return cardanoMithrilNet{
			Network:      "mainnet",
			Aggregator:   "https://aggregator.release-mainnet.api.mithril.network/aggregator",
			GenesisKey:   "https://raw.githubusercontent.com/IntersectMBO/mithril/main/mithril-infra/configuration/release-mainnet/genesis.vkey",
			AncillaryKey: "https://raw.githubusercontent.com/IntersectMBO/mithril/main/mithril-infra/configuration/release-mainnet/ancillary.vkey",
		}
	}
}

func mithrilClientTarballURL() string {
	arch := "linux-x64"
	switch runtimeGOARCH() {
	case "arm64", "aarch64":
		arch = "linux-arm64"
	}
	return fmt.Sprintf(
		"https://github.com/IntersectMBO/mithril/releases/download/%s/mithril-%s-%s.tar.gz",
		mithrilClientVersion, mithrilClientVersion, arch,
	)
}

func ensureMithrilClientInstalled(optPath string) (string, error) {
	link := filepath.Join(optPath, "bin", "mithril-client")
	if fileExists(link) {
		return link, nil
	}
	if p, err := exec.LookPath("mithril-client"); err == nil && p != "" {
		_ = os.MkdirAll(filepath.Dir(link), 0o755)
		_ = os.Remove(link)
		_ = os.Symlink(p, link)
		return p, nil
	}

	url := preferVendoredArtifact("cardano", "mainnet", mithrilClientTarballURL())
	tmp := filepath.Join(os.TempDir(), "mithril-"+mithrilClientVersion+".tgz")
	logDownload("GET", url, "mithril dest="+tmp)
	extractDir := filepath.Join(os.TempDir(), "rpcnode-mithril-"+mithrilClientVersion)
	_ = os.RemoveAll(extractDir)
	destBin := filepath.Join(optPath, "bin")
	_ = os.MkdirAll(destBin, 0o755)

	cmd := exec.Command("bash", "-lc", fmt.Sprintf(
		`set -euo pipefail
curl -fsSL --connect-timeout 30 --max-time 600 -o %q %q
mkdir -p %q
tar -xzf %q -C %q
BIN=$(find %q -type f -name mithril-client | head -1)
test -n "$BIN"
install -m 755 "$BIN" %q/mithril-client
rm -rf %q %q
`, tmp, url, extractDir, tmp, extractDir, extractDir, destBin, extractDir, tmp))
	out, err := cmd.CombinedOutput()
	logDownloadDone("GET", url, "mithril dest="+tmp, out, err)
	if err != nil {
		return "", fmt.Errorf("install mithril-client %s: %v (%s)", mithrilClientVersion, err, strings.TrimSpace(string(out)))
	}
	if !fileExists(link) {
		return "", fmt.Errorf("mithril-client missing after install at %s", link)
	}
	return link, nil
}

func ensureCardanoSnapshotUnit(prof networkPortProfile, clientBin string) (unitPath, scriptPath string, err error) {
	env := normalizeEnv(prof.Env)
	data := prof.DataPath
	if data == "" {
		data = fmt.Sprintf("/data/cardano/%s", env)
	}
	opt := prof.OptPath
	if opt == "" {
		opt = fmt.Sprintf("/opt/cardano/%s", env)
	}
	m := cardanoMithrilParams(env)
	marker := filepath.Join(data, ".snapshot-ready")
	stateJSON := filepath.Join(data, ".snapshot-state.json")
	logPath := fmt.Sprintf("/var/log/cardano/%s-snapshot.log", env)
	_ = os.MkdirAll(filepath.Dir(logPath), 0o755)
	_ = os.MkdirAll(filepath.Join(opt, "bin"), 0o755)

	scriptPath = filepath.Join(opt, "bin", "cardano-mithril-snapshot.sh")
	if m.Aggregator != "" {
		logDownload("snapshot", m.Aggregator, "cardano/"+env+" mithril")
	}
	script := fmt.Sprintf(`#!/bin/bash
set -euo pipefail
ENV=%q
NODE_UNIT="cardano-${ENV}.service"
CLIENT=%q
DATA=%q
DB="$DATA/db"
WORK="$DATA/mithril-dl"
MARKER=%q
STATE=%q
LOG=%q
NETWORK=%q
AGG=%q
GENESIS_URL=%q
ANCILLARY_URL=%q
mkdir -p "$(dirname "$LOG")" "$DATA" "$WORK"
echo "{\"phase\":\"download\",\"pct\":0,\"detail\":\"Mithril · fetching keys\",\"updated_at\":\"$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)\"}" >"$STATE"
systemctl stop "$NODE_UNIT" 2>/dev/null || true
systemctl stop "cardano-ogmios-${ENV}.service" 2>/dev/null || true
export CARDANO_NETWORK="$NETWORK"
export AGGREGATOR_ENDPOINT="$AGG"
export GENESIS_VERIFICATION_KEY="$(curl -fsSL --connect-timeout 20 --max-time 60 "$GENESIS_URL")"
export ANCILLARY_VERIFICATION_KEY="$(curl -fsSL --connect-timeout 20 --max-time 60 "$ANCILLARY_URL")"
test -n "$GENESIS_VERIFICATION_KEY"
test -n "$ANCILLARY_VERIFICATION_KEY"
if [ ! -f "$MARKER" ]; then
  find "$WORK" -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || true
  if [ ! -d "$DB/immutable" ] && [ ! -d "$DB/ledger" ]; then
    find "$DB" -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || true
  fi
fi
echo "$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ) START mithril-client cardano-db download latest --include-ancillary" | tee -a "$LOG"
echo "$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ) INFO  [api-agent] download snapshot $AGG  cardano/$ENV mithril" >>/var/log/rpcnode.log
set -o pipefail
"$CLIENT" -vv cardano-db download latest \
  --include-ancillary \
  --download-dir "$WORK" \
  --allow-override \
  --aggregator-endpoint "$AGG" \
  2>&1 | tee -a "$LOG"
# Official client writes db/ (sometimes under a digest dir).
SRC=""
if [ -d "$WORK/db" ]; then
  SRC="$WORK/db"
else
  SRC="$(find "$WORK" -type d -name db | head -1 || true)"
fi
test -n "$SRC"
mkdir -p "$DB"
# Move restored files into the unit --database-path.
if [ "$SRC" != "$DB" ]; then
  find "$SRC" -mindepth 1 -maxdepth 1 -exec mv -f {} "$DB"/ \;
fi
rm -rf "$WORK"
chown -R nodeop:nodeop "$DATA" 2>/dev/null || true
date -u +"%%Y-%%m-%%dT%%H:%%M:%%SZ" >"$MARKER"
echo "{\"phase\":\"done\",\"pct\":100,\"detail\":\"Mithril Cardano DB ready\",\"updated_at\":\"$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)\"}" >"$STATE"
chown nodeop:nodeop "$MARKER" "$STATE" 2>/dev/null || true
echo "$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ) DONE" | tee -a "$LOG"
`, env, clientBin, data, marker, stateJSON, logPath, m.Network, m.Aggregator, m.GenesisKey, m.AncillaryKey)
	if err = os.WriteFile(scriptPath, []byte(script), 0o755); err != nil {
		return "", "", err
	}

	unitName := fmt.Sprintf("cardano-%s-snapshot.service", env)
	unitPath = filepath.Join("/etc/systemd/system", unitName)
	body := fmt.Sprintf(`[Unit]
Description=Cardano %s Mithril snapshot (official cardano-db)
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
`, env, marker, scriptPath)
	if err = os.WriteFile(unitPath, []byte(body), 0o644); err != nil {
		return "", "", err
	}
	_ = exec.Command("systemctl", "daemon-reload").Run()
	return unitPath, scriptPath, nil
}
