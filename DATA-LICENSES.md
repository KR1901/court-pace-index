# Data licences, terms and attribution

**Last checked: 11 November 2026.** This document distinguishes the MIT licence for project software from the separate rights that may apply to source data, published ratings and generated datasets. It is a provenance and risk guide, not legal advice.

There is no single licence that covers every data point in this repository. Do not infer that the MIT licence, or one third-party data licence, applies to the entire bundled dataset.

## 1. Original software

Original project code is licensed under the MIT License in [LICENSE](LICENSE), subject to third-party software and asset licences. The MIT grant does not grant ownership of or rights to external datasets and published website content.

## 2. Historical ATP match archive

The historical match-statistics source is the [Jeff Sackmann ATP repository](https://github.com/JeffSackmann/tennis_atp), obtained through the [tennis-sackmann-archive mirror](https://github.com/Aneeshers/tennis-sackmann-archive).

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

## 9. Private repository status does not change source rights

This repository is currently private for personal development and research. Repository privacy is an access-control measure, not a licence or permission from any data provider. It does not change the non-commercial, attribution, share-alike, redistribution or systematic-retrieval restrictions described above. Do not publish the generated dataset or distribute data-derived functionality beyond the rights available for each source. Obtain source permission where the terms require it.

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
