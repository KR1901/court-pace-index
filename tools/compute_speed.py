#!/usr/bin/env python3
"""Computes an ace-based surface speed rating per tournament per season.

Reimplements the published method (Tennis Abstract): for every match, compare the aces
actually hit with the aces the same server would have been expected to hit against that same
returner on a neutral surface, then average the ratios across the event and normalise so a
tour-average event for the season reads 1.00.

Data: the public ATP match archive compiled by Jeff Sackmann (CC BY-NC-SA 4.0), fetched from
the public archive mirror. Only tour-level main-draw matches with serve stats are used.

Run:  python3 tools/compute_speed.py            # compute and validate against 2026
      python3 tools/compute_speed.py --write    # write speed history + live player baseline
"""
import csv, json, os, sys, urllib.request
from collections import defaultdict
from datetime import date, datetime, timezone
from urllib.error import HTTPError

CACHE = os.path.join(os.path.dirname(__file__), "..", ".cache", "atp")
MIRROR = "https://raw.githubusercontent.com/Aneeshers/tennis-sackmann-archive/main/atp/atp_matches_{}.csv"
YEARS = range(2012, date.today().year + 1)
LEVELS = {"G", "M", "A", "F"}          # Slams, Masters, ATP 250/500, Finals
MIN_MATCHES_PER_SURFACE = 3

# CSV event name -> tournament id used by the app
NAMES = {
    "Australian Open": "ausopen", "Roland Garros": "rolandgarros", "Wimbledon": "wimbledon",
    "Us Open": "usopen", "US Open": "usopen",
    "Indian Wells Masters": "indianwells", "Miami Masters": "miami",
    "Monte Carlo Masters": "montecarlo", "Madrid Masters": "madrid",
    "Rome Masters": "rome", "Canada Masters": "canada",
    "Cincinnati Masters": "cincinnati", "Shanghai Masters": "shanghai",
    "Paris Masters": "paris", "Tour Finals": "atpfinals",
    "Brisbane": "brisbane", "Hong Kong": "hongkong", "Auckland": "auckland",
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


def load(year):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, f"atp_matches_{year}.csv")
    if not os.path.exists(path):
        with urllib.request.urlopen(MIRROR.format(year), timeout=90) as r:
            open(path, "wb").write(r.read())
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def valid_rows(rows):
    """Tour-level main-draw rows with both players' aces and service-point totals."""
    usable = []
    for r in rows:
        if r["tourney_level"] not in LEVELS:
            continue
        w_sv, l_sv = num(r["w_svpt"]), num(r["l_svpt"])
        w_ac, l_ac = num(r["w_ace"]), num(r["l_ace"])
        if None in (w_sv, l_sv, w_ac, l_ac) or w_sv <= 0 or l_sv <= 0:
            continue
        if not r["winner_id"] or not r["loser_id"]:
            continue
        usable.append(r)
    return usable


def player_rates(usable):
    """
    Return the historical method's season-level player profiles.

    serve_rate: clay ace rate blended 1/3 with hard/grass ace rate blended 2/3,
    requiring at least three matches in each available surface bucket.
    concede_rate: aces conceded while returning / opponents' service points,
    pooled across surfaces.
    """
    clay_ace = defaultdict(float); clay_sv = defaultdict(float); clay_n = defaultdict(int)
    hg_ace = defaultdict(float); hg_sv = defaultdict(float); hg_n = defaultdict(int)
    ret_ace = defaultdict(float); ret_pts = defaultdict(float)
    names = {}

    for r in usable:
        w_sv, l_sv = num(r["w_svpt"]), num(r["l_svpt"])
        w_ac, l_ac = num(r["w_ace"]), num(r["l_ace"])
        wid, lid = r["winner_id"], r["loser_id"]
        names[wid] = r["winner_name"]
        names[lid] = r["loser_name"]
        surface = (r["surface"] or "").strip()

        if surface == "Clay":
            clay_ace[wid] += w_ac; clay_sv[wid] += w_sv; clay_n[wid] += 1
            clay_ace[lid] += l_ac; clay_sv[lid] += l_sv; clay_n[lid] += 1
        elif surface in ("Hard", "Grass"):
            hg_ace[wid] += w_ac; hg_sv[wid] += w_sv; hg_n[wid] += 1
            hg_ace[lid] += l_ac; hg_sv[lid] += l_sv; hg_n[lid] += 1

        # The winner is returning against the loser's serve, and vice versa.
        ret_ace[wid] += l_ac; ret_pts[wid] += l_sv
        ret_ace[lid] += w_ac; ret_pts[lid] += w_sv

    serve_rate = {}
    serve_samples = {}
    for p in set(clay_sv) | set(hg_sv):
        c = clay_ace[p] / clay_sv[p] if clay_sv[p] > 0 else None
        h = hg_ace[p] / hg_sv[p] if hg_sv[p] > 0 else None
        if c is not None and h is not None and clay_n[p] >= MIN_MATCHES_PER_SURFACE and hg_n[p] >= MIN_MATCHES_PER_SURFACE:
            serve_rate[p] = (1 / 3) * c + (2 / 3) * h
        elif c is not None and h is None and clay_n[p] >= MIN_MATCHES_PER_SURFACE:
            serve_rate[p] = c
        elif h is not None and c is None and hg_n[p] >= MIN_MATCHES_PER_SURFACE:
            serve_rate[p] = h
        if p in serve_rate:
            serve_samples[p] = {
                "clayMatches": clay_n[p],
                "hardGrassMatches": hg_n[p],
            }

    concede_rate = {p: ret_ace[p] / ret_pts[p] for p in ret_pts if ret_pts[p] > 0}
    return {
        p: {
            "id": p,
            "name": names.get(p, ""),
            "serve_rate": serve_rate.get(p),
            "concede_rate": concede_rate.get(p),
            "serve_matches": sum(serve_samples.get(p, {}).values()),
            "clay_matches": clay_n[p],
            "hard_grass_matches": hg_n[p],
            "return_points": ret_pts.get(p, 0.0),
            # Retain the raw surface totals so low-sample live-only estimates can be
            # shrunk toward the season surface average without changing historical ratings.
            "clay_aces": clay_ace[p],
            "clay_service_points": clay_sv[p],
            "hard_grass_aces": hg_ace[p],
            "hard_grass_service_points": hg_sv[p],
        }
        for p in set(names)
    }


