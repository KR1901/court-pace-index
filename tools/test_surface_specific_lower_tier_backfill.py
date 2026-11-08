import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audit_lower_tier_profile_backfill as base
import audit_surface_specific_lower_tier_backfill as audit


def row(
    winner="player-a",
    loser="player-b",
    winner_aces=8,
    loser_aces=6,
    winner_points=100,
    loser_points=100,
    surface="Clay",
):
    return {
        "tourney_id": "2020-999",
        "tourney_name": "Sample Event",
        "surface": surface,
        "winner_id": winner,
        "loser_id": loser,
        "w_svpt": str(winner_points),
        "l_svpt": str(loser_points),
        "w_ace": str(winner_aces),
        "l_ace": str(loser_aces),
    }


class SurfaceWeightConstructionTests(unittest.TestCase):
    def test_clay_and_hard_grass_weights_are_applied_independently(self):
        clay_players, _ = base.aggregate_rows([row(surface="Clay")])
        hard_players, _ = base.aggregate_rows([row(surface="Hard")])
        combined = {}
        for source in (clay_players, hard_players):
            for player_id, stats in source.items():
                target = combined.setdefault(player_id, base.empty_player())
                # Add the source structures without weights so the test can distinguish them.
                for role in base.ROLES:
                    for bucket in base.BUCKETS:
                        for field in ("aces", "points", "matches"):
                            target[role][bucket][field] += stats[role][bucket][field]

        lower_by_year = {
            2020: {
                "challenger": combined,
                "qualifying": {},
            }
        }
        candidate = audit.arm(
            "clay_050_hardgrass_025",
            challenger=audit.make_weight_map(
                pool=0.25, clay=0.50, hard_grass=0.25
            ),
        )
        players, global_stats = audit.build_training_stats(
            2021, {}, lower_by_year, candidate
        )
        decay = audit.RECENCY_DECAY
        self.assertAlmostEqual(
            8.0 * decay * 0.50,
            players["player-a"]["serve"]["clay"]["aces"],
        )
        self.assertAlmostEqual(
            8.0 * decay * 0.25,
            players["player-a"]["serve"]["hard_grass"]["aces"],
        )
        self.assertAlmostEqual(
            (8.0 + 8.0) * decay * 0.25,
            players["player-a"]["serve"]["pooled"]["aces"],
        )
        self.assertEqual(0.0, global_stats["serve"]["pooled"]["aces"])

    def test_lower_tier_does_not_change_atp_population_prior(self):
        main_players, main_global = base.aggregate_rows([row(winner_aces=8, loser_aces=6)])
        lower_players, _ = base.aggregate_rows([
            row(winner_aces=60, loser_aces=50, surface="Clay")
        ])
        lower_by_year = {
            2020: {"challenger": lower_players, "qualifying": {}}
        }
        candidate = audit.arm(
            "challenger_surface",
            challenger=audit.make_weight_map(0.25, 0.50, 0.25),
        )
        _players, global_stats = audit.build_training_stats(
            2021, {2020: (main_players, main_global)}, lower_by_year, candidate
        )
        self.assertEqual(
            main_global["serve"]["pooled"]["aces"] * audit.RECENCY_DECAY,
            global_stats["serve"]["pooled"]["aces"],
        )
        self.assertEqual(
            main_global["serve"]["pooled"]["points"] * audit.RECENCY_DECAY,
            global_stats["serve"]["pooled"]["points"],
        )

    def test_target_and_future_season_rows_are_excluded(self):
        past_players, past_global = base.aggregate_rows([
            row(winner_aces=8, loser_aces=6)
        ])
        target_players, target_global = base.aggregate_rows([
            row(winner_aces=90, loser_aces=90)
        ])
        future_players, future_global = base.aggregate_rows([
            row(winner_aces=99, loser_aces=99)
        ])
        candidate = audit.arm("atp_only")
        players, global_stats = audit.build_training_stats(
            2022,
            {
                2020: (past_players, past_global),
                2022: (target_players, target_global),
                2023: (future_players, future_global),
            },
            {},
            candidate,
        )
        self.assertAlmostEqual(
            14.0 * audit.RECENCY_DECAY ** 2,
            global_stats["serve"]["pooled"]["aces"],
        )
        self.assertNotIn("future-only", players)

    def test_global_arm_has_equal_surface_weights(self):
        candidate = next(
            item for item in audit.arm_grid()
            if item["name"] == "challenger_global_0.25"
        )
        self.assertEqual(
            {"pooled": 0.25, "clay": 0.25, "hard_grass": 0.25},
            candidate["challenger"],
        )

    def test_surface_grid_contains_independent_clay_and_hardgrass_combinations(self):
        candidates = {
            item["name"]: item for item in audit.arm_grid()
        }
        clay_only = candidates[
            "challenger_surface_clay_1.00_hardgrass_0.00"
        ]
        hard_only = candidates[
            "challenger_surface_clay_0.00_hardgrass_1.00"
        ]
        self.assertEqual(1.0, clay_only["challenger"]["clay"])
        self.assertEqual(0.0, clay_only["challenger"]["hard_grass"])
        self.assertEqual(0.0, hard_only["challenger"]["clay"])
        self.assertEqual(1.0, hard_only["challenger"]["hard_grass"])


