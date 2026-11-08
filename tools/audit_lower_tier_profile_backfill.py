#!/usr/bin/env python3
"""Leakage-safe, research-only audit of Challenger / ATP qualifying profile backfill.

The experiment never changes production ratings. It builds rolling-origin profiles from
ATP main-draw history only, then adds separately weighted Challenger and ATP qualifying
history while keeping the population prior ATP-only. Each target ATP season is strictly
out of sample: no match from that season or a later season is allowed into its profiles.

Only aggregate, de-identified metrics are written. Raw CSVs and player-level profiles
remain in the local Actions cache and are never uploaded as workflow artifacts.
"""
import argparse
import csv
import hashlib
import json
import math
import random
import urllib.request
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

import compute_speed

ARCHIVE_REPOSITORY = "Aneeshers/tennis-sackmann-archive"
ARCHIVE_COMMIT = "83733587353df8a41f2fd4f516147d5aa83f5a8d"
ARCHIVE_BASE_URL = (
    "https://raw.githubusercontent.com/"
    + ARCHIVE_REPOSITORY
    + "/"
    + ARCHIVE_COMMIT
    + "/atp/"
)
MAIN_DRAW_TEMPLATE = "atp_matches_{year}.csv"
LOWER_TIER_TEMPLATE = "atp_matches_qual_chall_{year}.csv"
PROFILE_START_YEAR = 1991
CHALLENGER_START_YEAR = 2008
QUALIFYING_START_YEAR = 2011
RECENCY_DECAY = 0.90
PRIOR_SERVICE_POINTS = 150.0
MIN_SURFACE_MATCHES = 3
CLAY_WEIGHT = 1.0 / 3.0
BUCKETS = ("pooled", "clay", "hard_grass")
ROLES = ("serve", "return")
WEIGHT_LEVELS = (0.25, 0.50, 1.00)
EPSILON = 1e-9


def empty_stat():
    return {"aces": 0.0, "points": 0.0, "matches": 0}


def empty_role_stats():
    return {
        role: {bucket: empty_stat() for bucket in BUCKETS}
        for role in ROLES
    }


def empty_global_stats():
    return empty_role_stats()


def empty_player():
    return empty_role_stats()


def add_side(stats, role, surface_key, aces, points, value_weight=1.0):
    """Add one role observation; weighting changes evidence, not match qualification."""
    if value_weight <= 0.0:
        return
    targets = ["pooled"]
    if surface_key:
        targets.append(surface_key)
    for bucket in targets:
        item = stats[role][bucket]
        item["aces"] += float(aces) * value_weight
        item["points"] += float(points) * value_weight
        item["matches"] += 1


def merge_stats(target, source, value_weight):
    """Merge sufficient statistics; surface match counts remain unweighted."""
    if value_weight <= 0.0:
        return
    for role in ROLES:
        for bucket in BUCKETS:
            dst = target[role][bucket]
            src = source[role][bucket]
            dst["aces"] += src["aces"] * value_weight
            dst["points"] += src["points"] * value_weight
            dst["matches"] += src["matches"]


def surface_bucket(surface):
    value = (surface or "").strip().casefold()
    if value == "clay":
        return "clay"
    if value in ("hard", "grass"):
        return "hard_grass"
    return None


def aggregate_rows(rows):
    """Aggregate valid rows into player-role and population sufficient statistics."""
    players = {}
    global_stats = empty_global_stats()
    for row in rows:
        winner_id = str(row.get("winner_id") or "")
        loser_id = str(row.get("loser_id") or "")
        if not winner_id or not loser_id or winner_id == loser_id:
            continue
        w_sv = compute_speed.num(row.get("w_svpt"))
        l_sv = compute_speed.num(row.get("l_svpt"))
        w_ace = compute_speed.num(row.get("w_ace"))
        l_ace = compute_speed.num(row.get("l_ace"))
        if any(v is None or not math.isfinite(v) for v in (w_sv, l_sv, w_ace, l_ace)):
            continue
        bucket = surface_bucket(row.get("surface"))
        winner = players.setdefault(winner_id, empty_player())
        loser = players.setdefault(loser_id, empty_player())

        # Each player contributes one serve and one return observation per match.
        add_side(winner, "serve", bucket, w_ace, w_sv)
        add_side(winner, "return", bucket, l_ace, l_sv)
        add_side(loser, "serve", bucket, l_ace, l_sv)
        add_side(loser, "return", bucket, w_ace, w_sv)

        add_side(global_stats, "serve", bucket, w_ace, w_sv)
        add_side(global_stats, "serve", bucket, l_ace, l_sv)
        add_side(global_stats, "return", bucket, l_ace, l_sv)
        add_side(global_stats, "return", bucket, w_ace, w_sv)
    return players, global_stats


