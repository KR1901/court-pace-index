# Court Pace

Court Pace is an independent Android app and research toolkit for comparing how tennis courts play. It brings together **physical Court Pace Index (CPI) readings** and **behavioural, ace-based ATP Surface Speed ratings**, with historical comparisons, tournament detail and a live-tournament view.

The app is a reader and analysis tool, not a court-measuring instrument. It does not use the microphone or phone sensors to estimate court pace. Bundled readings are available offline; internet access is used for optional dataset refreshes and live ATP match statistics.

| | |
|---|---|
| Platform | Android |
| Language and UI | Kotlin, Jetpack Compose |
| Build | AGP 9.5.0, Gradle 9.8.2, Kotlin 2.4.21 |
| SDK | compileSdk 37, targetSdk 37, minSdk 26 |
| Data | Bundled CPI and CALC/TA Surface Speed readings; exact coverage is in the generated dataset |
| Network | HTTPS for manual dataset updates and live ATP results/statistics |

## Measurements

### Court Pace Index (CPI)

CPI is a physical measurement derived from ball-surface interaction on instrumented show courts. The published composite is:

```
CPI = 100(1 − μ) + 150(0.81 − e)
```

where `μ` is the coefficient of friction and `e` is the coefficient of restitution. Higher CPI indicates a faster measured court under this scale. Coverage is limited to the courts and dates for which measurements have been published; it is not a tournament-wide census.

### Surface Speed

Surface Speed is a behavioural estimate based on aces. It adjusts each server's ace rate for the server's serving tendency and the returner's ace-concession tendency, estimates expected aces on a tour-average surface, averages valid server-side ratios and season-normalises the result. A value of 1.00 represents the season baseline.

The calibrated expected-aces odds ratio is:

```
O = (serverOdds × returnerConcessionOdds) / tourAverageAceOdds
expectedAceRate = O / (1 + O)
expectedAces = servicePoints × expectedAceRate
sideRatio = actualAces / max(expectedAces, 0.5)
```

The 0.5 expected-aces floor protects the denominator from very small expectations; it does not remove an otherwise valid server side from coverage.

Historical and live ratings use leave-one-tournament-out training, but player tendencies are now built from up to 35 prior ATP seasons (1991 onward), plus the current eligible season. Historical samples receive a 0.90 per-year recency weight and a 150-service-point shrinkage prior; qualified surface rates retain the neutral clay/hard-grass blend, with pooled career estimates as the next fallback. TML alphanumeric IDs are joined to historical Sackmann IDs only through a unique normalised full-name match; ambiguous identities are not merged. If a genuinely unseen player still has no defensible personal rate, the season tour-average rate is used as an explicit neutral prior and separately counted. Because this is a behavioural metric, it also responds to balls, weather, altitude and player tactics. It is not a pure measurement of the physical surface.

## App features

- **Pace board:** season rankings with surface filters and like-for-like changes.
- **Tournament detail:** CPI and Surface Speed histories, source labels and comparisons.
- **Compare:** event comparisons and CPI/Speed percentile divergence.
- **Surfaces:** surface-level distributions and trends.
- **Method:** formulas, bands, source provenance and known limitations.
- **Live now:** discovers active ATP 1000/500/250 events, reads completed singles results and computes opponent-adjusted Surface Speed from ATP/Infosys statistics on the device. Diagnostics expose the raw score, normalisation, tour ace rate, service-point totals, matched profiles and influential server-side ratios.

The live pipeline excludes walkovers, confirmed no-play records, doubles and in-progress matches. Played retirements remain eligible when valid statistics exist. Missing data remains visible; the app must not invent statistics to complete a rating. ATP/Infosys access can be device-sensitive, so a green CI build is not a substitute for live handset acceptance.

### Live data path and published snapshot

The checked-in `data/live.json` is an **event-window metadata file**, not the production live-rating output. `tools/build_dataset.py` retains all known event windows in the dataset's `liveSchedule` array so the app can reject stale ATP score-hub routes. It separately publishes only non-expired, non-terminal rows in the visible `live` fallback list. An ended event may therefore remain in `liveSchedule` as a tombstone without appearing in Live now. Do not delete an ended schedule row merely because the source's old status label says `in_progress`: its tournament ID and end date help prevent a stale route from reviving it.

