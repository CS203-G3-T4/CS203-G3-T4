# PR 48 review routing by ticket

Routing aid for changed production, configuration and operator-script paths versus current main. Test/doc files follow their corresponding production area. These are technical review areas, not invented teammate assignments. PR 40 dependency paths remain visible until that PR merges.

| Ticket | Review area | Path |
|---|---|---|
| CSDT4-35/36 | F3 serving and integration / shared config | [.gitattributes](../../.gitattributes) |
| CSDT4-35/36 | F3 serving and integration / shared config | [ml/src/wattly_ml/artifacts.py](../../ml/src/wattly_ml/artifacts.py) |
| CSDT4-35/36 | F3 serving and integration / shared config | [ml/src/wattly_ml/cli.py](../../ml/src/wattly_ml/cli.py) |
| CSDT4-100 | F3 input provenance / F1 hand-off | [ml/src/wattly_ml/demand_candidate.py](../../ml/src/wattly_ml/demand_candidate.py) |
| CSDT4-35/36 | F3 serving and integration / shared config | [ml/src/wattly_ml/features.py](../../ml/src/wattly_ml/features.py) |
| CSDT4-59 | F3 operations | [ml/src/wattly_ml/operations.py](../../ml/src/wattly_ml/operations.py) |
| CSDT4-35/36 | F3 serving and integration / shared config | [ml/src/wattly_ml/serving/app.py](../../ml/src/wattly_ml/serving/app.py) |
| CSDT4-35/36 | F3 serving and integration / shared config | [ml/src/wattly_ml/solar.py](../../ml/src/wattly_ml/solar.py) |
| CSDT4-33/94 | Spike labels and descriptive evaluation | [ml/src/wattly_ml/spike_rule_review.py](../../ml/src/wattly_ml/spike_rule_review.py) |
| CSDT4-59 | F3 operations | [ml/src/wattly_ml/warning_budget.py](../../ml/src/wattly_ml/warning_budget.py) |
| CSDT4-35/36 | F3 serving and integration / shared config | [scripts/collect-emc-solar.cjs](../../scripts/collect-emc-solar.cjs) |
| CSDT4-35/36 | F3 serving and integration / shared config | [scripts/collector-live-feed.py](../../scripts/collector-live-feed.py) |
| CSDT4-51 | Savings / F4 / DB where SQL | [scripts/compare-forecast-candidates.py](../../scripts/compare-forecast-candidates.py) |
| CSDT4-100 | F3 input provenance / F1 hand-off | [scripts/demand-candidate.py](../../scripts/demand-candidate.py) |
| CSDT4-35/36 | F3 serving and integration / shared config | [scripts/emc-snapshot-limits.cjs](../../scripts/emc-snapshot-limits.cjs) |
| CSDT4-43/44/45 | Replay / F1 / F2 / F4 | [scripts/forecast-replay/PriceForecast.java](../../scripts/forecast-replay/PriceForecast.java) |
| CSDT4-59 | F3 operations | [scripts/forecast-weekly-preflight.sh](../../scripts/forecast-weekly-preflight.sh) |
| CSDT4-59 | F3 operations | [scripts/forecast-weekly.env.example](../../scripts/forecast-weekly.env.example) |
| CSDT4-35/36 | F3 serving and integration / shared config | [scripts/install-forecast-timer.sh](../../scripts/install-forecast-timer.sh) |
| CSDT4-35/36 | F3 serving and integration / shared config | [scripts/package-solar-research-model.py](../../scripts/package-solar-research-model.py) |
| CSDT4-33/94 | Spike labels and descriptive evaluation | [scripts/review-spike-rule.py](../../scripts/review-spike-rule.py) |
| CSDT4-35/36 | F3 serving and integration / shared config | [scripts/spike-rule-review.template.html](../../scripts/spike-rule-review.template.html) |
| CSDT4-43/44/45 | Replay / F1 / F2 / F4 | [src/main/java/sg/edu/smu/cs203/common/DemoClock.java](../../src/main/java/sg/edu/smu/cs203/common/DemoClock.java) |
| CSDT4-43/44/45 | Replay / F1 / F2 / F4 | [src/main/java/sg/edu/smu/cs203/common/DemoClockController.java](../../src/main/java/sg/edu/smu/cs203/common/DemoClockController.java) |
| CSDT4-43/44/45 | Replay / F1 / F2 / F4 | [src/main/java/sg/edu/smu/cs203/common/DemoReplayRefreshService.java](../../src/main/java/sg/edu/smu/cs203/common/DemoReplayRefreshService.java) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/java/sg/edu/smu/cs203/common/TimeConfig.java](../../src/main/java/sg/edu/smu/cs203/common/TimeConfig.java) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/java/sg/edu/smu/cs203/forecast/CurrentAssessmentService.java](../../src/main/java/sg/edu/smu/cs203/forecast/CurrentAssessmentService.java) |
| CSDT4-100 | F3 input provenance / F1 hand-off | [src/main/java/sg/edu/smu/cs203/forecast/EmcDemandSnapshotImporter.java](../../src/main/java/sg/edu/smu/cs203/forecast/EmcDemandSnapshotImporter.java) |
| CSDT4-100 | F3 input provenance / F1 hand-off | [src/main/java/sg/edu/smu/cs203/forecast/EmcDemandSnapshotJob.java](../../src/main/java/sg/edu/smu/cs203/forecast/EmcDemandSnapshotJob.java) |
| CSDT4-52 | F3 admin accuracy / UI | [src/main/java/sg/edu/smu/cs203/forecast/ForecastAccuracyService.java](../../src/main/java/sg/edu/smu/cs203/forecast/ForecastAccuracyService.java) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/java/sg/edu/smu/cs203/forecast/ForecastController.java](../../src/main/java/sg/edu/smu/cs203/forecast/ForecastController.java) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/java/sg/edu/smu/cs203/forecast/ForecastJob.java](../../src/main/java/sg/edu/smu/cs203/forecast/ForecastJob.java) |
| CSDT4-59 | F3 operations | [src/main/java/sg/edu/smu/cs203/forecast/ForecastOperationsRepository.java](../../src/main/java/sg/edu/smu/cs203/forecast/ForecastOperationsRepository.java) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/java/sg/edu/smu/cs203/forecast/ForecastRepository.java](../../src/main/java/sg/edu/smu/cs203/forecast/ForecastRepository.java) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/java/sg/edu/smu/cs203/forecast/ForecastSaved.java](../../src/main/java/sg/edu/smu/cs203/forecast/ForecastSaved.java) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/java/sg/edu/smu/cs203/forecast/ForecastService.java](../../src/main/java/sg/edu/smu/cs203/forecast/ForecastService.java) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/java/sg/edu/smu/cs203/forecast/PythonForecastClient.java](../../src/main/java/sg/edu/smu/cs203/forecast/PythonForecastClient.java) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/java/sg/edu/smu/cs203/household/HouseholdRecommendationReader.java](../../src/main/java/sg/edu/smu/cs203/household/HouseholdRecommendationReader.java) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/java/sg/edu/smu/cs203/household/HouseholdRecommendationReaderService.java](../../src/main/java/sg/edu/smu/cs203/household/HouseholdRecommendationReaderService.java) |
| CSDT4-43/44/45 | Replay / F1 / F2 / F4 | [src/main/java/sg/edu/smu/cs203/household/spend/SpendService.java](../../src/main/java/sg/edu/smu/cs203/household/spend/SpendService.java) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/java/sg/edu/smu/cs203/market/price/AsOfMarketPrices.java](../../src/main/java/sg/edu/smu/cs203/market/price/AsOfMarketPrices.java) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/java/sg/edu/smu/cs203/market/price/MarketHistoryRepository.java](../../src/main/java/sg/edu/smu/cs203/market/price/MarketHistoryRepository.java) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/java/sg/edu/smu/cs203/market/price/MarketIngestionService.java](../../src/main/java/sg/edu/smu/cs203/market/price/MarketIngestionService.java) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/java/sg/edu/smu/cs203/market/price/MarketPriceService.java](../../src/main/java/sg/edu/smu/cs203/market/price/MarketPriceService.java) |
| CSDT4-43/44/45 | Replay / F1 / F2 / F4 | [src/main/java/sg/edu/smu/cs203/market/price/ReplayPriceCatalog.java](../../src/main/java/sg/edu/smu/cs203/market/price/ReplayPriceCatalog.java) |
| CSDT4-43/44/45 | Replay / F1 / F2 / F4 | [src/main/java/sg/edu/smu/cs203/market/price/ReplayPriceRepository.java](../../src/main/java/sg/edu/smu/cs203/market/price/ReplayPriceRepository.java) |
| CSDT4-43/44/45 | Replay / F1 / F2 / F4 | [src/main/java/sg/edu/smu/cs203/market/price/ReplayPriceService.java](../../src/main/java/sg/edu/smu/cs203/market/price/ReplayPriceService.java) |
| CSDT4-43/44/45 | Replay / F1 / F2 / F4 | [src/main/java/sg/edu/smu/cs203/recommendation/DemoPriceForecastSource.java](../../src/main/java/sg/edu/smu/cs203/recommendation/DemoPriceForecastSource.java) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/java/sg/edu/smu/cs203/recommendation/F3PriceForecastSource.java](../../src/main/java/sg/edu/smu/cs203/recommendation/F3PriceForecastSource.java) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/java/sg/edu/smu/cs203/recommendation/LiveCaptureIsolation.java](../../src/main/java/sg/edu/smu/cs203/recommendation/LiveCaptureIsolation.java) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/java/sg/edu/smu/cs203/recommendation/LiveComparisonService.java](../../src/main/java/sg/edu/smu/cs203/recommendation/LiveComparisonService.java) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/java/sg/edu/smu/cs203/recommendation/LiveEvidenceController.java](../../src/main/java/sg/edu/smu/cs203/recommendation/LiveEvidenceController.java) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/java/sg/edu/smu/cs203/recommendation/LiveEvidenceJob.java](../../src/main/java/sg/edu/smu/cs203/recommendation/LiveEvidenceJob.java) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/java/sg/edu/smu/cs203/recommendation/LiveEvidenceRepository.java](../../src/main/java/sg/edu/smu/cs203/recommendation/LiveEvidenceRepository.java) |
| CSDT4-43/44/45 | Replay / F1 / F2 / F4 | [src/main/java/sg/edu/smu/cs203/recommendation/MarketHistoryPastPrices.java](../../src/main/java/sg/edu/smu/cs203/recommendation/MarketHistoryPastPrices.java) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/java/sg/edu/smu/cs203/recommendation/PriceForecast.java](../../src/main/java/sg/edu/smu/cs203/recommendation/PriceForecast.java) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/java/sg/edu/smu/cs203/recommendation/PriceForecastConfiguration.java](../../src/main/java/sg/edu/smu/cs203/recommendation/PriceForecastConfiguration.java) |
| CSDT4-43/44/45 | Replay / F1 / F2 / F4 | [src/main/java/sg/edu/smu/cs203/recommendation/RealisedSavingsService.java](../../src/main/java/sg/edu/smu/cs203/recommendation/RealisedSavingsService.java) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/java/sg/edu/smu/cs203/recommendation/RecommendationRecomputeJob.java](../../src/main/java/sg/edu/smu/cs203/recommendation/RecommendationRecomputeJob.java) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/java/sg/edu/smu/cs203/recommendation/RecommendationRepository.java](../../src/main/java/sg/edu/smu/cs203/recommendation/RecommendationRepository.java) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/java/sg/edu/smu/cs203/recommendation/RecommendationService.java](../../src/main/java/sg/edu/smu/cs203/recommendation/RecommendationService.java) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/java/sg/edu/smu/cs203/recommendation/RecommendationsResponse.java](../../src/main/java/sg/edu/smu/cs203/recommendation/RecommendationsResponse.java) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/java/sg/edu/smu/cs203/recommendation/SavingsErrorRange.java](../../src/main/java/sg/edu/smu/cs203/recommendation/SavingsErrorRange.java) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/java/sg/edu/smu/cs203/recommendation/SavingsMonitoringService.java](../../src/main/java/sg/edu/smu/cs203/recommendation/SavingsMonitoringService.java) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/java/sg/edu/smu/cs203/security/SecurityConfiguration.java](../../src/main/java/sg/edu/smu/cs203/security/SecurityConfiguration.java) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/resources/application.yml](../../src/main/resources/application.yml) |
| CSDT4-43/44/45 | Replay / F1 / F2 / F4 | [src/main/resources/db/migration/V23__replay_prices_and_household.sql](../../src/main/resources/db/migration/V23__replay_prices_and_household.sql) |
| CSDT4-43/44/45 | Replay / F1 / F2 / F4 | [src/main/resources/db/migration/V24__mark_demo_forecast_runs.sql](../../src/main/resources/db/migration/V24__mark_demo_forecast_runs.sql) |
| CSDT4-43/44/45 | Replay / F1 / F2 / F4 | [src/main/resources/db/migration/V25__drop_redundant_replay_price_index.sql](../../src/main/resources/db/migration/V25__drop_redundant_replay_price_index.sql) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/resources/db/migration/V26__live_comparison_and_savings_ranges.sql](../../src/main/resources/db/migration/V26__live_comparison_and_savings_ranges.sql) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/resources/db/migration/V27__live_capture_exclusions.sql](../../src/main/resources/db/migration/V27__live_capture_exclusions.sql) |
| CSDT4-43/44/45 | Replay / F1 / F2 / F4 | [src/main/resources/static/admin-demo.html](../../src/main/resources/static/admin-demo.html) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/resources/static/admin-feed.html](../../src/main/resources/static/admin-feed.html) |
| CSDT4-52 | F3 admin accuracy / UI | [src/main/resources/static/admin-forecast.html](../../src/main/resources/static/admin-forecast.html) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/resources/static/admin-live-comparison.html](../../src/main/resources/static/admin-live-comparison.html) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/resources/static/admin-overview.html](../../src/main/resources/static/admin-overview.html) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/resources/static/css/wattly.css](../../src/main/resources/static/css/wattly.css) |
| CSDT4-43/44/45 | Replay / F1 / F2 / F4 | [src/main/resources/static/js/admin-demo-clock.js](../../src/main/resources/static/js/admin-demo-clock.js) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/resources/static/js/admin-live-comparison.js](../../src/main/resources/static/js/admin-live-comparison.js) |
| CSDT4-35/36 | F3 serving and integration / shared config | [src/main/resources/static/js/forecast.js](../../src/main/resources/static/js/forecast.js) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/resources/static/js/recommendations-page.js](../../src/main/resources/static/js/recommendations-page.js) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/resources/static/js/recommendations.js](../../src/main/resources/static/js/recommendations.js) |
| CSDT4-43/44/45 | Replay / F1 / F2 / F4 | [src/main/resources/static/js/wattly.js](../../src/main/resources/static/js/wattly.js) |
| CSDT4-51 | Savings / F4 / DB where SQL | [src/main/resources/static/recommendations.html](../../src/main/resources/static/recommendations.html) |
