# Court Pace

Court Pace is an Android app and research project for comparing how tennis courts play. It brings together **physical Court Pace Index (CPI) readings** and **behavioural, ace-based ATP Surface Speed ratings**, with historical comparisons, tournament detail and a live-tournament view.

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
- **Live now:** discovers active ATP 1000/500/250 events, reads completed singles results and rates available match statistics. Diagnostics expose the raw score, normalisation, tour ace rate, service-point totals, matched profiles and influential server-side ratios.

The live pipeline excludes walkovers, confirmed no-play records, doubles and in-progress matches. Played retirements remain eligible when valid statistics exist. Missing data remains visible; the app must not invent statistics to complete a rating. ATP/Infosys access can be device-sensitive, so a green CI build is not a substitute for live handset acceptance.

## Calculation evidence

The latest completed formula comparison covered 1,289 common event-seasons across 36 seasons (1991–2026), with 5,000 season-stratified paired bootstrap resamples. The calibrated surface-returner variant had the strongest overall rank agreement and mean absolute error among the tested candidates (Pearson 0.925, Spearman 0.926, MAE 0.052) against the Tennis Abstract reference series.

That result measures agreement with a published behavioural reference, **not physical accuracy**. The deep audit also tested shrinkage, surface weighting, uncertainty and a limited CPI cross-check. The production formulation is retained; exact Montpellier match-population reconciliation and broader independent physical validation remain explicit research tasks.

- [PR #1 — live ATP discovery, parsing and coverage](docs/PR1.md)
- [PR #2 — formulation, statistical audit and diagnostics](docs/PR2.md)
- [Data provenance and calculation details](docs/DATA.md)
- [Physical validation protocol](docs/PHYSICAL_VALIDATION.md)
- [Recommended GitHub About description and Topics](docs/ABOUT.md)

## Data and provenance

| Purpose | Source | Role |
|---|---|---|
| Physical CPI | [courtspeed.com](https://courtspeed.com/) | Published physical measurements |
| Behavioural reference | [Tennis Abstract](https://tennisabstract.com/reports/atp_surface_speed.html) | Published reference values and labelled fallbacks |
| Historical match statistics | [Jeff Sackmann ATP archive](https://github.com/JeffSackmann/tennis_atp) via [archive mirror](https://github.com/Aneeshers/tennis-sackmann-archive) | Historical CALC inputs |
| Current-season match statistics | [TennisMyLife ATP database](https://github.com/Tennismylife/TML-Database) | Current-season CALC inputs when available |
| Live match statistics | [ATP/Infosys Stats Plus](https://www.atptour.com/) | Read-only runtime statistics retrieval |

The current-season loader records source URL, fetch time, latest match date, row count, byte count and SHA-256 in a local sidecar manifest. A cached TML snapshot is reused only after refresh failure and only when its manifest/hash match and it is no older than seven days. Source selection is recorded; a fallback must not be mistaken for a fresh TML snapshot.

Tennis Abstract is a reference series, not physical ground truth. The source-required 2026 TML audit reconciled 27/27 source-stat-eligible Montpellier matches (54 statistically complete server sides); expanded multi-season leave-one-event-out profiles now rate all 54 sides without a neutral-prior assignment. This resolves the earlier 54-versus-47 profile-coverage gap. The TML source ace rate remains 14.1964% (545 aces / 3,839 service points), versus TA's published 19.8%; this does not establish TA's match population or explain the remaining rate difference. The full 1991–2026 profile sweep covered 1,533 mapped event-seasons, 71,150 matches and 142,300 sides; 1,584 sides used at least one explicit neutral tour-average prior. See the [consolidated all-event audit](https://github.com/KR1901/court-pace-index/actions/runs/42479832106). Separately, the current TML public terms do not clearly grant redistribution of TML-derived ratings in a public app; the 2026 bundled TML-derived output should be treated as not cleared for onward distribution until the source confirms this use in writing.

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

- Original software code: MIT; see [LICENSE](LICENSE).
- Match data, published reference readings and generated data: source-specific rights and restrictions apply. See [DATA-LICENSES.md](DATA-LICENSES.md).
- The repository's GitHub licence badge describes the code licence only; it does not imply that all data in the repository is MIT-licensed or freely reusable.
