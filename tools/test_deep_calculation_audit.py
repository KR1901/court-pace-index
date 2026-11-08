import sys
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


class UncertaintyTests(unittest.TestCase):
    def test_match_cluster_bootstrap_is_reproducible(self):
        clusters = [[0.8, 1.2], [1.0, 0.9], [1.3, 0.7], [1.1, 1.0]]
        one = audit.cluster_bootstrap_interval(clusters, 1.0, 500, 123)
        two = audit.cluster_bootstrap_interval(clusters, 1.0, 500, 123)
        self.assertEqual(one, two)
        self.assertLessEqual(one["low"], one["high"])
        self.assertEqual(500, one["iterations"])

    def test_configuration_grid_contains_weights_shrinkage_and_carpet_test(self):
        configs = audit.config_grid()
        self.assertIn("loeo_current_1_3", configs)
        self.assertIn("loeo_carpet_pooled", configs)
        self.assertIn("loeo_carpet_specific", configs)
        self.assertIn("loeo_empirical_bayes_1_3", configs)
        self.assertIn("shrink_100_min3", configs)
        self.assertIn("shrink_150_min3", configs)
        self.assertIn("loeo_empirical_bayes_1_3_min3", configs)
        self.assertIn("shrink_150", configs)
        self.assertIn("shrink_300", configs)
        self.assertIn("weight_clay_0_500", configs)
        self.assertIn("weight_clay_0_667", configs)
        self.assertIn("weight_050_shrink_150", configs)


if __name__ == "__main__":
    unittest.main()
