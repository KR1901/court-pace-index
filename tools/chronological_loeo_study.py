#!/usr/bin/env python3
"""Research-only comparison of retrospective LOEO and chronological pre-event CALC.

This script does not change production ratings. It compares the retrospective cross-fit
with the production as-of-event chronology policy. Sources with verified per-match
timestamps use strict UTC ordering; sources with per-match calendar days use strict
day-before ordering (same-day matches are excluded when no clock is available). Legacy
archives containing only tournament-start dates retain the explicitly-labelled 14-day
fallback until a permitted match-day or event-window source is available.

The target event's complete sample is included in its observed score. Only training
profiles and the normalisation anchor are restricted to information available before it.
"""
import argparse
import json
import math
import os
import statistics
from collections import defaultdict
from datetime import date
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


def grouped_standard_chronology_rows(rows):
    """
    Return source-dated target rows regardless of serve-stat validity.

    Production uses all identified target rows to establish its first match day,
    while only valid-stat rows contribute to the event's score. The research study
    must apply the same distinction to avoid shifting the target start when the first
    match has missing/invalid serve statistics.
    """
    groups = defaultdict(list)
    for row in rows:
        event_id = compute_speed.tournament_id(row.get("tourney_name"))
        if not event_id or event_id in compute_speed.CALC_TARGET_ONLY_EVENT_IDS:
            continue
        if row.get("tourney_level") not in compute_speed.LEVELS:
            continue
        names = (row.get("winner_name") or "", row.get("loser_name") or "")
        if any("/" in name or " & " in name or " and " in name.casefold() for name in names):
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


