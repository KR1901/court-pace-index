import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audit_lower_tier_profile_backfill as audit


def row(
    *,
    winner="player-a",
    loser="player-b",
    winner_aces=8,
    loser_aces=6,
    winner_points=100,
    loser_points=100,
    surface="Clay",
    level="250",
    round_name="R32",
    tournament="Sample Event",
    tournament_id="2020-999",
):
    return {
        "tourney_id": tournament_id,
        "tourney_name": tournament,
        "tourney_level": level,
        "round": round_name,
        "surface": surface,
        "winner_id": winner,
        "loser_id": loser,
        "w_svpt": str(winner_points),
        "l_svpt": str(loser_points),
        "w_ace": str(winner_aces),
        "l_ace": str(loser_aces),
    }


class LowerTierClassificationTests(unittest.TestCase):
    def test_challenger_main_draw_uses_level_c_and_non_qualifying_round(self):
        self.assertEqual(
            "challenger",
            audit.classify_lower_row(row(level="C", round_name="R32")),
        )

    def test_atp_qualifying_uses_parent_tour_level_and_q_round(self):
        self.assertEqual(
            "qualifying",
            audit.classify_lower_row(row(level="M", round_name="Q1")),
        )
        self.assertEqual(
            "qualifying",
            audit.classify_lower_row(row(level="250", round_name="Q3")),
        )

    def test_rejects_challenger_qualifying_and_atp_main_draw(self):
        self.assertIsNone(
            audit.classify_lower_row(row(level="C", round_name="Q1"))
        )
        self.assertIsNone(
            audit.classify_lower_row(row(level="M", round_name="R32"))
        )

    def test_reuses_production_stat_validity_checks(self):
        valid = row(level="C", round_name="R32")
        invalid = row(level="C", round_name="R32", winner_aces=101, winner_points=100)
        selected = audit.validate_lower_rows([valid, invalid])
        self.assertEqual(1, len(selected))
        self.assertEqual("challenger", selected[0]["_lower_tier"])
        self.assertEqual("250", selected[0]["tourney_level"])


class AggregationTests(unittest.TestCase):
    def test_player_role_stats_keep_serve_and_return_concession_distinct(self):
        players, global_stats = audit.aggregate_rows([row()])
        winner = players["player-a"]
        loser = players["player-b"]
        self.assertEqual(8.0, winner["serve"]["clay"]["aces"])
        self.assertEqual(100.0, winner["serve"]["clay"]["points"])
        self.assertEqual(6.0, winner["return"]["clay"]["aces"])
        self.assertEqual(100.0, winner["return"]["clay"]["points"])
        self.assertEqual(6.0, loser["serve"]["clay"]["aces"])
        self.assertEqual(8.0, loser["return"]["clay"]["aces"])
        self.assertEqual(14.0, global_stats["serve"]["pooled"]["aces"])
        self.assertEqual(14.0, global_stats["return"]["pooled"]["aces"])

    def test_hard_and_grass_are_grouped_but_carpet_remains_pooled(self):
        hard, _ = audit.aggregate_rows([row(surface="Hard")])
        grass, _ = audit.aggregate_rows([row(surface="Grass")])
        carpet, _ = audit.aggregate_rows([row(surface="Carpet")])
        self.assertEqual(1, hard["player-a"]["serve"]["hard_grass"]["matches"])
        self.assertEqual(1, grass["player-a"]["serve"]["hard_grass"]["matches"])
        self.assertEqual(0, carpet["player-a"]["serve"]["hard_grass"]["matches"])
        self.assertEqual(1, carpet["player-a"]["serve"]["pooled"]["matches"])


