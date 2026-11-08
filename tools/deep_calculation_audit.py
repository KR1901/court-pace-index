#!/usr/bin/env python3
"""Out-of-sample, surface-stratified audit of the Court Pace Index ace model.

Every event is scored with player profiles and the tour baseline rebuilt after removing
all matches from that event. Match-cluster bootstrap intervals preserve the two sides of
a match together. This is a validation tool; it does not change production ratings.
"""
import argparse
import json
import math
import random
import statistics
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compare_speed_variants as reference  # noqa: E402
import compute_speed  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "app" / "src" / "main" / "assets" / "pace_dataset.json"
FLOOR = compute_speed.EXPECTED_ACES_FLOOR
MIN_SIDES = 20


def empty_stats():
    return {"aces": 0.0, "points": 0.0, "matches": 0}


def add_stats(target, aces, points):
    target["aces"] += float(aces)
    target["points"] += float(points)
    target["matches"] += 1


def subtract_stats(total, excluded):
    excluded = excluded or empty_stats()
    return {
        "aces": max(0.0, total["aces"] - excluded["aces"]),
        "points": max(0.0, total["points"] - excluded["points"]),
        "matches": max(0, total["matches"] - excluded["matches"]),
    }


TA_KEY_BY_ID = {
    "ausopen": "australianopen",
    "indianwells": "indianwells",
    "montecarlo": "montecarlo",
    "canada": "canada",
    "cincinnati": "cincinnati",
    "shanghai": "shanghai",
    "paris": "parismasters",
    "atpfinals": "tourfinals",
    "rio": "riodejaneiro",
}


def event_id(name):
    raw = (name or "").strip()
    tournament_id = compute_speed.NAMES.get(raw)
    if tournament_id:
        return TA_KEY_BY_ID.get(tournament_id, tournament_id.casefold())
    return reference.tournament_key(raw)


def ensure_player(container, player_id):
    if player_id not in container:
        container[player_id] = {
            role: {bucket: empty_stats() for bucket in ("clay", "hard_grass", "hard", "grass", "carpet", "pooled")}
            for role in ("serve", "return")
        }
    return container[player_id]


def ensure_event_player(container, tournament_id, player_id):
    if tournament_id not in container:
        container[tournament_id] = {}
    return ensure_player(container[tournament_id], player_id)


def ensure_event_stats(container, tournament_id):
    if tournament_id not in container:
        container[tournament_id] = {
            role: {bucket: empty_stats() for bucket in ("clay", "hard_grass", "hard", "grass", "carpet", "pooled")}
            for role in ("serve", "return")
        }
    return container[tournament_id]


def surface_bucket(surface):
    surface = (surface or "").strip().casefold()
    if surface == "clay":
        return "clay"
    if surface in ("hard", "grass"):
        return "hard_grass"
    if surface == "carpet":
        return "carpet"
    return None


def exact_surface_bucket(surface):
    surface = (surface or "").strip().casefold()
    return surface if surface in ("clay", "hard", "grass", "carpet") else None


def aggregate_season(rows):
    """Aggregate player/event sufficient statistics, retaining unnamed rows for priors."""
    usable = compute_speed.valid_rows(rows)
    players = {}
    by_event_players = {}
    global_stats = {
        role: {bucket: empty_stats() for bucket in ("clay", "hard_grass", "hard", "grass", "carpet", "pooled")}
        for role in ("serve", "return")
    }
    event_stats = {}
    event_rows = defaultdict(list)
    event_surfaces = {}

    for row in usable:
        tid = event_id(row.get("tourney_name"))
        if tid:
            event_rows[tid].append(row)
            surface = (row.get("surface") or "").strip()
            event_surfaces.setdefault(tid, surface or "Unknown")
        else:
            surface = (row.get("surface") or "").strip()
        bucket = surface_bucket(surface)
        exact_bucket = exact_surface_bucket(surface)

        w_id, l_id = row["winner_id"], row["loser_id"]
        w_sv, l_sv = compute_speed.num(row["w_svpt"]), compute_speed.num(row["l_svpt"])
        w_ac, l_ac = compute_speed.num(row["w_ace"]), compute_speed.num(row["l_ace"])

        for player_id, aces, points, conceded_aces, conceded_points in (
            (w_id, w_ac, w_sv, l_ac, l_sv),
            (l_id, l_ac, l_sv, w_ac, w_sv),
        ):
            player = ensure_player(players, player_id)
            event_player = ensure_event_player(by_event_players, tid, player_id) if tid else None
            event_total = ensure_event_stats(event_stats, tid) if tid else None

            # All valid ATP matches, including rows with an unmapped event label, train
            # player tendencies and the tour anchor. Only named events form event clusters.
            add_stats(player["serve"]["pooled"], aces, points)
            add_stats(global_stats["serve"]["pooled"], aces, points)
            add_stats(player["return"]["pooled"], conceded_aces, conceded_points)
            add_stats(global_stats["return"]["pooled"], conceded_aces, conceded_points)
            if event_player is not None:
                add_stats(event_player["serve"]["pooled"], aces, points)
                add_stats(event_total["serve"]["pooled"], aces, points)
                add_stats(event_player["return"]["pooled"], conceded_aces, conceded_points)
                add_stats(event_total["return"]["pooled"], conceded_aces, conceded_points)

            if bucket:
                add_stats(player["serve"][bucket], aces, points)
                add_stats(global_stats["serve"][bucket], aces, points)
                add_stats(player["return"][bucket], conceded_aces, conceded_points)
                add_stats(global_stats["return"][bucket], conceded_aces, conceded_points)
                if event_player is not None:
                    add_stats(event_player["serve"][bucket], aces, points)
                    add_stats(event_total["serve"][bucket], aces, points)
                    add_stats(event_player["return"][bucket], conceded_aces, conceded_points)
                    add_stats(event_total["return"][bucket], conceded_aces, conceded_points)

            # Preserve hard and grass separately for an explicit sensitivity experiment.
            if exact_bucket in ("hard", "grass"):
                add_stats(player["serve"][exact_bucket], aces, points)
                add_stats(global_stats["serve"][exact_bucket], aces, points)
                add_stats(player["return"][exact_bucket], conceded_aces, conceded_points)
                add_stats(global_stats["return"][exact_bucket], conceded_aces, conceded_points)
                if event_player is not None:
                    add_stats(event_player["serve"][exact_bucket], aces, points)
                    add_stats(event_total["serve"][exact_bucket], aces, points)
                    add_stats(event_player["return"][exact_bucket], conceded_aces, conceded_points)
                    add_stats(event_total["return"][exact_bucket], conceded_aces, conceded_points)

    return usable, event_rows, event_surfaces, players, by_event_players, global_stats, event_stats


