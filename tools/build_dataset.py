#!/usr/bin/env python3
"""Builds app/src/main/assets/pace_dataset.json from the two public sources.

CPI  : courtspeed.com, Court Pace Index database 2012-2026 (Grand Slams, Masters 1000,
       ATP Finals). Values in CPI points. "COVID" and missing seasons are simply absent.
SPEED: Tennis Abstract, ATP Surface Speed Ratings, 2026 season, updated 2026-11-02.
       Ace rate per event plus a field-adjusted speed rating centred on 1.00.
"""
import json, os

# id, name, city, country, surface, indoor, level, altitude
TOURNAMENTS = [
    ("ausopen",    "Australian Open",      "Melbourne",    "AUS", "HARD",  False, "GS",       None),
    ("rolandgarros","Roland Garros",       "Paris",        "FRA", "CLAY",  False, "GS",       None),
    ("wimbledon",  "Wimbledon",            "London",       "GBR", "GRASS", False, "GS",       None),
    ("usopen",     "US Open",              "New York",     "USA", "HARD",  False, "GS",       None),
    ("indianwells","Indian Wells Masters", "Indian Wells", "USA", "HARD",  False, "M1000",    None),
    ("miami",      "Miami Masters",        "Miami",        "USA", "HARD",  False, "M1000",    None),
    ("montecarlo", "Monte Carlo Masters",  "Monte Carlo",  "MCO", "CLAY",  False, "M1000",    None),
    ("madrid",     "Madrid Masters",       "Madrid",       "ESP", "CLAY",  False, "M1000",    667),
    ("rome",       "Rome Masters",         "Rome",         "ITA", "CLAY",  False, "M1000",    None),
    ("canada",     "Canadian Open",        "Toronto",      "CAN", "HARD",  False, "M1000",    None),
    ("cincinnati", "Cincinnati Masters",   "Cincinnati",   "USA", "HARD",  False, "M1000",    None),
    ("shanghai",   "Shanghai Masters",     "Shanghai",     "CHN", "HARD",  False, "M1000",    None),
    ("paris",      "Paris Masters",        "Paris",        "FRA", "HARD",  True,  "M1000",    None),
    ("atpfinals",  "ATP Finals",           "Turin",        "ITA", "HARD",  True,  "FINALS",   None),
    ("nextgen",    "Next Gen Finals",      "Jeddah",       "SAU", "HARD",  True,  "SPECIAL",  None),
    ("unitedcup",  "United Cup",           "Perth",        "AUS", "HARD",  False, "SPECIAL",  None),
    ("lavercup",   "Laver Cup",            "San Francisco","USA", "HARD",  True,  "SPECIAL",  None),
    ("brisbane",   "Brisbane International","Brisbane",    "AUS", "HARD",  False, "ATP250",   None),
    ("hongkong",   "Hong Kong Open",       "Hong Kong",    "HKG", "HARD",  False, "ATP250",   None),
    ("auckland",   "Auckland Classic",     "Auckland",     "NZL", "HARD",  False, "ATP250",   None),
    ("adelaide",   "Adelaide International","Adelaide",    "AUS", "HARD",  False, "ATP250",   None),
    ("montpellier","Open Occitanie",       "Montpellier",  "FRA", "HARD",  True,  "ATP250",   None),
    ("dallas",     "Dallas Open",          "Dallas",       "USA", "HARD",  True,  "ATP500",   None),
    ("rotterdam",  "Rotterdam Open",       "Rotterdam",    "NED", "HARD",  True,  "ATP500",   None),
    ("delraybeach","Delray Beach Open",    "Delray Beach", "USA", "HARD",  False, "ATP250",   None),
    ("buenosaires","Argentina Open",       "Buenos Aires", "ARG", "CLAY",  False, "ATP250",   None),
    ("rio",        "Rio Open",             "Rio de Janeiro","BRA","CLAY",  False, "ATP500",   None),
    ("doha",       "Qatar Open",           "Doha",         "QAT", "HARD",  False, "ATP500",   None),
    ("acapulco",   "Mexican Open",         "Acapulco",     "MEX", "HARD",  False, "ATP500",   None),
    ("dubai",      "Dubai Championships",  "Dubai",        "UAE", "HARD",  False, "ATP500",   None),
    ("santiago",   "Chile Open",           "Santiago",     "CHI", "CLAY",  False, "ATP250",   None),
    ("marrakech",  "Marrakech Open",       "Marrakech",    "MAR", "CLAY",  False, "ATP250",   None),
    ("houston",    "US Clay Court Championship","Houston", "USA", "CLAY",  False, "ATP250",   None),
    ("bucharest",  "Bucharest Open",       "Bucharest",    "ROU", "CLAY",  False, "ATP250",   None),
    ("estoril",    "Estoril Open",         "Estoril",      "POR", "CLAY",  False, "ATP250",   None),
    ("barcelona",  "Barcelona Open",       "Barcelona",    "ESP", "CLAY",  False, "ATP500",   None),
    ("munich",     "Bavarian Open",        "Munich",       "GER", "CLAY",  False, "ATP500",   None),
    ("hamburg",    "Hamburg Open",         "Hamburg",      "GER", "CLAY",  False, "ATP500",   None),
    ("geneva",     "Geneva Open",          "Geneva",       "SUI", "CLAY",  False, "ATP250",   None),
    ("lyon",       "Lyon Open",            "Lyon",         "FRA", "CLAY",  False, "ATP250",   None),
    ("stuttgart",  "Stuttgart Open",       "Stuttgart",    "GER", "GRASS", False, "ATP250",   None),
    ("shertogenbosch","Libema Open",       "'s-Hertogenbosch","NED","GRASS",False, "ATP250",  None),
    ("halle",      "Halle Open",           "Halle",        "GER", "GRASS", False, "ATP500",   None),
    ("queens",     "Queen's Club",         "London",       "GBR", "GRASS", False, "ATP500",   None),
    ("mallorca",   "Mallorca Championships","Mallorca",    "ESP", "GRASS", False, "ATP250",   None),
    ("eastbourne", "Eastbourne International","Eastbourne","GBR", "GRASS", False, "ATP250",   None),
    ("bastad",     "Swedish Open",         "Bastad",       "SWE", "CLAY",  False, "ATP250",   None),
    ("gstaad",     "Swiss Open",           "Gstaad",       "SUI", "CLAY",  False, "ATP250",   1050),
    ("kitzbuhel",  "Austrian Open",        "Kitzbuhel",    "AUT", "CLAY",  False, "ATP250",   762),
    ("umag",       "Croatia Open",         "Umag",         "CRO", "CLAY",  False, "ATP250",   None),
    ("washington", "Washington Open",      "Washington",   "USA", "HARD",  False, "ATP500",   None),
    ("losCabos",   "Los Cabos Open",       "Los Cabos",    "MEX", "HARD",  False, "ATP250",   None),
    ("winstonsalem","Winston-Salem Open",  "Winston-Salem","USA", "HARD",  False, "ATP250",   None),
    ("chengdu",    "Chengdu Open",         "Chengdu",      "CHN", "HARD",  False, "ATP250",   None),
    ("hangzhou",   "Hangzhou Open",        "Hangzhou",     "CHN", "HARD",  False, "ATP250",   None),
    ("beijing",    "China Open",           "Beijing",      "CHN", "HARD",  False, "ATP500",   None),
    ("tokyo",      "Japan Open",           "Tokyo",        "JPN", "HARD",  False, "ATP500",   None),
    ("shanghai",   "Shanghai Masters",     "Shanghai",     "CHN", "HARD",  False, "M1000",    None),
    ("almaty",     "Almaty Open",          "Almaty",       "KAZ", "HARD",  True,  "ATP250",   None),
    ("brussels",   "European Open",        "Brussels",     "BEL", "HARD",  True,  "ATP250",   None),
    ("vienna",     "Vienna Open",          "Vienna",       "AUT", "HARD",  True,  "ATP500",   None),
    ("basel",      "Swiss Indoors",        "Basel",        "SUI", "HARD",  True,  "ATP500",   None),
    ("metz",       "Moselle Open",         "Metz",         "FRA", "HARD",  True,  "ATP250",   None),
    ("athens",     "Athens Open",          "Athens",       "GRE", "HARD",  True,  "ATP250",   None),
]

