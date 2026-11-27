#!/usr/bin/env python3
"""One-time inspection of the ATP-official 1999 Düsseldorf event results page."""
from __future__ import annotations

import asyncio
import json
from pathlib import Path
from playwright.async_api import async_playwright

URL = "https://www.atptour.com/en/scores/archive/dusseldorf/615/1999/results"

async def main() -> None:
    result: dict = {"url": URL, "responses": [], "errors": []}
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        page = await browser.new_page(
            viewport={"width": 1440, "height": 1100},
            user_agent="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                       "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        )
        page.on("pageerror", lambda error: result["errors"].append(str(error)))
        responses = []
        page.on("response", lambda response: responses.append(response))
        try:
            response = await page.goto(URL, wait_until="domcontentloaded", timeout=90000)
            result["navigationStatus"] = response.status if response else None
        except Exception as exc:
            result["navigationError"] = f"{type(exc).__name__}: {exc}"
        try:
            await page.wait_for_load_state("networkidle", timeout=30000)
        except Exception:
            pass
        await page.wait_for_timeout(10000)
        result["finalUrl"] = page.url
        result["title"] = await page.title()
        result["bodyText"] = await page.locator("body").inner_text(timeout=30000)
        result["html"] = await page.content()
        result["scriptUrls"] = await page.locator("script[src]").evaluate_all(
            "(nodes) => nodes.map(n => n.src)"
        )
        result["links"] = await page.locator("a[href]").evaluate_all(
            "(nodes) => nodes.map(n => ({text:(n.innerText||'').trim(), href:n.href}))"
        )
        for item in responses:
            url = item.url
            if "atptour.com" not in url:
                continue
            entry = {"url": url, "status": item.status}
            try:
                entry["contentType"] = item.headers.get("content-type", "")
                if "json" in entry["contentType"].lower():
                    body = await item.text()
                    if len(body) <= 2_000_000:
                        entry["body"] = body
                    else:
                        entry["bodyLength"] = len(body)
            except Exception as exc:
                entry["readError"] = str(exc)
            result["responses"].append(entry)
        await page.screenshot(path="atp-dusseldorf-1999.png", full_page=True)
        await browser.close()
    Path("atp_dusseldorf_1999_probe.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print("ATP official event probe complete")
    print("navigationStatus:", result.get("navigationStatus"))
    print("finalUrl:", result.get("finalUrl"))
    print("title:", result.get("title"))
    print("ATP responses:", len(result["responses"]))
    print("page text:")
    print(result.get("bodyText", "")[:20000])

if __name__ == "__main__":
    asyncio.run(main())
