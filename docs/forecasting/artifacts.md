# Forecast research artifacts

Maintained Java/Python/UI code, regression fixtures, protocols, compact acceptance
summaries and human-readable results stay in source. Thirty-seven generated
reports, horizon CSVs, screenshots and the full historical demo payload are now
preserved in a reproducible archive rather than the current source tree. No
experiments, prices or saved results were discarded or recomputed to improve a
score. [The manifest](artifacts.json) records every original path, byte count and
SHA-256, pinned to reviewed commit `76c4866284ffb9c92c0369a088ad034c29a9d6f6`.
The original Git history remains intact.

## Retrieval

GitHub Actions' **Frozen research archive** job publishes the verified archive as
`wattly-forecast-evidence-76c4866` on each PR run, retained for 30 days. Open PR #11's
Actions run and download that artifact once the job succeeds. Expiry does not lose
the evidence: a full clone can reproduce the exact archive from the frozen commit.
For shallow clones, fetch that commit first. From the repository root:

```bash
git fetch origin 76c4866284ffb9c92c0369a088ad034c29a9d6f6
mkdir -p .local/artifacts
python3 scripts/forecast-artifacts.py build \
  .local/artifacts/wattly-forecast-evidence-76c4866.tar.gz
python3 scripts/forecast-artifacts.py verify \
  .local/artifacts/wattly-forecast-evidence-76c4866.tar.gz
python3 scripts/forecast-artifacts.py restore \
  .local/artifacts/wattly-forecast-evidence-76c4866.tar.gz \
  .local/research-evidence-76c4866
```

Build and restore refuse existing destinations. Every entry and the complete
archive are checked before restoration; unexpected entries, links, traversal,
changed contents and duplicate members are rejected. Original relative paths are
preserved under the destination. For example, the full AR study is at
`.local/research-evidence-76c4866/docs/forecasting/ar-improvements-2026-10-06.json`.
Numerical report links that previously pointed into generated files now lead here.
Earlier executable reproduction commands remain in the dated reports/runbook.
Raw `.local` model binaries, collector snapshots and household database dumps are
not in this archive; the manifest lists its complete contents.

Archive SHA-256:
`4d153531160edf9cb77e160cc1107a37e80434b61585c0f9d689af8531c63551`.
The first local verified copy is
`.local/review-fixes-2026-10-19/wattly-forecast-evidence-76c4866.tar.gz`.

## Public demo data

The full 209,345-byte `forecast-demo-latest.json` was inspected recursively. Its
contents are market observations, predictions, evaluation scores, timestamps,
coverage and provenance, with no household/user identifiers or credentials found.
Its dataset digest is
`b0797a3dfd148a8368507e08a64600154a1d660075aaaebfcd10fa6bc5bb5502`;
the source snapshot was acquired September 29, 2026 and uses provisional prices
available by each evaluation cutoff. Public availability of a feed is not proof
of redistribution rights; this inspection does not certify licensing.

The bundled resource is now an allowlisted 10 KB projection: one genuine saved
24-point origin, actuals, and aggregate scores. It omits the other 23 overlapping
chart origins, training details, per-horizon metrics and unused report fields.
It explicitly distinguishes one displayed origin from 24 scored origins/576
comparison pairs and includes the full source report's SHA-256. No synthetic
series or new fitting substitutes for the real results. The live resident page
continues to use saved application forecasts.

To reproduce the projection into a new private file:

```bash
python3 scripts/compact-forecast-demo.py \
  .local/research-evidence-76c4866/src/main/resources/static/forecast-demo-latest.json \
  .local/forecast-demo-public.json
```

The original AI selector is labelled as an optional restored reference. If that
separate presentation bundle is absent, it displays unavailable; the default
bundled diagnostic and live resident experience remain usable. No silent fixture
fallback is performed.

## Results retained in source

- [Baseline accuracy and household savings](acceptance-2026-10-18.md): 91-day
  identical-pair baseline scoreboard; unseen AI comparisons; AI modelled household
  savings below the validation-selected baseline, including losses.
- [Additional spike testing](additional-spike-tests-2026-10-18.md): recent
  candidate warned 8/13 events versus 4/13, with 54 versus 13 false-alarm
  half-hours; historical training strategies tie at 31/125 advance warnings.
- [Controlled improvement](improvement-2026-10-18.md): fixed selection, protected
  periods, training-only transforms, data coverage and frozen future confirmation.

These describe fixed-rule price anomalies, not independent genuine-spike labels.
There is no approved sensitivity/AI model, measured household-bill benefit or
permanent operational timer. Final-price historical studies remain retrospective.

## Scope decision

Keep the Java/Python contract, V15, UI, persistence and their tests together: the
working forecasting path crosses those components. Weekly operation depends on
the protected audit endpoints, schema2 artifacts and controlled inference reload.
Splitting it now would leave that lifecycle only partly tested in each PR.
Experimental research commands are offline, use separate immutable outputs and
cannot approve a model. After merge, further model experiments or timer policy
changes can be independent follow-ups against these stable contracts. No
language-based split is proposed for this correction. This is an engineering
decision, not an assertion of reviewer approval.
