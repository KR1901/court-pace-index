#!/usr/bin/env python3
"""Forward-test surface-specific Challenger / qualifying weights on available 2026 ATP data.

Historical player profiles and population priors are built only from seasons before 2026.
2026 ATP main-draw matches are the untouched forward target. 2026 Challenger and ATP
qualifying rows are ingested and counted for provenance / next-season coverage, but are
never used to predict 2026 ATP matches (same-season look-ahead is prohibited).

The experiment is research-only. TML source files are downloaded into a temporary folder
and deleted when the script exits. Only aggregate metrics, counts and source hashes are
written to the report directory.
"""
import argparse
import csv
import hashlib
import json
import math
import random
import shutil
import tempfile
import urllib.request
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

import compute_speed
import audit_lower_tier_profile_backfill as backfill


TML_API_URL = "https://stats.tennismylife.org/api/data-files"
TML_REQUIRED_FILES = (
    "2026.csv",
    "ongoing_tourneys.csv",
    "2026_challenger.csv",
    "challenger_ongoing_tourneys.csv",
    "atp_quali/2026_atp_quali.csv",
    "ATP_Database.csv",
)
POST_MAY_17 = "20260518"
TARGET_SEASON = 2026
DEVELOPMENT_YEARS = tuple(range(2012, 2019))
SURFACE_MIN_SIDES = 300
SURFACE_MIN_EVENTS = 5
DEFAULT_BOOTSTRAP = 2000


def parse_csv_payload(payload, expected_header):
    if not payload.startswith(expected_header.encode("utf-8")):
        raise ValueError(f"Unexpected CSV schema; expected header {expected_header!r}")
    return list(csv.DictReader(payload.decode("utf-8-sig").splitlines()))


