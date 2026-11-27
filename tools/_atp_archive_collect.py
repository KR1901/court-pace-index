#!/usr/bin/env python3
"""One-time, permissioned collection of official ATP Results Archive event-date cards."""
from __future__ import annotations
import asyncio
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from playwright.async_api import async_playwright

RESULT_LINK = re.compile(r"/en/scores/archive/([^/?#]+)/([0-9]+)/([0-9]{4})/results(?:[?#]|$)", re.I)
DATE_PATTERNS = (
    re.compile(r"\b\d{1,2}\s+[A-Za-z]+\s*,?\s*\d{4}\s*-\s*\d{1,2}\s+[A-Za-z]+,?\s*\d{4}\b", re.I),
    re.compile(r"\b\d{1,2}\s+[A-Za-z]+\s*-\s*\d{1,2}\s+[A-Za-z]+,?\s*\d{4}\b", re.I),
    re.compile(r"\b\d{1,2}\s*-\s*\d{1,2}\s+[A-Za-z]+,?\s*\d{4}\b", re.I),
)
ROOT = Path(__file__).resolve().parents[1]

def extract_official_date(candidates: list[dict], year: int) -> str | None:
    for candidate in sorted(candidates, key=lambda value: value["depth"]):
        matches = []
        for pattern in DATE_PATTERNS:
            matches.extend(match.group(0).strip(" |") for match in pattern.finditer(candidate.get("text", "")))
        unique = list(dict.fromkeys(matches))
        if len(unique) != 1:
            continue
        date_text = unique[0]
        years = [int(value) for value in re.findall(r"\b(?:19|20)\d{2}\b", date_text)]
        if years and years[-1] == year:
            return date_text
    return None

async def collect(year: int) -> None:
    result = {
        "season": year,
        "requestedUrl": f"https://www.atptour.com/en/scores/results-archive?year={year}",
        "source": "ATP Tour official Results Archive",
        "retrievedAtUtc": datetime.now(timezone.utc).isoformat(),
        "status": "not_started",
        "events": [],
        "missingDates": [],
        "attempts": [],
    }
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch()
        context = await browser.new_context(locale="en-GB", viewport={"width": 1440, "height": 1100})
        page = await context.new_page()
        try:
            for attempt in range(1, 4):
                try:
                    response = await page.goto(result["requestedUrl"], wait_until="domcontentloaded", timeout=60000)
                    await page.wait_for_load_state("networkidle", timeout=20000)
                    await page.wait_for_timeout(1200)
                    body = await page.locator("body").inner_text()
                    title = await page.title()
                    status_code = response.status if response else None
                    blocked = (
                        status_code in {401, 403, 429}
                        or "security verification" in body.casefold()
                        or "you have been blocked" in body.casefold()
                        or "attention required" in title.casefold()
                    )
                    result["attempts"].append({
                        "attempt": attempt, "httpStatus": status_code, "title": title,
                        "bodyLength": len(body), "blocked": blocked,
                    })
                    if blocked:
                        raise RuntimeError(f"ATP returned HTTP {status_code} or an access challenge; stopping without bypass")
                    if status_code != 200:
                        raise RuntimeError(f"ATP archive returned HTTP {status_code}")

                    anchors = await page.locator("a").evaluate_all("""nodes => nodes.map(a => {
                      const li = a.closest('li');
                      const ancestors = [];
                      let node = a;
                      for (let depth = 0; node && depth < 9; depth++, node = node.parentElement) {
                        const text = (node.innerText || node.textContent || '').trim().replace(/\\s+/g, ' ');
                        if (text) ancestors.push({depth, text});
                      }
                      return {
                        href: a.href,
                        linkText: (a.innerText || a.textContent || '').trim(),
                        cardText: li ? (li.innerText || li.textContent || '').trim().replace(/\\s+/g, ' ') : '',
                        ancestors
                      };
                    })""")

                    events = {}
                    missing = []
                    for anchor in anchors:
                        match = RESULT_LINK.search(anchor.get("href", ""))
                        if not match or int(match.group(3)) != year:
                            continue
                        slug, atp_id, _ = match.groups()
                        href = f"https://www.atptour.com/en/scores/archive/{slug}/{atp_id}/{year}/results"
                        official_date = extract_official_date(anchor.get("ancestors", []), year)
                        if not official_date:
                            missing.append({
                                "slug": slug, "atpId": atp_id,
                                "linkText": anchor.get("linkText", ""),
                                "cardText": anchor.get("cardText", ""),
                            })
                            continue
                        card_text = anchor.get("cardText", "") or anchor.get("linkText", "")
                        date_pos = card_text.find(official_date)
                        if date_pos >= 0:
                            card_text = card_text[:date_pos]
                        card_text = re.sub(r"\s+", " ", card_text).strip(" |,")
                        events[href] = {
                            "season": year, "slug": slug, "atpId": atp_id,
                            "sourceUrl": href, "cardText": card_text,
                            "officialDateText": official_date,
                        }

                    result["events"] = list(events.values())
                    result["missingDates"] = missing
                    print(
                        f"ATP official archive {year}: HTTP {status_code}; "
                        f"{len(events)} dated cards; {len(missing)} cards without a date",
                        flush=True,
                    )
                    if not events:
                        raise RuntimeError("ATP archive returned no tournament result links for the selected season")
                    if missing:
                        raise RuntimeError(f"{len(missing)} official event cards did not expose a unique date range")
                    result["status"] = "success"
                    break
                except Exception as exc:
                    result["attempts"].append({"attempt": attempt, "error": f"{type(exc).__name__}: {exc}"})
                    print(f"ATP archive {year}: attempt {attempt}: {exc}", flush=True)
                    if any(code in str(exc) for code in ("HTTP 401", "HTTP 403", "HTTP 429", "access challenge")):
                        break
                    if attempt < 3:
                        await page.wait_for_timeout(attempt * 3000)
        finally:
            await browser.close()

    result["retrievedAtUtc"] = datetime.now(timezone.utc).isoformat()
    output = ROOT / f"atp_archive_{year}.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"ATP archive {year}: final status={result['status']} · "
        f"{len(result['events'])} dated cards · output={output.name}",
        flush=True,
    )
    if result["status"] != "success":
        raise SystemExit(1)

def main() -> None:
    if len(sys.argv) != 2 or not sys.argv[1].isdigit():
        raise SystemExit("usage: _atp_archive_collect.py YEAR")
    year = int(sys.argv[1])
    if not 2020 <= year <= 2026:
        raise SystemExit(f"unsupported one-time collection year: {year}")
    asyncio.run(collect(year))

if __name__ == "__main__":
    main()
