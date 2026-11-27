# Court Pace

Court Pace is an independent Android app and research toolkit for comparing how tennis courts play. It brings together physical Court Pace Index (CPI) readings and behavioural, ace-based ATP Surface Speed ratings, with historical comparisons, player statistics, head-to-head records and live-tournament coverage.

The app is an analysis tool, not a court-measuring instrument. It does not use the microphone or phone sensors to estimate court pace. Bundled readings work offline; internet access is used for dataset refreshes and live match statistics.

<details>
<summary>Current production release</summary>

**Court Pace 4.1.9** · versionCode 61 · published 27 November 2026

- [Download the signed production APK](https://github.com/KR1901/court-pace-index/releases/download/v4.1.9/court-pace-4.1.9.apk)
- [Release notes and build verification](https://github.com/KR1901/court-pace-index/releases/tag/v4.1.9)
- APK SHA-256: 68d07d4bc885a51122292cfbaebc3d91b4a68afd94b8ce41d22a6ce1dda92ae8
- Package: ink.courtpace.index.public

</details>

## App identity and platform

| Item | Current state |
|---|---|
| Platform | Android |
| Public app ID (4.0.0+) | ink.courtpace.index.public |
| Legacy app ID | ink.courtpace.index |
| Language and UI | Kotlin, Jetpack Compose |
| Build tools | AGP 9.5.0, Gradle 9.8.2, Kotlin 2.4.21 |
| Android SDK | compileSdk 37, targetSdk 37, minSdk 26 |
| Data | Bundled CPI, CALC and Tennis Abstract reference readings, player-statistics data and event metadata |

The public app uses its own release signing certificate and installs alongside the legacy app; it does not update or replace the legacy package. Android keeps the two installations' private settings and cached data separate.

## App features

- **Pace board:** season rankings, surface filters and like-for-like comparisons.
- **Tournament detail:** CPI and Surface Speed history, CALC diagnostics, source labels and uncertainty intervals where available.
- **Compare:** tournament-to-tournament comparisons and CPI/Surface Speed percentile divergence.
- **Surfaces:** surface-level distributions and trends.
- **Method:** formulas, rating bands, source provenance and limitations.
- **Players:** calendar-year Hold%, Break% and combined H+B leaderboards, all-player search, surface splits and height groups. Cohort analysis supports Top 100, Top 500 and Top 1000; Top 100 is the default.
- **Head to head:** searchable player selection, career meeting history and player context including ranking, age and height.
- **Live now:** active ATP 1000/500/250 events and the Davis Cup Final 8 are shown as live targets. Earlier Davis Cup ties remain a separate completed target; once the Final 8 finishes, the two Davis Cup phases remain distinct in Completed.

Live ratings exclude walkovers, confirmed no-play records, doubles and in-progress matches. Played retirements remain eligible when valid statistics exist. Missing serve statistics are never filled with zero or invented to complete a rating.

## Measurements and rating method

### Court Pace Index (CPI)

CPI is a physical measurement derived from ball-surface interaction on instrumented courts. The published composite is:

~~~text
CPI = 100(1 − μ) + 150(0.81 − e)
~~~

Here μ is the coefficient of friction and e is the coefficient of restitution. Higher CPI indicates a faster measured court under this scale. Coverage is limited to courts and dates for which measurements have been published; it is not a tournament-wide census.

### Behavioural Surface Speed

Surface Speed estimates how a court played through ace outcomes. Each valid server-side observation adjusts the server's ace rate for the server's serving tendency and the returner's ace-concession tendency, compares actual aces with expected aces, then season-normalises the tournament result. A value of 1.00 represents the season baseline.

~~~text
O = (serverOdds × returnerConcessionOdds) / tourAverageAceOdds
expectedAceRate = O / (1 + O)
expectedAces = servicePoints × expectedAceRate
sideRatio = actualAces / max(expectedAces, 0.5)
~~~

The 0.5 expected-aces floor protects the denominator from very small expectations; it does not remove an otherwise valid server side from the sample.

Production player profiles use up to 35 prior ATP seasons (from 1991 onward), with a 0.90 per-year recency weight and a 150-service-point shrinkage prior. A surface-specific rate is used after at least three matches on that surface; qualified clay and hard/grass rates use the neutral 1/3–2/3 blend. Pooled serve/return profiles provide fallbacks. Historical Sackmann IDs are joined to current TML player IDs only through a unique normalised full-name match; ambiguous identities are not merged. Where no defensible player rate exists, a tour-average prior is used explicitly and counted.

### Chronology and normalisation

The calculation uses actual match timestamps and event start/end information where the sources provide them. Where a source provides only a match calendar day, the code applies a date-level completion rule. Legacy archives that publish only a tournament start/week date use conservative maximum event windows by level: Grand Slam 15 days, Masters/ATP umbrella 12 days, and Finals/500/250 8 days (unknown levels 15 days). The old fixed 14-day quarantine is not used; the current chronology diagnostics report zero arbitrary quarantine days.

Only completed earlier events can train a same-season normalisation anchor. Player profile training is also cut off at the latest date that can be shown to precede the target. See [CHRONOLOGY.md](docs/CHRONOLOGY.md) for the precedence rules and edge cases.

Surface Speed is behavioural, not a pure physical measurement. Balls, weather, altitude, player tactics and sample size can affect it. CPI and Surface Speed should not be treated as interchangeable measurements.

## Davis Cup 2026: source coverage and phase split

Davis Cup is handled as two separate targets:

| 2026 phase | Identified/completed singles | Valid serve-stat matches | Missing serve stats | CALC matches | CALC speed |
|---|---:|---:|---:|---:|---:|
| Earlier ties (qualifiers/group ties) | 183 | 148 | 35 | 148 | 1.138 |
| Final 8 | 8 | 8 | 0 | 8 | 1.389 |
| **Total** | **191** | **156** | **35** | **156** | — |

The 35 are completed earlier-phase results in the TennisMyLife (TML) source, but the source does not record one or more of the required winner/loser ace and service-point fields. They are counted in the source-coverage denominator and deliberately excluded from the rating. They are not parser failures, scoreless matches, walkovers or malformed-stat rows. The current audit reports zero malformed rows.

The earlier-phase rating of 1.138 uses 148 matches (296 adjusted server sides; conditional match-cluster 95% interval 1.013–1.269). The Final 8 rating of 1.389 uses 8 matches (16 sides; interval 1.078–1.789). The Final 8 interval is wide because its sample is small. These phase-specific source counts are surfaced with the tournament CALC diagnostics and checked by the production-release gate.

The missing World Group II point statistics are an upstream coverage limitation: public match records for the relevant ties explicitly state that point statistics were not recorded. A scoreline alone cannot reconstruct aces or service points. Court Pace does not impute these values or treat them as zero. If TML later publishes the missing fields, the next validated dataset refresh can incorporate them automatically.

The TML client merges the annual ATP CSV with the ongoing-tournament feed, validates and deduplicates singles rows, and keeps the Final 8 separate by its source event identifier. The earlier Davis Cup phase is shown in Completed; only the Final 8 is eligible for Live now. Neither phase enters standard ATP player-profile training or the ATP normalisation pool.

## Latest calculation validation

PR #46 (merged 27 November 2026) added research-only chronology, cross-fitting, shrinkage, surface-weighting and ace-probability audits. It did not change the production CALC formula or the production profile policy.

- **Production chronology parity:** 564 event-seasons from 2016–2026 were compared. Raw scores, normalisers and final ratings matched the production implementation exactly for all 564.
- **Archive-wide LOEO study:** 2,205 common event-seasons across 36 seasons (1991–2026). The production 150-point prior / three-match surface qualification policy achieved Pearson r = 0.923, Spearman ρ = 0.928 and MAE = 0.066 against the no-shrink control (r = 0.884, ρ = 0.894, MAE = 0.080).
- **Tennis Abstract reference comparison:** 1,240 common event-seasons across 35 completed seasons (1991–2025). The production surface-returner variant achieved Pearson r = 0.925, Spearman ρ = 0.926 and MAE = 0.052. This is agreement with a behavioural reference, not proof of physical court speed.
- **Ace-probability calibration:** 564 tournament-seasons, 48,536 server sides and 3,985,366 service points. The production Log5 model achieved log loss 0.269706 and Brier score 0.072021; the tested alternatives were worse. No post-hoc correction was adopted.
- **Physical cross-check:** the available CPI comparison is small and non-random, especially within clay events. It does not support a clay-specific correction.

The research supports keeping the existing 150-point prior, three-match surface threshold, 1/3–2/3 surface blend and Log5 probability model. No new production normaliser or expected-aces formula was approved. The complete results are in [CALC_RESEARCH_AUDIT.md](docs/CALC_RESEARCH_AUDIT.md).

## Data flow and provenance

| Purpose | Source | Role |
|---|---|---|
| Physical CPI | [Court Speed](https://courtspeed.com/) | Published physical measurements |
| Behavioural reference | [Tennis Abstract](https://tennisabstract.com/reports/atp_surface_speed.html) | Published reference values and labelled fallbacks |
| Historical match statistics | [Jeff Sackmann ATP archive](https://github.com/JeffSackmann/tennis_atp) via [archive mirror](https://github.com/Aneeshers/tennis-sackmann-archive) | Historical CALC inputs |
| Current-season ATP and Davis Cup statistics | [TennisMyLife ATP database](https://github.com/Tennismylife/TML-Database) and ongoing tournament feed | Current-season CALC and Davis Cup source coverage |
| Live ATP match statistics | [ATP Tour / Infosys Stats Plus](https://www.atptour.com/) | Read-only runtime statistics retrieval |
| Player statistics, heights and ranking snapshots | [TennisMyLife Match Database](https://stats.tennismylife.org/tennis-match-database) | Separate Player Stats dataset |

The tournament pace dataset, live event metadata and Player Stats dataset are generated independently. The bundled pace dataset contains CPI, TA reference and CALC readings plus the current-season baseline and event metadata. The app calculates ATP live ratings on-device from ATP/Infosys match statistics; a CI build alone is not a substitute for live handset acceptance. Player Stats is built into its own asset and does not feed the live ace-rating engine.

The current-season loader records source URL, fetch time, latest match date, row count, byte count and SHA-256 in a local sidecar manifest. A cached TML snapshot is reused only after refresh failure and only if the manifest/hash match and it is no older than seven days. The source selected for each year is recorded; a fallback must not be mistaken for a fresh TML snapshot.

TML's public terms describe personal, educational and non-commercial research use and do not clearly grant redistribution of TML-derived ratings in a public app. This project does not claim a written redistribution licence. Only aggregate ratings and coverage diagnostics are bundled; raw TML match rows are not. Keep use free and non-commercial, do not resell or redistribute raw source data, and obtain written permission from the source before monetising or expanding redistribution. The MIT code licence does not grant rights to third-party datasets.

## Build and test

~~~bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
python3 -m unittest discover -s tools -p "test_*.py" -v
python3 tools/compute_speed.py --write
python3 tools/build_dataset.py
python3 tools/build_player_stats.py
python3 tools/audit_event_population.py --all-events --season 2026
~~~

The event-population audit writes local CSV and JSON reports under build/event-population-audit/. Use the source-selection options to fail rather than silently accept an unintended fallback. Raw TML source CSV files and player-level source reports are not copied into the repository.

## Documentation and audits

- [Data provenance and calculation details](docs/DATA.md)
- [Source-specific data terms](DATA-LICENSES.md)
- [Chronology rules](docs/CHRONOLOGY.md)
- [Consolidated CALC research audit](docs/CALC_RESEARCH_AUDIT.md)
- [Player Stats methodology](docs/PLAYER_STATS.md)
- [Physical validation protocol](docs/PHYSICAL_VALIDATION.md)
- [Global Challenger / qualifying backfill audit](docs/LOWER_TIER_BACKFILL_AUDIT.md)
- [Surface-specific lower-tier weight experiment](docs/SURFACE_SPECIFIC_TIER_WEIGHT_EXPERIMENT.md)
- [PR #1 — live ATP discovery, parsing and coverage](docs/PR1.md)
- [PR #2 — formulation, statistical audit and diagnostics](docs/PR2.md)

## Limitations and licensing

CPI covers a limited set of instrumented courts. Surface Speed can be noisy in small samples and is not a direct measure of physical friction or restitution. Strong physical validation requires a larger independent CPI dataset with court identity, measurement date, source and environmental metadata.

Court Pace is independent and is not affiliated with, endorsed by or connected to the ATP or ITF.

- Original project code: MIT; see [LICENSE](LICENSE).
- Jeff Sackmann historical match data: CC BY-NC-SA 4.0, including attribution, non-commercial and share-alike conditions.
- TennisMyLife data: source-specific personal/non-commercial terms; no raw database redistribution/resale permission is asserted.
- Tennis Abstract reference values and Court Speed CPI readings: no blanket open-data licence is asserted.
- ATP/Infosys content: ATP terms restrict systematic retrieval without prior written permission.

See [DATA-LICENSES.md](DATA-LICENSES.md) for source-specific details. Public repository visibility does not expand third-party data rights.
