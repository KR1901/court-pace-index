#!/usr/bin/env python3
"""Computes an ace-based surface speed rating per tournament per season.

Reimplements the published method (Tennis Abstract): for every match, compare the aces
actually hit with the aces the same server would have been expected to hit against that same
returner on a neutral surface, then average the ratios across the event and normalise so a
tour-average event for the season reads 1.00.

Data: Jeff Sackmann ATP archive for historical seasons; TennisMyLife ATP database for the
current season. Only tour-level main-draw matches with serve stats are used.

Run:  python3 tools/compute_speed.py            # compute and validate against 2026
      python3 tools/compute_speed.py --write    # write speed history + live player baseline
"""
import csv, hashlib, io, json, math, os, random, sys, urllib.request, unicodedata
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
from urllib.error import HTTPError

from davis_cup_policy import TML_DAVIS_CUP_SOURCE, is_supported_davis_cup_calculation_year

CACHE = os.path.join(os.path.dirname(__file__), "..", ".cache", "atp")
MIRROR = "https://raw.githubusercontent.com/Aneeshers/tennis-sackmann-archive/main/atp/atp_matches_{}.csv"
TML_CURRENT_URL = "https://stats.tennismylife.org/data/{}.csv"
TML_ONGOING_URL = "https://stats.tennismylife.org/data/ongoing_tourneys.csv"
PROFILE_HISTORY_START_YEAR = 1991
YEARS = range(PROFILE_HISTORY_START_YEAR, date.today().year + 1)
LEVELS = {"G", "M", "A", "F", "250", "500"}  # Standard ATP tour events
TARGET_LEVELS = LEVELS | {"D"}  # Davis Cup is a separate target, never ATP training
SOURCE_BY_YEAR = {}
SOURCE_METADATA_BY_YEAR = {}
MIN_MATCHES_PER_SURFACE = 3
MIN_EVENT_RATING_SERVER_SIDES = 1
MIN_NORMALISER_SERVER_SIDES = 20
# Legacy archives expose tournament start/week dates, not individual match days.
# Bound the latest possible source event day by a conservative maximum duration for
# its level, instead of pretending every event lasts exactly fourteen days.
LEGACY_MAX_EVENT_DAYS_BY_LEVEL = {
    "G": 15,   # Grand Slam main-draw window
    "M": 12,   # Extended Masters 1000 window
    "F": 8,    # ATP Finals / Next Gen week
    "A": 12,   # Historical ATP umbrella / multi-day team formats
    "500": 8,
    "250": 8,
}
LEGACY_UNKNOWN_MAX_EVENT_DAYS = 15
MIN_EVENT_CI_MATCH_CLUSTERS = 5
EVENT_CI_BOOTSTRAP_ITERATIONS = 1000
PROFILE_PRIOR_SERVICE_POINTS = 150.0
TML_CACHE_MAX_AGE_DAYS = 7
LIVE_EVENT_FEED_MAX_AGE_DAYS = 7

# CSV event name -> tournament id used by the app
NAMES = {
    "Australian Open": "ausopen", "Roland Garros": "rolandgarros", "Wimbledon": "wimbledon",
    "Us Open": "usopen", "US Open": "usopen",
    "Indian Wells Masters": "indianwells", "Miami Masters": "miami",
    "Monte Carlo Masters": "montecarlo", "Madrid Masters": "madrid",
    "Rome Masters": "rome", "Canada Masters": "canada",
    "Cincinnati Masters": "cincinnati", "Shanghai Masters": "shanghai",
    "Paris Masters": "paris", "Tour Finals": "atpfinals", "ATP Finals": "atpfinals", "Nitto ATP Finals": "atpfinals",
    "Brisbane": "brisbane", "Hong Kong": "hongkong", "United Cup": "unitedcup", "Laver Cup": "lavercup", "Auckland": "auckland",
    # TennisMyLife uses these shorter/alternate event labels in its ATP CSV.
    "Monte Carlo": "montecarlo", "Cincinnati": "cincinnati", "Shanghai": "shanghai",
    "'s-Hertogenbosch": "shertogenbosch", "’s-Hertogenbosch": "shertogenbosch",
    "Adelaide": "adelaide", "Adelaide International": "adelaide",
    "Adelaide 1": "adelaide", "Adelaide 2": "adelaide",
    "Adelaide International 1": "adelaide", "Adelaide International 2": "adelaide",
    "Montpellier": "montpellier", "Dallas": "dallas",
    "Rotterdam": "rotterdam", "Delray Beach": "delraybeach",
    "Buenos Aires": "buenosaires", "Rio de Janeiro": "rio", "Rio De Janeiro": "rio",
    "ATP Rio de Janeiro": "rio", "Rio Open": "rio", "Doha": "doha",
    "Acapulco": "acapulco", "Dubai": "dubai", "Santiago": "santiago",
    "Marseille": "marseille", "Open 13 Provence": "marseille",
    "Stockholm": "stockholm", "Stockholm Open": "stockholm",
    "Next Gen Finals": "nextgen", "Next Gen ATP Finals": "nextgen",
    "Next Generation ATP Finals": "nextgen",
    "Marrakech": "marrakech", "Houston": "houston", "Bucharest": "bucharest",
    "Estoril": "estoril", "Barcelona": "barcelona", "Munich": "munich",
    "Hamburg": "hamburg", "Geneva": "geneva", "Lyon": "lyon",
    "Stuttgart": "stuttgart", "s Hertogenbosch": "shertogenbosch",
    "Halle": "halle", "Queen's Club": "queens", "Mallorca": "mallorca",
    "Eastbourne": "eastbourne", "Bastad": "bastad", "Gstaad": "gstaad",
    "Kitzbuhel": "kitzbuhel", "Umag": "umag", "Washington": "washington",
    "Los Cabos": "losCabos", "Winston-Salem": "winstonsalem",
    "Chengdu": "chengdu", "Hangzhou": "hangzhou", "Beijing": "beijing",
    "Tokyo": "tokyo", "Almaty": "almaty", "Brussels": "brussels",
    "Vienna": "vienna", "Basel": "basel", "Metz": "metz", "Athens": "athens",
}


def _normalise_event_name(value):
    """Normalise source-specific event labels without fuzzy or partial matching."""
    value = unicodedata.normalize("NFKD", str(value or "")).casefold()
    return "".join(character for character in value if character.isalnum())


_NORMALISED_EVENT_NAMES = {}
for _event_label, _event_id in NAMES.items():
    _event_key = _normalise_event_name(_event_label)
    _existing_event_id = _NORMALISED_EVENT_NAMES.get(_event_key)
    if _existing_event_id is not None and _existing_event_id != _event_id:
        raise ValueError(f"ambiguous normalised event alias: {_event_label}")
    _NORMALISED_EVENT_NAMES[_event_key] = _event_id


def tournament_id(event_name):
    """Resolve exact event labels and punctuation/case variants to a known ID."""
    label = str(event_name or "").strip()
    normalised = _normalise_event_name(label)
    # The archive stores separate Davis Cup tie labels. Group them into one annual target.
    if normalised.startswith("daviscup"):
        return "daviscup"
    return NAMES.get(label) or _NORMALISED_EVENT_NAMES.get(normalised)


def tml_cache_is_fresh(metadata_path, now=None, max_age_days=TML_CACHE_MAX_AGE_DAYS):
    """A cached current-season CSV needs a recent manifest and a matching content hash."""
    try:
        with open(metadata_path, encoding="utf-8") as source:
            metadata = json.load(source)
        fetched = datetime.fromisoformat(metadata["fetchedAtUtc"].replace("Z", "+00:00"))
        if fetched.tzinfo is None:
            fetched = fetched.replace(tzinfo=timezone.utc)
        current = now or datetime.now(timezone.utc)
        if current.tzinfo is None:
            current = current.replace(tzinfo=timezone.utc)
        age = current - fetched
        if not timedelta(seconds=-900) <= age <= timedelta(days=max_age_days):
            return False

        metadata_name = os.fspath(metadata_path)
        suffix = ".meta.json"
        if not metadata_name.endswith(suffix):
            return False
        csv_path = metadata_name[:-len(suffix)]
        expected_bytes = int(metadata["bytes"])
        expected_sha256 = str(metadata["sha256"])
        if not os.path.isfile(csv_path) or os.path.getsize(csv_path) != expected_bytes:
            return False
        digest = hashlib.sha256()
        with open(csv_path, "rb") as cached:
            for chunk in iter(lambda: cached.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest() == expected_sha256
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
        return False



def load_tml_ongoing_davis_cup(year):
    """Load only current-year Davis Cup rows from TML's live supplement.

    Raw CSVs stay in the temporary build cache; they are never bundled or committed.
    A header-only feed is valid when no new rubbers are published yet.
    """
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, "tml_ongoing_tourneys.csv")
    metadata_path = path + ".meta.json"
    payload = None
    metadata = None
    try:
        request = urllib.request.Request(
            TML_ONGOING_URL,
            headers={"User-Agent": "CourtPaceIndex/1.0", "Accept": "text/csv"},
        )
        with urllib.request.urlopen(request, timeout=45) as response:
            payload = response.read()
        if not payload.startswith(b"tourney_id,") or b"w_svpt" not in payload[:4096]:
            raise ValueError("TML ongoing feed returned an unexpected CSV schema")
        parsed = list(csv.DictReader(io.StringIO(payload.decode("utf-8-sig"))))
        required = {
            "tourney_id", "tourney_level", "tourney_name", "tourney_date", "match_num",
            "winner_id", "winner_name", "loser_id", "loser_name", "score",
            "w_ace", "l_ace", "w_svpt", "l_svpt",
        }
        header = set((payload.decode("utf-8-sig").splitlines() or [""])[0].split(","))
        if not required.issubset(header):
            raise ValueError("TML ongoing feed is missing required fields")
        if parsed and not required.issubset(parsed[0]):
            raise ValueError("TML ongoing feed is missing required fields")
        with open(path, "wb") as cached:
            cached.write(payload)
        metadata = {
            "source": "TennisMyLife ATP Match Database ongoing tournament feed",
            "url": TML_ONGOING_URL,
            "fetchedAtUtc": datetime.now(timezone.utc).isoformat(),
            "bytes": len(payload),
            "sha256": hashlib.sha256(payload).hexdigest(),
            "matchRows": len(parsed),
        }
        with open(metadata_path, "w", encoding="utf-8") as output:
            json.dump(metadata, output, indent=2)
    except (HTTPError, urllib.error.URLError, TimeoutError, ValueError, OSError) as error:
        if not os.path.exists(path) or not tml_cache_is_fresh(metadata_path):
            print(f"warning: TML ongoing Davis Cup supplement unavailable: {error}")
            return [], None
        with open(metadata_path, encoding="utf-8") as source:
            metadata = json.load(source)
        with open(path, "rb") as cached:
            payload = cached.read()
        print(f"warning: TML ongoing refresh failed ({error}); using a fresh cached supplement")

    parsed = list(csv.DictReader(io.StringIO(payload.decode("utf-8-sig"))))
    davis_rows = []
    for row in parsed:
        if row.get("tourney_level") != "D":
            continue
        if not _normalise_event_name(row.get("tourney_name")).startswith("daviscup"):
            continue
        match_date = str(row.get("tourney_date") or "")
        if len(match_date) < 4 or not match_date[:4].isdigit() or int(match_date[:4]) != int(year):
            continue
        davis_rows.append(row)
    metadata = dict(metadata or {})
    metadata["davisCupRows"] = len(davis_rows)
    return davis_rows, metadata


def davis_cup_match_key(row):
    """Stable rubber key across annual and ongoing TML snapshots."""
    tournament_id = str(row.get("tourney_id") or "").strip()
    match_date = str(row.get("tourney_date") or "").strip()
    match_number = str(row.get("match_num") or "").strip()
    if tournament_id and match_date and match_number:
        players = tuple(sorted((
            str(row.get("winner_id") or "").strip(),
            str(row.get("loser_id") or "").strip(),
        )))
        return ("rubber", tournament_id, match_date, match_number, *players)
    return (
        "fallback",
        tournament_id,
        match_date,
        str(row.get("winner_id") or "").strip(),
        str(row.get("loser_id") or "").strip(),
        str(row.get("winner_name") or "").strip(),
        str(row.get("loser_name") or "").strip(),
    )


