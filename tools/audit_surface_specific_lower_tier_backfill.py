#!/usr/bin/env python3
"""Surface-bucket-specific lower-tier weighting experiment with a new 2026 holdout.

Development weight selection is restricted to 2012-2018. The forward test is the pinned
2026 ATP main-draw archive snapshot (through its latest source date). Seasons 2019-2025
are not scored or used to select weights; they are prior profile inputs for 2026.
Production ratings and assets are never modified.
"""
import argparse
import json
import math
import random
from datetime import datetime, timezone
from pathlib import Path

import audit_lower_tier_profile_backfill as base

compute_speed = base.compute_speed
ARCHIVE_COMMIT = base.ARCHIVE_COMMIT
PROFILE_START_YEAR = base.PROFILE_START_YEAR
RECENCY_DECAY = base.RECENCY_DECAY
BUCKETS = base.BUCKETS
ROLES = base.ROLES
EPSILON = 1e-9
TIER_POOL_WEIGHT = 0.25
SURFACE_WEIGHT_GRID = (0.0, 0.25, 0.50, 1.0)


def make_weight_map(pool=0.0, clay=0.0, hard_grass=0.0):
    return {"pooled": float(pool), "clay": float(clay), "hard_grass": float(hard_grass)}


def merge_surface_weighted(target, source, decay, weights):
    """Apply distinct clay and hard/grass weights; keep raw match qualification counts."""
    for role in ROLES:
        for bucket in BUCKETS:
            weight = weights[bucket]
            if weight <= 0.0:
                continue
            src = source[role][bucket]
            dst = target[role][bucket]
            factor = decay * weight
            dst["aces"] += src["aces"] * factor
            dst["points"] += src["points"] * factor
            dst["matches"] += src["matches"]


def build_training_stats(target_year, main_by_year, lower_by_year, candidate):
    """Only prior seasons train profiles; the population prior remains ATP-only."""
    players = {}
    global_stats = base.empty_global_stats()
    start = max(PROFILE_START_YEAR, target_year - 35)

    for year in range(start, target_year):
        yearly = main_by_year.get(year)
        if yearly is None:
            continue
        year_players, year_global = yearly
        decay = RECENCY_DECAY ** (target_year - year)
        base.merge_stats(global_stats, year_global, decay)
        for player_id, source in year_players.items():
            target = players.setdefault(player_id, base.empty_player())
            base.merge_stats(target, source, decay)

    for year in range(max(base.CHALLENGER_START_YEAR, target_year - 35), target_year):
        yearly = lower_by_year.get(year)
        if yearly is None:
            continue
        decay = RECENCY_DECAY ** (target_year - year)
        for tier in ("challenger", "qualifying"):
            weights = candidate[tier]
            if not any(value > 0.0 for value in weights.values()):
                continue
            for player_id, source in yearly[tier].items():
                target = players.setdefault(player_id, base.empty_player())
                merge_surface_weighted(target, source, decay, weights)

    return players, global_stats


def empty_cluster(surface):
    return {
        "surface": surface,
        "sides": 0,
        "points": 0.0,
        "aces": 0.0,
        "predicted_aces": 0.0,
        "point_abs_error": 0.0,
        "side_abs_error": 0.0,
        "binomial_log_loss": 0.0,
        "server_profile_sides": 0,
        "returner_profile_sides": 0,
        "both_profile_sides": 0,
    }


