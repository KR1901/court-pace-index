# Strict open-data source audit

**Reviewed: 17 November 2026**  
**Decision: no complete, feature-preserving, strictly open replacement source has been verified.**

This audit uses “open data” in the strict sense: the published licence must permit anyone to use, modify and redistribute the data for any purpose, including commercial use. A public download, free API, open-source scraper or research-only permission is not enough. The project software's MIT licence does not relicense source data.

## Eligibility rule

A source is eligible only when its data licence is explicit and permits:
- reuse for any purpose, including commercial use;
- redistribution of the data and adaptations;
- modification and combination with other data;
- attribution/share-alike obligations that can be met by this project, where applicable.

CC0 and CC BY 4.0 generally meet this rule. CC BY-NC, CC BY-NC-SA, personal/research-only terms, app-display-only API terms and pages without a data licence do not.

## Source-by-source result

| Source | Licence / terms found | Data available | Strict-open decision |
|---|---|---|---|
| Jeff Sackmann ATP archive and mirrors | CC BY-NC-SA 4.0 | ATP match results/stats, rankings and player records; match-stat coverage from 1991 onward | **Reject for strict-open build.** The non-commercial condition fails the any-purpose rule. Do not relabel it as fully open. |
| `serve-and-volley/atp-world-tour-tennis-data` | Repository README declares CC BY 4.0 | ATP results and detailed serve/return match-stat CSVs for 1991–2022 | **Historical candidate only; not approved for cutover.** It declares a permissive licence, but its README says the collection was scraped from ATP pages. That provenance does not resolve ATP's separate terms restricting systematic retrieval/compilation. The repository has no 2023–2026 stats archive. Review the data artefact's provenance before treating the declaration as sufficient. |
| ValueBet Tennis match archive | CC BY 4.0 | ATP/WTA match dates, tournament, category, surface, participants, scores, winner, duration; seasons 2021–2026 | **Eligible for results metadata only.** It does not publish the ace, service-point, service-game and break-point fields required by CALC and Player Stats. It is not a match-stat replacement. |
| Wikidata structured data | CC0 | Supplementary player identifiers, biographies and some height facts | **Eligible as supplementary metadata.** It is not a complete/authoritative ATP roster or match-stat, ranking-snapshot, live-score or CPI source. Keep unknowns unknown. |
| Open Tennis Data (research release) | Mixed source registry; includes CC BY-NC-SA and source-specific restrictions | Research-oriented match identity/results/date data | **Reject as a general provider.** Its own registry records source-specific restrictions and the output is not a complete serve-stat feed. |
| TennisMyLife (TML) | Personal/educational/non-commercial research terms | Current and historical ATP match statistics, rankings, player records and heights | **Reject.** Not an unrestricted open-data licence. |
| Live Tennis API | Subscription/service terms; no open-data licence for API responses | Results, historical and live stats, point-by-point data, player/ranking endpoints depending on plan | **Reject.** Free access or a service subscription is not an open-data grant; its terms restrict bulk redistribution and caching. |
| KickServe rankings | Dated attribution request; separate contact for commercial redistribution; no standard open-data licence identified | Current ranking CSV | **Reject for strict-open feed.** Current rank data only and no unrestricted licence identified. |
| AcesAPI | Service terms; published tennis example lacks required serve stats | Limited tennis match payloads | **Reject.** Published example does not provide the required ace/break-point statistics. |
| ATP / Infosys pages and endpoints | ATP website terms restrict systematic retrieval/compilation absent express permission | Live and archived scores/statistics/rankings | **Reject as a strict-open automated source.** An endpoint or scraper being technically accessible does not make the data open. |
| Tennis Abstract surface-speed tables | No open redistribution licence identified | Published behavioural reference ratings | **Reject.** Public display is not a data licence. |
| Court Speed CPI readings | No open-data licence identified | Published physical court-pace measurements | **Reject.** Do not bundle or republish as open data. |
| Wolfram ATP match dataset | Dataset is attributed to Jeff Sackmann; no separate permissive data grant identified | Match results, stats, rankings and heights for 1968–2024 | **Reject as an independent open source.** Its provenance points back to Sackmann; a hosting catalogue does not create a new upstream licence. |

