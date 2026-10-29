# Spike sensitivity review for CSDT4-33/94

Prepared for Jonathan/team review. No human agreement or genuine-event validation is asserted.

The current rule compares each half-hour with its own preceding28 daily same-slot observations: typical=median, robust spread=max(1,1.4826*MAD), spike when price>typical+3*spread, with at least14 observations. This avoids treating an ordinary evening peak as unusual merely because it exceeds morning prices. The current-assessment endpoint and dashboard cheap/normal/spike labels are implemented.

Keep k=3 unchanged for this delivery pending review. It is the ticket's starting setting, not an empirically proven optimum. Rule-derived labels cannot independently prove genuine physical scarcity. Retrospective manual/source-event labels would need separate provenance and timing review.

For the June–July2026 eight-week saved mean forecasts, repeated target-pair precision/recall against this statistical rule are55.4%/40.7% (5,145 true positives,4,141 false positives,7,481 false negatives). This measures forecasting the rule, not correctness of the current-price rule itself. The separate strict costly-spike policy catches13/28 events with786 false-warning origins/199 episodes; it must not be confused with the current-assessment classifier or actual resident notifications.

The team decision is: accept k=3 and its explicitly limited statistical interpretation for the current-price display, or request a specifically scoped sensitivity change with an agreed alert budget. Also decide whether a false-warning budget applies overall or independently every week. Existing experiments imposed the stricter per-week condition; it is a research constraint, not a previously agreed team requirement. Do not change it silently or claim a policy improvement merely by relaxing it.

CSDT4-94 remains incomplete until the team agrees sensitivity and accepts the validation scope. The [final report](../final-report-f3.md) preserves positive and negative forecasting/savings evidence. No new threshold sweep or model training is requested by this handoff.