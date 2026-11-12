#!/usr/bin/env python3
"""Build a private, derived ATP player-statistics asset from TML annual ATP files.

Raw CSVs are downloaded to .cache/player_stats and are never added to the repository.
The output is personal/non-commercial research data and must remain in this private repo.
"""
from __future__ import annotations

import csv
import copy
import hashlib
import io
import json
import math
import re
import time
import unicodedata
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".cache" / "player_stats"
ROSTER_URL = "https://stats.tennismylife.org/data/ATP_Database.csv"
MANIFEST_URL = "https://stats.tennismylife.org/api/data-files"
MATCH_URL = "https://stats.tennismylife.org/data/{year}.csv"
RANKING_DATES_URL = "https://stats.tennismylife.org/api/ranking/dates"
RANKING_URL = "https://stats.tennismylife.org/api/ranking"
TML_RANKINGS_ARCHIVE_URL = (
    "https://raw.githubusercontent.com/Tennismylife/TML-Rankings-Database/main/"
    "TML%20Rankings/{year}.csv"
)
KICKSERVE_LIVE_RANKINGS_URL = "https://kick-serve.com/data/ratings_atp.csv"
KICKSERVE_ATTRIBUTION_URL = "https://kick-serve.com/data/"
OUTPUTS = (
    ROOT / "data" / "player_stats.json",
    ROOT / "app" / "src" / "main" / "assets" / "player_stats.json",
)
DATA_SOURCE = "TennisMyLife ATP Match Database"
DATA_SOURCE_URL = "https://stats.tennismylife.org/tennis-match-database"
USER_AGENT = "CourtPaceIndex-PrivatePlayerStats/1.0 (personal research)"
TOUR_LEVELS = {"G", "M", "A", "F", "250", "500"}
BUCKETS = ("all", "clay", "grass", "hardAll", "hardOutdoor", "hardIndoor", "hardUnknown", "carpet")
BASE_FIELDS = ("mp", "w", "l", "sp", "sw", "rp", "rw", "sm")


def normalize_name(value: str | None) -> str:
    value = unicodedata.normalize("NFKD", str(value or "")).casefold()
    return "".join(ch for ch in value if ch.isalnum())


def integer(value: object) -> int | None:
    try:
        if value is None or str(value).strip() == "":
            return None
        number = float(str(value).strip())
        if not math.isfinite(number) or number < 0 or not number.is_integer():
            return None
        return int(number)
    except (TypeError, ValueError):
        return None


def valid_height(value: object) -> int | None:
    height = integer(value)
    return height if height is not None and 140 <= height <= 220 else None


def match_has_result(score: str | None, winner_id: str | None, loser_id: str | None) -> bool:
    """Official played-match result; retirements count, walkovers/no-play do not."""
    if not winner_id or not loser_id or winner_id == loser_id or not (score or "").strip():
        return False
    upper = (score or "").upper()
    return not bool(re.search(r"\b(?:W/O|W\.O\.|WO|WALKOVER|DEF|ABD|ABN|ABANDONED)\b", upper))


def match_is_retirement(score: str | None) -> bool:
    return bool(re.search(r"\bRET(?:IRED)?\b", (score or "").upper()))


def service_return_contribution(row: dict[str, str]) -> tuple[int, int, int, int] | None:
    """Return (winner serve games played/won, loser serve games played/won).

    Winner/loser BP faced and saved fields refer to the break points faced while
    that player was serving. Breaks conceded are therefore BP faced minus saved.
    """
    keys = ("w_SvGms", "w_bpFaced", "w_bpSaved", "l_SvGms", "l_bpFaced", "l_bpSaved")
    values = [integer(row.get(key)) for key in keys]
    if any(value is None for value in values):
        return None
    w_sg, w_faced, w_saved, l_sg, l_faced, l_saved = values
    assert w_sg is not None and w_faced is not None and w_saved is not None
    assert l_sg is not None and l_faced is not None and l_saved is not None
    if w_saved > w_faced or l_saved > l_faced:
        return None
    w_broken = w_faced - w_saved
    l_broken = l_faced - l_saved
    if w_broken > w_sg or l_broken > l_sg:
        return None
    return w_sg, w_sg - w_broken, l_sg, l_sg - l_broken


def match_identity_key(tournament_id: str, match_num: str, winner_id: str, loser_id: str) -> str:
    """Stable cross-feed match identity; the same key is used by Android live sync."""
    if not all((tournament_id, match_num, winner_id, loser_id)):
        raise ValueError("match identity requires tournament, match number and both player IDs")
    return "|".join((tournament_id, match_num, *sorted((winner_id, loser_id))))


def height_band_id(height_cm: int | None) -> str | None:
    if height_cm is None or height_cm < 140 or height_cm > 220:
        return None
    if height_cm < 183:
        return "under183"
    if height_cm <= 185:
        return "183to185"
    if height_cm <= 188:
        return "186to188"
    if height_cm <= 191:
        return "189to191"
    if height_cm <= 194:
        return "192to194"
    return "195plus"


def indoor_setting(value: str | None) -> str | None:
    """Normalize TML's documented Yes/No flag and equivalent encodings."""
    token = (value or "").strip().casefold()
    if token in {"i", "indoor", "yes", "y", "true", "1"}:
        return "indoor"
    if token in {"o", "outdoor", "no", "n", "false", "0"}:
        return "outdoor"
    return None


def match_buckets(surface: str | None, indoor: str | None) -> tuple[str, ...]:
    surface = (surface or "").strip().casefold()
    if surface == "hard":
        setting = indoor_setting(indoor)
        bucket = {"indoor": "hardIndoor", "outdoor": "hardOutdoor", None: "hardUnknown"}[setting]
        return ("all", "hardAll", bucket)
    mapping = {"clay": "clay", "grass": "grass", "carpet": "carpet"}
    bucket = mapping.get(surface)
    return ("all", bucket) if bucket else ("all",)


def empty_counter() -> dict[str, int]:
    return {key: 0 for key in BASE_FIELDS}


def load_json_url(url: str) -> dict:
    """Fetch a small JSON endpoint with bounded retry for transient source failures."""
    request = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    last_error: Exception | None = None
    for attempt in range(3):
        try:
            with urlopen(request, timeout=60) as response:
                payload = response.read()
            result = json.loads(payload.decode("utf-8-sig"))
            if not isinstance(result, dict):
                raise ValueError(f"{url} did not return a JSON object")
            return result
        except (HTTPError, URLError, TimeoutError, OSError, ValueError, json.JSONDecodeError) as error:
            last_error = error
            if attempt < 2:
                time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"Unable to load JSON from {url}: {last_error}")


