# CALC methodology research audit

## Scope and production boundary

This is a research-only branch. It does not modify `tools/compute_speed.py`, the Android calculation model, the bundled production readings, or release outputs. No CALC formula, prior, surface weight, minimum sample threshold, or published rating is changed by this study.

The study tests three different questions separately: (1) does the chronology research reproduce the production calculation exactly; (2) how sensitive are event scores to the profile/normalisation estimator; and (3) how closely do alternative tournament ratings agree with Tennis Abstract's published playing-speed series. Tennis Abstract agreement is a reference comparison, not physical ground truth.

## 1. Production-equivalent chronology and parity

The earlier audit harness had two defects that were corrected before the successful run. First, it independently reconstructed the target profile-training rows and missed production's opening-event previous-season fallback. It now calls production's `_chronological_profiles_and_anchor()` helper. Second, it assumed the compact highest/lowest side-diagnostics display contained every scored side. Repeated display match numbers can be deduplicated there; log loss is now calculated directly over every eligible match-side and checked against the production ratio-cluster population.

The successful research run compared **564 event-seasons across 2016–2026**. The archive-wide guard required the study's target raw score and production normaliser to match `season_ratings()` to an absolute tolerance of 1e-9 for every compared event; the job passed. The full Python tooling suite also passed (241 tests).

The calendar-year retrospective comparison is deliberately not an as-of-event benchmark: it excludes the target event but can use later matches from the same season. The production chronological score must instead use only data available before the target begins. A difference between those estimands is therefore expected and is not, by itself, a production discrepancy.

## 2. Rio Open 2026: what changed and why

The research source snapshot was generated on 2026-11-27 at 02:36:18 UTC from the validated TennisMyLife ATP feed plus the ongoing Davis Cup supplement. For Rio, the research study exactly reproduces the production payload:

| Diagnostic | Production chronological | Calendar-year retrospective |
|---|---:|---:|
| Rating | **1.5324** | 0.9188 |
| Raw adjusted event score | 1.5246 | 0.9527 |
| Season normaliser | 0.9949 | 1.0370 |
| Same-season profile-training matches | 364 | 2,582 |
| Tour ace rate used | 10.11% | 8.65% |
| Rio sample | 31 matches / 62 sides | 31 matches / 62 sides |

Rio began on 2026-02-16. The TML source was classified as match-day precise for the event, and the maximum training match date was 2026-02-13. Same-day matches and matches in the interval before the target were excluded; no arbitrary quarantine days were added. The production-parity record reports zero difference for the Rio raw score, normaliser, and rating.

The largest explanation for the change from the older retrospective estimate is the information set: the chronological model used 364 earlier same-season matches, while the retrospective score could use 2,582 same-season matches, including later 2026 data. The target's own 31 matches were excluded from both training sets. The tour baseline and player predictions therefore changed substantially when future information was removed.

### Normalisation sensitivity

The current production method scores earlier completed anchor events using the target event's own pre-event player profiles and tour ace baseline. This keeps the target numerator and its anchor on the same target-specific calibration, but the anchor matches can also have contributed to those target profiles.

A separate cross-fitted research comparator gives each earlier completed anchor event its own production chronological LOEO raw score, with that anchor event's own matches excluded from its profiles and tour baseline. For Rio, the seven completed standard-event anchors (United Cup, Brisbane, Hong Kong, Adelaide, Auckland, Australian Open, Montpellier) have a mean raw score of **1.432992** across 289 matches / 578 adjusted sides. Holding Rio's raw score fixed gives a sensitivity rating of **1.0640**, instead of 1.5324.

The Rio full-pipeline cross-fitted bootstrap completed 100/100 draws and produced a 95% interval of **0.725–1.604**. This interval is wide and includes 1.00; 100 draws are exploratory rather than a high-precision tail estimate. It does not establish that cross-fitting is superior or justify replacing the current normaliser. It does establish that the anchor definition is a material sensitivity that deserves further independent testing.

Across all 564 events, the production chronological and calendar-year retrospective ratings had a mean absolute difference of 0.0944 and median absolute difference of 0.0793. Cross-fitted versus production chronological differences averaged 0.0826 (median 0.0605), with Spearman rank correlation 0.8944. The 2026 cross-fitted versus production correlation was 0.8943 across 61 events.

The overall retrospective log loss per service point was 0.26796 versus 0.26971 for the chronological study. Because the retrospective calculation has access to later-season matches, this is not a fair forecasting comparison; the slight advantage cannot be interpreted as proof that the production chronology is inferior.

## 3. Player-profile estimator, shrinkage, and surface sensitivity

The archive-wide audit scored **2,205 common event-seasons across 36 seasons (1991–2026)** against the common Tennis Abstract event set and used 1,000 season-stratified paired bootstrap resamples.

