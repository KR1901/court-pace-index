#!/usr/bin/env python3
"""Research-only, chronological probability calibration audit for expected ace rates.

Every scored tournament is excluded from its own profile training. The audit compares
the production calibrated Log5 probability with a neutral tour-rate baseline and an
additive probability baseline, then evaluates held-out grouped-binomial outcomes.
It does not change production ratings or the expected-aces formula.
"""
import argparse
import json
import math
import random
import statistics
from collections import defaultdict
from datetime import date
from pathlib import Path

import compute_speed
from chronological_loeo_study import (
    grouped_standard_chronology_rows,
    grouped_standard_events,
)

EPSILON = 1e-9
MODEL_NAMES = ("log5", "neutral", "additive")
PROBABILITY_BINS = (0.0, 0.03, 0.05, 0.07, 0.09, 0.12, 0.16, 1.0000001)


def clamp_probability(value):
    if not math.isfinite(value):
        raise ValueError("probability must be finite")
    return min(max(float(value), EPSILON), 1.0 - EPSILON)


def probability_models(serve_rate, concede_rate, tour_rate):
    """Return per-service-point probabilities under three explicit models.

    log5 is the production odds-ratio formula. Its 0.5 expected-ace floor is deliberately
    not applied here: that floor stabilises the actual/expected rating denominator, not
    the probability used to predict an ace on a service point.
    """
    rates = (serve_rate, concede_rate, tour_rate)
    if not all(
        isinstance(value, (int, float))
        and math.isfinite(float(value))
        and 0.0 < float(value) < 1.0
        for value in rates
    ):
        raise ValueError("serve, return-concession and tour rates must be in (0, 1)")

    expected_for_one_point = compute_speed.expected_calibrated_aces(
        float(serve_rate), float(concede_rate), float(tour_rate), 1.0,
    )
    log5 = clamp_probability(expected_for_one_point)
    neutral = clamp_probability(tour_rate)
    additive_unclipped = float(serve_rate) + float(concede_rate) - float(tour_rate)
    additive = clamp_probability(additive_unclipped)
    return {
        "log5": log5,
        "neutral": neutral,
        "additive": additive,
    }, {
        "additiveClippedLow": additive_unclipped <= EPSILON,
        "additiveClippedHigh": additive_unclipped >= 1.0 - EPSILON,
    }


def binomial_scores(aces, service_points, probability):
    """Grouped-binomial negative log loss and exact per-trial Brier numerator."""
    aces = float(aces)
    service_points = float(service_points)
    probability = clamp_probability(probability)
    if service_points <= 0.0 or aces < 0.0 or aces > service_points:
        raise ValueError("invalid ace count or service-point total")
    log_loss = -(
        aces * math.log(probability)
        + (service_points - aces) * math.log1p(-probability)
    )
    # Sum of squared errors over the individual service-point outcomes, reconstructed
    # exactly from grouped successes/failures; no point-level rows are needed.
    brier = (
        aces * (1.0 - probability) ** 2
        + (service_points - aces) * probability ** 2
    )
    return log_loss, brier


def _logit(probability):
    probability = clamp_probability(probability)
    return math.log(probability / (1.0 - probability))


def _sigmoid(value):
    if value >= 0.0:
        z = math.exp(-min(value, 700.0))
        return 1.0 / (1.0 + z)
    z = math.exp(max(value, -700.0))
    return z / (1.0 + z)


