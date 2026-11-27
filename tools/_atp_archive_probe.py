#!/usr/bin/env python3
"""Temporary one-time probe of ATP's official Results Archive DOM."""
import asyncio, json, re
from playwright.async_api import async_playwright

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
PATTERN = re.compile(r"/en/scores/archive/([^/?#]+)/([0-9]+)/([0-9]{4})/results", re.I)

async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(args=["--no-sandbox", "--disable-blink-features=AutomationControlled"])
        context = await browser.new_context(user_agent=UA, locale="en-GB", viewport={"width": 1440, "height": 1100})
        await context.add_init_script("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})")
        for year in (1991, 2026):
            page = await context.new_page()
            url = f"https://www.atptour.com/en/scores/results-archive?year={year}"
            try:
                response = await page.goto(url, wait_until="domcontentloaded", timeout=60000)
                for _ in range(30):
                    await page.wait_for_timeout(500)
                    body = await page.locator("body").inner_text()
                    if "Results Archive" in body and len(body) > 1000:
                        break
                anchors = await page.locator("a").evaluate_all("""nodes => nodes.map(a => ({
                    href: a.href,
                    text: (a.innerText || a.textContent || '').trim().replace(/\\s+/g, ' '),
                    html: a.outerHTML.slice(0, 1200),
                    parent: a.parentElement ? a.parentElement.innerText.slice(0, 300) : ''
                }))""")
                events = []
                for a in anchors:
                    m = PATTERN.search(a["href"] or "")
                    if m and int(m.group(3)) == year:
                        events.append({"slug":m.group(1), "atpId":m.group(2), "year":m.group(3),
                                       "anchorText":a["text"], "parentText":a["parent"],
                                       "html":a["html"], "href":a["href"]})
                print("PROBE", json.dumps({"year":year, "status":response.status if response else None,
                     "url":page.url, "bodyLength":len(body), "events":events[:12],
                     "bodyHead":body[:1600]}, ensure_ascii=False))
            except Exception as exc:
                print("ERROR", year, repr(exc))
            finally:
                await page.close()
        await browser.close()

if __name__ == "__main__":
    asyncio.run(main())