def classify_lower_row(row):
    """Classify only ATP tour qualifying and Challenger main-draw rows.

    The upstream combined file uses C for Challenger main draw. ATP qualifying rounds
    retain the parent tour-event level (G/M/A/F/250/500) and Q1/Q2/Q3 round codes.
    Challenger qualifying is not included by this experiment.
    """
    level = (row.get("tourney_level") or "").strip().upper()
    round_name = (row.get("round") or "").strip().upper()
    if level == "C" and not round_name.startswith("Q"):
        return "challenger"
    if (
        level in compute_speed.LEVELS
        and level != "C"
        and round_name in {"Q1", "Q2", "Q3"}
    ):
        return "qualifying"
    return None


def validate_lower_rows(rows):
    """Reuse production stat sanity checks without changing source classification."""
    candidates = []
    for row in rows:
        tier = classify_lower_row(row)
        if tier is None:
            continue
        normalized = dict(row)
        normalized["tourney_level"] = "250"
        normalized["_lower_tier"] = tier
        candidates.append(normalized)
    return compute_speed.valid_rows(candidates)


def fetch_csv(cache_dir, relative_name):
    """Download a pinned archive file once; return rows and byte-level provenance."""
    cache_root = Path(cache_dir) / ARCHIVE_COMMIT[:12]
    cache_root.mkdir(parents=True, exist_ok=True)
    target = cache_root / relative_name
    if not target.is_file():
        url = ARCHIVE_BASE_URL + relative_name
        request = urllib.request.Request(
            url,
            headers={
                "User-Agent": "CourtPaceIndex-LowerTierAudit/1.0",
                "Accept": "text/csv",
            },
        )
        with urllib.request.urlopen(request, timeout=120) as response:
            payload = response.read()
        if not payload.startswith(b"tourney_id,") or b"w_svpt" not in payload[:4096]:
            raise ValueError(f"Unexpected ATP archive CSV schema: {relative_name}")
        temporary = target.with_suffix(target.suffix + ".tmp")
        temporary.write_bytes(payload)
        temporary.replace(target)
    payload = target.read_bytes()
    if not payload.startswith(b"tourney_id,") or b"w_svpt" not in payload[:4096]:
        raise ValueError(f"Cached ATP archive CSV has an unexpected schema: {relative_name}")
    digest = hashlib.sha256(payload).hexdigest()
    rows = list(csv.DictReader(payload.decode("utf-8-sig").splitlines()))
    return rows, {
        "file": relative_name,
        "sha256": digest,
        "bytes": len(payload),
        "rows": len(rows),
        "url": ARCHIVE_BASE_URL + relative_name,
    }


def load_main_year(year, cache_dir):
    raw, manifest = fetch_csv(
        cache_dir, MAIN_DRAW_TEMPLATE.format(year=year)
    )
    valid = compute_speed.valid_rows(raw)
    return valid, manifest, len(raw)


def load_lower_year(year, cache_dir):
    raw, manifest = fetch_csv(
        cache_dir, LOWER_TIER_TEMPLATE.format(year=year)
    )
    selected = validate_lower_rows(raw)
    challenger = [row for row in selected if row.get("_lower_tier") == "challenger"]
    qualifying = (
        [row for row in selected if row.get("_lower_tier") == "qualifying"]
        if year >= QUALIFYING_START_YEAR else []
    )
    return {
        "challenger": aggregate_rows(challenger)[0],
        "qualifying": aggregate_rows(qualifying)[0],
    }, {
        **manifest,
        "raw_rows": len(raw),
        "selected_challenger_matches": len(challenger),
        "selected_qualifying_matches": len(qualifying),
    }