def fit_logistic_calibration(observations):
    """Post-hoc grouped-binomial calibration intercept/slope for logit(predicted p).

    A calibrated model has intercept 0 and slope 1. This is descriptive evaluation over
    held-out, event-excluded predictions; the fitted coefficients are not applied to CALC.
    """
    prepared = [
        (_logit(item["probability"]), float(item["aces"]), float(item["servicePoints"]))
        for item in observations
        if item["servicePoints"] > 0
    ]
    if len(prepared) < 3:
        return None

    intercept = 0.0
    slope = 1.0
    ridge = 1e-9
    converged = False
    for iteration in range(100):
        g0 = -ridge * intercept
        g1 = -ridge * slope
        h00 = ridge
        h01 = 0.0
        h11 = ridge
        for x, aces, trials in prepared:
            q = _sigmoid(intercept + slope * x)
            residual = aces - trials * q
            variance = trials * q * (1.0 - q)
            g0 += residual
            g1 += residual * x
            h00 += variance
            h01 += variance * x
            h11 += variance * x * x
        determinant = h00 * h11 - h01 * h01
        if determinant <= 1e-18 or not math.isfinite(determinant):
            return None
        delta_intercept = (g0 * h11 - g1 * h01) / determinant
        delta_slope = (g1 * h00 - g0 * h01) / determinant
        if not math.isfinite(delta_intercept) or not math.isfinite(delta_slope):
            return None
        # Guard an ill-conditioned iteration without silently accepting divergence.
        delta_intercept = max(min(delta_intercept, 5.0), -5.0)
        delta_slope = max(min(delta_slope, 5.0), -5.0)
        intercept += delta_intercept
        slope += delta_slope
        if max(abs(delta_intercept), abs(delta_slope)) < 1e-8:
            converged = True
            break

    return {
        "intercept": intercept,
        "slope": slope,
        "converged": converged,
        "iterations": iteration + 1,
        "observations": len(prepared),
        "servicePoints": int(sum(item[2] for item in prepared)),
    }


def _new_event_accumulator():
    return {
        model: {
            "logLoss": 0.0,
            "brier": 0.0,
            "predictedAces": 0.0,
            "actualAces": 0.0,
            "servicePoints": 0.0,
            "sides": 0,
        }
        for model in MODEL_NAMES
    }


def score_event(event_rows, profiles, tour_rate, year, event_id):
    """Score every valid server side using production pre-event player profiles."""
    compute_speed.ensure_event_profiles(event_rows, profiles, tour_rate)
    totals = _new_event_accumulator()
    observations = {model: [] for model in MODEL_NAMES}
    clipping = {"additiveLowSides": 0, "additiveHighSides": 0}
    rateable_sides = 0

    for row in event_rows:
        for server_key, returner_key, points_key, aces_key in (
            ("winner_id", "loser_id", "w_svpt", "w_ace"),
            ("loser_id", "winner_id", "l_svpt", "l_ace"),
        ):
            service_points = compute_speed.num(row.get(points_key))
            aces = compute_speed.num(row.get(aces_key))
            serve_rate = profiles.get(row.get(server_key), {}).get("serve_rate")
            concede_rate = profiles.get(row.get(returner_key), {}).get("concede_rate")
            if (
                service_points is None or aces is None
                or service_points <= 0.0 or aces < 0.0 or aces > service_points
                or serve_rate is None or concede_rate is None
                or tour_rate is None
                or not all(
                    math.isfinite(float(value)) and 0.0 < float(value) < 1.0
                    for value in (serve_rate, concede_rate, tour_rate)
                )
            ):
                continue

            probabilities, flags = probability_models(
                serve_rate, concede_rate, tour_rate,
            )
            clipping["additiveLowSides"] += int(flags["additiveClippedLow"])
            clipping["additiveHighSides"] += int(flags["additiveClippedHigh"])
            rateable_sides += 1

            for model, probability in probabilities.items():
                log_loss, brier = binomial_scores(
                    aces, service_points, probability,
                )
                stats = totals[model]
                stats["logLoss"] += log_loss
                stats["brier"] += brier
                stats["predictedAces"] += probability * service_points
                stats["actualAces"] += aces
                stats["servicePoints"] += service_points
                stats["sides"] += 1
                observations[model].append({
                    "probability": probability,
                    "aces": aces,
                    "servicePoints": service_points,
                    "year": year,
                    "event": event_id,
                })

    if rateable_sides == 0:
        return None

    return {
        "year": year,
        "event": event_id,
        "matches": len(event_rows),
        "rateableSides": rateable_sides,
        "models": totals,
        "observations": observations,
        "additiveClipping": clipping,
    }


def _sum_event_records(records):
    totals = _new_event_accumulator()
    for event in records:
        for model in MODEL_NAMES:
            for key in totals[model]:
                totals[model][key] += event["models"][model][key]
    return totals


def _summary_from_totals(totals, observations=None):
    summary = {}
    for model in MODEL_NAMES:
        item = totals[model]
        points = item["servicePoints"]
        summary[model] = {
            "sides": int(item["sides"]),
            "servicePoints": int(points),
            "actualAces": round(item["actualAces"], 3),
            "predictedAces": round(item["predictedAces"], 3),
            "observedAceRate": item["actualAces"] / points if points else None,
            "predictedAceRate": item["predictedAces"] / points if points else None,
            "logLossPerServicePoint": item["logLoss"] / points if points else None,
            "brierPerServicePoint": item["brier"] / points if points else None,
            "calibration": (
                fit_logistic_calibration(observations.get(model, []))
                if observations is not None else None
            ),
        }
    return summary


