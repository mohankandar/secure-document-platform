#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd -- "$(dirname -- "$0")" && pwd -P)"
STATE_ROOT="${LEXAVAULT_LOCAL_STATE_DIR:-$ROOT/../runtime}"
RUN="$STATE_ROOT/run"
start_tick() { awk '{print $22}' "/proc/$1/stat"; }

stop_owned() {
  local name="$1" file="$RUN/$1.pid" pid tick pgid i
  [[ -f "$file" ]] || { echo "$name: no launcher PID record; nothing to stop."; return; }
  read -r pid tick <"$file" || { echo "$name: malformed PID record." >&2; return 1; }
  if [[ ! "$pid" =~ ^[0-9]+$ || ! "$tick" =~ ^[0-9]+$ || ! -r "/proc/$pid/stat" ]]; then
    echo "$name: stale PID record removed."
    rm -f -- "$file"
    return
  fi
  if [[ "$(start_tick "$pid")" != "$tick" ]]; then
    echo "$name: PID reused; refusing to stop unrelated process." >&2
    return 1
  fi
  pgid="$(ps -o pgid= -p "$pid" | tr -d ' ')"
  if [[ "$pgid" != "$pid" ]]; then
    echo "$name: unexpected process group; refusing to terminate." >&2
    return 1
  fi
  echo "Stopping $name process group $pgid..."
  kill -TERM -- "-$pgid" 2>/dev/null || true
  for ((i=0; i<15; i++)); do
    if ! kill -0 "$pid" 2>/dev/null; then break; fi
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "$name did not exit; inspect before stopping manually." >&2
    return 1
  fi
  rm -f -- "$file"
  echo "$name stopped."
}

echo "=== LexaVault Ubuntu LOCAL shutdown ==="
stop_owned frontend
stop_owned backend
echo "PostgreSQL, Ollama, Docker, Keycloak and SDV were left untouched."