def build_training_stats(target_year, main_by_year, lower_by_year,
                         challenger_weight, qualifying_weight):
    """Build a target-year profile from strictly prior seasons only."""
    players = {}
    global_stats = empty_global_stats()
    lower_counts = {
        "challenger_matches_in_training": 0,
        "qualifying_matches_in_training": 0,
    }
    main_start = max(PROFILE_START_YEAR, target_year - 35)
    lower_start = max(CHALLENGER_START_YEAR, target_year - 35)

    for year in range(main_start, target_year):
        yearly = main_by_year.get(year)
        if yearly is None:
            continue
        decay = RECENCY_DECAY ** (target_year - year)
        year_players, year_global = yearly
        merge_stats(global_stats, year_global, decay)
        for player_id, source in year_players.items():
            target = players.setdefault(player_id, empty_player())
            merge_stats(target, source, decay)

    for year in range(lower_start, target_year):
        yearly = lower_by_year.get(year)
        if yearly is None:
            continue
        decay = RECENCY_DECAY ** (target_year - year)
        for tier, alpha in (
            ("challenger", challenger_weight),
            ("qualifying", qualifying_weight),
        ):
            if alpha <= 0.0:
                continue
            source_players = yearly[tier]
            count_key = f"{tier}_matches_in_training"
            lower_counts[count_key] += sum(
                int(player["serve"]["pooled"]["matches"])
                for player in source_players.values()
            )
            factor = decay * alpha
            for player_id, source in source_players.items():
                target = players.setdefault(player_id, empty_player())
                merge_stats(target, source, factor)

    return players, global_stats, lower_counts


def prior_rate(stats):
    if stats["points"] <= 0.0:
        return None
    rate = stats["aces"] / stats["points"]
    return rate if 0.0 < rate < 1.0 else None


def shrunk_rate(player, role, priors):
    """Mirror the production 150-point surface / pooled profile hierarchy."""
    def surface_estimate(bucket):
        sample = player[role][bucket]
        base = priors[role][bucket]
        if (
            sample["points"] <= 0.0
            or sample["matches"] < MIN_SURFACE_MATCHES
            or base is None
        ):
            return None
        return (
            sample["aces"] + PRIOR_SERVICE_POINTS * base
        ) / (sample["points"] + PRIOR_SERVICE_POINTS)

    clay = surface_estimate("clay")
    hard_grass = surface_estimate("hard_grass")
    if clay is not None and hard_grass is not None:
        return CLAY_WEIGHT * clay + (1.0 - CLAY_WEIGHT) * hard_grass
    if clay is not None:
        return clay
    if hard_grass is not None:
        return hard_grass

    pooled = player[role]["pooled"]
    pooled_base = priors[role]["pooled"]
    if pooled["points"] > 0.0 and pooled_base is not None:
        return (
            pooled["aces"] + PRIOR_SERVICE_POINTS * pooled_base
        ) / (pooled["points"] + PRIOR_SERVICE_POINTS)
    return None


def build_profiles(target_player_ids, players, global_stats):
    priors = {
        role: {
            bucket: prior_rate(global_stats[role][bucket])
            for bucket in BUCKETS
        }
        for role in ROLES
    }
    tour_rate = prior_rate(global_stats["serve"]["pooled"])
    if tour_rate is None:
        return {}, priors, None
    profiles = {}
    for player_id in target_player_ids:
        sample = players.get(player_id)
        if sample is None:
            profiles[player_id] = {"serve_rate": None, "concede_rate": None}
            continue
        profiles[player_id] = {
            "serve_rate": shrunk_rate(sample, "serve", priors),
            "concede_rate": shrunk_rate(sample, "return", priors),
        }
    return profiles, priors, tour_rate


def clip_probability(value):
    return min(1.0 - EPSILON, max(EPSILON, float(value)))


def empty_cluster():
    return {
        "sides": 0,
        "points": 0.0,
        "aces": 0.0,
        "predicted_aces": 0.0,
        "side_abs_error": 0.0,
        "point_abs_error": 0.0,
        "binomial_log_loss": 0.0,
        "server_profile_sides": 0,
        "returner_profile_sides": 0,
        "both_profile_sides": 0,
        "newly_both_profile_sides": 0,
    }


