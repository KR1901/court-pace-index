#!/usr/bin/env python3
"""Compares ways of turning expected aces into an event speed rating, scored against the
Tennis Abstract readings already in the app's dataset.

Why this exists
---------------
The server/returner-adjusted rating divides actual aces by expected aces, where expected comes
from  p = ps*qr / (ps*qr + (1-ps)*(1-qr))  times the server's service points. That form has no
tour-average term, so for an average pair p is roughly a tenth of the real ace rate and the
expected count sits right around 0.5 - the very value the pipeline uses as a cut-off / floor.
The 0.5 threshold can affect a substantial share of sides, so its binding rate must be measured
from the match archive rather than assumed to be rare. This script measures what each choice
does to agreement with the published reference series.

Variants
--------
  drop        uncalibrated formula, sides with expected <= 0.5 are skipped  (pipeline before)
  floor       uncalibrated formula, expected is floored at 0.5               (pipeline now)
  calibrated  odds-ratio form WITH the season tour-average ace rate, same 0.5 floor
              (the floor then rarely binds - the share that does is printed)
  pooled      event ace rate / season ace rate, no player adjustment at all (reference point)

Scoring
-------
All variants are intersected with the published Tennis Abstract event set, then normalised
to a mean of 1.00 on exactly the same common events within each season. Pearson r, Spearman
rho, mean absolute difference, and signed bias are reported with paired bootstrap 95% intervals.
Paired differences against a baseline use identical event resamples.

Run (downloads/caches ATP match CSVs and annual Tennis Abstract tables):
    python3 tools/compare_speed_variants.py
    python3 tools/compare_speed_variants.py --seasons 2012 2013 2014 2015 2016 2017 2018 2019 2020 2021 2022 2023 2024 2025 2026 --boot 5000
"""
import argparse
import json
import math
import os
import random
import sys
import time
import unicodedata
from datetime import date
from collections import defaultdict
from html.parser import HTMLParser
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compute_speed import LEVELS, MIN_MATCHES_PER_SURFACE, load, num  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_DATASET = os.path.join(ROOT, "app", "src", "main", "assets", "pace_dataset.json")
VARIANTS = ("drop", "floor", "calibrated", "pooled")
FLOOR = 0.5
MIN_SIDES = 20
FIRST_ARCHIVE_SEASON = 2012
TA_URL = "https://www.tennisabstract.com/cgi-bin/surface-speed.cgi?year={year}"
TA_CACHE = os.path.join(ROOT, ".cache", "tennisabstract")
TA_REQUEST_DELAY_SECONDS = 1.0


def datetime_now_year():
    return date.today().year


# ---------------------------------------------------------------- player profiles

def valid_rows(rows):
    """Tour-level rows with both players' aces and service-point totals."""
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
    """Same construction as compute_speed.py: clay 1/3 + hard/grass 2/3 serve rate, pooled concede rate."""
    clay_ace = defaultdict(float); clay_sv = defaultdict(float); clay_n = defaultdict(int)
    hg_ace = defaultdict(float); hg_sv = defaultdict(float); hg_n = defaultdict(int)
    ret_ace = defaultdict(float); ret_pts = defaultdict(float)
    tour_aces = tour_points = 0.0

    for r in usable:
        w_sv, l_sv = num(r["w_svpt"]), num(r["l_svpt"])
        w_ac, l_ac = num(r["w_ace"]), num(r["l_ace"])
        wid, lid = r["winner_id"], r["loser_id"]
        surface = (r["surface"] or "").strip()
        tour_aces += w_ac + l_ac
        tour_points += w_sv + l_sv
        if surface == "Clay":
            clay_ace[wid] += w_ac; clay_sv[wid] += w_sv; clay_n[wid] += 1
            clay_ace[lid] += l_ac; clay_sv[lid] += l_sv; clay_n[lid] += 1
        elif surface in ("Hard", "Grass"):
            hg_ace[wid] += w_ac; hg_sv[wid] += w_sv; hg_n[wid] += 1
            hg_ace[lid] += l_ac; hg_sv[lid] += l_sv; hg_n[lid] += 1
        ret_ace[wid] += l_ac; ret_pts[wid] += l_sv
        ret_ace[lid] += w_ac; ret_pts[lid] += w_sv

    serve = {}
    for p in set(clay_sv) | set(hg_sv):
        c = clay_ace[p] / clay_sv[p] if clay_sv[p] > 0 else None
        h = hg_ace[p] / hg_sv[p] if hg_sv[p] > 0 else None
        if c is not None and h is not None and clay_n[p] >= MIN_MATCHES_PER_SURFACE \
                and hg_n[p] >= MIN_MATCHES_PER_SURFACE:
            serve[p] = (1 / 3) * c + (2 / 3) * h
        elif c is not None and h is None and clay_n[p] >= MIN_MATCHES_PER_SURFACE:
            serve[p] = c
        elif h is not None and c is None and hg_n[p] >= MIN_MATCHES_PER_SURFACE:
            serve[p] = h
    concede = {p: ret_ace[p] / ret_pts[p] for p in ret_pts if ret_pts[p] > 0}
    tour_rate = tour_aces / tour_points if tour_points > 0 else None
    return serve, concede, tour_rate