class RollingOriginTests(unittest.TestCase):
    def test_target_and_future_seasons_are_excluded_and_lower_tier_never_changes_tour_prior(self):
        main_2021 = audit.aggregate_rows([
            row(winner_aces=8, loser_aces=6, winner_points=100, loser_points=100)
        ])
        main_2022 = audit.aggregate_rows([
            row(winner_aces=90, loser_aces=90, winner_points=100, loser_points=100)
        ])
        main_2023 = audit.aggregate_rows([
            row(winner_aces=99, loser_aces=99, winner_points=100, loser_points=100)
        ])
        lower_2021 = audit.aggregate_rows([
            row(
                winner_aces=30, loser_aces=25, winner_points=100, loser_points=100,
                level="C", tournament="Challenger 2021",
            )
        ])[0]
        lower_2022 = audit.aggregate_rows([
            row(
                winner_aces=50, loser_aces=40, winner_points=100, loser_points=100,
                level="250", round_name="Q1", tournament="Qualifying 2022",
            )
        ])[0]
        lower_2023 = audit.aggregate_rows([
            row(
                winner_aces=99, loser_aces=99, winner_points=100, loser_points=100,
                level="C", tournament="Challenger target year",
            )
        ])[0]

        main_by_year = {
            2021: main_2021,
            2022: main_2022,
            2023: main_2023,
        }
        lower_by_year = {
            2021: {"challenger": lower_2021, "qualifying": {}},
            2022: {"challenger": {}, "qualifying": lower_2022},
            2023: {"challenger": lower_2023, "qualifying": {}},
        }

        players, global_stats, _ = audit.build_training_stats(
            2023, main_by_year, lower_by_year,
            challenger_weight=0.5, qualifying_weight=0.25,
        )
        decay_2021 = audit.RECENCY_DECAY ** 2
        decay_2022 = audit.RECENCY_DECAY
        # The ATP population prior is made only from main-draw history.
        expected_main_aces = (
            14.0 * decay_2021 + 180.0 * decay_2022
        )
        expected_main_points = (
            200.0 * decay_2021 + 200.0 * decay_2022
        )
        self.assertAlmostEqual(
            expected_main_aces / expected_main_points,
            audit.prior_rate(global_stats["serve"]["pooled"]),
        )
        # Personal server history receives the separately weighted lower-tier evidence.
        player_aces = players["player-a"]["serve"]["pooled"]["aces"]
        self.assertAlmostEqual(
            8.0 * decay_2021 + 90.0 * decay_2022
            + 30.0 * decay_2021 * 0.5
            + 50.0 * decay_2022 * 0.25,
            player_aces,
        )
        # Target-year main and lower-tier rows do not enter the 2023 training profile.
        self.assertAlmostEqual(
            1 + 1 + 1,
            players["player-a"]["serve"]["pooled"]["matches"],
        )

    def test_zero_lower_tier_weight_is_identical_to_atp_only_stats(self):
        main = audit.aggregate_rows([row()])
        lower = audit.aggregate_rows([row(level="C", tournament="Challenger")])[0]
        main_by_year = {2020: main}
        lower_by_year = {2020: {"challenger": lower, "qualifying": {}}}
        atp_only, prior_only, _ = audit.build_training_stats(
            2021, main_by_year, lower_by_year, 0.0, 0.0
        )
        with_lower, prior_with_lower, _ = audit.build_training_stats(
            2021, main_by_year, lower_by_year, 0.0, 1.0
        )
        self.assertEqual(
            prior_only["serve"]["pooled"]["aces"],
            prior_with_lower["serve"]["pooled"]["aces"],
        )
        # Challenger weight is zero in both profiles; changing qualifying weight
        # cannot accidentally inject a challenger-only player.
        self.assertEqual(
            atp_only["player-a"]["serve"]["pooled"]["aces"],
            with_lower["player-a"]["serve"]["pooled"]["aces"],
        )

    def test_recent_target_year_data_is_not_used_for_training(self):
        old = audit.aggregate_rows([row(winner_aces=8, loser_aces=6)])
        target_year = audit.aggregate_rows([row(winner_aces=99, loser_aces=99)])
        future = audit.aggregate_rows([row(winner_aces=100, loser_aces=100)])
        players, global_stats, _ = audit.build_training_stats(
            2022,
            {2020: old, 2021: target_year, 2022: future},
            {},
            0.0,
            0.0,
        )
        self.assertAlmostEqual(
            14.0 * audit.RECENCY_DECAY ** 2,
            global_stats["serve"]["pooled"]["aces"],
        )
        self.assertNotAlmostEqual(
            global_stats["serve"]["pooled"]["aces"],
            198.0,
        )


class ProfileAndScoringTests(unittest.TestCase):
    def test_surface_and_pooled_profiles_use_the_150_point_prior(self):
        player = audit.empty_player()
        player["serve"]["clay"] = {
            "aces": 30.0, "points": 300.0, "matches": 3
        }
        player["return"]["pooled"] = {
            "aces": 8.0, "points": 100.0, "matches": 1
        }
        priors = {
            "serve": {"pooled": 0.08, "clay": 0.09, "hard_grass": 0.11},
            "return": {"pooled": 0.08, "clay": 0.09, "hard_grass": 0.11},
        }
        serve = audit.shrunk_rate(player, "serve", priors)
        concede = audit.shrunk_rate(player, "return", priors)
        self.assertAlmostEqual((30 + 150 * 0.09) / 450, serve)
        self.assertAlmostEqual((8 + 150 * 0.08) / 250, concede)

    def test_training_profiles_use_only_atp_population_prior(self):
        main = audit.aggregate_rows([row(winner_aces=8, loser_aces=6)])
        lower = audit.aggregate_rows([
            row(level="C", winner_aces=30, loser_aces=25)
        ])[0]
        players, global_stats, _ = audit.build_training_stats(
            2021,
            {2020: main},
            {2020: {"challenger": lower, "qualifying": {}}},
            1.0,
            0.0,
        )
        profiles, _priors, tour_rate = audit.build_profiles(
            {"player-a"}, players, global_stats
        )
        self.assertAlmostEqual(14 / 200, tour_rate)
        self.assertNotEqual(
            profiles["player-a"]["serve_rate"],
            tour_rate,
        )

    def test_cluster_metrics_and_bootstrap_delta_are_finite(self):
        clusters = {
            "event-one": {
                "sides": 2,
                "points": 200.0,
                "aces": 14.0,
                "predicted_aces": 15.0,
                "side_abs_error": 0.02,
                "point_abs_error": 2.0,
                "binomial_log_loss": 80.0,
                "server_profile_sides": 2,
                "returner_profile_sides": 2,
                "both_profile_sides": 2,
                "newly_both_profile_sides": 0,
            }
        }
        metrics = audit.metrics_from_clusters(list(clusters.values()))
        self.assertAlmostEqual(0.01, metrics["point_weighted_mae"])
        self.assertAlmostEqual(0.4, metrics["binomial_log_loss_per_point"])
        results = {
            "atp_only": {2022: clusters},
            "candidate": {2022: clusters},
        }
        delta = audit.bootstrap_validation_delta(
            results, "atp_only", "candidate", [2022], 100, 7
        )
        self.assertAlmostEqual(0.0, delta["binomial_log_loss_per_point"]["low"])
        self.assertAlmostEqual(0.0, delta["point_weighted_mae"]["high"])


if __name__ == "__main__":
    unittest.main()
