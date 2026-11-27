import math
import unittest

import ace_probability_calibration_audit as audit


class ProbabilityFormulaTests(unittest.TestCase):
    def test_production_log5_returns_the_tour_rate_for_neutral_players(self):
        probabilities, flags = audit.probability_models(0.08, 0.08, 0.08)
        self.assertAlmostEqual(0.08, probabilities["log5"], places=12)
        self.assertAlmostEqual(0.08, probabilities["neutral"], places=12)
        self.assertAlmostEqual(0.08, probabilities["additive"], places=12)
        self.assertFalse(flags["additiveClippedLow"])
        self.assertFalse(flags["additiveClippedHigh"])

    def test_server_and_returner_effects_move_production_probability_in_expected_direction(self):
        baseline, _ = audit.probability_models(0.08, 0.08, 0.08)
        stronger_server, _ = audit.probability_models(0.15, 0.08, 0.08)
        stronger_returner, _ = audit.probability_models(0.08, 0.15, 0.08)
        faster_tour, _ = audit.probability_models(0.08, 0.08, 0.12)
        self.assertGreater(stronger_server["log5"], baseline["log5"])
        self.assertGreater(stronger_returner["log5"], baseline["log5"])
        self.assertLess(faster_tour["log5"], baseline["log5"])

    def test_additive_probability_is_clipped_and_diagnostic_is_reported(self):
        low, low_flags = audit.probability_models(0.01, 0.01, 0.08)
        high, high_flags = audit.probability_models(0.90, 0.90, 0.10)
        self.assertEqual(audit.EPSILON, low["additive"])
        self.assertTrue(low_flags["additiveClippedLow"])
        self.assertEqual(1.0 - audit.EPSILON, high["additive"])
        self.assertTrue(high_flags["additiveClippedHigh"])

    def test_invalid_player_or_tour_rate_is_rejected(self):
        with self.assertRaises(ValueError):
            audit.probability_models(0.0, 0.08, 0.08)
        with self.assertRaises(ValueError):
            audit.probability_models(0.08, 0.08, 1.0)

    def test_grouped_binomial_brier_score_matches_individual_trial_expansion(self):
        log_loss, brier = audit.binomial_scores(aces=1, service_points=3, probability=0.2)
        expected_brier = ((1 - 0.2) ** 2 + 2 * (0.2 ** 2))
        expected_log_loss = -(math.log(0.2) + 2 * math.log(0.8))
        self.assertAlmostEqual(expected_brier, brier, places=12)
        self.assertAlmostEqual(expected_log_loss, log_loss, places=12)

    def test_calibration_fit_recovers_neutral_intercept_and_unit_slope(self):
        observations = []
        for probability in (0.04, 0.06, 0.08, 0.12, 0.18):
            trials = 100_000
            observations.append({
                "probability": probability,
                "aces": probability * trials,
                "servicePoints": trials,
            })
        result = audit.fit_logistic_calibration(observations)
        self.assertIsNotNone(result)
        self.assertTrue(result["converged"])
        self.assertAlmostEqual(0.0, result["intercept"], places=5)
        self.assertAlmostEqual(1.0, result["slope"], places=5)

    def test_bootstrap_is_reproducible_and_reports_paired_model_differences(self):
        def event(year, log5, neutral, additive):
            return {
                "year": year,
                "models": {
                    "log5": {
                        "logLoss": log5, "brier": log5 / 10,
                        "servicePoints": 100, "sides": 2,
                        "actualAces": 8, "predictedAces": 8,
                    },
                    "neutral": {
                        "logLoss": neutral, "brier": neutral / 10,
                        "servicePoints": 100, "sides": 2,
                        "actualAces": 8, "predictedAces": 8,
                    },
                    "additive": {
                        "logLoss": additive, "brier": additive / 10,
                        "servicePoints": 100, "sides": 2,
                        "actualAces": 8, "predictedAces": 8,
                    },
                },
            }

        events = [
            event(2020, 20, 25, 23),
            event(2020, 22, 27, 24),
            event(2021, 21, 24, 23),
            event(2021, 23, 28, 25),
        ]
        a = audit.event_cluster_bootstrap(events, iterations=100, seed=12)
        b = audit.event_cluster_bootstrap(events, iterations=100, seed=12)
        self.assertEqual(a, b)
        self.assertIn("neutral_minus_log5", a)
        self.assertGreater(a["neutral_minus_log5"]["logLossPerServicePoint"]["meanDifference"], 0.0)


if __name__ == "__main__":
    unittest.main()
