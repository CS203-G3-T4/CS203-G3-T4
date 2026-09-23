# CS203 Household Energy Market Service

Minimal Spring Boot and PostgreSQL project baseline. No upstream data is fetched.

## Run locally

Requirements: Java 21 or newer and Docker Compose (or a PostgreSQL server).
The Maven wrapper downloads Maven automatically.

```sh
docker compose up -d postgres
./mvnw spring-boot:run
```

The default database URL is `jdbc:postgresql://localhost:55432/energy_market`,
with local development credentials `energy_market` / `energy_market`.
Override these using `DB_URL`, `DB_USER`, and `DB_PASSWORD`.
Use `POSTGRES_PORT` to change the Docker host port.

`GET /actuator/health` reports application and database health.
This baseline contains no feature database migrations or scheduled collectors.

## Build

```sh
./mvnw verify
```