def merge_cluster(target, source):
    for key in target:
        target[key] += source.get(key, 0)


def evaluate_target_season(year, target_rows, main_by_year, lower_by_year,
                           challenger_weight, qualifying_weight, baseline_both=None):
    target_player_ids = {
        str(player_id)
        for row in target_rows
        for player_id in (row.get("winner_id"), row.get("loser_id"))
        if player_id
    }
    players, global_stats, lower_counts = build_training_stats(
        year, main_by_year, lower_by_year,
        challenger_weight, qualifying_weight,
    )
    profiles, _priors, tour_rate = build_profiles(
        target_player_ids, players, global_stats
    )
    if tour_rate is None:
        raise RuntimeError(
            f"No valid ATP-only tour prior for held-out season {year}; refusing fallback."
        )

    by_event = defaultdict(empty_cluster)
    for row in target_rows:
        event_key = str(
            row.get("tourney_id")
            or row.get("tourney_name")
            or "unknown-event"
        )
        for server_key, returner_key, points_key, aces_key in (
            ("winner_id", "loser_id", "w_svpt", "w_ace"),
            ("loser_id", "winner_id", "l_svpt", "l_ace"),
        ):
            server_id = str(row[server_key])
            returner_id = str(row[returner_key])
            points = compute_speed.num(row[points_key])
            aces = compute_speed.num(row[aces_key])
            if points is None or aces is None or points <= 0.0:
                continue
            server_profile = profiles.get(server_id, {})
            returner_profile = profiles.get(returner_id, {})
            personal_server = server_profile.get("serve_rate")
            personal_returner = returner_profile.get("concede_rate")
            has_server = personal_server is not None
            has_returner = personal_returner is not None
            serve_rate = personal_server if has_server else tour_rate
            concede_rate = personal_returner if has_returner else tour_rate
            predicted_rate = compute_speed.expected_calibrated_aces(
                serve_rate, concede_rate, tour_rate, 1.0
            )
            predicted_rate = clip_probability(predicted_rate)
            actual_rate = aces / points
            log_loss = -(
                aces * math.log(predicted_rate)
                + (points - aces) * math.log(1.0 - predicted_rate)
            )
            cluster = by_event[event_key]
            cluster["sides"] += 1
            cluster["points"] += points
            cluster["aces"] += aces
            cluster["predicted_aces"] += predicted_rate * points
            cluster["side_abs_error"] += abs(actual_rate - predicted_rate)
            cluster["point_abs_error"] += abs(actual_rate - predicted_rate) * points
            cluster["binomial_log_loss"] += log_loss
            cluster["server_profile_sides"] += int(has_server)
            cluster["returner_profile_sides"] += int(has_returner)
            cluster["both_profile_sides"] += int(has_server and has_returner)
            if baseline_both is not None:
                baseline_key = (event_key, server_id, returner_id)
                cluster["newly_both_profile_sides"] += int(
                    (not baseline_both.get(baseline_key, False))
                    and has_server and has_returner
                )
    return dict(by_event), lower_counts


def metrics_from_clusters(clusters):
    total = empty_cluster()
    for cluster in clusters:
        merge_cluster(total, cluster)
    sides = total["sides"]
    points = total["points"]
    if sides <= 0 or points <= 0.0:
        return {
            "server_sides": 0,
            "service_points": 0,
            "side_mae": None,
            "point_weighted_mae": None,
            "binomial_log_loss_per_point": None,
            "calibration_bias": None,
            "both_personal_profile_pct": None,
            "server_personal_profile_pct": None,
            "returner_personal_profile_pct": None,
            "newly_both_profile_sides": 0,
        }
    return {
        "server_sides": sides,
        "service_points": int(points),
        "side_mae": total["side_abs_error"] / sides,
        "point_weighted_mae": total["point_abs_error"] / points,
        "binomial_log_loss_per_point": total["binomial_log_loss"] / points,
        "calibration_bias": (total["aces"] - total["predicted_aces"]) / points,
        "both_personal_profile_pct": 100.0 * total["both_profile_sides"] / sides,
        "server_personal_profile_pct": 100.0 * total["server_profile_sides"] / sides,
        "returner_personal_profile_pct": 100.0 * total["returner_profile_sides"] / sides,
        "newly_both_profile_sides": int(total["newly_both_profile_sides"]),
    }


