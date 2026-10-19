#!/usr/bin/env bash
set -euo pipefail
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
env_file=${1:?Supply the private weekly environment file}
[[ -r "$env_file" ]] || { echo 'Weekly environment file unavailable' >&2; exit 2; }
set -a
source "$env_file"
set +a
: "${WATTLY_PYTHON:?Set the dedicated Python executable}"
: "${WATTLY_ML_HOME:?Set the training/inference runtime directory}"
export PYTHONPATH="$repo/ml/src" PYTHONDONTWRITEBYTECODE=1 OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1
cd "$repo"
exec timeout --signal=TERM --kill-after=30s 45m nice -n 10 "$WATTLY_PYTHON" -m wattly_ml.scheduled
