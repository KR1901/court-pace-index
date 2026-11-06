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

ACES = re.compile(
    r"(?:([0-9]+)\s*Aces\s*([0-9]+)\s*Aces|Aces\s*([0-9]+)\s*Aces\s*([0-9]+))",
    re.I,
)
FIRST_SERVE = re.compile(
    r"(?:([0-9]+)/([0-9]+)\s*\([0-9]+%\)\s*First serve|"
    r"First serve\s*([0-9]+)/([0-9]+)\s*\([0-9]+%\))",
    re.I,
)
STATS_LINK = re.compile(
    r"stats[-/]?centre/(archive|live)/(\d{4})/(\d+)/((?:ms|md)\d+)", re.I
)


USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)


async def render(context, url):
    page = await context.new_page()
    try:
        await page.goto(url, wait_until="domcontentloaded", timeout=30000)
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


def describe(text):
    """A short, honest account of what the page actually returned."""
    if not text:
        return "empty"
    return f"{len(text)} chars, aces={bool(ACES.search(text))}, head={text[:80]!r}"


def parse(text):
    aces = ACES.search(text or "")
    serves = FIRST_SERVE.findall(text or "")
    if not aces or len(serves) < 2:
        return None
    try:
        pair = (aces.group(1), aces.group(2)) if aces.group(1) else (aces.group(3), aces.group(4))
        total_aces = int(pair[0]) + int(pair[1])
        points = sum(int(row[1] or row[3]) for row in serves[:2])
    except (TypeError, ValueError):
        return None
    if points <= 0:
        return None
    return total_aces, points


async def discover_completed_singles(context, event):
    """Use the event results page as the authoritative list; never guess match IDs."""
    event_id = str(event.get("atpId") or "")
    year = str(event.get("s") or "")
    slug = event.get("slug") or event.get("t")
    if not event_id or not year or not slug:
        return []

    url = f"https://www.atptour.com/en/scores/current/{slug}/{event_id}/results"
    page = await context.new_page()
    try:
        await page.goto(url, wait_until="domcontentloaded", timeout=45000)
        previous = None
        stable_polls = 0
        anchors = []
        for poll in range(90):
            await page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
            anchors = await page.locator("a").evaluate_all(
                "nodes => nodes.map(a => [a.href, a.getAttribute('href'), "
                "a.getAttribute('data-href'), a.getAttribute('data-url')].join(' '))"
            )
            snapshot = "\n".join(anchors)
            stable_polls = stable_polls + 1 if snapshot == previous else 0
            previous = snapshot
            # Mirror the app's minimum settle window and require repeated stable snapshots.
            if poll >= 16 and stable_polls >= 4:
                break
            await page.wait_for_timeout(500)

        # A finished match can still be linked through ATP's /live/ stats route while its
        # tournament is underway. Preserve the discovered route; archive/live is not completion.
        by_id = {}
        for anchor in anchors:
            for match in STATS_LINK.finditer(anchor):
                section, link_year, link_event, match_id = match.groups()
                if (section.lower() not in ("archive", "live") or link_year != year or
                        link_event != event_id or not match_id.lower().startswith("ms")):
                    continue
                match_id = match_id.lower()
                if match_id not in by_id or section.lower() == "archive":
                    by_id[match_id] = section.lower()
        links = [(section, match_id) for match_id, section in by_id.items()]
        print(f"{event.get('t')}: {len(links)} completed singles links on results page")
        return links
    except Exception as exc:  # noqa: BLE001 - the ATP results page may be temporarily unavailable
        print(f"  ! results page {url}: {exc}")
        return []
    finally:
        await page.close()

async def probe():
    """Render a few known ids and report exactly what comes back. Diagnostic only."""
    from playwright.async_api import async_playwright
    async with async_playwright() as pw:
        browser = await pw.chromium.launch(args=["--no-sandbox", "--disable-blink-features=AutomationControlled"])
        context = await browser.new_context(user_agent=USER_AGENT, locale="en-GB")
        await context.add_init_script("Object.defineProperty(navigator,'webdriver',{get:()=>undefined})")
        for path in ("archive", "live"):
            for match_id in ("ms019", "ms030", "ms031"):
                url = f"https://www.atptour.com/en/scores/stats-centre/{path}/2026/352/{match_id}"
                text = await render(context, url)
                print(f"{path}/{match_id}: {describe(text)}")
        await browser.close()


async def main():
    from playwright.async_api import async_playwright

    payload = json.load(open(LIVE))
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    payload["generated"] = now

    async with async_playwright() as pw:
        # A plain headless client gets the empty shell: the site serves real content only to
        # something that looks like a browser, so the automation flags and UA are normalised.
        browser = await pw.chromium.launch(args=[
            "--no-sandbox",
            "--disable-blink-features=AutomationControlled",
        ])
        context = await browser.new_context(
            user_agent=USER_AGENT,
            viewport={"width": 1280, "height": 900},
            locale="en-GB",
        )
        await context.add_init_script(
            "Object.defineProperty(navigator, 'webdriver', {get: () => undefined})"
        )
        for event in payload.get("events", []):
            event_id = event.get("atpId")
            year = event.get("s")
            if not event_id:
                print(f"{event['t']}: no ATP id, skipped")
                continue

            match_links = await discover_completed_singles(context, event)
            aces_total = points_total = 0
            found = 0
            described = 0
            for section, match_id in match_links:
                other = "live" if section == "archive" else "archive"
                parsed = None
                for route in (section, other):
                    url = (f"https://www.atptour.com/en/scores/stats-centre/{route}/"
                           f"{year}/{event_id}/{match_id}")
                    text = await render(context, url)
                    parsed = parse(text)
                    if parsed is not None:
                        break
                if parsed is None:
                    if described < 3:
                        described += 1
                        print(f"  {match_id}: {describe(text)}")
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
    if "--probe" in sys.argv:
        asyncio.run(probe())
    else:
        asyncio.run(main())