def posterior_rate(player_stats, prior_stats, prior_points, min_matches):
    """Beta-binomial style rate shrinkage using service points as the exposure."""
    if player_stats["points"] <= 0 or player_stats["matches"] < min_matches:
        return None
    prior_rate = (
        prior_stats["aces"] / prior_stats["points"]
        if prior_stats["points"] > 0 else None
    )
    if prior_points > 0 and prior_rate is not None:
        return (player_stats["aces"] + prior_points * prior_rate) / (
            player_stats["points"] + prior_points
        )
    return player_stats["aces"] / player_stats["points"]


def resolve_prior_points(prior_points, role, bucket):
    """Resolve fixed or role/surface-specific empirical-Bayes pseudo service points."""
    if isinstance(prior_points, dict):
        role_values = prior_points.get(role, prior_points)
        if isinstance(role_values, dict):
            return float(role_values.get(bucket, 0.0))
    return float(prior_points)


def estimate_empirical_bayes_prior_points(players, event_players, prior_players,
                                          min_matches=3, min_prior=25.0, max_prior=1000.0):
    """
    Estimate beta-binomial concentration by moments from training players only.

    Remove the target event from each player's sample before measuring between-player
    variance. Subtract average binomial sampling variance from the observed variance,
    then convert the remaining heterogeneity into an equivalent service-point prior.
    """
    result = {}
    diagnostics = {}
    for role in ("serve", "return"):
        result[role] = {}
        diagnostics[role] = {}
        for bucket in ("clay", "hard_grass", "carpet", "pooled"):
            aggregate = prior_players[role][bucket]
            mean_rate = (
                aggregate["aces"] / aggregate["points"]
                if aggregate["points"] > 0 else None
            )
            samples = []
            for player_id, total in players.items():
                event_sample = event_players.get(player_id, {}).get(role, {}).get(bucket)
                sample = subtract_stats(total[role][bucket], event_sample)
                if sample["points"] > 0 and sample["matches"] >= min_matches:
                    samples.append((sample["aces"] / sample["points"], sample["points"]))
            if mean_rate is None or len(samples) < 3:
                observed_variance = 0.0
                sampling_variance = 0.0
                between_variance = 0.0
                prior = max_prior
            else:
                rates = [rate for rate, _points in samples]
                observed_variance = statistics.variance(rates)
                sampling_variance = statistics.fmean(
                    rate * (1.0 - rate) / points
                    for rate, points in samples if points > 0
                )
                between_variance = max(0.0, observed_variance - sampling_variance)
                if between_variance <= 1e-10:
                    prior = max_prior
                else:
                    concentration = mean_rate * (1.0 - mean_rate) / between_variance - 1.0
                    prior = min(max_prior, max(min_prior, concentration))
            result[role][bucket] = prior
            diagnostics[role][bucket] = {
                "players": len(samples),
                "meanRate": mean_rate,
                "observedVariance": observed_variance,
                "samplingVariance": sampling_variance,
                "betweenPlayerVariance": between_variance,
                "priorServicePoints": prior,
            }
    return result, diagnostics


def weighted_profile(player, role, prior_player, clay_weight, prior_points, min_matches,
                     pooled_fallback, pooled_min_matches=None):
    """Build one neutral player tendency; return None when no defensible sample exists."""
    rates = {}
    for bucket in ("clay", "hard_grass"):
        rates[bucket] = posterior_rate(
            player[role][bucket], prior_player[role][bucket],
            resolve_prior_points(prior_points, role, bucket), min_matches
        )
    clay, hard_grass = rates["clay"], rates["hard_grass"]
    if clay is not None and hard_grass is not None:
        return clay_weight * clay + (1.0 - clay_weight) * hard_grass
    if clay is not None:
        return clay
    if hard_grass is not None:
        return hard_grass
    if pooled_fallback:
        return posterior_rate(
            player[role]["pooled"], prior_player[role]["pooled"],
            resolve_prior_points(prior_points, role, "pooled"),
            min_matches if pooled_min_matches is None else pooled_min_matches,
        )
    return None


