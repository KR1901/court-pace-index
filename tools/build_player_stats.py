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
TOP100_PATH = ROOT / "data" / "current_atp_top100.csv"
OUTPUTS = (
    ROOT / "data" / "player_stats.json",
    ROOT / "app" / "src" / "main" / "assets" / "player_stats.json",
)
RANKING_AS_OF = "2026-11-09"
RANKING_SOURCE = "https://www.tennis.esp.br/en/rankings?circuito=ATP&data=2026-11-09"
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


def match_is_completed(score: str | None, winner_id: str | None, loser_id: str | None) -> bool:
    if not winner_id or not loser_id or winner_id == loser_id or not (score or "").strip():
        return False
    upper = (score or "").upper()
    return not bool(re.search(r"\b(?:RET|W/O|W\.O\.|WO|WALKOVER|DEF|ABD|ABN|ABANDONED)\b", upper))


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


def match_buckets(surface: str | None, indoor: str | None) -> tuple[str, ...]:
    surface = (surface or "").strip().casefold()
    indoor = (indoor or "").strip().upper()
    if surface == "hard":
        if indoor == "I":
            setting = "hardIndoor"
        elif indoor == "O":
            setting = "hardOutdoor"
        else:
            setting = "hardUnknown"
        return ("all", "hardAll", setting)
    mapping = {"clay": "clay", "grass": "grass", "carpet": "carpet"}
    bucket = mapping.get(surface)
    return ("all", bucket) if bucket else ("all",)


def empty_counter() -> dict[str, int]:
    return {key: 0 for key in BASE_FIELDS}


def load_json_url(url: str) -> dict:
    request = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    with urlopen(request, timeout=60) as response:
        payload = response.read()
    return json.loads(payload.decode("utf-8-sig"))


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


def load_top100() -> list[dict[str, object]]:
    if not TOP100_PATH.is_file():
        raise RuntimeError(f"Missing pinned current Top 100 file: {TOP100_PATH}")
    rows = csv_rows(TOP100_PATH.read_bytes())
    top100 = []
    seen_ranks: set[int] = set()
    seen_names: set[str] = set()
    for row in rows:
        rank = integer(row.get("rank"))
        name = (row.get("name") or "").strip()
        country = (row.get("country") or "").strip().upper()
        points = integer(row.get("points"))
        key = normalize_name(name)
        if rank is None or not 1 <= rank <= 100 or not name or not key:
            raise RuntimeError(f"Invalid Top 100 CSV row: {row}")
        if rank in seen_ranks or key in seen_names:
            raise RuntimeError(f"Duplicate Top 100 rank/name: {rank}/{name}")
        seen_ranks.add(rank)
        seen_names.add(key)
        top100.append({"rank": rank, "name": name, "country": country, "points": points})
    if sorted(seen_ranks) != list(range(1, 101)) or len(top100) != 100:
        raise RuntimeError("Pinned ranking snapshot must contain exactly ranks 1 through 100")
    return sorted(top100, key=lambda item: int(item["rank"]))


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
    years = aggregate[player_id]
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
    aliases: dict[str, set[str]],
    players: dict[str, dict],
) -> dict[str, int]:
    result: dict[str, int] = {}
    missing: list[str] = []
    ambiguous: list[str] = []
    for entry in top100:
        name = str(entry["name"])
        key = normalize_name(name)
        candidates = set(aliases.get(key, set()))
        if len(candidates) > 1:
            country = str(entry.get("country") or "")
            country_matches = {
                player_id for player_id in candidates
                if str(players.get(player_id, {}).get("country") or "").upper() == country
            }
            if len(country_matches) == 1:
                candidates = country_matches
        if not candidates:
            missing.append(name)
            continue
        if len(candidates) != 1:
            ambiguous.append(f"{name} -> {sorted(candidates)}")
            continue
        player_id = next(iter(candidates))
        if player_id in result:
            ambiguous.append(f"{name} and another ranking row map to {player_id}")
            continue
        result[player_id] = int(entry["rank"])
    if missing or ambiguous or len(result) != 100:
        raise RuntimeError(
            "Current Top 100 identity mapping failed. "
            f"Missing={missing[:12]}; ambiguous={ambiguous[:12]}; mapped={len(result)}/100"
        )
    return result


def aggregate_files(
    files: dict[str, dict],
    years: range,
    players: dict[str, dict],
) -> tuple[dict[str, dict[int, dict[str, dict[str, int]]]], dict[str, set[str]], dict]:
    aggregate: dict[str, dict[int, dict[str, dict[str, int]]]] = defaultdict(dict)
    match_aliases: dict[str, set[str]] = defaultdict(set)
    totals = {
        "sourceRows": 0,
        "completedTourMatches": 0,
        "matchesWithValidStats": 0,
        "excludedNoPlayOrRetirement": 0,
        "excludedIneligibleLevel": 0,
        "excludedInvalidStatsSides": 0,
    }
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
            "tourney_level", "winner_id", "winner_name", "winner_ht", "winner_ioc",
            "loser_id", "loser_name", "loser_ht", "loser_ioc", "score",
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
            if not match_is_completed(row.get("score"), winner_id, loser_id):
                totals["excludedNoPlayOrRetirement"] += 1
                continue
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
            stats = service_return_contribution(row)
            if stats is None:
                totals["excludedInvalidStatsSides"] += 1
                add_player_match(
                    aggregate, winner_id, season, buckets, True, 0, 0, 0, 0, False
                )
                add_player_match(
                    aggregate, loser_id, season, buckets, False, 0, 0, 0, 0, False
                )
                totals["completedTourMatches"] += 1
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
            totals["completedTourMatches"] += 1
            totals["matchesWithValidStats"] += 1
        print(
            f"{filename}: {len(rows)} source rows processed; "
            f"{totals['completedTourMatches']} completed tour matches so far"
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
    years = range(1967, current_year + 1)
    roster, roster_aliases = load_roster(files)
    players = roster
    top100 = load_top100()
    aggregate, match_aliases, totals = aggregate_files(files, years, players)

    aliases: dict[str, set[str]] = defaultdict(set)
    for key, ids in roster_aliases.items():
        aliases[key].update(ids)
    for key, ids in match_aliases.items():
        aliases[key].update(ids)
    top100_ranks = resolve_current_top100(top100, aliases, players)
    season_rows = compact_player_seasons(aggregate)
    player_rows = []
    for player_id, record in players.items():
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
            "firstSeason": min(years),
            "lastSeason": current_year,
            "players": len(player_rows),
            "playersWithHeight": height_count,
            "playersInCurrentTop100": len(top100_ranks),
            "currentTop100AsOf": RANKING_AS_OF,
            "currentTop100Source": RANKING_SOURCE,
            "source": DATA_SOURCE,
            "sourceUrl": DATA_SOURCE_URL,
            "seasonDefinition": "Calendar year in the TML tournament ID; not a rolling 52-week window.",
            "statistics": "Hold% = service games won / service games played. Break% = return games won / return games played. Combined = Hold% + Break%.",
            "sampleNote": "Only completed ATP tour-level singles matches are used. Missing or inconsistent match-stat lines contribute results but no service/return games.",
            "statsAvailableSeasons": stat_seasons,
            "tourLevels": sorted(TOUR_LEVELS),
            "coverage": totals,
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
        f"{meta['coverage']['completedTourMatches']} completed matches, "
        f"{meta['coverage']['matchesWithValidStats']} with valid service/return stats, "
        f"{meta['playersInCurrentTop100']} current Top 100 players mapped"
    )


if __name__ == "__main__":
    main()
