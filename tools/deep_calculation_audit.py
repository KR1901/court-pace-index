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
            role: {bucket: empty_stats() for bucket in ("clay", "hard_grass", "pooled")}
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
            role: {bucket: empty_stats() for bucket in ("clay", "hard_grass", "pooled")}
            for role in ("serve", "return")
        }
    return container[tournament_id]


def surface_bucket(surface):
    surface = (surface or "").strip().casefold()
    if surface == "clay":
        return "clay"
    if surface in ("hard", "grass"):
        return "hard_grass"
    return None


def aggregate_season(rows):
    """Aggregate player and event sufficient statistics once for fast event exclusion."""
    usable = compute_speed.valid_rows(rows)
    players = {}
    by_event_players = {}
    global_stats = {
        role: {bucket: empty_stats() for bucket in ("clay", "hard_grass", "pooled")}
        for role in ("serve", "return")
    }
    event_stats = {}
    event_rows = defaultdict(list)
    event_surfaces = {}

    for row in usable:
        tid = event_id(row.get("tourney_name"))
        if not tid:
            continue
        event_rows[tid].append(row)
        surface = (row.get("surface") or "").strip()
        event_surfaces.setdefault(tid, surface or "Unknown")
        bucket = surface_bucket(surface)

        w_id, l_id = row["winner_id"], row["loser_id"]
        w_sv, l_sv = compute_speed.num(row["w_svpt"]), compute_speed.num(row["l_svpt"])
        w_ac, l_ac = compute_speed.num(row["w_ace"]), compute_speed.num(row["l_ace"])

        # Both player-server observations.
        for player_id, aces, points, conceded_aces, conceded_points in (
            (w_id, w_ac, w_sv, l_ac, l_sv),
            (l_id, l_ac, l_sv, w_ac, w_sv),
        ):
            player = ensure_player(players, player_id)
            event_player = ensure_event_player(by_event_players, tid, player_id)
            event_total = ensure_event_stats(event_stats, tid)

            add_stats(player["serve"]["pooled"], aces, points)
            add_stats(event_player["serve"]["pooled"], aces, points)
            add_stats(global_stats["serve"]["pooled"], aces, points)
            add_stats(event_total["serve"]["pooled"], aces, points)

            # A returner's concession rate is the opponent's aces per opponent service point.
            add_stats(player["return"]["pooled"], conceded_aces, conceded_points)
            add_stats(event_player["return"]["pooled"], conceded_aces, conceded_points)
            add_stats(global_stats["return"]["pooled"], conceded_aces, conceded_points)
            add_stats(event_total["return"]["pooled"], conceded_aces, conceded_points)

            if bucket:
                add_stats(player["serve"][bucket], aces, points)
                add_stats(event_player["serve"][bucket], aces, points)
                add_stats(global_stats["serve"][bucket], aces, points)
                add_stats(event_total["serve"][bucket], aces, points)
                add_stats(player["return"][bucket], conceded_aces, conceded_points)
                add_stats(event_player["return"][bucket], conceded_aces, conceded_points)
                add_stats(global_stats["return"][bucket], conceded_aces, conceded_points)
                add_stats(event_total["return"][bucket], conceded_aces, conceded_points)

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


def weighted_profile(player, role, prior_player, clay_weight, prior_points, min_matches,
                     pooled_fallback, pooled_min_matches=None):
    """Build one neutral player tendency; return None when no defensible sample exists."""
    rates = {}
    for bucket in ("clay", "hard_grass"):
        rates[bucket] = posterior_rate(
            player[role][bucket], prior_player[role][bucket], prior_points, min_matches
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
            prior_points,
            min_matches if pooled_min_matches is None else pooled_min_matches,
        )
    return None


def build_event_profiles(tid, event_surface, players, event_players, global_stats,
                         event_stats, config):
    """Build profiles from the season after subtracting every target-event observation."""
    excluded_players = event_players.get(tid, {})
    excluded_totals = event_stats.get(tid, {
        role: {bucket: empty_stats() for bucket in ("clay", "hard_grass", "pooled")}
        for role in ("serve", "return")
    })
    prior_players = {
        role: {
            bucket: subtract_stats(global_stats[role][bucket], excluded_totals[role][bucket])
            for bucket in ("clay", "hard_grass", "pooled")
        }
        for role in ("serve", "return")
    }
    profiles = {}
    carpet_pooled = (
        config.get("carpet_pooled")
        and (event_surface or "").strip().casefold() == "carpet"
    )

    for player_id, event_player in excluded_players.items():
        total_player = players[player_id]
        training_player = {
            role: {
                bucket: subtract_stats(
                    total_player[role][bucket],
                    event_player.get(role, {}).get(bucket),
                )
                for bucket in ("clay", "hard_grass", "pooled")
            }
            for role in ("serve", "return")
        }

        if carpet_pooled:
            serve_rate = posterior_rate(
                training_player["serve"]["pooled"], prior_players["serve"]["pooled"],
                config["prior_points"], config["min_matches"],
            )
            concede_rate = posterior_rate(
                training_player["return"]["pooled"], prior_players["return"]["pooled"],
                config["prior_points"], config["min_matches"],
            )
        else:
            serve_rate = weighted_profile(
                training_player, "serve", prior_players,
                config["clay_weight"], config["prior_points"], config["min_matches"],
                config.get("pooled_serve_fallback", False),
            )
            concede_rate = weighted_profile(
                training_player, "return", prior_players,
                config["clay_weight"], config["prior_points"], config["min_matches"],
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
        role: {bucket: empty_stats() for bucket in ("clay", "hard_grass", "pooled")}
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
            "pooled_return_fallback": True, "carpet_pooled": True,
        },
    }
    for weight in (0.0, 0.2, 0.5, 2.0 / 3.0, 0.8, 1.0):
        label = f"{weight:.3f}".replace(".", "_")
        configs[f"weight_clay_{label}"] = {
            "clay_weight": weight, "prior_points": 0.0, "min_matches": 3,
            "pooled_return_fallback": True,
        }
    for prior in (150.0, 300.0, 600.0, 1000.0):
        configs[f"shrink_{int(prior)}"] = {
            "clay_weight": 1.0 / 3.0, "prior_points": prior, "min_matches": 1,
            "pooled_return_fallback": True,
        }
    return configs


