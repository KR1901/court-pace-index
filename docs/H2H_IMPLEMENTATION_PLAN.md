# H2H screen — implementation and data contract

**Status:** Implemented in progress on `feature/h2h-screen-plan`  
**Android stack:** Kotlin + Jetpack Compose  
**Provider:** TennisMyLife ATP player, match and ranking endpoints plus the existing Player Stats archive

## 1. Screen behaviour

The H2H tab lets a user search for and select two ATP players. It displays:

- Head-to-head match wins for both players.
- Current ATP rank, age and height.
- Current calendar-year W/L, win percentage and titles.
- Career W/L, win percentage and titles.
- Independent surface filters for W/L, win percentage and title counts in both YTD and Career sections.
- A newest-first event/match breakdown with dates formatted as `DD/MM/YYYY`.

Both record sections provide the same six choices:

1. **All** — all recorded surfaces.
2. **Hard** — all hard-court matches combined (indoor + outdoor + hard matches whose venue classification is unknown).
3. **Outdoor Hard** — hard matches explicitly classified as outdoor.
4. **Indoor Hard** — hard matches explicitly classified as indoor.
5. **Grass**.
6. **Clay**.

The YTD selector and Career selector are independent; changing one does not change the other. Each selector filters W/L, win percentage and title counts together. Win percentage is wins ÷ (wins + losses) × 100, shown to one decimal place; it is unavailable when the selected player has no eligible W/L denominator. Titles are counted from unique ATP tour-level final wins in the selected period and selected surface.

## 2. Data integration

H2H uses TennisMyLife's published website API at `https://stats.tennismylife.org` and fetches only the selected players' data at runtime. It does not add a second copy of provider match records to the APK or repository.

| Screen data | Endpoint / local data | Use |
|---|---|---|
| Player search | `GET /api/h2h/search?q=` | Search by player name; returns stable ATP ID, name and country |
| Pair meetings | `GET /api/h2h?player1={id}&player2={id}` | All recorded meetings for the selected pair |
| Player profile | `GET /api/players?id={id}` | Name, country, date of birth and height |
| Player match history | `GET /api/players/performance?id={id}` | Match-level final results used to count titles |
| Current ranking | `GET /api/players/current-ranking?id={id}` | Latest ranking record and its publication date |
| YTD/Career W/L | Existing `PlayerStatsRepository` data, including its live cache | Complete annual W/L aggregates and validated surface/environment buckets |

The provider's player match-history API response includes result rows and a `team_event` flag, but the Prisma match response does not include the CSV's per-match `indoor` field. Therefore the app does **not** guess indoor/outdoor venue from a match row. YTD/Career W/L uses the existing Player Stats dataset, whose builder and live updater parse the source indoor/outdoor field and aggregate it into `hardAll`, `hardIndoor` and `hardOutdoor` buckets. The builder also records a compact `meta.tournamentIndoorById` map for hard-court tournament IDs, using only consistent explicit source flags; conflicting flags are stored as unknown. The live updater extends this map as current-season ATP rows are published. H2H uses that map to classify title finals for Indoor Hard and Outdoor Hard; hard finals with unknown venue remain in Hard and All but are excluded from the two narrower venue splits. No match-level venue is inferred.

The provider requests are read-only GET calls. Network calls run off the UI thread, use bounded timeouts, and surface errors in the H2H screen rather than fabricating values. Optional biography/ranking request failures leave those facts unavailable without suppressing match history.

## 3. Calculations and definitions

### Head-to-head wins

The pair-match response is counted by stable ATP player ID. Every eligible meeting contributes one win to the recorded winner. The meeting list is sorted newest first; no name-based matching is used for results.

### YTD W/L

YTD means the current calendar year from the latest Player Stats archive/cache. Results are read from that season's existing aggregate bucket for the selected filter. It is a calendar-year view, not a rolling 52-week period.

### Career W/L

Career is the sum of the player's annual ATP tour-level buckets across all seasons in the Player Stats archive. The W/L formula and match population stay identical to the existing Player Stats feature.

### H2H set record and Set Win%

The H2H match-history rows include a set-by-set `score`, with the winner's score listed first and the loser's second. The H2H score card derives each player's set record across the pair's recorded meetings:

- **Set W/L** is the player's total completed sets won–lost.
- **Set Win%** is completed sets won divided by all completed sets played, multiplied by 100; the UI displays one decimal place.
- Standard sets count when a player reaches at least six games and wins by two, including advantage sets, or when the set ends 7–6.
- A deciding match tiebreak such as `[10-8]` counts as one completed set.
- In retirements, completed sets before the retirement count, but an unfinished final set (for example `3-2 RET.`) does not.
- Missing or unparseable scores contribute no sets. If no completed set scores are available for the selected rivalry, Set W/L and Set Win% display as unavailable rather than a fabricated zero record.

The set calculation is separate from match W/L, YTD/Career records, surface filters and title calculations. It does not alter any existing player-statistics aggregate.

### Surface filters

The H2H W/L surface selectors reuse the existing, validated Player Stats bucket keys:

