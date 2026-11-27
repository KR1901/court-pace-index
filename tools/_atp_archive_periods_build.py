#!/usr/bin/env python3
"""Build the one-time registry from ATP official Results Archive date cards."""
from __future__ import annotations
import argparse, json, re, sys, unicodedata
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta
from difflib import SequenceMatcher
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(Path(__file__).resolve().parent))
import compute_speed
from atp_event_periods import audit_source_coverage, load_event_periods

CARDS=ROOT/"tools"/"_atp_archive_cards.json"
OUT=ROOT/"data"/"atp_event_periods.json"
MONTHS={n.casefold():i for i,n in enumerate(
 ["January","February","March","April","May","June","July","August","September","October","November","December"],1)}
TARGET_LEVELS=set(compute_speed.TARGET_LEVELS)
CITY_TZ={
 "adelaide":"Australia/Adelaide","wellington":"Pacific/Auckland","sydney":"Australia/Sydney",
 "auckland":"Pacific/Auckland","melbourne":"Australia/Melbourne","perth":"Australia/Perth",
 "doha":"Asia/Qatar","chennai":"Asia/Kolkata","milan":"Europe/Rome","marseille":"Europe/Paris",
 "vinadelmar":"America/Santiago","zagreb":"Europe/Zagreb","rotterdam":"Europe/Amsterdam",
 "sanjose":"America/Los_Angeles","costadosauipe":"America/Bahia","memphis":"America/Chicago",
 "buenosaires":"America/Argentina/Buenos_Aires","saopaulo":"America/Sao_Paulo","dubai":"Asia/Dubai",
 "indianwells":"America/Los_Angeles","miami":"America/New_York","montecarlo":"Europe/Monaco",
 "estoril":"Europe/Lisbon","hongkong":"Asia/Hong_Kong","orlando":"America/New_York",
 "tokyo":"Asia/Tokyo","barcelona":"Europe/Madrid","nice":"Europe/Paris","seoul":"Asia/Seoul",
 "singapore":"Asia/Singapore","madrid":"Europe/Madrid","munich":"Europe/Berlin","hamburg":"Europe/Berlin",
 "rome":"Europe/Rome","dusseldorf":"Europe/Berlin","paris":"Europe/Paris","london":"Europe/London",
 "florence":"Europe/Rome","rosmalen":"Europe/Amsterdam","genoa":"Europe/Rome","manchester":"Europe/London",
 "gstaad":"Europe/Zurich","bastad":"Europe/Stockholm","bstad":"Europe/Stockholm","newport":"America/New_York",
 "stuttgart":"Europe/Berlin","washington":"America/New_York","montreal":"America/Toronto",
 "toronto":"America/Toronto","hilversum":"Europe/Amsterdam","kitzbuhel":"Europe/Vienna",
 "losangeles":"America/Los_Angeles","sanmarino":"Europe/San_Marino","cincinnati":"America/New_York",
 "prague":"Europe/Prague","indianapolis":"America/Indiana/Indianapolis","newhaven":"America/New_York",
 "longisland":"America/New_York","schenectady":"America/New_York","newyork":"America/New_York",
 "bordeaux":"Europe/Paris","brasilia":"America/Sao_Paulo","geneva":"Europe/Zurich","basel":"Europe/Zurich",
 "palermo":"Europe/Rome","brisbane":"Australia/Brisbane","delraybeach":"America/New_York","antalya":"Europe/Istanbul","nursultan":"Asia/Almaty","sardinia":"Europe/Rome","eastbourne":"Europe/London","nottingham":"Europe/London","newhaven":"America/New_York","longisland":"America/New_York","porto":"Europe/Lisbon","essen":"Europe/Berlin","pinehurst":"America/New_York","coralsprings":"America/New_York","toulouse":"Europe/Paris","athens":"Europe/Athens",
 "berlin":"Europe/Berlin","telaviv":"Asia/Jerusalem","lyon":"Europe/Paris","vienna":"Europe/Vienna",
 "stockholm":"Europe/Stockholm","guaruja":"America/Sao_Paulo","buzios":"America/Sao_Paulo",
 "antwerp":"Europe/Brussels","santiago":"America/Santiago","moscow":"Europe/Moscow",
 "brighton":"Europe/London","stpetersburg":"Europe/Moscow","taipei":"Asia/Taipei","bangkok":"Asia/Bangkok",
 "kualalumpur":"Asia/Kuala_Lumpur","jakarta":"Asia/Jakarta","johannesburg":"Africa/Johannesburg",
 "quito":"America/Guayaquil","pune":"Asia/Kolkata","cordoba":"America/Argentina/Cordoba",
 "bucharest":"Europe/Bucharest","marrakech":"Africa/Casablanca","montevideo":"America/Montevideo",
 "valencia":"Europe/Madrid","umag":"Europe/Zagreb","loscabos":"America/Mazatlan",
 "winstonsalem":"America/New_York","chengdu":"Asia/Shanghai","hangzhou":"Asia/Shanghai",
 "almaty":"Asia/Almaty","brussels":"Europe/Brussels","metz":"Europe/Paris","turin":"Europe/Rome","torino":"Europe/Rome","bologna":"Europe/Rome","frankfurt":"Europe/Berlin","hanover":"Europe/Berlin","lisbon":"Europe/Lisbon","houston":"America/Chicago",
 "halle":"Europe/Berlin","bologna":"Europe/Rome","torino":"Europe/Rome","turin":"Europe/Rome","stpolten":"Europe/Vienna","portschach":"Europe/Vienna",
 "meran":"Europe/Rome","mallorca":"Europe/Madrid","cologne":"Europe/Berlin",
 "suncity":"Africa/Johannesburg","bermuda":"Atlantic/Bermuda",
 "bogota":"America/Bogota","guadalajara":"America/Mexico_City","monterrey":"America/Monterrey",
 "acapulco":"America/Mexico_City","mexicocity":"America/Mexico_City","puebla":"America/Mexico_City",
 "houston":"America/Chicago","dallas":"America/Chicago","charlotte":"America/New_York",
 "atlanta":"America/New_York","philadelphia":"America/New_York","chicago":"America/Chicago",
 "sanfrancisco":"America/Los_Angeles","scottsdale":"America/Phoenix","lasvegas":"America/Los_Angeles",
 "oahu":"Pacific/Honolulu","honolulu":"Pacific/Honolulu","copenhagen":"Europe/Copenhagen",
 "oslo":"Europe/Oslo","helsinki":"Europe/Helsinki","budapest":"Europe/Budapest",
 "belgrade":"Europe/Belgrade","sofia":"Europe/Sofia","warsaw":"Europe/Warsaw","istanbul":"Europe/Istanbul",
 "riyadh":"Asia/Riyadh","beijing":"Asia/Shanghai","shanghai":"Asia/Shanghai","jeddah":"Asia/Riyadh","guangzhou":"Asia/Shanghai",
 "manila":"Asia/Manila","jaipur":"Asia/Kolkata","delhi":"Asia/Kolkata","sopot":"Europe/Warsaw"
}
ALIASES={
 "frenchopen":{"rolandgarros"},"rolandgarros":{"frenchopen"},
 "sydneyoutdoor":{"sydney"},"sydneyindoor":{"sydney"},
 "miamimasters":{"miami"},"madridmasters":{"madrid"},"romemasters":{"rome"},
 "canadamasters":{"canada"},"cincinnatimasters":{"cincinnati"},
 "shanghaimasters":{"shanghai"},"parismasters":{"paris"},
 "queensclub":{"hsbcchampionships","london"},
 "tokyooutdoor":{"tokyo"},"tokyoindoor":{"tokyo"},
 "stuttgartoutdoor":{"stuttgart"},"stuttgartindoor":{"stuttgart"},
 "genova":{"genoa"},"stpoelten":{"stpolten"},"bstad":{"bastad"},"bastad":{"bstad"},
 "indianwellsmaster":{"indianwells"},"miamimaster":{"miami"},"romemaster":{"rome"},
 "greatoceanroadopen":{"melbourne1"},"murrayriveropen":{"melbourne2"},
 "nextgenfinals":{"nextgenatpfinals","nextgen"},"nextgenatpfinals":{"nextgenfinals","nextgen"},
 "nittoatpfinals":{"tourfinals","atpfinals"},"masterscup":{"tourfinals","atpfinals"},
 "canadamasters":{"canada"},"parismasters":{"paris"},"tourfinals":{"atptourworldchampionship","tennismasterscup","atpfinals"},
 "atpfinals":{"tourfinals","atptourworldchampionship","tennismasterscup"},
 "tennismasterscup":{"tourfinals","atpfinals"},
 "atptourworldchampionship":{"tourfinals","atpfinals"},
 "scottsdale":{"lasvegas"},"lasvegas":{"scottsdale"},
 "charlotte":{"houston"},"houston":{"charlotte"},
 "sanfrancisco":{"dallas"},"dallas":{"sanfrancisco"}
}

