# PR48 review routing by ticket

Updated 2 November 2026 against actual main `ee11c05`. PR40 is already merged;
its unchanged replay paths are omitted. This is a routing aid, not a claim that
all parent-story acceptance or specialist approvals are complete. Tests follow
their corresponding production paths. Shared auth, F1/F2/F4 and database paths
still need the appropriate feature-owner review.

| Ticket | Review area | Changed source/operator path |
|---|---|---|
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [.gitattributes](../../.gitattributes) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [ml/src/wattly_ml/artifacts.py](../../ml/src/wattly_ml/artifacts.py) |
| CSDT4-17/18/35 | Walk-forward baseline/model comparison | [ml/src/wattly_ml/backtest.py](../../ml/src/wattly_ml/backtest.py) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [ml/src/wattly_ml/cli.py](../../ml/src/wattly_ml/cli.py) |
| CSDT4-35/100 | Expected-demand pipeline and bounded candidate | [ml/src/wattly_ml/demand_candidate.py](../../ml/src/wattly_ml/demand_candidate.py) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [ml/src/wattly_ml/features.py](../../ml/src/wattly_ml/features.py) |
| CSDT4-59 | Weekly operations; activation remains off | [ml/src/wattly_ml/operations.py](../../ml/src/wattly_ml/operations.py) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [ml/src/wattly_ml/price_only.py](../../ml/src/wattly_ml/price_only.py) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [ml/src/wattly_ml/serving/app.py](../../ml/src/wattly_ml/serving/app.py) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [ml/src/wattly_ml/solar.py](../../ml/src/wattly_ml/solar.py) |
| CSDT4-33/94 | Spike definition, review and offline validation | [ml/src/wattly_ml/spike_policy_validation.py](../../ml/src/wattly_ml/spike_policy_validation.py) |
| CSDT4-33/94 | Spike definition, review and offline validation | [ml/src/wattly_ml/spike_rule_review.py](../../ml/src/wattly_ml/spike_rule_review.py) |
| CSDT4-33/94 | Spike definition, review and offline validation | [ml/src/wattly_ml/warning_budget.py](../../ml/src/wattly_ml/warning_budget.py) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [scripts/collect-emc-solar.cjs](../../scripts/collect-emc-solar.cjs) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [scripts/collector-live-feed.py](../../scripts/collector-live-feed.py) |
| CSDT4-51 | Paired savings/evidence; F4/DB review where applicable | [scripts/compare-forecast-candidates.py](../../scripts/compare-forecast-candidates.py) |
| CSDT4-35/100 | Expected-demand pipeline and bounded candidate | [scripts/demand-candidate.py](../../scripts/demand-candidate.py) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [scripts/emc-snapshot-limits.cjs](../../scripts/emc-snapshot-limits.cjs) |
| CSDT4-51 | Paired savings/evidence; F4/DB review where applicable | [scripts/forecast-replay/PriceForecast.java](../../scripts/forecast-replay/PriceForecast.java) |
| CSDT4-59 | Weekly operations; activation remains off | [scripts/forecast-weekly-preflight.sh](../../scripts/forecast-weekly-preflight.sh) |
| CSDT4-59 | Weekly operations; activation remains off | [scripts/forecast-weekly.env.example](../../scripts/forecast-weekly.env.example) |
| CSDT4-59 | Weekly operations; activation remains off | [scripts/install-forecast-timer.sh](../../scripts/install-forecast-timer.sh) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [scripts/package-price-research-model.py](../../scripts/package-price-research-model.py) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [scripts/package-solar-research-model.py](../../scripts/package-solar-research-model.py) |
| CSDT4-33/94 | Spike definition, review and offline validation | [scripts/review-spike-rule.py](../../scripts/review-spike-rule.py) |
| CSDT4-33/94 | Spike definition, review and offline validation | [scripts/spike-rule-review.template.html](../../scripts/spike-rule-review.template.html) |
| CSDT4-35/100 | Expected-demand pipeline and bounded candidate | [src/main/java/sg/edu/smu/cs203/forecast/EmcDemandSnapshotImporter.java](../../src/main/java/sg/edu/smu/cs203/forecast/EmcDemandSnapshotImporter.java) |
| CSDT4-35/100 | Expected-demand pipeline and bounded candidate | [src/main/java/sg/edu/smu/cs203/forecast/EmcDemandSnapshotJob.java](../../src/main/java/sg/edu/smu/cs203/forecast/EmcDemandSnapshotJob.java) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [src/main/java/sg/edu/smu/cs203/forecast/ForecastJob.java](../../src/main/java/sg/edu/smu/cs203/forecast/ForecastJob.java) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [src/main/java/sg/edu/smu/cs203/forecast/ForecastOperationsRepository.java](../../src/main/java/sg/edu/smu/cs203/forecast/ForecastOperationsRepository.java) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [src/main/java/sg/edu/smu/cs203/forecast/ForecastRepository.java](../../src/main/java/sg/edu/smu/cs203/forecast/ForecastRepository.java) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [src/main/java/sg/edu/smu/cs203/forecast/ForecastSaved.java](../../src/main/java/sg/edu/smu/cs203/forecast/ForecastSaved.java) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [src/main/java/sg/edu/smu/cs203/forecast/PythonForecastClient.java](../../src/main/java/sg/edu/smu/cs203/forecast/PythonForecastClient.java) |
| CSDT4-35/36 + F4 integration | Saved-advice refresh, ownership and resident presentation | [src/main/java/sg/edu/smu/cs203/household/HouseholdRecommendationReader.java](../../src/main/java/sg/edu/smu/cs203/household/HouseholdRecommendationReader.java) |
| CSDT4-35/36 + F4 integration | Saved-advice refresh, ownership and resident presentation | [src/main/java/sg/edu/smu/cs203/household/HouseholdRecommendationReaderService.java](../../src/main/java/sg/edu/smu/cs203/household/HouseholdRecommendationReaderService.java) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [src/main/java/sg/edu/smu/cs203/market/price/MarketHistoryRepository.java](../../src/main/java/sg/edu/smu/cs203/market/price/MarketHistoryRepository.java) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [src/main/java/sg/edu/smu/cs203/recommendation/F3PriceForecastSource.java](../../src/main/java/sg/edu/smu/cs203/recommendation/F3PriceForecastSource.java) |
| CSDT4-51 | Paired savings/evidence; F4/DB review where applicable | [src/main/java/sg/edu/smu/cs203/recommendation/LiveCaptureIsolation.java](../../src/main/java/sg/edu/smu/cs203/recommendation/LiveCaptureIsolation.java) |
| CSDT4-51 | Paired savings/evidence; F4/DB review where applicable | [src/main/java/sg/edu/smu/cs203/recommendation/LiveComparisonService.java](../../src/main/java/sg/edu/smu/cs203/recommendation/LiveComparisonService.java) |
| CSDT4-51 | Paired savings/evidence; F4/DB review where applicable | [src/main/java/sg/edu/smu/cs203/recommendation/LiveEvidenceController.java](../../src/main/java/sg/edu/smu/cs203/recommendation/LiveEvidenceController.java) |
| CSDT4-51 | Paired savings/evidence; F4/DB review where applicable | [src/main/java/sg/edu/smu/cs203/recommendation/LiveEvidenceJob.java](../../src/main/java/sg/edu/smu/cs203/recommendation/LiveEvidenceJob.java) |
| CSDT4-51 | Paired savings/evidence; F4/DB review where applicable | [src/main/java/sg/edu/smu/cs203/recommendation/LiveEvidenceRepository.java](../../src/main/java/sg/edu/smu/cs203/recommendation/LiveEvidenceRepository.java) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [src/main/java/sg/edu/smu/cs203/recommendation/PriceForecast.java](../../src/main/java/sg/edu/smu/cs203/recommendation/PriceForecast.java) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [src/main/java/sg/edu/smu/cs203/recommendation/PriceForecastConfiguration.java](../../src/main/java/sg/edu/smu/cs203/recommendation/PriceForecastConfiguration.java) |
| CSDT4-35/36 + F4 integration | Saved-advice refresh, ownership and resident presentation | [src/main/java/sg/edu/smu/cs203/recommendation/RecommendationRecomputeJob.java](../../src/main/java/sg/edu/smu/cs203/recommendation/RecommendationRecomputeJob.java) |
| CSDT4-35/36 + F4 integration | Saved-advice refresh, ownership and resident presentation | [src/main/java/sg/edu/smu/cs203/recommendation/RecommendationService.java](../../src/main/java/sg/edu/smu/cs203/recommendation/RecommendationService.java) |
| CSDT4-35/36 + F4 integration | Saved-advice refresh, ownership and resident presentation | [src/main/java/sg/edu/smu/cs203/recommendation/RecommendationsResponse.java](../../src/main/java/sg/edu/smu/cs203/recommendation/RecommendationsResponse.java) |
| CSDT4-51 | Paired savings/evidence; F4/DB review where applicable | [src/main/java/sg/edu/smu/cs203/recommendation/SavingsErrorRange.java](../../src/main/java/sg/edu/smu/cs203/recommendation/SavingsErrorRange.java) |
| CSDT4-51 | Paired savings/evidence; F4/DB review where applicable | [src/main/java/sg/edu/smu/cs203/recommendation/SavingsMonitoringService.java](../../src/main/java/sg/edu/smu/cs203/recommendation/SavingsMonitoringService.java) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [src/main/java/sg/edu/smu/cs203/security/SecurityConfiguration.java](../../src/main/java/sg/edu/smu/cs203/security/SecurityConfiguration.java) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [src/main/resources/application.yml](../../src/main/resources/application.yml) |
| CSDT4-51 | Paired savings/evidence; F4/DB review where applicable | [src/main/resources/db/migration/V26__live_comparison_and_savings_ranges.sql](../../src/main/resources/db/migration/V26__live_comparison_and_savings_ranges.sql) |
| CSDT4-51 | Paired savings/evidence; F4/DB review where applicable | [src/main/resources/db/migration/V27__live_capture_exclusions.sql](../../src/main/resources/db/migration/V27__live_capture_exclusions.sql) |
| CSDT4-52 | Admin forecast accuracy and mobile layout | [src/main/resources/static/admin-forecast.html](../../src/main/resources/static/admin-forecast.html) |
| CSDT4-51 | Paired savings/evidence; F4/DB review where applicable | [src/main/resources/static/admin-live-comparison.html](../../src/main/resources/static/admin-live-comparison.html) |
| CSDT4-51 | Paired savings/evidence; F4/DB review where applicable | [src/main/resources/static/js/admin-live-comparison.js](../../src/main/resources/static/js/admin-live-comparison.js) |
| CSDT4-35/36 | Serving/input integration; auth/F1/API review where applicable | [src/main/resources/static/js/forecast.js](../../src/main/resources/static/js/forecast.js) |
| CSDT4-35/36 + F4 integration | Saved-advice refresh, ownership and resident presentation | [src/main/resources/static/js/recommendations-page.js](../../src/main/resources/static/js/recommendations-page.js) |
| CSDT4-35/36 + F4 integration | Saved-advice refresh, ownership and resident presentation | [src/main/resources/static/js/recommendations.js](../../src/main/resources/static/js/recommendations.js) |
| CSDT4-35/36 + F4 integration | Saved-advice refresh, ownership and resident presentation | [src/main/resources/static/recommendations.html](../../src/main/resources/static/recommendations.html) |

The current review follow-up adds only a portable offline grader, synthetic
fixtures/tests and evidence/retention documentation. It does not deploy, train,
change serving or collector behaviour, activate cleanup/retraining, or modify
the frozen trial. The private data and original operator scripts remain private.
