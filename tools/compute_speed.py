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
import csv, hashlib, json, math, os, random, sys, urllib.request, unicodedata
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
from urllib.error import HTTPError

CACHE = os.path.join(os.path.dirname(__file__), "..", ".cache", "atp")
MIRROR = "https://raw.githubusercontent.com/Aneeshers/tennis-sackmann-archive/main/atp/atp_matches_{}.csv"
TML_CURRENT_URL = "https://stats.tennismylife.org/data/{}.csv"
YEARS = range(2012, date.today().year + 1)
LEVELS = {"G", "M", "A", "F", "250", "500"}  # Slams, Masters, ATP 250/500, Finals
SOURCE_BY_YEAR = {}
SOURCE_METADATA_BY_YEAR = {}
MIN_MATCHES_PER_SURFACE = 3
MIN_EVENT_SERVER_SIDES = 20
PROVISIONAL_EVENT_SERVER_SIDES = 10
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
    "Paris Masters": "paris", "Tour Finals": "atpfinals",
    "Brisbane": "brisbane", "Hong Kong": "hongkong", "United Cup": "unitedcup", "Laver Cup": "lavercup", "Auckland": "auckland",
    # TennisMyLife uses these shorter/alternate event labels in its ATP CSV.
    "Monte Carlo": "montecarlo", "Cincinnati": "cincinnati", "Shanghai": "shanghai",
    "'s-Hertogenbosch": "shertogenbosch", "’s-Hertogenbosch": "shertogenbosch",
    "Adelaide": "adelaide", "Montpellier": "montpellier", "Dallas": "dallas",
    "Rotterdam": "rotterdam", "Delray Beach": "delraybeach",
    "Buenos Aires": "buenosaires", "Rio de Janeiro": "rio", "Doha": "doha",
    "Acapulco": "acapulco", "Dubai": "dubai", "Santiago": "santiago",
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
            return list(csv.DictReader(f))

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
        if r.get("tourney_level") not in LEVELS:
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
PROFILE_HISTORY_START_YEAR = 1991
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
    """Cache source rows/profile estimates so a 35-year backfill is not re-parsed per event."""
    if year not in _PROFILE_YEAR_CACHE:
        _PROFILE_YEAR_CACHE[year] = player_rates(valid_rows(load(year)))
    return _PROFILE_YEAR_CACHE[year]


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
            )
            profile["serve_rate_sample_points"] = int(round(
                source["clay_service_points"] + source["hard_grass_service_points"]
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
    """Crosswalk names and prevent valid event sides from being dropped for missing profiles.

    A unique normalized full name may bridge TML alphanumeric IDs to Sackmann numeric IDs.
    If no trustworthy player-specific rate exists, use an explicit tour-average prior and
    count that use. Ambiguous identities are never assigned another player's profile.
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
        "career_history_server_sides": 0,
        "career_history_returner_sides": 0,
        "missing_server_profile_before_fallback": 0,
        "missing_returner_profile_before_fallback": 0,
        "missing_both_profiles_before_fallback": 0,
        "tour_prior_sides": 0,
        "rateable_server_sides": 0,
    }
    for row in event_rows:
        profiles_by_role = {}
        for player_id_key, player_name_key in (
            ("winner_id", "winner_name"),
            ("loser_id", "loser_name"),
        ):
            player_id = row.get(player_id_key)
            player_name = row.get(player_name_key) or ""
            profile = profiles.get(player_id)
            candidate = unique_by_name.get(_normalise_profile_name(player_name))
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

            # A current-ID record may exist but have one missing role; merge only that
            # role from a unique full-name crosswalk before falling back to the tour prior.
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

        side_fallbacks = []
        for player_id_key, player_role in (
            ("winner_id", "server"),
            ("loser_id", "returner"),
            ("loser_id", "server"),
            ("winner_id", "returner"),
        ):
            profile = profiles_by_role[player_id_key]
            rate_key = "serve_rate" if player_role == "server" else "concede_rate"
            source_key = "serve_rate_source" if player_role == "server" else "concede_rate_source"
            if profile.get(rate_key) is None:
                coverage[f"missing_{player_role}_profile_before_fallback"] += 1
                profile[rate_key] = tour_rate
                profile[source_key] = "tour-average-prior-only"
                coverage[f"tour_prior_{player_role}_profiles"] += 1
                side_fallbacks.append((player_id_key, player_role))
            elif str(profile.get(source_key) or "").startswith("career-history"):
                coverage[f"career_history_{player_role}_sides"] += 1

        # Rate each actual server side with the right two player-role parameters.
        winner = profiles_by_role["winner_id"]
        loser = profiles_by_role["loser_id"]
        sides = (
            (
                winner.get("serve_rate"), loser.get("concede_rate"),
                number(row, "w_svpt"),
            ),
            (
                loser.get("serve_rate"), winner.get("concede_rate"),
                number(row, "l_svpt"),
            ),
        )
        for server_rate, concede_rate, service_points in sides:
            if (
                server_rate is not None and concede_rate is not None
                and math.isfinite(server_rate) and math.isfinite(concede_rate)
                and 0.0 < server_rate < 1.0 and 0.0 < concede_rate < 1.0
                and tour_rate is not None and 0.0 < tour_rate < 1.0
                and service_points is not None and service_points > 0
            ):
                expected = expected_calibrated_aces(
                    server_rate, concede_rate, tour_rate, service_points
                )
                if math.isfinite(expected) and expected > MIN_EXPECTED_ACES:
                    coverage["rateable_server_sides"] += 1
        if side_fallbacks:
            # Count the number of server sides that used at least one tour-prior rate.
            for side_index, (server_rate, concede_rate, _points) in enumerate(sides):
                server_id_key = "winner_id" if side_index == 0 else "loser_id"
                returner_id_key = "loser_id" if side_index == 0 else "winner_id"
                server_profile = profiles_by_role[server_id_key]
                returner_profile = profiles_by_role[returner_id_key]
                if (
                    server_profile.get("serve_rate_source") == "tour-average-prior-only"
                    or returner_profile.get("concede_rate_source") == "tour-average-prior-only"
                ):
                    coverage["tour_prior_sides"] += 1

    return coverage


def dedupe_live_profiles_with_diagnostics(profiles):
    """
    Build the name-keyed Android index without silently merging conflicting same-name players.

    Different archive IDs with the same normalized name are collapsed only when same-season
    rates agree. Conflicting same-season identities are omitted because ATP live stats arrive
    as display names, not stable player IDs. Across seasons, the newest/strongest record wins.
    """
    source_priority = {
        "current-season-qualified": 4,
        "prior-season-qualified": 3,
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
    concede_priority = {"surface-qualified": 2, "pooled-fallback": 1}

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
    excluded = exclude_event_ids or set()
    if tour_rate is None:
        tour_rate = tour_average_ace_rate(usable)
    if tour_rate is None or not 0.0 < tour_rate < 1.0:
        return {}, defaultdict(float), defaultdict(float), defaultdict(int)
    per_event = defaultdict(list)
    event_aces = defaultdict(float); event_sv = defaultdict(float)
    event_matches = defaultdict(int)

    for r in usable:
        tid = NAMES.get((r["tourney_name"] or "").strip())
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

    raw = {t: sum(v) / len(v) for t, v in per_event.items() if len(v) >= 20}
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


def season_ratings(year, rows):
    """LOEO tournament ratings with a long-history, recency-weighted player-profile backfill."""
    usable = valid_rows(rows)
    rows_by_event = defaultdict(list)
    mapped_rows = []
    for row in usable:
        tid = NAMES.get((row.get("tourney_name") or "").strip())
        mapped_rows.append((tid, row))
        if tid is not None:
            rows_by_event[tid].append(row)

    details = {}
    for tid, event_rows in rows_by_event.items():
        training_rows = [row for mapped_id, row in mapped_rows if mapped_id != tid]
        if not training_rows:
            continue
        current_profiles = player_rates(
            training_rows,
            prior_points=PROFILE_PRIOR_SERVICE_POINTS,
        )
        # Backfill from every available prior ATP season (stats archive begins in 1991).
        # build_live_profiles crosswalks current TML alphanumeric IDs to historical archive
        # IDs by unique normalised full name and aggregates weighted career samples.
        profiles = build_live_profiles(year, current_profiles)
        tour_rate = tour_average_ace_rate(training_rows)
        if tour_rate is None or not 0.0 < tour_rate < 1.0:
            continue
        profile_coverage = ensure_event_profiles(event_rows, profiles, tour_rate)
        clusters = event_match_ratio_clusters(event_rows, profiles, tour_rate)
        ratios = [ratio for cluster in clusters for ratio in cluster]
        sides = len(ratios)
        if sides < PROVISIONAL_EVENT_SERVER_SIDES:
            continue
        aces = sum(num(row["w_ace"]) + num(row["l_ace"]) for row in event_rows)
        points = sum(num(row["w_svpt"]) + num(row["l_svpt"]) for row in event_rows)
        details[tid] = {
            "raw": sum(ratios) / sides,
            "clusters": clusters,
            "sides": sides,
            "matches": len(event_rows),
            "ace": 100.0 * aces / points if points else None,
            "profile_coverage": profile_coverage,
        }

    standard = {tid: item for tid, item in details.items() if item["sides"] >= MIN_EVENT_SERVER_SIDES}
    if not standard:
        return {}
    mean_raw = sum(item["raw"] for item in standard.values()) / len(standard)
    output = {}
    for tid, item in details.items():
        seed = int(hashlib.sha256(f"{year}:{tid}:match-cluster-ci-v1".encode()).hexdigest()[:8], 16)
        interval = bootstrap_event_interval(item["clusters"], mean_raw, seed=seed)
        ci = {
            "low": round(interval["low"], 3), "high": round(interval["high"], 3),
            "method": "match-cluster-conditional-v1",
        } if interval else {}
        common = {
            "ace": round(item["ace"], 1) if item["ace"] is not None else None,
            "matches": item["matches"],
            "tourPriorSides": item["profile_coverage"]["tour_prior_sides"],
            "careerHistoryServerProfiles": item["profile_coverage"]["career_history_server_profiles"],
            "careerHistoryReturnerProfiles": item["profile_coverage"]["career_history_returner_profiles"],
        }
        if item["sides"] >= MIN_EVENT_SERVER_SIDES:
            output[tid] = {
                **common,
                "speed": item["raw"] / mean_raw,
                "adjustedServerSides": item["sides"],
                "speedCiLow": ci.get("low"),
                "speedCiHigh": ci.get("high"),
                "speedCiMethod": ci.get("method"),
            }
        else:
            output[tid] = {
                **common,
                "provisionalSpeed": item["raw"] / mean_raw,
                "provisionalServerSides": item["sides"],
                "provisionalMatches": item["matches"],
                "provisionalCiLow": ci.get("low"),
                "provisionalCiHigh": ci.get("high"),
                "provisionalCiMethod": ci.get("method"),
            }
    return output


def live_event_exclusion(live_path=None, today=None, now=None):
    """Return active tournament IDs only from a recent, date-valid live manifest."""
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
            if event.get("status") != "in_progress" or int(event.get("s", 0)) != today.year:
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
    return [row for row in rows if NAMES.get((row.get("tourney_name") or "").strip()) not in excluded]


def main():
    published_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main",
                                  "assets", "pace_dataset.json")
    published = json.load(open(published_path))
    published_speed = {r["t"]: r["speed"] for r in published["readings"]
                       if r.get("speed") is not None and r["s"] == 2026}

    history = {}
    season_sources = {}
    for year in YEARS:
        try:
            rows = load(year)
        except HTTPError as error:
            if year == date.today().year and error.code == 404:
                print(f"{year}: archive not published yet; skipping current-year history")
                continue
            raise
        ratings = season_ratings(year, rows)
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
                all_profile_rows = valid_rows(load(profile_year))
            except HTTPError:
                all_profile_rows = []
            excluded_live_event_ids = live_manifest["eventIds"]
            profile_rows = exclude_live_event_rows(all_profile_rows, excluded_live_event_ids)
            excluded_live_event_matches = len(all_profile_rows) - len(profile_rows)
        else:
            profile_year = current_year - 1
            print(f"live manifest stale; using completed season {profile_year}: {live_manifest['reason']}")
            profile_rows = valid_rows(load(profile_year))
        current_profiles = player_rates(profile_rows, prior_points=PROFILE_PRIOR_SERVICE_POINTS)
        profiles = build_live_profiles(profile_year, current_profiles)
        tour_rate = tour_average_ace_rate(profile_rows)
        raw_events, _, _, _ = event_raw_scores(profile_rows, profiles, tour_rate=tour_rate)
        if len(raw_events) < 5 and profile_year == current_year:
            profile_year = current_year - 1
            excluded_live_event_ids = []
            excluded_live_event_matches = 0
            profile_rows = valid_rows(load(profile_year))
            current_profiles = player_rates(profile_rows, prior_points=PROFILE_PRIOR_SERVICE_POINTS)
            profiles = build_live_profiles(profile_year, current_profiles)
            tour_rate = tour_average_ace_rate(profile_rows)
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
            "withoutServeRate": sum(p["serveRate"] is None for p in live_profile_rows),
            "withoutConcedeRate": sum(p["concedeRate"] is None for p in live_profile_rows),
        }
        if tour_rate is None or not 0.0 < tour_rate < 1.0:
            raise RuntimeError(f"cannot build calibrated live baseline for {current_year}: invalid tour-average ace rate")
        baseline = {
            "season": current_year,
            "profileSeason": profile_year,
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
            "method": "leave-one-tournament-out ace rate adjusted with a season-calibrated server/returner odds ratio; qualified clay and hard/grass player rates are shrunk toward their surface tour rates using a 150-service-point prior before the 1/3–2/3 neutral blend; pooled returner fallback is shrunk toward the tour rate; positive expected-ace values are floored at 0.5 and event ratings are season-normalised",
            "source": "Jeff Sackmann ATP archive for historical seasons; TennisMyLife for the current season when available.",
            "season_sources": {str(y): source for y, source in season_sources.items()},
            "source_metadata": {
                str(y): SOURCE_METADATA_BY_YEAR[y] for y in SOURCE_METADATA_BY_YEAR
            },
            "seasons": {str(y): {t: {k: round(v, 3) if isinstance(v, float) else v
                                     for k, v in d.items()} for t, d in ratings.items()}
                        for y, ratings in history.items()},
        }
        json.dump(payload, open(out_path, "w"), indent=1)
        print("wrote", out_path)


if __name__ == "__main__":
    main()