def calibration_bins(observations):
    bins = []
    for low, high in zip(PROBABILITY_BINS[:-1], PROBABILITY_BINS[1:]):
        selected = [
            item for item in observations
            if low <= item["probability"] < high
        ]
        points = sum(item["servicePoints"] for item in selected)
        aces = sum(item["aces"] for item in selected)
        predicted = sum(item["probability"] * item["servicePoints"] for item in selected)
        bins.append({
            "lowerProbability": low,
            "upperProbabilityExclusive": high,
            "sides": len(selected),
            "servicePoints": int(points),
            "actualAces": round(aces, 3),
            "predictedAces": round(predicted, 3),
            "observedAceRate": aces / points if points else None,
            "meanPredictedAceRate": predicted / points if points else None,
        })
    return bins


def _percentile(values, q):
    ordered = sorted(values)
    if not ordered:
        return None
    position = (len(ordered) - 1) * q
    lower = int(math.floor(position))
    upper = int(math.ceil(position))
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] * (1.0 - fraction) + ordered[upper] * fraction


def event_cluster_bootstrap(events, iterations=1000, seed=73191):
    """Season-stratified tournament-cluster bootstrap of proper-score differences."""
    by_year = defaultdict(list)
    for event in events:
        by_year[event["year"]].append(event)
    years = sorted(by_year)
    rng = random.Random(seed)
    comparisons = (
        ("neutral", "log5"),
        ("additive", "log5"),
    )
    draws = {
        f"{candidate}_minus_{baseline}": {
            "logLossPerServicePoint": [],
            "brierPerServicePoint": [],
        }
        for candidate, baseline in comparisons
    }

    for _ in range(iterations):
        totals = _new_event_accumulator()
        for year in years:
            sample = by_year[year]
            for event in rng.choices(sample, k=len(sample)):
                for model in MODEL_NAMES:
                    for key in totals[model]:
                        totals[model][key] += event["models"][model][key]
        for candidate, baseline in comparisons:
            name = f"{candidate}_minus_{baseline}"
            for metric, numerator in (
                ("logLossPerServicePoint", "logLoss"),
                ("brierPerServicePoint", "brier"),
            ):
                cpoints = totals[candidate]["servicePoints"]
                bpoints = totals[baseline]["servicePoints"]
                candidate_score = (
                    totals[candidate][numerator] / cpoints if cpoints else float("nan")
                )
                baseline_score = (
                    totals[baseline][numerator] / bpoints if bpoints else float("nan")
                )
                if math.isfinite(candidate_score) and math.isfinite(baseline_score):
                    draws[name][metric].append(candidate_score - baseline_score)

    output = {}
    for comparison, metrics in draws.items():
        output[comparison] = {}
        for metric, values in metrics.items():
            output[comparison][metric] = {
                "meanDifference": statistics.fmean(values) if values else None,
                "ci95": [_percentile(values, 0.025), _percentile(values, 0.975)],
                "resamples": len(values),
                "cluster": "tournament-season, stratified by season",
            }
    return output


