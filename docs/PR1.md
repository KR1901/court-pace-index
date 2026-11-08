# PR #1 — Live ATP match discovery, parsing and coverage

**Scope:** PR #1 contains the live ATP discovery, progressive result-page parsing, match-stat retrieval and coverage work. This document is maintained on PR #2's branch so repository documentation can describe PR #1 without changing PR #1's code branch.

## Pull request and current verification

- PR: https://github.com/KR1901/court-pace-index/pull/1
- Base: `main`
- Head branch: `fix/live-tournament-discovery-and-refresh`
- Latest branch head observed for this review: `299e019f50a8b3233656a7d9542f76c44aa8e090` (6 November 2026; scheduled dataset refresh).
- Android CI for that head passed in run [#42289096259](https://github.com/KR1901/court-pace-index/actions/runs/42289096259).

The branch includes the generic match-discovery and statistics pipeline and progressive rendering changes. A CI pass verifies build and test behaviour; ATP/Infosys CDN/WAF behaviour still requires real-device acceptance.

## Behaviour and eligibility

- Active ATP 1000/500/250 tournaments are discovered from current ATP result pages; match IDs are read from the rendered results rather than inferred from sequential IDs.
- The reader waits for progressive result-page rendering to settle, scrolls to trigger lazy-loaded sections and preserves results already collected if its internal time budget expires.
- Completed singles matches with valid serving statistics are eligible.
- Played retirements remain eligible when valid aggregate or played-set statistics exist.
- Walkovers and confirmed no-play matches are excluded; doubles and in-progress matches are excluded.
- Full-match aggregate stats are preferred. If those are absent or incomplete, valid played-set arrays are summed, including a partially played final set.
- Invalid or incomplete stats stay unresolved and visible. The app must never invent values to fill coverage.
- Cache membership is intersected with the current draw so obsolete match IDs do not contribute.

## Paris Masters regression case

The original failure exposed 48 outcomes, of which 47 had been played; Alcaraz–Berrettini was a walkover and must not be rated. The initial app result showed only 45 eligible ratings. The two missing played-retirement IDs were `ms043` (Arthur Rinderknech–Francisco Cerundolo) and `ms053` (Denis Shapovalov–Karen Khachanov).

The test suite retains these match-specific examples as regression fixtures, while production classification is generic and data-driven. CI fixtures do not prove that ATP/Infosys will serve the same payload to a physical handset.

## Operational acceptance

For each live-event acceptance check, compare:

1. result-page outcomes discovered;
2. played singles matches;
3. walkovers/no-play/doubles/in-progress exclusions;
4. valid statistics returned;
5. rated server sides and unresolved IDs/reasons.

A retirement should be counted only when play occurred and valid stats are present. A persistent 403/WAF denial must remain visible rather than being mistaken for a walkover or silently converted into data.

## Relationship to PR #2

PR #1 establishes live match ingestion and the app-facing live flow. PR #2 owns the calibrated expected-aces formulation, historical/live profile methodology, statistical audit, source-population tooling and related documentation. PR #2 is stacked on PR #1.

This document, the refreshed root README and the data/licensing references are committed only to PR #2's branch. PR #1's code branch is not modified by those documentation commits.