The scheduled `refresh-live.yml` workflow rebuilds and publishes the dataset snapshot as `data/latest.json`; it does **not** fetch match statistics or calculate live Surface Speed. The `probe_sofascore.py` script is a reachability diagnostic only. The old `tools/live_update.py` script is a retired experiment and is not called by the app or scheduled workflows.

Live ratings are calculated at runtime by the Android app: `AppViewModel` calls `AtpLiveClient`, which discovers completed singles links on ATP results pages, requests match statistics through `AtpInfosysStatsClient`, and passes them to the current opponent-adjusted `AceRating` method with the published season baseline. Ratings are cached locally on the device. This separation is intentional: a missing `speed` value in `data/live.json` or `data/latest.json` does not, by itself, mean the on-device live rating path is broken.

## Calculation evidence

The latest completed formula comparison covered 1,289 common event-seasons across 36 seasons (1991–2026), with 5,000 season-stratified paired bootstrap resamples. The calibrated surface-returner variant had the strongest overall rank agreement and mean absolute error among the tested candidates (Pearson 0.925, Spearman 0.926, MAE 0.052) against the Tennis Abstract reference series.

That result measures agreement with a published behavioural reference, **not physical accuracy**. The corrected 1991–2026 profile audit covered 1,533 mapped event-seasons, 71,150 matches and 142,300 eligible server sides; all were rateable. A neutral tour-average prior was used on 1,930 sides (1.356%), comprising 980 server-role and 980 returner-role uses; 30 sides needed both roles. There were 1,610 unique missing player-role assignments, zero ambiguous normalized-name collisions and zero existing profiles with unusable role rates.

The current 2026 production full-history ratings are Montpellier **1.664** (54/54 sides; rank 1/57; conditional match-cluster 95% interval [1.465, 1.862]) and Marrakech **0.999** (52/52 sides; rank 34/57; interval [0.828, 1.182]). The separate same-season sensitivity audit produces different figures and must not be confused with the production long-history calculation. Montpellier's 54-side source/profile gap is resolved, but its TML ace rate (14.2%) still differs from Tennis Abstract's published 19.8%; TA's exact population/statistic definition remains unresolved. The deep audit tested shrinkage, surface weighting, uncertainty and a limited CPI cross-check. Physical validation remains limited and does not justify a clay-specific correction.

The bundled asset and downloadable `data/latest.json` are generated from the full-history calculation. The dataset-refresh workflow checks computed readings, match/side counts and confidence intervals against `data/speed_history.json` before publication. The dataset snapshot also carries the current season baseline and event-window metadata required by the on-device Live now path; it is not a server-computed live-rating feed.

