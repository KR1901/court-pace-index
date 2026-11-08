# Independent physical validation plan

## Purpose

The ace-based Surface Speed rating is behavioural. Tennis Abstract is a useful external behavioural reference, but it is not an independent physical measurement. Physical validation must use actual court measurements such as ITF Court Pace Rating (CPR) or appropriately sourced CPI readings.

The validation question is whether event/court Surface Speed rankings track independently measured ball-surface interaction. Do not interpret a high correlation as proof that the measures are interchangeable: Surface Speed is a match-outcome statistic, while CPR is a controlled ball-impact test.

## Preferred physical evidence: ITF Court Pace Rating

The 2026 ITF Technical Booklet describes ITF Court Pace Rating (ITF CS 01/02) as a measurement of ball-surface interaction, including horizontal friction and vertical restitution. The prescribed procedure uses three test balls with three impacts per ball (nine impacts total), at an incident angle of 16.0 ± 2.0 degrees and a speed of 30.0 ± 2.0 m/s. Ball spin is limited to no more than 3 revolutions per second. Impact locations are varied; directional surfaces such as natural or artificial grass are tested in the typical direction of play.

The apparatus is calibrated against ITF reference devices using standard surfaces every two years. The test report records the inbound and outbound horizontal/vertical velocities, coefficient of restitution, coefficient of friction and mean ball temperature. CPR is calculated as:

\`\`\`
e   = v_fy / v_iy
mu  = (v_ix - v_fx) / (v_iy * (1 + e))
e_T = e + 0.003 * (23 - T)
CPR = 100 * (1 - mu) + 150 * (0.81 - e_T)
\`\`\`

The reported test value is the mean CPR over the impacts, excluding court markings; spatial variation is reported separately. The 2026 booklet states that court-surface product testing is undertaken by ITF-accredited laboratories and that ITF Court Pace Classification is valid for three years.

**Important scope distinction:** an ITF classified-surface listing is a product classification, not a measurement of the actual installed court at a particular tournament and season. Use product classifications as contextual metadata only; do not treat them as a court-specific CPR observation.

Reference: [ITF 2026 Technical Booklet](https://www.itftennis.com/media/15639/2026-technical-booklet.pdf), Part B, section 4, printed pages 26–28.

## Dataset schema

Create a versioned CSV with one row per event-season-court measurement. Keep source provenance and measurement conditions explicit.

| Column | Meaning |
|---|---|
| event_id, event_name, season | Stable event identity |
| court_id, court_name | Actual measured court; do not treat all courts at an event as identical |
| surface, court_construction | Surface category and construction |
| physical_metric, physical_value, physical_unit | CPR/CPI/COF/COR name, value and units |
| measurement_date, measurement_source, source_url | When and where the measurement was published |
| lab_name, lab_accreditation, test_standard | Laboratory, accreditation and test method |
| calibration_date, apparatus_id | Calibration provenance where available |
| test_ball_brand, test_ball_model, test_ball_condition | Ball context where known |
| test_ball_temperature_c | Mean ball temperature used for CPR adjustment |
| temperature_c, humidity_pct, pressure_kpa, altitude_m | Environmental context at measurement |
| impact_count, location_count, location_variation, uncertainty | Test replication, spatial variation and uncertainty |
| calc_speed, ta_speed | Behavioural ratings for the same event-season |
| matches, service_points, adjusted_server_sides | Statistical coverage |
| notes | Measurement and matching caveats |

Unknown values must remain blank; never infer court-specific measurements or environmental values from an event-level number. Preserve the original measurement and its source units; do not silently convert an ITF product category into a numeric CPR.

## Evidence hierarchy and acquisition plan

1. **Highest priority — court-specific ITF CPR report.** Request the actual test report or measured court-level CPR from the ITF Technical Centre, an ITF-accredited laboratory, tournament organisers or court operators. Require court identity, test date, standard, apparatus calibration, test-ball temperature, impact/location replication and spatial variation.
2. **Second priority — independently published CPI/CPR with traceable provenance.** Accept only when the source identifies the actual court or installation and the measurement method. Store the raw value and uncertainty separately.
3. **Context only — product classifications and generic surface descriptions.** The ITF classified-surface list describes surface products. It does not establish the properties of a particular tournament court after installation, wear, maintenance, weather or temporary resurfacing. Do not enter it as an event-level physical observation.

The current repository CPI table provides a limited show-court cross-check. It must not be enlarged by copying generic surface categories, assigning a single court's value to every court at an event, or inferring missing values from surface type.

## Validation protocol

1. Freeze the model version, code commit and dataset snapshot before analysis.
2. Match physical measurements to the same event, season and court wherever possible. Keep exact-court matches separate from event-level or product-level proxies.
3. Report exact-court, event-level and surface-product coverage separately.
4. Compare within-season rankings and within-surface associations; do not assume the CPR and Surface Speed scales have a one-to-one linear conversion.
5. Calculate overall and within-surface Pearson/Spearman association, rank error, MAE after a predeclared calibration, bias and calibration slope. Report raw and within-surface-centred results.
6. Use event-cluster bootstrap intervals; do not treat several courts from one tournament as independent tournaments.
7. Stratify by surface and report sample sizes, court counts, missingness and measurement uncertainty.
8. Run environmental sensitivity analyses only where measurements exist; do not impute temperature, humidity, pressure, altitude or ball data.
9. Reserve entire events or seasons for out-of-sample validation. Do not tune the model and then report the same observations as independent evidence.
10. Publish exclusions, matching confidence and uncertainty intervals alongside all headline results.

## Current status and limitations

The current repository CPI audit contains 106 selected show-court overlaps overall and only 24 clay observations. The negative clay association is a reason to expand the sample and inspect measurement matching; it is not sufficient evidence for a clay-specific formula correction.

The 2026 ITF booklet provides a reproducible physical test protocol, but the publicly listed surface-product classifications are not a substitute for actual tournament court measurements. No new event-level CPR dataset has been acquired as part of this change. This document expands the acquisition standard and schema; it does not claim additional physical observations or completed physical validation.
