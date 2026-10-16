#!/usr/bin/env bash
# snapshot-to-spaces.sh — copy a snapshot archive from a direct link into DigitalOcean Spaces (S3-compatible).
#
#   ./scripts/snapshot-to-spaces.sh URL [--key PATH] [--public] [--background] [--log FILE] [--dry-run]
#
# The archive is streamed (curl | aws s3 cp -): nothing is written to local disk, so a multi-TiB archive needs no
# scratch space. Run it where bandwidth is cheap and fast — a small DigitalOcean Droplet in the same region as the
# Space (a Function cannot do this, see the notes at the bottom).
#
# Config (environment, or a file given as SPACES_ENV_FILE; keys are never taken from arguments):
#   SPACES_KEY, SPACES_SECRET   Spaces Access Key + Secret Key (Control Panel -> API -> Spaces Keys); also read from
#                               AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY, or asked in a terminal when unset
#   SPACES_BUCKET               Space name
#   SPACES_REGION               e.g. fra1, ams3, nyc3, sgp1   (default: fra1)
#   SPACES_PREFIX               key prefix inside the Space   (default: snapshots)
#
# Options:
#   --key PATH   object key under SPACES_PREFIX (default: the file name from the URL)
#   --public     make the object publicly readable (ACL public-read) so it can be used as a snapshot link
#   --background run detached (survives closing the terminal); progress goes to the log file, watch it with
#                tail -f <log>. Keys are asked/validated first, then the job detaches.
#   --log FILE   log file for --background (default: ./snapshot-to-spaces-<name>.log)
#   --dry-run    only check the link and print what would be done
#
# Progress: a live bar in a terminal; in a log one line per ~20 s (bytes, bar, rate, ETA) when `pv` is installed
# (apt install pv), otherwise one line per percent from curl.
#
# Result: prints the public URL (https://BUCKET.REGION.digitaloceanspaces.com/PREFIX/KEY, or via the CDN host if
# SPACES_CDN_HOST is set) — paste it into the wizard's Snapshot step -> "Custom link".

set -euo pipefail

die() { echo "error: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "$1 is required (apt install $2)"; }

ARGS=("$@")
URL=""
KEY=""
PUBLIC=0
DRY=0
BACKGROUND=0
LOG=""
while [ $# -gt 0 ]; do
  case "$1" in
    --key)     KEY="${2:-}"; [ -n "$KEY" ] || die "--key needs a value"; shift 2 ;;
    --public)  PUBLIC=1; shift ;;
    --dry-run) DRY=1; shift ;;
    --background) BACKGROUND=1; shift ;;
    --log)     LOG="${2:-}"; [ -n "$LOG" ] || die "--log needs a value"; shift 2 ;;
    -h|--help) sed -n '2,34p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)        die "unknown option: $1" ;;
    *)         [ -z "$URL" ] || die "only one URL is accepted"; URL="$1"; shift ;;
  esac
done
[ -n "$URL" ] || die "usage: $0 URL [--key PATH] [--public] [--background] [--log FILE] [--dry-run]"
case "$URL" in http://*|https://*) ;; *) die "URL must be http(s)" ;; esac

if [ -n "${SPACES_ENV_FILE:-}" ]; then
  [ -r "$SPACES_ENV_FILE" ] || die "cannot read SPACES_ENV_FILE=$SPACES_ENV_FILE"
  # shellcheck disable=SC1090
  . "$SPACES_ENV_FILE"
fi
# Access Key pair: from the environment, else asked in a terminal (the secret is typed hidden, never echoed).
SPACES_KEY="${SPACES_KEY:-${AWS_ACCESS_KEY_ID:-}}"
SPACES_SECRET="${SPACES_SECRET:-${AWS_SECRET_ACCESS_KEY:-}}"
if [ -t 0 ] && [ "$DRY" = 0 ]; then
  [ -n "$SPACES_KEY" ] || read -r -p "Spaces Access Key: " SPACES_KEY
  [ -n "$SPACES_SECRET" ] || read -r -s -p "Spaces Secret Key: " SPACES_SECRET && echo
fi
: "${SPACES_KEY:?set SPACES_KEY (Spaces Access Key)}"
: "${SPACES_SECRET:?set SPACES_SECRET (Spaces Secret Key)}"
: "${SPACES_BUCKET:?set SPACES_BUCKET}"
REGION="${SPACES_REGION:-fra1}"
PREFIX="${SPACES_PREFIX:-snapshots}"
ENDPOINT="https://${REGION}.digitaloceanspaces.com"

need curl curl
if [ "$DRY" = 0 ] && ! command -v aws >/dev/null 2>&1; then
  die "aws cli is required. Install it:
  macOS:           brew install awscli
  Ubuntu/Debian:   sudo snap install aws-cli --classic     (apt's awscli is gone on Ubuntu 24.04)
  any Linux x86_64: curl -sSLo /tmp/awscliv2.zip https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip && unzip -q /tmp/awscliv2.zip -d /tmp && sudo /tmp/aws/install"
fi

# --- probe the source: must answer, and we need its size (S3 multipart from a pipe needs --expected-size) ---------
headers="$(curl -sIL --max-time 30 "$URL")" || die "cannot reach $URL"
status="$(printf '%s\n' "$headers" | awk '/^HTTP/{s=$2} END{print s}')"
[ "$status" = "200" ] || die "source answered HTTP ${status:-?} (expected 200)"
SIZE="$(printf '%s\n' "$headers" | awk '/^HTTP/{n=""} tolower($1)=="content-length:"{gsub("\r","",$2); n=$2} END{print n}')"
case "$SIZE" in ''|0|*[!0-9]*) die "source did not report Content-Length — cannot stream it safely" ;; esac

