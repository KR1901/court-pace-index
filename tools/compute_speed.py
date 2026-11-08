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
EVENT_CI_BOOTSTRAP_ITERATIONS = 300
PROFILE_PRIOR_SERVICE_POINTS = 150.0
TML_CACHE_MAX_AGE_DAYS = 7
LIVE_EVENT_FEED_MAX_AGE_DAYS = 1

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
    names = {}
    tour_aces = tour_points = clay_tour_aces = clay_tour_points = hg_tour_aces = hg_tour_points = 0.0

    for r in usable:
        w_sv, l_sv = num(r["w_svpt"]), num(r["l_svpt"])
        w_ac, l_ac = num(r["w_ace"]), num(r["l_ace"])
        wid, lid = r["winner_id"], r["loser_id"]
        names[wid] = r.get("winner_name") or ""
        names[lid] = r.get("loser_name") or ""
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
            "clay_aces": clay_ace[p], "clay_service_points": clay_sv[p],
            "hard_grass_aces": hg_ace[p], "hard_grass_service_points": hg_sv[p],
        }
        for p in set(names)
    }


# A low-sample profile uses a prior equal to roughly three tour matches. This is only
# used when no qualified player-specific rate is available in the baseline season or
# any of the preceding ten seasons.
LIVE_PROFILE_FALLBACK_SEASONS = 10
LIVE_PROFILE_PRIOR_SERVICE_POINTS = 150.0
MIN_EXPECTED_ACES = 0.0
EXPECTED_ACES_FLOOR = 0.5


def _surface_priors(profiles):
    clay_aces = sum(p["clay_aces"] for p in profiles.values())
    clay_points = sum(p["clay_service_points"] for p in profiles.values())
    hard_grass_aces = sum(p["hard_grass_aces"] for p in profiles.values())
    hard_grass_points = sum(p["hard_grass_service_points"] for p in profiles.values())
    return {
        "clay": clay_aces / clay_points if clay_points > 0 else None,
        "hard_grass": hard_grass_aces / hard_grass_points if hard_grass_points > 0 else None,
    }


def _shrunk_serve_rate(profile, priors):
    clay_points = profile["clay_service_points"]
    hard_grass_points = profile["hard_grass_service_points"]
    clay_estimate = None
    hard_grass_estimate = None

    if clay_points > 0 and priors["clay"] is not None:
        clay_estimate = (
            profile["clay_aces"] + LIVE_PROFILE_PRIOR_SERVICE_POINTS * priors["clay"]
        ) / (clay_points + LIVE_PROFILE_PRIOR_SERVICE_POINTS)
    if hard_grass_points > 0 and priors["hard_grass"] is not None:
        hard_grass_estimate = (
            profile["hard_grass_aces"] + LIVE_PROFILE_PRIOR_SERVICE_POINTS * priors["hard_grass"]
        ) / (hard_grass_points + LIVE_PROFILE_PRIOR_SERVICE_POINTS)

    if clay_estimate is not None and hard_grass_estimate is not None:
        return (1 / 3) * clay_estimate + (2 / 3) * hard_grass_estimate
    return clay_estimate if clay_estimate is not None else hard_grass_estimate