def download_cached(url: str, name: str, expected_size: int | None, modified: str | None) -> bytes:
    CACHE.mkdir(parents=True, exist_ok=True)
    target = CACHE / name
    sidecar = target.with_suffix(target.suffix + ".meta.json")
    if target.is_file() and sidecar.is_file():
        try:
            cached_meta = json.loads(sidecar.read_text(encoding="utf-8"))
            if (
                cached_meta.get("modified") == modified
                and (expected_size is None or target.stat().st_size == expected_size)
            ):
                return target.read_bytes()
        except (OSError, ValueError, json.JSONDecodeError):
            pass

    last_error: Exception | None = None
    for attempt in range(3):
        try:
            request = Request(
                url,
                headers={"User-Agent": USER_AGENT, "Accept": "text/csv,application/json,*/*"},
            )
            with urlopen(request, timeout=90) as response:
                payload = response.read()
            if expected_size is not None and len(payload) != expected_size:
                raise ValueError(
                    f"{name}: downloaded {len(payload)} bytes, manifest reports {expected_size}"
                )
            target.write_bytes(payload)
            sidecar.write_text(
                json.dumps(
                    {
                        "url": url,
                        "modified": modified,
                        "bytes": len(payload),
                        "sha256": hashlib.sha256(payload).hexdigest(),
                    },
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )
            return payload
        except (HTTPError, URLError, TimeoutError, OSError, ValueError) as error:
            last_error = error
            if attempt < 2:
                time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"Unable to download {url}: {last_error}")


def csv_rows(payload: bytes) -> list[dict[str, str]]:
    text = payload.decode("utf-8-sig", errors="replace")
    return list(csv.DictReader(io.StringIO(text, newline="")))


def valid_manifest_files(manifest: dict) -> dict[str, dict]:
    result: dict[str, dict] = {}
    for item in manifest.get("files", []):
        if isinstance(item, dict) and item.get("name") and item.get("url"):
            result[str(item["name"])] = item
    return result


def load_roster(files: dict[str, dict]) -> tuple[dict[str, dict], dict[str, set[str]]]:
    item = files.get("ATP_Database.csv")
    if not item:
        raise RuntimeError("TML manifest does not contain ATP_Database.csv")
    payload = download_cached(
        str(item["url"]),
        "ATP_Database.csv",
        integer(item.get("size")),
        str(item.get("mtime") or ""),
    )
    roster: dict[str, dict] = {}
    aliases: dict[str, set[str]] = defaultdict(set)
    for row in csv_rows(payload):
        player_id = (row.get("id") or "").strip()
        if not player_id:
            continue
        canonical = (row.get("atpname") or row.get("player") or "").strip()
        name = (row.get("player") or canonical).strip()
        if not canonical:
            canonical = name
        height = valid_height(row.get("height"))
        roster[player_id] = {
            "id": player_id,
            "name": canonical or name or player_id,
            "country": (row.get("ioc") or "").strip() or None,
            "heightCm": height,
            "heightSource": "tml-player-database" if height is not None else None,
            "_heightSeason": 9999 if height is not None else 0,
        }
        for label in (canonical, name):
            _add_player_alias(aliases, label, (row.get("ioc") or "").strip(), player_id)
    if len(roster) < 100:
        raise RuntimeError(f"ATP_Database.csv returned only {len(roster)} player records")
    return roster, aliases


def select_ranking_snapshot_date(payload: dict, today: date | None = None) -> str:
    """Choose the latest real TML ranking date, never a future or guessed date."""
    current = today or date.today()
    raw_dates = payload.get("dates")
    if not isinstance(raw_dates, list):
        raise RuntimeError("TML ranking-date endpoint did not return a dates array")
    valid_dates: list[date] = []
    for raw in raw_dates:
        try:
            parsed = date.fromisoformat(str(raw))
        except (TypeError, ValueError):
            continue
        if parsed <= current:
            valid_dates.append(parsed)
    if not valid_dates:
        raise RuntimeError("TML ranking archive contains no valid snapshot on or before today")
    return max(valid_dates).isoformat()


def unresolved_ranking_count(payload: dict, rank_limit: int) -> int:
    """Count ranked rows without stable IDs through a cutoff; never treat 'NA' as a person."""
    rows = payload.get("rankings")
    if not isinstance(rows, list):
        raise RuntimeError("TML ranking endpoint did not return a rankings array")
    placeholders = {"", "NA", "N/A", "NONE", "NULL", "UNKNOWN", "0", "-"}
    return sum(
        1
        for row in rows
        if isinstance(row, dict)
        and (rank := integer(row.get("rank"))) is not None
        and 1 <= rank <= rank_limit
        and str(row.get("id") or "").strip().upper() in placeholders
    )


def unresolved_top100_count(payload: dict) -> int:
    """Backward-compatible count of unresolved rows in the Top 100."""
    return unresolved_ranking_count(payload, 100)


def max_published_rank(payload: dict) -> int:
    """Return the highest valid position present in a dated ranking snapshot."""
    rows = payload.get("rankings")
    if not isinstance(rows, list):
        raise RuntimeError("TML ranking endpoint did not return a rankings array")
    ranks = [
        rank
        for row in rows
        if isinstance(row, dict)
        and (rank := integer(row.get("rank"))) is not None
        and rank > 0
    ]
    return max(ranks, default=0)


def parse_current_top_n(
    payload: dict,
    rank_limit: int,
    snapshot_label: str = "",
) -> list[dict[str, object]]:
    """Parse ranks through the requested cutoff, preserving ties and skipping unresolved IDs.

    A snapshot may contain more than the nominal cutoff when multiple players share the
    boundary rank. Missing/placeholder IDs are counted separately and never merged into a
    fabricated player. Duplicate real player IDs are rejected.
    """
    rows = payload.get("rankings")
    context = f" ({snapshot_label})" if snapshot_label else ""
    if not isinstance(rows, list):
        raise RuntimeError("TML ranking endpoint did not return a rankings array" + context)
    unresolved = unresolved_ranking_count(payload, rank_limit)
    placeholders = {"", "NA", "N/A", "NONE", "NULL", "UNKNOWN", "0", "-"}
    ranked: list[dict[str, object]] = []
    seen_ids: set[str] = set()
    for row in rows:
        if not isinstance(row, dict):
            continue
        rank = integer(row.get("rank"))
        if rank is None or not 1 <= rank <= rank_limit:
            continue
        player_id = str(row.get("id") or "").strip()
        name = str(row.get("name") or "").strip()
        country = str(row.get("ioc") or row.get("country") or "").strip().upper()
        points = integer(row.get("points"))
        if player_id.upper() in placeholders:
            continue
        if not name:
            name = player_id
        if player_id in seen_ids:
            raise RuntimeError(f"Duplicate TML player ID {player_id} in rank-{rank_limit} cohort" + context)
        seen_ids.add(player_id)
        ranked.append({"id": player_id, "rank": rank, "name": name, "country": country, "points": points})
    minimum_entries = min(rank_limit, 100)
    if len(ranked) + unresolved < minimum_entries:
        raise RuntimeError(
            f"TML rank-{rank_limit} cohort must contain at least {minimum_entries} identified or unresolved entries; "
            f"found {len(ranked)} identified and {unresolved} unresolved" + context
        )
    return sorted(ranked, key=lambda item: int(item["rank"]))


def parse_current_top100(payload: dict, snapshot_label: str = "") -> list[dict[str, object]]:
    """Backward-compatible Top 100 parser."""
    return parse_current_top_n(payload, 100, snapshot_label)


