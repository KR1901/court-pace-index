# PR #2 — Expected-aces formulation, calculation audit and provenance

**Branch:** `analysis/expected-aces-formula-comparison`  
**Base:** `fix/live-tournament-discovery-and-refresh` (PR #1 branch)  
**Policy:** PR #2 remains open and unmerged. Do not merge either PR as part of this work.

## Current implementation

- Historical and Android live ratings use the calibrated server/returner odds-ratio expected-aces equation.
- Season tour-average ace odds provide the neutral anchor.
- The expected-aces floor of 0.5 is applied to the denominator of the actual/expected ratio; it is not a coverage filter.
- Historical ratings use leave-one-tournament-out player profiles and tour anchor.
- Live ratings use the matching season baseline, profile backfill and explicitly tagged shrinkage fallbacks; active events are excluded from baseline training when the live manifest is fresh.
- CALC is primary when an archive calculation exists. Published Tennis Abstract values remain visible as references; TA-only values remain labelled fallbacks.
- Live diagnostics expose raw score, normalisation, tour ace rate, service-point totals, matched profile counts and influential server-side diagnostics.

## Statistical evidence

Latest completed formula comparison: 1,289 common event-seasons, 36 seasons (1991–2026), 5,000 season-stratified paired bootstrap resamples.

| Variant | Pearson r | Spearman rho | MAE |
|---|---:|---:|---:|
| Uncalibrated, no floor | 0.833 | 0.815 | 0.086 |
| Uncalibrated, 0.5 floor | 0.878 | 0.861 | 0.076 |
| Calibrated pooled returner | 0.925 | 0.917 | 0.055 |
| **Calibrated surface-returner** | **0.925** | **0.926** | **0.052** |
| Ratio of sums | 0.904 | 0.890 | 0.065 |
| Pooled | 0.837 | 0.830 | 0.097 |

The calibrated surface-returner configuration remains the strongest overall candidate. The old uncalibrated floor bound 63.6% of valid sides; the calibrated floor bound 0.8%.

The 150-service-point shrinkage configuration improved historical out-of-sample agreement over the unshrunk profile. Full-pipeline bootstrap intervals are wider than conditional match-only intervals because profile and target-event uncertainty are refitted.

## Event findings

- **Montpellier 2026:** CALC 1.60 versus TA 1.49. The TA value lies inside the full-pipeline CALC 95% interval [1.370, 1.986]. CALC ace rate (14.2%) differs materially from TA's published 19.8%; exact match-population and source-stat reconciliation remains outstanding. Do not assume qualifying scope explains the difference.
- **Marrakech 2026:** CALC 1.049 with full-pipeline 95% interval [0.866, 1.304]. The point estimate is uncertain; no formula change is justified by its ranking alone.
- **Clay:** CALC-to-TA agreement is strong in the historical surface-stratified comparison, but the small CPI clay cross-check (24 events) has a negative association. CPI coverage is sparse and selected; this is a validation warning, not sufficient evidence for a clay-specific formula.

## Live diagnostic evidence

The live diagnostics expose raw adjusted event score, season normalisation, tour-average ace rate, total service points, profile match counts and high/low actual-to-expected server-side ratios. A newly computed live result is required for detailed side diagnostics; old cached summary results may not contain them.

## Provenance and limitations

- Historical data: Jeff Sackmann ATP archive mirror.
- Current season: TennisMyLife ATP match CSV when available, with a seven-day hash-verified cache fallback and logged source fallback.
- Tennis Abstract is a comparison/reference series, not physical ground truth.
- TML public terms restrict use to personal, educational and non-commercial research; raw CSV redistribution/resale is not authorized by the public terms. Obtain written permission for commercial use or broader redistribution where required. See [DATA-LICENSES.md](../DATA-LICENSES.md).
- `tools/audit_event_population.py` now uses the same eligibility tests and ordering as `compute_speed.valid_rows`; the unit tests compare both paths directly. `--require-source` makes a current-season report fail closed unless the selected source is explicitly TennisMyLife.
- The dedicated event-population workflow now runs the Montpellier 2026 report with the TML source requirement. Its completed job output is the acceptance record; a unit-test-only pass is not an exact source-population reconciliation. Reports remain on the runner and raw source CSVs are not committed.
- Physical validation should use independently measured court pace (ITF CPR/CPI) with measurement date, court identity, surface, environmental and ball metadata. Do not tune the formula against the same observations used to report validation.

## Verification

Latest completed four-workflow run on the preceding head `d01025706a754a292445b7dbcbdf5f6b1bf0573c` passed Event population audit (unit tests and CLI syntax), formula comparison, Android CI and deep calculation audit. The event-population workflow has since been strengthened to execute the real Montpellier 2026 reconciliation against a source-verified TML snapshot; that newer invocation is not considered complete until its own run succeeds and its summary is inspected.

Historical run records:

- Android CI passed on `24c4331101b0aca8d299a0cb9df88b239bc61beb`.
- Formula comparison: [workflow run 42462414601](https://github.com/KR1901/court-pace-index/actions/runs/42462414601).
- Deep calculation audit: [workflow run 42462414602](https://github.com/KR1901/court-pace-index/actions/runs/42462414602).
- Android CI: [workflow run 42462414587](https://github.com/KR1901/court-pace-index/actions/runs/42462414587).

These historical runs do not verify the newer source-required Montpellier reconciliation. Check the latest PR #2 head and its event-population job before treating that reconciliation as complete.