# CPI readings: (tournament id, season, value). Source: courtspeed.com database.
CPI = [
    ("ausopen", 2017, 42.0), ("ausopen", 2020, 43.0), ("ausopen", 2021, 50.0),
    ("indianwells", 2016, 30.0), ("indianwells", 2017, 27.4), ("indianwells", 2018, 27.9),
    ("indianwells", 2019, 32.1), ("indianwells", 2021, 32.0), ("indianwells", 2023, 35.4),
    ("indianwells", 2024, 36.9), ("indianwells", 2025, 30.9), ("indianwells", 2026, 39.3),
    ("miami", 2013, 31.5), ("miami", 2014, 29.8), ("miami", 2015, 31.2), ("miami", 2016, 33.1),
    ("miami", 2017, 33.8), ("miami", 2018, 30.4), ("miami", 2019, 36.5), ("miami", 2023, 40.6),
    ("miami", 2024, 35.5), ("miami", 2025, 40.7), ("miami", 2026, 39.2),
    ("montecarlo", 2016, 23.7), ("montecarlo", 2017, 24.9), ("montecarlo", 2018, 22.1),
    ("montecarlo", 2019, 30.3), ("montecarlo", 2023, 30.0), ("montecarlo", 2024, 29.1),
    ("montecarlo", 2025, 29.0), ("montecarlo", 2026, 27.1),
    ("madrid", 2016, 22.5), ("madrid", 2017, 20.9), ("madrid", 2018, 21.6), ("madrid", 2019, 27.9),
    ("madrid", 2023, 26.6), ("madrid", 2024, 27.0), ("madrid", 2025, 26.1), ("madrid", 2026, 29.8),
    ("rome", 2016, 24.0), ("rome", 2017, 22.0), ("rome", 2018, 18.9), ("rome", 2023, 28.6),
    ("rome", 2024, 29.3), ("rome", 2025, 28.9), ("rome", 2026, 25.4),
    ("rolandgarros", 2017, 21.0),
    ("wimbledon", 2017, 37.0),
    ("canada", 2016, 35.2), ("canada", 2017, 36.3), ("canada", 2018, 28.8), ("canada", 2019, 42.8),
    ("canada", 2021, 42.4), ("canada", 2022, 39.5), ("canada", 2023, 41.2), ("canada", 2024, 37.8),
    ("canada", 2025, 44.6), ("canada", 2026, 35.4),
    ("cincinnati", 2016, 35.1), ("cincinnati", 2017, 33.6), ("cincinnati", 2018, 31.6),
    ("cincinnati", 2019, 37.4), ("cincinnati", 2021, 43.0), ("cincinnati", 2022, 38.6),
    ("cincinnati", 2023, 33.2), ("cincinnati", 2024, 42.5), ("cincinnati", 2025, 43.0),
    ("cincinnati", 2026, 39.0),
    ("usopen", 2017, 35.7), ("usopen", 2020, 43.0), ("usopen", 2024, 42.8),
    ("shanghai", 2016, 44.1), ("shanghai", 2017, 42.9), ("shanghai", 2018, 40.0),
    ("shanghai", 2019, 40.9), ("shanghai", 2023, 40.1), ("shanghai", 2024, 40.8),
    ("shanghai", 2025, 32.8), ("shanghai", 2026, 43.3),
    ("paris", 2012, 32.2), ("paris", 2013, 31.2), ("paris", 2014, 31.8), ("paris", 2015, 29.9),
    ("paris", 2016, 39.1), ("paris", 2017, 37.5), ("paris", 2018, 34.6), ("paris", 2019, 40.6),
    ("paris", 2021, 37.1), ("paris", 2022, 37.1), ("paris", 2023, 40.4), ("paris", 2024, 45.5),
    ("paris", 2025, 35.1),
    ("atpfinals", 2012, 34.1), ("atpfinals", 2013, 33.9), ("atpfinals", 2014, 34.0),
    ("atpfinals", 2015, 34.6), ("atpfinals", 2016, 42.1), ("atpfinals", 2017, 42.1),
    ("atpfinals", 2018, 40.3), ("atpfinals", 2019, 41.6), ("atpfinals", 2020, 36.7),
    ("atpfinals", 2021, 39.9), ("atpfinals", 2022, 43.2), ("atpfinals", 2023, 43.8),
    ("atpfinals", 2024, 39.9), ("atpfinals", 2025, 40.1),
]