def parse_current_top1000(payload: dict, snapshot_label: str = "") -> list[dict[str, object]]:
    """Parse the dated ranking snapshot through rank 1000, preserving co-ranked positions."""
    return parse_current_top_n(payload, 1000, snapshot_label)


def _parse_csv_rank_rows(
    rows: list[dict[str, str]],
    snapshot_label: str,
    *,
    rank_field: str,
    id_field: str,
    name_field: str,
    country_field: str,
    points_field: str,
    date_field: str | None = None,
    target_date: str | None = None,
    tour_field: str | None = None,
) -> dict[str, object]:
    """Parse a complete dated/current CSV rank list through position 1000."""
    placeholders = {"", "NA", "N/A", "NONE", "NULL", "UNKNOWN", "0", "-"}
    target_compact = (target_date or "").replace("-", "")
    ranked: list[dict[str, object]] = []
    seen_ids: set[str] = set()
    unresolved_top100 = 0
    unresolved_top500 = 0
    unresolved_top1000 = 0
    max_rank = 0
    matching_rows = 0

    for row in rows:
        if date_field is not None and str(row.get(date_field) or "").strip() != target_compact:
            continue
        if tour_field is not None and str(row.get(tour_field) or "").strip().casefold() not in {"atp", "men"}:
            continue
        rank = integer(row.get(rank_field))
        if rank is None or rank < 1:
            continue
        matching_rows += 1
        max_rank = max(max_rank, rank)
        if rank <= 100:
            unresolved_top100 += 1 if str(row.get(id_field) or "").strip().upper() in placeholders else 0
        if rank <= 500 and str(row.get(id_field) or "").strip().upper() in placeholders:
            unresolved_top500 += 1
        if rank <= 1000 and str(row.get(id_field) or "").strip().upper() in placeholders:
            unresolved_top1000 += 1
        if rank > 1000:
            continue

        player_id = str(row.get(id_field) or "").strip()
        if player_id.upper() in placeholders:
            continue
        name = str(row.get(name_field) or "").strip() or player_id
        country = str(row.get(country_field) or "").strip().upper()
        points = integer(row.get(points_field))
        if player_id in seen_ids:
            raise RuntimeError(
                f"Duplicate player ID {player_id} in rank archive {snapshot_label}"
            )
        seen_ids.add(player_id)
        ranked.append(
            {
                "id": player_id,
                "rank": rank,
                "name": name,
                "country": country,
                "points": points,
            }
        )

    if matching_rows == 0:
        raise RuntimeError(f"No ranking rows found for {snapshot_label}")
    return {
        "players": sorted(ranked, key=lambda item: (int(item["rank"]), str(item["id"]))),
        "unresolvedTop100": unresolved_top100,
        "unresolvedTop500": unresolved_top500,
        "unresolvedPlayers": unresolved_top1000,
        "rankedEntries": len(ranked) + unresolved_top1000,
        "maxRank": max_rank,
        "sourceRows": matching_rows,
    }


def parse_tml_ranking_archive_snapshot(
    payload: bytes,
    target_date: str,
    snapshot_label: str = "",
) -> dict[str, object]:
    """Read one year-end snapshot from TML's full historical ranking CSV."""
    label = snapshot_label or target_date
    return _parse_csv_rank_rows(
        csv_rows(payload),
        label,
        rank_field="rank",
        id_field="id",
        name_field="name",
        country_field="ioc",
        points_field="points",
        date_field="date",
        target_date=target_date,
    )


def parse_kickserve_live_rankings(
    payload: bytes,
    snapshot_label: str = "",
) -> dict[str, object]:
    """Read KickServe's daily CSV, using only its published official ATP rank column."""
    return _parse_csv_rank_rows(
        csv_rows(payload),
        snapshot_label or "KickServe current ATP rankings",
        rank_field="official_rank",
        id_field="id",
        name_field="player",
        country_field="country",
        points_field="points",
        tour_field="tour",
    )


def select_ranking_snapshot_dates(
    payload: dict,
    first_year: int,
    last_year: int,
    today: date | None = None,
) -> dict[int, str]:
    """Choose each season's final dated ranking; current season uses its latest live snapshot.

    Historical cohorts use the last TML ranking date published inside that calendar year.
    The live season uses the latest dated snapshot on or before today. Missing seasons fail
    closed instead of silently substituting a different year's cohort.
    """
    current = today or date.today()
    raw_dates = payload.get("dates")
    if not isinstance(raw_dates, list):
        raise RuntimeError("TML ranking-date endpoint did not return a dates array")

    latest_by_year: dict[int, date] = {}
    for raw in raw_dates:
        try:
            parsed = date.fromisoformat(str(raw))
        except (TypeError, ValueError):
            continue
        if parsed > current or not first_year <= parsed.year <= last_year:
            continue
        previous = latest_by_year.get(parsed.year)
        if previous is None or parsed > previous:
            latest_by_year[parsed.year] = parsed

    missing = [year for year in range(first_year, last_year + 1) if year not in latest_by_year]
    if missing:
        raise RuntimeError(
            "TML ranking archive has no dated Top-100 snapshot for calendar year(s): "
            + ", ".join(str(year) for year in missing)
        )
    return {year: latest_by_year[year].isoformat() for year in range(first_year, last_year + 1)}


def load_top100_season_snapshots(
    first_year: int,
    current_year: int,
    today: date | None = None,
) -> dict[int, dict[str, object]]:
    """Keep the existing dated TML Top-100 source and semantics unchanged."""
    dates_payload = load_json_url(RANKING_DATES_URL)
    ranking_dates = select_ranking_snapshot_dates(
        dates_payload, first_year, current_year, today=today,
    )

    def load_one(item: tuple[int, str]) -> tuple[int, dict[str, object]]:
        year, ranking_date = item
        source_url = f"{RANKING_URL}?date={ranking_date}"
        snapshot = load_json_url(source_url)
        return year, {
            "year": year,
            "asOf": ranking_date,
            "source": source_url,
            "players": parse_current_top100(snapshot, f"{year} snapshot {ranking_date}"),
            "unresolvedPlayers": unresolved_top100_count(snapshot),
        }

    # This endpoint intentionally supplies the original Top 100 cohort only.
    with ThreadPoolExecutor(max_workers=4) as executor:
        loaded = dict(executor.map(load_one, ranking_dates.items()))
    return {year: loaded[year] for year in sorted(loaded)}


def _validate_top100_subset(
    top100_snapshot: dict[str, object],
    expanded_snapshot: dict[str, object],
) -> None:
    """Ensure the expanded source can represent every player in the original Top 100."""
    expanded_by_id = {
        str(entry["id"]): int(entry["rank"])
        for entry in expanded_snapshot["players"]
        if int(entry["rank"]) <= 100
    }
    expected_by_id = {
        str(entry["id"]): int(entry["rank"])
        for entry in top100_snapshot["players"]
    }
    if expanded_by_id != expected_by_id:
        missing = sorted(set(expected_by_id) - set(expanded_by_id))
        extra = sorted(set(expanded_by_id) - set(expected_by_id))
        changed = sorted(
            player_id
            for player_id in set(expected_by_id) & set(expanded_by_id)
            if expected_by_id[player_id] != expanded_by_id[player_id]
        )
        raise RuntimeError(
            f"{expanded_snapshot['year']} Top-100 cross-check failed: "
            f"missing={missing[:5]}, extra={extra[:5]}, changedRanks={changed[:5]}"
        )


