> Local serving superseded on 31 October 2026 by [price-only AI and its separate comparison](price-only-ai-2026-10-31.md). This document retains the historical solar model/trial evidence.

# Local solar-assisted AI — 30 October 2026

The local app uses `solar-183d-20261026-local-v1`, packaged from the saved `SOLAR_183D` final research bundle. Its 24 gradient-boosted pipelines were fitted on 183 days / 8,760 origins ending before 29 July 2026. Packaging does not fit, select or promote a model. The original research artifact is unchanged.

This replaces the initially activated `price-calendar-holiday-demand-v2-20261018` model. The [earlier activation](local-experimental-ai.md) is retained as historical evidence.

## The positive result and its limits

The same SOLAR_183D recipe, refitted separately for each evaluation week, produced S$37.2051 simulated savings versus B3 S$31.0476 on 600 matched appliance runs over eight already examined weeks. It won four weeks and lost four. Its last-week advantage exceeded its aggregate advantage. This is a promising aggregate development result, not consistent superiority, prospective validation or a household guarantee. The final saved July bundle is the deployment artifact from that recipe; it is not one model that independently earned the whole eight-week result.

The older compatible model's own study lost S$7.44 to B3. These results must not be attributed to each other's model. No model search or protected-outcome evaluation was resumed for this integration.

## Public solar inputs

EMC's public [NEMS Prices](https://www.nems.emcsg.com/nems-prices) page supplies 72-period and seven-day period-based charts without a subscription. `scripts/collect-emc-solar.cjs` reads those same responses in an ordinary headless browser session. It does not log in, solve access challenges or export browser credentials. It captures actual UTC receipt times, archives changed responses locally and atomically replaces the latest snapshot. Default polling is once per five minutes; three consecutive failures stop collection. Generated feeds and model binaries stay outside Git.

The chart series labelled Solar is stacked above Demand. The adapter subtracts Demand, matching EMC's own tooltip calculation. Using the raw Solar series would supply gross demand instead of solar MW. Numeric precision is retained. Missing or negative required values are rejected rather than replaced with zero; an explicitly reported zero is valid.

Feature schema 3 reproduces the exact 30 research inputs: 19 price/calendar/holiday entries plus 11 solar lags, rolling statistics and changes. It requires six consecutive completed solar periods and the corresponding period yesterday. All included responses must actually have arrived by the forecast's asOf and within ten minutes; the current panel must be no more than forty minutes old. Future/current chart entries are excluded until completed. Periods beyond the source's current update are never used as historical inputs. Year rollover and exact half-hour mapping are checked.

The research recipe uses **completed-period prices**. Current-period prices remain visible immediately but are excluded from this model's numerical features to preserve fitting/inference parity. The frozen recipe keeps expected-demand features missing, as during fitting, even when the app has captured separate future-demand inputs; present demand is never relabelled as a future forecast. There are no weather substitutes or solar-imputation shortcuts.

Training used revised final archives with assumed period-end availability; live inputs are actually observed provisional public forecasts. Matching semantics and feature construction does not eliminate this distribution difference. Forecasts carry SOLAR_PUBLIC_PROVISIONAL_INPUTS, COMPLETED_PERIOD_PRICE_INPUTS, RETROSPECTIVE_TRAINING and EXPERIMENTAL_AI quality flags.

## Runtime and boundaries

- App: http://localhost:8080/forecast.html and /recommendations.html. The forecast page identifies Experimental AI, solar-assisted, and the model version.
- Inference and the existing read-only collector bridge remain on WSL loopback; a hidden Windows proxy provides localhost access.
- The original price collector and frozen research checkout are unchanged. Solar collection is a separate local helper, started for this session without startup registration. Weekly retraining remains inactive.
- Solar values, receipt times and snapshot identity are saved with forecast inputs. Solar revisions change the input/run identity even when prices are unchanged.
- Unapproved models are registered as EXPERIMENTAL in the app database. The immutable manifest retains productionEligible=false. No approved current.json pointer is written.
- Missing/stale/malformed solar blocks new solar AI inference. Java retains its ordinary baseline fallback. An older numerical model is never silently substituted as the solar model.
- Resident and automatic recommendation generation use the saved F3 forecast. Background work reads only real household inputs through a dedicated internal path and runs after forecast commit, without changing resident ownership rules. The open recommendations page reads updated advice every minute. See [automatic refresh](../automatic-recommendation-refresh.md).

## Reproduction