class SurfaceMetricTests(unittest.TestCase):
    def test_surface_metadata_is_not_added_as_a_numeric_metric(self):
        clay = audit.empty_cluster("clay")
        clay.update({
            "sides": 2,
            "points": 200.0,
            "aces": 14.0,
            "predicted_aces": 15.0,
            "point_abs_error": 2.0,
            "side_abs_error": 0.02,
            "binomial_log_loss": 80.0,
            "server_profile_sides": 2,
            "returner_profile_sides": 2,
            "both_profile_sides": 2,
        })
        hard = audit.empty_cluster("hard")
        hard.update({
            "sides": 2,
            "points": 200.0,
            "aces": 16.0,
            "predicted_aces": 16.0,
            "point_abs_error": 1.0,
            "side_abs_error": 0.01,
            "binomial_log_loss": 70.0,
            "server_profile_sides": 2,
            "returner_profile_sides": 2,
            "both_profile_sides": 2,
        })
        metrics = audit.metrics_from_clusters([clay, hard])
        self.assertEqual(4, metrics["server_sides"])
        self.assertAlmostEqual(0.375, metrics["binomial_log_loss_per_point"])
        self.assertAlmostEqual(0.0075, metrics["point_weighted_mae"])

    def test_paired_surface_bootstrap_keeps_surface_clusters_separate(self):
        def populated(surface, point_abs_error):
            value = audit.empty_cluster(surface)
            value.update({
                "sides": 2,
                "points": 200.0,
                "aces": 14.0,
                "predicted_aces": 15.0,
                "point_abs_error": point_abs_error,
                "side_abs_error": 0.02,
                "binomial_log_loss": 80.0,
                "server_profile_sides": 2,
                "returner_profile_sides": 2,
                "both_profile_sides": 2,
            })
            return value

        baseline = {}
        candidate = {}
        for index in range(4):
            baseline[f"clay-{index}"] = populated("clay", 2.0)
            candidate[f"clay-{index}"] = populated("clay", 1.0)
            baseline[f"hard-{index}"] = populated("hard", 2.0)
            candidate[f"hard-{index}"] = populated("hard", 3.0)

        result = audit.paired_surface_bootstrap(
            baseline, candidate, iterations=100, seed=17
        )
        self.assertEqual(4, result["clay"]["events"])
        self.assertEqual(4, result["hard"]["events"])
        self.assertEqual(100, result["clay"]["successful"])
        self.assertEqual(100, result["hard"]["successful"])
        clay_mae = result["clay"]["intervals"]["point_weighted_mae"]
        hard_mae = result["hard"]["intervals"]["point_weighted_mae"]
        self.assertLess(clay_mae["high"], 0.0)
        self.assertGreater(hard_mae["low"], 0.0)

    def test_paired_event_bootstrap_returns_zero_for_identical_arms(self):
        def populated(surface):
            value = audit.empty_cluster(surface)
            value.update({
                "sides": 2,
                "points": 200.0,
                "aces": 14.0,
                "predicted_aces": 15.0,
                "point_abs_error": 2.0,
                "side_abs_error": 0.02,
                "binomial_log_loss": 80.0,
                "server_profile_sides": 2,
                "returner_profile_sides": 2,
                "both_profile_sides": 2,
            })
            return value

        baseline = {
            "event-a": populated("clay"),
            "event-b": populated("hard"),
            "event-c": populated("grass"),
            "event-d": populated("clay"),
        }
        candidate = {
            key: dict(value) for key, value in baseline.items()
        }
        result = audit.paired_event_bootstrap(
            baseline, candidate, iterations=100, seed=11
        )
        self.assertEqual(4, result["events"])
        self.assertEqual(100, result["successful"])
        for interval in result["intervals"].values():
            self.assertEqual(0.0, interval["low"])
            self.assertEqual(0.0, interval["high"])


if __name__ == "__main__":
    unittest.main()