# ---------------------------------------------------------------- expectations

def expected_uncalibrated(ps, qr, sv):
    joint = ps * qr
    denom = joint + (1 - ps) * (1 - qr)
    return (joint / denom) * sv if denom > 0 else 0.0


def expected_calibrated(ps, qr, sv, tour):
    """Odds-ratio (log5) expectation: the server's odds scaled by the returner's, over the tour's."""
    if not (0 < ps < 1 and 0 < qr < 1 and 0 < tour < 1):
        return 0.0
    odds = (ps / (1 - ps)) * (qr / (1 - qr)) / (tour / (1 - tour))
    return (odds / (1 + odds)) * sv


# ---------------------------------------------------------------- season ratings

def season_variants(rows):
    """Returns ({variant: {tournament_id: rating}}, diagnostics) for one season."""
    usable = valid_rows(rows)
    serve, concede, tour = player_rates(usable)

    ratios = {v: defaultdict(list) for v in ("drop", "floor", "calibrated")}
    event_aces = defaultdict(float); event_sv = defaultdict(float)
    diag = {"sides": 0, "uncal_le_floor": 0, "cal_sides": 0, "cal_le_floor": 0}

    for r in usable:
        raw_name = (r["tourney_name"] or "").strip()
        if not raw_name:
            continue
        tid = tournament_key(raw_name)
        w_sv, l_sv = num(r["w_svpt"]), num(r["l_svpt"])
        w_ac, l_ac = num(r["w_ace"]), num(r["l_ace"])
        wid, lid = r["winner_id"], r["loser_id"]
        event_aces[tid] += w_ac + l_ac
        event_sv[tid] += w_sv + l_sv

        for server, returner, sv, ac in ((wid, lid, w_sv, w_ac), (lid, wid, l_sv, l_ac)):
            ps, qr = serve.get(server), concede.get(returner)
            if ps is None or qr is None:
                continue
            e_un = expected_uncalibrated(ps, qr, sv)
            if e_un <= 0:
                continue
            diag["sides"] += 1
            if e_un <= FLOOR:
                diag["uncal_le_floor"] += 1
            else:
                ratios["drop"][tid].append(ac / e_un)
            ratios["floor"][tid].append(ac / max(e_un, FLOOR))

            e_cal = expected_calibrated(ps, qr, sv, tour) if tour else 0.0
            if e_cal > 0:
                diag["cal_sides"] += 1
                if e_cal <= FLOOR:
                    diag["cal_le_floor"] += 1
                ratios["calibrated"][tid].append(ac / max(e_cal, FLOOR))

    out = {}
    for variant, per_event in ratios.items():
        out[variant] = {
            tournament_id: sum(values) / len(values)
            for tournament_id, values in per_event.items()
            if len(values) >= MIN_SIDES
        }

    # Pooled ace rate is an unadjusted reference. Its broader eligibility is intersected
    # with all adjusted variants before scoring.
    out["pooled"] = {
        tournament_id: event_aces[tournament_id] / event_sv[tournament_id]
        for tournament_id in event_sv
        if event_sv[tournament_id] > 0
    }
    return out, diag


