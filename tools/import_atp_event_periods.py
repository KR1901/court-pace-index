#!/usr/bin/env python3
"""Import a permissioned ATP event-period CSV export into the local JSON registry.

This tool does not connect to ATP or retrieve website content. The CSV must be obtained
through an ATP-authorised feed/export or another source whose collection and reuse are
authorised. See docs/ATP_EVENT_PERIODS_MIGRATION.md.
"""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

from atp_event_periods import DEFAULT_REGISTRY_PATH, EventPeriodError, load_event_periods, validate_record

FIELDS = [
    "season", "eventId", "tournamentId", "sourceEventIds", "startDate", "endDate",
    "timeZone", "sourceUrl", "sourceKind", "dateStatus", "verifiedAt",
]


def read_csv(path: Path) -> list[dict]:
    records = []
    with path.open(newline="", encoding="utf-8-sig") as handle:
        reader = csv.DictReader(handle)
        missing = sorted(set(FIELDS) - set(reader.fieldnames or []))
        if missing:
            raise EventPeriodError("CSV missing columns: " + ", ".join(missing))
        for line_number, row in enumerate(reader, start=2):
            raw_ids = (row.get("sourceEventIds") or "").strip()
            try:
                if raw_ids.startswith("["):
                    source_ids = json.loads(raw_ids)
                else:
                    source_ids = [value.strip() for value in raw_ids.replace("|", ";").split(";") if value.strip()]
                record = {
                    "season": int(row["season"]),
                    "eventId": row["eventId"].strip(),
                    "tournamentId": row["tournamentId"].strip(),
                    "sourceEventIds": source_ids,
                    "startDate": row["startDate"].strip(),
                    "endDate": row["endDate"].strip(),
                    "timeZone": row["timeZone"].strip(),
                    "sourceUrl": row["sourceUrl"].strip(),
                    "sourceKind": row["sourceKind"].strip(),
                    "dateStatus": row["dateStatus"].strip(),
                    "verifiedAt": row["verifiedAt"].strip(),
                }
                validate_record(record)
            except (ValueError, TypeError, KeyError, json.JSONDecodeError, EventPeriodError) as exc:
                raise EventPeriodError(f"CSV line {line_number}: {exc}") from exc
            records.append(record)
    return records


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="CSV export obtained under ATP permission")
    parser.add_argument("--output", type=Path, default=DEFAULT_REGISTRY_PATH)
    parser.add_argument("--replace", action="store_true", help="replace the registry rather than merge")
    args = parser.parse_args()

    incoming = read_csv(args.input)
    if args.replace:
        merged = incoming
    else:
        if args.output.exists():
            existing = json.loads(args.output.read_text(encoding="utf-8"))
            merged = list(existing.get("records", []))
        else:
            merged = []
        by_event = {(int(row["season"]), str(row["eventId"])): row for row in merged}
        for row in incoming:
            by_event[(int(row["season"]), str(row["eventId"]))] = row
        merged = list(by_event.values())

    # Validate global source-ID uniqueness and all time-zone/date relationships before write.
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temp_path = args.output.with_suffix(args.output.suffix + ".tmp")
    temp_path.write_text(
        json.dumps({"schemaVersion": 1, "records": merged}, indent=2) + "\n",
        encoding="utf-8",
    )
    try:
        load_event_periods(temp_path, require_non_empty=True)
    except Exception:
        temp_path.unlink(missing_ok=True)
        raise
    temp_path.replace(args.output)
    print(f"Imported {len(incoming)} authorised event periods; registry now has {len(merged)} records at {args.output}")


if __name__ == "__main__":
    main()
