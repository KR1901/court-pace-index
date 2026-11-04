#!/usr/bin/env python3
"""Refreshes data/live.json - the events in progress and, when match stats exist, their rating.

Contract
--------
data/live.json carries an `events` array. An event appears there while it is being played.
It gains `speed`/`ace`/`matches` only once the configured match source has enough completed
matches with ace counts to compute the same odds-ratio rating the historical series uses.

The script never invents a figure. If the source has no matches for an event, the event keeps
its status and its rating stays null, and the app says so.

Usage: python3 tools/live_update.py [--source archive|none]
"""
import csv, io, json, os, sys, urllib.request
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIVE = os.path.join(ROOT, "data", "live.json")
ARCHIVE = "https://raw.githubusercontent.com/Aneeshers/tennis-sackmann-archive/main/atp/atp_matches_{season}.csv"

# Minimum completed matches with serve stats before a live rating is worth showing.
MIN_MATCHES = 20

# Tournament name as it appears in the archive -> our id.
NAMES = {
    "paris masters": "paris",
    "tour finals": "atpfinals",
    "atp finals": "atpfinals",
    "shanghai masters": "shanghai",
    "vienna open": "vienna",
    "swiss indoors basel": "basel",
    "stockholm open": "stockholm",
    "metz": "metz",
    "athens": "athens",
}


def fetch(url, timeout=90):
    req = urllib.request.Request(url, headers={"User-Agent": "court-pace-index/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.read()


def season_matches(season):
    try:
        raw = fetch(ARCHIVE.format(season=season))
    except Exception as exc:  # noqa: BLE001 - a missing season is normal, not fatal
        print(f"archive unavailable for {season}: {exc}")
        return []
    return list(csv.DictReader(io.StringIO(raw.decode("utf-8", "replace"))))


def num(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def rating_for(matches, tournament_id):
    """Odds-ratio ace rating, the same construction as tools/compute_speed.py."""
    rows = [m for m in matches
            if NAMES.get((m.get("tourney_name") or "").lower()) == tournament_id
            and num(m.get("w_ace")) is not None and num(m.get("w_svpt")) is not None
            and num(m.get("l_ace")) is not None and num(m.get("l_svpt")) is not None]
    if len(rows) < MIN_MATCHES:
        return None, None, len(rows)

    # Player-season ace rates and ace-against rates, as the historical pipeline uses them.
    served, aced, against, faced = {}, {}, {}, {}
    for m in rows:
        for who, opp, ace_key, svpt_key in (("w", "l", "w_ace", "w_svpt"), ("l", "w", "l_ace", "l_svpt")):
            player, opponent = m.get(f"{who}_id"), m.get(f"{opp}_id")
            aces, points = num(m.get(ace_key)), num(m.get(svpt_key))
            if not player or not opponent or not points:
                continue
            served[player] = served.get(player, 0) + points
            aced[player] = aced.get(player, 0) + (aces or 0)
            faced[opponent] = faced.get(opponent, 0) + points
            against[opponent] = against.get(opponent, 0) + (aces or 0)

    def rate(num_map, den_map, key):
        den = den_map.get(key, 0)
        return (num_map.get(key, 0) / den) if den else None

    ratios, total_aces, total_points = [], 0, 0
    for m in rows:
        for who, opp, ace_key, svpt_key in (("w", "l", "w_ace", "w_svpt"), ("l", "w", "l_ace", "l_svpt")):
            server, returner = m.get(f"{who}_id"), m.get(f"{opp}_id")
            aces, points = num(m.get(ace_key)) or 0.0, num(m.get(svpt_key)) or 0.0
            if not server or not returner or points <= 0:
                continue
            p_serve = rate(aced, served, server)
            p_concede = rate(against, faced, returner)
            if p_serve is None or p_concede is None:
                continue
            denominator = p_serve * p_concede + (1 - p_serve) * (1 - p_concede)
            if denominator <= 0:
                continue
            expected = points * (p_serve * p_concede) / denominator
            if expected <= 0:
                continue
            ratios.append(aces / expected)
            total_aces += aces
            total_points += points

    if not ratios:
        return None, None, len(rows)
    # Normalised so a tour-average event of the season reads 1.00; the season mean is 1.0 by
    # construction in the historical series, so the mean ratio is used directly.
    speed = round(sum(ratios) / len(ratios), 2)
    ace = round(100.0 * total_aces / total_points, 1) if total_points else None
    return speed, ace, len(rows)


def main():
    source = "archive"
    if "--source" in sys.argv:
        source = sys.argv[sys.argv.index("--source") + 1]

    payload = json.load(open(LIVE))
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    payload["generated"] = now

    if source == "none":
        print("source disabled; leaving ratings untouched")
        json.dump(payload, open(LIVE, "w"), indent=2)
        return

    by_season = {}
    for event in payload.get("events", []):
        season = event["s"]
        if season not in by_season:
            by_season[season] = season_matches(season)
        speed, ace, count = rating_for(by_season[season], event["t"])
        event["asOf"] = now
        if speed is None:
            # Keep the event visible, leave the rating absent. Never fabricate one.
            event.pop("speed", None)
            event.pop("ace", None)
            event["matches"] = count if count else None
            print(f"{event['t']} {season}: {count} usable matches - below the {MIN_MATCHES} "
                  f"needed, no rating")
        else:
            event["speed"] = speed
            event["ace"] = ace
            event["matches"] = count
            print(f"{event['t']} {season}: rating {speed} from {count} matches")

    json.dump(payload, open(LIVE, "w"), indent=2)
    open(LIVE, "a").write("\n")


if __name__ == "__main__":
    main()
