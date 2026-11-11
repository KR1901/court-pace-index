# Player Stats

## Purpose and isolation

Player Stats is a separate annual ATP statistics module. It does not reuse or modify CPI readings, tournament Surface Speed ratings, the live ATP calculation, its baseline, `PaceData`, or `AppViewModel`. The Android screen reads its own `player_stats.json` asset through `PlayerStatsRepository`.

The independent builder is `tools/build_player_stats.py`. It downloads source CSVs into the temporary `.cache/player_stats/` directory and writes two byte-identical outputs:

- `data/player_stats.json`
- `app/src/main/assets/player_stats.json`

Raw CSVs and raw ranking API responses are not committed. The dedicated Player Stats workflow stages only these two JSON outputs; it does not run the CPI/Surface Speed data builders or change `pace_dataset.json` / `data/latest.json`.

## Player directory and calendar-year coverage

The directory includes every player with at least one eligible ATP tour-level singles main-draw result in the source archive, plus a member of the current Top 100 if that player is not present in the match archive. It does not include roster-only players with no eligible archive result. Players remain searchable when their match result exists but their height or service/return statistics are missing.

Included ATP competition levels are Grand Slams, Masters, ATP 500, ATP 250 and the ATP Finals. Qualifying, Challenger, Futures, doubles, Davis Cup and exhibitions are excluded. This keeps the population consistent with the ATP tour-level main-draw analysis.

The year selector uses the calendar year in the tournament ID, with the tournament date as fallback only when that ID has no valid year. It is not a rolling 52-week view. Tour-level match results are available across the historical archive; reliable service/return box-score fields are much more complete from the modern match-stat era, generally 1991 onward. Each season and surface retains its real denominators, and unavailable values are left blank rather than estimated.

Played retirements count in W–L, but partial-match service/return statistics are excluded. Walkovers, no-play defaults and abandoned matches are excluded. A played result with missing or inconsistent service/return counters still contributes to W–L but not to Hold% or Break%.

## Definitions and ranking rules

For each player, season and surface/environment bucket:

- **Hold %** = service games held ÷ service games played × 100.
- **Break %** = return games won ÷ return games played × 100.
- **Combined H+B** = Hold % + Break %.

H+B is a combined index in percentage points, not a separately normalized probability and not the average of the two percentages.

The match archive supplies service games played and break points faced/saved for each player. The builder derives:

- Service games held = that player's service games played − (that player's break points faced − break points saved).
- Return games won = opponent's break points faced − opponent's break points saved.
- Return games played = opponent's service games played.

The six service-game / break-point counters must be present and internally consistent. Breaks conceded cannot exceed service games played, and break points saved cannot exceed break points faced. Invalid or missing counters never become zeroes. Aggregation uses total game numerators and denominators across the season; it does not average percentages match by match.

The three descending leaderboards are Hold%, Break% and H+B. The default qualification threshold is 100 relevant games: service games for Hold, return games for Break, and at least 100 of each for H+B. The UI allows 0, 50, 100 or 250 games. Players below the selected threshold remain searchable and are labelled low-sample/unranked.

## Surface and indoor/outdoor filters

The screen provides All surfaces, Clay, Grass, Outdoor hard, Indoor hard and All hard. Carpet is also retained as a separate filter rather than silently classified as hard.

TennisMyLife's ATP match schema includes a surface field and a separate indoor/outdoor field. The builder recognizes its documented Yes/No values as well as equivalent I/O, Indoor/Outdoor, Boolean and 1/0 encodings. A missing or unrecognized setting remains unknown: it is not guessed as indoor or outdoor. Unknown-setting hard matches remain in the combined All hard aggregate and are counted separately in coverage metadata.

## Current Top 100 height cohort

The height-group analysis uses the same **current ATP Top 100 cohort** for every selected historical year. It answers how the players who are currently in the Top 100 performed in earlier seasons; it is not that historical season's Top 100. The interface displays the exact ranking snapshot date and explicitly labels this survivor/current-cohort comparison.

The builder reads TML's dated ranking archive:
- `https://stats.tennismylife.org/api/ranking/dates` to find the latest available ranking date that is not in the future.
- `https://stats.tennismylife.org/api/ranking?date=YYYY-MM-DD` to read the corresponding ranking list.

The ranking's stable player ID is joined directly to the match/player database ID. The pipeline requires ranks 1–100 exactly once and 100 unique IDs. It does not fuzzy-match player names or use the stale mirrored January 2026 rankings file as a substitute. The cohort is refreshed when a newer dated ranking snapshot is available; the precise date is stored in the asset.

## Heights and bands

