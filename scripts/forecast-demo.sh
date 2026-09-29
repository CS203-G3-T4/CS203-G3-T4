#!/usr/bin/env bash
# Local presentation processes only. Source your explicit demo environment first.
set -euo pipefail
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo"
: "${WATTLY_DEMO_HOME:?Set WATTLY_DEMO_HOME outside the repository and collector}"
: "${WATTLY_DEMO_EXPERIMENT:?Set the immutable experiment directory}"
: "${COLLECTOR_DATA_DIR:?Set the read-only collector data directory}"
: "${WATTLY_DEMO_PG_BIN:?Set the PostgreSQL bin directory}"
: "${WATTLY_DEMO_PYTHON:?Set the dedicated Python executable}"
: "${JAVA_HOME:?Set a Java 21 JDK}"
demo_root=$(realpath -m "$WATTLY_DEMO_HOME")
case "$demo_root" in /home/bryan/cs203|/home/bryan/cs203/*|"$repo"|"$repo"/*|/) echo 'Use a separate demo directory' >&2; exit 2;; esac
run="$demo_root/run"
mkdir -p "$run"
exec 9>"$run/control.lock"
flock -n 9 || { echo 'Another demo command is running' >&2; exit 2; }
pg_port=${WATTLY_DEMO_PG_PORT:-55440}
app_port=${WATTLY_DEMO_PORT:-8082}
python_port=${WATTLY_DEMO_PYTHON_PORT:-8002}
export DB_URL="jdbc:postgresql://127.0.0.1:$pg_port/postgres?currentSchema=f3_presentation"
export DB_USER=wattly_demo DB_PASSWORD=
export FORECAST_MODE=LIVE FORECAST_PYTHON_URL="http://127.0.0.1:$python_port"
export WATTLY_ML_HOME="$WATTLY_DEMO_EXPERIMENT"
export OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1
. "$repo/scripts/forecast-period-mapping.env"
jar="$repo/target/energy-market-service-0.0.1-SNAPSHOT.jar"
db_args=(--spring.flyway.default-schema=f3_presentation)

alive() {
  [[ -f "$run/$1.pid" ]] || return 1
  read -r pid started < "$run/$1.pid"
  [[ -r "/proc/$pid/stat" ]] && [[ $(awk '{print $22}' "/proc/$pid/stat") == "$started" ]] && kill -0 "$pid" 2>/dev/null
}
launch() {
  local name=$1; shift
  if alive "$name"; then echo "$name already running (PID $pid)"; return; fi
  nohup setsid "$@" </dev/null >"$run/$name.log" 2>&1 9>&- &
  local child=$!
  printf '%s %s\n' "$child" "$(awk '{print $22}' "/proc/$child/stat")" > "$run/$name.pid"
}
stop_process() {
  local name=$1
  if alive "$name"; then
    kill -TERM "$pid"
    for _ in {1..50}; do
      if ! alive "$name" || [[ $(awk '{print $3}' "/proc/$pid/stat") == Z ]]; then break; fi
      sleep 0.1
    done
    if alive "$name" && [[ $(awk '{print $3}' "/proc/$pid/stat") != Z ]]; then
      echo "$name has not stopped; inspect $run/$name.log" >&2; return 1
    fi
  fi
  rm -f "$run/$name.pid"
}
wait_http() {
  local name=$1 url=$2
  for _ in {1..45}; do
    if curl --fail --silent --max-time 1 "$url" >/dev/null; then return; fi
    alive "$name" || break
    sleep 1
  done
  echo "$name failed to start; inspect $run/$name.log" >&2; return 1
}
start_python() {
  launch python "$WATTLY_DEMO_PYTHON" -m wattly_ml.cli serve --port "$python_port"
  wait_http python "http://127.0.0.1:$python_port/health"
  echo 'Python process healthy; model readiness remains 503 because the experiment is unapproved.'
}
refresh_forecast() {
  "$JAVA_HOME/bin/java" -jar "$jar" "${db_args[@]}" --spring.main.web-application-type=none \
    --market.feed.enabled=false --weather.daily.enabled=false --forecast.import-file= --forecast.demo.run-once=true
}
case ${1:-status} in
  start)
    [[ -f "$jar" && -f "$WATTLY_DEMO_EXPERIMENT/public/forecast-demo.json" ]] || { echo 'Build the jar and experiment first' >&2; exit 2; }
    if [[ ! -f "$demo_root/postgres/PG_VERSION" ]]; then
      "$WATTLY_DEMO_PG_BIN/initdb" -D "$demo_root/postgres" -U wattly_demo --auth=trust --no-locale -E UTF8 > "$run/initdb.log"
    fi
    if ! "$WATTLY_DEMO_PG_BIN/pg_ctl" -D "$demo_root/postgres" status >/dev/null; then
      "$WATTLY_DEMO_PG_BIN/pg_ctl" -D "$demo_root/postgres" -l "$run/postgres.log" -o "-p $pg_port -h 127.0.0.1 -k $run" start 9>&-
    fi
    start_python
    # Refresh operational history for tomorrow without changing the frozen experiment.
    snapshot_dir=$("$WATTLY_DEMO_PYTHON" -m wattly_ml.cli snapshot "$COLLECTOR_DATA_DIR/collector.sqlite3" \
      --kind sqlite --output "$demo_root/live-snapshots")
    live_dataset="$demo_root/live-datasets/${snapshot_dir##*/}"
    if [[ ! -f "$live_dataset/manifest.json" ]]; then
      "$WATTLY_DEMO_PYTHON" -m wattly_ml.cli prepare "$snapshot_dir" --output "$live_dataset" > "$run/import-manifest.json"
    fi
    launch spring "$JAVA_HOME/bin/java" -jar "$jar" "${db_args[@]}" --server.address=127.0.0.1 --server.port="$app_port" \
      --market.feed.enabled=true --weather.daily.enabled=true --forecast.import-file="$live_dataset/prices.jsonl" \
      --spring.web.resources.static-locations="classpath:/static/,file:$WATTLY_DEMO_EXPERIMENT/public/"
    wait_http spring "http://127.0.0.1:$app_port/actuator/health"
    refresh_forecast > "$run/refresh.log" 2>&1
    echo "Open http://127.0.0.1:$app_port/forecast-demo.html"
    ;;
  refresh)
    refresh_forecast
    ;;
  python-stop) stop_process python; echo 'Only the demo Python service stopped.';;
  python-start) start_python;;
  stop)
    stop_process spring; stop_process python
    if "$WATTLY_DEMO_PG_BIN/pg_ctl" -D "$demo_root/postgres" status >/dev/null 2>&1; then
      "$WATTLY_DEMO_PG_BIN/pg_ctl" -D "$demo_root/postgres" stop -m fast
    fi
    ;;
  status)
    for name in spring python; do if alive "$name"; then echo "$name running (PID $pid)"; else echo "$name stopped"; fi; done
    curl --silent --show-error --max-time 3 "http://127.0.0.1:$app_port/api/v1/forecast/latest" || true
    ;;
  *) echo 'Usage: forecast-demo.sh {start|status|refresh|python-stop|python-start|stop}' >&2; exit 2;;
esac
