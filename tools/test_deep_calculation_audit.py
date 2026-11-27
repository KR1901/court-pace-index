import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import deep_calculation_audit as audit


def match(event, winner_aces, loser_aces, surface="Clay"):
    return {
        "tourney_name": event,
        "tourney_level": "250",
        "winner_id": "p1",
        "loser_id": "p2",
        "winner_name": "Player One",
        "loser_name": "Player Two",
        "surface": surface,
        "w_svpt": 100,
        "l_svpt": 100,
        "w_ace": winner_aces,
        "l_ace": loser_aces,
    }


class PhysicalCpiDiagnosticsTests(unittest.TestCase):
    def test_event_centered_clay_analysis_and_cluster_bootstrap_are_reproducible(self):
        readings = []
        tournaments = []
        clay_specs = (
            ("madrid", 1.0, 24.0),
            ("rome", 2.0, 31.0),
            ("montecarlo", 0.7, 27.0),
        )
        residual_speed = (-1.5, -0.5, 0.5, 1.5)
        residual_cpi = (1.5, 0.5, -0.5, -1.5)
        for event_id, speed_mean, cpi_mean in clay_specs:
            tournaments.append({"id": event_id, "surface": "Clay"})
            for index, (dx, dy) in enumerate(zip(residual_speed, residual_cpi)):
                season = 2018 + index
                readings.append({
                    "t": event_id, "s": season,
                    "cpi": cpi_mean + dy, "src": "courtspeed",
                })
                readings.append({
                    "t": event_id, "s": season,
                    "speed": speed_mean + dx, "src": "computed",
                })

        tournaments.append({"id": "hard_event", "surface": "Hard"})
        for index, (dx, dy) in enumerate(zip(residual_speed, tuple(-v for v in residual_speed))):
            season = 2018 + index
            readings.append({
                "t": "hard_event", "s": season,
                "cpi": 40.0 + dy, "src": "courtspeed",
            })
            readings.append({
                "t": "hard_event", "s": season,
                "speed": 1.2 + dx, "src": "computed",
            })

        with tempfile.TemporaryDirectory() as temporary:
            dataset_path = Path(temporary) / "pace_dataset.json"
            dataset_path.write_text(
                json.dumps({"tournaments": tournaments, "readings": readings}),
                encoding="utf-8",
            )
            first = audit.physical_cpi_diagnostics(
                dataset_path, bootstrap_iterations=200, bootstrap_seed=41
            )
            second = audit.physical_cpi_diagnostics(
                dataset_path, bootstrap_iterations=200, bootstrap_seed=41
            )

        self.assertEqual(first, second)
        self.assertEqual(
            12,
            first["within_event_centered"]["by_surface"]["clay"]["n"],
        )
        self.assertAlmostEqual(
            -1.0,
            first["within_event_centered"]["by_surface"]["clay"]["pearson"],
            places=12,
        )
        self.assertLess(
            first["within_event_centered"]["by_surface"]["clay"]["spearman"],
            -0.95,
        )
        self.assertEqual(4, first["by_event"]["madrid"]["n"])
        self.assertAlmostEqual(-1.0, first["by_event"]["madrid"]["pearson"])
        bootstrap = first["clay_event_cluster_bootstrap"]
        self.assertEqual(3, bootstrap["cluster_count"])
        self.assertEqual(200, bootstrap["iterations"])
        self.assertEqual(200, bootstrap["successful_resamples"])
        self.assertEqual("raw event-season association", bootstrap["estimand"])
        self.assertLessEqual(
            bootstrap["pearson_95_interval"][0],
            bootstrap["pearson_95_interval"][1],
        )
        centered_bootstrap = first["clay_within_event_centered_cluster_bootstrap"]
        self.assertEqual(3, centered_bootstrap["cluster_count"])
        self.assertEqual(200, centered_bootstrap["successful_resamples"])
        self.assertEqual("within-event-centred association", centered_bootstrap["estimand"])
        for bound in centered_bootstrap["pearson_95_interval"]:
            self.assertAlmostEqual(-1.0, bound, places=12)
        self.assertLess(centered_bootstrap["spearman_95_interval"][1], -0.95)
        self.assertIn("tournament ID", bootstrap["resampling_unit"])


