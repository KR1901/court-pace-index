import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import nextgen_sensitivity_study as study


class NextGenSensitivityStudyTests(unittest.TestCase):
    def test_target_only_event_never_enters_standard_anchor(self):
        raw = {"standard_a": 1.0, "standard_b": 3.0, "nextgen": 100.0}
        sides = {"standard_a": 30, "standard_b": 40, "nextgen": 30}
        anchor, count = study.standard_anchor(raw, sides, minimum_sides=20)

        self.assertEqual(2, count)
        self.assertAlmostEqual(2.0, anchor)

    def test_anchor_fallback_still_excludes_next_gen(self):
        raw = {"standard_small": 0.8, "nextgen": 50.0}
        sides = {"standard_small": 8, "nextgen": 30}
        anchor, count = study.standard_anchor(raw, sides, minimum_sides=20)

        self.assertEqual(1, count)
        self.assertAlmostEqual(0.8, anchor)

    def test_side_weighted_anchor_uses_only_standard_events(self):
        raw = {"standard_a": 1.0, "standard_b": 2.0, "nextgen": 40.0}
        sides = {"standard_a": 10, "standard_b": 30, "nextgen": 100}
        anchor, count = study.standard_anchor(
            raw, sides, minimum_sides=1, weighting="side"
        )

        self.assertEqual(2, count)
        self.assertAlmostEqual(1.75, anchor)

    def test_sensitivity_grid_contains_one_factor_changes_and_baseline(self):
        scenarios = study.sensitivity_configurations()
        self.assertEqual(15, len(scenarios))
        self.assertEqual("production_baseline", scenarios[0]["name"])
        for scenario in scenarios[1:]:
            self.assertEqual(1, len(scenario["overrides"]))

    def test_robust_match_aggregates_keep_both_sides_inside_match_clusters(self):
        summary = study.aggregate_sensitivity([[1.0, 3.0], [2.0, 2.0], [5.0, 7.0]])

        self.assertEqual(3, summary["match_count"])
        self.assertEqual(6, summary["side_count"])
        self.assertAlmostEqual(20.0 / 6.0, summary["production_side_weighted_mean"])
        self.assertAlmostEqual(10.0 / 3.0, summary["equal_match_mean"])
        self.assertAlmostEqual(2.0, summary["median_match_mean"])


if __name__ == "__main__":
    unittest.main()
