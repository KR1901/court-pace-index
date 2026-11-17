# Strict open-data migration plan

**Status: Phase 1 — strict source audit and cutover blockers. No production feed has been switched.**  
**Reviewed: 17 November 2026**

This plan follows the project's request to use genuinely open data rather than waiting for bespoke provider permissions. “Open” means a published licence that allows use, modification and redistribution for any purpose, including commercial use. A free API, public website, public GitHub repository or non-commercial research licence does not by itself qualify.

The detailed source evidence is in [OPEN_DATA_SOURCE_AUDIT.md](OPEN_DATA_SOURCE_AUDIT.md). This document is an engineering plan and source-provenance record, not legal advice.

## Executive decision

**There is no verified, feature-preserving, strictly open replacement for the current Court Pace data stack.** A safe migration cannot be completed by switching every existing URL to another public tennis feed:

- Jeff Sackmann ATP data is CC BY-NC-SA 4.0. It is useful historical data, but the non-commercial restriction means it is not suitable for a fully open-data build.
- The `serve-and-volley/atp-world-tour-tennis-data` repository declares CC BY 4.0 and contains detailed match statistics for 1991–2022. Its README also says its source collection scraped ATP pages, so the declared repository licence does not, by itself, resolve upstream ATP terms. Treat it as a historical candidate, not an approved automatic source.
- ValueBet publishes CC BY 4.0 match results for 2021–2026, but not the serve-stat fields required by CALC or Hold%/Break%.
- Wikidata structured data is CC0 and can supplement player metadata, but it cannot provide full match statistics, dated ranking cohorts, live match stats or physical CPI.
- TML, Live Tennis API, KickServe, ATP/Infosys, Tennis Abstract and Court Speed do not provide a verified unrestricted open-data grant for the uses needed here.

Therefore this PR deliberately does **not** cut over production data or remove current features. The existing APK/assets are not transformed into a fully open-data product by this documentation-only phase. A full strict-open cutover requires either independently collected/contributed data under CC0/CC BY 4.0 or an accepted reduction in feature coverage.

## Existing source dependency map

| Module / builder | Current source | Purpose | Strict-open target status |
|---|---|---|---|
| `tools/compute_speed.py` | Sackmann history + TML current-season files | CALC history, current-season event ratings and player profiles | No approved feed for full history plus current season |
| `tools/build_player_stats.py` | TML annual CSVs, roster and dated ranking API; KickServe current rankings; Sackmann ID crosswalk | W–L, Hold%, Break%, H+B, surface/environment splits, heights and Top 100/500/1000 cohorts | No complete permissive match-stat + ranking + identity + height source |
| `AtpLiveClient` | ATP results pages | Event discovery, match links and lifecycle status | No verified strict-open live results feed |
| `AtpInfosysStatsClient` | ATP/Infosys Stats Plus JSON | Live and final ace/service-point statistics | No verified strict-open live-stat replacement |
| `tools/fetch_reference_data.py` | Tennis Abstract and Court Speed pages | TA reference values and CPI | No verified open reference table or event CPI feed |
| `tools/build_dataset.py` | Canonical dataset builder | Bundled/downloadable app payload | Must publish only source-eligible fields in any strict-open edition |

## Strict-open source candidates

### Candidate: serve-and-volley ATP match-stat archive

Repository: [serve-and-volley/atp-world-tour-tennis-data](https://github.com/serve-and-volley/atp-world-tour-tennis-data)  
Declared repository licence: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)

The repository README declares CC BY 4.0 and its historical CSV collection includes match-stat files from 1991 through 2022. The 2022 sample includes match/tournament identifiers, duration, winner/loser identifiers, aces, double faults, serve points, first-serve values, service games and break-point counters.

Limits that prevent cutover now:

- There are no 2023–2026 match-stat CSV files in the repository tree reviewed on 17 November 2026.
- Its README describes scraping ATP pages. The declared CC BY 4.0 licence is recorded, but the upstream collection provenance and ATP's separate systematic-retrieval terms need review before automated ingestion or bundled redistribution can be treated as cleared.
- The source's abbreviated player slugs need ambiguity-safe crosswalks. Ranking snapshots, full player biography and heights are not a complete replacement for current Player Stats.
- Per-year and per-event coverage has not yet been compared against the current 1991–2026 calculation archive.

