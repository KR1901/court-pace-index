import unittest

from build_player_stats import (
    add_player_match,
    height_band_id,
    match_buckets,
    match_has_result,
    match_is_retirement,
    normalize_name,
    service_return_contribution,
)


class PlayerStatsFormulaTest(unittest.TestCase):
    def test_hold_and_break_components_use_game_denominators(self):
        row = {
            "w_SvGms": "10",
            "w_bpFaced": "4",
            "w_bpSaved": "3",
            "l_SvGms": "9",
            "l_bpFaced": "3",
            "l_bpSaved": "2",
        }
        # Winner: 9/10 holds and converts 1/9 return games.
        # Loser: 8/9 holds and converts 1/10 return games.
        self.assertEqual(service_return_contribution(row), (10, 9, 9, 8))

    def test_missing_or_inconsistent_stat_line_is_not_estimated(self):
        missing = {"w_SvGms": "10"}
        impossible = {
            "w_SvGms": "2",
            "w_bpFaced": "3",
            "w_bpSaved": "0",
            "l_SvGms": "8",
            "l_bpFaced": "1",
            "l_bpSaved": "0",
        }
        self.assertIsNone(service_return_contribution(missing))
        self.assertIsNone(service_return_contribution(impossible))

    def test_breakpoint_saves_cannot_exceed_faced(self):
        row = {
            "w_SvGms": "10",
            "w_bpFaced": "2",
            "w_bpSaved": "3",
            "l_SvGms": "9",
            "l_bpFaced": "3",
            "l_bpSaved": "1",
        }
        self.assertIsNone(service_return_contribution(row))

    def test_walkovers_and_no_play_defaults_are_excluded_but_retirements_count_as_results(self):
        self.assertFalse(match_has_result("W/O", "A", "B"))
        self.assertFalse(match_has_result("DEF", "A", "B"))
        self.assertFalse(match_has_result("ABN", "A", "B"))
        self.assertTrue(match_has_result("6-4 2-1 RET", "A", "B"))
        self.assertTrue(match_is_retirement("6-4 2-1 RET"))
        self.assertFalse(match_is_retirement("6-4 6-3"))

    def test_match_result_requires_two_distinct_player_ids(self):
        self.assertFalse(match_has_result("6-4 6-3", "A", "A"))
        self.assertFalse(match_has_result("6-4 6-3", "", "B"))

    def test_calendar_surface_buckets_keep_indoor_outdoor_distinct(self):
        self.assertEqual(match_buckets("Hard", "I"), ("all", "hardAll", "hardIndoor"))
        self.assertEqual(match_buckets("Hard", "O"), ("all", "hardAll", "hardOutdoor"))
        self.assertEqual(match_buckets("Hard", ""), ("all", "hardAll", "hardUnknown"))
        self.assertEqual(match_buckets("Clay", "O"), ("all", "clay"))
        self.assertEqual(match_buckets("Grass", "I"), ("all", "grass"))
        self.assertEqual(match_buckets("Carpet", "I"), ("all", "carpet"))

    def test_height_bands_are_exhaustive_at_cut_points(self):
        cases = {
            182: "under183",
            183: "183to190",
            190: "183to190",
            191: "191to194",
            194: "191to194",
            195: "195plus",
            211: "195plus",
        }
        for height, expected in cases.items():
            with self.subTest(height=height):
                self.assertEqual(height_band_id(height), expected)
        self.assertIsNone(height_band_id(None))
        self.assertIsNone(height_band_id(139))
        self.assertIsNone(height_band_id(221))

    def test_annual_aggregate_tracks_results_and_game_denominators(self):
        aggregate = {}
        add_player_match(
            aggregate, "P1", 2026, ("all", "hardAll", "hardIndoor"),
            True, 10, 9, 9, 1, True,
        )
        add_player_match(
            aggregate, "P2", 2026, ("all", "hardAll", "hardIndoor"),
            False, 9, 8, 10, 1, True,
        )
        stats = aggregate["P1"][2026]["hardIndoor"]
        self.assertEqual(stats["mp"], 1)
        self.assertEqual(stats["w"], 1)
        self.assertEqual(stats["sp"], 10)
        self.assertEqual(stats["sw"], 9)
        self.assertEqual(stats["rp"], 9)
        self.assertEqual(stats["rw"], 1)
        self.assertEqual(stats["sm"], 1)
        self.assertNotIn(2025, aggregate["P1"])

    def test_name_matching_ignores_accents_and_punctuation(self):
        self.assertEqual(normalize_name("João Fonseca"), "joaofonseca")
        self.assertEqual(normalize_name("Botic van de Zandschulp"), "boticvandezandschulp")


if __name__ == "__main__":
    unittest.main()