def build_live_profiles(profile_year, current_profiles):
    """
    Build a broad but auditable live player baseline.

    Profiles are the union of players seen in the baseline season and the preceding ten
    ATP seasons. Serve rates prefer the most recent qualified profile, then a shrunk estimate.
    Returner rates prefer surface-qualified concession profiles over pooled fallbacks, even
    when the qualified profile comes from a prior season.

    This changes only the *live baseline's player coverage*. Historical season_ratings()
    continues to use player_rates() unchanged, and the expected-aces formula plus season
    normaliser are unchanged. Each profile records the year, source and sample behind its rate.
    """
    live = {player_id: dict(profile) for player_id, profile in current_profiles.items()}
    source_profile_by_id = {player_id: dict(profile) for player_id, profile in current_profiles.items()}
    source_year_by_id = {player_id: profile_year for player_id in current_profiles}
    season_profiles = {profile_year: current_profiles}

    for player_id, profile in live.items():
        profile["serve_rate_season"] = profile_year if profile["serve_rate"] is not None else None
        profile["serve_rate_source"] = (
            "current-season-qualified" if profile["serve_rate"] is not None else None
        )
        profile["serve_rate_sample_matches"] = profile["serve_matches"]
        profile["serve_rate_sample_points"] = (
            profile.get("serve_service_points")
            if profile["serve_rate"] is not None and profile.get("serve_service_points") is not None
            else profile["clay_service_points"] + profile["hard_grass_service_points"]
        )
        profile["concede_rate_season"] = (
            profile_year if profile["concede_rate"] is not None else None
        )
        profile["concede_rate_source"] = profile.get("concede_rate_source")
        profile["concede_matches"] = profile.get("concede_matches", 0)
        profile["concede_service_points"] = profile.get("concede_service_points", 0.0)

    # Union the recent seasons by stable Sackmann player ID. The newest available profile
    # supplies the display name and, when needed, the raw observations for shrinkage.
    for historical_year in range(
        profile_year - 1,
        max(2011, profile_year - LIVE_PROFILE_FALLBACK_SEASONS - 1),
        -1,
    ):
        historical = player_rates(valid_rows(load(historical_year)))
        season_profiles[historical_year] = historical

        for player_id, previous in historical.items():
            if player_id not in live:
                live[player_id] = dict(previous)
                source_profile_by_id[player_id] = dict(previous)
                source_year_by_id[player_id] = historical_year
                live[player_id]["serve_rate_season"] = (
                    historical_year if previous["serve_rate"] is not None else None
                )
                live[player_id]["serve_rate_source"] = (
                    "prior-season-qualified" if previous["serve_rate"] is not None else None
                )
                live[player_id]["serve_rate_sample_matches"] = (
                    previous["serve_matches"] if previous["serve_rate"] is not None else 0
                )
                live[player_id]["serve_rate_sample_points"] = (
                    previous.get("serve_service_points",
                                 previous["clay_service_points"] + previous["hard_grass_service_points"])
                    if previous["serve_rate"] is not None else 0.0
                )
                live[player_id]["concede_rate_season"] = (
                    historical_year if previous["concede_rate"] is not None else None
                )

            profile = live[player_id]
            # Fill only missing values: a qualified newer-season estimate always wins.
            if profile["serve_rate"] is None and previous["serve_rate"] is not None:
                profile["serve_rate"] = previous["serve_rate"]
                profile["serve_rate_season"] = historical_year
                profile["serve_rate_source"] = "prior-season-qualified"
                profile["serve_rate_sample_matches"] = previous["serve_matches"]
                profile["serve_rate_sample_points"] = previous.get(
                    "serve_service_points",
                    previous["clay_service_points"] + previous["hard_grass_service_points"],
                )
                profile["serve_rate_prior_points"] = previous.get("serve_rate_prior_points", 0.0)
            previous_concede_source = previous.get("concede_rate_source")
            current_concede_source = profile.get("concede_rate_source")
            prefer_previous_concede = (
                profile["concede_rate"] is None
                or (
                    current_concede_source != "surface-qualified"
                    and previous_concede_source == "surface-qualified"
                )
            )
            if prefer_previous_concede and previous["concede_rate"] is not None:
                profile["concede_rate"] = previous["concede_rate"]
                profile["concede_rate_season"] = historical_year
                profile["concede_rate_source"] = previous_concede_source
                profile["concede_matches"] = previous.get("concede_matches", 0)
                profile["concede_service_points"] = previous.get("concede_service_points", 0.0)
                profile["concede_rate_prior_points"] = previous.get("concede_rate_prior_points", 0.0)

    # Players without any qualified profile still get a player-specific, explicitly
    # low-sample estimate when the archive contains their own service-point observations.
    priors_by_year = {
        year: _surface_priors(profiles)
        for year, profiles in season_profiles.items()
    }
    for player_id, profile in live.items():
        if profile["serve_rate"] is not None:
            continue

        raw_profile = source_profile_by_id[player_id]
        raw_year = source_year_by_id[player_id]
        estimate = _shrunk_serve_rate(raw_profile, priors_by_year[raw_year])
        if estimate is None:
            continue

        profile["serve_rate"] = estimate
        profile["serve_rate_season"] = raw_year
        profile["serve_rate_source"] = (
            "current-season-shrunk" if raw_year == profile_year else "prior-season-shrunk"
        )
        profile["serve_rate_sample_matches"] = (
            raw_profile["clay_matches"] + raw_profile["hard_grass_matches"]
        )
        profile["serve_rate_sample_points"] = (
            raw_profile["clay_service_points"] + raw_profile["hard_grass_service_points"]
        )
        profile["serve_rate_prior_points"] = LIVE_PROFILE_PRIOR_SERVICE_POINTS

    return live