def build_event_profiles(tid, event_surface, players, event_players, global_stats,
                         event_stats, config, target_player_ids=None):
    """Build training-only profiles, including target players with no training history."""
    excluded_players = dict(event_players.get(tid, {}))
    for player_id in target_player_ids or ():
        if player_id not in excluded_players:
            ensure_player(excluded_players, player_id)
    excluded_totals = event_stats.get(tid, {
        role: {bucket: empty_stats() for bucket in ("clay", "hard_grass", "hard", "grass", "carpet", "pooled")}
        for role in ("serve", "return")
    })
    prior_players = {
        role: {
            bucket: subtract_stats(global_stats[role][bucket], excluded_totals[role][bucket])
            for bucket in ("clay", "hard_grass", "hard", "grass", "carpet", "pooled")
        }
        for role in ("serve", "return")
    }
    profiles = {}
    prior_points = config["prior_points"]
    if config.get("empirical_bayes"):
        prior_points, prior_diagnostics = estimate_empirical_bayes_prior_points(
            players, excluded_players, prior_players, min_matches=3
        )
        # Keep audit metadata separate from the serve/return surface aggregates.
        prior_players["_empirical_bayes_audit"] = prior_diagnostics
    carpet_pooled = (
        config.get("carpet_pooled")
        and (event_surface or "").strip().casefold() == "carpet"
    )
    carpet_specific = (
        config.get("carpet_specific")
        and (event_surface or "").strip().casefold() == "carpet"
    )
    grass_specific = (
        config.get("grass_specific")
        and (event_surface or "").strip().casefold() == "grass"
    )

    for player_id, event_player in excluded_players.items():
        total_player = players.get(player_id)
        if total_player is None:
            total_player = ensure_player({}, player_id)
        training_player = {
            role: {
                bucket: subtract_stats(
                    total_player[role][bucket],
                    event_player.get(role, {}).get(bucket),
                )
                for bucket in ("clay", "hard_grass", "hard", "grass", "carpet", "pooled")
            }
            for role in ("serve", "return")
        }

        if grass_specific:
            serve_rate = posterior_rate(
                training_player["serve"]["grass"], prior_players["serve"]["grass"],
                prior_points, config["min_matches"],
            )
            concede_rate = posterior_rate(
                training_player["return"]["grass"], prior_players["return"]["grass"],
                prior_points, config["min_matches"],
            )
            # Without enough grass history, retain the normal neutral hard/grass + clay profile.
            if serve_rate is None:
                serve_rate = weighted_profile(
                    training_player, "serve", prior_players,
                    config["clay_weight"], prior_points, config["min_matches"],
                    config.get("pooled_serve_fallback", False),
                )
            if concede_rate is None:
                concede_rate = weighted_profile(
                    training_player, "return", prior_players,
                    config["clay_weight"], prior_points, config["min_matches"],
                    config.get("pooled_return_fallback", True),
                    config.get("pooled_return_min_matches", 1),
                )
        elif carpet_pooled:
            serve_rate = posterior_rate(
                training_player["serve"]["pooled"], prior_players["serve"]["pooled"],
                prior_points, config["min_matches"],
            )
            concede_rate = posterior_rate(
                training_player["return"]["pooled"], prior_players["return"]["pooled"],
                prior_points, config["min_matches"],
            )
        elif carpet_specific:
            serve_rate = posterior_rate(
                training_player["serve"]["carpet"], prior_players["serve"]["carpet"],
                prior_points, config["min_matches"],
            )
            concede_rate = posterior_rate(
                training_player["return"]["carpet"], prior_players["return"]["carpet"],
                prior_points, config["min_matches"],
            )
            # A player without three eligible carpet matches retains the usual neutral profile.
            if serve_rate is None:
                serve_rate = weighted_profile(
                    training_player, "serve", prior_players,
                    config["clay_weight"], prior_points, config["min_matches"],
                    config.get("pooled_serve_fallback", False),
                )
            if concede_rate is None:
                concede_rate = weighted_profile(
                    training_player, "return", prior_players,
                    config["clay_weight"], prior_points, config["min_matches"],
                    config.get("pooled_return_fallback", True),
                    config.get("pooled_return_min_matches", 1),
                )
        else:
            serve_rate = weighted_profile(
                training_player, "serve", prior_players,
                config["clay_weight"], prior_points, config["min_matches"],
                config.get("pooled_serve_fallback", False),
            )
            concede_rate = weighted_profile(
                training_player, "return", prior_players,
                config["clay_weight"], prior_points, config["min_matches"],
                config.get("pooled_return_fallback", True),
                config.get("pooled_return_min_matches", 1),
            )

        profiles[player_id] = {
            "serve_rate": serve_rate,
            "concede_rate": concede_rate,
        }
    return profiles, prior_players


def tour_rate_excluding_event(tid, global_stats, event_stats):
    """Season ace probability after subtracting all matches from the target event."""
    excluded = event_stats.get(tid, {
        role: {bucket: empty_stats() for bucket in ("clay", "hard_grass", "hard", "grass", "carpet", "pooled")}
        for role in ("serve", "return")
    })
    tour = subtract_stats(
        global_stats["serve"]["pooled"], excluded["serve"]["pooled"]
    )
    return tour["aces"] / tour["points"] if tour["points"] > 0 else None


