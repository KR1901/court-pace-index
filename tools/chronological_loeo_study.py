#!/usr/bin/env python3
"""Research-only comparison of retrospective LOEO and chronological pre-event CALC.

This script does not change production ratings. It compares the retrospective cross-fit
with the production as-of-event chronology policy. Sources with verified per-match
timestamps use strict UTC ordering; sources with per-match calendar days use strict
day-before ordering (same-day matches are excluded when no clock is available). Legacy
archives containing only tournament-start dates use conservative maximum event windows
by source level (15 days for Grand Slams, 12 for Masters/ATP umbrella, 8 for Finals/500/250).

The target event's complete sample is included in its observed score. Only training
profiles and the normalisation anchor are restricted to information available before it.
"""
import argparse
import json
import math
import os
import random
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
    """Score an event with the same identity fallback and ratio path as production."""
    profile_coverage = compute_speed.ensure_event_profiles(event_rows, profiles, tour_rate)
    clusters = compute_speed.event_match_ratio_clusters(event_rows, profiles, tour_rate)
    ratios = [ratio for cluster in clusters for ratio in cluster]
    if not ratios:
        return None

    # Keep the production side diagnostics for auditability, but use the same
    # match-cluster ratio function as season_ratings() for the event raw score.
    details = compute_speed.event_side_diagnostics(
        event_rows,
        profiles,
        tour_rate,
        retain_per_tail=max(1, len(event_rows) * 2),
    )
    sides = details["sideDiagnostics"]
    if len(sides) != len(ratios):
        raise RuntimeError(
            "Research event-side diagnostics do not match production ratio coverage: "
            f"{len(sides)} diagnostic sides vs {len(ratios)} score sides"
        )

    nll = 0.0
    points = 0
    for side in sides:
        service_points = side["servicePoints"]
        aces = side["actualAces"]
        # The 0.5 expected-ace floor protects the ratio denominator only. It is
        # not a probability floor and must not be substituted into binomial log loss.
        expected = compute_speed.expected_calibrated_aces(
            side["serveRate"], side["returnerConcedeRate"], tour_rate, service_points
        )
        probability = expected / service_points
        probability = min(max(probability, 1e-9), 1.0 - 1e-9)
        nll -= aces * math.log(probability) + (service_points - aces) * math.log(1.0 - probability)
        points += service_points
    return {
        "raw": statistics.fmean(ratios),
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
        "tourPriorServerSideUses": profile_coverage["tour_prior_server_side_uses"],
        "tourPriorReturnerSideUses": profile_coverage["tour_prior_returner_side_uses"],
        "careerHistoryServerSides": profile_coverage["career_history_server_sides"],
        "careerHistoryReturnerSides": profile_coverage["career_history_returner_sides"],
    }


def completed_standard_event_ids_before_target(
    chronology_groups, target_event_id, target_start_date, target_start_timestamp,
    source_precision_by_event,
):
    """Return whole standard events provably finished before the target began.

    Event aliases may map several source tournament IDs into one app event ID. An
    event is admitted only when every source edition contributing to that app event
    is independently proven complete; this prevents a partial alias from making a
    combined cross-fit anchor look complete.
    """
    completed = set()
    for event_id, event_rows in chronology_groups.items():
        if event_id == target_event_id or event_id in compute_speed.CALC_TARGET_ONLY_EVENT_IDS:
            continue
        rows_by_source = defaultdict(list)
        for row in event_rows:
            source_key = compute_speed._source_event_key(row)
            if source_key:
                rows_by_source[source_key].append(row)
        if not rows_by_source:
            continue
        source_editions_complete = all(
            compute_speed._event_completion_precedes_target(
                source_rows,
                target_start_date,
                target_start_timestamp,
                match_day_precision=(
                    source_precision_by_event.get(source_key) == "match_day"
                ),
            )
            for source_key, source_rows in rows_by_source.items()
        )
        if source_editions_complete:
            completed.add(event_id)
    return completed