def map_live_rankings_to_tml_ids(
    snapshot: dict[str, object],
    roster_aliases: dict[str, set[str]],
) -> dict[str, object]:
    """Crosswalk KickServe's internal IDs to stable TML IDs using unique exact-name keys.

    The feed's player IDs are internal to KickServe, not ATP/TML IDs. Names are normalized
    only for accents and punctuation; ambiguous or missing roster matches stay unresolved.
    """
    mapped: list[dict[str, object]] = []
    seen_ids: set[str] = set()
    unresolved_top100 = int(snapshot.get("unresolvedTop100", 0))
    unresolved_top500 = int(snapshot.get("unresolvedTop500", 0))
    unresolved_top1000 = int(snapshot.get("unresolvedPlayers", 0))
    unresolved_ranks = list(snapshot.get("unresolvedRanks", []))

    for entry in snapshot["players"]:
        rank = int(entry["rank"])
        name_key = normalize_name(str(entry["name"]))
        country_key = (str(entry.get("country") or "")).strip().upper()
        country_candidates = roster_aliases.get(f"{name_key}|{country_key}", set()) if country_key else set()
        candidate_ids = country_candidates if country_candidates else roster_aliases.get(name_key, set())
        if len(candidate_ids) != 1:
            if rank <= 100:
                unresolved_top100 += 1
            if rank <= 500:
                unresolved_top500 += 1
            unresolved_top1000 += 1
            unresolved_ranks.append(rank)
            reason = "ambiguous exact name/country match" if len(candidate_ids) > 1 else "no exact name/country match"
            unresolved_entries = list(snapshot.get("unresolvedEntries", []))
            unresolved_entries.append({
                "rank": rank,
                "name": str(entry.get("name") or "").strip(),
                "country": country_key,
                "reason": reason,
            })
            snapshot["unresolvedEntries"] = unresolved_entries
            continue
        player_id = next(iter(candidate_ids))
        if player_id in seen_ids:
            raise RuntimeError(
                f"KickServe names map to duplicate TML player ID {player_id}"
            )
        seen_ids.add(player_id)
        mapped_entry = dict(entry)
        mapped_entry["id"] = player_id
        mapped.append(mapped_entry)

    return {
        **snapshot,
        "players": mapped,
        "unresolvedTop100": unresolved_top100,
        "unresolvedTop500": unresolved_top500,
        "unresolvedPlayers": unresolved_top1000,
        "unresolvedRanks": unresolved_ranks,
        "unresolvedEntries": list(snapshot.get("unresolvedEntries", [])),
        "rankedEntries": len(mapped) + unresolved_top1000,
    }


def supplement_top100_from_tml(
    expanded_snapshot: dict[str, object],
    top100_snapshot: dict[str, object],
) -> dict[str, object]:
    """Preserve every player/rank from the established TML Top 100 in the live full list.

    The external full-list feed can omit a small number of rated players. Those are restored
    from the exact same dated TML Top-100 snapshot; existing conflicts still fail validation.
    """
    expanded_by_id = {
        str(entry["id"]): dict(entry)
        for entry in expanded_snapshot["players"]
    }
    unresolved_ranks = list(expanded_snapshot.get("unresolvedRanks", []))
    unresolved_counts = {
        "unresolvedTop500": int(expanded_snapshot.get("unresolvedTop500", 0)),
        "unresolvedPlayers": int(expanded_snapshot.get("unresolvedPlayers", 0)),
    }
    for entry in top100_snapshot["players"]:
        player_id = str(entry["id"])
        expected_rank = int(entry["rank"])
        existing = expanded_by_id.get(player_id)
        if existing is not None:
            if int(existing["rank"]) != expected_rank:
                raise RuntimeError(
                    f"Live full ranking feed rank conflict for Top-100 player {player_id}: "
                    f"feed={existing['rank']}, TML={expected_rank}"
                )
            continue
        expanded_by_id[player_id] = dict(entry)
        try:
            unresolved_ranks.remove(expected_rank)
        except ValueError:
            pass
        else:
            if expected_rank <= 500:
                unresolved_counts["unresolvedTop500"] = max(0, unresolved_counts["unresolvedTop500"] - 1)
            unresolved_counts["unresolvedPlayers"] = max(0, unresolved_counts["unresolvedPlayers"] - 1)

    players = sorted(
        expanded_by_id.values(),
        key=lambda item: (int(item["rank"]), str(item["id"])),
    )
    return {
        **expanded_snapshot,
        "players": players,
        "unresolvedTop100": int(top100_snapshot.get("unresolvedPlayers", 0)),
        **unresolved_counts,
        "unresolvedRanks": unresolved_ranks,
        "rankedEntries": len(players) + unresolved_counts["unresolvedPlayers"],
    }


def load_top1000_season_snapshots(
    first_year: int,
    current_year: int,
    top100_snapshots: dict[int, dict[str, object]],
    roster_aliases: dict[str, set[str]],
    today: date | None = None,
) -> dict[int, dict[str, object]]:
    """Load full year-specific ranking lists; never infer larger cohorts from a Top-200 API."""
    current = today or date.today()
    historical_items = [
        (year, snapshot)
        for year, snapshot in sorted(top100_snapshots.items())
        if year != current_year
    ]

    def load_historical(item: tuple[int, dict[str, object]]) -> tuple[int, dict[str, object]]:
        year, top100 = item
        archive_url = TML_RANKINGS_ARCHIVE_URL.format(year=year)
        # Ranking archive files are refreshed daily so source corrections are not cached forever.
        payload = download_cached(
            archive_url,
            f"tml_rankings_{year}.csv",
            None,
            current.isoformat(),
        )
        parsed = parse_tml_ranking_archive_snapshot(
            payload,
            str(top100["asOf"]),
            f"TML {year} year-end snapshot {top100['asOf']}",
        )
        snapshot = {
            "year": year,
            "asOf": str(top100["asOf"]),
            "source": archive_url,
            "provider": "TML archive",
            **parsed,
        }
        snapshot = supplement_top100_from_tml(snapshot, top100)
        _validate_top100_subset(top100, snapshot)
        return year, snapshot

    # The live official_rank feed is a full ATP list; TML's dated API remains authoritative for Top 100.
    live_url = KICKSERVE_LIVE_RANKINGS_URL
    live_payload = download_cached(
        live_url,
        "kickserve_live_atp_rankings.csv",
        None,
        current.isoformat(),
    )
    live_top = parse_kickserve_live_rankings(
        live_payload,
        f"KickServe live ATP ranking list ({current.isoformat()})",
    )
    live_top = map_live_rankings_to_tml_ids(live_top, roster_aliases)
    live_snapshot = {
        "year": current_year,
        "asOf": str(top100_snapshots[current_year]["asOf"]),
        "source": live_url,
        "provider": "KickServe/TML",
        **live_top,
    }
    live_snapshot = supplement_top100_from_tml(
        live_snapshot,
        top100_snapshots[current_year],
    )
    _validate_top100_subset(top100_snapshots[current_year], live_snapshot)

    with ThreadPoolExecutor(max_workers=4) as executor:
        historical = dict(executor.map(load_historical, historical_items))
    historical[current_year] = live_snapshot

    expected_years = list(range(first_year, current_year + 1))
    missing_years = sorted(set(expected_years) - set(historical))
    if missing_years:
        raise RuntimeError(f"Missing expanded ranking snapshots for years: {missing_years}")
    return {year: historical[year] for year in expected_years}


