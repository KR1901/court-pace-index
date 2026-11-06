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


if __name__ == "__main__":
    unittest.main()
