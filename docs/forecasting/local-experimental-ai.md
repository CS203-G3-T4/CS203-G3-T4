# Earlier local experimental AI activation — 30 October 2026

**Superseded:** the app now uses the solar-assisted SOLAR_183D recipe. See [current solar activation](solar-assisted-ai.md). The text and evidence below describe the earlier compatible model, not the current selection.

The local application now uses a real fitted model for its12-hour forecast and F4 recommendations. It is an explicitly experimental deployment, not an approved production rollout. The model's immutable productionEligible=false manifest and the approved current.json pointer are not altered.

## Which model is running

`price-calendar-holiday-demand-v2-20261018`:24 gradient-boosted horizon pipelines, feature schema2, trained on twelve months of historical prices with calendar/holiday features. Future expected-demand coverage in training is0%; missing future demand stays missing. The dedicated pinned Python environment, feature/calendar order, dependencies and model hashes are verified before loading.

This is the compatible twelve-month model, not the later solar-assisted research model. The S$37.2051 versus S$31.0476 simulated result belongs to the latter saved-forecast comparison and must NOT be attributed to this local model. The older model's own497-run study lost S$7.44 to B3. Activating AI does not establish consistent savings superiority. Solar-assisted results depend on historical auxiliary inputs without an equivalent verified live solar feed.

## Explicit opt-in; unchanged approved serving

Set WATTLY_ALLOW_EXPERIMENTAL_AI=true in BOTH Spring and Python, WATTLY_ML_HOME to a trusted private directory, WATTLY_EXPERIMENTAL_MODEL_VERSION to its named model under models/, and WATTLY_EXPERIMENTAL_MANIFEST_SHA256 to the exact immutable manifest hash. Supply a trusted locally trained bundle; never load an untrusted uploaded joblib. Python validates the named bundle and rejects a changed manifest. Java accepts the unapproved response only with explicit opt-in, EXPERIMENTAL serving mode, the EXPERIMENTAL_AI quality flag and unchanged temporal/units/24-target/ranking checks. Experimental inference URLs must use loopback.

With the flag absent, ordinary approved-pointer validation remains mandatory and unapproved models remain rejected. Experimental loading never creates current.json, promotes a model or changes its manifest. Readiness and forecast responses identify EXPERIMENTAL; the forecast page and F4 model label say Experimental AI. Stale input, bad contracts and service failures retain existing Java fallback/unavailable behavior.

Real runtime testing exposed a nanosecond/microsecond mismatch: Java Instant can serialize nanoseconds while Python datetime returns microseconds. The Java client now sends the request's asOf at microsecond precision before requiring exact equality. Target periods, request IDs, training cutoffs and stale checks are unchanged. A real-HTTP regression supplies sub-microsecond input and checks the normalized request and accepted response.

## Current local setup

- App: http://localhost:8080/forecast.html and /recommendations.html.
- Shared demo-profile account: resident / resident-demo. These are existing published local fixture credentials, not a production account.
- Runtime: /home/bryan/.local/share/wattly-local-ai-20261030; private environment: /home/bryan/.config/wattly/local-ai-20261030.env.
- A new dedicated PostgreSQL17 cluster on127.0.0.1:55449; no existing application database was migrated or overwritten.
- Private inference on127.0.0.1:8001; read-only collector feed proxy on127.0.0.1:8877.
- The app selects RECOMMENDATION_FORECAST_SOURCE=f3, FORECAST_MODE=LIVE and actual receipt-stamped collector history. No fixed demo-price curve, shifted timestamps or invented missing prices are used. Period mapping is labelled with the user's current-half-hour confirmation.
- The proxy reads collector SQLite in mode=ro/query_only. The original collector, its environment and its timers remain unchanged. Weather/solar inputs are not fabricated. The local app reports expected-demand and retrospective-training quality flags.

The four user services are wattly-local-ai-db, wattly-local-ai-feed, wattly-local-ai-python and wattly-local-ai-app. They were started, not enabled for boot. Start/stop in dependency order:

```bash
systemctl --user start wattly-local-ai-db wattly-local-ai-feed wattly-local-ai-python
systemctl --user start wattly-local-ai-app
# Stop app first; preserve models, database and audit files.
systemctl --user stop wattly-local-ai-app wattly-local-ai-python wattly-local-ai-feed wattly-local-ai-db
```

After a long shutdown, refresh the actual receipt-stamped import snapshot before starting; do not replay an old payload as fresh. The app's normal polling reads the existing collector through the read-only bridge. On this Windows host, native WSL localhost forwarding was unavailable, so a hidden loopback-only Windows bridge forwards requests to WSL without logging request bodies. Its local operator helpers/PID live in this chat's outputs/local-ai-activation-2026-10-30 directory. It is not a cloud deployment or startup registration. Weekly retraining remains intentionally inactive, as previously requested.

## Verification

11 focused Java tests pass (Python client, forecast job/fallback and F3 recommendation source),4 Python experimental-serving tests pass, and61frontend tests pass. Python tests verify opt-in, unchanged unapproved manifest/no production pointer, corrupt-pin rejection and failure-closed startup. Browser verification uses the actual app:24 forecast rows, Experimental AI label, shared resident login, F3_FORECAST / AI (experimental) / demoForecast=false and one generated recommendation. The displayed S$0.24 is an estimate for a modelled household run, not realized savings or evidence the recommendation will win.

Desktop and390px mobile were checked: no page errors or horizontal overflow. The application, trained inference and database remain running for the user. The original failed startup and timestamp repair logs are retained in local operator outputs. No model training, threshold scan, protected evaluation or savings claim was introduced. Compact [activation record](evidence/2026-10-30-local-ai/activation.json); full requests/screenshots remain outside Git.
