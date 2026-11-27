import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import compute_speed
from chronological_loeo_study import (
    completed_standard_event_ids_before_target,
    cross_fitted_normalisation,
    event_metrics,
    grouped_standard_chronology_rows,
)


class ProductionEquivalentResearchTests(unittest.TestCase):
    def test_event_metrics_uses_production_name_crosswalk_and_ratio_clusters(self):
        rows = [{
            "tourney_level": "250",
            "tourney_name": "Example Open",
            "surface": "Hard",
            "winner_id": "tml-current-1",
            "loser_id": "tml-current-2",
            "winner_name": "Player One",
            "loser_name": "Player Two",
            "w_svpt": "65",
            "l_svpt": "68",
            "w_ace": "7",
            "l_ace": "2",
        }]
        profiles = {
            "archive-old-1": {
                "id": "archive-old-1", "name": "Player One",
                "serve_rate": 0.12, "serve_rate_source": "current-season-qualified",
                "concede_rate": 0.09, "concede_rate_source": "surface-qualified",
            },
            "archive-old-2": {
                "id": "archive-old-2", "name": "Player Two",
                "serve_rate": 0.08, "serve_rate_source": "current-season-qualified",
                "concede_rate": 0.14, "concede_rate_source": "surface-qualified",
            },
        }
        expected_profiles = {key: dict(value) for key, value in profiles.items()}
        compute_speed.ensure_event_profiles(rows, expected_profiles, 0.08)
        expected_clusters = compute_speed.event_match_ratio_clusters(
            rows, expected_profiles, 0.08,
        )
        expected_ratios = [ratio for cluster in expected_clusters for ratio in cluster]
        expected_raw = sum(expected_ratios) / len(expected_ratios)

        result = event_metrics(rows, profiles, 0.08)

        self.assertIsNotNone(result)
        self.assertAlmostEqual(expected_raw, result["raw"], places=12)
        self.assertEqual(len(expected_ratios), result["adjustedServerSides"])
        self.assertIn("tml-current-1", profiles)
        self.assertIn("tml-current-2", profiles)
        self.assertEqual(0, result["serverProfilesFallback"])
        self.assertEqual(0, result["returnerProfilesFallback"])

    def test_log_loss_uses_unfloored_probability_not_ratio_denominator_floor(self):
        rows = [{
            "tourney_level": "250",
            "tourney_name": "Example Open",
            "surface": "Clay",
            "winner_id": "p1",
            "loser_id": "p2",
            "winner_name": "Player One",
            "loser_name": "Player Two",
            "w_svpt": "65",
            "l_svpt": "68",
            "w_ace": "1",
            "l_ace": "0",
        }]
        profiles = {
            "p1": {
                "id": "p1", "name": "Player One",
                "serve_rate": 0.01, "serve_rate_source": "current-season-qualified",
                "concede_rate": 0.01, "concede_rate_source": "surface-qualified",
            },
            "p2": {
                "id": "p2", "name": "Player Two",
                "serve_rate": 0.01, "serve_rate_source": "current-season-qualified",
                "concede_rate": 0.01, "concede_rate_source": "surface-qualified",
            },
        }
        tour_rate = 0.08
        result = event_metrics(rows, profiles, tour_rate)

        expected_rate_winner = compute_speed.expected_calibrated_aces(
            0.01, 0.01, tour_rate, 65,
        ) / 65
        expected_rate_loser = compute_speed.expected_calibrated_aces(
            0.01, 0.01, tour_rate, 68,
        ) / 68
        expected_rate_winner = min(max(expected_rate_winner, 1e-9), 1.0 - 1e-9)
        expected_rate_loser = min(max(expected_rate_loser, 1e-9), 1.0 - 1e-9)
        expected_nll = -(
            1 * math.log(expected_rate_winner)
            + 64 * math.log(1.0 - expected_rate_winner)
            + 0 * math.log(expected_rate_loser)
            + 68 * math.log(1.0 - expected_rate_loser)
        )

        self.assertAlmostEqual(expected_nll, result["logLoss"], places=10)

    def test_cross_fitted_anchor_uses_independent_scores_and_stable_cohort(self):
        ratings = {
            "event-a": {"matches": 12, "calculationDiagnostics": {
                "rawAdjustedEventScore": 1.0, "adjustedServerSides": 30,
                "completedMatches": 12,
            }},
            "event-b": {"matches": 9, "calculationDiagnostics": {
                "rawAdjustedEventScore": 9.0, "adjustedServerSides": 19,
                "completedMatches": 9,
            }},
            "event-c": {"matches": 14, "calculationDiagnostics": {
                "rawAdjustedEventScore": 1.5, "adjustedServerSides": 40,
                "completedMatches": 14,
            }},
            "event-d": {"matches": 3, "calculationDiagnostics": {
                "rawAdjustedEventScore": 3.0, "adjustedServerSides": 12,
                "completedMatches": 3,
            }},
            "daviscup": {"matches": 40, "calculationDiagnostics": {
                "rawAdjustedEventScore": 100.0, "adjustedServerSides": 80,
                "completedMatches": 40,
            }},
        }

        result = cross_fitted_normalisation(
            {"event-a", "event-b", "event-c", "event-d", "daviscup"},
            ratings,
        )

        self.assertAlmostEqual(1.25, result["normalisation"], places=12)
        self.assertEqual("cross_fitted_stable_completed_events", result["mode"])
        self.assertEqual(["event-a", "event-c"], result["eventIds"])
        self.assertEqual(["event-a", "event-b", "event-c", "event-d"], result["candidateEventIds"])
        self.assertEqual(70, result["adjustedServerSides"])
        self.assertEqual(26, result["matches"])

    def test_cross_fitted_anchor_falls_back_to_all_rateable_events_below_threshold(self):
        ratings = {
            "event-a": {"calculationDiagnostics": {
                "rawAdjustedEventScore": 1.0, "adjustedServerSides": 8,
                "completedMatches": 4,
            }},
            "event-b": {"calculationDiagnostics": {
                "rawAdjustedEventScore": 3.0, "adjustedServerSides": 12,
                "completedMatches": 6,
            }},
        }

        result = cross_fitted_normalisation({"event-a", "event-b"}, ratings)

        self.assertAlmostEqual(2.0, result["normalisation"], places=12)
        self.assertEqual("cross_fitted_all_completed_events_fallback", result["mode"])
        self.assertEqual(["event-a", "event-b"], result["eventIds"])

    def test_completion_filter_excludes_adjacent_local_day_without_time_proof(self):
        def row(event, played_day, round_name):
            return {
                "tourney_name": event,
                "tourney_level": "250",
                "surface": "Hard",
                "tourney_date": played_day,
                "round": round_name,
                "score": "6-4 6-4",
                "winner_id": "p1",
                "loser_id": "p2",
                "winner_name": "Player One",
                "loser_name": "Player Two",
                "w_svpt": "65",
                "l_svpt": "68",
                "w_ace": "7",
                "l_ace": "2",
            }

        rows = [
            row("Brisbane", "20260104", "R32"),
            row("Brisbane", "20260111", "F"),
            row("Rotterdam", "20260209", "R32"),
            row("Rotterdam", "20260215", "F"),
            row("Rio de Janeiro", "20260216", "R32"),
            row("Rio de Janeiro", "20260217", "R32"),
        ]
        chronology_groups = grouped_standard_chronology_rows(rows)
        source = "TennisMyLife ATP Match Database"
        precision = compute_speed._source_event_date_granularity(rows, source)
        result = completed_standard_event_ids_before_target(
            chronology_groups,
            "rio",
            compute_speed._match_date(rows[-2]),
            None,
            precision,
        )

        self.assertIn("brisbane", result)
        self.assertNotIn("rotterdam", result)


if __name__ == "__main__":
    unittest.main()
