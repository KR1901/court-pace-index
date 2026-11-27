#!/usr/bin/env python3
"""Temporary one-time probe of ATP's official Results Archive DOM."""
import asyncio,json,re
from playwright.async_api import async_playwright
UA=("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
PATTERN=re.compile(r"/en/scores/archive/([^/?#]+)/([0-9]+)/([0-9]{4})/results",re.I)
async def extract(page,year):
  anchors=await page.locator("a").evaluate_all("""nodes=>nodes.map(a=>{
    const li=a.closest('li');return {href:a.href,card:li?(li.innerText||'').trim().replace(/\\s+/g,' ').slice(0,550):''}
  })""")
  events=[]
  for a in anchors:
    m=PATTERN.search(a["href"] or "")
    if m and int(m.group(3))==year:
      events.append({"slug":m.group(1),"atpId":m.group(2),"year":year,
                     "card":a["card"],"href":a["href"]})
  return events
async def main():
  async with async_playwright() as p:
    browser=await p.chromium.launch(args=["--no-sandbox","--disable-blink-features=AutomationControlled"])
    ctx=await browser.new_context(user_agent=UA,locale="en-GB",viewport={"width":1440,"height":1100})
    await ctx.add_init_script("Object.defineProperty(navigator,'webdriver',{get:()=>undefined})")
    page=await ctx.new_page()
    for year in (2026,1992,1992):
      try:
        response=await page.goto(f"https://www.atptour.com/en/scores/results-archive?year={year}",
                                 wait_until="domcontentloaded",timeout=50000)
        await page.wait_for_timeout(1800)
        body=await page.locator("body").inner_text()
        events=await extract(page,year)
        print("PROBE",json.dumps({"year":year,"status":response.status if response else None,
          "url":page.url,"title":await page.title(),"bodyLength":len(body),
          "blocked":("security verification" in body.lower() or "you have been blocked" in body.lower()),
          "eventCount":len(events),"events":events},ensure_ascii=False),flush=True)
        if year==1992 and events: break
      except Exception as exc: print("ERROR",year,repr(exc),flush=True)
    await browser.close()
if __name__=="__main__": asyncio.run(main())