def cross_fitted_normalisation(completed_event_ids, event_ratings):
    """Mean independent event-specific raw scores for a completed anchor cohort.

    Each anchor event's raw score comes from its own production chronological LOEO
    profile and tour baseline, rather than reusing the target event's player profiles.
    The production stable-cohort rule (20 adjusted server sides) is applied again.
    This is a research-only comparator; it does not change production calculations.
    """
    candidates = {}
    for event_id in sorted(set(completed_event_ids)):
        if event_id in compute_speed.CALC_TARGET_ONLY_EVENT_IDS:
            continue
        item = event_ratings.get(event_id)
        if not item:
            continue
        diagnostics = item.get("calculationDiagnostics") or {}
        raw = diagnostics.get("rawAdjustedEventScore")
        sides = diagnostics.get("adjustedServerSides", item.get("adjustedServerSides"))
        matches = diagnostics.get("completedMatches", item.get("matches"))
        if (
            isinstance(raw, (int, float)) and math.isfinite(raw) and raw >= 0
            and isinstance(sides, (int, float)) and math.isfinite(sides) and sides > 0
        ):
            candidates[event_id] = {
                "raw": float(raw),
                "adjustedServerSides": int(sides),
                "matches": int(matches or 0),
            }

    stable = {
        event_id: item for event_id, item in candidates.items()
        if item["adjustedServerSides"] >= compute_speed.MIN_NORMALISER_SERVER_SIDES
    }
    selected = stable or candidates
    if not selected:
        return {
            "normalisation": None,
            "mode": "no_cross_fitted_completed_anchor",
            "eventIds": [],
            "candidateEventIds": [],
            "adjustedServerSides": 0,
            "matches": 0,
        }
    return {
        "normalisation": statistics.fmean(item["raw"] for item in selected.values()),
        "mode": (
            "cross_fitted_stable_completed_events"
            if stable else "cross_fitted_all_completed_events_fallback"
        ),
        "eventIds": sorted(selected),
        "candidateEventIds": sorted(candidates),
        "adjustedServerSides": sum(item["adjustedServerSides"] for item in selected.values()),
        "matches": sum(item["matches"] for item in selected.values()),
    }