def combine_year_clusters(year_results, years):
    combined = []
    for year in years:
        combined.extend(year_results.get(year, {}).values())
    return metrics_from_clusters(combined)


def bootstrap_validation_delta(year_results, baseline_name, candidate_name,
                                years, iterations, seed):
    """Season-stratified event-cluster bootstrap; candidate minus baseline."""
    rng = random.Random(seed)
    deltas = {"binomial_log_loss_per_point": [], "point_weighted_mae": []}
    usable_years = [
        year for year in years
        if year_results.get(baseline_name, {}).get(year)
        and year_results.get(candidate_name, {}).get(year)
    ]
    for _ in range(iterations):
        baseline_sample = []
        candidate_sample = []
        for year in usable_years:
            baseline_events = year_results[baseline_name][year]
            candidate_events = year_results[candidate_name][year]
            keys = sorted(set(baseline_events) & set(candidate_events))
            if not keys:
                continue
            chosen = [keys[rng.randrange(len(keys))] for _ in range(len(keys))]
            baseline_sample.extend(baseline_events[key] for key in chosen)
            candidate_sample.extend(candidate_events[key] for key in chosen)
        baseline = metrics_from_clusters(baseline_sample)
        candidate = metrics_from_clusters(candidate_sample)
        if baseline["binomial_log_loss_per_point"] is None or candidate["binomial_log_loss_per_point"] is None:
            continue
        for metric in deltas:
            deltas[metric].append(candidate[metric] - baseline[metric])
    result = {}
    for metric, values in deltas.items():
        if not values:
            result[metric] = {"low": None, "high": None, "successful": 0}
            continue
        ordered = sorted(values)
        result[metric] = {
            "low": ordered[int(0.025 * (len(ordered) - 1))],
            "high": ordered[int(0.975 * (len(ordered) - 1))],
            "successful": len(ordered),
        }
    return result


def arm_grid():
    arms = {"atp_only": (0.0, 0.0)}
    for weight in WEIGHT_LEVELS:
        label = f"{weight:.2f}".replace(".", "_")
        arms[f"challenger_{label}"] = (weight, 0.0)
        arms[f"qualifying_{label}"] = (0.0, weight)
        arms[f"both_{label}"] = (weight, weight)
    return arms


def select_arm(development_results, development_years, arms):
    scored = []
    for name in arms:
        metrics = combine_year_clusters(
            development_results.get(name, {}), development_years
        )
        if metrics["binomial_log_loss_per_point"] is not None:
            scored.append((
                metrics["binomial_log_loss_per_point"],
                metrics["point_weighted_mae"],
                name,
                metrics,
            ))
    if not scored:
        raise RuntimeError("No candidate arm had sufficient development observations.")
    scored.sort(key=lambda item: (item[0], item[1], item[2]))
    return scored[0][2], [
        {"arm": name, **metrics}
        for _loss, _mae, name, metrics in scored
    ]