def score_event(tid, event_rows, event_surface, profiles, global_stats,
                event_stats, prior_players):
    """Return match-clustered ratios using a tour baseline excluding this event."""
    tour_rate = tour_rate_excluding_event(tid, global_stats, event_stats)
    if tour_rate is None or not 0.0 < tour_rate < 1.0:
        return [], None

    clusters = []
    for row in event_rows:
        match_ratios = []
        for server, returner, service_points, actual_aces in (
            (row["winner_id"], row["loser_id"],
             compute_speed.num(row["w_svpt"]), compute_speed.num(row["w_ace"])),
            (row["loser_id"], row["winner_id"],
             compute_speed.num(row["l_svpt"]), compute_speed.num(row["l_ace"])),
        ):
            serve_rate = profiles.get(server, {}).get("serve_rate")
            concede_rate = profiles.get(returner, {}).get("concede_rate")
            if serve_rate is None or concede_rate is None:
                continue
            expected = compute_speed.expected_calibrated_aces(
                serve_rate, concede_rate, tour_rate, service_points
            )
            if expected <= 0 or not math.isfinite(expected):
                continue
            match_ratios.append(actual_aces / max(expected, FLOOR))
        if match_ratios:
            clusters.append(match_ratios)

    flattened = [ratio for cluster in clusters for ratio in cluster]
    return flattened, clusters


def config_grid():
    configs = {
        "loeo_current_1_3": {
            "clay_weight": 1.0 / 3.0, "prior_points": 0.0, "min_matches": 3,
            "pooled_return_fallback": True, "pooled_return_min_matches": 1,
        },
        "loeo_carpet_pooled": {
            "clay_weight": 1.0 / 3.0, "prior_points": 0.0, "min_matches": 3,
            "pooled_return_fallback": True, "pooled_return_min_matches": 1,
            "carpet_pooled": True,
        },
        "loeo_carpet_specific": {
            "clay_weight": 1.0 / 3.0, "prior_points": 0.0, "min_matches": 3,
            "pooled_return_fallback": True, "pooled_return_min_matches": 1,
            "carpet_specific": True,
        },
        "loeo_grass_specific_shrink_150": {
            "clay_weight": 1.0 / 3.0, "prior_points": 150.0, "min_matches": 3,
            "pooled_return_fallback": True, "pooled_return_min_matches": 1,
            "grass_specific": True,
        },
        "loeo_empirical_bayes_1_3": {
            "clay_weight": 1.0 / 3.0, "prior_points": 0.0, "min_matches": 1,
            "pooled_return_fallback": True, "pooled_return_min_matches": 1,
            "empirical_bayes": True,
        },
    }
    for weight in (0.0, 0.2, 0.5, 2.0 / 3.0, 0.8, 1.0):
        label = f"{weight:.3f}".replace(".", "_")
        configs[f"weight_clay_{label}"] = {
            "clay_weight": weight, "prior_points": 0.0, "min_matches": 3,
            "pooled_return_fallback": True,
        }
    for prior in (75.0, 100.0, 150.0, 200.0, 250.0, 300.0, 450.0, 600.0, 1000.0):
        configs[f"shrink_{int(prior)}"] = {
            "clay_weight": 1.0 / 3.0, "prior_points": prior, "min_matches": 1,
            "pooled_return_fallback": True, "pooled_return_min_matches": 1,
        }
    # Isolate shrinkage from profile-coverage changes: same three-match qualification,
    # with only the prior strength changed.
    for prior in (100.0, 150.0, 300.0):
        configs[f"shrink_{int(prior)}_min3"] = {
            "clay_weight": 1.0 / 3.0, "prior_points": prior, "min_matches": 3,
            "pooled_return_fallback": True, "pooled_return_min_matches": 1,
        }
    configs["loeo_empirical_bayes_1_3_min3"] = {
        "clay_weight": 1.0 / 3.0, "prior_points": 0.0, "min_matches": 3,
        "pooled_return_fallback": True, "pooled_return_min_matches": 1,
        "empirical_bayes": True,
    }
    # Test whether the best training-window weight and best shrinkage prior interact.
    configs["weight_050_shrink_150"] = {
        "clay_weight": 0.5, "prior_points": 150.0, "min_matches": 1,
        "pooled_return_fallback": True, "pooled_return_min_matches": 1,
    }
    configs["weight_050_shrink_300"] = {
        "clay_weight": 0.5, "prior_points": 300.0, "min_matches": 1,
        "pooled_return_fallback": True, "pooled_return_min_matches": 1,
    }
    return configs


