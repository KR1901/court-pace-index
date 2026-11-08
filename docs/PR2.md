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
| Sides using at least one neutral tour-average prior | 1,930 (1.356%) |

The 100% figure means every source-stat-eligible side received finite inputs to the expected-aces calculation; it does **not** mean every role has an individual player-history estimate. A neutral prior is a transparent coverage fallback, not a recovered personal statistic. **Audit accounting correction:** the prior audit mutated a missing player-role to `tour-average-prior-only` on its first match and then failed to count later sides reusing that role. The corrected 1991–2026 run counts **1,930 affected sides (1.356%)**, comprising **980 server-role uses** and **980 returner-role uses**. The 1,960 role uses exceed the affected-side count because 30 sides needed a prior for both roles.

The fallback-reason audit found **zero** occurrences where a player profile record existed but lacked a usable role rate, and **zero** ambiguous normalized-name collisions. The 1,610 unique missing player-role assignments were classified as **no player profile found in the selected history window**: 805 server-role and 805 returner-role assignments, summed across seasons. These unique assignments are separate from the 1,960 per-side role uses. The report does not publish player names or IDs.

For 2026 specifically, 58 mapped events and 2,519 matches produced 5,038 eligible sides, all rateable. **84 sides (1.667%)** used at least one neutral prior: **43 server-role uses** and **43 returner-role uses**. These are 72 unique missing role assignments (36 server, 36 returner) across 36 distinct fallback player IDs and 25 events. Montpellier remains 54/54 rateable with no neutral-prior use.

**What this says about the remaining gap:** the current gap is not primarily a player record whose serve/return rate failed to calculate. It is an identity/history coverage gap in the selected ATP main-draw profile pool. The audit alone cannot determine whether each player is genuinely new to the available match-stat archive, has prior matches only in a lower competition tier, or has a name/identity crosswalk mismatch. We must not turn ambiguous matches into automatic identity links.

The first research-only backfill candidate should be TennisMyLife's ATP Tour Qualifying dataset. Its database documentation says it uses ATP player IDs and provides the same ace/service-point fields; its public catalogue lists separate yearly qualifying and Challenger files. Because the IDs align with the current TML main-tour source, this is a better first identity-matching test than fuzzy name crosswalking. No qualifying/Challenger rows have yet been downloaded, matched or included in production profiles. TML's public terms limit use to personal, educational and non-commercial research and prohibit raw CSV redistribution; they do not clearly authorize onward distribution of TML-derived ratings in a public app. [TennisMyLife match database, catalogue, fields and terms](https://stats.tennismylife.org/tennis-match-database).

A second research source is Sackmann's separate qualifying/Challenger archive. Its upstream documentation says match statistics are generally available from 2008 for Challenger matches and 2011 for tour-level qualifying, under CC BY-NC-SA 4.0. This source uses Sackmann IDs and requires a unique-identity crosswalk. Lower-tier observations change opponent mix and ace outcomes; any experiment must keep them separately labelled, exclude target events, quantify gap recovery and calibrate competition-level effects with held-out validation before they can influence ATP-level ratings. [Archive documentation](https://github.com/Aneeshers/tennis-sackmann-archive/blob/main/atp/UPSTREAM_README.md) · [Archive licence](https://github.com/Aneeshers/tennis-sackmann-archive/blob/main/LICENSE).

This is the mapped event population in the selected source files, not a claim that every ATP event or every match is present and not proof that the source population equals Tennis Abstract's population. The consolidated workflow retains aggregate season/event CSVs, a machine-readable report and a README as a GitHub Actions artifact for 90 days; it excludes raw source CSVs, player names and player IDs. The latest ambiguity-aware report is attached to [Event population audit run #92](https://github.com/KR1901/court-pace-index/actions/runs/42480868160).

## Other findings and remaining validation

- **Montpellier 2026 production full-history CALC:** 1.664, 54/54 server sides, rank 1/57; conditional match-cluster 95% interval [1.465, 1.862]. TA reference 1.49 lies inside the interval.
- **Marrakech 2026 production full-history CALC:** 0.999, 52/52 server sides, rank 34/57; conditional match-cluster 95% interval [0.828, 1.182]. TA reference 0.93 lies inside the interval.
- The separate deep-calculation sensitivity grid gives same-season-only values 1.600 for Montpellier and 1.049 for Marrakech, with 47 and 41 profile-supported sides respectively. Those intervals are not production 35-season full-history bootstrap intervals; see \`docs/FORMULATION_AUDIT.md\` for the distinction.
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

The neutral-prior side-use accounting correction and its regression test are on code head `ee41ac36b719acb4235e81477d2d34f8307a6fd4`. The latest validation runs on that code head are:

- [Event population audit #122](https://github.com/KR1901/court-pace-index/actions/runs/42483108359): **passed**. Unit tests, CLI check, source-required Montpellier reconciliation, all 36 annual all-event sweeps (1991–2026), aggregate report generation and 90-day artifact upload completed. The corrected report records 1,930 prior-affected sides (1.356%), with 980 server-role and 980 returner-role uses; 2026 records 84 sides (1.667%), with 43 uses for each role.
- [Expected-aces formula comparison #253](https://github.com/KR1901/court-pace-index/actions/runs/42483108399): **passed**.
- [Android CI #627](https://github.com/KR1901/court-pace-index/actions/runs/42483108406): **passed**, including unit tests, debug APK assembly and artifact upload.
- [Deep calculation audit #183](https://github.com/KR1901/court-pace-index/actions/runs/42483108360): still running at the time of this update. Its Python test suite passed; the leave-one-tournament-out, shrinkage, weighting and physical cross-check stage has not yet completed.

The Event population audit artifact is [all-event-profile-coverage-1991-2026](https://github.com/KR1901/court-pace-index/actions/runs/42483108359). The corrected audit reporting change does not alter expected-aces rates, tournament ratings or rankings; it fixes the accounting of repeated neutral-prior use.

PR #2 remains open and unmerged. PR #1's code branch has not been modified. The documentation-only commits after the validated code head update these audit findings and do not change calculation code.

## Reproduction

```bash
python3 -m unittest discover -s tools -p "test_*.py" -v
python3 tools/compare_speed_variants.py --seasons $(seq 1991 $(date +%Y)) --boot 5000
python3 tools/deep_calculation_audit.py --seasons $(seq 1991 $(date +%Y)) --boot 2000 --detail marrakech,lavercup,montpellier,paris
python3 tools/audit_event_population.py --event Montpellier --season 2026 --require-source "TennisMyLife ATP Match Database"
```
