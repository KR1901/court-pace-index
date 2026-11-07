import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compute_speed


def profile(
    player_id,
    name,
    *,
    serve_rate=None,
    concede_rate=0.08,
    clay_aces=0.0,
    clay_points=0.0,
    clay_matches=0,
    hard_grass_aces=0.0,
    hard_grass_points=0.0,
    hard_grass_matches=0,
    serve_matches=0,
):
    return {
        "id": player_id,
        "name": name,
        "serve_rate": serve_rate,
        "concede_rate": concede_rate,
        "serve_matches": serve_matches,
        "clay_matches": clay_matches,
        "hard_grass_matches": hard_grass_matches,
        "return_points": 1000.0,
        "clay_aces": clay_aces,
        "clay_service_points": clay_points,
        "hard_grass_aces": hard_grass_aces,
        "hard_grass_service_points": hard_grass_points,
    }


class BuildLiveProfilesTests(unittest.TestCase):
    def test_duplicate_normalized_names_keep_the_strongest_serve_profile(self):
        older = profile(
            "old-id", "Martin Landaluce",
            serve_rate=0.0733,
            concede_rate=0.0232,
            serve_matches=1,
            hard_grass_aces=2,
            hard_grass_points=63,
            hard_grass_matches=1,
        )
        older.update({
            "serve_rate_season": 2022,
            "serve_rate_source": "prior-season-shrunk",
            "serve_rate_sample_matches": 1,
            "serve_rate_sample_points": 63,
            "concede_rate_season": 2022,
        })
        current = profile(
            "current-id", "Martin Landaluce",
            serve_rate=0.0609,
            concede_rate=0.0699,
            serve_matches=11,
            clay_aces=25,
            clay_points=400,
            clay_matches=6,
            hard_grass_aces=36,
            hard_grass_points=412,
            hard_grass_matches=5,
        )
        current.update({
            "serve_rate_season": 2026,
            "serve_rate_source": "current-season-qualified",
            "serve_rate_sample_matches": 11,
            "serve_rate_sample_points": 812,
            "concede_rate_season": 2026,
        })

        deduped = compute_speed.dedupe_live_profiles([older, current])

        self.assertEqual(1, len(deduped))
        self.assertEqual("current-season-qualified", deduped[0]["serve_rate_source"])
        self.assertEqual(2026, deduped[0]["serve_rate_season"])
        self.assertEqual(0.0609, deduped[0]["serve_rate"])
        self.assertEqual(0.0699, deduped[0]["concede_rate"])
        self.assertEqual(2026, deduped[0]["concede_rate_season"])

    def test_duplicate_names_with_different_accents_are_collapsed(self):
        first = profile("one", "Alex Molcan", serve_rate=0.08)
        second = profile("two", "Alex Molčan", serve_rate=0.07)
        first.update({"serve_rate_season": 2026, "serve_rate_source": "current-season-qualified",
                      "serve_rate_sample_matches": 8, "serve_rate_sample_points": 500,
                      "concede_rate_season": 2026})
        second.update({"serve_rate_season": 2025, "serve_rate_source": "prior-season-qualified",
                       "serve_rate_sample_matches": 5, "serve_rate_sample_points": 300,
                       "concede_rate_season": 2025})

        deduped = compute_speed.dedupe_live_profiles([first, second])

        self.assertEqual(1, len(deduped))
        self.assertEqual(0.08, deduped[0]["serve_rate"])


    def test_preserves_qualified_current_profiles_and_backfills_prior_season_profiles(self):
        current = {
            "p1": profile(
                "p1", "Current Qualified", serve_rate=0.11,
                clay_aces=10, clay_points=100, clay_matches=4, serve_matches=4,
            ),
            "p2": profile(
                "p2", "Prior Season", serve_rate=None,
                clay_aces=6, clay_points=100, clay_matches=2,
            ),
            "p3": profile(
                "p3", "Low Sample", serve_rate=None,
                hard_grass_aces=5, hard_grass_points=70, hard_grass_matches=1,
            ),
            "p4": profile("p4", "No Current Serve Data", serve_rate=None),
        }
        prior = {
            "p2": profile(
                "p2", "Prior Season", serve_rate=0.095,
                clay_aces=20, clay_points=200, clay_matches=5, serve_matches=5,
            ),
            "p5": profile(
                "p5", "Historical Only", serve_rate=0.10,
                clay_aces=12, clay_points=120, clay_matches=4, serve_matches=4,
            ),
            "p6": profile(
                "p6", "Historical Low Sample", serve_rate=None,
                hard_grass_aces=2, hard_grass_points=45, hard_grass_matches=1,
            ),
        }

        def fake_load(year):
            return [{"year": year}]

        def fake_player_rates(rows):
            return prior if rows[0]["year"] == 2025 else {}

        with patch.object(compute_speed, "load", side_effect=fake_load), \
             patch.object(compute_speed, "valid_rows", side_effect=lambda rows: rows), \
             patch.object(compute_speed, "player_rates", side_effect=fake_player_rates):
            live = compute_speed.build_live_profiles(2026, current)

        self.assertEqual(0.11, live["p1"]["serve_rate"])
        self.assertEqual("current-season-qualified", live["p1"]["serve_rate_source"])
        self.assertEqual(2026, live["p1"]["serve_rate_season"])

        self.assertEqual(0.095, live["p2"]["serve_rate"])
        self.assertEqual("prior-season-qualified", live["p2"]["serve_rate_source"])
        self.assertEqual(2025, live["p2"]["serve_rate_season"])

        self.assertIsNotNone(live["p3"]["serve_rate"])
        self.assertGreaterEqual(live["p3"]["serve_rate"], 0.0)
        self.assertLessEqual(live["p3"]["serve_rate"], 1.0)
        self.assertEqual("current-season-shrunk", live["p3"]["serve_rate_source"])
        self.assertEqual(2026, live["p3"]["serve_rate_season"])
        self.assertEqual(300.0, live["p3"]["serve_rate_prior_points"])

        self.assertEqual(0.10, live["p5"]["serve_rate"])
        self.assertEqual("prior-season-qualified", live["p5"]["serve_rate_source"])
        self.assertEqual(2025, live["p5"]["serve_rate_season"])

        self.assertIsNotNone(live["p6"]["serve_rate"])
        self.assertEqual("prior-season-shrunk", live["p6"]["serve_rate_source"])
        self.assertEqual(2025, live["p6"]["serve_rate_season"])

        # Never manufacture a rate if the archive contains no serve-side sample at all.
        self.assertIsNone(live["p4"]["serve_rate"])
        self.assertIsNone(live["p4"]["serve_rate_source"])

    def test_uses_only_the_ten_most_recent_prior_seasons(self):
        current = {
            "p1": profile("p1", "Current", serve_rate=None, clay_aces=1, clay_points=20, clay_matches=1),
        }
        visited = []

        def fake_load(year):
            visited.append(year)
            return [{"year": year}]

        def fake_player_rates(rows):
            return {}

        with patch.object(compute_speed, "load", side_effect=fake_load), \
             patch.object(compute_speed, "valid_rows", side_effect=lambda rows: rows), \
             patch.object(compute_speed, "player_rates", side_effect=fake_player_rates):
            compute_speed.build_live_profiles(2026, current)

        self.assertEqual(list(range(2025, 2015, -1)), visited)


