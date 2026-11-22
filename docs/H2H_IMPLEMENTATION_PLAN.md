# H2H screen — implementation and data contract

**Status:** Implemented in progress on `feature/h2h-screen-plan`  
**Android stack:** Kotlin + Jetpack Compose  
**Provider:** TennisMyLife ATP player, match and ranking endpoints

## 1. Screen behaviour

The H2H tab lets a user search for and select two ATP players. It displays:

- Head-to-head match wins for both players.
- Current ATP rank, age and height.
- Current calendar-year W/L and titles.
- Career W/L and titles.
- Independent surface filters for the YTD and Career sections.
- A newest-first event/match breakdown with dates formatted as `DD/MM/YYYY`.

Both record sections provide the same six choices:

1. **All** — all recorded surfaces.
2. **Hard** — all hard-court matches combined (indoor + outdoor + hard matches whose venue classification is unknown).
3. **Outdoor Hard** — hard matches explicitly classified as outdoor.
4. **Indoor Hard** — hard matches explicitly classified as indoor.
5. **Grass**.
6. **Clay**.

The YTD selector and Career selector are independent; changing one does not change the other.

## 2. Data integration

H2H uses TennisMyLife's existing website API at `https://stats.tennismylife.org` and fetches only the selected players' data at runtime. No second copy of the provider's match database is bundled in the APK or committed to the repository.

| Screen data | Endpoint | Use |
|---|---|---|
| Player search | `GET /api/h2h/search?q=` | Search by player name; returns stable ATP ID, name and country |
| Pair meetings | `GET /api/h2h?player1={id}&player2={id}` | All recorded meetings for the selected pair |
| Player profile | `GET /api/players?id={id}` | Name, country, date of birth and height |
| Player match history | `GET /api/players/performance?id={id}` | Full player result history used for YTD and Career W/L and title counts |
| Current ranking | `GET /api/players/current-ranking?id={id}` | Latest ranking record and its publication date |

The endpoints are read-only GET requests. Network calls run off the UI thread, use bounded timeouts, and surface errors in the H2H screen rather than substituting fake data.

## 3. Calculations and definitions

### Head-to-head wins

The pair-match response is counted by stable ATP player ID. Every eligible meeting contributes one win to the recorded winner. The meeting list is sorted newest first; no name-based matching is used for results.

### YTD W/L and titles

YTD means the current calendar year on the device. Results are grouped by the match's recorded year/date. Wins and losses are derived from the winner/loser IDs.

### Career W/L and titles

Career is calculated from all played ATP match results returned by the player's match-history endpoint. Walkovers, defaults, abandoned/no-play outcomes and other no-play markers are excluded. Played retirements remain in the W/L record.

### Surface filters

Surface is read from the match result. For hard-court records, the source's separate indoor/outdoor field is normalized:
- Indoor: `I`, `indoor`, `yes`, `true`, `1`.
- Outdoor: `O`, `outdoor`, `no`, `false`, `0`.

Unknown setting is preserved. It is included in **Hard** because the playing surface is known to be hard, but it is not guessed into **Indoor Hard** or **Outdoor Hard**. Grass and Clay are matched directly. All surfaces includes every returned eligible surface, including any carpet records.

### Titles

A title is a played final-round win (`F` or `Final`) deduplicated by tournament ID. Team competitions such as Davis Cup, United Cup, Laver Cup, ATP Cup, Olympics and Hopman Cup are excluded from the singles ATP title count. A semifinal, round-robin win, walkover, or final loss does not count as a title.

### Current rank, age and height

- Current rank comes from the latest ranking endpoint response. Inactive/unranked players display `Unranked`; the ranking publication date is displayed when supplied.
- Age is calculated in whole years from the player's date of birth as of the device's current local date. Missing or future DOB is unavailable.
- Height is displayed in centimetres and feet/inches. Missing or out-of-range height is unavailable.

### Meeting date and event breakdown

Dates are parsed from the source's calendar date and rendered as `DD/MM/YYYY` without timezone conversion. Each meeting displays event, date, round, surface/environment, winner, loser and score (when supplied).

## 4. Architecture

- `core/h2h/H2HData.kt`: provider DTOs, player search, API calls, match parsing, no-play filtering and source field normalization.
- `core/h2h/H2HAnalytics.kt`: pure record, title, surface, age, height and date calculations.
- `ui/screens/H2HScreen.kt`: player selectors, H2H result, player facts, separate YTD/Career filters, and lazy meeting list.
- `ui/AppNav.kt` and `ui/glass/Glass.kt`: sixth H2H tab, route and dedicated navigation glyph.

The module remains independent from `PaceData`, `AppViewModel`, CPI/CALC calculations, Live Now and the existing Player Stats data/refresh code. H2H does not modify the Player Stats schema or bundle match-level source records.

## 5. Regression and acceptance checks

- Pair wins are counted by stable IDs and match the displayed meeting list.
- YTD is limited to the current calendar year; Career is not year-limited.
- YTD and Career surface selectors retain independent state.
- Hard combines indoor, outdoor and unknown-environment hard; Indoor Hard and Outdoor Hard require an explicit source classification.
- Grass and Clay splits use the source surface field.
- Titles count deduplicated final-round wins, not every match win or team-competition final.
- Age, rank and height remain unavailable when source facts are absent; no estimates or zero filling.
- Meeting dates use `DD/MM/YYYY`, newest first.
- Search, loading, network-error, no-meetings and empty-selection states are handled.
- Existing screens, Player Stats refresh, live ratings, CALC data and release workflows remain unchanged.

## 6. Source-use note

This feature uses the same TennisMyLife data family already used by the repository's Player Stats pipeline. The project's strict-open-data target from PR #24 is not a requirement for this H2H feature. That does **not** change TennisMyLife's published source terms: the provider describes its data as personal/educational/non-commercial research material and does not authorize commercial use or redistribution of the raw database. The implementation therefore requests data from the provider at runtime rather than publishing a bundled H2H match database. Any commercial deployment or broader data redistribution still requires the relevant permissions.

Source documentation:
- [TennisMyLife H2H and player statistics site](https://stats.tennismylife.org/)
- [TennisMyLife ATP Match Database and field documentation](https://stats.tennismylife.org/tennis-match-database)
- [TennisMyLife database terms and credits](https://tennismylife.github.io/TML-Database/)