def ensure_player(
    players: dict[str, dict],
    player_id: str,
    name: str | None,
    country: str | None,
    height: object,
    season: int,
) -> None:
    if not player_id:
        return
    record = players.get(player_id)
    if record is None:
        record = {
            "id": player_id,
            "name": (name or "").strip() or player_id,
            "country": (country or "").strip() or None,
            "heightCm": None,
            "heightSource": None,
            "_heightSeason": 0,
        }
        players[player_id] = record
    if not record.get("name") or record["name"] == player_id:
        record["name"] = (name or "").strip() or player_id
    if not record.get("country") and country:
        record["country"] = country.strip() or None
    candidate = valid_height(height)
    if candidate is not None and record.get("heightSource") != "tml-player-database":
        if record.get("heightCm") is None or season >= int(record.get("_heightSeason", 0)):
            record["heightCm"] = candidate
            record["heightSource"] = "tml-match-record-fallback"
            record["_heightSeason"] = season


def add_player_match(
    aggregate: dict[str, dict[int, dict[str, dict[str, int]]]],
    player_id: str,
    season: int,
    buckets: tuple[str, ...],
    won: bool,
    serve_played: int,
    serve_won: int,
    return_played: int,
    return_won: int,
    has_stats: bool,
) -> None:
    years = aggregate.setdefault(player_id, {})
    for bucket in buckets:
        counter = years.setdefault(season, {}).setdefault(bucket, empty_counter())
        counter["mp"] += 1
        counter["w" if won else "l"] += 1
        if has_stats:
            counter["sp"] += serve_played
            counter["sw"] += serve_won
            counter["rp"] += return_played
            counter["rw"] += return_won
            counter["sm"] += 1


def resolve_current_top100(
    top100: list[dict[str, object]],
    players: dict[str, dict],
    current_year: int,
) -> dict[str, int]:
    """Join the current cohort by stable TML player ID, never by fuzzy name."""
    result: dict[str, int] = {}
    for entry in top100:
        player_id = str(entry["id"])
        ensure_player(players, player_id, str(entry["name"]), str(entry.get("country") or ""), None, current_year)
        rank = int(entry["rank"])
        if player_id in result or rank in result.values():
            raise RuntimeError(f"Duplicate TML current-cohort ID/rank: {player_id}/{rank}")
        result[player_id] = rank
    if len(result) != 100 or sorted(result.values()) != list(range(1, 101)):
        raise RuntimeError("Current TML cohort must map exactly 100 unique IDs at ranks 1–100")
    return result


def _coverage_totals() -> dict[str, int]:
    return {
        "sourceRows": 0,
        "matchResults": 0,
        "matchesWithValidStats": 0,
        "retiredMatchResults": 0,
        "excludedNoPlay": 0,
        "excludedIneligibleLevel": 0,
        "matchesWithoutValidStats": 0,
        "duplicateMatchRowsSkipped": 0,
        "hardRowsWithUnknownEnvironment": 0,
    }


def _manifest_item(files: dict[str, dict], filename: str) -> dict | None:
    """Find a manifest file by its exact basename, even when the manifest includes folders."""
    direct = files.get(filename)
    if direct:
        return direct
    for key, item in files.items():
        name = str(item.get("name") or key).replace("\\", "/")
        url = str(item.get("url") or "").replace("\\", "/")
        if name.rsplit("/", 1)[-1] == filename or url.rsplit("/", 1)[-1] == filename:
            return item
    return None


def _add_player_alias(
    aliases: dict[str, set[str]],
    name: str,
    country: str | None,
    player_id: str,
) -> None:
    key = normalize_name(name)
    if not key or not player_id:
        return
    aliases[key].add(player_id)
    country_key = (country or "").strip().upper()
    if country_key:
        aliases[f"{key}|{country_key}"].add(player_id)


def aggregate_match_rows(
    rows: list[dict[str, str]],
    filename: str,
    source_kind: str,
    aggregate: dict[str, dict[int, dict[str, dict[str, int]]]],
    players: dict[str, dict],
    match_aliases: dict[str, set[str]],
    totals: dict[str, int],
    seen_matches: set[tuple[str, str, str, str, str]],
    *,
    current_year: int | None = None,
    current_match_keys: set[str] | None = None,
    current_stat_match_keys: set[str] | None = None,
) -> None:
    """Aggregate a source file whose provenance determines its competition scope.

    Tour rows must have a recognized ATP Tour level. Challenger and qualifying files are
    source-scoped by their manifest path, so their rows are accepted regardless of the
    provider's level label (which is not consistent across those separate exports).
    """
    required = {
        "tourney_id", "tourney_name", "surface", "indoor", "tourney_date",
        "tourney_level", "match_num", "winner_id", "winner_name", "winner_ht", "winner_ioc",
        "loser_id", "loser_name", "loser_ht", "loser_ioc", "score",
        "w_SvGms", "w_bpFaced", "w_bpSaved", "l_SvGms", "l_bpFaced", "l_bpSaved",
    }
    if rows:
        missing = required - set(rows[0])
        if missing:
            raise RuntimeError(f"{filename} is missing required columns: {sorted(missing)}")
    totals["sourceRows"] += len(rows)

    for row in rows:
        if source_kind == "tour" and (row.get("tourney_level") or "").strip().upper() not in TOUR_LEVELS:
            totals["excludedIneligibleLevel"] += 1
            continue

        tournament_id = (row.get("tourney_id") or "").strip()
        season_text = tournament_id[:4] if len(tournament_id) >= 4 else ""
        if not season_text.isdigit():
            season_text = (row.get("tourney_date") or "")[:4]
        if not season_text.isdigit():
            continue
        season = int(season_text)

        winner_id = (row.get("winner_id") or "").strip()
        loser_id = (row.get("loser_id") or "").strip()
        winner_name = (row.get("winner_name") or "").strip()
        loser_name = (row.get("loser_name") or "").strip()
        winner_country = (row.get("winner_ioc") or "").strip()
        loser_country = (row.get("loser_ioc") or "").strip()

        # Keep identity and height enrichment even for walkovers/no-play rows. Those rows
        # do not contribute a result, but still provide safe ATP ID/name/country/height links.
        if winner_id:
            ensure_player(players, winner_id, winner_name, winner_country, row.get("winner_ht"), season)
            _add_player_alias(match_aliases, winner_name, winner_country, winner_id)
        if loser_id:
            ensure_player(players, loser_id, loser_name, loser_country, row.get("loser_ht"), season)
            _add_player_alias(match_aliases, loser_name, loser_country, loser_id)

        if not match_has_result(row.get("score"), winner_id, loser_id):
            totals["excludedNoPlay"] += 1
            continue

        match_num = (row.get("match_num") or "").strip()
        if not match_num:
            continue
        identity = (
            source_kind,
            tournament_id,
            match_num,
            min(winner_id, loser_id),
            max(winner_id, loser_id),
        )
        if identity in seen_matches:
            totals["duplicateMatchRowsSkipped"] += 1
            continue
        seen_matches.add(identity)

        stable_key = match_identity_key(tournament_id, match_num, winner_id, loser_id)
        if current_match_keys is not None and season == current_year:
            current_match_keys.add(stable_key if source_kind == "tour" else stable_key + "|" + source_kind)

        retirement = match_is_retirement(row.get("score"))
        if retirement:
            totals["retiredMatchResults"] += 1

        buckets = match_buckets(row.get("surface"), row.get("indoor"))
        if (row.get("surface") or "").strip().casefold() == "hard" and "hardUnknown" in buckets:
            totals["hardRowsWithUnknownEnvironment"] += 1

        # Retirements count in W-L results, but their partial-match game totals do not
        # contribute to Hold/Break rates.
        stats = None if retirement else service_return_contribution(row)
        if (
            current_stat_match_keys is not None
            and season == current_year
            and stats is not None
        ):
            current_stat_match_keys.add(stable_key if source_kind == "tour" else stable_key + "|" + source_kind)

        if stats is None:
            totals["matchesWithoutValidStats"] += 1
            add_player_match(aggregate, winner_id, season, buckets, True, 0, 0, 0, 0, False)
            add_player_match(aggregate, loser_id, season, buckets, False, 0, 0, 0, 0, False)
            totals["matchResults"] += 1
            continue

        winner_sg, winner_sw, loser_sg, loser_sw = stats
        winner_return_won = loser_sg - loser_sw
        loser_return_won = winner_sg - winner_sw
        add_player_match(
            aggregate, winner_id, season, buckets, True,
            winner_sg, winner_sw, loser_sg, winner_return_won, True,
        )
        add_player_match(
            aggregate, loser_id, season, buckets, False,
            loser_sg, loser_sw, winner_sg, loser_return_won, True,
        )
        totals["matchResults"] += 1
        totals["matchesWithValidStats"] += 1