def evaluate_season(year, target_rows, main_by_year, lower_by_year, candidate):
    target_ids = {
        str(player_id)
        for row in target_rows
        for player_id in (row.get("winner_id"), row.get("loser_id"))
        if player_id
    }
    players, global_stats = build_training_stats(
        year, main_by_year, lower_by_year, candidate
    )
    profiles, _priors, tour_rate = base.build_profiles(
        target_ids, players, global_stats
    )
    if tour_rate is None or not 0.0 < tour_rate < 1.0:
        raise RuntimeError(
            f"No valid ATP-only population prior for target season {year}; aborting."
        )

    events = {}
    for row in target_rows:
        event_key = str(row.get("tourney_id") or row.get("tourney_name") or "unknown-event")
        surface = (row.get("surface") or "").strip().casefold()
        if surface not in {"clay", "hard", "grass", "carpet"}:
            surface = "unknown"
        if event_key not in events:
            events[event_key] = empty_cluster(surface)
        elif events[event_key]["surface"] != surface:
            events[event_key]["surface"] = "mixed"
        cluster = events[event_key]

        for server_key, returner_key, points_key, aces_key in (
            ("winner_id", "loser_id", "w_svpt", "w_ace"),
            ("loser_id", "winner_id", "l_svpt", "l_ace"),
        ):
            server = str(row[server_key])
            returner = str(row[returner_key])
            points = compute_speed.num(row.get(points_key))
            aces = compute_speed.num(row.get(aces_key))
            if points is None or aces is None or points <= 0.0:
                continue
            server_rate = profiles.get(server, {}).get("serve_rate")
            return_rate = profiles.get(returner, {}).get("concede_rate")
            has_server = server_rate is not None
            has_returner = return_rate is not None
            if not has_server:
                server_rate = tour_rate
            if not has_returner:
                return_rate = tour_rate

            predicted_rate = compute_speed.expected_calibrated_aces(
                server_rate, return_rate, tour_rate, 1.0
            )
            predicted_rate = min(1.0 - EPSILON, max(EPSILON, float(predicted_rate)))
            observed_rate = aces / points
            log_loss = -(
                aces * math.log(predicted_rate)
                + (points - aces) * math.log(1.0 - predicted_rate)
            )

            cluster["sides"] += 1
            cluster["points"] += points
            cluster["aces"] += aces
            cluster["predicted_aces"] += predicted_rate * points
            cluster["side_abs_error"] += abs(observed_rate - predicted_rate)
            cluster["point_abs_error"] += abs(observed_rate - predicted_rate) * points
            cluster["binomial_log_loss"] += log_loss
            cluster["server_profile_sides"] += int(has_server)
            cluster["returner_profile_sides"] += int(has_returner)
            cluster["both_profile_sides"] += int(has_server and has_returner)
    return events


def metrics_from_clusters(clusters):
    totals = {
        key: 0.0 for key in (
            "sides", "points", "aces", "predicted_aces", "point_abs_error",
            "side_abs_error", "binomial_log_loss", "server_profile_sides",
            "returner_profile_sides", "both_profile_sides",
        )
    }
    for cluster in clusters:
        for key in totals:
            totals[key] += cluster.get(key, 0.0)
    sides = int(totals["sides"])
    points = totals["points"]
    if sides <= 0 or points <= 0:
        return {
            "server_sides": sides,
            "service_points": int(points),
            "both_personal_profile_pct": None,
            "server_personal_profile_pct": None,
            "returner_personal_profile_pct": None,
            "point_weighted_mae": None,
            "side_mae": None,
            "binomial_log_loss_per_point": None,
            "calibration_bias": None,
        }
    return {
        "server_sides": sides,
        "service_points": int(points),
        "both_personal_profile_pct": 100.0 * totals["both_profile_sides"] / sides,
        "server_personal_profile_pct": 100.0 * totals["server_profile_sides"] / sides,
        "returner_personal_profile_pct": 100.0 * totals["returner_profile_sides"] / sides,
        "point_weighted_mae": totals["point_abs_error"] / points,
        "side_mae": totals["side_abs_error"] / sides,
        "binomial_log_loss_per_point": totals["binomial_log_loss"] / points,
        "calibration_bias": (totals["aces"] - totals["predicted_aces"]) / points,
    }


def arm(name, challenger=None, qualifying=None):
    return {
        "name": name,
        "challenger": challenger or make_weight_map(),
        "qualifying": qualifying or make_weight_map(),
    }


