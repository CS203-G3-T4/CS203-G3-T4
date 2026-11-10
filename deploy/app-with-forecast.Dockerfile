# Spring app plus the Python forecast service in one container [CSDT4-35].
# Experimental inference must use loopback (PythonForecastClient), so Python
# listens on 127.0.0.1:8001 inside this container and is never exposed.
# Opt-in on Railway: RAILWAY_DOCKERFILE_PATH=deploy/app-with-forecast.Dockerfile.
# Models are never baked in; see docs/deployment.md#ai-forecast-container.

# Match the existing model's exact Python/dependency contract (see ml/Dockerfile.weekly).
ARG PYTHON_IMAGE=python:3.12.3-slim-bookworm@sha256:afc139a0a640942491ec481ad8dda10f2c5b753f5c969393b12480155fe15a63
# Multi-arch index digests, pinned on 2026-11-10 so rebuilds are reproducible.
ARG JDK_IMAGE=eclipse-temurin:21-jdk@sha256:ebfbb18d11ff163ee83b21258acf12248af48d22a0fdfdc68fa1a1516c05976a
ARG JRE_IMAGE=eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a

FROM ${JDK_IMAGE} AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package \
    && cp target/energy-market-service-*.jar /app.jar

FROM ${JRE_IMAGE} AS jre

FROM ${PYTHON_IMAGE}
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH \
    PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1
RUN apt-get update \
    && apt-get install -y --no-install-recommends libstdc++6 libgomp1 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
WORKDIR /opt/wattly-ml
COPY ml/requirements.lock ml/requirements-server.lock ml/pyproject.toml ./
RUN python -m pip install --no-cache-dir -r requirements-server.lock
COPY ml/src ./src
RUN python -m pip install --no-cache-dir --no-deps --no-build-isolation . \
    && python -m pip check
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
COPY deploy/fetch_forecast_model.py deploy/start-app-with-forecast.sh /app/
RUN chmod 755 /app/start-app-with-forecast.sh
EXPOSE 8080
CMD ["/app/start-app-with-forecast.sh"]
