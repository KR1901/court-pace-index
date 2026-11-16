"""Select the Court Speed CPI snapshot without retaining withdrawn fallback rows.

A valid source snapshot is a complete table, not a set of incremental additions.
The small built-in table is only a last-known fallback for source outages or
incomplete parses.
"""
from __future__ import annotations

from collections.abc import Iterable, Mapping
from typing import Any


def _normalise_rows(rows: Iterable[Any]) -> dict[tuple[str, int], float]:
    readings: dict[tuple[str, int], float] = {}
    for row in rows:
        if not isinstance(row, Mapping):
            raise ValueError("CPI snapshot row is not an object")
        tournament = str(row["t"]).strip()
        season = int(row["s"])
        value = float(row["cpi"])
        if not tournament or not 10.0 <= value <= 60.0:
            raise ValueError("CPI snapshot contains an invalid tournament or value")
        readings[(tournament, season)] = value
    return readings


def select_cpi_readings(
    fallback_rows: Iterable[tuple[str, int, float]],
    source_payload: Mapping[str, Any] | None,
    current_year: int,
    *,
    minimum_total: int = 50,
    minimum_current_year: int = 5,
) -> tuple[list[tuple[str, int, float]], bool]:
    """Return (readings, authoritative_source_used).

    The source table replaces the fallback table only when it passes the same
    completeness floor used by the network fetcher. This is important: merging
    an authoritative snapshot into a static list can resurrect rows that the
    source has deliberately removed or corrected.
    """
    fallback = {
        (str(tournament).strip(), int(season)): float(value)
        for tournament, season, value in fallback_rows
    }

    if source_payload is not None:
        try:
            source = _normalise_rows(source_payload.get("readings") or [])
        except (KeyError, TypeError, ValueError):
            source = {}

        current_count = sum(1 for _tournament, season in source if season == current_year)
        if len(source) >= minimum_total and current_count >= minimum_current_year:
            rows = [
                (tournament, season, value)
                for (tournament, season), value in sorted(source.items())
            ]
            return rows, True

    rows = [
        (tournament, season, value)
        for (tournament, season), value in sorted(fallback.items())
    ]
    return rows, False
