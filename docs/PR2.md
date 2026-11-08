# PR #2 — Expected-aces formulation, calculation audit and provenance

**Branch:** `analysis/expected-aces-formula-comparison`  
**Base:** `fix/live-tournament-discovery-and-refresh` (PR #1 branch)  
**Policy:** PR #2 is open and unmerged. Do not merge either PR as part of this work.

## Scope

This PR implements and audits the calibrated expected-aces Surface Speed methodology, its historical/live profile integration, diagnostics, reproducibility tools and supporting documentation. PR #1 remains the separate live match-ingestion foundation.

## Production method

- Calibrated expected-aces odds ratio combines server ace tendency and returner ace-concession tendency against a season tour-average ace-odds anchor.
- Each valid server side contributes actual aces divided by expected aces, with a 0.5 expected-aces denominator floor.
- Event raw scores are equal-weight means of valid server-side ratios, season-normalised.
- Historical ratings use leave-one-tournament-out profiles and tour anchor, 150-service-point shrinkage and a three-match surface qualification threshold.
- Live ratings use the same equation and season normalisation with a distinct, explicitly tagged profile-backfill policy and active-event exclusion when the live manifest is fresh.
- CALC is primary when an archive calculation is available; Tennis Abstract remains a behavioural reference or labelled fallback.
- Live diagnostics expose raw score, normalisation, tour ace rate, service-point totals, matched profiles and influential side ratios.

## Statistical audit

Latest completed comparison: 1,289 common event-seasons across 36 seasons (1991–2026), 5,000 season-stratified paired bootstrap resamples.

| Variant | Pearson r | Spearman rho | MAE |
|---|---:|---:|---:|
| Uncalibrated, no floor | 0.833 | 0.815 | 0.086 |
| Uncalibrated, 0.5 floor | 0.878 | 0.861 | 0.076 |
| Calibrated pooled returner | 0.925 | 0.917 | 0.055 |
| **Calibrated surface-returner** | **0.925** | **0.926** | **0.052** |
| Ratio of sums | 0.904 | 0.890 | 0.065 |
| Pooled | 0.837 | 0.830 | 0.097 |

The surface-returner candidate has the best MAE and Spearman rank agreement in the full sample; Pearson is tied with calibrated pooled returner. The old uncalibrated floor bound 63.6% of sides; the calibrated floor bound 0.8%. The 150-point prior improves out-of-sample reference agreement over the unshrunk profile. TA agreement is not physical validation.

## Montpellier 2026: completed source and model-coverage audit

The source-required event audit loaded the **TennisMyLife ATP Match Database** and checked the same eligibility and leave-one-event-out profile functions used by the production calculation.

Source snapshot: retrieved 8 November 2026 at 10:02:19 UTC; latest match date 1 November 2026; 2,719 rows; SHA-256 `4219d664cb7f4989b2529791ed60cbc6276b0b621708016799064163bda9efe8`.

| Population / stage | Result |
|---|---:|
| Montpellier raw event rows | 27 |
| Source-stat eligible matches | 27 |
| Statistically complete server sides | 54 |
| LOEO-profile-rateable sides | 47 |
| Missing server profile | 5 |
| Missing returner profile | 1 |
| Both profiles missing | 1 |
| Invalid expected-aces values after profile match | 0 |
| Aces / service points | 545 / 3,839 |
| Source ace rate | 14.1964% |

This fully accounts for the 54-versus-47 CALC coverage gap: seven source-stat-complete sides lack the profile inputs required to estimate expected aces. It does not establish Tennis Abstract's match population or explain its published 19.8% ace rate. No qualifying-scope explanation is assumed. [Source-required workflow run #42469683324](https://github.com/KR1901/court-pace-index/actions/runs/42469683324).

## Other findings and remaining validation

- Montpellier CALC 1.60 versus TA 1.49; TA point estimate is inside the full-pipeline CALC 95% interval [1.370, 1.986].
- Marrakech 2026 CALC 1.049; full-pipeline 95% interval [0.866, 1.304]. Its point ranking alone does not justify changing the formula.
- The CPI cross-check contains only 106 selected show-court overlaps; clay n=24 has negative association. This is a validation warning, not evidence for a clay-specific correction.
- Tennis Abstract is a behavioural reference, not physical ground truth. See `docs/PHYSICAL_VALIDATION.md` for the CPR/CPI dataset schema and protocol.
- Exact TA match-population/stat-definition reconciliation still requires an authorized TA source or independently documented evidence.
- Expanded independent physical CPR/CPI observations are still needed.

## Repository documentation and source rights

- `README.md`, `docs/DATA.md`, `docs/PR1.md`, `docs/PR2.md`, `docs/FORMULATION_AUDIT.md` and `docs/PHYSICAL_VALIDATION.md` describe the current app and evidence.
- `docs/ABOUT.md` records the recommended GitHub About description and Topics.
- `DATA-LICENSES.md` explains source-specific rights; `LICENSE` covers original project code only.
- TML's public terms limit data to personal, educational and non-commercial research, do not authorize raw CSV redistribution, and do not clearly grant onward distribution of derived ratings in a public app. The current TML-derived bundled output is not treated as cleared for onward distribution until the source confirms that use in writing.
- ATP terms restrict systematic retrieval of scores/statistics without prior written permission. Review `DATA-LICENSES.md` before operating or redistributing data-derived functionality.

## Verification

On source/profile-audit code head `461272379dc3e69a4b444111b022bf0721f340ef`:
- [Event population audit](https://github.com/KR1901/court-pace-index/actions/runs/42469683324): passed, including the source-required Montpellier report.
- [Expected-aces formula comparison](https://github.com/KR1901/court-pace-index/actions/runs/42469683149): passed.
- [Android CI](https://github.com/KR1901/court-pace-index/actions/runs/42469683108): passed.
- [Deep calculation audit](https://github.com/KR1901/court-pace-index/actions/runs/42469683166): passed; the 1991–2026 LOEO, shrinkage, weighting and physical cross-check audit completed and its report artifact was uploaded.

The preceding code head `d01025706a754a292445b7dbcbdf5f6b1bf0573c` passed all four workflows, including the deep audit. The documentation-only updates after `4612723` do not alter the calculation code.

## Reproduction

```bash
python3 -m unittest discover -s tools -p "test_*.py" -v
python3 tools/compare_speed_variants.py --seasons $(seq 1991 $(date +%Y)) --boot 5000
python3 tools/deep_calculation_audit.py --seasons $(seq 1991 $(date +%Y)) --boot 2000 --detail marrakech,lavercup,montpellier,paris
python3 tools/audit_event_population.py --event Montpellier --season 2026 --require-source "TennisMyLife ATP Match Database"
```
