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
| LOEO-profile-rateable sides after expansion | 54 |
| Neutral tour-prior sides | 0 |
| Aces / service points | 545 / 3,839 |
| Source ace rate | 14.1964% |

The 35-season history and unique-name TML/Sackmann crosswalk recover all seven previously missing sides using personal player-history rates. Montpellier requires no neutral tour-prior side. This explains the prior 54-versus-47 profile-coverage discrepancy. It does not establish Tennis Abstract's match population or explain its published 19.8% ace rate. No qualifying-scope explanation is assumed.

## Full player-profile coverage: 1991–2026

The all-event audit swept every mapped event in each season's selected ATP source from 1991 through 2026. The completed 36-season run reported:

| Measure | Result |
|---|---:|
| Seasons audited | 36 |
| Mapped event-seasons | 1,533 |
| Eligible matches | 71,150 |
| Statistically eligible server sides | 142,300 |
| Rateable server sides | 142,300 (100%) |
| Unrateable sides | 0 |
| Sides using at least one neutral tour-average prior | 1,584 (1.113%) |

The 100% figure means every source-stat-eligible side received finite inputs to the expected-aces calculation; it does **not** mean every role has an individual player-history estimate. A neutral prior is a transparent coverage fallback, not a recovered personal statistic.

The fallback-reason audit found **zero** occurrences where a player profile record existed but lacked a usable role rate, and **zero** ambiguous normalized-name collisions. All 1,610 missing role-estimate occurrences were classified as **no player profile found in the selected history window**: 805 server-role and 805 returner-role occurrences, summed across seasons. The role count exceeds the 1,584 side count because some sides required a prior for both roles. These aggregate diagnostics do not publish player names or IDs.

For 2026 specifically, the audit covered 58 mapped events, 2,519 matches and 5,038 server sides. All 5,038 were rateable; 70 sides (1.39%) used at least one neutral tour-average prior. Those 70 sides corresponded to 72 missing role inputs and 36 distinct fallback player IDs in the 2026 season audit.

**What this says about the remaining gap:** the current gap is not primarily a player record whose serve/return rate failed to calculate. It is an identity/history coverage gap in the selected ATP main-draw profile pool. The audit alone cannot determine whether each player is genuinely new to the available match-stat archive, has prior matches only in a lower competition tier, or has a name/identity crosswalk mismatch. We must not turn ambiguous matches into automatic identity links.

A promising next research input is Sackmann's separate qualifying/Challenger match archive. Its upstream documentation states that serve statistics are generally available from 2008 for Challenger matches and 2011 for tour-level qualifying. This data has different competition-level context and is licensed CC BY-NC-SA 4.0. It is **not** folded into production profiles by this PR: a safe next experiment must match only unique identities, keep the target tournament excluded, quantify actual gap recovery, and calibrate the lower-tier observations with out-of-sample evidence before they can influence ATP-level ratings. [Archive documentation](https://github.com/Aneeshers/tennis-sackmann-archive/blob/main/atp/UPSTREAM_README.md) · [Archive licence](https://github.com/Aneeshers/tennis-sackmann-archive/blob/main/LICENSE).

This is the mapped event population in the selected source files, not a claim that every ATP event or every match is present and not proof that the source population equals Tennis Abstract's population. The consolidated workflow retains aggregate season/event CSVs, a machine-readable report and a README as a GitHub Actions artifact for 90 days; it excludes raw source CSVs, player names and player IDs. The completed report is attached to [Event population audit run #42479832106](https://github.com/KR1901/court-pace-index/actions/runs/42479832106).

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

On code head \`9042c2201c6902c15d79ad302fe84824a61d771e\`:

- [Event population audit #79](https://github.com/KR1901/court-pace-index/actions/runs/42479832106): passed. It ran the Montpellier source reconciliation, swept all mapped events from 1991 through 2026, aggregated the report and uploaded the 90-day artifact \`all-event-profile-coverage-1991-2026\`.
- [Expected-aces formula comparison #210](https://github.com/KR1901/court-pace-index/actions/runs/42479833008): passed.
- [Android CI #584](https://github.com/KR1901/court-pace-index/actions/runs/42479832112): passed.
- [Deep calculation audit #140](https://github.com/KR1901/court-pace-index/actions/runs/42479832179): in progress when this record was written; its completed result must be checked before claiming the full four-workflow set is green.

The documentation-only updates after this code head preserve the same calculation implementation. PR #2 remains open and unmerged; PR #1's branch has not been modified.

## Reproduction

```bash
python3 -m unittest discover -s tools -p "test_*.py" -v
python3 tools/compare_speed_variants.py --seasons $(seq 1991 $(date +%Y)) --boot 5000
python3 tools/deep_calculation_audit.py --seasons $(seq 1991 $(date +%Y)) --boot 2000 --detail marrakech,lavercup,montpellier,paris
python3 tools/audit_event_population.py --event Montpellier --season 2026 --require-source "TennisMyLife ATP Match Database"
```
