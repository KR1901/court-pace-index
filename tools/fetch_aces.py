#!/usr/bin/env python3
"""Reads the ATP per-match stats pages for the live event and computes its ace rating.

The stats pages are rendered client-side, so a plain HTTP request returns an empty shell. This
drives a headless browser, reads the rendered text, and applies the same construction the app
documents: ace rate against a tour-average surface.

If nothing usable is found it writes no rating and says so - it never invents a figure.
"""
import asyncio, json, os, re, sys
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIVE = os.path.join(ROOT, "data", "live.json")

# Tour-level ace rate, 2022-2025: 137,134 aces in 1,751,112 service points.
BASELINE = 0.078313
MIN_MATCHES = 8
MAX_MATCH = 80

ACES = re.compile(r"(\d+)\s*Aces\s*(\d+)\s*Aces")
FIRST_SERVE = re.compile(r"(\d+)/(\d+)\s*\(\d+%\)\s*First serve")


async def render(browser, url):
    page = await browser.new_page()
    try:
        await page.goto(url, wait_until="networkidle", timeout=30000)
        # The numbers are injected after load; wait for them rather than trusting the load.
        for _ in range(20):
            text = await page.inner_text("body")
            if ACES.search(text):
                return text
            await page.wait_for_timeout(500)
        return await page.inner_text("body")
    except Exception as exc:  # noqa: BLE001 - a missing page is normal
        print(f"  ! {url}: {exc}")
        return None
    finally:
        await page.close()


def parse(text):
    aces = ACES.search(text or "")
    serves = FIRST_SERVE.findall(text or "")
    if not aces or len(serves) < 2:
        return None
    try:
        total_aces = int(aces.group(1)) + int(aces.group(2))
        points = int(serves[0][1]) + int(serves[1][1])
    except (TypeError, ValueError):
        return None
    if points <= 0:
        return None
    return total_aces, points


async def main():
    from playwright.async_api import async_playwright

    payload = json.load(open(LIVE))
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    payload["generated"] = now

    async with async_playwright() as pw:
        browser = await pw.chromium.launch(args=["--no-sandbox"])
        for event in payload.get("events", []):
            event_id = event.get("atpId")
            year = event.get("s")
            if not event_id:
                print(f"{event['t']}: no ATP id, skipped")
                continue

            aces_total = points_total = 0
            found = 0
            # Match ids are sparse: ms019 and ms031 exist while ms001..ms008 may not, so the
            # walk covers the whole range instead of stopping at the first run of misses.
            for n in range(1, MAX_MATCH + 1):
                match_id = f"ms{n:03d}"
                url = (f"https://www.atptour.com/en/scores/stats-centre/archive/"
                       f"{year}/{event_id}/{match_id}")
                text = await render(browser, url)
                parsed = parse(text)
                if parsed is None:
                    continue
                found += 1
                aces_total += parsed[0]
                points_total += parsed[1]
                if found == 1:
                    print(f"  first match read at {match_id}: {parsed[0]} aces in {parsed[1]} points")
                elif found % 5 == 0:
                    print(f"  {found} matches read (last {match_id})")

            event["asOf"] = now
            if found < MIN_MATCHES or points_total <= 0:
                event.pop("speed", None)
                event.pop("ace", None)
                event["matches"] = found or None
                print(f"{event['t']}: {found} matches with stats - below the {MIN_MATCHES} "
                      f"needed, so no rating is written")
                continue

            rate = aces_total / points_total
            event["matches"] = found
            event["ace"] = round(rate * 100, 1)
            event["speed"] = round(rate / BASELINE, 3)
            print(f"{event['t']}: {found} matches, {aces_total} aces in {points_total} service "
                  f"points = {rate * 100:.2f}%, rating {event['speed']}")

        await browser.close()

    with open(LIVE, "w") as handle:
        json.dump(payload, handle, indent=2)
        handle.write("\n")


if __name__ == "__main__":
    asyncio.run(main())
