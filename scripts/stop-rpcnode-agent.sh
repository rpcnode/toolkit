#!/usr/bin/env bash
# Stop the local rpcnode-agent JVM started from IntelliJ (Agent run) or a local rpcnode-agent.jar.
# Does not touch /opt chain-agent, rpcnode-api-agent, or the server.
set -euo pipefail

stop_pattern() {
  local pat="$1"
  local pids
  pids="$(pgrep -f -- "$pat" || true)"
  if [ -z "$pids" ]; then
    return 0
  fi
  local pid
  for pid in $pids; do
    echo "SIGTERM $pid"
    kill "$pid" 2>/dev/null || true
  done
  local i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    local still=""
    for pid in $pids; do
      if kill -0 "$pid" 2>/dev/null; then
        still=1
      fi
    done
    if [ -z "$still" ]; then
      return 0
    fi
    sleep 0.2
  done
  for pid in $pids; do
    if kill -0 "$pid" 2>/dev/null; then
      echo "SIGKILL $pid"
      kill -9 "$pid" 2>/dev/null || true
    fi
  done
}

found=0
for pat in \
  'rpcnode\.toolkit\.agent\.presentation\.http\.AgentMainKt' \
  'rpcnode-agent\.jar'
do
  if pgrep -f -- "$pat" >/dev/null 2>&1; then
    found=1
    stop_pattern "$pat"
  fi
done

if [ "$found" -eq 0 ]; then
  echo "no rpcnode-agent process"
fi
