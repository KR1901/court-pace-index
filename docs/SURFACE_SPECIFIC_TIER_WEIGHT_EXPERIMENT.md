# Surface-specific lower-tier profile-weight experiment

## Question

The first lower-tier profile experiment selected Challenger-only backfill at a 0.25 weight on 2012–2018 development data. In the subsequent 2019–2025 holdout, clay error improved while hard and grass errors worsened slightly. This follow-up tests whether the lower-tier contribution should be weighted differently in the existing clay and hard/grass profile buckets.

This remains research-only. It does not change the production profile formula, expected-aces equation, data assets, tournament ratings, rankings, or Android behaviour.

## New validation design

- Development selection: ATP seasons 2012–2018.
- New forward test: 2026 ATP main-draw matches in the pinned Sackmann archive snapshot.
- The 2026 file in archive commit 83733587353df8a41f2fd4f516147d5aa83f5a8d contains 1,186 statistically eligible main-draw matches (2,372 server sides) with match dates from 4 January through 17 May 2026. It is a partial-season snapshot, not a full 2026 season.
- Seasons 2019–2025 are not scored and are not used to select surface weights. They are prior-history inputs for the 2026 player profiles; this is legitimate temporal training data, not held-out outcome evaluation.
- Every target-season player profile uses only ATP main-draw, Challenger and ATP qualifying match observations dated in earlier seasons. No 2026 or future lower-tier rows enter 2026 training.

The first lower-tier experiment's 2019–2025 holdout is not reused as a new validation set. Its match outcomes are not used for candidate selection in this experiment.

## Surface-weight parameterization

The existing player profile hierarchy is retained:

- Player rates are built separately for serve and return-conceded roles.
- Clay and hard/grass history remain the two surface-qualified profile buckets.
- Surface-qualified rates retain the existing minimum of three matches and 150-service-point shrinkage.
- Qualified clay and hard/grass estimates retain the existing one-third clay / two-thirds hard-grass blend.
- ATP main-draw history alone defines surface priors, pooled priors and the neutral tour-average ace-rate anchor.

For each lower-tier source (Challenger and ATP qualifying), the pooled fallback exposure weight is held fixed at 0.25. The surface-qualified clay and hard/grass exposure weights are varied independently over 0, 0.25, 0.50 and 1.00. This isolates the effect of surface-specific evidence while retaining the same lower-tier pooled coverage fallback in every surface-weight candidate.

The candidate set includes ATP-only, global equal-weight baselines, Challenger-only surface grids, qualifying-only surface grids and combined lower-tier surface grids. Development selection uses binomial log loss per service point, with point-weighted ace-rate MAE as a tie-breaker. It selects one arm on 2012–2018 only. The selected arm is then evaluated once on the new 2026 target snapshot.

## Evaluation

The validation report includes:

- Both-role, server-role and returner-role personal-profile coverage.
- Point-weighted and side-level ace-rate MAE.
- Binomial log loss per service point.
- Calibration bias.
- Clay, hard, grass and carpet target-match results reported separately.
- A paired 2,000-resample event-cluster bootstrap for the selected candidate versus ATP-only on the 2026 snapshot.

The surface breakdown is descriptive on the locked forward test; it is not used to retune the weights. An improvement in coverage alone is not sufficient. The candidate should only be considered for production after predictive improvements are supported by uncertainty intervals, calibration remains acceptable, and surface results do not reveal material regressions.

Because 2026 is a partial-season snapshot and may have relatively few grass events, surface-specific confidence intervals may be wide. The report must retain the exact source-date range and event counts; absence of sufficient data is not evidence of equal performance.

## Source, licence and output controls

- Source archive: https://github.com/Aneeshers/tennis-sackmann-archive
- Original dataset attribution: Jeff Sackmann / Tennis Abstract, based on the Jeff Sackmann ATP match database.
- Pinned source commit: 83733587353df8a41f2fd4f516147d5aa83f5a8d
- Licence: CC BY-NC-SA 4.0. This is non-commercial research; attribution and share-alike obligations apply.
- Source CSVs are downloaded to an ephemeral runner working directory. They are not committed, uploaded as artifacts, or persisted in a GitHub Actions cache.
- The artifact contains aggregate metrics and file hashes only. It excludes raw match rows, player names and player IDs.

