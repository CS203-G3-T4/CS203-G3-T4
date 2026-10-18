#!/usr/bin/env bash
# Run the restored presentation database and the bundle's saved chart.
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_dir"
config_file="$repo_dir/.env.team-demo"
if [[ ! -f "$config_file" ]]; then
  echo 'Copy .env.team-demo.example to .env.team-demo and set your database and bundle path.' >&2
  exit 1
fi
set -a
source "$config_file"
set +a
: "${DB_URL:?Set DB_URL in .env.team-demo to the restored database}"
: "${DB_USER:?Set DB_USER in .env.team-demo}"
: "${WATTLY_TEAM_BUNDLE:?Set WATTLY_TEAM_BUNDLE in .env.team-demo}"
bundle_dir=$(cd -- "$WATTLY_TEAM_BUNDLE" && pwd)
if [[ ! -f "$bundle_dir/public/forecast-demo.json" ]]; then
  echo "Missing saved chart: $bundle_dir/public/forecast-demo.json" >&2
  exit 1
fi
source "$repo_dir/scripts/forecast-period-mapping.env"
./mvnw -DskipTests package
exec java -jar "$repo_dir/target/energy-market-service-0.0.1-SNAPSHOT.jar" \
  --server.address=127.0.0.1 --server.port="${WATTLY_TEAM_PORT:-8080}" \
  --spring.flyway.default-schema=f3_presentation \
  --spring.web.resources.static-locations="classpath:/static/,file:$bundle_dir/public/"