def run(start_year, end_year):
    year_results = []
    all_event_pairs = []
    for year in range(start_year, end_year + 1):
        print(f"Loading {year} source rows...", flush=True)
        rows = compute_speed.load(year)
        source = compute_speed.SOURCE_BY_YEAR.get(year)
        standard_rows = compute_speed.calculation_eligible_rows(compute_speed.valid_rows(rows))
        groups = grouped_standard_events(rows)
        chronology_groups = grouped_standard_chronology_rows(rows)
        if not groups:
            continue

        source_precision_by_event = compute_speed._source_event_date_granularity(rows, source)
        date_granularity = compute_speed._source_match_date_granularity(rows, source)
        prior_tour_rate, prior_anchor, prior_error = prior_year_reference(year)
        dated_events = []
        for event_id, event_rows in groups.items():
            chronology_rows = chronology_groups.get(event_id) or event_rows
            start_date = min(row_date(row) for row in chronology_rows)
            dated_events.append((start_date, event_id, event_rows, chronology_rows))
        dated_events.sort(key=lambda item: (item[0], item[1]))

        # First fit the production retrospective LOEO profile independently for each target.
        # This reproduces season_ratings(): all same-season standard events except the target
        # may train profiles and the target-specific tour-average ace anchor.
        retrospective_by_event = {}
        for start_date, event_id, event_rows, chronology_rows in dated_events:
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

        event_items = []
        for start_date, event_id, event_rows, chronology_rows in dated_events:
            retro = retrospective_by_event.get(event_id)
            if retro is None:
                continue

            target_start_timestamp = compute_speed._event_first_match_timestamp(chronology_rows)
            target_date_granularity = compute_speed._event_date_granularity(
                chronology_rows, source_precision_by_event,
            )
            if target_start_timestamp is not None:
                chronology_precision = "match_timestamp"
                chronology_rule = "strictly_before_target_match_timestamp_utc"
                event_quarantine_days = 0
            elif target_date_granularity == "match_day":
                chronology_precision = "match_day"
                chronology_rule = "strictly_before_target_local_match_day"
                event_quarantine_days = 0
            else:
                chronology_precision = "tournament_start"
                chronology_rule = "legacy_max_event_window_by_source_level"
                event_quarantine_days = 0

            # Legacy start-date rows keep their own maximum event window; daily editions
            # are ordered by verified per-match dates, independently of the target source.
            chronological_training = [
                row for row in standard_rows
                if compute_speed._row_precedes_target(
                    row,
                    start_date,
                    source_precision_by_event.get(
                        compute_speed._source_event_key(row), "tournament_start",
                    ),
                    target_start_timestamp,
                )
            ]
            chrono_profiles = fit_profiles(year, chronological_training)
            # Match production: use this pre-target sample's tour rate directly;
            # use the completed prior-year rate only when no same-season sample exists.
            current_tour_rate = compute_speed.tour_average_ace_rate(chronological_training)
            chrono_tour_rate = (
                current_tour_rate if current_tour_rate is not None else prior_tour_rate
            )

            if chrono_tour_rate is None or not 0.0 < chrono_tour_rate < 1.0:
                continue
            chronological = event_metrics(event_rows, chrono_profiles, chrono_tour_rate)
            if chronological is None:
                continue

            # Match production exactly: derive the normalisation anchor from the same
            # pre-target training rows and frozen player profile used for this target.
            anchor_events, _, _, _ = compute_speed.event_raw_scores(
                chronological_training, chrono_profiles, tour_rate=chrono_tour_rate,
            )
            eligible_anchor = {
                event_id: value for event_id, value in anchor_events.items()
                if event_id not in compute_speed.CALC_TARGET_ONLY_EVENT_IDS
            }
            chrono_anchor = (
                statistics.mean(eligible_anchor.values())
                if eligible_anchor else chronological["raw"]
            )

            retro_rating = retro["raw"] / retrospective_anchor
            chrono_rating = chronological["raw"] / chrono_anchor if chrono_anchor else None
            event = {
                "year": year,
                "event": event_id,
                "startDate": start_date.isoformat(),
                "lastObservedMatchDate": (
                    max(row_date(row) for row in chronology_rows).isoformat()
                    if target_date_granularity == "match_day" else None
                ),
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
                    "datePrecision": chronology_precision,
                    "targetDateGranularity": target_date_granularity,
                    "sourceDateGranularity": date_granularity,
                    "chronologyRule": chronology_rule,
                    "legacyMaxEventDaysByLevel": compute_speed.LEGACY_MAX_EVENT_DAYS_BY_LEVEL,
                    "legacyUnknownMaxEventDays": compute_speed.LEGACY_UNKNOWN_MAX_EVENT_DAYS,
                    "legacyFallbackTrainingMatches": sum(
                        1 for row in chronological_training
                        if source_precision_by_event.get(
                            compute_speed._source_event_key(row), "tournament_start",
                        ) != "match_day"
                    ),
                    "trainingMatchDateMax": (
                        max(filter(None, (row_date(row) for row in chronological_training))).isoformat()
                        if chronological_training else None
                    ),
                    "quarantineDays": event_quarantine_days,
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
            "sourceDateGranularity": date_granularity,
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
        "legacyMaxEventDaysByLevel": compute_speed.LEGACY_MAX_EVENT_DAYS_BY_LEVEL,
        "legacyUnknownMaxEventDays": compute_speed.LEGACY_UNKNOWN_MAX_EVENT_DAYS,
        "eventsCompared": len(all_event_pairs),
        "meanAbsoluteRatingDelta": statistics.mean(abs(value) for value in deltas) if deltas else None,
        "medianAbsoluteRatingDelta": statistics.median(abs(value) for value in deltas) if deltas else None,
        "retrospectiveLogLossPerServicePoint": ret_loss / ret_points if ret_points else None,
        "chronologicalLogLossPerServicePoint": chrono_loss / chrono_points if chrono_points else None,
        "warning": (
            "Research only. Match-day and timestamp precision is source-dependent. The legacy "
            "Sackmann/TML historical archive provides tournament-start dates only; those years use "
            "conservative maximum event windows by source level (15 days for Grand Slams, 12 for "
            "Masters/ATP umbrella, 8 for Finals/500/250). These are upper bounds, not actual end dates. "
            "The current-season TML feed can provide per-match calendar days, but no clock time. "
            "ATP website automation is not used because ATP terms require prior written permission."
        ),
        "years": year_results,
    }
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--start-year", type=int, default=2016)
    parser.add_argument("--end-year", type=int, default=date.today().year)
    parser.add_argument("--output", default="build/chronological-loeo-study.json")
    args = parser.parse_args()
    if args.start_year < compute_speed.PROFILE_HISTORY_START_YEAR:
        parser.error(f"start year must be >= {compute_speed.PROFILE_HISTORY_START_YEAR}")
    if args.end_year < args.start_year:
        parser.error("end year must be >= start year")

    report = run(args.start_year, args.end_year)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Research report written to {output}")


if __name__ == "__main__":
    main()