FINALS_ZONES = {
    **{year: "Europe/Berlin" for year in range(1991, 2000)},
    2000: "Europe/Lisbon",
    2001: "Australia/Sydney",
    2002: "Asia/Shanghai",
    2003: "America/Chicago",
    2004: "America/Chicago",
    **{year: "Asia/Shanghai" for year in range(2005, 2009)},
    **{year: "Europe/London" for year in range(2009, 2021)},
    **{year: "Europe/Rome" for year in range(2021, 2027)},
}

def finals_zone(year):
    zone = FINALS_ZONES.get(year)
    if zone:
        ZoneInfo(zone)
    return zone

def norm(s):
 s=unicodedata.normalize("NFKD",str(s or "")).casefold()
 return "".join(c for c in s if c.isalnum())

def toks(s):
 return {x for x in re.findall(r"[a-z0-9]+",unicodedata.normalize("NFKD",str(s or "")).casefold()) if x}

def parse_range(s):
 s=re.sub(r"\s+"," ",s.strip())
 ms="|".join(MONTHS)
 full=re.fullmatch(rf"(\d{{1,2}})\s+({ms}),?\s*(\d{{4}})?\s*-\s*(\d{{1,2}})\s+({ms}),?\s*(\d{{4}})",s,re.I)
 short=re.fullmatch(rf"(\d{{1,2}})\s*-\s*(\d{{1,2}})\s+({ms}),?\s*(\d{{4}})",s,re.I)
 if full:
  sd,sm,sy,ed,em,ey=full.groups()
  y=int(ey); smn=MONTHS[sm.casefold()]; emn=MONTHS[em.casefold()]
  sy=int(sy) if sy else (y-1 if smn>emn else y)
  return date(sy,smn,int(sd)),date(y,emn,int(ed))
 if short:
  sd,ed,em,ey=short.groups(); y=int(ey); m=MONTHS[em.casefold()]
  return date(y,m,int(sd)),date(y,m,int(ed))
 raise ValueError("unrecognised ATP date range "+repr(s))

