#!/usr/bin/env python3
"""Build a private, derived ATP player-statistics asset from TML annual ATP files.

Raw CSVs are downloaded to .cache/player_stats and are never added to the repository.
The output is personal/non-commercial research data and must remain in this private repo.
"""
from __future__ import annotations

import csv
import hashlib
import io
import json
import math
import re
import time
import unicodedata
from collections import defaultdict
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
    if height_cm <= 190:
        return "183to190"
    if height_cm <= 194:
        return "191to194"
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
            key = normalize_name(label)
            if key:
                aliases[key].add(player_id)
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


def parse_current_top100(payload: dict) -> list[dict[str, object]]:
    """Validate a dated TML Top-100 response and preserve stable player IDs."""
    rows = payload.get("rankings")
    if not isinstance(rows, list):
        raise RuntimeError("TML ranking endpoint did not return a rankings array")
    top100: list[dict[str, object]] = []
    seen_ranks: set[int] = set()
    seen_ids: set[str] = set()
    for row in rows:
        if not isinstance(row, dict):
            continue
        rank = integer(row.get("rank"))
        if rank is None or not 1 <= rank <= 100:
            continue
        player_id = str(row.get("id") or "").strip()
        name = str(row.get("name") or "").strip()
        country = str(row.get("ioc") or row.get("country") or "").strip().upper()
        points = integer(row.get("points"))
        if not player_id or not name:
            raise RuntimeError(f"Invalid TML Top 100 row at rank {rank}: missing player ID/name")
        if rank in seen_ranks or player_id in seen_ids:
            raise RuntimeError(f"Duplicate TML Top 100 rank/player ID: {rank}/{player_id}")
        seen_ranks.add(rank)
        seen_ids.add(player_id)
        top100.append({"id": player_id, "rank": rank, "name": name, "country": country, "points": points})
    if sorted(seen_ranks) != list(range(1, 101)) or len(top100) != 100:
        raise RuntimeError(f"TML ranking snapshot must contain exactly ranks 1–100; found {len(top100)}")
    return sorted(top100, key=lambda item: int(item["rank"]))


def load_current_top100() -> tuple[str, str, list[dict[str, object]]]:
    dates_payload = load_json_url(RANKING_DATES_URL)
    ranking_date = select_ranking_snapshot_date(dates_payload)
    source_url = f"{RANKING_URL}?date={ranking_date}"
    snapshot = load_json_url(source_url)
    return ranking_date, source_url, parse_current_top100(snapshot)


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