# Tennis Abstract 2026 surface speed: (id, speed, ace%) with the event's start date.
SPEED = [
    ("montpellier", "2026-02-02", 1.49, 19.8), ("dallas", "2026-02-09", 1.45, 16.5),
    ("chengdu", "2026-09-23", 1.44, 13.6), ("atpfinals", "2025-11-09", 1.44, 11.7),
    ("hangzhou", "2026-09-23", 1.36, 14.1), ("athens", "2025-11-02", 1.34, 14.5),
    ("delraybeach", "2026-02-16", 1.31, 13.6), ("metz", "2025-11-02", 1.31, 12.6),
    ("shanghai", "2026-10-07", 1.30, 12.7), ("stuttgart", "2026-06-08", 1.30, 17.7),
    ("dubai", "2026-02-23", 1.29, 15.9), ("nextgen", "2025-12-17", 1.26, 11.6),
    ("lyon", "2026-10-19", 1.21, 12.0), ("basel", "2026-10-26", 1.20, 15.0),
    ("miami", "2026-03-18", 1.19, 12.3), ("halle", "2026-06-15", 1.19, 13.8),
    ("mallorca", "2026-06-21", 1.18, 11.0), ("brisbane", "2026-01-04", 1.17, 14.6),
    ("eastbourne", "2026-06-22", 1.16, 11.9), ("indianwells", "2026-03-04", 1.16, 11.4),
    ("brussels", "2026-10-19", 1.14, 13.5), ("cincinnati", "2026-08-13", 1.13, 11.7),
    ("washington", "2026-07-27", 1.13, 12.5), ("ausopen", "2026-01-19", 1.12, 11.9),
    ("almaty", "2026-10-19", 1.12, 14.6), ("vienna", "2026-10-26", 1.10, 9.7),
    ("acapulco", "2026-02-23", 1.10, 12.2), ("auckland", "2026-01-12", 1.10, 11.8),
    ("wimbledon", "2026-06-29", 1.10, 11.8), ("beijing", "2026-09-30", 1.08, 11.5),
    ("unitedcup", "2026-01-05", 1.07, 14.0), ("hongkong", "2026-01-05", 1.06, 11.4),
    ("winstonsalem", "2026-08-23", 1.05, 10.9), ("adelaide", "2026-01-12", 1.05, 9.8),
    ("queens", "2026-06-15", 1.03, 10.5), ("rio", "2026-02-16", 0.97, 8.2),
    ("usopen", "2026-08-31", 0.95, 10.7), ("shertogenbosch", "2026-06-08", 0.95, 11.0),
    ("paris", "2025-10-27", 0.94, 10.2), ("tokyo", "2026-09-30", 0.93, 9.3),
    ("marrakech", "2026-03-30", 0.93, 8.0), ("gstaad", "2026-07-13", 0.90, 11.2),
    ("doha", "2026-02-16", 0.89, 9.1), ("rotterdam", "2026-02-09", 0.89, 11.1),
    ("canada", "2026-08-02", 0.86, 9.3), ("madrid", "2026-04-22", 0.86, 8.7),
    ("santiago", "2026-02-23", 0.81, 7.7), ("kitzbuhel", "2026-07-20", 0.74, 9.4),
    ("umag", "2026-07-13", 0.70, 5.2), ("munich", "2026-04-13", 0.70, 7.2),
    ("bastad", "2026-07-13", 0.67, 7.5), ("houston", "2026-03-30", 0.67, 9.0),
    ("rome", "2026-05-06", 0.66, 6.8), ("geneva", "2026-05-17", 0.65, 7.0),
    ("montecarlo", "2026-04-05", 0.57, 5.9), ("bucharest", "2026-03-30", 0.55, 4.4),
    ("estoril", "2026-07-20", 0.54, 5.3), ("losCabos", "2026-07-27", 0.53, 5.5),
    ("barcelona", "2026-04-13", 0.53, 5.1), ("buenosaires", "2026-02-09", 0.52, 4.6),
    ("hamburg", "2026-05-17", 0.51, 5.1), ("lavercup", "2026-09-25", 0.44, 4.4),
]

