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

CACHE = os.path.join(os.path.dirname(__file__), "..", ".cache", "atp")
MIRROR = "https://raw.githubusercontent.com/Aneeshers/tennis-sackmann-archive/main/atp/atp_matches_{}.csv"
YEARS = range(2012, 2027)
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


def season_ratings(year, rows):
    """Returns {tournament_id: {'speed': r, 'ace': pct, 'matches': n}} for one season."""
    clay_ace = defaultdict(float); clay_sv = defaultdict(float); clay_n = defaultdict(int)
    hg_ace = defaultdict(float); hg_sv = defaultdict(float); hg_n = defaultdict(int)
    ret_ace = defaultdict(float); ret_pts = defaultdict(float)

    usable = []
    for r in rows:
        if r["tourney_level"] not in LEVELS:
            continue
        w_sv, l_sv = num(r["w_svpt"]), num(r["l_svpt"])
        w_ac, l_ac = num(r["w_ace"]), num(r["l_ace"])
        if None in (w_sv, l_sv, w_ac, l_ac) or w_sv <= 0 or l_sv <= 0:
            continue
        surface = (r["surface"] or "").strip()
        wid, lid = r["winner_id"], r["loser_id"]
        if not wid or not lid:
            continue
        usable.append(r)

        if surface == "Clay":
            clay_ace[wid] += w_ac; clay_sv[wid] += w_sv; clay_n[wid] += 1
            clay_ace[lid] += l_ac; clay_sv[lid] += l_sv; clay_n[lid] += 1
        elif surface in ("Hard", "Grass"):
            hg_ace[wid] += w_ac; hg_sv[wid] += w_sv; hg_n[wid] += 1
            hg_ace[lid] += l_ac; hg_sv[lid] += l_sv; hg_n[lid] += 1

        ret_ace[wid] += l_ac; ret_pts[wid] += l_sv
        ret_ace[lid] += w_ac; ret_pts[lid] += w_sv

    serve_rate = {}
    for p in set(clay_sv) | set(hg_sv):
        c = clay_ace[p] / clay_sv[p] if clay_sv[p] > 0 else None
        h = hg_ace[p] / hg_sv[p] if hg_sv[p] > 0 else None
        if c is not None and h is not None and clay_n[p] >= MIN_MATCHES_PER_SURFACE and hg_n[p] >= MIN_MATCHES_PER_SURFACE:
            serve_rate[p] = (1 / 3) * c + (2 / 3) * h
        elif c is not None and h is None and clay_n[p] >= MIN_MATCHES_PER_SURFACE:
            serve_rate[p] = c
        elif h is not None and c is None and hg_n[p] >= MIN_MATCHES_PER_SURFACE:
            serve_rate[p] = h
    concede_rate = {p: ret_ace[p] / ret_pts[p] for p in ret_pts if ret_pts[p] > 0}

    per_event = defaultdict(list)
    event_aces = defaultdict(float); event_sv = defaultdict(float)
    event_matches = defaultdict(int)

    for r in usable:
        tid = NAMES.get((r["tourney_name"] or "").strip())
        if tid is None:
            continue
        w_sv, l_sv = num(r["w_svpt"]), num(r["l_svpt"])
        w_ac, l_ac = num(r["w_ace"]), num(r["l_ace"])
        wid, lid = r["winner_id"], r["loser_id"]
        event_aces[tid] += w_ac + l_ac
        event_sv[tid] += w_sv + l_sv
        event_matches[tid] += 1

        for server, returner, sv, ac in ((wid, lid, w_sv, w_ac), (lid, wid, l_sv, l_ac)):
            ps, qr = serve_rate.get(server), concede_rate.get(returner)
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