def render_markdown(report):
    development = report["development"]
    validation = report["validation"]
    selected = report["selected_arm"]
    base = validation["atp_only"]
    candidate = validation[selected]
    lines = [
        "# Lower-tier player-profile backfill experiment",
        "",
        f"Generated UTC: {report['generated_utc']}",
        "",
        "## Decision",
        "",
        f"Development seasons selected **{selected}**. The arm was selected only on "
        f"{development['start_season']}–{development['end_season']} and evaluated once "
        f"on the later holdout {validation['start_season']}–{validation['end_season']}.",
        "",
        "This is a research-only profile experiment. It does not change production ratings.",
        "",
        "## Locked holdout result",
        "",
        "| Metric | ATP-only baseline | Selected lower-tier arm |",
        "|---|---:|---:|",
        f"| Server sides | {base['server_sides']:,} | {candidate['server_sides']:,} |",
        f"| Both player roles have personal histories | {base['both_personal_profile_pct']:.2f}% | {candidate['both_personal_profile_pct']:.2f}% |",
        f"| Server personal-profile coverage | {base['server_personal_profile_pct']:.2f}% | {candidate['server_personal_profile_pct']:.2f}% |",
        f"| Returner personal-profile coverage | {base['returner_personal_profile_pct']:.2f}% | {candidate['returner_personal_profile_pct']:.2f}% |",
        f"| Point-weighted ace-rate MAE | {base['point_weighted_mae']:.6f} | {candidate['point_weighted_mae']:.6f} |",
        f"| Binomial log loss / service point | {base['binomial_log_loss_per_point']:.6f} | {candidate['binomial_log_loss_per_point']:.6f} |",
        f"| Calibration bias (actual − predicted) | {base['calibration_bias']:+.6f} | {candidate['calibration_bias']:+.6f} |",
        "",
        "### Paired event-cluster bootstrap (selected minus ATP-only)",
        "",
    ]
    for metric, label in (
        ("binomial_log_loss_per_point", "Binomial log loss / service point"),
        ("point_weighted_mae", "Point-weighted ace-rate MAE"),
    ):
        item = validation["bootstrap_delta"][metric]
        if item["low"] is None:
            lines.append(f"- {label}: bootstrap interval unavailable.")
        else:
            lines.append(
                f"- {label}: 95% interval [{item['low']:.6f}, {item['high']:.6f}] "
                f"({item['successful']:,} successful resamples)."
            )
    lines.extend([
        "",
        "Negative deltas favour lower-tier backfill. Treat an interval spanning zero as inconclusive.",
        "",
        "## Development candidate grid",
        "",
        "| Arm | Both personal roles (%) | Point-weighted MAE | Binomial log loss / point |",
        "|---|---:|---:|---:|",
    ])
    for item in development["arm_ranking"]:
        lines.append(
            f"| {item['arm']} | {item['both_personal_profile_pct']:.2f} | "
            f"{item['point_weighted_mae']:.6f} | {item['binomial_log_loss_per_point']:.6f} |"
        )
    lines.extend([
        "",
        "## Validation by season",
        "",
        "| Season | ATP-only log loss | Selected log loss | ATP-only profile coverage (%) | Selected coverage (%) |",
        "|---:|---:|---:|---:|---:|",
    ])
    for year, item in sorted(validation["by_season"].items()):
        lines.append(
            f"| {year} | {item['atp_only']['binomial_log_loss_per_point']:.6f} | "
            f"{item[selected]['binomial_log_loss_per_point']:.6f} | "
            f"{item['atp_only']['both_personal_profile_pct']:.2f} | "
            f"{item[selected]['both_personal_profile_pct']:.2f} |"
        )
    lines.extend([
        "",
        "## Source, controls and limitations",
        "",
        f"- Source archive: {ARCHIVE_REPOSITORY}.",
        f"- Pinned archive commit: {ARCHIVE_COMMIT}.",
        "- Source licence: Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International.",
        "- Historical ATP match rows and lower-tier rows are downloaded into a local cache only; raw rows, player IDs and player names are not part of the output artifact.",
        "- ATP main-draw history alone defines all population priors and the tour-average anchor. Challenger and qualifying observations can affect only individual player-role profiles.",
        "- Each target season is evaluated only with match data from earlier seasons. No target-season or future rows enter its player profiles.",
        "- Lower-tier observations receive the declared tier weight and the same year-recency decay. Match counts remain raw counts for the three-match surface-qualification rule; ace/service-point exposures are weighted.",
        "- The combined file is classified by source level and round. Challenger main draw uses level C; ATP qualifying uses tour-level events with Q1/Q2/Q3 round labels. Challenger qualifying is not included.",
        "- Binomial log loss treats each service point as a Bernoulli opportunity; event-season clusters are used for uncertainty resampling.",
        "- This non-commercial research-only archive is not cleared for commercial use. Do not transfer its derived profiles or ratings into a commercial app without the necessary rights and licence review.",
        "- A useful backfill must improve later-year predictive scores while increasing personal-profile coverage. Better coverage alone is not evidence of better estimates.",
        "- Production equations, weights, data assets and rankings are unchanged.",
        "",
        "The JSON report contains aggregate season/arm metrics and source hashes only; it does not include player-level records.",
        "",
    ])
    return "\n".join(lines)