# Historical ace-based ratings computed from the public match archive
# (tools/compute_speed.py). On overlaps, the calibrated value is primary and the original
# Tennis Abstract value is retained as a published comparison. TA-only values remain fallbacks.
COMPUTED = os.path.join(os.path.dirname(__file__), "..", "data", "speed_history.json")

# Events in progress. Carried in the bundled asset so the app shows them without a refresh.
LIVE_FEED = os.path.join(os.path.dirname(__file__), "..", "data", "live.json")

# dedupe tournaments by id, keeping the first definition
seen, tournaments = set(), []
for t in TOURNAMENTS:
    if t[0] in seen:
        continue
    seen.add(t[0])
    tournaments.append({
        "id": t[0], "name": t[1], "city": t[2], "country": t[3],
        "surface": t[4], "indoor": t[5], "level": t[6], "altitudeM": t[7],
    })

known = {t["id"] for t in tournaments}
readings = []

for tid, season, value in CPI:
    assert tid in known, tid
    readings.append({"t": tid, "s": season, "cpi": value, "src": "courtspeed"})

for tid, date, speed, ace in SPEED:
    assert tid in known, tid
    readings.append({"t": tid, "s": int(date[:4]), "speed": speed, "ace": ace,
                     "date": date, "src": "tennisabstract"})