## First completed 2026 forward test (8 November 2026)

Run: [Surface-specific lower-tier backfill audit #10](https://github.com/KR1901/court-pace-index/actions/runs/42490461457). Unit tests, development weight selection, 2026 forward scoring, overall bootstrap, per-surface bootstraps and aggregate-only artifact upload all passed.

The 2012–2018 development window selected the combined Challenger + qualifying arm with:
- Pooled lower-tier fallback weight: 0.25 for each tier.
- Clay surface-qualified lower-tier weight: 0.00.
- Hard/grass surface-qualified lower-tier weight: 0.25.

The 2026 archive snapshot contains 1,186 eligible ATP main-draw matches (2,372 server sides), 181,256 service points and 28 events, from 4 January through 17 May 2026. It is a partial-season snapshot. The observed target surfaces are clay (1,054 sides) and hard (1,318 sides); there are no grass target matches in this file.

| Metric | ATP-only | Selected surface-weight arm |
|---|---:|---:|
| Both-role personal-profile coverage | 96.12% | 99.92% |
| Server-role personal-profile coverage | 98.06% | 99.96% |
| Returner-role personal-profile coverage | 98.06% | 99.96% |
| Point-weighted ace-rate MAE | 0.037139 | 0.036645 |
| Binomial log loss / service point | 0.277418 | 0.277328 |
| Calibration bias (actual − predicted) | +0.006665 | +0.009596 |

Paired 2,000-resample event-cluster bootstrap, selected minus ATP-only:
- Overall log-loss difference: 95% interval **[-0.000626, +0.000368]** (includes zero).
- Overall point-weighted MAE difference: 95% interval **[-0.001199, +0.000106]** (includes zero).
- Overall calibration-bias difference: 95% interval **[+0.002272, +0.003458]** (worsens significantly).

### 2026 results by actual target surface

| Surface | Event clusters | Sides | ATP-only log loss | Selected log loss | ATP-only point MAE | Selected point MAE |
|---|---:|---:|---:|---:|---:|---:|
| Clay | 13 | 1,054 | 0.210499 | 0.209334 | 0.032908 | 0.031035 |
| Hard | 15 | 1,318 | 0.325732 | 0.326417 | 0.040193 | 0.040695 |
| Grass | 0 | 0 | Not available | Not available | Not available | Not available |

### Paired event-cluster bootstrap within each surface (candidate minus ATP-only)

| Surface | Log-loss difference 95% CI | Point-weighted MAE difference 95% CI | Calibration-bias difference 95% CI |
|---|---:|---:|---:|
| Clay (13 events) | **[-0.001526, -0.000732]** (improves) | **[-0.002473, -0.001208]** (improves) | **[+0.000760, +0.003407]** (worsens) |
| Hard (15 events) | **[+0.000314, +0.001123]** (worsens) | **[-0.000022, +0.001138]** (inconclusive) | **[+0.002958, +0.003771]** (worsens) |

These per-surface intervals are descriptive uncertainty estimates on the **same locked 2026 holdout**, not a second independent test or a new tuning set. The clay improvements and hard-court log-loss regression have intervals excluding zero, but the candidate worsens calibration bias on both surfaces. There are no grass matches, so the hard/grass profile-weight setting has not been directly tested on grass.

The candidate materially increases profile coverage, but the outcome is surface-dependent: predictive loss improves on clay and worsens on hard; overall predictive-loss intervals include zero; calibration bias worsens across both surfaces. **Do not promote this candidate to production.** A 2026 partial-season result with 28 events cannot establish stable universal surface-specific weights. Wait for a fuller independent 2027 season or a separately approved prospective dataset; do not retune using this 2026 holdout and then report it as untouched validation.

The workflow report and aggregate source manifest are available from the linked run. It contains no raw CSVs, player names or player IDs.

## Decision gate

Do not promote lower-tier data into production unless the 2026 forward test shows credible out-of-sample improvements in predictive loss, acceptable calibration, meaningful profile-coverage gains, and no material surface regression. A partial-year test with wide intervals is inconclusive. A candidate that improves development fit but fails the 2026 forward test must be rejected.

Even a statistically useful research result does not grant rights for commercial or public-app distribution of derived data. Source licences and permissions require a separate review.
