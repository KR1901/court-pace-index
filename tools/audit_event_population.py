#!/usr/bin/env python3
"""Create a local, non-redistributive match-population audit for an ATP event.

Reads the same source snapshot and reproduces compute_speed.valid_rows eligibility.
It emits match-level reconciliation metadata and aggregate totals, never copies the
source CSV or raw response payloads into the repository.

Run locally:
  python3 tools/audit_event_population.py --event Montpellier --season 2026
Require the current-season TennisMyLife source:
  python3 tools/audit_event_population.py --event Montpellier --season 2026 \
    --require-source "TennisMyLife ATP Match Database"
"""
import argparse
import csv
import json
import math
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compute_speed  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]


def number(row, key):
    return compute_speed.num(row.get(key))


def reason(row):
    """Return the first exclusion reason using compute_speed.valid_rows rules/order."""
    if row.get("tourney_level") not in compute_speed.LEVELS:
        return "ineligible_tournament_level"

    vals = [number(row, key) for key in ("w_svpt", "l_svpt", "w_ace", "l_ace")]
    if any(value is None or not math.isfinite(value) for value in vals):
        return "missing_or_non_numeric_serve_stats"

    wsv, lsv, wace, lace = vals
    if wsv <= 0 or lsv <= 0 or wace < 0 or lace < 0:
        return "non_positive_points_or_negative_aces"
    if wace > wsv or lace > lsv:
        return "aces_exceed_service_points"
    if not row.get("winner_id") or not row.get("loser_id") or row["winner_id"] == row["loser_id"]:
        return "missing_or_conflicting_player_ids"
    return None


def model_profile_coverage(all_rows, event_id, season=None):
    """Use the production long-history profile builder and explicit neutral prior fallback."""
    if not event_id:
        return {
            "rateable_server_sides": None,
            "tour_prior_sides": None,
            "missing_server_profile_before_fallback": None,
            "missing_returner_profile_before_fallback": None,
            "missing_both_profiles_before_fallback": None,
        }

    usable = compute_speed.valid_rows(all_rows)
    target_rows = [
        row for row in usable
        if compute_speed.NAMES.get((row.get("tourney_name") or "").strip()) == event_id
    ]
    training_rows = [
        row for row in usable
        if compute_speed.NAMES.get((row.get("tourney_name") or "").strip()) != event_id
    ]
    season = season or datetime.now().year
    current_profiles = compute_speed.player_rates(
        training_rows,
        prior_points=compute_speed.PROFILE_PRIOR_SERVICE_POINTS,
    )
    profiles = compute_speed.build_live_profiles(season, current_profiles)
    tour_rate = compute_speed.tour_average_ace_rate(training_rows)
    if tour_rate is None or not 0.0 < tour_rate < 1.0:
        return {
            "rateable_server_sides": 0,
            "tour_prior_sides": None,
            "reason": "invalid_leave_one_event_out_tour_rate",
        }

    coverage = compute_speed.ensure_event_profiles(target_rows, profiles, tour_rate)
    return {
        **coverage,
        "profile_database_players": len(profiles),
        "profile_training_matches": len(training_rows),
        "profile_history_start_year": compute_speed.PROFILE_HISTORY_START_YEAR,
        "profile_history_end_year": season,
        "profile_history_decay": compute_speed.LIVE_PROFILE_HISTORY_DECAY,
        "profile_source_window_years": compute_speed.LIVE_PROFILE_FALLBACK_SEASONS,
        "profile_match_key": "unique normalised full name across TML/Sackmann IDs",
    }