def _normalise_profile_name(name):
    return "".join(
        char for char in unicodedata.normalize("NFKD", name).casefold()
        if char.isalnum()
    )


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
    """LOEO tournament ratings with conditional match-cluster intervals and provisional low-sample values."""
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
        profiles = player_rates(training_rows, prior_points=PROFILE_PRIOR_SERVICE_POINTS)
        tour_rate = tour_average_ace_rate(training_rows)
        if tour_rate is None or not 0.0 < tour_rate < 1.0:
            continue
        clusters = event_match_ratio_clusters(event_rows, profiles, tour_rate)
        ratios = [ratio for cluster in clusters for ratio in cluster]
        sides = len(ratios)
        if sides < PROVISIONAL_EVENT_SERVER_SIDES:
            continue
        aces = sum(num(row["w_ace"]) + num(row["l_ace"]) for row in event_rows)
        points = sum(num(row["w_svpt"]) + num(row["l_svpt"]) for row in event_rows)
        details[tid] = {
            "raw": sum(ratios) / sides, "clusters": clusters, "sides": sides,
            "matches": len(event_rows), "ace": 100.0 * aces / points if points else None,
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
        common = {"ace": round(item["ace"], 1) if item["ace"] is not None else None,
                  "matches": item["matches"]}
        if item["sides"] >= MIN_EVENT_SERVER_SIDES:
            output[tid] = {
                **common, "speed": item["raw"] / mean_raw, "adjustedServerSides": item["sides"],
                "speedCiLow": ci.get("low"), "speedCiHigh": ci.get("high"),
                "speedCiMethod": ci.get("method"),
            }
        else:
            output[tid] = {
                **common, "provisionalSpeed": item["raw"] / mean_raw,
                "provisionalServerSides": item["sides"], "provisionalMatches": item["matches"],
                "provisionalCiLow": ci.get("low"), "provisionalCiHigh": ci.get("high"),
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
            all_profile_rows = valid_rows(load(profile_year))
            excluded_live_event_ids = live_manifest["eventIds"]
            profile_rows = exclude_live_event_rows(all_profile_rows, excluded_live_event_ids)
            excluded_live_event_matches = len(all_profile_rows) - len(profile_rows)
        else:
            profile_year = current_year - 1
            print(f"live manifest stale; using completed season {profile_year}: {live_manifest['reason']}")
            profile_rows = valid_rows(load(profile_year))
        profiles = player_rates(profile_rows, prior_points=PROFILE_PRIOR_SERVICE_POINTS)
        tour_rate = tour_average_ace_rate(profile_rows)
        raw_events, _, _, _ = event_raw_scores(profile_rows, profiles, tour_rate=tour_rate)
        if len(raw_events) < 5 and profile_year == current_year:
            profile_year = current_year - 1
            excluded_live_event_ids = []
            excluded_live_event_matches = 0
            profile_rows = valid_rows(load(profile_year))
            profiles = player_rates(profile_rows, prior_points=PROFILE_PRIOR_SERVICE_POINTS)
            tour_rate = tour_average_ace_rate(profile_rows)
            raw_events, _, _, _ = event_raw_scores(profile_rows, profiles, tour_rate=tour_rate)
        if not raw_events:
            raise RuntimeError(f"cannot build live opponent-adjustment baseline for {current_year}: no rated events in current or previous season")
        normalisation = sum(raw_events.values()) / len(raw_events)

        # Expand only the live baseline. Historical season_ratings() continues to use
        # its original qualified profiles and therefore keeps all published rankings intact.
        live_profiles = build_live_profiles(profile_year, profiles)
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
            "currentSeasonQualified": sum(
                p["serveRateSource"] == "current-season-qualified" for p in live_profile_rows
            ),
            "priorSeasonQualified": sum(
                p["serveRateSource"] == "prior-season-qualified" for p in live_profile_rows
            ),
            "currentSeasonShrunk": sum(
                p["serveRateSource"] == "current-season-shrunk" for p in live_profile_rows
            ),
            "priorSeasonShrunk": sum(
                p["serveRateSource"] == "prior-season-shrunk" for p in live_profile_rows
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
            f"{source_counts['currentSeasonQualified']} current-season qualified, "
            f"{source_counts['priorSeasonQualified']} prior-season qualified, "
            f"{source_counts['currentSeasonShrunk']} low-sample shrunk, "
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
