#!/usr/bin/env python3
"""Temporary one-time probe of ATP's official Results Archive DOM."""
import asyncio, json, re
from playwright.async_api import async_playwright

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
PATTERN = re.compile(r"/en/scores/archive/([^/?#]+)/([0-9]+)/([0-9]{4})/results", re.I)

async def details(page, year):
    return await page.locator("a").evaluate_all("""nodes => nodes.map(a => {
      const levels=[]; let n=a;
      for(let i=0;i<8 && n;i++,n=n.parentElement) levels.push({
        tag:n.tagName, cls:String(n.className||'').slice(0,160),
        text:(n.innerText||'').trim().replace(/\\s+/g,' ').slice(0,500)});
      return {href:a.href,text:(a.innerText||a.textContent||'').trim(),levels};
    })""")

async def page_result(page, year, mode):
    await page.wait_for_timeout(1200)
    body=await page.locator("body").inner_text()
    anchors=await details(page,year)
    events=[]
    for a in anchors:
      m=PATTERN.search(a["href"] or "")
      if m and int(m.group(3))==year:
        events.append({"slug":m.group(1),"atpId":m.group(2),"year":year,
                       "levels":a["levels"],"href":a["href"]})
    years=[{"text":a["text"],"href":a["href"]} for a in anchors
           if a["text"].strip()==str(year) or str(year) in (a["href"] or "")]
    print("PROBE",json.dumps({"year":year,"mode":mode,"url":page.url,
          "title":await page.title(),"bodyLength":len(body),
          "verification":"security verification" in body.lower(),
          "eventCount":len(events),"events":events[:3],
          "yearLinks":years[:8],"bodyHead":body[:900]},ensure_ascii=False),flush=True)

async def main():
  async with async_playwright() as p:
    browser=await p.chromium.launch(args=["--no-sandbox","--disable-blink-features=AutomationControlled"])
    context=await browser.new_context(user_agent=UA,locale="en-GB",viewport={"width":1440,"height":1100})
    await context.add_init_script("Object.defineProperty(navigator,'webdriver',{get:()=>undefined})")
    page=await context.new_page()
    response=await page.goto("https://www.atptour.com/en/scores/results-archive?year=2026",
                             wait_until="domcontentloaded",timeout=45000)
    await page.wait_for_timeout(2200)
    await page_result(page,2026,"direct-warmup")
    for year in (1991,1992):
      candidates=await page.locator("a").evaluate_all("""nodes=>nodes.filter(a=>
        (a.innerText||a.textContent||'').trim()==='YEAR' ||
        (a.getAttribute('href')||'').includes('year=YEAR'))
        .map(a=>({text:(a.innerText||a.textContent||'').trim(),href:a.href}))""".replace("YEAR",str(year)))
      if candidates:
        try:
          await page.get_by_role("link",name=str(year),exact=True).first.click(timeout=8000)
          await page.wait_for_load_state("domcontentloaded",timeout=25000)
          await page_result(page,year,"clicked-year-selector")
          continue
        except Exception as exc: print("CLICKERROR",year,repr(exc),flush=True)
      try:
        response=await page.goto(f"https://www.atptour.com/en/scores/results-archive?year={year}",
                                 wait_until="domcontentloaded",timeout=35000)
        await page_result(page,year,f"direct-fallback-{response.status if response else 'none'}")
      except Exception as exc: print("NAVERROR",year,repr(exc),flush=True)
    await browser.close()

if __name__=="__main__":
  asyncio.run(main())