def aggregate_files(
    files: dict[str, dict],
    years: range,
    players: dict[str, dict],
    current_year: int | None = None,
    current_season_match_keys: set[str] | None = None,
    current_season_stat_match_keys: set[str] | None = None,
) -> tuple[dict[str, dict[int, dict[str, dict[str, int]]]], dict[str, set[str]], dict]:
    aggregate: dict[str, dict[int, dict[str, dict[str, int]]]] = defaultdict(dict)
    match_aliases: dict[str, set[str]] = defaultdict(set)
    totals = {
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
    seen_matches: set[tuple[str, str, str, str]] = set()
    for year in years:
        filename = f"{year}.csv"
        item = files.get(filename)
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
        required = {
            "tourney_id", "tourney_name", "surface", "indoor", "tourney_date",
            "tourney_level", "match_num", "winner_id", "winner_name", "winner_ht", "winner_ioc",
            "loser_id", "loser_name", "loser_ht", "loser_ioc", "score",
            "w_SvGms", "w_bpFaced", "w_bpSaved", "l_SvGms", "l_bpFaced", "l_bpSaved",
        }
        missing = required - set(rows[0])
        if missing:
            raise RuntimeError(f"{filename} is missing required columns: {sorted(missing)}")
        totals["sourceRows"] += len(rows)
        for row in rows:
            if (row.get("tourney_level") or "").strip().upper() not in TOUR_LEVELS:
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
            if not match_has_result(row.get("score"), winner_id, loser_id):
                totals["excludedNoPlay"] += 1
                continue
            match_num = (row.get("match_num") or "").strip()
            stable_key = None
            if match_num:
                identity = (tournament_id, match_num, min(winner_id, loser_id), max(winner_id, loser_id))
                stable_key = match_identity_key(tournament_id, match_num, winner_id, loser_id)
                if identity in seen_matches:
                    totals["duplicateMatchRowsSkipped"] += 1
                    continue
                seen_matches.add(identity)
                if current_season_match_keys is not None and season == current_year:
                    current_season_match_keys.add(stable_key)
            retirement = match_is_retirement(row.get("score"))
            if retirement:
                totals["retiredMatchResults"] += 1
            ensure_player(
                players, winner_id, winner_name, row.get("winner_ioc"),
                row.get("winner_ht"), season,
            )
            ensure_player(
                players, loser_id, loser_name, row.get("loser_ioc"),
                row.get("loser_ht"), season,
            )
            for alias, player_id in ((winner_name, winner_id), (loser_name, loser_id)):
                key = normalize_name(alias)
                if key:
                    match_aliases[key].add(player_id)

            buckets = match_buckets(row.get("surface"), row.get("indoor"))
            if (row.get("surface") or "").strip().casefold() == "hard" and "hardUnknown" in buckets:
                totals["hardRowsWithUnknownEnvironment"] += 1
            # Retirements count in W-L results, but partial-match games do not train annual rates.
            stats = None if retirement else service_return_contribution(row)
            if (
                current_season_stat_match_keys is not None
                and season == current_year
                and stable_key is not None
                and stats is not None
            ):
                current_season_stat_match_keys.add(stable_key)
            if stats is None:
                totals["matchesWithoutValidStats"] += 1
                add_player_match(
                    aggregate, winner_id, season, buckets, True, 0, 0, 0, 0, False
                )
                add_player_match(
                    aggregate, loser_id, season, buckets, False, 0, 0, 0, 0, False
                )
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
        print(
            f"{filename}: {len(rows)} source rows processed; "
            f"{totals['matchResults']} tour match results so far"
        )
    return aggregate, match_aliases, totals


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
    ranking_as_of, ranking_source, top100 = load_current_top100()
    current_season_match_keys: set[str] = set()
    current_season_stat_match_keys: set[str] = set()
    aggregate, _match_aliases, totals = aggregate_files(
        files, years, players, current_year,
        current_season_match_keys, current_season_stat_match_keys,
    )
    top100_ranks = resolve_current_top100(top100, players, current_year)
    season_rows = compact_player_seasons(aggregate)
    player_rows = []
    # All archive players are included, plus a current Top-100 player if absent from the archive.
    archive_player_ids = set(season_rows) | set(top100_ranks)
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
                "currentRank": top100_ranks.get(player_id),
                "seasons": season_rows.get(player_id, []),
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
            "players": len(player_rows),
            "playersWithHeight": height_count,
            "playersInCurrentTop100": len(top100_ranks),
            "currentTop100AsOf": ranking_as_of,
            "currentTop100Source": ranking_source,
            "currentTop100Policy": "Latest dated TML ATP Top-100 snapshot at refresh time; stable player IDs; same cohort followed backward through historical calendar years.",
            "source": DATA_SOURCE,
            "sourceUrl": DATA_SOURCE_URL,
            "seasonDefinition": "Calendar year in the TML tournament ID; not a rolling 52-week window.",
            "statistics": "Hold% = service games won / service games played. Break% = return games won / return games played. Combined = Hold% + Break%.",
            "sampleNote": "ATP tour-level singles match results include retirements in W-L. Walkovers and no-play defaults are excluded. Retirement match statistics are not used for Hold/Break because the match sample is partial. Missing or inconsistent stat lines contribute results but no service/return games.",
            "environmentNote": "TML indoor Yes/No values and equivalent I/O, Boolean and 1/0 encodings are normalized. Unknown hard-court settings stay in All hard but not in Indoor hard or Outdoor hard.",
            "statsAvailableSeasons": stat_seasons,
            "tourLevels": sorted(TOUR_LEVELS),
            "coverage": totals,
            "playersWithEligibleArchiveResults": len(season_rows),
            "usage": "Personal, educational and non-commercial research only. Raw source CSVs are not included. Keep this derived asset private; do not redistribute or use commercially.",
            "heightBandsCm": [
                {"id": "under183", "minCm": None, "maxCm": 182, "label": "Under 1.83 m"},
                {"id": "183to190", "minCm": 183, "maxCm": 190, "label": "1.83–1.90 m"},
                {"id": "191to194", "minCm": 191, "maxCm": 194, "label": "1.91–1.94 m"},
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


if __name__ == "__main__":
    main()