computed_n = 0
published_overlap_n = 0
if os.path.exists(COMPUTED):
    hist = json.load(open(COMPUTED))
    published_keys = {(r["t"], r["s"]) for r in readings
                      if "speed" in r and r["src"] == "tennisabstract"}
    computed_by_key = {
        (tid, int(season)): values
        for season, events in hist["seasons"].items()
        for tid, values in events.items()
        if tid in known
    }

    # Upgrade the primary value on every overlap while preserving the published figure.
    for reading in readings:
        if reading.get("src") != "tennisabstract" or reading.get("speed") is None:
            continue
        values = computed_by_key.get((reading["t"], reading["s"]))
        if values is None:
            continue
        reading["publishedSpeed"] = reading["speed"]
        reading["speed"] = round(values["speed"], 2)
        reading["ace"] = round(values["ace"], 1)
        reading["matches"] = values.get("matches")
        reading["src"] = "computed"
        computed_n += 1
        published_overlap_n += 1

    # Add archive calculations where the published table has no event-season value.
    for season, events in hist["seasons"].items():
        year = int(season)
        for tid, values in events.items():
            if tid not in known or (tid, year) in published_keys:
                continue
            readings.append({
                "t": tid, "s": year,
                "speed": round(values["speed"], 2),
                "ace": round(values["ace"], 1),
                "matches": values.get("matches"),
                "src": "computed",
            })
            computed_n += 1
else:
    print("no computed history found; run tools/compute_speed.py --write first")

