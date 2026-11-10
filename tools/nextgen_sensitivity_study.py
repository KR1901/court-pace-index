#!/usr/bin/env python3
"""Research-only sensitivity study for the Next Gen Finals CALC target.

The study calls the production season_ratings() pipeline. Next Gen remains a target-only
event: its matches are scored, but excluded from standard ATP player histories, seasonal
priors and the standard-event normaliser. Outputs contain aggregate results and source hashes
only; raw CSVs, player names and player identifiers are not published.
"""
import argparse
import csv
import hashlib
import json
import math
import statistics
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compute_speed  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
HISTORY_PATH = ROOT / "data" / "speed_history.json"
TARGET_EVENT_ID = "nextgen"
TARGET_ONLY_IDS = set(compute_speed.CALC_TARGET_ONLY_EVENT_IDS)

DEFAULTS = {
    "PROFILE_PRIOR_SERVICE_POINTS": compute_speed.PROFILE_PRIOR_SERVICE_POINTS,
    "LIVE_PROFILE_PRIOR_SERVICE_POINTS": compute_speed.LIVE_PROFILE_PRIOR_SERVICE_POINTS,
    "LIVE_PROFILE_HISTORY_DECAY": compute_speed.LIVE_PROFILE_HISTORY_DECAY,
    "LIVE_PROFILE_FALLBACK_SEASONS": compute_speed.LIVE_PROFILE_FALLBACK_SEASONS,
    "MIN_MATCHES_PER_SURFACE": compute_speed.MIN_MATCHES_PER_SURFACE,
    "EXPECTED_ACES_FLOOR": compute_speed.EXPECTED_ACES_FLOOR,
    "MIN_NORMALISER_SERVER_SIDES": compute_speed.MIN_NORMALISER_SERVER_SIDES,
}


def sensitivity_configurations():
    """One-factor-at-a-time perturbations around the production policy."""
    scenarios = [{"name": "production_baseline", "overrides": {}}]
    dimensions = (
        ("season_profile_prior", "PROFILE_PRIOR_SERVICE_POINTS", (75.0, 300.0)),
        ("career_profile_prior", "LIVE_PROFILE_PRIOR_SERVICE_POINTS", (75.0, 300.0)),
        ("history_recency_decay", "LIVE_PROFILE_HISTORY_DECAY", (0.80, 0.98)),
        ("history_window_seasons", "LIVE_PROFILE_FALLBACK_SEASONS", (15, 25)),
        ("surface_match_qualification", "MIN_MATCHES_PER_SURFACE", (1, 5)),
        ("expected_aces_denominator_floor", "EXPECTED_ACES_FLOOR", (0.25, 1.0)),
        ("normaliser_minimum_sides", "MIN_NORMALISER_SERVER_SIDES", (10, 30)),
    )
    for label, key, values in dimensions:
        for value in values:
            scenarios.append({
                "name": f"{label}_{str(value).replace('.', '_')}",
                "overrides": {key: value},
            })
    return scenarios


def flatten(clusters):
    return [value for cluster in clusters for value in cluster]


def percentile(values, quantile):
    ordered = sorted(values)
    if not ordered:
        return None
    position = (len(ordered) - 1) * quantile
    low, high = math.floor(position), math.ceil(position)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def aggregate_sensitivity(clusters):
    """Contrast the production side mean with robust match-cluster summaries."""
    match_means = [statistics.fmean(cluster) for cluster in clusters if cluster]
    side_values = flatten(clusters)
    if not match_means or not side_values:
        return {}
    lower, upper = percentile(match_means, 0.10), percentile(match_means, 0.90)
    winsorised = [min(upper, max(lower, value)) for value in match_means]
    trimmed = sorted(match_means)[1:-1] if len(match_means) >= 5 else match_means
    return {
        "production_side_weighted_mean": statistics.fmean(side_values),
        "equal_match_mean": statistics.fmean(match_means),
        "median_match_mean": statistics.median(match_means),
        "winsorised_match_mean_10_90": statistics.fmean(winsorised),
        "trimmed_match_mean_drop_one_each_tail": statistics.fmean(trimmed),
        "match_count": len(match_means),
        "side_count": len(side_values),
    }


