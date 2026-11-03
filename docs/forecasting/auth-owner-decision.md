# Forecast admin authentication and weekly client

Current main uses Spring Security session login, ADMIN/RESIDENT roles and CSRF protection. Forecast routes retain their server-side ADMIN guard. The selected Python runtime does not introduce a competing production authentication layer.

The weekly client defaults to `WATTLY_RUN_AUTH_MODE=session`. It obtains `/api/v1/csrf` with a cookie jar, POSTs form credentials to `/login`, obtains the new CSRF token after authentication and supplies the token/cookies with each admin JSON POST. A real shared ADMIN account must be configured privately through `WATTLY_ADMIN_USER` and `WATTLY_ADMIN_PASSWORD`; arbitrary local names do not create accounts. Credentials require HTTPS or loopback HTTP, redirects are not followed, and role denial propagates without retry or fallback authentication.

Fresh disposable Spring/PostgreSQL verification covers a successful Python audit with local Basic disabled/CSRF enabled, an intentionally failed weekly acquisition persisted in model_run, unchanged champion state and authenticated RESIDENT denial. This does not constitute auth-owner approval or a production deployment.

## Local demo compatibility

`WATTLY_RUN_AUTH_MODE=local-basic` exists only for explicitly enabled loopback demo apps. Spring must opt in to `WATTLY_FORECAST_LOCAL_ADMIN_ENABLED=true` with matching private credentials. This client mode also supplies CSRF tokens; it cannot bypass shared authentication or role denial. Keep it disabled for permanent scheduling.

The exact forecasting guard routes remain `/admin-forecast.html`, `/api/v1/admin/forecast-accuracy`, `/api/v1/admin/forecast-baseline-validation`, `/api/v1/admin/forecast-demand`, `/api/v1/admin/forecast-training-attempts` and `/api/v1/admin/forecast-savings-replay`. Other admin routes are owned by shared security. There are no new CSRF exemptions or default credentials.

Review the dedicated service account, secret storage, deployment transport and role scope before production activation. Weekly WSL preparation is intentionally inactive at the user's request. See [delivery verification](delivery-2026-10-29.md) and the [runbook](runbook.md).