def aggregate_files(
    files: dict[str, dict],
    years: range,
    players: dict[str, dict],
    current_year: int | None = None,
    current_season_match_keys: set[str] | None = None,
    current_season_stat_match_keys: set[str] | None = None,
) -> tuple[
    dict[str, dict[int, dict[str, dict[str, int]]]],
    dict[str, set[str]],
    dict,
    set[tuple[str, str, str, str, str]],
]:
    aggregate: dict[str, dict[int, dict[str, dict[str, int]]]] = defaultdict(dict)
    match_aliases: dict[str, set[str]] = defaultdict(set)
    totals = _coverage_totals()
    seen_matches: set[tuple[str, str, str, str, str]] = set()

    for year in years:
        filename = f"{year}.csv"
        item = _manifest_item(files, filename)
        if not item:
            raise RuntimeError(f"TML data-files manifest is missing {filename}")
        payload = download_cached(
            str(item["url"]),
            filename,
            integer(item.get("size")),
            str(item.get("mtime") or ""),
        )
        rows = csv_rows(payload)
        if not rows:
            raise RuntimeError(f"TML {filename} is empty")
        aggregate_match_rows(
            rows, filename, "tour", aggregate, players, match_aliases, totals, seen_matches,
            current_year=current_year,
            current_match_keys=current_season_match_keys,
            current_stat_match_keys=current_season_stat_match_keys,
        )
        print(
            f"{filename}: {len(rows)} source rows processed; "
            f"{totals['matchResults']} ATP Tour match results so far"
        )
    return aggregate, match_aliases, totals, seen_matches


def aggregate_height_group_extra_files(
    files: dict[str, dict],
    years: range,
    players: dict[str, dict],
    aggregate: dict[str, dict[int, dict[str, dict[str, int]]]],
    match_aliases: dict[str, set[str]],
    seen_matches: set[tuple[str, str, str, str, str]],
    current_year: int,
    current_match_keys: set[str],
    current_stat_match_keys: set[str],
) -> tuple[dict, dict[str, int], dict[str, str]]:
    """Add ATP Challenger and ATP Tour qualifying results without changing Ranking stats."""
    totals = _coverage_totals()
    source_mtimes: dict[str, str] = {}
    for year in years:
        for source_kind, filename in (
            ("challenger", f"{year}_challenger.csv"),
            ("qualifying", f"{year}_atp_quali.csv"),
        ):
            item = _manifest_item(files, filename)
            if not item:
                continue
            payload = download_cached(
                str(item["url"]),
                f"height-groups-{filename}",
                integer(item.get("size")),
                str(item.get("mtime") or ""),
            )
            rows = csv_rows(payload)
            if rows:
                aggregate_match_rows(
                    rows, filename, source_kind, aggregate, players, match_aliases, totals, seen_matches,
                    current_year=current_year,
                    current_match_keys=current_match_keys,
                    current_stat_match_keys=current_stat_match_keys,
                )
            if year == current_year:
                source_mtimes[source_kind] = str(item.get("mtime") or "")
            print(
                f"{filename}: {len(rows)} source rows processed; "
                f"{totals['matchResults']} additional ATP results so far"
            )

    return aggregate, totals, source_mtimes


def compact_player_seasons(
    aggregate: dict[str, dict[int, dict[str, dict[str, int]]]]
) -> dict[str, list[dict]]:
    result: dict[str, list[dict]] = {}
    for player_id, years in aggregate.items():
        seasons = []
        for year, buckets in sorted(years.items()):
            compact = {
                key: value
                for key, value in sorted(buckets.items())
                if value["mp"] > 0
            }
            if compact:
                seasons.append({"y": year, "b": compact})
        result[player_id] = seasons
    return result