# A low-sample profile uses a prior equal to roughly three tour matches. This is only
# used when no qualified player-specific rate is available in the baseline season or
# any of the preceding three seasons.
LIVE_PROFILE_FALLBACK_SEASONS = 10
LIVE_PROFILE_PRIOR_SERVICE_POINTS = 300.0


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

    Profiles are the union of players seen in the baseline season and the preceding three
    ATP seasons. A player's rate comes from the most recent season with a qualified profile.
    If no season meets the historical three-match surface threshold, a low-sample estimate
    is shrunk toward that season's surface average using a 300-service-point prior.

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
            profile["clay_service_points"] + profile["hard_grass_service_points"]
        )
        profile["concede_rate_season"] = (
            profile_year if profile["concede_rate"] is not None else None
        )

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
                    previous["clay_service_points"] + previous["hard_grass_service_points"]
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
                profile["serve_rate_sample_points"] = (
                    previous["clay_service_points"] + previous["hard_grass_service_points"]
                )
            if profile["concede_rate"] is None and previous["concede_rate"] is not None:
                profile["concede_rate"] = previous["concede_rate"]
                profile["concede_rate_season"] = historical_year

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


def event_raw_scores(usable, profiles, exclude_event_ids=None):
    """
    Apply the historical match-level expected-aces formula to each event.

    Returns raw event means before the season-wide normalisation. The caller can
    exclude an event (e.g. a tournament still in progress) without changing the
    player-profile calculation.
    """
    excluded = exclude_event_ids or set()
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
            denom = ps * qr + (1 - ps) * (1 - qr)
            if denom <= 0:
                continue
            expected = ((ps * qr) / denom) * sv
            if expected <= 0.5:
                continue
            per_event[tid].append(ac / expected)

    raw = {t: sum(v) / len(v) for t, v in per_event.items() if len(v) >= 20}
    return raw, event_aces, event_sv, event_matches


def season_ratings(year, rows):
    """Returns {tournament_id: {'speed': r, 'ace': pct, 'matches': n}} for one season."""
    usable = valid_rows(rows)
    profiles = player_rates(usable)
    raw, event_aces, event_sv, event_matches = event_raw_scores(usable, profiles)
    if not raw:
        return {}
    season_mean = sum(raw.values()) / len(raw)
    return {
        tid: {
            "speed": value / season_mean,
            "ace": 100.0 * event_aces[tid] / event_sv[tid],
            "matches": event_matches[tid],
        }
        for tid, value in raw.items()
    }


def main():
    published_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main",
                                  "assets", "pace_dataset.json")
    published = json.load(open(published_path))
    published_speed = {r["t"]: r["speed"] for r in published["readings"]
                       if r.get("speed") is not None and r["s"] == 2026}

    history = {}
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
        print(f"{year}: {len(rows):5d} matches, {len(ratings):3d} events rated")

    ours = history.get(2026, {})
    common = sorted(set(ours) & set(published_speed))
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
        # Export the same season-wide player profiles and normaliser used by historical Speed.
        current_year = date.today().year
        # Use the current season once it has enough rated events; at season start, use the
        # previous completed season's player profiles and normaliser rather than publishing
        # an unstable baseline or reverting to a different live formula.
        profile_year = current_year
        try:
            profile_rows = valid_rows(load(profile_year))
        except HTTPError:
            profile_rows = []
        profiles = player_rates(profile_rows)
        raw_events, _, _, _ = event_raw_scores(profile_rows, profiles)
        if len(raw_events) < 5:
            profile_year = current_year - 1
            profile_rows = valid_rows(load(profile_year))
            profiles = player_rates(profile_rows)
            raw_events, _, _, _ = event_raw_scores(profile_rows, profiles)
        if not raw_events:
            raise RuntimeError(f"cannot build live opponent-adjustment baseline for {current_year}: no rated events in current or previous season")
        normalisation = sum(raw_events.values()) / len(raw_events)

        # Expand only the live baseline. Historical season_ratings() continues to use
        # its original qualified profiles and therefore keeps all published rankings intact.
        live_profiles = build_live_profiles(profile_year, profiles)
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
            }
            for profile in live_profiles.values()
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
        baseline = {
            "season": current_year,
            "profileSeason": profile_year,
            "normalisation": normalisation,
            "generated": datetime.now(timezone.utc).isoformat(),
            "profiles": live_profile_rows,
            "profileCoverage": {
                "playerProfiles": len(live_profile_rows),
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
            "generated": "2026-11-04",
            "method": "ace rate adjusted for the servers and returners in each match, "
                      "normalised so a tour-average event of the season reads 1.00",
            "source": "ATP match archive compiled by Jeff Sackmann (CC BY-NC-SA 4.0)",
            "seasons": {str(y): {t: {k: round(v, 3) if isinstance(v, float) else v
                                     for k, v in d.items()} for t, d in ratings.items()}
                        for y, ratings in history.items()},
        }
        json.dump(payload, open(out_path, "w"), indent=1)
        print("wrote", out_path)


if __name__ == "__main__":
    main()
