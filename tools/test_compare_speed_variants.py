#!/usr/bin/env python3
"""Unit tests for the expected-aces variant comparison (no network required)."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compare_speed_variants as comparison


class ArchiveRangeTests(unittest.TestCase):
    def test_comparison_starts_with_first_available_match_stats_season(self):
        self.assertEqual(comparison.FIRST_ARCHIVE_SEASON, 1991)

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
            "surface_returner": {"a": 3.0, "b": 3.0, "not_common": 350.0},
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


class SeasonStratifiedBootstrapTests(unittest.TestCase):
    def test_resampling_preserves_each_seasons_event_count(self):
        keys = [
            (2012, "a"), (2012, "b"), (2012, "c"),
            (2013, "d"), (2013, "e"),
        ]
        draws = comparison.season_stratified_draws(keys, iterations=20, seed=7)
        self.assertEqual(len(draws), 20)
        for draw in draws:
            self.assertEqual(len(draw), len(keys))
            self.assertEqual(sum(index in (0, 1, 2) for index in draw), 3)
            self.assertEqual(sum(index in (3, 4) for index in draw), 2)

    def test_stratified_bootstrap_is_reproducible(self):
        keys = [(2012, "a"), (2012, "b"), (2013, "c")]
        self.assertEqual(
            comparison.season_stratified_draws(keys, 10, 99),
            comparison.season_stratified_draws(keys, 10, 99),
        )


class MetricTests(unittest.TestCase):
    def test_reports_rank_correlation_error_and_signed_bias(self):
        result = comparison.metrics([(0.8, 0.9), (1.0, 1.0), (1.2, 1.1)])
        self.assertAlmostEqual(result["r"], 1.0)
        self.assertAlmostEqual(result["rho"], 1.0)
        self.assertAlmostEqual(result["mae"], 0.0666666667)
        self.assertAlmostEqual(result["bias"], 0.0)

    def test_average_ranks_are_used_for_ties(self):
        self.assertEqual(comparison.ranks([1.0, 1.0, 2.0]), [1.5, 1.5, 3.0])



class ReferenceNormalisationTests(unittest.TestCase):
    def test_published_reference_is_recentered_on_common_events(self):
        published = {"a": 0.8, "b": 1.2, "not_common": 4.0}
        normalised = comparison.normalise_reference_on_common_events(published, ["a", "b"])
        self.assertEqual(set(normalised), {"a", "b"})
        self.assertAlmostEqual(sum(normalised.values()) / 2, 1.0)
        self.assertAlmostEqual(normalised["a"], 0.8)
        self.assertAlmostEqual(normalised["b"], 1.2)

    def test_empty_common_reference_returns_empty(self):
        self.assertEqual(comparison.normalise_reference_on_common_events({"a": 1.0}, []), {})


class TennisAbstractReferenceParsingTests(unittest.TestCase):
    def test_parses_annual_table_rows_with_nested_tournament_links(self):
        html = """
        <table>
          <tr><th>Date</th><th>Tournament</th><th>Surface</th><th>Ace%</th><th>Surface Speed</th></tr>
          <tr><td>20-Feb-2012</td><td><a href="/event">Marseille</a></td>
              <td>Hard</td><td>14.9%</td><td>1.57</td></tr>
          <tr><td>27-May-2012</td><td>Roland Garros</td>
              <td>Clay</td><td>7.1%</td><td>0.80</td></tr>
        </table>
        """
        result = comparison.parse_tennis_abstract_page(html)
        self.assertEqual(result["marseille"], 1.57)
        self.assertEqual(result["rolandgarros"], 0.80)

    def test_event_name_aliases_collapse_common_source_variants(self):
        self.assertEqual(comparison.tournament_key("US Open"), comparison.tournament_key("Us Open"))
        self.assertEqual(comparison.tournament_key("Rio"), comparison.tournament_key("Rio de Janeiro"))
        self.assertEqual(
            comparison.tournament_key("ATP Finals"),
            comparison.tournament_key("Tour Finals"),
        )

    def test_rejects_non_table_and_out_of_range_rows(self):
        html = """
        <table>
          <tr><td>Date</td><td>Tournament</td><td>Surface</td><td>Ace%</td><td>Surface Speed</td></tr>
          <tr><td>01-Jan-2012</td><td>Broken Event</td><td>Hard</td><td>10%</td><td>not-a-number</td></tr>
          <tr><td>02-Jan-2012</td><td>Bad Event</td><td>Hard</td><td>10%</td><td>8.0</td></tr>
        </table>
        """
        self.assertEqual(comparison.parse_tennis_abstract_page(html), {})



class PlayerRateEligibilityTests(unittest.TestCase):
    def test_qualified_surface_is_not_discarded_by_a_small_other_surface_sample(self):
        rows = []
        index = 0
        for surface, count, aces in (("Clay", 3, 10), ("Hard", 2, 30)):
            for _ in range(count):
                index += 1
                rows.append({
                    "surface": surface,
                    "winner_id": "player",
                    "loser_id": f"opponent-{index}",
                    "w_svpt": 100,
                    "l_svpt": 100,
                    "w_ace": aces,
                    "l_ace": 8,
                })

        serve, _concede, _surface_concede, _tour = comparison.player_rates(rows)

        self.assertAlmostEqual(0.10, serve["player"])



class SurfaceReturnerProfileTests(unittest.TestCase):
    def test_surface_weighted_returner_rate_matches_server_profile_construction(self):
        rows = []
        for surface, rate in (("Clay", 0.10), ("Hard", 0.20)):
            for index in range(3):
                rows.append({
                    "surface": surface,
                    "winner_id": "returner",
                    "loser_id": f"{surface}-{index}",
                    "w_svpt": 100,
                    "l_svpt": 100,
                    "w_ace": 8,
                    "l_ace": int(rate * 100),
                })

        _serve, pooled, surface_concede, _tour = comparison.player_rates(rows)

        self.assertAlmostEqual(0.15, pooled["returner"])
        self.assertAlmostEqual((0.10 / 3) + (0.20 * 2 / 3), surface_concede["returner"])


class SurfaceStratifiedMetricsTests(unittest.TestCase):
    def test_reports_reference_error_separately_by_surface(self):
        pairs = [(1.1, 1.0), (0.9, 1.0), (1.2, 1.1), (0.8, 1.0)]
        surfaces = ["Clay", "Clay", "Hard", "Hard"]

        result = comparison.surface_metrics(pairs, surfaces)

        self.assertEqual(2, result["Clay"]["n"])
        self.assertAlmostEqual(0.0, result["Clay"]["bias"])
        self.assertAlmostEqual(0.10, result["Clay"]["mae"])
        self.assertEqual(2, result["Hard"]["n"])
        self.assertAlmostEqual(-0.05, result["Hard"]["bias"])
        self.assertAlmostEqual(0.15, result["Hard"]["mae"])

    def test_rejects_mismatched_pair_and_surface_counts(self):
        with self.assertRaises(ValueError):
            comparison.surface_metrics([(1.0, 1.0)], ["Clay", "Hard"])


if __name__ == "__main__":
    unittest.main()
