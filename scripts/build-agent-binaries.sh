#!/usr/bin/env bash
# Cross-compile host agents and stage them into the connect site tree.
#
#   ./scripts/build-agent-binaries.sh
#
# Writes:
#   dist/binaries/                          scratch
#   frontend/connect/public/install/        live origin (rpcnode.dev/install/...)
#
#   agent.sh
#   uninstall-agents.sh
#   rpcnode-agent-watchdog.sh
#   TOOLKIT_VERSION
#   binaries/rpcnode-*-agent-<os>-<arch>
#   binaries/sha256sums.txt
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MONOREPO_ROOT="$(cd "$ROOT/../../../.." && pwd 2>/dev/null || true)"
OUT_DIR="${OUT_DIR:-$ROOT/dist/binaries}"
# CONNECT_PUBLIC_INSTALL set (even empty) wins — RpcNode.app Settings.
if [[ "${CONNECT_PUBLIC_INSTALL+x}" == "x" ]]; then
  CONNECT_INSTALL="$CONNECT_PUBLIC_INSTALL"
else
  CONNECT_INSTALL="${MONOREPO_ROOT}/frontend/connect/public/install"
fi
# linux first (prod hosts); darwin for a Mac agent.
TARGETS="${TARGETS:-linux/amd64 linux/arm64 darwin/amd64 darwin/arm64}"

API_PKG="$ROOT/api-agent"
SYS_PKG="$ROOT/system-agent"

log() { printf '+ %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

command -v go >/dev/null 2>&1 || die "go is required"
[[ -d "$API_PKG" && -d "$SYS_PKG" ]] || die "run from toolkit repo (missing api-agent / system-agent)"

mkdir -p "$OUT_DIR"
rm -f "$OUT_DIR"/rpcnode-*-* "$OUT_DIR"/sha256sums.txt 2>/dev/null || true

VERSION="$(tr -d '[:space:]' <"$ROOT/TOOLKIT_VERSION" 2>/dev/null || echo unknown)"
[[ -n "$VERSION" && "$VERSION" != "unknown" ]] || die "TOOLKIT_VERSION missing/empty"
LDFLAGS="-s -w -X main.toolkitVersion=${VERSION}"

built=0
for target in $TARGETS; do
  goos="${target%/*}"
  goarch="${target#*/}"
  api_out="$OUT_DIR/rpcnode-api-agent-${goos}-${goarch}"
  sys_out="$OUT_DIR/rpcnode-system-agent-${goos}-${goarch}"

  log "building api-agent ${goos}/${goarch}"
  (
    cd "$API_PKG"
    CGO_ENABLED=0 GOOS="$goos" GOARCH="$goarch" go build -trimpath -ldflags="$LDFLAGS" -o "$api_out" .
  ) || die "api-agent build failed for ${goos}/${goarch}"

  log "building system-agent ${goos}/${goarch}"
  (
    cd "$SYS_PKG"
    CGO_ENABLED=0 GOOS="$goos" GOARCH="$goarch" go build -trimpath -ldflags="$LDFLAGS" -o "$sys_out" .
  ) || die "system-agent build failed for ${goos}/${goarch}"

  chmod 755 "$api_out" "$sys_out"
  built=$((built + 2))
done

log "writing sha256sums.txt"
(
  cd "$OUT_DIR"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 rpcnode-*-* | sort -k2 >sha256sums.txt
  else
    sha256sum rpcnode-*-* | sort -k2 >sha256sums.txt
  fi
)

stage_connect() {
  local dest="$1"
  mkdir -p "${dest}/binaries"
  cp -f "$ROOT/install/agent.sh" "${dest}/agent.sh"
  chmod 755 "${dest}/agent.sh"
  if [[ -f "$ROOT/install/uninstall-agents.sh" ]]; then
    cp -f "$ROOT/install/uninstall-agents.sh" "${dest}/uninstall-agents.sh"
    chmod 755 "${dest}/uninstall-agents.sh"
  fi
  if [[ -f "$ROOT/install/rpcnode-agent-watchdog.sh" ]]; then
    cp -f "$ROOT/install/rpcnode-agent-watchdog.sh" "${dest}/rpcnode-agent-watchdog.sh"
    chmod 755 "${dest}/rpcnode-agent-watchdog.sh"
  fi
  printf '%s\n' "$VERSION" >"${dest}/TOOLKIT_VERSION"
  if [[ -f "$ROOT/install/donate.json" ]]; then
    cp -f "$ROOT/install/donate.json" "${dest}/donate.json"
  fi
  cp -f "$OUT_DIR"/rpcnode-api-agent-* "$OUT_DIR"/rpcnode-system-agent-* "${dest}/binaries/"
  [[ -f "$OUT_DIR/sha256sums.txt" ]] && cp -f "$OUT_DIR/sha256sums.txt" "${dest}/binaries/"
  chmod 755 "${dest}/binaries"/rpcnode-* 2>/dev/null || true
  log "staged ${dest}  TOOLKIT_VERSION=${VERSION}"
  ls -lh "${dest}/binaries"
}

log "built ${built} binaries -> $OUT_DIR  version=${VERSION}"
ls -lh "$OUT_DIR"

if [[ -n "${CONNECT_INSTALL}" ]]; then
  stage_connect "$CONNECT_INSTALL"
fi

cat <<EOF

ok  ${VERSION}
    ${CONNECT_INSTALL}/agent.sh
    ${CONNECT_INSTALL}/binaries/

agent.sh downloads from https://rpcnode.dev/install/binaries/
redeploy connect for the site to serve the new files.

linux-only:
  TARGETS='linux/amd64 linux/arm64' ./scripts/build-agent-binaries.sh
EOF
