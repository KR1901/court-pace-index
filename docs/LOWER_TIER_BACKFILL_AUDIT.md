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

The current experiment deliberately does **not** use TennisMyLife data. TML has separate public terms that limit use to personal, educational and non-commercial research and do not clearly authorize public-app distribution of TML-derived ratings. Making this repository private would not alter those source terms.

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

The script downloads source CSVs into a local / Actions cache pinned to the archive commit. It never commits or uploads raw CSV rows, player names, player IDs or player-level profile estimates.

The workflow artifact contains only aggregate development/validation metrics, season counts and source-file SHA-256 hashes. It is retained as a 90-day research artifact. Review its contents before any wider redistribution.

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

## Decision gate

No lower-tier profile contribution should enter production unless:

1. The selected candidate improves validation log loss and point-weighted MAE relative to ATP-only history.
2. The event-cluster interval for the primary validation improvement excludes zero, or the uncertainty is explicitly judged inconclusive and a larger holdout is collected.
3. Profile coverage gains are material and do not arise from duplicate matches or unsafe identity matching.
4. Tier weights are stable across surfaces and years, or a justified tier-specific model is independently validated.
5. Source licence, attribution and share-alike obligations are reviewed for the intended distribution. The repository's privacy setting does not grant new data rights.
