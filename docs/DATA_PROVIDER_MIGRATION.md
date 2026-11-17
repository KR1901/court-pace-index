# Data-provider migration plan

**Status: Phase 1 — source-rights remediation and migration design. No production feed has been switched.**  
**Reviewed: 17 November 2026**

This document defines a migration path that keeps the Court Pace Android experience and its calculation semantics stable while replacing data providers. It is an engineering plan and provenance record, not legal advice or a statement that any uncontracted provider has granted permission.

## Executive decision

Use a hybrid provider architecture rather than trying to find one universal tennis API:

- **Historical match-stat foundation:** the Jeff Sackmann ATP archive mirror, under its stated CC BY-NC-SA 4.0 terms, for seasons and fields where its records are available.
- **Current/live match statistics:** evaluate Live Tennis API as a service provider. Its terms permit limited use of API responses inside a subscriber's app, but this is not an open-data licence and does not grant ATP rights. Written confirmation is required for our derived, persisted and distributed output before production adoption.
- **Current rankings:** evaluate KickServe's official-rank CSV with its requested dated attribution. Do not use its internal player IDs as cross-provider identity keys.
- **Supplementary player facts:** Wikidata structured data (CC0) may fill selected biography fields, subject to provenance and validation.
- **Physical CPI and published TA reference values:** do not redistribute without permissions. Prefer independently sourced measurements and Court Pace's own calculated Surface Speed series.

No single source found in this review covers all of historical ATP match stats, 2026-current live stats, dated rankings, player heights and physical court measurements under one clear, permissive data licence.

## Current dependency map

| Existing module / builder | Current source dependency | Function in Court Pace | Migration target |
|---|---|---|---|
| `tools/compute_speed.py` | Sackmann historical archive + TML current-season files | Historical and current CALC inputs, player profiles and baseline | Keep Sackmann history; replace TML current season only after a licensed current-stat feed is approved |
| `tools/build_player_stats.py` | TML annual CSVs, roster, rankings; KickServe live ranking CSV; Sackmann IDs for crosswalk | Player W–L, Hold%, Break%, H+B, surface/environment splits, heights and year-specific cohorts | Sackmann historical match rows/rankings where schema and coverage pass; licensed current-year feed; KickServe rankings; CC0 metadata supplement |
| `AtpLiveClient` | ATP results pages | Event discovery, completed match identifiers, walkover/retirement classification | Licensed event/match endpoint via server-side adapter; retain existing status and eligibility rules |
| `AtpInfosysStatsClient` | ATP/Infosys Stats Plus JSON | Live and final ace/service-point statistics | Licensed match-stat adapter; do not replace with results-only data |
| `tools/fetch_reference_data.py` | Tennis Abstract and Court Speed pages | TA comparison/fallback values and CPI | Remove third-party table ingestion absent permission; maintain separately sourced CPI only when its rights and measurement provenance are clear |
| `tools/build_dataset.py` | Canonical pace dataset and metadata | Bundled/downloadable app payload | Keep the output schema stable; include only fields whose rights permit the exact distribution method |

The current implementation already keeps the Android UI and the core calculation functions mostly separate from their source loaders. The provider migration should therefore happen at the ingestion boundary, not in the Compose screens or rating formula.

## Candidate sources and restrictions

### 1. Jeff Sackmann historical ATP archive