def merge_davis_cup_rows(annual_rows, supplement_rows):
    """Merge annual and ongoing TML rows, preferring the fresher supplement on overlap."""
    merged = {}
    order = []
    for row in annual_rows:
        key = davis_cup_match_key(row)
        if key not in merged:
            order.append(key)
        merged[key] = dict(row)
    added = 0
    for row in supplement_rows:
        key = davis_cup_match_key(row)
        if key not in merged:
            order.append(key)
            added += 1
        # The ongoing feed wins even when the annual row has the same rubber but older,
        # missing or partial statistics. A match must appear exactly once in the target.
        merged[key] = dict(row)
    return [merged[key] for key in order], added


def davis_cup_coverage_audit(rows, year):
    """Classify the deduplicated current-year TML Davis Cup singles feed without retaining rows."""
    davis_rows = [
        row for row in rows
        if row.get("tourney_level") == "D"
        and _normalise_event_name(row.get("tourney_name")).startswith("daviscup")
        and str(row.get("tourney_date") or "").strip()[:4] == str(year)
    ]
    unique = {}
    excluded_doubles = 0
    rows_without_player_ids = 0
    for row in davis_rows:
        names = (str(row.get("winner_name") or ""), str(row.get("loser_name") or ""))
        if any("/" in name or " & " in name or " and " in name.casefold() for name in names):
            excluded_doubles += 1
            continue
        if not str(row.get("winner_id") or "").strip() or not str(row.get("loser_id") or "").strip():
            rows_without_player_ids += 1
            continue
        unique[davis_cup_match_key(row)] = row

    required = ("w_ace", "w_svpt", "l_ace", "l_svpt")
    unplayed = [row for row in unique.values() if not str(row.get("score") or "").strip()]
    no_play = [
        row for row in unique.values()
        if str(row.get("score") or "").strip() and _is_no_play_score(row.get("score"))
    ]
    completed = [
        row for row in unique.values()
        if str(row.get("score") or "").strip() and not _is_no_play_score(row.get("score"))
    ]
    valid_stats = 0
    missing_stats = 0
    malformed_stats = 0
    for row in completed:
        if any(not str(row.get(key) or "").strip() for key in required):
            missing_stats += 1
            continue
        try:
            winner_aces, winner_points, loser_aces, loser_points = (
                int(str(row[key]).strip()) for key in required
            )
            valid = (
                bool(str(row.get("winner_name") or "").strip())
                and bool(str(row.get("loser_name") or "").strip())
                and winner_aces >= 0 and loser_aces >= 0
                and winner_points > 0 and loser_points > 0
                and winner_aces <= winner_points and loser_aces <= loser_points
            )
        except (TypeError, ValueError):
            valid = False
        if valid:
            valid_stats += 1
        else:
            malformed_stats += 1

    return {
        "sourceRowsAfterMerge": len(davis_rows),
        "excludedDoubles": excluded_doubles,
        "rowsWithoutPlayerIds": rows_without_player_ids,
        "identifiedSingles": len(unique),
        "scorelessSingles": len(unplayed),
        "walkoverOrNoPlaySingles": len(no_play),
        "completedPlayedSingles": len(completed),
        "completedWithValidServeStats": valid_stats,
        "completedMissingServeStats": missing_stats,
        "completedMalformedStats": malformed_stats,
    }


def _is_no_play_score(score):
    normalized = "".join(str(score or "").upper().split())
    return (
        "W/O" in normalized or normalized == "WO"
        or "WALKOVER" in normalized or "DEF" in normalized
    )


def load(year):
    os.makedirs(CACHE, exist_ok=True)

    # Prefer a fresh TML current-season download. After an outage, reuse a cached TML file
    # only if its sidecar proves it was fetched in the prior seven days.
    if year == date.today().year:
        SOURCE_BY_YEAR.pop(year, None)
        SOURCE_METADATA_BY_YEAR.pop(year, None)
        tml_path = os.path.join(CACHE, f"tml_atp_matches_{year}.csv")
        metadata_path = tml_path + ".meta.json"
        request = urllib.request.Request(
            TML_CURRENT_URL.format(year),
            headers={"User-Agent": "CourtPaceIndex/1.0", "Accept": "text/csv"},
        )
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                payload = response.read()
            if not payload.startswith(b"tourney_id,") or b"w_svpt" not in payload[:4096]:
                raise ValueError("TennisMyLife returned an unexpected ATP CSV schema")
            decoded = payload.decode("utf-8-sig")
            parsed_rows = list(csv.DictReader(decoded.splitlines()))
            if not parsed_rows:
                raise ValueError("TennisMyLife returned an empty ATP match table")
            if not {"tourney_date", "w_svpt", "l_svpt", "w_ace", "l_ace"}.issubset(parsed_rows[0]):
                raise ValueError("TennisMyLife ATP CSV is missing required serve-stat fields")
            match_dates = [
                row.get("tourney_date", "")
                for row in parsed_rows
                if (row.get("tourney_date") or "").isdigit()
            ]
            latest_match_date = max(match_dates) if match_dates else None
            with open(tml_path, "wb") as f:
                f.write(payload)
            metadata = {
                "source": "TennisMyLife ATP Match Database",
                "url": TML_CURRENT_URL.format(year),
                "fetchedAtUtc": datetime.now(timezone.utc).isoformat(),
                "latestMatchDate": latest_match_date,
                "matchRows": len(parsed_rows),
                "bytes": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(),
            }
            with open(metadata_path, "w", encoding="utf-8") as f:
                json.dump(metadata, f, indent=2)
            SOURCE_BY_YEAR[year] = "TennisMyLife ATP Match Database"
            SOURCE_METADATA_BY_YEAR[year] = metadata
        except (HTTPError, urllib.error.URLError, TimeoutError, ValueError, OSError) as error:
            if os.path.exists(tml_path) and tml_cache_is_fresh(metadata_path):
                with open(metadata_path, encoding="utf-8") as f:
                    metadata = json.load(f)
                print(
                    f"warning: TML refresh failed ({error}); using cached snapshot "
                    f"fetched {metadata['fetchedAtUtc']}"
                )
                SOURCE_BY_YEAR[year] = "TennisMyLife ATP Match Database (fresh cache)"
                SOURCE_METADATA_BY_YEAR[year] = metadata
            else:
                if os.path.exists(tml_path):
                    print(
                        f"warning: TML refresh failed ({error}); cached snapshot has no "
                        f"valid fetch manifest or is older than {TML_CACHE_MAX_AGE_DAYS} days"
                    )
                else:
                    print(f"warning: TML unavailable ({error}); no cached current-season snapshot")
                path = os.path.join(CACHE, f"atp_matches_{year}.csv")
                if not os.path.exists(path):
                    with urllib.request.urlopen(MIRROR.format(year), timeout=90) as response:
                        with open(path, "wb") as f:
                            f.write(response.read())
                SOURCE_BY_YEAR[year] = "Jeff Sackmann ATP archive (current-year fallback)"
                with open(path, newline="") as f:
                    return list(csv.DictReader(f))
        with open(tml_path, newline="") as f:
            annual_rows = list(csv.DictReader(f))
        ongoing_rows, ongoing_metadata = load_tml_ongoing_davis_cup(year)
        if ongoing_metadata is not None:
            merged_rows, added = merge_davis_cup_rows(annual_rows, ongoing_rows)
            metadata = dict(SOURCE_METADATA_BY_YEAR[year])
            metadata["ongoingDavisCup"] = {
                **ongoing_metadata,
                "rowsAdded": added,
            }
            SOURCE_METADATA_BY_YEAR[year] = metadata
            SOURCE_BY_YEAR[year] = TML_DAVIS_CUP_SOURCE
            print(
                f"{year}: TML Davis Cup supplement validated · "
                f"{len(ongoing_rows)} source rows · {added} new annual rows"
            )
            return merged_rows
        # Without a validated live supplement, keep ordinary ATP calculations but
        # withhold the 2026 Davis Cup target instead of using an unverified/stale source.
        SOURCE_BY_YEAR[year] = "TennisMyLife ATP Match Database (Davis Cup supplement unavailable)"
        return annual_rows

    path = os.path.join(CACHE, f"atp_matches_{year}.csv")
    if not os.path.exists(path):
        with urllib.request.urlopen(MIRROR.format(year), timeout=90) as response:
            with open(path, "wb") as f:
                f.write(response.read())
    SOURCE_BY_YEAR[year] = "Jeff Sackmann ATP archive"
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def valid_rows(rows):
    """Tour-level main-draw rows with internally consistent ace/service-point counts."""
    usable = []
    for r in rows:
        if r.get("tourney_level") not in TARGET_LEVELS:
            continue
        # Davis Cup ratings are singles-only. Exclude doubles before both target scoring
        # and profile/normaliser decisions, even if a source provides ace/service-point counts.
        if r.get("tourney_level") == "D":
            names = (r.get("winner_name") or "", r.get("loser_name") or "")
            if any(
                "/" in name or " & " in name or " and " in name.lower()
                for name in names
            ):
                continue
        values = [num(r.get(k)) for k in ("w_svpt", "l_svpt", "w_ace", "l_ace")]
        if any(value is None or not math.isfinite(value) for value in values):
            continue
        w_sv, l_sv, w_ac, l_ac = values
        if w_sv <= 0 or l_sv <= 0 or w_ac < 0 or l_ac < 0:
            continue
        if w_ac > w_sv or l_ac > l_sv:
            continue
        if not r.get("winner_id") or not r.get("loser_id") or r["winner_id"] == r["loser_id"]:
            continue
        usable.append(r)
    return usable

# Next Gen uses short sets and no-ad scoring. We now calculate it as a target event,
# following Tennis Abstract's decision to publish an adjusted ace-rate rating for it.
# Its matches must still stay out of standard ATP player profiles and the ATP-wide
# normaliser; otherwise its different match format would contaminate every other event.
PROFILE_TRAINING_EXCLUDED_EVENT_KEYS = frozenset({
    "nextgenfinals",
    "nextgenatpfinals",
    "nextgenerationatpfinals",
})
CALC_EXCLUDED_EVENT_KEYS = PROFILE_TRAINING_EXCLUDED_EVENT_KEYS  # Backwards-compatible name.
CALC_TARGET_ONLY_EVENT_IDS = frozenset({"nextgen", "daviscup"})


def calculation_eligible_rows(rows):
    """Rows eligible to train standard ATP player profiles and tour-average priors."""
    return [
        row for row in rows
        if row.get("tourney_level") != "D"
        and not _normalise_event_name(row.get("tourney_name")).startswith("daviscup")
        and _normalise_event_name(row.get("tourney_name")) not in PROFILE_TRAINING_EXCLUDED_EVENT_KEYS
    ]