class NextGenTargetOnlyIsolationTests(unittest.TestCase):
    def test_next_gen_rows_remain_targets_but_never_train_standard_profiles(self):
        rows = [
            match("Training Event", 5, 4, surface="Hard"),
            match("Training Event", 6, 5, surface="Hard"),
            match("Training Event", 4, 5, surface="Hard"),
            match("Next Gen Finals", 20, 15, surface="Hard"),
        ]
        (
            usable, event_rows, _surfaces, players, event_players,
            global_stats, event_stats,
        ) = audit.aggregate_season(rows)

        target = audit.event_id("Next Gen Finals")
        self.assertEqual(4, len(usable))
        self.assertEqual(1, len(event_rows[target]))
        self.assertNotIn(target, event_players)
        self.assertNotIn(target, event_stats)
        self.assertEqual(3, players["p1"]["serve"]["pooled"]["matches"])
        self.assertEqual(6, global_stats["serve"]["pooled"]["matches"])
        self.assertEqual(29.0, global_stats["serve"]["pooled"]["aces"])
        self.assertEqual(600.0, global_stats["serve"]["pooled"]["points"])

    def test_next_gen_is_scored_but_excluded_from_the_standard_normaliser(self):
        rows = (
            [match("Training Event A", 5, 4, surface="Hard") for _ in range(10)]
            + [match("Training Event B", 10, 6, surface="Hard") for _ in range(10)]
            + [match("Next Gen Finals", 20, 15, surface="Hard") for _ in range(10)]
        )
        config = {
            "clay_weight": 1 / 3,
            "prior_points": 150.0,
            "min_matches": 3,
            "pooled_return_fallback": True,
            "pooled_return_min_matches": 1,
        }
        raw, normalisers, details, *_ = audit.score_season(
            rows, {"standard": config}, {"nextgen"}
        )

        self.assertIn("nextgen", raw["standard"])
        self.assertIn("nextgen", details["standard"])
        standard_raw = [
            value for event_id, value in raw["standard"].items()
            if event_id != "nextgen"
        ]
        self.assertEqual(2, len(standard_raw))
        self.assertAlmostEqual(
            sum(standard_raw) / len(standard_raw),
            normalisers["standard"],
        )