def arm_grid():
    arms = [arm("atp_only")]
    for weight in (0.25, 0.50, 1.0):
        weights = make_weight_map(weight, weight, weight)
        arms.append(arm(f"challenger_global_{weight:.2f}", challenger=weights))
        arms.append(arm(f"qualifying_global_{weight:.2f}", qualifying=weights))
    for weight in (0.25, 0.50):
        weights = make_weight_map(weight, weight, weight)
        arms.append(arm(f"both_global_{weight:.2f}", challenger=weights, qualifying=weights))

    for tier in ("challenger", "qualifying", "both"):
        for clay_weight in SURFACE_WEIGHT_GRID:
            for hard_grass_weight in SURFACE_WEIGHT_GRID:
                if clay_weight == 0.0 and hard_grass_weight == 0.0:
                    continue
                weights = make_weight_map(
                    TIER_POOL_WEIGHT, clay_weight, hard_grass_weight
                )
                if tier == "challenger":
                    arms.append(arm(
                        f"challenger_surface_clay_{clay_weight:.2f}_hardgrass_{hard_grass_weight:.2f}",
                        challenger=weights,
                    ))
                elif tier == "qualifying":
                    arms.append(arm(
                        f"qualifying_surface_clay_{clay_weight:.2f}_hardgrass_{hard_grass_weight:.2f}",
                        qualifying=weights,
                    ))
                else:
                    arms.append(arm(
                        f"both_surface_clay_{clay_weight:.2f}_hardgrass_{hard_grass_weight:.2f}",
                        challenger=weights, qualifying=weights,
                    ))
    return arms


def aggregate_arm_results(results, years, arm_name):
    clusters = []
    for year in years:
        clusters.extend(results[arm_name].get(year, {}).values())
    return metrics_from_clusters(clusters)


def select_arm(results, development_years, candidates):
    scored = []
    for item in candidates:
        name = item["name"]
        metrics = aggregate_arm_results(results, development_years, name)
        if metrics["binomial_log_loss_per_point"] is None:
            continue
        scored.append((
            metrics["binomial_log_loss_per_point"],
            metrics["point_weighted_mae"],
            name,
            metrics,
        ))
    if not scored:
        raise RuntimeError("No surface-weight candidate was scoreable on development data.")
    scored.sort(key=lambda x: (x[0], x[1], x[2]))
    return scored[0][2], [
        {"arm": name, **metrics}
        for _loss, _mae, name, metrics in scored
    ]


def paired_event_bootstrap(baseline, candidate, iterations, seed):
    common = sorted(set(baseline) & set(candidate))
    if len(common) < 4:
        return {"events": len(common), "successful": 0, "intervals": {}}
    rng = random.Random(seed)
    deltas = defaultdict(list)
    for _ in range(iterations):
        chosen = [common[rng.randrange(len(common))] for _ in range(len(common))]
        a = metrics_from_clusters([baseline[key] for key in chosen])
        b = metrics_from_clusters([candidate[key] for key in chosen])
        for metric in (
            "binomial_log_loss_per_point",
            "point_weighted_mae",
            "calibration_bias",
        ):
            deltas[metric].append(b[metric] - a[metric])
    intervals = {}
    for metric, values in deltas.items():
        values.sort()
        intervals[metric] = {
            "low": values[int(0.025 * (len(values) - 1))],
            "high": values[int(0.975 * (len(values) - 1))],
            "successful": len(values),
        }
    return {"events": len(common), "successful": iterations, "intervals": intervals}


