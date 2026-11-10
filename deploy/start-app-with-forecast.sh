#!/bin/sh
# Start the loopback Python forecast service (when configured), then Spring [CSDT4-35].
# Without WATTLY_ML_HOME, or if Python fails, Spring falls back to the Java baselines.
set -eu

if [ -n "${WATTLY_ML_HOME:-}" ]; then
    mkdir -p "$WATTLY_ML_HOME/models"
    if [ -n "${WATTLY_MODEL_ZIP_URL:-}" ]; then
        python /app/fetch_forecast_model.py \
            || echo "Forecast model fetch failed; continuing with Java baselines" >&2
    fi
    # cli serve binds 127.0.0.1 only; the port is never published.
    python -m wattly_ml.cli serve --port 8001 &
else
    echo "WATTLY_ML_HOME not set; Python forecast service not started" >&2
fi

exec java ${JAVA_OPTS:-} -jar /app/app.jar
