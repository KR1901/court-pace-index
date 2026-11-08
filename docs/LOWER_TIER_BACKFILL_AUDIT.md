# Lower-tier player-profile backfill experiment

## Purpose and scope

This is a research-only experiment to determine whether prior ATP Challenger main-draw and ATP tour qualifying matches improve the player-specific serve / return inputs used by the expected-aces formula.

It does **not** change production ratings, live baselines, historical data assets, tournament rankings or Android behaviour. Lower-tier observations remain excluded from production unless a separate review approves the method, weights, source rights and regenerated-data parity.

## Source and attribution

The experiment uses the pinned Jeff Sackmann ATP archive mirror:

- Repository: https://github.com/Aneeshers/tennis-sackmann-archive
- Pinned source commit: 83733587353df8a41f2fd4f516147d5aa83f5a8d
- Main draw: atp/atp_matches_YYYY.csv
- Combined lower tier: atp/atp_matches_qual_chall_YYYY.csv
- Upstream documentation: https://github.com/Aneeshers/tennis-sackmann-archive/blob/main/atp/UPSTREAM_README.md
- Licence: CC BY-NC-SA 4.0; attribution, non-commercial use and share-alike obligations apply.

The upstream archive documentation says match statistics are generally present from 1991 for tour-level matches, from 2008 for Challenger matches and from 2011 for tour-level qualifying. The script therefore begins target seasons in 2012, uses main-draw player history from 1991, Challenger observations from 2008 and qualifying observations from 2011.

The historical 2012–2025 rolling-origin comparison uses the pinned Sackmann archive only. A separate 2026 current-season forward test uses TennisMyLife's available-to-date 2026 files for non-commercial research only. TML has separate public terms that limit use to personal, educational and non-commercial research and do not clearly authorize public-app distribution of TML-derived ratings. Making this repository private would not alter those source terms.

## Data classification

The combined file is split using its source level and round labels:

- Challenger main draw: tourney_level C and a non-qualifying round.
- ATP tour qualifying: a tour-level event level (G/M/A/F/250/500) and Q1/Q2/Q3.
- Challenger qualifying and other unmatched categories are excluded.

Rows must also pass the same service-point, ace-count and player-ID sanity checks used by the production ATP match parser. The lower-tier rows are not allowed to affect the population prior.

## Leakage controls and temporal design

Each target ATP season is scored using only earlier seasons:

- Development window: 2012–2018.
- Locked validation window: 2019–2025.
- Main-draw and lower-tier data from the target season and future seasons are excluded from that season's profiles.
- Player identities use the same Sackmann ID namespace in both archives; no fuzzy name crosswalk is performed.
- The ATP main-draw archive alone defines surface priors, pooled priors and the neutral tour-average ace-rate anchor.
- Challenger and qualifying observations affect only the relevant player's individual serve and return profiles.

Up to 35 previous seasons are used. Older observations receive a 0.90 per-year recency factor. Lower-tier ace and service-point exposures receive a separate tier weight, while match counts remain raw counts for the three-match surface qualification rule. Rates are shrunk toward the ATP-only surface prior with a 150-service-point prior, and the existing one-third clay / two-thirds hard-grass blend is retained.

## Candidate arms and selection

The experiment compares:

- ATP main draw only.
- Challenger-only history at weights 0.25, 0.50 and 1.00.
- ATP qualifying-only history at weights 0.25, 0.50 and 1.00.
- Combined Challenger + qualifying history at matching weights 0.25, 0.50 and 1.00.

The candidate is selected on the development window by binomial log loss per service point, with point-weighted ace-rate MAE as a tie-breaker. The selected weights are then evaluated on the later validation window. The validation window is not used to select the candidate.

Metrics include personal-profile coverage by role, the percentage of sides with both player-role histories, point-weighted ace-rate MAE, side-level MAE, binomial log loss per service point and calibration bias. Paired uncertainty intervals resample complete event-season clusters within each validation season. A descriptive holdout breakdown by clay, hard, grass and carpet is also reported; the candidate weights remain fixed from the development window and are not retuned by surface.

A higher personal-profile coverage percentage alone is not sufficient. The candidate must also improve out-of-sample predictive scores; an interval spanning zero is inconclusive. If ATP-only is selected, that is a valid result and no backfill should be adopted.

## Privacy and artifact controls

