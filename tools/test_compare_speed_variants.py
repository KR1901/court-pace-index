#!/usr/bin/env python3
"""Unit tests for the expected-aces variant comparison (no network required)."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compare_speed_variants as comparison


class ExpectedAceFormulaTests(unittest.TestCase):
    def test_calibrated_average_pair_returns_tour_average_ace_rate(self):
        service_points = 75
        expected = comparison.expected_calibrated(0.08, 0.08, service_points, 0.08)
        self.assertAlmostEqual(expected, 0.08 * service_points, places=10)

    def test_uncalibrated_head_to_head_is_not_tour_scale(self):
        expected = comparison.expected_uncalibrated(0.08, 0.08, 75)
        self.assertAlmostEqual(expected, 0.562851782, places=6)
        self.assertGreater(0.08 * 75, expected)


class CommonEventNormalisationTests(unittest.TestCase):
    def test_variants_are_normalised_on_identical_common_events(self):
        raw = {
            "drop": {"a": 2.0, "b": 4.0, "not_common": 100.0},
            "floor": {"a": 1.0, "b": 3.0, "not_common": 200.0},
            "calibrated": {"a": 4.0, "b": 2.0, "not_common": 300.0},
            "pooled": {"a": 10.0, "b": 10.0, "not_common": 400.0},
        }
        normalised, common = comparison.normalise_on_common_events(raw, {"a", "b", "unrated"})
        self.assertEqual(common, ["a", "b"])
        self.assertEqual(set(normalised), set(comparison.VARIANTS))
        for variant in comparison.VARIANTS:
            self.assertEqual(set(normalised[variant]), {"a", "b"})
            self.assertAlmostEqual(sum(normalised[variant].values()) / len(common), 1.0)

    def test_no_common_events_returns_empty_result(self):
        raw = {variant: {} for variant in comparison.VARIANTS}
        self.assertEqual(comparison.normalise_on_common_events(raw, {"a"}), ({}, []))


class MetricTests(unittest.TestCase):
    def test_reports_rank_correlation_error_and_signed_bias(self):
        result = comparison.metrics([(0.8, 0.9), (1.0, 1.0), (1.2, 1.1)])
        self.assertAlmostEqual(result["r"], 1.0)
        self.assertAlmostEqual(result["rho"], 1.0)
        self.assertAlmostEqual(result["mae"], 0.0666666667)
        self.assertAlmostEqual(result["bias"], 0.0)

    def test_average_ranks_are_used_for_ties(self):
        self.assertEqual(comparison.ranks([1.0, 1.0, 2.0]), [1.5, 1.5, 3.0])


if __name__ == "__main__":
    unittest.main()