def standard_anchor(raw_by_event, sides_by_event, minimum_sides=20, weighting="event"):
    """Build a standard-ATP anchor; target-only IDs can never enter it."""
    candidates = [
        event_id for event_id, raw in raw_by_event.items()
        if event_id not in TARGET_ONLY_IDS and raw is not None
        and sides_by_event.get(event_id, 0) >= minimum_sides
    ]
    if not candidates:
        candidates = [
            event_id for event_id, raw in raw_by_event.items()
            if event_id not in TARGET_ONLY_IDS and raw is not None
        ]
    if not candidates:
        return None, 0
    if weighting == "event":
        return statistics.fmean(raw_by_event[key] for key in candidates), len(candidates)
    if weighting == "side":
        total = sum(sides_by_event[key] for key in candidates)
        if total <= 0:
            return None, 0
        return (
            sum(raw_by_event[key] * sides_by_event[key] for key in candidates) / total,
            len(candidates),
        )
    raise ValueError(f"unsupported anchor weighting: {weighting}")


def source_sha256(year):
    path = Path(compute_speed.CACHE) / f"atp_matches_{year}.csv"
    if not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def event_key(event_rows):
    ids = {
        compute_speed.tournament_id(row.get("tourney_name"))
        for row in event_rows
    }
    ids.discard(None)
    return next(iter(ids)) if len(ids) == 1 else None


def run_scenario(year, rows, scenario, boot_iterations=10000):
    """Run the production leave-one-event-out pipeline and capture aggregates."""
    for key, value in DEFAULTS.items():
        setattr(compute_speed, key, value)
    for key, value in scenario["overrides"].items():
        setattr(compute_speed, key, value)
    compute_speed._PROFILE_YEAR_CACHE.clear()

    captured_clusters, captured_coverage = {}, {}
    original_cluster = compute_speed.event_match_ratio_clusters
    original_coverage = compute_speed.ensure_event_profiles

    def capture_clusters(event_rows, profiles, tour_rate):
        clusters = original_cluster(event_rows, profiles, tour_rate)
        tid = event_key(event_rows)
        if tid:
            captured_clusters[tid] = clusters
        return clusters

    def capture_coverage(event_rows, profiles, tour_rate):
        coverage = original_coverage(event_rows, profiles, tour_rate)
        tid = event_key(event_rows)
        if tid:
            captured_coverage[tid] = dict(coverage)
        return coverage

    compute_speed.event_match_ratio_clusters = capture_clusters
    compute_speed.ensure_event_profiles = capture_coverage
    try:
        ratings = compute_speed.season_ratings(year, rows)
    finally:
        compute_speed.event_match_ratio_clusters = original_cluster
        compute_speed.ensure_event_profiles = original_coverage

    if TARGET_EVENT_ID not in ratings or TARGET_EVENT_ID not in captured_clusters:
        raise RuntimeError(f"{year}: production pipeline did not return a Next Gen rating")

    raw_by_event, sides_by_event = {}, {}
    for tid, clusters in captured_clusters.items():
        values = flatten(clusters)
        if values:
            raw_by_event[tid] = statistics.fmean(values)
            sides_by_event[tid] = len(values)

    target_clusters = captured_clusters[TARGET_EVENT_ID]
    target_raw = statistics.fmean(flatten(target_clusters))
    threshold = compute_speed.MIN_NORMALISER_SERVER_SIDES
    normaliser, normaliser_count = standard_anchor(
        raw_by_event, sides_by_event, threshold, weighting="event"
    )
    if normaliser is None or normaliser <= 0:
        raise RuntimeError(f"{year}: no standard-event normaliser")
    reconstructed = target_raw / normaliser
    reported = float(ratings[TARGET_EVENT_ID]["speed"])
    if abs(round(reconstructed, 3) - reported) > 0.0011:
        raise RuntimeError(
            f"{year} {scenario['name']}: captured target/anchor "
            f"({reconstructed:.6f}) does not reproduce production ({reported:.3f})"
        )

    valid = compute_speed.valid_rows(rows)
    target_rows = [
        row for row in valid
        if compute_speed.tournament_id(row.get("tourney_name")) == TARGET_EVENT_ID
    ]
    aces = sum(
        compute_speed.num(row["w_ace"]) + compute_speed.num(row["l_ace"])
        for row in target_rows
    )
    points = sum(
        compute_speed.num(row["w_svpt"]) + compute_speed.num(row["l_svpt"])
        for row in target_rows
    )
    coverage = captured_coverage.get(TARGET_EVENT_ID, {})
    interval = compute_speed.bootstrap_event_interval(
        target_clusters, normaliser, iterations=boot_iterations, seed=73191 + year
    )

    return {
        "year": year,
        "scenario": scenario["name"],
        "overrides": dict(scenario["overrides"]),
        "matches": len(target_rows),
        "sides": sides_by_event.get(TARGET_EVENT_ID, 0),
        "aces": int(aces),
        "service_points": int(points),
        "ace_rate_pct": 100.0 * aces / points if points else None,
        "raw_target": target_raw,
        "normaliser": normaliser,
        "normaliser_event_count": normaliser_count,
        "normaliser_minimum_sides": threshold,
        "rating": reported,
        "reconstructed_rating": reconstructed,
        "ci_low": interval["low"] if interval else None,
        "ci_high": interval["high"] if interval else None,
        "coverage": {
            key: coverage.get(key, 0)
            for key in (
                "rateable_server_sides", "tour_prior_sides",
                "career_history_server_sides", "career_history_returner_sides",
                "tour_prior_server_profiles", "tour_prior_returner_profiles",
            )
        },
        "clusters": target_clusters,
        "target_rows": target_rows,
        "raw_by_event": raw_by_event,
        "sides_by_event": sides_by_event,
    }


