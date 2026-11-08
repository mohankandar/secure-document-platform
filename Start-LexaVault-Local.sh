#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd -- "$(dirname -- "$0")" && pwd -P)"
BACKEND="$ROOT/backend"
FRONTEND="$ROOT/frontend/securedoc-ui"
RUN="$ROOT/.run/ubuntu"
MODEL="qwen3.5:9b"
BACK=true
FRONT=true
WATCH=false

usage() {
  echo "Usage: bash Start-LexaVault-Local.sh [--backend-only|--frontend-only] [--model NAME] [--enable-watched-folder]"
  echo "PostgreSQL and Ollama must be started manually. Windows WIA scanner bridge is not started."
}
while (($#)); do
  case "$1" in
    --backend-only) FRONT=false ;;
    --frontend-only) BACK=false ;;
    --model) [[ $# -ge 2 && -n "$2" ]] || { echo "Missing model" >&2; exit 2; }; MODEL="$2"; shift ;;
    --enable-watched-folder) WATCH=true ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

require() { command -v "$1" >/dev/null || { echo "Missing required command: $1" >&2; exit 1; }; }
port_ready() { (echo >"/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1; }
start_tick() { awk '{print $22}' "/proc/$1/stat"; }
owned() {
  local name="$1" file="$RUN/$1.pid" pid tick
  [[ -f "$file" ]] || return 1
  read -r pid tick <"$file" || return 1
  [[ "$pid" =~ ^[0-9]+$ && "$tick" =~ ^[0-9]+$ && -r "/proc/$pid/stat" ]] || return 1
  [[ "$(start_tick "$pid")" == "$tick" ]]
}
launch() {
  local name="$1" port="$2" workdir="$3"
  shift 3
  if owned "$name"; then
    echo "$name already launched by Ubuntu script."
    return
  fi
  if port_ready "$port"; then
    echo "Port $port is already occupied; refusing to replace existing $name process." >&2
    return 1
  fi
  (
    cd -- "$workdir"
    exec setsid "$@"
  ) >"$RUN/$name.log" 2>&1 </dev/null &
  local pid=$!
  sleep 0.2
  if [[ ! -r "/proc/$pid/stat" ]]; then
    echo "$name exited immediately. Check $RUN/$name.log" >&2
    return 1
  fi
  printf '%s %s\n' "$pid" "$(start_tick "$pid")" >"$RUN/$name.pid"
  local deadline=$((SECONDS + 120))
  while ((SECONDS < deadline)); do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "$name exited. Check $RUN/$name.log" >&2
      return 1
    fi
    if port_ready "$port"; then
      echo "$name listening on localhost:$port"
      return
    fi
    sleep 1
  done
  echo "$name did not become available. Check $RUN/$name.log" >&2
  return 1
}

mkdir -p -- "$RUN"
echo "=== LexaVault Ubuntu LOCAL startup ==="
echo "Only LexaVault processes are launched. PostgreSQL, Ollama, Docker and Keycloak are managed manually."
if "$BACK"; then
  for tool in java tesseract curl setsid; do require "$tool"; done
  JAR="$BACKEND/securedoc-api/target/securedoc-api-0.1.0-SNAPSHOT.jar"
  [[ -f "$JAR" ]] || { echo "Backend JAR missing; run bash Build-LexaVault-All.sh first." >&2; exit 1; }
  port_ready 5432 || { echo "PostgreSQL not available on localhost:5432." >&2; exit 1; }
  curl -fsS --max-time 5 http://localhost:11434/api/tags >"$RUN/ollama-models.json" || {
    echo "Ollama not running. Start manually: sudo systemctl start ollama" >&2
    exit 1
  }
  if ! ollama list | grep -Fq "$MODEL"; then
    echo "Ollama model $MODEL is not installed; verify with ollama list." >&2
    exit 1
  fi
  export SECUREDOC_AI_MODEL="$MODEL"
  export SECUREDOC_AI_PROVIDER=ollama
  export SECUREDOC_AI_OLLAMA_BASE_URL="http://localhost:11434"
  export SECUREDOC_LOGGING_CONFIG="classpath:logback-json.xml"
  if "$WATCH"; then
    WATCH_ROOT="$BACKEND/watched-intake"
    [[ -d "$WATCH_ROOT" && -r "$WATCH_ROOT" ]] || { echo "Watched folder missing/unreadable: $WATCH_ROOT" >&2; exit 1; }
    export SECUREDOC_WATCHED_FOLDER_ROOT_PATH="$WATCH_ROOT"
    export SECUREDOC_WATCHED_FOLDER_ENABLED=true
    export SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED=true
  else
    export SECUREDOC_WATCHED_FOLDER_ENABLED=false
    export SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED=false
  fi
  launch backend 8080 "$BACKEND" java -jar "$JAR" --spring.profiles.active=local --spring.main.banner-mode=off
fi
if "$FRONT"; then
  for tool in node npm setsid; do require "$tool"; done
  [[ -d "$FRONTEND/node_modules" ]] || { echo "Frontend dependencies missing. Run npm ci in frontend/securedoc-ui." >&2; exit 1; }
  export VITE_SECURITY_MODE=LOCAL
  launch frontend 5173 "$FRONTEND" npm run dev -- --host 0.0.0.0 --port 5173 --strictPort
fi
echo "Startup checks complete. Logs: $RUN"
echo "UI: http://localhost:5173  API: http://localhost:8080"
echo "Stop: bash Stop-LexaVault-Local.sh"
