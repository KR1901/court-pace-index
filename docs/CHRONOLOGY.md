# Chronological tournament ratings

## Production chronology contract

The production `python3 tools/compute_speed.py --write` path now uses a strict official-event-period calculation. It no longer uses match-day inference, actual match clocks, the legacy tournament-start date or level-based maximum-duration estimates as a fallback for CALC production history.

Every ATP source tournament edition must resolve by its exact source `tourney_id` to one official date-period record. The record contains the tournament's inclusive venue-local start/end dates, timezone basis, first-party ATP source URL, source kind and verification date. For cards explicitly published by ATP as "Multiple Locations" (or a location-less team event), the reserved timezone value `MULTI_LOCATION` is used instead of inventing a venue. Its UTC envelope starts 14 hours before local-date midnight and ends 12 hours after midnight following the final date, conservatively covering all global civil timezones (UTC+14 through UTC-12).

The engine converts both local calendar boundaries to UTC:
- event start: local midnight on the official start date;
- event completion: local midnight after the inclusive official end date.

A source tournament can enter **either** player-profile training or the season normalisation cohort only when:
- it has a verified `results_archive` / `actual` period;
- its complete UTC end boundary is no later than the target event's UTC start boundary;
- it is not one of the source editions being rated as the target.

Therefore a tournament that overlaps, shares any part of the target week, has an incomparable period, or has only a scheduled calendar period cannot train that rating. The whole source edition is excluded; an early round from a still-running event is not used. The target event never contributes to its own profile or normalisation baseline. If no completed official event can supply a normalisation anchor, the target is not rated; the old target-self normalisation fallback is disabled.

Some app tournament IDs aggregate multiple ATP source editions (for example, separately named editions in a shared week). Each source edition still requires its own ATP registry record. The app target uses the union of those verified periods, and all of those source editions are excluded from training for that aggregate target.

## Date coverage is independent of match/stat eligibility

Before a production write, every standard ATP tour-level singles source edition (Grand Slams, Masters, ATP 500/250 and ATP Finals levels) is audited from 1991 onward, including identified singles with missing or malformed serve statistics. Every source edition must map to one official period. Historical years require an actual Results Archive record; the current season may use an official calendar record marked scheduled until its completed Results Archive period is available. Davis Cup is a separate target-only population, never ATP profile training: its current-season source IDs receive a separate all-ID date audit, while previously validated historical TML Davis Cup snapshots are preserved rather than reconstructed from source archives that collapse tie identities.

A missing source ID, missing event period, duplicate mapping, wrong app tournament mapping, invalid IANA timezone, or unresolved source identity fails the refresh before any production dataset is written. The gate reports source-event date coverage independently of valid aces/service points and match-count eligibility. Standard ATP events are audited for every historical season; the current supported Davis Cup source population is audited separately. Historical overall Davis Cup readings from 2016–2024 are retained only as explicitly marked frozen legacy target snapshots because older archive rows do not preserve tie-level chronology. The compact `data/legacy_davis_cup_snapshots.json` seed preserves the last published values so the first strict-date refresh can recover them even if a prior refresh has already rewritten `data/speed_history.json`. These snapshots are not presented as strict-date recalculations and are never used for standard ATP player-profile training or season normalisation. The separately identified 2026 Final 8 is preserved only from its validated TML source audit.

Once date coverage passes, the existing match-stat and sample rules still apply. A tournament can have a valid official period and still be omitted because it has no eligible serve-stat sample, no usable prior player profiles, or no completed earlier normalisation cohort. These are sample/calculation exclusions, not date-coverage failures.

## Official ATP source and collection permission

The ATP Results Archive publishes historical event date spans, including 1991. The official season calendar publishes scheduled date spans. Results Archive periods are the preferred source for completed editions because schedules can change.

The one-time historical date-card capture for this repository was carried out on the basis of the repository owner's confirmation that the required permission was in place. The temporary retrieval code has since been removed. The permanent app contains only event-period metadata and source links; it does not perform live ATP archive collection.

Official references:
- [ATP Terms and Conditions, section 7 — Prohibited Uses](https://www.atptour.com/en/terms-and-conditions-app)
- [ATP Results Archive — 1991](https://www.atptour.com/en/scores/results-archive?year=1991)
- [ATP Results Archive](https://www.atptour.com/en/scores/results-archive)
- [Official 2026 ATP Tour calendar](https://www.atptour.com/en/news/what-is-the-2026-atp-tour-calendar)

## Importing an authorised event-period export

The registry contract is in `tools/atp_event_periods.schema.json`; the empty starter registry is `data/atp_event_periods.json`.

For future corrections or additions, use an ATP-authorised export or another expressly authorised delivery and convert it to CSV with these columns:

`season,eventId,tournamentId,sourceEventIds,startDate,endDate,timeZone,sourceUrl,sourceKind,dateStatus,verifiedAt`

`sourceEventIds` accepts a semicolon-separated list or a JSON string array. Example command:

```bash
python3 tools/import_atp_event_periods.py --input authorised-atp-event-periods.csv
```

The importer validates every record, source URL, date, timezone and global source-ID uniqueness before atomically updating the JSON registry. It performs no network requests. Use `--replace` only when the input is a complete replacement registry.

## Current repository status

The checked-in `data/atp_event_periods.json` contains **2,655 event periods across 1991–2026**. The one-time ATP archive card capture and its temporary cache, builder, browser probe and workflow have been removed; the application contains only derived event-period metadata and source links, and it does not scrape ATP at runtime.

The previously unresolved source event `1999-615` (World Team Cup, Düsseldorf) is now mapped. ATP's [1999 Results Archive](https://www.atptour.com/en/scores/results-archive?year=1999) confirms the event and its 16 May start, but its displayed end-date field is malformed. The official [ITF World Team Cup 1999 event record](https://www.itftennis.com/en/tournament/world-team-cup/ger/1999/m-tc-1999/draws-and-results/) confirms the competition ran 16–22 May 1999. The registry records that complete period with the Düsseldorf timezone `Europe/Berlin` and includes the ITF URL and an explicit evidence note, so the corrected end date is not misrepresented as a value displayed by ATP. The event's 26 source match rows (21 with valid serve statistics) are covered.

The historical registry now includes source ID `1999-615`; the strict source-event coverage audit can be rerun against the full 1991–2026 source population. Match/stat sample eligibility remains a separate calculation gate.