def main():
    parser = argparse.ArgumentParser(
        description="Reconcile a tournament's match rows against the calculation pipeline's eligibility rules."
    )
    parser.add_argument("--event", default="Montpellier")
    parser.add_argument(
        "--all-events",
        action="store_true",
        help="Audit profile coverage for every mapped ATP event in the loaded season source.",
    )
    parser.add_argument("--season", type=int, default=datetime.now().year)
    parser.add_argument("--out", default="build/event-population-audit")
    parser.add_argument(
        "--require-source",
        help="Fail unless the loaded source label contains this text (case-insensitive).",
    )
    args = parser.parse_args()

    rows = compute_speed.load(args.season)
    source_label = compute_speed.SOURCE_BY_YEAR.get(args.season, "unknown")
    if args.require_source and args.require_source.casefold() not in source_label.casefold():
        raise SystemExit(
            f"Required source {args.require_source!r}, but compute_speed.load({args.season}) "
            f"selected {source_label!r}. No population reconciliation was produced."
        )

    if args.all_events:
        mapped_event_ids = sorted({
            event_id
            for row in rows
            if (event_id := compute_speed.NAMES.get((row.get("tourney_name") or "").strip()))
        })
        if not mapped_event_ids:
            raise SystemExit(f"No mapped ATP events found in the {args.season} source.")

        usable_rows = compute_speed.valid_rows(rows)
        coverage_records = []
        totals = Counter()
        fallback_reason_totals = Counter()
        fallback_role_totals = Counter()
        fallback_player_ids = set()
        fallback_player_role_keys = set()
        event_names_by_id = {}
        raw_rows_by_id = Counter()
        for row in rows:
            event_id = compute_speed.NAMES.get((row.get("tourney_name") or "").strip())
            if event_id:
                raw_rows_by_id[event_id] += 1
                event_names_by_id.setdefault(event_id, set()).add(row.get("tourney_name") or "")

        for event_id in mapped_event_ids:
            raw_event_rows = [
                row for row in rows
                if compute_speed.NAMES.get((row.get("tourney_name") or "").strip()) == event_id
            ]
            eligible_rows = [
                row for row in usable_rows
                if compute_speed.NAMES.get((row.get("tourney_name") or "").strip()) == event_id
            ]
            eligible_sides = len(eligible_rows) * 2
            coverage = model_profile_coverage(rows, event_id, args.season)
            fallback_details = coverage.get("tour_prior_fallback_details") or []
            fallback_reasons = Counter(detail.get("reason") for detail in fallback_details)
            fallback_roles = Counter(detail.get("role") for detail in fallback_details)
            fallback_role_keys = {
                (str(detail.get("player_id") or detail.get("player_name") or ""), str(detail.get("role") or ""))
                for detail in fallback_details
                if detail.get("player_id") or detail.get("player_name")
            }
            fallback_reason_totals.update(fallback_reasons)
            fallback_role_totals.update(fallback_roles)
            fallback_player_ids.update(
                str(detail.get("player_id"))
                for detail in fallback_details
                if detail.get("player_id")
            )
            fallback_player_role_keys.update(fallback_role_keys)
            rateable_sides = int(coverage.get("rateable_server_sides") or 0)
            aces = sum(number(row, "w_ace") + number(row, "l_ace") for row in eligible_rows)
            points = sum(number(row, "w_svpt") + number(row, "l_svpt") for row in eligible_rows)
            gap_sides = max(0, eligible_sides - rateable_sides)
            labels = sorted(name for name in event_names_by_id.get(event_id, set()) if name)
            record = {
                "season": args.season,
                "event_id": event_id,
                "event_names": "; ".join(labels),
                "raw_event_rows": len(raw_event_rows),
                "eligible_matches": len(eligible_rows),
                "eligible_server_sides": eligible_sides,
                "rateable_server_sides": rateable_sides,
                "unrateable_server_sides": gap_sides,
                "tour_prior_sides": int(coverage.get("tour_prior_sides") or 0),
                "tour_prior_server_profiles": int(coverage.get("tour_prior_server_profiles") or 0),
                "tour_prior_returner_profiles": int(coverage.get("tour_prior_returner_profiles") or 0),
                "fallback_profile_exists_without_rate": int(
                    fallback_reasons["player_profile_exists_without_rate"]
                ),
                "fallback_no_player_profile_in_history": int(
                    fallback_reasons["no_player_profile_in_history"]
                ),
                "fallback_server_role_occurrences": int(fallback_roles["server"]),
                "fallback_returner_role_occurrences": int(fallback_roles["returner"]),
                "fallback_distinct_player_roles": len(fallback_role_keys),
                "missing_server_profiles_before_fallback": int(
                    coverage.get("missing_server_profile_before_fallback") or 0
                ),
                "missing_returner_profiles_before_fallback": int(
                    coverage.get("missing_returner_profile_before_fallback") or 0
                ),
                "missing_both_profiles_before_fallback": int(
                    coverage.get("missing_both_profiles_before_fallback") or 0
                ),
                "career_history_server_sides": int(coverage.get("career_history_server_sides") or 0),
                "career_history_returner_sides": int(coverage.get("career_history_returner_sides") or 0),
                "profile_database_players": int(coverage.get("profile_database_players") or 0),
                "total_aces": aces,
                "total_service_points": points,
                "ace_rate_pct": round(100 * aces / points, 4) if points else None,
            }
            coverage_records.append(record)
            totals["events"] += 1
            totals["raw_event_rows"] += len(raw_event_rows)
            totals["eligible_matches"] += len(eligible_rows)
            totals["eligible_server_sides"] += eligible_sides
            totals["rateable_server_sides"] += rateable_sides
            totals["unrateable_server_sides"] += gap_sides
            totals["tour_prior_sides"] += record["tour_prior_sides"]
            totals["fallback_profile_exists_without_rate"] += record["fallback_profile_exists_without_rate"]
            totals["fallback_no_player_profile_in_history"] += record["fallback_no_player_profile_in_history"]
            totals["fallback_server_role_occurrences"] += record["fallback_server_role_occurrences"]
            totals["fallback_returner_role_occurrences"] += record["fallback_returner_role_occurrences"]
            totals["events_with_unrateable_sides"] += gap_sides > 0
            totals["events_with_tour_prior_sides"] += record["tour_prior_sides"] > 0

        metadata = compute_speed.SOURCE_METADATA_BY_YEAR.get(args.season, {})
        summary = {
            "generated_utc": datetime.now(timezone.utc).isoformat(),
            "season": args.season,
            "source": source_label,
            "source_metadata": {
                key: metadata.get(key)
                for key in ("url", "fetchedAtUtc", "latestMatchDate", "matchRows", "bytes", "sha256")
            },
            "scope": "all mapped ATP tour events in the selected season source",
            "event_count": int(totals["events"]),
            "raw_event_rows": int(totals["raw_event_rows"]),
            "eligible_matches": int(totals["eligible_matches"]),
            "eligible_server_sides": int(totals["eligible_server_sides"]),
            "rateable_server_sides": int(totals["rateable_server_sides"]),
            "unrateable_server_sides": int(totals["unrateable_server_sides"]),
            "events_with_unrateable_sides": int(totals["events_with_unrateable_sides"]),
            "tour_prior_sides": int(totals["tour_prior_sides"]),
            "events_with_tour_prior_sides": int(totals["events_with_tour_prior_sides"]),
            "fallback_diagnostics": {
                "profile_exists_without_rate_occurrences": int(
                    fallback_reason_totals["player_profile_exists_without_rate"]
                ),
                "no_player_profile_in_history_occurrences": int(
                    fallback_reason_totals["no_player_profile_in_history"]
                ),
                "server_role_fallback_occurrences": int(fallback_role_totals["server"]),
                "returner_role_fallback_occurrences": int(fallback_role_totals["returner"]),
                "distinct_fallback_player_ids": len(fallback_player_ids),
                "distinct_fallback_player_role_keys": len(fallback_player_role_keys),
                "player_names_and_ids_published": False,
            },
            "profile_history_start_year": compute_speed.PROFILE_HISTORY_START_YEAR,
            "profile_history_window_years": compute_speed.LIVE_PROFILE_FALLBACK_SEASONS,
            "profile_history_decay": compute_speed.LIVE_PROFILE_HISTORY_DECAY,
            "id_crosswalk": "unique normalized full name only; ambiguous names not merged",
            "events": coverage_records,
            "raw_source_csv_included": False,
        }
        output = ROOT / args.out
        output.mkdir(parents=True, exist_ok=True)
        with (output / "event-profile-coverage.csv").open(
            "w", newline="", encoding="utf-8"
        ) as file:
            writer = csv.DictWriter(file, fieldnames=list(coverage_records[0]))
            writer.writeheader()
            writer.writerows(coverage_records)
        (output / "summary.json").write_text(
            json.dumps(summary, indent=2) + "\\n", encoding="utf-8"
        )
        print(json.dumps(summary, indent=2))
        return

    event_rows = [
        row for row in rows
        if (row.get("tourney_name") or "").strip().casefold() == args.event.strip().casefold()
    ]
    if not event_rows:
        raise SystemExit(
            f"No exact event-name match for {args.event!r} in {args.season} source; "
            "check the source event label."
        )

    records, totals, excluded = [], Counter(), Counter()
    for row in event_rows:
        why = reason(row)
        wsv, lsv, wace, lace = (number(row, key) for key in ("w_svpt", "l_svpt", "w_ace", "l_ace"))
        if why:
            excluded[why] += 1
        else:
            totals["eligible_matches"] += 1
            totals["winner_aces"] += wace
            totals["loser_aces"] += lace
            totals["winner_service_points"] += wsv
            totals["loser_service_points"] += lsv
            totals["eligible_server_sides"] += 2

        records.append({
            "season": args.season,
            "event": args.event,
            "source_event_name": row.get("tourney_name", ""),
            "tourney_level": row.get("tourney_level", ""),
            "tourney_date": row.get("tourney_date", ""),
            "round": row.get("round", ""),
            "match_id": row.get("match_id", ""),
            "winner_id": row.get("winner_id", ""),
            "loser_id": row.get("loser_id", ""),
            "winner": row.get("winner_name", ""),
            "loser": row.get("loser_name", ""),
            "score": row.get("score", ""),
            "eligible": not bool(why),
            "exclusion_reason": why or "",
            "winner_aces": wace if not why else "",
            "loser_aces": lace if not why else "",
            "winner_service_points": wsv if not why else "",
            "loser_service_points": lsv if not why else "",
        })

    aces = totals["winner_aces"] + totals["loser_aces"]
    points = totals["winner_service_points"] + totals["loser_service_points"]
    event_id = compute_speed.NAMES.get((event_rows[0].get("tourney_name") or "").strip())
    model_coverage = model_profile_coverage(rows, event_id, args.season)
    metadata = compute_speed.SOURCE_METADATA_BY_YEAR.get(args.season, {})
    summary = {
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "season": args.season,
        "requested_event": args.event,
        "source_event_name": event_rows[0].get("tourney_name"),
        "source": source_label,
        "source_metadata": {
            key: metadata.get(key)
            for key in ("url", "fetchedAtUtc", "latestMatchDate", "matchRows", "bytes", "sha256")
        },
        "raw_event_rows": len(event_rows),
        "eligible_matches": int(totals["eligible_matches"]),
        "eligible_server_sides": int(totals["eligible_server_sides"]),
        "model_profile_coverage": model_coverage,
        "excluded_rows": int(sum(excluded.values())),
        "total_aces": aces,
        "total_service_points": points,
        "ace_rate_pct": round(100 * aces / points, 4) if points else None,
        "exclusions": dict(excluded),
        "scope_note": (
            "This describes only the source file loaded by compute_speed.py; "
            "it does not establish Tennis Abstract's match population."
        ),
        "raw_source_csv_included": False,
    }

    output = ROOT / args.out
    output.mkdir(parents=True, exist_ok=True)
    with (output / "match-population.csv").open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=list(records[0]))
        writer.writeheader()
        writer.writerows(records)
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