def score_season(rows, configs, detail_ids):
    (usable, event_rows, event_surfaces, players, event_players,
     global_stats, event_stats) = aggregate_season(rows)
    raw = {name: {} for name in configs}
    details = {name: {} for name in configs}
    normalisers = {}
    empirical_bayes_strengths = defaultdict(lambda: defaultdict(list))

    for tid, matches in event_rows.items():
        surface = event_surfaces.get(tid, "Unknown")
        for name, config in configs.items():
            profiles, prior_players = build_event_profiles(
                tid, surface, players, event_players, global_stats, event_stats, config
            )
            if config.get("empirical_bayes"):
                for role, buckets in prior_players.get("_empirical_bayes_audit", {}).items():
                    for bucket, values in buckets.items():
                        if values.get("priorServicePoints") is not None:
                            empirical_bayes_strengths[role][bucket].append(
                                values["priorServicePoints"]
                            )
            ratios, clusters = score_event(
                tid, matches, surface, profiles, global_stats, event_stats, prior_players
            )
            if tid in detail_ids and ratios:
                details[name][tid] = {
                    "surface": surface,
                    "matches": len(matches),
                    "sides": len(ratios),
                    "raw": statistics.fmean(ratios),
                    "ordinaryRatingEligible": len(ratios) >= MIN_SIDES,
                    "clusters": clusters,
                }
            if len(ratios) < MIN_SIDES:
                continue
            raw[name][tid] = statistics.fmean(ratios)

    for name, ratings in raw.items():
        normalisers[name] = statistics.fmean(ratings.values()) if ratings else None
    if empirical_bayes_strengths:
        print("  empirical-Bayes prior exposure (median pseudo service points across LOEO events):")
        for role, buckets in sorted(empirical_bayes_strengths.items()):
            for bucket, values in sorted(buckets.items()):
                print(f"    {role:7s} {bucket:10s} n={len(values):4d} "
                      f"median={statistics.median(values):.1f}")
    return raw, normalisers, details, len(usable), len(event_rows), event_surfaces, event_rows


def mean_ci(values):
    values = sorted(value for value in values if math.isfinite(value))
    if not values:
        return (float("nan"), float("nan"))
    return (
        reference.percentile(values, 0.025),
        reference.percentile(values, 0.975),
    )


def paired_bootstrap_differences(candidate_samples, baseline_samples):
    """Paired metric deltas from identical bootstrap draws (candidate minus baseline)."""
    if len(candidate_samples) != len(baseline_samples):
        raise ValueError("paired bootstrap samples must have equal lengths")
    if not candidate_samples:
        return {}
    metrics = ("r", "rho", "mae", "bias")
    return {
        metric: [
            candidate[metric] - baseline[metric]
            for candidate, baseline in zip(candidate_samples, baseline_samples)
        ]
        for metric in metrics
    }


def cluster_bootstrap_interval(clusters, normaliser, iterations, seed):
    """Conditional CI: resample whole matches, retaining both server sides together."""
    if not clusters or not normaliser or normaliser <= 0:
        return None
    rng = random.Random(seed)
    count = len(clusters)
    samples = []
    for _ in range(iterations):
        chosen = [clusters[rng.randrange(count)] for _ in range(count)]
        ratios = [ratio for cluster in chosen for ratio in cluster]
        if ratios:
            samples.append(statistics.fmean(ratios) / normaliser)
    if not samples:
        return None
    low, high = mean_ci(samples)
    return {"low": low, "high": high, "iterations": iterations}



def profile_and_match_bootstrap_interval(tid, target_rows, event_surface, all_event_rows,
                                         config, normaliser, iterations=250, seed=1):
    """
    Nested bootstrap: resample training matches within each tournament, rebuild the
    target players' profiles, and resample target-event matches as intact clusters.

    The original LOEO season normaliser is held fixed. This captures player-profile and
    match-sample uncertainty, but deliberately does not claim normaliser uncertainty.
    """
    if not target_rows or not normaliser or normaliser <= 0 or iterations < 1:
        return None
    rng = random.Random(seed)
    target_player_ids = {
        player_id
        for row in target_rows
        for player_id in (row["winner_id"], row["loser_id"])
    }
    samples = []
    for _ in range(iterations):
        training_rows = []
        for event_id_key, matches in all_event_rows.items():
            if event_id_key == tid or not matches:
                continue
            training_rows.extend(rng.choices(matches, k=len(matches)))
        target_sample = rng.choices(target_rows, k=len(target_rows))
        if not training_rows:
            continue

        (_usable, _train_events, _surfaces, players, event_players,
         global_stats, event_stats) = aggregate_season(training_rows)
        profiles, prior_players = build_event_profiles(
            tid, event_surface, players, event_players, global_stats, event_stats,
            config, target_player_ids=target_player_ids,
        )
        ratios, _clusters = score_event(
            tid, target_sample, event_surface, profiles,
            global_stats, event_stats, prior_players,
        )
        if ratios:
            samples.append(statistics.fmean(ratios) / normaliser)

    if not samples:
        return None
    low, high = mean_ci(samples)
    return {
        "low": low,
        "high": high,
        "iterations": iterations,
        "successful": len(samples),
        "normaliser": "fixed to full-sample LOEO mean",
    }