def score_season(rows, configs, detail_ids):
    (usable, event_rows, event_surfaces, players, event_players,
     global_stats, event_stats) = aggregate_season(rows)
    raw = {name: {} for name in configs}
    details = {name: {} for name in configs}
    normalisers = {}

    for tid, matches in event_rows.items():
        surface = event_surfaces.get(tid, "Unknown")
        for name, config in configs.items():
            profiles, prior_players = build_event_profiles(
                tid, surface, players, event_players, global_stats, event_stats, config
            )
            ratios, clusters = score_event(
                tid, matches, surface, profiles, global_stats, event_stats, prior_players
            )
            if len(ratios) < MIN_SIDES:
                continue
            raw[name][tid] = statistics.fmean(ratios)
            if tid in detail_ids:
                details[name][tid] = {
                    "surface": surface,
                    "matches": len(matches),
                    "sides": len(ratios),
                    "raw": statistics.fmean(ratios),
                    "clusters": clusters,
                }

    for name, ratings in raw.items():
        normalisers[name] = statistics.fmean(ratings.values()) if ratings else None
    return raw, normalisers, details, len(usable), len(event_rows), event_surfaces


def mean_ci(values):
    values = sorted(value for value in values if math.isfinite(value))
    if not values:
        return (float("nan"), float("nan"))
    return (
        reference.percentile(values, 0.025),
        reference.percentile(values, 0.975),
    )


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

    print("season  usable matches  events  TA references  common LOEO events")
    for year in sorted(published):
        rows = compute_speed.load(year)
        raw, normalisers, details, usable_count, event_count, event_surfaces = score_season(
            rows, configs, detail_ids
        )
        raw_by_season[year] = raw
        detail_by_season[year] = (details, normalisers)
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

    # Compare weights on an earlier training window and a later held-out window.
    training_keys = [key for key in shared if key[0] <= 2020]
    holdout_keys = [key for key in shared if 2021 <= key[0] <= 2025]
    print("\nWEIGHT-SENSITIVITY: train 1991-2020; held-out 2021-2025")
    print("variant                         train MAE   holdout MAE   heldout rho")
    weight_names = [name for name in configs if name.startswith("weight_clay_")]
    for name in weight_names:
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

    print("\nEVENT-LEVEL MATCH-CLUSTER BOOTSTRAP INTERVALS (2026; conditional on profiles)")
    for variant, events in detail_by_season.get(max(detail_by_season), ({}, {}))[0].items():
        for tid, item in events.items():
            if tid not in detail_ids:
                continue
            normaliser = detail_by_season[max(detail_by_season)][1].get(variant)
            interval = cluster_bootstrap_interval(
                item["clusters"], normaliser, max(500, boot // 2),
                1000 + sum(ord(c) for c in tid) + sum(ord(c) for c in variant),
            )
            if interval:
                print(f"  {variant} {tid}: LOEO={item['raw'] / normaliser:.3f}, "
                      f"95% CI [{interval['low']:.3f},{interval['high']:.3f}], "
                      f"{item['matches']} matches/{item['sides']} sides")

    print("\nPHYSICAL CPI CROSS-CHECK")
    print(json.dumps(physical_cpi_diagnostics(DATASET), indent=2, sort_keys=True))
    print("\nInterpretation: TA agreement is a behavioural reference comparison, not physical "
          "ground truth. LOEO profiles and tour baselines exclude every match from the event "
          "being rated. Event intervals resample whole matches but condition on fitted player "
          "profiles and the season normaliser; they do not include profile-estimation uncertainty.")


def details_for_event(details, tid):
    for variant in details.values():
        if tid in variant:
            yield variant[tid]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seasons", type=int, nargs="*", default=None)
    parser.add_argument("--boot", type=int, default=2000)
    parser.add_argument("--detail", default="marrakech,lavercup",
                        help="comma-separated event ids for conditional match-cluster intervals")
    args = parser.parse_args()
    if args.boot < 100:
        parser.error("--boot must be at least 100 for useful uncertainty estimates")
    seasons = args.seasons or list(range(1991, compute_speed.date.today().year + 1))
    detail_ids = {item.strip() for item in args.detail.split(",") if item.strip()}
    run_audit(seasons, args.boot, detail_ids)


if __name__ == "__main__":
    main()