def normalise_on_common_events(raw_variants, published_event_ids):
    """Normalise every variant on exactly the same published event set."""
    common = set(published_event_ids)
    for variant in VARIANTS:
        common.intersection_update(raw_variants.get(variant, {}))
    common = sorted(common)
    if not common:
        return {}, []

    normalised = {}
    for variant in VARIANTS:
        mean = sum(raw_variants[variant][event_id] for event_id in common) / len(common)
        if not math.isfinite(mean) or mean <= 0:
            return {}, []
        normalised[variant] = {
            event_id: raw_variants[variant][event_id] / mean
            for event_id in common
        }
    return normalised, common


# ---------------------------------------------------------------- statistics

def pearson(xs, ys):
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    syy = sum((y - my) ** 2 for y in ys)
    if sxx == 0 or syy == 0:
        return float("nan")
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / math.sqrt(sxx * syy)


def ranks(values):
    order = sorted(range(len(values)), key=lambda i: values[i])
    out = [0.0] * len(values)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and values[order[j + 1]] == values[order[i]]:
            j += 1
        for k in range(i, j + 1):
            out[order[k]] = (i + j) / 2 + 1
        i = j + 1
    return out


def spearman(xs, ys):
    return pearson(ranks(xs), ranks(ys))


def metrics(pairs):
    xs = [p[0] for p in pairs]
    ys = [p[1] for p in pairs]
    return {
        "r": pearson(xs, ys),
        "rho": spearman(xs, ys),
        "mae": sum(abs(x - y) for x, y in pairs) / len(pairs),
        "bias": sum(x - y for x, y in pairs) / len(pairs),
    }


def percentile(sorted_values, q):
    if not sorted_values:
        return float("nan")
    k = (len(sorted_values) - 1) * q
    lo, hi = int(math.floor(k)), int(math.ceil(k))
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * (k - lo)


def interval(values):
    clean = sorted(v for v in values if not math.isnan(v))
    return percentile(clean, 0.025), percentile(clean, 0.975)


# ---------------------------------------------------------------- Tennis Abstract references

def tournament_key(name):
    """Create a source-independent event key from an event's displayed name."""
    decomposed = unicodedata.normalize("NFKD", name).casefold()
    key = "".join(char for char in decomposed if char.isalnum())
    aliases = {
        "indianwellstournament": "indianwells",
        "indianwellsmaster": "indianwells",
        "indianwellsmasterstournament": "indianwells",
        "usopen": "usopen",
        "unitedstatesopen": "usopen",
        "frenchopen": "rolandgarros",
        "rolandgarros": "rolandgarros",
        "atpfinals": "tourfinals",
        "nittoatpfinals": "tourfinals",
        "nextgenfinals": "nextgenfinals",
        "rio": "riodejaneiro",
        "riodejaneiro": "riodejaneiro",
        "shertogenbosch": "shertogenbosch",
    }
    return aliases.get(key, key)