def fetch_tml_manifest():
    request = urllib.request.Request(
        TML_API_URL,
        headers={"User-Agent": "CourtPaceIndex-2026-ForwardAudit/1.0"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        manifest = json.loads(response.read().decode("utf-8"))
    files = manifest.get("files")
    if not isinstance(files, list):
        raise ValueError("TennisMyLife data-files API returned no files list")
    return {
        item.get("name"): item
        for item in files
        if isinstance(item, dict) and item.get("name")
    }


def fetch_tml_file(name, file_index, temporary_dir, expected_header):
    metadata = file_index.get(name)
    if metadata is None:
        raise FileNotFoundError(f"TennisMyLife data-files API does not list {name!r}")
    url = metadata.get("url")
    if not url:
        raise ValueError(f"TennisMyLife file metadata has no URL for {name!r}")
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "CourtPaceIndex-2026-ForwardAudit/1.0",
            "Accept": "text/csv",
        },
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        payload = response.read()
    rows = parse_csv_payload(payload, expected_header)
    safe_name = name.replace("/", "__")
    local_path = Path(temporary_dir) / safe_name
    local_path.write_bytes(payload)
    return rows, {
        "name": name,
        "url": url,
        "api_modified_utc": metadata.get("mtime"),
        "api_size_bytes": metadata.get("size"),
        "downloaded_bytes": len(payload),
        "downloaded_sha256": hashlib.sha256(payload).hexdigest(),
        "downloaded_at_utc": datetime.now(timezone.utc).isoformat(),
        "raw_rows": len(rows),
    }


def match_key(row):
    event_id = str(row.get("tourney_id") or "")
    date_key = str(row.get("tourney_date") or "")
    match_num = str(row.get("match_num") or "")
    if event_id and match_num:
        return (event_id, date_key, match_num)
    return (
        event_id,
        date_key,
        str(row.get("round") or ""),
        str(row.get("winner_id") or ""),
        str(row.get("loser_id") or ""),
        str(row.get("score") or ""),
    )


def deduplicate_rows(*groups):
    merged = []
    seen = set()
    duplicates = 0
    for group in groups:
        for row in group:
            key = match_key(row)
            if key in seen:
                duplicates += 1
                continue
            seen.add(key)
            merged.append(row)
    return merged, duplicates


def date_key(row):
    value = str(row.get("tourney_date") or "")
    return value if value.isdigit() else ""


def source_row_summary(rows, valid_rows, manifest, classification=None):
    dates = sorted(date_key(row) for row in rows if date_key(row))
    valid_dates = sorted(date_key(row) for row in valid_rows if date_key(row))
    post_raw = [row for row in rows if date_key(row) >= POST_MAY_17]
    post_valid = [row for row in valid_rows if date_key(row) >= POST_MAY_17]
    output = {
        **manifest,
        "valid_rows": len(valid_rows),
        "post_2026_05_17_raw_rows": len(post_raw),
        "post_2026_05_17_valid_rows": len(post_valid),
        "first_tournament_date_key": dates[0] if dates else None,
        "last_tournament_date_key": dates[-1] if dates else None,
        "first_valid_tournament_date_key": valid_dates[0] if valid_dates else None,
        "last_valid_tournament_date_key": valid_dates[-1] if valid_dates else None,
    }
    if classification:
        output["classification"] = classification
    return output


def load_current_tml_sources(temporary_dir):
    file_index = fetch_tml_manifest()
    main_rows, main_meta = fetch_tml_file(
        "2026.csv", file_index, temporary_dir, "tourney_id,"
    )
    ongoing_rows, ongoing_meta = fetch_tml_file(
        "ongoing_tourneys.csv", file_index, temporary_dir, "tourney_id,"
    )
    challenger_rows, challenger_meta = fetch_tml_file(
        "2026_challenger.csv", file_index, temporary_dir, "tourney_id,"
    )
    challenger_ongoing_rows, challenger_ongoing_meta = fetch_tml_file(
        "challenger_ongoing_tourneys.csv", file_index, temporary_dir, "tourney_id,"
    )
    qualifying_rows, qualifying_meta = fetch_tml_file(
        "atp_quali/2026_atp_quali.csv", file_index, temporary_dir, "tourney_id,"
    )
    database_rows, database_meta = fetch_tml_file(
        "ATP_Database.csv", file_index, temporary_dir, "id,player,atpname,"
    )

    main_all, main_duplicates = deduplicate_rows(main_rows, ongoing_rows)
    challenger_all, challenger_duplicates = deduplicate_rows(
        challenger_rows, challenger_ongoing_rows
    )

    valid_main = compute_speed.valid_rows(main_all)
    valid_challenger = [
        row for row in backfill.validate_lower_rows(challenger_all)
        if row.get("_lower_tier") == "challenger"
    ]
    valid_qualifying = [
        row for row in backfill.validate_lower_rows(qualifying_rows)
        if row.get("_lower_tier") == "qualifying"
    ]

    sources = {
        "atp_main_draw_2026": {
            **source_row_summary(main_rows, compute_speed.valid_rows(main_rows), main_meta),
            "ongoing_feed": source_row_summary(
                ongoing_rows, compute_speed.valid_rows(ongoing_rows), ongoing_meta
            ),
            "deduplicated_combined_valid_rows": len(valid_main),
            "duplicates_removed_between_static_and_ongoing": main_duplicates,
        },
        "challenger_2026": {
            **source_row_summary(
                challenger_rows,
                [
                    row for row in backfill.validate_lower_rows(challenger_rows)
                    if row.get("_lower_tier") == "challenger"
                ],
                challenger_meta,
            ),
            "ongoing_feed": source_row_summary(
                challenger_ongoing_rows,
                [
                    row for row in backfill.validate_lower_rows(challenger_ongoing_rows)
                    if row.get("_lower_tier") == "challenger"
                ],
                challenger_ongoing_meta,
            ),
            "deduplicated_combined_valid_rows": len(valid_challenger),
            "duplicates_removed_between_static_and_ongoing": challenger_duplicates,
        },
        "atp_qualifying_2026": source_row_summary(
            qualifying_rows, valid_qualifying, qualifying_meta,
            classification="ATP tour-level Q1/Q2/Q3 only; non-qualifying round labels excluded",
        ),
        "atp_player_database": {
            **database_meta,
            "rows": len(database_rows),
            "purpose": "ATP-ID-to-name aliases for conservative historical identity crosswalk",
        },
    }
    return {
        "main_rows": valid_main,
        "challenger_rows": valid_challenger,
        "qualifying_rows": valid_qualifying,
        "database_rows": database_rows,
        "source_coverage": sources,
    }


def add_historical_names(name_to_ids, rows):
    for row in rows:
        for id_key, name_key in (
            ("winner_id", "winner_name"),
            ("loser_id", "loser_name"),
        ):
            player_id = str(row.get(id_key) or "")
            name_key_value = compute_speed._normalise_profile_name(
                row.get(name_key) or ""
            )
            if player_id and name_key_value:
                name_to_ids[name_key_value].add(player_id)


def build_tml_identity_crosswalk(target_rows, database_rows, historical_name_to_ids):
    """Map TML ATP IDs to one unique Sackmann ID using exact normalised full names only."""
    aliases_by_tml_id = defaultdict(set)
    target_tml_ids = set()
    for row in target_rows:
        for id_key, name_key in (
            ("winner_id", "winner_name"),
            ("loser_id", "loser_name"),
        ):
            player_id = str(row.get(id_key) or "")
            normalized = compute_speed._normalise_profile_name(row.get(name_key) or "")
            if player_id:
                target_tml_ids.add(player_id)
            if player_id and normalized:
                aliases_by_tml_id[player_id].add(normalized)

    # Use ATP_Database as an alias bridge only for IDs present in the target population.
    for row in database_rows:
        player_id = str(row.get("id") or "")
        if not player_id or player_id not in target_tml_ids:
            continue
        for name_key in ("player", "atpname"):
            normalized = compute_speed._normalise_profile_name(row.get(name_key) or "")
            if normalized:
                aliases_by_tml_id[player_id].add(normalized)

    mapping = {}
    ambiguous = set()
    unmatched = set()
    for tml_id, aliases in aliases_by_tml_id.items():
        archive_ids = set()
        has_ambiguous_alias = False
        for alias in aliases:
            candidates = historical_name_to_ids.get(alias, set())
            if len(candidates) > 1:
                has_ambiguous_alias = True
            elif len(candidates) == 1:
                archive_ids.update(candidates)
        if has_ambiguous_alias or len(archive_ids) > 1:
            ambiguous.add(tml_id)
        elif len(archive_ids) == 1:
            mapping[tml_id] = next(iter(archive_ids))
        else:
            unmatched.add(tml_id)
    return mapping, {
        "target_tml_player_ids": len(aliases_by_tml_id),
        "uniquely_crosswalked_to_archive_id": len(mapping),
        "ambiguous_name_crosswalks": len(ambiguous),
        "without_historical_name_match": len(unmatched),
        "crosswalk_rule": "exact normalized full name, unique archive ID; ambiguous aliases excluded",
    }


def load_historical_training(archive_cache_dir):
    main_by_year = {}
    lower_by_year = {}
    historical_name_to_ids = defaultdict(set)
    main_manifests = []
    lower_manifests = []

    for year in range(backfill.PROFILE_START_YEAR, TARGET_SEASON):
        rows, manifest, raw_count = backfill.load_main_year(year, archive_cache_dir)
        main_by_year[year] = backfill.aggregate_rows(rows)
        add_historical_names(historical_name_to_ids, rows)
        main_manifests.append({
            "year": year,
            **manifest,
            "raw_rows": raw_count,
            "valid_main_draw_matches": len(rows),
        })

    for year in range(backfill.CHALLENGER_START_YEAR, TARGET_SEASON):
        raw, manifest = backfill.fetch_csv(
            archive_cache_dir,
            backfill.LOWER_TIER_TEMPLATE.format(year=year),
        )
        selected = backfill.validate_lower_rows(raw)
        challenger = [
            row for row in selected if row.get("_lower_tier") == "challenger"
        ]
        qualifying = [
            row for row in selected if row.get("_lower_tier") == "qualifying"
        ] if year >= backfill.QUALIFYING_START_YEAR else []
        lower_by_year[year] = {
            "challenger": backfill.aggregate_rows(challenger)[0],
            "qualifying": backfill.aggregate_rows(qualifying)[0],
        }
        add_historical_names(historical_name_to_ids, selected)
        lower_manifests.append({
            "year": year,
            **manifest,
            "raw_rows": len(raw),
            "selected_challenger_matches": len(challenger),
            "selected_qualifying_matches": len(qualifying),
        })
    return main_by_year, lower_by_year, historical_name_to_ids, {
        "main_draw_years": [backfill.PROFILE_START_YEAR, TARGET_SEASON - 1],
        "lower_tier_years": [backfill.CHALLENGER_START_YEAR, TARGET_SEASON - 1],
        "main_draw_file_count": len(main_manifests),
        "lower_tier_file_count": len(lower_manifests),
        "main_draw_manifest_sha256": {
            str(item["year"]): item["sha256"] for item in main_manifests
        },
        "lower_tier_manifest_sha256": {
            str(item["year"]): item["sha256"] for item in lower_manifests
        },
    }


def surface_label(row):
    value = (row.get("surface") or "").strip().casefold()
    return value if value in {"clay", "hard", "grass", "carpet"} else "unknown"


def remap_tml_rows_to_archive_ids(rows, tml_to_archive_id):
    """Use a unique historical archive ID where available; retain unmatched TML IDs."""
    remapped = []
    for row in rows:
        item = dict(row)
        for key in ("winner_id", "loser_id"):
            player_id = str(item.get(key) or "")
            if player_id:
                item[key] = tml_to_archive_id.get(player_id, player_id)
        remapped.append(item)
    return remapped


def aggregate_2026_event_groups(rows):
    """Aggregate match statistics by 2026 event-week, ready for chronological use."""
    grouped = defaultdict(list)
    for row in rows:
        grouped[(
            date_key(row),
            str(row.get("tourney_id") or row.get("tourney_name") or "unknown-event"),
        )].append(row)
    result = []
    for (event_date, event_id), event_rows in sorted(grouped.items()):
        players, global_stats = backfill.aggregate_rows(event_rows)
        result.append({
            "date": event_date,
            "event_id": event_id,
            "players": players,
            "global_stats": global_stats,
            "matches": len(event_rows),
        })
    return result


def event_recency_weight(source_date, target_date):
    """Fractional-year form of the historical 0.90-per-year decay."""
    if not source_date or not target_date or source_date >= target_date:
        return 0.0
    source = datetime.strptime(source_date, "%Y%m%d").date()
    target = datetime.strptime(target_date, "%Y%m%d").date()
    years = max(0.0, (target - source).days / 365.25)
    return backfill.RECENCY_DECAY ** years


def merge_player_history(target_players, source_players, weight):
    if weight <= 0.0:
        return
    for player_id, source in source_players.items():
        target = target_players.setdefault(player_id, backfill.empty_player())
        backfill.merge_stats(target, source, weight)


def evaluate_tml_target_rows(target_rows, tml_to_archive_id,
                             main_by_year, lower_by_year,
                             current_main_rows, current_challenger_rows,
                             current_qualifying_rows,
                             challenger_weight, qualifying_weight):
    """Score each 2026 ATP event using only historical and strictly prior 2026 events.

    The tournament-week date key is used as a conservative cutoff: a same-week or later
    ATP, Challenger or qualifying event is excluded. Prior 2026 ATP main-draw matches may
    update both player profiles and the ATP-only population priors. Prior 2026 lower-tier
    matches update individual player profiles only.
    """
    historical_players, historical_global, _historical_counts = (
        backfill.build_training_stats(
            TARGET_SEASON,
            main_by_year,
            lower_by_year,
            challenger_weight,
            qualifying_weight,
        )
    )
    remapped_main = aggregate_2026_event_groups(
        remap_tml_rows_to_archive_ids(current_main_rows, tml_to_archive_id)
    )
    remapped_challenger = aggregate_2026_event_groups(
        remap_tml_rows_to_archive_ids(current_challenger_rows, tml_to_archive_id)
    )
    remapped_qualifying = aggregate_2026_event_groups(
        remap_tml_rows_to_archive_ids(current_qualifying_rows, tml_to_archive_id)
    )

    target_groups = defaultdict(list)
    for row in target_rows:
        target_groups[(
            date_key(row),
            str(row.get("tourney_id") or row.get("tourney_name") or "unknown-event"),
        )].append(row)

    # Maintain one rolling profile state per weight arm. Sorted target events only admit
    # same-season source events once their week key is strictly earlier than the target.
    training_players = {
        player_id: json.loads(json.dumps(stats))
        for player_id, stats in historical_players.items()
    }
    training_global = json.loads(json.dumps(historical_global))
    main_index = 0
    challenger_index = 0
    qualifying_index = 0

    by_event = {}
    post_by_event = {}
    for (target_date, event_key), event_rows in sorted(target_groups.items()):
        while (
            main_index < len(remapped_main)
            and remapped_main[main_index]["date"]
            and target_date
            and remapped_main[main_index]["date"] < target_date
        ):
            source_event = remapped_main[main_index]
            decay = event_recency_weight(source_event["date"], target_date)
            backfill.merge_stats(
                training_global, source_event["global_stats"], decay
            )
            merge_player_history(
                training_players, source_event["players"], decay
            )
            main_index += 1

        if challenger_weight > 0.0:
            while (
                challenger_index < len(remapped_challenger)
                and remapped_challenger[challenger_index]["date"]
                and target_date
                and remapped_challenger[challenger_index]["date"] < target_date
            ):
                source_event = remapped_challenger[challenger_index]
                decay = (
                    event_recency_weight(source_event["date"], target_date)
                    * challenger_weight
                )
                # Lower-tier observations never change ATP population priors.
                merge_player_history(
                    training_players, source_event["players"], decay
                )
                challenger_index += 1

        if qualifying_weight > 0.0:
            while (
                qualifying_index < len(remapped_qualifying)
                and remapped_qualifying[qualifying_index]["date"]
                and target_date
                and remapped_qualifying[qualifying_index]["date"] < target_date
            ):
                source_event = remapped_qualifying[qualifying_index]
                decay = (
                    event_recency_weight(source_event["date"], target_date)
                    * qualifying_weight
                )
                merge_player_history(
                    training_players, source_event["players"], decay
                )
                qualifying_index += 1

        target_player_ids = {
            tml_to_archive_id.get(str(player_id), str(player_id))
            for row in event_rows
            for player_id in (row.get("winner_id"), row.get("loser_id"))
            if player_id
        }
        profiles, _priors, tour_rate = backfill.build_profiles(
            target_player_ids, training_players, training_global
        )
        if tour_rate is None:
            raise RuntimeError(
                f"No ATP-only population prior for 2026 event {event_key}; refusing fallback."
            )

        event_surfaces = {surface_label(row) for row in event_rows}
        event_surface = (
            next(iter(event_surfaces)) if len(event_surfaces) == 1 else "mixed"
        )
        full_cluster = backfill.empty_cluster(event_surface)
        post_cluster = backfill.empty_cluster(event_surface)
        has_post_cutoff_rows = False

        for row in event_rows:
            after_cutoff = date_key(row) >= POST_MAY_17
            has_post_cutoff_rows = has_post_cutoff_rows or after_cutoff
            for server_key, returner_key, points_key, aces_key in (
                ("winner_id", "loser_id", "w_svpt", "w_ace"),
                ("loser_id", "winner_id", "l_svpt", "l_ace"),
            ):
                server_tml_id = str(row.get(server_key) or "")
                returner_tml_id = str(row.get(returner_key) or "")
                server_profile_id = tml_to_archive_id.get(
                    server_tml_id, server_tml_id
                )
                returner_profile_id = tml_to_archive_id.get(
                    returner_tml_id, returner_tml_id
                )
                server_profile = profiles.get(server_profile_id, {})
                returner_profile = profiles.get(returner_profile_id, {})
                points = compute_speed.num(row.get(points_key))
                aces = compute_speed.num(row.get(aces_key))
                if points is None or aces is None or points <= 0:
                    continue

                personal_server = server_profile.get("serve_rate")
                personal_returner = returner_profile.get("concede_rate")
                has_server = personal_server is not None
                has_returner = personal_returner is not None
                serve_rate = personal_server if has_server else tour_rate
                concede_rate = personal_returner if has_returner else tour_rate
                predicted_rate = backfill.clip_probability(
                    compute_speed.expected_calibrated_aces(
                        serve_rate, concede_rate, tour_rate, 1.0
                    )
                )
                actual_rate = aces / points
                log_loss = -(
                    aces * math.log(predicted_rate)
                    + (points - aces) * math.log(1.0 - predicted_rate)
                )

                for cluster in (full_cluster, post_cluster if after_cutoff else None):
                    if cluster is None:
                        continue
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

        if full_cluster["sides"]:
            by_event[event_key] = full_cluster
        if has_post_cutoff_rows and post_cluster["sides"]:
            post_by_event[event_key] = post_cluster

    return by_event, post_by_event


def select_surface_policy(development_results, years, arms):
    surfaces = sorted({
        cluster.get("surface", "unknown")
        for arm in arms
        for year in years
        for cluster in development_results.get(arm, {}).get(year, {}).values()
    })
    policy = {}
    diagnostics = {}
    for surface in surfaces:
        filtered = {
            arm: {
                year: {
                    event: cluster
                    for event, cluster in development_results.get(arm, {}).get(year, {}).items()
                    if cluster.get("surface") == surface
                }
                for year in years
            }
            for arm in arms
        }
        base_clusters = [
            cluster
            for year in years
            for cluster in filtered["atp_only"].get(year, {}).values()
        ]
        sides = sum(item["sides"] for item in base_clusters)
        event_count = len(base_clusters)
        if sides < SURFACE_MIN_SIDES or event_count < SURFACE_MIN_EVENTS:
            selected = "atp_only"
            ranking = []
            reason = "insufficient development observations; defaulted to ATP-only"
        else:
            selected, ranking = backfill.select_arm(filtered, years, arms)
            reason = "selected on development seasons only by log loss, MAE tie-break"
        policy[surface] = selected
        diagnostics[surface] = {
            "selected_arm": selected,
            "development_server_sides": sides,
            "development_event_seasons": event_count,
            "selection_reason": reason,
            "ranking": ranking,
        }
    return policy, diagnostics


def apply_surface_policy(arm_results, selected_by_surface):
    selected = {}
    for event, baseline_cluster in arm_results["atp_only"].items():
        surface = baseline_cluster.get("surface", "unknown")
        arm = selected_by_surface.get(surface, "atp_only")
        selected[event] = arm_results[arm][event]
    return selected


def bootstrap_paired_by_surface(baseline, candidate, iterations, seed):
    rng = random.Random(seed)
    by_surface = defaultdict(list)
    for event, cluster in baseline.items():
        by_surface[cluster.get("surface", "unknown")].append(event)
    deltas = {
        "binomial_log_loss_per_point": [],
        "point_weighted_mae": [],
    }
    successful = 0
    for _ in range(iterations):
        base_sample = []
        candidate_sample = []
        for surface, event_keys in sorted(by_surface.items()):
            chosen = [
                event_keys[rng.randrange(len(event_keys))]
                for _ in range(len(event_keys))
            ]
            for event in chosen:
                if event not in candidate:
                    continue
                base_sample.append(baseline[event])
                candidate_sample.append(candidate[event])
        base_metrics = backfill.metrics_from_clusters(base_sample)
        candidate_metrics = backfill.metrics_from_clusters(candidate_sample)
        if (
            base_metrics["binomial_log_loss_per_point"] is None
            or candidate_metrics["binomial_log_loss_per_point"] is None
        ):
            continue
        successful += 1
        for metric in deltas:
            deltas[metric].append(
                candidate_metrics[metric] - base_metrics[metric]
            )
    output = {}
    for metric, values in deltas.items():
        ordered = sorted(values)
        output[metric] = {
            "low": ordered[int(0.025 * (len(ordered) - 1))] if ordered else None,
            "high": ordered[int(0.975 * (len(ordered) - 1))] if ordered else None,
            "successful_resamples": successful,
        }
    return output


def metrics_by_surface(baseline, candidate):
    surfaces = sorted({
        cluster.get("surface", "unknown")
        for cluster in baseline.values()
    })
    result = {}
    for surface in surfaces:
        base_clusters = [
            cluster for cluster in baseline.values()
            if cluster.get("surface") == surface
        ]
        candidate_clusters = [
            cluster for event, cluster in candidate.items()
            if event in baseline and baseline[event].get("surface") == surface
        ]
        result[surface] = {
            "atp_only": backfill.metrics_from_clusters(base_clusters),
            "surface_policy": backfill.metrics_from_clusters(candidate_clusters),
        }
    return result


def metrics_full_report(baseline, candidate, bootstrap_iterations, seed):
    return {
        "atp_only": backfill.metrics_from_clusters(list(baseline.values())),
        "surface_policy": backfill.metrics_from_clusters(list(candidate.values())),
        "by_surface": metrics_by_surface(baseline, candidate),
        "bootstrap_delta": bootstrap_paired_by_surface(
            baseline, candidate, bootstrap_iterations, seed
        ),
    }


def render_markdown(report):
    full = report["validation"]["full_available_2026"]
    late = report["validation"]["post_2026_05_17"]
    lines = [
        "# 2026 surface-specific lower-tier backfill forward test",
        "",
        f"Generated UTC: {report['generated_utc']}",
        "",
        "## Data extension",
        "",
        "The incomplete Sackmann 2026 match file is not used as the 2026 target. The full",
        "TennisMyLife 2026 ATP season CSV is used instead, supplemented by completed rows",
        "from the ongoing ATP feed. All 2026 Challenger and qualifying files are also fetched.",
        "For each ATP target event, current-season ATP, Challenger and qualifying matches are",
        "admitted only if their event-week date key is strictly earlier than the target event.",
        "Same-week and later events are excluded to prevent look-ahead.",
        "",
        "| Population | Valid match rows in available-to-date 2026 snapshot | After 17 May |",
        "|---|---:|---:|",
        f"| ATP main draw + ongoing ATP feed | {report['source_coverage']['atp_main_draw_2026']['deduplicated_combined_valid_rows']:,} | {report['source_coverage']['atp_main_draw_2026']['post_2026_05_17_valid_rows_in_combined']:,} |",
        f"| Challenger + ongoing Challenger feed | {report['source_coverage']['challenger_2026']['deduplicated_combined_valid_rows']:,} | {report['source_coverage']['challenger_2026']['post_2026_05_17_valid_rows_in_combined']:,} |",
        f"| ATP qualifying Q1/Q2/Q3 | {report['source_coverage']['atp_qualifying_2026']['valid_rows']:,} | {report['source_coverage']['atp_qualifying_2026']['post_2026_05_17_valid_rows']:,} |",
        "",
        "## Surface-specific weight selection",
        "",
        "Weights were selected independently by surface using only 2012–2018 development data.",
        "Surfaces below the minimum development sample default to ATP-only. The 2026 data is",
        "a forward holdout and does not select or retune any weight.",
        "",
        "| Surface | Selected arm | Development sides | Development events |",
        "|---|---|---:|---:|",
    ]
    for surface, item in sorted(report["development"]["surface_policy"].items()):
        lines.append(
            f"| {surface} | {item['selected_arm']} | "
            f"{item['development_server_sides']:,} | {item['development_event_seasons']:,} |"
        )
    lines.extend([
        "",
        "## Forward test — all available 2026 ATP main-draw rows",
        "",
        "| Metric | ATP-only | Surface-specific policy |",
        "|---|---:|---:|",
        f"| Server sides | {full['atp_only']['server_sides']:,} | {full['surface_policy']['server_sides']:,} |",
        f"| Both player roles have personal histories | {full['atp_only']['both_personal_profile_pct']:.2f}% | {full['surface_policy']['both_personal_profile_pct']:.2f}% |",
        f"| Point-weighted ace-rate MAE | {full['atp_only']['point_weighted_mae']:.6f} | {full['surface_policy']['point_weighted_mae']:.6f} |",
        f"| Binomial log loss / service point | {full['atp_only']['binomial_log_loss_per_point']:.6f} | {full['surface_policy']['binomial_log_loss_per_point']:.6f} |",
        f"| Calibration bias | {full['atp_only']['calibration_bias']:+.6f} | {full['surface_policy']['calibration_bias']:+.6f} |",
        "",
        "### Forward test — matches from 18 May onward",
        "",
        "| Metric | ATP-only | Surface-specific policy |",
        "|---|---:|---:|",
        f"| Server sides | {late['atp_only']['server_sides']:,} | {late['surface_policy']['server_sides']:,} |",
        f"| Both player roles have personal histories | {late['atp_only']['both_personal_profile_pct']:.2f}% | {late['surface_policy']['both_personal_profile_pct']:.2f}% |",
        f"| Point-weighted ace-rate MAE | {late['atp_only']['point_weighted_mae']:.6f} | {late['surface_policy']['point_weighted_mae']:.6f} |",
        f"| Binomial log loss / service point | {late['atp_only']['binomial_log_loss_per_point']:.6f} | {late['surface_policy']['binomial_log_loss_per_point']:.6f} |",
        f"| Calibration bias | {late['atp_only']['calibration_bias']:+.6f} | {late['surface_policy']['calibration_bias']:+.6f} |",
        "",
        "## Surface diagnostics — all available 2026 rows",
        "",
        "| Surface | Sides | ATP-only log loss | Policy log loss | ATP-only MAE | Policy MAE |",
        "|---|---:|---:|---:|---:|---:|",
    ])
    for surface, item in sorted(full["by_surface"].items()):
        base = item["atp_only"]
        candidate = item["surface_policy"]
        lines.append(
            f"| {surface} | {base['server_sides']:,} | "
            f"{base['binomial_log_loss_per_point']:.6f} | "
            f"{candidate['binomial_log_loss_per_point']:.6f} | "
            f"{base['point_weighted_mae']:.6f} | "
            f"{candidate['point_weighted_mae']:.6f} |"
        )
    lines.extend([
        "",
        "## Paired event-cluster bootstrap",
        "",
    ])
    for label, result in (
        ("All available 2026", full),
        ("After 17 May", late),
    ):
        lines.append(f"### {label}")
        for metric, name in (
            ("binomial_log_loss_per_point", "Binomial log loss / point"),
            ("point_weighted_mae", "Point-weighted MAE"),
        ):
            interval = result["bootstrap_delta"][metric]
            if interval["low"] is None:
                lines.append(f"- {name}: interval unavailable.")
            else:
                lines.append(
                    f"- {name}: policy minus ATP-only 95% interval "
                    f"[{interval['low']:.6f}, {interval['high']:.6f}] "
                    f"({interval['successful_resamples']:,} resamples)."
                )
    lines.extend([
        "",
        "Negative deltas favour the surface policy. Intervals spanning zero are inconclusive.",
        "",
        "## Identity and data safeguards",
        "",
        f"- TML player IDs uniquely crosswalked to the historical archive: {report['identity_crosswalk']['uniquely_crosswalked_to_archive_id']:,} of {report['identity_crosswalk']['target_tml_player_ids']:,}.",
        f"- Ambiguous full-name crosswalks excluded: {report['identity_crosswalk']['ambiguous_name_crosswalks']:,}.",
        f"- TML IDs with no historical name match: {report['identity_crosswalk']['without_historical_name_match']:,}.",
        "- Crosswalk rule: exact Unicode-normalised full name; all aliases must resolve to one historical archive ID. No fuzzy matching.",
        "- The ATP population prior is built from ATP main-draw rows only. Challenger / qualifying affect only individual player-role estimates.",
        "- Current-season 2026 rows are used only when their event-week date key is strictly earlier than the ATP target event. Same-week and future rows are excluded.",
        "- The ATP population prior uses historical and earlier 2026 ATP main-draw rows only. Challenger / qualifying observations affect individual player-role profiles only.",
        "- All raw TML files are downloaded to an ephemeral temporary directory and deleted on exit. They are not committed, cached or uploaded.",
        "- Output artifacts contain aggregate metrics, source-file hashes and source metadata only; no match rows, player names or player IDs.",
        "- TennisMyLife public terms allow personal, educational and non-commercial research use only; commercial use requires prior express written permission. Do not deploy these derived estimates in a commercial service without authorization.",
        "- Historical Jeff Sackmann archive data is licensed CC BY-NC-SA 4.0 and retains its attribution, non-commercial and share-alike obligations.",
        "- Production formulas, profile assets, ratings, rankings and Android behaviour are unchanged.",
        "",
    ])
    return "\n".join(lines)


def run_forward_test(archive_cache_dir, output_dir, bootstrap_iterations):
    with tempfile.TemporaryDirectory(prefix="tml-2026-forward-") as tml_temp:
        tml = load_current_tml_sources(tml_temp)
        main_by_year, lower_by_year, historical_name_to_ids, archive_manifest = (
            load_historical_training(archive_cache_dir)
        )
        crosswalk, crosswalk_summary = build_tml_identity_crosswalk(
            tml["main_rows"], tml["database_rows"], historical_name_to_ids
        )

        arms = backfill.arm_grid()
        development_results = {arm: {} for arm in arms}
        for year in DEVELOPMENT_YEARS:
            target_rows, _manifest, _raw_count = backfill.load_main_year(
                year, archive_cache_dir
            )
            for arm, (challenger_weight, qualifying_weight) in arms.items():
                event_clusters, _ = backfill.evaluate_target_season(
                    year,
                    target_rows,
                    main_by_year,
                    lower_by_year,
                    challenger_weight,
                    qualifying_weight,
                )
                development_results[arm][year] = event_clusters

        selected_by_surface, selection_diagnostics = select_surface_policy(
            development_results, DEVELOPMENT_YEARS, arms
        )

        evaluated_full = {}
        evaluated_late = {}
        for arm, (challenger_weight, qualifying_weight) in arms.items():
            full, late = evaluate_tml_target_rows(
                tml["main_rows"],
                crosswalk,
                main_by_year,
                lower_by_year,
                tml["main_rows"],
                tml["challenger_rows"],
                tml["qualifying_rows"],
                challenger_weight,
                qualifying_weight,
            )
            evaluated_full[arm] = full
            evaluated_late[arm] = late

        baseline_full = evaluated_full["atp_only"]
        baseline_late = evaluated_late["atp_only"]
        policy_full = apply_surface_policy(evaluated_full, selected_by_surface)
        policy_late = apply_surface_policy(evaluated_late, selected_by_surface)

        full_report = metrics_full_report(
            baseline_full, policy_full, bootstrap_iterations, 20261108
        )
        late_report = metrics_full_report(
            baseline_late, policy_late, bootstrap_iterations, 20260518
        )
        main_source = tml["source_coverage"]["atp_main_draw_2026"]
        challenger_source = tml["source_coverage"]["challenger_2026"]
        qualifying_source = tml["source_coverage"]["atp_qualifying_2026"]

        report = {
            "schema_version": 1,
            "generated_utc": datetime.now(timezone.utc).isoformat(),
            "target_season": TARGET_SEASON,
            "target_data_cutoff": {
                "static_atp_csv_latest_valid_tournament_date_key":
                    main_source["last_valid_tournament_date_key"],
                "ongoing_atp_feed_latest_valid_tournament_date_key":
                    main_source["ongoing_feed"]["last_valid_tournament_date_key"],
                "interpretation": (
                    "tourney_date is the event-week key, not necessarily the match-day; "
                    "rows count only when both players and valid serve statistics are present"
                ),
            },
            "archive": {
                "repository": backfill.ARCHIVE_REPOSITORY,
                "commit": backfill.ARCHIVE_COMMIT,
                "license": "CC BY-NC-SA 4.0",
                **archive_manifest,
            },
            "source_coverage": {
                **tml["source_coverage"],
                "atp_main_draw_2026": {
                    **main_source,
                    "deduplicated_combined_valid_rows":
                        len(tml["main_rows"]),
                    "post_2026_05_17_valid_rows_in_combined":
                        sum(date_key(row) >= POST_MAY_17 for row in tml["main_rows"]),
                },
                "challenger_2026": {
                    **challenger_source,
                    "deduplicated_combined_valid_rows":
                        len(tml["challenger_rows"]),
                    "post_2026_05_17_valid_rows_in_combined":
                        sum(date_key(row) >= POST_MAY_17 for row in tml["challenger_rows"]),
                },
                "atp_qualifying_2026": qualifying_source,
            },
            "identity_crosswalk": crosswalk_summary,
            "development": {
                "seasons": [DEVELOPMENT_YEARS[0], DEVELOPMENT_YEARS[-1]],
                "selection_metric": "binomial log loss / service point, point-weighted MAE tie-break",
                "minimum_server_sides_per_surface": SURFACE_MIN_SIDES,
                "minimum_event_seasons_per_surface": SURFACE_MIN_EVENTS,
                "surface_policy": selection_diagnostics,
            },
            "validation": {
                "season": TARGET_SEASON,
                "target_population": "TML ATP main-draw 2026 file + current ongoing ATP main-draw feed",
                "full_available_2026": full_report,
                "post_2026_05_17": late_report,
            },
            "controls": {
                "same_season_rows_are_strictly_prior_event_only": True,
                "lower_tier_2026_rows_used_for_2026_prediction": (
                    "only when event-week date is strictly earlier than the ATP target event"
                ),
                "atp_main_draw_only_population_prior": True,
                "surface_weights_selected_without_2026_holdout": True,
                "raw_tml_sources_ephemeral_only": True,
                "raw_match_rows_in_report": False,
                "player_names_or_ids_in_report": False,
                "production_changed": False,
            },
        }

    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    (output / "summary.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (output / "README.md").write_text(render_markdown(report), encoding="utf-8")
    print(json.dumps({
        "selected_surface_policy": {
            surface: item["selected_arm"]
            for surface, item in selection_diagnostics.items()
        },
        "full_2026_atp_sides": full_report["atp_only"]["server_sides"],
        "post_may_17_atp_sides": late_report["atp_only"]["server_sides"],
        "source_post_may_17": {
            "atp_main_draw": main_source["post_2026_05_17_valid_rows"],
            "challenger": challenger_source["post_2026_05_17_valid_rows"],
            "atp_qualifying": qualifying_source["post_2026_05_17_valid_rows"],
        },
        "full_2026_log_loss": {
            "atp_only": full_report["atp_only"]["binomial_log_loss_per_point"],
            "surface_policy": full_report["surface_policy"]["binomial_log_loss_per_point"],
        },
        "late_2026_log_loss": {
            "atp_only": late_report["atp_only"]["binomial_log_loss_per_point"],
            "surface_policy": late_report["surface_policy"]["binomial_log_loss_per_point"],
        },
        "raw_tml_sources_ephemeral_only": True,
        "player_names_or_ids_in_report": False,
    }, indent=2))
    return report


def main():
    parser = argparse.ArgumentParser(
        description="Forward-test surface-specific Challenger / qualifying profile weights on 2026 ATP results."
    )
    parser.add_argument("--archive-cache-dir", default=".cache/lower-tier-backfill")
    parser.add_argument("--output-dir", default="build/surface-backfill-2026")
    parser.add_argument("--bootstrap", type=int, default=DEFAULT_BOOTSTRAP)
    args = parser.parse_args()
    if args.bootstrap < 100:
        parser.error("--bootstrap must be at least 100")
    run_forward_test(
        args.archive_cache_dir,
        args.output_dir,
        args.bootstrap,
    )


if __name__ == "__main__":
    main()
