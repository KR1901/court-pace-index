#!/usr/bin/env python3
"""Create a local, non-redistributive match-population audit for an ATP event.

Reads the same source snapshot and eligibility rules as compute_speed.py. It emits
match-level reconciliation metadata and aggregate totals, never copies the source CSV
or raw response payloads into the repository.

Run locally:
  python3 tools/audit_event_population.py --event Montpellier --season 2026
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
    if row.get("tourney_level") not in compute_speed.LEVELS:
        return "ineligible_tournament_level"
    if not row.get("winner_id") or not row.get("loser_id") or row.get("winner_id") == row.get("loser_id"):
        return "missing_or_conflicting_player_ids"
    vals = [number(row, k) for k in ("w_svpt", "l_svpt", "w_ace", "l_ace")]
    if any(v is None or not math.isfinite(v) for v in vals):
        return "missing_or_non_numeric_serve_stats"
    wsv, lsv, wace, lace = vals
    if wsv <= 0 or lsv <= 0:
        return "non_positive_service_points"
    if wace < 0 or lace < 0 or wace > wsv or lace > lsv:
        return "invalid_ace_counts"
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--event", default="Montpellier")
    parser.add_argument("--season", type=int, default=datetime.now().year)
    parser.add_argument("--out", default="build/event-population-audit")
    args = parser.parse_args()

    rows = compute_speed.load(args.season)
    event_rows = [r for r in rows if (r.get("tourney_name") or "").strip().casefold() == args.event.strip().casefold()]
    if not event_rows:
        raise SystemExit(f"No exact event-name match for {args.event!r} in {args.season} source; check source event label.")

    records, totals, excluded = [], Counter(), Counter()
    for row in event_rows:
        why = reason(row)
        wsv, lsv, wace, lace = (number(row, k) for k in ("w_svpt", "l_svpt", "w_ace", "l_ace"))
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
            "season": args.season, "event": args.event,
            "source_event_name": row.get("tourney_name", ""),
            "tourney_level": row.get("tourney_level", ""),
            "tourney_date": row.get("tourney_date", ""), "round": row.get("round", ""),
            "match_id": row.get("match_id", ""),
            "winner_id": row.get("winner_id", ""), "loser_id": row.get("loser_id", ""),
            "winner": row.get("winner_name", ""), "loser": row.get("loser_name", ""),
            "score": row.get("score", ""), "eligible": not bool(why),
            "exclusion_reason": why or "",
            "winner_aces": wace if not why else "", "loser_aces": lace if not why else "",
            "winner_service_points": wsv if not why else "", "loser_service_points": lsv if not why else "",
        })

    aces = totals["winner_aces"] + totals["loser_aces"]
    points = totals["winner_service_points"] + totals["loser_service_points"]
    meta = compute_speed.SOURCE_METADATA_BY_YEAR.get(args.season, {})
    summary = {
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "season": args.season, "requested_event": args.event,
        "source_event_name": event_rows[0].get("tourney_name"),
        "source": compute_speed.SOURCE_BY_YEAR.get(args.season, "unknown"),
        "source_metadata": {k: meta.get(k) for k in ("url", "fetchedAtUtc", "latestMatchDate", "matchRows", "bytes", "sha256")},
        "raw_event_rows": len(event_rows),
        "eligible_matches": int(totals["eligible_matches"]),
        "eligible_server_sides": int(totals["eligible_server_sides"]),
        "total_aces": aces, "total_service_points": points,
        "ace_rate_pct": round(100 * aces / points, 4) if points else None,
        "exclusions": dict(excluded),
        "scope_note": "This describes only the source file loaded by compute_speed.py; it does not establish Tennis Abstract's match population.",
        "raw_source_csv_included": False,
    }

    out = ROOT / args.out
    out.mkdir(parents=True, exist_ok=True)
    with (out / "match-population.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(records[0]))
        writer.writeheader()
        writer.writerows(records)
    (out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