def player_rates(usable, prior_points=PROFILE_PRIOR_SERVICE_POINTS):
    """Surface-specific, 150-service-point-shrunk player profiles."""
    if not math.isfinite(float(prior_points)) or prior_points < 0:
        raise ValueError("prior_points must be finite and non-negative")
    clay_ace = defaultdict(float); clay_sv = defaultdict(float); clay_n = defaultdict(int)
    hg_ace = defaultdict(float); hg_sv = defaultdict(float); hg_n = defaultdict(int)
    ret_ace = defaultdict(float); ret_pts = defaultdict(float); ret_n = defaultdict(int)
    ret_clay_ace = defaultdict(float); ret_clay_pts = defaultdict(float); ret_clay_n = defaultdict(int)
    ret_hg_ace = defaultdict(float); ret_hg_pts = defaultdict(float); ret_hg_n = defaultdict(int)
    all_serve_ace = defaultdict(float); all_serve_sv = defaultdict(float); all_serve_n = defaultdict(int)
    names = {}
    tour_aces = tour_points = clay_tour_aces = clay_tour_points = hg_tour_aces = hg_tour_points = 0.0

    for r in usable:
        w_sv, l_sv = num(r["w_svpt"]), num(r["l_svpt"])
        w_ac, l_ac = num(r["w_ace"]), num(r["l_ace"])
        wid, lid = r["winner_id"], r["loser_id"]
        names[wid] = r.get("winner_name") or ""
        names[lid] = r.get("loser_name") or ""
        all_serve_ace[wid] += w_ac; all_serve_sv[wid] += w_sv; all_serve_n[wid] += 1
        all_serve_ace[lid] += l_ac; all_serve_sv[lid] += l_sv; all_serve_n[lid] += 1
        surface = (r.get("surface") or "").strip()
        tour_aces += w_ac + l_ac
        tour_points += w_sv + l_sv
        if surface == "Clay":
            clay_tour_aces += w_ac + l_ac; clay_tour_points += w_sv + l_sv
            clay_ace[wid] += w_ac; clay_sv[wid] += w_sv; clay_n[wid] += 1
            clay_ace[lid] += l_ac; clay_sv[lid] += l_sv; clay_n[lid] += 1
            ret_clay_ace[wid] += l_ac; ret_clay_pts[wid] += l_sv; ret_clay_n[wid] += 1
            ret_clay_ace[lid] += w_ac; ret_clay_pts[lid] += w_sv; ret_clay_n[lid] += 1
        elif surface in ("Hard", "Grass"):
            hg_tour_aces += w_ac + l_ac; hg_tour_points += w_sv + l_sv
            hg_ace[wid] += w_ac; hg_sv[wid] += w_sv; hg_n[wid] += 1
            hg_ace[lid] += l_ac; hg_sv[lid] += l_sv; hg_n[lid] += 1
            ret_hg_ace[wid] += l_ac; ret_hg_pts[wid] += l_sv; ret_hg_n[wid] += 1
            ret_hg_ace[lid] += w_ac; ret_hg_pts[lid] += w_sv; ret_hg_n[lid] += 1
        ret_ace[wid] += l_ac; ret_pts[wid] += l_sv; ret_n[wid] += 1
        ret_ace[lid] += w_ac; ret_pts[lid] += w_sv; ret_n[lid] += 1

    tour_rate = tour_aces / tour_points if tour_points else None
    clay_tour_rate = clay_tour_aces / clay_tour_points if clay_tour_points else None
    hg_tour_rate = hg_tour_aces / hg_tour_points if hg_tour_points else None

    def posterior(aces, points, prior):
        if points <= 0:
            return None
        raw = aces / points
        return (aces + prior_points * prior) / (points + prior_points) if prior_points and prior is not None else raw

    serve_rate, serve_samples = {}, {}
    for player_id in set(clay_sv) | set(hg_sv):
        cq = clay_sv[player_id] > 0 and clay_n[player_id] >= MIN_MATCHES_PER_SURFACE
        hq = hg_sv[player_id] > 0 and hg_n[player_id] >= MIN_MATCHES_PER_SURFACE
        clay = posterior(clay_ace[player_id], clay_sv[player_id], clay_tour_rate) if cq else None
        hard = posterior(hg_ace[player_id], hg_sv[player_id], hg_tour_rate) if hq else None
        if clay is not None and hard is not None:
            serve_rate[player_id] = clay / 3 + 2 * hard / 3
        elif clay is not None:
            serve_rate[player_id] = clay
        elif hard is not None:
            serve_rate[player_id] = hard
        if player_id in serve_rate:
            serve_samples[player_id] = {
                "clayMatches": clay_n[player_id] if cq else 0,
                "hardGrassMatches": hg_n[player_id] if hq else 0,
                "servicePoints": (clay_sv[player_id] if cq else 0.0) + (hg_sv[player_id] if hq else 0.0),
            }

    pooled_concede = {p: ret_ace[p] / ret_pts[p] for p in ret_pts if ret_pts[p] > 0}
    concede_rate, concede_samples = {}, {}
    for player_id in ret_pts:
        cq = ret_clay_pts[player_id] > 0 and ret_clay_n[player_id] >= MIN_MATCHES_PER_SURFACE
        hq = ret_hg_pts[player_id] > 0 and ret_hg_n[player_id] >= MIN_MATCHES_PER_SURFACE
        clay = posterior(ret_clay_ace[player_id], ret_clay_pts[player_id], clay_tour_rate) if cq else None
        hard = posterior(ret_hg_ace[player_id], ret_hg_pts[player_id], hg_tour_rate) if hq else None
        if clay is not None and hard is not None:
            concede_rate[player_id] = clay / 3 + 2 * hard / 3
        elif clay is not None:
            concede_rate[player_id] = clay
        elif hard is not None:
            concede_rate[player_id] = hard
        elif player_id in pooled_concede:
            concede_rate[player_id] = posterior(ret_ace[player_id], ret_pts[player_id], tour_rate)

        surface_qualified = cq or hq
        concede_samples[player_id] = {
            "clayMatches": ret_clay_n[player_id] if cq else 0,
            "hardGrassMatches": ret_hg_n[player_id] if hq else 0,
            "matches": ((ret_clay_n[player_id] if cq else 0) + (ret_hg_n[player_id] if hq else 0))
                       if surface_qualified else ret_n[player_id],
            "servicePoints": ((ret_clay_pts[player_id] if cq else 0.0) + (ret_hg_pts[player_id] if hq else 0.0))
                             if surface_qualified else ret_pts[player_id],
        }

    return {
        p: {
            "id": p, "name": names.get(p, ""), "serve_rate": serve_rate.get(p),
            "concede_rate": concede_rate.get(p), "pooled_concede_rate": pooled_concede.get(p),
            "concede_rate_source": "surface-qualified" if (concede_samples.get(p, {}).get("clayMatches", 0)
                + concede_samples.get(p, {}).get("hardGrassMatches", 0)) > 0 else "pooled-fallback",
            "concede_matches": concede_samples.get(p, {}).get("matches", 0),
            "concede_service_points": concede_samples.get(p, {}).get("servicePoints", 0.0),
            "serve_matches": serve_samples.get(p, {}).get("clayMatches", 0)
                + serve_samples.get(p, {}).get("hardGrassMatches", 0),
            "serve_service_points": serve_samples.get(p, {}).get("servicePoints", 0.0),
            "serve_rate_prior_points": prior_points if serve_rate.get(p) is not None else 0.0,
            "concede_rate_prior_points": prior_points if concede_rate.get(p) is not None else 0.0,
            "clay_matches": clay_n[p], "hard_grass_matches": hg_n[p],
            "return_points": ret_pts.get(p, 0.0),
            "pooled_serve_aces": all_serve_ace.get(p, 0.0),
            "pooled_serve_points": all_serve_sv.get(p, 0.0),
            "pooled_serve_matches": all_serve_n.get(p, 0),
            "pooled_return_aces": ret_ace.get(p, 0.0),
            "pooled_return_points": ret_pts.get(p, 0.0),
            "pooled_return_matches": ret_n.get(p, 0),
            "clay_return_aces": ret_clay_ace.get(p, 0.0),
            "clay_return_points": ret_clay_pts.get(p, 0.0),
            "clay_return_matches": ret_clay_n.get(p, 0),
            "hard_grass_return_aces": ret_hg_ace.get(p, 0.0),
            "hard_grass_return_points": ret_hg_pts.get(p, 0.0),
            "hard_grass_return_matches": ret_hg_n.get(p, 0),
            "clay_aces": clay_ace[p], "clay_service_points": clay_sv[p],
            "hard_grass_aces": hg_ace[p], "hard_grass_service_points": hg_sv[p],
        }
        for p in set(names)
    }


# Live and historical profile coverage share one long-window, recency-weighted player history.
LIVE_PROFILE_FALLBACK_SEASONS = 35
LIVE_PROFILE_HISTORY_DECAY = 0.90
LIVE_PROFILE_PRIOR_SERVICE_POINTS = 150.0
MIN_EXPECTED_ACES = 0.0
EXPECTED_ACES_FLOOR = 0.5
_PROFILE_YEAR_CACHE = {}


def _surface_priors(profiles):
    clay_aces = sum(p.get("clay_aces", 0.0) for p in profiles.values())
    clay_points = sum(p.get("clay_service_points", 0.0) for p in profiles.values())
    hard_grass_aces = sum(p.get("hard_grass_aces", 0.0) for p in profiles.values())
    hard_grass_points = sum(p.get("hard_grass_service_points", 0.0) for p in profiles.values())
    pooled_aces = sum(p.get("pooled_serve_aces", 0.0) for p in profiles.values())
    pooled_points = sum(p.get("pooled_serve_points", 0.0) for p in profiles.values())
    return {
        "clay": clay_aces / clay_points if clay_points > 0 else None,
        "hard_grass": hard_grass_aces / hard_grass_points if hard_grass_points > 0 else None,
        "pooled": pooled_aces / pooled_points if pooled_points > 0 else None,
    }


def _shrunk_serve_rate(profile, priors):
    """Low-sample serve estimate; surface-qualified samples take precedence."""
    clay_points = profile.get("clay_service_points", 0.0)
    hard_grass_points = profile.get("hard_grass_service_points", 0.0)
    clay_estimate = None
    hard_grass_estimate = None

    if profile.get("clay_matches", 0) >= MIN_MATCHES_PER_SURFACE and clay_points > 0 and priors["clay"] is not None:
        clay_estimate = (
            profile["clay_aces"] + LIVE_PROFILE_PRIOR_SERVICE_POINTS * priors["clay"]
        ) / (clay_points + LIVE_PROFILE_PRIOR_SERVICE_POINTS)
    if profile.get("hard_grass_matches", 0) >= MIN_MATCHES_PER_SURFACE and hard_grass_points > 0 and priors["hard_grass"] is not None:
        hard_grass_estimate = (
            profile["hard_grass_aces"] + LIVE_PROFILE_PRIOR_SERVICE_POINTS * priors["hard_grass"]
        ) / (hard_grass_points + LIVE_PROFILE_PRIOR_SERVICE_POINTS)

    if clay_estimate is not None and hard_grass_estimate is not None:
        return (1 / 3) * clay_estimate + (2 / 3) * hard_grass_estimate
    if clay_estimate is not None:
        return clay_estimate
    if hard_grass_estimate is not None:
        return hard_grass_estimate

    pooled_points = profile.get("pooled_serve_points", 0.0)
    pooled_aces = profile.get("pooled_serve_aces", 0.0)
    pooled_prior = priors.get("pooled")
    if pooled_points > 0 and pooled_prior is not None:
        return (
            pooled_aces + LIVE_PROFILE_PRIOR_SERVICE_POINTS * pooled_prior
        ) / (pooled_points + LIVE_PROFILE_PRIOR_SERVICE_POINTS)
    return None


def _shrunk_return_rate(profile, priors):
    """Returner concession estimate using the same long-window surface hierarchy."""
    clay_points = profile.get("clay_return_points", 0.0)
    hard_grass_points = profile.get("hard_grass_return_points", 0.0)
    clay_estimate = None
    hard_grass_estimate = None
    if profile.get("clay_return_matches", 0) >= MIN_MATCHES_PER_SURFACE and clay_points > 0 and priors["clay"] is not None:
        clay_estimate = (
            profile["clay_return_aces"] + LIVE_PROFILE_PRIOR_SERVICE_POINTS * priors["clay"]
        ) / (clay_points + LIVE_PROFILE_PRIOR_SERVICE_POINTS)
    if profile.get("hard_grass_return_matches", 0) >= MIN_MATCHES_PER_SURFACE and hard_grass_points > 0 and priors["hard_grass"] is not None:
        hard_grass_estimate = (
            profile["hard_grass_return_aces"] + LIVE_PROFILE_PRIOR_SERVICE_POINTS * priors["hard_grass"]
        ) / (hard_grass_points + LIVE_PROFILE_PRIOR_SERVICE_POINTS)

    if clay_estimate is not None and hard_grass_estimate is not None:
        return (1 / 3) * clay_estimate + (2 / 3) * hard_grass_estimate
    if clay_estimate is not None:
        return clay_estimate
    if hard_grass_estimate is not None:
        return hard_grass_estimate

    points = profile.get("pooled_return_points", 0.0)
    aces = profile.get("pooled_return_aces", 0.0)
    pooled_prior = priors.get("pooled")
    if points > 0 and pooled_prior is not None:
        return (
            aces + LIVE_PROFILE_PRIOR_SERVICE_POINTS * pooled_prior
        ) / (points + LIVE_PROFILE_PRIOR_SERVICE_POINTS)
    return None


