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


def model_profile_coverage(all_rows, event_id):
    """Measure current-season LOEO profile coverage and identify missing profile inputs."""
    if not event_id:
        return {
            "rateable_server_sides": None,
            "missing_server_profile": None,
            "missing_returner_profile": None,
            "missing_both_profiles": None,
            "invalid_expected_aces": None,
            "missing_profile_sides": [],
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
    profiles = compute_speed.player_rates(
        training_rows,
        prior_points=compute_speed.PROFILE_PRIOR_SERVICE_POINTS,
    )
    tour_rate = compute_speed.tour_average_ace_rate(training_rows)
    counts = Counter()
    missing_sides = []

    if tour_rate is None or not 0.0 < tour_rate < 1.0:
        return {
            "rateable_server_sides": 0,
            "missing_server_profile": None,
            "missing_returner_profile": None,
            "missing_both_profiles": None,
            "invalid_expected_aces": None,
            "missing_profile_sides": [],
            "reason": "invalid_leave_one_event_out_tour_rate",
        }

    for row in target_rows:
        for server_key, returner_key, service_points_key, aces_key in (
            ("winner_id", "loser_id", "w_svpt", "w_ace"),
            ("loser_id", "winner_id", "l_svpt", "l_ace"),
        ):
            server = row[server_key]
            returner = row[returner_key]
            serve_rate = profiles.get(server, {}).get("serve_rate")
            concede_rate = profiles.get(returner, {}).get("concede_rate")
            if serve_rate is None or concede_rate is None:
                reason = (
                    "missing_both_profiles" if serve_rate is None and concede_rate is None
                    else "missing_server_profile" if serve_rate is None
                    else "missing_returner_profile"
                )
                counts[reason] += 1
                missing_sides.append({
                    "match_id": row.get("match_id", ""),
                    "round": row.get("round", ""),
                    "server_id": server,
                    "server_name": row.get("winner_name" if server_key == "winner_id" else "loser_name", ""),
                    "returner_id": returner,
                    "returner_name": row.get("loser_name" if returner_key == "loser_id" else "winner_name", ""),
                    "missing": reason,
                    "server_service_points": number(row, service_points_key),
                    "server_aces": number(row, aces_key),
                })
                continue
            expected = compute_speed.expected_calibrated_aces(
                serve_rate, concede_rate, tour_rate, number(row, service_points_key)
            )
            if not math.isfinite(expected) or expected <= compute_speed.MIN_EXPECTED_ACES:
                counts["invalid_expected_aces"] += 1
                continue
            counts["rateable_server_sides"] += 1

    return {
        "rateable_server_sides": int(counts["rateable_server_sides"]),
        "missing_server_profile": int(counts["missing_server_profile"]),
        "missing_returner_profile": int(counts["missing_returner_profile"]),
        "missing_both_profiles": int(counts["missing_both_profiles"]),
        "invalid_expected_aces": int(counts["invalid_expected_aces"]),
        "missing_profile_sides": missing_sides,
        "reason": None,
    }


def main():
    parser = argparse.ArgumentParser(
        description="Reconcile a tournament's match rows against the calculation pipeline's eligibility rules."
    )
    parser.add_argument("--event", default="Montpellier")
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
    model_coverage = model_profile_coverage(rows, event_id)
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
