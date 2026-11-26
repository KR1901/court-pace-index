# Chronological tournament ratings

## Why the 14-day quarantine was removed

The previous rule used a fixed 14-day gap from the source tournament start date. It was a defensive workaround for archives that record only a tournament start/week date, but it treated every tournament alike and could discard genuinely earlier matches. It also did not prove that a long event had finished.

The revised calculation uses the most precise trustworthy chronology available for each source tournament edition.

## Chronology precedence

1. **Actual UTC match times.** A training match is eligible only when its confirmed finish precedes the target event's first confirmed actual match start. A recorded UTC finish is preferred. Where the source supplies an actual UTC start and a minute-rounded duration, the finish estimate adds a one-minute safety margin. Scheduled/not-before order-of-play times are not actual starts and are never used as such.
2. **Match calendar days and final-round result.** The current-season TennisMyLife feed contains multiple distinct `tourney_date` values and a `round` field within many tournament editions. Daily precision is enabled independently per source tournament ID only when the edition itself has multiple match days. A scored `F`/`FINAL` row identifies the final singles match day and can prove that a multi-day event has finished. Because the source dates are venue-local days without a time-zone field, the final match day is compared across the full civil-time range UTC-12 to UTC+14. Same-day and timezone-ambiguous matches are excluded.
3. **Confirmed UTC event-end dates.** A completed event's confirmed UTC end date can prove that all its results precede the target. Provisional or local-only end dates are ignored.
4. **Legacy maximum event window.** The historical Sackmann annual archive exposes tournament start/week dates, not match days or event-end dates. These rows use conservative maximum duration bounds by source level (Grand Slam 15 days; Masters/ATP umbrella 12; Finals/ATP 500/250 8; unknown 15). These are safety bounds, not claimed actual event end dates.

## Concurrent tournaments and two separate samples

Player-profile training and the season normalisation anchor are separate.

- **Profiles** may use an earlier result from a tournament that is still in progress, but only when that individual match is provably complete before the target begins. Earlier rounds of a concurrent event can therefore contribute without leaking later rounds.
- **Normalisation** may use only fully completed earlier event editions. Partial concurrent events cannot set the 1.00 reference. If no stable completed same-season anchor exists, the prior completed season is used. The target event never contributes its own raw score to the anchor.

## ATP Tour validation and limits

The official ATP 2026 calendar publishes event date ranges and illustrates concurrent weeks (for example, Brisbane 4–11 January and Hong Kong 5–11 January). The official Brisbane 2026 results archive groups results under individual daily headings. These first-party pages are used as manual validation references for calendar/match-day semantics; they are not ingested into the app as a database.

An ATP order-of-play "start" or "not before" time is a scheduled time, not proof of when a match actually started or finished. Exact same-day ordering is enabled only when a source supplies a timezone-aware actual start plus a reliable finish or duration.

No ATP website scraper or bulk extraction is included. ATP's terms prohibit systematic retrieval and compiling of its website content without prior express written permission. The official calendar/results pages are cited as manual validation references only. See [ATP Terms and Conditions](https://www.atptour.com/en/terms-and-conditions), the [official 2026 ATP Tour calendar](https://www.atptour.com/en/news/what-is-the-2026-atp-tour-calendar), the [ATP tournament calendar](https://www.atptour.com/en/tournaments/), and the [Brisbane 2026 results archive](https://www.atptour.com/en/scores/archive/brisbane/339/2026/results).

## Diagnostics

Each CALC event records its target start and last observed match day where available, chronology precision/rule, latest training match day, exclusions due to same-day/time-zone ambiguity/legacy event window, source-level duration bounds, and normalisation anchor mode and counts. A zero-day quarantine means the blanket 14-day rule is not applied; it does not mean every historical row has exact match timestamps.
