# Data licences, terms and attribution

**Last checked: 17 November 2026.** This document distinguishes the MIT licence for project software from the separate rights that may apply to source data, published ratings and generated datasets. It is a provenance and risk guide, not legal advice.

There is no single licence that covers every data point in this repository. Do not infer that the MIT licence, or one third-party data licence, applies to the entire bundled dataset.

## 1. Original software

Original project code is licensed under the MIT License in [LICENSE](LICENSE), subject to third-party software and asset licences. The MIT grant does not grant ownership of or rights to external datasets and published website content.

## 2. Historical ATP match archive

The historical match-statistics source is obtained through the [tennis-sackmann-archive mirror](https://github.com/Aneeshers/tennis-sackmann-archive), which identifies [Jeff Sackmann's tennis data](https://github.com/JeffSackmann) as the original compilation. This mirror is a distribution channel; it does not create additional rights.

The mirror states that the datasets were compiled by Jeff Sackmann and are redistributed under **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International (CC BY-NC-SA 4.0)**. Its stated conditions require attribution, prohibit commercial use and require share-alike licensing for adaptations. See the [mirror's licence](https://github.com/Aneeshers/tennis-sackmann-archive/blob/main/LICENSE) and the [CC BY-NC-SA 4.0 legal code](https://creativecommons.org/licenses/by-nc-sa/4.0/legalcode).

When reusing archive-derived material, attribute Jeff Sackmann, link to the upstream repository, identify changes, comply with the non-commercial restriction and preserve the required share-alike terms for adaptations. The mirror does not claim ownership of the underlying data or grant rights beyond the upstream licence.

## 3. Current-season TennisMyLife data

The current-season loader may use the [TennisMyLife ATP database](https://github.com/Tennismylife/TML-Database). Its published repository terms describe the datasets as intended for personal, educational and non-commercial research. The public terms limit use to personal, educational and non-commercial research; free downloads do not grant commercial rights. They do not authorise raw-database redistribution or resale. The current terms additionally state that no commercial use is authorised by the public terms and require prior express written permission for commercial services using the data, including model training/validation, statistical predictions and monetisation of derived predictions. They warn that third-party rights may apply.

Accordingly:

- Do not commit or redistribute the raw TennisMyLife CSV or raw database responses.
- Do not treat TML-derived current-season values, profiles or models as commercially cleared by this repository.
- The public terms do not clearly grant a right to sublicense or redistribute TML-derived match records/ratings in a public app. Treat the current-season TML-derived bundled output as **not cleared for onward distribution** until TennisMyLife confirms in writing that this specific use is permitted.
- Obtain prior written permission for commercial use, training/validation of models for commercial services, monetised derived predictions, or broader redistribution where required by the current terms.
- Review any third-party rights separately; TennisMyLife cannot grant rights it does not hold.

The loader records the source URL, retrieval time, row count, byte count and SHA-256 for traceability. A local cache manifest is not a licence.

## 4. Tennis Abstract Surface Speed values

[Tennis Abstract's ATP Surface Speed page](https://tennisabstract.com/reports/atp_surface_speed.html) publicly displays its ace-adjusted tournament ratings and explains the metric. The public page does not itself provide an open-data licence for copying or redistributing its tables.

This project uses Tennis Abstract values as attributed comparison references and clearly labelled fallbacks. Do not describe those values as MIT-licensed, public-domain or freely redistributable. Seek permission before broader republication or reuse beyond what applicable law and the source's terms permit.

## 5. Court Pace Index readings

Published CPI readings are sourced from [courtspeed.com](https://courtspeed.com/). No explicit open-data licence for the readings was identified during this review. The project attributes the source and treats the values as third-party published measurements; this repository does not claim ownership or grant a new licence to the source data. Seek permission before substantial redistribution or commercial reuse.

## 6. Live ATP / Infosys content

The Android app can retrieve live match results and statistics from ATP/Infosys pages. These requests are not an official ATP API and do not create a licence to reuse ATP content.

The [ATP Terms and Conditions](https://www.atptour.com/en/terms-and-conditions) limit general website/content use to individual non-commercial, entertainment and informational purposes. They expressly prohibit systematic retrieval of scores, statistics or rankings to create or compile a collection or database without ATP's prior express written permission. The app's on-device implementation does not itself grant permission for systematic retrieval. Users and operators must review the current ATP terms; this repository does not represent that ATP permission has been obtained.

## 7. Combined and generated datasets

`app/src/main/assets/pace_dataset.json` and `data/latest.json` combine project metadata, CPI readings, CALC-derived ratings and Tennis Abstract reference/fallback values. Current-season CALC inputs may originate from TennisMyLife; historical inputs may originate from the Jeff Sackmann archive.

Because sources have different terms, **do not apply one blanket licence to the generated JSON files**. A derived numerical series does not automatically become MIT-licensed, nor does use of a historical archive licence override the terms of another source. Evaluate the provenance of each season and field before reuse.

## 8. Third-party software and assets

Android/Gradle dependencies, fonts, icons, graphics and any separately sourced assets remain under their own licences. Review dependency metadata and asset notices before redistributing a build.

## 9. Public repository and app distribution

This repository is public and the project publishes Android builds. Public GitHub visibility, a free APK, or the MIT software licence does not grant rights to third-party match data, rankings, ratings, player biographies or published court measurements. Each source must independently permit the relevant collection, transformation, persistence, display and distribution. A source that permits personal research or in-app display does not automatically permit bundling its raw or derived dataset in an APK or downloadable JSON file.

Until a source-specific permission or licence has been confirmed, treat its content as **not cleared for redistribution**. This is a conservative project release gate, not a legal conclusion that every individual fact is copyrightable or that every use is necessarily prohibited.

## 10. Player Stats dataset

The dedicated Player Stats feature uses the annual ATP match files, player roster, heights and dated ranking endpoint published by the [TennisMyLife ATP Match Database](https://stats.tennismylife.org/tennis-match-database). It derives calendar-year player Hold%, Break%, H+B, match results, surface/environment splits and height-group summaries. Each calendar year's Top 100 cohort is loaded from the TML dated ranking API by stable player ID: completed years use that year's final ranking snapshot, while the live year uses its latest available snapshot. Co-ranked players are preserved, so the membership may exceed 100 when the source has ties. Rows with placeholder IDs are counted as unresolved source entries and excluded from player-level height calculations. Each exact snapshot date and identified/unresolved counts are recorded in the asset.

The source's public terms describe use as personal, educational and non-commercial research and do not authorise raw database redistribution or resale. This feature is intended only for this owner's private hobby app and private repository. Raw TML CSVs and API responses remain temporary build inputs and are not committed. Do not publish the derived asset or distribute a public APK containing it without obtaining the necessary source and third-party permissions. The MIT licence applies to original software, not to this dataset. Private repository visibility does not itself grant data rights.

The annual source files, player roster and current ranking snapshot are fetched by `tools/build_player_stats.py`. Indoor/outdoor hard splits use the explicit source environment flag; unknown values remain unknown. Heights are stored in canonical centimetres; the app derives metric and imperial display strings from that one value.

## Source links

- [Jeff Sackmann ATP data](https://github.com/JeffSackmann/tennis_atp)
- [Archive mirror licence](https://github.com/Aneeshers/tennis-sackmann-archive/blob/main/LICENSE)
- [TennisMyLife database and terms](https://github.com/Tennismylife/TML-Database)
- [TennisMyLife ATP Match Database catalogue and data-files endpoint](https://stats.tennismylife.org/tennis-match-database)
- [TennisMyLife dated ranking list](https://stats.tennismylife.org/api/ranking/dates)
- [Tennis Abstract ATP Surface Speed](https://tennisabstract.com/reports/atp_surface_speed.html)
- [ATP Terms and Conditions](https://www.atptour.com/en/terms-and-conditions-app)
- [Creative Commons BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/legalcode)
 
## 11. Candidate replacement providers — not yet production-authorised

The following candidates are being evaluated for a provider migration. Inclusion here is not an assertion that the provider is an open-data source, that all upstream rights are cleared, or that production use has been approved.

### Live Tennis API — licensed service candidate

[Terms of Use](https://livetennisapi.com/terms) grant a limited, non-exclusive, non-transferable and revocable licence to access API responses and use them within the subscriber's own application/service, subject to the active subscription tier. The terms prohibit redistributing raw responses as a feed, bulk dataset or competing API and prohibit storing data beyond what is reasonably necessary to operate the application. Paid credentials are to be kept server-side.

This is a service licence, **not an open-source or open-data licence**. The provider's [data rights notes](https://docs.livetennisapi.com/reference.html) indicate that the provider is not an official ATP partner and does not grant ATP league rights. Before using it as the production source, obtain written confirmation covering this project's intended derivation and persistence of Surface Speed, Player Stats aggregates, offline assets, public APK distribution and any upstream ATP/statistical rights. Do not bundle its raw API responses or expose a paid key in the Android application.

The documentation describes historical ATP/WTA results from 1968–2022, match statistics where recorded, and current/historical match endpoints for 2023 onward. Match-level coverage is field- and tour-dependent. Verify `/history/coverage` and actual per-field availability before relying on any value.

### KickServe — ranking-source candidate

[KickServe's data page](https://kick-serve.com/data/) says its CSV ratings are free to use with a dated citation and asks users to contact the publisher for commercial redistribution. It publishes an `official_rank` field but its player IDs are KickServe-specific and must not be treated as ATP, TML or Sackmann IDs. It is a candidate for the live ranking snapshot only; it does not replace match statistics or historical match files. Its permission terms are not a blanket open-data licence.

### Wikidata — supplementary player metadata

[Wikidata structured data](https://www.wikidata.org/wiki/Wikidata:Licensing) is released under CC0. It can supplement player heights or other basic facts, with provenance recorded per field. It is not a complete or authoritative tennis roster and must not be used to silently impute missing values. Retain the distinction between source-reported, manually verified and unknown heights.

### AcesAPI — not suitable for the required match-stat feed

[AcesAPI's published match example](https://acesapi.com/docs/get-match) currently returns `serve_stats: null` for tennis and explicitly notes that aces, double faults and break-point match statistics are unavailable in that response. Its general service terms allow some derived in-app uses but do not grant league rights. On the evidence reviewed, it cannot replace the match-stat inputs required by Court Pace's ace-based calculation or Player Stats.

### Tennis Abstract, Court Speed and ATP/Infosys

No open redistribution licence has been identified for the Tennis Abstract surface-speed tables or the Court Speed CPI readings. ATP's [Terms and Conditions](https://www.atptour.com/en/terms-and-conditions-app) reserve rights over its scores, rankings and statistics and prohibit systematic retrieval/compilation without prior express written permission. Do not treat an unofficial endpoint, a public HTML page, a scraper, or a third-party API's in-app display permission as a substitute for the underlying data rights.

## 12. Migration release gate

A provider migration must not alter the app's calculation definitions merely to fit a new feed. Before any new source becomes the production default:

1. Record the provider, source URL, retrieved-at timestamp, stable source record ID, licence/terms version and permitted uses for every source family.
2. Confirm in writing that the provider's rights cover both the source fields and the project's intended derived values, caching/persistence, public display and distribution.
3. Map provider-specific IDs through explicit, ambiguity-safe crosswalks. Never join people by an unqualified fuzzy name.
4. Preserve unknown or absent values as unknown. Do not zero-fill aces, service points, games, heights, indoor/outdoor status or rankings.
5. Run shadow builds and compare event/match populations, server-side counts, CALC ratings, seasonal normalisers, Player Stats denominators, height cohorts and Live now behaviour against the current pipeline.
6. Keep the previous production snapshot and adapter available for rollback until the new provider has passed the complete regression suite and a real-device live acceptance test.

The detailed adapter and cutover plan is maintained in [DATA_PROVIDER_MIGRATION.md](docs/DATA_PROVIDER_MIGRATION.md).