def run_audit(start_year, end_year, bootstrap_iterations=1000):
    events = []
    skipped = defaultdict(int)
    source_by_year = {}

    for year in range(start_year, end_year + 1):
        print(f"Loading {year} source rows...", flush=True)
        rows = compute_speed.load(year)
        source = compute_speed.SOURCE_BY_YEAR.get(year, "unknown")
        source_by_year[str(year)] = source
        event_groups = grouped_standard_events(rows)
        chronology_groups = grouped_standard_chronology_rows(rows)
        source_precision_by_event = compute_speed._source_event_date_granularity(
            rows, source,
        )

        year_events = 0
        for event_id, event_rows in event_groups.items():
            chronology_rows = chronology_groups.get(event_id) or event_rows
            dates = [
                compute_speed._match_date(row)
                for row in chronology_rows
            ]
            dates = [value for value in dates if value is not None]
            if not dates:
                skipped["missing_event_start"] += 1
                continue
            start_date = min(dates)
            target_start_timestamp = compute_speed._event_first_match_timestamp(
                chronology_rows,
            )
            profiles, tour_rate, _training_rows = (
                compute_speed._chronological_profiles_and_anchor(
                    year,
                    rows,
                    start_date,
                    source_precision_by_event,
                    target_start_timestamp,
                )
            )
            if (
                profiles is None or tour_rate is None
                or not math.isfinite(tour_rate)
                or not 0.0 < tour_rate < 1.0
            ):
                skipped["no_pre_event_profile_or_tour_rate"] += 1
                continue

            result = score_event(
                event_rows, profiles, tour_rate, year, event_id,
            )
            if result is None:
                skipped["no_rateable_server_sides"] += 1
                continue
            events.append(result)
            year_events += 1
        print(
            f"{year}: {year_events} events scored · source={source}",
            flush=True,
        )

    if not events:
        raise RuntimeError("No rateable chronological event predictions were produced.")

    all_observations = {
        model: [
            observation
            for event in events
            for observation in event["observations"][model]
        ]
        for model in MODEL_NAMES
    }
    totals = _sum_event_records(events)
    summary = _summary_from_totals(totals, all_observations)

    split_events = {
        "2016-2020": [event for event in events if 2016 <= event["year"] <= 2020],
        "2021-2026": [event for event in events if 2021 <= event["year"] <= 2026],
    }
    time_splits = {}
    for label, selected in split_events.items():
        observations = {
            model: [
                item for event in selected for item in event["observations"][model]
            ]
            for model in MODEL_NAMES
        }
        time_splits[label] = _summary_from_totals(
            _sum_event_records(selected), observations,
        )

    output = {
        "startYear": start_year,
        "endYear": end_year,
        "eventsCompared": len(events),
        "yearsCovered": sorted({event["year"] for event in events}),
        "totalRateableSides": int(totals["log5"]["sides"]),
        "totalServicePoints": int(totals["log5"]["servicePoints"]),
        "skipped": dict(skipped),
        "sourceByYear": source_by_year,
        "models": summary,
        "timeSplits": time_splits,
        "productionLog5CalibrationBins": calibration_bins(all_observations["log5"]),
        "additiveClipping": {
            "lowProbabilitySides": sum(
                event["additiveClipping"]["additiveLowSides"] for event in events
            ),
            "highProbabilitySides": sum(
                event["additiveClipping"]["additiveHighSides"] for event in events
            ),
        },
        "pairedTournamentClusterBootstrap": event_cluster_bootstrap(
            events,
            iterations=bootstrap_iterations,
        ),
        "interpretation": (
            "All probabilities are scored on target-event matches excluded from the "
            "profiles used to predict them. Log5 is the production odds-ratio model; "
            "neutral and additive are research comparators only. The 0.5 expected-ace "
            "floor is intentionally absent from probability scoring because it is only "
            "used in the actual/expected ratio denominator. Calibration coefficients "
            "are descriptive and are not applied to the production CALC."
        ),
    }
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--start-year", type=int, default=2016)
    parser.add_argument("--end-year", type=int, default=date.today().year)
    parser.add_argument("--bootstrap", type=int, default=1000)
    parser.add_argument("--output", default="ace-probability-calibration.json")
    args = parser.parse_args()
    if args.start_year < compute_speed.PROFILE_HISTORY_START_YEAR:
        parser.error(
            f"start year must be >= {compute_speed.PROFILE_HISTORY_START_YEAR}"
        )
    if args.end_year < args.start_year:
        parser.error("end year must be >= start year")
    if args.bootstrap < 100:
        parser.error("--bootstrap must be at least 100")

    report = run_audit(args.start_year, args.end_year, args.bootstrap)
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    print(
        "ACE PROBABILITY CALIBRATION RESULTS "
        f"· {report['eventsCompared']} events · "
        f"{report['totalRateableSides']} server sides · "
        f"{report['totalServicePoints']} service points"
    )
    for model, item in report["models"].items():
        print(
            f"{model:8s} logloss={item['logLossPerServicePoint']:.6f} "
            f"Brier={item['brierPerServicePoint']:.6f} "
            f"observed={item['observedAceRate']:.4%} "
            f"predicted={item['predictedAceRate']:.4%} "
            f"calibration={item['calibration']}"
        )
    print("Paired tournament-cluster bootstrap:")
    for name, result in report["pairedTournamentClusterBootstrap"].items():
        print(f"  {name}: {result}")
    print("Report written to", output_path)


if __name__ == "__main__":
    main()
