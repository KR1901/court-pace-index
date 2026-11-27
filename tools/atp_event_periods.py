"""Validated registry of official ATP tournament edition date periods.

This module deliberately performs no network requests. Populate the registry only from
an ATP-authorised feed/export or another delivery whose collection and reuse are authorised.
"""
from __future__ import annotations

import json
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from urllib.parse import urlparse
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

DEFAULT_REGISTRY_PATH = Path(__file__).resolve().parents[1] / "data" / "atp_event_periods.json"
REQUIRED_FIELDS = {
    "season", "eventId", "tournamentId", "sourceEventIds", "startDate", "endDate",
    "timeZone", "sourceUrl", "sourceKind", "dateStatus", "verifiedAt",
}


MULTI_LOCATION_TIMEZONE = "MULTI_LOCATION"


class EventPeriodError(ValueError):
    """Invalid or incomplete official event-period registry."""


def _parse_date(value: object, field: str) -> date:
    try:
        parsed = date.fromisoformat(str(value))
    except (TypeError, ValueError) as exc:
        raise EventPeriodError(f"{field} must be an ISO date: {value!r}") from exc
    if parsed.isoformat() != str(value):
        raise EventPeriodError(f"{field} must use YYYY-MM-DD: {value!r}")
    return parsed


def _utc_boundary(day: date, zone: ZoneInfo) -> datetime:
    # ATP Results Archive periods are inclusive venue-local calendar dates. Convert
    # local midnight boundaries with the event's IANA zone; never use machine-local TZ.
    local = datetime.combine(day, time.min, tzinfo=zone)
    return local.astimezone(timezone.utc)


def validate_record(raw: object) -> dict:
    if not isinstance(raw, dict):
        raise EventPeriodError("each event-period record must be an object")
    missing = sorted(REQUIRED_FIELDS - set(raw))
    if missing:
        raise EventPeriodError(f"event-period record missing fields: {', '.join(missing)}")

    season = raw.get("season")
    if not isinstance(season, int) or isinstance(season, bool) or season < 1991:
        raise EventPeriodError("season must be an integer >= 1991")

    event_id = str(raw.get("eventId") or "").strip()
    tournament_id = str(raw.get("tournamentId") or "").strip()
    if not event_id or not tournament_id:
        raise EventPeriodError("eventId and tournamentId must be non-empty")

    source_ids = raw.get("sourceEventIds")
    if not isinstance(source_ids, list) or not source_ids:
        raise EventPeriodError("sourceEventIds must be a non-empty list")
    source_ids = [str(value).strip() for value in source_ids]
    if any(not value for value in source_ids) or len(set(source_ids)) != len(source_ids):
        raise EventPeriodError("sourceEventIds must contain unique non-empty strings")

    start_day = _parse_date(raw.get("startDate"), "startDate")
    end_day = _parse_date(raw.get("endDate"), "endDate")
    verified_day = _parse_date(raw.get("verifiedAt"), "verifiedAt")
    if start_day > end_day:
        raise EventPeriodError("startDate must not be after endDate")

    zone_name = str(raw.get("timeZone") or "").strip()
    if zone_name == MULTI_LOCATION_TIMEZONE:
        # ATP publishes some team-event windows as "Multiple Locations". There is no
        # single venue timezone. Bound all IANA civil zones conservatively: UTC+14 is
        # the earliest possible local midnight, UTC-12 the latest possible day-end.
        zone = None
    else:
        try:
            zone = ZoneInfo(zone_name)
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise EventPeriodError(f"unknown IANA timeZone: {zone_name!r}") from exc

    source_url = str(raw.get("sourceUrl") or "").strip()
    parsed_url = urlparse(source_url)
    if parsed_url.scheme != "https" or parsed_url.hostname not in {"atptour.com", "www.atptour.com"}:
        raise EventPeriodError("sourceUrl must be an HTTPS ATP Tour URL")

    supporting_source_url = str(raw.get("supportingSourceUrl") or "").strip()
    if supporting_source_url:
        supporting_parsed = urlparse(supporting_source_url)
        if (
            supporting_parsed.scheme != "https"
            or supporting_parsed.hostname not in {"itftennis.com", "www.itftennis.com"}
        ):
            raise EventPeriodError("supportingSourceUrl must be an HTTPS official ITF URL")
        if not str(raw.get("dateEvidenceNote") or "").strip():
            raise EventPeriodError("dateEvidenceNote is required when supportingSourceUrl is supplied")

    source_kind = str(raw.get("sourceKind") or "").strip()
    date_status = str(raw.get("dateStatus") or "").strip()
    if (source_kind, date_status) not in {
        ("results_archive", "actual"),
        ("calendar", "scheduled"),
    }:
        raise EventPeriodError(
            "sourceKind/dateStatus must be results_archive/actual or calendar/scheduled"
        )

    if zone is None:
        start_utc = datetime.combine(start_day, time.min, tzinfo=timezone.utc) - timedelta(hours=14)
        end_utc_exclusive = (
            datetime.combine(end_day + timedelta(days=1), time.min, tzinfo=timezone.utc)
            + timedelta(hours=12)
        )
    else:
        start_utc = _utc_boundary(start_day, zone)
        end_utc_exclusive = _utc_boundary(end_day + timedelta(days=1), zone)
    if end_utc_exclusive <= start_utc:
        raise EventPeriodError("event period must have positive duration")

    return {
        **raw,
        "season": season,
        "eventId": event_id,
        "tournamentId": tournament_id,
        "sourceEventIds": source_ids,
        "startDate": start_day.isoformat(),
        "endDate": end_day.isoformat(),
        "verifiedAt": verified_day.isoformat(),
        "timeZone": zone_name,
        "sourceKind": source_kind,
        "dateStatus": date_status,
        "sourceUrl": source_url,
        "_start_utc": start_utc,
        "_end_utc_exclusive": end_utc_exclusive,
    }


