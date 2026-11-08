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

## Decision gate

Do not promote lower-tier data into production unless the 2026 forward test shows credible out-of-sample improvements in predictive loss, acceptable calibration, meaningful profile-coverage gains, and no material surface regression. A partial-year test with wide intervals is inconclusive. A candidate that improves development fit but fails the 2026 forward test must be rejected.

Even a statistically useful research result does not grant rights for commercial or public-app distribution of derived data. Source licences and permissions require a separate review.
