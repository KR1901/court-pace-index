#!/usr/bin/env python3
"""Temporary one-time probe of ATP's official Results Archive DOM."""
import asyncio, json, re
from playwright.async_api import async_playwright

UA=("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
PATTERN=re.compile(r"/en/scores/archive/([^/?#]+)/([0-9]+)/([0-9]{4})/results",re.I)

async def extract(page, year, include_all=False):
  anchors=await page.locator("a").evaluate_all("""nodes=>nodes.map(a=>{
    let li=a.closest('li');
    return {href:a.href,text:(a.innerText||a.textContent||'').trim(),
      card:li?(li.innerText||'').trim().replace(/\\s+/g,' ').slice(0,550):''};
  })""")
  events=[]
  for a in anchors:
    m=PATTERN.search(a["href"] or "")
    if m and int(m.group(3))==year:
      events.append({"slug":m.group(1),"atpId":m.group(2),"year":year,
        "card":a["card"],"href":a["href"]})
  selects=await page.locator("select").evaluate_all("""nodes=>nodes.map((s,i)=>({
    i,name:s.name,id:s.id,cls:String(s.className||''),
    value:s.value,options:[...s.options].slice(0,120).map(o=>({text:o.textContent.trim(),value:o.value}))
  }))""")
  body=await page.locator("body").inner_text()
  return {"year":year,"url":page.url,"title":await page.title(),"bodyLength":len(body),
    "verification":"security verification" in body.lower() or "you have been blocked" in body.lower(),
    "eventCount":len(events),"events":events if include_all else events[:4],
    "selects":selects,"bodyHead":body[:700]}

async def main():
  async with async_playwright() as p:
    browser=await p.chromium.launch(args=["--no-sandbox","--disable-blink-features=AutomationControlled"])
    context=await browser.new_context(user_agent=UA,locale="en-GB",viewport={"width":1440,"height":1100})
    await context.add_init_script("Object.defineProperty(navigator,'webdriver',{get:()=>undefined})")
    page=await context.new_page()
    for url in ("https://www.atptour.com/en/scores/results-archive?year=1991",
                "https://www.atptour.com/en/scores/results-archive?year=2026",
                "https://www.atptour.com/en/scores/results-archive?year=1991"):
      try:
        response=await page.goto(url,wait_until="domcontentloaded",timeout=45000)
        await page.wait_for_timeout(1200)
        result=await extract(page,int(url.split("year=")[-1]),include_all=False)
        result["status"]=response.status if response else None
        print("INITIAL",json.dumps(result,ensure_ascii=False),flush=True)
        if result["eventCount"]>0: break
      except Exception as exc:
        print("NAVERROR",repr(exc),flush=True)
    for year in (1992,1993):
      try:
        selects=await page.locator("select").evaluate_all("""nodes=>nodes.map((s,i)=>({
          i,labels:[...s.options].map(o=>o.textContent.trim()),values:[...s.options].map(o=>o.value)
        }))""")
        selected=None
        for s in selects:
          i=s["i"]
          if str(year) in s["labels"] or str(year) in s["values"]:
            loc=page.locator("select").nth(i)
            if str(year) in s["values"]:
              await loc.select_option(value=str(year),timeout=8000)
            else:
              await loc.select_option(label=str(year),timeout=8000)
            selected=i
            await page.wait_for_timeout(1800)
            break
        if selected is None:
          raise RuntimeError(f"no year select option for {year}; options={selects}")
        result=await extract(page,year,include_all=False)
        result["selectedYearControl"]=selected
        print("SELECTED",json.dumps(result,ensure_ascii=False),flush=True)
      except Exception as exc:
        print("SELECTERROR",year,repr(exc),flush=True)
    await browser.close()

if __name__=="__main__":
  asyncio.run(main())
