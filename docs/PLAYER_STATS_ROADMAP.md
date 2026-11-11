# Player Stats — Research & Implementation Roadmap

Status: research complete; implementation gated on source clearance and tournament indoor/outdoor metadata.

This document scopes a new, additive Player Stats feature. It is deliberately isolated from the existing CPI and Surface Speed engines.

## 1. Agreed product scope

### Player directory and rankings
- Include every player with eligible ATP main-tour singles match records in the selected archive, not only the Top 100.
- Use calendar-season/year data (2026, 2025, etc.), not a rolling 52-week window.
- Provide three independently sorted leaderboards:
  1. Hold % (serve).
  2. Break % (return games won).
  3. Combined H+B = (Hold % + Break %) / 2.
- Surface filters: All surfaces, Clay, Grass, Outdoor Hard, Indoor Hard, and All Hard (Indoor + Outdoor).
- Display a player's height using canonical centimetres converted to metres and feet/inches. Store only centimetres.
- Include match count, games-played denominators, and source/coverage notes. Keep players in the directory even when their sample is too small to qualify for a ranked position.

### Height groups
- The cohort is the **current ATP Top 100**, fixed from one explicitly dated ranking snapshot.
- The same 100-player cohort is used for every selected historical year. This answers: "How did the players who are currently Top 100 perform in earlier seasons?"
- This is not the historical Top 100 for each season. The UI must state the cohort snapshot date and disclose survivorship/selection bias.
- Bands are exhaustive and non-overlapping, using integer centimetres:
  - Group 1: below 183 cm.
  - Group 2: 183–190 cm inclusive.
  - Group 3: 191–194 cm inclusive.
  - Group 4: 195 cm and above.
- Show Hold %, Break %, Combined H+B, wins, losses, win %, matches, service games and return games. Show each group's player count and missing-height count.
- Compare each group by the same six surface filters as the player leaderboard.

The displayed band descriptions should be:
- Under 1.83 m (under 6′0″)
- 1.83–1.90 m (6′0″–6′2.8″)
- 1.91–1.94 m (6′3″–6′4.4″)
- 1.95 m+ (approximately 6′5″+)

## 2. Research findings and data-source decision

### Jeff Sackmann ATP archive — best base for the independent player database
The upstream README documents:
- Stable player IDs and a player table with name, country, birth date, and height in centimetres.
- Weekly rankings and match-level player/rank fields.
- Tour-level main-draw match statistics generally from 1991 onward; some rows/seasons have missing statistics.
- The archive's CC BY-NC-SA 4.0 licence: attribution, non-commercial use, and share-alike for derivatives.

Source: https://github.com/Aneeshers/tennis-sackmann-archive/blob/main/atp/UPSTREAM_README.md
Field dictionary: https://github.com/Aneeshers/tennis-sackmann-archive/blob/main/atp/matches_data_dictionary.txt

The match schema contains the fields needed to calculate hold and break rates (service games, break points saved and break points faced), along with match surface, player IDs and height values. It does **not** contain an indoor/outdoor flag.

A direct inspection of the current public mirror's ranking files found that the 2020s weekly ranking file ends at 29 December 2025, while its separate current-ranking file only had a 5 January 2026 snapshot. Neither should be treated as the current 9 November 2026 Top 100 without a verified update.

### TennisMyLife — has the missing indoor flag, but needs written permission/clarification
Its documented match schema includes:
- ATP player IDs, player heights in cm, surface, and an `indoor` Yes/No field.
- `w_SvGms` / `l_SvGms`, and break-points-faced/saved fields for both players.

Source and schema: https://stats.tennismylife.org/tennis-match-database

Its public repository notice says the database is intended for educational, analytical and research use, warns that redistribution/commercial use/selling of the raw database without permission may violate rights, and says usage is non-commercial unless explicitly permitted. Since the intended app distributes an APK containing derived player data, obtain written confirmation covering the exact derived aggregates, cohort IDs, and indoor/outdoor metadata before using TML as a production source.

Terms/reference: https://github.com/Tennismylife/TML-Database

### ATP official leaderboards — useful for definition and validation, not a scraping source
ATP's leaderboard exposes calendar-year selectors and All Surfaces, Clay, Grass, and Hard filters. It shows Service Games Won and Return Games Won percentages. It does not offer separate Indoor Hard and Outdoor Hard filters in the leaderboard.

ATP terms prohibit systematic retrieval of scores, statistics or rankings to create or compile a collection/database without prior express written permission. Use ATP pages for metric-definition research and small, manual validation only unless ATP permission is obtained.