def _profile_year_rates(year):
    """Cache prior-season profile estimates so a long backfill is parsed only once."""
    if year not in _PROFILE_YEAR_CACHE:
        _PROFILE_YEAR_CACHE[year] = player_rates(calculation_eligible_rows(valid_rows(load(year))))
    return _PROFILE_YEAR_CACHE[year]


def _match_date(row):
    """Parse a source YYYYMMDD match/tournament date, returning None for malformed dates."""
    raw = str(row.get("tourney_date") or "").strip()
    if len(raw) != 8 or not raw.isdigit():
        return None
    try:
        return datetime.strptime(raw, "%Y%m%d").date()
    except ValueError:
        return None


def _match_start_timestamp(row):
    """Return an explicitly timezone-aware match-start instant when the source supplies one."""
    for key in (
        "match_start_utc", "match_started_at_utc", "actual_start_utc",
        "match_start_at", "match_datetime_utc",
    ):
        raw = str(row.get(key) or "").strip()
        if not raw:
            continue
        try:
            value = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        except ValueError:
            continue
        # A clock without an offset is not comparable across tournament locations.
        if value.tzinfo is None:
            continue
        return value.astimezone(timezone.utc)
    return None


def _source_event_key(row):
    """Stable source-edition key for date-precision detection, with alias fallback for fixtures."""
    raw_id = str(row.get("tourney_id") or "").strip()
    if raw_id:
        return f"id:{raw_id}"
    resolved = tournament_id(row.get("tourney_name"))
    if resolved:
        return f"event:{resolved}"
    return f"name:{_normalise_event_name(row.get('tourney_name'))}"


def _source_event_date_granularity(rows, source=None):
    """
    Determine date precision per source tournament edition.

    A TML 2026 feed is not automatically assumed to be daily for every row. An edition
    is considered match-day precise only when that edition itself has multiple distinct
    date values. This keeps a one-date/incomplete edition on the legacy fallback even if
    other tournaments in the same annual file already show daily dates.
    """
    if "TennisMyLife ATP Match Database" not in str(source or ""):
        return {}

    dates_by_event = defaultdict(set)
    for row in rows:
        if row.get("tourney_level") not in TARGET_LEVELS:
            continue
        names = (row.get("winner_name") or "", row.get("loser_name") or "")
        if any("/" in name or " & " in name or " and " in name.casefold() for name in names):
            continue
        event_key = _source_event_key(row)
        match_date = _match_date(row)
        if event_key and match_date is not None:
            dates_by_event[event_key].add(match_date)
    return {
        event_key: ("match_day" if len(values) > 1 else "tournament_start")
        for event_key, values in dates_by_event.items()
    }


def _event_date_granularity(event_rows, source_precision_by_event):
    """Use daily precision for a grouped app event only if each source edition proves it."""
    keys = {_source_event_key(row) for row in event_rows}
    return (
        "match_day"
        if keys and all(source_precision_by_event.get(key) == "match_day" for key in keys)
        else "tournament_start"
    )


def _source_match_date_granularity(rows, source=None):
    """Backward-compatible summary of the finest precision observed in a source."""
    precisions = set(_source_event_date_granularity(rows, source).values())
    if not precisions:
        return "tournament_start"
    if precisions == {"match_day"}:
        return "match_day"
    if precisions == {"tournament_start"}:
        return "tournament_start"
    return "mixed"


def _event_first_match_timestamp(event_rows):
    """Return the first event match instant only when every target row has a real timestamp."""
    timestamps = [_match_start_timestamp(row) for row in event_rows]
    if not timestamps or any(value is None for value in timestamps):
        return None
    return min(timestamps)


def _match_day_definitely_precedes_target(source_day, target_day):
    """
    Compare local calendar days without guessing their time zones.

    A local day may span UTC from 14 hours before its date (UTC+14) to 12 hours
    after the following midnight (UTC-12). Include the source day only when its
    latest possible UTC instant is no later than the target day's earliest possible
    UTC instant. This protects overlapping events in different time zones without
    inventing a local clock time.
    """
    source_latest_utc = (
        datetime.combine(source_day + timedelta(days=1), datetime.min.time(), tzinfo=timezone.utc)
        + timedelta(hours=12)
    )
    target_earliest_utc = (
        datetime.combine(target_day, datetime.min.time(), tzinfo=timezone.utc)
        - timedelta(hours=14)
    )
    return source_latest_utc <= target_earliest_utc


def _legacy_max_event_days(row):
    """Conservative maximum main-draw duration for a legacy source event level."""
    level = str(row.get("tourney_level") or "").strip()
    return LEGACY_MAX_EVENT_DAYS_BY_LEVEL.get(level, LEGACY_UNKNOWN_MAX_EVENT_DAYS)


def _legacy_event_window_end_date(row):
    """Latest possible event day for a legacy row, based on its source-level duration bound."""
    match_date = _match_date(row)
    if match_date is None:
        return None
    return match_date + timedelta(days=_legacy_max_event_days(row) - 1)


def _legacy_event_window_precedes_target(row, target_start_date, target_start_timestamp=None):
    """
    Whether the complete maximum possible source-event window precedes the target.

    This is an explicitly conservative duration bound, not a recovered actual event-end
    date. Historical annual sources do not contain individual match dates or end dates.
    """
    last_possible_day = _legacy_event_window_end_date(row)
    if last_possible_day is None:
        return False
    if target_start_timestamp is not None:
        source_latest_utc = (
            datetime.combine(last_possible_day + timedelta(days=1), datetime.min.time(), tzinfo=timezone.utc)
            + timedelta(hours=12)
        )
        return source_latest_utc <= target_start_timestamp
    return _match_day_definitely_precedes_target(last_possible_day, target_start_date)


def _row_precedes_target(
    row, target_start_date, date_precision, target_start_timestamp=None,
):
    """Whether a training match is provably earlier than the target event's first match."""
    match_date = _match_date(row)
    if match_date is None:
        return False

    row_timestamp = _match_start_timestamp(row)
    if target_start_timestamp is not None and row_timestamp is not None:
        return row_timestamp < target_start_timestamp

    if date_precision in {"match_day", "match_timestamp"}:
        # Without two real timestamps and their offsets, prove precedence across every
        # possible tournament time zone. Adjacent local calendar dates can overlap in UTC.
        return _match_day_definitely_precedes_target(match_date, target_start_date)

    return _legacy_event_window_precedes_target(row, target_start_date, target_start_timestamp)

def _prior_year_rows(year):
    """Return eligible ATP training rows from a completed prior season."""
    return calculation_eligible_rows(valid_rows(load(year)))


def _chronological_profiles_and_anchor(
    year, rows, target_start_date, training_precision_by_event, target_start_timestamp=None,
):
    """Fit profiles using each source edition's own verified date precision."""
    before = [
        row for row in calculation_eligible_rows(valid_rows(rows))
        if _row_precedes_target(
            row,
            target_start_date,
            training_precision_by_event.get(_source_event_key(row), "tournament_start"),
            target_start_timestamp,
        )
    ]
    if before:
        profiles_current = player_rates(before, prior_points=PROFILE_PRIOR_SERVICE_POINTS)
        profiles = build_live_profiles(year, profiles_current)
        tour_rate = tour_average_ace_rate(before)
    else:
        # Opening-event anchor: the completed preceding season is the only permissible
        # calibration sample. Its profiles are fitted on that prior season's ATP rows and
        # historical player history, never on any match from the target season.
        if year <= PROFILE_HISTORY_START_YEAR:
            return None, None, []
        prior = _prior_year_rows(year - 1)
        if not prior:
            return None, None, []
        profiles_current = player_rates(prior, prior_points=PROFILE_PRIOR_SERVICE_POINTS)
        profiles = build_live_profiles(year - 1, profiles_current)
        tour_rate = tour_average_ace_rate(prior)
        before = prior
    if tour_rate is None or not 0.0 < tour_rate < 1.0:
        return None, None, []
    return profiles, tour_rate, before

