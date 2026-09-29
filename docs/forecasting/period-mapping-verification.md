# NEMS half-hour period verification — 29 September 2026

Evidence ID: `EMC_RT_20260929_24_PERIODS`

The NEMS `updated` Unix timestamp belongs to the current half-hour interval:
`periodStart = floor(updated / 1800) * 1800`. Display calendar dates in Asia/Singapore.
Keep source publication and collection timestamps separate from period start.
For example, 11:31 SGT maps to 11:30–12:00 SGT, not 11:00–11:30.
At midnight, 00:01 SGT maps to 00:00 on the same Singapore date.

## Independent comparison

Read the official [EMC real-time table](https://www.nems.emcsg.com/nems-prices)
on 29 September 2026, with its displayed last update 11:31 SGT and current
interval 11:30–12:00. Transcribed the 24 past/current table rows from midnight
through 11:30 into the accompanying verification script. Future forecast rows
were excluded. This is a transcription of the rendered official table, not an
original downloadable EMC CSV. The accompanying comparison preserves its USEP
and demand values alongside the collector observations.

The read-only verifier matched 24 of 24 saved observations to their archived raw
API responses, then compared prices exactly and demand rounded to the nearest MW
(EMC displays three decimals; NEMS displays whole MW). All 24 prices and all 24
demands matched. Previous-period and next-period alternatives each had 23
comparable observations and zero joint price/demand matches. Midnight is included.
The comparison and timestamped summary are in `evidence/2026-09-29-period-mapping/`.

## Scope and activation

This resolves the current-versus-previous interval ambiguity for the observed
NEMS live feed, independently of collection timing. The Java/Python floor mapping
already implements the correct rule; no timestamp correction or collector change
is required. These are provisional real-time prices, not final settlement prices.
This one-day check does not prove every historical revision or future provider
change follows the same rule. Recheck if the feed changes or discrepancies arise.
Late collection must continue using the source timestamp, never collection time.

For Spring, export the existing setting before starting the application:

```sh
export FORECAST_PERIOD_MAPPING_EVIDENCE=EMC_RT_20260929_24_PERIODS
```

An optional `scripts/forecast-period-mapping.env` supplies that setting when
explicitly sourced. The default remains fail-closed for deployments that have
not loaded this evidence. No running service was restarted. Nonzero importer
offsets are not covered. Existing frozen manifests and reports remain immutable
and retain their original unverified status; do not hand-edit model approval or
dataset quality flags. Training and historical evaluation still need sufficient
data and their normal validation workflow. This evidence does not prove AI
accuracy or approve a production AI model.

Reproduce the comparison from the repository root:

```sh
python3 docs/forecasting/evidence/2026-09-29-period-mapping/verify_period_mapping.py \
  /home/bryan/cs203/data/collector.sqlite3 /tmp/wattly-period-verification
```
