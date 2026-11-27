#!/usr/bin/env python3
"""One-time collection of ATP official Results Archive event cards for a single season."""
import asyncio
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

from playwright.async_api import async_playwright

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
RESULT_LINK = re.compile(
    r"/en/scores/archive/([^/?#]+)/([0-9]+)/([0-9]{4})/results(?:[?#]|$)", re.I
)
ROOT = Path(__file__).resolve().parents[1]


async def read_archive(page, year):
    anchors = await page.locator("a").evaluate_all("""nodes => nodes.map(a => {
      const li = a.closest('li');
      return {
        href: a.href,
        linkText: (a.innerText || a.textContent || '').trim(),
        cardText: li ? (li.innerText || '').trim().replace(/\\s+/g, ' ') : '',
        cardHtml: li ? li.innerHTML : ''
      };
    })""")
    events = {}
    for anchor in anchors:
        match = RESULT_LINK.search(anchor.get("href", ""))
        if not match or int(match.group(3)) != year:
            continue
        slug, atp_id, _ = match.groups()
        href = f"https://www.atptour.com/en/scores/archive/{slug}/{atp_id}/{year}/results"
        events[href] = {
            "season": year,
            "slug": slug,
            "atpId": atp_id,
            "sourceUrl": href,
            "cardText": anchor.get("cardText", ""),
            "cardHtml": anchor.get("cardHtml", ""),
        }
    body = await page.locator("body").inner_text()
    return body, list(events.values())


async def collect(year):
    result = {
        "season": year,
        "requestedUrl": f"https://www.atptour.com/en/scores/results-archive?year={year}",
        "source": "ATP Tour official Results Archive",
        "retrievedAtUtc": datetime.now(timezone.utc).isoformat(),
        "status": "not_started",
        "events": [],
        "attempts": [],
    }
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(args=[
            "--no-sandbox",
            "--disable-blink-features=AutomationControlled",
        ])
        context = await browser.new_context(
            user_agent=UA,
            locale="en-GB",
            viewport={"width": 1440, "height": 1100},
        )
        await context.add_init_script(
            "Object.defineProperty(navigator, 'webdriver', {get: () => undefined})"
        )
        page = await context.new_page()
        for attempt in range(1, 6):
            try:
                response = await page.goto(
                    result["requestedUrl"],
                    wait_until="domcontentloaded",
                    timeout=45000,
                )
                await page.wait_for_timeout(1800 + attempt * 900)
                body, events = await read_archive(page, year)
                status_code = response.status if response else None
                blocked = (
                    "security verification" in body.casefold()
                    or "you have been blocked" in body.casefold()
                    or "attention required" in (await page.title()).casefold()
                )
                result["attempts"].append({
                    "attempt": attempt,
                    "httpStatus": status_code,
                    "title": await page.title(),
                    "bodyLength": len(body),
                    "blocked": blocked,
                    "eventCount": len(events),
                })
                print(
                    f"ATP archive {year}: attempt {attempt} · HTTP {status_code} · "
                    f"{len(events)} event links · blocked={blocked}",
                    flush=True,
                )
                if status_code == 200 and not blocked and events:
                    result["status"] = "success"
                    result["events"] = events
                    break

                # Re-establish an ordinary first-party ATP session before retrying the
                # requested season. This is still the ATP Tour site, not another date source.
                try:
                    await page.goto(
                        "https://www.atptour.com/en/scores/results-archive",
                        wait_until="domcontentloaded",
                        timeout=30000,
                    )
                    await page.wait_for_timeout(2200)
                except Exception:
                    pass
            except Exception as exc:
                result["attempts"].append({
                    "attempt": attempt,
                    "error": f"{type(exc).__name__}: {exc}",
                })
                print(f"ATP archive {year}: attempt {attempt} error: {exc}", flush=True)
            if attempt < 5:
                await page.wait_for_timeout(min(15000, attempt * 2500))
        await browser.close()

    result["retrievedAtUtc"] = datetime.now(timezone.utc).isoformat()
    output = ROOT / f"atp_archive_{year}.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"ATP archive {year}: final status={result['status']} · "
        f"{len(result['events'])} event links · output={output.name}",
        flush=True,
    )
    if result["status"] != "success":
        raise SystemExit(1)


def main():
    if len(sys.argv) != 2 or not sys.argv[1].isdigit():
        raise SystemExit("usage: _atp_archive_collect.py YEAR")
    year = int(sys.argv[1])
    if not 1991 <= year <= datetime.now(timezone.utc).year:
        raise SystemExit(f"unsupported ATP archive year: {year}")
    asyncio.run(collect(year))


if __name__ == "__main__":
    main()