def build_live_profiles(profile_year, current_profiles):
    """
    Build a broad live profile database from current-season plus prior ATP seasons.

    Current-season TML IDs are alphanumeric, while older Sackmann IDs are numeric.
    Cross-source backfill therefore uses a unique normalised full-name identity key,
    not raw IDs. Ambiguous same-name collisions are never merged. Historical samples
    are decayed by 0.90 per year and aggregated, rather than selecting a single prior
    season. Surface-qualified estimates take precedence; sparse profiles are shrunk
    toward the recency-weighted tour/surface prior. Each profile records its source,
    sample and profile year.

    This policy is also reused by historical leave-one-tournament-out ratings.
    """
    live = {player_id: dict(profile) for player_id, profile in current_profiles.items()}
    current_by_name = defaultdict(list)
    raw_by_name = defaultdict(lambda: {
        "clay_aces": 0.0, "clay_service_points": 0.0, "clay_matches": 0.0,
        "hard_grass_aces": 0.0, "hard_grass_service_points": 0.0, "hard_grass_matches": 0.0,
        "pooled_serve_aces": 0.0, "pooled_serve_points": 0.0, "pooled_serve_matches": 0.0,
        "clay_return_aces": 0.0, "clay_return_points": 0.0, "clay_return_matches": 0.0,
        "hard_grass_return_aces": 0.0, "hard_grass_return_points": 0.0, "hard_grass_return_matches": 0.0,
        "pooled_return_aces": 0.0, "pooled_return_points": 0.0, "pooled_return_matches": 0.0,
    })
    most_recent_year_by_name = {}
    most_recent_id_by_name = {}

    def name_key(profile):
        return _normalise_profile_name(profile.get("name") or "")

    def add_raw(target, source, weight):
        for field in target:
            if field.endswith("_matches"):
                target[field] += float(source.get(field, 0) or 0)
            else:
                target[field] += float(source.get(field, 0.0) or 0.0) * weight

    # Current-season records are the freshest identity/name and sample layer.
    for player_id, profile in live.items():
        key = name_key(profile)
        if key:
            current_by_name[key].append(player_id)
            add_raw(raw_by_name[key], profile, 1.0)
            most_recent_year_by_name[key] = profile_year
            most_recent_id_by_name[key] = player_id
        profile["serve_rate_season"] = profile_year if profile.get("serve_rate") is not None else None
        profile["serve_rate_source"] = (
            "current-season-qualified" if profile.get("serve_rate") is not None else None
        )
        profile["serve_rate_sample_matches"] = profile.get("serve_matches", 0)
        profile["serve_rate_sample_points"] = profile.get(
            "serve_service_points", profile.get("clay_service_points", 0.0)
            + profile.get("hard_grass_service_points", 0.0)
        )
        profile["concede_rate_season"] = (
            profile_year if profile.get("concede_rate") is not None else None
        )
        profile["concede_rate_source"] = profile.get("concede_rate_source")
        profile["concede_matches"] = profile.get("concede_matches", 0)
        profile["concede_service_points"] = profile.get("concede_service_points", 0.0)

    # Detect duplicate full-name keys within the current source before crosswalking.
    ambiguous_names = {
        key for key, player_ids in current_by_name.items() if len(player_ids) != 1
    }

    # Merge all available prior-year ATP tour histories using a stable name crosswalk.
    lower_year = max(PROFILE_HISTORY_START_YEAR, profile_year - LIVE_PROFILE_FALLBACK_SEASONS)
    historical_profiles_by_name = defaultdict(list)
    for historical_year in range(profile_year - 1, lower_year - 1, -1):
        historical = _profile_year_rates(historical_year)
        by_name = defaultdict(list)
        for player_id, previous in historical.items():
            key = name_key(previous)
            if key:
                by_name[key].append((player_id, previous))
        for key, candidates in by_name.items():
            if len(candidates) != 1:
                ambiguous_names.add(key)
                continue
            player_id, previous = candidates[0]
            if key in ambiguous_names:
                continue

            decay = LIVE_PROFILE_HISTORY_DECAY ** (profile_year - historical_year)
            add_raw(raw_by_name[key], previous, decay)
            historical_profiles_by_name[key].append((historical_year, player_id, previous))

            # Attach the prior history to the current ATP ID if the unique name matches.
            current_ids = current_by_name.get(key, [])
            target_id = (
                current_ids[0] if len(current_ids) == 1
                else most_recent_id_by_name.get(key, player_id)
            )
            if target_id not in live:
                live[target_id] = dict(previous)
                live[target_id]["id"] = target_id
                live[target_id]["serve_rate_season"] = None
                live[target_id]["serve_rate_source"] = None
                live[target_id]["serve_rate_sample_matches"] = 0
                live[target_id]["serve_rate_sample_points"] = 0
                live[target_id]["concede_rate_season"] = None
            most_recent_year_by_name.setdefault(key, historical_year)
            most_recent_id_by_name.setdefault(key, target_id)

    # Recency-weighted population priors from the same profile evidence window.
    prior_profiles = {
        key: values for key, values in raw_by_name.items()
        if key not in ambiguous_names
    }
    surface_priors = _surface_priors(prior_profiles)

    # Fill sparse/current profiles from the multi-season player sample. A qualified
    # current-season profile remains authoritative; otherwise use the weighted career
    # window, with a low-sample pooled estimate as the final player-specific fallback.
    for key, source in raw_by_name.items():
        if not key or key in ambiguous_names:
            continue
        target_ids = current_by_name.get(key, [])
        target_id = target_ids[0] if len(target_ids) == 1 else most_recent_id_by_name.get(key)
        if target_id is None or target_id not in live:
            continue
        profile = live[target_id]
        current_serve_qualified = (
            profile_year == profile.get("serve_rate_season")
            and profile.get("serve_rate") is not None
            and profile.get("serve_matches", 0) >= MIN_MATCHES_PER_SURFACE
            and (
                profile.get("clay_matches", 0) >= MIN_MATCHES_PER_SURFACE
                or profile.get("hard_grass_matches", 0) >= MIN_MATCHES_PER_SURFACE
            )
        )
        current_return_qualified = (
            profile_year == profile.get("concede_rate_season")
            and profile.get("concede_rate") is not None
            and profile.get("concede_rate_source") == "surface-qualified"
        )
        serve_rate = _shrunk_serve_rate(source, surface_priors)
        if not current_serve_qualified and serve_rate is not None:
            profile["serve_rate"] = serve_rate
            profile["serve_rate_season"] = most_recent_year_by_name.get(key, profile_year)
            has_surface = (
                source["clay_matches"] >= MIN_MATCHES_PER_SURFACE
                or source["hard_grass_matches"] >= MIN_MATCHES_PER_SURFACE
            )
            profile["serve_rate_source"] = (
                "career-history-surface-shrunk" if has_surface else "career-history-pooled-shrunk"
            )
            profile["serve_rate_sample_matches"] = int(
                source["clay_matches"] + source["hard_grass_matches"]
                or source["pooled_serve_matches"]
            )
            profile["serve_rate_sample_points"] = int(round(
                source["clay_service_points"] + source["hard_grass_service_points"]
                or source["pooled_serve_points"]
            ))
            profile["serve_rate_prior_points"] = LIVE_PROFILE_PRIOR_SERVICE_POINTS

        concede_rate = _shrunk_return_rate(source, surface_priors)
        if not current_return_qualified and concede_rate is not None:
            profile["concede_rate"] = concede_rate
            profile["concede_rate_season"] = most_recent_year_by_name.get(key, profile_year)
            has_surface = (
                source["clay_return_matches"] >= MIN_MATCHES_PER_SURFACE
                or source["hard_grass_return_matches"] >= MIN_MATCHES_PER_SURFACE
            )
            profile["concede_rate_source"] = (
                "career-history-surface-shrunk" if has_surface else "career-history-pooled-shrunk"
            )
            profile["concede_matches"] = int(
                source["clay_return_matches"] + source["hard_grass_return_matches"]
                or source["pooled_return_matches"]
            )
            profile["concede_service_points"] = int(round(
                source["clay_return_points"] + source["hard_grass_return_points"]
                or source["pooled_return_points"]
            ))
            profile["concede_rate_prior_points"] = LIVE_PROFILE_PRIOR_SERVICE_POINTS

    return live



def _normalise_profile_name(name):
    return "".join(
        char for char in unicodedata.normalize("NFKD", name).casefold()
        if char.isalnum()
    )


def ensure_event_profiles(event_rows, profiles, tour_rate):
    """Crosswalk names and count every side that uses a neutral tour-average prior.

    A unique normalized full name may bridge TML alphanumeric IDs to Sackmann numeric IDs.
    If no trustworthy player-specific rate exists, use an explicit tour-average prior and
    count that use. Ambiguous identities are never assigned another player's profile.

    Profile-assignment diagnostics are unique per player/role. Side-use diagnostics are
    counted for every server side, including later matches by a player whose profile was
    already assigned the neutral prior earlier in this event.
    """
    candidates = defaultdict(list)
    for profile in profiles.values():
        key = _normalise_profile_name(profile.get("name") or "")
        if key:
            candidates[key].append(profile)
    unique_by_name = {
        key: values[0] for key, values in candidates.items() if len(values) == 1
    }

    coverage = {
        "tour_prior_server_profiles": 0,
        "tour_prior_returner_profiles": 0,
        "tour_prior_server_side_uses": 0,
        "tour_prior_returner_side_uses": 0,
        "career_history_server_sides": 0,
        "career_history_returner_sides": 0,
        "missing_server_profile_before_fallback": 0,
        "missing_returner_profile_before_fallback": 0,
        "missing_both_profiles_before_fallback": 0,
        "tour_prior_sides": 0,
        "rateable_server_sides": 0,
        "tour_prior_fallback_details": [],
    }
    fallback_profile_roles = set()

    def record_fallback(role, identity):
        key = (str(identity.get("player_id") or ""), role)
        if key in fallback_profile_roles:
            return
        fallback_profile_roles.add(key)
        if role == "server":
            coverage["missing_server_profile_before_fallback"] += 1
            coverage["tour_prior_server_profiles"] += 1
        else:
            coverage["missing_returner_profile_before_fallback"] += 1
            coverage["tour_prior_returner_profiles"] += 1
        coverage["tour_prior_fallback_details"].append({
            **identity,
            "role": role,
            "reason": (
                "ambiguous_name_match"
                if (
                    not identity["had_exact_player_profile"]
                    and identity["name_candidate_count"] > 1
                )
                else (
                    "player_profile_exists_without_rate"
                    if identity["had_player_profile"] else "no_player_profile_in_history"
                )
            ),
        })

    for row in event_rows:
        profiles_by_role = {}
        profile_identity = {}
        for player_id_key, player_name_key in (
            ("winner_id", "winner_name"),
            ("loser_id", "loser_name"),
        ):
            player_id = row.get(player_id_key)
            player_name = row.get(player_name_key) or ""
            profile = profiles.get(player_id)
            had_exact_player_profile = profile is not None
            normalized_name = _normalise_profile_name(player_name)
            name_candidates = candidates.get(normalized_name, []) if normalized_name else []
            candidate = unique_by_name.get(normalized_name)
            had_player_profile = profile is not None or bool(name_candidates)

            # Crosswalk current TML IDs to historical archive IDs by unique full name.
            if candidate is not None and profile is None:
                profile = dict(candidate)
                profile["id"] = player_id
                profile["name"] = player_name or profile.get("name", "")
                profiles[player_id] = profile
            if profile is None:
                profile = {
                    "id": player_id,
                    "name": player_name,
                    "serve_rate": None,
                    "concede_rate": None,
                    "serve_rate_source": None,
                    "concede_rate_source": None,
                }
                profiles[player_id] = profile

            # Fill only missing roles from a unique alias profile; current-source values win.
            if candidate is not None and candidate is not profile:
                for field in (
                    "serve_rate", "serve_rate_source", "concede_rate",
                    "concede_rate_source", "serve_rate_season", "concede_rate_season",
                    "serve_rate_sample_matches", "serve_rate_sample_points",
                    "concede_matches", "concede_service_points",
                    "serve_rate_prior_points", "concede_rate_prior_points",
                ):
                    if profile.get(field) is None and candidate.get(field) is not None:
                        profile[field] = candidate[field]

            profiles_by_role[player_id_key] = profile
            profile_identity[player_id_key] = {
                "player_id": player_id,
                "player_name": player_name,
                "had_player_profile": had_player_profile,
                "had_exact_player_profile": had_exact_player_profile,
                "name_candidate_count": len(name_candidates),
            }

        sides = (
            ("winner_id", "loser_id", "w_svpt"),
            ("loser_id", "winner_id", "l_svpt"),
        )
        for server_id_key, returner_id_key, service_points_key in sides:
            server_profile = profiles_by_role[server_id_key]
            returner_profile = profiles_by_role[returner_id_key]
            serve_rate = server_profile.get("serve_rate")
            concede_rate = returner_profile.get("concede_rate")
            server_fallback = (
                serve_rate is None
                or server_profile.get("serve_rate_source") == "tour-average-prior-only"
            )
            returner_fallback = (
                concede_rate is None
                or returner_profile.get("concede_rate_source") == "tour-average-prior-only"
            )

            if server_fallback:
                coverage["tour_prior_server_side_uses"] += 1
                record_fallback("server", profile_identity[server_id_key])
                server_profile["serve_rate"] = tour_rate
                server_profile["serve_rate_source"] = "tour-average-prior-only"
                serve_rate = tour_rate

            if returner_fallback:
                coverage["tour_prior_returner_side_uses"] += 1
                record_fallback("returner", profile_identity[returner_id_key])
                returner_profile["concede_rate"] = tour_rate
                returner_profile["concede_rate_source"] = "tour-average-prior-only"
                concede_rate = tour_rate

            if server_fallback and returner_fallback:
                coverage["missing_both_profiles_before_fallback"] += 1
            if server_fallback or returner_fallback:
                coverage["tour_prior_sides"] += 1
            if str(server_profile.get("serve_rate_source") or "").startswith("career-history"):
                coverage["career_history_server_sides"] += 1
            if str(returner_profile.get("concede_rate_source") or "").startswith("career-history"):
                coverage["career_history_returner_sides"] += 1

            service_points = num(row.get(service_points_key))
            if (
                serve_rate is None or concede_rate is None
                or not math.isfinite(serve_rate) or not math.isfinite(concede_rate)
                or not 0.0 < serve_rate < 1.0 or not 0.0 < concede_rate < 1.0
                or tour_rate is None or not 0.0 < tour_rate < 1.0
                or service_points is None or service_points <= 0
            ):
                continue
            expected = expected_calibrated_aces(
                serve_rate, concede_rate, tour_rate, service_points
            )
            if math.isfinite(expected) and expected > MIN_EXPECTED_ACES:
                coverage["rateable_server_sides"] += 1

    return coverage