The historical Sackmann archive script downloads pinned CSVs into a local / Actions cache. The separate TML 2026 forward script downloads current-season CSVs into an ephemeral temporary directory and deletes them when it exits; no TML raw file is saved in the repository, workflow cache or report artifact. Neither workflow commits or uploads raw match rows, player names, player IDs or player-level profile estimates.

Workflow artifacts contain aggregate development/validation metrics, season counts, source metadata and source-file SHA-256 hashes only. They are retained as 90-day research artifacts. Review their contents before any wider redistribution.

## Reproduction

Run the dedicated GitHub Actions workflow, or locally:

\`\`\`bash
python3 -m unittest discover -s tools -p "test_lower_tier_profile_backfill.py" -v
python3 tools/audit_lower_tier_profile_backfill.py \\
  --start-season 2012 \\
  --end-season 2025 \\
  --development-end 2018 \\
  --bootstrap 2000 \\
  --cache-dir .cache/lower-tier-backfill \\
  --output-dir build/lower-tier-profile-backfill
\`\`\`

The result is written to summary.json and README.md. Raw caches remain outside the report directory.

## First completed experiment (8 November 2026)

Run: [Lower-tier profile backfill audit #6](https://github.com/KR1901/court-pace-index/actions/runs/42485844666). The pinned-archive experiment completed successfully on the PR branch. The candidate selected on the 2012–2018 development window was Challenger-only history with an exposure weight of **0.25**.

On the locked 2019–2025 holdout (34,594 server sides), compared with ATP-only profiles:

| Metric | ATP-only | Challenger 0.25 |
|---|---:|---:|
| Both player roles have personal histories | 94.81% | 99.62% |
| Server-role personal history | 97.32% | 99.81% |
| Returner-role personal history | 97.32% | 99.81% |
| Point-weighted ace-rate MAE | 0.034117 | 0.033607 |
| Binomial log loss / service point | 0.265734 | 0.265596 |
| Calibration bias (actual − predicted) | +0.004971 | +0.008953 |

Paired event-season bootstrap differences (candidate minus ATP-only): point-weighted MAE 95% interval **[-0.000719, -0.000301]** (improvement); binomial log-loss 95% interval **[-0.000293, +0.000018]** (includes zero, therefore inconclusive). Calibration bias increased, indicating more underprediction of aces overall.

### Descriptive surface breakdown of the same locked holdout

| Surface | Server sides | ATP-only log loss | Challenger 0.25 log loss | ATP-only point MAE | Challenger 0.25 point MAE |
|---|---:|---:|---:|---:|---:|
| Clay | 10,374 | 0.200186 | 0.198830 | 0.030450 | 0.028422 |
| Hard | 20,468 | 0.290860 | 0.291207 | 0.035558 | 0.035681 |
| Grass | 3,752 | 0.305103 | 0.305611 | 0.036146 | 0.036307 |

The descriptive result is heterogeneous: the Challenger 0.25 arm improves both loss and point-weighted MAE on clay, but worsens both metrics on hard and grass. Coverage rises on all three surfaces. This surface breakdown was not used to tune or reselect the candidate; it is a diagnostic on the already-locked holdout.

**Decision: do not promote lower-tier backfill to production.** The experiment shows that modest Challenger evidence can materially improve player-role coverage, and it improves point-weighted MAE overall, but the primary log-loss uncertainty includes zero, calibration worsens overall, and the surface response is not uniform. A single global lower-tier weight is not established. Any surface-specific tier weighting requires a new development and genuinely untouched holdout; the 2019–2025 results must not be reused to tune a follow-on model and then presented as independent validation.

The run artifact contains only aggregate metrics and pinned-source hashes, not match-level rows, names or player IDs. The workflow's next run also includes explicit Jeff Sackmann / Tennis Abstract attribution in the generated README.

## 2026 available-to-date source extension and chronological forward test

The original pinned Sackmann 2026 main-draw file is not a complete 2026 season snapshot; it stops on 17 May. The 2026 forward experiment therefore obtains the current season data from TennisMyLife's data-files API at run time:

- ATP main draw: \`2026.csv\`, supplemented by \`ongoing_tourneys.csv\`.
- Challenger main draw: \`2026_challenger.csv\`, supplemented by \`challenger_ongoing_tourneys.csv\`.
- ATP qualifying: \`atp_quali/2026_atp_quali.csv\`, restricted to Q1/Q2/Q3 rows with valid service statistics.
- Player ID aliases: \`ATP_Database.csv\`, used only for exact full-name crosswalks to historical archive IDs.

The downloaded files are hashed and their API modification timestamps / source metadata are recorded in the aggregate report. For the TML 2026 files, \`tourney_date\` changes by match day within a tournament; the post-17-May count uses match dates on or after 18 May 2026. This is not a tournament-start-week approximation.

The current snapshot available at the 8 November 2026 run includes ATP main-draw rows through the Paris Masters ongoing-results feed (latest valid match date 7 November); the ATP static 2026 CSV itself extends through 1 November. It is the complete **available-to-date** record, not the yet-unplayed remainder of the calendar. Future 2026 tournaments (including those not yet played as of the run) need a later refresh after results exist.

### Chronological use of same-season matches

The 2026 ATP target is evaluated match-day by match-day:

- Historical ATP main draw (1991–2025) and historical Challenger / qualifying (2008–2025) form the starting player-history database.
- Earlier 2026 ATP main-draw matches may update both player profiles and the ATP-only population prior.
- Earlier 2026 Challenger and qualifying matches may update individual player-role profiles only, and only when their recorded match date is **strictly earlier** than the target ATP match date.
- All matches on the target date are held out together. Same-day and future rows are excluded, including other events whose matches share that date.
- A prior day of the same ATP tournament can contribute to the profile for the next match day; the target match day's own results cannot.
- Challenger and qualifying observations never enter the ATP population prior.

This is different from using the entire 2026 Challenger / qualifying file to predict all 2026 ATP matches, which would introduce look-ahead. Lower-tier results after 17 May can contribute to profiles for later 2026 ATP matches after those results are available and their recorded match date precedes the target match date.

### Corrected 2026 available-to-date forward-test result (8 November 2026)

The 2026 run uses the current TML season snapshot rather than treating the 17 May cutoff in the pinned Sackmann snapshot as the end of the season. It fetches the ATP main-draw file plus ongoing feed, the Challenger main-draw file plus ongoing feed, and the ATP qualifying Q1/Q2/Q3 file. It hashes every downloaded file, de-duplicates static / ongoing feeds, and validates player IDs and service-point / ace counts.

| Population | Valid available-to-date rows | Match-date keys on/after 18 May |
|---|---:|---:|
| ATP main draw + ongoing ATP feed | 2,572 | 1,444 |
| Challenger + ongoing Challenger feed | 5,993 | 3,405 |
| ATP qualifying Q1/Q2/Q3 | 1,268 | 753 |

The ATP ongoing-results feed extends through valid match-date key 7 November 2026. The static ATP season file extends through 1 November. The dataset is therefore the full **available-to-date** season snapshot as of 8 November, not the results of future tournaments which had not yet been played. The qualifying source includes valid rows with date keys through 9 November; that is source date metadata and is not treated as proof that a future event has been completed.

The surface-specific lower-tier policy was selected only on the historical 2012–2018 development window:

| Surface | Selected profile arm | Development sides | Development event-seasons |
|---|---|---:|---:|
| Clay | Challenger + qualifying, weight 1.00 each | 11,388 | 155 |
| Hard | Qualifying, weight 0.25 | 20,996 | 261 |
| Grass | ATP-only | 4,456 | 49 |

The 2026 data was not used to select or retune these weights. In 2026, each ATP match day is evaluated using only source matches with a strictly earlier recorded match date. Same-day and future matches are excluded together. Earlier 2026 ATP main-draw history can update player profiles and the ATP-only population prior; earlier 2026 Challenger / qualifying history can update individual player profiles only and never changes the ATP population prior. A tournament's multiple match days are accumulated rather than overwritten.

#### All available 2026 ATP main-draw matches

| Metric | ATP-only history | Surface-specific policy |
|---|---:|---:|
| Server sides | 5,144 | 5,144 |
| Both server and returner have personal histories | 97.74% | 99.49% |
| Point-weighted ace-rate MAE | 0.035828 | 0.034805 |
| Binomial log loss / service point | 0.285229 | 0.284828 |
| Calibration bias (actual − predicted) | +0.007953 | +0.011978 |

Paired event-cluster bootstrap, 2,000 resamples, policy minus ATP-only:
- Binomial log-loss difference, 95% interval **[-0.000707, -0.000120]**.
- Point-weighted MAE difference, 95% interval **[-0.001510, -0.000582]**.

Both intervals favour the surface-specific policy on the full available 2026 dataset. However, calibration bias is worse under the policy.

#### Match dates on or after 18 May 2026

This subset contains 1,444 ATP main-draw matches / 2,888 server sides.

| Metric | ATP-only history | Surface-specific policy |
|---|---:|---:|
| Both server and returner have personal histories | 97.58% | 99.31% |
| Point-weighted ace-rate MAE | 0.034921 | 0.034032 |
| Binomial log loss / service point | 0.288031 | 0.287696 |
| Calibration bias (actual − predicted) | +0.008520 | +0.011933 |

Paired event-cluster bootstrap, 2,000 resamples, policy minus ATP-only:
- Binomial log-loss difference, 95% interval **[-0.000716, +0.000018]**; it includes zero and is inconclusive.
- Point-weighted MAE difference, 95% interval **[-0.001475, -0.000400]**; it favours the surface-specific policy.

The post-17-May subset therefore shows better coverage and MAE, but not a conclusive log-loss improvement. Calibration bias is again worse. This is evidence that later-season Challenger / qualifying results can help estimate players for later ATP matches, but it is not sufficient to enable these sources in production.

The TML-to-Sackmann crosswalk used exact Unicode-normalised full names only: 277 of 291 target TML IDs mapped uniquely; 3 ambiguous IDs and 11 IDs without a historical name match were excluded from backfill. No fuzzy identity matching was used.

Run and aggregate-only artifact: [Lower-tier profile backfill audit #38](https://github.com/KR1901/court-pace-index/actions/runs/42497240644).

**Decision:** retain ATP-only production profiles for now. The 2026 surface-specific research arm improves aggregate predictive scores, but worsens calibration, and the post-17-May log-loss interval includes zero. The experiment must not silently promote its weights into live or historical rating generation.

### Scheduled available-to-date refresh

The research workflow is configured for Mondays at 08:00 UTC, after the repository's Monday 06:00 UTC production dataset refresh. Each run fetches the current TML ATP main-draw, ongoing ATP, Challenger, ongoing Challenger and qualifying files again, records new source hashes and repeats the chronological 2026 forward test. This schedule becomes active when the workflow file is present on the repository's default branch; PR #2 remains open and unmerged, so the new schedule is not claimed to be active on the default branch yet. Pull-request CI continues to validate changes on the research branch.

### Ephemeral source handling

TML files are fetched into a temporary directory for the duration of the research run and are deleted when the script exits. They are not saved into the repository, workflow cache, or artifact. Only aggregate counts, model metrics, source metadata and SHA-256 hashes are uploaded. The repository remains public; no visibility or access setting is changed.

The TML terms remain separate from the repository visibility setting: this is a non-commercial research audit only. It is not permission to distribute TML rows or deploy TML-derived profiles / predictions in a commercial app. Obtain written permission before any such use. Historical Sackmann-derived material continues to carry CC BY-NC-SA attribution, non-commercial and share-alike obligations.

### Reproduction

Run the 2026 extension as part of the dedicated workflow, or locally:

\`\`\`bash
python3 -m unittest discover -s tools -p "test_surface_backfill_2026_forward.py" -v
python3 tools/audit_surface_backfill_2026_forward.py \\
  --archive-cache-dir .cache/lower-tier-backfill \\
  --output-dir build/surface-backfill-2026 \\
  --bootstrap 2000
\`\`\`

The output separates the full available-to-date 2026 ATP population from matches dated on or after 18 May. The 2026 Challenger / qualifying source coverage is reported separately; these observations enter 2026 profiles only for strictly later match dates.


## Decision gate

No lower-tier profile contribution should enter production unless:

1. The selected candidate improves validation log loss and point-weighted MAE relative to ATP-only history.
2. The event-cluster interval for the primary validation improvement excludes zero, or the uncertainty is explicitly judged inconclusive and a larger holdout is collected.
3. Profile coverage gains are material and do not arise from duplicate matches or unsafe identity matching.
4. Tier weights are stable across surfaces and years, or a justified tier-specific model is independently validated.
5. Source licence, attribution and share-alike obligations are reviewed for the intended distribution. The repository's privacy setting does not grant new data rights.
