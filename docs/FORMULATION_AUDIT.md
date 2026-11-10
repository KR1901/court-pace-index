# Calculation and formulation audit

**Current code-audit head:** `461272379dc3e69a4b444111b022bf0721f340ef`  
**PR:** [#2 — Evaluate expected-aces formulas against 2025–26 reference data](https://github.com/KR1901/court-pace-index/pull/2)  
**Status:** PR #2 is open and unmerged. This document is maintained on PR #2's branch.

## Executive finding

Retain the calibrated server/returner expected-aces formulation and equal-weight mean of valid server-side actual/expected ratios. Player profiles now use up to 35 prior ATP seasons beginning in 1991, 0.90 per-year recency decay, a 150-service-point shrinkage prior, three-match surface qualification and safe TML/Sackmann name crosswalking. A tour-average prior completes a side only when no defensible personal rate exists, and is explicitly counted. Across the current 1991–2026 reference comparison, the calibrated surface-returner variant has the best combined rank agreement and mean absolute error among tested variants. Tennis Abstract is a behavioural reference, not a physical measurement.

No clay-specific correction is justified by the limited physical CPI cross-check. The data are sparse and selected, and the negative clay-only association is a warning to improve validation rather than proof that the formulation is physically biased.

## Production calculation

For a server side, let:

- (p_s) be the server's estimated ace probability per service point;
- (p_r) be the returner's estimated ace-concession probability per service point;
- (p_t) be the season tour-average ace probability per service point.

The calibrated odds ratio is:

```
O = ((p_s / (1 - p_s)) × (p_r / (1 - p_r))) / (p_t / (1 - p_t))
p_expected = O / (1 + O)
expectedAces = servicePoints × p_expected
sideRatio = actualAces / max(expectedAces, 0.5)
```

The event raw rating is the arithmetic mean of valid server-side ratios. The season normaliser is the mean raw score across eligible standard events; the published CALC value is the event raw score divided by that normaliser. The 0.5 expected-aces floor protects the denominator and is not an eligibility filter.

**Format-specific target exception — Next Gen Finals:** Tennis Abstract publishes an adjusted ace-rate surface-speed value for Next Gen Finals (2025: 0.81), so the project now calculates its own target estimate instead of leaving the event TA-only. Its short first-to-four sets and no-ad rules are not standard ATP scoring. Accordingly, Next Gen rows are allowed into the target-event calculation only; they are excluded from current-season and historical player-profile training and from the standard ATP season normaliser. The standard anchor is calculated from standard-format ATP events, and Next Gen cannot move any standard-event CALC value. Its own point estimate is retained even above 2.0 and is labelled format-specific in the Android detail/method views. The value should not be interpreted as a standard-format tournament estimate without that caveat. See the [Tennis Abstract 2025 season table](https://www.tennisabstract.com/cgi-bin/surface-speed.cgi?year=2025) and [ATP Next Gen rules](https://www.atptour.com/en/news/next-gen-atp-finals-presented-by-pif-2024-rules-and-innovations).

The source-name resolver also maps the official/commonly used labels for Adelaide International and Rio Open to the existing `adelaide` and `rio` event IDs. The refreshed 10 November history has Adelaide 2026 CALC 1.134 (TA 1.04) and Rio 2026 CALC 0.932 (TA 0.95). Next Gen has CALC 2.146 in 2024 (15 matches, 30 adjusted sides; conditional match-cluster 95% interval [1.659, 2.669]) and 1.303 in 2025 (15 matches, 30 adjusted sides; interval [1.009, 1.690]). The 2024 value exceeds 2.0 and is intentionally retained; the wide interval is an important uncertainty qualification.

Tennis Abstract's 2025 calendar-year selector lists Next Gen Finals at 0.81, while its rolling 52-week report last updated 9 November 2026 lists the same 17 December 2025 event at 1.26. The history builder retains the calendar-year selector snapshot for reproducibility and does not silently replace it with the rolling report. Both TA pages describe an ace-rate metric adjusted for the servers and returners in each match.

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

**Interpretation warning:** the separate \`deep_calculation_audit.py\` variant grid and its nested bootstrap deliberately rebuild player profiles from the current season's training rows only. They are same-season LOEO sensitivity checks, not the production 35-season profile-history estimator. Their lower side counts for Montpellier/Marrakech therefore do not contradict the production all-event profile-coverage audit.

The same-season-only full-pipeline bootstrap refits its training profiles, target-event matches and season LOEO normaliser on each draw (250 successful draws per event):

| Season / event | Same-season LOEO CALC | Full-pipeline 95% interval | Matches / profile-supported sides |
|---|---:|---:|---:|
| 2025 Marrakech | 0.772 | [0.607, 0.933] | 27 / 47 |
| 2025 Montpellier | 1.304 | [1.067, 1.563] | 27 / 45 |
| 2026 Marrakech | 1.049 | [0.866, 1.304] | 26 / 41 |
| 2026 Montpellier | 1.600 | [1.370, 1.986] | 27 / 47 |

The production full-history series in \`data/speed_history.json\` uses up to 35 prior ATP seasons, 0.90 annual recency decay, unique-name-only TML/Sackmann crosswalking and explicit neutral tour-prior fallback. Its latest 2026 values are:

| Event | Production CALC | Production sides | Match-cluster conditional 95% interval | TA reference |
|---|---:|---:|---:|---:|
| Montpellier | 1.664 (rank 1/57) | 54/54 | [1.465, 1.862] | 1.49 |
| Marrakech | 0.999 (rank 34/57) | 52/52 | [0.828, 1.182] | 0.93 |

Both TA reference values fall inside the production conditional intervals. The production values differ from the same-season-only sensitivity values because the former have the longer historical player profiles and the complete event-side population. The distinction is important when interpreting ranking changes: the latest full-history calculation does not place Marrakech near the top; it places it 34th among 57 ranked 2026 events. Montpellier is first, consistent with the high value already present in the TA behavioural reference.

The production intervals shown here are match-cluster conditional intervals: they hold fitted player profiles and the season normaliser fixed. The wider same-season full-pipeline intervals above refit that narrower model's profiles, matches and normaliser; they are not a bootstrap of the 35-season production profile estimator. A dedicated full-history bootstrap is still required before presenting production-wide uncertainty as fully propagated.

Laver Cup 2026 remains provisional and is not promoted into the ordinary ranking. The latest production history carries 7 matches / 14 server sides; this is below the standard 20-side threshold.

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

## Expanded player database and 1991–2026 all-event coverage

The profile database is constructed from match-level serve and return observations, not from biographical player records. The ATP player directory contains identity/biography fields, but not the service-point/ace observations required by this formulation. The calculation instead uses historical ATP match archives from 1991 onward and the current-season match file.

For each target event, all matches belonging to that event are excluded from its training profiles and tour anchor. Player rates are aggregated across up to 35 prior ATP seasons, with a 0.90 multiplicative decay per year of age. Current TML player IDs are crosswalked to historical archive IDs only when the normalised full name is unique. Ambiguous collisions are not merged. Surface rates require at least three observations in the surface bucket and are shrunk with the 150-service-point prior; pooled career estimates are the next player-specific fallback. If no defensible individual role estimate can be formed, the calculation uses the event's leave-one-event-out tour-average rate as an explicitly labelled neutral prior.

The all-season workflow swept every mapped event in every available year from 1991 through 2026. The completed 36-season run reported:

| Measure | 1991–2026 result |
|---|---:|
| Seasons | 36 |
| Mapped event-seasons | 1,533 |
| Eligible matches | 71,150 |
| Statistically eligible server sides | 142,300 |
| Rateable server sides | 142,300 |
| Unrateable sides | 0 |
| Sides using at least one neutral tour-average prior | 1,930 |
| Neutral-prior share | 1.356% |

The 100% figure is **rateability**, not 100% individual profile coverage. A side is rateable when both role inputs are mathematically valid, including when one or both use an explicitly recorded neutral tour-average prior. The corrected audit counts **1,930 affected sides (1.356%)**, comprising **980 server-role uses** and **980 returner-role uses**; 30 sides required both roles. The earlier 1,584 count underreported reuse because it counted a missing role when first assigned but not on later sides. The audit covers mapped events in the selected source files; it does not establish that every ATP event is represented or that the source population equals Tennis Abstract's population.

The 2026 source-required TML sweep is separately reconciled:

| Measure | 2026 result |
|---|---:|
| Mapped events | 58 |
| Eligible matches | 2,519 |
| Statistically eligible server sides | 5,038 |
| Rateable server sides | 5,038 |
| Unrateable sides | 0 |
| Sides using at least one neutral tour-average prior | 84 |
| Events with at least one neutral-prior side | 25 |

The corrected 2026 audit finds 84 affected sides (1.667% of 5,038), with 43 server-role uses and 43 returner-role uses; two sides required neutral priors for both roles. This represents 72 unique missing player-role assignments across 36 distinct fallback player IDs and 25 events. The earlier 70 count omitted repeated match-side uses. Neutral priors remain explicit and are not represented as recovered personal rates.

The Montpellier 2026 discrepancy was resolved at the profile-coverage stage: 54/54 statistically complete sides are now rateable, with no neutral-prior assignments in that event. The source ace rate (14.1964%) remains different from Tennis Abstract's published 19.8%; the profile expansion does not resolve the TA match-population/stat-definition discrepancy. That requires authorized TA match-level data or independently documented evidence.

The ambiguity-aware aggregate fallback diagnostics show zero occurrences where a player profile record existed but lacked a usable role rate, and zero ambiguous normalized-name collisions. All 1,610 missing role-estimate occurrences across 1991–2026 were unique player-role assignments classified as no player profile found in the selected history window: 805 server-role and 805 returner-role assignments, summed across seasons. These unique assignments are distinct from the 1,960 per-side role uses across the archive. For 2026, 72 unique role inputs across 36 distinct fallback player IDs produced 84 affected sides (43 server-role and 43 returner-role uses) in 25 events.

This narrows the observed issue to history/identity coverage in the selected main-draw archive, rather than a present profile record whose role rate failed to materialise. It does not establish why each identity is absent: a player may be new to the tour-level stats archive, have previous records only in Challenger/qualifying, or have a cross-source name/identity mismatch. Ambiguous names must remain unmatched unless stronger identity evidence is available.

The first audit-only backfill candidate should be TennisMyLife's separate ATP Tour Qualifying dataset: its database documentation says it uses ATP player IDs (which are much easier to match safely to the current TML main-tour data) and exposes the same ace/service-point match-stat fields. Its current public catalogue lists yearly qualifying files and Challenger files; the visible catalogue includes qualifying back to 2017 and Challenger back to 2018, with older years behind its load-more catalogue. No qualifying or Challenger rows have yet been retrieved, cross-matched or added to this calculation. TML's published terms permit personal, educational and non-commercial research only and do not authorize redistribution of the raw CSVs; they do not clearly authorize public-app redistribution of derived ratings either. [TML ATP match database, data fields, catalog and terms](https://stats.tennismylife.org/tennis-match-database).

A second source is the Sackmann qualifying/Challenger archive. Its upstream documentation says match statistics are generally available from 2008 for Challenger matches and 2011 for tour-level qualifying, with CC BY-NC-SA 4.0 restrictions. Those rows use Sackmann IDs and require cautious unique-name crosswalking. [Upstream archive documentation](https://github.com/Aneeshers/tennis-sackmann-archive/blob/main/atp/UPSTREAM_README.md) · [Licence](https://github.com/Aneeshers/tennis-sackmann-archive/blob/main/LICENSE).

Either source may recover some prior service history, but lower-tier competition changes opponent mix and ace outcomes. Any experiment must (1) keep ATP main-draw and lower-tier observations separately labelled, (2) exclude the entire target event from training, (3) match identities by authoritative IDs first and unique full-name evidence only as a fallback, (4) quantify how many of the 36 2026 profile gaps are recovered, and (5) test competition-level shrinkage and out-of-sample agreement before any lower-tier observation can influence a production ATP profile. No lower-tier backfill is part of this PR's production calculation.

The consolidated workflow report retains aggregate season- and event-level coverage tables, source manifests and fallback categories, including ambiguous-name collisions as a separate category. It excludes raw source CSVs, player names and player IDs. The latest ambiguity-aware 1991–2026 report is attached to [Event population audit run #92](https://github.com/KR1901/court-pace-index/actions/runs/42480868160). Its output is a diagnostic, not permission to redistribute source-derived data.

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

The ambiguity-aware profile and event-audit implementation passed the full validation set on code head \`05a8cf83f3022782563ad4abeff1391cd78f9f36\`:

- [Event population audit #92](https://github.com/KR1901/court-pace-index/actions/runs/42480868160): passed, including the 1991–2026 coverage sweep, report aggregation and artifact upload.
- [Expected-aces formula comparison #223](https://github.com/KR1901/court-pace-index/actions/runs/42480868104): passed.
- [Android CI #597](https://github.com/KR1901/court-pace-index/actions/runs/42480868071): passed.
- [Deep calculation audit #153](https://github.com/KR1901/court-pace-index/actions/runs/42480868223): passed; 250 successful bootstrap draws per selected event and the full audit artifact was uploaded.

The bundled app asset and \`data/latest.json\` have now been regenerated from \`data/speed_history.json\`. Both files share Git blob SHA \`edff2da9fc71f0be397a58944021cd5601ca282c\` and generated timestamp 8 November 2026, 12:55:22 UTC. Their PR diff confirms the production Montpellier (1.664; 54/54 sides) and Marrakech (0.999; 52/52 sides) values and preserves Tennis Abstract in \`publishedSpeed\`. The refresh workflow now checks every computed rating, match/side count and confidence interval against the history source before publication. The latest PR validation cycle is queued separately; its final conclusions have not yet been returned.

## Reproduction

```bash
python3 -m unittest discover -s tools -p "test_*.py" -v
python3 tools/compare_speed_variants.py --seasons $(seq 1991 $(date +%Y)) --boot 5000
python3 tools/deep_calculation_audit.py --seasons $(seq 1991 $(date +%Y)) --boot 2000 --detail marrakech,lavercup,montpellier,paris
python3 tools/audit_event_population.py --event Montpellier --season 2026 --require-source "TennisMyLife ATP Match Database"
```

Event-population output is local under `build/event-population-audit/`. Do not commit the raw TML CSV or generated match-level report to a public repository. The audit summary reports the source label, source hash and aggregate event totals.
