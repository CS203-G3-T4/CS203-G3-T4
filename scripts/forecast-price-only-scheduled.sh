#!/usr/bin/env bash
# A rendered service may call this after separate activation. Never promotes.
set -euo pipefail
env_file=${1:?Supply the private weekly environment file}
config_file=${2:?Supply the separate weekly JSON configuration}
[[ -r "$env_file" && $(stat -c %a "$env_file") == 600 && -O "$env_file" ]] || { echo 'Use an owner-only mode-600 environment file' >&2; exit 2; }
set -a
source "$env_file"
set +a
: "${WATTLY_PYTHON:?Set the dedicated Python executable}"
: "${WATTLY_RUN_API_URL:?Set the authenticated audit API URL}"
: "${WATTLY_ADMIN_USER:?Set the shared admin account}"
: "${WATTLY_ADMIN_PASSWORD:?Set the shared admin password}"
[[ ${WATTLY_RUN_AUTH_MODE:-session} == session ]] || { echo 'Weekly scheduling requires shared session authentication' >&2; exit 2; }
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export PYTHONPATH="$repo/ml/src" PYTHONDONTWRITEBYTECODE=1
export OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1
cd "$repo"
# Scheduling supplies the wall-clock tick; the worker itself has an injectable clock.
exec timeout --signal=TERM --kill-after=30s 45m nice -n 10 "$WATTLY_PYTHON" -m wattly_ml.weekly_runtime run --config "$config_file" --as-of "$(date --iso-8601=seconds)"
