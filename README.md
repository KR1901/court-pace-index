# Court Pace

Court Pace is an ATP court-speed research and reading app. It compares a physical court measurement (CPI) with a behavioural, ace-based Surface Speed estimate. The app bundles historical readings for offline use and optionally fetches live ATP match results while open.

| | |
|---|---|
| Platform | Android, Kotlin, Jetpack Compose |
| Build | AGP 9.5.0, Gradle 9.8.2, Kotlin 2.4.21 |
| SDK | compileSdk 37, targetSdk 37, minSdk 26 |
| Data | Bundled CPI and CALC/TA speed readings, 2012–2026; see the generated dataset for exact counts |
| Network | Optional HTTPS access for live ATP results/statistics and manual dataset updates |

## The two measurements

**Court Pace Index (CPI)** is a physical reading derived from ball-surface interaction on instrumented show courts. The published combination is:

```
CPI = 100(1 − μ) + 150(0.81 − e)
```

where μ is coefficient of friction and e is coefficient of restitution. Coverage is limited to measured courts; it is not a complete tournament-wide court census.

**Surface Speed** is a behavioural reading based on aces. It adjusts each server's actual aces for the server's serve tendency and the returner's ace-concession tendency, compares that with expected aces on a neutral tour-average surface, averages valid server-side ratios and season-normalises the event. 1.00 is the season tour average. The metric also responds to balls, weather, altitude and player tactics; it is not a pure physical surface measurement.

The calibrated expected-aces odds ratio is:

```
O = (serverOdds × returnerConcessionOdds) / tourAverageAceOdds
expectedAceRate = O / (1 + O)
expectedAces = servicePoints × expectedAceRate
```

Historical ratings use leave-one-tournament-out player profiles and tour anchor. Live ratings use the same calibrated equation and season normalisation, with a separately documented profile-backfill policy. The 0.5 expected-aces floor is applied as a small-denominator safeguard; it does not remove a valid server side from coverage.

## App features

- **Pace board:** season rankings with surface filters and like-for-like changes.
- **Tournament detail:** CPI and Surface Speed histories, source labels and comparisons.
- **Compare:** event comparisons and CPI/Speed percentile divergence.
- **Surfaces:** surface-level distributions and trends.
- **Method:** formulas, bands, source provenance and limitations.
- **Live now:** discovers active ATP 1000/500/250 events on-device, parses completed singles results and rates available match statistics. Live diagnostics expose raw score, normalisation, tour ace rate, service-point totals, profile matches and influential side ratios.

The live pipeline excludes walkovers, no-play, doubles and in-progress matches; played retirements remain eligible when valid statistics exist. Missing data stays visible rather than being fabricated. ATP/Infosys can block datacentre clients, so CI cannot replace real-device acceptance testing.

## Calculation evidence and decisions

PR #2's 1991–2026 comparison covers 1,289 common event-seasons across 36 seasons with 5,000 season-stratified paired bootstrap resamples. The calibrated surface-returner variant had the best overall rank agreement and MAE among tested variants (Pearson 0.925, Spearman 0.926, MAE 0.052 against the Tennis Abstract reference series). This is reference-series agreement, not proof of physical accuracy.

The full calculation audit also tested shrinkage, surface weighting, uncertainty and a limited CPI cross-check. The current production formula is retained; Montpellier source-population reconciliation and expanded independent physical validation remain open research tasks. See [PR #2 methodology record](docs/PR2.md) and [physical validation plan](docs/PHYSICAL_VALIDATION.md).

## Data and provenance

- CPI: courtspeed.com published CPI data.
- Tennis Abstract: published ATP Surface Speed figures, retained as comparison values and labelled fallbacks.
- Historical match archive: Jeff Sackmann ATP archive mirror, used to calculate CALC ratings.
- Current season: TennisMyLife ATP match CSV when available; a hash-verified cache is reused only after a failed refresh and only within the documented freshness window.
- Live match statistics: ATP/Infosys Stats Plus, fetched by the Android app.

Tennis Abstract is not physical ground truth. TennisMyLife's public terms restrict use to personal, educational and non-commercial research; do not commit or redistribute its raw CSV. Confirm current terms and obtain written permission before commercial use or broader redistribution. See [Data provenance](docs/DATA.md).

## PR records

- [PR #1 — live ATP match discovery, parsing and coverage](docs/PR1.md)
- [PR #2 — expected-aces formulation, calculation audit and provenance](docs/PR2.md)

PR #2 is stacked on PR #1. Changes in this workstream are committed only to PR #2's branch; neither PR is to be merged as part of this work.

## Build and tests

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
python3 -m unittest discover -s tools -p "test_*.py" -v
python3 tools/compute_speed.py --write
python3 tools/build_dataset.py
python3 tools/audit_event_population.py --event Montpellier --season 2026
```

The event-population audit writes a local CSV and JSON summary under `build/event-population-audit/`. It does not copy the raw source CSV into the repository. The output describes the source loaded by the calculation pipeline and does not establish Tennis Abstract's match population.

## Limitations

CPI is available for a limited set of instrumented courts. Surface Speed is behavioural and noisy for small draws. The current CPI cross-check is sparse, particularly on clay. A broader, independently measured CPR/CPI dataset with court identity, measurement date and environmental metadata is needed before claiming physical validation. See [PHYSICAL_VALIDATION.md](docs/PHYSICAL_VALIDATION.md).

Court Pace is not affiliated with, endorsed by or connected to the ATP or ITF.