Height is stored once in centimetres. The ATP player roster is preferred; when its height is missing, the latest valid height recorded in an eligible match row is used. Implausible values outside 140–220 cm are treated as missing. The Android screen derives metres and feet/inches from that one value. Missing height is not imputed and appears in an unassigned-height count.

The four bands are exhaustive and non-overlapping:

| Group | Canonical height | Display |
|---|---:|---|
| 1 | Up to 182 cm | Under 1.83 m / under 6′0″ |
| 2 | 183–190 cm | 1.83–1.90 m / 6′0″–6′2.8″ |
| 3 | 191–194 cm | 1.91–1.94 m / 6′3.2″–6′4.4″ |
| 4 | 195 cm and above | 1.95 m+ / approximately 6′5″+ |

Each group reports its player count, players with selected-year match results, players with valid service/return statistics, Hold%, Break%, H+B, W–L, win percentage and match count. Group rates are pooled from the group's total game counts, not the unweighted mean of player percentages. Surface filters apply to the group comparison as well as the player leaderboard.

These are descriptive associations, not proof that height causes a performance advantage. Group sizes, valid-stat coverage, games played and the selected current-cohort date must be considered when interpreting a leading group.

## Source, rights and personal use

Match results, match statistics, indoor/outdoor setting and player heights come from the [TennisMyLife ATP Match Database](https://stats.tennismylife.org/tennis-match-database). The annual CSV files and player roster are listed by its [data-files endpoint](https://stats.tennismylife.org/api/data-files); the dated ranking cohort comes from its ranking API.

The source's public terms limit use to personal, educational and non-commercial research and do not authorize raw database redistribution/resale. This feature is intended for the owner's private hobby app and private repository. Raw source CSVs remain temporary local build inputs. Do not publish the derived asset or distribute an APK containing it without obtaining the necessary source and third-party permissions. Private repository visibility does not itself grant data rights, and the project's MIT software licence does not apply to this dataset.

## Refresh and verification

Run:

```bash
python3 -m unittest tools/test_build_player_stats.py -v
python3 tools/build_player_stats.py
```

The dedicated `.github/workflows/refresh-player-stats.yml` workflow tests the parser and formulas, validates the exact dated 100-player cohort, verifies stable IDs and unique player-season rows, checks valid game numerators/denominators, confirms the height and archive-player counts, and verifies the two JSON outputs are byte-identical. It commits only `data/player_stats.json` and `app/src/main/assets/player_stats.json`. It never invokes `tools/compute_speed.py`, `tools/build_dataset.py` or updates the existing CPI/Surface Speed datasets.

The archive workflow runs weekly and refreshes the private repository asset. The installed Android app does not need a new APK for current-season match updates.

### Automatic live updates

The app uses a separate Player Stats-only updater. It reads TennisMyLife's `api/data-files` manifest and compares the published modification time for:

- `ongoing_tourneys.csv` — newly completed ATP matches from ongoing tour events.
- `YYYY.csv` for the current calendar year — results once they are included in the annual archive.

The two feeds are reconciled using the same stable identity key: tournament ID, match number and the two sorted ATP player IDs. The static archive builder records the current-season keys it already contains. The on-device updater stores the keys it has applied in a private app cache, so moving a match from the ongoing feed into the annual file cannot double-count it.

When a new eligible match appears, both players' W–L, service-game and return-game counters are updated for All surfaces and the corresponding surface/environment buckets. New player IDs are added to the directory automatically; this is not limited to the current Top 100. Played retirements count in W–L but partial-match game statistics are excluded. Walkovers/no-play results are excluded. Invalid or missing game counters contribute W–L only and are never guessed. The updater tracks result keys separately from valid-stat keys: when a final score arrives before the provider's game counters, W–L is counted once and Hold%/Break% are enriched later when those counters appear.

While any app screen is in the foreground, the app checks the source immediately and approximately once per minute. The Player Stats screen observes the shared live state, so updates continue while another tab is selected. WorkManager also schedules an immediate one-time check on app launch and connected-network background checks with a 15-minute periodic interval. Android may defer background work for battery and system scheduling reasons; foreground checks are more frequent. If the device is offline or the provider is unavailable, the last saved statistics remain visible and the app retries automatically.

The provider describes the ongoing feed as real-time, but also describes its database refresh cadence as daily/ideally real-time. There is no published delivery-time guarantee. Therefore the app can ingest a match on the first poll after the provider publishes it, but cannot guarantee that every match appears at the exact second the on-court match ends. The interface distinguishes the bundled archive baseline from matches merged by live sync and shows the last successful addition/check state.

The live updater is isolated from CPI, Surface Speed, `PaceData`, and the existing `AppViewModel` refresh loops. It uses the same private personal/non-commercial source boundary as the bundled archive.
