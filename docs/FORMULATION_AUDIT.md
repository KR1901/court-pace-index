# Calculation and formulation audit

## Scope and baseline
- Repository: `KR1901/court-pace-index`
- PR #2 baseline: `f1693cf6349c0050fc575bdf848cb4015ed629a2`
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
3. Service-point prior sensitivity: 150, 300, 600 and 1,000 pseudo service points.
4. A carpet-pooled stress test and a carpet-specific profile experiment that requires three carpet matches before use, with neutral-profile fallback otherwise.
5. A method-of-moments empirical-Bayes prior estimated separately by serve/return role and surface bucket from player-rate heterogeneity after excluding the target event.
6. Season-stratified event bootstrap, training/held-out weight comparisons, surface-stratified metrics and match-cluster uncertainty intervals for selected 2026 events.
6. Physical CPI overlap diagnostics, explicitly exploratory because CPI covers a small, non-random set of show-court events.
7. A seven-day source freshness and SHA-256 integrity gate for cached TML current-season data.
8. Conservative resolution of conflicting same-season live player-name collisions.
9. Clear documentation that historical and live calculations share the expected-aces equation but have different profile fallback policies.

## Decision rules
- Retain calibrated odds-ratio expected aces as the production foundation; the existing 35-season benchmark materially improves on the uncalibrated method.
- Do not replace equal-weight event means with ratio-of-sums; its historical reference agreement is weaker.
- Do not promote surface-matched returner profiles universally until leave-one-out and carpet-specific results support it.
- A carpet-specific profile is only used when the player has at least three carpet matches; otherwise the neutral surface profile is retained. This is an experiment, not a production change.
- Select weights on training seasons only and report held-out seasons separately.
- Compare fixed service-point priors with empirical-Bayes shrinkage. Do not promote the estimated prior until its leave-one-event-out and held-out 2021–2025 metrics beat or match the fixed-prior benchmark without materially harming any surface.
- Separate shrinkage from minimum-match coverage: compare one-match profiles and three-match profiles with the same prior strengths before changing the production qualification threshold.
- Match-cluster intervals condition on fitted player profiles and the season normaliser; full profile-estimation uncertainty remains a follow-up.
- CPI association is diagnostic, not proof of physical validity. Independent court-level and conditions data are needed for physical or causal claims.
- Laver Cup remains below the ordinary 20-side threshold unless a multi-year or explicitly provisional method is independently validated.
- TML public terms are non-commercial; obtain written permission before commercial use or redistribution. Raw TML match CSV files are not committed.
- Marrakech source reconciliation (2026): TML main draw is 259 aces / 4,019 service points (6.44%); qualifying adds 100 / 1,559 (6.41%); combined 359 / 5,578 (6.44%). Tennis Abstract's 2 November 2026 table publishes 8.0% Ace% and 0.93 speed. The Ace% mismatch remains unexplained; it is recorded as a source-definition/coverage discrepancy and is not used as a formula-tuning target.

## Reproduction
```bash
python3 -m unittest discover -s tools -p 'test_*.py' -v
python3 tools/deep_calculation_audit.py --seasons $(seq 1991 $(date +%Y)) --boot 2000
python3 tools/compare_speed_variants.py --seasons $(seq 1991 $(date +%Y)) --boot 5000
```
