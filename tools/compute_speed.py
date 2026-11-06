#!/usr/bin/env python3
"""Computes an ace-based surface speed rating per tournament per season.

Reimplements the published method (Tennis Abstract): for every match, compare the aces
actually hit with the aces the same server would have been expected to hit against that same
returner on a neutral surface, then average the ratios across the event and normalise so a
tour-average event for the season reads 1.00.

Data: the public ATP match archive compiled by Jeff Sackmann (CC BY-NC-SA 4.0), fetched from
the public archive mirror. Only tour-level main-draw matches with serve stats are used.

Run:  python3 tools/compute_speed.py            # compute and validate against 2026
      python3 tools/compute_speed.py --write    # also write data/speed_history.json
"""
import csv, json, os, sys, urllib.request
from collections import defaultdict
from datetime import date, datetime, timezone

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
        }
        for p in set(names)
    }


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
        rows = load(year)
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
        current_usable = valid_rows(load(current_year))
        profiles = player_rates(current_usable)
        raw_events, _, _, _ = event_raw_scores(current_usable, profiles)
        if not raw_events:
            raise RuntimeError(f"cannot build live opponent-adjustment baseline for {current_year}: no rated events")
        normalisation = sum(raw_events.values()) / len(raw_events)
        baseline = {
            "season": current_year,
            "normalisation": round(normalisation, 10),
            "generated": datetime.now(timezone.utc).isoformat(),
            "profiles": [
                {
                    "name": profile["name"],
                    "serveRate": round(profile["serve_rate"], 10) if profile["serve_rate"] is not None else None,
                    "concedeRate": round(profile["concede_rate"], 10) if profile["concede_rate"] is not None else None,
                }
                for profile in profiles.values()
                if profile["name"] and (profile["serve_rate"] is not None or profile["concede_rate"] is not None)
            ],
            "ratedEvents": len(raw_events),
        }
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