class SufficientStatisticsTests(unittest.TestCase):
    def test_subtract_stats_removes_the_entire_event_sample(self):
        total = {"aces": 18.0, "points": 300.0, "matches": 3}
        excluded = {"aces": 10.0, "points": 100.0, "matches": 1}
        self.assertEqual(
            {"aces": 8.0, "points": 200.0, "matches": 2},
            audit.subtract_stats(total, excluded),
        )

    def test_posterior_rate_shrinks_low_sample_rate_toward_surface_prior(self):
        estimate = audit.posterior_rate(
            {"aces": 8.0, "points": 100.0, "matches": 2},
            {"aces": 10.0, "points": 100.0, "matches": 2},
            prior_points=300.0,
            min_matches=1,
        )
        self.assertAlmostEqual(0.095, estimate)

    def test_weighted_profile_applies_the_declared_clay_weight(self):
        player = {
            "serve": {
                "clay": {"aces": 10.0, "points": 100.0, "matches": 3},
                "hard_grass": {"aces": 20.0, "points": 100.0, "matches": 3},
                "pooled": {"aces": 30.0, "points": 200.0, "matches": 6},
            },
            "return": {
                "clay": {"aces": 10.0, "points": 100.0, "matches": 3},
                "hard_grass": {"aces": 20.0, "points": 100.0, "matches": 3},
                "pooled": {"aces": 30.0, "points": 200.0, "matches": 6},
            },
        }
        prior = {
            role: {
                bucket: {"aces": 8.0, "points": 100.0, "matches": 3}
                for bucket in ("clay", "hard_grass", "pooled")
            }
            for role in ("serve", "return")
        }
        result = audit.weighted_profile(
            player, "serve", prior, clay_weight=1 / 3,
            prior_points=0.0, min_matches=3, pooled_fallback=False,
        )
        self.assertAlmostEqual((1 / 3) * 0.10 + (2 / 3) * 0.20, result)

    def test_carpet_specific_profile_uses_carpet_sample_when_qualified(self):
        rows = (
            [match("Carpet target", 5, 3, surface="Carpet")]
            + [match("Carpet training", 20, 10, surface="Carpet") for _ in range(3)]
        )
        (
            _usable, _event_rows, event_surfaces, players, by_event_players,
            global_stats, event_stats,
        ) = audit.aggregate_season(rows)
        target = audit.event_id("Carpet target")
        config = {
            "clay_weight": 1 / 3,
            "prior_points": 0.0,
            "min_matches": 3,
            "pooled_return_fallback": True,
            "pooled_return_min_matches": 1,
            "carpet_specific": True,
        }
        profiles, _ = audit.build_event_profiles(
            target, event_surfaces[target], players, by_event_players,
            global_stats, event_stats, config,
        )
        self.assertAlmostEqual(0.20, profiles["p1"]["serve_rate"])

    def test_carpet_specific_profile_falls_back_to_neutral_when_under_sampled(self):
        rows = [match("Carpet target", 5, 3, surface="Carpet")] + [
            match("Hard training", 20, 10, surface="Hard") for _ in range(3)
        ]
        (
            _usable, _event_rows, event_surfaces, players, by_event_players,
            global_stats, event_stats,
        ) = audit.aggregate_season(rows)
        target = audit.event_id("Carpet target")
        config = {
            "clay_weight": 1 / 3,
            "prior_points": 0.0,
            "min_matches": 3,
            "pooled_return_fallback": True,
            "pooled_return_min_matches": 1,
            "carpet_specific": True,
        }
        profiles, _ = audit.build_event_profiles(
            target, event_surfaces[target], players, by_event_players,
            global_stats, event_stats, config,
        )
        self.assertAlmostEqual(0.20, profiles["p1"]["serve_rate"])

    def test_empirical_bayes_prior_is_estimated_from_between_player_variance(self):
        players = {}
        for index, rate in enumerate((0.05, 0.08, 0.10, 0.12, 0.15)):
            players[str(index)] = {
                role: {
                    bucket: {"aces": rate * 10000, "points": 10000.0, "matches": 12}
                    for bucket in ("clay", "hard_grass", "carpet", "pooled")
                }
                for role in ("serve", "return")
            }
        priors = {
            role: {
                bucket: {"aces": 1000.0, "points": 10000.0, "matches": 60}
                for bucket in ("clay", "hard_grass", "carpet", "pooled")
            }
            for role in ("serve", "return")
        }
        estimated, diagnostics = audit.estimate_empirical_bayes_prior_points(
            players, {}, priors, min_matches=3
        )
        for role in ("serve", "return"):
            self.assertGreaterEqual(estimated[role]["clay"], 25.0)
            self.assertLessEqual(estimated[role]["clay"], 1000.0)
            self.assertEqual(5, diagnostics[role]["clay"]["players"])
            self.assertGreater(diagnostics[role]["clay"]["betweenPlayerVariance"], 0.0)

    def test_empirical_bayes_estimation_excludes_target_event_observations(self):
        players = {}
        event_players = {}
        for player_id, total_aces, event_aces in (
            ("p1", 500.0, 300.0),
            ("p2", 500.0, 50.0),
            ("p3", 500.0, 0.0),
        ):
            players[player_id] = {
                role: {
                    bucket: {"aces": total_aces, "points": 5000.0, "matches": 5}
                    for bucket in ("clay", "hard_grass", "carpet", "pooled")
                }
                for role in ("serve", "return")
            }
            if event_aces or player_id == "p3":
                event_players[player_id] = {
                    role: {
                        bucket: {"aces": event_aces, "points": 1000.0 if player_id != "p3" else 0.0,
                                 "matches": 1 if player_id != "p3" else 0}
                        for bucket in ("clay", "hard_grass", "carpet", "pooled")
                    }
                    for role in ("serve", "return")
                }

        total = {
            role: {
                bucket: {
                    "aces": sum(p[role][bucket]["aces"] for p in players.values()),
                    "points": sum(p[role][bucket]["points"] for p in players.values()),
                    "matches": sum(p[role][bucket]["matches"] for p in players.values()),
                }
                for bucket in ("clay", "hard_grass", "carpet", "pooled")
            }
            for role in ("serve", "return")
        }
        event_total = {
            role: {
                bucket: {
                    "aces": sum(p[role][bucket]["aces"] for p in event_players.values()),
                    "points": sum(p[role][bucket]["points"] for p in event_players.values()),
                    "matches": sum(p[role][bucket]["matches"] for p in event_players.values()),
                }
                for bucket in ("clay", "hard_grass", "carpet", "pooled")
            }
            for role in ("serve", "return")
        }
        excluded_prior = {
            role: {
                bucket: audit.subtract_stats(total[role][bucket], event_total[role][bucket])
                for bucket in ("clay", "hard_grass", "carpet", "pooled")
            }
            for role in ("serve", "return")
        }
        full_prior = total

        excluded, _ = audit.estimate_empirical_bayes_prior_points(
            players, event_players, excluded_prior, min_matches=3
        )
        leaked, _ = audit.estimate_empirical_bayes_prior_points(
            players, {}, full_prior, min_matches=3
        )
        self.assertNotEqual(
            excluded["serve"]["clay"],
            leaked["serve"]["clay"],
            "The target tournament must not influence its own empirical-Bayes prior.",
        )

    def test_empirical_bayes_prior_is_role_and_surface_specific(self):
        values = {
            "serve": {"clay": 100.0, "hard_grass": 200.0, "pooled": 300.0},
            "return": {"clay": 400.0, "hard_grass": 500.0, "pooled": 600.0},
        }
        self.assertEqual(100.0, audit.resolve_prior_points(values, "serve", "clay"))
        self.assertEqual(500.0, audit.resolve_prior_points(values, "return", "hard_grass"))
        self.assertEqual(600.0, audit.resolve_prior_points(values, "return", "pooled"))

    def test_pooled_returner_fallback_keeps_production_one_match_eligibility(self):
        player = {
            "serve": {
                "clay": {"aces": 0.0, "points": 0.0, "matches": 0},
                "hard_grass": {"aces": 0.0, "points": 0.0, "matches": 0},
                "pooled": {"aces": 0.0, "points": 0.0, "matches": 0},
            },
            "return": {
                "clay": {"aces": 0.0, "points": 0.0, "matches": 0},
                "hard_grass": {"aces": 0.0, "points": 0.0, "matches": 0},
                "pooled": {"aces": 8.0, "points": 100.0, "matches": 1},
            },
        }
        prior = {
            role: {
                bucket: {"aces": 8.0, "points": 100.0, "matches": 3}
                for bucket in ("clay", "hard_grass", "pooled")
            }
            for role in ("serve", "return")
        }
        result = audit.weighted_profile(
            player, "return", prior, clay_weight=1 / 3,
            prior_points=0.0, min_matches=3, pooled_fallback=True,
            pooled_min_matches=1,
        )
        self.assertAlmostEqual(0.08, result)

    def test_grass_is_grouped_with_hard_for_the_neutral_profile(self):
        self.assertEqual("hard_grass", audit.surface_bucket("Grass"))
        self.assertEqual("hard_grass", audit.surface_bucket("Hard"))
        self.assertEqual("clay", audit.surface_bucket("Clay"))
        self.assertEqual("carpet", audit.surface_bucket("Carpet"))


