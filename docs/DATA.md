# Data the measurement needs, and where it comes from

The Court Pace Index needs eight inputs. Four are measured by the phone, four come from the
court and the ball. This page lists each one, why the physics needs it, and its source.

## Measured by the phone

| Input | Why | How |
|---|---|---|
| Impact timestamps | Flight time between bounces is the only thing that determines restitution | `AudioRecord`, 44.1 kHz mono PCM16, peak envelope with an adaptive threshold (noise floor + 3.5σ) and a 45 ms refractory window so a single impact is never counted twice |
| Slide deceleration | A body sliding under friction alone decelerates at μg | `TYPE_ACCELEROMETER`, gravity included; flatness gate 7.5–12 m/s² on the vertical axis, longest steady segment, first and last fifth trimmed |
| Sample rate and timing resolution | Sets the precision of the restitution estimate | Fixed at 44.1 kHz; the envelope hop is 32 samples, i.e. 0.73 ms per hop, refined to the exact peak sample |
| Device flatness | A phone sliding on its edge is not a friction test | Same accelerometer stream, `|a_z|` gate |

## Entered by the user, because only they have it

| Input | Why | Where to find it |
|---|---|---|
| Drop height | Anchors the first bounce to a known impact speed | 254 cm is the ITF ball-rebound drop (ITF TB 04/01); 127 cm is the ITF predictive rebound minimum; the app offers both |
| Surface family | Sets the fallback friction when the slide test is skipped, and the sanity range for a measured value | Court operator or club records; the ITF classified-surface lists in the annual Technical Booklet |
| Ball type | Type 1 / 2 / 3 and high-altitude balls have different legal rebound ranges (138–151, 135–147, 122–135 cm) and different expected restitution | Printed on the can; ITF Rules of Tennis, Appendix I |
| Ball and air temperature | Restitution is corrected to 23 °C with the ITF coefficient 0.003 per °C | Thermometer or the venue's reading |
| Court identity | A measurement is only useful as a record you can compare against later | Name, indoor or outdoor, location note, date |

## Optional, if the user wants a fuller record

Latitude, longitude and elevation would let the app pick the correct high-altitude ball
class automatically (above 1219 m) and group courts by region. They are deliberately not
requested: the app has no location permission and no network access.

## Reference values used for sanity checks

| Surface | Typical COF | Typical CPR | Source |
|---|---|---|---|
| Clay, unbound mineral | 0.71–0.85 | 20–29 | ITF Table 3 (high friction), Cross (2002) μ ≈ 0.8 |
| Acrylic hard | 0.60–0.70 | 30–39 | ITF Table 3 (medium friction) |
| Synthetic clay | 0.66–0.76 | 27–35 | Sand-dressed systems, ITF Category 2 |
| Carpet | 0.55–0.68 | 35–48 | ITF Categories 3–5 |
| Asphalt | 0.58–0.70 | 34–44 | ITF Categories 3–4 |
| Natural grass | 0.50–0.60 | 40–50 | Cross (2002) μ ≈ 0.6; ITF Categories 4–5 |
| Artificial grass | 0.52–0.62 | 38–48 | ITF Categories 4–5 |
| Wood, indoor | 0.55–0.65 | 38–48 | ITF Category 4 |

Coefficient bands from ITF CS 01/02, Table 3: COR high ≥ 0.85, medium 0.79–0.84, low ≤ 0.78;
COF high ≥ 0.71, medium 0.56–0.70, low ≤ 0.55. Surfaces below COR 0.70 are not recommended
by the ITF for tennis, and the app says so when it measures one.

## Sources

1. ITF, *Approved tennis balls, classified surfaces and recognised courts — a guide to
   products and test methods* (annual Technical Booklet). Section 4, Court pace (ITF CS
   01/02): apparatus, procedure, the CPR equation, Tables 2 and 3. Section 5, ball rebound
   (predictive method, ITF TB 04/01).
2. ITF, *Rules of Tennis*, Appendix I — ball types, mass 56.0–59.4 g, diameter 6.54–6.86 cm,
   rebound ranges.
3. R. Cross, *Measurements of the horizontal and vertical speeds of tennis courts*, Sports
   Engineering (2002) — COF and COR by video, the SPR → CPR relationship, typical values per
   surface.
4. R. Cross, *Measurement of the speed and bounce of tennis courts* — simple COF (dragged
   ball box) and COR (vertical drop) methods, and the limits of each.
5. ITF, *Court surface assessment programmes* — the five pace categories and their
   interpretation for purchasers.

## What the app deliberately does not claim

The ITF method assumes a rigid ball and surface, a ball sliding throughout contact, and an
impact at 30 m/s and 16° with spin below 3 rev/s. None of those hold for a phone
measurement. The result correlates with the reference method and separates courts reliably;
it is not a certification, and the app labels it as an estimate in the UI, in the saved
record (`protocol: CPI-v1`) and in the export.