def dedupe_live_profiles_with_diagnostics(profiles):
    """
    Build the name-keyed Android index without silently merging conflicting same-name players.

    Different archive IDs with the same normalized name are collapsed only when same-season
    rates agree. Conflicting same-season identities are omitted because ATP live stats arrive
    as display names, not stable player IDs. Across seasons, the newest/strongest record wins.
    """
    source_priority = {
        "current-season-qualified": 5,
        "career-history-surface-shrunk": 4,
        "prior-season-qualified": 3,
        "career-history-pooled-shrunk": 2,
        "current-season-shrunk": 2,
        "prior-season-shrunk": 1,
    }
    groups = defaultdict(list)
    for profile in profiles:
        key = _normalise_profile_name((profile.get("name") or "").strip())
        if key:
            groups[key].append(dict(profile))

    result = []
    ambiguous = 0
    collapsed = 0
    concede_priority = {
        "surface-qualified": 4,
        "career-history-surface-shrunk": 3,
        "pooled-fallback": 2,
        "career-history-pooled-shrunk": 1,
        "tour-average-prior-only": 0,
    }

    for key, group in groups.items():
        collapsed += max(0, len(group) - 1)
        seasons = {
            profile.get("serve_rate_season") or profile.get("concede_rate_season")
            for profile in group
            if profile.get("serve_rate_season") or profile.get("concede_rate_season")
        }
        if len(group) > 1 and len(seasons) == 1:
            # Similar rates do not prove distinct archive IDs are the same athlete.
            ambiguous += 1
            continue

        def serve_rank(profile):
            return (
                source_priority.get(profile.get("serve_rate_source"), 0),
                profile.get("serve_rate_season") or 0,
                profile.get("serve_rate_sample_points") or 0,
                profile.get("serve_rate_sample_matches") or 0,
            )

        candidate = dict(max(group, key=serve_rank))
        best_return = max(
            group,
            key=lambda p: (
                concede_priority.get(p.get("concede_rate_source"), 0),
                p.get("concede_rate_season") or 0,
                p.get("concede_service_points") or 0,
            ),
        )
        if best_return.get("concede_rate") is not None:
            for field in (
                "concede_rate", "concede_rate_season", "concede_rate_source",
                "concede_matches", "concede_service_points", "concede_rate_prior_points",
            ):
                candidate[field] = best_return.get(field)
        result.append(candidate)

    return result, {
        "duplicateNameRecordsCollapsed": collapsed,
        "ambiguousNameCollisionsExcluded": ambiguous,
    }


def dedupe_live_profiles(profiles):
    """Compatibility wrapper returning only safely name-resolvable profiles."""
    return dedupe_live_profiles_with_diagnostics(profiles)[0]


def tour_average_ace_rate(usable):
    """Season-wide ace probability per service point across usable tour-level matches."""
    total_aces = sum(num(r["w_ace"]) + num(r["l_ace"]) for r in usable)
    total_points = sum(num(r["w_svpt"]) + num(r["l_svpt"]) for r in usable)
    return total_aces / total_points if total_points > 0 else None


def expected_calibrated_aces(serve_rate, concede_rate, tour_rate, service_points):
    """Log5/odds-ratio expected aces calibrated to the season tour-average ace rate."""
    if not all(0.0 < rate < 1.0 for rate in (serve_rate, concede_rate, tour_rate)):
        return 0.0
    server_odds = serve_rate / (1.0 - serve_rate)
    returner_odds = concede_rate / (1.0 - concede_rate)
    tour_odds = tour_rate / (1.0 - tour_rate)
    matchup_odds = (server_odds * returner_odds) / tour_odds
    if not math.isfinite(matchup_odds) or matchup_odds <= 0.0:
        return 0.0
    expected_ace_rate = matchup_odds / (1.0 + matchup_odds)
    return expected_ace_rate * service_points


def event_raw_scores(usable, profiles, exclude_event_ids=None, tour_rate=None):
    """
    Apply the calibrated match-level expected-aces formula to each event.

    Expected ace odds multiply the server's odds by the returner's concession odds and
    divide by the season tour-average ace odds. Returns raw event means before the
    season-wide normalisation.
    """
    usable = calculation_eligible_rows(usable)
    excluded = exclude_event_ids or set()
    if tour_rate is None:
        tour_rate = tour_average_ace_rate(usable)
    if tour_rate is None or not 0.0 < tour_rate < 1.0:
        return {}, defaultdict(float), defaultdict(float), defaultdict(int)
    per_event = defaultdict(list)
    event_aces = defaultdict(float); event_sv = defaultdict(float)
    event_matches = defaultdict(int)

    for r in usable:
        tid = tournament_id(r.get("tourney_name"))
        if tid is None or tid in excluded:
            continue
        w_sv, l_sv = num(r["w_svpt"]), num(r["l_svpt"])
        w_ac, l_ac = num(r["w_ace"]), num(r["l_ace"])
        wid, lid = r["winner_id"], r["loser_id"]
        event_aces[tid] += w_ac + l_ac
        event_sv[tid] += w_sv + l_sv
        event_matches[tid] += 1

        for server, returner, sv, ac in ((wid, lid, w_sv, w_ac), (lid, wid, l_sv, l_ac)):
            ps = profiles.get(server, {}).get("serve_rate")
            qr = profiles.get(returner, {}).get("concede_rate")
            if ps is None or qr is None:
                continue
            expected = expected_calibrated_aces(ps, qr, tour_rate, sv)
            # Keep every mathematically valid player-server side. Calibrated expected-ace
            # counts below 0.5 are floored at 0.5 rather than dropping valid observations.
            if expected <= MIN_EXPECTED_ACES:
                continue
            expected = max(expected, EXPECTED_ACES_FLOOR)
            per_event[tid].append(ac / expected)

    raw = {
        t: sum(v) / len(v)
        for t, v in per_event.items()
        if len(v) >= MIN_NORMALISER_SERVER_SIDES
    }
    return raw, event_aces, event_sv, event_matches


def event_match_ratio_clusters(event_rows, profiles, tour_rate):
    """Keep both server sides together as one independent match-level bootstrap cluster."""
    clusters = []
    for row in event_rows:
        winner, loser = row["winner_id"], row["loser_id"]
        sides = []
        for server, returner, service_points, aces in (
            (winner, loser, num(row["w_svpt"]), num(row["w_ace"])),
            (loser, winner, num(row["l_svpt"]), num(row["l_ace"])),
        ):
            serve_rate = profiles.get(server, {}).get("serve_rate")
            concede_rate = profiles.get(returner, {}).get("concede_rate")
            if serve_rate is None or concede_rate is None:
                continue
            expected = expected_calibrated_aces(serve_rate, concede_rate, tour_rate, service_points)
            if not math.isfinite(expected) or expected <= MIN_EXPECTED_ACES:
                continue
            sides.append(aces / max(expected, EXPECTED_ACES_FLOOR))
        if sides:
            clusters.append(sides)
    return clusters


def bootstrap_event_interval(clusters, normaliser, iterations=EVENT_CI_BOOTSTRAP_ITERATIONS, seed=1):
    """Conditional 95% interval; resample whole matches, holding profiles and normaliser fixed."""
    if not clusters or normaliser <= 0 or not math.isfinite(normaliser):
        return None
    rng = random.Random(seed)
    values = []
    for _ in range(iterations):
        ratios = []
        for _ in range(len(clusters)):
            ratios.extend(rng.choice(clusters))
        if ratios:
            values.append((sum(ratios) / len(ratios)) / normaliser)
    if len(values) < max(100, int(iterations * 0.9)):
        return None
    values.sort()
    def percentile(q):
        position = (len(values) - 1) * q
        low, high = int(math.floor(position)), int(math.ceil(position))
        return values[low] + (values[high] - values[low]) * (position - low)
    return {"low": percentile(0.025), "high": percentile(0.975)}



def event_side_diagnostics(event_rows, profiles, tour_rate, retain_per_tail=10):
    """Compact archive diagnostic matching the Live Now highest/lowest side detail."""
    total_aces = 0
    total_points = 0
    server_profiles_matched = 0
    returner_profiles_matched = 0
    server_fallbacks = 0
    returner_fallbacks = 0
    sides = []
    for match_index, row in enumerate(event_rows, start=1):
        match_num = str(row.get("match_num") or "").strip()
        # Davis Cup tie labels are combined into one season event, but their local
        # match numbers can repeat. Use the event-wide row index to keep diagnostics unique.
        is_davis_cup = (
            row.get("tourney_level") == "D"
            or _normalise_event_name(row.get("tourney_name")).startswith("daviscup")
        )
        match_id = (
            f"dc{match_index:03d}" if is_davis_cup
            else (f"m{int(match_num):03d}" if match_num.isdigit() else f"m{match_index:03d}")
        )
        total_aces += int(num(row.get("w_ace")) + num(row.get("l_ace")))
        total_points += int(num(row.get("w_svpt")) + num(row.get("l_svpt")))
        for server_id, returner_id, server_name, returner_name, aces_key, points_key in (
            ("winner_id", "loser_id", "winner_name", "loser_name", "w_ace", "w_svpt"),
            ("loser_id", "winner_id", "loser_name", "winner_name", "l_ace", "l_svpt"),
        ):
            server_id_value = row.get(server_id)
            returner_id_value = row.get(returner_id)
            server_profile = profiles.get(server_id_value, {})
            returner_profile = profiles.get(returner_id_value, {})
            serve_rate = server_profile.get("serve_rate")
            concede_rate = returner_profile.get("concede_rate")
            server_source = server_profile.get("serve_rate_source") or "player-profile"
            returner_source = returner_profile.get("concede_rate_source") or "player-profile"
            server_fallback = serve_rate is None or server_source == "tour-average-prior-only"
            returner_fallback = concede_rate is None or returner_source == "tour-average-prior-only"
            if server_fallback:
                serve_rate = tour_rate
                server_source = "tour-average-prior-only"
                server_fallbacks += 1
            else:
                server_profiles_matched += 1
            if returner_fallback:
                concede_rate = tour_rate
                returner_source = "tour-average-prior-only"
                returner_fallbacks += 1
            else:
                returner_profiles_matched += 1
            service_points = int(num(row.get(points_key)))
            aces = int(num(row.get(aces_key)))
            if (
                service_points <= 0 or aces < 0 or aces > service_points
                or serve_rate is None or concede_rate is None
                or not 0.0 < serve_rate < 1.0 or not 0.0 < concede_rate < 1.0
                or tour_rate is None or not 0.0 < tour_rate < 1.0
            ):
                continue
            expected = expected_calibrated_aces(serve_rate, concede_rate, tour_rate, service_points)
            if not math.isfinite(expected) or expected <= MIN_EXPECTED_ACES:
                continue
            adjusted_expected = max(expected, EXPECTED_ACES_FLOOR)
            sides.append({
                "matchId": match_id,
                "server": row.get(server_name) or str(server_id_value or "Unknown"),
                "returner": row.get(returner_name) or str(returner_id_value or "Unknown"),
                "actualAces": aces,
                "servicePoints": service_points,
                "serveRate": serve_rate,
                "returnerConcedeRate": concede_rate,
                "expectedAces": adjusted_expected,
                "actualExpectedRatio": aces / adjusted_expected,
                "serverProfileSource": server_source,
                "returnerProfileSource": returner_source,
            })
    highest = sorted(sides, key=lambda side: side["actualExpectedRatio"], reverse=True)[:retain_per_tail]
    lowest = sorted(sides, key=lambda side: side["actualExpectedRatio"])[:retain_per_tail]
    retained = {}
    for side in highest + lowest:
        retained[(side["matchId"], side["server"])] = side
    return {
        "totalServerSides": len(event_rows) * 2,
        "aces": total_aces,
        "totalServicePoints": total_points,
        "tourAverageAceRate": tour_rate,
        "serverProfilesMatched": server_profiles_matched,
        "returnerProfilesMatched": returner_profiles_matched,
        "serverProfilesFallback": server_fallbacks,
        "returnerProfilesFallback": returner_fallbacks,
        "sideDiagnostics": list(retained.values()),
    }



