# PR #1 — Live ATP match discovery, parsing and coverage

**Scope:** PR #1 established the generic live-match ingestion pipeline. This document is maintained on PR #2's branch so repository documentation reflects current work without modifying PR #1.

## Latest verified changes on the PR #1 branch

The latest source commit visible during this documentation refresh is `37cb815d10a3b66defefc7c4239b8dce8846786a` (6 November 2026). It addressed a progressively rendered ATP results page:

- The reader waits for rendered text to stop growing for four consecutive polls, scrolling to trigger lazy-loaded sections, with a 45-second deadline.
- The completed-match total is selected from the fuller independently observed count rather than preferring a larger percentage from a truncated sample.
- Earlier fixes moved blocking HTTP reads to `Dispatchers.IO`, read match links from the rendered DOM, and removed unsafe assumptions about sequential match IDs and generic page titles.
- The per-match walk retains already-collected results when its internal time budget expires.
- WebView concurrency, per-page render timeouts and the overall walk budget were tuned after release comparisons showed that excessive parallel rendering and short timeouts reduced coverage.

## Match eligibility rules

- Completed singles matches with usable statistics are eligible.
- Retirements are eligible when play occurred and valid service statistics are available.
- Walkovers, confirmed no-play records, doubles and in-progress matches are excluded.
- Full-match aggregate statistics are preferred; played-set statistics are summed only when the aggregate is absent or incomplete.
- Invalid or incomplete statistics remain unresolved and visible. The app must not invent statistics to fill a coverage gap.
- The current draw is authoritative for cache membership; stale cached match IDs must not contribute.

## Operational limit

ATP/Infosys access is device-sensitive. Server-side runners have received CDN/WAF denials, so a green CI build proves compilation and test behaviour, not successful live retrieval from a handset. Device acceptance requires refreshing a live event and reconciling discovered, played, rated, excluded and unresolved counts.

PR #1 remains a separate stacked change. This documentation update is committed only to PR #2's branch.
