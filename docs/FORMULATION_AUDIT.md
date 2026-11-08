# Calculation and formulation audit

**Current code-audit head:** `461272379dc3e69a4b444111b022bf0721f340ef`  
**PR:** [#2 — Evaluate expected-aces formulas against 2025–26 reference data](https://github.com/KR1901/court-pace-index/pull/2)  
**Status:** PR #2 is open and unmerged. This document is maintained on PR #2's branch.

## Executive finding

Retain the calibrated server/returner expected-aces formulation, equal-weight mean of valid server-side actual/expected ratios, 150-service-point profile shrinkage and three-match surface-qualification threshold. Across the current 1991–2026 comparison set, the calibrated surface-returner variant has the best combined historical rank agreement and mean absolute error among tested variants. The reference comparison is against Tennis Abstract, not a measurement of physical court speed.

No clay-specific correction is justified by the limited physical CPI cross-check. The data are sparse and selected, and the negative clay-only association is a warning to improve validation rather than proof that the formulation is physically biased.

## Production calculation

For a server side, let:

- (p_s) be the server's estimated ace probability per service point;
- (p_r) be the returner's estimated ace-concession probability per service point;
- (p_t) be the season tour-average ace probability per service point.

The calibrated odds ratio is:

[
O = rac{\left(\frac{p_s}{1-p_s}\right)\left(\frac{p_r}{1-p_r}\right)}
{\frac{p_t}{1-p_t}}
]

[
p_{expected} = \frac{O}{1+O}, \qquad
E[aces] = servicePoints \times p_{expected}
]

The valid server-side contribution is:

[
ratio = \frac{actualAces}{\max(E[aces],0.5)}
]

The event raw rating is the arithmetic mean of valid server-side ratios. The season normaliser is the mean raw score across eligible standard events; the published CALC value is the event raw score divided by that normaliser. The 0.5 expected-aces floor protects the denominator and is not an eligibility filter.

Historical ratings use leave-one-tournament-out player profiles and tour anchor: the target event's matches are excluded from both. The production profile estimator uses surface-qualified player rates, a 150-service-point prior toward the relevant surface tour rate, a neutral clay/hard-grass blend of one third/two thirds, and pooled returner fallback when qualified profiles are unavailable. Three matches are required for a surface-qualified player bucket.

The live baseline uses the same calibrated expected-aces equation and season normalisation but a separate, explicitly tagged profile-backfill policy. Active events are removed from training when the live manifest is fresh. That difference in profile coverage must not be mistaken for a difference in the expected-aces equation.

## Formula comparison against Tennis Abstract

Latest completed comparison: **1,289 common event-seasons across 36 seasons (1991–2026)**, using **5,000 season-stratified paired bootstrap resamples**.

| Variant | Pearson r | Spearman rho | MAE |
|---|---:|---:|---:|
| Uncalibrated, no floor | 0.833 | 0.815 | 0.086 |
| Uncalibrated, 0.5 floor | 0.878 | 0.861 | 0.076 |
| Calibrated pooled returner | 0.925 | 0.917 | 0.055 |
| **Calibrated surface-returner** | **0.925** | **0.926** | **0.052** |
| Ratio of sums | 0.904 | 0.890 | 0.065 |
| Pooled | 0.837 | 0.830 | 0.097 |

The calibrated surface-returner candidate has the best MAE and Spearman rank agreement in the full sample; its Pearson correlation is tied with calibrated pooled returner. This supports its use as the current candidate, but not a claim of physical validity.

The old uncalibrated 0.5 floor bound 63.6% of valid sides; after calibration, the floor bound 0.8%. This is why a floor-only change to historical values was not a neutral denominator adjustment.

The 150-service-point shrinkage configuration improved out-of-sample reference agreement over the unshrunk profile. Paired held-out differences were:
- Pearson: +0.0405 [0.0310, 0.0514]
- Spearman: +0.0331 [0.0268, 0.0396]
- MAE: −0.0139 [−0.0161, −0.0117]

Surface-stratified TA reference results for the 150-point profile were:
- Clay: n=772, Spearman 0.904, MAE 0.058, bias −0.009.
- Hard: n=1,066, Spearman 0.882, MAE 0.063, bias −0.002.
- Grass: n=191, Spearman 0.818, MAE 0.076, bias −0.002.
- Carpet: n=179, Spearman 0.821, MAE 0.097, bias +0.053.

Carpet-specific profile variants performed poorly; retain the pooled fallback treatment rather than introducing carpet-only player profiles. A 50/50 clay/hard-grass weighting can improve some behavioural-reference comparisons, but the established one-third/two-thirds neutral blend is retained rather than tuning weights to the same TA series.

## Event uncertainty and sensitivity

The full-pipeline event bootstrap refits player profiles, target-event matches and the season LOEO normaliser on each draw (250 successful draws per event in the reported run):

| Season / event | CALC | Full-pipeline 95% interval | Matches / rateable sides |
|---|---:|---:|---:|
| 2025 Marrakech | 0.772 | [0.607, 0.933] | 27 / 47 |
| 2025 Montpellier | 1.304 | [1.067, 1.563] | 27 / 45 |
| 2026 Marrakech | 1.049 | [0.866, 1.304] | 26 / 41 |
| 2026 Montpellier | 1.600 | [1.370, 1.986] | 27 / 47 |

Conditional match-cluster intervals hold the fitted profiles and normaliser fixed; they are narrower and must not be confused with full-pipeline intervals. Laver Cup 2026 remains provisional at 0.472 (7 matches / 14 sides; full-pipeline interval [0.276, 0.706]) and is not promoted into the ordinary ranking.

## Montpellier 2026 source-population and model-coverage reconciliation

A source-required CI run loaded **TennisMyLife ATP Match Database** and ran the audit against the exact source used by `compute_speed.py`.

Snapshot provenance:
- URL: `https://stats.tennismylife.org/data/2026.csv`
- Retrieved: 8 November 2026, 10:02:19 UTC
- Latest match date in source: 1 November 2026
- Total source rows: 2,719
- SHA-256: `4219d664cb7f4989b2529791ed60cbc6276b0b621708016799064163bda9efe8`

Montpellier reconciliation:

| Population / stage | Result |
|---|---:|
| Raw event rows | 27 |
| Source-stat eligible main-tour matches | 27 |
| Statistically complete server sides | 54 |
| Sides rateable with LOEO profiles | 47 |
| Missing server profile | 5 |
| Missing returner profile | 1 |
| Both profiles missing | 1 |
| Invalid expected-aces values after profile match | 0 |
| Aces / service points | 545 / 3,839 |
| Source ace rate | 14.1964% |

This establishes why 54 source-stat-complete server sides become 47 rateable sides in the opponent-adjusted CALC event rating: seven lack the player-profile inputs required by the production formula. It does **not** establish Tennis Abstract's match population or explain the published TA ace rate of 19.8%, compared with the TML/CALC source rate of 14.1964%. No qualifying-scope explanation is assumed.

[Source-required reconciliation workflow run #42469683324](https://github.com/KR1901/court-pace-index/actions/runs/42469683324).

## Physical cross-check

The available CPI overlap contains 106 observations from a small, non-random selection of show-court events:
- Overall: Pearson 0.662, Spearman 0.695.
- Within-surface centred: Pearson 0.395, Spearman 0.346.
- Clay: n=24, Pearson −0.429, Spearman −0.442.
- Hard: n=81, Pearson 0.512, Spearman 0.507.

The clay result merits investigation but is not a sufficient basis for a clay-specific correction. CPI is limited to measured courts and does not provide a complete or randomly sampled tournament-level physical benchmark. Tennis Abstract agreement is behavioural comparison, not independent physical validation. The data schema and protocol for expanding ITF CPR/CPI validation are in [PHYSICAL_VALIDATION.md](PHYSICAL_VALIDATION.md); the plan is not itself an expanded dataset.

## Montpellier and Marrakech interpretation

Montpellier's CALC rating is 1.60 versus TA's 1.49. TA is within the full-pipeline CALC interval. The source audit explains the difference between 54 complete statistics sides and 47 model-rateable sides, but not the 14.2% versus 19.8% ace-rate difference with TA. TA's exact population and statistic definitions remain unverified.

Marrakech 2026 CALC is 1.049 with a full-pipeline interval [0.866, 1.304]. Its ordinal placement alone is not evidence of a formula defect. Do not infer physical speed or a systematic clay adjustment from that one rating.

## Live baseline and coverage controls

- Active tournament IDs are removed from live player profiles, the tour ace-rate anchor and the live normaliser only when the manifest is within the seven-day freshness window and the event's date window is current.
- With a stale or missing live manifest, the baseline falls back to the previous completed season; a stale event list is not presumed complete.
- The current live baseline uses the 150-service-point low-sample prior, consistent with the historical shrinkage strength.
- Source, match and profile diagnostics are recorded so missing profile inputs and unavailable match statistics are distinguishable.
- A valid stats side without the required serve/returner profile remains unrateable; the calculation must not fabricate a neutral profile solely to inflate coverage.
- CI does not prove ATP/Infosys retrieval will succeed on a physical handset. Validate live coverage on-device and leave HTTP/CDN denials visible.

## Source and licence limitations

Historical ATP match data is sourced from the Jeff Sackmann archive mirror (CC BY-NC-SA 4.0). The 2026 current-season calculation used TennisMyLife data; its public terms are limited to personal, educational and non-commercial research, do not authorize raw CSV redistribution, and do not clearly grant onward distribution of TML-derived ratings in a public app. The 2026 bundled TML-derived output is not treated as cleared for onward distribution until TennisMyLife confirms that use in writing. ATP/Infosys terms also restrict systematic retrieval of scores and statistics absent prior express written permission. See [DATA-LICENSES.md](../DATA-LICENSES.md). The root MIT licence applies to original code, not third-party data.

## Verification

On source/profile-audit code head `461272379dc3e69a4b444111b022bf0721f340ef`:
- [Event population audit](https://github.com/KR1901/court-pace-index/actions/runs/42469683324): passed, including the source-required Montpellier reconciliation.
- [Expected-aces formula comparison](https://github.com/KR1901/court-pace-index/actions/runs/42469683149): passed.
- [Android CI](https://github.com/KR1901/court-pace-index/actions/runs/42469683108): passed.
- [Deep calculation audit](https://github.com/KR1901/court-pace-index/actions/runs/42469683166): pending at last check.

The preceding code head `d01025706a754a292445b7dbcbdf5f6b1bf0573c` passed all four workflows, including deep calculation audit: [run #42468091511](https://github.com/KR1901/court-pace-index/actions/runs/42468091511). The updated source/profile coverage tooling was separately validated by run #42469683324. Later commits changing only documentation do not alter the audited calculation code.

## Reproduction

```bash
python3 -m unittest discover -s tools -p "test_*.py" -v
python3 tools/compare_speed_variants.py --seasons $(seq 1991 $(date +%Y)) --boot 5000
python3 tools/deep_calculation_audit.py --seasons $(seq 1991 $(date +%Y)) --boot 2000 --detail marrakech,lavercup,montpellier,paris
python3 tools/audit_event_population.py --event Montpellier --season 2026 --require-source "TennisMyLife ATP Match Database"
```

Event-population output is local under `build/event-population-audit/`. Do not commit the raw TML CSV or generated match-level report to a public repository. The audit summary reports the source label, source hash and aggregate event totals.
