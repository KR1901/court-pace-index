# CALC methodology research audit

## Scope and production boundary

This branch is a research-only change. It does not modify `tools/compute_speed.py`, the Android calculation model, the bundled readings, the release workflow, or any production rating. It improves the existing research comparison and adds a second, independently cross-fitted normalisation experiment.

The research asks whether the current chronological leave-one-tournament-out (LOEO) event score and its season normaliser behave as intended, whether the player-profile estimator is stable, and whether uncertainty estimates reflect the complete estimation pipeline.

## Research correction: make the comparison reproduce production

The prior `chronological_loeo_study.py` computed its event raw score from `event_side_diagnostics()` alone. That diagnostic path does not itself assign unique-name aliases or the explicit tour-average fallback to current event player IDs. Production first calls `ensure_event_profiles()`, then uses `event_match_ratio_clusters()` for the raw score.

The research study now follows that exact sequence and obtains the event raw score from the same ratio-cluster function as production. It also checks that the diagnostic side population and raw-score side population agree. A focused unit test exercises the TML alphanumeric-ID to historical ID name crosswalk.

The study's binomial log loss now uses the un-floored model probability. The 0.5 expected-ace floor is a stabiliser for the actual/expected ratio denominator, not a probability correction. Using the floored value as a predicted ace probability would answer a different statistical question.

## Normalisation experiments

The current production normaliser scores completed earlier anchor events using the target event's player profiles and target-specific tour ace baseline. That guarantees that the target event itself is excluded, but it does not cross-fit the anchor observations: an anchor event's own matches can also contribute to the player profiles used to predict that anchor event.

The new research comparator uses a separate score for every anchor event. It takes each earlier completed event's own production chronological LOEO raw score, where that event's own matches are excluded from its profile training and tour baseline. It then averages those independent raw scores using the same stable-cohort rule (at least 20 adjusted server sides; otherwise all rateable completed events). The target numerator remains its production chronological raw score.

The source-completion filter is conservative. It requires all source editions that roll up into an app event to be demonstrably complete before the target. For date-only sources, the same timezone-safe boundary used by production applies; a previous local calendar day is not assumed to finish before the target's earliest possible UTC instant.

The report includes both values side by side:
- **Production chronological:** current same-target-profile anchor.
- **Cross-fitted chronological:** independent event-specific anchor scores.

Neither research value changes the app or the generated dataset. The cross-fitted score is a candidate diagnostic, not an approved replacement methodology.

## Rio 2026 snapshot diagnostic

The refreshed dataset used for the initial audit is generated at 2026-11-26 15:28:25 UTC (asset blob `e9c7530ed6b52d40599f4d957449668446c88cc7`).

For Rio 2026, the production payload records:
- raw adjusted event score: 1.524634
- same-target-profile normaliser: 0.994929
- published CALC value: 1.532
- 31 matches / 62 adjusted server sides
- 7 completed normalisation anchors / 289 anchor matches

The seven independently scored earlier standard-event anchors in that snapshot are:

| Event | Independent raw score | Adjusted sides |
|---|---:|---:|
| United Cup | 1.276742 | 50 |
| Brisbane | 1.382550 | 62 |
| Hong Kong | 1.008539 | 54 |
| Adelaide | 1.814823 | 52 |
| Auckland | 1.324719 | 54 |
| Australian Open | 1.453112 | 252 |
| Montpellier | 1.770457 | 54 |
| **Mean** | **1.432992** | **578** |

Dividing the Rio raw score by this independent mean gives approximately **1.064**. This is a sensitivity comparison on the recorded eligible cohort, not a recommendation to overwrite 1.532: the cross-fitted implementation, cohort membership, and full-pipeline uncertainty must first be reproduced by the research workflow across the complete archive.

The Rio interval currently bundled in the production payload is conditional on fixed profiles and a fixed normaliser. The optional `--bootstrap` mode resamples match records, refits production chronological ratings for the target and completed anchors, and recomputes the independent anchor on every draw. It preserves the original chronology skeleton so bootstrap sampling cannot erase the source evidence for an event's start or final-round completion.

## Profile estimator and expected-aces research

The repository already contains the complementary research grid in:
- `tools/deep_calculation_audit.py`: 150-point shrinkage, alternative shrinkage strengths, empirical-Bayes prior estimates, surface weights, and conditional/profile/full-pipeline bootstrap comparisons.
- `tools/compare_speed_variants.py`: calibrated and uncalibrated expected-aces formulations, expected-ace floor handling, returner-profile alternatives, ratio-of-sums versus mean-of-ratios, and comparison to Tennis Abstract's published event series.

These scripts are exploratory model comparisons, not proof of physical court speed. Tennis Abstract's surface-speed series is a behavioural ace-rate benchmark, not physical ground truth; its own explanation notes that ace rates cannot capture all the variables that determine perceived court speed. CPI/physical measurements, where available, should be treated as a separate and sparse evidence stream.

## Validation run

The research workflow runs:
1. All Python tooling unit tests.
2. Production-equivalent chronological event scoring, the same-profile versus cross-fitted normaliser comparison, and a full-pipeline bootstrap for Rio.
3. The longer LOEO/profile/prior/surface-weighting audit across the archive.
4. Formula-variant comparison against Tennis Abstract.

Acceptance requires exact parity between the research study's production-chronological raw score/normaliser and `season_ratings()` for the same event. A mismatch is a failed audit, not a reason to select whichever candidate produces the preferred ranking.

No formula, prior strength, surface weighting, minimum-side threshold, or published rating should be changed solely because one alternative matches an external ranking more closely.
