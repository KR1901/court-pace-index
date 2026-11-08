# Calculation and formulation audit

## Scope and baseline
- Repository: `KR1901/court-pace-index`
- Initial PR #2 benchmark base: `f1693cf6349c0050fc575bdf848cb4015ed629a2`
- Deep-audit implementation reviewed at: `aba2408c15f656dc405b2b898bb176b8e7a34aeb`
- Production foundation: season-calibrated odds-ratio expected aces, match-level actual/expected ratios, a 20-server-side event threshold, a 0.5 expected-aces floor and season normalisation.
- Tennis Abstract is a behavioural reference series, not independent physical ground truth.

## Existing benchmark (verified from GitHub Actions)
The existing comparison covers 1,240 common event-season observations across 35 completed seasons (1991–2025), with 5,000 season-stratified paired bootstrap resamples.

| Variant | Pearson r | Spearman rho | MAE |
|---|---:|---:|---:|
| Uncalibrated, dropping low expectations | 0.833 | 0.815 | 0.086 |
| Uncalibrated, 0.5 floor | 0.878 | 0.861 | 0.076 |
| Calibrated odds ratio, pooled returner | 0.925 | 0.917 | 0.055 |
| Calibrated odds ratio, surface-matched returner | 0.925 | 0.926 | 0.052 |
| Ratio of sums | 0.904 | 0.890 | 0.065 |
| Pooled ace rate | 0.837 | 0.830 | 0.097 |

The surface-matched returner variant improves aggregate Spearman agreement and MAE, but worsens carpet MAE (0.094 versus 0.060 for the pooled-returner calibrated variant). It is therefore a candidate, not a universal winner.

## Audit programme added
1. Leave-one-tournament-out validation. Each target event is scored with every match from that event removed from both player profiles and the tour-average ace-rate anchor.
2. Clay-weight sensitivity: 0, 0.20, 1/3, 0.50, 2/3, 0.80 and 1.
3. Service-point prior sensitivity: 75–1,000 pseudo service points, with explicit one-match and three-match qualification comparisons at 100, 150 and 300 points.
4. A carpet-pooled stress test and a carpet-specific profile experiment that requires three carpet matches before use, with neutral-profile fallback otherwise.
5. A method-of-moments empirical-Bayes prior estimated separately by serve/return role and surface bucket from player-rate heterogeneity after excluding the target event.
6. Season-stratified event bootstrap, training/held-out weight comparisons, surface-stratified metrics and match-cluster uncertainty intervals for selected 2026 events.
7. Physical CPI overlap diagnostics, explicitly exploratory because CPI covers a small, non-random set of show-court events.
8. A seven-day source freshness and SHA-256 integrity gate for cached TML current-season data.
9. Conservative resolution of conflicting same-season live player-name collisions.
10. Clear documentation that historical and live calculations share the expected-aces equation but have different profile fallback policies.

## Decision rules
- Retain calibrated odds-ratio expected aces as the production foundation; the existing 35-season benchmark materially improves on the uncalibrated method.
- Do not replace equal-weight event means with ratio-of-sums; its historical reference agreement is weaker.
- Do not promote surface-matched returner profiles universally until leave-one-out and carpet-specific results support it.
- Carpet-specific and carpet-pooled profile variants are rejected for production: their carpet error worsened materially. Retain the neutral surface profile on carpet.
- Select weights on training seasons only and report held-out seasons separately.
- Paired-bootstrap fixed-prior versus empirical-Bayes differences on identical event-season draws. Prefer the fixed 150-service-point prior with three-match surface qualification: it beats the unshrunk and empirical-Bayes alternatives on the full LOEO matched set; retain the 1/3–2/3 neutral blend because a 50/50 blend is only a benchmark-specific improvement, not an independent physical-pace justification.
- Separate shrinkage from minimum-match coverage: compare one-match profiles and three-match profiles with the same prior strengths before changing the production qualification threshold.
- Match-cluster intervals condition on fitted profiles and the season normaliser. The nested bootstrap additionally resamples training matches within each tournament and target-event matches, rebuilding player profiles in each draw; the full-sample LOEO normaliser remains fixed, so normaliser uncertainty is still not included.
- CPI association is diagnostic, not proof of physical validity. Independent court-level and conditions data are needed for physical or causal claims.
- Laver Cup remains below the ordinary 20-side threshold. It may have a clearly labelled provisional uncertainty interval, but never an ordinary CALC ranking until it meets the threshold.
- TML public terms are non-commercial; obtain written permission before commercial use or redistribution. Raw TML match CSV files are not committed.
- Marrakech source reconciliation (2026): TML main draw is 259 aces / 4,019 service points (6.44%); qualifying adds 100 / 1,559 (6.41%); combined 359 / 5,578 (6.44%). Tennis Abstract's 2 November 2026 table publishes 8.0% Ace% and 0.93 speed. The Ace% mismatch remains unexplained; it is recorded as a source-definition/coverage discrepancy and is not used as a formula-tuning target.