Repository: [Aneeshers/tennis-sackmann-archive](https://github.com/Aneeshers/tennis-sackmann-archive)  
Licence: [CC BY-NC-SA 4.0](https://github.com/Aneeshers/tennis-sackmann-archive/blob/main/LICENSE)

The archive contains ATP annual match files, player data and ranking history. Match files expose the key columns needed for historical ace-based Surface Speed and service-game statistics, including aces, service points, service games and break-point counters. It is the best available open-licensed historical backbone found in this review.

Important limitations:

- CC BY-NC-SA permits sharing/adaptation only with attribution, non-commercial use and ShareAlike compliance. It is not a commercially permissive replacement.
- The mirror does not own the upstream data and does not grant additional rights.
- The live mirror's 2026 ATP match file checked on 17 November 2026 contained records only through 7 February 2026. Do not treat it as a complete 2026/current feed.
- Historical player identifiers and ranking identifiers need explicit crosswalks. Keep unresolved and ambiguous identities separate.
- Validate each year, tour level and statistic rather than assuming a file's existence means complete coverage.

### 2. Live Tennis API

Provider: [Live Tennis API](https://livetennisapi.com/)  
Terms: [Terms of Use](https://livetennisapi.com/terms)  
Reference: [API documentation](https://docs.livetennisapi.com/reference.html)

The terms provide a limited, non-exclusive, non-transferable and revocable licence for using responses within the subscriber's own application/service. They prohibit republishing raw responses as a feed or bulk dataset, disallow unnecessary storage/caching, and require paid credentials to remain server-side. This is a contracted-service model, not open-source/open-data.

The documentation describes:

- ATP/WTA historical match results for 1968–2022, with match-level serving statistics where the source recorded them.
- Current and completed match data from 2023 onward, with coverage differences between tours and fields.
- Live match statistics including aces, service splits, break points and service/return point/game measures.
- Player biography fields including height in the archive and player endpoints.
- Coverage reporting for the API's current history corpus.

Open blockers before production:

1. Obtain written confirmation that Court Pace may calculate and persist its own event ratings and player aggregates from the responses.
2. Confirm whether the resulting derived ratings, Player Stats values and confidence/coverage summaries may be bundled in a public APK or downloadable JSON.
3. Confirm what completed-match statistics remain available after a match ends, and for how long.
4. Confirm rights and permitted display for ATP-derived names, scores, rankings and statistics, including any upstream restrictions.
5. Confirm the applicable plan, quota and service continuity terms for the required polling rate.

Do not assume that a paid API subscription alone clears upstream rights. Do not copy raw API responses into Git, public assets or a bulk-download endpoint. Do not embed a paid API key in the APK. Use a server-side relay with minimal fields and cache duration approved by the provider.

### 3. KickServe rankings

Source: [KickServe tennis ratings data](https://kick-serve.com/data/)

The data page permits reuse with a dated citation and directs users to contact the publisher for commercial redistribution. The CSV contains an `official_rank` column and a provider-specific ID. It can be evaluated as a current ranking snapshot only, not a match-stat feed or a historical ranking archive.

Requirements:

- Store the retrieval date and cited snapshot date.
- Attribute the feed using the wording requested by the publisher.
- Crosswalk provider identities through verified country-qualified full-name matching and ambiguity detection; do not treat KickServe IDs as ATP/TML/Sackmann IDs.
- Seek written confirmation for the project's actual public-distribution model if that model could be considered commercial redistribution.

### 4. Wikidata

Source: [Wikidata licensing policy](https://www.wikidata.org/wiki/Wikidata:Licensing)

Wikidata's structured data is CC0. It is a permissible supplementary candidate for player heights and basic facts, but it is not a complete, authoritative or tennis-specialist roster. Store the Wikidata entity ID, property, source revision/retrieval time and value. Keep missing and disputed facts as unknown; do not silently overwrite a better verified ATP/Sackmann measurement.

### 5. Sources not suitable for immediate cutover

- **AcesAPI:** the published tennis `get_match` example returns `serve_stats: null` and says aces, double faults and break-point match statistics are not served in that response. It is therefore not a replacement for the current match-stat path. See [get_match](https://acesapi.com/docs/get-match).
- **Tennis-Data.co.uk:** its public contact notice says the data is intended for private individuals and excludes commercial/data-training products. Its schema is also results/odds focused rather than sufficient match-stat coverage. See [contact notice](https://www.tennis-data.co.uk/contact.php).
- **Tennis Abstract:** its Surface Speed table has no identified open-data licence for republishing. Keep Court Pace's own CALC as the primary behavioural rating; remove or separately license external reference/fallback rows.
- **Court Speed / courtspeed.com:** no explicit open-data licence for its CPI table was identified. Its method notes say its values are sourced from ATP/TennisTV CPI charts. Do not redistribute the table without permission from the relevant rights holders.
- **ATP/Infosys direct retrieval:** ATP's [terms](https://www.atptour.com/en/terms-and-conditions-app) restrict systematic retrieval and compilation of scores, statistics and rankings absent prior express written permission. An unofficial endpoint or scraper does not create that permission.
- **ITF classified surfaces:** useful as product classification context, but its slow-to-fast surface categories are not event-specific measured CPI records and cannot be substituted as though they were the same metric.

## Canonical contract and architecture

All source adapters should map into canonical project-owned types before calculations run. The Android app should not know which provider supplied a record.

### Canonical match record

Minimum fields:

- Stable internal match key and original provider/match ID.
- Tournament ID, season, event start date, match date when actually present, surface, indoor/outdoor setting and draw/tier.
- Player IDs as namespaced values (for example `sackmann:...`, `livetennisapi:...`), names and country codes.
- Result status (completed, in-progress, retirement, walkover, abandoned, unknown) and score.
- For each player/server role: aces, service points, service games, break points faced and saved; optional extended counters.
- Per-field availability and validation state. Unknown is distinct from zero.

### Canonical player and ranking records

- Namespaced provider ID; verified cross-provider identity link and match confidence.
- Height in centimetres, source, retrieval date, and verification status.
- Ranking position, ranking points, exact snapshot date, source and rank-tie handling.
- No implicit rank interpolation, backfilling or identity joins on fuzzy names.

### Provenance record

Every output family should retain:

- Provider and dataset/product name.
- Original source URL and provider record ID.
- Retrieved-at UTC and provider's as-of timestamp/date (separate fields).
- Licence/terms URL and version/effective date where available.
- Permitted uses: internal computation, caching, in-app display, derived output, offline bundle, redistribution.
- Adapter/schema version and source checksum for reproducibility where storage is allowed.

### Data flow

`Provider API / licensed archive -> Source adapter -> Canonical validation -> Existing calculation -> Rights-aware output publisher -> Existing Android model/UI`

For subscription APIs, the paid API key stays in a server-side secret. The server should return only the data/derived fields the provider has confirmed may be served. Public assets must not be a back door for redistributing a raw feed.

## Calculation and UI invariants

Provider migration must not change the mathematical definitions or eligibility rules without a separate, reviewed methodology proposal.

Keep:

- Surface Speed expected-ace odds formula, the 0.5 expected-aces floor, season normalisation and current player-profile/shrinkage rules.
- Player Stats definitions: Hold% = service games held / service games played; Break% = return games won / return games played; H+B = Hold% + Break%.
- Calendar-year cohorts: each previous year uses that year's own final ranking snapshot; the live year uses its latest valid snapshot.
- Existing height bands and metric/imperial rendering.
- Singles-only rating population, Next Gen's separately identified format treatment, walkover/no-play exclusion, and played-retirement handling.
- Missing values remain missing. Do not replace unknowns with zeros or estimates simply to increase apparent coverage.
- Existing Android screens, filters, sorting, offline dataset schema and user-facing labels unless a field genuinely cannot be sourced.

The expected result is a provider change with unchanged user experience, not a formula rewrite. Numerical values may still differ where the original providers have different match populations, corrections or field definitions; those differences must be quantified and attributed.

## Staged implementation plan

### Phase 1 — documentation and rights baseline (this PR)

- Correct the stale statement that the repository is private.
- Publish source-by-source rights and candidate-provider findings.
- Document a canonical provider boundary and an explicit release gate.
- Make no production source, data asset, formula, app UI, secrets or workflow changes.

### Phase 2 — schema fixtures and historical adapter

- Capture permitted, non-sensitive example records and write parser fixtures.
- Build a Sackmann-to-canonical adapter for ATP historical matches and ranking files.
- Compare year-by-year population and statistic coverage against the current build.
- Confirm the CC BY-NC-SA attribution and ShareAlike obligations for any derived distribution.
- Keep 2026 after 7 February out of any claim that the archive is complete until a separate current source supplies it.

### Phase 3 — current/live licensed service adapter

- Resolve the Live Tennis API rights questions in writing.
- Add a server-side credentialed client and endpoint-specific parser.
- Test final statistics, live updates, coverage flags, rate limits, retries, caching expiry and provider outages using test fixtures.
- Maintain a no-provider-key local test mode.
- Do not expose paid keys to the mobile client or distribute raw provider responses.

### Phase 4 — Player Stats source swap

- Source historical annual match stats from the archive adapter.
- Source recent/current matches from an approved licensed feed.
- Source dated historical ranking snapshots from licensed archive rankings; use KickServe only for the current ranking if its terms fit the actual use.
- Use explicit, stable identity crosswalks; validate unresolved and ambiguous players.
- Use CC0 metadata only as a supplementary height source.
- Preserve the current year's and historical cohort definitions.

### Phase 5 — CPI and TA cleanup

- Stop automatically importing and redistributing Tennis Abstract values unless permission is secured.
- Stop automatically importing/repackaging Court Speed CPI readings unless permission is secured.
- Keep original independently sourced measurements only where the collection process, measurement method, court identity and redistribution rights are documented.
- Keep CPI blank where no properly sourced reading exists.

### Phase 6 — dual-run, acceptance and release

- Run old and new builds in parallel without changing the public production branch.
- Compare match counts, eligible server sides, unrateable reasons, rating ranks and absolute differences by event/season.
- Compare Player Stats numerators, denominators, heights, unknowns and Top 100/500/1000 membership.
- Exercise live discovery, finals, Next Gen and completed-event transitions on a physical Android device.
- Obtain a human review of the source-rights evidence before merging the cutover PR.
- Keep a tested rollback to the old data snapshot and adapter until the new source is stable.

## Acceptance criteria for any source cutover

A production source change is ready only when all of the following are met:

1. Written rights confirmation or an applicable published licence covers collection, processing, persistence, derived output and the exact distribution method.
2. The source schema maps to the canonical contract without fabricated values.
3. Per-field coverage is measured across seasons and competition levels.
4. The Surface Speed formula and player-profile tests pass unchanged.
5. Player Stats formula, ranking cohort, height grouping and denominator tests pass unchanged.
6. Live now discovers and updates supported tournaments on a real handset and correctly distinguishes in-progress, completed, walkover and retirement cases.
7. Provenance is visible in generated metadata and documentation.
8. CI passes and the release notes disclose any genuine source-coverage differences.

## Confidence

- **High** that the adapter-based migration can preserve the existing formulas and UI: the current project already separates its main ingestion paths from calculation and presentation.
- **Moderate** that Sackmann can replace the TML historical Player Stats inputs: the required historical fields exist, but full year-by-year coverage and ranking-cohort parity still need executable comparisons.
- **Moderate** that Live Tennis API can replace the current/live service technically: the documented endpoints appear to expose the relevant categories of data, but exact final-stat field coverage, quotas and the project's persisted derived outputs need tests and written rights confirmation.
- **Low** that a fully open, one-provider replacement exists: none was identified in this review.
- **Low** that CPI can be recreated from a permissively licensed public dataset without collecting or commissioning new measurements.

**No production cutover is approved by this document.** Phase 1 is documentation and risk control only. Later implementation PRs must be independent, testable and remain off `main` until reviewed and approved.
