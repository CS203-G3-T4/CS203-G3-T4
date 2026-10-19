# Official history and holiday acquisition — 18 October 2026

The previous historical-access blocker was rechecked against EMC's current public
form. It now returned legitimate HTTP 200 CSV responses. Sixteen original monthly
files cover **1 June 2025–30 September 2026 inclusive: 487 days and 23,376 distinct
half-hours**, with no gaps, duplicate half-hours, invalid periods, or nonfinite
prices. The existing importer parsed all 23,376 records successfully.

This is **RETROSPECTIVE_FINAL_PRICE** evidence. It is not operational replay, and
neither historical receipt times nor publication times were reconstructed.

## Source, acquisition and audit

EMC's [public NEMS Prices page](https://www.nems.emcsg.com/nems-prices#market-data-download)
advertises the *Uniform Singapore Energy Price and Demand Forecast* product,
classifies it as final, and states availability at D+6 business days. It permits
CSV date ranges of up to 31 days and a five-year rolling history. Its linked public
JavaScript constructs the request below. No credentials, paid service, access
control bypass, or independent collector changes were involved.

The archived source and download implementation are:

- `.local/acceptance-2026-10-18/research/emc-prices.html` and `.headers`.
- `.local/acceptance-2026-10-18/research/emc-validation.js` and `.headers`.
- `.local/acceptance-2026-10-18/research/emc-usep-YYYY-MM.csv` and `.headers`, for
  every month June 2025 through September 2026.
- `official-history-audit.json`: per-file SHA-256, byte length, row count, URL,
  requested dates, response Date header and unknown vintage fields.
- `audit_official_history.py`: independent strict date/period/finite-price/coverage
  audit. Run with `python3 .local/acceptance-2026-10-18/research/audit_official_history.py`.

Example permitted request, copied from the advertised public form:

```bash
curl -L --max-time 45 \
  -D emc-usep-2026-06.headers -o emc-usep-2026-06.csv \
  'https://www.nems.emcsg.com/api/sitecore/DataSync/DataDownload?value=1&fromDate=2026-06-01&toDate=2026-06-30&tpcValue=1'
```

The first successful monthly response's Date header was
`Sat, 17 Oct 2026 18:43:16 GMT` (18 October in Singapore). This records present
acquisition only; it says nothing about availability at a historical origin.
All sixteen responses were `text/csv` and supplied an attachment filename.
Prices range from **48.39 to 4,500.00 SGD/MWh**. No zero or negative prices happen
to occur in these files; the importer and evaluation must continue accepting them.
Every date contains all 48 periods. Revisions were not counted as new periods.

Exact CSV headers include:

```csv
"INFORMATION TYPE","DATE","PERIOD","USEP ($/MWh)","LCP ($/MWh)","DEMAND (MW)","SOLAR(MW)","TCL (MW)","RUSEP ($/MWh)","MAP ($/MWh)","MAPT ($/MWh)","TPC Applied"
```

`DATE` uses `01-Jun-2026`; `PERIOD` is 1–48, mapping to 00:00–23:30 Singapore
period starts. Only `USEP ($/MWh)` is the target price. The CSV's demand value is
not independently observed household consumption. More importantly, this export
does **not** identify the issue/receipt vintage or advance coverage of a demand
forecast, so it must not be silently supplied as future demand known at origin.

## Existing data inspected

Before acquisition, the earlier research cache contained a single September 20,
2026 EMC day: 48 final labels, with unknown historical vintages. The October 6
acquisition audit had found no missing-period additions in the daily CSV exports.
Older immutable collector snapshots in `wattly-f3-research/datasets` have 548–556
distinct periods; the October 17 dataset has 1,118 periods and 36 revisions. These
are short, incomplete provisional datasets and cannot themselves satisfy a
three-month study or twelve-month training requirement. Existing files and the
independent `/home/bryan/cs203` collector were preserved.

## Frozen evaluation windows and import

The authoritative protocol is
[`evaluation-protocol-2026-10-18.json`](evaluation-protocol-2026-10-18.json), frozen
before the newly acquired historical model scores were examined:

| Purpose | Singapore interval, start inclusive / end exclusive |
| --- | --- |
| Warm-up | 2025-06-01 / 2025-07-01 |
| Twelve-month training | 2025-07-01 / 2026-07-01 |
| Earlier validation and baseline ranking | 2026-07-01 / 2026-07-29 |
| Protected 56-day test | 2026-07-29 / 2026-09-23 |
| Three-month baseline scoreboard | 2026-04-01 / 2026-07-01 |