def run_experiment(start_season, end_season, development_end, bootstrap,
                   cache_dir, output_dir):
    if start_season < 2012:
        raise ValueError("start_season must be 2012 or later for the qualifying archive window.")
    if end_season >= datetime.now(timezone.utc).year:
        raise ValueError("The target end season must be a completed historical season.")
    if start_season >= end_season:
        raise ValueError("At least two target seasons are required.")
    if not start_season <= development_end < end_season:
        raise ValueError("development_end must split the target seasons into development and holdout.")

    main_by_year = {}
    target_rows = {}
    main_manifests = []
    for year in range(PROFILE_START_YEAR, end_season + 1):
        rows, manifest, raw_count = load_main_year(year, cache_dir)
        players, global_stats = aggregate_rows(rows)
        main_by_year[year] = (players, global_stats)
        main_manifests.append({
            "year": year,
            **manifest,
            "raw_rows": raw_count,
            "valid_main_draw_matches": len(rows),
        })
        if start_season <= year <= end_season:
            target_rows[year] = rows

    lower_by_year = {}
    lower_manifests = []
    for year in range(CHALLENGER_START_YEAR, end_season):
        tiers, manifest = load_lower_year(year, cache_dir)
        lower_by_year[year] = tiers
        lower_manifests.append({"year": year, **manifest})

    arms = arm_grid()
    year_results = {name: {} for name in arms}

    for year in range(start_season, end_season + 1):
        rows = target_rows.get(year, [])
        if not rows:
            raise RuntimeError(f"No eligible ATP main-draw target matches in {year}.")
        target_ids = {
            str(pid)
            for row in rows
            for pid in (row.get("winner_id"), row.get("loser_id"))
            if pid
        }
        baseline_players, baseline_global, _ = build_training_stats(
            year, main_by_year, lower_by_year, 0.0, 0.0
        )
        baseline_profiles, _, _ = build_profiles(
            target_ids, baseline_players, baseline_global
        )
        baseline_both = {}
        for row in rows:
            event_key = str(row.get("tourney_id") or row.get("tourney_name") or "unknown-event")
            for server_key, returner_key in (("winner_id", "loser_id"), ("loser_id", "winner_id")):
                server_id = str(row[server_key])
                returner_id = str(row[returner_key])
                serve_has = baseline_profiles.get(server_id, {}).get("serve_rate") is not None
                return_has = baseline_profiles.get(returner_id, {}).get("concede_rate") is not None
                baseline_both[(event_key, server_id, returner_id)] = serve_has and return_has
        del baseline_players, baseline_profiles, baseline_global

        for arm_name, (challenger_weight, qualifying_weight) in arms.items():
            event_clusters, _ = evaluate_target_season(
                year, rows, main_by_year, lower_by_year,
                challenger_weight, qualifying_weight,
                baseline_both if arm_name != "atp_only" else None,
            )
            year_results[arm_name][year] = event_clusters

    development_years = list(range(start_season, development_end + 1))
    validation_years = list(range(development_end + 1, end_season + 1))
    selected_arm, arm_ranking = select_arm(year_results, development_years, arms)
    dev_metrics = {
        name: combine_year_clusters(year_results[name], development_years)
        for name in arms
    }
    validation_metrics = {
        name: combine_year_clusters(year_results[name], validation_years)
        for name in arms
    }
    bootstrap_delta = bootstrap_validation_delta(
        year_results, "atp_only", selected_arm,
        validation_years, bootstrap, 91731,
    )
    by_season = {}
    for year in validation_years:
        by_season[year] = {
            name: metrics_from_clusters(
                list(year_results[name].get(year, {}).values())
            )
            for name in ("atp_only", selected_arm)
        }

    source_coverage = {
        "challenger_source_matches": sum(
            item["selected_challenger_matches"] for item in lower_manifests
        ),
        "qualifying_source_matches": sum(
            item["selected_qualifying_matches"] for item in lower_manifests
        ),
        "lower_tier_years": len(lower_manifests),
        "lower_tier_manifest_sha256": {
            str(item["year"]): item["sha256"] for item in lower_manifests
        },
    }
    selected_weights = arms[selected_arm]
    report = {
        "schema_version": 1,
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "archive": {
            "repository": ARCHIVE_REPOSITORY,
            "commit": ARCHIVE_COMMIT,
            "license": "CC BY-NC-SA 4.0",
            "main_draw_file_years": [PROFILE_START_YEAR, end_season],
            "lower_tier_file_years": [CHALLENGER_START_YEAR, end_season - 1],
            "main_draw_manifest_sha256": {
                str(item["year"]): item["sha256"] for item in main_manifests
            },
        },
        "method": {
            "profile_history_start_year": PROFILE_START_YEAR,
            "max_profile_history_seasons": 35,
            "recency_decay_per_year": RECENCY_DECAY,
            "prior_service_points": PRIOR_SERVICE_POINTS,
            "min_surface_matches": MIN_SURFACE_MATCHES,
            "clay_weight": CLAY_WEIGHT,
            "tour_prior_source": "ATP main-draw history only",
            "lower_tier_effect": "individual player-role profiles only",
            "target_season_is_excluded_from_training": True,
            "target_and_future_season_rows_used": False,
            "lower_tier_match_count_is_weighted": False,
            "lower_tier_ace_and_service_point_exposure_is_weighted": True,
            "validation_primary_metric": "binomial_log_loss_per_point",
            "uncertainty_unit": "event-season cluster",
        },
        "source_coverage": source_coverage,
        "selected_arm": selected_arm,
        "selected_weights": {
            "challenger": selected_weights[0],
            "qualifying": selected_weights[1],
        },
        "development": {
            "start_season": start_season,
            "end_season": development_end,
            "baseline": dev_metrics["atp_only"],
            "selected_arm": selected_arm,
            "selected_arm_metrics": dev_metrics[selected_arm],
            "arm_ranking": arm_ranking,
        },
        "validation": {
            "start_season": development_end + 1,
            "end_season": end_season,
            "baseline_arm": "atp_only",
            "selected_arm": selected_arm,
            "atp_only": validation_metrics["atp_only"],
            selected_arm: validation_metrics[selected_arm],
            "bootstrap_delta": bootstrap_delta,
            "by_season": by_season,
        },
        "season_source_coverage": {
            str(year): {
                "eligible_target_main_draw_matches": len(target_rows[year]),
                "eligible_target_server_sides": len(target_rows[year]) * 2,
            }
            for year in sorted(target_rows)
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
    (output / "README.md").write_text(render_markdown(report), encoding="utf-8")
    print(json.dumps({
        "output_dir": str(output),
        "selected_arm": selected_arm,
        "development_binomial_log_loss": dev_metrics[selected_arm]["binomial_log_loss_per_point"],
        "validation_baseline_binomial_log_loss": validation_metrics["atp_only"]["binomial_log_loss_per_point"],
        "validation_selected_binomial_log_loss": validation_metrics[selected_arm]["binomial_log_loss_per_point"],
        "validation_delta_bootstrap": bootstrap_delta,
        "contains_player_names_or_ids": False,
        "raw_source_csv_included": False,
    }, indent=2))
    return report


def main():
    parser = argparse.ArgumentParser(
        description="Audit research-only Challenger / ATP qualifying player-profile backfill."
    )
    parser.add_argument("--start-season", type=int, default=2012)
    parser.add_argument("--end-season", type=int, default=2025)
    parser.add_argument("--development-end", type=int, default=2018)
    parser.add_argument("--bootstrap", type=int, default=2000)
    parser.add_argument("--cache-dir", default=".cache/lower-tier-backfill")
    parser.add_argument("--output-dir", default="build/lower-tier-profile-backfill")
    args = parser.parse_args()
    if args.bootstrap < 100:
        parser.error("--bootstrap must be at least 100")
    run_experiment(
        args.start_season, args.end_season, args.development_end,
        args.bootstrap, args.cache_dir, args.output_dir,
    )


if __name__ == "__main__":
    main()