class SurfaceSpeedHtmlParser(HTMLParser):
    """Extract table rows without depending on pandas or a third-party HTML package."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.rows = []
        self._row = None
        self._cell = None
        self._cell_depth = 0

    def handle_starttag(self, tag, attrs):
        tag = tag.lower()
        if tag == "tr":
            self._row = []
        elif tag in ("td", "th") and self._row is not None and self._cell is None:
            self._cell = []

    def handle_data(self, data):
        if self._cell is not None:
            self._cell.append(data)

    def handle_endtag(self, tag):
        tag = tag.lower()
        if tag in ("td", "th") and self._cell is not None:
            self._row.append(" ".join(" ".join(self._cell).split()))
            self._cell = None
        elif tag == "tr" and self._row is not None:
            if self._row:
                self.rows.append(self._row)
            self._row = None
            self._cell = None


def parse_tennis_abstract_page(html):
    """Parse {normalised tournament key: published speed} from one annual TA page."""
    parser = SurfaceSpeedHtmlParser()
    parser.feed(html)
    readings = {}
    for cells in parser.rows:
        # The current TA table is Date | Tournament | Surface | Ace% | Surface Speed.
        if len(cells) < 5:
            continue
        if cells[2].strip().casefold() not in ("hard", "clay", "grass", "carpet"):
            continue
        if not cells[3].strip().endswith("%"):
            continue
        event_name = cells[1]
        try:
            speed = float(cells[-1].strip().replace(",", ""))
        except ValueError:
            continue
        if not event_name or not math.isfinite(speed) or not 0.0 < speed < 3.0:
            continue
        readings[tournament_key(event_name)] = speed
    return readings


def fetch_tennis_abstract_season(year, cache_dir=TA_CACHE):
    """Fetch and cache the official annual reference table, with gentle retry on rate limits."""
    os.makedirs(cache_dir, exist_ok=True)
    cache_path = os.path.join(cache_dir, f"surface-speed-{year}.html")
    html = None
    if os.path.exists(cache_path):
        with open(cache_path, encoding="utf-8", errors="replace") as source:
            html = source.read()

    if html is None or not parse_tennis_abstract_page(html):
        request = Request(
            TA_URL.format(year=year),
            headers={"User-Agent": "CourtPaceIndex-formula-analysis/1.0 (research; contact via GitHub)"},
        )
        last_error = None
        for attempt in range(4):
            try:
                with urlopen(request, timeout=45) as response:
                    html = response.read().decode("utf-8", errors="replace")
                break
            except (HTTPError, URLError, TimeoutError) as error:
                last_error = error
                if isinstance(error, HTTPError) and error.code not in (429, 500, 502, 503, 504):
                    raise
                time.sleep(2 ** attempt)
        else:
            raise RuntimeError(f"could not fetch Tennis Abstract {year}: {last_error}")
        if not parse_tennis_abstract_page(html):
            raise ValueError(f"could not parse Tennis Abstract annual table for {year}")
        with open(cache_path, "w", encoding="utf-8") as destination:
            destination.write(html)
        time.sleep(TA_REQUEST_DELAY_SECONDS)

    return parse_tennis_abstract_page(html)


def fetch_tennis_abstract_speeds(seasons, cache_dir=TA_CACHE):
    """Fetch annual published readings for the requested seasons (same calendar-year keys)."""
    result = {}
    for season in seasons:
        readings = fetch_tennis_abstract_season(season, cache_dir)
        if readings:
            result[season] = readings
        print(f"Tennis Abstract {season}: {len(readings)} published event readings")
    return result


# ---------------------------------------------------------------- main

def published_speeds(dataset_path, seasons):
    with open(dataset_path, encoding="utf-8") as f:
        dataset = json.load(f)
    readings = [r for r in dataset["readings"] if r.get("speed") is not None]
    ta = [r for r in readings if r.get("src") == "tennisabstract"]
    if not ta:
        raise ValueError("dataset contains no published Tennis Abstract readings")
    names_by_id = {
        tournament["id"]: tournament["name"]
        for tournament in dataset.get("tournaments", [])
    }
    by_season = defaultdict(dict)
    for reading in ta:
        if seasons is None or reading["s"] in seasons:
            event_name = names_by_id.get(reading["t"], reading["t"])
            by_season[reading["s"]][tournament_key(event_name)] = reading["speed"]
    return by_season


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dataset", default=DEFAULT_DATASET)
    ap.add_argument("--reference-source", choices=("tennisabstract", "dataset"),
                    default="tennisabstract",
                    help="fetch annual Tennis Abstract tables, or use bundled dataset references")
    ap.add_argument("--ta-cache-dir", default=TA_CACHE)
    ap.add_argument("--seasons", type=int, nargs="*", default=None,
                    help=f"seasons to score (default: {FIRST_ARCHIVE_SEASON} through current year)")
    ap.add_argument("--boot", type=int, default=2000, help="bootstrap resamples")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--baseline", choices=VARIANTS, default="drop",
                    help="variant the paired differences are measured against")
    args = ap.parse_args()
    if args.boot < 1:
        ap.error("--boot must be at least 1")

    seasons = args.seasons if args.seasons else list(range(FIRST_ARCHIVE_SEASON, datetime_now_year() + 1))
    if args.reference_source == "tennisabstract":
        published = fetch_tennis_abstract_speeds(seasons, args.ta_cache_dir)
    else:
        published = published_speeds(args.dataset, set(seasons))
    if not published:
        sys.exit("no published Tennis Abstract readings found to score against")

    pairs = {v: [] for v in VARIANTS}       # (ours, published) pooled across seasons
    keys = {v: [] for v in VARIANTS}        # (season, tournament) in the same order
    print("season  TA events  matched  common  sides  uncal<=0.5  cal<=0.5")
    for season in sorted(published):
        try:
            rows = load(season)
        except Exception as error:  # noqa: BLE001 - report and carry on with other seasons
            print(f"{season}: could not load match data ({error})")
            continue
        raw_variants, diag = season_variants(rows)
        published_matches = set(published[season]) & set(raw_variants["floor"])
        normalised, common = normalise_on_common_events(raw_variants, published[season])
        share_un = diag["uncal_le_floor"] / diag["sides"] if diag["sides"] else float("nan")
        share_cal = diag["cal_le_floor"] / diag["cal_sides"] if diag["cal_sides"] else float("nan")
        print(
            f"{season}   {len(published[season]):9d}  {len(published_matches):7d}  "
            f"{len(common):6d}  {diag['sides']:5d}   {share_un:8.1%}  {share_cal:8.1%}"
        )
        if not common:
            continue
        # Every variant has mean 1.00 on this exact event set for this season.
        for variant in VARIANTS:
            for tournament_id in common:
                pairs[variant].append((
                    normalised[variant][tournament_id],
                    published[season][tournament_id],
                ))
                keys[variant].append((season, tournament_id))

    # Score every variant on the events ALL variants rated, so the comparison is like for like.
    shared = set(keys[VARIANTS[0]])
    for v in VARIANTS[1:]:
        shared &= set(keys[v])
    shared = sorted(shared)
    if len(shared) < 8:
        sys.exit(f"only {len(shared)} events rated by every variant - too few to compare")
    table = {}
    for v in VARIANTS:
        index = {k: p for k, p in zip(keys[v], pairs[v])}
        table[v] = [index[k] for k in shared]

    # Season-stratified paired bootstrap: preserve each season's event count and resample
    # events within that season. All variants use identical sampled event indices.
    by_season = defaultdict(list)
    for index, (season, _event_id) in enumerate(shared):
        by_season[season].append(index)
    available_seasons = sorted(by_season)
    rng = random.Random(args.seed)
    draws = []
    for _ in range(args.boot):
        sampled_indices = []
        for season in available_seasons:
            event_indices = by_season[season]
            sampled_indices.extend(rng.choice(event_indices) for _ in event_indices)
        draws.append(sampled_indices)
    boot = {v: [metrics([table[v][i] for i in d]) for d in draws] for v in VARIANTS}

    print(f"\nScored on {len(shared)} events across {len(available_seasons)} seasons, rated by every variant "
          f"and matched to Tennis Abstract; {args.boot} season-stratified paired bootstrap resamples\n")
    metric_labels = (
        ("r", "Pearson r"),
        ("rho", "Spearman rho"),
        ("mae", "MAE"),
        ("bias", "Bias"),
    )
    print(f"{'variant':11s} " + " ".join(f"{label:>23s}" for _, label in metric_labels))
    for variant in VARIANTS:
        point = metrics(table[variant])
        cells = []
        for metric, _ in metric_labels:
            low, high = interval([sample[metric] for sample in boot[variant]])
            cells.append(f"{point[metric]:7.3f} [{low:6.3f},{high:6.3f}]")
        print(f"{variant:11s} " + " ".join(f"{cell:>23s}" for cell in cells))

    baseline = args.baseline
    print(
        f"\nPaired differences against '{baseline}' (variant minus baseline); "
        "intervals containing zero do not establish a reliable difference"
    )
    print(f"{'variant':11s} " + " ".join(f"{label:>23s}" for _, label in metric_labels))
    for variant in VARIANTS:
        if variant == baseline:
            continue
        point_variant = metrics(table[variant])
        point_baseline = metrics(table[baseline])
        cells = []
        for metric, _ in metric_labels:
            differences = [
                boot[variant][i][metric] - boot[baseline][i][metric]
                for i in range(args.boot)
            ]
            low, high = interval(differences)
            cells.append(
                f"{point_variant[metric] - point_baseline[metric]:+7.3f} "
                f"[{low:+6.3f},{high:+6.3f}]"
            )
        print(f"{variant:11s} " + " ".join(f"{cell:>23s}" for cell in cells))

    print(
        "\nInterpretation: higher r/rho and lower MAE are closer to the published reference. "
        "Bias near zero indicates less systematic over/under-rating. Agreement with Tennis "
        "Abstract is a reference comparison, not proof of a better physical court-speed measure. "
        "Bootstrap resampling is stratified by season to preserve the observed season composition."
    )


if __name__ == "__main__":
    main()