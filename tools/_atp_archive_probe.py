#!/usr/bin/env python3
"""Temporary one-time probe of ATP's official Results Archive DOM."""
import asyncio, json, re
from playwright.async_api import async_playwright

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
PATTERN = re.compile(r"/en/scores/archive/([^/?#]+)/([0-9]+)/([0-9]{4})/results", re.I)

async def extract(page, year):
    anchors = await page.locator("a").evaluate_all("""nodes => nodes.map(a => {
        const levels = [];
        let n = a;
        for (let i = 0; i < 6 && n; i++, n = n.parentElement) {
            levels.push({tag:n.tagName, cls:String(n.className || '').slice(0,180),
              text:(n.innerText || '').trim().replace(/\\s+/g,' ').slice(0,650)});
        }
        return {href:a.href, text:(a.innerText || a.textContent || '').trim().replace(/\\s+/g,' '),
          levels};
    })""")
    events = []
    for a in anchors:
        m = PATTERN.search(a["href"] or "")
        if m and int(m.group(3)) == year:
            events.append({"slug":m.group(1), "atpId":m.group(2), "year":m.group(3),
                           "levels":a["levels"], "href":a["href"]})
    return events

async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(args=["--no-sandbox", "--disable-blink-features=AutomationControlled"])
        context = await browser.new_context(user_agent=UA, locale="en-GB", viewport={"width": 1440, "height": 1100})
        await context.add_init_script("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})")
        page = await context.new_page()
        for year in (2026, 1991, 1992):
            url = f"https://www.atptour.com/en/scores/results-archive?year={year}"
            status = None
            for attempt in range(1, 4):
                try:
                    response = await page.goto(url, wait_until="domcontentloaded", timeout=60000)
                    status = response.status if response else None
                    await page.wait_for_timeout(1800 + attempt * 700)
                    body = await page.locator("body").inner_text()
                    if status == 200 and "Results Archive" in body and len(body) > 1000:
                        break
                    print("RETRY", json.dumps({"year":year,"attempt":attempt,"status":status,
                           "bodyHead":body[:260]}, ensure_ascii=False))
                except Exception as exc:
                    print("NAVERROR", year, attempt, repr(exc))
            events = await extract(page, year) if status == 200 else []
            body = await page.locator("body").inner_text()
            print("PROBE", json.dumps({"year":year, "status":status, "url":page.url,
                  "bodyLength":len(body), "eventCount":len(events), "events":events[:4],
                  "bodyHead":body[:1800]}, ensure_ascii=False))
        await browser.close()

if __name__ == "__main__":
    asyncio.run(main())