The initial research-local draft named August–September as test. It was superseded
before any newly acquired scores were computed to exclude dates already examined
by prior collector research. It remains archived as a superseded draft; use the
versioned repository protocol above for actual fitting and evaluation.

The existing CLI accepts the raw official files without adaptation. Run from the
Git root, using the configured ML Python runtime and new output directories:

```bash
export PYTHONPATH=ml/src
python -m wattly_ml.cli snapshot \
  .local/acceptance-2026-10-18/research/emc-usep-2025-06.csv \
  --kind emc \
  --source-url 'https://www.nems.emcsg.com/api/sitecore/DataSync/DataDownload?value=1&fromDate=2025-06-01&toDate=2025-06-30&tpcValue=1' \
  --output .local/acceptance-2026-10-18/history-snapshots
# Repeat once per original monthly file with its matching source URL.
python -m wattly_ml.cli prepare \
  .local/acceptance-2026-10-18/history-snapshots/emc-* \
  --output .local/acceptance-2026-10-18/history-dataset
```

Do not combine these files with provisional snapshots to manufacture historical
as-of eligibility. Retrospective runs may use the existing explicitly labelled
period-end assumption, but this does not satisfy the past-only information claim
for real operational replay. Baseline rankings require identical eligible
origin/target pairs; the acquisition audit alone establishes no winning method.

## Versioned Singapore public holidays

Verified official Ministry of Manpower announcements:

- [2025 holidays, published 5 August 2024](https://www.mom.gov.sg/newsroom/press-releases/2024/0805-public-holidays-for-2025).
- [Additional 3 May 2025 Polling Day, published 15 April 2025](https://www.mom.gov.sg/newsroom/press-releases/2025/0415-public-holiday-on-polling-day_3-may-2025).
- [2026 holidays, published 16 June 2025](https://www.mom.gov.sg/newsroom/press-releases/2025/0616-public-holidays-for-2026).
- [2027 holidays, published 18 June 2026](https://www.mom.gov.sg/newsroom/press-releases/2026/0618-public-holidays-for-2027).

The [current official calendar](https://www.mom.gov.sg/employment-practices/public-holidays)
was also saved and checked against these announcements. Original HTML files,
HTTP metadata where retained, and `mom-source-sha256.json` are in the research
archive. The extracted resource is
`singapore-public-holidays-MOM-20261018-v1.json`, version
`MOM_SG_2025_2027_20261018_V1`. It includes 12 dates for 2025, 14 for 2026 and
12 for 2027, including explicitly specified Monday substitutes.

Year-level publication is conservatively the announcement date at 23:59:59 +08:00
when only a date is documented; this is a publication convention, never a claim
about observed receipt. The complete 2025 version has publication 15 April 2025
because it includes the later election announcement. Origins before that complete
version must receive an unavailable calendar feature rather than knowledge of the
future election. The 2026 release marked March 21 and May 27 subject to confirmation;
they are included as dates announced then, not retrospectively labelled confirmed.
Employer-specific substitutions and household attendance are not measured here.

## Remaining acceptance dependencies

Long final-price history is now available, so missing price coverage no longer
blocks the retrospective three-month baseline study or twelve-month training
study. The separate implementation/evaluation report must provide actual scores
and preserve unfavourable outcomes.

Operational twelve-month replay remains unsupported: final CSVs do not provide
historical source publication, first availability, revision identity/vintage, or
per-origin future demand forecast coverage. An authorized operational import
must provide aware `periodStart`, `sourceUpdatedAt`, `availableAt`, `usep`,
`priceStatus`, `source`, `revisionId`, `timeMapping`, and provenance for prices.
Demand additionally requires `targetPeriod`, `demandMw`, `publishedAt`, `availableAt`,
`source` and revision identity for each forecast vintage, restricted to actual
known coverage; unavailable future periods remain missing. Required history is
June 2025 through September 22, 2026 for the frozen protocol above. Do not fill
these fields from final settlement times or the date of today's download.

No sensitivity choice has team approval merely because a retrospective study
supports it. No measured household consumption or genuine feasible-plan evidence
was acquired by this price/holiday research. The collector was not modified or
restarted, and no product code, live database, Jira issue or deployment was changed.