This is the best historical technical candidate found, but it remains **not approved for production**. Do not imply it solves current-season/live data.

### Candidate: ValueBet Tennis match results

Source: [ValueBet Tennis open downloads](https://www.valuebetennis.com/en/donnees.htm)  
Declared licence: CC BY 4.0

The source publishes ATP/WTA match results, date/time, category, surface, players, score, winner and duration for 2021–2026. It is usable for result and event metadata with attribution. It does not provide aces, service points, service games or break-point faced/saved fields. It cannot drive CALC or the existing Player Stats game-denominator formulas.

### Candidate: Wikidata metadata

Source: [Wikidata licensing policy](https://www.wikidata.org/wiki/Wikidata:Licensing)  
Structured-data licence: CC0

Use only as a supplementary source for player identifiers and biography facts, such as some heights. Coverage is incomplete and should be recorded by entity/property/revision. Do not silently substitute a Wikidata height where the entity match is ambiguous or the field is absent.

## Rejected sources for a strict-open build

| Source | Reason it is not eligible |
|---|---|
| Sackmann ATP archive / mirrors | CC BY-NC-SA 4.0 excludes commercial use; therefore not “open for any purpose”. |
| TennisMyLife | Personal/educational/non-commercial research conditions; no unrestricted redistribution licence. |
| Live Tennis API | Subscription/service terms, raw-feed/bulk-redistribution and caching restrictions; not open data. |
| KickServe | No standard unrestricted open-data licence verified; commercial redistribution is separately restricted/queried. |
| ATP / Infosys | ATP terms restrict systematic retrieval/compilation without prior express permission; accessible endpoints do not make the data openly licensed. |
| Tennis Abstract | No open redistribution licence found for its surface-speed tables. |
| Court Speed | No open-data licence found for published CPI readings. |
| AcesAPI | Published tennis match example does not expose the serve-stat fields required by CALC. |
| Tennis-Data.co.uk | Results/odds-oriented and source terms do not provide the unrestricted match-stat redistribution grant needed here. |
| Wolfram ATP match dataset | Its source metadata identifies Jeff Sackmann; no independent permissive licence overriding the source provenance was verified. |
| Open Tennis Data research release | Source registry includes non-commercial and source-specific restrictions; not a complete match-stat provider. |

## Feature impact if the project insists on strict-open data only

| Feature | What can remain with evidence found | What cannot currently be maintained at existing coverage |
|---|---|---|
| Pace board / tournament detail | Existing app structure and calculation code can remain; ValueBet may support results metadata for its covered seasons once its feed is integrated | Current and full-history CALC data cannot be recomputed from a verified, fully open, complete feed |
| Surface Speed | The formula can remain unchanged | No verified 2023–current open serve-stat feed; historical 1991–2022 candidate not yet approved |
| Player Stats | Existing formulas and UI code can remain; CC0 height facts may supplement a subset of players | Complete W–L/hold/break history, exact year-end ranking cohorts and broad height coverage cannot all be rebuilt from verified open sources |
| Live now | UI can remain as dormant/unavailable state | No verified strict-open ATP event + results + live/final match-stat feed |
| CPI | CPI formula/scale explanation can remain | Published tournament/court readings must be removed from a strict-open dataset until independently sourced open measurements exist |
| TA reference comparison | Calculation can be explained independently | TA comparison values/fallbacks must be removed unless an open reference dataset is identified |

Do not zero-fill missing readings, use results-only feeds to fabricate ace-based ratings, use current rankings as historical year-end snapshots, or infer unknown heights.

## Implementation sequence

### Phase 1 — source and provenance baseline (this PR)

- Define the strict-open eligibility rule.
- Record each source's published licence and actual field/coverage limitations.
- Identify the features that cannot be preserved from the currently verified open data.
- Keep all changes on a feature branch. Do not modify `main`.

### Phase 2 — first-party open data contribution design

Create a documented contribution format for match statistics and physical court measurements. Each row needs stable source IDs, match/event date, surface, both player identities, all original fields, provenance URL or contributor record, submission time, validation status and correction history. Require contributors to dedicate submitted facts to CC0 or publish them under CC BY 4.0 with attribution; record that grant per contribution. No automated ATP page collection is used as a substitute.

### Phase 3 — historical match-stat adapter evaluation

Build a non-production parser/adapter for the declared CC BY 4.0 serve-and-volley archive only after the upstream provenance issue is resolved. Run fixtures and year-by-year comparisons for 1991–2022. Retain it as an experimental source until every field, event-level population and licence condition is verified. It cannot be used to fill 2023 onward.

### Phase 4 — result metadata and player facts

Evaluate ValueBet for the fields it supplies (results/date/surface only) and Wikidata for supplementary CC0 metadata. Do not use either source to synthesize match stats or historical ranking snapshots. Keep a separate exact-source field for every value.

### Phase 5 — current/live and physical measurement data

Until first-party contributions or a genuinely open provider exists, show no strict-open Live now stats and no third-party CPI readings. A manual/community workflow must publish measurements under an explicit CC0/CC BY 4.0 grant and preserve test method, court identity, date and measurement conditions.

### Phase 6 — strict-open release and legacy-data removal

Only after the complete replacements pass all checks should a separate cutover PR remove TML, Sackmann NC-SA, ATP/Infosys, Tennis Abstract, Court Speed and KickServe from production collection and generated assets. Regenerate every bundled/downloadable JSON, audit Git history and workflow artefacts for legacy payloads, and verify the strict-open attribution and notices. If data coverage is not there, disclose the narrower feature set rather than retaining restricted data under a misleading label.

## Non-negotiable calculation and UI invariants

- CALC keeps the existing opponent-adjusted expected-ace method, the 0.5 expected-aces floor, seasonal normalisation and player profile fallback hierarchy.
- Player Stats keeps Hold% = service games held / service games played; Break% = return games won / return games played; H+B = Hold% + Break%.
- Previous years use that calendar year's own final ranking snapshot; the current year uses its latest available snapshot. Do not invent a Top 100/500/1000 cohort from a non-ranking file.
- Live eligibility keeps completed singles only, excludes doubles, walkovers/no-play and in-progress matches, and retains played retirements when valid stats exist.
- Nulls stay null. No zero-filling, inferred statistics, unqualified fuzzy player joins or fabricated court readings.

## Acceptance criteria

A strict-open production cutover is ready only when:

1. Every raw source and each derived/bundled data family has an explicit eligible licence and attribution record.
2. Collection and distribution methods comply with the source's published terms; public visibility alone is not treated as permission.
3. Provider IDs and player/event identities map through stable, ambiguity-safe crosswalks.
4. Per-field, per-year and per-event coverage is measured against the existing outputs.
5. The existing CALC, Player Stats and live lifecycle tests pass without changing formulas to compensate for missing fields.
6. Every asset and downloadable JSON contains only eligible values and complete provenance.
7. CPI readings have independent documented measurements and an open contribution licence.
8. CI passes, the changed coverage is disclosed and a rollback plan exists.

## Confidence

- **High** that existing UI and calculation modules can remain largely separate from ingestion: the current codebase already has distinct builders and runtime clients.
- **Moderate** that the CC BY 4.0 historical archive can technically provide a useful 1991–2022 stat adapter; schemas look adequate, but coverage comparisons and source-provenance review remain uncompleted.
- **Low** that current ATP live statistics, complete player ranking cohorts and CPI can be replaced by existing verified open datasets in the near term.
- **High** confidence in the conclusion that no complete strict-open, feature-preserving provider was verified in this audit.

**No production data cutover is included in this PR.** It updates the source audit and migration plan only; it does not change Android/Python runtime code, generated datasets, workflows, API keys or signing configuration. The branch is `research/data-provider-migration-phase-1` and `main` remains untouched.
