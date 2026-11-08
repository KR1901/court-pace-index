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

## 2026 available-to-date season extension and chronological profile backfill

The earlier pinned Sackmann 2026 file was a partial snapshot through 17 May. The research-only forward test now uses the available-to-date TML 2026 season datasets, including ongoing feeds, and evaluates ATP matches chronologically. This is separate from production ratings.

| Source population | Valid rows available through the 8 Nov snapshot | Rows with match-date key on/after 18 May |
|---|---:|---:|
| ATP main draw + ongoing ATP feed | 2,572 | 1,444 |
| Challenger + ongoing Challenger feed | 5,993 | 3,405 |
| ATP qualifying Q1/Q2/Q3 | 1,268 | 753 |

The ATP static file includes valid matches through 1 November and the ongoing ATP feed through 7 November. The qualifying file's latest valid date key is 9 November; this is reported as source metadata, not treated as evidence that future matches have been played. This is the complete **available-to-date** snapshot, not the results of tournaments still unplayed.

The previous 2026 forward-test report undercounted matches because TML's date key changes on each match day within a tournament and the event accumulator overwrote previous days. That report's 150-side total is invalid and superseded. The accumulator now adds every match day to its tournament totals, and regression tests verify multi-day event accumulation and strict same-day/future exclusion.

The surface-specific profile weights were selected on the 2012–2018 development data only:

| Surface | Selected arm | Development sides | Development event-seasons |
|---|---|---:|---:|
| Clay | Challenger + qualifying, each at 1.00 | 11,388 | 155 |
| Hard | Qualifying at 0.25 | 20,996 | 261 |
| Grass | ATP-only | 4,456 | 49 |

The policy was then applied to all available 2026 ATP main-draw match sides, with date-ordered same-season history. Prior 2026 ATP main-draw matches update both player profiles and the ATP-only population anchor. Prior 2026 Challenger / qualifying matches update player-role profiles only. Every match with the same date as the target ATP match-day and all future matches are excluded. This allows later 2026 lower-tier results to inform later 2026 ATP matches without look-ahead.

| Full available-to-date 2026 | ATP-only profiles | Surface-specific policy |
|---|---:|---:|
| Server sides | 5,144 | 5,144 |
| Both roles with personal histories | 97.74% | 99.49% |
| Point-weighted ace-rate MAE | 0.035828 | 0.034805 |
| Binomial log loss / service point | 0.285229 | 0.284828 |
| Calibration bias | +0.007953 | +0.011978 |

The 2,000-resample paired event-cluster bootstrap for policy minus ATP-only is [-0.000707, -0.000120] for log loss and [-0.001510, -0.000582] for point-weighted MAE. Both intervals favour the surface policy on the full available 2026 dataset. Calibration bias becomes worse.

For the 1,444 ATP matches / 2,888 server sides dated on or after 18 May:

| Post-17-May 2026 | ATP-only profiles | Surface-specific policy |
|---|---:|---:|
| Both roles with personal histories | 97.58% | 99.31% |
| Point-weighted ace-rate MAE | 0.034921 | 0.034032 |
| Binomial log loss / service point | 0.288031 | 0.287696 |
| Calibration bias | +0.008520 | +0.011933 |

The post-17-May paired 95% interval is [-0.000716, +0.000018] for log loss (includes zero; inconclusive) and [-0.001475, -0.000400] for point-weighted MAE (favours the policy). Calibration again worsens. Therefore, later-season data improves personal-profile coverage and point-weighted MAE, but does not establish a conclusive post-May log-loss improvement.

TML-to-archive identity mapping used exact Unicode-normalised full names only: 277 of 291 target IDs mapped uniquely; 3 ambiguous and 11 unmatched IDs were excluded. No fuzzy identity match was used. The source files are ephemeral, raw CSVs are not cached or uploaded, and artifacts contain aggregate metrics / hashes only.

**Decision: keep ATP-only production profile policy unchanged.** The extended 2026 results are promising as a research signal, but calibration worsens and the key post-May log-loss confidence interval crosses zero. No player profiles, surface weights, ratings, rankings or Android assets were changed by this experiment. [Corrected aggregate-only 2026 forward-test run #38](https://github.com/KR1901/court-pace-index/actions/runs/42497240644).

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

Latest code and documentation head: pending final documentation-only commit. The validated calculation / research-code head for the 8 November run was \`c6295e01bbb39b1fd38e8a8c2bdd9734fbd71e92\`. Five of six workflows completed successfully; the deep calculation audit's full statistical stage was still running at the latest check.

- [Lower-tier profile backfill audit #38](https://github.com/KR1901/court-pace-index/actions/runs/42497240644): **passed**. Historical 2012–2025 rolling-origin backfill, 2026 source fetch, chronological current-season profile test, all 2026 match-day scoring, 2,000 paired bootstrap resamples, and aggregate-only report upload.
- [Event population audit #179](https://github.com/KR1901/court-pace-index/actions/runs/42497240739): **passed**, including Montpellier source reconciliation and the 1991–2026 all-event sweep.
- [Android CI #684](https://github.com/KR1901/court-pace-index/actions/runs/42497240662): **passed**, including unit tests, debug APK assembly and artifact upload.
- [Expected-aces formula comparison #310](https://github.com/KR1901/court-pace-index/actions/runs/42497240927): **passed**.
- [Surface-specific lower-tier backfill audit #28](https://github.com/KR1901/court-pace-index/actions/runs/42497240670): **passed**.
- [Deep calculation audit #240](https://github.com/KR1901/court-pace-index/actions/runs/42497240791): Python tests passed; leave-one-tournament-out, shrinkage, weighting and physical cross-check stage still running.

The 2026 forward-test source and all-date results are documented above and in \`docs/LOWER_TIER_BACKFILL_AUDIT.md\`. The corrected match-day aggregation is covered by a regression test. No production calculation file or data asset is changed by the lower-tier research tools.

### Lower-tier research decision

Neither the prior 2019–2025 global backfill nor the extended 2026 surface-specific forward test authorises production adoption. The results indicate strong personal-profile coverage gains and point-weighted MAE improvement, but calibration bias worsens; the later-season (post-17-May) log-loss interval includes zero. ATP-only production profiles remain in place. A subsequent independent prospective period is required before changing the production policy.

PR #2 remains open and unmerged. PR #1's base branch remains unchanged. The repository remains public; no visibility or permission changes were made.

## Reproduction

```bash
python3 -m unittest discover -s tools -p "test_*.py" -v
python3 tools/compare_speed_variants.py --seasons $(seq 1991 $(date +%Y)) --boot 5000
python3 tools/deep_calculation_audit.py --seasons $(seq 1991 $(date +%Y)) --boot 2000 --detail marrakech,lavercup,montpellier,paris
python3 tools/audit_event_population.py --event Montpellier --season 2026 --require-source "TennisMyLife ATP Match Database"
```