# live events: status always, rating only when the feed has one
live_events = []
if os.path.exists(LIVE_FEED):
    feed = json.load(open(LIVE_FEED))
    for event in feed.get("events", []):
        if event.get("t") not in known:
            continue
        live_events.append(event)
        # Never publish an unversioned/legacy pooled-rate live reading as if it used the
        # current opponent-adjusted method. Runtime live ratings are computed on-device.
        if event.get("speed") is not None and event.get("methodVersion") == 2:
            readings.append({
                "t": event["t"], "s": event["s"], "speed": event["speed"],
                "ace": event.get("ace"), "src": "live",
                "live": True, "asOf": event.get("asOf"), "matches": event.get("matches"),
            })


doc = {
    "meta": {
        "generated": (
            json.load(open(os.path.join(os.path.dirname(__file__), "..", "data", "live_speed_baseline.json"))).get("generated", "2026-11-03")
            if os.path.exists(os.path.join(os.path.dirname(__file__), "..", "data", "live_speed_baseline.json"))
            else "2026-11-03"
        ),
        "season": (
            json.load(open(os.path.join(os.path.dirname(__file__), "..", "data", "live_speed_baseline.json"))).get("season", 2026)
            if os.path.exists(os.path.join(os.path.dirname(__file__), "..", "data", "live_speed_baseline.json"))
            else 2026
        ),
        "sources": [
            {
                "id": "courtspeed",
                "name": "Court Pace Index database (courtspeed.com)",
                "metric": "CPI",
                "detail": "Court Pace Index readings 2012-2026 for the Grand Slams, Masters 1000s and "
                          "ATP Finals, compiled from ATP / Tennis TV broadcast data. Centre-court "
                          "readings; no altitude adjustment.",
                "url": "https://courtspeed.com/",
            },
            {
                "id": "tennisabstract",
                "name": "Tennis Abstract ATP Surface Speed Ratings",
                "metric": "Speed",
                "detail": "Published ATP Surface Speed ratings and ace rates as reported by Tennis Abstract. "
                          "The figures are reproduced as published, not recomputed by this project.",
                "url": "https://tennisabstract.com/reports/atp_surface_speed.html",
            },
            {
                "id": "computed",
                "name": "Computed history - ATP match archives",
                "metric": "Speed",
                "detail": "Historical seasons use the Jeff Sackmann ATP archive (CC BY-NC-SA 4.0); "
                          "the current season prefers the TennisMyLife ATP Match Database. Both use "
                          "surface-consistent server and returner profiles (clay 1/3, hard/grass 2/3; "
                          "pooled returner fallback without a qualified surface sample), the "
                          "season-calibrated odds-ratio method, 0.5 expected-ace floor and season "
                          "normalisation. TML data use is non-commercial only.",
                "url": "https://github.com/Aneeshers/tennis-sackmann-archive",
            },
        ],
    },
    "tournaments": tournaments,
    "readings": readings,
    "live": live_events,
    "speedBaseline": (
        json.load(open(os.path.join(os.path.dirname(__file__), "..", "data", "live_speed_baseline.json")))
        if os.path.exists(os.path.join(os.path.dirname(__file__), "..", "data", "live_speed_baseline.json"))
        else None
    ),
}

out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "pace_dataset.json")
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w") as f:
    json.dump(doc, f, indent=1, sort_keys=False)
    f.write("\n")

cpi_n = len([r for r in readings if "cpi" in r])
speed_n = len([r for r in readings if "speed" in r])
print(f"{len(tournaments)} tournaments, {cpi_n} CPI readings, {speed_n} primary speed readings, "
      f"{computed_n} calibrated calculations ({published_overlap_n} with TA comparisons)")
print("seasons with CPI:", sorted({r['s'] for r in readings if 'cpi' in r}))
both = {r['t'] for r in readings if 'cpi' in r and r['s'] == 2026} & {r['t'] for r in readings if 'speed' in r}
print("2026 events with both metrics:", len(both), sorted(both))
