#!/usr/bin/env bash
# Shared helpers for rpcnodectl. Stack: Go agents + bash CLI + Docker (no Python).
set -euo pipefail

if [[ -t 1 ]]; then
  C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_BLU=$'\033[34m'; C_BLD=$'\033[1m'; C_OFF=$'\033[0m'
else
  C_RED=""; C_GRN=""; C_YEL=""; C_BLU=""; C_BLD=""; C_OFF=""
fi

log()  { printf '%s\n' "$*"; }
info() { printf '%s[i]%s %s\n' "$C_BLU" "$C_OFF" "$*"; }
ok()   { printf '%s[ok]%s %s\n' "$C_GRN" "$C_OFF" "$*"; }
warn() { printf '%s[!]%s %s\n' "$C_YEL" "$C_OFF" "$*" >&2; }
err()  { printf '%s[x]%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; }
die()  { err "$*"; exit 1; }

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    die "Run as root: sudo $0 …"
  fi
}

require_jq() {
  command -v jq >/dev/null 2>&1 || die "jq required (apt install jq) — toolkit is Go+bash, not Python"
}

ts() { date -u +"%Y-%m-%dT%H:%M:%SZ"; }

upgrade_log() {
  local file="$1"; shift
  mkdir -p "$(dirname "$file")"
  printf '%s %s\n' "$(ts)" "$*" | tee -a "$file" >/dev/null
}

# Write JSON via jq (stdout or to file as $1 when path given as first arg after --).
# Usage: write_json_file /path/to/file --arg k v ... '{...}'
write_json_file() {
  local dest="$1"; shift
  require_jq
  mkdir -p "$(dirname "$dest")"
  local tmp="${dest}.tmp.$$"
  jq -n "$@" >"$tmp"
  mv -f "$tmp" "$dest"
}

json_escape() {
  require_jq
  jq -Rn --arg s "$1" '$s'
}
