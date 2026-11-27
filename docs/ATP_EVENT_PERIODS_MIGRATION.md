# ATP official event-period migration plan

## Goal

Make historical CALC chronology deterministic for every ATP tournament edition from 1991 onward. Do not infer tournament duration from event level, a generic quarantine, a week number, the first match row, a final-round row, or a schedule estimate when the actual completed edition period is required.

## Authoritative source hierarchy

1. **ATP Results Archive for completed editions.** Store the official date range displayed for that tournament edition and link directly to its ATP archive page.
2. **ATP official season calendar for future/current provisional editions.** Store the announced period as scheduled. It can define a target event's published window, but it cannot prove that a source tournament has completed and cannot enter player-profile training or normalisation until reconciled to an actual Results Archive period.
3. Do not treat order-of-play "start", "not before", or court schedule times as actual match start/finish timestamps.

The official ATP Results Archive exposes historical date ranges back to 1991, while the season calendar publishes scheduled ranges. The repository owner confirmed that permission was in place for a one-time official archive date capture. The temporary retrieval tooling has been removed after collection; the application does not perform live ATP archive scraping.

## Implemented registry and import contract

- `tools/atp_event_periods.schema.json` defines the record schema.
- `data/atp_event_periods.json` is the checked-in starter registry.
- `tools/atp_event_periods.py` validates the records, converts inclusive local date periods to UTC boundaries, resolves source IDs and audits annual coverage.
- `tools/import_atp_event_periods.py` imports a permissioned CSV export. It does not connect to ATP or retrieve web pages.
- `tools/test_atp_event_periods.py` covers timezone conversion, overlap ordering, scheduled-period exclusion from training, duplicate IDs, invalid timezones and source-coverage accounting.

Each record includes:
- `season`: calendar season.
- `eventId`: unique event-edition identity within that season.
- `tournamentId`: exact app/CALC tournament identity.
- `sourceEventIds`: exact source `tourney_id` values mapped to that ATP event edition.
- `startDate` / `endDate`: inclusive local dates shown by ATP.
- `timeZone`: IANA venue timezone, or the reserved value `MULTI_LOCATION` for an ATP-published card that spans multiple venues or has no single venue. This uses a conservative UTC envelope (local midnight minus 14 hours at the start; midnight after the final date plus 12 hours at the end) covering the full UTC+14 to UTC-12 range.
- `sourceUrl`: direct ATP first-party archive or calendar page.
- `sourceKind`: `results_archive` or `calendar`.
- `dateStatus`: `actual` or `scheduled`.
- `verifiedAt`: ISO date when the source range was checked.

For multi-venue/team events, a single fabricated venue timezone is not acceptable. If ATP publishes a round-level date span across multiple locations, each exact source event/tie ID may map to that round window using `MULTI_LOCATION`; the conservative UTC envelope prevents the round from being treated as complete before it could have finished in any participating timezone.

## Strict CALC behaviour implemented

1. Every target app tournament group must resolve all its source IDs to an official ATP period with the matching app tournament identity.
2. Historical target editions require an actual Results Archive record. The current season may use a scheduled calendar period for its target boundary until the event is reconciled to its actual Results Archive record.
3. Player-profile training and season normalisation use only complete source event editions whose actual Results Archive UTC end boundary is no later than the target's official UTC start boundary.
4. Concurrent/overlapping/same-week/incomparable event periods and scheduled-only source events are excluded entirely. No earlier individual round from an event still in progress is used.
5. The target event never contributes to its own profile or normalisation anchor. If no eligible completed-event anchor exists, no CALC rating is emitted; there is no target-self normalisation fallback.
6. Historical profile backfill across prior years uses the same official period registry; no unregistered historic event contributes to a profile.
7. The old level-based maximum-duration fallback is not used in strict production mode.
8. Data sufficiency remains a separate gate: completed valid match count, valid ace/service-point fields, player-profile coverage and normalisation-anchor availability are reported independently of date coverage.

## Production coverage/release gate

Every standard ATP tour-level singles event ID in each annual match source from 1991 onward, including events whose matches lack serve statistics, must map to one registry record before `--write` may proceed. Current-season supported Davis Cup target IDs are separately audited and must all map to ATP's round/final date windows. Historical Davis Cup ratings from a previously validated TML source snapshot are retained and are not re-derived from old archive IDs that do not represent individual tie periods. Historical seasons require `results_archive` / `actual`; the current year may additionally contain `calendar` / `scheduled` target records.

The gate fails on missing IDs, missing date records, duplicate source mappings, mismatched tournament identity, invalid timezones, or incomplete historical actual-period coverage. No dataset/history/baseline output is written if coverage is incomplete.

After the registry reaches 100% coverage:
- rerun chronological LOEO and event-population audits;
- rerun all Python unit tests;
- regenerate the complete history and dataset;
- verify that missing-stat and sample-threshold exclusions are independent of date coverage;
- run Android unit tests/build and production refresh gates;
- inspect every concurrent tournament pair and all same-week boundaries before promoting the PR from Draft.

## Current status

**Strict calculation consumer and one-time official archive date collection are implemented.** The one-time retrieval helper is temporary and removed after the archive cards were collected. The remaining registry build must pass 100% coverage for standard ATP tour events and current supported Davis Cup targets before production can write.