Install the pinned Python dependencies and a local Playwright browser runtime. Package only a trusted local research file with `scripts/package-solar-research-model.py`. Required options are --source, --sha256, --baseline-report, --models-root and --version. The known SOLAR_183D source SHA256 is `5dbb15ad26ae0b3ee93cd41512fd1b3729bf47bfcd896168d4cbd64b3d58bac2`. The baseline report is the existing solar-v1/summary.json from the examined eight-week study. Its MAE ranking is labelled development evidence, not fresh validation.

Set WATTLY_ALLOW_EXPERIMENTAL_AI=true in both Spring and Python. Set WATTLY_ML_HOME, WATTLY_EXPERIMENTAL_MODEL_VERSION and WATTLY_EXPERIMENTAL_MANIFEST_SHA256 to the immutable packaged bundle. Set WATTLY_SOLAR_SNAPSHOT in Python to the collector's latest JSON path. Start collection before inference:

```bash
node scripts/collect-emc-solar.cjs --output /private/solar/latest.json --channel msedge
```

Python refuses a changed manifest pin, incompatible calendar/dependencies/order/model hash or an approved schema-3 bundle. Without experimental opt-in, ordinary approved-pointer rules apply. Spring's experimental inference URL must use loopback.

The private WSL environment and runtime remain under ~/.config/wattly/local-ai-20261030.env and ~/.local/share/wattly-local-ai-20261030. Start the database and read-only bridge first, then inference and Spring. Following a long shutdown, refresh the price import from actual collector receipts and wait for a genuine successful price poll; never make old data look fresh. The demo profile retains the repository's published fixture accounts.

## Verification

- 11,520 feature scalars exactly matched the original cached features at 16 distributed development origins.
- 384 saved weekly predictions reproduced with zero difference. Another 384 final-model predictions were unchanged by packaging.
- 36 focused Python tests passed, including future/stale/malformed input, gross-minus-net extraction, missing yesterday, year rollover, completed-price parity, experimental opt-in and failure-closed serving.
- 13 focused Java tests and the PostgreSQL persistence check passed. Solar evidence is saved, receipt times are checked and revisions get distinct identities.
- 61 JavaScript tests passed. Browser evidence is recorded separately after activation.

Compact [solar activation](evidence/2026-10-30-solar-ai/activation.json) and [parity check](evidence/2026-10-30-solar-ai/parity.json). Raw public responses, model binaries, private configuration and screenshots remain in local operator storage outside Git.


## 30 October noon-window repair

The 72-period chart may begin at noon and omit completed periods earlier today. The collector now also archives the public today panel (`value=10`, 48 points). The adapter checks only the recipe's seven required completed periods (six recent periods and the matching period yesterday). Every panel containing a required period must supply a valid, exactly agreeing value; panel order is not authority. Conflicts in unused chart history are archived but cannot veto inference. Required-period conflicts or missing values fail closed to B3. No latest-wins or tolerance change has been applied to the frozen recipe; availability and fallback reasons must be reported separately. Every included response retains its real receipt and freshness checks. No archived forward solar value is turned into an observed value merely because time passed. Two-panel snapshots remain readable when sufficient, while new collection uses all three panels. Midday, late-night and New Year boundaries are tested.

Future demand capture is separate from the frozen solar recipe. See [demand integration and verification](../demand-solar-repair-2026-10-30.md). Model weights, manifest pins and the current live trial were preserved.


## Snapshot size and retention contract (31 October review)

The collector caps each raw response at 240,000 bytes and the complete UTF-8
snapshot, including all panels and metadata, at 800,000 bytes. Both Python solar
and Java demand readers use the same 800,000-byte total limit. Python reads at
most one byte beyond the limit before rejecting the file. A failed collection
does not replace the last valid snapshot; readers still reject stale receipts.

Archive identities deliberately include real receipt timestamps: repeated source
values at distinct receipts are separate observations, not duplicates to discard.
At five-minute polling there can be 288 snapshots/day; the total-size ceiling is
230.4 MB/day, or 6.45 GB over 28 days, before compression. These are upper bounds,
not observed usage. Archive storage must be persistent and monitored. Keep every
snapshot referenced by an issued forecast/trial, together with receipt provenance,
through trial completion and human review. There is no automatic deletion or
rotation in this patch. After review, rotate/compress closed archives only under
an operator-approved retention decision that preserves referenced hashes and a
recoverable copy. Do not delete files merely because source values repeat.

See [collection permission decision](emc-collection-permission.md) before enabling
any collector. Fresh API requests were not part of this repair's tests.