class LeaveOneEventOutTests(unittest.TestCase):
    def test_target_event_is_removed_from_both_player_profiles(self):
        rows = (
            [match("Clay A", 10, 5) for _ in range(3)]
            + [match("Clay B", 20, 10) for _ in range(3)]
        )
        (
            _usable, event_rows, event_surfaces, players, by_event_players,
            global_stats, event_stats,
        ) = audit.aggregate_season(rows)
        target = audit.event_id("Clay A")
        config = {
            "clay_weight": 1 / 3,
            "prior_points": 0.0,
            "min_matches": 3,
            "pooled_return_fallback": True,
        }
        profiles, _priors = audit.build_event_profiles(
            target,
            event_surfaces[target],
            players,
            by_event_players,
            global_stats,
            event_stats,
            config,
        )

        # Player One served at 10% in Clay A and 20% in Clay B. A leaked profile
        # would be 15%; the event-excluded profile must be 20%.
        self.assertAlmostEqual(0.20, profiles["p1"]["serve_rate"])
        self.assertEqual(3, len(event_rows[target]))

    def test_event_names_map_to_tennis_abstract_reference_keys(self):
        self.assertEqual("australianopen", audit.event_id("Australian Open"))
        self.assertEqual("montecarlo", audit.event_id("Monte Carlo Masters"))
        self.assertEqual("parismasters", audit.event_id("Paris Masters"))
        self.assertEqual("marrakech", audit.event_id("Marrakech"))

    def test_target_event_is_removed_from_tour_average_anchor(self):
        rows = (
            [match("Clay A", 10, 5) for _ in range(3)]
            + [match("Clay B", 20, 10) for _ in range(3)]
        )
        (
            _usable, event_rows, event_surfaces, players, by_event_players,
            global_stats, event_stats,
        ) = audit.aggregate_season(rows)
        target = audit.event_id("Clay A")
        config = {
            "clay_weight": 1 / 3,
            "prior_points": 0.0,
            "min_matches": 3,
            "pooled_return_fallback": True,
        }
        profiles, priors = audit.build_event_profiles(
            target, event_surfaces[target], players, by_event_players,
            global_stats, event_stats, config,
        )
        tour_rate = audit.tour_rate_excluding_event(target, global_stats, event_stats)
        # Only Clay B contributes to the neutral anchor after event exclusion.
        self.assertAlmostEqual(30 / 200, tour_rate)


