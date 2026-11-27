# Expected-ace probability calibration research

## Purpose and production boundary

This study evaluates the production expected-ace probability against observed service-point outcomes in tournaments whose own matches were excluded from profile training. It does not change the expected-aces formula, CALC rating, 150-point profile prior, or any production dataset.

## Models compared

- **Log5**: the production calibrated odds-ratio model. The serve and returner odds multipliers are combined and calibrated back to the pre-event ATP tour ace rate.
- **Neutral**: use the pre-event tour ace rate for every server side, ignoring player-specific serve/returner ability.
- **Additive**: add the server and returner deviations from the tour rate on the probability scale (`server + return-concession − tour rate`), clipping only when the arithmetic leaves the probability interval.

The 0.5 expected-aces floor is not applied to these probabilities. It belongs only to the actual/expected ratio denominator. The probability scoring uses the unfloored expected ace rate, as proper binomial log loss requires.

## Evaluation

For each standard ATP tournament from 2016 onward, the target event's player profiles and tour baseline are built using the exact production chronological helper and only pre-event information. Both server sides of each valid match are then evaluated as grouped binomial observations (aces out of service points).

The report gives per-model log loss per service point, exact per-trial Brier score, observed versus predicted ace rates, post-hoc calibration intercept/slope, and reliability bins. It also reports a season-stratified tournament-cluster paired bootstrap for Log5 versus the neutral and additive baselines.

The post-hoc calibration intercept and slope are diagnostics only; they are not applied to the production probabilities. A good tournament-rating correlation against Tennis Abstract is not a substitute for this probability-level test.

## Interpretation limits

This evaluation uses match-side aggregate ace counts, not point-by-point location, score state, first/second serve, ball type, weather, altitude or physical court measurements. A match-side grouped-binomial log loss is therefore useful evidence about expected ace probability, but it does not prove that the probability model captures every causal variable. Results should be stratified by surface and season where sample sizes permit, and the production formula should not be changed solely because one alternative wins one aggregate metric.
