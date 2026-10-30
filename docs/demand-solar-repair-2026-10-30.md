# Demand integration and solar-window repair — 30 October 2026

## What was wrong

The public 72-period chart switches its displayed window at midday. The earlier collector fetched only that panel and the previous seven days, leaving today's recently completed solar periods missing. Inference correctly refused missing inputs and Java fell back to B3 at 12:03. This was a feed-coverage bug, not a model-weight failure.

The same archived public feed already contained forward demand values with actual receipt times. The app had a timestamped future-demand contract, persistence and HTTP transfer, but its dedicated table had zero rows because nothing imported those public snapshots. Searching for another provider was unnecessary.

## Repair and configuration

Collection now includes EMC public panel 10 (today, 48 periods), 12 (72 periods, including future forecasts) and 14 (seven-day history). The solar adapter validates each panel's source, update, receipt, ordering and interval mapping; today overrides overlapping earlier history. Only completed periods at or before the provider's update are eligible. Do not repair a missing solar period with a forward forecast from an earlier snapshot.

Set `WATTLY_EMC_DEMAND_SNAPSHOT` to the trusted collector's local latest JSON file. Empty means disabled. A LIVE-only job runs at startup and each minute, reads at most 800 KB, requires a fresh receipt (10 minutes) and panel update (40 minutes), checks the panel's current interval, unique demand series, consecutive 72-point grid, year/midnight boundaries and finite nonnegative values, then imports exactly the next 24 targets. It performs no network request or credential access. Source `publishedAt` records the displayed panel update; `availableAt` is the actual response receipt. The panel timestamp is not a claim of an independently known issuance timestamp for every demand value.

Repeated polling of an unchanged provider vintage retains its first receipt. Conflicting values under that immutable source/target/publication identity are rejected and logged; they are not overwritten. A short database transaction lock serializes imports. The importer commits before triggering a forecast; REPLAY never runs this local feed job. Source archives stay outside Git. The original NEMS collector is unchanged.

One-time local import processed archive snapshots in receipt order after checking each filename's content hash. It preserved the timestamps that were genuinely observed then; it did not invent earlier publication or collection. Two older snapshots with filename/hash mismatches were excluded, without modifying them. This reconstructs demand availability for research; it does not claim the app consumed those values in past forecasts or backfill the prospective trial.

At the initial repair checkpoint, 385 target/vintage records across 16 panel publication times were stored, first actual receipt 04:33:36 SGT. A fresh saved solar AI forecast contained all 24 demand records and seven solar periods. The exact pinned model SHA and trial start/end were unchanged. Collection/import continues automatically while the local helper and app are running; neither boot registration nor weekly retraining was enabled.

## Model use and evidence boundary

The active `solar-183d-20261026-local-v1` recipe was trained with future demand missing. It intentionally retains that representation. Capture and HTTP transfer do not silently change a fitted model's feature semantics. On actual received data, all 24 schema-2 demand feature values are present, while all 24 predictions from the frozen solar model are exactly unchanged with and without the newly transferred demand records.

A fitted demand-aware schema-2 model is exercised over HTTP in a synthetic regression test: changing each target's known demand changes its prediction, and a receipt later than issue time is rejected. This proves feature delivery and consumption, not real price accuracy or savings. Existing dataset preparation accepts a receipt-preserving JSONL export with `--demand-forecasts`, and the standard chronological trainer consumes it.

The initial archive spans only about eight hours. None of its full 12-hour outcome windows had matured at verification, so no demand-aware price-quality claim or live promotion is justified. Fit one separately versioned candidate only after enough training origins and a later purged validation interval exist; the existing trainer requires at least 48 complete eligible training origins. Compare it with the frozen solar recipe and B1–B3 on identical untouched eligible pairs. Never replace missing past forecast vintages with subsequently observed/final demand, tune on protected dates, or restart a search merely because an inadequate panel failed. CSDT4-100's data connection is implemented; completing the model requirement still needs that honest candidate evidence or team acceptance of the current scope.

## Validation

- Full Java/PostgreSQL suite: 381 passed with no failures/errors/skips, including source parsing, New Year/midnight mapping, morning windows containing historical points, stale/future rejection, malformed values, first-receipt deduplication, transactional rollback and transfer to the Python client.
- Solar tests: 16 passed, including noon/late-night/New Year recovery and rejection of future solar values/receipts.
- Fitted demand-aware HTTP consumption and future-receipt rejection: passed; synthetic fixture only.
- Actual local forecast: solar-assisted AI, actionable, all 24 demand inputs retained, seven real solar inputs, identical frozen-model predictions and model hash.

Compact operator receipts are in [repair evidence](demand-solar-repair-evidence-2026-10-30.json). Raw public payloads, demand exports and model files stay outside Git. PR #42 carries the patch; human review remains separate from automated test results.