| H2H option | Player Stats bucket | Meaning |
|---|---|---|
| All | `all` | All recorded surfaces |
| Hard | `hardAll` | Indoor + outdoor hard, including hard matches with unknown venue setting |
| Outdoor Hard | `hardOutdoor` | Hard with source setting explicitly marked outdoor |
| Indoor Hard | `hardIndoor` | Hard with source setting explicitly marked indoor |
| Grass | `grass` | Grass |
| Clay | `clay` | Clay |

A hard match with unknown indoor/outdoor setting remains in **Hard** but is not placed into either venue-specific split. Missing or unknown setting is never guessed. The year aggregates also preserve the existing Player Stats rules and source eligibility; H2H does not change those calculations.

### Titles

Titles are counted from the player's match-history endpoint as unique tournament wins in the final round (`F` or `Final`). A match with `team_event = true`, Davis Cup level, or a recognized team-event name is not counted as an ATP singles title. Only ATP tour-level final records (`G`, `M`, `A`, `F`, `250`, `500`) are considered. A semifinal, round-robin win, Challenger/qualifying title, team event, walkover, no-play result or final loss does not count. Titles are deduplicated by tournament and season. The title count is filtered by year (YTD or Career) and by the selected surface. Hard includes all hard-court finals, including unknown venue classifications; Indoor Hard and Outdoor Hard require a consistent explicit venue classification from the tournament's source CSV rows. For a tournament with conflicting or unavailable venue flags, its hard title is not assigned to either narrower split. The title counts are independently deduplicated by tournament and season.

### Current rank, age and height

- Current rank comes from the latest ranking endpoint response. Inactive/unranked players display `Unranked`; if the endpoint itself is unavailable, the UI displays `Unavailable`. The ranking publication date is displayed when supplied.
- Age is calculated in whole years from the player's date of birth as of the device's current local date. Missing or future DOB is unavailable.
- Height is displayed in centimetres and feet/inches. Missing or out-of-range height is unavailable.

### Meeting date and event breakdown

The source's `tourney_date` is the tournament-week/event date, not a verified individual match-day timestamp. It is rendered as `DD/MM/YYYY` without timezone conversion and explicitly labelled **Tournament date** in the UI. Each meeting displays event, tournament date, round, surface, winner, loser and score (when supplied).

## 4. Architecture

- `core/h2h/H2HData.kt`: provider models, player search, API calls, match parsing and no-play filtering.
- `core/h2h/H2HAnalytics.kt`: pure aggregation lookups, title counts, age, height and date calculations.
- `ui/screens/H2HScreen.kt`: player selectors, H2H result, player facts, independent YTD/Career filters, and lazy meeting list.
- `ui/AppNav.kt` and `ui/glass/Glass.kt`: sixth H2H tab, route, responsive six-item tab bar and dedicated navigation glyph.

The module does not modify `PaceData`, `AppViewModel`, CPI/CALC calculations, Live Now, the Player Stats schema, existing Player Stats builder/refresh logic or the match archive. It reads the existing Player Stats archive/cache for W/L aggregates and uses the provider API for H2H meetings, titles, profile facts and ranking.

## 5. Regression and acceptance checks

- Pair wins are counted by stable IDs and match the displayed meeting list.
- YTD is limited to the current calendar year; Career sums annual archive buckets.
- YTD and Career surface selectors retain independent state.
- Hard combines indoor, outdoor and unknown-environment hard; Indoor Hard and Outdoor Hard require an explicit source classification.
- Grass and Clay splits use the existing source aggregates.
- Titles count deduplicated final-round wins, exclude team events, and follow the selected YTD/Career surface.
- Win percentage is wins divided by wins plus losses, with no fabricated value when the denominator is zero or unavailable.
- Indoor/Outdoor Hard titles use a source-derived tournament venue lookup; unknown or conflicting venue flags are excluded from the narrower venue-specific title splits.
- Age, rank and height remain unavailable when source facts are absent; no estimates or zero filling.
- Meeting dates use `DD/MM/YYYY`, newest first.
- Search, loading, network-error, no-meetings and empty-selection states are handled.
- The six-item bottom navigation remains usable on compact screen widths.
- Existing screens, Player Stats refresh, live ratings, CALC data and release workflows remain unchanged.

## 6. Source-use note

This feature uses the same TennisMyLife data family already used by the repository's Player Stats pipeline. The strict-open-data requirement from PR #24 is **not** a requirement for this feature. This does not change TennisMyLife's published source terms: the provider describes its data as personal/educational/non-commercial research material and does not authorize commercial use or redistribution of raw database files. The implementation fetches selected player data at runtime and does not publish a bundled H2H match database. Any commercial deployment or broader data redistribution still requires the relevant permissions.

Source documentation:
- [TennisMyLife H2H and player statistics site](https://stats.tennismylife.org/)
- [TennisMyLife ATP Match Database and field documentation](https://stats.tennismylife.org/tennis-match-database)
- [TennisMyLife database terms and credits](https://tennismylife.github.io/TML-Database/)
