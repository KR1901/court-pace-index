# Independent physical validation plan

## Purpose

The ace-based Surface Speed rating is behavioural. Tennis Abstract is a useful external comparison but not an independent physical measurement. Physical validation must use court-surface measurements such as ITF Court Pace Rating (CPR) or appropriately sourced CPI readings.

## Dataset schema

Create a versioned CSV with one row per event-season-court measurement. Keep source provenance and measurement conditions explicit.

| Column | Meaning |
|---|---|
| event_id, event_name, season | Stable event identity |
| court_id, court_name | Actual measured court; do not treat all courts at an event as identical |
| surface, court_construction | Surface category and construction |
| physical_metric, physical_value, physical_unit | CPR/CPI name, value and units |
| measurement_date, measurement_source, source_url | When and where the measurement was published |
| temperature_c, humidity_pct, altitude_m | Environmental context |
| ball_brand, ball_model | Ball context where known |
| calc_speed, ta_speed | Behavioural ratings for the same event-season |
| matches, service_points, adjusted_server_sides | Statistical coverage |
| uncertainty, notes | Measurement uncertainty and caveats |

Unknown values must remain blank; never infer court-specific measurements from an event-level number.

## Validation protocol

1. Freeze the model version and dataset snapshot before analysis.
2. Match physical measurements to the same event, season and court wherever possible.
3. Report event-level and court-level coverage separately.
4. Calculate overall and within-surface Pearson/Spearman association, MAE, bias and calibration slope.
5. Use event-cluster bootstrap intervals; do not treat multiple courts from one event as independent tournaments.
6. Stratify by surface and report sample sizes and missingness.
7. Run environmental sensitivity analyses only where measurements exist; do not impute temperature, humidity, altitude or ball data.
8. Reserve events/seasons for out-of-sample validation. Do not tune the model and then report the same observations as independent evidence.

## Current status

The repository CPI table provides a limited show-court physical cross-check, not a comprehensive court-level CPR dataset. The previous audit found 106 CPI overlaps overall and only 24 clay events. The negative clay association is a reason to expand the sample and inspect measurement matching, not sufficient evidence for a clay-specific correction.

This document defines the schema and protocol. It does not claim an expanded independent physical dataset has already been acquired.