def build_report_readme(report):
    validation = report["validation"]
    baseline = validation["atp_only"]
    candidate = validation[report["selected_arm"]]
    lines = [
        "# Surface-specific lower-tier profile weighting experiment",
        "",
        f"Generated UTC: {report['generated_utc']}",
        "",
        "## Design",
        "",
        "- Development weight selection: 2012–2018 only.",
        "- New forward test: pinned 2026 ATP main-draw archive snapshot only.",
        "- Seasons 2019–2025 are not scored or used to select weights; they are prior profile inputs for 2026.",
        "- ATP main-draw history alone defines population priors and the tour-average anchor.",
        "- Lower-tier observations affect individual player profiles only.",
        "",
        f"Selected development arm: **{report['selected_arm']}**.",
        "",
        "## New 2026 forward test",
        "",
        "| Metric | ATP-only | Selected surface-weight arm |",
        "|---|---:|---:|",
        f"| Eligible server sides | {baseline['server_sides']:,} | {candidate['server_sides']:,} |",
        f"| Both roles have personal histories | {baseline['both_personal_profile_pct']:.2f}% | {candidate['both_personal_profile_pct']:.2f}% |",
        f"| Server personal-profile coverage | {baseline['server_personal_profile_pct']:.2f}% | {candidate['server_personal_profile_pct']:.2f}% |",
        f"| Returner personal-profile coverage | {baseline['returner_personal_profile_pct']:.2f}% | {candidate['returner_personal_profile_pct']:.2f}% |",
        f"| Point-weighted ace-rate MAE | {baseline['point_weighted_mae']:.6f} | {candidate['point_weighted_mae']:.6f} |",
        f"| Binomial log loss / service point | {baseline['binomial_log_loss_per_point']:.6f} | {candidate['binomial_log_loss_per_point']:.6f} |",
        f"| Calibration bias (actual − predicted) | {baseline['calibration_bias']:+.6f} | {candidate['calibration_bias']:+.6f} |",
        "",
        "### Paired event-cluster bootstrap (selected minus ATP-only)",
        "",
    ]
    for metric, label in (
        ("binomial_log_loss_per_point", "Binomial log loss / service point"),
        ("point_weighted_mae", "Point-weighted ace-rate MAE"),
        ("calibration_bias", "Calibration bias"),
    ):
        value = validation["bootstrap"]["intervals"].get(metric)
        if value:
            lines.append(
                f"- {label}: 95% interval [{value['low']:+.6f}, {value['high']:+.6f}] "
                f"({value['successful']:,} resamples)."
            )
    lines.extend([
        "",
        "Negative loss / MAE deltas favour backfill. An interval spanning zero is inconclusive.",
        "",
        "## 2026 results by actual target surface",
        "",
        "| Surface | Sides | ATP-only log loss | Selected log loss | ATP-only point MAE | Selected point MAE |",
        "|---|---:|---:|---:|---:|---:|",
    ])
    for surface, values in sorted(validation["by_surface"].items()):
        a = values["atp_only"]
        b = values[report["selected_arm"]]
        if a["server_sides"]:
            lines.append(
                f"| {surface} | {a['server_sides']:,} | "
                f"{a['binomial_log_loss_per_point']:.6f} | "
                f"{b['binomial_log_loss_per_point']:.6f} | "
                f"{a['point_weighted_mae']:.6f} | {b['point_weighted_mae']:.6f} |"
            )
    lines.extend([
        "",
        "## Development ranking",
        "",
        "| Arm | Both-role profile coverage (%) | Point-weighted MAE | Log loss / point |",
        "|---|---:|---:|---:|",
    ])
    for item in report["development"]["ranking"]:
        lines.append(
            f"| {item['arm']} | {item['both_personal_profile_pct']:.2f} | "
            f"{item['point_weighted_mae']:.6f} | "
            f"{item['binomial_log_loss_per_point']:.6f} |"
        )
    lines.extend([
        "",
        "## Source provenance and safeguards",
        "",
        f"- Archive: {base.ARCHIVE_REPOSITORY}.",
        "- Original dataset attribution: Jeff Sackmann / Tennis Abstract; based on the Jeff Sackmann ATP match database (https://github.com/JeffSackmann/tennis_atp; https://tennisabstract.com/).",
        f"- Pinned archive commit: {ARCHIVE_COMMIT}.",
        "- Licence: CC BY-NC-SA 4.0; non-commercial research only, with attribution and share-alike obligations.",
        "- Source SHA-256 hashes and row counts are included in the JSON. Raw CSVs, player names and IDs are excluded from the artifact.",
        "- The 2026 target file is a partial archive snapshot, not a full 2026 season.",
        "- The existing profile hierarchy, 150-point shrinkage, one-third clay / two-thirds hard-grass blend and expected-aces equation are unchanged.",
        "- The experiment does not modify production calculations, ratings, assets, rankings or app behaviour.",
        "",
    ])
    return "\n".join(lines)