class ExpectedAceCalibrationTests(unittest.TestCase):
    def test_average_server_and_returner_expect_tour_average_ace_rate(self):
        expected = compute_speed.expected_calibrated_aces(
            serve_rate=0.08,
            concede_rate=0.08,
            tour_rate=0.08,
            service_points=75,
        )
        self.assertAlmostEqual(6.0, expected, places=12)

    def test_calibration_accounts_for_server_returner_and_tour_tendencies(self):
        average = compute_speed.expected_calibrated_aces(0.08, 0.08, 0.08, 75)
        stronger_server = compute_speed.expected_calibrated_aces(0.15, 0.08, 0.08, 75)
        stronger_returner = compute_speed.expected_calibrated_aces(0.08, 0.15, 0.08, 75)
        faster_tour = compute_speed.expected_calibrated_aces(0.08, 0.08, 0.12, 75)

        self.assertGreater(stronger_server, average)
        self.assertGreater(stronger_returner, average)
        self.assertLess(faster_tour, average)

    def test_invalid_endpoint_rates_are_not_calibrated(self):
        self.assertEqual(0.0, compute_speed.expected_calibrated_aces(0.0, 0.08, 0.08, 75))
        self.assertEqual(0.0, compute_speed.expected_calibrated_aces(0.08, 0.08, 1.0, 75))


class EventRawScoresCoverageTests(unittest.TestCase):
    def test_includes_valid_positive_expected_aces_below_the_old_half_ace_floor(self):
        # Ten matches produce twenty server sides. Against an 8% tour baseline, each
        # side expects far fewer than half an ace, but the expected value is positive.
        rows = [
            {
                "tourney_name": "Paris Masters",
                "winner_id": "p1",
                "loser_id": "p2",
                "w_svpt": 80,
                "l_svpt": 82,
                "w_ace": 1,
                "l_ace": 1,
                "surface": "Hard",
            }
            for _ in range(10)
        ]
        profiles = {
            "p1": {"serve_rate": 0.01, "concede_rate": 0.01},
            "p2": {"serve_rate": 0.01, "concede_rate": 0.01},
        }

        raw, _, _, matches = compute_speed.event_raw_scores(rows, profiles, tour_rate=0.08)

        expected = compute_speed.expected_calibrated_aces(0.01, 0.01, 0.08, 80)
        self.assertGreater(expected, 0.0)
        self.assertLess(expected, 0.5)
        self.assertIn("paris", raw)
        self.assertEqual(10, matches["paris"])
        self.assertAlmostEqual(1.0 / compute_speed.EXPECTED_ACES_FLOOR, raw["paris"], places=9)


if __name__ == "__main__":
    unittest.main()