[ -n "$KEY" ] || KEY="$(basename "${URL%%\?*}")"
[ -n "$KEY" ] || die "cannot derive an object name from the URL — pass --key"
OBJECT="${PREFIX:+${PREFIX%/}/}${KEY#/}"

# Spaces allows 10,000 parts per object: size the part so that even a multi-TiB archive fits (min 64 MiB).
PART_MIB=$(( (SIZE / 9000 / 1048576) + 1 ))
[ "$PART_MIB" -ge 64 ] || PART_MIB=64
[ "$PART_MIB" -le 5120 ] || die "archive too large for one Spaces object (10,000 parts x 5 GiB)"

PUBLIC_URL="https://${SPACES_BUCKET}.${REGION}.digitaloceanspaces.com/${OBJECT}"
[ -z "${SPACES_CDN_HOST:-}" ] || PUBLIC_URL="https://${SPACES_CDN_HOST}/${OBJECT}"

printf 'source : %s\nsize   : %s bytes (%s GiB)\ntarget : s3://%s/%s  (%s)\npart   : %s MiB\n' \
  "$URL" "$SIZE" "$(( SIZE / 1073741824 ))" "$SPACES_BUCKET" "$OBJECT" "$ENDPOINT" "$PART_MIB"
if [ "$DRY" = 1 ]; then
  echo "dry run: link is reachable, nothing uploaded."
  exit 0
fi

# Detach: the keys are already in hand (asked above), so the child needs no terminal.
if [ "$BACKGROUND" = 1 ] && [ -z "${SNAPSHOT_TO_SPACES_CHILD:-}" ]; then
  [ -n "$LOG" ] || LOG="./snapshot-to-spaces-$(printf '%s' "${KEY//\//_}" | tr -c 'A-Za-z0-9._-' '_').log"
  self="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
  child_args=()
  for a in "${ARGS[@]}"; do [ "$a" = "--background" ] || child_args+=("$a"); done
  export SPACES_KEY SPACES_SECRET SPACES_BUCKET SNAPSHOT_TO_SPACES_CHILD=1
  nohup "$self" "${child_args[@]}" >>"$LOG" 2>&1 </dev/null &
  echo "started in background, pid $!"
  echo "log: $LOG"
  echo "watch: tail -f $LOG"
  exit 0
fi

# aws-cli reads multipart tuning from its config file only; keep it in a throwaway file, keys in the environment.
cfg="$(mktemp)"
trap 'rm -f "$cfg"' EXIT
cat > "$cfg" <<EOF
[default]
s3 =
  multipart_threshold = 64MB
  multipart_chunksize = ${PART_MIB}MB
  max_concurrent_requests = 8
  addressing_style = virtual
EOF

acl=()
[ "$PUBLIC" = 0 ] || acl=(--acl public-read)

# Log lines get a time stamp; a bar redraws with \r, which a log file cannot show, so split it into lines.
stamp() { while IFS= read -r l; do printf '%s %s\n' "$(date +%T)" "$l"; done; }
# \r -> new line, flushed at once (tr would hold the lines back in a pipe buffer and the log would stay empty).
splitcr() { awk 'BEGIN { RS = "\r" } { n = split($0, a, "\n"); for (j = 1; j <= n; j++) if (a[j] ~ /[^ \t]/) { print a[j]; fflush() } }'; }

export AWS_ACCESS_KEY_ID="$SPACES_KEY" AWS_SECRET_ACCESS_KEY="$SPACES_SECRET" AWS_CONFIG_FILE="$cfg"
export AWS_DEFAULT_REGION="$REGION" AWS_EC2_METADATA_DISABLED=true
echo "$(date +%T) streaming $SIZE bytes to s3://${SPACES_BUCKET}/${OBJECT}"

upload() {
  aws s3 cp - "s3://${SPACES_BUCKET}/${OBJECT}" \
    --endpoint-url "$ENDPOINT" --expected-size "$SIZE" ${acl[@]+"${acl[@]}"} --only-show-errors
}

if command -v pv >/dev/null 2>&1; then
  if [ -t 2 ]; then
    curl -sSL --fail --retry 5 --retry-delay 5 "$URL" | pv -s "$SIZE" -pterb | upload
  else
    curl -sSL --fail --retry 5 --retry-delay 5 "$URL" |
      pv -f -i 20 -w 100 -s "$SIZE" -F '%b %p %r ETA %e' 2> >(splitcr | stamp >&2) | upload
  fi
else
  echo "(install pv for a richer progress line: apt install pv)" >&2
  # curl's own meter: one line per percent, in a terminal or a log.
  curl -L --fail --retry 5 --retry-delay 5 -# "$URL" 2> >(
    splitcr | awk '
      /%/ { p = $NF; sub("%", "", p); i = int(p); if (i != last) { printf "progress %d%%\n", i; fflush(); last = i } next }
      NF  { print; fflush() }' | stamp >&2
  ) | upload
fi

# Verify by size: a truncated pipe would otherwise look like success.
got="$(aws s3api head-object --endpoint-url "$ENDPOINT" --bucket "$SPACES_BUCKET" --key "$OBJECT" \
  --query ContentLength --output text)"
[ "$got" = "$SIZE" ] || die "uploaded object is $got bytes, source is $SIZE — upload is incomplete"

echo "$(date +%T) done."
echo "$PUBLIC_URL"

# Notes — why not a DigitalOcean Function:
#   A Function is meant for short jobs (a few minutes, ~1 GB RAM, small scratch disk). A snapshot archive is hundreds
#   of GB to several TiB and takes hours to copy, so it cannot run inside one. A Function is fine as a *trigger*:
#   have it start a Droplet (or call a droplet-side HTTP endpoint) that runs this script and powers off afterwards.