def run_experiment(validation_year, development_start, development_end,
                   bootstrap, cache_dir, output_dir):
    if validation_year <= development_end:
        raise ValueError("validation_year must be later than the development window.")
    if development_start < 2012:
        raise ValueError("development_start must be 2012 or later.")
    if bootstrap < 100:
        raise ValueError("bootstrap must be at least 100.")

    main_by_year = {}
    main_manifests = []
    target_by_year = {}
    validation_dates = []
    for year in range(PROFILE_START_YEAR, validation_year + 1):
        rows, manifest, raw_count = base.load_main_year(year, cache_dir)
        players, global_stats = base.aggregate_rows(rows)
        main_by_year[year] = (players, global_stats)
        main_manifests.append({
            "year": year,
            **manifest,
            "raw_rows": raw_count,
            "valid_main_draw_matches": len(rows),
        })
        if development_start <= year <= development_end or year == validation_year:
            target_by_year[year] = rows
        if year == validation_year:
            validation_dates = sorted(
                str(row.get("tourney_date") or "")
                for row in rows if row.get("tourney_date")
            )

    lower_by_year = {}
    lower_manifests = []
    for year in range(base.CHALLENGER_START_YEAR, validation_year):
        tiers, manifest = base.load_lower_year(year, cache_dir)
        lower_by_year[year] = tiers
        lower_manifests.append({"year": year, **manifest})

    candidates = arm_grid()
    development_results = {item["name"]: {} for item in candidates}
    for year in range(development_start, development_end + 1):
        rows = target_by_year.get(year, [])
        if not rows:
            raise RuntimeError(f"No eligible ATP main-draw development rows for {year}.")
        for candidate in candidates:
            development_results[candidate["name"]][year] = evaluate_season(
                year, rows, main_by_year, lower_by_year, candidate
            )

    development_years = list(range(development_start, development_end + 1))
    selected_arm, ranking = select_arm(
        development_results, development_years, candidates
    )
    selected = next(item for item in candidates if item["name"] == selected_arm)
    target_rows = target_by_year.get(validation_year, [])
    if not target_rows:
        raise RuntimeError(f"No eligible ATP main-draw forward-test rows for {validation_year}.")

    atp_only = next(item for item in candidates if item["name"] == "atp_only")
    validation_results = {
        "atp_only": evaluate_season(
            validation_year, target_rows, main_by_year, lower_by_year, atp_only
        ),
        selected_arm: evaluate_season(
            validation_year, target_rows, main_by_year, lower_by_year, selected
        ),
    }
    baseline_metrics = metrics_from_clusters(list(validation_results["atp_only"].values()))
    selected_metrics = metrics_from_clusters(list(validation_results[selected_arm].values()))

    by_surface = {}
    surfaces = sorted({
        cluster["surface"]
        for events in validation_results.values()
        for cluster in events.values()
    })
    for surface in surfaces:
        by_surface[surface] = {}
        for name, events in validation_results.items():
            subset = [
                cluster for cluster in events.values()
                if cluster["surface"] == surface
            ]
            by_surface[surface][name] = metrics_from_clusters(subset)

    bootstrap_result = paired_event_bootstrap(
        validation_results["atp_only"],
        validation_results[selected_arm],
        bootstrap,
        seed=20261108,
    )
    selected_weights = {
        "challenger": selected["challenger"],
        "qualifying": selected["qualifying"],
    }
    report = {
        "schema_version": 1,
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "archive": {
            "repository": base.ARCHIVE_REPOSITORY,
            "commit": ARCHIVE_COMMIT,
            "license": "CC BY-NC-SA 4.0",
            "main_draw_sha256": {
                str(item["year"]): item["sha256"] for item in main_manifests
            },
            "lower_tier_sha256": {
                str(item["year"]): item["sha256"] for item in lower_manifests
            },
        },
        "design": {
            "development_start": development_start,
            "development_end": development_end,
            "validation_year": validation_year,
            "development_seasons_scored": development_years,
            "intervening_seasons_scored_or_used_for_selection": [],
            "intervening_seasons_used_as_prior_profile_history": list(
                range(development_end + 1, validation_year)
            ),
            "target_year_and_future_rows_used_for_profiles": False,
            "population_prior": "ATP main-draw only",
            "lower_tier_effect": "individual player profiles only",
            "surface_weight_buckets": ["clay", "hard_grass"],
            "pooled_lower_tier_fallback_weight": TIER_POOL_WEIGHT,
        },
        "validation_snapshot": {
            "eligible_matches": len(target_rows),
            "eligible_server_sides": len(target_rows) * 2,
            "first_match_date": validation_dates[0] if validation_dates else None,
            "last_match_date": validation_dates[-1] if validation_dates else None,
            "is_full_season": False,
        },
        "selected_arm": selected_arm,
        "selected_weights": selected_weights,
        "development": {
            "ranking_metric": "binomial_log_loss_per_service_point",
            "tie_breaker": "point_weighted_ace_rate_mae",
            "ranking": ranking,
        },
        "validation": {
            "atp_only": baseline_metrics,
            selected_arm: selected_metrics,
            "bootstrap": bootstrap_result,
            "by_surface": by_surface,
        },
        "source_coverage": {
            "lower_tier_years": len(lower_manifests),
            "challenger_matches": sum(
                item["selected_challenger_matches"] for item in lower_manifests
            ),
            "qualifying_matches": sum(
                item["selected_qualifying_matches"] for item in lower_manifests
            ),
            "main_draw_manifest": [
                {key: item[key] for key in ("year", "sha256", "raw_rows", "valid_main_draw_matches")}
                for item in main_manifests
            ],
            "lower_tier_manifest": [
                {key: item[key] for key in (
                    "year", "sha256", "selected_challenger_matches",
                    "selected_qualifying_matches",
                )}
                for item in lower_manifests
            ],
        },
        "contains_player_names_or_ids": False,
        "raw_source_csv_included": False,
        "production_changed": False,
    }

    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    (output / "summary.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (output / "README.md").write_text(
        build_report_readme(report), encoding="utf-8"
    )
    print(json.dumps({
        "selected_arm": selected_arm,
        "validation_year": validation_year,
        "eligible_matches": len(target_rows),
        "source_date_range": [
            validation_dates[0] if validation_dates else None,
            validation_dates[-1] if validation_dates else None,
        ],
        "baseline_log_loss": baseline_metrics["binomial_log_loss_per_point"],
        "candidate_log_loss": selected_metrics["binomial_log_loss_per_point"],
        "baseline_point_mae": baseline_metrics["point_weighted_mae"],
        "candidate_point_mae": selected_metrics["point_weighted_mae"],
        "bootstrap": bootstrap_result,
        "production_changed": False,
    }, indent=2))
    return report


def main():
    parser = argparse.ArgumentParser(
        description="Select clay / hard-grass lower-tier profile weights on 2012-2018 and test once on 2026."
    )
    parser.add_argument("--validation-year", type=int, default=2026)
    parser.add_argument("--development-start", type=int, default=2012)
    parser.add_argument("--development-end", type=int, default=2018)
    parser.add_argument("--bootstrap", type=int, default=2000)
    parser.add_argument("--cache-dir", default=".cache/surface-tier-weight-backfill")
    parser.add_argument("--output-dir", default="build/surface-tier-weight-backfill")
    args = parser.parse_args()
    run_experiment(
        args.validation_year, args.development_start, args.development_end,
        args.bootstrap, args.cache_dir, args.output_dir,
    )


if __name__ == "__main__":
    main()