class PairedBootstrapDifferenceTests(unittest.TestCase):
    def test_paired_differences_use_identical_resample_draws(self):
        candidate = [
            {"r": 0.92, "rho": 0.93, "mae": 0.06, "bias": 0.0},
            {"r": 0.91, "rho": 0.92, "mae": 0.07, "bias": 0.0},
        ]
        baseline = [
            {"r": 0.90, "rho": 0.91, "mae": 0.08, "bias": 0.0},
            {"r": 0.90, "rho": 0.91, "mae": 0.08, "bias": 0.0},
        ]
        result = audit.paired_bootstrap_differences(candidate, baseline)
        for actual, expected in zip(result["r"], [0.02, 0.01]):
            self.assertAlmostEqual(expected, actual, places=12)
        for actual, expected in zip(result["rho"], [0.02, 0.01]):
            self.assertAlmostEqual(expected, actual, places=12)
        for actual, expected in zip(result["mae"], [-0.02, -0.01]):
            self.assertAlmostEqual(expected, actual, places=12)

    def test_paired_differences_reject_unmatched_resample_counts(self):
        with self.assertRaises(ValueError):
            audit.paired_bootstrap_differences([{"r": 1}], [])


class UncertaintyTests(unittest.TestCase):
    def test_match_cluster_bootstrap_is_reproducible(self):
        clusters = [[0.8, 1.2], [1.0, 0.9], [1.3, 0.7], [1.1, 1.0]]
        one = audit.cluster_bootstrap_interval(clusters, 1.0, 500, 123)
        two = audit.cluster_bootstrap_interval(clusters, 1.0, 500, 123)
        self.assertEqual(one, two)
        self.assertLessEqual(one["low"], one["high"])
        self.assertEqual(500, one["iterations"])

    def test_full_pipeline_bootstrap_refits_profiles_matches_and_normaliser(self):
        rows = (
            [match("Paris Masters", 8, 8, surface="Hard") for _ in range(10)]
            + [match("Rome Masters", 8, 8, surface="Hard") for _ in range(10)]
        )
        config = audit.config_grid()["loeo_production_150_min3"]
        one = audit.full_pipeline_bootstrap_interval(
            "parismasters", rows, config, iterations=12, seed=91
        )
        two = audit.full_pipeline_bootstrap_interval(
            "parismasters", rows, config, iterations=12, seed=91
        )
        self.assertEqual(one, two)
        self.assertEqual(12, one["iterations"])
        self.assertEqual(12, one["successful"])
        self.assertAlmostEqual(1.0, one["low"], places=10)
        self.assertAlmostEqual(1.0, one["high"], places=10)
        self.assertIn("recomputed", one["normaliser"])

    def test_configuration_grid_contains_weights_shrinkage_and_carpet_test(self):
        configs = audit.config_grid()
        self.assertIn("loeo_unshrunk_1_3", configs)
        self.assertIn("loeo_production_150_min3", configs)
        self.assertEqual(0.0, configs["loeo_unshrunk_1_3"]["prior_points"])
        self.assertEqual(
            audit.compute_speed.PROFILE_PRIOR_SERVICE_POINTS,
            configs["loeo_production_150_min3"]["prior_points"],
        )
        self.assertEqual(
            audit.compute_speed.MIN_MATCHES_PER_SURFACE,
            configs["loeo_production_150_min3"]["min_matches"],
        )
        self.assertIn("weight_050_shrink_150_min3", configs)
        self.assertEqual(0.5, configs["weight_050_shrink_150_min3"]["clay_weight"])
        self.assertEqual(
            audit.compute_speed.PROFILE_PRIOR_SERVICE_POINTS,
            configs["weight_050_shrink_150_min3"]["prior_points"],
        )
        self.assertEqual(
            configs["loeo_production_150_min3"]["min_matches"],
            configs["weight_050_shrink_150_min3"]["min_matches"],
        )
        self.assertIn("loeo_carpet_specific", configs)
        self.assertIn("loeo_grass_specific_shrink_150", configs)
        self.assertIn("loeo_empirical_bayes_1_3", configs)
        self.assertIn("shrink_100_min3", configs)
        self.assertIn("shrink_300_min3", configs)
        self.assertIn("loeo_empirical_bayes_1_3_min3", configs)
        self.assertIn("shrink_150", configs)
        self.assertIn("shrink_300", configs)
        self.assertIn("weight_clay_0_500", configs)
        self.assertIn("weight_clay_0_667", configs)
        self.assertIn("weight_050_shrink_150", configs)


    def test_profile_and_match_bootstrap_refits_profiles_and_is_reproducible(self):
        rows = (
            [match("Paris Masters", 8, 8, surface="Hard") for _ in range(10)]
            + [match("Rome Masters", 8, 8, surface="Hard") for _ in range(10)]
        )
        (_usable, event_rows, surfaces, _players, _event_players,
         _global_stats, _event_stats) = audit.aggregate_season(rows)
        target = audit.event_id("Paris Masters")
        config = audit.config_grid()["loeo_production_150_min3"]
        one = audit.profile_and_match_bootstrap_interval(
            target, event_rows[target], surfaces[target], event_rows,
            config, normaliser=1.0, iterations=12, seed=91,
        )
        two = audit.profile_and_match_bootstrap_interval(
            target, event_rows[target], surfaces[target], event_rows,
            config, normaliser=1.0, iterations=12, seed=91,
        )
        self.assertEqual(one, two)
        self.assertEqual(12, one["iterations"])
        self.assertEqual(12, one["successful"])
        self.assertAlmostEqual(1.0, one["low"], places=10)
        self.assertAlmostEqual(1.0, one["high"], places=10)

    def test_event_profiles_include_target_players_without_training_history(self):
        rows = [match("Rome Masters", 8, 8, surface="Hard") for _ in range(10)]
        (_usable, _event_rows, surfaces, players, event_players,
         global_stats, event_stats) = audit.aggregate_season(rows)
        target = audit.event_id("Paris Masters")
        profiles, _ = audit.build_event_profiles(
            target, "Hard", players, event_players, global_stats, event_stats,
            audit.config_grid()["loeo_production_150_min3"],
            target_player_ids={"target-one", "target-two"},
        )
        self.assertIn("target-one", profiles)
        self.assertIn("target-two", profiles)
        self.assertIsNone(profiles["target-one"]["serve_rate"])
        self.assertIsNone(profiles["target-two"]["concede_rate"])

    def test_unmapped_valid_match_rows_still_train_season_priors(self):
        row = match("Unknown Tour Event", 8, 7, surface="Hard")
        row["tourney_name"] = ""
        (_usable, event_rows, _surfaces, _players, _event_players,
         global_stats, _event_stats) = audit.aggregate_season([row])
        self.assertEqual({}, dict(event_rows))
        self.assertEqual(15.0, global_stats["serve"]["pooled"]["aces"])
        self.assertEqual(200.0, global_stats["serve"]["pooled"]["points"])


    def test_grass_specific_profiles_use_grass_only_when_three_grass_matches_qualify(self):
        rows = (
            [match("Grass Event A", 10, 5, surface="Grass") for _ in range(3)]
            + [match("Hard Event A", 20, 10, surface="Hard") for _ in range(3)]
            + [match("Queens Club", 15, 7, surface="Grass") for _ in range(10)]
        )
        (_usable, event_rows, surfaces, players, event_players,
         global_stats, event_stats) = audit.aggregate_season(rows)
        target = audit.event_id("Queens Club")
        config = audit.config_grid()["loeo_grass_specific_shrink_150"]
        profiles, _ = audit.build_event_profiles(
            target, surfaces[target], players, event_players,
            global_stats, event_stats, config,
        )
        target_serve = event_players[target]["p1"]["serve"]["grass"]
        target_event_serve = event_stats[target]["serve"]["grass"]
        training_serve = audit.subtract_stats(
            players["p1"]["serve"]["grass"], target_serve
        )
        training_grass_prior = audit.subtract_stats(
            global_stats["serve"]["grass"], target_event_serve
        )
        expected_serve = audit.posterior_rate(
            training_serve, training_grass_prior,
            prior_points=150.0,
            min_matches=3,
        )
        self.assertIsNotNone(expected_serve)
        self.assertAlmostEqual(expected_serve, profiles["p1"]["serve_rate"])


if __name__ == "__main__":
    unittest.main()
