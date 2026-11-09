#!/usr/bin/env python3
"""Refresh the public Court Speed CPI table and Tennis Abstract calendar-year tables."""
from __future__ import annotations

import argparse
import ast
from datetime import datetime, timezone
from html.parser import HTMLParser
import json
import re
import time
import unicodedata
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parents[1]
BUILD_DATASET = ROOT / "tools" / "build_dataset.py"
TA_OUTPUT = ROOT / "data" / "tennis_abstract_history.json"
CPI_OUTPUT = ROOT / "data" / "courtspeed_cpi.json"
TA_URL = "https://www.tennisabstract.com/cgi-bin/surface-speed.cgi?year={year}"
CPI_URL = "https://courtspeed.com/"
USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"


class TableParser(HTMLParser):
    """Collect visible table cell text using only Python's standard library."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.rows: list[list[str]] = []
        self._row: list[str] | None = None
        self._cell: list[str] | None = None

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag.lower() == "tr":
            self._row = []
        elif tag.lower() in ("td", "th") and self._row is not None:
            self._cell = []

    def handle_data(self, data: str) -> None:
        if self._cell is not None:
            self._cell.append(data)

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if tag in ("td", "th") and self._row is not None and self._cell is not None:
            self._row.append(" ".join(" ".join(self._cell).split()))
            self._cell = None
        elif tag == "tr" and self._row is not None:
            if self._row:
                self.rows.append(self._row)
            self._row = None
            self._cell = None


def normalize(value: str) -> str:
    value = unicodedata.normalize("NFKD", value).casefold()
    return "".join(ch for ch in value if ch.isalnum())


def tournament_rows() -> list[tuple]:
    tree = ast.parse(BUILD_DATASET.read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(
            isinstance(target, ast.Name) and target.id == "TOURNAMENTS"
            for target in node.targets
        ):
            value = ast.literal_eval(node.value)
            if isinstance(value, list):
                return value
    raise RuntimeError("Could not read TOURNAMENTS from tools/build_dataset.py")


def tournament_lookup() -> tuple[dict[str, str], dict[str, set[str]]]:
    exact: dict[str, set[str]] = {}
    for row in tournament_rows():
        tid, name, city = row[0], row[1], row[2]
        for label in (tid, name, city):
            key = normalize(str(label))
            if key:
                exact.setdefault(key, set()).add(tid)
    aliases = {
        "ausopen": "ausopen",
        "australianopen": "ausopen",
        "frenchopen": "rolandgarros",
        "ndgarros": "rolandgarros",
        "rolandgarros": "rolandgarros",
        "usopen": "usopen",
        "paris": "paris",
        "parismasters": "paris",
        "tourfinals": "atpfinals",
        "atpfinals": "atpfinals",
        "nextgenfinals": "nextgen",
        "nextgenerationfinals": "nextgen",
        "canadamaster": "canada",
        "canadamasters": "canada",
        "canadianopen": "canada",
        "miamimasters": "miami",
        "indianwellsmaster": "indianwells",
        "cincinnatimasters": "cincinnati",
        "shanghaimasters": "shanghai",
        "montecarlomasters": "montecarlo",
        "madridmasters": "madrid",
        "romemasters": "rome",
    }
    return {normalize(k): v for k, v in aliases.items()}, exact


def resolve_tournament(label: str, aliases: dict[str, str], exact: dict[str, set[str]]) -> str | None:
    key = normalize(label)
    if key in aliases:
        return aliases[key]
    candidates = exact.get(key, set())
    if len(candidates) == 1:
        return next(iter(candidates))

    # Explicit aliases take precedence over generic city labels. This matters for
    # "Paris Hard indoor": the city Paris also belongs to Roland Garros.
    preferred = [
        (len(alias), tid)
        for alias, tid in aliases.items()
        if len(alias) >= 4 and alias in key
    ]
    if preferred:
        longest = max(length for length, _ in preferred)
        ids = {tid for length, tid in preferred if length == longest}
        if len(ids) == 1:
            return next(iter(ids))

    possible: set[str] = set()
    for alias, ids in exact.items():
        if len(alias) >= 5 and alias in key:
            possible.update(ids)
    if len(possible) == 1:
        return next(iter(possible))

    matches = [
        (len(alias), next(iter(ids)))
        for alias, ids in exact.items()
        if len(alias) >= 5 and alias in key and len(ids) == 1
    ]
    if matches:
        longest = max(length for length, _ in matches)
        ids = {tid for length, tid in matches if length == longest}
        if len(ids) == 1:
            return next(iter(ids))
    return None


def fetch(url: str, timeout: int = 40) -> str:
    request = Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "text/html,application/xhtml+xml",
            "Accept-Language": "en-US,en;q=0.9",
            "Referer": "https://tennisabstract.com/",
        },
    )
    last_error = None
    for attempt in range(3):
        try:
            with urlopen(request, timeout=timeout) as response:
                if response.status != 200:
                    raise RuntimeError(f"{url} returned HTTP {response.status}")
                return response.read().decode("utf-8", "replace")
        except HTTPError as error:
            last_error = error
            if error.code not in (429, 500, 502, 503, 504) or attempt == 2:
                raise
            retry_after = error.headers.get("Retry-After")
            try:
                wait_seconds = int(retry_after) if retry_after else 5 * (attempt + 1)
            except ValueError:
                wait_seconds = 5 * (attempt + 1)
            time.sleep(min(30, max(2, wait_seconds)))
    raise RuntimeError(f"Could not fetch {url}: {last_error}")


def parse_ta_year(html: str, season: int, aliases, exact) -> tuple[list[dict], list[str]]:
    parser = TableParser()
    parser.feed(html)
    readings: dict[str, dict] = {}
    unmapped: list[str] = []
    for cells in parser.rows:
        if len(cells) < 5:
            continue
        date_text, event_name, _surface, ace_text, speed_text = cells[:5]
        date_match = re.search(r"\d{1,2}-[A-Za-z]{3}-\d{4}", date_text.strip())
        if date_match:
            date_text = date_match.group()
        try:
            event_date = datetime.strptime(date_text.strip(), "%d-%b-%Y").date()
        except ValueError:
            try:
                event_date = datetime.strptime(date_text.strip(), "%Y-%m-%d").date()
            except ValueError:
                continue
        ace_match = re.search(r"\d+(?:\.\d+)?", ace_text.replace(",", ""))
        speed_match = re.search(r"\d+(?:\.\d+)?", speed_text.replace(",", ""))
        if not ace_match or not speed_match:
            continue
        tid = resolve_tournament(event_name, aliases, exact)
        if tid is None:
            unmapped.append(event_name)
            continue
        # The selected TA year is the season key, even when an event starts in December
        # of the preceding calendar year (e.g. Hong Kong/Brisbane/United Cup for 2025).
        readings[tid] = {
            "t": tid,
            "s": int(season),
            "date": event_date.isoformat(),
            "speed": float(speed_match.group()),
            "ace": float(ace_match.group()),
            "src": "tennisabstract",
        }
    return sorted(readings.values(), key=lambda row: row["t"]), sorted(set(unmapped))


def split_tables(rows: list[list[str]]) -> list[list[list[str]]]:
    tables: list[list[list[str]]] = []
    current: list[list[str]] = []
    for row in rows:
        if row and "tournament" in normalize(row[0]) and current:
            tables.append(current)
            current = []
        current.append(row)
    if current:
        tables.append(current)
    return tables


def parse_cpi_page(html: str, aliases, exact) -> list[dict]:
    parser = TableParser()
    parser.feed(html)
    selected = None
    year_indices: dict[int, int] = {}
    for rows in split_tables(parser.rows):
        for index, row in enumerate(rows):
            if not row or "tournament" not in normalize(row[0]):
                continue
            columns = {}
            for cell_index, heading in enumerate(row[1:], start=1):
                match = re.search(r"(?<!\d)(\d{2})(?!\d)", heading)
                if not match:
                    continue
                yy = int(match.group(1))
                year = 2000 + yy if yy <= 79 else 1900 + yy
                if 2012 <= year <= 2099:
                    columns[year] = cell_index
            if len(columns) >= 8:
                selected = (rows, index + 1)
                year_indices = columns
                break
        if selected:
            break
    if selected is None:
        raise RuntimeError("Could not find the Court Speed CPI table/year headings")

    rows, start = selected
    readings: dict[tuple[str, int], dict] = {}
    for row in rows[start:]:
        if len(row) <= max(year_indices.values()):
            continue
        tid = resolve_tournament(row[0], aliases, exact)
        if tid is None:
            continue
        for year, cell_index in year_indices.items():
            value_text = row[cell_index].strip().replace(",", "")
            number = re.search(r"(?<![\w.])-?\d+(?:\.\d+)?", value_text)
            if not number:
                continue
            value = float(number.group())
            if 10.0 <= value <= 60.0:
                readings[(tid, year)] = {
                    "t": tid, "s": year, "cpi": value, "src": "courtspeed"
                }
    return sorted(readings.values(), key=lambda row: (row["s"], row["t"]))


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def refresh(start_year: int, end_year: int) -> None:
    aliases, exact = tournament_lookup()

    # Older seasons are stable. Recheck only the previous and current season, retaining the
    # rest of the last-known snapshot so the source isn't hammered with 36 requests every run.
    seasons: dict[str, list[dict]] = {}
    unmapped: dict[str, list[str]] = {}
    if TA_OUTPUT.exists():
        try:
            previous = json.loads(TA_OUTPUT.read_text(encoding="utf-8"))
            seasons.update(previous.get("seasons", {}))
            unmapped.update(previous.get("unmapped", {}))
        except (OSError, json.JSONDecodeError, TypeError):
            print("WARNING: existing Tennis Abstract snapshot unreadable; use the bundled fallback.")
    update_start = max(start_year, end_year - 1)
    ta_updated = False
    for year in range(update_start, end_year + 1):
        try:
            html = fetch(TA_URL.format(year=year))
            rows, missing = parse_ta_year(html, year, aliases, exact)
            if len(rows) < 30:
                raise RuntimeError(f"only {len(rows)} mapped rows parsed from Tennis Abstract {year}")
            seasons[str(year)] = rows
            if missing:
                unmapped[str(year)] = missing
            else:
                unmapped.pop(str(year), None)
            ta_updated = True
        except Exception as error:
            print(f"WARNING: Tennis Abstract {year} refresh skipped: {error}")
        time.sleep(2)

    if ta_updated:
        generated = datetime.now(timezone.utc).isoformat(timespec="seconds")
        write_json(TA_OUTPUT, {
            "generated": generated,
            "source": TA_URL.format(year=end_year),
            "seasonPolicy": "Tennis Abstract year selector",
            "seasons": seasons,
            "unmapped": unmapped,
        })
        print(f"Updated Tennis Abstract snapshot: {sum(len(rows) for rows in seasons.values())} mapped event-seasons.")
    else:
        print("WARNING: Tennis Abstract unavailable; keep the bundled/last-known calendar-year rows.")

    try:
        cpi_rows = parse_cpi_page(fetch(CPI_URL), aliases, exact)
        current_cpi = [row for row in cpi_rows if row["s"] == end_year]
        if len(cpi_rows) < 50 or len(current_cpi) < 5:
            raise RuntimeError(f"only {len(cpi_rows)} CPI rows ({len(current_cpi)} for {end_year}) parsed")
        generated = datetime.now(timezone.utc).isoformat(timespec="seconds")
        write_json(CPI_OUTPUT, {"generated": generated, "source": CPI_URL, "readings": cpi_rows})
        print(f"Updated Court Speed snapshot: {len(cpi_rows)} CPI readings.")
    except Exception as error:
        print(f"WARNING: Court Speed refresh skipped: {error}")
        print("The reviewed bundled CPI table remains in use.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--start-year", type=int, default=1991)
    parser.add_argument("--end-year", type=int, default=datetime.now(timezone.utc).year)
    args = parser.parse_args()
    if args.start_year < 1991 or args.end_year < args.start_year:
        parser.error("year range must be ordered and start no earlier than 1991")
    refresh(args.start_year, args.end_year)


if __name__ == "__main__":
    main()
