#!/usr/bin/env bash
# Run Wattly on this machine for markers: demo accounts, Forecast and Recommendations from
# the bundled 26 Sep 2026 replay. Needs Docker and Java 21+; no internet or Python service.
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_dir"
db_name=${WATTLY_LOCAL_DEMO_DB:-wattly_local_demo}
port=${WATTLY_LOCAL_DEMO_PORT:-8080}
pg_port=${POSTGRES_PORT:-55432}
if [[ ! $db_name =~ ^[a-z_][a-z0-9_]*$ || $db_name == energy_market ]]; then
  echo "WATTLY_LOCAL_DEMO_DB must be a lowercase name other than energy_market: $db_name" >&2
  exit 1
fi
command -v docker >/dev/null || { echo 'Docker is required to run PostgreSQL.' >&2; exit 1; }

export POSTGRES_PORT=$pg_port
docker compose up -d --wait postgres
psql=(docker compose exec -T postgres psql -U energy_market -d energy_market -tAc)
if [[ $("${psql[@]}" "SELECT 1 FROM pg_database WHERE datname = '$db_name'") != 1 ]]; then
  echo "Creating database $db_name"
  "${psql[@]}" "CREATE DATABASE $db_name"
fi

./mvnw -q -DskipTests package

# Set explicitly so a shell configured for another database or live feeds cannot leak in.
export SPRING_PROFILES_ACTIVE=demo
export DB_URL="jdbc:postgresql://localhost:$pg_port/$db_name" DB_USER=energy_market DB_PASSWORD=energy_market
export USEP_POLLING_ENABLED=false NEA_DAILY_POLLING_ENABLED=false   # NEMS and both NEA feeds
export RECOMMENDATION_FORECAST_SOURCE=demo WATTLY_LIVE_COMPARISON_ENABLED=false WATTLY_EMC_DEMAND_SNAPSHOT=
export WATTLY_DEMO_REPLAY_AUTOSTART=true
export WATTLY_DEMO_REPLAY_DATE=${WATTLY_DEMO_REPLAY_DATE:-2026-09-26}
export WATTLY_DEMO_REPLAY_TIME=${WATTLY_DEMO_REPLAY_TIME:-14:00}

echo "Wattly demo: http://127.0.0.1:$port/ (resident / resident-demo, admin / admin-demo)"
exec java -jar "$repo_dir/target/energy-market-service-0.0.1-SNAPSHOT.jar" \
  --server.address=127.0.0.1 --server.port="$port"