Leaderboard: https://www.atptour.com/en/stats/leaderboard?boardType=return
Terms: https://www.atptour.com/en/terms-and-conditions

### Cito API — technical candidate, not a rights-clearance substitute
Cito's public terms allow API data to be displayed in applications with attribution, but the terms describe third-party sourced data and disclaim responsibility for upstream delays or inaccuracies. This is not evidence that ATP or other rights-holder permissions have been obtained. Its visible tournament metadata documents surface but does not establish the required historical indoor/outdoor classification. Before using Cito for an offline bundled dataset, obtain written clarification on caching, retention, and the proposed player-stat displays.

Terms: https://citoapi.com/terms/
Tennis API docs: https://dashboard.citoapi.com/docs/api/tennis/

### Current Top-100 cohort snapshot
The latest ranking date located during this research was Monday, 9 November 2026. Independent ranking trackers identify this as the latest weekly ATP list. However, the production dataset must obtain the 100 player IDs and ranks from a source whose use and storage are permitted; do not scrape ATP rankings automatically under the current ATP terms, and do not use the stale Sackmann mirror snapshot as a substitute.

Candidate date reference: https://pythiatennis.com/rankings/atp/
ATP rankings page: https://www.atptour.com/en/rankings/singles

### Height research
A 2019 observational study of the ATP Top 100 found surface-dependent differences: taller players showed advantages in some serve outcomes, while the shortest group had an advantage in second-serve return points on clay. Rankings were broadly comparable across height groups. This supports analysing serve and return separately by surface; it does not establish one universally optimal height band.

Study: https://doi.org/10.1007/s12662-019-00612-2
Repository record and abstract: https://open.metu.edu.tr/handle/11511/42124

## 3. Metric definitions

For each player, season, and surface/environment bucket, aggregate games from eligible match rows. Do not average match percentages.

- Hold % = service games held / service games played × 100.
- Break % = return games won / return games played × 100.
- Combined H+B = (aggregate Hold % + aggregate Break %) / 2.

Where match-stat fields are complete and internally consistent:
- Breaks conceded by a player = that player's break points faced − break points saved.
- Service games held = that player's service games played − breaks conceded.
- Return games won = opponent's break points faced − opponent's break points saved.
- Return games played = opponent's service games played.

These derivations must be validated against score/service-game invariants and source samples before production. Do not silently turn missing, inconsistent or partial statistics into zeroes.

For each cohort group:
- Group Hold % = sum(group service games held) / sum(group service games played).
- Group Break % = sum(group return games won) / sum(group return games played).
- Group Combined H+B = average of those two group-level rates.
- Group W-L and win % are shown separately from game-rate metrics.

Ranking eligibility threshold is not final yet. Proposed initial experiment: 100 service games for Hold ranking and 100 return games for Break ranking; Combined requires both. Run sensitivity comparisons at 50, 100 and 200 games before choosing the default. All players remain searchable, with a visible low-sample / unranked status.

Walkovers/no-play are excluded from performance and W-L. Retirements count as match results; include their service/return statistics only when the match statistics pass completeness and consistency checks. Tiebreaks are not service games.

## 4. Data model and pipeline

Create a completely separate player data asset (proposed path: `data/player_stats.json`, copied to `app/src/main/assets/player_stats.json`). Do not extend `pace_dataset.json` or add player statistics to existing tournament readings.

Proposed compact schema:
- `meta`: schema version, generated timestamp, years, source manifest, licence notices, coverage summary, ranking snapshot date.
- `players[]`: stable player ID, display name, country, nullable `heightCm`, height provenance.
- `seasonStats[]`: player ID, season, surface, indoor flag, matches, wins, losses, service games played/held, return games played/won, and stat-source coverage.
- `currentTop100[]`: player ID, rank, and the exact ranking snapshot date/source.

Build pipeline (new independent script, e.g. `tools/build_player_stats.py`):
1. Load only the permitted ATP match/player/ranking sources and retain their source manifest.
2. Join using stable player IDs; never fuzzy-match names for production identity.
3. Create a tournament-edition environment map only from an approved source. Hard + indoor maps to Indoor Hard; Hard + outdoor maps to Outdoor Hard. Unknown environment remains unknown and is not guessed.
4. Derive counters and annual/surface aggregates; retain source denominators and coverage.
5. Validate player IDs, duplicate rows, height conflicts, sample consistency, ranking cohort cardinality, and year/surface completeness.
6. Write the independent player asset; never overwrite or regenerate the existing pace dataset.

