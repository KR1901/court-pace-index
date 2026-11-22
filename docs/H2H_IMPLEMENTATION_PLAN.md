# H2H screen — implementation plan

**Status:** Planning / data-source gate not yet passed  
**Target:** Android app (Kotlin + Jetpack Compose)  
**Proposed screen:** `H2H`  
**Implementation branch for this plan:** `feature/h2h-screen-plan`

## 1. Goal and requested product behaviour

Add a dedicated H2H screen where the user selects two ATP singles players and can see:

- Head-to-head match wins for each player.
- Current ATP singles rank.
- Age.
- Height.
- Current-season (YTD) W/L and titles.
- Career W/L and titles.
- Surface-specific W/L for **Outdoor Hard**, **Indoor Hard**, **Grass** and **Clay**, for both the YTD and career sections.
- A chronological meeting list with event, surface, round, score/result and match date formatted exactly as `DD/MM/YYYY`.

The YTD and career surface selectors should be independent so changing one section does not unexpectedly change the other. Each section should also offer an overall/all-surfaces view.

## 2. Repository findings

The current app is Kotlin/Jetpack Compose. Navigation is centralised in `ui/AppNav.kt`; it currently exposes five bottom tabs and routes the fifth tab to `PlayerStatsHost()`. The tab bar in `ui/glass/Glass.kt` gives every item equal width, so adding a sixth item must include a layout/accessibility check rather than assuming the current bar will still fit.

The Player Stats module is deliberately separate:

- `PlayerStatsData.kt` stores player identity, current rank, height, and per-season/per-surface aggregate buckets.
- Its `PlayerStatsBucket` contains match count and W/L, but not the individual match records, date, event, score, title/final information, date of birth, or hard-court indoor/outdoor classification.
- `PlayerStatsLiveUpdater` is a separate TML-backed refresh loop.
- `docs/PLAYER_STATS.md` documents that its source and derived data are restricted to personal/non-commercial use and are not cleared for public APK/data redistribution.

Consequently, the existing Player Stats JSON cannot independently provide the requested H2H meeting list or complete requested profile/title fields. The H2H feature must not infer individual meetings from aggregate W/L buckets, and it must not silently reuse restricted fields as though they were open data.

## 3. Data-source gate — what is currently verifiable