- [PR #1 — live ATP discovery, parsing and coverage](docs/PR1.md)
- [PR #2 — formulation, statistical audit and diagnostics](docs/PR2.md)
- [Data provenance and calculation details](docs/DATA.md)
- [Physical validation protocol](docs/PHYSICAL_VALIDATION.md)
- [Global Challenger / qualifying backfill audit](docs/LOWER_TIER_BACKFILL_AUDIT.md)
- [Surface-specific lower-tier weights and 2026 forward test](docs/SURFACE_SPECIFIC_TIER_WEIGHT_EXPERIMENT.md)
- [Recommended GitHub About description and Topics](docs/ABOUT.md)


Lower-tier profile backfill is research-only and is not used in production. The first 2019–2025 holdout and the new partial-2026 forward test both showed coverage gains but inconclusive overall predictive-loss improvements, worsening calibration, and mixed surface effects. ATP-only profiles remain the production policy pending a stronger independent validation set and data-rights review.
## Data and provenance

| Purpose | Source | Role |
|---|---|---|
| Physical CPI | [courtspeed.com](https://courtspeed.com/) | Published physical measurements |
| Behavioural reference | [Tennis Abstract](https://tennisabstract.com/reports/atp_surface_speed.html) | Published reference values and labelled fallbacks |
| Historical match statistics | [Jeff Sackmann ATP archive](https://github.com/JeffSackmann/tennis_atp) via [archive mirror](https://github.com/Aneeshers/tennis-sackmann-archive) | Historical CALC inputs |
| Current-season match statistics | [TennisMyLife ATP database](https://github.com/Tennismylife/TML-Database) | Current-season CALC inputs when available |
| Live match statistics | [ATP/Infosys Stats Plus](https://www.atptour.com/) | Read-only runtime statistics retrieval |

The current-season loader records source URL, fetch time, latest match date, row count, byte count and SHA-256 in a local sidecar manifest. A cached TML snapshot is reused only after refresh failure and only when its manifest/hash match and it is no older than seven days. Source selection is recorded; a fallback must not be mistaken for a fresh TML snapshot.

Tennis Abstract is a reference series, not physical ground truth. The source-required 2026 TML audit reconciled 27/27 source-stat-eligible Montpellier matches (54 statistically complete server sides); expanded multi-season leave-one-event-out profiles now rate all 54 sides without a neutral-prior assignment. This resolves the earlier 54-versus-47 profile-coverage gap. The TML source ace rate remains 14.1964% (545 aces / 3,839 service points), versus TA's published 19.8%; this does not establish TA's match population or explain the remaining rate difference. The corrected 1991–2026 profile sweep covered 1,533 mapped event-seasons, 71,150 matches and 142,300 sides; 1,930 sides (1.356%) used at least one neutral tour-average prior, comprising 980 server-role and 980 returner-role uses. Thirty sides required both roles. See the [consolidated all-event audit](https://github.com/KR1901/court-pace-index/actions/runs/42483108359). Separately, the current TML public terms do not clearly grant redistribution of TML-derived ratings in a public app; the 2026 bundled TML-derived output should be treated as not cleared for onward distribution until the source confirms this use in writing.

See [DATA.md](docs/DATA.md) for provenance and [DATA-LICENSES.md](DATA-LICENSES.md) for the separate terms that apply to code and third-party data. **The MIT code licence is not a blanket licence for the bundled datasets or third-party content.**

## Build and test

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
python3 -m unittest discover -s tools -p "test_*.py" -v
python3 tools/compute_speed.py --write
python3 tools/build_dataset.py
python3 tools/audit_event_population.py --event Montpellier --season 2026 --require-source "TennisMyLife ATP Match Database"
python3 tools/audit_event_population.py --all-events --season 2026 --require-source "TennisMyLife ATP Match Database"
```

The event-population audit writes local CSV and JSON reports under `build/event-population-audit/`. Use `--require-source` to fail rather than silently use a fallback source. `--all-events` audits each mapped tournament in the selected season and reports profile coverage, neutral-prior use and any remaining unrateable sides. The raw TML source CSV and player-level report are not copied into the repository.

## Limitations

CPI covers a limited set of instrumented courts. Surface Speed is behavioural and can be noisy in small draws. The present CPI cross-check is sparse and non-random, particularly on clay. Strong physical validation requires a larger independently measured CPR/CPI dataset with court identity, measurement date, source and relevant environmental metadata.

Court Pace is an independent project. It is not affiliated with, endorsed by or connected to the ATP or ITF.

## Licensing

- Original project code: MIT; see [LICENSE](LICENSE).
- Jeff Sackmann historical match data: CC BY-NC-SA 4.0, including attribution, non-commercial and share-alike conditions.
- TennisMyLife current-season data: its published terms limit use to personal, educational and non-commercial research; raw-database redistribution/resale is not authorised by those terms.
- Tennis Abstract reference values and Court Speed CPI readings: no blanket open-data licence is asserted by this project.
- ATP/Infosys content: ATP's terms restrict systematic retrieval of scores, statistics and rankings without prior express written permission.

See [DATA-LICENSES.md](DATA-LICENSES.md) for source-specific details. The MIT licence covers only original project code; private repository visibility does not expand any third-party data rights.