def full_pipeline_bootstrap_interval(tid, all_rows, config, iterations=250, seed=1):
    """
    Full-pipeline bootstrap for one event.

    Resample matches within each tournament, rebuild every player profile and each event's
    leave-one-event-out tour anchor, then recompute the season normaliser on every draw.
    Unlike the conditional and profile-only intervals, this propagates normaliser uncertainty.
    """
    if not all_rows or iterations < 1:
        return None
    grouped = defaultdict(list)
    for row in all_rows:
        key = event_id(row.get("tourney_name")) or "__unmapped__"
        grouped[key].append(row)
    if tid not in grouped:
        return None

    rng = random.Random(seed)
    samples = []
    for _ in range(iterations):
        sampled_rows = []
        for matches in grouped.values():
            sampled_rows.extend(rng.choices(matches, k=len(matches)))
        (_usable, event_rows, event_surfaces, players, event_players,
         global_stats, event_stats) = aggregate_season(sampled_rows)

        raw_by_event = {}
        for event_id_key, matches in event_rows.items():
            surface = event_surfaces.get(event_id_key, "Unknown")
            profiles, prior_players = build_event_profiles(
                event_id_key, surface, players, event_players,
                global_stats, event_stats, config,
            )
            ratios, _clusters = score_event(
                event_id_key, matches, surface, profiles,
                global_stats, event_stats, prior_players,
            )
            if len(ratios) >= MIN_SIDES:
                raw_by_event[event_id_key] = statistics.fmean(ratios)

        normaliser = statistics.fmean(raw_by_event.values()) if raw_by_event else None
        target_raw = raw_by_event.get(tid)
        if normaliser is not None and normaliser > 0 and target_raw is not None:
            samples.append(target_raw / normaliser)

    if not samples:
        return None
    low, high = mean_ci(samples)
    return {
        "low": low,
        "high": high,
        "iterations": iterations,
        "successful": len(samples),
        "normaliser": "recomputed from all LOEO event scores on every bootstrap draw",
    }


def physical_cpi_diagnostics(dataset_path):
    if not dataset_path.exists():
        return {"status": "dataset not found"}
    dataset = json.loads(dataset_path.read_text(encoding="utf-8"))
    cpi = {(row["t"], row["s"]): row["cpi"] for row in dataset["readings"]
           if row.get("cpi") is not None}
    surfaces = {item["id"]: item.get("surface", "Unknown").casefold()
                for item in dataset.get("tournaments", [])}
    pairs = []
    for row in dataset["readings"]:
        if row.get("src") != "computed" or row.get("speed") is None:
            continue
        value = cpi.get((row["t"], row["s"]))
        if value is not None:
            pairs.append((row["s"], row["t"], surfaces.get(row["t"], "unknown"),
                          float(row["speed"]), float(value)))
    if len(pairs) < 4:
        return {"n": len(pairs), "status": "insufficient overlaps"}
    result = {"n": len(pairs), "pearson": reference.pearson(
        [row[3] for row in pairs], [row[4] for row in pairs]
    ), "spearman": reference.spearman(
        [row[3] for row in pairs], [row[4] for row in pairs]
    ), "by_surface": {}}
    for surface in sorted({row[2] for row in pairs}):
        subset = [row for row in pairs if row[2] == surface]
        if len(subset) >= 4:
            result["by_surface"][surface] = {
                "n": len(subset),
                "pearson": reference.pearson([row[3] for row in subset],
                                             [row[4] for row in subset]),
                "spearman": reference.spearman([row[3] for row in subset],
                                               [row[4] for row in subset]),
            }
    # Remove surface means before calculating the pooled within-surface association.
    cpi_mean = {surface: statistics.fmean(row[4] for row in pairs if row[2] == surface)
                for surface in {row[2] for row in pairs}}
    speed_mean = {surface: statistics.fmean(row[3] for row in pairs if row[2] == surface)
                  for surface in {row[2] for row in pairs}}
    result["within_surface_centered"] = {
        "pearson": reference.pearson(
            [row[3] - speed_mean[row[2]] for row in pairs],
            [row[4] - cpi_mean[row[2]] for row in pairs],
        ),
        "spearman": reference.spearman(
            [row[3] - speed_mean[row[2]] for row in pairs],
            [row[4] - cpi_mean[row[2]] for row in pairs],
        ),
    }
    result["caveat"] = (
        "CPI overlaps cover a small, non-random set of show-court events; "
        "association is diagnostic, not independent validation of all tournaments."
    )
    return result