def full_pipeline_crossfit_interval(
    year, rows, target_event_id, completed_anchor_event_ids,
    iterations=100, seed=1,
):
    """Bootstrap the full production chronology pipeline with an independent anchor.

    Match statistics are resampled within each tournament. A statistics-free copy of
    every source row is retained as a chronology skeleton so the original event start,
    final-round completion evidence, and source date precision do not disappear when
    a match is not selected in a bootstrap draw. Each draw reruns season_ratings(), so
    player profiles, target raw score, and every independent anchor raw score are refit.
    The completed-event ID set is held fixed to isolate rating uncertainty conditional
    on the original, source-verified chronology.
    """
    if not rows or iterations < 1:
        return None
    usable = compute_speed.calculation_eligible_rows(compute_speed.valid_rows(rows))
    by_event = defaultdict(list)
    for row in usable:
        event_id = compute_speed.tournament_id(row.get("tourney_name"))
        if event_id:
            by_event[event_id].append(row)
    if target_event_id not in by_event:
        return None

    chronology_skeleton = []
    for row in rows:
        stub = dict(row)
        for key in ("w_ace", "l_ace", "w_svpt", "l_svpt"):
            stub[key] = None
        chronology_skeleton.append(stub)

    rng = random.Random(seed)
    source = compute_speed.SOURCE_BY_YEAR.get(year)
    samples = []
    for _ in range(iterations):
        sampled_rows = list(chronology_skeleton)
        for matches in by_event.values():
            sampled_rows.extend(rng.choices(matches, k=len(matches)))
        ratings = compute_speed.season_ratings(year, sampled_rows, source=source)
        target = ratings.get(target_event_id)
        if not target:
            continue
        target_raw = (target.get("calculationDiagnostics") or {}).get("rawAdjustedEventScore")
        anchor = cross_fitted_normalisation(completed_anchor_event_ids, ratings)
        normalisation = anchor.get("normalisation")
        if (
            isinstance(target_raw, (int, float)) and math.isfinite(target_raw)
            and isinstance(normalisation, (int, float)) and math.isfinite(normalisation)
            and normalisation > 0
        ):
            samples.append(target_raw / normalisation)

    if len(samples) < max(30, int(iterations * 0.8)):
        return {
            "low": None,
            "high": None,
            "iterations": iterations,
            "successful": len(samples),
            "method": "full-pipeline-match-bootstrap-cross-fitted-anchor",
            "warning": "Too few successful bootstrap draws for a stable interval.",
        }
    samples.sort()

    def percentile(q):
        position = (len(samples) - 1) * q
        low, high = int(math.floor(position)), int(math.ceil(position))
        return samples[low] + (samples[high] - samples[low]) * (position - low)

    return {
        "low": percentile(0.025),
        "high": percentile(0.975),
        "iterations": iterations,
        "successful": len(samples),
        "method": "full-pipeline-match-bootstrap-cross-fitted-anchor",
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


def run(start_year, end_year, crossfit_enabled=False, bootstrap_iterations=0, detail_ids=None):
    year_results = []
    all_event_pairs = []
    detail_ids = set(detail_ids or [])
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
        production_ratings = (
            compute_speed.season_ratings(year, rows, source=source)
            if crossfit_enabled else {}
        )
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

            # Use the exact production chronology/profile helper, including its special
            # opening-event fallback to the completed previous season. Reimplementing this
            # selection in the study previously missed that fallback and created false
            # production-parity failures in the first event of a season.
            chrono_profiles, chrono_tour_rate, chronological_training = (
                compute_speed._chronological_profiles_and_anchor(
                    year,
                    rows,
                    start_date,
                    source_precision_by_event,
                    target_start_timestamp,
                )
            )

            if chrono_tour_rate is None or not 0.0 < chrono_tour_rate < 1.0:
                continue
            chronological = event_metrics(event_rows, chrono_profiles, chrono_tour_rate)
            if chronological is None:
                continue

            # Profile training may contain earlier rounds of a concurrent event, but the
            # normalisation anchor may contain completed event editions only.
            anchor_rows, anchor_mode, eligible_anchor = compute_speed._chronological_normalisation_anchor_rows(
                year, rows, event_id, start_date, chrono_profiles, chrono_tour_rate,
                target_start_timestamp, source_precision_by_event=source_precision_by_event,
            )
            chrono_anchor = (
                statistics.mean(value["raw"] for value in eligible_anchor.values())
                if eligible_anchor else chronological["raw"]
            )
            if not eligible_anchor:
                anchor_mode = "target_self_fallback_no_prior_anchor"

            retro_rating = retro["raw"] / retrospective_anchor
            chrono_rating = chronological["raw"] / chrono_anchor if chrono_anchor else None

            crossfit = None
            crossfit_anchor_ids = set()
            production_item = production_ratings.get(event_id) if crossfit_enabled else None
            production_diagnostics = (
                (production_item or {}).get("calculationDiagnostics") or {}
            )
            production_raw = production_diagnostics.get("rawAdjustedEventScore")
            production_normalisation = production_diagnostics.get("seasonNormalisation")
            production_rating = (production_item or {}).get("speed")
            production_parity = None
            crossfit_rating = None
            if crossfit_enabled:
                completed_anchor_ids = completed_standard_event_ids_before_target(
                    chronology_groups,
                    event_id,
                    start_date,
                    target_start_timestamp,
                    source_precision_by_event,
                )
                crossfit = cross_fitted_normalisation(completed_anchor_ids, production_ratings)
                crossfit_anchor_ids = set(completed_anchor_ids)
                if crossfit["normalisation"] is None and year > compute_speed.PROFILE_HISTORY_START_YEAR:
                    try:
                        prior_rows = compute_speed.load(year - 1)
                        prior_source = compute_speed.SOURCE_BY_YEAR.get(year - 1)
                        prior_independent_ratings = compute_speed.season_ratings(
                            year - 1, prior_rows, source=prior_source,
                        )
                        crossfit = cross_fitted_normalisation(
                            set(prior_independent_ratings), prior_independent_ratings,
                        )
                        if crossfit["normalisation"] is not None:
                            crossfit["mode"] = "cross_fitted_prior_season"
                            crossfit_anchor_ids = set(prior_independent_ratings)
                    except Exception as error:
                        crossfit["priorSeasonWarning"] = str(error)
                crossfit_raw = (
                    production_raw if isinstance(production_raw, (int, float))
                    and math.isfinite(production_raw) else chronological["raw"]
                )
                if crossfit["normalisation"] is None:
                    crossfit["normalisation"] = crossfit_raw
                    crossfit["mode"] = "target_self_fallback_no_prior_anchor"
                crossfit_rating = (
                    crossfit_raw / crossfit["normalisation"]
                    if crossfit["normalisation"] and crossfit["normalisation"] > 0 else None
                )
                production_parity = {
                    "rawScoreDifference": (
                        production_raw - chronological["raw"]
                        if isinstance(production_raw, (int, float)) else None
                    ),
                    "normalisationDifference": (
                        production_normalisation - chrono_anchor
                        if isinstance(production_normalisation, (int, float)) else None
                    ),
                    "ratingDifference": (
                        production_rating - chrono_rating
                        if isinstance(production_rating, (int, float)) and chrono_rating is not None
                        else None
                    ),
                    "rawScoreMatches": (
                        math.isclose(production_raw, chronological["raw"], rel_tol=0.0, abs_tol=1e-9)
                        if isinstance(production_raw, (int, float)) else False
                    ),
                    "normalisationMatches": (
                        math.isclose(production_normalisation, chrono_anchor, rel_tol=0.0, abs_tol=1e-9)
                        if isinstance(production_normalisation, (int, float)) else False
                    ),
                }
                if production_item is not None and not (
                    production_parity["rawScoreMatches"]
                    and production_parity["normalisationMatches"]
                ):
                    raise RuntimeError(
                        "Chronological research study does not reproduce production season_ratings() "
                        f"for {year} {event_id}: {production_parity}"
                    )

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
                    "normalisationAnchorMode": anchor_mode,
                    "normalisationAnchorEvents": len(eligible_anchor),
                    "normalisationAnchorMatches": len(anchor_rows),
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
            if crossfit_enabled:
                event["productionParity"] = production_parity
                event["crossFitted"] = {
                    "rating": crossfit_rating,
                    "raw": (
                        production_raw if isinstance(production_raw, (int, float))
                        and math.isfinite(production_raw) else chronological["raw"]
                    ),
                    "normalisation": crossfit["normalisation"],
                    "normalisationAnchorMode": crossfit["mode"],
                    "normalisationAnchorEvents": len(crossfit["eventIds"]),
                    "normalisationAnchorMatches": crossfit["matches"],
                    "normalisationAnchorServerSides": crossfit["adjustedServerSides"],
                    "normalisationAnchorEventIds": crossfit["eventIds"],
                    "normalisationCandidateEventIds": crossfit["candidateEventIds"],
                }
                if bootstrap_iterations > 0 and event_id in detail_ids and year == end_year:
                    event["crossFitted"]["fullPipelineBootstrap95"] = (
                        full_pipeline_crossfit_interval(
                            year,
                            rows,
                            event_id,
                            crossfit_anchor_ids,
                            iterations=bootstrap_iterations,
                            seed=90000 + year + sum(ord(char) for char in event_id),
                        )
                    )
            event["ratingDeltaChronologicalMinusRetrospective"] = (
                chrono_rating - retro_rating if chrono_rating is not None else None
            )
            event_items.append(event)
            all_event_pairs.append(event)

        retro_scores = {item["event"]: item["retrospective"]["rating"] for item in event_items}
        chrono_scores = {item["event"]: item["chronological"]["rating"] for item in event_items}
        crossfit_scores = {
            item["event"]: item["crossFitted"]["rating"]
            for item in event_items
            if item.get("crossFitted") and item["crossFitted"].get("rating") is not None
        }
        crossfit_deltas = [
            crossfit_scores[event_id] - chrono_scores[event_id]
            for event_id in set(crossfit_scores) & set(chrono_scores)
        ]
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
            "crossFittedSpearmanVsProductionChronological": (
                spearman_rank(chrono_scores, crossfit_scores) if crossfit_scores else None
            ),
            "crossFittedMeanAbsoluteRatingDelta": (
                statistics.mean(abs(value) for value in crossfit_deltas) if crossfit_deltas else None
            ),
            "crossFittedMedianAbsoluteRatingDelta": (
                statistics.median(abs(value) for value in crossfit_deltas) if crossfit_deltas else None
            ),
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
    crossfit_pairs = [
        (item["chronological"]["rating"], item["crossFitted"]["rating"])
        for item in all_event_pairs
        if item.get("crossFitted") and item["crossFitted"].get("rating") is not None
    ]
    crossfit_delta_values = [right - left for left, right in crossfit_pairs]
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
        "crossFittedEventsCompared": len(crossfit_pairs),
        "crossFittedMeanAbsoluteRatingDelta": (
            statistics.mean(abs(value) for value in crossfit_delta_values)
            if crossfit_delta_values else None
        ),
        "crossFittedMedianAbsoluteRatingDelta": (
            statistics.median(abs(value) for value in crossfit_delta_values)
            if crossfit_delta_values else None
        ),
        "crossFittedSpearmanVsProductionChronological": (
            spearman_rank(
                {item["event"]: item["chronological"]["rating"] for item in all_event_pairs
                 if item.get("crossFitted") and item["crossFitted"].get("rating") is not None},
                {item["event"]: item["crossFitted"]["rating"] for item in all_event_pairs
                 if item.get("crossFitted") and item["crossFitted"].get("rating") is not None},
            ) if crossfit_pairs else None
        ),
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
    parser.add_argument("--crossfit", action="store_true", help="add independent event-specific normalisation diagnostics")
    parser.add_argument("--bootstrap", type=int, default=0, help="full-pipeline match bootstrap draws for selected --detail events")
    parser.add_argument("--detail", default="rio", help="comma-separated event IDs for optional cross-fit bootstrap")
    args = parser.parse_args()
    if args.start_year < compute_speed.PROFILE_HISTORY_START_YEAR:
        parser.error(f"start year must be >= {compute_speed.PROFILE_HISTORY_START_YEAR}")
    if args.end_year < args.start_year:
        parser.error("end year must be >= start year")

    if args.bootstrap < 0:
        parser.error("--bootstrap must be non-negative")
    detail_ids = {item.strip() for item in args.detail.split(",") if item.strip()}
    report = run(args.start_year, args.end_year, crossfit_enabled=args.crossfit or args.bootstrap > 0,
                 bootstrap_iterations=args.bootstrap, detail_ids=detail_ids)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Research report written to {output}")


if __name__ == "__main__":
    main()