def score_name(source,card):
 sn,cn=norm(source),norm(card)
 if not sn or not cn: return 0.0
 if sn in cn or cn in sn: return 1.0
 st,ct=toks(source),toks(card)
 if st and st.issubset(ct): return 1.0
 if any(a in cn or a in ct for a in ALIASES.get(sn,set())): return 0.98
 if sn in ALIASES.get(cn,set()): return 0.98
 if not st: return 0.0
 overlap=len(st&ct)/len(st)
 if len(st)==1 and overlap<1: return 0.0
 return max(overlap,SequenceMatcher(None,sn,cn).ratio()*0.85)

def city_index():
 try:
  import geonamescache
  from timezonefinder import TimezoneFinder
  gc=geonamescache.GeonamesCache()
  countries={norm(v.get("name","")):k for k,v in gc.get_countries().items()}
  cities=defaultdict(list)
  for c in gc.get_cities().values(): cities[norm(c.get("name",""))].append(c)
  return cities,countries,TimezoneFinder(in_memory=True)
 except Exception as exc:
  print("warning GeoNames/timezone fallback unavailable:",exc,file=sys.stderr)
  return {},{},None

def country_code(s,country_names):
 aliases={"greatbritain":"GB","england":"GB","unitedstates":"US","unitedstatesofamerica":"US",
  "usa":"US","uk":"GB","czechrepublic":"CZ","korea":"KR","southkorea":"KR","russia":"RU",
  "turkiye":"TR","turkey":"TR","uae":"AE","unitedarabemirates":"AE"}
 return aliases.get(norm(s)) or country_names.get(norm(s))

def venue_zone(card,cindex):
 cities,countries,finder=cindex
 left=card.strip()
 if " -- " in left: left=left.split(" -- ",1)[1].strip()
 pieces=left.rsplit(",",1)
 venue=pieces[0].strip()
 country=pieces[1].strip() if len(pieces)==2 else ""
 cc=country_code(country,countries)
 words=re.findall(r"[a-z0-9]+",unicodedata.normalize("NFKD",venue).casefold())
 for n in range(min(5,len(words)),0,-1):
  key="".join(words[-n:])
  manual=CITY_TZ.get(key)
  possible=cities.get(key,[])
  if cc: possible=[x for x in possible if x.get("countrycode")==cc]
  if manual:
   try: ZoneInfo(manual); return key,manual
   except Exception: pass
  if possible and finder:
   zones=[]
   for c in possible:
    try: z=finder.timezone_at(lng=float(c["longitude"]),lat=float(c["latitude"]))
    except Exception: z=None
    if z: zones.append((z,int(c.get("population") or 0)))
   distinct={z for z,_ in zones}
   if len(distinct)==1: return key,next(iter(distinct))
   if len(distinct)>1:
    zones.sort(key=lambda x:x[1],reverse=True)
    if zones and (len(zones)==1 or zones[0][1]>=4*max(1,zones[1][1])):
     return key,zones[0][0]
 return None,None

