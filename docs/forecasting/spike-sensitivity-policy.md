# Applied spike sensitivity policy — 30 October 2026

Policy ID: K3_POOLED_WORST_WEEK_REVIEW_V1. Bryan authorized applying this policy in the current chat. This records owner authorization, not Jonathan/team approval or independent genuine-spike validation.

## Current behavior

Keep the current-price rule at k=3, using the previous28 daily observations for the same half-hour, minimum14 samples and spread floor1. Existing F4 appliance-overlap logic, S$0.05 minimum predicted saving and household windows remain. A predicted spike is uncertain; it is not confirmation of the current observed state. No replacement warning model, calibrated confidence percentage or conservative risk-aware recommendation rule is activated.

## New bounded research review gate

`python -m wattly_ml.cli assess-warning-budget EVIDENCE.json --output NEW_REPORT.json` assesses ONE fixed candidate. It does not train, scan thresholds, choose a candidate, read protected outcomes, promote a model or change live behavior. The existing historical research selectors and their saved failures stay frozen; future selection code must explicitly call the new assessor rather than silently rewriting prior results.

For independently graded, identical earlier weeks and exposures:

1. Candidate false episodes and false-warning duration must each be no higher overall than control.
2. For each measure, the candidate's rate in every reviewed week must not exceed the maximum control rate across the entire completed cohort available before `decisionAt`. "Earlier" means earlier than the review decision, not earlier than each candidate week. The same fixed cap applies to every week, so a later week within this already completed cohort can set the cap for an earlier one. This is a retrospective research review, not an online or walk-forward acceptance decision. Rates use each week's exposure. The cap never uses candidate outcomes or weeks/labels after the declared decision. The output records `capWindow` explicitly. This is a pragmatic initial safeguard, not a measured optimal user tolerance.
3. Advance detections before the ORIGINAL parent episode start must not decrease in aggregate. Current/already-high or post-onset detections get no advance credit. Any event losses, new detections and delayed/earlier shared leads are listed explicitly; passing does not excuse those tradeoffs or imply approval.
4. At least one detection or burden measure must improve strictly. Silence with lost detections and unchanged control both fail as improvements.
5. Require at least four nonoverlapping complete earlier weeks and20 complete events. Insufficient coverage retains control. Labels must be available no later than the declared decision time; duplicate event IDs, mismatched units, invalid coverage and nonpositive advance leads are rejected.

Counting units are explicit. `FORECAST_ISSUE_ORIGIN` means forecast origins; it cannot be described as actual household notifications. `EXPOSED_HOUSEHOLD_WEEK` requires independently deduplicated household notification episodes and their duration on a common exposure. Do not convert existing raw-origin counts into per-household notification counts. Grade SHIFT/AVOID usefulness, overlapping-run durations and false labels consistently upstream. Assign each event to one onset week. `truthDigest` identifies the common independently graded cohort; the assessor checks structure/arithmetic, not authenticity of raw truth.

The report always retains existing behavior and sets productionEligible=false and automaticPromotion=false. An eligible result only merits further review. Matched household savings, harmful-move losses and fresh later confirmation remain required before adoption. Thus relaxing the old same-week baseline constraint cannot itself become a claim of model improvement or actual bill savings.

## Evidence contract

The input has schemaVersion1, candidateId, controlId, aware decisionAt, metricUnit, labelId=`PAST_ONLY_SAME_SLOT_K3`, onsetDefinition=`KNOWN_LOW_BEFORE_PARENT_EPISODE_START`, and weeks. Each week has aware start/end/labelsAvailableAt, positive integer exposureUnits, completeEvents, SHA256 truthDigest, plus control and candidate metrics. Each metric object contains integer falseEpisodes/falseHalfHours and advanceEventLeadsMinutes mapping distinct event IDs to positive integer minutes (maximum720). Both methods share the one week's exposure and truth. Output is write-once.

The input must come from an independently verified grader. Existing saved reports with different labels, any-false-target origins, incomplete label availability or different episode boundaries cannot simply be relabelled into this format. No real-history sweep was run for this policy change.

## Verification and remaining review

24 focused checks passed (23 new policy/CLI cases plus the existing evening-peak regression). Focused tests demonstrate that an overall improvement may worsen one week within the historical worst-week cap; a low average cannot conceal an excessive bad week; exposure normalization matters; longer false warnings cannot hide behind fewer episodes; and suppressed detections cannot qualify as improvement. Temporal, event-ID, cohort, unit and immutable-output checks are included. Existing evening-peak/k3 regression is also preserved.

The previous c10a6d7 PR head passed all GitHub checks including full Python CI. This policy update has its own focused local verification and needs new-head CI/human review. CSDT4-33/94 remain open for independent validation scope and team review. No automatic follow-on experiment is requested.
