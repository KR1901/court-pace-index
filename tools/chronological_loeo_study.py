#!/usr/bin/env python3
"""Research-only comparison of retrospective LOEO with the source-date fallback baseline.

This script does not change production ratings. It measures the conservative fallback
needed when the yearly Sackmann/TML archive provides only a tournament start date. In
that case it admits training events whose start date is at least 14 days before the target.
The production calculation now prefers actual UTC match times, explicit UTC match dates,
and verified event end dates when a permitted source supplies them; this study isolates
the legacy fallback for comparison and reproducibility.

The target event's own complete match sample is included in both methods. Only the
reference player profiles and tour anchor are event-excluded.
"""
import argparse
import json
import math
import os
import statistics
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

import compute_speed


def row_date(row):
    value = str(row.get("tourney_date") or "")
    if len(value) < 8 or not value[:8].isdigit():
        return None
    try:
        return date(int(value[:4]), int(value[4:6]), int(value[6:8]))
    except ValueError:
        return None


def grouped_standard_events(rows):
    groups = defaultdict(list)
    for row in compute_speed.calculation_eligible_rows(compute_speed.valid_rows(rows)):
        event_id = compute_speed.tournament_id(row.get("tourney_name"))
        if not event_id or event_id in compute_speed.CALC_TARGET_ONLY_EVENT_IDS:
            continue
        if row.get("tourney_level") not in compute_speed.LEVELS:
            continue
        if row_date(row) is None:
            continue
        groups[event_id].append(row)
    return groups


def event_metrics(event_rows, profiles, tour_rate):
    details = compute_speed.event_side_diagnostics(
        event_rows,
        profiles,
        tour_rate,
        retain_per_tail=max(1, len(event_rows) * 2),
    )
    sides = details["sideDiagnostics"]
    ratios = [side["actualExpectedRatio"] for side in sides]
    if not ratios:
        return None
    nll = 0.0
    points = 0
    for side in sides:
        service_points = side["servicePoints"]
        aces = side["actualAces"]
        probability = side["expectedAces"] / service_points
        probability = min(max(probability, 1e-9), 1.0 - 1e-9)
        nll -= aces * math.log(probability) + (service_points - aces) * math.log(1.0 - probability)
        points += service_points
    return {
        "raw": sum(ratios) / len(ratios),
        "adjustedServerSides": len(ratios),
        "totalServerSides": len(event_rows) * 2,
        "matches": len(event_rows),
        "profileTrainingMatches": None,
        "totalServicePoints": points,
        "logLoss": nll,
        "logLossPerServicePoint": nll / points if points else None,
        "serverProfilesMatched": details["serverProfilesMatched"],
        "returnerProfilesMatched": details["returnerProfilesMatched"],
        "serverProfilesFallback": details["serverProfilesFallback"],
        "returnerProfilesFallback": details["returnerProfilesFallback"],
    }


def fit_profiles(year, training_rows):
    current = compute_speed.player_rates(
        training_rows,
        prior_points=compute_speed.PROFILE_PRIOR_SERVICE_POINTS,
    )
    return compute_speed.build_live_profiles(year, current)


def prior_year_reference(year):
    try:
        rows = compute_speed.load(year - 1)
    except Exception as error:
        return None, None, f"prior-year source unavailable: {error}"
    standard = compute_speed.calculation_eligible_rows(compute_speed.valid_rows(rows))
    tour_rate = compute_speed.tour_average_ace_rate(standard)
    prior_ratings = compute_speed.season_ratings(
        year - 1, rows, source=compute_speed.SOURCE_BY_YEAR.get(year - 1)
    )
    normalisers = [
        item.get("calculationDiagnostics", {}).get("seasonNormalisation")
        for event_id, item in prior_ratings.items()
        if event_id not in compute_speed.CALC_TARGET_ONLY_EVENT_IDS
        and item.get("speed") is not None
    ]
    normalisers = [value for value in normalisers if value and math.isfinite(value) and value > 0]
    anchor = statistics.mean(normalisers) if normalisers else None
    return tour_rate, anchor, None


def rank_map(values):
    ordered = sorted(values, key=lambda key: (-values[key], key))
    return {key: index + 1 for index, key in enumerate(ordered)}


def spearman_rank(left, right):
    keys = sorted(set(left) & set(right))
    n = len(keys)
    if n < 2:
        return None
    left_ranks, right_ranks = rank_map(left), rank_map(right)
    d2 = sum((left_ranks[key] - right_ranks[key]) ** 2 for key in keys)
    return 1.0 - (6.0 * d2) / (n * (n * n - 1))


