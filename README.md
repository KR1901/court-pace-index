# Court Pace Index

Measure how fast a tennis court plays, with a phone. Two measurements, one number.

The app captures the coefficient of restitution of the bounce and the coefficient of
friction of the surface, then combines them with the published ITF Court Pace Rating
equation to give a Court Pace Index from 0 to 100 and the matching ITF pace category.

| | |
|---|---|
| Language | Kotlin, Jetpack Compose |
| Build | AGP 9.5.0, Gradle 9.8.2, Kotlin 2.4.21, Compose BOM 2026.09.00 |
| SDK | compileSdk 37, targetSdk 37, minSdk 26 |
| UI | Liquid Glass — translucent layered surfaces, specular rims, spring motion, drifting mesh backdrop |
| Storage | One JSON file in app-private storage. No account, no network, no telemetry. |

## What it measures

**Restitution.** A ball is dropped from a measured height. The microphone times the
impacts; the flight time between them gives the bounce. For the first bounce

```
e = g · Δt / (2 · √(2gh))
```

which is the same quantity as the textbook `√(h_rebound / h_drop)`. Later bounces leave the
surface at `eⁿ · √(2gh)`, so their flight times decay geometrically and the app takes `e`
from the ratio of successive flight times instead — no drop height needed, no accumulated
error.

**Friction.** The phone is laid flat and pushed along the court. A body sliding under
friction alone decelerates at `μg`, so the accelerometer reads the coefficient directly:

```
μ = a / g
```

**Combination.** The ITF equation (ITF CS 01/02), with the ball temperature correction:

```
e₂₃ = e + 0.003 · (23 − T)
CPR = 100 · (1 − μ) + 150 · (0.81 − e₂₃)
CPI = clamp((CPR − 20) / 30 · 100, 0, 100)
```

| Category | CPR | Typical surface |
|---|---|---|
| 1 Slow | ≤ 29 | Clay, unbound mineral |
| 2 Medium-slow | 30–34 | Acrylic, some carpet |
| 3 Medium | 35–39 | Acrylic |
| 4 Medium-fast | 40–44 | Some carpet, artificial grass |
| 5 Fast | ≥ 45 | Natural grass, artificial grass |

## Screens

Home with the last measured court and its dial, the three-step test flow (court and ball,
bounce, friction), the result, the court library with per-court detail, the method page with
the formulas and sources, and settings with JSON export and import.

## Build

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # physics tests
./gradlew :app:assembleRelease        # release APK, signed if the env vars below are set
```

Release signing reads four environment variables and falls back to debug signing when they
are absent:

```
COURT_PACE_KEYSTORE=/path/to/release.jks
COURT_PACE_KEYSTORE_PASSWORD=...
COURT_PACE_KEY_ALIAS=...
COURT_PACE_KEY_PASSWORD=...
```

## Honest limits

This is a predictive estimate, not an ITF classification. The reference method fires a ball
at 30 m/s and 16° from an air cannon through infrared gates; a phone cannot. Friction here
is measured at sliding speed rather than at impact speed, and restitution at a vertical drop
rather than at 16° of incidence. The app reports the uncertainty it can actually observe —
spread between bounces, number of impacts, whether friction was measured or assumed — and
labels an estimated friction figure wherever it is used.

The ITF quotes ±2 points of uncertainty on its own method and ±5 on a quoted installation
value; treat differences of a few points between courts as noise and differences of ten as
real.

## Data

See [docs/DATA.md](docs/DATA.md) for every input the measurement needs, why it is needed,
and where it comes from.

## Licence

MIT. The physics follows published ITF test methods and R. Cross's papers; the app is not
affiliated with or endorsed by the ITF.