## Reproduction
```bash
python3 -m unittest discover -s tools -p 'test_*.py' -v
python3 tools/deep_calculation_audit.py --seasons $(seq 1991 $(date +%Y)) --boot 2000
python3 tools/compare_speed_variants.py --seasons $(seq 1991 $(date +%Y)) --boot 5000
```


## Final leave-one-event-out findings (verified at PR head aba2408)
The expanded audit passed on 2,208 common event-seasons across 36 seasons, using 2,000 season-stratified bootstrap resamples. Each target tournament is excluded from both player profiles and the tour-average ace-rate anchor.

| Variant | Surface profile qualification | Pearson r | Spearman rho | MAE |
|---|---:|---:|---:|---:|
| Unshrunk baseline | 3 matches | 0.880 | 0.894 | 0.080 |
| Fixed 100-point prior | 3 matches | 0.917 | 0.924 | 0.067 |
| Fixed 150-point prior | 3 matches | 0.920 | 0.927 | 0.066 |
| Fixed 300-point prior | 3 matches | 0.921 | 0.927 | 0.068 |
| Empirical-Bayes prior | 3 matches | 0.915 | 0.922 | 0.067 |

The fixed 150-point prior is marginally best on the full event-season sample. Empirical Bayes is slightly better on the 2021–2025 held-out set (MAE 0.063 versus 0.064) while slightly worse on the 1991–2020 training set (0.068 versus 0.067). These small differences require paired bootstrap-difference intervals rather than selecting by separate marginal confidence intervals. One-match variants score better overall, but the gain mixes shrinkage with a lower qualification threshold; the standard three-match threshold remains in place.

The 50/50 clay/hard-grass blend gives a modest improvement versus 1/3–2/3 on the behavioural TA reference (LOEO MAE 0.077 vs 0.079; held-out MAE 0.074 vs 0.079). Keep the existing 1/3–2/3 blend for now because it has a published neutral-player rationale; this is not an independent physical-pace criterion.

Carpet-specific profiles are rejected (carpet MAE 0.367); pooled-only carpet profiles are also rejected (MAE 0.144). Preserve the neutral profile treatment for carpet.

Conditional match-cluster intervals: Marrakech without shrinkage is 1.180 (26 matches, 40 sides; 95% CI 0.877–1.596). With fixed 150-point shrinkage and the three-match threshold it is 1.049 (41 sides; 95% CI 0.866–1.261); empirical Bayes at the same threshold gives 1.083 (41 sides; 95% CI 0.879–1.305). Laver Cup remains provisional (7 matches, 14 sides): unshrunk 0.464 (95% CI 0.271–0.713), fixed-150 0.472 (0.280–0.726), empirical-Bayes 0.470 (0.275–0.716). These intervals condition on fitted profiles and season normalisation and do not include full profile-estimation uncertainty.

The physical CPI overlap has 106 observations from a non-random set of show-court events: overall r=0.676, rho=0.702; within-surface centred r=0.395, rho=0.345. Clay alone is negatively associated in this small overlap (n=24, r=-0.387, rho=-0.429), while hard is positive (n=81, r=0.511, rho=0.506). This is diagnostic evidence that CALC is behavioural, not a physical court-speed substitute.

The 2026 production asset at the pre-fix audit baseline contained 57 CALC / 1 TA-only, with Marrakech at 1.11. The production refresh after this audit change recalculates the event ratings using the LOEO/150-point estimator; the pre-fix 1.11 value should not be carried forward as the post-fix result.


## Live-baseline leakage and prior consistency hardening
- Active tournament IDs are read from data/live.json only when its timestamp is within 24 hours and the event date window includes the current date.
- With a fresh manifest, active-event match rows are excluded from current-season player profiles, the tour-average ace-rate anchor and live baseline normaliser. Excluded event IDs and row counts are recorded in data/live_speed_baseline.json.
- With a stale or missing manifest, the live baseline falls back to the previous completed season; it does not assume a stale event list is complete.
- Live low-sample shrinkage now uses the same 150-service-point prior as historical ratings; the previous 300-point live fallback was inconsistent.
- The Method screen no longer asserts a typical hard range of 0.50–1.50 and clarifies that CALC is a relative behavioural measure, not absolute physical court speed.
- Regression tests cover fresh/current event filtering, stale-manifest rejection and removal of active-event rows from baseline training.