def run(start_year, end_year, quarantine_days):
    year_results = []
    all_event_pairs = []
    for year in range(start_year, end_year + 1):
        print(f"Loading {year} source rows...", flush=True)
        rows = compute_speed.load(year)
        source = compute_speed.SOURCE_BY_YEAR.get(year)
        standard_rows = compute_speed.calculation_eligible_rows(compute_speed.valid_rows(rows))
        groups = grouped_standard_events(rows)
        if not groups:
            continue

        prior_tour_rate, prior_anchor, prior_error = prior_year_reference(year)
        dated_events = []
        for event_id, event_rows in groups.items():
            start_date = min(row_date(row) for row in event_rows)
            dated_events.append((start_date, event_id, event_rows))
        dated_events.sort(key=lambda item: (item[0], item[1]))

        # First fit the production retrospective LOEO profile independently for each target.
        # This reproduces season_ratings(): all same-season standard events except the target
        # may train profiles and the target-specific tour-average ace anchor.
        retrospective_by_event = {}
        for start_date, event_id, event_rows in dated_events:
            training = [
                row for row in standard_rows
                if compute_speed.tournament_id(row.get("tourney_name")) != event_id
            ]
            if not training:
                continue
            profiles = fit_profiles(year, training)
            tour_rate = compute_speed.tour_average_ace_rate(training)
            if tour_rate is None or not 0.0 < tour_rate < 1.0:
                continue
            metrics = event_metrics(event_rows, profiles, tour_rate)
            if metrics is None:
                continue
            metrics["profileTrainingMatches"] = len(training)
            metrics["tourAceRate"] = tour_rate
            retrospective_by_event[event_id] = metrics

        # Production season normalisation: prefer events with at least twenty adjusted sides;
        # otherwise use all rateable standard events.
        ret_stable = [
            metrics["raw"] for metrics in retrospective_by_event.values()
            if metrics["adjustedServerSides"] >= compute_speed.MIN_NORMALISER_SERVER_SIDES
        ]
        ret_anchor_values = ret_stable or [
            metrics["raw"] for metrics in retrospective_by_event.values()
        ]
        if not ret_anchor_values:
            continue
        retrospective_anchor = statistics.mean(ret_anchor_values)

        if prior_tour_rate is None:
            prior_rows = []
            try:
                prior_rows = compute_speed.calculation_eligible_rows(
                    compute_speed.valid_rows(compute_speed.load(year - 1))
                )
            except Exception:
                prior_rows = []
            prior_tour_rate = compute_speed.tour_average_ace_rate(prior_rows)

        if prior_anchor is None:
            # Only the earliest covered season should need this labelled neutral anchor.
            prior_anchor = retrospective_anchor

        chronological_raws = []
        event_items = []
        for start_date, event_id, event_rows in dated_events:
            retro = retrospective_by_event.get(event_id)
            if retro is None:
                continue

            # The archive gives tournament start dates rather than individual match dates.
            # Exclude the target's entire 14-day pre-start window so an overlapping or
            # two-week event cannot leak matches played after this target began.
            cutoff = start_date - timedelta(days=quarantine_days)
            chronological_training = [
                row for row in standard_rows
                if row_date(row) is not None and row_date(row) <= cutoff
            ]
            chrono_profiles = fit_profiles(year, chronological_training)
            current_tour_rate = compute_speed.tour_average_ace_rate(chronological_training)

            if current_tour_rate is None:
                chrono_tour_rate = prior_tour_rate
            elif prior_tour_rate is None:
                chrono_tour_rate = current_tour_rate
            else:
                current_points = sum(
                    compute_speed.num(row.get("w_svpt")) + compute_speed.num(row.get("l_svpt"))
                    for row in chronological_training
                )
                current_aces = sum(
                    compute_speed.num(row.get("w_ace")) + compute_speed.num(row.get("l_ace"))
                    for row in chronological_training
                )
                prior_points = compute_speed.PROFILE_PRIOR_SERVICE_POINTS
                chrono_tour_rate = (
                    current_aces + prior_tour_rate * prior_points
                ) / (current_points + prior_points)

            if chrono_tour_rate is None or not 0.0 < chrono_tour_rate < 1.0:
                continue
            chronological = event_metrics(event_rows, chrono_profiles, chrono_tour_rate)
            if chronological is None:
                continue

            # Rolling anchor uses only earlier events outside the same quarantine window.
            prior_event_metrics = [
                item for event_date, item in chronological_raws
                if event_date <= cutoff
            ]
            stable_prior = [
                item["raw"] for item in prior_event_metrics
                if item["adjustedServerSides"] >= compute_speed.MIN_NORMALISER_SERVER_SIDES
            ]
            if not stable_prior:
                stable_prior = [item["raw"] for item in prior_event_metrics]
            chrono_anchor = statistics.mean(stable_prior) if stable_prior else prior_anchor
            chronological_raws.append((start_date, chronological))

            retro_rating = retro["raw"] / retrospective_anchor
            chrono_rating = chronological["raw"] / chrono_anchor if chrono_anchor else None
            event = {
                "year": year,
                "event": event_id,
                "startDate": start_date.isoformat(),
                "matches": len(event_rows),
                "retrospective": {
                    "rating": retro_rating,
                    "raw": retro["raw"],
                    "normalisation": retrospective_anchor,
                    "logLoss": retro["logLoss"],
                    "logLossPerServicePoint": retro["logLossPerServicePoint"],
                    "totalServicePoints": retro["totalServicePoints"],
                    "adjustedServerSides": retro["adjustedServerSides"],
                    "profileTrainingMatches": retro["profileTrainingMatches"],
                    "tourAceRate": retro["tourAceRate"],
                },
                "chronological": {
                    "rating": chrono_rating,
                    "raw": chronological["raw"],
                    "normalisation": chrono_anchor,
                    "logLoss": chronological["logLoss"],
                    "logLossPerServicePoint": chronological["logLossPerServicePoint"],
                    "totalServicePoints": chronological["totalServicePoints"],
                    "adjustedServerSides": chronological["adjustedServerSides"],
                    "profileTrainingMatches": len(chronological_training),
                    "tourAceRate": chrono_tour_rate,
                    "quarantineDays": quarantine_days,
                },
            }
            event["ratingDeltaChronologicalMinusRetrospective"] = (
                chrono_rating - retro_rating if chrono_rating is not None else None
            )
            event_items.append(event)
            all_event_pairs.append(event)

        retro_scores = {item["event"]: item["retrospective"]["rating"] for item in event_items}
        chrono_scores = {item["event"]: item["chronological"]["rating"] for item in event_items}
        deltas = [item["ratingDeltaChronologicalMinusRetrospective"] for item in event_items
                  if item["ratingDeltaChronologicalMinusRetrospective"] is not None]
        ret_points = sum(item["retrospective"]["totalServicePoints"] for item in event_items)
        chrono_points = sum(item["chronological"]["totalServicePoints"] for item in event_items)
        ret_loss = sum(item["retrospective"]["logLoss"] for item in event_items)
        chrono_loss = sum(item["chronological"]["logLoss"] for item in event_items)
        year_results.append({
            "year": year,
            "source": source,
            "eventsCompared": len(event_items),
            "meanAbsoluteRatingDelta": statistics.mean(abs(value) for value in deltas) if deltas else None,
            "medianAbsoluteRatingDelta": statistics.median(abs(value) for value in deltas) if deltas else None,
            "spearmanRatingRankCorrelation": spearman_rank(retro_scores, chrono_scores),
            "retrospectiveLogLossPerServicePoint": ret_loss / ret_points if ret_points else None,
            "chronologicalLogLossPerServicePoint": chrono_loss / chrono_points if chrono_points else None,
            "priorYearFallbackAnchor": prior_anchor,
            "priorYearTourAceRate": prior_tour_rate,
            "priorYearWarning": prior_error,
            "retrospectiveNormalisation": retrospective_anchor,
            "events": event_items,
        })
        print(
            f"{year}: {len(event_items)} events · "
            f"mean |rating delta|={year_results[-1]['meanAbsoluteRatingDelta']} · "
            f"Spearman={year_results[-1]['spearmanRatingRankCorrelation']}",
            flush=True,
        )

    deltas = [item["ratingDeltaChronologicalMinusRetrospective"] for item in all_event_pairs
              if item["ratingDeltaChronologicalMinusRetrospective"] is not None]
    ret_points = sum(item["retrospective"]["totalServicePoints"] for item in all_event_pairs)
    chrono_points = sum(item["chronological"]["totalServicePoints"] for item in all_event_pairs)
    ret_loss = sum(item["retrospective"]["logLoss"] for item in all_event_pairs)
    chrono_loss = sum(item["chronological"]["logLoss"] for item in all_event_pairs)
    summary = {
        "startYear": start_year,
        "endYear": end_year,
        "quarantineDays": quarantine_days,
        "eventsCompared": len(all_event_pairs),
        "meanAbsoluteRatingDelta": statistics.mean(abs(value) for value in deltas) if deltas else None,
        "medianAbsoluteRatingDelta": statistics.median(abs(value) for value in deltas) if deltas else None,
        "retrospectiveLogLossPerServicePoint": ret_loss / ret_points if ret_points else None,
        "chronologicalLogLossPerServicePoint": chrono_loss / chrono_points if chrono_points else None,
        "warning": (
            "Research only. The source CSV provides tournament start dates, not exact match "
            "timestamps. The conservative quarantine avoids most overlapping-event leakage but "
            "also discards recent evidence. The result is a model comparison, not an automatic "
            "production-methodology change."
        ),
        "years": year_results,
    }
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--start-year", type=int, default=2016)
    parser.add_argument("--end-year", type=int, default=2025)
    parser.add_argument("--quarantine-days", type=int, default=14)
    parser.add_argument("--output", default="build/chronological-loeo-study.json")
    args = parser.parse_args()
    if args.start_year < compute_speed.PROFILE_HISTORY_START_YEAR:
        parser.error(f"start year must be >= {compute_speed.PROFILE_HISTORY_START_YEAR}")
    if args.end_year < args.start_year:
        parser.error("end year must be >= start year")

    report = run(args.start_year, args.end_year, args.quarantine_days)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Research report written to {output}")


if __name__ == "__main__":
    main()
