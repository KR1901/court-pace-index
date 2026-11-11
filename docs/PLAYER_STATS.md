# Player Stats

## Purpose and isolation

The Player Stats screen is a separate annual ATP statistics module. It does not reuse or modify the CPI readings, tournament Surface Speed ratings, live ATP calculation, live baseline, `PaceData`, or `AppViewModel`. It loads its own `player_stats.json` asset through `PlayerStatsRepository`.

The source builder is `tools/build_player_stats.py`. It downloads source CSVs into the temporary `.cache/player_stats/` directory, validates and aggregates them, and writes two byte-identical derived files:

- `data/player_stats.json`
- `app/src/main/assets/player_stats.json`

The raw CSVs and API responses are not committed.

## Coverage and season key

The directory begins with the player records in TennisMyLife's `ATP_Database.csv` and adds any match participant not present in that roster. Players remain in the directory even when they have no eligible match or no usable height.

Only ATP tour-level men's singles main-draw matches are aggregated. The included tournament-level codes are `G`, `M`, `A`, `F`, `250` and `500`. Qualifying, Challenger, Futures, doubles, Davis Cup and exhibition records are not included.

Every record belongs to a calendar season. The builder uses the four-digit year at the start of the tournament ID, falling back to the tournament date only when the ID does not contain a valid year. This is a calendar-year selector, not the rolling last-52-weeks window.

Match results are counted only for completed matches. Walkovers, retirements, defaults and abandoned matches are excluded. A completed result remains in wins/losses when its service/return stat line is missing or invalid; that match contributes no hold/break games.

## Definitions

A player's own service-game hold is calculated from that player's service games and break points faced/saved:

- `service games won = service games played - (break points faced - break points saved)`
- `Hold % = service games won / service games played × 100`

Return performance is calculated from the opponent's service-game denominator and the break points the player converted:

- `return games won = opponent break points faced - opponent break points saved`
- `Break % = return games won / return games played × 100`

The combined ranking is the conventional `H+B` index: `Hold % + Break %`. It is a combined index in percentage points, not a separately normalized probability. It is ranked in descending order.

For each player/year/surface bucket, the asset stores raw game numerators and denominators, match wins/losses, and the count of matches with valid serve/return stats. Percentages are calculated from those totals in the app. A missing or internally inconsistent match-stat line is never imputed.

Leaderboard qualification uses a default minimum of 100 relevant service games for Hold, 100 return games for Break, or 100 of each for H+B. The UI allows 0, 50, 100 or 250 games. Players below the selected threshold remain searchable and are labelled low-sample/unranked.

## Surface and environment filters

- All surfaces: every recognized surface and records whose surface is unavailable.
- Clay and Grass: their own surface records.
- Outdoor hard: hard courts with an explicit outdoor flag.
- Indoor hard: hard courts with an explicit indoor flag.
- All hard: indoor and outdoor hard together, including hard rows whose setting is unavailable.
- Carpet: retained separately and never silently classified as hard.

An absent or unknown indoor/outdoor flag does not become either indoor or outdoor. Such hard-court rows are retained in the all-hard aggregate and tracked in a separate unknown-setting bucket.

## Heights and the current Top 100 cohort

Height is stored once in centimetres. The ATP player database is preferred; where it has no valid height, the builder uses the most recent valid match-record height. Values outside the plausible 140–220 cm range are treated as missing. The app displays metres and feet/inches from that one value.

The height bands use exhaustive centimetre cutoffs so no player falls through a gap:

| Group | Height in centimetres |
|---|---:|
| Under 1.83 m | ≤182 |
| 1.83–1.90 m | 183–190 |
| 1.91–1.94 m | 191–194 |
| 1.95 m+ | ≥195 |

The height comparison uses the same **current Top 100** cohort when the user selects any historical calendar year. It does not substitute that historical year's Top 100. The ranking cohort is pinned in `data/current_atp_top100.csv` to the 9 November 2026 ranking snapshot: [Tennis.esp.br ATP ranking archive](https://www.tennis.esp.br/en/rankings?circuito=ATP&data=2026-11-09). The app displays this snapshot date. Refresh the CSV and its source/date metadata when a newer current cohort is to be adopted.

Group percentages are pooled from all games in that height band: total service games won divided by total service games played, and total return games won divided by total return games played. They are not the unweighted mean of player percentages. Each group also reports its player count, number with selected-year data, W–L record, win percentage and match count. Players with missing height are reported as unassigned and are not put into a guessed band.

## Source and use limitations

Match results, match statistics and player heights are sourced from the [TennisMyLife ATP Match Database](https://stats.tennismylife.org/tennis-match-database), whose annual ATP files and player database are listed at [its data-files endpoint](https://stats.tennismylife.org/api/data-files).

The source's published terms limit use to personal, educational and non-commercial research. Raw database redistribution/resale is not authorised by those public terms, and third-party rights may also apply. This feature is intended only for the owner's private hobby app and private repository. Do not publish the derived asset or distribute a public APK containing it unless the required source permissions are obtained. The project's MIT software licence does not apply to this data.

## Refresh and verification

Run:

```bash
python3 -m unittest tools/test_build_player_stats.py -v
python3 tools/build_player_stats.py
```

The dedicated `.github/workflows/refresh-player-stats.yml` workflow validates that the bundled and published JSON files are byte-identical, that exactly 100 unique Top-100 IDs are resolved, that source player IDs are unique, that game numerators do not exceed their denominators, and that the generated asset contains both match results and valid serve/return stats. It stages only `data/player_stats.json` and `app/src/main/assets/player_stats.json`; it does not invoke `tools/compute_speed.py`, `tools/build_dataset.py`, or alter `pace_dataset.json` / `data/latest.json`.

The Top-100 cohort is a pinned snapshot, not an automatically updated weekly feed. A source or schema change must fail closed rather than silently remap a player by a fuzzy name match.