def season_ratings(year, rows, source=None):
    """
    Chronological LOEO ratings using the finest trustworthy source chronology available.

    Per-match timestamp sources are ordered by UTC instant; daily sources use strict
    timezone-safe calendar-day precedence when no clock is supplied. Legacy week/start-date
    archives use a conservative maximum event-window bound by source level, not a fixed
    quarantine shared by Grand Slams, Masters, ATP 500s and ATP 250s.
    """
    source_precision_by_event = _source_event_date_granularity(rows, source)
    source_precision_summary = _source_match_date_granularity(rows, source)
    target_rows = valid_rows(rows)
    rows_by_event = defaultdict(list)
    chronology_rows_by_event = defaultdict(list)
    for row in rows:
        if row.get("tourney_level") not in TARGET_LEVELS:
            continue
        # Chronology evidence must describe the same singles population as the rating.
        # Preserve singles with missing serve stats, but do not let doubles or unrelated
        # draw levels set an earlier target start date.
        if row.get("tourney_level") == "D":
            names = (row.get("winner_name") or "", row.get("loser_name") or "")
            if any("/" in name or " & " in name or " and " in name.lower() for name in names):
                continue
        tid = tournament_id(row.get("tourney_name"))
        if tid is not None and is_supported_davis_cup_calculation_year(tid, year, source=source):
            chronology_rows_by_event[tid].append(row)
    for row in target_rows:
        tid = tournament_id(row.get("tourney_name"))
        if tid is not None and is_supported_davis_cup_calculation_year(tid, year, source=source):
            rows_by_event[tid].append(row)

    details = {}
    for tid, event_rows in rows_by_event.items():
        # Establish the target's true first observed day from every identified source row,
        # including matches whose serve statistics are missing/invalid. Only the target's
        # score sample is restricted to valid rows; missing-stat matches still set chronology.
        chronology_rows = chronology_rows_by_event.get(tid) or event_rows
        dates = [_match_date(row) for row in chronology_rows]
        dates = [value for value in dates if value is not None]
        if not dates:
            continue
        event_start = min(dates)
        event_last_observed_match_date = max(dates)
        target_start_timestamp = _event_first_match_timestamp(chronology_rows)
        target_date_granularity = _event_date_granularity(chronology_rows, source_precision_by_event)
        if target_date_granularity != "match_day":
            event_last_observed_match_date = None
        if target_start_timestamp is not None:
            chronology_precision = "match_timestamp"
            chronology_rule = "strictly_before_target_match_timestamp_utc"
            quarantine_days = 0
        elif target_date_granularity == "match_day":
            chronology_precision = "match_day"
            chronology_rule = "strictly_before_target_local_match_day"
            quarantine_days = 0
        else:
            chronology_precision = "tournament_start"
            chronology_rule = "legacy_max_event_window_by_source_level"
            quarantine_days = 0

        # Legacy rows use their own level's maximum possible event window. A source row
        # enters training only after that entire window is safely before the target.
        training_precision_by_event = source_precision_by_event
        profiles, tour_rate, training_rows = _chronological_profiles_and_anchor(
            year, rows, event_start, training_precision_by_event, target_start_timestamp,
        )
        if profiles is None or tour_rate is None:
            continue
        if tour_rate is None or not 0.0 < tour_rate < 1.0:
            continue
        profile_coverage = ensure_event_profiles(event_rows, profiles, tour_rate)
        clusters = event_match_ratio_clusters(event_rows, profiles, tour_rate)
        side_diagnostics = event_side_diagnostics(event_rows, profiles, tour_rate)
        ratios = [ratio for cluster in clusters for ratio in cluster]
        sides = len(ratios)
        if sides < MIN_EVENT_RATING_SERVER_SIDES:
            continue
        aces = sum(num(row["w_ace"]) + num(row["l_ace"]) for row in event_rows)
        points = sum(num(row["w_svpt"]) + num(row["l_svpt"]) for row in event_rows)
        event_raw = sum(ratios) / sides
        # The chronological profiles and tour anchor are already fitted for this target.
        # Score earlier standard ATP events once here, using that same pre-target snapshot,
        # rather than refitting the profiles a second time during output serialisation.
        anchor_events, _, _, _ = event_raw_scores(training_rows, profiles, tour_rate=tour_rate)
        eligible_anchor = {
            event_id: value for event_id, value in anchor_events.items()
            if event_id not in CALC_TARGET_ONLY_EVENT_IDS
        }
        mean_raw = sum(eligible_anchor.values()) / len(eligible_anchor) if eligible_anchor else event_raw
        training_dates = [_match_date(row) for row in training_rows]
        training_dates = [value for value in training_dates if value is not None]
        same_day_excluded = 0
        date_interval_excluded = 0
        legacy_fallback_buffer_excluded = 0
        eligible_training_population = calculation_eligible_rows(valid_rows(rows))
        for row in eligible_training_population:
            if tournament_id(row.get("tourney_name")) == tid:
                continue
            row_date = _match_date(row)
            if row_date is None:
                continue
            row_precision = training_precision_by_event.get(
                _source_event_key(row), "tournament_start",
            )
            row_timestamp = _match_start_timestamp(row)
            timestamp_comparable = target_start_timestamp is not None and row_timestamp is not None
            if timestamp_comparable and row_timestamp < target_start_timestamp:
                continue
            if row_precision == "match_day":
                if row_date == event_start:
                    same_day_excluded += 1
                elif (
                    row_date < event_start
                    and not _row_precedes_target(
                        row, event_start, row_precision, target_start_timestamp,
                    )
                ):
                    date_interval_excluded += 1
            elif not _legacy_event_window_precedes_target(
                row, event_start, target_start_timestamp,
            ):
                legacy_fallback_buffer_excluded += 1

        details[tid] = {
            "raw": event_raw,
            "normalisation": mean_raw,
            "clusters": clusters,
            "sides": sides,
            "matches": len(event_rows),
            "ace": 100.0 * aces / points if points else None,
            "profile_coverage": profile_coverage,
            "calculation_diagnostics": side_diagnostics,
            "chronology_cutoff": (
                target_start_timestamp.isoformat().replace("+00:00", "Z")
                if target_start_timestamp is not None else event_start.isoformat()
            ),
            "event_start_date": event_start.isoformat(),
            "event_last_observed_match_date": (
                event_last_observed_match_date.isoformat()
                if event_last_observed_match_date is not None else None
            ),
            "chronology_precision": chronology_precision,
            "target_date_granularity": target_date_granularity,
            "source_date_granularity": source_precision_summary,
            "chronology_rule": chronology_rule,
            "quarantine_days": quarantine_days,
            "training_match_date_max": max(training_dates).isoformat() if training_dates else None,
            "same_day_excluded_matches": same_day_excluded,
            "date_interval_excluded_matches": date_interval_excluded,
            "legacy_fallback_buffer_excluded_matches": legacy_fallback_buffer_excluded,
            "legacy_fallback_training_matches": sum(
                1 for row in training_rows
                if training_precision_by_event.get(_source_event_key(row), "tournament_start")
                != "match_day"
            ),
        }

    # Small events receive a CALC rating too, but do not move the season anchor. Prefer
    # events with at least twenty adjusted sides for the normaliser. If an entire season has
    # no such event, fall back to all rateable events rather than suppressing the season.
    # Next Gen receives its own rating, but never defines the standard ATP season anchor.
    standard_format_details = {
        tid: item for tid, item in details.items()
        if tid not in CALC_TARGET_ONLY_EVENT_IDS
    }
    output = {}
    for tid, item in details.items():
        mean_raw = item["normalisation"]
        seed = int(hashlib.sha256(f"{year}:{tid}:match-cluster-ci-v1".encode()).hexdigest()[:8], 16)
        # A match-cluster interval is not meaningful for fewer than five independent matches.
        interval = (
            bootstrap_event_interval(item["clusters"], mean_raw, seed=seed)
            if len(item["clusters"]) >= MIN_EVENT_CI_MATCH_CLUSTERS
            else None
        )
        ci = {
            "low": round(interval["low"], 3), "high": round(interval["high"], 3),
            "method": "match-cluster-conditional-v1",
        } if interval else {}
        common = {
            "ace": round(item["ace"], 1) if item["ace"] is not None else None,
            "matches": item["matches"],
            "tourPriorSides": item["profile_coverage"]["tour_prior_sides"],
            "careerHistoryServerSides": item["profile_coverage"]["career_history_server_sides"],
            "careerHistoryReturnerSides": item["profile_coverage"]["career_history_returner_sides"],
            "tourPriorSides": item["profile_coverage"]["tour_prior_sides"],
            "tourPriorServerSides": item["profile_coverage"]["tour_prior_server_profiles"],
            "tourPriorReturnerSides": item["profile_coverage"]["tour_prior_returner_profiles"],
        }
        output[tid] = {
            **common,
            "speed": item["raw"] / mean_raw,
            "adjustedServerSides": item["sides"],
            "speedCiLow": ci.get("low"),
            "speedCiHigh": ci.get("high"),
            "speedCiMethod": ci.get("method"),
            "calculationDiagnostics": {
                "chronologicalCutoff": item["chronology_cutoff"],
                "eventStartDate": item["event_start_date"],
                "eventLastObservedMatchDate": item["event_last_observed_match_date"],
                "chronologyPrecision": item["chronology_precision"],
                "targetDateGranularity": item["target_date_granularity"],
                "sourceDateGranularity": item["source_date_granularity"],
                "chronologyRule": item["chronology_rule"],
                "trainingMatchDateMax": item["training_match_date_max"],
                "sameDayExcludedMatches": item["same_day_excluded_matches"],
                "dateIntervalExcludedMatches": item["date_interval_excluded_matches"],
                "legacyFallbackEventWindowExcludedMatches": item["legacy_fallback_buffer_excluded_matches"],
                "legacyFallbackTrainingMatches": item["legacy_fallback_training_matches"],
                "legacyMaxEventDaysByLevel": LEGACY_MAX_EVENT_DAYS_BY_LEVEL,
                "legacyUnknownMaxEventDays": LEGACY_UNKNOWN_MAX_EVENT_DAYS,
                "quarantineDays": 0,
                "completedMatches": item["matches"],
                "adjustedServerSides": item["sides"],
                "totalServerSides": item["calculation_diagnostics"]["totalServerSides"],
                "aces": item["calculation_diagnostics"]["aces"],
                "totalServicePoints": item["calculation_diagnostics"]["totalServicePoints"],
                "tourAverageAceRate": item["calculation_diagnostics"]["tourAverageAceRate"],
                "rawAdjustedEventScore": item["raw"],
                "seasonNormalisation": mean_raw,
                "serverProfilesMatched": item["calculation_diagnostics"]["serverProfilesMatched"],
                "returnerProfilesMatched": item["calculation_diagnostics"]["returnerProfilesMatched"],
                "serverProfilesFallback": item["calculation_diagnostics"]["serverProfilesFallback"],
                "returnerProfilesFallback": item["calculation_diagnostics"]["returnerProfilesFallback"],
                "sideDiagnostics": item["calculation_diagnostics"]["sideDiagnostics"],
            },
        }
    return output


def live_event_exclusion(live_path=None, today=None, now=None):
    """Return active tournament IDs from current in-progress or date-active scheduled rows."""
    live_path = live_path or os.path.join(os.path.dirname(__file__), "..", "data", "live.json")
    today = today or date.today()
    now = now or datetime.now(timezone.utc)
    try:
        with open(live_path, encoding="utf-8") as source:
            payload = json.load(source)
        generated = datetime.fromisoformat(payload["generated"].replace("Z", "+00:00"))
        if generated.tzinfo is None:
            generated = generated.replace(tzinfo=timezone.utc)
        if now.tzinfo is None:
            now = now.replace(tzinfo=timezone.utc)
        age = now - generated
        if age < timedelta(seconds=-900) or age > timedelta(days=LIVE_EVENT_FEED_MAX_AGE_DAYS):
            return {"fresh": False, "generated": generated.isoformat(), "eventIds": [], "reason": f"manifest age {age} outside freshness window"}
        active = set()
        for event in payload.get("events", []):
            status = str(event.get("status", "")).strip().lower()
            if status not in {"in_progress", "scheduled"} or int(event.get("s", 0)) != today.year:
                continue
            try:
                start = date.fromisoformat(event["start"])
                end = date.fromisoformat(event["end"])
            except (KeyError, TypeError, ValueError):
                continue
            if start <= today <= end and event.get("t"):
                active.add(str(event["t"]))
        return {"fresh": True, "generated": generated.isoformat(), "eventIds": sorted(active), "reason": None}
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        return {"fresh": False, "generated": None, "eventIds": [], "reason": f"manifest unavailable or invalid: {error}"}


def exclude_live_event_rows(rows, event_ids):
    """Remove active tournament rows from live player profiles and baseline fitting."""
    excluded = set(event_ids or ())
    if not excluded:
        return list(rows)
    return [row for row in rows if tournament_id(row.get("tourney_name")) not in excluded]