The source audit and strict-open policy recorded in [PR #24](https://github.com/KR1901/court-pace-index/pull/24) define open data as data that can be used, modified and redistributed for any purpose, including commercial use. This plan keeps that policy: a public endpoint, scraper, or free download is not by itself a redistribution licence.

### Match results available for an initial H2H/YTD slice

[ValueBetennis' published match-results CSV](https://www.valuebetennis.com/en/donnees.htm) declares **CC BY 4.0** and provides ATP/WTA singles rows from **1 January 2021 onward**. Its documented fields include UTC date/time, tournament and tournament ID, category, tour, surface, round, both players and player IDs, winner ID and score. It is regenerated nightly; the source page reviewed for this plan reported a generation date of 21 November 2026.

This is a viable, attribution-compatible source for:
- Pairwise H2H meetings within the published archive.
- ATP main-tour W/L within the published archive.
- 2026 YTD W/L and result history.
- Titles within the published archive, where the final-round result is present and can be validated.

It is **not** a full-career history source: there are no matches before 2021 in the published files. Any metric computed from it must carry the archive coverage window. Until a full-history source is approved, the UI must say **“Archive W/L (2021–present)”** / **“Archive titles (2021–present)”**, or show Career as unavailable with a coverage note. It must not label a 2021-onward count as a player's complete career record.

### Required fields not currently covered by that feed

| Requirement | Current gap | Safe handling |
|---|---|---|
| Full career W/L and career titles | ValueBet's published archive starts in 2021. The historical candidates reviewed in PR #24 are not currently cleared for the strict-open production feed. | Keep complete career totals unavailable until a full-history source or first-party contributed archive is approved. Do not use the non-commercial Sackmann dataset or infer earlier matches. |
| Indoor Hard vs Outdoor Hard | ValueBet documents a `surface` field, but no indoor/outdoor field. | Preserve hard matches with environment `unknown`; do not classify them from tournament names or assumptions. Add the split only when a separately licensed, event-edition-level environment source is available. |
| Current rank | No unrestricted, verified current ranking feed is recorded in the source audit. | Show “Unavailable” until a CC0/CC BY ranking feed with date and player IDs is approved. Never substitute an old match-time ranking for current rank. |
| Age | Requires an explicit date of birth, which is not in the ValueBet match CSV. | Calculate age only from a verified DOB and a stated as-of date. Missing or ambiguous DOB remains unavailable. |
| Height | Requires a reusable, attributable height source and an unambiguous player identity join. | Use verified CC0/CC BY player facts only; retain unknown values. Do not silently carry over TML-derived heights into a strict-open H2H dataset. |

Wikidata's structured data is CC0 and may be considered as a *supplementary* source for DOB/height where the ATP player identity can be matched unambiguously. It is not a complete source for all player facts and it does not solve current ranking or career match-history coverage.

The `serve-and-volley/atp-world-tour-tennis-data` archive declares CC BY 4.0, but the repository documents ATP-page scraping as its collection method and the upstream provenance/ATP terms remain unresolved in PR #24. It should remain a research candidate, not be ingested into production until that provenance gate is explicitly closed. Jeff Sackmann's CC BY-NC-SA data is not eligible under the project's strict-open rule.

## 4. Proposed data architecture

Create an independent H2H data module. Do **not** add H2H match records to `pace_dataset.json`, `data/latest.json`, or the current Player Stats asset.

### 4.1 Build-time data pipeline

Add a dedicated builder, tentatively `tools/build_h2h.py`, and a separate scheduled workflow, tentatively `.github/workflows/refresh-h2h.yml`.

The builder should:
1. Download only the source files whose licence and provenance have passed the gate above.
2. Filter to ATP singles and an explicit, tested ATP Tour main-draw category allowlist. Do not mix WTA, Challenger, ITF, qualifying, doubles or exhibitions into ATP Tour records. Explicitly verify event-category coverage for Next Gen ATP Finals before including it; preserve the project's existing decision not to exclude Next Gen when it is represented as an ATP event.
3. Retain the provider's stable match/player/event IDs. Do not merge players using fuzzy name matching. Any cross-source identity bridge must be exact, auditable and ambiguity-safe.
4. Exclude walkovers/no-play outcomes from W/L and title calculations. Count a retirement only when the source represents a played match with a valid winner/result.
5. Store source URL, declared licence, required attribution, retrieval time, source-file checksum, coverage start/end, and row counts in the generated database metadata.
6. Preserve unknown fields as unknown. No zero-filling for missing player facts or indoor/outdoor classification.

### 4.2 On-device storage

Prefer a compact SQLite database asset (for example `h2h.sqlite`) over loading every match into a Kotlin list. It keeps the existing pace and Player Stats assets separate and allows pairwise history lookup through indexes.

Proposed logical schema:

- `players`: canonical provider ID, display name, country (nullable), DOB (nullable), height in cm (nullable), current rank (nullable), rank-as-of date (nullable), and per-field source/provenance.
- `matches`: stable match ID, event ID/name/category, UTC match timestamp, surface, court environment (`indoor`, `outdoor`, or `unknown`), round, player IDs, winner ID and score.
- `event_environment`: event ID + season, environment, source, and verification metadata. A hard-court event without a verified environment stays `unknown`.
- `metadata`: schema version, generated timestamp, source/attribution, checksums and coverage window.

Add indexes for unordered player pairs and date ordering, plus player/season/surface lookups. Open the database read-only from app-private storage after an atomic first-copy and checksum/version check. Keep the query/repository layer independent from `AppViewModel`, `PaceData`, `AtpLiveClient` and the Player Stats live updater.

For the initial CC BY results slice, historical 2021–2025 files can be pinned by checksum and the current 2026 file refreshed on a separate schedule. Reconcile historical files periodically so source corrections are not silently ignored.

## 5. H2H calculations and definitions

All calculations should be pure Kotlin domain functions with unit tests, fed by repository queries.

### Head-to-head record

For the two selected canonical player IDs, query all eligible meetings, group by winner ID and display `A wins – B wins`. A match is counted once. The same pair must return the same total regardless of selection order; swapping player A and B should only swap the displayed sides.

### YTD W/L

Use the current calendar year, with its exact start/end dates captured in the query. Count wins and losses from eligible ATP singles results. Surface grouping is:
- Outdoor Hard
- Indoor Hard
- Grass
- Clay

A hard match with unknown environment contributes to the overall hard/overall record but not to either indoor-hard or outdoor-hard split. The UI must expose that classification coverage in the source/coverage note.

### Career W/L and titles

Compute only when a full-career eligible results archive is approved and ingested. Until then, show the 2021–present archive slice as a clearly labelled partial archive, not as complete Career W/L.

### Titles

Count a title only from a validated ATP singles final (the source's final-round code must be confirmed against representative events) with a played result and the selected player as winner. Deduplicate by event ID + season. Do not count a semifinal, round-robin win, walkover, abandoned final or exhibition as a title. Apply the same coverage window as the underlying result archive.

### Age, rank and height

- Age is whole years calculated from verified DOB as of a displayed `asOf` date.
- Current rank is a dated ATP singles rank snapshot, never a ranking taken from an old match record.
- Height is stored in centimetres and displayed in both metric and imperial units.
- A missing or ambiguous value displays `—` / `Unavailable`, never an estimate.

### Meeting list

Sort newest first, then stable match ID for deterministic ordering. Show:
- Date as `DD/MM/YYYY` using the source UTC date (no device-timezone date rollover).
- Tournament/event name and round.
- Surface and indoor/outdoor state when verified.
- Selected player's result (W/L), winner/loser names and score.

Use pagination or lazy database queries so a long rivalry does not load its entire history into Compose at once.

## 6. Screen and navigation plan

Add a new `H2H` destination and tab without changing existing screen behaviour.

Proposed screen hierarchy on a phone:
1. **Header:** “H2H” and a short instruction.
2. **Player selectors:** two independent searchable selectors, with a clear action and same-player validation. Search by player name; display country as a disambiguator where available.
3. **Player comparison:** two player identity blocks with current rank, age and height (each field can show unavailable when source coverage is missing).
4. **H2H score:** prominent A wins – B wins count, with the archive coverage period visible.
5. **Season section:** YTD W/L and YTD titles, with its own Overall / Outdoor Hard / Indoor Hard / Grass / Clay selector.
6. **Career section:** career W/L and career titles, with a separate surface selector. When complete history is unavailable, clearly label the 2021–present archive slice and do not call it career.
7. **Event breakdown:** collapsible or paginated list of all recorded meetings, newest first, with the requested date format.

Navigation considerations:
- Add a sixth tab only after adapting/testing the equal-weight bottom bar for narrow Android widths and accessibility font scaling. Keep all existing five tab targets readable and tappable.
- Preserve current route/back-stack behaviour, tab inset, nested scrolling and the hide-on-scroll behaviour.
- Do not change the existing Player Stats screen or its background refresh lifecycle.

## 7. Phased implementation sequence

### Phase 0 — close the source gates (blocking)
- Confirm the chosen source set remains compatible with the strict-open policy.
- Decide whether a 2021–present, transparently labelled first release is acceptable while full career is unavailable.
- Obtain a verifiable event-edition indoor/outdoor dataset.
- Identify a permissively licensed current ranking source and a DOB/height source with stable player identity mapping.
- Resolve full-career history from a source that permits redistribution, or accept that the complete career fields remain unavailable.

### Phase 1 — source adapter and database builder
- Implement `tools/build_h2h.py`, schema versioning, provenance, checksums and validation.
- Generate the compact H2H SQLite database separately from all current datasets.
- Add builder unit tests for filtering, IDs, dates, results, round mapping, titles, duplicates, surface/environment unknowns and coverage.

### Phase 2 — Android repository and analytics
- Add H2H models, read-only SQLite repository and pure analytics functions.
- Implement pair lookup, H2H record, YTD/archive W/L, surface splits, title counts and date formatting.
- Add Kotlin tests for swapped player order, all four requested surfaces, unknown hard environment, year boundaries, no-play/retirement handling and incomplete coverage.

### Phase 3 — Compose screen and navigation
- Add searchable player selectors and the comparison cards.
- Add independent YTD and career surface filters.
- Add lazy event-history rows with `DD/MM/YYYY` dates.
- Add an explicit empty state for no shared matches and for fields not covered by the approved sources.
- Integrate the sixth tab only after the bottom-bar layout is validated on small and large screens.

### Phase 4 — regression and release gate
- Run Python tooling syntax/tests, `./gradlew :app:testDebugUnitTest`, screenshot tests and `./gradlew :app:assembleDebug`.
- Verify no edits to CPI/CALC formulas, `pace_dataset.json`, `data/latest.json`, existing Player Stats data/refresh, live match discovery, live rating calculation, release signing or APK versioning.
- Review the generated database's licence notice/attribution and coverage metadata before merging.
- Do not trigger a production APK release as part of the H2H feature PR.

## 8. Acceptance criteria

The feature is ready only when all of the following are true:

- The user can select any player in the approved H2H player directory as Player A and Player B; selecting the same player twice is blocked.
- H2H wins are correct and invariant to selection order.
- Current rank, age and height have explicit source/as-of metadata; unknown values remain unavailable.
- YTD W/L and titles use the current calendar year and show the data's last-updated date.
- Career W/L and titles are either backed by a full approved history or visibly identified as a partial archive; incomplete results are never presented as full career.
- Overall, Outdoor Hard, Indoor Hard, Grass and Clay splits are correct; unknown hard-court environments are not misclassified.
- H2H event history is newest-first and each match date is shown in `DD/MM/YYYY`.
- Empty, incomplete-source, no-shared-match and long-history states render correctly.
- Existing screens, Player Stats auto-refresh, live ratings, data builders and release workflows pass their regression checks unchanged.

## 9. Scope of this planning PR

This PR adds this implementation plan only. It intentionally does **not** add a non-functional H2H screen, generate a misleading partial-career asset, reuse restricted TML/ATP/Infosys/Sackmann fields, or change production data and application behaviour. Implementation should begin once Phase 0 has an accepted source decision, or should explicitly ship a coverage-limited first release with the limitations above visible in the UI.
