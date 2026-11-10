# Automatic recommendation refresh

The background worker previously called the resident-facing generation method without a signed-in account. Household ownership checks correctly rejected it. Refreshing directly from the price event also raced the asynchronous forecast worker and could read the previous forecast.

The worker now uses a package-private generation method and a dedicated read-only F2 hand-off for real household profiles and enabled flexible appliances. Simulated and deleted households are refused. The worker uses the same calculation, freshness, minimum-saving, accepted-run, snooze and ranking rules as manual generation. It does not create an admin identity, alter SecurityContext, expose a background HTTP endpoint or weaken resident ownership checks.

F3 publishes ForecastSaved inside its save transaction only after inserting a complete new run. An AFTER_COMMIT listener recomputes real households using independent REQUIRES_NEW transactions, so advice is committed after the forecast is visible. A rollback or duplicate save produces no refresh; replay events are ignored. One failed household does not stop the others. F3 recommendation mode waits for the saved forecast rather than reacting early to the price event; demo mode retains price-event updates. Startup, household/appliance changes and the existing schedule remain enabled.

The resident-facing Generate endpoint remains ownership checked. Listing, accepting, dismissing and snoozing recommendations retain their existing checks. No migration, new role, password, forecast-model change or retraining activation is introduced.

The recommendations page reads saved advice every minute while the Active tab is visible, and when returning to the page. It never calls Generate automatically. Polling pauses while the resident edits a time, has an unsaved time change or has a decision in progress. Older responses cannot overwrite newer advice.

Validation: all 358 Java tests passed with PostgreSQL enabled and zero skips; all 64 frontend tests passed. Integration coverage proves refresh after commit without a user session or Generate request, no refresh on rollback, distinct advice after a later forecast, unchanged accepted runs, rejected simulated households, and HTTP isolation between residents. Local runtime evidence also checks startup recomputation and browser polling without Generate requests.