def representative(rows):
 counts=Counter((str(r.get("tourney_name") or "").strip(),
  str(r.get("tourney_date") or "").strip(),str(r.get("tourney_level") or "").strip()) for r in rows)
 n,d,l=counts.most_common(1)[0][0]
 return {"tourney_id":str(rows[0].get("tourney_id") or "").strip(),
  "tourney_name":n,"tourney_date":d,"tourney_level":l}

def main():
 ap=argparse.ArgumentParser()
 ap.add_argument("--write",action="store_true")
 ap.add_argument("--report",type=Path,default=ROOT/"atp_event_periods_build_audit.json")
 args=ap.parse_args()
 global city_index_value
 ci=city_index()
 payload=json.loads(CARDS.read_text(encoding="utf-8"))
 cards=defaultdict(list); unresolved_zones=[]; bad_dates=[]
 for raw in payload["records"]:
  try: start,end=parse_range(raw["officialDateText"])
  except Exception as e:
   bad_dates.append({"season":raw.get("season"),"card":raw.get("cardText"),"date":raw.get("officialDateText"),"error":str(e)})
   continue
  city,zone=venue_zone(raw["cardText"],ci)
  # ATP annual Results Archive may omit Finals city; use ATP's official historic venue table.
  title_norm=norm(raw["cardText"])
  if not zone and any(x in title_norm for x in ("tourworldchampionship","tourfinals","masterscup","nittoatpfinals")):
   zone=finals_zone(int(raw["season"]))
  if not zone:
   unresolved_zones.append({"season":raw["season"],"cardText":raw["cardText"],"dateText":raw["officialDateText"],"cityGuess":city})
  cards[int(raw["season"])].append({**raw,"start":start,"end":end,"city":city,"timeZone":zone})

 records=[]; coverage_rows=[]; details=[]
 for year in range(1991,datetime.utcnow().year+1):
  print(f"Loading source IDs for {year}...",flush=True)
  try: rows=compute_speed.load(year)
  except Exception as e:
   coverage_rows.append({"season":year,"error":f"{type(e).__name__}: {e}"})
   print(f"SOURCE_LOAD_FAILED {year}: {e}",flush=True); continue
  grouped=defaultdict(list)
  for row in rows:
   if row.get("tourney_level") in TARGET_LEVELS and row.get("tourney_id"):
    grouped[str(row["tourney_id"])].append(row)
  events=[]
  for sid,rs in grouped.items():
   e=representative(rs)
   if not re.fullmatch(r"\d{8}",e["tourney_date"]): continue
   try: e["_date"]=datetime.strptime(e["tourney_date"],"%Y%m%d").date()
   except ValueError: continue
   events.append(e)

  mapped=set(); unmatched=[]; unmatched_events=[]; mapped_levels=Counter()
  for e in events:
   is_d=e["tourney_level"]=="D"; sname=e["tourney_name"]; sid=e["tourney_id"]
   is_final=bool(re.search(r"\bF\b|final",sname,re.I) or "FLS" in re.split(r"[^A-Z0-9]+",sid.upper()))
   scored=[]
   for c in cards.get(year,[]):
    # Match using the legacy source's start-of-week date; store exact ATP dates.
    tolerance_days = 7 if any(k in norm(c["cardText"]) for k in ("finals","worldchampionship","masterscup")) else 3
    if not c["start"]-timedelta(days=tolerance_days)<=e["_date"]<=c["end"] or not c["timeZone"]: continue
    if is_d:
     if "davis" not in toks(c["cardText"]) or not is_final: continue
     score=1.0
    else:
     if "davis" in toks(c["cardText"]): continue
     score=score_name(sname,c["cardText"])
     if score < (0.60 if len(toks(sname))>1 else 0.99): continue
    scored.append((score,c))
   scored.sort(key=lambda x:x[0],reverse=True)
   if not scored:
    unmatched.append(sid)
    unmatched_events.append({"sourceEventId":sid,"name":sname,"date":e["tourney_date"],"level":e["tourney_level"]})
    continue
   best,card=scored[0]
   if len(scored)>1 and best-scored[1][0]<0.08:
    unmatched.append(sid)
    unmatched_events.append({"sourceEventId":sid,"name":sname,"date":e["tourney_date"],"level":e["tourney_level"],"reason":"ambiguous ATP card"})
    continue
   if sid in mapped:
    unmatched.append(sid)
    unmatched_events.append({"sourceEventId":sid,"name":sname,"date":e["tourney_date"],"level":e["tourney_level"],"reason":"duplicate source mapping"})
    continue
   tid=compute_speed._row_tournament_id(e) or ("unmapped-"+norm(sname)[:48])
   records.append({
    "season":year,"eventId":f"atp-{year}-{sid}","tournamentId":tid,
    "sourceEventIds":[sid],"startDate":card["start"].isoformat(),
    "endDate":card["end"].isoformat(),"timeZone":card["timeZone"],
    "sourceUrl":card["sourceUrl"],"sourceKind":"results_archive",
    "dateStatus":"actual","verifiedAt":"2026-11-27"
   })
   mapped.add(sid); mapped_levels[e["tourney_level"]]+=1
   details.append({"season":year,"sourceEventId":sid,"sourceEventName":sname,
    "sourceDate":e["_date"].isoformat(),"atpCard":card["cardText"],
    "officialStart":card["start"].isoformat(),"officialEnd":card["end"].isoformat(),
    "timeZone":card["timeZone"],"score":round(best,3)})

  coverage={"dateCoverageComplete":False}
  if records:
   tmp=ROOT/"atp_event_periods.partial.json"
   tmp.write_text(json.dumps({"schemaVersion":1,"records":records},indent=2)+"\n",encoding="utf-8")
   try:
    reg=load_event_periods(tmp,require_non_empty=True)
    coverage=audit_source_coverage(rows,year,reg)
   except Exception as e: coverage={"dateCoverageComplete":False,"error":f"{type(e).__name__}: {e}"}
   finally: tmp.unlink(missing_ok=True)
  coverage_rows.append({
   "season":year,"sourceEventCount":len(events),"mappedCount":len(mapped),
   "sourceEventsByLevel":dict(Counter(e["tourney_level"] for e in events)),
   "mappedEventsByLevel":dict(mapped_levels),
   "unmatchedEventsByLevel":dict(Counter(e.get("level","?") for e in unmatched_events)),
   "unmatchedCount":len(unmatched),"unmatchedSourceEventIds":unmatched[:100],
   "unmatchedEvents":unmatched_events[:250],
   "dateCoverageComplete":coverage.get("dateCoverageComplete",False),
   "identifiedSourceEvents":coverage.get("identifiedSourceEvents"),
   "coveredSourceEvents":coverage.get("coveredSourceEvents"),
   "coverageMissingSourceEventIds":coverage.get("missingSourceEventIds",[])[:100]
  })
  print(f"{year}: mapped {len(mapped)}/{len(events)}; unmatched {len(unmatched)}; coverage={coverage.get('dateCoverageComplete',False)}",flush=True)

 if not records: raise SystemExit("No official event periods mapped; registry left unchanged.")
 tmp=ROOT/"atp_event_periods.final.tmp.json"
 tmp.write_text(json.dumps({"schemaVersion":1,"records":records},indent=2)+"\n",encoding="utf-8")
 load_event_periods(tmp,require_non_empty=True)
 tmp.unlink(missing_ok=True)
 report={"dateSource":"ATP Tour official Results Archive year pages",
  "crosswalk":"Existing match-source IDs used only to associate dates; not used as a date source",
  "verifiedAt":"2026-11-27","officialCardsExtracted":len(payload["records"]),
  "unparsedDateCards":bad_dates,"registryRecordCount":len(records),
  "mappedBySeason":coverage_rows,"unresolvedVenueTimeZones":unresolved_zones[:1000],
  "matchDetails":details}
 args.report.write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
 if args.write:
  OUT.write_text(json.dumps({"schemaVersion":1,"records":records},indent=2)+"\n",encoding="utf-8")
  print(f"Wrote {len(records)} registry rows to {OUT}",flush=True)
 else: print("Dry run; pass --write to persist.",flush=True)
 print(f"Coverage incomplete in {sum(not x.get('dateCoverageComplete',False) for x in coverage_rows)} seasons; {len(unresolved_zones)} cards lack a resolved venue timezone.",flush=True)

if __name__=="__main__":
 main()
