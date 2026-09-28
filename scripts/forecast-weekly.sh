#!/bin/sh
# Explicit invocation only. Run after loading your local environment file.
set -eu
: "${WATTLY_ML_HOME:?Set WATTLY_ML_HOME to the trusted local runtime directory}"
mkdir -p "$WATTLY_ML_HOME"
export OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1
exec flock -n "$WATTLY_ML_HOME/weekly.lock" timeout 45m nice -n 10 python -m wattly_ml.cli weekly "$@"