An important reporting correction: the no-shrink control is now labelled `loeo_unshrunk_1_3` (prior points = 0). The exact production profile policy is labelled `loeo_production_150_min3` (150-point prior, three-match surface qualification, one-match pooled-return fallback). The older label `loeo_current_1_3` incorrectly implied that the unshrunk control was production.

| Profile configuration | Pearson r | Spearman rho | MAE |
|---|---:|---:|---:|
| Unshrunk control, 3-match qualification | 0.884 | 0.894 | 0.080 |
| **Production 150-point prior, 3-match qualification** | **0.923** | **0.928** | **0.066** |
| 100-point prior, 3-match qualification | 0.921 | 0.925 | 0.066 |
| 300-point prior, 3-match qualification | 0.923 | 0.928 | 0.067 |
| Empirical-Bayes prior, same 3-match qualification | 0.919 | 0.923 | 0.066 |

The paired 1,000-draw comparison of the production 150-point configuration against the unshrunk control showed +0.0389 Pearson correlation (95% interval +0.0304 to +0.0489), +0.0334 Spearman (+0.0274 to +0.0398), and -0.0140 MAE (-0.0160 to -0.0120). With the same three-match eligibility, empirical Bayes did not beat the fixed 150-point prior: relative to production its deltas were -0.0045 Pearson, -0.0048 Spearman, and +0.0008 MAE, with all paired intervals excluding zero. This supports retaining the existing prior for now, not changing it.

The method-of-moments empirical-Bayes estimates varied by role and surface. In 2026 their median equivalent pseudo-service-point priors were about 81 for clay serve, 59 for hard/grass serve, 87 for clay return, and 109 for hard/grass return. Those are diagnostics from a particular estimator, not automatically better hyperparameters; the out-of-sample event-reference comparison above did not show that the empirical-Bayes variant improves on the current fixed prior under matched eligibility.

The preliminary clay-weight grid (which does not hold every other production setting fixed) had a 0.5 clay weight slightly ahead of 1/3 on the 2021–2025 holdout MAE (0.072 versus 0.075). This is not a sufficient reason to change the production 1/3–2/3 blend. The audit now adds a one-factor 0.5-versus-1/3 test while holding the exact production 150-point prior and three-match qualification constant.

Carpet-specific player profiles performed materially worse than a pooled-carpet fallback in the historical comparison, which is consistent with the smaller carpet sample. A fully separate grass profile also does not show a robust advantage over the production hard/grass pooling. These comparisons are relative to a published event-rating benchmark, not proof of true court speed.

## 4. Expected-aces formula and floor comparison

The formula study compared uncalibrated, floored, calibrated Log5-style, surface-returner, ratio-of-sums, and pooled-returner variants against common Tennis Abstract event references. It covered **196,819 valid server sides across 36 seasons**.

The 0.5 expected-ace floor bound 63.5% of sides in the uncalibrated variant but only **0.8%** in the calibrated variant. This indicates that calibration materially reduces reliance on the denominator floor in the broad archive, although individual sides (including a displayed Rio extreme) can still be affected.

Across 1,240 common event-seasons in completed 1991–2025 seasons, the production `surface_returner` variant achieved Pearson 0.925, Spearman 0.926, and MAE 0.052. The generic calibrated pooled-returner alternative achieved 0.925, 0.917, and 0.055 respectively. Paired bootstrap differences were +0.001 Pearson (95% interval -0.004 to +0.005, includes zero), +0.010 Spearman (+0.006 to +0.014), and -0.003 MAE (-0.004 to -0.002). This is a modest reference-agreement advantage for the production surface-returner handling, not proof of physical correctness.

The ratio-of-sums variant has strong correlations but a higher MAE (0.065) than production. Removing calibration or using pooled profiles substantially reduces agreement. The 2026 season is incomplete and is treated as a separate partial-season diagnostic, not mixed into the completed-season headline.

## 5. Physical Court Pace Index cross-check

The physical CPI audit contained 107 event-season pairs across a small, non-random set of show-court tournaments. Overall Pearson/Spearman association was 0.681/0.712. After centering within event, the overall association fell to 0.421/0.408. Clay-only within-event-centered association was negative (-0.377/-0.396) across 24 observations and only four tournament clusters; its cluster-bootstrap intervals are exploratory.

These are not independent measurements of the same construct. The ITF Court Pace Rating (CPR) tests ball-surface interaction through friction and vertical restitution under controlled ball impact. Tennis Abstract's ace-based index describes how matches played under a combination of court, players, balls, and conditions. CPI coverage is sparse and non-random. The observed divergence is a reason not to treat CALC, Tennis Abstract, and physical CPI as interchangeable scales.

## 6. Held-out ace-probability calibration