def load_bundled_history():
    if not HISTORY_PATH.is_file():
        return {}
    with HISTORY_PATH.open(encoding="utf-8") as source:
        return json.load(source).get("seasons", {})


def historical_nextgen_context(history):
    result = []
    for year, events in sorted(history.items(), key=lambda item: int(item[0])):
        item = events.get(TARGET_EVENT_ID)
        if item:
            result.append({
                "year": int(year), "matches": item.get("matches"),
                "sides": item.get("adjustedServerSides"), "ace_rate_pct": item.get("ace"),
                "rating": item.get("speed"), "ci_low": item.get("speedCiLow"),
                "ci_high": item.get("speedCiHigh"),
            })
    return result


def fmt(value, digits=3):
    return "—" if value is None else f"{value:.{digits}f}"


def render_report(results_by_year, history_context, hashes, boot_iterations, output_path):
    lines = [
        "# Next Gen Finals CALC sensitivity study", "",
        f"Run timestamp: {datetime.now(timezone.utc).isoformat(timespec='seconds')}", "",
        "## Scope and estimand", "",
        "Research-only audit of Next Gen as a **target-only, format-specific CALC**. The production "
        "formula is unchanged. Next Gen match rows are scored, but excluded from standard ATP "
        "player-profile training and from every standard-event normaliser.",
        "",
        "The production score is the equal-server-side mean of actual aces divided by calibrated "
        "expected aces (with the configured denominator floor), divided by the equal-event mean "
        "of standard ATP event raw scores. Profiles are leave-one-event-out and use the production "
        "historical player-profile pipeline.", "",
        "## Source population and baseline", "",
        "| Season | Matches | Rateable sides | Aces / service points | Ace rate | Tour-prior sides | Career-history server sides | Career-history returner sides | CALC | Match-cluster 95% interval |",
        "|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    baseline_by_year = {}
    for year, scenarios in sorted(results_by_year.items()):
        baseline = next(row for row in scenarios if row["scenario"] == "production_baseline")
        baseline_by_year[year] = baseline
        c = baseline["coverage"]
        lines.append(
            f"| {year} | {baseline['matches']} | {baseline['sides']} | "
            f"{baseline['aces']} / {baseline['service_points']} | {fmt(baseline['ace_rate_pct'], 2)}% | "
            f"{c['tour_prior_sides']} | {c['career_history_server_sides']} | "
            f"{c['career_history_returner_sides']} | {baseline['rating']:.3f} | "
            f"[{fmt(baseline['ci_low'])}, {fmt(baseline['ci_high'])}] |"
        )
    lines += [
        "",
        f"The interval is a match-cluster bootstrap with profiles and the standard-event normaliser held "
        f"fixed ({boot_iterations:,} resamples). It captures target-match sampling uncertainty, not full "
        "profile/normaliser uncertainty.", "",
        "## One-factor-at-a-time profile and scoring sensitivity", "",
        "Each row changes one production parameter while holding all others at their production values. "
        "Scores are recalculated through the full leave-one-event-out production pipeline, including "
        "the standard-event normaliser.", "",
        "| Scenario | 2024 CALC | Δ vs baseline | 2025 CALC | Δ vs baseline |",
        "|---|---:|---:|---:|---:|",
    ]
    scenario_names = [row["scenario"] for row in next(iter(results_by_year.values()))]
    for name in scenario_names:
        cells = [name]
        for year in sorted(results_by_year):
            row = next(item for item in results_by_year[year] if item["scenario"] == name)
            delta = row["rating"] - baseline_by_year[year]["rating"]
            cells.extend((f"{row['rating']:.3f}", f"{delta:+.3f}"))
        lines.append("| " + " | ".join(cells) + " |")

    lines += [
        "", "## Standard-event normaliser sensitivity", "",
        "Using the baseline player profiles and event raw scores, this changes only the standard-event "
        "denominator. Next Gen is excluded from every anchor.", "",
        "| Season | Anchor policy | Events | Normaliser | Next Gen CALC | Δ vs production |",
        "|---:|---|---:|---:|---:|---:|",
    ]
    for year in sorted(results_by_year):
        baseline = baseline_by_year[year]
        for label, minimum, weighting in (
            ("all rateable standard events, equal event", 1, "event"),
            ("at least 10 sides, equal event", 10, "event"),
            ("production: at least 20 sides, equal event", 20, "event"),
            ("at least 30 sides, equal event", 30, "event"),
            ("at least 20 sides, side weighted", 20, "side"),
        ):
            normaliser, count = standard_anchor(
                baseline["raw_by_event"], baseline["sides_by_event"], minimum, weighting
            )
            score = baseline["raw_target"] / normaliser if normaliser else None
            delta = score - baseline["rating"] if score is not None else None
            lines.append(
                f"| {year} | {label} | {count} | {fmt(normaliser, 5)} | "
                f"{fmt(score)} | {fmt(delta, 3)} |"
            )

    lines += [
        "", "## Target-match aggregation sensitivity", "",
        "These alternatives hold the production standard-event anchor fixed and change only how the "
        "15 match clusters are summarised. The production estimator remains the server-side mean.", "",
        "| Season | Aggregation | CALC | Δ vs production |",
        "|---:|---|---:|---:|",
    ]
    for year in sorted(results_by_year):
        baseline = baseline_by_year[year]
        summaries = aggregate_sensitivity(baseline["clusters"])
        for key, label in (
            ("production_side_weighted_mean", "Production: server-side mean"),
            ("equal_match_mean", "Equal match mean"),
            ("median_match_mean", "Median of match means"),
            ("winsorised_match_mean_10_90", "Match means winsorised at 10th/90th percentiles"),
            ("trimmed_match_mean_drop_one_each_tail", "Trim one lowest and one highest match"),
        ):
            score = summaries[key] / baseline["normaliser"]
            lines.append(
                f"| {year} | {label} | {score:.3f} | {score - baseline['rating']:+.3f} |"
            )

    lines += [
        "", "## Match and participant influence diagnostics", "",
        "Ranges remove one match cluster at a time, then all clusters involving one participant at a time. "
        "These are influence diagnostics, not confidence intervals.", "",
        "| Season | One-match-deleted CALC range | One-participant-deleted CALC range | Participants assessed |",
        "|---:|---:|---:|---:|",
    ]
    for year in sorted(results_by_year):
        baseline = baseline_by_year[year]
        clusters, rows, anchor = baseline["clusters"], baseline["target_rows"], baseline["normaliser"]
        match_values = [
            statistics.fmean(flatten(clusters[:i] + clusters[i + 1:])) / anchor
            for i in range(len(clusters)) if len(flatten(clusters[:i] + clusters[i + 1:])) > 0
        ]
        people = {
            player for row in rows for player in (row["winner_id"], row["loser_id"])
        }
        player_values = []
        if len(rows) == len(clusters):
            for player in people:
                remaining = [
                    cluster for row, cluster in zip(rows, clusters)
                    if player not in (row["winner_id"], row["loser_id"])
                ]
                values = flatten(remaining)
                if values:
                    player_values.append(statistics.fmean(values) / anchor)
        match_range = f"{min(match_values):.3f}–{max(match_values):.3f}" if match_values else "—"
        player_range = f"{min(player_values):.3f}–{max(player_values):.3f}" if player_values else "—"
        lines.append(f"| {year} | {match_range} | {player_range} | {len(player_values)} |")

    lines += [
        "", "## Longitudinal Next Gen context", "",
        "Existing bundled historical estimates (not fresh re-estimates of every prior year):", "",
        "| Season | Matches | Sides | Ace rate | CALC | Match-cluster 95% interval |",
        "|---:|---:|---:|---:|---:|---:|",
    ]
    for item in history_context:
        ci = (
            f"[{fmt(item['ci_low'])}, {fmt(item['ci_high'])}]"
            if item["ci_low"] is not None and item["ci_high"] is not None else "—"
        )
        lines.append(
            f"| {item['year']} | {item['matches']} | {item['sides']} | "
            f"{fmt(item['ace_rate_pct'], 1)}% | {fmt(item['rating'])} | {ci} |"
        )

    lines += [
        "", "## Input provenance", "",
        "Match statistics: [Jeff Sackmann ATP match archive](https://github.com/Aneeshers/tennis-sackmann-archive). "
        "Raw CSV files are not committed or uploaded. SHA-256 hashes for the target-season CSVs:", "",
    ]
    for year, digest in sorted(hashes.items()):
        lines.append(f"- {year}: {digest or 'unavailable'}")
    lines += [
        "", "Format context: [Next Gen ATP Finals rules and innovations]"
        "(https://www.nextgenatpfinals.com/en/event/rules-and-innovations). The available match archive "
        "contains aggregate aces and service points, not point-by-point sequences needed to isolate "
        "no-ad deciding points, serve-box choice or other format mechanisms.", "",
        "## Interpretation and decision", "",
        "- Each edition has a valid 15-match / 30-side target sample. The 2024 and 2025 raw ace rates "
        "are similar, while their CALC values differ, so CALC is not simply a restatement of raw ace rate.",
        "- The sensitivity grid quantifies dependence on profile history, shrinkage, denominator floor and "
        "the standard-event anchor. Robust match summaries and deletion diagnostics show how much the "
        "small sample depends on individual match/player clusters.",
        "- The bootstrap interval is conditional. The parameter grid is a deterministic robustness exercise, "
        "not a probability distribution over a true format effect.",
        "- Aggregate statistics cannot identify a causal correction for short sets/no-ad scoring. That requires "
        "point-by-point, format-aware data and a separately validated model.", "",
        "**Decision: retain the current production treatment unchanged.** Keep Next Gen as a target-only, "
        "format-specific CALC; do not let it train standard ATP profiles or move the standard ATP anchor. "
        "Do not add a hand-tuned numeric correction from these two small editions, and do not interpret the "
        "result as a literal physical-court speed multiplier.", "",
    ]
    output_path.write_text("\n".join(lines), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seasons", type=int, nargs="+", default=[2024, 2025])
    parser.add_argument("--boot", type=int, default=10000)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "nextgen-sensitivity-output")
    args = parser.parse_args()
    if args.boot < 1000:
        parser.error("--boot must be at least 1,000 for a useful cluster interval")
    if TARGET_EVENT_ID not in TARGET_ONLY_IDS:
        raise RuntimeError("Next Gen is not configured as a target-only event")

    history = load_bundled_history()
    results_by_year, hashes = {}, {}
    for year in args.seasons:
        print(f"Loading ATP match history for {year} and prior player profiles...")
        rows = compute_speed.load(year)
        hashes[year] = source_sha256(year)
        results = []
        for scenario in sensitivity_configurations():
            result = run_scenario(year, rows, scenario, boot_iterations=args.boot)
            results.append(result)
            print(
                f"{year} {scenario['name']}: CALC={result['rating']:.3f}; "
                f"{result['matches']} matches/{result['sides']} sides; "
                f"anchor={result['normaliser']:.6f}"
            )
        results_by_year[year] = results
        bundled = history.get(str(year), {}).get(TARGET_EVENT_ID)
        baseline = next(item for item in results if item["scenario"] == "production_baseline")
        if bundled:
            delta = baseline["rating"] - float(bundled["speed"])
            print(
                f"{year} bundled-history reproduction: {baseline['rating']:.3f} vs "
                f"{float(bundled['speed']):.3f} (delta {delta:+.3f})"
            )

    history_context = historical_nextgen_context(history)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    report_path = args.output_dir / "NEXT_GEN_SENSITIVITY_STUDY.md"
    json_path = args.output_dir / "nextgen_sensitivity_results.json"
    csv_path = args.output_dir / "nextgen_sensitivity_grid.csv"
    render_report(results_by_year, history_context, hashes, args.boot, report_path)

    compact_results, csv_rows = {}, []
    for year, scenarios in results_by_year.items():
        compact_results[str(year)] = []
        for item in scenarios:
            clean = {
                key: value for key, value in item.items()
                if key not in ("clusters", "target_rows", "raw_by_event", "sides_by_event", "ratings")
            }
            compact_results[str(year)].append(clean)
            csv_rows.append({
                "year": year, "scenario": item["scenario"], "rating": item["rating"],
                "matches": item["matches"], "sides": item["sides"], "aces": item["aces"],
                "service_points": item["service_points"], "ace_rate_pct": item["ace_rate_pct"],
                "normaliser": item["normaliser"], "normaliser_event_count": item["normaliser_event_count"],
                "normaliser_minimum_sides": item["normaliser_minimum_sides"],
                "ci_low": item["ci_low"], "ci_high": item["ci_high"], **item["coverage"],
            })
    json_path.write_text(json.dumps({
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "sourceSha256": {str(year): digest for year, digest in hashes.items()},
        "results": compact_results,
        "historicalNextGenContext": history_context,
    }, indent=2), encoding="utf-8")
    with csv_path.open("w", newline="", encoding="utf-8") as target:
        writer = csv.DictWriter(target, fieldnames=list(csv_rows[0]))
        writer.writeheader()
        writer.writerows(csv_rows)

    print(f"Report: {report_path}")
    print(f"Aggregate JSON: {json_path}")
    print(f"Sensitivity CSV: {csv_path}")
    print(report_path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