## What this means for Court Pace

The current feature set needs several distinct data families, and no verified source covers them all:

| Feature / data family | Required fields | Strict-open coverage found | Result |
|---|---|---|---|
| Historical CALC Surface Speed | Tournament, season, surface, both players, aces and service points per server side | A CC BY 4.0 archive declares ATP match stats for 1991–2022, but upstream ATP collection provenance is unresolved; Sackmann has broad history but is NC | **No approved full-history feed** |
| Current-season CALC | Recent completed ATP matches with ace/service-point fields and stable IDs | No verified strict-open 2023–current ATP stat feed | **Cannot replace TML for current seasons** |
| Player Stats | Match outcomes, service games, BP faced/saved, surfaces, player identity; year-end ranking snapshots and heights for cohorts | Some historical stat fields exist in candidate archives; CC0 heights are incomplete; no complete permissive historical ranking snapshot provider verified | **Cannot reproduce current all-player / Top 100–1000 cohort feature at current coverage** |
| Live now | Live events, completed-match discovery, match stats, walkover/retirement/doubles status | No verified strict-open ATP live results-and-stat feed | **No open live replacement** |
| Physical CPI | Independently documented court measurements, court/event identity, test date and method | No verified open dataset of event-level CPI readings found | **No replacement** |
| TA reference comparison | Independently and openly licensed reference surface-speed series | No verified open reference series found | **Remove the TA dependency in a strict-open edition; do not silently substitute another unlicensed table** |

## Recommendation

Do not switch the app to a source merely because it has a public GitHub repository or a downloadable CSV. Do not keep Sackmann in a “fully open” build: CC BY-NC-SA is not compatible with unrestricted use. Do not continue TML, ATP/Infosys, Tennis Abstract, Court Speed or KickServe collection under an open-data label.

A strict-open product can proceed in one of two honest ways:

1. **Build an independently collected dataset.** Publish contributor-submitted match statistics, player facts and court measurements under CC0 or CC BY 4.0, with contributor warranties, provenance, timestamps, match IDs, field-level validation and corrections. Do not use automated ATP collection as a shortcut.
2. **Reduce the strict-open feature set until data exists.** Use CC0 Wikidata only for supplementary metadata, use a CC BY 4.0 results file only for the fields it actually contains, and leave CALC/live/CPI/height-cohort values unavailable rather than inventing them.

A future source adapter must include source URL, exact licence, attribution text, retrieval or contribution date, stable IDs, parser version, field completeness and a reproducible checksum. It must preserve nulls and must never fill missing aces, service points, games, rankings, heights or court readings with zero or estimates.

## Explicit cutover blockers

- No current 2023–2026 permissively licensed ATP match-stat feed was verified.
- No strict-open live ATP results/statistics provider was verified.
- No strict-open event-level CPI database was verified.
- The historical CC BY 4.0 repository's ATP-scrape provenance needs resolution before production use; it is not yet treated as an approved source.
- Existing generated assets and app code still contain/use legacy source-derived data. Documentation changes alone do not make the currently released APK or bundled assets fully open-data compliant.

## Related project behaviour that must remain invariant

When an eligible source eventually exists, keep the existing app formulas and eligibility logic unchanged unless a separately reviewed methodology change is requested:

- CALC keeps the current opponent-adjusted expected-ace method, the 0.5 expected-aces floor, seasonal normalisation and profile fallback hierarchy.
- Player Stats keeps Hold% = service games held / service games played, Break% = return games won / return games played, and H+B = Hold% + Break%.
- Historical ranking cohorts continue to use the selected calendar year's own final snapshot; the current year uses its latest available snapshot.
- Live match inclusion continues to exclude doubles, walkovers/no-play and in-progress records; stats-backed retirements remain eligible.
- Missing or unverified data stays missing. No synthetic zero-fill or fuzzy identity joins.

**This audit is a blocker report, not a production cutover.** No data feeds, bundled assets, app runtime behaviour, GitHub Actions workflows, API keys or signing configuration are changed by this documentation update. All changes remain on `research/data-provider-migration-phase-1`; `main` is not modified.
