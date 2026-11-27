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

A missing source ID, missing event period, duplicate mapping, wrong app tournament mapping, invalid IANA timezone, or unresolved source identity fails the refresh before any production dataset is written. The gate reports source-event date coverage independently of valid aces/service points and match-count eligibility.

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

When an ATP-authorised export or another expressly authorised delivery is available, convert it to CSV with these columns:

`season,eventId,tournamentId,sourceEventIds,startDate,endDate,timeZone,sourceUrl,sourceKind,dateStatus,verifiedAt`

`sourceEventIds` accepts a semicolon-separated list or a JSON string array. Example command:

```bash
python3 tools/import_atp_event_periods.py --input authorised-atp-event-periods.csv
```

The importer validates every record, source URL, date, timezone and global source-ID uniqueness before atomically updating the JSON registry. It performs no network requests. Use `--replace` only when the input is a complete replacement registry.

## Current repository status

The strict registry loader, authorised-export importer, source-coverage audit and strict CALC code path are implemented and unit-tested. The checked-in registry is intentionally empty because ATP bulk collection is not authorised under the currently published terms and no permissioned export has been provided.

Consequently, production `--write` remains fail-closed until every standard ATP tour-level event and every current-season supported Davis Cup target source ID has a valid official date period. Match/stat sample eligibility remains a separate calculation gate.