The 2026 ranking cohort is a single snapshot. On a later refresh, replace the cohort only when a newer ranking snapshot is lawfully available, and update the displayed as-of date.

## 5. Android architecture (additive only)

Keep all existing CPI and Surface Speed files and calculations unchanged.

New files/components proposed:
- `core/player/PlayerStats.kt`: serializable player/stat data models.
- `core/player/PlayerStatsAnalytics.kt`: pure Kotlin aggregations, ranks, filters, height bands and formatters.
- `data/PlayerStatsRepository.kt`: loads/validates the independent asset and optionally handles a separate player-data update path.
- `ui/PlayerStatsViewModel.kt`: player-screen state only.
- `ui/screens/PlayerStatsScreen.kt`: ranking and height-group modes.
- Optional `ui/screens/PlayerDetailScreen.kt`: yearly history, height and source/coverage detail.

Navigation change: append a fifth Players tab and a new route. Do not reorder or repurpose the four existing tabs. Prefer a separate PlayerStatsViewModel/repository over changing the existing AppViewModel's live refresh path.

The existing project remains the source of visual styling; reuse its theme/components without changing their behavior.

## 6. Regression and data-quality gates

### Data pipeline gates
- Exactly 100 unique player IDs in the dated Top-100 snapshot; all IDs resolve to the player table.
- Every player height is either a sourced centimetre value or explicitly missing; no inferred values.
- Match rows are unique by tournament/match/player identity.
- All rates are in [0, 100], with denominators greater than zero.
- Service games held never exceed service games played.
- Return games won never exceed return games played.
- Per-match break/service-game arithmetic is internally consistent.
- Missing-stat rows, retirements, walkovers, carpet and unknown environments are handled explicitly.
- Produce year × surface coverage tables and a height-coverage report before shipping.

### Unit/screenshot/regression gates
- Tests for the game-rate formulas, group aggregation, rank ordering, ties, low-sample qualification, missing height and all height-band boundaries (182/183/190/191/194/195 cm).
- Screenshot tests for Rankings and Height Groups in light and dark themes.
- Existing CPI, Surface Speed, live ATP/Infosys discovery, live calculation diagnostics, tournament detail and navigation tests must remain green.
- Verify no change to current CPI/Surface Speed dataset hashes or production formulas during the Player Stats feature PR.
- New data refresh workflow must write only the player asset/data files; do not change the existing refresh-live or speed-calculation workflow.

## 7. Staged implementation plan

1. **Source clearance and cohort snapshot (gate):** obtain written permission/clarification for the data source that supplies indoor/outdoor status and for the latest current Top-100 IDs. Decide whether the derivative asset can be shipped in the APK under the relevant terms.
2. **Coverage prototype:** generate a local report from a permitted archive (no raw data committed) with player counts, year × surface coverage, height completeness, and the 50/100/200-game leaderboard sensitivity comparison.
3. **Independent generator and tests:** build the player asset separately and verify all invariants.
4. **Pure analytics layer:** implement and test leaderboard and height-group calculations without touching existing analytics.
5. **UI and navigation:** add the new screen and fifth tab.
6. **Regression audit and APK:** run full CI, compare the four existing screens and live-engine behavior against the baseline, then release only after every gate passes.

## 8. Explicit non-goals

- No changes to CPI calculations, Surface Speed formulation, player profiles used by the live ace engine, ATP/Infosys clients, live event discovery, tournament diagnostics, or current data updater.
- No replacement of the app's existing dataset format.
- No scraping of ATP leaderboards/rankings for bulk storage without prior express written permission.
- No guessed indoor/outdoor labels, guessed heights, or zero-filled missing statistics.
- No "ideal height" conclusion from raw group means alone; results are observational and should disclose cohort selection, player counts, games and surface sample sizes.

## 9. Decision summary

The base player-stat engine is technically feasible from the licensed non-commercial Sackmann archive, with service/return match statistics generally available from 1991 onward. The requested complete feature is **not yet data-ready** because the archive lacks indoor/outdoor classification and the current ranking mirror is stale. TennisMyLife has the needed indoor flag and game-stat fields but requires written permission/clarification for the intended derived-data distribution. ATP's own terms also prohibit systematic statistics/ranking collection without permission.

Therefore the next technical work should be a local data-coverage prototype only after the source path is chosen. Do not merge a partial or guessed dataset into the production app.
