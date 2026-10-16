#!/usr/bin/env bash
# Call the panel (or a host agent) API with a token kept in a git-ignored file.
#
#   scripts/panel-api.sh /api/nodes/<id>/height              panel, GET
#   scripts/panel-api.sh POST /api/some/path '{"k":"v"}'     panel, POST with a JSON body
#   scripts/panel-api.sh --agent /api/v1/nodes                host agent (X-Api-Token)
#
# Tokens (one line, no quotes), both under /local/ which .gitignore already covers:
#   local/panel.token   session token of the admin UI (localStorage key `rpcnode_session`)
#   local/agent.token   host agent token
# Overrides: PANEL_URL (default http://127.0.0.1:8094), AGENT_URL (default http://192.168.2.136:48990),
# or PANEL_TOKEN / AGENT_TOKEN in the environment instead of the files.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode=panel
if [[ "${1:-}" == "--agent" ]]; then mode=agent; shift; fi

method=GET
if [[ "${1:-}" =~ ^(GET|POST|PUT|DELETE)$ ]]; then method="$1"; shift; fi
path="${1:?usage: panel-api.sh [--agent] [METHOD] /path [json-body]}"
body="${2:-}"

if [[ "$mode" == agent ]]; then
  base="${AGENT_URL:-http://192.168.2.136:48990}"
  token="${AGENT_TOKEN:-}"; file="$root/local/agent.token"
  auth=(-H "X-Api-Token: __TOKEN__")
else
  base="${PANEL_URL:-http://127.0.0.1:8094}"
  token="${PANEL_TOKEN:-}"; file="$root/local/panel.token"
  auth=(-H "Authorization: Bearer __TOKEN__")
fi
if [[ -z "$token" && -f "$file" ]]; then token="$(tr -d '[:space:]' < "$file")"; fi
if [[ -z "$token" ]]; then
  echo "no token: put it in ${file#"$root"/} or export ${mode^^}_TOKEN" >&2
  exit 2
fi
auth=("${auth[0]}" "${auth[1]//__TOKEN__/$token}")

args=(-sS -m 30 -X "$method" "${auth[@]}")
if [[ -n "$body" ]]; then args+=(-H "content-type: application/json" -d "$body"); fi

out="$(curl "${args[@]}" -w $'\n%{http_code}' "$base$path")"
code="${out##*$'\n'}"; json="${out%$'\n'*}"
if command -v jq >/dev/null 2>&1; then jq . <<<"$json" 2>/dev/null || printf '%s\n' "$json"; else printf '%s\n' "$json"; fi
echo "HTTP $code" >&2
[[ "$code" =~ ^2 ]]