A separate probability-level audit now evaluates the production Log5 probability itself, rather than only the final tournament rating. For each target tournament, the player profiles and tour baseline are built from pre-event information only. Every scored target-event match is excluded from its own profile training. The probability comparison covers **564 tournament-seasons (2016–2026), 48,536 server sides, and 3,985,366 service points**. The 0.5 expected-ace floor is not applied because it stabilises the actual/expected ratio denominator, not the per-point probability.

| Probability model | Log loss / service point | Brier / service point | Predicted ace rate | Observed ace rate |
|---|---:|---:|---:|---:|
| **Production calibrated Log5** | **0.269706** | **0.072021** | 8.0497% | 7.9668% |
| Neutral pre-event tour-rate baseline | 0.278264 | 0.073367 | 8.0356% | 7.9668% |
| Additive probability baseline | 0.272646 | 0.072038 | 8.0376% | 7.9668% |

A paired, season-stratified tournament-season cluster bootstrap with 1,000 draws found the following differences (comparator minus production Log5):

- Neutral baseline: log-loss difference **+0.008559** (95% CI +0.008019 to +0.009143); Brier difference **+0.001346** (+0.001245 to +0.001455).
- Additive baseline: log-loss difference **+0.002914** (+0.001821 to +0.004511); Brier difference **+0.0000165** (+0.00000279 to +0.0000326).

These results favour the production Log5 probabilities over the two simple baselines on this chronological held-out sample. They do not prove universal probability calibration or establish that Log5 is the best possible tennis point model. The post-hoc calibration intercept and slope were **-0.224 and 0.909** respectively (fit converged), rather than the ideal 0 and 1. Reliability bins indicate modest underprediction in the lowest probability bands and overprediction in the higher bands. The fitted coefficients are descriptive only and are not applied to production.

The comparator set is intentionally limited: it does not include a fully specified hierarchical serve/return point model, and it does not model score state, first/second serve, ball type, weather, altitude or physical court measurements. Server-side outcomes are grouped binomial observations; the bootstrap clusters by tournament-season rather than player identity. The result supports retaining Log5 over these simple alternatives for now, while keeping its calibration uncertainty visible.

## 7. Literature and interpretation

- Gollub, **Forecasting serve performance in professional tennis matches**, *Journal of Sports Analytics* (2021): evaluates sample-size shrinkage (Efron–Morris), strength-of-schedule adjustment and Elo-informed serve estimates on 7,622 ATP matches. It supports evaluating shrinkage and opponent adjustment, but its target is serve-point performance rather than tournament ace-rate speed. [IOS Press paper](https://content.iospress.com/articles/journal-of-sports-analytics/jsa200345).
- Ingram, **A point-based Bayesian hierarchical model to predict the outcome of tennis matches**, *Journal of Quantitative Analysis in Sports* (2019): models serve/return skill, surface, tournament and temporal variation using a Bayesian hierarchy. It is a more elaborate forward model, not a drop-in replacement for an ace-rate index. [DOI: 10.1515/jqas-2018-0008](https://doi.org/10.1515/jqas-2018-0008).
- Morey and Cohen, **Bias in the log5 estimation of outcome of batter/pitcher matchups, and an alternative** (2015): documents Log5 bias in low-probability baseball outcomes. This is a warning to test probability calibration, not evidence that the tennis ace formula is biased. [DOI: 10.3233/JSA-150005](https://doi.org/10.3233/JSA-150005).
- Tennis Abstract's surface-speed methodology explains why it adjusts for server and returner ability and why ace rate measures playing conditions rather than intrinsic court construction. [2016 methodology](https://www.tennisabstract.com/blog/2016/11/23/the-speed-of-every-surface-2016-edition/) and [2026 surface-speed table](https://www.tennisabstract.com/).
- The ITF's 2025 technical booklet defines physical Court Pace Rating using controlled ball-surface testing, including friction and coefficient of restitution. [ITF 2025 technical booklet](https://www.world.tennis/media/14104/2025-technical-booklet.pdf).

The probability-level audit strengthens the evidence for the current expected-ace probability against neutral and additive baselines. The historical tournament-rating comparison to Tennis Abstract remains a separate reference comparison and cannot certify physical court pace.

## 8. Validation and production decision

The successful GitHub research run completed:
1. 241 Python tooling tests.
2. Production-equivalent chronology/parity checks for 564 event-seasons (2016–2026), including a 100-draw Rio full-pipeline cross-fit bootstrap.
3. Archive-wide shrinkage, empirical-Bayes, surface-weighting, conditional/profile/full-pipeline bootstrap and physical CPI diagnostics.
4. Expected-aces formula comparisons against Tennis Abstract with 2,000 season-stratified paired bootstrap draws.

Production parity was exact on the run's compared events. The current evidence supports retaining the production formula and 150-point prior while the cross-fitted normaliser and the Rio sensitivity remain under study. It does **not** support claiming 100% certainty or replacing production normalisation on the basis of a single alternative benchmark.

No formula, prior strength, surface weighting, minimum-side threshold, or published rating has been changed.