def run_audit(seasons, boot, detail_ids):
    configs = config_grid()
    published = reference.fetch_tennis_abstract_speeds(seasons)
    if not published:
        raise RuntimeError("No Tennis Abstract reference seasons were available.")

    pairs = {name: [] for name in configs}
    keys = {name: [] for name in configs}
    surfaces_by_key = {}
    raw_by_season = {}
    detail_by_season = {}
    event_rows_by_season = {}
    rows_by_season = {}

    print("season  usable matches  events  TA references  common LOEO events")
    for year in sorted(published):
        rows = compute_speed.load(year)
        rows_by_season[year] = rows
        raw, normalisers, details, usable_count, event_count, event_surfaces, event_rows = score_season(
            rows, configs, detail_ids
        )
        raw_by_season[year] = raw
        detail_by_season[year] = (details, normalisers)
        event_rows_by_season[year] = event_rows
        common = set(published[year])
        for variant in configs:
            common.intersection_update(raw[variant])
        common = sorted(common)
        print(f"{year}  {usable_count:14,d}  {event_count:6d}  "
              f"{len(published[year]):13d}  {len(common):18d}")
        if not common:
            continue
        reference_mean = statistics.fmean(published[year][tid] for tid in common)
        reference_values = {tid: published[year][tid] / reference_mean for tid in common}
        for tid in common:
            surfaces_by_key[(year, tid)] = event_surfaces.get(tid, "Unknown")
        for variant in configs:
            calc_mean = statistics.fmean(raw[variant][tid] for tid in common)
            for tid in common:
                pairs[variant].append((
                    raw[variant][tid] / calc_mean,
                    reference_values[tid],
                ))
                keys[variant].append((year, tid))

    shared = set(keys[next(iter(configs))])
    for variant in configs:
        shared.intersection_update(keys[variant])
    shared = sorted(shared)
    if len(shared) < 20:
        raise RuntimeError(f"Only {len(shared)} common LOEO event-seasons; audit needs at least 20.")

    tables = {}
    for variant in configs:
        index = {key: pair for key, pair in zip(keys[variant], pairs[variant])}
        tables[variant] = [index[key] for key in shared]

    print("\nEVENT COVERAGE BY VARIANT (rated event-seasons before common-set intersection)")
    for variant in configs:
        total_rated = sum(len(season_raw.get(variant, {})) for season_raw in raw_by_season.values())
        common_rated = len(keys[variant])
        print(f"  {variant:30s} rated={total_rated:5d} common={common_rated:5d}")

    print(f"\nLEAVE-ONE-TOURNAMENT-OUT RESULTS: {len(shared)} event-seasons, "
          f"{len({year for year, _ in shared})} seasons")
    print("variant                         r       rho      MAE      bias")
    point_metrics = {}
    for variant in configs:
        point = reference.metrics(tables[variant])
        point_metrics[variant] = point
        print(f"{variant:30s} {point['r']:7.3f} {point['rho']:8.3f} "
              f"{point['mae']:8.3f} {point['bias']:+8.3f}")

    draws = reference.season_stratified_draws(shared, boot, 73191)
    bootstrap = {
        variant: [reference.metrics([tables[variant][index] for index in draw])
                  for draw in draws]
        for variant in configs
    }
    print(f"\nSEASON-STRATIFIED MATCHED BOOTSTRAP ({boot} resamples)")
    for variant in configs:
        result = point_metrics[variant]
        r_ci = mean_ci([sample["r"] for sample in bootstrap[variant]])
        rho_ci = mean_ci([sample["rho"] for sample in bootstrap[variant]])
        mae_ci = mean_ci([sample["mae"] for sample in bootstrap[variant]])
        print(f"{variant:30s} r {result['r']:.3f} [{r_ci[0]:.3f},{r_ci[1]:.3f}]  "
              f"rho {result['rho']:.3f} [{rho_ci[0]:.3f},{rho_ci[1]:.3f}]  "
              f"MAE {result['mae']:.3f} [{mae_ci[0]:.3f},{mae_ci[1]:.3f}]")

    print("\nPAIRED BOOTSTRAP DIFFERENCES (candidate minus baseline; same resamples)")
    comparison_pairs = (
        ("shrink_150_min3", "loeo_current_1_3"),
        ("loeo_empirical_bayes_1_3_min3", "loeo_current_1_3"),
        ("loeo_empirical_bayes_1_3_min3", "shrink_150_min3"),
    )
    for candidate, baseline in comparison_pairs:
        deltas = paired_bootstrap_differences(bootstrap[candidate], bootstrap[baseline])
        print(f"  {candidate} minus {baseline}:")
        for metric in ("r", "rho", "mae"):
            values = deltas[metric]
            delta = point_metrics[candidate][metric] - point_metrics[baseline][metric]
            low, high = mean_ci(values)
            conclusion = "interval includes zero" if low <= 0.0 <= high else "interval excludes zero"
            print(f"    {metric:5s} {delta:+.4f} [{low:+.4f},{high:+.4f}] ({conclusion})")

    # Compare weights on an earlier training window and a later held-out window.
    training_keys = [key for key in shared if key[0] <= 2020]
    holdout_keys = [key for key in shared if 2021 <= key[0] <= 2025]
    print("\nWEIGHT-SENSITIVITY: train 1991-2020; held-out 2021-2025")
    print("variant                         train MAE   holdout MAE   heldout rho")
    sensitivity_names = [
        name for name in configs
        if name == "loeo_current_1_3" or name.startswith("weight_clay_")
        or name.startswith("shrink_") or name.startswith("weight_050_shrink_")
        or name.startswith("loeo_empirical_bayes")
    ]
    for name in sensitivity_names:
        index = {key: pair for key, pair in zip(keys[name], pairs[name])}
        train = [index[key] for key in training_keys if key in index]
        test = [index[key] for key in holdout_keys if key in index]
        if train and test:
            train_metrics = reference.metrics(train)
            test_metrics = reference.metrics(test)
            print(f"{name:30s} {train_metrics['mae']:10.3f} "
                  f"{test_metrics['mae']:13.3f} {test_metrics['rho']:13.3f}")

    print("\nSURFACE-STRATIFIED LEAVE-ONE-OUT ERROR")
    for variant in configs:
        index = {key: pair for key, pair in zip(keys[variant], pairs[variant])}
        print(f"  {variant}:")
        for surface in ("clay", "hard", "grass", "carpet"):
            selected = [index[key] for key in shared
                        if key in index and surfaces_by_key.get(key, "").casefold() == surface]
            if len(selected) >= 4:
                m = reference.metrics(selected)
                print(f"    {surface:8s} n={len(selected):4d} "
                      f"rho={m['rho']:.3f} MAE={m['mae']:.3f} bias={m['bias']:+.3f}")

    detail_years = sorted(rows_by_season)[-2:]
    print(f"\nEVENT-LEVEL MATCH-CLUSTER BOOTSTRAP INTERVALS ({detail_years}; conditional on profiles)")
    for year in detail_years:
        year_details, year_normalisers = detail_by_season[year]
        for variant, events in year_details.items():
            for tid, item in events.items():
                if tid not in detail_ids:
                    continue
                normaliser = year_normalisers.get(variant)
                interval = cluster_bootstrap_interval(
                    item["clusters"], normaliser, max(500, boot // 2),
                    1000 + year + sum(ord(c) for c in tid) + sum(ord(c) for c in variant),
                )
                if interval:
                    status = "standard" if item["ordinaryRatingEligible"] else "provisional (below 20 sides)"
                    print(f"  {year} {variant} {tid}: {status}, LOEO={item['raw'] / normaliser:.3f}, "
                          f"95% CI [{interval['low']:.3f},{interval['high']:.3f}], "
                          f"{item['matches']} matches/{item['sides']} sides")

    # This second interval re-fits the player profiles in every draw. The season
    # normaliser is intentionally held fixed to isolate profile + match sampling variance.
    print("\nEVENT-LEVEL PROFILE-AND-MATCH BOOTSTRAP (2026; fixed LOEO normaliser)")
    latest_year = max(event_rows_by_season)
    latest_events = event_rows_by_season[latest_year]
    latest_details, latest_normalisers = detail_by_season[latest_year]
    profile_bootstrap_variants = ("loeo_current_1_3", "shrink_150_min3",
                                  "loeo_empirical_bayes_1_3_min3")
    for variant in profile_bootstrap_variants:
        if variant not in configs:
            continue
        for tid in sorted(detail_ids):
            item = latest_details.get(variant, {}).get(tid)
            matches = latest_events.get(tid)
            normaliser = latest_normalisers.get(variant)
            if not item or not matches or not normaliser:
                continue
            interval = profile_and_match_bootstrap_interval(
                tid, matches, item["surface"], latest_events, configs[variant],
                normaliser, iterations=250,
                seed=20000 + sum(ord(char) for char in (variant + tid)),
            )
            if interval:
                status = "standard" if item["ordinaryRatingEligible"] else "provisional (below 20 sides)"
                print(f"  {variant} {tid}: {status}, LOEO={item['raw'] / normaliser:.3f}, "
                      f"95% CI [{interval['low']:.3f},{interval['high']:.3f}], "
                      f"{interval['successful']}/{interval['iterations']} successful resamples "
                      f"({item['matches']} matches/{item['sides']} sides)")

    # Recompute the season normaliser on every resample for the two most recent seasons.
    # This is the full uncertainty propagation requested by the audit council.
    print("\nFULL-PIPELINE EVENT BOOTSTRAP (profile + target matches + LOEO normaliser refit)")
    production_config = configs["shrink_150_min3"]
    for year in detail_years:
        year_details, year_normalisers = detail_by_season[year]
        for tid in sorted(detail_ids):
            item = year_details.get("shrink_150_min3", {}).get(tid)
            if not item:
                continue
            interval = full_pipeline_bootstrap_interval(
                tid, rows_by_season[year], production_config,
                iterations=250,
                seed=30000 + year + sum(ord(char) for char in tid),
            )
            if interval:
                normaliser = year_normalisers["shrink_150_min3"]
                status = "standard" if item["ordinaryRatingEligible"] else "provisional (below 20 sides)"
                print(f"  {year} {tid}: {status}, LOEO={item['raw'] / normaliser:.3f}, "
                      f"95% CI [{interval['low']:.3f},{interval['high']:.3f}], "
                      f"{interval['successful']}/{interval['iterations']} successful resamples; "
                      f"normaliser refit each draw ({item['matches']} matches/{item['sides']} sides)")

    print("\nPHYSICAL CPI CROSS-CHECK")
    print(json.dumps(physical_cpi_diagnostics(DATASET), indent=2, sort_keys=True))
    print("\nInterpretation: TA agreement is a behavioural reference comparison, not physical "
          "ground truth. LOEO profiles and tour baselines exclude every match from the event "
          "being rated. Conditional intervals hold profiles fixed; profile-and-match intervals "
          "refit profiles but hold the normaliser fixed; full-pipeline intervals refit profiles, "
          "target matches and the season LOEO normaliser on every bootstrap draw.")


def details_for_event(details, tid):
    for variant in details.values():
        if tid in variant:
            yield variant[tid]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seasons", type=int, nargs="*", default=None)
    parser.add_argument("--boot", type=int, default=2000)
    parser.add_argument("--detail", default="marrakech,lavercup,montpellier,paris",
                        help="comma-separated event ids for conditional match-cluster intervals")
    args = parser.parse_args()
    if args.boot < 100:
        parser.error("--boot must be at least 100 for useful uncertainty estimates")
    seasons = args.seasons or list(range(1991, compute_speed.date.today().year + 1))
    detail_ids = {item.strip() for item in args.detail.split(",") if item.strip()}
    run_audit(seasons, args.boot, detail_ids)


if __name__ == "__main__":
    main()
