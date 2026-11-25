#!/usr/bin/env python3
"""Research-only comparison of retrospective LOEO and chronological pre-event CALC.

This script does not change production ratings. It compares the existing cross-fitted
season method with a conservative as-of-event candidate using only match records from
tournaments whose archive start date is at least 14 days before the target event. The
14-day quarantine is necessary because the Sackmann/TML yearly CSV stores tournament
start dates, not individual match dates; this deliberately sacrifices some recent history
rather than risk training on matches played after the target event began.

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

        # This is the production retrospective LOEO result and normaliser for each event.
        retrospective = compute_speed.season_ratings(year, rows, source=source)
        prior_tour_rate, prior_anchor, prior_error = prior_year_reference(year)
        if prior_tour_rate is None:
            prior_tour_rate = compute_speed.tour_average_ace_rate(standard_rows)
        if prior_anchor is None:
            # Last-resort anchor only for the earliest covered year. The output marks it.
            prior_anchor = 1.0

        dated_events = []
        for event_id, event_rows in groups.items():
            start = min(row_date(row) for row in event_rows)
            dated_events.append((start, event_id, event_rows))
        dated_events.sort(key=lambda item: (item[0], item[1]))

        chronological_raws = []
        event_items = []
        for start, event_id, event_rows in dated_events:
            production = retrospective.get(event_id)
            if production is None or production.get("speed") is None:
                continue

            target_start_cutoff = start - timedelta(days=quarantine_days)
            chronological_training = [
                row for row in standard_rows
                if (row_date(row) is not None and row_date(row) <= target_start_cutoff)
            ]
            # Build a reference only from earlier-season tournaments outside the conservative
            # overlap window. Current-year player samples and tour anchor both obey this cutoff.
            chrono_profiles = fit_profiles(year, chronological_training)
            current_tour_rate = compute_speed.tour_average_ace_rate(chronological_training)
            if current_tour_rate is None:
                chrono_tour_rate = prior_tour_rate
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

            # A causal season anchor uses only standard events that began outside the same
            # quarantine window and have already contributed an event estimate.
            prior_event_metrics = [
                item for event_date, item in chronological_raws
                if event_date <= target_start_cutoff
            ]
            stable_prior = [
                item["raw"] for item in prior_event_metrics
                if item["adjustedServerSides"] >= compute_speed.MIN_NORMALISER_SERVER_SIDES
            ]
            if not stable_prior:
                stable_prior = [item["raw"] for item in prior_event_metrics]
            chrono_anchor = statistics.mean(stable_prior) if stable_prior else prior_anchor
            chronological_raws.append((start, chronological))

            retrospective_diag = production.get("calculationDiagnostics") or {}
            event = {
                "year": year,
                "event": event_id,
                "startDate": start.isoformat(),
                "matches": chronological["matches"],
                "retrospective": {
                    "rating": production.get("speed"),
                    "raw": retrospective_diag.get("rawAdjustedEventScore"),
                    "normalisation": retrospective_diag.get("seasonNormalisation"),
                    "logLossPerServicePoint": None,
                    "adjustedServerSides": production.get("adjustedServerSides"),
                    "profileTrainingMatches": None,
                },
                "chronological": {
                    "rating": chronological["raw"] / chrono_anchor if chrono_anchor else None,
                    "raw": chronological["raw"],
                    "normalisation": chrono_anchor,
                    "logLossPerServicePoint": chronological["logLossPerServicePoint"],
                    "adjustedServerSides": chronological["adjustedServerSides"],
                    "profileTrainingMatches": len(chronological_training),
                    "tourAceRate": chrono_tour_rate,
                    "quarantineDays": quarantine_days,
                },
            }
            # Recreate the production retrospective training context to get a fair held-out
            # per-service-point log-loss comparison for exactly the same target matches.
            retro_training = [row for row in standard_rows
                              if compute_speed.tournament_id(row.get("tourney_name")) != event_id]
            retro_profiles = fit_profiles(year, retro_training)
            retro_tour_rate = compute_speed.tour_average_ace_rate(retro_training)
            if retro_tour_rate and 0.0 < retro_tour_rate < 1.0:
                retro_metrics = event_metrics(event_rows, retro_profiles, retro_tour_rate)
                if retro_metrics:
                    event["retrospective"]["logLossPerServicePoint"] = retro_metrics["logLossPerServicePoint"]
                    event["retrospective"]["profileTrainingMatches"] = len(retro_training)
            event["ratingDeltaChronologicalMinusRetrospective"] = (
                event["chronological"]["rating"] - event["retrospective"]["rating"]
                if event["chronological"]["rating"] is not None else None
            )
            event_items.append(event)
            all_event_pairs.append(event)

        retro_scores = {item["event"]: item["retrospective"]["rating"] for item in event_items}
        chrono_scores = {item["event"]: item["chronological"]["rating"] for item in event_items}
        paired = [item for item in event_items if item["chronological"]["logLossPerServicePoint"] is not None
                  and item["retrospective"]["logLossPerServicePoint"] is not None]
        deltas = [item["ratingDeltaChronologicalMinusRetrospective"] for item in event_items
                  if item["ratingDeltaChronologicalMinusRetrospective"] is not None]
        year_results.append({
            "year": year,
            "source": source,
            "eventsCompared": len(event_items),
            "meanAbsoluteRatingDelta": statistics.mean(abs(value) for value in deltas) if deltas else None,
            "medianAbsoluteRatingDelta": statistics.median(abs(value) for value in deltas) if deltas else None,
            "spearmanRatingRankCorrelation": spearman_rank(retro_scores, chrono_scores),
            "retrospectiveMeanLogLoss": (
                statistics.mean(item["retrospective"]["logLossPerServicePoint"] for item in paired)
                if paired else None
            ),
            "chronologicalMeanLogLoss": (
                statistics.mean(item["chronological"]["logLossPerServicePoint"] for item in paired)
                if paired else None
            ),
            "priorYearFallbackAnchor": prior_anchor,
            "priorYearTourAceRate": prior_tour_rate,
            "priorYearWarning": prior_error,
            "events": event_items,
        })
        print(
            f"{year}: {len(event_items)} events · "
            f"mean |rating delta|={year_results[-1]['meanAbsoluteRatingDelta']} · "
            f"Spearman={year_results[-1]['spearmanRatingRankCorrelation']}",
            flush=True,
        )

    paired_events = [
        item for item in all_event_pairs
        if item["retrospective"]["logLossPerServicePoint"] is not None
        and item["chronological"]["logLossPerServicePoint"] is not None
    ]
    deltas = [item["ratingDeltaChronologicalMinusRetrospective"] for item in all_event_pairs
              if item["ratingDeltaChronologicalMinusRetrospective"] is not None]
    summary = {
        "startYear": start_year,
        "endYear": end_year,
        "quarantineDays": quarantine_days,
        "eventsCompared": len(all_event_pairs),
        "meanAbsoluteRatingDelta": statistics.mean(abs(value) for value in deltas) if deltas else None,
        "medianAbsoluteRatingDelta": statistics.median(abs(value) for value in deltas) if deltas else None,
        "meanRetrospectiveLogLoss": (
            statistics.mean(item["retrospective"]["logLossPerServicePoint"] for item in paired_events)
            if paired_events else None
        ),
        "meanChronologicalLogLoss": (
            statistics.mean(item["chronological"]["logLossPerServicePoint"] for item in paired_events)
            if paired_events else None
        ),
        "warning": (
            "Research only. CSV tournament_date is a tournament start-date proxy, not an individual "
            "match timestamp. A 14-day quarantine reduces overlap leakage but removes recent evidence. "
            "This does not prove one method is objectively superior or change production ratings."
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