def main():
    published_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main",
                                  "assets", "pace_dataset.json")
    published = json.load(open(published_path))
    published_speed = {r["t"]: r["speed"] for r in published["readings"]
                       if r.get("speed") is not None and r["s"] == 2026}

    history = {}
    season_sources = {}
    davis_cup_coverage = {}
    for year in YEARS:
        try:
            rows = load(year)
        except HTTPError as error:
            if year == date.today().year and error.code == 404:
                print(f"{year}: archive not published yet; skipping current-year history")
                continue
            raise
        if year == date.today().year:
            audit = davis_cup_coverage_audit(rows, year)
            audit["source"] = SOURCE_BY_YEAR.get(year, "unknown")
            audit["validatedSource"] = SOURCE_BY_YEAR.get(year) == TML_DAVIS_CUP_SOURCE
            davis_cup_coverage[str(year)] = audit
            print(
                "Davis Cup source reconciliation: "
                f"{audit['identifiedSingles']} identified singles · "
                f"{audit['completedPlayedSingles']} played · "
                f"{audit['scorelessSingles']} scoreless/pending-result · "
                f"{audit['walkoverOrNoPlaySingles']} walkovers/no-play · "
                f"{audit['completedWithValidServeStats']} valid stats · "
                f"{audit['completedMissingServeStats']} missing stats · "
                f"{audit['completedMalformedStats']} malformed stats"
            )
        ratings = season_ratings(year, rows, source=SOURCE_BY_YEAR.get(year))
        history[year] = ratings
        season_sources[year] = SOURCE_BY_YEAR.get(year, "Jeff Sackmann ATP archive")
        print(f"{year}: {len(rows):5d} matches, {len(ratings):3d} events rated")

    ours = history.get(2026, {})
    common = sorted(t for t in set(ours) & set(published_speed) if ours[t].get("speed") is not None)
    if len(common) >= 5:
        pairs = [(ours[t]["speed"], published_speed[t]) for t in common]
        n = len(pairs)
        mx = sum(p[0] for p in pairs) / n; my = sum(p[1] for p in pairs) / n
        cov = sum((p[0] - mx) * (p[1] - my) for p in pairs)
        sx = sum((p[0] - mx) ** 2 for p in pairs) ** 0.5
        sy = sum((p[1] - my) ** 2 for p in pairs) ** 0.5
        r = cov / (sx * sy) if sx and sy else float("nan")
        mae = sum(abs(p[0] - p[1]) for p in pairs) / n
        print(f"\nvalidation on {n} events in 2026: correlation r = {r:.3f}, mean |diff| = {mae:.3f}")

    if "--write" in sys.argv:
        # Active events must not train their own live profiles, tour anchor or normaliser.
        current_year = date.today().year
        live_manifest = live_event_exclusion()
        profile_year = current_year
        excluded_live_event_ids = []
        excluded_live_event_matches = 0
        if live_manifest["fresh"]:
            try:
                all_profile_rows = calculation_eligible_rows(valid_rows(load(profile_year)))
            except HTTPError:
                all_profile_rows = []
            excluded_live_event_ids = live_manifest["eventIds"]
            profile_rows = exclude_live_event_rows(all_profile_rows, excluded_live_event_ids)
            excluded_live_event_matches = len(all_profile_rows) - len(profile_rows)
        else:
            profile_year = current_year - 1
            print(f"live manifest stale; using completed season {profile_year}: {live_manifest['reason']}")
            profile_rows = calculation_eligible_rows(valid_rows(load(profile_year)))
        current_profiles = player_rates(profile_rows, prior_points=PROFILE_PRIOR_SERVICE_POINTS)
        profiles = build_live_profiles(profile_year, current_profiles)
        tour_rate = tour_average_ace_rate(profile_rows)
        profile_fit_coverage = ensure_event_profiles(profile_rows, profiles, tour_rate)
        raw_events, _, _, _ = event_raw_scores(profile_rows, profiles, tour_rate=tour_rate)
        if len(raw_events) < 5 and profile_year == current_year:
            profile_year = current_year - 1
            excluded_live_event_ids = []
            excluded_live_event_matches = 0
            profile_rows = calculation_eligible_rows(valid_rows(load(profile_year)))
            current_profiles = player_rates(profile_rows, prior_points=PROFILE_PRIOR_SERVICE_POINTS)
            profiles = build_live_profiles(profile_year, current_profiles)
            tour_rate = tour_average_ace_rate(profile_rows)
            profile_fit_coverage = ensure_event_profiles(profile_rows, profiles, tour_rate)
            raw_events, _, _, _ = event_raw_scores(profile_rows, profiles, tour_rate=tour_rate)
        if not raw_events:
            raise RuntimeError(f"cannot build live opponent-adjustment baseline for {current_year}: no rated events in current or previous season")
        normalisation = sum(raw_events.values()) / len(raw_events)

        # Historical and live ratings share the same multi-season profile backfill;
        # only their target-match population and normalisation policies differ.
        live_profiles = profiles
        unique_live_profiles, identity_diagnostics = dedupe_live_profiles_with_diagnostics(
            live_profiles.values()
        )
        live_profile_rows = [
            {
                "name": profile["name"],
                "serveRate": profile["serve_rate"],
                "concedeRate": profile["concede_rate"],
                "serveRateSeason": profile["serve_rate_season"],
                "serveRateSource": profile["serve_rate_source"],
                "serveRateSampleMatches": int(profile["serve_rate_sample_matches"]),
                # Service-point totals are integer counts. The archive parser uses float
                # arithmetic for rates, so explicitly cast before serialising for Kotlin Int?.
                "serveRateSampleServicePoints": int(round(profile["serve_rate_sample_points"])),
                "serveRatePriorServicePoints": profile.get("serve_rate_prior_points"),
                "concedeRateSeason": profile["concede_rate_season"],
                "concedeRateSource": profile.get("concede_rate_source"),
                "concedeRateSampleMatches": int(profile.get("concede_matches", 0)),
                "concedeRateSampleServicePoints": int(round(profile.get("concede_service_points", 0.0))),
                "concedeRatePriorServicePoints": profile.get("concede_rate_prior_points"),
            }
            for profile in unique_live_profiles
            if profile["name"] and (
                profile["serve_rate"] is not None or profile["concede_rate"] is not None
            )
        ]
        source_counts = {
            "currentSeasonQualifiedServeProfiles": sum(
                p["serveRateSource"] == "current-season-qualified" for p in live_profile_rows
            ),
            "careerHistorySurfaceShrunkServeProfiles": sum(
                p["serveRateSource"] == "career-history-surface-shrunk" for p in live_profile_rows
            ),
            "careerHistoryPooledShrunkServeProfiles": sum(
                p["serveRateSource"] == "career-history-pooled-shrunk" for p in live_profile_rows
            ),
            "tourAveragePriorOnlyProfiles": sum(
                p["serveRateSource"] == "tour-average-prior-only" for p in live_profile_rows
            ),
            "withoutServeRate": sum(p["serveRate"] is None for p in live_profile_rows),
            "withoutConcedeRate": sum(p["concedeRate"] is None for p in live_profile_rows),
        }
        if tour_rate is None or not 0.0 < tour_rate < 1.0:
            raise RuntimeError(f"cannot build calibrated live baseline for {current_year}: invalid tour-average ace rate")
        history_years_loaded = sorted(
            year for year in _PROFILE_YEAR_CACHE
            if PROFILE_HISTORY_START_YEAR <= year < profile_year
        )
        history_player_matches = sum(
            int(profile.get("pooled_serve_matches", 0))
            for year in history_years_loaded
            for profile in _PROFILE_YEAR_CACHE[year].values()
        ) // 2
        baseline = {
            "season": current_year,
            "profileSeason": profile_year,
            "profileHistoryStartYear": PROFILE_HISTORY_START_YEAR,
            "profileHistoryEndYear": profile_year - 1,
            "profileHistoryYearsLoaded": len(history_years_loaded),
            "profileHistoryMatches": history_player_matches,
            "profileHistoryDecayPerYear": LIVE_PROFILE_HISTORY_DECAY,
            "tourAceRate": tour_rate,
            "normalisation": normalisation,
            "generated": datetime.now(timezone.utc).isoformat(),
            "sourceMetadata": SOURCE_METADATA_BY_YEAR.get(profile_year),
            "liveFeedGenerated": live_manifest.get("generated"),
            "liveFeedFresh": bool(live_manifest.get("fresh")),
            "excludedEventIds": excluded_live_event_ids,
            "excludedEventMatches": excluded_live_event_matches,
            "profileTrainingMatches": len(profile_rows),
            "profilePriorServicePoints": PROFILE_PRIOR_SERVICE_POINTS,
            "liveProfilePriorServicePoints": LIVE_PROFILE_PRIOR_SERVICE_POINTS,
            "profiles": live_profile_rows,
            "profileCoverage": {
                "playerProfiles": len(live_profile_rows),
                "currentSeasonMatchRows": len(profile_rows),
                "historicalProfileYears": len(history_years_loaded),
                "historicalProfileMatches": history_player_matches,
                "historicalServerSidesRateableAfterFallback": profile_fit_coverage["rateable_server_sides"],
                "tourPriorServerSides": profile_fit_coverage["tour_prior_server_profiles"],
                "tourPriorReturnerSides": profile_fit_coverage["tour_prior_returner_profiles"],
                "tourPriorSides": profile_fit_coverage["tour_prior_sides"],
                "careerHistoryServerSides": profile_fit_coverage["career_history_server_sides"],
                "careerHistoryReturnerSides": profile_fit_coverage["career_history_returner_sides"],
                **identity_diagnostics,
                **source_counts,
            },
            "ratedEvents": len(raw_events),
        }
        print(
            "live profile coverage: "
            f"{source_counts['currentSeasonQualifiedServeProfiles']} current-season qualified, "
            f"{source_counts['careerHistorySurfaceShrunkServeProfiles']} career-history surface-shrunk, "
            f"{source_counts['careerHistoryPooledShrunkServeProfiles']} career-history pooled-shrunk, "
            f"{source_counts['withoutServeRate']} without serve rate"
        )
        baseline_path = os.path.join(os.path.dirname(__file__), "..", "data", "live_speed_baseline.json")
        os.makedirs(os.path.dirname(baseline_path), exist_ok=True)
        json.dump(baseline, open(baseline_path, "w"), indent=1, ensure_ascii=False)
        print(f"wrote {baseline_path}: {len(baseline['profiles'])} player profiles, normalisation {normalisation:.6f}")

        out_path = os.path.join(os.path.dirname(__file__), "..", "data", "speed_history.json")
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        payload = {
            "generated": date.today().isoformat(),
            "method": "chronological leave-one-tournament-out calibrated server/returner expected-aces odds ratio using actual match timestamps/days where available and conservative level-based event-window bounds for legacy tournament-start-only archives; player profiles aggregate up to 35 prior ATP seasons from 1991 with 0.90 per-year recency decay and a 150-service-point prior; qualified clay and hard/grass rates use the 1/3–2/3 neutral blend, pooled profiles are shrunk toward the tour rate, current TML alphanumeric IDs are crosswalked to historical Sackmann IDs only by unique normalised full name, and players with no defensible personal rate receive an explicit tour-average prior; valid-side actual/expected ratios use the 0.5 expected-aces floor and are season-normalised",
            "source": "Jeff Sackmann ATP archive for historical seasons; TennisMyLife annual ATP CSV for the current season; TML ongoing_tourneys.csv is merged for current-year Davis Cup targets when validated.",
            "season_sources": {str(y): source for y, source in season_sources.items()},
            "source_metadata": {
                str(y): SOURCE_METADATA_BY_YEAR[y] for y in SOURCE_METADATA_BY_YEAR
            },
            "davis_cup_coverage": davis_cup_coverage,
            "seasons": {str(y): {t: {k: round(v, 3) if isinstance(v, float) else v
                                     for k, v in d.items()} for t, d in ratings.items()}
                        for y, ratings in history.items()},
        }
        json.dump(payload, open(out_path, "w"), indent=1)
        print("wrote", out_path)


if __name__ == "__main__":
    main()