def load_event_periods(path: str | Path | None = None, *, require_non_empty: bool = False) -> dict:
    registry_path = Path(path) if path is not None else DEFAULT_REGISTRY_PATH
    try:
        payload = json.loads(registry_path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise EventPeriodError(f"official event-period registry is missing: {registry_path}") from exc
    except json.JSONDecodeError as exc:
        raise EventPeriodError(f"official event-period registry is invalid JSON: {exc}") from exc

    if not isinstance(payload, dict) or payload.get("schemaVersion") != 1:
        raise EventPeriodError("registry must be an object with schemaVersion=1")
    records = payload.get("records")
    if not isinstance(records, list):
        raise EventPeriodError("registry records must be a list")
    if require_non_empty and not records:
        raise EventPeriodError(
            "official ATP event-period registry is empty; refusing to generate CALC ratings"
        )

    by_source_id = {}
    event_ids = set()
    validated = []
    for raw in records:
        record = validate_record(raw)
        event_key = (record["season"], record["eventId"])
        if event_key in event_ids:
            raise EventPeriodError(f"duplicate eventId for season: {event_key}")
        event_ids.add(event_key)
        for source_id in record["sourceEventIds"]:
            key = (record["season"], source_id)
            if key in by_source_id:
                previous = by_source_id[key]
                raise EventPeriodError(
                    f"source event ID {key} maps to multiple ATP event records "
                    f"({previous['eventId']!r}, {record['eventId']!r})"
                )
            by_source_id[key] = record
        validated.append(record)

    return {
        "path": str(registry_path),
        "records": validated,
        "by_source_id": by_source_id,
    }


def period_for_rows(
    rows: list[dict], season: int, registry: dict, expected_tournament_id: str | None = None,
) -> dict | None:
    """Resolve every source ID in an app event group to its complete official period.

    Some app tournament IDs intentionally aggregate two ATP editions (for example,
    Adelaide 1/2). In that case the app target uses the union period, while source IDs
    remain distinct and each source edition must still have its own verified period.
    """
    source_ids = {
        str(row.get("tourney_id") or "").strip()
        for row in rows
        if str(row.get("tourney_id") or "").strip()
    }
    if not source_ids:
        return None
    by_source = registry.get("by_source_id", {})
    records = [by_source.get((int(season), source_id)) for source_id in source_ids]
    if any(record is None for record in records):
        return None
    tournament_ids = {record["tournamentId"] for record in records}
    if len(tournament_ids) != 1:
        return None
    resolved_tournament_id = next(iter(tournament_ids))
    if expected_tournament_id is not None and resolved_tournament_id != expected_tournament_id:
        return None
    event_keys = {(record["season"], record["eventId"]) for record in records}
    if len(records) == 1:
        return records[0]

    ordered = sorted(records, key=lambda value: value["_start_utc"])
    result = dict(ordered[0])
    result["eventId"] = "+".join(sorted(record["eventId"] for record in records))
    result["sourceEventIds"] = sorted(source_ids)
    result["_event_keys"] = event_keys
    result["_start_utc"] = min(record["_start_utc"] for record in records)
    result["_end_utc_exclusive"] = max(record["_end_utc_exclusive"] for record in records)
    result["startDate"] = min(records, key=lambda value: value["_start_utc"])["startDate"]
    result["endDate"] = max(records, key=lambda value: value["_end_utc_exclusive"])["endDate"]
    if any(record["dateStatus"] != "actual" for record in records):
        result["sourceKind"], result["dateStatus"] = "calendar", "scheduled"
    return result


def completed_before(source_period: dict | None, target_period: dict | None) -> bool:
    """Only a Results Archive actual period can qualify as a completed training event."""
    if not source_period or not target_period:
        return False
    if source_period.get("sourceKind") != "results_archive" or source_period.get("dateStatus") != "actual":
        return False
    target_event_keys = target_period.get("_event_keys") or {
        (target_period.get("season"), target_period.get("eventId"))
    }
    same_event = (source_period.get("season"), source_period.get("eventId")) in target_event_keys
    return (
        not same_event
        and source_period["_end_utc_exclusive"] <= target_period["_start_utc"]
    )


def audit_source_coverage(
    rows: list[dict], season: int, registry: dict, tournament_id_resolver=None,
    require_actual: bool = False, include_davis_cup: bool = False,
) -> dict:
    """Count ATP tour-level singles editions; Davis Cup is separately target-audited."""
    source_ids = set()
    missing_ids = []
    audited_levels = {"G", "M", "A", "F", "250", "500"}
    if include_davis_cup:
        audited_levels.add("D")
    for row in rows:
        if str(row.get("tourney_level") or "") not in audited_levels:
            continue
        winner = str(row.get("winner_name") or "")
        loser = str(row.get("loser_name") or "")
        if any("/" in name or " & " in name or " and " in name.casefold() for name in (winner, loser)):
            continue
        source_id = str(row.get("tourney_id") or "").strip()
        if not source_id:
            missing_ids.append("<missing tourney_id>")
        else:
            source_ids.add(source_id)

    covered = []
    for source_id in sorted(source_ids):
        period = registry.get("by_source_id", {}).get((int(season), source_id))
        if period is None:
            missing_ids.append(source_id)
        else:
            if tournament_id_resolver is not None:
                expected = {
                    tournament_id_resolver(row)
                    for row in rows
                    if str(row.get("tourney_id") or "").strip() == source_id
                }
                if len(expected) != 1:
                    missing_ids.append(f"{source_id} (multiple app event identities)")
                    continue
                expected_id = next(iter(expected))
                # An untracked ATP edition can still train player profiles if its exact
                # source ID and official dates are registered; only known app IDs need
                # to match the registry's tournamentId value.
                if expected_id is not None and period["tournamentId"] != expected_id:
                    missing_ids.append(f"{source_id} (event ID mapping mismatch)")
                    continue
            if require_actual and (
                period["sourceKind"] != "results_archive" or period["dateStatus"] != "actual"
            ):
                missing_ids.append(f"{source_id} (not an actual Results Archive period)")
                continue
            covered.append(period)

    missing_ids = sorted(set(missing_ids))
    return {
        "season": int(season),
        "identifiedSourceEvents": len(source_ids) + (1 if "<missing tourney_id>" in missing_ids else 0),
        "coveredSourceEvents": len(covered),
        "actualResultsArchivePeriods": sum(
            period["sourceKind"] == "results_archive" and period["dateStatus"] == "actual"
            for period in covered
        ),
        "scheduledCalendarPeriods": sum(
            period["sourceKind"] == "calendar" and period["dateStatus"] == "scheduled"
            for period in covered
        ),
        "missingSourceEventIds": missing_ids,
        "dateCoverageComplete": not missing_ids and len(covered) == len(source_ids),
    }