def build() -> dict:
    manifest = load_json_url(MANIFEST_URL)
    files = valid_manifest_files(manifest)
    current_year = date.today().year
    years = range(1968, current_year + 1)
    roster, roster_aliases = load_roster(files)
    players = roster
    top100_snapshots = load_top100_season_snapshots(1973, current_year)
    current_season_match_keys: set[str] = set()
    current_season_stat_match_keys: set[str] = set()
    aggregate, match_aliases, totals, seen_matches = aggregate_files(
        files, years, players, current_year,
        current_season_match_keys, current_season_stat_match_keys,
    )
    height_group_current_match_keys = set(current_season_match_keys)
    height_group_current_stat_match_keys = set(current_season_stat_match_keys)
    height_group_aggregate = copy.deepcopy(aggregate)
    height_group_aggregate, extra_coverage, extra_source_mtimes = aggregate_height_group_extra_files(
        files, years, players, height_group_aggregate, match_aliases, seen_matches,
        current_year, height_group_current_match_keys, height_group_current_stat_match_keys,
    )
    # Use stable ATP IDs exposed by the ATP Tour, Challenger and qualifying feeds to crosswalk
    # KickServe names. Country-qualified aliases disambiguate players who share a normalized name.
    for alias, player_ids in match_aliases.items():
        roster_aliases.setdefault(alias, set()).update(player_ids)
    top1000_snapshots = load_top1000_season_snapshots(
        1973, current_year, top100_snapshots, roster_aliases,
    )
    current_expanded_snapshot = top1000_snapshots[current_year]
    if int(current_expanded_snapshot.get("maxRank", 0)) < 1000:
        raise RuntimeError(
            "The live official ATP rank feed only publishes positions through rank "
            f"{current_expanded_snapshot.get('maxRank', 0)}; Top 500/Top 1000 data cannot be built safely"
        )
    # A Top-N cohort is defined by the published rank cutoff, not by an exact
    # number of rows. Tied ranks and skipped/empty official-rank positions mean a
    # complete source list can legitimately contain fewer or more than N players.
    # maxRank above confirms the live feed extends through the requested cutoff;
    # only actual source rows with rank <= N are included in each cohort.

    top100_ranks_by_player: dict[str, dict[int, int]] = defaultdict(dict)
    top1000_ranks_by_player: dict[str, dict[int, int]] = defaultdict(dict)
    for year, snapshot in top100_snapshots.items():
        seen_ids: set[str] = set()
        for entry in snapshot["players"]:
            player_id = str(entry["id"])
            rank = int(entry["rank"])
            if not 1 <= rank <= 100:
                raise RuntimeError(f"TML {year} rank outside the Top 100: {player_id}/{rank}")
            if player_id in seen_ids:
                raise RuntimeError(f"Duplicate TML player ID in {year} Top-100 cohort: {player_id}")
            seen_ids.add(player_id)
            ensure_player(
                players, player_id, str(entry["name"]),
                str(entry.get("country") or ""), None, year,
            )
            top100_ranks_by_player[player_id][year] = rank

    for year, snapshot in top1000_snapshots.items():
        seen_ids: set[str] = set()
        for entry in snapshot["players"]:
            player_id = str(entry["id"])
            rank = int(entry["rank"])
            if not 1 <= rank <= 1000:
                raise RuntimeError(f"{year} rank outside the Top 1000: {player_id}/{rank}")
            if player_id in seen_ids:
                raise RuntimeError(f"Duplicate player ID in {year} expanded rank list: {player_id}")
            seen_ids.add(player_id)
            ensure_player(
                players, player_id, str(entry["name"]),
                str(entry.get("country") or ""), None, year,
            )
            top1000_ranks_by_player[player_id][year] = rank
        unresolved = int(snapshot.get("unresolvedPlayers", 0))
        if len(seen_ids) + unresolved < 100:
            raise RuntimeError(
                f"{year} expanded ranking snapshot has fewer than 100 ranked entries"
            )
        _validate_top100_subset(top100_snapshots[year], snapshot)

    current_top100_count = len(top100_snapshots[current_year]["players"])
    current_top500_count = sum(
        1 for entry in top1000_snapshots[current_year]["players"] if int(entry["rank"]) <= 500
    )
    current_top1000_count = len(top1000_snapshots[current_year]["players"])
    current_top100_as_of = str(top100_snapshots[current_year]["asOf"])
    current_top100_source = str(top100_snapshots[current_year]["source"])
    season_rows = compact_player_seasons(aggregate)
    height_group_season_rows = compact_player_seasons(height_group_aggregate)
    player_rows = []
    # Include all Top-1000 members, including players absent from the match archive.
    archive_player_ids = set(season_rows) | set(height_group_season_rows) | set(top1000_ranks_by_player)
    for player_id in archive_player_ids:
        record = players[player_id]
        height = record.get("heightCm")
        player_rows.append(
            {
                "id": player_id,
                "name": record.get("name") or player_id,
                "country": record.get("country"),
                "heightCm": height,
                "heightSource": record.get("heightSource"),
                "currentRank": top100_ranks_by_player.get(player_id, {}).get(current_year),
                "top100RanksBySeason": [
                    {"y": year, "rank": rank}
                    for year, rank in sorted(top100_ranks_by_player.get(player_id, {}).items())
                ],
                "top1000RanksBySeason": [
                    {"y": year, "rank": rank}
                    for year, rank in sorted(top1000_ranks_by_player.get(player_id, {}).items())
                ],
                "seasons": season_rows.get(player_id, []),
                "heightGroupSeasons": height_group_season_rows.get(player_id, []),
            }
        )
    player_rows.sort(key=lambda item: (item["name"].casefold(), item["id"]))

    height_count = sum(1 for player in player_rows if player["heightCm"] is not None)
    stat_seasons = sorted({
        season["y"]
        for player in player_rows
        for season in player["seasons"]
        if season["b"].get("all", {}).get("sm", 0) > 0
    })
    payload = {
        "meta": {
            "generated": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
            "firstSeason": 1968,
            "lastSeason": current_year,
            "currentSeasonSourceMtime": str(files.get(f"{current_year}.csv", {}).get("mtime") or ""),
            "currentSeasonMatchKeys": sorted(current_season_match_keys),
            "currentSeasonStatMatchKeys": sorted(current_season_stat_match_keys),
            "heightGroupCurrentSeasonMatchKeys": sorted(height_group_current_match_keys),
            "heightGroupCurrentSeasonStatMatchKeys": sorted(height_group_current_stat_match_keys),
            "currentSeasonChallengerSourceMtime": extra_source_mtimes.get("challenger", ""),
            "currentSeasonQualifyingSourceMtime": extra_source_mtimes.get("qualifying", ""),
            "heightGroupCoverage": {
                "tour": totals,
                "challengerAndQualifying": extra_coverage,
            },
            "players": len(player_rows),
            "playersWithHeight": height_count,
            "playersInCurrentTop100": current_top100_count,
            "playersInCurrentTop500": current_top500_count,
            "playersInCurrentTop1000": current_top1000_count,
            "currentTop100AsOf": current_top100_as_of,
            "currentTop100Source": current_top100_source,
            "currentTop100Policy": "Latest dated TML ATP Top-100 snapshot on or before today for the live current season; stable player IDs.",
            "firstTop100Season": 1973,
            "top100SeasonSnapshots": [
                {
                    "year": year,
                    "asOf": snapshot["asOf"],
                    "source": snapshot["source"],
                    "players": len(snapshot["players"]),
                    "unresolvedPlayers": int(snapshot.get("unresolvedPlayers", 0)),
                    "rankedEntries": len(snapshot["players"]) + int(snapshot.get("unresolvedPlayers", 0)),
                }
                for year, snapshot in sorted(top100_snapshots.items())
            ],
            "top100SeasonPolicy": "For completed seasons, use the final TML ATP ranking snapshot published within that calendar year. For the live current season, use the latest available dated snapshot on or before today. Preserve co-ranked players; count rows without stable player IDs separately and exclude them from player-level height calculations. Each cohort is analyzed against match statistics from the same calendar year.",
            "firstTop1000Season": 1973,
            "top1000SeasonSnapshots": [
                {
                    "year": year,
                    "asOf": snapshot["asOf"],
                    "source": snapshot["source"],
                    "players": len(snapshot["players"]),
                    "unresolvedPlayers": int(snapshot.get("unresolvedPlayers", 0)),
                    "rankedEntries": len(snapshot["players"]) + int(snapshot.get("unresolvedPlayers", 0)),
                    "maxRank": int(snapshot.get("maxRank", 0)),
                    "unresolvedTop500": int(snapshot.get("unresolvedTop500", 0)),
                    "provider": str(snapshot.get("provider") or "TML rankings archive"),
                    "unresolvedEntries": [
                        {
                            "rank": int(entry.get("rank", 0)),
                            "name": str(entry.get("name") or ""),
                            "country": str(entry.get("country") or ""),
                            "reason": str(entry.get("reason") or ""),
                        }
                        for entry in snapshot.get("unresolvedEntries", [])
                    ],
                }
                for year, snapshot in sorted(top1000_snapshots.items())
            ],
            "top1000SeasonPolicy": "For completed seasons, the full TML Rankings archive CSV is filtered to the exact final-in-year date already used by the Top 100 snapshot. For the live year, the full KickServe ATP CSV official_rank column is used and its Top 100 membership is cross-checked against the unchanged TML Top 100 snapshot. KickServe's daily CSV is free to use with attribution to https://kick-serve.com/data/. Ties are preserved and unresolved IDs are counted separately. Snapshots that do not extend through a selected cutoff are marked unavailable. Group performance always uses match statistics from that same calendar year.",
            "source": DATA_SOURCE,
            "sourceUrl": DATA_SOURCE_URL,
            "seasonDefinition": "Calendar year in the TML tournament ID; not a rolling 52-week window.",
            "statistics": "Hold% = service games won / service games played. Break% = return games won / return games played. Combined = Hold% + Break%.",
            "sampleNote": "Ranking tables use ATP Tour-level singles main-draw results. Height-group statistics use the separate heightGroupSeasons scope: ATP Tour main draws plus ATP Challenger main draws and ATP Tour qualifying where the source is available. Results include retirements in W-L; walkovers and no-play defaults are excluded. Retirement match statistics are not used for Hold/Break because the match sample is partial. Missing or inconsistent stat lines contribute results but no service/return games.",
            "environmentNote": "TML indoor Yes/No values and equivalent I/O, Boolean and 1/0 encodings are normalized. Unknown hard-court settings stay in All hard but not in Indoor hard or Outdoor hard.",
            "statsAvailableSeasons": stat_seasons,
            "heightGroupStatsAvailableSeasons": sorted({
                season["y"]
                for player in player_rows
                for season in player["heightGroupSeasons"]
                if season["b"].get("all", {}).get("sm", 0) > 0
            }),
            "tourLevels": sorted(TOUR_LEVELS),
            "coverage": totals,
            "playersWithEligibleArchiveResults": len(season_rows),
            "usage": "Personal, educational and non-commercial research only. Raw source CSVs are not included. Keep this derived asset private; do not redistribute or use commercially.",
            "heightBandsCm": [
                {"id": "under183", "minCm": None, "maxCm": 182, "label": "Under 1.83 m"},
                {"id": "183to185", "minCm": 183, "maxCm": 185, "label": "1.83–1.85 m"},
                {"id": "186to188", "minCm": 186, "maxCm": 188, "label": "1.86–1.88 m"},
                {"id": "189to191", "minCm": 189, "maxCm": 191, "label": "1.89–1.91 m"},
                {"id": "192to194", "minCm": 192, "maxCm": 194, "label": "1.92–1.94 m"},
                {"id": "195plus", "minCm": 195, "maxCm": None, "label": "1.95 m+"},
            ],
        },
        "players": player_rows,
    }
    return payload


def main() -> None:
    payload = build()
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n"
    for path in OUTPUTS:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(path.suffix + ".tmp")
        temporary.write_text(encoded, encoding="utf-8")
        temporary.replace(path)
        print(f"wrote {path}: {len(payload['players'])} players, {len(encoded)} bytes")
    meta = payload["meta"]
    print(
        "player stats coverage: "
        f"{meta['coverage']['matchResults']} tour match results, "
        f"{meta['coverage']['matchesWithValidStats']} with valid service/return stats, "
        f"{meta['playersInCurrentTop100']} current Top 100 players mapped"
    )

    current_year = int(meta["lastSeason"])
    expanded_snapshot = next(
        row for row in meta["top1000SeasonSnapshots"] if row["year"] == current_year
    )
    cohort = []
    for player in payload["players"]:
        rank = next(
            (entry["rank"] for entry in player.get("top1000RanksBySeason", [])
             if entry["y"] == current_year),
            None,
        )
        if rank is not None and rank <= 1000:
            cohort.append(player)

    known = [player for player in cohort if player.get("heightCm") is not None]
    missing = len(cohort) - len(known)
    height_sources: dict[str, int] = defaultdict(int)
    for player in known:
        height_sources[str(player.get("heightSource") or "source not recorded")] += 1

    print(
        f"current Top 1000 ({current_year}): "
        f"{len(cohort)} identified, {expanded_snapshot['unresolvedPlayers']} unresolved rank rows, "
        f"{len(known)} heights known, {missing} heights missing"
    )
    print(
        "Top-1000 height provenance: "
        + ", ".join(f"{source}={count}" for source, count in sorted(height_sources.items()))
    )

    for band in meta["heightBandsCm"]:
        members = [player for player in cohort
                   if height_band_id(valid_height(player.get("heightCm"))) == band["id"]]
        with_results = 0
        with_valid_stats = 0
        result_player_rows = 0
        valid_stat_player_rows = 0
        for player in members:
            season = next(
                (entry for entry in player.get("heightGroupSeasons", [])
                 if entry["y"] == current_year),
                {},
            )
            bucket = (season.get("b") or {}).get("all") or {}
            matches = int(bucket.get("mp", 0))
            valid_matches = int(bucket.get("sm", 0))
            with_results += matches > 0
            with_valid_stats += valid_matches > 0
            result_player_rows += matches
            valid_stat_player_rows += valid_matches
        print(
            f"height group {band['label']}: players={len(members)}, "
            f"withResults={with_results}, withValidStats={with_valid_stats}, "
            f"playerMatchResults={result_player_rows}, playerMatchStats={valid_stat_player_rows}"
        )

    lower_tier = (meta.get("heightGroupCoverage") or {}).get("challengerAndQualifying") or {}
    print(
        "expanded lower-tier coverage: "
        f"{lower_tier.get('matchResults', 0)} match results, "
        f"{lower_tier.get('matchesWithValidStats', 0)} with valid service/return stats, "
        f"{lower_tier.get('matchesWithoutValidStats', 0)} without valid stats, "
        f"{lower_tier.get('excludedNoPlay', 0)} no-play rows excluded"
    )


if __name__ == "__main__":
    main()
